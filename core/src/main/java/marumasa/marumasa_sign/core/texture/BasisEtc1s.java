package marumasa.marumasa_sign.core.texture;

import java.io.IOException;

/**
 * Basis Universal の ETC1S (KTX2 の BasisLZ 超圧縮) を RGBA に展開する。
 * Basis Universal (https://github.com/BinomialLLC/basis_universal, Apache License 2.0) の
 * transcoder (basisu_lowlevel_etc1s_transcoder) の処理を Java に書き直したもの。
 * ライセンスの全文は jar の marumasa_sign/licenses/basis_universal.txt にある。
 */
final class BasisEtc1s {

    // ETC1 の明るさの変化の表
    private static final int[][] INTEN_TABLES = {
            {-8, -2, 2, 8}, {-17, -5, 5, 17}, {-29, -9, 9, 29}, {-42, -13, 13, 42},
            {-60, -18, 18, 60}, {-80, -24, 24, 80}, {-106, -33, 33, 106}, {-183, -47, 47, 183}
    };

    private static final int COLOR5_PAL0_PREV_HI = 9;
    private static final int COLOR5_PAL1_PREV_HI = 21;
    private static final int ENDPOINT_PRED_TOTAL_SYMBOLS = 4 * 4 * 4 * 4 + 1;
    private static final int ENDPOINT_PRED_REPEAT_LAST_SYMBOL = ENDPOINT_PRED_TOTAL_SYMBOLS - 1;
    private static final int ENDPOINT_PRED_MIN_REPEAT_COUNT = 3;
    private static final int ENDPOINT_PRED_COUNT_VLC_BITS = 4;
    private static final int SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH = 3;
    private static final int SELECTOR_HISTORY_BUF_RLE_COUNT_TOTAL = 1 << 6;

    // 符号の長さを表す符号の並び順
    private static final int[] SORTED_CODELENGTH_CODES = {17, 18, 19, 20, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15, 16};
    private static final int MAX_SYMS_LOG2 = 14;
    private static final int MAX_CODE_SIZE = 31;

    // 色の端点 (5 ビットの RGB と、明るさの表の番号)
    private final int[] endpointColor;
    private final int[] endpointInten;
    // 4x4 の選択子。1 行が 1 バイト (2 ビット x 4)
    private final byte[] selectors;
    private final int selectorCount;
    private final int endpointCount;

    private final Huffman endpointPredModel;
    private final Huffman deltaEndpointModel;
    private final Huffman selectorModel;
    private final Huffman selectorHistoryRleModel;
    private final int selectorHistorySize;

