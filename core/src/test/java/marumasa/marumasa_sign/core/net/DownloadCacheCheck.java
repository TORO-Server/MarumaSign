package marumasa.marumasa_sign.core.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ダウンロードしたファイルを残しておく仕組み (DownloadCache) と、それを使う Http.download を確かめる。
 * 手元に HTTP サーバーを立て、変わっていないときは本体を受け取らないこと、変わったら受け取り直すこと、
 * サーバーにつながらないときは残しておいたものを使うこと、などを順に確かめる。1 つでも違えば終了コード 1 で終わる。
 */
public final class DownloadCacheCheck {

    private static int failures;

    // サーバーが返す内容と ETag (null なら ETag を返さない)、返す状態 (0 なら普通に返す)
    private static volatile byte[] body = "first".getBytes(StandardCharsets.UTF_8);
    private static volatile String etag = "\"v1\"";
    private static volatile int forcedStatus;
    // サーバーが受け取った要求 ("GET /a If-None-Match=..." の形)
    private static final List<String> requests = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        // 手元に立てたサーバーから読む
        System.setProperty("marumasign.allowLocalUrls", "true");
        final File directory = Files.createTempDirectory("marumasign-cache-check").toFile();
        DownloadCache.directoryOverride = directory;
        HttpServer server = start(0);
        final int port = server.getAddress().getPort();
        final String url = "http://127.0.0.1:" + port + "/a.png";
        try {
            expect("first download", Http.download(url), "first");
            expect("unchanged file is not sent again", Http.download(url), "first");
            check("the second request is conditional", last().contains("If-None-Match=\"v1\""));
            check("the server answered 304 (no body)", last().endsWith("-> 304"));

            body = "second".getBytes(StandardCharsets.UTF_8);
            etag = "\"v2\"";
            expect("changed file is downloaded again", Http.download(url), "second");
            expect("the new file is kept", Http.download(url), "second");
            check("revalidated with the new ETag", last().contains("If-None-Match=\"v2\""));

            forcedStatus = 503;
            expect("server error uses the kept file", Http.download(url), "second");
            forcedStatus = 404;
            try {
                Http.download(url);
                check("a deleted file (404) is not taken from the cache", false);
            } catch (IOException e) {
                check("a deleted file (404) is not taken from the cache", e.getMessage().contains("404"));
            }
            forcedStatus = 0;

            // 種類の判定 (ContentCheck) には、ダウンロードしたときと同じ名前が渡る
            final String[] seen = new String[1];
            final Http.ContentCheck check = new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    seen[0] = name;
                    return 1024;
                }
            };
            Http.download(url, check);
            check("the content check gets the file name", seen[0] != null && seen[0].endsWith("/a.png"));

            // ETag も Last-Modified もない場合は残さない
            etag = null;
            final String other = "http://127.0.0.1:" + port + "/b.png";
            Http.download(other);
            Http.download(other);
            check("without a validator the file is not kept", !last().contains("If-None-Match") && last().endsWith("-> 200"));
            etag = "\"v2\"";

            // サーバーが止まっても、残しておいたものを使う
            server.stop(0);
            server = null;
            expect("unreachable server uses the kept file", Http.download(url), "second");
            final String missing = "http://127.0.0.1:" + port + "/never.png";
            try {
                Http.download(missing);
                check("unreachable server without a kept file fails", false);
            } catch (IOException e) {
                check("unreachable server without a kept file fails", true);
            }

            // 合計が上限を超えたら、最後に使ったのが古いものから消える
            server = start(port);
            DownloadCache.maxTotal = 100;
            body = new byte[40];
            for (int i = 0; i < 4; i++) {
                Http.download("http://127.0.0.1:" + port + "/size" + i + ".png");
                Thread.sleep(20);
            }
            long total = 0;
            int kept = 0;
            for (File file : directory.listFiles()) {
                if (file.getName().endsWith(".data")) {
                    total += file.length();
                    kept++;
                }
            }
            check("the cache stays under its limit (" + total + " bytes in " + kept + " files)", total <= 100 && kept == 2);
            check("the newest file is kept", DownloadCache.lookup("http://127.0.0.1:" + port + "/size3.png") != null);
            check("the oldest file is removed", DownloadCache.lookup("http://127.0.0.1:" + port + "/size0.png") == null);
            body = new byte[60];
            Http.download("http://127.0.0.1:" + port + "/large.png");
            check("a file larger than half the limit is not kept", DownloadCache.lookup("http://127.0.0.1:" + port + "/large.png") == null);
        } finally {
            if (server != null) server.stop(0);
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
        System.out.println(failures == 0 ? "All download cache checks passed" : failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static HttpServer start(int port) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final String condition = exchange.getRequestHeaders().getFirst("If-None-Match");
                final String tag = etag;
                final int status = forcedStatus != 0 ? forcedStatus
                        : tag != null && tag.equals(condition) ? 304 : 200;
                synchronized (requests) {
                    requests.add("GET " + exchange.getRequestURI() + (condition == null ? "" : " If-None-Match=" + condition)
                            + " -> " + status);
                }
                if (tag != null) exchange.getResponseHeaders().set("ETag", tag);
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                    return;
                }
                final byte[] content = body;
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
        return server;
    }

    private static String last() {
        synchronized (requests) {
            return requests.isEmpty() ? "" : requests.get(requests.size() - 1);
        }
    }

    private static void expect(String name, byte[] actual, String expected) {
        check(name, Arrays.equals(actual, expected.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name + (ok ? "" : " (last request: " + last() + ")"));
    }
}
