package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.media.Ffmpeg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板の文字列から表示する画像を引くための管理クラス。
 */
public final class SignManager {

    private final Platform platform;
    private final ImageLoader loader;

    // 読み込み済み看板マップ (看板の文字列 -> 画像)
    private final Map<String, SignImage> signs = new ConcurrentHashMap<String, SignImage>();
    // 読み込み済みテクスチャマップ (URL -> テクスチャ)
    private final Map<String, TextureEntry> textures = new ConcurrentHashMap<String, TextureEntry>();
    // 再生中の動画や音声
    private final List<MediaSession> sessions = new ArrayList<MediaSession>();
    private boolean ffmpegNoticeShown;

    // 読み込み中・読み込み失敗のときに表示するテクスチャ
    private Object loadingTexture;
    private Object errorTexture;
    // 音声だけを再生しているときに表示するテクスチャ
    private Object audioTexture;
    private int placeholderWidth = 160;
    private int placeholderHeight = 96;

    public SignManager(Platform platform, ImageLoader loader) {
        this.platform = platform;
        this.loader = loader;
    }

    /**
     * 看板の文字列に対応する画像を返す。MarumaSign の看板でなければ null。
     * メインスレッドから呼ぶこと。
     */
    public SignImage get(String text) {
        if (!SignSpec.looksLikeSign(text)) return null;

        SignImage image = signs.get(text);
        if (image != null) return image;

        final SignSpec spec = SignSpec.parse(text);
        if (spec == null) return null;

        image = new SignImage(spec, texture(spec.url));
        signs.put(text, image);
        return image;
    }

    private TextureEntry texture(final String url) {
        TextureEntry entry = textures.get(url);
        if (entry != null) return entry;

        ensurePlaceholders();
        final TextureEntry created = new TextureEntry(TextureEntry.LOADING, placeholderWidth, placeholderHeight, loadingTexture);
        textures.put(url, created);

        loader.request(url, new ImageLoader.Callback() {
            @Override
            public void onLoaded(final DecodedImage decoded) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        register(url, created, decoded);
                    }
                });
            }

            @Override
            public void onMedia() {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        startMedia(url, created);
                    }
                });
            }

            @Override
            public void onFailed() {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        fail(url, created);
                    }
                });
            }
        });
        return created;
    }

    private void register(String url, TextureEntry entry, DecodedImage decoded) {
        // キャッシュ削除後に届いた結果は捨てる
        if (textures.get(url) != entry) return;
        try {
            final int count = decoded.frames.length;
            final Object[] frames = new Object[count];
            final int[] frameEnd = new int[count];
            int time = 0;
            for (int i = 0; i < count; i++) {
                frames[i] = platform.createTexture(decoded.width, decoded.height, decoded.frames[i]);
                time += Math.max(1, decoded.delays[i]);
                frameEnd[i] = time;
            }
            entry.set(TextureEntry.LOADED, decoded.width, decoded.height, frames, frameEnd, decoded.repetitions, true);
            MarumaSign.LOGGER.info("Load: " + url);
        } catch (RuntimeException | Error e) {
            // スケジュールされたタスクの中の例外は呼び出し元で捨てられるため、ここで記録する
            MarumaSign.LOGGER.error("Failed to register texture: " + url, e);
            fail(url, entry);
        }
    }

    // 画像ではなかった URL を、動画や音声として再生する
    private void startMedia(final String url, final TextureEntry entry) {
        if (textures.get(url) != entry) return;
        if (!Ffmpeg.isAvailable()) {
            // 再生できない理由を、最初の 1 回だけチャットで知らせる
            if (!ffmpegNoticeShown) {
                ffmpegNoticeShown = true;
                MarumaSign.chat("text.ffmpeg_missing");
            }
            fail(url, entry);
            return;
        }
        final MediaSession[] holder = new MediaSession[1];
        final MediaSession session = new MediaSession(platform, url, new MediaSession.Listener() {
            @Override
            public void onReady(int width, int height) {
                if (textures.get(url) != entry) return;
                if (width > 0) {
                    // テクスチャは再生の側が持っているので、ここでは所有しない
                    entry.set(TextureEntry.LOADED, width, height, new Object[]{holder[0].texture()}, new int[]{1}, 0, false);
                } else {
                    // 音声だけの場合は、スピーカーの絵を表示する
                    entry.set(TextureEntry.LOADED, placeholderWidth, placeholderHeight, new Object[]{audioTexture}, new int[]{1}, 0, false);
                }
                MarumaSign.LOGGER.info("Play: " + url);
            }

            @Override
            public void onFailed() {
                fail(url, entry);
            }
        });
        holder[0] = session;
        entry.setMedia(session);
        sessions.add(session);
        session.start();
    }

    private void fail(String url, TextureEntry entry) {
        if (textures.get(url) != entry) return;
        entry.set(TextureEntry.ERROR, placeholderWidth, placeholderHeight, new Object[]{errorTexture}, new int[]{1}, 0, false);
    }

    private void ensurePlaceholders() {
        if (loadingTexture != null) return;
        final DecodedImage loading = ImageLoader.readResource("/marumasa_sign/loading.png");
        final DecodedImage error = ImageLoader.readResource("/marumasa_sign/error.png");
        placeholderWidth = loading.width;
        placeholderHeight = loading.height;
        loadingTexture = platform.createTexture(loading.width, loading.height, loading.frames[0]);
        errorTexture = platform.createTexture(error.width, error.height, error.frames[0]);
        audioTexture = platform.createTexture(placeholderWidth, placeholderHeight, speakerIcon(placeholderWidth, placeholderHeight));
    }

    // スピーカーの絵を描く
    private static int[] speakerIcon(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x3C3C3C));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0x6ABE30));
            final int cx = width / 2 - height / 6;
            final int cy = height / 2;
            final int unit = height / 8;
            // 本体
            g.fillRect(cx - unit * 2, cy - unit, unit, unit * 2);
            g.fillPolygon(new int[]{cx - unit, cx + unit, cx + unit, cx - unit},
                    new int[]{cy - unit, cy - unit * 2 - unit / 2, cy + unit * 2 + unit / 2, cy + unit}, 4);
            // 音の波
            g.setStroke(new BasicStroke(Math.max(2f, unit / 2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i <= 3; i++) {
                final int radius = unit * (i + 1);
                g.drawArc(cx + unit - radius / 2, cy - radius, radius * 2, radius * 2, -40, 80);
            }
        } finally {
            g.dispose();
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    // tick ごとに呼ぶ
    public void tick() {
        for (int i = 0; i < sessions.size(); i++) {
            sessions.get(i).tick();
        }
    }

    // 読み込み待ち・読み込み中の画像があるかどうか
    public boolean isBusy() {
        return loader.isBusy();
    }

    // 登録されているテクスチャをすべて削除する
    public void clearCache() {
        for (MediaSession session : sessions) {
            session.close();
        }
        sessions.clear();
        for (TextureEntry entry : textures.values()) {
            for (Object frame : entry.ownedTextures()) {
                platform.releaseTexture(frame);
            }
        }
        signs.clear();
        textures.clear();
    }
}
