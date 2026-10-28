package marumasa.marumasa_sign.core.texture;

import java.io.IOException;

/**
 * Basis Universal の ETC1S (KTX2 の BasisLZ 超圧縮) を RGBA に展開する。
 * KTX 2.0 仕様の付録「BasisLZ Global Data」「BasisLZ/ETC1S Bitstream Specification」と、
 * Khronos Data Format Specification の ETC1 の節に沿って書いたもの。
 */
final class BasisEtc1s {

    // ETC1 の明るさの変化の表 (a, b)。選択子 0 - 3 は -b, -a, +a, +b を表す
    private static final int[][] MODIFIERS = {
            {2, 8}, {5, 17}, {9, 29}, {13, 42}, {18, 60}, {24, 80}, {33, 106}, {47, 183}
    };

    // 端点の予測の記号。4 ブロック分の 2 ビットずつと、直前の記号の繰り返し
    private static final int ENDPOINT_PRED_SYMBOLS = 4 * 4 * 4 * 4 + 1;
    private static final int ENDPOINT_PRED_REPEAT = ENDPOINT_PRED_SYMBOLS - 1;
    private static final int ENDPOINT_PRED_MIN_REPEAT = 3;
    private static final int ENDPOINT_PRED_COUNT_BITS = 4;
    // 選択子の履歴の繰り返し
    private static final int SELECTOR_RLE_MIN = 3;
    private static final int SELECTOR_RLE_SYMBOLS = 64;

    private final int endpointCount;
    private final int selectorCount;
    // 端点ごとの 5 ビットの RGB と、明るさの表の番号
    private final int[] endpointRgb;
    private final int[] endpointInten;
    // 選択子ごとの 4 行分 (1 行が 1 バイト、左の画素が下位の 2 ビット)
    private final byte[] selectors;

    private final Huffman endpointPredModel;
    private final Huffman endpointDeltaModel;
    private final Huffman selectorModel;
    private final Huffman selectorRleModel;
    private final int selectorHistorySize;

    /**
     * KTX2 の全体のデータ (supercompressionGlobalData) から、端点と選択子の表を読む。
     */
    BasisEtc1s(int endpointCount, byte[] data, int endpointsOffset, int endpointsLength,
               int selectorCount, int selectorsOffset, int selectorsLength,
               int tablesOffset, int tablesLength) throws IOException {
        if (endpointCount <= 0 || endpointCount > 0xFFFF || selectorCount <= 0 || selectorCount > 0xFFFF) {
            throw new IOException("Invalid ETC1S codebook size");
        }
        this.endpointCount = endpointCount;
        this.selectorCount = selectorCount;

        // スライスの展開に使う 4 つのハフマン符号と、選択子の履歴の大きさ
        BitReader in = new BitReader(data, tablesOffset, tablesLength);
        endpointPredModel = Huffman.read(in);
        endpointDeltaModel = Huffman.read(in);
        selectorModel = Huffman.read(in);
        selectorRleModel = Huffman.read(in);
        selectorHistorySize = in.read(13);

        // 端点: 明るさの番号と色を、1 つ前との差で並べてある
        in = new BitReader(data, endpointsOffset, endpointsLength);
        final Huffman[] colorModels = {Huffman.read(in), Huffman.read(in), Huffman.read(in)};
        final Huffman intenModel = Huffman.read(in);
        final boolean grayscale = in.read(1) != 0;
        endpointRgb = new int[endpointCount * 3];
        endpointInten = new int[endpointCount];
        final int[] previous = {16, 16, 16};
        int previousInten = 0;
        for (int i = 0; i < endpointCount; i++) {
            previousInten = (previousInten + in.decode(intenModel)) & 7;
            endpointInten[i] = previousInten;
            final int channels = grayscale ? 1 : 3;
            for (int c = 0; c < channels; c++) {
                // 差の表は、1 つ前の値が 0 - 9、10 - 21、22 - 31 のどれかで使い分ける
                final int prev = previous[c];
                final Huffman model = colorModels[prev <= 9 ? 0 : prev <= 21 ? 1 : 2];
                previous[c] = (prev + in.decode(model)) & 31;
            }
            for (int c = 0; c < 3; c++) endpointRgb[i * 3 + c] = previous[grayscale ? 0 : c];
        }

        // 選択子: 最初の 2 ビットは 0 と決まっている。続く 1 ビットが立っていればそのまま並んでいる
        in = new BitReader(data, selectorsOffset, selectorsLength);
        if (in.read(2) != 0) throw new IOException("Unsupported ETC1S selector codebook");
        selectors = new byte[selectorCount * 4];
        if (in.read(1) != 0) {
            for (int i = 0; i < selectors.length; i++) selectors[i] = (byte) in.read(8);
        } else {
            final Huffman deltaModel = Huffman.read(in);
            for (int j = 0; j < 4; j++) selectors[j] = (byte) in.read(8);
            // 2 つ目からは、1 つ前の同じ行との排他的論理和
            for (int i = 4; i < selectors.length; i++) {
                selectors[i] = (byte) (in.decode(deltaModel) ^ selectors[i - 4]);
            }
        }
    }

