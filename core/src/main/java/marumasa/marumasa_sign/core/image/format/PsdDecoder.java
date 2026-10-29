package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * Photoshop (PSD と PSB)。レイヤーではなく、保存時に添えられた「統合した画像」を読む。
 * ビットマップ・グレー・色の一覧・RGB・CMYK の、8 ビットと 16 ビット。
 */
final class PsdDecoder {

    private PsdDecoder() {
    }

    static boolean looksLikePsd(byte[] h, int n) {
        return FormatUtil.startsWith(h, n, "8BPS");
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 30 || !looksLikePsd(d, d.length)) throw new IOException("Not a PSD file");
        final int version = FormatUtil.u16be(d, 4);
        if (version != 1 && version != 2) throw new IOException("Unsupported PSD version");
        final int channels = FormatUtil.u16be(d, 12);
        final long height = FormatUtil.u32be(d, 14);
        final long width = FormatUtil.u32be(d, 18);
        final int depth = FormatUtil.u16be(d, 22);
        final int mode = FormatUtil.u16be(d, 24);
        FormatUtil.checkSize(width, height);
        if (channels < 1 || channels > 56) throw new IOException("Invalid PSD channels");
        if (depth != 1 && depth != 8 && depth != 16 && depth != 32) throw new IOException("Unsupported PSD depth: " + depth);
        if (mode != 0 && mode != 1 && mode != 2 && mode != 3 && mode != 4) throw new IOException("Unsupported PSD color mode: " + mode);
        final int w = (int) width, h = (int) height;

        int p = 26;
        // 色の一覧 (インデックスカラーのとき 768 バイト)
        final long modeLength = FormatUtil.u32be(d, p);
        p += 4;
        int[] palette = null;
        if (mode == 2 && modeLength >= 768) {
            palette = new int[256];
            for (int i = 0; i < 256; i++) palette[i] = FormatUtil.argb(255, d[p + i], d[p + 256 + i], d[p + 512 + i]);
        }
        // 長さの欄は 32 ビット (PSB のレイヤーは 64 ビット) なので、long で足して範囲を確かめる (int にすると桁あふれで戻ってしまう)
        long at = p + modeLength;
        // 画像リソースとレイヤーの情報は読み飛ばす
        if (at + 4 > d.length) throw new IOException("PSD has no merged image");
        at += 4 + FormatUtil.u32be(d, (int) at);
        if (at + (version == 2 ? 8 : 4) > d.length) throw new IOException("PSD has no merged image");
        final long layerLength = version == 2 ? FormatUtil.u32be(d, (int) at) << 32 | FormatUtil.u32be(d, (int) at + 4) : FormatUtil.u32be(d, (int) at);
        at += (version == 2 ? 8 : 4) + layerLength;
        if (layerLength < 0 || at + 2 > d.length) throw new IOException("PSD has no merged image");
        p = (int) at;

        final int compression = FormatUtil.u16be(d, p);
        p += 2;
        final int bytesPerSample = depth == 1 ? 0 : depth / 8;
        final int rowBytes = depth == 1 ? (w + 7) / 8 : w * bytesPerSample;
        // 使うチャンネルの数: 色 + 透明度 (あれば)
        final int colorChannels = mode == 3 ? 3 : (mode == 4 ? 4 : 1);
        final int used = Math.min(channels, colorChannels + (mode == 4 || channels > colorChannels ? 1 : 0));
        final long total = (long) channels * h * rowBytes;
        if (total > (1 << 29)) throw new IOException("PSD is too large");

