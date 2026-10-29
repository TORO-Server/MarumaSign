package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * Draco のストリームを読む。範囲の外を読もうとしたら IOException にする。
 * ビット単位の読み込み (下位のビットから) にも切り替えられる。
 */
final class ByteReader {

    // ストリームの版 (major << 8 | minor) の区切り
    static final int V12 = 0x102;
    static final int V13 = 0x103;
    static final int V20 = 0x200;
    static final int V21 = 0x201;
    static final int V22 = 0x202;

    final byte[] data;
    final int end;
    final int version;
    int pos;

    ByteReader(byte[] data, int pos, int end, int version) {
        this.data = data;
        this.pos = pos;
        this.end = end;
        this.version = version;
    }

    // 同じデータを別の位置から読む読み手を作る
    ByteReader copy() {
        return new ByteReader(data, pos, end, version);
    }

    int remaining() {
        return end - pos;
    }

    private static IOException eof() {
        return new IOException("Draco data ended unexpectedly");
    }

    void need(long bytes) throws IOException {
        if (bytes < 0 || bytes > end - pos) throw eof();
    }

    void skip(long bytes) throws IOException {
        need(bytes);
        pos += (int) bytes;
    }

    int u8() throws IOException {
        if (pos >= end) throw eof();
        return data[pos++] & 0xff;
    }

    int i8() throws IOException {
        return (byte) u8();
    }

    int u16() throws IOException {
        need(2);
        final int v = (data[pos] & 0xff) | (data[pos + 1] & 0xff) << 8;
        pos += 2;
        return v;
    }

    int i32() throws IOException {
        need(4);
        final int v = Bits.le32(data, pos);
        pos += 4;
        return v;
    }

    long u32() throws IOException {
        return i32() & 0xffffffffL;
    }

    long u64() throws IOException {
        need(8);
        final long v = Bits.le64(data, pos);
        pos += 8;
        return v;
    }

    float f32() throws IOException {
        return Float.intBitsToFloat(i32());
    }

    /**
     * 可変長の符号なし整数 (LEB128)。32 ビットに収まらない分は捨てる。
     */
    long varU32() throws IOException {
        return var(5) & 0xffffffffL;
    }

    /**
     * 可変長の符号なし 64 ビット整数。2^63 以上は負の値になる。
     */
    long varU64() throws IOException {
        return var(10);
    }

    private long var(int maxBytes) throws IOException {
        long v = 0;
        int shift = 0;
        for (int i = 0; ; i++) {
            if (i >= maxBytes) throw new IOException("Draco varint is too long");
            final int b = u8();
            if (shift < 64) v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
        }
    }

    /**
     * 古い形式 (2.0 より前) は 32 ビット固定、それ以降は可変長で書かれる数。
     */
    long count32Before20() throws IOException {
        return version < V20 ? u32() : varU32();
    }

    /**
     * 2.2 より前は 32 ビット固定、それ以降は可変長で書かれる数。
     */
    long count32Before22() throws IOException {
        return version < V22 ? u32() : varU32();
    }

    /**
     * ここからビット単位で読む。読み終えたら endBits で先へ進める。
     */
    BitReader startBits() {
        return new BitReader(data, pos, end);
    }

    void endBits(BitReader bits) {
        pos += (int) ((bits.consumed + 7) >>> 3);
    }

    /**
     * 下位のビットから順に読む。データの終わりより先は 0 として読み、進めない。
     */
    static final class BitReader {
        private final byte[] data;
        private final int start;
        private final int end;
        long consumed;

        BitReader(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }

        int bit() {
            final long byteIndex = start + (consumed >>> 3);
            if (byteIndex >= end) return 0;
            final int b = (data[(int) byteIndex] >> (int) (consumed & 7)) & 1;
            consumed++;
            return b;
        }

        /**
         * n ビットを読む (n は 32 まで)。32 より多いときは -1 ではなく例外にする。
         */
        int bits(int n) throws IOException {
            if (n < 0 || n > 32) throw new IOException("Too many bits requested: " + n);
            int v = 0;
            for (int i = 0; i < n; i++) v |= bit() << i;
            return v;
        }
    }
}
