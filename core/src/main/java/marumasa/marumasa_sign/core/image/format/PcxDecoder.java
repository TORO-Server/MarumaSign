package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * PCX (ZSoft Paintbrush)。1・2・4・8 ビットの色の一覧つきと、24・32 ビットのフルカラー。
 */
final class PcxDecoder {

    private PcxDecoder() {
    }

    // 先頭の 128 バイトが PCX らしいかどうか
    static boolean looksLikePcx(byte[] h, int n) {
        if (n < 70) return false;
        final int version = h[1] & 0xFF;
        final int bits = h[3] & 0xFF;
        final int planes = h[65] & 0xFF;
        return (h[0] & 0xFF) == 0x0A && (version == 0 || version == 2 || version == 3 || version == 4 || version == 5)
                && (h[2] & 0xFF) == 1 && (bits == 1 || bits == 2 || bits == 4 || bits == 8)
                && planes >= 1 && planes <= 4
                && FormatUtil.u16le(h, 8) >= FormatUtil.u16le(h, 4) && FormatUtil.u16le(h, 10) >= FormatUtil.u16le(h, 6);
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 128 || !looksLikePcx(d, d.length)) throw new IOException("Not a PCX image");
        final int bits = d[3] & 0xFF;
        final int width = FormatUtil.u16le(d, 8) - FormatUtil.u16le(d, 4) + 1;
        final int height = FormatUtil.u16le(d, 10) - FormatUtil.u16le(d, 6) + 1;
        final int planes = d[65] & 0xFF;
        final int lineBytes = FormatUtil.u16le(d, 66);
        FormatUtil.checkSize(width, height);
        if (lineBytes <= 0 || lineBytes * 8L < (long) width * bits) throw new IOException("Invalid PCX line size");

        final int rowSize = FormatUtil.checkedProduct(lineBytes, planes, 1 << 20);
        final byte[] row = new byte[rowSize];
        final int[] result = new int[width * height];

        // 256 色の一覧は、末尾の 769 バイト (0x0C と 256 色分の RGB)
        int[] palette = new int[256];
        final boolean hasMap = bits == 8 && planes == 1 && d.length >= 128 + 769 && (d[d.length - 769] & 0xFF) == 0x0C;
        if (hasMap) {
            for (int i = 0; i < 256; i++) {
                final int p = d.length - 768 + i * 3;
                palette[i] = FormatUtil.argb(255, d[p], d[p + 1], d[p + 2]);
            }
        } else if (bits == 8 && planes == 1) {
            // 一覧がなければグレー
            for (int i = 0; i < 256; i++) palette[i] = FormatUtil.argb(255, i, i, i);
        } else {
            // 16 色以下の画像は、ヘッダーの一覧を使う
            for (int i = 0; i < 16; i++) {
                final int p = 16 + i * 3;
                palette[i] = FormatUtil.argb(255, d[p], d[p + 1], d[p + 2]);
            }
        }

        int position = 128;
        final int end = hasMap ? d.length - 769 : d.length;
        for (int y = 0; y < height; y++) {
            // 1 行分を RLE から展開する
            int filled = 0;
            while (filled < rowSize) {
                if (position >= end) throw new IOException("Truncated PCX");
                int value = d[position++] & 0xFF;
                int repeat = 1;
                if ((value & 0xC0) == 0xC0) {
                    repeat = value & 0x3F;
                    if (position >= end) throw new IOException("Truncated PCX");
                    value = d[position++] & 0xFF;
                }
                for (int i = 0; i < repeat && filled < rowSize; i++) row[filled++] = (byte) value;
            }

            final int base = y * width;
            if (bits == 8 && planes >= 3) {
                for (int x = 0; x < width; x++) {
                    final int a = planes == 4 ? row[x + lineBytes * 3] & 0xFF : 255;
                    result[base + x] = FormatUtil.argb(a, row[x], row[x + lineBytes], row[x + lineBytes * 2]);
                }
            } else if (bits == 1 && planes == 1) {
                // 白黒。1 が白
                for (int x = 0; x < width; x++) {
                    result[base + x] = ((row[x >> 3] >> (7 - (x & 7))) & 1) != 0 ? 0xFFFFFFFF : 0xFF000000;
                }
            } else {
                // 色の番号が 1 から 8 ビットの面に分かれている (または 1 面に詰まっている)
                final int mask = (1 << bits) - 1;
                for (int x = 0; x < width; x++) {
                    int index = 0;
                    for (int plane = 0; plane < planes; plane++) {
                        final int bitPosition = x * bits;
                        final int value = (row[plane * lineBytes + (bitPosition >> 3)] >> (8 - bits - (bitPosition & 7))) & mask;
                        index |= value << (plane * bits);
                    }
                    result[base + x] = palette[index & 255];
                }
            }
        }
        return FormatUtil.still(width, height, result);
    }
}