        // チャンネルごとの生のデータ
        final byte[][] planes = new byte[used][];
        if (compression == 0) {
            if (total > d.length - p) throw new IOException("Truncated PSD");
            for (int c = 0; c < used; c++) {
                planes[c] = java.util.Arrays.copyOfRange(d, p + c * h * rowBytes, p + (c + 1) * h * rowBytes);
            }
        } else if (compression == 1) {
            // 行ごとの圧縮後の長さが、全チャンネル分まず並ぶ
            final int lengthSize = version == 2 ? 4 : 2;
            int data = p + channels * h * lengthSize;
            if (data > d.length) throw new IOException("Truncated PSD");
            for (int c = 0; c < channels; c++) {
                final byte[] plane = c < used ? new byte[h * rowBytes] : null;
                for (int y = 0; y < h; y++) {
                    final int lengthAt = p + (c * h + y) * lengthSize;
                    final int length = lengthSize == 4 ? FormatUtil.s32be(d, lengthAt) : FormatUtil.u16be(d, lengthAt);
                    if (length < 0 || (long) data + length > d.length) throw new IOException("Truncated PSD");
                    if (plane != null) packBits(d, data, length, plane, y * rowBytes, rowBytes);
                    data += length;
                }
                if (plane != null) planes[c] = plane;
            }
        } else {
            throw new IOException("Unsupported PSD compression: " + compression);
        }

        final int[] result = new int[w * h];
        final boolean hasAlpha = used > colorChannels;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = y * w + x;
                int a = 255;
                if (hasAlpha) a = value(planes[colorChannels], depth, bytesPerSample, rowBytes, x, y);
                switch (mode) {
                    case 0: {
                        // ビットマップ。1 が黒
                        final int bit = (planes[0][y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                        final int v = bit != 0 ? 0 : 255;
                        result[i] = FormatUtil.argb(a, v, v, v);
                        break;
                    }
                    case 1: {
                        final int v = value(planes[0], depth, bytesPerSample, rowBytes, x, y);
                        result[i] = FormatUtil.argb(a, v, v, v);
                        break;
                    }
                    case 2: {
                        final int index = planes[0][y * rowBytes + x] & 0xFF;
                        result[i] = (palette != null ? palette[index] : FormatUtil.argb(255, index, index, index)) & 0xFFFFFF | a << 24;
                        break;
                    }
                    case 3:
                        result[i] = FormatUtil.argb(a, value(planes[0], depth, bytesPerSample, rowBytes, x, y),
                                value(planes[1], depth, bytesPerSample, rowBytes, x, y), value(planes[2], depth, bytesPerSample, rowBytes, x, y));
                        break;
                    default: {
                        // CMYK は Photoshop では値が反転している (255 がインクなし)
                        final int cc = 255 - value(planes[0], depth, bytesPerSample, rowBytes, x, y);
                        final int mm = 255 - value(planes[1], depth, bytesPerSample, rowBytes, x, y);
                        final int yy = 255 - value(planes[2], depth, bytesPerSample, rowBytes, x, y);
                        final int kk = 255 - value(planes[3], depth, bytesPerSample, rowBytes, x, y);
                        result[i] = FormatUtil.cmyk(cc, mm, yy, kk) & 0xFFFFFF | (hasAlpha ? a << 24 : 0xFF000000);
                        break;
                    }
                }
            }
        }
        return FormatUtil.still(w, h, result);
    }

    // 1 つの値を 0 から 255 にして読む
    private static int value(byte[] plane, int depth, int bytesPerSample, int rowBytes, int x, int y) {
        final int p = y * rowBytes + x * bytesPerSample;
        switch (depth) {
            case 8:
                return plane[p] & 0xFF;
            case 16:
                return FormatUtil.to8(FormatUtil.u16be(plane, p), 65535);
            case 32:
                return FormatUtil.clamp8(Math.round(Float.intBitsToFloat(FormatUtil.s32be(plane, p)) * 255));
            default:
                return ((plane[y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1) != 0 ? 255 : 0;
        }
    }

    private static void packBits(byte[] d, int start, int length, byte[] out, int outStart, int outLength) throws IOException {
        int p = start;
        final int end = start + length;
        int o = outStart;
        final int outEnd = outStart + outLength;
        while (p < end && o < outEnd) {
            final int n = d[p++];
            if (n >= 0) {
                final int count = n + 1;
                if (p + count > end) throw new IOException("Truncated PSD run");
                final int copy = Math.min(count, outEnd - o);
                System.arraycopy(d, p, out, o, copy);
                p += count;
                o += copy;
            } else if (n != -128) {
                final int count = 1 - n;
                if (p >= end) throw new IOException("Truncated PSD run");
                final byte value = d[p++];
                for (int i = 0; i < count && o < outEnd; i++) out[o++] = value;
            }
        }
    }
}
