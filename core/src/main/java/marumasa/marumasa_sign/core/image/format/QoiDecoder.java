package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * QOI (Quite OK Image Format)。
 */
final class QoiDecoder {

    private QoiDecoder() {
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 14 + 8 || !FormatUtil.startsWith(d, d.length, "qoif")) throw new IOException("Not a QOI image");
        final long width = FormatUtil.u32be(d, 4);
        final long height = FormatUtil.u32be(d, 8);
        FormatUtil.checkSize(width, height);
        final int count = (int) (width * height);
        final int[] result = new int[count];
        final int[] index = new int[64];

        int r = 0, g = 0, b = 0, a = 255;
        int run = 0;
        int p = 14;
        final int chunksEnd = d.length - 8;
        for (int i = 0; i < count; i++) {
            if (run > 0) {
                run--;
            } else if (p < chunksEnd) {
                final int b1 = d[p++] & 0xFF;
                if (b1 == 0xFE) {
                    r = d[p++] & 0xFF;
                    g = d[p++] & 0xFF;
                    b = d[p++] & 0xFF;
                } else if (b1 == 0xFF) {
                    r = d[p++] & 0xFF;
                    g = d[p++] & 0xFF;
                    b = d[p++] & 0xFF;
                    a = d[p++] & 0xFF;
                } else if ((b1 & 0xC0) == 0x00) {
                    final int color = index[b1];
                    a = color >>> 24;
                    r = (color >> 16) & 0xFF;
                    g = (color >> 8) & 0xFF;
                    b = color & 0xFF;
                } else if ((b1 & 0xC0) == 0x40) {
                    r = (r + ((b1 >> 4) & 3) - 2) & 0xFF;
                    g = (g + ((b1 >> 2) & 3) - 2) & 0xFF;
                    b = (b + (b1 & 3) - 2) & 0xFF;
                } else if ((b1 & 0xC0) == 0x80) {
                    final int b2 = d[p++] & 0xFF;
                    final int dg = (b1 & 0x3F) - 32;
                    r = (r + dg - 8 + ((b2 >> 4) & 0x0F)) & 0xFF;
                    g = (g + dg) & 0xFF;
                    b = (b + dg - 8 + (b2 & 0x0F)) & 0xFF;
                } else {
                    run = b1 & 0x3F;
                }
                index[(r * 3 + g * 5 + b * 7 + a * 11) & 63] = FormatUtil.argb(a, r, g, b);
            }
            result[i] = FormatUtil.argb(a, r, g, b);
        }
        return FormatUtil.still((int) width, (int) height, result);
    }
}
