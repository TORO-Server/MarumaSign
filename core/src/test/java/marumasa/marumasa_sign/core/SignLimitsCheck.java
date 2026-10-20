package marumasa.marumasa_sign.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignManager;
import marumasa.marumasa_sign.core.sign.TextureEntry;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 違う URL の看板をたくさん並べられても、メモリを使い切らないことと、動画をたくさん並べられても
 * 同時に再生するのは近いものから MarumaSign の上限の数だけであることを、Minecraft を起動せずに確かめる。
 * 1 つでも違えば終了コード 1 で終わる。
 */
public final class SignLimitsCheck {

    private static int failures;
    // 画像の数 (どれも 2048 x 2048。全部を持つと、上限を超える)
    private static final int IMAGES = 80;
    private static final int VIDEOS = SignManager.MAX_PLAYING + 4;
    // 上限の外にある、読み込み中などの印のテクスチャの分
    private static final long PLACEHOLDERS = 1024 * 1024;

    private static byte[] image;
    private static byte[] video;

    public static void main(String[] args) throws Exception {
        // 手元に立てたサーバーから読む
        System.setProperty("marumasign.allowLocalUrls", "true");
        image = png();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final byte[] content = exchange.getRequestURI().getPath().endsWith(".mp4") ? video : image;
                exchange.sendResponseHeaders(200, content.length);
                final OutputStream output = exchange.getResponseBody();
                try {
                    output.write(content);
                } finally {
                    output.close();
                }
            }
        });
        server.start();
        final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        final FakePlatform platform = new FakePlatform("en_us");
        MarumaSign.init(platform);
        MarumaSign.config().setVolume(0);
        try {
            checkWalkingPast(platform, base);
            MarumaSign.signs().clearCache();
            run(platform, new String[0], 0, 40);
            checkAllAtOnce(platform, base);
            MarumaSign.signs().clearCache();
            run(platform, new String[0], 0, 40);
            if (Ffmpeg.isAvailable()) {
                checkVideos(platform, base);
            } else {
                System.out.println("skip: FFmpeg was not found, so the number of videos playing at once was not checked");
            }
        } finally {
            MarumaSign.signs().clearCache();
            server.stop(0);
        }
        System.out.println(failures == 0 ? "All sign limit checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    // 看板の前を通り過ぎていく: 10 枚ずつ見て、次へ進む。見えなくなったものから消え、上限を超えない
    private static void checkWalkingPast(FakePlatform platform, String base) throws Exception {
        long most = 0;
        int loaded = 0;
        for (int group = 0; group < IMAGES / 10; group++) {
            final String[] texts = new String[10];
            for (int i = 0; i < 10; i++) texts[i] = base + "walk" + (group * 10 + i) + ".png|0|0|0|1|1|0|0|0";
            run(platform, texts, 1.0, 140);
            for (String text : texts) {
                if (MarumaSign.sign(text).isLoaded()) loaded++;
            }
            most = Math.max(most, platform.liveBytes);
        }
        run(platform, new String[0], 0, 40);
        System.out.println("  walking past: at most " + (most >> 20) + " MB of textures, " + loaded + " of " + IMAGES + " shown");
        check("textures stay under the limit while walking past signs", most <= SignManager.MAX_BYTES + PLACEHOLDERS);
        check("every sign in view is shown while walking past", loaded == IMAGES);
    }

    // すべてが同時に見えている: 上限を超える分は表示されない (読み込めなかった印になる) が、上限は超えない
    private static void checkAllAtOnce(FakePlatform platform, String base) throws Exception {
        final String[] texts = new String[IMAGES];
        for (int i = 0; i < IMAGES; i++) texts[i] = base + "all" + i + ".png|0|0|0|1|1|0|0|0";
        run(platform, texts, 1.0, 400);
        int shown = 0, refused = 0;
        for (String text : texts) {
            final TextureEntry entry = MarumaSign.sign(text).textureEntry();
            if (entry.state() == TextureEntry.LOADED) shown++;
            if (entry.state() == TextureEntry.ERROR) refused++;
        }
        System.out.println("  all at once: " + (platform.liveBytes >> 20) + " MB of textures, " + shown + " shown, " + refused + " refused");
        check("textures stay under the limit when every sign is in view", platform.liveBytes <= SignManager.MAX_BYTES + PLACEHOLDERS);
        check("signs are shown up to the limit", shown > 0 && shown + refused == IMAGES && refused > 0);
    }

    // 動画を近い順に並べる: 近いものから上限の数だけが再生される
    private static void checkVideos(FakePlatform platform, String base) throws Exception {
        final File file = File.createTempFile("marumasign-limits", ".mp4");
        try {
            final Process process = new ProcessBuilder(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner", "-loglevel", "error",
                    "-y", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=4", "-pix_fmt", "yuv420p", file.getPath()))
                    .redirectErrorStream(true).start();
            process.getInputStream().close();
            process.waitFor();
            video = Files.readAllBytes(file.toPath());
        } finally {
            file.delete();
        }
        final String[] texts = new String[VIDEOS];
        for (int i = 0; i < VIDEOS; i++) texts[i] = base + "video" + i + ".mp4|0|0|0|1|1|0|0|0";
        // 看板 i までの距離は i + 1 ブロック
        int most = 0;
        final List<Integer> playing = new ArrayList<Integer>();
        for (int step = 0; step < 6; step++) {
            run(platform, texts, -1, 40);
            playing.clear();
            for (int i = 0; i < VIDEOS; i++) {
                if (MarumaSign.sign(texts[i]).textureEntry().mediaGain() >= 0) playing.add(i);
            }
            most = Math.max(most, playing.size());
        }
        System.out.println("  videos: at most " + most + " playing at once, now playing " + playing);
        check("no more than " + SignManager.MAX_PLAYING + " videos play at once", most <= SignManager.MAX_PLAYING);
        boolean nearest = playing.size() == SignManager.MAX_PLAYING;
        for (int i = 0; i < playing.size(); i++) {
            if (playing.get(i) != i) nearest = false;
        }
        check("the nearest videos are the ones playing", nearest);
    }

    /**
     * ゲームと同じように tick と描画を ticks 回くり返す。描画では texts の看板を引いて描く。
     *
     * @param distance 看板までの距離。負なら、看板 i までを i + 1 ブロックとする
     */
    private static void run(FakePlatform platform, String[] texts, double distance, int ticks) throws Exception {
        for (int tick = 0; tick < ticks; tick++) {
            MarumaSign.tick();
            for (int frame = 0; frame < 3; frame++) {
                platform.runTasks();
                for (int i = 0; i < texts.length; i++) {
                    final SignImage image = MarumaSign.sign(texts[i]);
                    image.texture(distance < 0 ? i + 1 : distance);
                }
                Thread.sleep(16);
            }
        }
    }

    private static byte[] png() throws IOException {
        final BufferedImage picture = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x3366CC));
        g.fillRect(0, 0, 2048, 2048);
        g.dispose();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", bytes);
        return bytes.toByteArray();
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }
}
