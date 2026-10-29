package marumasa.marumasa_sign.core.pdf.filter;

import marumasa.marumasa_sign.core.pdf.cos.PdfDict;

import java.io.IOException;

/**
 * CCITTFaxDecode (ITU-T T.4 の G3 1 次元・2 次元と、T.6 の G4)。白黒の画像を、1 行ずつバイト境界にそろえた 1 ビットの画素にする。
 * 黒の画素は BlackIs1 が false なら 0 (PDF の決まり)。
 * <p>
 * 行の数が分からない (Rows がない) データでも、呼ぶ側が渡す上限 maxRows を超えて作らない。
 * 2 次元の符号は 1 行を 1 ビットで表せるので、上限がないと小さなデータから巨大な画像ができてしまう。
 */
public final class CcittFax {

    // 白と黒の、走る長さ (ラン) の符号。{符号のビット列, 長さ}
    private static final String[] WHITE_CODES = {
            "00110101", "000111", "0111", "1000", "1011", "1100", "1110", "1111", "10011", "10100", "00111", "01000",
            "001000", "000011", "110100", "110101", "101010", "101011", "0100111", "0001100", "0001000", "0010111",
            "0000011", "0000100", "0101000", "0101011", "0010011", "0100100", "0011000", "00000010", "00000011",
            "00011010", "00011011", "00010010", "00010011", "00010100", "00010101", "00010110", "00010111", "00101000",
            "00101001", "00101010", "00101011", "00101100", "00101101", "00000100", "00000101", "00001010", "00001011",
            "01010010", "01010011", "01010100", "01010101", "00100100", "00100101", "01011000", "01011001", "01011010",
            "01011011", "01001010", "01001011", "00110010", "00110011", "00110100"};
    private static final String[] WHITE_MAKEUP = {
            "11011", "10010", "010111", "0110111", "00110110", "00110111", "01100100", "01100101", "01101000",
            "01100111", "011001100", "011001101", "011010010", "011010011", "011010100", "011010101", "011010110",
            "011010111", "011011000", "011011001", "011011010", "011011011", "010011000", "010011001", "010011010",
            "011000", "010011011"};
    private static final String[] BLACK_CODES = {
            "0000110111", "010", "11", "10", "011", "0011", "0010", "00011", "000101", "000100", "0000100", "0000101",
            "0000111", "00000100", "00000111", "000011000", "0000010111", "0000011000", "0000001000", "00001100111",
            "00001101000", "00001101100", "00000110111", "00000101000", "00000010111", "00000011000", "000011001010",
            "000011001011", "000011001100", "000011001101", "000001101000", "000001101001", "000001101010",
            "000001101011", "000011010010", "000011010011", "000011010100", "000011010101", "000011010110",
            "000011010111", "000001101100", "000001101101", "000011011010", "000011011011", "000001010100",
            "000001010101", "000001010110", "000001010111", "000001100100", "000001100101", "000001010010",
            "000001010011", "000000100100", "000000110111", "000000111000", "000000100111", "000000101000",
            "000001011000", "000001011001", "000000101011", "000000101100", "000001011010", "000001100110",
            "000001100111"};
    private static final String[] BLACK_MAKEUP = {
            "0000001111", "000011001000", "000011001001", "000001011011", "000000110011", "000000110100",
            "000000110101", "0000001101100", "0000001101101", "0000001001010", "0000001001011", "0000001001100",
            "0000001001101", "0000001110010", "0000001110011", "0000001110100", "0000001110101", "0000001110110",
            "0000001110111", "0000001010010", "0000001010011", "0000001010100", "0000001010101", "0000001011010",
            "0000001011011", "0000001100100", "0000001100101"};
    // 1792 から 2560 まで。白と黒で共通
    private static final String[] EXTENDED_MAKEUP = {
            "00000001000", "00000001100", "00000001101", "000000010010", "000000010011", "000000010100",
            "000000010101", "000000010110", "000000010111", "000000011100", "000000011101", "000000011110",
            "000000011111"};

    // 13 ビットを先読みして引く表。値は (符号の長さ << 16) | ラン、なければ 0
    private static final int[] WHITE_TABLE = new int[1 << 13];
    private static final int[] BLACK_TABLE = new int[1 << 13];

