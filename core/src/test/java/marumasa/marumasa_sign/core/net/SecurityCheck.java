package marumasa.marumasa_sign.core.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import marumasa.marumasa_sign.core.SafeLog;
import marumasa.marumasa_sign.core.gltf.GltfLoader;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.sign.SignSpec;

import javax.imageio.ImageIO;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 看板の URL を使って、見た人のパソコンの中や家の中のネットワークにアクセスさせられないことを確かめる。
 * 手元に HTTP と HTTPS のサーバーを立て、いろいろな書き方の URL、リダイレクト、FFmpeg の再生 (HLS の分割されたファイルも)
 * のどれでも、そのサーバーに 1 度も要求が届かないことを確かめる。確認のための設定 (marumasign.allowLocalUrls) では
 * 読めることも確かめる。あわせて、看板の数値に NaN や大きすぎる値が書かれた場合も確かめる。
 * 1 つでも違えば終了コード 1 で終わる。
 */
public final class SecurityCheck {

    private static int failures;
    private static final AtomicInteger hits = new AtomicInteger();
    private static final Map<String, byte[]> files = new HashMap<String, byte[]>();

    public static void main(String[] args) throws Exception {
        System.clearProperty("marumasign.allowLocalUrls");
        final File directory = Files.createTempDirectory("marumasign-security-check").toFile();
        DownloadCache.directoryOverride = directory;
        files.put("/a.png", png());
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new FileHandler());
        server.start();
        final int port = server.getAddress().getPort();
        HttpsServer secure = null;
        try {
            checkPolicy();
            checkSignValues();
            checkLogText();
            checkModelResources(directory);
            checkPlaylists();
            checkDownloads(port);
            checkPublicRedirect(port);
            checkProxy(port);
            if (Ffmpeg.isAvailable()) {
                secure = https(directory);
                checkFfmpeg(directory, port, secure.getAddress().getPort());
            } else {
                System.out.println("skip: FFmpeg was not found, so playback through the proxy was not checked");
            }
            checkAllowedForVerification(port);
            checkSlowServers();
        } finally {
            server.stop(0);
            if (secure != null) secure.stop(0);
            delete(directory);
        }
        System.out.println(failures == 0 ? "All security checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    private static void delete(File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }

    private static void checkPolicy() {
        final String[] blocked = {
                "http://127.0.0.1/", "http://localhost/", "http://LocalHost./", "http://a.localhost/", "http://[::1]/",
                "http://0.0.0.0/", "http://10.1.2.3/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.0.1/",
                "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://198.18.0.1/", "http://224.0.0.1/",
                "http://[fd00::1]/", "http://[fe80::1]/", "http://[::ffff:192.168.0.1]/", "http://printer.local/",
                "http://router.lan/", "http://nas.home.arpa/", "http://printer.local./", "http://localhost../", "file:///C:/Windows/win.ini", "ftp://example.com/a.png",
                "jar:http://example.com/a.jar!/a.png", "gopher://example.com/", "", "http:///a.png",
        };
        for (String url : blocked) check("blocked: " + url, !UrlPolicy.isAllowed(url));
        // IPv4 を埋め込んだ IPv6 の書き方と、ウェブではないサービスの番号
        final String[] alsoBlocked = {
                "http://[::127.0.0.1]/", "http://[64:ff9b::c0a8:1]/", "http://[2002:c0a8:0101::1]/", "http://[2002:7f00:1::1]/",
                "http://example.com:25/", "https://example.com:22/a.png", "http://93.184.215.14:6667/",
        };
        for (String url : alsoBlocked) check("blocked: " + url, !UrlPolicy.isAllowed(url));
        check("allowed: a usual port", UrlPolicy.isAllowed("https://example.com:8443/a.png"));
        final String[] allowed = {"https://example.com/a.png", "http://93.184.215.14/a.png", "https://[2606:4700::6810:84e5]/"};
        for (String url : allowed) check("allowed: " + url, UrlPolicy.isAllowed(url));
        check("172.32.0.1 is on the internet", UrlPolicy.isAllowed("http://172.32.0.1/"));
    }

    private static void checkSignValues() {
        check("normal values", same(SignSpec.parse("https://a/b.png|0|.5|-1|2|3|90|-45|10"), 0f, .5f, -1f, 2f, 3f, 90f, -45f, 10f));
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "0x10", "1,5", "", " "}) {
            check("rejected value '" + value + "'", SignSpec.parse("https://a/b.png|" + value + "|0|0|1|1|0|0|0") == null);
        }
        check("huge values are limited", same(SignSpec.parse("https://a/b.png|1e30|-1e30|5000|99999|-3e38|1e9|0|0"),
                1000f, -1000f, 1000f, 1000f, -1000f, 1000f, 0f, 0f));
        check("too few values", SignSpec.parse("https://a/b.png|0|0|0|1|1|0|0") == null);
        check("written text is limited too", SignSpec.parse(SignSpec.parse("u|1e30|0|0|1|1|0|0|0").toText()).x == 1000f);
    }

    // 記録に書く文字に、Log4j に解釈させる書き方や、行を偽る文字が残らないこと
    private static void checkLogText() {
        final String cleaned = SafeLog.clean("https://a/${jndi:ldap://x/a}|${${lower:j}ndi:x}\nFAKE LINE\r\u001b[31m\u202e");
        check("log text has no lookups", !cleaned.contains("${"));
        check("log text has no line breaks or control characters", cleaned.indexOf('\n') < 0 && cleaned.indexOf('\r') < 0
                && cleaned.indexOf('\u001b') < 0 && cleaned.indexOf('\u202e') < 0);
        check("ordinary log text is unchanged", SafeLog.clean("Load: https://a/b.png?x=$1 (12 ms)").equals("Load: https://a/b.png?x=$1 (12 ms)"));
    }

    // 選んだ 3D モデルが参照する外部ファイルは、モデルのフォルダの中のバッファと画像だけを読む (一緒にアップロードされるため)
    private static void checkModelResources(File directory) throws IOException {
        final File folder = new File(directory, "model");
        final File textures = new File(folder, "textures");
        textures.mkdirs();
        final File model = new File(folder, "scene.gltf");
        Files.write(model.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder, "scene.bin").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(textures, "a b+c.png").toPath(), png());
        final File secret = new File(directory, "secret.bin");
        Files.write(secret.toPath(), new byte[]{9});
        Files.write(new File(folder, "notes.txt").toPath(), new byte[]{9});
        final GltfLoader.Resources resources = ImageLoader.fileResources(model);
        check("model buffer in the same folder is read", readable(resources, "scene.bin"));
        check("model texture in a sub folder is read", readable(resources, "textures/a%20b+c.png"));
        final String[] refused = {
                "../secret.bin", "..%2Fsecret.bin", "textures/../../secret.bin", secret.getAbsolutePath(), "/etc/passwd.bin",
                "file:///" + secret.getAbsolutePath().replace('\\', '/'), "notes.txt", "missing.bin", "\\\\server\\share\\a.bin",
                "C:/Windows/win.ini",
        };
        for (String uri : refused) check("model resource is refused: " + uri, !readable(resources, uri));
    }

    private static boolean readable(GltfLoader.Resources resources, String uri) {
        try {
            return resources.load(uri) != null;
        } catch (IOException e) {
            return false;
        }
    }

    // 画像として FFmpeg に渡すものが再生リストなら、渡さない
    private static void checkPlaylists() {
        final String[] playlists = {
                "#EXTM3U\n#EXTINF:1,\nfile:///a.mp4\n", "ffconcat version 1.0\nfile a.mp4\n",
                "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">",
                "<CompositionPlaylist><ContentTitle>a</ContentTitle>",
        };
        for (String text : playlists) {
            check("playlist is refused: " + text.substring(0, 12).trim(), Ffmpeg.isPlaylist(text.getBytes(StandardCharsets.UTF_8)));
        }
        check("binary data is not a playlist", !Ffmpeg.isPlaylist(new byte[]{0, 0, 0, 12, 'j', 'P', ' ', ' ', '#', 'E', 'X', 'T', 'M', '3', 'U'}));
    }

    private static boolean same(SignSpec spec, float... values) {
        if (spec == null) return false;
        final float[] actual = {spec.x, spec.y, spec.z, spec.scaleX, spec.scaleY, spec.roll, spec.yaw, spec.pitch};
        return Arrays.equals(actual, values);
    }

    // いろいろな書き方の、このコンピューターを指す URL
    private static void checkDownloads(int port) {
        final String[] hosts = {
                "127.0.0.1", "localhost", "LOCALHOST", "127.0.0.1.", "[::1]", "[::ffff:127.0.0.1]", "127.1", "2130706433",
                "0x7f000001", "0177.0.0.1", "0.0.0.0", "localtest.me",
        };
        for (String host : hosts) {
            final String url = "http://" + host + ":" + port + "/a.png";
            hits.set(0);
            try {
                Http.download(url);
                check("download is refused: " + url, false);
            } catch (IOException e) {
                check("download is refused: " + url + " (" + e.getMessage() + ")", hits.get() == 0);
            }
        }
        hits.set(0);
        try {
            Http.download("http://127.0.0.1:" + port + "/a.png", new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    return Http.MAX_SIZE;
                }
            });
            check("download with a content check is refused", false);
        } catch (IOException e) {
            check("download with a content check is refused", hits.get() == 0);
        }
    }

    // インターネット上の URL から、このコンピューターへリダイレクトされる場合
    private static void checkPublicRedirect(int port) throws IOException {
        final String target = "http://127.0.0.1:" + port + "/a.png";
        final String url = "https://httpbin.org/redirect-to?url=" + URLEncoder.encode(target, "UTF-8");
        hits.set(0);
        try {
            Http.download(url);
            check("redirect to this computer is refused", false);
        } catch (IOException e) {
            final boolean blocked = String.valueOf(e.getMessage()).startsWith("Blocked URL: " + target);
            if (!blocked) System.out.println("note: the redirect service could not be reached (" + e + ")");
            check("redirect to this computer is refused", hits.get() == 0);
        }
    }

    private static void checkProxy(int port) throws IOException {
        final String[] requests = {
                "GET http://127.0.0.1:" + port + "/a.png HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                "GET http://localhost:" + port + "/a.png HTTP/1.1\r\nHost: localhost\r\n\r\n",
                "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                "CONNECT [::1]:" + port + " HTTP/1.1\r\n\r\n",
                "CONNECT localtest.me:" + port + " HTTP/1.1\r\n\r\n",
        };
        for (String request : requests) {
            hits.set(0);
            final String status = proxy(request);
            check("proxy refuses " + request.substring(0, request.indexOf(' ', 8)) + " (" + status + ")",
                    hits.get() == 0 && !status.contains(" 200 "));
        }
        check("proxy rejects a request that is not for the web", proxy("GET file:///C:/Windows/win.ini HTTP/1.1\r\n\r\n").contains(" 400 "));
        check("proxy rejects a broken request", proxy("hello\r\n\r\n").contains(" 400 "));
    }

    // プロキシに要求を送り、返ってきた最初の行を返す
    private static String proxy(String request) throws IOException {
        final String address = FilteringProxy.address();
        final Socket socket = new Socket("127.0.0.1", Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
        try {
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            final InputStream input = socket.getInputStream();
            final StringBuilder line = new StringBuilder();
            int c;
            while ((c = input.read()) >= 0 && c != '\n') line.append((char) c);
            return line.toString().trim();
        } finally {
            socket.close();
        }
    }

    private static void checkFfmpeg(File directory, int port, int securePort) throws Exception {
        final File video = new File(directory, "v.mp4");
        final File segment = new File(directory, "seg.ts");
        check("make a test video", run(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=1", "-pix_fmt", "yuv420p", video.getPath()), null) == 0);
        check("make a test segment", run(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=64x64:rate=10:duration=1", "-f", "mpegts", segment.getPath()), null) == 0);
        files.put("/v.mp4", Files.readAllBytes(video.toPath()));
        files.put("/seg.ts", Files.readAllBytes(segment.toPath()));

        hits.set(0);
        check("FFmpeg cannot read this computer", play("http://127.0.0.1:" + port + "/v.mp4", true) != 0 && hits.get() == 0);
        hits.set(0);
        check("FFmpeg cannot read this computer over https", play("https://127.0.0.1:" + securePort + "/v.mp4", true) != 0 && hits.get() == 0);

        // 再生リストの中の、分割されたファイルの URL も確かめる
        final File playlist = new File(directory, "list.m3u8");
        Files.write(playlist.toPath(), ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXTINF:1.0,\n"
                + "http://127.0.0.1:" + port + "/seg.ts\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8));
        hits.set(0);
        play(playlist.getPath(), false);
        final boolean reachable = hits.get() > 0;
        check("without the proxy, the playlist reaches the server (the check below means something)", reachable);
        hits.set(0);
        play(playlist.getPath(), true);
        check("a playlist cannot make FFmpeg read this computer", hits.get() == 0);

        // インターネット上の https のファイルは、プロキシを通しても (証明書を確かめたまま) 読める
        final String remote = "https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4";
        final List<String> probe = Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-protocol_whitelist", "http,https,tcp,tls,crypto,httpproxy", "-rw_timeout", "15000000",
                "-i", remote, "-t", "0.5", "-f", "null", "-");
        if (run(probe, null) == 0) {
            check("FFmpeg plays a public https file through the proxy", run(probe, remote) == 0);
        } else {
            System.out.println("skip: the public test file could not be read even without the proxy");
        }

        // 確認のための設定では、プロキシを通して読める (http はそのまま、https はトンネルで)
        System.setProperty("marumasign.allowLocalUrls", "true");
        try {
            hits.set(0);
            check("FFmpeg plays http through the proxy", play("http://127.0.0.1:" + port + "/v.mp4", true) == 0 && hits.get() > 0);
            hits.set(0);
            check("FFmpeg plays https through the proxy", play("https://127.0.0.1:" + securePort + "/v.mp4", true) == 0 && hits.get() > 0);
            hits.set(0);
            check("FFmpeg plays a playlist through the proxy", play(playlist.getPath(), true) == 0 && hits.get() > 0);
        } finally {
            System.clearProperty("marumasign.allowLocalUrls");
        }
    }

    private static int play(String input, boolean proxy) throws Exception {
        final List<String> command = new ArrayList<String>(Arrays.asList(Ffmpeg.requireFfmpeg().getPath(), "-hide_banner", "-loglevel", "error",
                "-nostdin", "-protocol_whitelist", "file,http,https,tcp,tls,crypto,httpproxy", "-rw_timeout", "10000000"));
        // 手元のサーバーの証明書は、その場で作ったもの
        if (input.startsWith("https://")) command.addAll(Arrays.asList("-tls_verify", "0"));
        command.addAll(Arrays.asList("-i", input, "-f", "null", "-"));
        return run(command, proxy ? input : null);
    }

    // proxyInput が null でなければ、FFmpeg がそれを読むものとしてプロキシを使わせる
    private static int run(List<String> command, String proxyInput) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(command)).redirectErrorStream(true);
        if (proxyInput != null) FilteringProxy.apply(builder, proxyInput);
        final Process process = builder.start();
        process.getOutputStream().close();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputStream input = process.getInputStream();
        final byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        final String text = output.toString("UTF-8").trim();
        if (!text.isEmpty()) System.out.println("  ffmpeg: " + text.replace("\n", "\n  ffmpeg: "));
        return process.exitValue();
    }

    // 1 バイトずつゆっくり送るサーバーに、いつまでも付き合わない
    private static void checkSlowServers() throws Exception {
        System.setProperty("marumasign.allowLocalUrls", "true");
        try {
            final String body = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 100000\r\n\r\n";
            check("a slowly sent file is given up within about 30 seconds", slowDownload(body, 0L, 45));
            check("slowly sent headers are given up within about 60 seconds", slowDownload("HTTP/1.1 200 OK\r\nX-Slow: ", 0L, 75));
            check("the deadline stops a slow download", slowDownload(body, System.nanoTime() + 3000000000L, 8));
        } finally {
            System.clearProperty("marumasign.allowLocalUrls");
        }
    }

    // prefix を送ってから、1 秒に 1 バイトずつ送り続けるサーバーから読み、maxSeconds 以内に失敗すれば true
    private static boolean slowDownload(final String prefix, long deadline, int maxSeconds) throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Socket socket = server.accept();
                    final OutputStream output = socket.getOutputStream();
                    output.write(prefix.getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    while (true) {
                        Thread.sleep(1000);
                        output.write('a');
                        output.flush();
                    }
                } catch (Exception e) {
                    // 読む側が切った
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        final long start = System.nanoTime();
        boolean failed = false;
        try {
            final String url = "http://127.0.0.1:" + server.getLocalPort() + "/slow.png";
            if (deadline != 0) {
                Http.downloadBefore(url, null, deadline);
            } else {
                Http.download(url);
            }
        } catch (IOException e) {
            failed = true;
            System.out.println(String.format("  gave up after %.1f s (%s)", (System.nanoTime() - start) / 1e9, e.getMessage()));
        } finally {
            server.close();
        }
        return failed && System.nanoTime() - start < maxSeconds * 1000000000L;
    }

    private static void checkAllowedForVerification(int port) throws IOException {
        System.setProperty("marumasign.allowLocalUrls", "true");
        try {
            hits.set(0);
            check("verification setting reads this computer", Http.download("http://127.0.0.1:" + port + "/a.png").length > 0 && hits.get() == 1);
        } finally {
            System.clearProperty("marumasign.allowLocalUrls");
        }
    }

    // 手元の HTTPS のサーバー。証明書はその場で作る (FFmpeg は既定では証明書を確かめない)
    private static HttpsServer https(File directory) throws Exception {
        final File store = new File(directory, "key.p12");
        final String keytool = new File(System.getProperty("java.home"), "bin/keytool" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        check("make a test certificate", run(Arrays.asList(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore", store.getPath(),
                "-storepass", "changeit", "-keypass", "changeit"), null) == 0);
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        final InputStream input = new FileInputStream(store);
        try {
            keys.load(input, "changeit".toCharArray());
        } finally {
            input.close();
        }
        final KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "changeit".toCharArray());
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", new FileHandler());
        server.start();
        return server;
    }

    private static byte[] png() throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        return bytes.toByteArray();
    }

    // 置いたファイルを返し、届いた要求を数える
    private static final class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            final byte[] content = files.get(exchange.getRequestURI().getPath());
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            final OutputStream output = exchange.getResponseBody();
            try {
                output.write(content);
            } finally {
                output.close();
            }
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }
}
