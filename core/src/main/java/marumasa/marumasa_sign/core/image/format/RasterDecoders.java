package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 構造が単純な、古い形式や小さな形式のデコーダ: SGI、Sun Raster、WBMP、farbfeld、FITS。
 */
final class RasterDecoders {

    private RasterDecoders() {
    }

    // ---------- SGI (.sgi .rgb .rgba .bw) ----------

    static boolean looksLikeSgi(byte[] h, int n) {
        return n >= 12 && (h[0] & 0xFF) == 0x01 && (h[1] & 0xFF) == 0xDA && (h[2] == 0 || h[2] == 1) && (h[3] == 1 || h[3] == 2);
    }

    static DecodedImage decodeSgi(byte[] d) throws IOException {
        if (d.length < 512 || !looksLikeSgi(d, d.length)) throw new IOException("Not an SGI image");
        final boolean rle = d[2] == 1;
        final int bytes = d[3];
        final int width = FormatUtil.u16be(d, 6);
        final int height = FormatUtil.u16be(d, 8);
        final int channels = FormatUtil.u16be(d, 10);
        FormatUtil.checkSize(width, height);
        if (channels < 1 || channels > 4) throw new IOException("Unsupported SGI channels");

        // チャンネルごと・行ごとの値 (0 から 65535)。行は下から上へ並ぶ
        final int[][] plane = new int[channels][width * height];
        final int[] row = new int[width];
        for (int c = 0; c < channels; c++) {
            for (int y = 0; y < height; y++) {
                if (rle) {
                    final int table = 512 + (y + c * height) * 4;
                    final int offset = FormatUtil.s32be(d, table);
                    readSgiRle(d, offset, row, width, bytes);
                } else {
                    final long offset = 512L + ((long) c * height + y) * width * bytes;
                    if (offset + (long) width * bytes > d.length) throw new IOException("Truncated SGI");
                    for (int x = 0; x < width; x++) {
                        row[x] = bytes == 1 ? d[(int) offset + x] & 0xFF : FormatUtil.u16be(d, (int) offset + x * 2);
                    }
                }
                System.arraycopy(row, 0, plane[c], (height - 1 - y) * width, width);
            }
        }
        final long max = bytes == 1 ? 255 : 65535;
        final int[] result = new int[width * height];
        for (int i = 0; i < result.length; i++) {
            final int v0 = FormatUtil.to8(plane[0][i], max);
            if (channels >= 3) {
                result[i] = FormatUtil.argb(channels == 4 ? FormatUtil.to8(plane[3][i], max) : 255, v0,
                        FormatUtil.to8(plane[1][i], max), FormatUtil.to8(plane[2][i], max));
            } else {
                result[i] = FormatUtil.argb(channels == 2 ? FormatUtil.to8(plane[1][i], max) : 255, v0, v0, v0);
            }
        }
        return FormatUtil.still(width, height, result);
    }

    private static void readSgiRle(byte[] d, int offset, int[] row, int width, int bytes) throws IOException {
        int p = offset;
        int x = 0;
        while (x < width) {
            if (p + bytes > d.length) throw new IOException("Truncated SGI");
            final int control = bytes == 1 ? d[p] & 0xFF : FormatUtil.u16be(d, p);
            p += bytes;
            final int count = control & 0x7F;
            if (count == 0) break;
            if (x + count > width) throw new IOException("Invalid SGI run");
            if ((control & 0x80) != 0) {
                if (p + (long) count * bytes > d.length) throw new IOException("Truncated SGI");
                for (int i = 0; i < count; i++, p += bytes) row[x++] = bytes == 1 ? d[p] & 0xFF : FormatUtil.u16be(d, p);
            } else {
                if (p + bytes > d.length) throw new IOException("Truncated SGI");
                final int value = bytes == 1 ? d[p] & 0xFF : FormatUtil.u16be(d, p);
                p += bytes;
                for (int i = 0; i < count; i++) row[x++] = value;
            }
        }
        if (x < width) java.util.Arrays.fill(row, x, width, 0);
    }

    // ---------- Sun Raster (.ras .sun) ----------

    static boolean looksLikeSun(byte[] h, int n) {
        return n >= 32 && (h[0] & 0xFF) == 0x59 && (h[1] & 0xFF) == 0xA6 && (h[2] & 0xFF) == 0x6A && (h[3] & 0xFF) == 0x95;
    }