    static {
        fill(WHITE_TABLE, WHITE_CODES, 0, 1);
        fill(WHITE_TABLE, WHITE_MAKEUP, 64, 64);
        fill(WHITE_TABLE, EXTENDED_MAKEUP, 1792, 64);
        fill(BLACK_TABLE, BLACK_CODES, 0, 1);
        fill(BLACK_TABLE, BLACK_MAKEUP, 64, 64);
        fill(BLACK_TABLE, EXTENDED_MAKEUP, 1792, 64);
    }

    private static void fill(int[] table, String[] codes, int first, int step) {
        for (int i = 0; i < codes.length; i++) {
            final String code = codes[i];
            final int length = code.length();
            final int value = Integer.parseInt(code, 2) << (13 - length);
            for (int suffix = 0; suffix < 1 << (13 - length); suffix++) {
                table[value | suffix] = (length << 16) | (first + i * step);
            }
        }
    }

    // 2 次元の符号の種類
    private static final int PASS = 100, HORIZONTAL = 101, EXTENSION = 102, EOL = 103, ERROR = 104;

    private final byte[] data;
    private int bitPosition;
    private final long totalBits;
    // EOL をバイト境界にあるものだけに限るか (EncodedByteAlign で、EndOfLine でないもの。
    // EndOfLine なら行ごとに EOL があり、詰め物と行の始まりの 0 を EOL と取り違えることはない)
    private boolean alignedEol;

    private CcittFax(byte[] data) {
        this.data = data;
        this.totalBits = (long) data.length * 8;
    }

    /**
     * 戻す。
     *
     * @param maxRows 作る行の数の上限 (画像の高さ)
     */
    public static byte[] decode(byte[] data, PdfDict parameters, int width, int maxRows, int limit) throws IOException {
        final int k = parameters == null ? 0 : parameters.integer("K", 0);
        final int columns = parameters == null ? width : parameters.integer("Columns", 1728);
        int rows = parameters == null ? 0 : parameters.integer("Rows", 0);
        final boolean byteAlign = parameters != null && parameters.bool("EncodedByteAlign", false);
        final boolean endOfLine = parameters != null && parameters.bool("EndOfLine", false);
        final boolean blackIs1 = parameters != null && parameters.bool("BlackIs1", false);
        if (columns < 1 || columns > (1 << 20)) throw new IOException("Bad CCITT columns");
        if (rows <= 0 || rows > maxRows) rows = maxRows;
        final int rowBytes = (columns + 7) / 8;
        if ((long) rowBytes * maxRows > limit) throw new IOException("CCITT image is too large");
        // Rows が画像の高さより少なくても、高さの分の行を作る (戻さない行は、読めなかった行と同じく白。poppler と同じ)。
        // 短いデータを渡すと、足りない行は画素の値 0 (BlackIs1 が false なら黒) として描かれてしまう
        final byte[] out = new byte[rowBytes * maxRows];
        final CcittFax decoder = new CcittFax(data);
        decoder.alignedEol = byteAlign && !endOfLine;
        decoder.run(out, columns, rows, k, byteAlign);
        // 黒を 1 にした結果を、決まりに合わせて反転する (読めなかった残りの行は白になる)
        if (!blackIs1) {
            for (int i = 0; i < out.length; i++) out[i] = (byte) ~out[i];
        }
        return out;
    }

    // 戻せた行の数を返す。out には黒を 1 として書く
    private int run(byte[] out, int columns, int rows, int k, boolean byteAlign) {
        final int rowBytes = (columns + 7) / 8;
        // 変化点 (色の変わる位置) の並び。最後に番兵として columns を 2 つ置く
        int[] reference = new int[columns + 3];
        int[] current = new int[columns + 3];
        int referenceCount = 0;
        reference[0] = columns;
        reference[1] = columns;
        int row = 0;
        while (row < rows) {
            boolean twoDimensional = k < 0;
            if (k >= 0) {
                // G3: 行の前の EOL (と、その前の詰め物の 0) を飛ばす
                final int eol = skipEol(byteAlign);
                if (eol < 0) break;
                if (k > 0) {
                    final int tag = readBit();
                    if (tag < 0) break;
                    twoDimensional = tag == 0;
                }
            } else {
                // G4: 行の前の EOL (EndOfLine が true のもの) を飛ばす。仕様では EndOfLine が false でも EOL を受け付ける
                skipG4Eol(byteAlign);
            }
            if (bitPosition >= totalBits) break;
            final int count;
            if (twoDimensional) {
                count = decode2d(reference, referenceCount, current, columns);
            } else {
                count = decode1d(current, columns);
            }
            if (count < 0) break;
            // 画素にする
            final int base = row * rowBytes;
            for (int i = 0; i + 1 <= count; i += 2) {
                final int from = current[i];
                final int to = i + 1 < count ? current[i + 1] : columns;
                setBlack(out, base, from, Math.min(to, columns));
            }
            row++;
            final int[] swap = reference;
            reference = current;
            current = swap;
            referenceCount = count;
            reference[count] = columns;
            reference[count + 1] = columns;
        }
        return row;
    }

