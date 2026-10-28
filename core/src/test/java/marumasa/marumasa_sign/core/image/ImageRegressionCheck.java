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
        checkSvg();
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

    // SVG の描き方と、悪意のあるファイルへの備え。色は、100x100 の SVG の座標 (x, y) で調べる
    private static void checkSvg() throws Exception {
        // 切り抜き (円、ピクセルに揃った四角形、物の大きさに対する割合) とマスク (cairo が書き出す、色の行列で白くした不透明度のマスクと、明るさのマスク)
        final DecodedImage clip = decode("svg_clip_mask.svg");
        expectColor("svg_clip_mask.svg circle", clip, 25, 25, 0xFFFF0000);
        expectColor("svg_clip_mask.svg outside circle", clip, 7, 7, 0);
        expectColor("svg_clip_mask.svg rect", clip, 60, 25, 0xFF0000FF);
        expectColor("svg_clip_mask.svg outside rect", clip, 90, 40, 0);
        expectColor("svg_clip_mask.svg bounding box", clip, 80, 10, 0xFF008080);
        expectColor("svg_clip_mask.svg outside bounding box", clip, 92, 10, 0);
        expectColor("svg_clip_mask.svg alpha mask", clip, 75, 75, 0x80008000);
        expectColor("svg_clip_mask.svg luminance mask", clip, 25, 75, 0x80000000);

        // symbol は use からだけ描く。自分を指す use、条件に合わない switch の子、読めない fill、セレクターと !important
        final DecodedImage structure = decode("svg_structure.svg");
        expectColor("svg_structure.svg symbol", structure, 5, 5, 0);
        expectColor("svg_structure.svg use symbol", structure, 25, 5, 0xFFFF0000);
        expectColor("svg_structure.svg use cycle", structure, 55, 5, 0);
        expectColor("svg_structure.svg switch", structure, 65, 5, 0xFF008000);
        expectColor("svg_structure.svg class", structure, 5, 25, 0xFF00FF00);
        expectColor("svg_structure.svg child selector", structure, 25, 25, 0xFF0000FF);
        expectColor("svg_structure.svg important", structure, 45, 25, 0xFFFF0000);
        expectColor("svg_structure.svg invalid fill", structure, 65, 25, 0xFFFFA500);
        expectColor("svg_structure.svg media", structure, 85, 25, 0xFF000080);
        // 相対座標の誤差で、閉じる直前にごく短い辺ができても、尖った角 (マイター) が欠けない
        expectColor("svg_structure.svg miter", structure, 63.5, 66, 0xFF000000);
        expectColor("svg_structure.svg arc", structure, 80, 70, 0xFF800000);

        // 外部の実体・DTD・XInclude・画像・塗りは読まない (何も描かれない)
        final DecodedImage external = decode("svg_external.svg");
        for (int p : external.frames[0]) {
            if (p >>> 24 != 0) {
                fail("svg_external.svg drew something from outside the file");
                break;
            }
        }

        // 悪意のあるファイル: 深い入れ子 (スタックの使い切り)、正規表現が遅くなる文字列、use で何度も描かせるもの、循環、実体の展開
        final String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\">";
        final String rect = "<rect width=\"9\" height=\"9\"/>";
        hostile("deep g", svg + repeat("<g opacity=\"0.9\">", 100000) + rect + repeat("</g>", 100000) + "</svg>");
        hostile("deep tspan", svg + "<text y=\"50\">" + repeat("<tspan>x", 100000) + repeat("</tspan>", 100000) + "</text></svg>");
        hostile("deep style", svg + "<style>" + repeat("<a>", 100000) + repeat("</a>", 100000) + "</style>" + rect + "</svg>");
        hostile("css without braces", svg + "<style>" + repeat("a", 300000) + "</style>" + rect + "</svg>");
        hostile("unclosed comments", svg + "<style>" + repeat("/*", 150000) + "</style>" + rect + "</svg>");
        hostile("css colons", svg + "<rect width=\"9\" height=\"9\" style=\"" + repeat(";", 200000) + ":\"/></svg>");
        hostile("transform", svg + "<rect width=\"9\" height=\"9\" transform=\"" + repeat("a(", 150000) + "\"/></svg>");
        hostile("fill fallbacks", svg + "<rect width=\"9\" height=\"9\" fill=\"" + repeat("url(#x) ", 200000) + "\"/></svg>");
        final StringBuilder bomb = new StringBuilder(svg + "<defs><rect id=\"u0\" width=\"100\" height=\"100\" opacity=\"0.5\" stroke=\"red\" stroke-dasharray=\"0.01\"/>");
        for (int i = 1; i <= 8; i++) bomb.append("<g id=\"u").append(i).append("\" opacity=\"0.9\">").append(repeat("<use xlink:href=\"#u" + (i - 1) + "\"/>", 10)).append("</g>");
        hostile("use bomb", bomb + "</defs><use xlink:href=\"#u8\"/></svg>");
        hostile("use cycle", svg + "<g id=\"a\" opacity=\"0.5\"><use href=\"#b\"/><use href=\"#b\"/></g><g id=\"b\" clip-path=\"url(#c)\"><use href=\"#a\"/><use href=\"#a\"/></g>"
                + "<clipPath id=\"c\"><use href=\"#a\"/></clipPath><mask id=\"m\" mask=\"url(#m)\"><use href=\"#a\"/></mask><use href=\"#a\" mask=\"url(#m)\"/></svg>");
        hostile("huge path", svg + "<path stroke=\"black\" stroke-linejoin=\"round\" d=\"M0 0" + repeat(" L100 100 L0 100", 200000) + "\"/></svg>");
        hostile("huge numbers", svg.replace("width=\"100\"", "width=\"1e308\"") + "<path d=\"M-1e300 -1e300 L1e300 1e300 A 1e300 1e-300 45 1 1 7 9\" stroke=\"red\" stroke-width=\"1e300\""
                + " stroke-dasharray=\"1e-30\" transform=\"scale(1e300)\"/><text font-size=\"1e300\">x</text></svg>");
        // 拡大率が極端に小さい変換で破線を描くと、Java 2D の破線の処理が終わらなくなっていた
        hostile("tiny scale dash", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1e-308\" height=\"59\" viewBox=\"0 50 56 12\" preserveAspectRatio=\"junk\">"
                + "<rect width=\"1e-308\" height=\"100\" stroke=\"red\" stroke-dasharray=\"1\"/><rect x=\"9\" width=\"1e-308\" height=\"100\" stroke=\"red\" stroke-dasharray=\"1\"/></svg>");
        // Java 8 の Java 2D は、極端に大きな曲線や、1 行に辺が非常に多い図形 (長い 1 行の文字) を塗るのに、終わらないほど時間がかかっていた
        hostile("huge curve", svg + "<path d=\"M-12 44 m 133 108 -40 143 16 3.4e38 Q 142 98 87 -33 136 z\" stroke=\"red\"/></svg>");
        hostile("long text line", svg + "<text y=\"40\" stroke=\"red\">" + repeat("مرحبا", 4000) + "</text></svg>");
        hostile("billion laughs", "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"" + repeat("x", 10000) + "\"><!ENTITY b \"" + repeat("&a;", 10)
                + "\"><!ENTITY c \"" + repeat("&b;", 10) + "\"><!ENTITY d \"" + repeat("&c;", 10) + "\">]>" + svg + "<text>" + repeat("&d;", 10) + "</text></svg>");
        hostile("truncated DTD", "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"");
    }

    // 例外 (IOException 以外) やスタックの使い切りなしに、5 秒以内に終わり、標準エラーに何も書かないこと
    private static void hostile(String label, String svg) {
        final java.io.PrintStream err = System.err;
        final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        final long start = System.nanoTime();
        System.setErr(new java.io.PrintStream(captured));
        try {
            ImageLoader.decode(svg.getBytes(StandardCharsets.UTF_8), "hostile.svg");
        } catch (IOException e) {
            // 読めないファイルとして断るのは正しい
        } catch (Throwable e) {
            fail("hostile SVG (" + label + "): " + e);
        } finally {
            System.setErr(err);
        }
        final long ms = (System.nanoTime() - start) / 1000000;
        if (ms > 5000) fail("hostile SVG (" + label + ") took " + ms + " ms");
        if (captured.size() > 0) fail("hostile SVG (" + label + ") wrote to stderr: " + captured);
    }

    // 透明な部分は、色を比べない
    private static void expectColor(String label, DecodedImage image, double x, double y, int argb) {
        final int px = (int) (x * image.width / 100), py = (int) (y * image.height / 100);
        final int actual = image.frames[0][py * image.width + px];
        int d = Math.abs((actual >>> 24) - (argb >>> 24));
        if ((argb >>> 24) != 0) {
            for (int shift = 0; shift < 24; shift += 8) d = Math.max(d, Math.abs((actual >> shift & 0xFF) - (argb >> shift & 0xFF)));
        }
        if (d > 8) fail(label + ": " + Integer.toHexString(actual) + ", expected " + Integer.toHexString(argb));
    }

    private static String repeat(String s, int count) {
        final StringBuilder b = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) b.append(s);
        return b.toString();
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
