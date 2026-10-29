package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * OpenEXR (スキャンライン形式)。圧縮は、なし・RLE・ZIPS・ZIP・PXR24 に対応する。
 * 明るさは線形として、1.0 で頭打ちにして sRGB にする。PIZ・B44・DWA やタイル形式は読めない (FFmpeg があればそちらに任せる)。
 */
final class ExrDecoder {

    private static final class Channel {
        String name;
        int type;
        int xSampling, ySampling;
        int offset;
    }

    // 1 ピクセルのバイト数の上限 (小数 32 ビットのチャンネルなら 128 個)
    private static final int MAX_PIXEL_BYTES = 512;

    private ExrDecoder() {
    }

    static boolean looksLikeExr(byte[] h, int n) {
        return n >= 4 && (h[0] & 0xFF) == 0x76 && (h[1] & 0xFF) == 0x2F && h[2] == 0x31 && h[3] == 0x01;
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 8 || !looksLikeExr(d, d.length)) throw new IOException("Not an EXR image");
        final int version = FormatUtil.s32le(d, 4);
        if ((version & 0x200) != 0) throw new IOException("Unsupported EXR: tiled");
        if ((version & 0x1800) != 0) throw new IOException("Unsupported EXR: deep or multipart");

        int p = 8;
        final List<Channel> channels = new ArrayList<Channel>();
        int compression = -1;
        int xMin = 0, yMin = 0, xMax = -1, yMax = -1;
        while (true) {
            if (p >= d.length) throw new IOException("Truncated EXR header");
            if (d[p] == 0) {
                p++;
                break;
            }
            final int nameEnd = indexOf(d, p);
            final String name = new String(d, p, nameEnd - p, java.nio.charset.StandardCharsets.ISO_8859_1);
            final int typeEnd = indexOf(d, nameEnd + 1);
            final int size = FormatUtil.s32le(d, typeEnd + 1);
            final int body = typeEnd + 5;
            if (size < 0 || (long) body + size > d.length) throw new IOException("Invalid EXR attribute");
            if (name.equals("channels")) {
                int q = body;
                while (q < body + size && d[q] != 0) {
                    final int end = indexOf(d, q);
                    final Channel c = new Channel();
                    c.name = new String(d, q, end - q, java.nio.charset.StandardCharsets.ISO_8859_1);
                    c.type = FormatUtil.s32le(d, end + 1);
                    c.xSampling = FormatUtil.s32le(d, end + 9);
                    c.ySampling = FormatUtil.s32le(d, end + 13);
                    channels.add(c);
                    q = end + 17;
                }
            } else if (name.equals("compression")) {
                compression = d[body] & 0xFF;
            } else if (name.equals("dataWindow")) {
                xMin = FormatUtil.s32le(d, body);
                yMin = FormatUtil.s32le(d, body + 4);
                xMax = FormatUtil.s32le(d, body + 8);
                yMax = FormatUtil.s32le(d, body + 12);
            }
            p = body + size;
        }
        final long width = (long) xMax - xMin + 1, height = (long) yMax - yMin + 1;
        FormatUtil.checkSize(width, height);
        if (channels.isEmpty()) throw new IOException("EXR has no channels");
        if (compression != 0 && compression != 1 && compression != 2 && compression != 3 && compression != 5) {
            throw new IOException("Unsupported EXR compression: " + compression);
        }
        final int w = (int) width, h = (int) height;
        for (Channel c : channels) {
            if (c.xSampling != 1 || c.ySampling != 1) throw new IOException("Unsupported EXR sub-sampling");
            if (c.type < 0 || c.type > 2) throw new IOException("Unsupported EXR pixel type");
        }

        // 1 行分の中の、チャンネルごとの位置。1 ピクセルの大きさに上限を設ける
        // (チャンネルをたくさん並べた小さなファイルで、展開用に大きな領域を確保させないように)
        int pixelBytes = 0;
        for (Channel c : channels) {
            pixelBytes += c.type == 1 ? 2 : 4;
            if (pixelBytes > MAX_PIXEL_BYTES) throw new IOException("EXR has too many channels");
        }
        int lineBytes = 0;
        for (Channel c : channels) {
            c.offset = lineBytes;
            lineBytes += w * (c.type == 1 ? 2 : 4);
        }
        final int linesPerBlock = compression == 3 || compression == 5 ? 16 : (compression == 2 ? 1 : 1);
        final int blocks = (h + linesPerBlock - 1) / linesPerBlock;
        if ((long) p + 8L * blocks > d.length) throw new IOException("Truncated EXR offsets");