    static DecodedImage decodeSun(byte[] d) throws IOException {
        if (!looksLikeSun(d, d.length)) throw new IOException("Not a Sun Raster image");
        final long width = FormatUtil.u32be(d, 4);
        final long height = FormatUtil.u32be(d, 8);
        final int depth = FormatUtil.s32be(d, 12);
        final int type = FormatUtil.s32be(d, 20);
        final int mapType = FormatUtil.s32be(d, 24);
        final int mapLength = FormatUtil.s32be(d, 28);
        FormatUtil.checkSize(width, height);
        if (depth != 1 && depth != 8 && depth != 24 && depth != 32) throw new IOException("Unsupported Sun Raster depth");
        if (type > 3) throw new IOException("Unsupported Sun Raster type");
        int p = 32;
        int[] palette = null;
        if (mapType == 1) {
            final int n = mapLength / 3;
            palette = new int[Math.max(n, 256)];
            for (int i = 0; i < n; i++) {
                palette[i] = FormatUtil.argb(255, d[p + i], d[p + n + i], d[p + 2 * n + i]);
            }
        }
        if (mapType != 0) p += mapLength;

        final int w = (int) width, h = (int) height;
        final int rowBytes = (int) (((long) w * depth + 15) / 16 * 2);
        final byte[] data = new byte[FormatUtil.checkedProduct(rowBytes, h, 1 << 28)];
        if (type == 2) {
            int out = 0;
            while (out < data.length) {
                if (p >= d.length) throw new IOException("Truncated Sun Raster");
                final int b = d[p++] & 0xFF;
                if (b != 0x80) {
                    data[out++] = (byte) b;
                    continue;
                }
                final int count = d[p++] & 0xFF;
                if (count == 0) {
                    data[out++] = (byte) 0x80;
                } else {
                    final byte value = d[p++];
                    for (int i = 0; i <= count && out < data.length; i++) data[out++] = value;
                }
            }
        } else {
            if (data.length > d.length - p) throw new IOException("Truncated Sun Raster");
            System.arraycopy(d, p, data, 0, data.length);
        }

        final int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            final int row = y * rowBytes;
            for (int x = 0; x < w; x++) {
                final int color;
                if (depth == 1) {
                    final int bit = (data[row + (x >> 3)] >> (7 - (x & 7))) & 1;
                    // 1 が黒
                    color = palette != null ? palette[bit] : (bit != 0 ? 0xFF000000 : 0xFFFFFFFF);
                } else if (depth == 8) {
                    final int v = data[row + x] & 0xFF;
                    color = palette != null ? palette[v] : FormatUtil.argb(255, v, v, v);
                } else if (depth == 24) {
                    final int o = row + x * 3;
                    color = type == 3 ? FormatUtil.argb(255, data[o], data[o + 1], data[o + 2])
                            : FormatUtil.argb(255, data[o + 2], data[o + 1], data[o]);
                } else {
                    final int o = row + x * 4;
                    color = type == 3 ? FormatUtil.argb(255, data[o + 1], data[o + 2], data[o + 3])
                            : FormatUtil.argb(255, data[o + 3], data[o + 2], data[o + 1]);
                }
                result[y * w + x] = color;
            }
        }
        return FormatUtil.still(w, h, result);
    }

    // ---------- WBMP (ワイヤレス・ビットマップ。拡張子で判断する) ----------

    static DecodedImage decodeWbmp(byte[] d) throws IOException {
        if (d.length < 4 || d[0] != 0 || d[1] != 0) throw new IOException("Not a WBMP image");
        int p = 2;
        long width = 0, height = 0;
        int b;
        do {
            b = d[p++] & 0xFF;
            width = width << 7 | (b & 0x7F);
        } while ((b & 0x80) != 0 && p < d.length && width < (1 << 20));
        do {
            b = d[p++] & 0xFF;
            height = height << 7 | (b & 0x7F);
        } while ((b & 0x80) != 0 && p < d.length && height < (1 << 20));
        FormatUtil.checkSize(width, height);
        final int w = (int) width, h = (int) height;
        final int rowBytes = (w + 7) / 8;
        if ((long) rowBytes * h > d.length - p) throw new IOException("Truncated WBMP");
        final int[] result = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 1 が白
                result[y * w + x] = ((d[p + y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1) != 0 ? 0xFFFFFFFF : 0xFF000000;
            }
        }
        return FormatUtil.still(w, h, result);
    }

    // ---------- farbfeld ----------

    static DecodedImage decodeFarbfeld(byte[] d) throws IOException {
        if (d.length < 16 || !FormatUtil.startsWith(d, d.length, "farbfeld")) throw new IOException("Not a farbfeld image");
        final long width = FormatUtil.u32be(d, 8);
        final long height = FormatUtil.u32be(d, 12);
        FormatUtil.checkSize(width, height);
        final int w = (int) width, h = (int) height;
        if ((long) w * h * 8 > d.length - 16) throw new IOException("Truncated farbfeld");
        final int[] result = new int[w * h];
        for (int i = 0; i < result.length; i++) {
            final int p = 16 + i * 8;
            // 16 ビットの上位 8 ビットで足りる (四捨五入のため 255 / 65535 を掛ける)
            result[i] = FormatUtil.argb(FormatUtil.to8(FormatUtil.u16be(d, p + 6), 65535), FormatUtil.to8(FormatUtil.u16be(d, p), 65535),
                    FormatUtil.to8(FormatUtil.u16be(d, p + 2), 65535), FormatUtil.to8(FormatUtil.u16be(d, p + 4), 65535));
        }
        return FormatUtil.still(w, h, result);
    }

    // ---------- FITS (天文学の画像。明るさを最小から最大へ引き伸ばして灰色で表す) ----------

    static boolean looksLikeFits(byte[] h, int n) {
        return FormatUtil.startsWith(h, n, "SIMPLE  =");
    }

    static DecodedImage decodeFits(byte[] d) throws IOException {
        if (!looksLikeFits(d, d.length)) throw new IOException("Not a FITS image");
        int bitpix = 0, naxis = 0;
        int width = 0, height = 0, planes = 1;
        double scale = 1, zero = 0;
        int p = 0;
        while (true) {
            if (p + 80 > d.length) throw new IOException("Truncated FITS");
            final String card = new String(d, p, 80, StandardCharsets.US_ASCII);
            p += 80;
            final String key = card.substring(0, 8).trim();
            if (key.equals("END")) break;
            if (card.length() < 10 || card.charAt(8) != '=') continue;
            String value = card.substring(10);
            final int comment = value.indexOf('/');
            if (comment >= 0) value = value.substring(0, comment);
            value = value.trim();
            try {
                switch (key) {
                    case "BITPIX": bitpix = Integer.parseInt(value); break;
                    case "NAXIS": naxis = Integer.parseInt(value); break;
                    case "NAXIS1": width = Integer.parseInt(value); break;
                    case "NAXIS2": height = Integer.parseInt(value); break;
                    case "NAXIS3": planes = Integer.parseInt(value); break;
                    case "BSCALE": scale = Double.parseDouble(value); break;
                    case "BZERO": zero = Double.parseDouble(value); break;
                    default: break;
                }
            } catch (NumberFormatException e) {
                throw new IOException("Invalid FITS header", e);
            }
        }
        // データは 2880 バイトの区切りから
        p = (p + 2879) / 2880 * 2880;
        if (naxis < 2 || naxis > 3) throw new IOException("Unsupported FITS dimensions");
        FormatUtil.checkSize(width, height);
        // 3 面は RGB、4 面は RGB と透明度
        final boolean color = naxis == 3 && (planes == 3 || planes == 4);
        final boolean alpha = naxis == 3 && planes == 4;
        if (naxis == 3 && planes != 3 && planes != 4 && planes != 1) throw new IOException("Unsupported FITS planes");
        final int bytes = Math.abs(bitpix) / 8;
        if (bitpix != 8 && bitpix != 16 && bitpix != 32 && bitpix != -32 && bitpix != -64) throw new IOException("Unsupported FITS BITPIX");
        final int count = width * height * (alpha ? 4 : (color ? 3 : 1));
        if ((long) count * bytes > d.length - p) throw new IOException("Truncated FITS");
        final double[] values = new double[count];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            final int o = p + i * bytes;
            final double raw;
            switch (bitpix) {
                case 8: raw = d[o] & 0xFF; break;
                case 16: raw = (short) FormatUtil.u16be(d, o); break;
                case 32: raw = FormatUtil.s32be(d, o); break;
                case -32: raw = Float.intBitsToFloat(FormatUtil.s32be(d, o)); break;
                default: raw = Double.longBitsToDouble((long) FormatUtil.s32be(d, o) << 32 | FormatUtil.u32be(d, o + 4)); break;
            }
            final double v = raw * scale + zero;
            values[i] = v;
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        if (min > max) {
            min = 0;
            max = 1;
        }
        final double range = max > min ? max - min : 1;
        final int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            // FITS は左下が原点
            final int row = (height - 1 - y) * width;
            for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                final int r = level(values[i], min, range);
                final int a = alpha ? level(values[i + 3 * width * height], min, range) : 255;
                result[row + x] = color ? FormatUtil.argb(a, r, level(values[i + width * height], min, range),
                        level(values[i + 2 * width * height], min, range)) : FormatUtil.argb(255, r, r, r);
            }
        }
        return FormatUtil.still(width, height, result);
    }

    private static int level(double value, double min, double range) {
        if (Double.isNaN(value)) return 0;
        return FormatUtil.clamp8((int) Math.round((value - min) / range * 255));
    }
}