    /**
     * KTX2 の全体のデータ (supercompressionGlobalData) から、端点と選択子の表を読む。
     */
    BasisEtc1s(int endpointCount, byte[] data, int endpointsOffset, int endpointsLength,
               int selectorCount, int selectorsOffset, int selectorsLength,
               int tablesOffset, int tablesLength) throws IOException {
        if (endpointCount <= 0 || selectorCount <= 0) throw new IOException("Invalid ETC1S codebook size");
        this.endpointCount = endpointCount;
        this.selectorCount = selectorCount;

        // 表
        Bits bits = new Bits(data, tablesOffset, tablesLength);
        endpointPredModel = bits.readHuffman();
        deltaEndpointModel = bits.readHuffman();
        selectorModel = bits.readHuffman();
        selectorHistoryRleModel = bits.readHuffman();
        if (endpointPredModel == null || deltaEndpointModel == null || selectorModel == null || selectorHistoryRleModel == null) {
            throw new IOException("Invalid ETC1S tables");
        }
        selectorHistorySize = bits.get(13);
        if (selectorHistorySize == 0) throw new IOException("Invalid ETC1S selector history size");

        // 端点
        bits = new Bits(data, endpointsOffset, endpointsLength);
        final Huffman color5Model0 = bits.readHuffman();
        final Huffman color5Model1 = bits.readHuffman();
        final Huffman color5Model2 = bits.readHuffman();
        final Huffman intenModel = bits.readHuffman();
        if (color5Model0 == null || color5Model1 == null || color5Model2 == null || intenModel == null) {
            throw new IOException("Invalid ETC1S endpoint models");
        }
        final boolean grayscale = bits.get(1) != 0;
        endpointColor = new int[endpointCount * 3];
        endpointInten = new int[endpointCount];
        final int[] previous = {16, 16, 16};
        int previousInten = 0;
        for (int i = 0; i < endpointCount; i++) {
            endpointInten[i] = (bits.decode(intenModel) + previousInten) & 7;
            previousInten = endpointInten[i];
            for (int c = 0; c < (grayscale ? 1 : 3); c++) {
                final int delta;
                if (previous[c] <= COLOR5_PAL0_PREV_HI) delta = bits.decode(color5Model0);
                else if (previous[c] <= COLOR5_PAL1_PREV_HI) delta = bits.decode(color5Model1);
                else delta = bits.decode(color5Model2);
                final int v = (previous[c] + delta) & 31;
                endpointColor[i * 3 + c] = v;
                previous[c] = v;
            }
            if (grayscale) {
                endpointColor[i * 3 + 1] = endpointColor[i * 3];
                endpointColor[i * 3 + 2] = endpointColor[i * 3];
            }
        }

        // 選択子
        bits = new Bits(data, selectorsOffset, selectorsLength);
        selectors = new byte[selectorCount * 4];
        if (bits.get(1) == 1) throw new IOException("ETC1S global selector codebooks are not supported");
        if (bits.get(1) == 1) throw new IOException("ETC1S hybrid selector codebooks are not supported");
        if (bits.get(1) == 1) {
            // そのまま並んでいる
            for (int i = 0; i < selectorCount * 4; i++) selectors[i] = (byte) bits.get(8);
        } else {
            final Huffman deltaModel = bits.readHuffman();
            if (selectorCount > 1 && deltaModel == null) throw new IOException("Invalid ETC1S selector model");
            final int[] previousBytes = new int[4];
            for (int i = 0; i < selectorCount; i++) {
                for (int j = 0; j < 4; j++) {
                    final int value = i == 0 ? bits.get(8) : bits.decode(deltaModel) ^ previousBytes[j];
                    previousBytes[j] = value & 0xFF;
                    selectors[i * 4 + j] = (byte) value;
                }
            }
        }
    }

    /**
     * 1 枚分のデータを展開して、ARGB の画素に書く。alpha が true なら、透明度の画像 (緑の値を透明度にする) として書く。
     */
    void decodeSlice(byte[] data, int offset, int length, int width, int height, int[] argb, boolean alpha) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final Bits bits = new Bits(data, offset, length);
        final int[] history = new int[selectorHistorySize];
        int historyRover = selectorHistorySize / 2;
        final int historyFirstSymbol = selectorCount;
        final int historyRleSymbol = selectorHistorySize + historyFirstSymbol;

        // 1 つ上の行と今の行の、端点の番号と予測の指定
        final int[][] predEndpoint = new int[2][blocksX];
        final int[][] predBits = new int[2][blocksX];
        int curPredBits = 0;
        int prevEndpointPredSym = 0;
        int endpointPredRepeatCount = 0;
        int prevEndpointIndex = 0;
        int curSelectorRleCount = 0;
        final int[] colors = new int[4];