    private static void setBlack(byte[] out, int base, int from, int to) {
        for (int x = from; x < to; x++) out[base + (x >> 3)] |= (byte) (0x80 >> (x & 7));
    }

    /**
     * 1 次元の行 (MH)。変化点の数を返す。読めなければ -1。
     */
    private int decode1d(int[] changes, int columns) {
        int position = 0;
        int count = 0;
        boolean white = true;
        while (position < columns) {
            final int run = readRun(white);
            if (run < 0) return count > 0 ? finish(changes, count, columns) : -1;
            position = Math.min(columns, position + run);
            if (count >= columns + 1) return -1;
            changes[count++] = position;
            white = !white;
        }
        return finish(changes, count, columns);
    }

    // 行の最後が columns で終わる変化点を除く (columns の位置の変化は画素に影響しない)
    private static int finish(int[] changes, int count, int columns) {
        while (count > 0 && changes[count - 1] >= columns) count--;
        return count;
    }

    /**
     * 2 次元の行 (MR / MMR)。reference は前の行の変化点 (番兵つき)。
     */
    private int decode2d(int[] reference, int referenceCount, int[] changes, int columns) {
        int a0 = -1;
        boolean white = true;
        int count = 0;
        int b = 0;
        while (a0 < columns) {
            final int mode = readMode();
            if (mode == ERROR || mode == EOL || mode == EXTENSION) {
                // 行の途中で終わったか、扱えない符号。ここまでの行で終える
                if (mode == EOL) bitPosition -= 12;
                return count > 0 || a0 > 0 ? finish(changes, count, columns) : -1;
            }
            // b1: a0 より右にある、今の色と反対の色に変わる、前の行の変化点。偶数番目は白から黒への変化
            while (b > 0 && reference[b - 1] > a0) b--;
            while (b < referenceCount && reference[b] <= a0) b++;
            if (b < referenceCount && (b & 1) != (white ? 0 : 1)) b++;
            final int b1 = b < referenceCount ? reference[b] : columns;
            final int b2 = b + 1 < referenceCount ? reference[b + 1] : columns;
            if (mode == PASS) {
                a0 = b2;
                continue;
            }
            if (count + 2 > columns + 1) return -1;
            if (mode == HORIZONTAL) {
                final int start = Math.max(a0, 0);
                final int run1 = readRun(white);
                final int run2 = run1 < 0 ? -1 : readRun(!white);
                if (run1 < 0 || run2 < 0) return count > 0 ? finish(changes, count, columns) : -1;
                final int a1 = Math.min(columns, start + run1);
                final int a2 = Math.min(columns, a1 + run2);
                changes[count++] = a1;
                changes[count++] = a2;
                a0 = a2;
            } else {
                // 縦の符号: a1 = b1 + 差
                final int a1 = Math.max(0, Math.min(columns, b1 + mode));
                // 左に戻る変化点は壊れたデータ。ここまでの行で終える
                if (a1 < a0) return finish(changes, count, columns);
                changes[count++] = a1;
                a0 = a1;
                white = !white;
            }
        }
        return finish(changes, count, columns);
    }

    // 2 次元の符号の種類。縦の符号は差 (-3 から 3) を返す
    private int readMode() {
        final int bits = peek(12);
        if (bits < 0) return ERROR;
        if ((bits & 0x800) != 0) return consume(1, 0);           // 1: V0
        if ((bits & 0xE00) == 0x600) return consume(3, 1);       // 011: VR1
        if ((bits & 0xE00) == 0x400) return consume(3, -1);      // 010: VL1
        if ((bits & 0xE00) == 0x200) return consume(3, HORIZONTAL); // 001
        if ((bits & 0xF00) == 0x100) return consume(4, PASS);    // 0001
        if ((bits & 0xFC0) == 0x0C0) return consume(6, 2);       // 000011: VR2
        if ((bits & 0xFC0) == 0x080) return consume(6, -2);      // 000010: VL2
        if ((bits & 0xFE0) == 0x060) return consume(7, 3);       // 0000011: VR3
        if ((bits & 0xFE0) == 0x040) return consume(7, -3);      // 0000010: VL3
        if (bits == 1) return consume(12, EOL);
        if ((bits & 0xFE0) == 0x020) return consume(10, EXTENSION); // 0000001xxx
        return ERROR;
    }

