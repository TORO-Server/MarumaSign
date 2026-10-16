package marumasa.marumasa_sign.core.texture;

/**
 * Basis Universal の UASTC (LDR 4x4) のブロックを RGBA に展開する。
 * Basis Universal (https://github.com/BinomialLLC/basis_universal, Apache License 2.0) の
 * transcoder (unpack_uastc) の処理と表を Java に書き直したもの。
 * ライセンスの全文は jar の marumasa_sign/licenses/basis_universal.txt にある。
 */
final class Uastc {

    private static final int TOTAL_MODES = 19;
    private static final int MODE_SOLID_COLOR = 8;

    private static final int[] HUFF_CODE_SIZES = {4, 6, 5, 5, 5, 5, 5, 5, 5, 5, 3, 2, 3, 5, 5, 7, 6, 6, 4, 7};
    // 先頭の 7 ビットからモードを引く表
    private static final int[] HUFF_MODES = {
            11, 0, 10, 3, 11, 15, 12, 7, 11, 18, 10, 5, 11, 14, 12, 9, 11, 0, 10, 4, 11, 16, 12, 8, 11, 18, 10, 6, 11, 2, 12, 13,
            11, 0, 10, 3, 11, 17, 12, 7, 11, 18, 10, 5, 11, 14, 12, 9, 11, 0, 10, 4, 11, 1, 12, 8, 11, 18, 10, 6, 11, 2, 12, 13,
            11, 0, 10, 3, 11, 19, 12, 7, 11, 18, 10, 5, 11, 14, 12, 9, 11, 0, 10, 4, 11, 16, 12, 8, 11, 18, 10, 6, 11, 2, 12, 13,
            11, 0, 10, 3, 11, 17, 12, 7, 11, 18, 10, 5, 11, 14, 12, 9, 11, 0, 10, 4, 11, 1, 12, 8, 11, 18, 10, 6, 11, 2, 12, 13
    };

    private static final int[] WEIGHT_BITS = {4, 2, 3, 2, 2, 3, 2, 2, 0, 2, 4, 2, 3, 1, 2, 4, 2, 2, 5};
    private static final int[] ENDPOINT_RANGES = {19, 20, 8, 7, 12, 20, 18, 12, 0, 8, 13, 13, 19, 20, 20, 20, 20, 20, 11};
    private static final int[] SUBSETS = {1, 1, 2, 3, 2, 1, 1, 2, 0, 2, 1, 1, 1, 1, 1, 1, 2, 1, 1};
    private static final int[] PLANES = {1, 1, 1, 1, 1, 1, 2, 1, 0, 1, 1, 2, 1, 2, 1, 1, 1, 2, 1};
    private static final int[] COMPS = {3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 2, 2, 2, 3};
    private static final int[] TOTAL_HINT_BITS = {15, 15, 15, 15, 15, 15, 15, 15, 0, 23, 17, 17, 17, 23, 23, 23, 23, 23, 15};

    // ASTC の値の範囲ごとの、ビット・3 進・5 進の数
    private static final int[][] BISE_RANGES = {
            {1, 0, 0}, {0, 1, 0}, {2, 0, 0}, {0, 0, 1}, {1, 1, 0}, {3, 0, 0}, {1, 0, 1}, {2, 1, 0},
            {4, 0, 0}, {2, 0, 1}, {3, 1, 0}, {5, 0, 0}, {3, 0, 1}, {4, 1, 0}, {6, 0, 0}, {4, 0, 1},
            {5, 1, 0}, {7, 0, 0}, {5, 0, 1}, {6, 1, 0}, {8, 0, 0}
    };
    // 3 進・5 進を含む範囲の端点の復元に使う値 (ASTC の仕様から)
    private static final String[] UNQUANT_B = {"", "", "", "", "000000000", "", "000000000", "b000b0bb0", "", "b0000bb00",
            "cb000cbcb", "", "cb0000cbc", "dcb000dcb", "", "dcb0000dc", "edcb000ed", "", "edcb0000e", "fedcb000f", ""};
    private static final int[] UNQUANT_C = {0, 0, 0, 0, 204, 0, 113, 93, 0, 54, 44, 0, 26, 22, 0, 13, 11, 0, 6, 5, 0};
    // [範囲][符号化された値] -> 0 - 255 の端点
    private static final int[][] UNQUANT = new int[21][];

