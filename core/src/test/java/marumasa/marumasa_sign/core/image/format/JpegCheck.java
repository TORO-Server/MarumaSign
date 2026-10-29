package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Java だけで書いた JPEG の読み込み (JpegReader) を、libjpeg (Pillow) で戻した結果と比べる。
 * src/test/resources/jpeg の *.jpg と、同じ名前に .ref.png を付けた正解 (Pillow で戻して、写真の向きを直したもの。
 * CMYK は (255 - C) * (255 - K) / 255 の式で RGB にしたもの) を使う。
 * ベースライン・プログレッシブ、色の間引き (4:4:4、4:2:0)、灰色、CMYK (Adobe)、リスタートマーカー、Exif の向きを含む。
 * <p>
 * 引数: 試験用のファイルのフォルダ (省略すると src/test/resources/jpeg)
 */
public final class JpegCheck {

    // 1 つの色の差の上限 (IDCT と色の変換の丸めの違いの分)
    private static final int MAX_DIFFERENCE = 6;
    private static int failures;

    private JpegCheck() {
    }

    public static void main(String[] args) throws Exception {
        final File directory = new File(args.length > 0 ? args[0] : "src/test/resources/jpeg");
        final File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) throw new IOException("No JPEG files in " + directory);
        Arrays.sort(files);
        for (File file : files) compare(file);
        checkBroken();
        System.out.println(failures == 0 ? "All JPEG checks passed" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void compare(File file) throws IOException {
        final byte[] data = Files.readAllBytes(file.toPath());
        final DecodedImage image = JpegDecoder.decode(data);
        final BufferedImage reference = ImageIO.read(new File(file.getPath() + ".ref.png"));
        if (image.width != reference.getWidth() || image.height != reference.getHeight()) {
            fail(file.getName() + ": size " + image.width + "x" + image.height + ", expected " + reference.getWidth() + "x" + reference.getHeight());
            return;
        }
        final int[] pixels = image.frames[0];
        int max = 0;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                final int p = pixels[y * image.width + x], q = reference.getRGB(x, y);
                if ((p >>> 24) != 0xFF) {
                    fail(file.getName() + ": not opaque at " + x + "," + y);
                    return;
                }
                for (int shift = 0; shift < 24; shift += 8) max = Math.max(max, Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF)));
            }
        }
        if (max > MAX_DIFFERENCE) fail(file.getName() + ": differs from libjpeg by " + max);
        else System.out.println("ok   " + file.getName() + " (max difference " + max + ")");
        // ImageBytes (3D モデルのテクスチャなど) も、ImageIO ではなく同じ読み込みを使う
        final BufferedImage viaBytes = ImageBytes.read(data);
        if (viaBytes == null || viaBytes.getWidth() * viaBytes.getHeight() != image.width * image.height) {
            fail(file.getName() + ": ImageBytes.read gave a different image");
        }
    }

    // 大きさを偽ったものや途中で切れたものは、IOException になるか、読めたところまでを返す (ほかの例外やメモリ不足にならない)
    private static void checkBroken() {
        final byte[] huge = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 11, 8, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                1, 1, 0x11, 0, (byte) 0xFF, (byte) 0xD9};
        expectRejected("65535 x 65535", huge);
        expectRejected("only the start", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private static void expectRejected(String name, byte[] data) {
        try {
            JpegDecoder.decode(data);
            fail(name + ": accepted");
        } catch (IOException e) {
            System.out.println("ok   rejected " + name);
        } catch (RuntimeException | OutOfMemoryError e) {
            fail(name + ": " + e);
        }
        try {
            ImageBytes.read(data);
            fail(name + ": accepted by ImageBytes");
        } catch (IOException e) {
            System.out.println("ok   rejected " + name + " (ImageBytes)");
        } catch (RuntimeException | OutOfMemoryError e) {
            fail(name + " (ImageBytes): " + e);
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