        for (int blockY = 0; blockY < blocksY; blockY++) {
            final int cur = blockY & 1;
            for (int blockX = 0; blockX < blocksX; blockX++) {
                if ((blockX & 1) == 0) {
                    if ((blockY & 1) == 0) {
                        if (endpointPredRepeatCount > 0) {
                            endpointPredRepeatCount--;
                            curPredBits = prevEndpointPredSym;
                        } else {
                            curPredBits = bits.decode(endpointPredModel);
                            if (curPredBits == ENDPOINT_PRED_REPEAT_LAST_SYMBOL) {
                                endpointPredRepeatCount = bits.decodeVlc(ENDPOINT_PRED_COUNT_VLC_BITS) + ENDPOINT_PRED_MIN_REPEAT_COUNT - 1;
                                curPredBits = prevEndpointPredSym;
                            } else {
                                prevEndpointPredSym = curPredBits;
                            }
                        }
                        predBits[cur ^ 1][blockX] = curPredBits >> 4;
                    } else {
                        curPredBits = predBits[cur][blockX];
                    }
                }

                // 端点の番号 (左、上、左上のどれかと同じか、差分)
                final int pred = curPredBits & 3;
                curPredBits >>= 2;
                int endpointIndex;
                if (pred == 0) {
                    if (blockX == 0) throw new IOException("Invalid ETC1S data stream (0)");
                    endpointIndex = prevEndpointIndex;
                } else if (pred == 1) {
                    if (blockY == 0) throw new IOException("Invalid ETC1S data stream (1)");
                    endpointIndex = predEndpoint[cur ^ 1][blockX];
                } else if (pred == 2) {
                    if (blockX == 0 || blockY == 0) throw new IOException("Invalid ETC1S data stream (2)");
                    endpointIndex = predEndpoint[cur ^ 1][blockX - 1];
                } else {
                    endpointIndex = bits.decode(deltaEndpointModel) + prevEndpointIndex;
                    if (endpointIndex >= endpointCount) endpointIndex -= endpointCount;
                }
                predEndpoint[cur][blockX] = endpointIndex;
                prevEndpointIndex = endpointIndex;

                // 選択子の番号 (最近使ったものの履歴と、その繰り返し)
                int selectorSym;
                if (curSelectorRleCount > 0) {
                    curSelectorRleCount--;
                    selectorSym = selectorCount;
                } else {
                    selectorSym = bits.decode(selectorModel);
                    if (selectorSym == historyRleSymbol) {
                        final int run = bits.decode(selectorHistoryRleModel);
                        if (run == SELECTOR_HISTORY_BUF_RLE_COUNT_TOTAL - 1) {
                            curSelectorRleCount = bits.decodeVlc(7) + SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH;
                        } else {
                            curSelectorRleCount = run + SELECTOR_HISTORY_BUF_RLE_COUNT_THRESH;
                        }
                        if (curSelectorRleCount > blocksX * blocksY) throw new IOException("Invalid ETC1S data stream (3)");
                        selectorSym = selectorCount;
                        curSelectorRleCount--;
                    }
                }
                int selectorIndex;
                if (selectorSym >= selectorCount) {
                    final int historyIndex = selectorSym - selectorCount;
                    if (historyIndex >= selectorHistorySize) throw new IOException("Invalid ETC1S data stream (4)");
                    selectorIndex = history[historyIndex];
                    if (historyIndex != 0) {
                        // 使ったものを前の方へ寄せる
                        final int swap = history[historyIndex / 2];
                        history[historyIndex / 2] = history[historyIndex];
                        history[historyIndex] = swap;
                    }
                } else {
                    selectorIndex = selectorSym;
                    history[historyRover++] = selectorIndex;
                    if (historyRover == selectorHistorySize) historyRover = selectorHistorySize / 2;
                }
                if (endpointIndex < 0 || endpointIndex >= endpointCount || selectorIndex < 0 || selectorIndex >= selectorCount) {
                    throw new IOException("Invalid ETC1S data stream (5)");
                }

                // 4 色を作り、選択子で選ぶ
                final int inten = endpointInten[endpointIndex];
                for (int k = 0; k < 4; k++) {
                    final int[] table = INTEN_TABLES[inten];
                    final int r = clamp(expand5(endpointColor[endpointIndex * 3]) + table[k]);
                    final int g = clamp(expand5(endpointColor[endpointIndex * 3 + 1]) + table[k]);
                    final int b = clamp(expand5(endpointColor[endpointIndex * 3 + 2]) + table[k]);
                    colors[k] = alpha ? g : (0xFF000000 | (r << 16) | (g << 8) | b);
                }
                for (int y = 0; y < 4; y++) {
                    final int py = blockY * 4 + y;
                    if (py >= height) break;
                    final int row = selectors[selectorIndex * 4 + y] & 0xFF;
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

    // ---------- ビットの読み出しとハフマン符号 ----------

    // 正規ハフマン符号。長さごとの最初の符号と、符号の順に並べた記号
    private static final class Huffman {
        final int[] firstCode = new int[MAX_CODE_SIZE + 2];
        final int[] firstIndex = new int[MAX_CODE_SIZE + 2];
        final int[] counts = new int[MAX_CODE_SIZE + 2];
        int[] symbols;
        int maxLength;

        static Huffman build(int[] codeSizes, int total) throws IOException {
            final Huffman table = new Huffman();
            int used = 0;
            for (int i = 0; i < total; i++) {
                final int size = codeSizes[i];
                if (size > MAX_CODE_SIZE) throw new IOException("Invalid Huffman code size");
                if (size > 0) {
                    table.counts[size]++;
                    used++;
                    table.maxLength = Math.max(table.maxLength, size);
                }
            }
            if (used == 0) return null;
            // 符号の割り当て (短いものから順に)
            long code = 0;
            int index = 0;
            for (int length = 1; length <= MAX_CODE_SIZE; length++) {
                table.firstCode[length] = (int) code;
                table.firstIndex[length] = index;
                code = (code + table.counts[length]) << 1;
                index += table.counts[length];
            }
            if (used > 1 && code != (1L << (MAX_CODE_SIZE + 1))) throw new IOException("Invalid Huffman code sizes");
            table.symbols = new int[used];
            final int[] next = new int[MAX_CODE_SIZE + 2];
            for (int length = 1; length <= MAX_CODE_SIZE; length++) next[length] = table.firstIndex[length];
            for (int s = 0; s < total; s++) {
                if (codeSizes[s] > 0) table.symbols[next[codeSizes[s]]++] = s;
            }
            return table;
        }
    }

    // 前から下位ビット順に読む。範囲を超えた分は 0 として読む
    private static final class Bits {
        private final byte[] data;
        private final int end;
        private long position;

        Bits(byte[] data, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || (long) offset + length > data.length) throw new IOException("ETC1S data out of range");
            this.data = data;
            this.end = offset + length;
            this.position = (long) offset * 8;
        }

        int get(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                final int index = (int) (position >> 3);
                final int bit = index < end ? (data[index] >> (position & 7)) & 1 : 0;
                result |= bit << i;
                position++;
            }
            return result;
        }

        int decode(Huffman table) throws IOException {
            if (table == null) throw new IOException("Missing Huffman table");
            if (table.symbols.length == 1) {
                // 記号が 1 つだけの表は、その符号の長さのビットを読み飛ばす
                get(table.maxLength);
                return table.symbols[0];
            }
            // 符号は上位のビットから 1 ビットずつ並んでいる
            int code = 0;
            for (int length = 1; length <= table.maxLength; length++) {
                code = (code << 1) | get(1);
                final int offset = code - table.firstCode[length];
                if (table.counts[length] > 0 && offset >= 0 && offset < table.counts[length]) {
                    return table.symbols[table.firstIndex[length] + offset];
                }
            }
            throw new IOException("Invalid Huffman code");
        }

        int decodeVlc(int chunkBits) {
            final int chunkSize = 1 << chunkBits;
            int value = 0;
            int shift = 0;
            for (;;) {
                final int s = get(chunkBits + 1);
                value |= (s & (chunkSize - 1)) << shift;
                shift += chunkBits;
                if ((s & chunkSize) == 0 || shift >= 32) break;
            }
            return value;
        }

        Huffman readHuffman() throws IOException {
            final int totalUsed = get(MAX_SYMS_LOG2);
            if (totalUsed == 0) return null;
            final int lengthCodes = get(5);
            if (lengthCodes < 1 || lengthCodes > 21) throw new IOException("Invalid Huffman table");
            final int[] lengthSizes = new int[21];
            for (int i = 0; i < lengthCodes; i++) lengthSizes[SORTED_CODELENGTH_CODES[i]] = get(3);
            final Huffman lengthTable = Huffman.build(lengthSizes, 21);
            if (lengthTable == null) throw new IOException("Invalid Huffman table");
            final int[] sizes = new int[totalUsed];
            int cur = 0;
            while (cur < totalUsed) {
                final int c = decode(lengthTable);
                if (c <= 16) {
                    sizes[cur++] = c;
                } else if (c == 17) {
                    cur += get(3) + 3;
                } else if (c == 18) {
                    cur += get(7) + 11;
                } else {
                    if (cur == 0) throw new IOException("Invalid Huffman table");
                    int count = c == 19 ? get(2) + 3 : get(7) + 7;
                    final int previous = sizes[cur - 1];
                    if (previous == 0) throw new IOException("Invalid Huffman table");
                    while (count-- > 0) {
                        if (cur >= totalUsed) throw new IOException("Invalid Huffman table");
                        sizes[cur++] = previous;
                    }
                }
            }
            if (cur != totalUsed) throw new IOException("Invalid Huffman table");
            return Huffman.build(sizes, totalUsed);
        }
    }
}
