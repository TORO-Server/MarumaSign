package marumasa.marumasa_sign.core.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * ArgbPixels.read が、どの形式の画像でも BufferedImage.getRGB と同じ結果になるかを確かめる。
 * 引数に画像のファイルを渡すと、それも ImageIO で読んで比べる。1 つでも違えば終了コード 1 で終わる。
 */
public final class ArgbPixelsCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        final int[] types = {
                BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE,
                BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR,
                BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_USHORT_565_RGB, BufferedImage.TYPE_USHORT_555_RGB,
                BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_BYTE_BINARY,
                BufferedImage.TYPE_BYTE_INDEXED,
        };
        for (int type : types) {
            final BufferedImage image = sample(type, 97, 61);
            compare("type " + type, image);
            // PNG と JPEG に書いて ImageIO で読み直した形式 (ファイルから読むときと同じ形式になる)
            for (String format : new String[]{"png", "jpg", "gif", "bmp"}) {
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                try {
                    if (!ImageIO.write(image, format, output)) continue;
                } catch (javax.imageio.IIOException e) {
                    // Java 8 の JPEG の書き出しは、透明度のある画像を false ではなく例外で断る (Java 9 からは false)
                    continue;
                }
                final BufferedImage read = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
                if (read != null) compare("type " + type + " via " + format + " (read as type " + read.getType() + ")", read);
            }
        }
        // 一部を切り出した画像
        compare("sub image", sample(BufferedImage.TYPE_3BYTE_BGR, 120, 80).getSubimage(7, 5, 50, 40));

        // wrap した画像は、元の配列と同じ内容になる
        final int[] argb = new int[33 * 21];
        final Random random = new Random(3);
        for (int i = 0; i < argb.length; i++) argb[i] = random.nextInt();
        final BufferedImage wrapped = ArgbPixels.wrap(33, 21, argb);
        if (wrapped.getType() != BufferedImage.TYPE_INT_ARGB || !Arrays.equals(ArgbPixels.read(wrapped), argb)
                || !Arrays.equals(wrapped.getRGB(0, 0, 33, 21, null, 0, 33), argb)) {
            fail("wrap: the wrapped image differs from the array");
        }

        for (String name : args) {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(new File(name).toPath())));
            if (image == null) {
                fail(name + ": not readable");
                continue;
            }
            compare(new File(name).getName() + " (type " + image.getType() + ")", image);
        }
        System.out.println(failures == 0 ? "All images match getRGB" : failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static void compare(String name, BufferedImage image) {
        final int width = image.getWidth(), height = image.getHeight();
        final int[] expected = image.getRGB(0, 0, width, height, null, 0, width);
        final int[] actual = ArgbPixels.read(image);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name + ": pixel (" + i % width + ", " + i / width + ") is " + Integer.toHexString(actual[i])
                        + ", getRGB gives " + Integer.toHexString(expected[i]));
                return;
            }
        }
        System.out.println("ok: " + name);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL: " + message);
    }

    // 半透明の色を含む、いろいろな色の図形を描いた画像
    private static BufferedImage sample(int type, int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, type);
        final Graphics2D g = image.createGraphics();
        try {
            final Random random = new Random(type);
            for (int i = 0; i < 40; i++) {
                g.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                g.fillOval(random.nextInt(width), random.nextInt(height), 5 + random.nextInt(40), 5 + random.nextInt(40));
            }
        } finally {
            g.dispose();
        }
        return image;
    }
}
