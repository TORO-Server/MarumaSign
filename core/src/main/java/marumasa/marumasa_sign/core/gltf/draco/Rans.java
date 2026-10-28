package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * Draco の rANS (非対称数系) による記号の復号と、1 ビットずつの復号 (rABS)。
 * 符号化側は値を後ろから詰めていくので、復号はデータの末尾に置かれた状態から始め、前へ向かってバイトを読む。
 */
final class Rans {

    // 記号の種類の数の上限。精度は最大 2^20 なので、正しいデータならこれを超えない
    private static final int MAX_SYMBOLS = 1 << 20;

    private static final int TAGGED = 0;
    private static final int RAW = 1;

    private Rans() {
    }

    /**
     * 記号を numValues 個読み、out に符号なし 32 ビットの値として入れる。
     */
    static void decodeSymbols(ByteReader in, int numValues, int numComponents, int[] out) throws IOException {
        if (numValues == 0) return;
        if (numValues < 0 || numValues > out.length) throw new IOException("Invalid Draco symbol count");
        final int scheme = in.u8();
        if (scheme == TAGGED) {
            decodeTagged(in, numValues, numComponents, out);
        } else if (scheme == RAW) {
            decodeRaw(in, numValues, out);
        } else {
            throw new IOException("Unknown Draco symbol coding: " + scheme);
        }
    }

    // 各値のビット数を rANS で、値そのものを生のビットで持つ形式
    private static void decodeTagged(ByteReader in, int numValues, int numComponents, int[] out) throws IOException {
        if (numComponents <= 0 || numValues % numComponents != 0) {
            throw new IOException("Invalid Draco tagged symbol layout");
        }
        // タグの精度は 5 ビット分の記号から決まる 12 ビット
        final SymbolDecoder tags = new SymbolDecoder(in, 12);
        tags.start(in);
        if (tags.numSymbols == 0) throw new IOException("Empty Draco tag table");
        final ByteReader.BitReader bits = in.startBits();
        int k = 0;
        for (int i = 0; i < numValues; i += numComponents) {
            final int length = tags.read();
            for (int c = 0; c < numComponents; c++) {
                out[k++] = bits.bits(length);
            }
        }
        in.endBits(bits);
    }

    // 値そのものを rANS で持つ形式。精度は値の最大のビット数から決まる
    private static void decodeRaw(ByteReader in, int numValues, int[] out) throws IOException {
        final int maxBitLength = in.u8();
        if (maxBitLength < 1 || maxBitLength > 18) throw new IOException("Invalid Draco symbol bit length: " + maxBitLength);
        final int precision = Math.max(12, Math.min(20, (3 * maxBitLength) / 2));
        final SymbolDecoder decoder = new SymbolDecoder(in, precision);
        if (decoder.numSymbols == 0) throw new IOException("Empty Draco symbol table");
        decoder.start(in);
        for (int i = 0; i < numValues; i++) out[i] = decoder.read();
    }

    /**
     * 復号の状態と、まだ読んでいないバイト (data[base] から data[base + offset - 1] まで)。
     */
    private static class State {
        byte[] data;
        int base;
        int offset;
        long state;

        /**
         * size バイトのデータの末尾から状態を読む。先頭の 2 ビットが状態のバイト数 (1〜4) を表す。
         */
        void init(ByteReader in, long size, int maxStateBytes, long lowerBound) throws IOException {
            if (size < 1 || size > in.remaining()) throw new IOException("Invalid Draco entropy data size");
            data = in.data;
            base = in.pos;
            final int n = (int) size;
            in.skip(size);
            final int bytes = ((data[base + n - 1] & 0xff) >> 6) + 1;
            if (bytes > maxStateBytes || n < bytes) throw new IOException("Invalid Draco entropy state");
            long s = 0;
            for (int i = 0; i < bytes; i++) s |= (long) (data[base + n - bytes + i] & 0xff) << (8 * i);
            offset = n - bytes;
            state = (s & ((1L << (8 * bytes - 2)) - 1)) + lowerBound;
            if (state >= lowerBound * 256) throw new IOException("Invalid Draco entropy state");
        }

