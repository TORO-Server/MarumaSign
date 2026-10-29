package marumasa.marumasa_sign.core.texture.webp;

/**
 * WebP 可逆圧縮のビット列を、各バイトの下位ビットから順に読む。
 * データの終わりの先は 0 として読み、読んだビット数が全体を超えたかどうかを overrun() で返す。
 */
final class LosslessBits {

    private final byte[] data;
    private final int end;
    private int position;
    // まだ使っていないビット (下位から順に使う) と、その数
    private long window;
    private int count;
    // 読んだビットの数と、データ全体のビット数
    private long consumed;
    private final long total;

    LosslessBits(byte[] data, int start, int end) {
        this.data = data;
        this.position = start;
        this.end = end;
        this.total = (long) (end - start) * 8;
    }

    private void fill() {
        while (count <= 56) {
            final long b = position < end ? data[position++] & 0xFF : 0;
            window |= b << count;
            count += 8;
        }
    }

    // 次の n ビット (n <= 32) を、読み進めずに返す
    int peek(int n) {
        if (count < n) fill();
        return (int) (window & ((1L << n) - 1));
    }

    void skip(int n) {
        if (count < n) fill();
        window >>>= n;
        count -= n;
        consumed += n;
    }

    // n ビット (n <= 32) を読む
    int read(int n) {
        final int value = peek(n);
        skip(n);
        return value;
    }

    // データの終わりを超えて読んだか
    boolean overrun() {
        return consumed > total;
    }

    long remaining() {
        return total - consumed;
    }
}