    private int consume(int bits, int value) {
        bitPosition += bits;
        return value;
    }

    // 1 つの色のラン (メイクアップ符号の繰り返しと、終わりの符号の和)
    private int readRun(boolean white) {
        final int[] table = white ? WHITE_TABLE : BLACK_TABLE;
        int total = 0;
        for (int guard = 0; guard < 4096; guard++) {
            final int bits = peek(13);
            if (bits < 0) return -1;
            final int entry = table[bits];
            if (entry == 0) return -1;
            bitPosition += entry >>> 16;
            final int run = entry & 0xFFFF;
            total += run;
            if (total > (1 << 24)) return -1;
            if (run < 64) return total;
        }
        return -1;
    }

    // G3 の行の前の EOL を飛ばす。データの終わりなら -1
    private int skipEol(boolean byteAlign) {
        final long start = bitPosition;
        // 詰め物の 0 は、いくつ続いてもよい。11 個以上の 0 の後ろの 1 が EOL の終わり
        int zeros = 0;
        long p = bitPosition;
        while (p < totalBits && bit(p) == 0) {
            zeros++;
            p++;
        }
        if (p >= totalBits) return -1;
        // バイト境界にそろえるもの (EncodedByteAlign で EndOfLine でないもの) の EOL は、詰め物の後ろでバイト境界に終わる形か、バイト境界から始まる形。
        // そうでないものは、詰め物の 0 と、0 で始まる行の符号 (白の 29 は 00000010) が続いただけなので EOL としない
        final boolean eolAligned = !alignedEol || ((p + 1) & 7) == 0 || ((p - 11) & 7) == 0;
        if (zeros >= 11 && eolAligned) {
            bitPosition = (int) (p + 1);
            return 1;
        }
        bitPosition = (int) start;
        if (byteAlign && (bitPosition & 7) != 0) {
            // EOL のない、バイト境界にそろえた行
            final int aligned = (bitPosition + 7) & ~7;
            boolean allZero = true;
            for (long q = bitPosition; q < aligned && q < totalBits; q++) {
                if (bit(q) != 0) allZero = false;
            }
            if (allZero) bitPosition = aligned;
        }
        return 0;
    }

    /**
     * G4 の行の前の EOL (11 個以上の 0 と 1) を飛ばす。2 次元の符号は 0 が 6 個までしか続かないので、EOL と取り違えない。
     * バイト境界にそろえるもの (EncodedByteAlign で EndOfLine でないもの) では、EOL は詰め物の後ろでバイト境界に終わる形か、
     * バイト境界から始まる形 (次の行はそろえない) のどちらか。どちらでもなければ、行の始まりをバイト境界にそろえる。
     */
    private void skipG4Eol(boolean byteAlign) {
        long p = bitPosition;
        while (p < totalBits && bit(p) == 0) p++;
        if (p < totalBits && p - bitPosition >= 11 && (!alignedEol || ((p + 1) & 7) == 0)) {
            bitPosition = (int) (p + 1);
            return;
        }
        if (!byteAlign) return;
        bitPosition = (bitPosition + 7) & ~7;
        p = bitPosition;
        while (p < totalBits && bit(p) == 0) p++;
        if (p < totalBits && p - bitPosition >= 11) bitPosition = (int) (p + 1);
    }

    private int bit(long position) {
        return (data[(int) (position >> 3)] >> (7 - (position & 7))) & 1;
    }

    private int readBit() {
        if (bitPosition >= totalBits) return -1;
        return bit(bitPosition++);
    }

    // n ビットを先読みする。データの終わりを越える分は 0 で埋める。1 ビットも残っていなければ -1
    private int peek(int n) {
        if (bitPosition >= totalBits) return -1;
        int value = 0;
        for (int i = 0; i < n; i++) {
            final long p = (long) bitPosition + i;
            value = (value << 1) | (p < totalBits ? bit(p) : 0);
        }
        return value;
    }
}