        final int[] wanted = {find(channels, "R"), find(channels, "G"), find(channels, "B"), find(channels, "A")};
        final int gray = wanted[0] < 0 && wanted[1] < 0 && wanted[2] < 0 ? find(channels, "Y") : -1;
        if (gray < 0 && (wanted[0] < 0 || wanted[1] < 0 || wanted[2] < 0)) throw new IOException("EXR has no RGB channels");

        // 塊を展開するたびに、その行を色にして結果に書く (チャンネルごとの小数の領域は作らない)
        final int[] result = new int[w * h];
        final Channel red = channels.get(gray >= 0 ? gray : wanted[0]);
        final Channel green = gray >= 0 ? red : channels.get(wanted[1]);
        final Channel blue = gray >= 0 ? red : channels.get(wanted[2]);
        final Channel alpha = wanted[3] >= 0 ? channels.get(wanted[3]) : null;
        // 塊のない行は、透明度がなければ黒にする
        if (alpha == null) java.util.Arrays.fill(result, 0xFF000000);
        for (int block = 0; block < blocks; block++) {
            final long offset = FormatUtil.u32le(d, p + block * 8) | FormatUtil.u32le(d, p + block * 8 + 4) << 32;
            if (offset < 0 || offset + 8 > d.length) throw new IOException("Invalid EXR chunk offset");
            final int y = FormatUtil.s32le(d, (int) offset);
            final int size = FormatUtil.s32le(d, (int) offset + 4);
            if (size < 0 || offset + 8 + size > d.length) throw new IOException("Invalid EXR chunk size");
            final int rows = Math.min(linesPerBlock, yMax - y + 1);
            if (y < yMin || rows <= 0) throw new IOException("Invalid EXR chunk line");
            final int expected = lineBytes * rows;
            final byte[] raw = unpack(d, (int) offset + 8, size, expected, compression, channels, w, rows);
            for (int r = 0; r < rows; r++) {
                // 展開したあとの並びは、1 行ごとにチャンネル順
                final int base = r * lineBytes;
                final int row = (y - yMin + r) * w;
                for (int x = 0; x < w; x++) {
                    final float a = alpha == null ? 1f : Math.max(0f, Math.min(1f, sample(raw, base, alpha, x)));
                    float rr = sample(raw, base, red, x), gg = sample(raw, base, green, x), bb = sample(raw, base, blue, x);
                    // 色は透明度を掛けてある (乗算済み) ので、割って元に戻す
                    if (alpha != null && a > 0 && a < 1) {
                        rr /= a;
                        gg /= a;
                        bb /= a;
                    }
                    result[row + x] = FormatUtil.argb(Math.round(a * 255), FormatUtil.srgb(rr), FormatUtil.srgb(gg), FormatUtil.srgb(bb));
                }
            }
        }
        return FormatUtil.still(w, h, result);
    }

    // 展開した 1 行の中の、あるチャンネルの x 番目の値
    private static float sample(byte[] raw, int base, Channel c, int x) {
        final int p = base + c.offset;
        if (c.type == 1) return half(raw, p + x * 2);
        if (c.type == 2) return Float.intBitsToFloat(FormatUtil.s32le(raw, p + x * 4));
        return (float) FormatUtil.u32le(raw, p + x * 4);
    }

    private static int indexOf(byte[] d, int from) throws IOException {
        for (int i = from; i < d.length && i < from + 256; i++) {
            if (d[i] == 0) return i;
        }
        throw new IOException("Invalid EXR header");
    }

    private static int find(List<Channel> channels, String name) {
        for (int i = 0; i < channels.size(); i++) {
            final String n = channels.get(i).name;
            if (n.equals(name)) return i;
        }
        return -1;
    }

    private static float half(byte[] d, int p) {
        final int h = FormatUtil.u16le(d, p);
        final int exp = (h >> 10) & 31, mant = h & 1023;
        final float sign = (h & 0x8000) != 0 ? -1f : 1f;
        if (exp == 0) return sign * mant * (float) Math.pow(2, -24);
        if (exp == 31) return mant == 0 ? sign * Float.POSITIVE_INFINITY : Float.NaN;
        return sign * (1f + mant / 1024f) * (float) Math.pow(2, exp - 15);
    }

    // 1 つの塊を展開して、行ごとにチャンネル順に並んだ (小数のバイト順は下位が先の) データにする
    private static byte[] unpack(byte[] d, int start, int size, int expected, int compression, List<Channel> channels, int width, int rows)
            throws IOException {
        if (compression == 0) {
            if (size < expected) throw new IOException("Truncated EXR data");
            return java.util.Arrays.copyOfRange(d, start, start + expected);
        }
        if (size == expected) {
            // 圧縮しても小さくならなかった塊は、そのまま入っている
            return java.util.Arrays.copyOfRange(d, start, start + expected);
        }
        if (compression == 1) {
            return reorder(rle(d, start, size, expected), expected);
        }
        final byte[] inflated = inflate(d, start, size, expected);
        if (compression == 2 || compression == 3) {
            if (inflated.length != expected) throw new IOException("Truncated EXR deflate data");
            return reorder(inflated, expected);
        }
        return pxr24(inflated, channels, width, rows, expected);
    }

    // 展開した結果を返す (expected までの長さ。PXR24 は 24 ビットに丸めるため、短くなる)
    private static byte[] inflate(byte[] d, int start, int size, int expected) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(d, start, size);
            final byte[] out = new byte[expected];
            int filled = 0;
            while (filled < expected && !inflater.finished()) {
                final int n = inflater.inflate(out, filled, expected - filled);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                filled += n;
            }
            return filled == expected ? out : java.util.Arrays.copyOf(out, filled);
        } catch (DataFormatException e) {
            throw new IOException("Invalid EXR deflate data", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] rle(byte[] d, int start, int size, int expected) throws IOException {
        final byte[] out = new byte[expected];
        int p = start;
        final int end = start + size;
        int o = 0;
        while (p < end && o < expected) {
            final int count = d[p++];
            if (count < 0) {
                final int n = -count;
                if (p + n > end || o + n > expected) throw new IOException("Invalid EXR RLE data");
                System.arraycopy(d, p, out, o, n);
                p += n;
                o += n;
            } else {
                final int n = count + 1;
                if (p >= end || o + n > expected) throw new IOException("Invalid EXR RLE data");
                final byte value = d[p++];
                for (int i = 0; i < n; i++) out[o++] = value;
            }
        }
        if (o < expected) throw new IOException("Truncated EXR RLE data");
        return out;
    }

    // ZIP と RLE の後処理: 差分を足し、前半と後半のバイトを交互に並べ直す
    private static byte[] reorder(byte[] t, int n) {
        for (int i = 1; i < n; i++) t[i] = (byte) (t[i - 1] + t[i] - 128);
        final byte[] out = new byte[n];
        final int half = (n + 1) / 2;
        int a = 0, b = half;
        for (int i = 0; i < n; i += 2) {
            out[i] = t[a++];
            if (i + 1 < n) out[i + 1] = t[b++];
        }
        return out;
    }

    // PXR24: 行ごと・チャンネルごとに、上位のバイトから並べて差分にしてある。24 ビットに丸めた小数
    private static byte[] pxr24(byte[] in, List<Channel> channels, int width, int rows, int expected) throws IOException {
        final byte[] out = new byte[expected];
        int p = 0;
        int o = 0;
        for (int r = 0; r < rows; r++) {
            for (Channel c : channels) {
                if (c.type == 1) {
                    if (p + width * 2 > in.length) throw new IOException("Truncated EXR PXR24 data");
                    int pixel = 0;
                    for (int x = 0; x < width; x++) {
                        pixel += ((in[p + x] & 0xFF) << 8 | (in[p + width + x] & 0xFF)) & 0xFFFF;
                        out[o + x * 2] = (byte) pixel;
                        out[o + x * 2 + 1] = (byte) (pixel >> 8);
                    }
                    p += width * 2;
                    o += width * 2;
                } else if (c.type == 2) {
                    if (p + width * 3 > in.length) throw new IOException("Truncated EXR PXR24 data");
                    int pixel = 0;
                    for (int x = 0; x < width; x++) {
                        pixel += (in[p + x] & 0xFF) << 24 | (in[p + width + x] & 0xFF) << 16 | (in[p + 2 * width + x] & 0xFF) << 8;
                        out[o + x * 4] = (byte) pixel;
                        out[o + x * 4 + 1] = (byte) (pixel >> 8);
                        out[o + x * 4 + 2] = (byte) (pixel >> 16);
                        out[o + x * 4 + 3] = (byte) (pixel >> 24);
                    }
                    p += width * 3;
                    o += width * 4;
                } else {
                    if (p + width * 4 > in.length) throw new IOException("Truncated EXR PXR24 data");
                    int pixel = 0;
                    for (int x = 0; x < width; x++) {
                        pixel += (in[p + x] & 0xFF) << 24 | (in[p + width + x] & 0xFF) << 16 | (in[p + 2 * width + x] & 0xFF) << 8 | (in[p + 3 * width + x] & 0xFF);
                        out[o + x * 4] = (byte) pixel;
                        out[o + x * 4 + 1] = (byte) (pixel >> 8);
                        out[o + x * 4 + 2] = (byte) (pixel >> 16);
                        out[o + x * 4 + 3] = (byte) (pixel >> 24);
                    }
                    p += width * 4;
                    o += width * 4;
                }
            }
        }
        return out;
    }
}
