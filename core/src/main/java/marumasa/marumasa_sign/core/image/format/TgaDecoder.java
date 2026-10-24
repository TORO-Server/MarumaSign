package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * TGA (Truevision TARGA)。色の一覧つき・フルカラー・グレーの、圧縮なしと RLE。
 */
final class TgaDecoder {

    private TgaDecoder() {
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 18) throw new IOException("Truncated TGA");
        final int idLength = d[0] & 0xFF;
        final int mapType = d[1] & 0xFF;
        final int type = d[2] & 0xFF;
        final int mapFirst = FormatUtil.u16le(d, 3);
        final int mapLength = FormatUtil.u16le(d, 5);
        final int mapDepth = d[7] & 0xFF;
        final int width = FormatUtil.u16le(d, 12);
        final int height = FormatUtil.u16le(d, 14);
        final int depth = d[16] & 0xFF;
        final int descriptor = d[17] & 0xFF;
        FormatUtil.checkSize(width, height);

        final boolean rle = type >= 9;
        final int kind = type & 7;
        if (kind != 1 && kind != 2 && kind != 3 || type > 11) throw new IOException("Unsupported TGA type: " + type);
        final int bytesPerPixel = (depth + 7) / 8;
        if (bytesPerPixel < 1 || bytesPerPixel > 4) throw new IOException("Unsupported TGA depth: " + depth);
        if (kind == 1 && depth != 8 && depth != 16) throw new IOException("Unsupported TGA depth: " + depth);
        if (kind == 3 && depth != 8 && depth != 16) throw new IOException("Unsupported TGA depth: " + depth);
        if (kind == 2 && depth != 15 && depth != 16 && depth != 24 && depth != 32) throw new IOException("Unsupported TGA depth: " + depth);

        int position = 18 + idLength;
        int[] palette = null;
        if (mapType == 1) {
            final int entryBytes = (mapDepth + 7) / 8;
            if (entryBytes < 2 || entryBytes > 4) throw new IOException("Unsupported TGA color map");
            palette = new int[Math.max(mapFirst + mapLength, 1)];
            for (int i = 0; i < mapLength; i++) {
                palette[mapFirst + i] = pixel(d, position + i * entryBytes, entryBytes, 0);
            }
            position += mapLength * entryBytes;
        } else if (kind == 1) {
            throw new IOException("TGA has no color map");
        }

        final int count = width * height;
        final int alphaBits = descriptor & 0x0F;
        final boolean alpha = alphaBits > 0 || (kind == 2 && depth == 32);
        final int[] raw = new int[count];
        // 色の一覧つきのときは番号、グレーのときは明るさ (と透明度)、フルカラーのときは色そのものを入れる
        int out = 0;
        final byte[] unit = new byte[4];
        while (out < count) {
            int repeat = 1;
            boolean run = false;
            if (rle) {
                final int header = d[position++] & 0xFF;
                repeat = (header & 0x7F) + 1;
                run = (header & 0x80) != 0;
            }
            if (out + repeat > count) repeat = count - out;
            if (run) {
                System.arraycopy(d, position, unit, 0, bytesPerPixel);
                position += bytesPerPixel;
                final int value = raw(unit, 0, bytesPerPixel, kind, depth, alphaBits, palette);
                for (int i = 0; i < repeat; i++) raw[out++] = value;
            } else {
                for (int i = 0; i < repeat; i++) {
                    raw[out++] = raw(d, position, bytesPerPixel, kind, depth, alphaBits, palette);
                    position += bytesPerPixel;
                }
            }
        }

        // 32 ビットで透明度がすべて 0 なら、透明度を使っていない画像として扱う
        if (kind == 2 && depth == 32 && alphaBits == 0) {
            boolean any = false;
            for (int i = 0; i < count && !any; i++) any = (raw[i] >>> 24) != 0;
            if (!any) for (int i = 0; i < count; i++) raw[i] |= 0xFF000000;
        } else if (!alpha) {
            for (int i = 0; i < count; i++) raw[i] |= 0xFF000000;
        }

        // 原点は左下が標準。descriptor の 0x20 が上から、0x10 が右から
        final boolean topDown = (descriptor & 0x20) != 0;
        final boolean rightToLeft = (descriptor & 0x10) != 0;
        final int[] result = new int[count];
        for (int y = 0; y < height; y++) {
            final int sourceRow = (topDown ? y : height - 1 - y) * width;
            final int targetRow = y * width;
            for (int x = 0; x < width; x++) {
                result[targetRow + x] = raw[sourceRow + (rightToLeft ? width - 1 - x : x)];
            }
        }
        return FormatUtil.still(width, height, result);
    }

    private static int raw(byte[] d, int p, int bytes, int kind, int depth, int alphaBits, int[] palette) throws IOException {
        if (p + bytes > d.length) throw new IOException("Truncated TGA");
        if (kind == 1) {
            final int index = bytes == 1 ? d[p] & 0xFF : FormatUtil.u16le(d, p);
            if (index >= palette.length) return 0xFF000000;
            return palette[index];
        }
        if (kind == 3) {
            final int gray = d[p] & 0xFF;
            final int a = bytes == 2 ? d[p + 1] & 0xFF : 255;
            return FormatUtil.argb(a, gray, gray, gray);
        }
        return pixel(d, p, bytes, alphaBits);
    }

    // 色の 1 つ分 (2 から 4 バイト) を ARGB にする
    private static int pixel(byte[] d, int p, int bytes, int alphaBits) throws IOException {
        if (p + bytes > d.length) throw new IOException("Truncated TGA");
        if (bytes == 2) {
            final int v = FormatUtil.u16le(d, p);
            final int r = (v >> 10) & 31, g = (v >> 5) & 31, b = v & 31;
            // 透明度が 1 ビットある画像では、最上位のビットが 0 の色が透明
            final int a = alphaBits > 0 && (v & 0x8000) == 0 ? 0 : 255;
            return FormatUtil.argb(a, r * 255 / 31, g * 255 / 31, b * 255 / 31);
        }
        if (bytes == 3) return FormatUtil.argb(255, d[p + 2], d[p + 1], d[p]);
        return FormatUtil.argb(d[p + 3] & 0xFF, d[p + 2], d[p + 1], d[p]);
    }
}
