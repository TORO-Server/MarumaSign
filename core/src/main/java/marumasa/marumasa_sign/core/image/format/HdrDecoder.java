package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * Radiance HDR (RGBE)。明るさは 1.0 で頭打ちにして sRGB にする。
 */
final class HdrDecoder {

    private HdrDecoder() {
    }

    static boolean looksLikeHdr(byte[] h, int n) {
        return FormatUtil.startsWith(h, n, "#?RADIANCE") || FormatUtil.startsWith(h, n, "#?RGBE");
    }

    static DecodedImage decode(byte[] d) throws IOException {
        int p = 0;
        // 見出しは空行で終わる。そのあとに「-Y 高さ +X 幅」の行
        while (true) {
            final int lineEnd = indexOf(d, p, (byte) '\n');
            if (lineEnd < 0) throw new IOException("Truncated HDR");
            final boolean blank = lineEnd == p;
            p = lineEnd + 1;
            if (blank) break;
        }
        final int resolutionEnd = indexOf(d, p, (byte) '\n');
        if (resolutionEnd < 0) throw new IOException("Truncated HDR");
        final String[] fields = new String(d, p, resolutionEnd - p, java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+");
        p = resolutionEnd + 1;
        if (fields.length != 4) throw new IOException("Invalid HDR resolution");
        final boolean rowsFirst = fields[0].endsWith("Y");
        final int rows, width;
        try {
            rows = Integer.parseInt(fields[1]);
            width = Integer.parseInt(fields[3]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HDR resolution", e);
        }
        if (!rowsFirst) throw new IOException("Unsupported HDR orientation");
        final int height = rows;
        FormatUtil.checkSize(width, height);
        final boolean flipY = fields[0].charAt(0) == '+';
        final boolean flipX = fields[2].charAt(0) == '-';

        final int[] result = new int[width * height];
        final byte[] scan = new byte[width * 4];
        for (int y = 0; y < height; y++) {
            p = readScanline(d, p, scan, width);
            final int row = (flipY ? height - 1 - y : y) * width;
            for (int x = 0; x < width; x++) {
                final int e = scan[x * 4 + 3] & 0xFF;
                final int target = row + (flipX ? width - 1 - x : x);
                if (e == 0) {
                    result[target] = 0xFF000000;
                    continue;
                }
                final double scale = Math.scalb(1.0, e - 136);
                result[target] = FormatUtil.argb(255,
                        FormatUtil.srgb(((scan[x * 4] & 0xFF) + 0.5) * scale),
                        FormatUtil.srgb(((scan[x * 4 + 1] & 0xFF) + 0.5) * scale),
                        FormatUtil.srgb(((scan[x * 4 + 2] & 0xFF) + 0.5) * scale));
            }
        }
        return FormatUtil.still(width, height, result);
    }

    private static int indexOf(byte[] d, int from, byte value) {
        for (int i = from; i < d.length; i++) {
            if (d[i] == value) return i;
        }
        return -1;
    }

    // 1 行を読み、次の位置を返す
    private static int readScanline(byte[] d, int p, byte[] scan, int width) throws IOException {
        if (p + 4 > d.length) throw new IOException("Truncated HDR");
        if (width >= 8 && width < 32768 && d[p] == 2 && d[p + 1] == 2 && (d[p + 2] & 0x80) == 0) {
            if (((d[p + 2] & 0xFF) << 8 | (d[p + 3] & 0xFF)) != width) throw new IOException("Invalid HDR scanline");
            p += 4;
            // 新しい RLE。赤・緑・青・指数が別々に並ぶ
            for (int channel = 0; channel < 4; channel++) {
                int x = 0;
                while (x < width) {
                    if (p >= d.length) throw new IOException("Truncated HDR");
                    int count = d[p++] & 0xFF;
                    if (count > 128) {
                        count -= 128;
                        if (x + count > width || p >= d.length) throw new IOException("Invalid HDR run");
                        final byte value = d[p++];
                        for (int i = 0; i < count; i++) scan[(x++) * 4 + channel] = value;
                    } else {
                        if (count == 0 || x + count > width || p + count > d.length) throw new IOException("Invalid HDR run");
                        for (int i = 0; i < count; i++) scan[(x++) * 4 + channel] = d[p++];
                    }
                }
            }
            return p;
        }
        // 圧縮なし、または古い RLE (1, 1, 1, 回数 は直前の色の繰り返し)
        int x = 0;
        int shift = 0;
        while (x < width) {
            if (p + 4 > d.length) throw new IOException("Truncated HDR");
            if (d[p] == 1 && d[p + 1] == 1 && d[p + 2] == 1) {
                final int count = (d[p + 3] & 0xFF) << shift;
                if (x == 0 || x + count > width) throw new IOException("Invalid HDR run");
                for (int i = 0; i < count; i++, x++) System.arraycopy(scan, (x - 1) * 4, scan, x * 4, 4);
                shift += 8;
            } else {
                System.arraycopy(d, p, scan, x * 4, 4);
                x++;
                shift = 0;
            }
            p += 4;
        }
        return p;
    }
}
