package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.anim.DecodedAnimation;
import marumasa.marumasa_sign.core.anim.GifDecoder;
import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.image.jpeg.JpegReader;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 画像形式ごとのデコーダで共通して使う小さな道具。
 * 壊れたファイルで範囲外を読んだときの例外は、呼び出し側 (ImageFormats) がまとめて IOException にする。
 */
final class FormatUtil {

    private FormatUtil() {
    }

    // 大きさが上限を超えていたら、ピクセルの領域を確保する前に断る
    static void checkSize(long width, long height) throws IOException {
        final int max = ImageLoader.MAX_STATIC_SIZE;
        if (width <= 0 || height <= 0 || width > max || height > max) {
            throw new IOException("Image exceeds safety limits (max " + max + "x" + max + "): " + width + "x" + height);
        }
    }

    static DecodedImage still(int width, int height, int[] argb) {
        return new DecodedImage(width, height, new int[][]{argb}, new int[]{1}, 0);
    }

    /**
     * ほかの形式の中に入っている画像 (ICO と ICNS の中の PNG、SVG の data: URI) を読む。読めない形式なら null。
     * ImageIO は大きさを確かめずに領域を確保したり、BMP や TIFF の中の JPEG を libjpeg で読んだりするので、
     * PNG は大きさを確かめてから渡し、JPEG・BMP・GIF は自分のデコーダで読む。それ以外の形式は読まない。
     */
    static BufferedImage readEmbedded(byte[] data) throws IOException {
        return readEmbedded(data, (long) ImageLoader.MAX_STATIC_SIZE * ImageLoader.MAX_STATIC_SIZE);
    }

    /**
     * readEmbedded と同じだが、ピクセル数が maxPixels を超える画像は、展開する前に断る (先頭に書かれた大きさで確かめる)。
     */
    static BufferedImage readEmbedded(byte[] data, long maxPixels) throws IOException {
        final long[] size = embeddedSize(data);
        if (size == null) return null;
        checkSize(size[0], size[1]);
        if (size[0] * size[1] > maxPixels) throw new IOException("Embedded image is too large: " + size[0] + "x" + size[1]);
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return ImageBytes.read(data);
        }
        final DecodedImage image;
        if (ImageBytes.isJpeg(data)) {
            image = JpegDecoder.decode(data);
        } else if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
            BmpDecoder.checkHeader(data);
            image = BmpDecoder.decodeFile(data);
        } else if (ImageLoader.isGif(data)) {
            final DecodedAnimation gif = GifDecoder.read(data);
            if (gif.getFrames().isEmpty()) throw new IOException("GIF has no frames");
            checkSize(gif.getWidth(), gif.getHeight());
            image = still(gif.getWidth(), gif.getHeight(), gif.getFrames().get(0).getPixels());
        } else {
            return null;
        }
        return ArgbPixels.wrap(image.width, image.height, image.frames[0]);
    }

    // 埋め込まれた画像の、先頭に書かれた大きさ ({幅, 高さ})。readEmbedded が読まない形式なら null。壊れていれば IOException
    private static long[] embeddedSize(byte[] data) throws IOException {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            if (data.length < 24 || !matchesAt(data, data.length, 12, "IHDR")) throw new IOException("Invalid embedded PNG");
            return new long[]{u32be(data, 16), u32be(data, 20)};
        }
        if (ImageBytes.isJpeg(data)) {
            final int[] size = JpegReader.size(data);
            if (size == null) throw new IOException("Invalid embedded JPEG");
            return new long[]{size[0], size[1]};
        }
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
            if (data.length < 26) throw new IOException("Truncated BMP");
            if (s32le(data, 14) == 12) return new long[]{u16le(data, 18), u16le(data, 20)};
            return new long[]{s32le(data, 18), Math.abs((long) s32le(data, 22))};
        }
        if (ImageLoader.isGif(data)) {
            if (data.length < 10) throw new IOException("Truncated GIF");
            return new long[]{u16le(data, 6), u16le(data, 8)};
        }
        return null;
    }

    static int argb(int a, int r, int g, int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    static int clamp8(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    // 0 から 1 の線形な明るさを、sRGB の 0 から 255 にする (範囲の外は端に寄せる)
    static int srgb(double linear) {
        if (!(linear > 0)) return 0;
        if (linear >= 1) return 255;
        final double encoded = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return clamp8((int) Math.round(encoded * 255));
    }

    // 印刷用の色 (CMYK。各 0 から 255 で、255 が最も濃い) を ARGB にする
    static int cmyk(int c, int m, int y, int k) {
        final int r = (255 - c) * (255 - k) / 255;
        final int g = (255 - m) * (255 - k) / 255;
        final int b = (255 - y) * (255 - k) / 255;
        return argb(255, r, g, b);
    }

    // value を 0 から max (2^32 - 1 まで) の範囲から 0 から 255 に直す
    static int to8(long value, long max) {
        if (max <= 0 || max > 0xFFFFFFFFL) return 0;
        // 範囲の外の値 (壊れたファイル) は端に寄せる。0xFFFFFFFF * 255 は long に収まり、結果は 0 から 255
        final long v = Math.max(0, Math.min(value, max));
        return Math.toIntExact((v * 255 + max / 2) / max);
    }

    static int u8(byte[] d, int p) {
        return d[p] & 0xFF;
    }

    static int u16le(byte[] d, int p) {
        return (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8;
    }

    static int u16be(byte[] d, int p) {
        return (d[p] & 0xFF) << 8 | (d[p + 1] & 0xFF);
    }

    static int s32le(byte[] d, int p) {
        return (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 | (d[p + 2] & 0xFF) << 16 | (d[p + 3] & 0xFF) << 24;
    }

    static int s32be(byte[] d, int p) {
        return (d[p] & 0xFF) << 24 | (d[p + 1] & 0xFF) << 16 | (d[p + 2] & 0xFF) << 8 | (d[p + 3] & 0xFF);
    }

    static long u32le(byte[] d, int p) {
        return s32le(d, p) & 0xFFFFFFFFL;
    }

    static long u32be(byte[] d, int p) {
        return s32be(d, p) & 0xFFFFFFFFL;
    }

    static boolean startsWith(byte[] d, int length, String ascii) {
        final byte[] prefix = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (length < prefix.length || d.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (d[i] != prefix[i]) return false;
        }
        return true;
    }

    static boolean matchesAt(byte[] d, int length, int offset, String ascii) {
        final byte[] text = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || offset + text.length > length || offset + text.length > d.length) return false;
        for (int i = 0; i < text.length; i++) {
            if (d[offset + i] != text[i]) return false;
        }
        return true;
    }

    // 行の長さ (バイト数) が、範囲に収まるかを確かめて返す
    static int checkedProduct(long a, long b, long limit) throws IOException {
        final long product = a * b;
        if (a < 0 || b < 0 || product > limit) throw new IOException("Image data is too large");
        return (int) product;
    }
}
