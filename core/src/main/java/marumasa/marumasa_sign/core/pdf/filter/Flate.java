package marumasa.marumasa_sign.core.pdf.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * FlateDecode (zlib / deflate)。
 * 途中で壊れているデータは、そこまでに戻せた分を使う (ほかの PDF の読み手と同じ)。
 * zlib の先頭 (2 バイト) がないデータも読む。
 */
final class Flate {

    private Flate() {
    }

    static byte[] decode(byte[] data, int limit) throws IOException {
        try {
            return inflate(data, false, limit);
        } catch (DataFormatException e) {
            try {
                return inflate(data, true, limit);
            } catch (DataFormatException again) {
                throw new IOException("Broken FlateDecode data", again);
            }
        }
    }

    private static byte[] inflate(byte[] data, boolean raw, int limit) throws DataFormatException, IOException {
        final Inflater inflater = new Inflater(raw);
        try {
            inflater.setInput(data);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, Math.max(64, data.length * 4)));
            final byte[] buffer = new byte[65536];
            int idle = 0;
            while (!inflater.finished()) {
                final int n;
                try {
                    n = inflater.inflate(buffer);
                } catch (DataFormatException e) {
                    // 途中までは戻せたなら、その分を使う
                    if (out.size() > 0) break;
                    throw e;
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
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