        int previousByte() {
            return data[base + --offset] & 0xff;
        }
    }

    /**
     * 確率の表を持つ rANS の復号器。
     */
    private static final class SymbolDecoder extends State {
        final int precisionBits;
        final long lBase;
        final int numSymbols;
        private int[] lut;
        private int[] probability;
        private int[] cumulative;

        // 確率の表を読む
        SymbolDecoder(ByteReader in, int precisionBits) throws IOException {
            this.precisionBits = precisionBits;
            this.lBase = 4L << precisionBits;
            final long n = in.count32Before20();
            if (n / 64 > in.remaining() || n > MAX_SYMBOLS) throw new IOException("Too many Draco symbols: " + n);
            numSymbols = (int) n;
            if (n == 0) return;
            final int[] prob = new int[numSymbols];
            for (int i = 0; i < numSymbols; i++) {
                final int first = in.u8();
                final int token = first & 3;
                if (token == 3) {
                    // 確率 0 の記号が (first >> 2) + 1 個続く
                    final int run = first >> 2;
                    if (i + run >= numSymbols) throw new IOException("Invalid Draco probability table");
                    i += run;
                } else {
                    // 残りの 6 ビットと、token 個の追加のバイトが確率
                    int p = first >> 2;
                    for (int b = 0; b < token; b++) {
                        p |= in.u8() << (8 * (b + 1) - 2);
                    }
                    prob[i] = p;
                }
            }
            // 余りから記号を引く表を作る。確率の合計はちょうど精度と同じでなければならない
            final int precision = 1 << precisionBits;
            lut = new int[precision];
            cumulative = new int[numSymbols];
            long sum = 0;
            for (int i = 0; i < numSymbols; i++) {
                cumulative[i] = (int) sum;
                final long next = sum + prob[i];
                if (next > precision) throw new IOException("Invalid Draco probability table");
                for (int j = (int) sum; j < next; j++) lut[j] = i;
                sum = next;
            }
            if (sum != precision) throw new IOException("Invalid Draco probability table");
            probability = prob;
        }

        // 符号化されたデータの大きさを読み、状態を初期化する
        void start(ByteReader in) throws IOException {
            final long bytes = in.version < ByteReader.V20 ? in.u64() : in.varU64();
            init(in, bytes, 4, lBase);
        }

        int read() {
            while (state < lBase && offset > 0) {
                state = state * 256 + previousByte();
            }
            final long quo = state >>> precisionBits;
            final int rem = (int) (state & ((1 << precisionBits) - 1));
            final int symbol = lut[rem];
            state = (quo * probability[symbol] + rem - cumulative[symbol]) & 0xffffffffL;
            return symbol;
        }
    }

    /**
     * 1 ビットずつの rABS 復号器 (Draco の RAnsBitDecoder)。0 の確率を 256 段階で持つ。
     */
    static final class BitDecoder extends State {
        private int probZero;

        static BitDecoder start(ByteReader in) throws IOException {
            final BitDecoder d = new BitDecoder();
            d.probZero = in.u8();
            d.init(in, in.count32Before22(), 3, 4096);
            return d;
        }

        boolean next() {
            // p0 が 0 のときは 8 ビットに収まらず p も 0 になる (元の実装と同じ)
            final int p = (256 - probZero) & 0xff;
            if (state < 4096 && offset > 0) {
                state = state * 256 + previousByte();
            }
            final long x = state;
            final long quot = x >>> 8;
            final long rem = x & 0xff;
            final long xn = quot * p;
            final boolean bit = rem < p;
            state = (bit ? xn + rem : x - xn - p) & 0xffffffffL;
            return bit;
        }
    }
}
