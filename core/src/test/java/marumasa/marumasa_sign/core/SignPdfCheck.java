package marumasa.marumasa_sign.core;

import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.sign.PdfControls;
import marumasa.marumasa_sign.core.sign.SignImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

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

    public static void main(String[] args) throws Exception {
        final byte[] pdf = Files.readAllBytes(new File(args[0]).toPath());
        final File output = new File(args[1]);
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create " + output);

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test.pdf", exchange -> {
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
