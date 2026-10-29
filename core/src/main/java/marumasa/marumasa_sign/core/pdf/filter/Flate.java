package marumasa.marumasa_sign.core.pdf.filter;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * FlateDecode (zlib / deflate)。
 * 途中で壊れているデータは、そこまでに戻せた分を使う (ほかの PDF の読み手と同じ。最後の検査の値だけが違うものも多い)。
 * zlib の先頭 (2 バイト) がないデータも読む。
 */
final class Flate {

    // 1 回に戻す大きさ (壊れたデータを 1 バイトずつ戻し直すときの量も、これで決まる)
    private static final int CHUNK = 4096;

    private Flate() {
    }

    // 戻したデータと、途中で壊れていたか
    private static final class Inflated {
        final byte[] bytes;
        final boolean broken;

        Inflated(byte[] bytes, boolean broken) {
            this.bytes = bytes;
            this.broken = broken;
        }
    }

    static byte[] decode(byte[] data, int limit) throws IOException {
        final Inflated whole = inflate(data, 0, false, limit, Long.MAX_VALUE);
        if (!whole.broken) return whole.bytes;
        // zlib の先頭があれば、それを飛ばして deflate として読み直す (最後の検査の値 Adler-32 を確かめないので、
        // 検査の値だけが違うものも全部戻せる)。先頭のないデータは、そのまま deflate として読む
        final boolean header = data.length >= 2 && (data[0] & 0x0F) == 8 && (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) % 31 == 0;
        for (int offset : header ? new int[]{2, 0} : new int[]{0}) {
            final Inflated first = inflate(data, offset, true, limit, Long.MAX_VALUE);
            if (!first.broken) return first.bytes;
            // 途中で壊れていると、そこで止まった 1 回で戻した分 (CHUNK まで) が失われる。そこまでは同じように、その先は 1 バイトずつ戻し直す
            final Inflated slow = inflate(data, offset, true, limit, first.bytes.length);
            if (slow.bytes.length > 0) return slow.bytes;
        }
        throw new IOException("Broken FlateDecode data");
    }

    /**
     * @param offset 読み始める位置
     * @param raw    zlib の先頭と最後のない deflate として読むか
     * @param fast   この大きさまでは CHUNK ずつ、その先は (CHUNK の分だけ) 1 バイトずつ戻す
     */
    private static Inflated inflate(byte[] data, int offset, boolean raw, int limit, long fast) throws IOException {
        final Inflater inflater = new Inflater(raw);
        try {
            inflater.setInput(data, offset, data.length - offset);
            final ChunkedOutput out = new ChunkedOutput();
            final byte[] buffer = new byte[CHUNK];
            int idle = 0;
            while (!inflater.finished()) {
                final long size = out.size();
                if (size - CHUNK >= fast) break;
                final int length = size < fast ? (int) Math.min(CHUNK, fast - size) : 1;
                final int n;
                try {
                    n = inflater.inflate(buffer, 0, length);
                } catch (DataFormatException e) {
                    return new Inflated(out.toByteArray(), true);
                }
                if (n == 0) {
                    // 入力が尽きた (最後が欠けたデータ) か、辞書が要るもの (PDF では使わない)
                    if (inflater.needsInput() || inflater.needsDictionary() || ++idle > 16) break;
                    continue;
                }
                idle = 0;
                if (out.size() + (long) n > limit) throw new IOException("Decoded data is too large");
                out.write(buffer, 0, n);
            }
            return new Inflated(out.toByteArray(), false);
        } finally {
            inflater.end();
        }
    }
}
