package marumasa.marumasa_sign.core.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 画像の形式ごとのデコーダを、別のツール (ImageMagick、Pillow、FFmpeg) で作ったファイルで確かめる。
 * 引数: 出力先のフォルダ、元の画像 (PNG)、確かめるファイルまたはフォルダ…
 * 元の画像と同じ大きさのものは、ピクセルを比べる (透明な部分は白に重ねて比べる)。アニメーションは最初のコマを比べる。
 * 各ファイルを読んだ画像は、出力先に PNG で書き出す。
 */
public final class ImageFormatsCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: <outDir> <reference.png> <file or dir>...");
            return;
        }
        final File out = new File(args[0]);
        out.mkdirs();
        final File referenceDirectory = new File(args[1]);

        final List<File> files = new ArrayList<File>();
        for (int i = 2; i < args.length; i++) {
            final File f = new File(args[i]);
            if (f.isDirectory()) {
                final File[] children = f.listFiles();
                if (children != null) {
                    Arrays.sort(children);
                    for (File c : children) if (c.isFile()) files.add(c);
                }
            } else {
                files.add(f);
            }
        }

        int failed = 0;
        for (File file : files) {
            final byte[] bytes = Files.readAllBytes(file.toPath());
            final long start = System.nanoTime();
            try {
                final boolean supported = ImageLoader.isSupportedImage(Arrays.copyOf(bytes, Math.min(512, bytes.length)),
                        Math.min(512, bytes.length), file.getName());
                final DecodedImage image = ImageLoader.decode(bytes, file.getName());
                final long ms = (System.nanoTime() - start) / 1000000;
                final BufferedImage png = ArgbPixels.wrap(image.width, image.height, image.frames[0]);
                ImageIO.write(png, "png", new File(out, label(file) + ".png"));
                String comparison = "";
                final BufferedImage reference = reference(referenceDirectory, label(file));
                if (reference != null && image.width == reference.getWidth() && image.height == reference.getHeight()) {
                    comparison = compare(ArgbPixels.readDisplay(reference), image.frames[0]);
                } else if (reference != null) {
                    comparison = "size differs from reference " + reference.getWidth() + "x" + reference.getHeight();
                }
                System.out.printf("%-34s %s %4dx%-4d frames=%-3d %4d ms  %s%n", label(file), supported ? "ok  " : "NOSNIFF",
                        image.width, image.height, image.frames.length, ms, comparison);
            } catch (Throwable e) {
                failed++;
                System.out.printf("%-34s FAILED  %s%n", label(file), e);
            }
        }
        System.out.println(failed + " failed of " + files.size());
    }

    // 別のフォルダの同じ名前のファイルと区別するための名前 (フォルダ名_ファイル名)
    private static String label(File file) {
        final File parent = file.getAbsoluteFile().getParentFile();
        return (parent == null ? "" : parent.getName() + "_") + file.getName();
    }

    // ファイル名から、比べる元の画像を選ぶ (rgba は透明つき、gray はグレー、bilevel は白黒、それ以外はカラー)
    private static BufferedImage reference(File directory, String name) throws java.io.IOException {
        // 別のツールが同じファイルを読んだ結果 (per-file/ファイル名.png) があれば、それと比べる
        final File same = new File(directory, "per-file/" + name + ".png");
        if (same.isFile()) return ImageIO.read(same);
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        final String file = lower.contains("rgba") ? "ref.png" : lower.contains("gray") ? "ref_gray.png"
                : lower.contains("bilevel") ? "ref_bilevel.png" : "ref_rgb.png";
        final File f = new File(directory, file);
        return f.isFile() ? ImageIO.read(f) : null;
    }

    // 白に重ねた RGB の、最大の差と PSNR
    private static String compare(int[] a, int[] b) {
        int max = 0;
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                final int d = Math.abs(over(a[i], shift) - over(b[i], shift));
                if (d > max) max = d;
                sum += (double) d * d;
            }
        }
        final double mse = sum / (a.length * 3.0);
        final String psnr = mse == 0 ? "inf" : String.format("%.1f", 10 * Math.log10(255.0 * 255.0 / mse));
        return "maxDiff=" + max + " psnr=" + psnr + (max == 0 ? "  EXACT" : "");
    }

    private static int over(int argb, int shift) {
        final int alpha = argb >>> 24;
        final int c = (argb >> shift) & 0xFF;
        return (c * alpha + 255 * (255 - alpha) + 127) / 255;
    }
}
