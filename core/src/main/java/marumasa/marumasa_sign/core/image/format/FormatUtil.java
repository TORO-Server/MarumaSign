package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;

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

    // value を 0 から max の範囲から 0 から 255 に直す
    static int to8(long value, long max) {
        if (max == 255) return (int) value;
        return (int) ((value * 255 + max / 2) / max);
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