    private static final int[][] WEIGHTS = {
            null, {0, 64}, {0, 21, 43, 64}, {0, 9, 18, 27, 37, 46, 55, 64},
            {0, 4, 8, 12, 17, 21, 25, 29, 35, 39, 43, 47, 52, 56, 60, 64},
            {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64}
    };

    private static final int[][] PATTERNS2 = {
            {0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1}, {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}, {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1},
            {1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 0, 0}, {0, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0}, {1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1}, {1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0},
            {1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0},
            {1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1}, {0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0}, {0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 1}, {1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0}, {0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0},
            {1, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 1}, {0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0}, {1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1}, {1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0},
            {1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0}, {1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0}
    };
    private static final int[][] PATTERNS3 = {
            {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 1, 1, 2, 2}, {1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 2, 2, 2, 2}, {1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2}, {1, 1, 1, 1, 2, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2, 0}, {0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2, 0, 1, 1, 2}, {0, 2, 1, 1, 0, 2, 1, 1, 0, 2, 1, 1, 0, 2, 1, 1}, {2, 0, 0, 0, 2, 0, 0, 0, 2, 1, 1, 1, 2, 1, 1, 1},
            {2, 0, 1, 2, 2, 0, 1, 2, 2, 0, 1, 2, 2, 0, 1, 2}, {1, 1, 1, 1, 0, 0, 0, 0, 2, 2, 2, 2, 1, 1, 1, 1}, {0, 0, 2, 2, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 2, 2}
    };
    // モード 7 (BC7 の 3 つの分け方のうち 2 つを ASTC で表したもの)
    private static final int[][] PATTERNS2_MODE7 = {
            {0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0}, {1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1},
            {1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1}, {0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0}, {0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, {0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1},
            {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0}, {0, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 0}, {1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0},
            {0, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 0}, {1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0},
            {1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 0}, {0, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0}, {1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0}
    };
    // 分け方ごとの、各部分の最初の画素 (重みが 1 ビット少ない)
    private static final int[][] ANCHORS2 = {
            {0, 2}, {0, 3}, {1, 0}, {0, 3}, {7, 0}, {0, 2}, {3, 0}, {7, 0},
            {0, 11}, {2, 0}, {0, 7}, {11, 0}, {3, 0}, {8, 0}, {0, 4}, {12, 0},
            {1, 0}, {8, 0}, {0, 1}, {0, 2}, {0, 4}, {8, 0}, {1, 0}, {0, 2},
            {4, 0}, {0, 1}, {4, 0}, {1, 0}, {4, 0}, {1, 0}
    };
    private static final int[][] ANCHORS3 = {
            {0, 8, 10}, {8, 0, 12}, {4, 0, 12}, {8, 0, 4}, {3, 0, 2}, {0, 1, 3}, {0, 2, 1}, {1, 9, 0}, {1, 2, 0}, {4, 0, 8}, {0, 6, 2}
    };
    private static final int[][] ANCHORS2_MODE7 = {
            {0, 4}, {0, 2}, {2, 0}, {0, 7}, {8, 0}, {0, 1}, {0, 3}, {0, 1}, {2, 0}, {0, 1}, {0, 8}, {2, 0}, {0, 1}, {0, 7}, {12, 0}, {2, 0}, {9, 0}, {0, 2}, {4, 0}
    };

    static {
        for (int range = 0; range < 21; range++) {
            final int bits = BISE_RANGES[range][0];
            final int trits = BISE_RANGES[range][1];
            final int quints = BISE_RANGES[range][2];
            if ((trits != 0 || quints != 0) && UNQUANT_C[range] == 0) continue;
            final int levels = (1 + 2 * trits + 4 * quints) << bits;
            UNQUANT[range] = new int[256];
            for (int value = 0; value < levels; value++) {
                UNQUANT[range][value] = unquantEndpoint(value & ((1 << bits) - 1), value >> bits, range);
            }
        }
    }

    private Uastc() {
    }

    // ASTC の仕様にある、端点の値を 0 - 255 に戻す計算
    private static int unquantEndpoint(int packedBits, int packedTq, int range) {
        final int bits = BISE_RANGES[range][0];
        final int trits = BISE_RANGES[range][1];
        final int quints = BISE_RANGES[range][2];
        if (trits == 0 && quints == 0) {
            int value = 0;
            int bitsLeft = 8;
            while (bitsLeft > 0) {
                int v = packedBits;
                final int n = Math.min(bitsLeft, bits);
                if (n < bits) v >>= (bits - n);
                value |= v << (bitsLeft - n);
                bitsLeft -= n;
            }
            return value;
        }
        final int a = (packedBits & 1) != 0 ? 511 : 0;
        final int c = UNQUANT_C[range];
        final String pattern = UNQUANT_B[range];
        int b = 0;
        for (int i = 0; i < 9; i++) {
            b <<= 1;
            final char ch = pattern.charAt(i);
            if (ch != '0') b |= (packedBits >> (ch - 'a')) & 1;
        }
        int value = packedTq * c + b;
        value ^= a;
        return (a & 0x80) | (value >> 2);
    }

    private static int interpolate(int low, int high, int weight, boolean srgb) {
        if (srgb) {
            low = (low << 8) | 0x80;
            high = (high << 8) | 0x80;
        } else {
            low = (low << 8) | low;
            high = (high << 8) | high;
        }
        return ((low * (64 - weight) + high * weight + 32) >> 6) >> 8;
    }

    // 下位ビットから順に読む (ブロックの外は 0)
    private static int bits(byte[] data, int pos, int[] offset, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            final int bit = offset[0] + i;
            if (bit < 128) result |= ((data[pos + (bit >> 3)] >> (bit & 7)) & 1) << i;
        }
        offset[0] += count;
        return result;
    }

