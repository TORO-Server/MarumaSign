package marumasa.marumasa_sign.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.pdf.PdfFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * 看板に表示するファイルの読み込みにかかる時間を、段階ごとに測る。
 * ダウンロード (手元の HTTP サーバーから) と、デコード (画像・3D モデル・ガウシアンスプラッティング・PDF の 1 ページ目) を分けて測る。
 * 引数は、測るファイル。-Pargs="回数 ファイル..." のように、先頭に回数を付けてもよい (既定は 5 回)。
 */
public final class LoadBenchmark {

    public static void main(String[] args) throws Exception {
        int runs = 5;
        int first = 0;
        if (args.length > 0 && args[0].matches("\\d+")) {
            runs = Integer.parseInt(args[0]);
            first = 1;
        }
        if (args.length <= first) throw new IllegalArgumentException("Usage: LoadBenchmark [runs] <file>...");
        // ゲームの中と同じく、ゲームのフォルダ (PDF のフォントの一覧を保存する場所) がある状態にする
        MarumaSign.init(new FakePlatform("en_us"));

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final byte[][] served = new byte[1][];
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                final byte[] body = served[0];
                exchange.sendResponseHeaders(200, body.length);
                final OutputStream output = exchange.getResponseBody();
                try {
                    output.write(body);
                } finally {
                    output.close();
                }
            }
        });
        server.start();
        try {
            System.out.println(String.format(Locale.ROOT, "%-40s %10s %12s %12s %12s",
                    "file", "size", "download", "decode 1st", "decode best"));
            for (int i = first; i < args.length; i++) {
                final File file = new File(args[i]);
                final byte[] content = Files.readAllBytes(file.toPath());
                served[0] = content;
                final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + file.getName();

                final long[] download = new long[runs];
                final long[] decode = new long[runs];
                for (int run = 0; run < runs; run++) {
                    long start = System.nanoTime();
                    final byte[] fetched = Http.download(url, null, Integer.MAX_VALUE - 16);
                    download[run] = System.nanoTime() - start;
                    if (fetched.length != content.length) throw new IOException("Downloaded size differs: " + url);

                    start = System.nanoTime();
                    decode(file, content);
                    decode[run] = System.nanoTime() - start;
                }
                final long firstDecode = decode[0];
                Arrays.sort(download);
                Arrays.sort(decode);
                System.out.println(String.format(Locale.ROOT, "%-40s %9.1fM %10.1fms %10.1fms %10.1fms",
                        file.getName(), content.length / 1048576.0, download[0] / 1e6, firstDecode / 1e6, decode[0] / 1e6));
            }
        } finally {
            server.stop(0);
        }
    }

    // ゲームの中と同じ方法で、内容から種類を判別して読み込む
    private static void decode(File file, byte[] content) throws IOException {
        final byte[] head = Arrays.copyOf(content, Math.min(512, content.length));
        if (ImageLoader.isSupportedImage(head, head.length)) {
            ImageLoader.decode(content);
        } else if (PdfFile.isPdf(head, head.length)) {
            final long start = System.nanoTime();
            final PdfFile pdf = PdfFile.open(content);
            final long opened = System.nanoTime();
            try {
                pdf.render(0, PdfFile.PAGE_SIZE);
            } finally {
                pdf.close();
            }
            System.out.println(String.format(Locale.ROOT, "  (PDF: open %.1fms, first page %.1fms)",
                    (opened - start) / 1e6, (System.nanoTime() - opened) / 1e6));
        } else if (ImageLoader.isSplat(head, head.length, file.getName())) {
            ImageLoader.splat(file.getName(), content);
        } else if (ImageLoader.isModel(head, head.length)) {
            final long start = System.nanoTime();
            final marumasa.marumasa_sign.core.gltf.GltfModel model =
                    marumasa.marumasa_sign.core.gltf.GltfLoader.load(content, ImageLoader.fileResources(file));
            final long loaded = System.nanoTime();
            new marumasa.marumasa_sign.core.gltf.GltfScene(model).restBounds();
            System.out.println(String.format(Locale.ROOT, "  (model: load %.1fms, scene %.1fms)",
                    (loaded - start) / 1e6, (System.nanoTime() - loaded) / 1e6));
        } else {
            throw new IOException("Unsupported file: " + file);
        }
    }
}
