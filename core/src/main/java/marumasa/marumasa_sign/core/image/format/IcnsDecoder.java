package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ICNS (macOS のアイコン)。PNG で入っているいちばん大きい絵を使う。
 * 古い形式 (RLE で圧縮した RGB と、別に入っている透明度) にも対応する。JPEG 2000 で入っているものは読めない。
 */
final class IcnsDecoder {

    private IcnsDecoder() {
    }

    static boolean looksLikeIcns(byte[] h, int n) {
        return FormatUtil.startsWith(h, n, "icns");
    }

    // 古い形式: {RGB の種類, 透明度の種類, 辺の長さ}
    private static final Object[][] LEGACY = {
            {"it32", "t8mk", 128}, {"ih32", "h8mk", 48}, {"il32", "l8mk", 32}, {"is32", "s8mk", 16},
    };

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 16 || !looksLikeIcns(d, d.length)) throw new IOException("Not an ICNS file");
        int bestPng = -1, bestPngSize = 0;
        long bestPngPixels = 0;
        // 種類ごとの項目の位置と長さ
        final java.util.Map<String, int[]> entries = new java.util.HashMap<String, int[]>();
        int p = 8;
        final int end = (int) Math.min(d.length, FormatUtil.u32be(d, 4));
        while (p + 8 <= end) {
            final String type = new String(d, p, 4, StandardCharsets.ISO_8859_1);
            final long length = FormatUtil.u32be(d, p + 4);
            if (length < 8 || p + length > end) break;
            final int body = p + 8;
            final int size = (int) length - 8;
            entries.put(type, new int[]{body, size});
            if (size >= 24 && (d[body] & 0xFF) == 0x89 && d[body + 1] == 'P') {
                // PNG の IHDR から大きさを読む
                final long pixels = FormatUtil.u32be(d, body + 16) * FormatUtil.u32be(d, body + 20);
                if (pixels > bestPngPixels) {
                    bestPngPixels = pixels;
                    bestPng = body;
                    bestPngSize = size;
                }
            }
            p += (int) length;
        }
        if (bestPng >= 0) {
            final BufferedImage image = ImageBytes.read(java.util.Arrays.copyOfRange(d, bestPng, bestPng + bestPngSize));
            if (image == null) throw new IOException("Invalid PNG in ICNS");
            FormatUtil.checkSize(image.getWidth(), image.getHeight());
            return FormatUtil.still(image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
        }
        for (Object[] legacy : LEGACY) {
            final int[] rgb = entries.get((String) legacy[0]);
            if (rgb == null) continue;
            final int side = (Integer) legacy[2];
            final int count = side * side;
            int start = rgb[0];
            // 128 x 128 の RGB は、先頭に 4 バイトの 0 がある
            if (side == 128 && rgb[1] >= 4 && d[start] == 0 && d[start + 1] == 0 && d[start + 2] == 0 && d[start + 3] == 0) start += 4;
            final byte[][] channels = new byte[3][count];
            int q = start;
            final int limit = rgb[0] + rgb[1];
            for (int c = 0; c < 3; c++) {
                int out = 0;
                while (out < count) {
                    if (q >= limit) throw new IOException("Truncated ICNS");
                    final int b = d[q++] & 0xFF;
                    if (b < 128) {
                        final int n = b + 1;
                        if (q + n > limit || out + n > count) throw new IOException("Invalid ICNS run");
                        System.arraycopy(d, q, channels[c], out, n);
                        q += n;
                        out += n;
                    } else {
                        final int n = b - 125;
                        if (q >= limit || out + n > count) throw new IOException("Invalid ICNS run");
                        final byte value = d[q++];
                        for (int i = 0; i < n; i++) channels[c][out++] = value;
                    }
                }
            }
            final int[] mask = entries.get((String) legacy[1]);
            final int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                final int a = mask != null && mask[1] >= count ? d[mask[0] + i] & 0xFF : 255;
                result[i] = FormatUtil.argb(a, channels[0][i], channels[1][i], channels[2][i]);
            }
            return FormatUtil.still(side, side, result);
        }
        throw new IOException("ICNS has no readable image");
    }
}
