package marumasa.marumasa_sign.core;

import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.pdf.PdfFile;
import marumasa.marumasa_sign.core.sign.PdfControls;
import marumasa.marumasa_sign.core.sign.SignImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minecraft を起動せずに、PDF の看板の表示とページ送りを確かめる道具。
 * PDF を手元の HTTP サーバーから配り、看板として読み込んで、照準を進めるボタンに合わせてクリックし、
 * 2 ページ目に進むことと、照準を外すと帯が消えることを確かめる。帯と各ページの画像を出力先に保存する。
 * 引数: PDF のファイル, 出力先フォルダ
 */
public final class SignPdfCheck {

    // 看板のブロックの位置
    private static final int BX = 10, BY = 64, BZ = -5;

    private static FakePlatform platform;
    private static String text;
    // サーバーが受けたリクエストの URI
    private static final List<String> requests = new CopyOnWriteArrayList<String>();

    public static void main(String[] args) throws Exception {
        // 手元に立てたサーバーから読む
        System.setProperty("marumasign.allowLocalUrls", "true");
        final byte[] pdf = Files.readAllBytes(new File(args[0]).toPath());
        final File output = new File(args[1]);
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create " + output);

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test.pdf", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(200, pdf.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(pdf);
            }
        });
        server.start();
        try {
            run(output, "http://127.0.0.1:" + server.getAddress().getPort() + "/test.pdf");
        } finally {
            server.stop(0);
        }
        System.exit(0);
    }

    private static void run(File output, String url) throws Exception {
        platform = new FakePlatform("en_us");
        MarumaSign.init(platform);
        MarumaSign.config().setVolume(0);
        text = url + "|0|0|0|1|1|0|0|0";
        final PdfControls controls = MarumaSign.signs().pdfControls();

        // 読み込みが終わるまで待つ
        SignImage image = null;
        for (int i = 0; i < 40000 && (image == null || !image.isLoaded()); i++) {
            image = frame();
        }
        check(image != null && image.isLoaded(), "PDF loaded");
        save(output, "page1.png");
        final int[] page1 = shown(text);

        // 絵の正面の、3 ブロック離れた位置から、進めるボタンを見る。
        // 向き 0 の立て看板は南 (+z) を向いていて、文字や絵はその側から読める
        final double[] center = controls.lastTarget(PdfControls.PAGE);
        final double[] next = controls.lastTarget(PdfControls.NEXT);
        check(center != null && next != null, "page controls placed");
        final double[] eye = {center[0], center[1], center[2] + 3};
        aim(eye, next);
        check(image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ).length == 2,
                "page controls shown when aiming at the sign from the front (" + controls.missDetail() + ")");
        // 進めるボタンは、正面から見て右下にある (北 (-z) を向いて見ると、右は東 (+x))
        check(next[0] > center[0] && next[1] < center[1], "next button at the lower right seen from the front");
        check(controls.hoverPart() == PdfControls.NEXT, "aiming at the next button (part " + controls.hoverPart() + ")");
        saveBar(output, image, "bar-page1.png");

        // 裏から見ても帯は出ない
        aim(new double[]{center[0], center[1], 2 * center[2] - eye[2]}, center);
        check(image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ).length == 1, "no page controls from behind");

        // クリックで 2 ページ目へ
        aim(eye, next);
        image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
        check(MarumaSign.useClicked(), "click used by the page controls");
        int[] page = null;
        for (int i = 0; i < 400; i++) {
            aim(eye, next);
            frame();
            image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
            page = controls.hoveredPage();
            if (page != null && page[0] == 1 && page[2] == 0) break;
        }
        check(page != null && page[0] == 1 && page[2] == 0, "turned to page 2");
        save(output, "page2.png");
        final int[] page2 = shown(text);
        check(!Arrays.equals(page1, page2), "page 2 looks different from page 1");
        aim(eye, next);
        saveBar(output, image, "bar-page2.png");

        // 最後のページでは、進めるボタンを押しても何も起きないが、クリックはゲームに渡さない
        check(MarumaSign.useClicked(), "click on the disabled next button is still used by the page controls");
        frame();
        check(controls.hoveredPage()[0] == 1, "still on page 2");

        // 戻るボタン
        final double[] previous = controls.lastTarget(PdfControls.PREVIOUS);
        aim(eye, previous);
        image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
        check(controls.hoverPart() == PdfControls.PREVIOUS, "aiming at the previous button");
        check(MarumaSign.useClicked(), "click on the previous button");
        frame();
        image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
        check(controls.hoveredPage()[0] == 0, "back on page 1 (cached)");

        // 照準を外すと帯は消え、クリックはゲームに渡す
        platform.crosshair = new double[]{eye[0], eye[1], eye[2], 0, 1, 0, -1};
        check(image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ).length == 1, "page controls hidden when looking away");
        check(!MarumaSign.useClicked(), "click passed to the game when looking away");

        // 壁越し (照準が手前のブロックに当たっている) では操作できない
        aim(eye, next);
        platform.crosshair[6] = 0.5;
        check(image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ).length == 1, "no page controls through a wall");

        // URL の末尾の「#page=ページ番号」で、最初に表示するページを指定できる (ブラウザの PDF ビューアと同じ書き方)
        checkRequestedPages();
        final String[][] starts = {
                {"#page=2", "2"}, {"#page=1", "1"}, {"#zoom=50&page=2", "2"}, {"#PAGE=2", "2"}, {"#page=002", "2"},
                // 多すぎるページは最後のページ、正しくない値は最初のページ
                {"#page=99", "2"}, {"#page=99999999999999", "2"}, {"#page=0", "1"}, {"#page=-1", "1"}, {"#page=two", "1"},
                {"#page=", "1"}, {"#nameddest=intro", "1"}, {"#", "1"},
        };
        for (String[] start : starts) {
            final String pageText = url + start[0] + "|0|0|0|1|1|0|0|0";
            SignImage paged = null;
            for (int i = 0; i < 40000 && (paged == null || !paged.isLoaded()); i++) {
                MarumaSign.tick();
                platform.runTasks();
                paged = MarumaSign.sign(pageText);
                paged.batches(0f, false, 3.0);
                Thread.sleep(5);
            }
            check(paged != null && paged.isLoaded(), url + start[0] + " loaded");
            check(Arrays.equals(shown(pageText), start[1].equals("2") ? page2 : page1),
                    url + start[0] + " starts on page " + start[1]);
        }
        // 「#」から後ろはサーバーに送らない (HTTP の決まり。送るとサーバーによってはファイルが見つからない)
        check(requests.stream().noneMatch(uri -> uri.contains("#") || uri.contains("page=")),
                "the page is not sent to the server (" + requests + ")");
        // 指定だけが違う看板は、それぞれのページを表示したまま、ほかの看板のページ送りに影響されない
        check(Arrays.equals(shown(url + "#page=2|0|0|0|1|1|0|0|0"), page2), "a sign with #page=2 still shows page 2");
        check(Arrays.equals(shown(text), page1), "the sign without #page still shows page 1");

        System.out.println("ALL OK");
        MarumaSign.signs().clearCache();
    }

    // 1 フレーム分 (tick と、メインスレッドに渡された処理と、描画) を進める
    private static SignImage frame() throws InterruptedException {
        MarumaSign.tick();
        platform.runTasks();
        final SignImage image = MarumaSign.sign(text);
        image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
        Thread.sleep(25);
        return image;
    }

    // 看板に表示しているページの画素
    private static int[] shown(String signText) {
        return ((FakePlatform.Texture) MarumaSign.sign(signText).batches(0f, false, 3.0)[0].texture).pixels.clone();
    }

    // 「#page=」の読み取り。ページの数はまだ見ない (多すぎる値は、表示するときに最後のページに収める)
    private static void checkRequestedPages() {
        final Object[][] cases = {
                {"https://example.com/a.pdf", 0}, {"https://example.com/a.pdf#page=3", 2},
                {"https://example.com/a.pdf#page=1", 0}, {"https://example.com/a.pdf#zoom=50&page=4", 3},
                {"https://example.com/a.pdf#page=4&zoom=50", 3}, {"https://example.com/a.pdf#Page=5", 4},
                {"https://example.com/a.pdf#page= 6 ", 5}, {"https://example.com/a.pdf#page=0007", 6},
                {"https://example.com/a.pdf#page=0", 0}, {"https://example.com/a.pdf#page=-2", 0},
                {"https://example.com/a.pdf#page=2.5", 0}, {"https://example.com/a.pdf#page=", 0},
                {"https://example.com/a.pdf#page", 0}, {"https://example.com/a.pdf#pages=3", 0},
                {"https://example.com/a.pdf#page=99999999999999999999", Integer.MAX_VALUE},
                {"https://example.com/a.pdf?page=3", 0}, {"https://example.com/a.pdf?x=1#page=3", 2},
                {"https://example.com/a.pdf#", 0}, {null, 0},
        };
        for (Object[] item : cases) {
            final int actual = PdfFile.requestedPage((String) item[0]);
            check(actual == (Integer) item[1], "requestedPage(" + item[0] + ") = " + actual + " (expected " + item[1] + ")");
        }
    }

    private static void aim(double[] eye, double[] target) {
        double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        platform.crosshair = new double[]{eye[0], eye[1], eye[2], dx / length, dy / length, dz / length, -1};
    }

    private static void check(boolean condition, String what) {
        System.out.println((condition ? "ok   " : "FAIL ") + what);
        if (!condition) {
            System.exit(1);
        }
    }

    // 看板に表示しているページを保存する
    private static void save(File output, String name) throws Exception {
        final SignImage image = MarumaSign.sign(text);
        write((FakePlatform.Texture) image.batches(0f, false, 3.0)[0].texture, new File(output, name));
    }

    private static void write(FakePlatform.Texture texture, File file) throws Exception {
        final BufferedImage picture = new BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB);
        picture.setRGB(0, 0, texture.width, texture.height, texture.pixels, 0, texture.width);
        ImageIO.write(picture, "png", file);
    }

    private static void saveBar(File output, SignImage image, String name) throws Exception {
        final SignImage.Batch[] batches = image.batches(0f, false, 3.0, 0.5f, 0.5f, 4.5f, BX, BY, BZ);
        write((FakePlatform.Texture) batches[batches.length - 1].texture, new File(output, name));
    }
}