    /**
     * 16 バイトのブロックを展開して、16 画素分の ARGB を行ごとに書く。不正なブロックなら false。
     */
    static boolean decode(byte[] data, int pos, boolean srgb, int[] out) {
        final int mode = HUFF_MODES[data[pos] & 127];
        if (mode >= TOTAL_MODES) return false;
        final int[] offset = {HUFF_CODE_SIZES[mode]};

        if (mode == MODE_SOLID_COLOR) {
            final int r = bits(data, pos, offset, 8);
            final int g = bits(data, pos, offset, 8);
            final int b = bits(data, pos, offset, 8);
            final int a = bits(data, pos, offset, 8);
            java.util.Arrays.fill(out, (a << 24) | (r << 16) | (g << 8) | b);
            return true;
        }

        // ほかの形式に変換するときの手がかりは使わない
        offset[0] += TOTAL_HINT_BITS[mode];

        int subsets = 1;
        int commonPattern = 0;
        if (mode == 2 || mode == 4 || mode == 7 || mode == 9 || mode == 16) {
            commonPattern = bits(data, pos, offset, 5);
            subsets = 2;
        } else if (mode == 3) {
            commonPattern = bits(data, pos, offset, 4);
            subsets = 3;
        }
        int[] pattern = null;
        int[] anchors = {0, 0, 0};
        if (subsets == 3) {
            if (commonPattern >= PATTERNS3.length) return false;
            pattern = PATTERNS3[commonPattern];
            anchors = ANCHORS3[commonPattern];
        } else if (subsets == 2) {
            if (mode == 7) {
                if (commonPattern >= PATTERNS2_MODE7.length) return false;
                pattern = PATTERNS2_MODE7[commonPattern];
                anchors = ANCHORS2_MODE7[commonPattern];
            } else {
                if (commonPattern >= PATTERNS2.length) return false;
                pattern = PATTERNS2[commonPattern];
                anchors = ANCHORS2[commonPattern];
            }
        }

        int planes = 1;
        int ccs = -1;
        if (mode == 6 || mode == 11 || mode == 13) {
            ccs = bits(data, pos, offset, 2);
            planes = 2;
        } else if (mode == 17) {
            ccs = 3;
            planes = 2;
        }

        // 端点 (3 進や 5 進の桁をまとめた部分が先に、そのあとに各値の下位ビット)
        final int comps = COMPS[mode];
        final int weightBits = WEIGHT_BITS[mode];
        final int totalValues = comps * 2 * subsets;
        final int range = ENDPOINT_RANGES[mode];
        final int epBits = BISE_RANGES[range][0];
        final int epTrits = BISE_RANGES[range][1];
        final int epQuints = BISE_RANGES[range][2];
        int totalTqs = 0, bundleSize = 0, mul = 0;
        if (epTrits != 0) {
            totalTqs = (totalValues + 4) / 5;
            bundleSize = 5;
            mul = 3;
        } else if (epQuints != 0) {
            totalTqs = (totalValues + 2) / 3;
            bundleSize = 3;
            mul = 5;
        }
        final int[] tqValues = new int[8];
        for (int i = 0; i < totalTqs; i++) {
            int numBits = epTrits != 0 ? 8 : 7;
            if (i == totalTqs - 1) {
                final int remaining = totalValues - (totalTqs - 1) * bundleSize;
                if (epTrits != 0) {
                    if (remaining == 1) numBits = 2;
                    else if (remaining == 2) numBits = 4;
                    else if (remaining == 3) numBits = 5;
                    else if (remaining == 4) numBits = 7;
                } else {
                    if (remaining == 1) numBits = 3;
                    else if (remaining == 2) numBits = 5;
                }
            }
            tqValues[i] = bits(data, pos, offset, numBits);
        }
        final int[] endpoints = new int[18];
        int accum = 0, accumRemaining = 0, nextTq = 0;
        for (int i = 0; i < totalValues; i++) {
            int value = bits(data, pos, offset, epBits);
            if (totalTqs != 0) {
                if (accumRemaining == 0) {
                    accum = tqValues[nextTq++];
                    accumRemaining = bundleSize;
                }
                final int v = accum % mul;
                accum /= mul;
                accumRemaining--;
                value |= v << epBits;
            }
            endpoints[i] = value;
        }

        // 重み (各部分の最初の画素は 1 ビット少ない)
        final int[] weights = new int[32];
        if (planes == 2) {
            weights[0] = bits(data, pos, offset, weightBits - 1);
            weights[1] = bits(data, pos, offset, weightBits - 1);
            for (int i = 2; i < 32; i++) weights[i] = bits(data, pos, offset, weightBits);
        } else {
            for (int i = 0; i < 16; i++) {
                final boolean anchor = i == anchors[0] || (subsets > 1 && i == anchors[1]) || (subsets > 2 && i == anchors[2]);
                weights[i] = bits(data, pos, offset, anchor ? weightBits - 1 : weightBits);
            }
        }

        // 端点を 0 - 255 にして、重みごとの色を作る
        final int[] unquant = UNQUANT[range];
        if (unquant == null) return false;
        final int compsUsed = Math.min(4, comps);
        final int levels = 1 << weightBits;
        final int[] table = WEIGHTS[weightBits];
        final int[][][] colors = new int[subsets][levels][4];
        for (int s = 0; s < subsets; s++) {
            final int[] low = new int[4];
            final int[] high = new int[4];
            if (compsUsed == 2) {
                final int l0 = unquant[endpoints[s * 4]];
                final int l1 = unquant[endpoints[s * 4 + 1]];
                final int a0 = unquant[endpoints[s * 4 + 2]];
                final int a1 = unquant[endpoints[s * 4 + 3]];
                low[0] = low[1] = low[2] = l0;
                high[0] = high[1] = high[2] = l1;
                low[3] = a0;
                high[3] = a1;
            } else {
                for (int c = 0; c < 4; c++) {
                    if (c < compsUsed) {
                        low[c] = unquant[endpoints[s * compsUsed * 2 + c * 2]];
                        high[c] = unquant[endpoints[s * compsUsed * 2 + c * 2 + 1]];
                    } else {
                        low[c] = high[c] = 255;
                    }
                }
            }
            for (int l = 0; l < levels; l++) {
                for (int c = 0; c < 4; c++) {
                    colors[s][l][c] = (compsUsed == 2 || c < compsUsed) ? interpolate(low[c], high[c], table[l], srgb) : 255;
                }
            }
        }

        for (int i = 0; i < 16; i++) {
            final int r, g, b, a;
            if (planes == 1) {
                final int[] c = colors[pattern == null ? 0 : pattern[i]][weights[i]];
                r = c[0];
                g = c[1];
                b = c[2];
                a = c[3];
            } else {
                final int[] c0 = colors[0][weights[i * 2]];
                final int[] c1 = colors[0][weights[i * 2 + 1]];
                r = ccs == 0 ? c1[0] : c0[0];
                g = ccs == 1 ? c1[1] : c0[1];
                b = ccs == 2 ? c1[2] : c0[2];
                a = ccs == 3 ? c1[3] : c0[3];
            }
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return true;
    }
}
