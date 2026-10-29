package marumasa.marumasa_sign.core.pdf.font;

import java.nio.ByteBuffer;

/**
 * フォントのデータの一部分を、位置を指定して読むもの。
 * 範囲の外を読むと FontFormatError (実行時例外) を投げる。読む側は、グリフや表の単位でそれを受け止める。
 * パソコンの大きなフォント (100 MB を超える CJK のフォントもある) をメモリに読み込まないよう、ファイルを割り当てた ByteBuffer も使える。
 */
final class FontBytes {

    /**
     * 壊れたフォントのデータ。
     */
    static final class FontFormatError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FontFormatError(String message) {
            super(message, null, false, false);
        }
    }

    private final ByteBuffer buffer;
    private final int start;
    private final int length;

    FontBytes(byte[] data) {
        this(ByteBuffer.wrap(data), 0, data.length);
    }

    FontBytes(ByteBuffer buffer, int start, int length) {
        this.buffer = buffer;
        this.start = start;
        this.length = length;
    }

    int length() {
        return length;
    }

    /**
     * 一部分。範囲がはみ出していれば、収まる分だけにする。
     */
    FontBytes slice(long offset, long size) {
        if (offset < 0 || offset > length) throw new FontFormatError("Offset out of range");
        final long end = Math.min(length, offset + Math.max(0, size));
        return new FontBytes(buffer, (int) (start + offset), (int) (end - offset));
    }

    private void check(long offset, int size) {
        if (offset < 0 || offset + size > length) throw new FontFormatError("Read out of range");
    }

    int u8(long offset) {
        check(offset, 1);
        return buffer.get((int) (start + offset)) & 0xFF;
    }

    int s8(long offset) {
        check(offset, 1);
        return buffer.get((int) (start + offset));
    }

    int u16(long offset) {
        check(offset, 2);
        final int p = (int) (start + offset);
        return ((buffer.get(p) & 0xFF) << 8) | (buffer.get(p + 1) & 0xFF);
    }

    int s16(long offset) {
        return (short) u16(offset);
    }

    long u32(long offset) {
        check(offset, 4);
        return ((long) u16(offset) << 16) | u16(offset + 2);
    }

    int s32(long offset) {
        return (int) u32(offset);
    }

    // n バイト (1 から 4) の符号なしの数
    long unsigned(long offset, int n) {
        long value = 0;
        for (int i = 0; i < n; i++) value = (value << 8) | u8(offset + i);
        return value;
    }

    byte[] bytes(long offset, int size) {
        check(offset, size);
        final byte[] result = new byte[size];
        for (int i = 0; i < size; i++) result[i] = buffer.get((int) (start + offset + i));
        return result;
    }

    String ascii(long offset, int size) {
        return new String(bytes(offset, size), java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
