package marumasa.marumasa_sign.core.pdf.filter;

import java.io.IOException;

/**
 * LZWDecode。符号は 9 から 12 ビット、256 が表の初期化、257 が終わり。
 * 表の文字列は「前の符号 + 最後の 1 バイト」の形で持ち、展開のたびに逆にたどる (表の大きさは 4096 で固定)。
 */
final class Lzw {

    private static final int CLEAR = 256;
    private static final int END = 257;

    private Lzw() {
    }

    static byte[] decode(byte[] data, boolean earlyChange, int limit) throws IOException {
        final int[] prefix = new int[4096];
        final byte[] suffix = new byte[4096];
        final int[] length = new int[4096];
        for (int i = 0; i < 256; i++) {
            prefix[i] = -1;
            suffix[i] = (byte) i;
            length[i] = 1;
        }
        final ChunkedOutput out = new ChunkedOutput();
        final byte[] buffer = new byte[4096];
        int next = 258;
        int bits = 9;
        int previous = -1;
        long bitBuffer = 0;
        int bitCount = 0;
        int position = 0;
        while (true) {
            while (bitCount < bits && position < data.length) {
                bitBuffer = (bitBuffer << 8) | (data[position++] & 0xFF);
                bitCount += 8;
            }
            if (bitCount < bits) break;
            final int code = (int) ((bitBuffer >>> (bitCount - bits)) & ((1 << bits) - 1));
            bitCount -= bits;
            if (code == CLEAR) {
                next = 258;
                bits = 9;
                previous = -1;
                continue;
            }
            if (code == END) break;
            int emit;
            if (previous < 0) {
                if (code >= 256) {
                    // 初期化の直後は 1 バイトの符号しか来ないはず。壊れたデータなので終える
                    break;
                }
                emit = code;
            } else if (code < next) {
                emit = code;
            } else if (code == next) {
                // まだ表にない符号 (KwKwK の場合): 前の文字列 + その先頭
                emit = -1;
            } else {
                break;
            }
            // 文字列を書き出す
            final int source = emit >= 0 ? emit : previous;
            final int n = length[source];
            if (out.size() + (long) n + 1 > limit) throw new IOException("Decoded data is too large");
            int c = source;
            for (int i = n - 1; i >= 0; i--) {
                buffer[i] = suffix[c];
                c = prefix[c];
            }
            out.write(buffer, 0, n);
            final byte first = buffer[0];
            if (emit < 0) out.write(first);
            // 表に足す
            if (previous >= 0 && next < 4096) {
                prefix[next] = previous;
                suffix[next] = emit >= 0 ? first : buffer[0];
                length[next] = length[previous] + 1;
                next++;
            }
            previous = emit >= 0 ? emit : next - 1;
            final int threshold = earlyChange ? next + 1 : next;
            if (threshold >= 4096) {
                bits = 12;
            } else if (threshold >= 2048) {
                bits = 12;
            } else if (threshold >= 1024) {
                bits = 11;
            } else if (threshold >= 512) {
                bits = 10;
            } else {
                bits = 9;
            }
        }
        return out.toByteArray();
    }
}
