package marumasa.marumasa_sign.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.media.MediaPlayer;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignManager;
import marumasa.marumasa_sign.core.sign.TextureEntry;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 看板の読み込みの流れ (ダウンロード、残しておく仕組み、読み込みの順番待ち、テクスチャ、メモリの上限、動画の再生) で
 * 見つけて直した不具合が、もう起きないことを確かめる。Minecraft は起動せず、手元に HTTP サーバーを立てて確かめる。
 * 1 つでも違えば終了コード 1 で終わる。
 */
public final class RuntimeRegressionCheck {

    private static int failures;
    private static TestPlatform platform;
    private static File cacheDirectory;

    public static void main(String[] args) throws Exception {
        // 手元に立てたサーバーから読む
        System.setProperty("marumasign.allowLocalUrls", "true");
        final File game = Files.createTempDirectory("marumasign-runtime-check").toFile();
        platform = new TestPlatform(game);
        MarumaSign.init(platform);
        MarumaSign.config().setVolume(0);
        cacheDirectory = new File(game, "marumasign/cache");
        final long start = System.nanoTime();
        try {
            checkTruncatedDownload();
            checkLyingContentLength();
            checkCacheVanishesDuringRevalidation();
            checkPartialCacheFiles();
            checkCacheUnderConcurrentUse();
            checkCancelledRequestsAreNotLoaded();
            checkTexturesReleasedWhenRegisterFails();
            checkLongAnimationDelays();
            checkOneFailedReleaseKeepsReleasingOthers();
            if (Ffmpeg.isAvailable()) {
                checkStopWhileStarting();
                checkClosedVideoDoesNotRestart();
                checkVideoWithinMemoryLimit();
            } else {
                System.out.println("skip: FFmpeg was not found, so the video checks were not run");
            }
        } finally {
            MarumaSign.signs().clearCache();
            delete(game);
        }
        System.out.println("(" + (System.nanoTime() - start) / 1000000000L + " s)");
        System.out.println(failures == 0 ? "All runtime regression checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    // ---------- ダウンロードと、残しておく仕組み (Http, DownloadCache) ----------

    /**
     * 知らされた大きさ (Content-Length) より前に接続が切れたら、失敗にする。
     * 以前は途中までのものを全体として返し、ETag があれば残しておいて、次からは「変わっていない」と言われて使い続けていた。
     */
    private static void checkTruncatedDownload() throws Exception {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket socket = server.accept();
                        try {
                            requests.add(readRequestHead(socket.getInputStream()));
                            final OutputStream output = socket.getOutputStream();
                            output.write(("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nETag: \"t1\"\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            output.write(new byte[100]);
                            output.flush();
                        } finally {
                            socket.close();
                        }
                    } catch (IOException e) {
                        // 閉じた
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        final String url = "http://127.0.0.1:" + server.getLocalPort() + "/truncated.png";
        try {
            boolean failed = false;
            try {
                final byte[] content = Http.download(url);
                System.out.println("  truncated download returned " + content.length + " bytes");
            } catch (IOException e) {
                failed = true;
            }
            check("a download cut short of its Content-Length fails", failed);
            try {
                Http.download(url);
            } catch (IOException e) {
                // 失敗してよい
            }
            check("a download cut short is not kept in the cache",
                    requests.size() == 2 && !requests.get(1).toLowerCase(java.util.Locale.ROOT).contains("if-none-match"));
        } finally {
            server.close();
        }
    }

    /**
     * Content-Length だけを大きく書いて、ほとんど送らないサーバーに、その大きさのメモリを確保させられない。
     * 以前は書かれた大きさ (スプラットなら 200 MB まで) の配列を先に確保していたので、そうしたサーバーを指す看板を
     * いくつも並べるだけで、ゲームのメモリを使い切らせられた。
     */
    private static void checkLyingContentLength() throws Exception {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket socket = server.accept();
                        try {
                            readRequestHead(socket.getInputStream());
                            final OutputStream output = socket.getOutputStream();
                            output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + (200 * 1024 * 1024) + "\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            output.write(new byte[1000]);
                            output.flush();
                        } finally {
                            socket.close();
                        }
                    } catch (IOException e) {
                        // 閉じた
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        final String url = "http://127.0.0.1:" + server.getLocalPort() + "/huge.splat";
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        final long id = Thread.currentThread().getId();
        try {
            // 1 回目は、クラスの読み込みなどで確保するものを済ませておく
            for (int round = 0; round < 2; round++) {
                final long before = threads.getThreadAllocatedBytes(id);
                boolean failed = false;
                try {
                    Http.download(url, new Http.ContentCheck() {
                        @Override
                        public int maxBytes(byte[] head, int length, String name) {
                            return 200 * 1024 * 1024;
                        }
                    });
                } catch (IOException e) {
                    failed = true;
                }
                final long allocated = threads.getThreadAllocatedBytes(id) - before;
                if (round == 0) continue;
                check("a download that lies about its Content-Length fails", failed);
                check("a lying Content-Length does not make the download allocate its size (" + allocated / 1024 + " KB)",
                        allocated < 32L * 1024 * 1024);
            }
        } finally {
            server.close();
        }
    }

    // 要求の先頭を空行まで読む
    private static String readRequestHead(InputStream input) throws IOException {
        final StringBuilder head = new StringBuilder();
        int c;
        while ((c = input.read()) >= 0) {
            head.append((char) c);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        return head.toString();
    }

    /**
     * 「変わっていない」(304) と答えられたが、問い合わせている間に残しておいたファイルが消された (ほかのダウンロードが
     * 上限を超えて消したなど) か書き換えられたときは、失敗にせず本体を受け取り直す。
     */
    private static void checkCacheVanishesDuringRevalidation() throws Exception {
        final String[] action = {null};
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final String condition = exchange.getRequestHeaders().getFirst("If-None-Match");
                exchange.getResponseHeaders().set("ETag", "\"r1\"");
                if ("\"r1\"".equals(condition)) {
                    // 問い合わせの間に、ほかのスレッドが残しておいたものを消した・書き換えた
                    final File[] files = cacheDirectory.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (!file.getName().endsWith(".data")) continue;
                            if ("delete".equals(action[0])) file.delete();
                            if ("rewrite".equals(action[0])) Files.write(file.toPath(), new byte[3]);
                        }
                    }
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                respond(exchange, "revalidated body".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            for (String kind : new String[]{"delete", "rewrite"}) {
                final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + kind + ".png";
                action[0] = null;
                Http.download(url);
                action[0] = kind;
                String result;
                try {
                    result = new String(Http.download(url), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    result = e.toString();
                }
                check("a kept file that was " + (kind.equals("delete") ? "deleted" : "changed")
                        + " during a 304 is downloaded again (" + result + ")", result.equals("revalidated body"));
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * 残しておくのに失敗したとき (ディスクがいっぱいなど) の書きかけのファイルと、ゲームが途中で止まって残った
     * 古い書きかけのファイルは消す。合計の大きさに数えないので、残すとたまり続ける。
     */
    private static void checkPartialCacheFiles() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("ETag", "\"p1\"");
                respond(exchange, new byte[4096]);
            }
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            // 本体のファイルを置き換えられないようにして、残すのを失敗させる
            final String failing = base + "cannot-store.png";
            cacheDirectory.mkdirs();
            final File blocker = new File(cacheDirectory, key(failing) + ".data");
            blocker.mkdirs();
            Files.write(new File(blocker, "inside").toPath(), new byte[1]);
            Http.download(failing);
            check("a failed store leaves no partial file", !new File(cacheDirectory, key(failing) + ".tmp").exists());
            delete(blocker);

            final File stale = new File(cacheDirectory, "0123456789abcdef.tmp");
            Files.write(stale.toPath(), new byte[1024]);
            stale.setLastModified(System.currentTimeMillis() - 3600 * 1000L);
            Http.download(base + "after-crash.png");
            check("a partial file left by a crash is removed", !stale.exists());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 多くのスレッドが同じ URL と違う URL を同時に読み、残しておく上限が小さくて消し合い、サーバーの内容も変わっていく。
     * どのダウンロードも失敗せず、その URL のどれかの版の内容をそのまま返し、残しておく合計も上限を超えないこと。
     */
    private static void checkCacheUnderConcurrentUse() throws Exception {
        final AtomicInteger version = new AtomicInteger(1);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final String path = exchange.getRequestURI().getPath();
                final int current = version.get();
                final String tag = "\"" + current + "\"";
                exchange.getResponseHeaders().set("ETag", tag);
                if (tag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                respond(exchange, body(path, current));
            }
        });
        server.start();
        final Field maxTotal = Class.forName("marumasa.marumasa_sign.core.net.DownloadCache").getDeclaredField("maxTotal");
        maxTotal.setAccessible(true);
        final long savedMax = maxTotal.getLong(null);
        // 3 つ分ほどしか残せないようにして、ほかのスレッドが使っているものを消させる
        final long limit = 3 * 3000;
        maxTotal.setLong(null, limit);
        final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<String>();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            final int threads = 8;
            final CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final long seed = t;
                final Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Random random = new Random(seed);
                        try {
                            for (int i = 0; i < 150; i++) {
                                final String path = "/c" + random.nextInt(8) + ".png";
                                if (random.nextInt(40) == 0) version.incrementAndGet();
                                try {
                                    final byte[] content = Http.download(base + path.substring(1));
                                    if (!isSomeVersion(path, content, version.get())) problems.add("wrong content for " + path);
                                } catch (IOException e) {
                                    problems.add(path + ": " + e);
                                }
                            }
                        } finally {
                            done.countDown();
                        }
                    }
                });
                thread.start();
            }
            done.await(120, TimeUnit.SECONDS);
            long total = 0;
            final File[] files = cacheDirectory.listFiles();
            for (File file : files == null ? new File[0] : files) {
                if (file.getName().endsWith(".data")) total += file.length();
            }
            System.out.println("  concurrent cache use: " + problems.size() + " problems, " + total + " bytes kept");
            for (String problem : problems) {
                System.out.println("    " + problem);
                break;
            }
            check("concurrent downloads with a busy cache never fail or mix contents", problems.isEmpty() && done.getCount() == 0);
            check("the cache stays under its limit with concurrent downloads", total <= limit);
        } finally {
            maxTotal.setLong(null, savedMax);
            server.stop(0);
        }
    }

