package marumasa.marumasa_sign.core.pdf.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ASCIIHexDecode、ASCII85Decode、RunLengthDecode。
 */
final class AsciiFilters {

    private AsciiFilters() {
    }

    static byte[] hex(byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 1);
        int high = -1;
        for (byte b : data) {
            if (b == '>') break;
            final int value = b >= '0' && b <= '9' ? b - '0' : b >= 'a' && b <= 'f' ? b - 'a' + 10 : b >= 'A' && b <= 'F' ? b - 'A' + 10 : -1;
            if (value < 0) continue;
            if (high < 0) {
                high = value;
            } else {
                out.write(high * 16 + value);
                high = -1;
            }
        }
        if (high >= 0) out.write(high * 16);
        return out.toByteArray();
    }

    static byte[] base85(byte[] data, int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        final int[] group = new int[5];
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            final int b = data[i] & 0xFF;
            if (b == '~') break;
            if (b == 'z' && count == 0) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0);
            } else if (b >= '!' && b <= 'u') {
                group[count++] = b - '!';
                if (count == 5) {
                    write(out, group, 4);
                    count = 0;
                }
            }
            // 空白などは飛ばす
            if (out.size() > limit) throw new IOException("Decoded data is too large");
        }
        // 端数: 足りない分を 'u' で埋めて、得られたバイトのうち count - 1 個を使う
        if (count >= 2) {
            for (int i = count; i < 5; i++) group[i] = 84;
            write(out, group, count - 1);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, int[] group, int bytes) {
        long value = 0;
        for (int i = 0; i < 5; i++) value = value * 85 + group[i];
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (24 - 8 * i)) & 0xFF);
    }

    static byte[] runLength(byte[] data, int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
        int i = 0;
        while (i < data.length) {
            final int n = data[i++] & 0xFF;
            if (n == 128) break;
            if (n < 128) {
                final int count = Math.min(n + 1, data.length - i);
                out.write(data, i, count);
                i += count;
            } else {
                if (i >= data.length) break;
                final int value = data[i++];
                for (int k = 0; k < 257 - n; k++) out.write(value);
            }
            if (out.size() > limit) throw new IOException("Decoded data is too large");
        }
        return out.toByteArray();
    }
}
