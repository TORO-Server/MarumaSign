package marumasa.marumasa_sign.core.texture.webp;

/**
 * VP8 のブール値の算術符号 (RFC 6386 7 節) を読む。
 * 範囲 (range) と値 (value) を持ち、確率 prob (1〜255、0 になる確率を 256 分率で表したもの) で 1 ビットずつ読む。
 * データの終わりを過ぎて読もうとしたら 0 を補い、eof を立てる。呼び出し側は eof を見て、データが足りない画像を失敗にする。
 */
final class BoolReader {

    private final byte[] data;
    private final int end;
    private int position;
    // 範囲 (128〜255)
    private int range = 255;
    // 読み込んだビット。上位 8 ビット (bits 個の下位ビットを除いた部分) を範囲と比べる
    private int value;
    // value の中で、比べる 8 ビットより下にあるビットの数。負なら次の 1 バイトを読む
    private int bits = -8;
    private boolean eof;

    BoolReader(byte[] data, int start, int end) {
        this.data = data;
        this.position = start;
        this.end = end;
    }

    boolean eof() {
        return eof;
    }

    // 確率 prob で 1 ビット読む
    int readBit(int prob) {
        if (bits < 0) load();
        final int split = 1 + (((range - 1) * prob) >> 8);
        final int bigSplit = split << bits;
        final int bit;
        if (value >= bigSplit) {
            range -= split;
            value -= bigSplit;
            bit = 1;
        } else {
            range = split;
            bit = 0;
        }
        // 範囲が 128 以上になるまで広げる。value はずらさず、比べる位置 (bits) を下げる
        final int shift = Integer.numberOfLeadingZeros(range) - 24;
        range <<= shift;
        bits -= shift;
        return bit;
    }

    // 確率 1/2 で count ビット読み、上位ビットから並べた値にする
    int readLiteral(int count) {
        int result = 0;
        for (int i = 0; i < count; i++) result = (result << 1) | readBit(128);
        return result;
    }

    // count ビットの大きさのあとに符号のビットが続く値
    int readSigned(int count) {
        final int magnitude = readLiteral(count);
        return readBit(128) != 0 ? -magnitude : magnitude;
    }

    // 1 ビット読み、立っていれば count ビットの符号付きの値を、立っていなければ 0 を返す
    int readOptionalSigned(int count) {
        return readBit(128) != 0 ? readSigned(count) : 0;
    }

    // 木 (RFC 6386 8.1 の tree_index の表) をたどって値を読む。probs[offset + 節の位置 / 2] が各節の確率
    int readTree(int[] tree, int[] probs, int offset) {
        int i = 0;
        do {
            i = tree[i + readBit(probs[offset + (i >> 1)])];
        } while (i > 0);
        return -i;
    }

    private void load() {
        if (position < end) {
            value = (value << 8) | (data[position++] & 0xFF);
        } else {
            // データの終わり。0 を補って読み続けられるようにしておく (値は使われない)
            value <<= 8;
            eof = true;
        }
        // value は常に range << bits より小さく、bits は 0〜7 なので、int からあふれることはない
        bits += 8;
    }
}