    // URL と版ごとに違う内容 (長さも違う)
    private static byte[] body(String path, int version) {
        final byte[] content = new byte[2000 + version % 50 * 17 + path.length()];
        final byte[] label = (path + "#" + version).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < content.length; i++) content[i] = label[i % label.length];
        return content;
    }

    private static boolean isSomeVersion(String path, byte[] content, int latest) {
        for (int v = 1; v <= latest; v++) {
            if (Arrays.equals(content, body(path, v))) return true;
        }
        return false;
    }

    // ---------- 読み込みの順番待ちとテクスチャ (ImageLoader, SignManager) ----------

    /**
     * 順番を待っている間に消された看板 (メモリを空けるため、長く見えなかった、キャッシュを消した) は読み込まない。
     * 以前は消された後も順に全部ダウンロードして、見えている看板の読み込みを後回しにしていた。
     */
    private static void checkCancelledRequestsAreNotLoaded() throws Exception {
        final byte[] png = png(8, 8);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1);
        final List<String> requested = Collections.synchronizedList(new ArrayList<String>());
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final String path = exchange.getRequestURI().getPath();
                requested.add(path);
                if (path.startsWith("/slow")) {
                    blocked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(exchange, png);
            }
        });
        server.start();
        final int savedThreads = MarumaSign.config().getMaxThreads();
        MarumaSign.config().setMaxThreads(1);
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            MarumaSign.tick();
            MarumaSign.sign(sign(base + "slow.png"));
            blocked.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) MarumaSign.sign(sign(base + "queued" + i + ".png"));
            // 順番を待っている看板を消す
            MarumaSign.signs().clearCache();
            release.countDown();
            final long until = System.nanoTime() + 30000000000L;
            while (MarumaSign.signs().isBusy() && System.nanoTime() < until) {
                MarumaSign.tick();
                platform.runTasks();
                Thread.sleep(10);
            }
            int queued = 0;
            synchronized (requested) {
                for (String path : requested) {
                    if (path.startsWith("/queued")) queued++;
                }
            }
            check("requests removed while waiting are not downloaded (" + queued + " of 20 were)", queued == 0);
            check("the loader becomes idle", !MarumaSign.signs().isBusy());
        } finally {
            MarumaSign.config().setMaxThreads(savedThreads);
            release.countDown();
            server.stop(0);
        }
    }

    /**
     * アニメーションのテクスチャを作る途中で失敗したら (GPU のメモリが足りないなど)、それまでに作ったフレームのテクスチャも消す。
     * 以前は失敗の印に置き換えるだけで、作ったテクスチャは誰も消さなかった。
     */
    private static void checkTexturesReleasedWhenRegisterFails() throws Exception {
        final byte[] gif = animatedGif(3);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, gif);
            }
        });
        server.start();
        try {
            final String text = sign("http://127.0.0.1:" + server.getAddress().getPort() + "/anim.gif");
            MarumaSign.sign(text);
            final int before = platform.liveCount();
            // 2 つ目のフレームのテクスチャを作るところで失敗させる
            platform.failAfter = 1;
            run(new String[]{text}, 1.0, 60);
            final TextureEntry entry = MarumaSign.sign(text).textureEntry();
            check("the failed animation is shown as an error", entry.state() == TextureEntry.ERROR);
            run(new String[0], 0, 30);
            check("textures made before the failure are released (" + (platform.liveCount() - before) + " left)",
                    platform.liveCount() == before);
            check("no texture is released twice", platform.doubleReleases.get() == 0);
        } finally {
            platform.failAfter = -1;
            MarumaSign.signs().clearCache();
            server.stop(0);
        }
    }

    /**
     * 表示時間の長いコマがたくさんあるアニメーションでも、コマの終わりの時刻があふれない (正で、順に増えていく)。
     * 以前は int で足していたので、APNG の 1 コマ 65535 秒のコマが 33 枚あると負になり、違うコマを表示していた。
     */
    private static void checkLongAnimationDelays() throws Exception {
        final int[][] frames = new int[40][];
        for (int i = 0; i < frames.length; i++) frames[i] = new int[]{0xFF000000 | i * 0x060402};
        final byte[] apng = marumasa.marumasa_sign.core.image.ApngWriter.write(1, 1, frames, 65535, 1, 0);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, apng);
            }
        });
        server.start();
        try {
            final String text = sign("http://127.0.0.1:" + server.getAddress().getPort() + "/long.png");
            MarumaSign.sign(text);
            run(new String[]{text}, 1.0, 60);
            final TextureEntry entry = MarumaSign.sign(text).textureEntry();
            check("the long animation is loaded", entry.state() == TextureEntry.LOADED);
            final Field field = TextureEntry.class.getDeclaredField("frameEnd");
            field.setAccessible(true);
            final int[] frameEnd = (int[]) field.get(entry);
            boolean increasing = frameEnd != null && frameEnd.length == frames.length && frameEnd[0] > 0;
            for (int i = 1; increasing && i < frameEnd.length; i++) increasing = frameEnd[i] > frameEnd[i - 1];
            check("the end times of long frames do not overflow (" + Arrays.toString(frameEnd) + ")", increasing);
        } finally {
            MarumaSign.signs().clearCache();
            server.stop(0);
        }
    }

    /**
     * 後で消すテクスチャのうち 1 つを消すときに例外が出ても、同じ tick に消す残りのテクスチャは消す。
     * 以前は例外で残りが飛ばされ、待ちの一覧からは外されていたので、誰も消さなかった (GPU のメモリが戻らない)。
     */
    private static void checkOneFailedReleaseKeepsReleasingOthers() throws Exception {
        final Object first = platform.createTexture(4, 4, new int[16]);
        final Object failing = platform.createTexture(4, 4, new int[16]);
        final Object last = platform.createTexture(4, 4, new int[16]);
        platform.failOnRelease = failing;
        try {
            MarumaSign.releaseTexture(platform, first);
            MarumaSign.releaseTexture(platform, failing);
            MarumaSign.releaseTexture(platform, last);
            run(new String[0], 0, 30);
            // 数ではなく、この 3 つが消されたかで確かめる (前の試験が手放したテクスチャも、同じ tick に消されることがある)
            check("a texture whose release fails does not keep the others from being released",
                    !platform.isLive(first) && !platform.isLive(failing) && !platform.isLive(last));
        } finally {
            platform.failOnRelease = null;
        }
    }

    // ---------- 動画 (MediaPlayer, MediaSession) ----------

    private static byte[] video;

    private static String videoServer() throws Exception {
        if (video == null) {
            final File file = File.createTempFile("marumasign-runtime", ".mp4");
            try {
                final Process process = new ProcessBuilder(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner",
                        "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=4",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-pix_fmt", "yuv420p", "-shortest", file.getPath()))
                        .redirectErrorStream(true).start();
                process.getInputStream().close();
                process.waitFor();
                video = Files.readAllBytes(file.toPath());
            } finally {
                file.delete();
            }
        }
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, video);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static final List<HttpServer> servers = new ArrayList<HttpServer>();

    /**
     * 再生を始める途中 (FFmpeg を起動する直前) で止められても、FFmpeg と再生のスレッドが残らない。
     * 以前は、止めた後に起動した FFmpeg を止めず、誰も読まない映像の接続に書き込もうとして止まったまま、いつまでも残っていた。
     * (近いものから 8 つを再生する仕組みは、看板の距離が入れ替わるたびに再生を止めるので、よく起きる)
     */
    private static void checkStopWhileStarting() throws Exception {
        final String base = videoServer();
        int leaked = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            final MediaPlayer[] player = new MediaPlayer[1];
            final Thread[] thread = new Thread[1];
            final CountDownLatch ready = new CountDownLatch(1);
            player[0] = new MediaPlayer(base + "stop" + attempt + ".mp4", new MediaPlayer.Listener() {
                @Override
                public void onReady(int width, int height) {
                    // 情報を調べ終えて、FFmpeg を起動する直前に止められた
                    thread[0] = Thread.currentThread();
                    player[0].stop();
                    ready.countDown();
                }

                @Override
                public void onFailed(String reason) {
                    ready.countDown();
                }
            });
            player[0].start();
            ready.await(30, TimeUnit.SECONDS);
            if (thread[0] == null) {
                check("the video could be probed", false);
                return;
            }
            thread[0].join(8000);
            if (thread[0].isAlive()) leaked++;
        }
        check("stopping a video while FFmpeg starts leaves nothing running (" + leaked + " of 3 left running)", leaked == 0);
    }

    /**
     * 片付けた動画を、残っていた看板 (SignImage) から描いても、再生をやり直さない。
     * 以前は片付けた後も「再生してよい」のままで、描かれると誰も止めない FFmpeg を起動していた。
     */
    private static void checkClosedVideoDoesNotRestart() throws Exception {
        final String text = sign(videoServer() + "closed.mp4");
        final long until = System.nanoTime() + 30000000000L;
        while (MarumaSign.sign(text).textureEntry().mediaGain() < 0 && System.nanoTime() < until) {
            run(new String[]{text}, 1.0, 5);
        }
        final SignImage image = MarumaSign.sign(text);
        final TextureEntry entry = image.textureEntry();
        check("the video starts playing", entry.mediaGain() >= 0);
        MarumaSign.signs().clearCache();
        for (int i = 0; i < 5; i++) {
            image.texture(1.0);
            MarumaSign.tick();
            platform.runTasks();
        }
        check("a closed video is not started again when an old sign is drawn", entry.mediaGain() < 0);
        // 直す前は、ここで始まった再生を止める
        for (int i = 0; i < 100; i++) {
            MarumaSign.tick();
            platform.runTasks();
        }
    }

    /**
     * 動画の映像も、看板の中身のメモリの上限 (SignManager.MAX_BYTES) に収める。見えているものだけで上限に達していれば、
     * 動画も読み込めなかった印になる。以前は動画だけは確かめずに足していたので、見えている動画の数だけ上限を超えた。
     */
    private static void checkVideoWithinMemoryLimit() throws Exception {
        final byte[] large = png(LARGE, LARGE);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, large);
            }
        });
        server.start();
        final int savedThreads = MarumaSign.config().getMaxThreads();
        MarumaSign.config().setMaxThreads(2);
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            // 4096 x 4096 の画像 16 枚で、ちょうど上限になる
            final int count = (int) (SignManager.MAX_BYTES / (4L * LARGE * LARGE));
            final String[] texts = new String[count + 1];
            for (int i = 0; i < count; i++) texts[i] = sign(base + "large" + i + ".png");
            final String[] images = Arrays.copyOf(texts, count);
            MarumaSign.sign(images[0]);
            final long placeholders = platform.liveBytes();
            long until = System.nanoTime() + 120000000000L;
            while (System.nanoTime() < until && loadedCount(images) < count) {
                run(images, 1.0, 5);
            }
            check("the images fill the limit (" + loadedCount(images) + " of " + count + " shown)", loadedCount(images) == count);
            texts[count] = sign(videoServer() + "over.mp4");
            until = System.nanoTime() + 30000000000L;
            while (System.nanoTime() < until && MarumaSign.sign(texts[count]).textureEntry().state() == TextureEntry.LOADING) {
                run(texts, 1.0, 5);
            }
            run(texts, 1.0, 20);
            final long used = platform.liveBytes() - placeholders;
            System.out.println("  video over the limit: " + (used >> 20) + " MB of textures, video state "
                    + MarumaSign.sign(texts[count]).textureEntry().state());
            check("a video is refused when the signs in view already fill the memory limit",
                    MarumaSign.sign(texts[count]).textureEntry().state() == TextureEntry.ERROR);
            check("textures stay under the limit with a video", used <= SignManager.MAX_BYTES);
            check("no texture is released twice with a refused video", platform.doubleReleases.get() == 0);
        } finally {
            MarumaSign.config().setMaxThreads(savedThreads);
            MarumaSign.signs().clearCache();
            run(new String[0], 0, 30);
            server.stop(0);
            for (HttpServer video : servers) video.stop(0);
        }
    }

    // 静止画の大きさの上限 (ImageLoader.MAX_STATIC_SIZE)
    private static final int LARGE = 4096;

    private static int loadedCount(String[] texts) {
        int loaded = 0;
        for (String text : texts) {
            if (MarumaSign.sign(text).isLoaded()) loaded++;
        }
        return loaded;
    }

    // ---------- 補助 ----------

    private static String sign(String url) {
        return url + "|0|0|0|1|1|0|0|0";
    }

    /**
     * ゲームと同じように tick と描画を ticks 回くり返す。描画では texts の看板を引いて描く。
     */
    private static void run(String[] texts, double distance, int ticks) throws Exception {
        for (int tick = 0; tick < ticks; tick++) {
            MarumaSign.tick();
            for (int frame = 0; frame < 3; frame++) {
                platform.runTasks();
                for (String text : texts) {
                    final SignImage image = MarumaSign.sign(text);
                    image.texture(distance);
                }
                Thread.sleep(5);
            }
        }
    }

    private static void respond(HttpExchange exchange, byte[] content) throws IOException {
        exchange.sendResponseHeaders(200, content.length);
        final OutputStream output = exchange.getResponseBody();
        try {
            output.write(content);
        } finally {
            output.close();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        final BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x3366CC));
        g.fillRect(0, 0, width, height);
        g.dispose();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", bytes);
        return bytes.toByteArray();
    }

    // frames 枚の絵を持つ GIF
    private static byte[] animatedGif(int frames) throws IOException {
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ImageOutputStream output = ImageIO.createImageOutputStream(bytes);
        try {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                final BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                final Graphics2D g = frame.createGraphics();
                g.setColor(new Color(0x40 * i, 0x80, 0xFF - 0x40 * i));
                g.fillRect(0, 0, 16, 16);
                g.dispose();
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            output.close();
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // DownloadCache が URL から作るファイル名
    private static String key(String url) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
        return hex.toString();
    }

    private static void delete(File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }

    /**
     * Minecraft の代わり。テクスチャは中身を持たず、作って解放されていないものを数える。
     * failAfter が 0 以上なら、その数だけ作った後のテクスチャの作成を 1 回だけ失敗させる。
     */
    private static final class TestPlatform implements Platform {
        private final File gameDirectory;
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
        private final Set<Object> live = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private final Map<Object, Long> sizes = new ConcurrentHashMap<Object, Long>();
        final AtomicInteger doubleReleases = new AtomicInteger();
        volatile int failAfter = -1;
        // 消すときに例外を出すテクスチャ (消したことにはする)
        volatile Object failOnRelease;

        TestPlatform(File gameDirectory) {
            this.gameDirectory = gameDirectory;
        }

        void runTasks() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }

        synchronized boolean isLive(Object texture) {
            return live.contains(texture);
        }

        synchronized int liveCount() {
            return live.size();
        }

        synchronized long liveBytes() {
            long total = 0;
            for (Object texture : live) total += sizes.get(texture);
            return total;
        }

        @Override
        public synchronized Object createTexture(int width, int height, int[] argb) {
            if (failAfter == 0) {
                failAfter = -1;
                throw new IllegalStateException("Test: could not create a texture");
            }
            if (failAfter > 0) failAfter--;
            final Object texture = new Object();
            live.add(texture);
            sizes.put(texture, 4L * width * height);
            return texture;
        }

        @Override
        public void updateTexture(Object texture, int width, int height, int[] argb) {
        }

        @Override
        public synchronized void releaseTexture(Object texture) {
            if (!live.remove(texture)) doubleReleases.incrementAndGet();
            if (texture == failOnRelease) throw new IllegalStateException("Test: could not release a texture");
        }

        @Override
        public String minecraftVersion() {
            return "test";
        }

        @Override
        public String loaderName() {
            return "test";
        }

        @Override
        public File gameDirectory() {
            return gameDirectory;
        }

        @Override
        public String languageCode() {
            return "en_us";
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void showMessage(String message) {
            System.out.println("[chat] " + message);
        }

        @Override
        public boolean isInWorld() {
            return true;
        }

        @Override
        public int maxSignLineLength() {
            return 80;
        }

        @Override
        public void openUi() {
        }

        @Override
        public void closeUi() {
        }

        @Override
        public boolean supportsFileDrop() {
            return false;
        }

        @Override
        public String getClipboard() {
            return "";
        }

        @Override
        public void setClipboard(String text) {
        }

        @Override
        public void selfTestLook(float pitch) {
        }

        @Override
        public void selfTestUseItem() {
        }

        @Override
        public void quit() {
        }
    }
}