    /**
     * 1 枚分のデータを展開して、ARGB の画素に書く。alpha が true なら、透明度の画像 (緑の値を透明度にする) として書く。
     */
    void decodeSlice(byte[] data, int offset, int length, int width, int height, int[] argb, boolean alpha) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > argb.length) throw new IOException("Invalid ETC1S slice size");
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final long totalBlocks = (long) blocksX * blocksY;
        final BitReader in = new BitReader(data, offset, length);

        // 選択子の記号のうち、selectorCount 以上は履歴の位置、その次の 1 つが履歴の繰り返しを表す
        final int historyRleSymbol = selectorCount + selectorHistorySize;
        final int[] history = new int[selectorHistorySize];
        int historyRover = selectorHistorySize / 2;

        // 1 つ上の行と今の行の端点の番号、奇数の行で使う予測のビット
        final int[][] rowEndpoints = new int[2][blocksX];
        final int[] oddRowPredBits = new int[blocksX];
        int predBits = 0;
        int previousPredSymbol = 0;
        long predRepeat = 0;
        int previousEndpoint = 0;
        long selectorRepeat = 0;
        final int[] colors = new int[4];

        for (int blockY = 0; blockY < blocksY; blockY++) {
            final int[] current = rowEndpoints[blockY & 1];
            final int[] above = rowEndpoints[(blockY & 1) ^ 1];
            for (int blockX = 0; blockX < blocksX; blockX++) {
                // 予測のビットは 2x2 ブロックごとに 1 つの記号 (8 ビット) で、下の行の分は覚えておく
                if ((blockX & 1) == 0) {
                    if ((blockY & 1) == 0) {
                        if (predRepeat > 0) {
                            predRepeat--;
                            predBits = previousPredSymbol;
                        } else {
                            predBits = in.decode(endpointPredModel);
                            if (predBits > ENDPOINT_PRED_REPEAT) throw new IOException("Invalid ETC1S endpoint prediction");
                            if (predBits == ENDPOINT_PRED_REPEAT) {
                                predRepeat = in.readVlc(ENDPOINT_PRED_COUNT_BITS) + ENDPOINT_PRED_MIN_REPEAT - 1;
                                predBits = previousPredSymbol;
                            } else {
                                previousPredSymbol = predBits;
                            }
                        }
                        oddRowPredBits[blockX] = predBits >> 4;
                    } else {
                        predBits = oddRowPredBits[blockX];
                    }
                }

                // 端点の番号: 0 は左、1 は上、2 は左上と同じ。3 は直前の番号との差
                final int pred = predBits & 3;
                predBits >>= 2;
                int endpoint;
                if (pred == 0) {
                    if (blockX == 0) throw new IOException("Invalid ETC1S endpoint prediction (left)");
                    endpoint = previousEndpoint;
                } else if (pred == 1) {
                    if (blockY == 0) throw new IOException("Invalid ETC1S endpoint prediction (above)");
                    endpoint = above[blockX];
                } else if (pred == 2) {
                    if (blockX == 0 || blockY == 0) throw new IOException("Invalid ETC1S endpoint prediction (upper left)");
                    endpoint = above[blockX - 1];
                } else {
                    endpoint = previousEndpoint + in.decode(endpointDeltaModel);
                    if (endpoint >= endpointCount) endpoint -= endpointCount;
                    if (endpoint >= endpointCount) throw new IOException("Invalid ETC1S endpoint index");
                }
                current[blockX] = endpoint;
                previousEndpoint = endpoint;

                // 選択子の番号: 表の番号か、最近使ったものの履歴の位置か、履歴の先頭の繰り返し
                int symbol;
                if (selectorRepeat > 0) {
                    selectorRepeat--;
                    symbol = selectorCount;
                } else {
                    symbol = in.decode(selectorModel);
                    if (symbol == historyRleSymbol) {
                        final int run = in.decode(selectorRleModel);
                        if (run == SELECTOR_RLE_SYMBOLS - 1) {
                            selectorRepeat = in.readVlc(7) + SELECTOR_RLE_MIN;
                        } else {
                            selectorRepeat = (long) run + SELECTOR_RLE_MIN;
                        }
                        if (selectorRepeat > totalBlocks) throw new IOException("Invalid ETC1S selector run");
                        symbol = selectorCount;
                        selectorRepeat--;
                    }
                }
                final int selector;
                if (symbol < selectorCount) {
                    selector = symbol;
                    if (selectorHistorySize > 0) {
                        history[historyRover++] = selector;
                        if (historyRover == selectorHistorySize) historyRover = selectorHistorySize / 2;
                    }
                } else {
                    final int position = symbol - selectorCount;
                    if (position >= selectorHistorySize) throw new IOException("Invalid ETC1S selector history index");
                    selector = history[position];
                    // 使った項目を前の方へ寄せる (半分の位置と入れ替える)
                    if (position > 0) {
                        history[position] = history[position / 2];
                        history[position / 2] = selector;
                    }
                }

                // 4 色を作り、選択子で選ぶ
                final int[] modifier = MODIFIERS[endpointInten[endpoint]];
                for (int k = 0; k < 4; k++) {
                    final int delta = k == 0 ? -modifier[1] : k == 1 ? -modifier[0] : k == 2 ? modifier[0] : modifier[1];
                    final int r = clamp(expand5(endpointRgb[endpoint * 3]) + delta);
                    final int g = clamp(expand5(endpointRgb[endpoint * 3 + 1]) + delta);
                    final int b = clamp(expand5(endpointRgb[endpoint * 3 + 2]) + delta);
                    colors[k] = alpha ? g : 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                for (int y = 0; y < 4; y++) {
                    final int py = blockY * 4 + y;
                    if (py >= height) break;
                    final int row = selectors[selector * 4 + y] & 0xFF;
                    for (int x = 0; x < 4; x++) {
                        final int px = blockX * 4 + x;
                        if (px >= width) break;
                        final int c = colors[(row >> (x * 2)) & 3];
                        final int index = py * width + px;
                        argb[index] = alpha ? (argb[index] & 0x00FFFFFF) | (c << 24) : c;
                    }
                }
            }
        }
    }

    private static int expand5(int v) {
        return (v << 3) | (v >> 2);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    // ---------- ビットの読み出し ----------

    // 前から、各バイトの下位のビットから順に読む。範囲の外を使う読み出しは壊れたデータとして失敗にする
    private static final class BitReader {
        private final byte[] data;
        private final long endBit;
        private long position;

        BitReader(byte[] data, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || (long) offset + length > data.length) throw new IOException("ETC1S data out of range");
            this.data = data;
            this.position = (long) offset * 8;
            this.endBit = ((long) offset + length) * 8;
        }

        // count ビット (25 まで) を先読みする。範囲の外は 0 として読む
        int peek(int count) {
            int result = 0;
            long bit = position;
            int filled = 0;
            while (filled < count) {
                final int index = (int) (bit >> 3);
                final int shift = (int) (bit & 7);
                final int available = Math.min(8 - shift, count - filled);
                final int b = bit < endBit ? (data[index] & 0xFF) >> shift : 0;
                result |= (b & ((1 << available) - 1)) << filled;
                filled += available;
                bit += available;
            }
            return result;
        }

        void skip(int count) throws IOException {
            position += count;
            if (position > endBit) throw new IOException("Truncated ETC1S data");
        }

        int read(int count) throws IOException {
            final int value = peek(count);
            skip(count);
            return value;
        }

        // 小さな塊に分けた可変長の数 (各塊の上のビットが続きの印)。最大でも 32 ビット分で止める
        long readVlc(int chunkBits) throws IOException {
            long value = 0;
            for (int shift = 0; shift < 32; shift += chunkBits) {
                final int chunk = read(chunkBits + 1);
                value |= (long) (chunk & ((1 << chunkBits) - 1)) << shift;
                if ((chunk >> chunkBits) == 0) break;
            }
            return value & 0xFFFFFFFFL;
        }

        int decode(Huffman table) throws IOException {
            if (table == null) throw new IOException("Missing ETC1S Huffman table");
            return table.decode(this);
        }
    }

    // ---------- ハフマン符号 ----------

    // Deflate と同じ正規ハフマン符号。符号は上位のビットから順に並んでいる
    private static final class Huffman {
        private static final int MAX_LENGTH = 16;
        private static final int MAX_SYMBOLS = (1 << 14) - 1;
        private static final int FAST_BITS = 10;
        // 符号の長さの表の、長さを送る順番
        private static final int[] LENGTH_ORDER = {17, 18, 19, 20, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15, 16};

        // 長さごとの符号の数と、符号の順に並べた記号
        private final int[] counts = new int[MAX_LENGTH + 1];
        private final int[] symbols;
        // 先頭の FAST_BITS ビット (読んだ順) -> 記号 << 8 | 長さ。長い符号は -1
        private final int[] fast = new int[1 << FAST_BITS];
        private final int singleLength;

        private Huffman(int[] lengths, int total) throws IOException {
            int used = 0;
            int maxLength = 0;
            for (int s = 0; s < total; s++) {
                final int length = lengths[s];
                if (length > MAX_LENGTH) throw new IOException("Invalid ETC1S Huffman code length");
                if (length > 0) {
                    counts[length]++;
                    used++;
                    maxLength = Math.max(maxLength, length);
                }
            }
            if (used == 0) throw new IOException("Empty ETC1S Huffman table");
            // 2 つ以上の記号があるときは、符号がちょうど埋まっていなければならない (足りない・多すぎるのは壊れたデータ)
            int remaining = 1;
            for (int length = 1; length <= MAX_LENGTH; length++) {
                remaining = (remaining << 1) - counts[length];
                if (remaining < 0) throw new IOException("Over-subscribed ETC1S Huffman code");
            }
            if (used > 1 && remaining != 0) throw new IOException("Incomplete ETC1S Huffman code");
            singleLength = used == 1 ? maxLength : 0;

            final int[] offsets = new int[MAX_LENGTH + 2];
            for (int length = 1; length <= MAX_LENGTH; length++) offsets[length + 1] = offsets[length] + counts[length];
            symbols = new int[used];
            for (int s = 0; s < total; s++) {
                if (lengths[s] > 0) symbols[offsets[lengths[s]]++] = s;
            }

            // 短い符号の引き表。符号は上位のビットから読まれるので、読んだ順のビット列は符号を反転したものになる
            java.util.Arrays.fill(fast, -1);
            int code = 0;
            int index = 0;
            for (int length = 1; length <= Math.min(FAST_BITS, MAX_LENGTH); length++) {
                for (int i = 0; i < counts[length]; i++, index++, code++) {
                    final int reversed = Integer.reverse(code) >>> (32 - length);
                    for (int fill = reversed; fill < fast.length; fill += 1 << length) {
                        fast[fill] = (symbols[index] << 8) | length;
                    }
                }
                code <<= 1;
            }
        }

        int decode(BitReader in) throws IOException {
            if (singleLength > 0) {
                // 記号が 1 つだけの表は、符号の値を見ずにその長さだけ読み飛ばす
                in.skip(singleLength);
                return symbols[0];
            }
            final int entry = fast[in.peek(FAST_BITS)];
            if (entry >= 0) {
                in.skip(entry & 0xFF);
                return entry >>> 8;
            }
            // 長い符号は 1 ビットずつたどる
            final int bits = in.peek(MAX_LENGTH);
            int code = 0;
            int first = 0;
            int index = 0;
            for (int length = 1; length <= MAX_LENGTH; length++) {
                code |= (bits >> (length - 1)) & 1;
                final int count = counts[length];
                if (code >= first && code - first < count) {
                    in.skip(length);
                    return symbols[index + code - first];
                }
                index += count;
                first = (first + count) << 1;
                code <<= 1;
            }
            throw new IOException("Invalid ETC1S Huffman code");
        }

        // 符号の長さの並びを、長さの表 (これもハフマン符号) と繰り返しの記号から読む。記号が 0 個なら null
        static Huffman read(BitReader in) throws IOException {
            final int total = in.read(14);
            if (total == 0) return null;
            if (total > MAX_SYMBOLS) throw new IOException("Invalid ETC1S Huffman table");
            final int lengthCodes = in.read(5);
            if (lengthCodes < 1 || lengthCodes > LENGTH_ORDER.length) throw new IOException("Invalid ETC1S Huffman table");
            final int[] lengthLengths = new int[LENGTH_ORDER.length];
            for (int i = 0; i < lengthCodes; i++) lengthLengths[LENGTH_ORDER[i]] = in.read(3);
            final Huffman lengthTable = new Huffman(lengthLengths, LENGTH_ORDER.length);

            final int[] lengths = new int[total];
            int count = 0;
            while (count < total) {
                final int symbol = lengthTable.decode(in);
                if (symbol <= 16) {
                    lengths[count++] = symbol;
                    continue;
                }
                final int run;
                if (symbol == 17) run = in.read(3) + 3;
                else if (symbol == 18) run = in.read(7) + 11;
                else if (symbol == 19) run = in.read(2) + 3;
                else run = in.read(7) + 7;
                if (count + run > total) throw new IOException("Invalid ETC1S Huffman table");
                if (symbol >= 19) {
                    // 1 つ前の長さの繰り返し。先頭や、前の長さが 0 のときは使えない
                    if (count == 0 || lengths[count - 1] == 0) throw new IOException("Invalid ETC1S Huffman table");
                    final int previous = lengths[count - 1];
                    for (int i = 0; i < run; i++) lengths[count++] = previous;
                } else {
                    count += run;
                }
            }
            return new Huffman(lengths, total);
        }
    }
}
