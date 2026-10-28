package marumasa.marumasa_sign.core.pdf.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * 展開したデータを 64 KB ずつの塊にためて、最後に 1 度だけ 1 つの配列にする。
 * ByteArrayOutputStream は大きくなるたびに 2 倍の配列へ写すので、上限の近くまで展開すると
 * 上限の 3 倍ほどのメモリを一度に使い、連続した大きな領域も要る (Java 8 では足りなくなりやすい)。
 */
final class ChunkedOutput {

    private static final int CHUNK = 65536;
    private final List<byte[]> full = new ArrayList<>();
    private byte[] current = new byte[CHUNK];
    private int used;
    private long size;

    long size() {
        return size;
    }

    void write(byte[] data, int offset, int length) {
        while (length > 0) {
            if (used == CHUNK) {
                full.add(current);
                current = new byte[CHUNK];
                used = 0;
            }
            final int n = Math.min(length, CHUNK - used);
            System.arraycopy(data, offset, current, used, n);
            used += n;
            offset += n;
            length -= n;
            size += n;
        }
    }

    void write(int value) {
        if (used == CHUNK) {
            full.add(current);
            current = new byte[CHUNK];
            used = 0;
        }
        current[used++] = (byte) value;
        size++;
    }

    byte[] toByteArray() {
        final byte[] out = new byte[(int) size];
        int p = 0;
        for (byte[] chunk : full) {
            System.arraycopy(chunk, 0, out, p, CHUNK);
            p += CHUNK;
        }
        System.arraycopy(current, 0, out, p, used);
        return out;
    }
}
