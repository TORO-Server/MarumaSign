package marumasa.marumasa_sign.core.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 画像のデコーダで直した不具合が、また起きていないかを確かめる (引数は要らない)。
 * src/test/resources/image の各ファイルが読めること、名前.refN.png があれば N 番目のコマがそれと同じ色になることを確かめる。
 * 参照の PNG は、Pillow か ImageMagick で読んだ結果。ただし bmp_argb4444 は、ImageMagick が 4 ビットの透明度を 16 倍して
 * 少しずれるので、ImageMagick と 1 段階以内で一致することを確かめた自分のデコーダの結果。問題があれば終了コード 1 で終わる。
 */
public final class ImageRegressionCheck {

    private static int failed;

    public static void main(String[] args) throws Exception {
        final URL url = ImageRegressionCheck.class.getResource("/image/");
        if (url == null) throw new IOException("image/ is not on the class path");
        final File directory = new File(url.toURI());
        final File[] files = directory.listFiles();
        Arrays.sort(files);
        int checked = 0;
        for (File file : files) {
            if (file.getName().contains(".ref")) continue;
            checked++;
            final DecodedImage image;
            try {
                image = ImageLoader.decode(Files.readAllBytes(file.toPath()), file.getName());
            } catch (Exception e) {
                fail(file.getName() + ": " + e);
                continue;
            }
            for (int k = 0; k < image.frames.length; k++) {
                final File reference = new File(directory, file.getName() + ".ref" + k + ".png");
                if (reference.isFile()) compare(file.getName() + " frame " + k, image, k, ImageIO.read(reference));
            }
        }

        // APNG: fcTL より前の IDAT (アニメーションに含まれない絵) はコマにしない
        expect("apng_nodefault.png frames", decode("apng_nodefault.png").frames.length == 2);
        // GIF: 2 つ目のコマの符号の長さが 1 つ目より長くても、前のコマの表が残らない
        expect("gif_mixed_codesize.gif frames", decode("gif_mixed_codesize.gif").frames.length == 2);
        // BMP: 16 ビット (ARGB4444) の透明度を読む。元の画像の右上は透明
        final DecodedImage argb4444 = decode("bmp_argb4444.bmp");
        expect("bmp_argb4444.bmp alpha", argb4444.frames[0][argb4444.width - 1] >>> 24 == 0 && argb4444.frames[0][0] >>> 24 == 255);
        // GIF: 0.01 秒以下の表示時間は、ブラウザと同じく 0.1 秒にする
        for (int delay : decode("gif_anim.gif").delays) expect("gif_anim.gif delay " + delay, delay > 10);
        // PNG と GIF 以外で、自分のデコーダが知らない形式は (ImageIO が読めても) 読まない
        try {
            ImageLoader.decode("not an image".getBytes(StandardCharsets.US_ASCII), "a.txt");
            fail("plain text was decoded");
        } catch (IOException e) {
            // 期待どおり
        }

        System.out.println(checked + " files checked, " + failed + " problems");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static DecodedImage decode(String name) throws Exception {
        final URL url = ImageRegressionCheck.class.getResource("/image/" + name);
        return ImageLoader.decode(Files.readAllBytes(new File(url.toURI()).toPath()), name);
    }

    // 透明な部分は白に重ねて比べる (透明なピクセルの色は、形式によって違ってよい)
    private static void compare(String label, DecodedImage image, int frame, BufferedImage reference) {
        if (reference.getWidth() != image.width || reference.getHeight() != image.height) {
            fail(label + ": size " + image.width + "x" + image.height + ", expected " + reference.getWidth() + "x" + reference.getHeight());
            return;
        }
        final int[] expected = ArgbPixels.read(reference);
        final int[] actual = image.frames[frame];
        int worst = 0;
        for (int i = 0; i < expected.length; i++) {
            final int a = actual[i] >>> 24, b = expected[i] >>> 24;
            if (a == 0 && b == 0) continue;
            int d = Math.abs(a - b);
            for (int shift = 0; shift < 24; shift += 8) {
                final int ca = (actual[i] >> shift & 0xFF) * a + 255 * (255 - a);
                final int cb = (expected[i] >> shift & 0xFF) * b + 255 * (255 - b);
                d = Math.max(d, Math.abs(ca - cb) / 255);
            }
            worst = Math.max(worst, d);
        }
        if (worst > 3) fail(label + ": differs from the reference by " + worst);
    }

    private static void expect(String label, boolean condition) {
        if (!condition) fail(label);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("FAILED " + message);
    }
}
