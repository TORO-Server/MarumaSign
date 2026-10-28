package marumasa.marumasa_sign.core.texture;

/**
 * Basis Universal の UASTC (LDR 4x4) のブロックを RGBA に展開する。
 * 「UASTC LDR 4x4 Texture Specification」(Binomial, パブリックドメイン) のブロックの並びと表、
 * および Khronos Data Format Specification の ASTC の節 (端点と重みの復元、補間) に沿って書いたもの。
 */
final class Uastc {

    private static final int MODE_COUNT = 19;
    private static final int MODE_SOLID_COLOR = 8;

    // モードのハフマン符号 {符号, ビット数}。最初のビットが最下位。19 番は将来のために空けてある
    private static final int[][] MODE_CODES = {
            {0x1, 4}, {0x35, 6}, {0x1D, 5}, {0x3, 5}, {0x13, 5}, {0xB, 5}, {0x1B, 5}, {0x7, 5},
            {0x17, 5}, {0xF, 5}, {0x2, 3}, {0x0, 2}, {0x6, 3}, {0x1F, 5}, {0xD, 5}, {0x5, 7},
            {0x15, 6}, {0x25, 6}, {0x9, 4}, {0x45, 7}
    };

    // モードごとの値 (仕様の「UASTC Mode Description Tables」)
    private static final int[] WEIGHT_BITS = {4, 2, 3, 2, 2, 3, 2, 2, 0, 2, 4, 2, 3, 1, 2, 4, 2, 2, 5};
    private static final int[] ENDPOINT_RANGE = {19, 20, 8, 7, 12, 20, 18, 12, 0, 8, 13, 13, 19, 20, 20, 20, 20, 20, 11};
    private static final int[] SUBSETS = {1, 1, 2, 3, 2, 1, 1, 2, 0, 2, 1, 1, 1, 1, 1, 1, 2, 1, 1};
    private static final int[] PLANES = {1, 1, 1, 1, 1, 1, 2, 1, 0, 1, 1, 2, 1, 2, 1, 1, 1, 2, 1};
    private static final int[] COMPONENTS = {3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 2, 2, 2, 3};
    private static final int[] HAS_ETC1_BIAS = {1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1};
    private static final int[] HAS_BC1_HINT1 = {1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1};
    private static final int[] HAS_ALPHA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0};

    // ASTC の値の範囲 (BISE) ごとの {ビット数, 3 進の桁があるか, 5 進の桁があるか}
    private static final int[][] RANGES = {
            {1, 0, 0}, {0, 1, 0}, {2, 0, 0}, {0, 0, 1}, {1, 1, 0}, {3, 0, 0}, {1, 0, 1},
            {2, 1, 0}, {4, 0, 0}, {2, 0, 1}, {3, 1, 0}, {5, 0, 0}, {3, 0, 1}, {4, 1, 0},
            {6, 0, 0}, {4, 0, 1}, {5, 1, 0}, {7, 0, 0}, {5, 0, 1}, {6, 1, 0}, {8, 0, 0}
    };

    // 分け方 (仕様の表の写し)。数字が画素ごとの部分の番号
    private static final String[] PATTERNS2 = {
            "0011001100110011", "0001000100010001", "1000100010001000", "0001001100110111",
            "1111111011101100", "0011011101111111", "1110110010000000", "1111111011001000",
            "0000000000010011", "1100100000000000", "0000000101111111", "1111111111101000",
            "1110100000000000", "1111111100000000", "0000111111111111", "1111111111110000",
            "1000111011111111", "1111111101110001", "0111001100010000", "0011000100000000",
            "0000100011001110", "1111111101110011", "1000110011001110", "0011000100010000",
            "1111011101110011", "0110011001100110", "1111000000001111", "1010101010101010",
            "1111000011110000", "1001001101101100"
    };
    private static final String[] PATTERNS3 = {
            "0000000011221122", "1111111100002222", "1111000000002222", "1111222200000000",
            "1120112011201120", "0112011201120112", "0211021102110211", "2000200021112111",
            "2012201220122012", "1111000022221111", "0022001100110022"
    };
    // モード 7 の分け方 (BC7 の 3 つの分け方のうち、2 つの部分だけを使うもの)
    private static final String[] PATTERNS2_MODE7 = {
            "0000111100000000", "0010001000100010", "1100110010000000", "0000000100110011",
            "1111111100001111", "0100010001000100", "0001001111111111", "0111001100110011",
            "1100000000111100", "0111011100000000", "0000000011101110", "1100000000001100",
            "0111001100000000", "0000000111111111", "1111111111110110", "1100110011001000",
            "1111111110001000", "0011011011001000", "1111011100000000"
    };

    // 3 進の桁 (5 つまで)、5 進の桁 (3 つまで) をまとめた値を表すのに足りる最小のビット数
    private static final int[] TRIT_GROUP_BITS = {0, 2, 4, 5, 7, 8};
    private static final int[] QUINT_GROUP_BITS = {0, 3, 5, 7};

    // [範囲][値] -> 0 - 255 の端点。範囲に収まらない値 (壊れたデータでだけ現れる) は 0 のままにしておく
    private static final int[][] ENDPOINT_VALUES = new int[RANGES.length][];
    // [重みのビット数][値] -> 0 - 64 の重み
    private static final int[][] WEIGHT_VALUES = new int[6][];
    // 先頭の 7 ビット -> モード (使わない符号は MODE_COUNT 以上)
    private static final int[] MODE_LOOKUP = new int[128];
    // モードごとの、変換の手がかりのビット数 (展開には使わないので読み飛ばす)
    private static final int[] HINT_BITS = new int[MODE_COUNT];

    static {
        for (int x = 0; x < 128; x++) {
            MODE_LOOKUP[x] = MODE_COUNT;
            for (int mode = 0; mode < MODE_CODES.length; mode++) {
                if ((x & ((1 << MODE_CODES[mode][1]) - 1)) == MODE_CODES[mode][0]) MODE_LOOKUP[x] = mode;
            }
        }
        for (int range = 0; range < RANGES.length; range++) {
            final int bits = RANGES[range][0];
            final int levels = (RANGES[range][1] != 0 ? 3 : RANGES[range][2] != 0 ? 5 : 1) << bits;
            // 端点に使わない、ビットのない範囲は作らない
            if (bits == 0) continue;
            ENDPOINT_VALUES[range] = new int[256];
            for (int value = 0; value < levels; value++) {
                ENDPOINT_VALUES[range][value] = unquantizeEndpoint(range, value);
            }
        }
        for (int bits = 1; bits <= 5; bits++) {
            WEIGHT_VALUES[bits] = new int[1 << bits];
            for (int value = 0; value < (1 << bits); value++) {
                // 6 ビットになるまで上位のビットを繰り返し、32 を超えたら 1 を足す
                int w = 0;
                for (int filled = 0; filled < 6; filled += bits) w = (w << bits) | value;
                final int extra = (((6 + bits - 1) / bits) * bits) - 6;
                w >>= extra;
                if (w > 32) w++;
                WEIGHT_VALUES[bits][value] = w;
            }
        }
        for (int mode = 0; mode < MODE_COUNT; mode++) {
            if (mode == MODE_SOLID_COLOR) continue;
            HINT_BITS[mode] = 1 + HAS_BC1_HINT1[mode] + 1 + 1 + 3 + 3 + HAS_ETC1_BIAS[mode] * 5 + HAS_ALPHA[mode] * 8;
        }
    }

    private Uastc() {
    }

    // ASTC の、端点の値を 0 - 255 に戻す計算
    private static int unquantizeEndpoint(int range, int value) {
        final int bits = RANGES[range][0];
        final boolean trit = RANGES[range][1] != 0;
        final boolean quint = RANGES[range][2] != 0;
        if (!trit && !quint) {
            // ビットだけの値は、上位のビットを繰り返して 8 ビットにする
            int result = 0;
            for (int filled = 0; filled < 8; filled += bits) result = (result << bits) | value;
            return (result >> ((((8 + bits - 1) / bits) * bits) - 8)) & 0xFF;
        }
        final int d = value >> bits;
        final int low = value & ((1 << bits) - 1);
        final int a = (low & 1) != 0 ? 0x1FF : 0;
        // B は、値の下位ビット (a, b, c, ...) を仕様の並びで置いた 9 ビット
        final String pattern;
        final int c;
        switch (bits * 2 + (trit ? 0 : 1)) {
            case 2: pattern = "000000000"; c = 204; break;
            case 3: pattern = "000000000"; c = 113; break;
            case 4: pattern = "b000b0bb0"; c = 93; break;
            case 5: pattern = "b0000bb00"; c = 54; break;
            case 6: pattern = "cb000cbcb"; c = 44; break;
            case 7: pattern = "cb0000cbc"; c = 26; break;
            case 8: pattern = "dcb000dcb"; c = 22; break;
            case 9: pattern = "dcb0000dc"; c = 13; break;
            case 10: pattern = "edcb000ed"; c = 11; break;
            case 11: pattern = "edcb0000e"; c = 6; break;
            case 12: pattern = "fedcb000f"; c = 5; break;
            default: return 0;
        }
        int b = 0;
        for (int i = 0; i < 9; i++) {
            final char ch = pattern.charAt(i);
            b = (b << 1) | (ch == '0' ? 0 : (low >> (ch - 'a')) & 1);
        }
        int unq = d * c + b;
        unq ^= a;
        return (a & 0x80) | (unq >> 2);
    }

    // ASTC の補間。端点を 16 ビットに広げてから重みで混ぜ、上位 8 ビットを使う
    private static int interpolate(int low, int high, int weight, boolean srgb) {
        low = srgb ? (low << 8) | 0x80 : (low << 8) | low;
        high = srgb ? (high << 8) | 0x80 : (high << 8) | high;
        return ((low * (64 - weight) + high * weight + 32) >> 6) >> 8;
    }

    // ブロックの中を、下位のビットから順に読む
    private static final class Reader {
        private final long lo;
        private final long hi;
        int position;

        Reader(byte[] data, int pos) {
            long l = 0, h = 0;
            for (int i = 7; i >= 0; i--) {
                l = (l << 8) | (data[pos + i] & 0xFFL);
                h = (h << 8) | (data[pos + 8 + i] & 0xFFL);
            }
            lo = l;
            hi = h;
        }

        int read(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                final int bit = position + i;
                // ブロックの外 (128 ビット目以降) は 0 として読む
                final long word = bit < 64 ? lo : bit < 128 ? hi : 0;
                result |= (int) ((word >>> (bit & 63)) & 1) << i;
            }
            position += count;
            return result;
        }
    }

    /**
     * 16 バイトのブロックを展開して、16 画素分の ARGB を行ごとに書く。不正なブロックなら false。
     */
    static boolean decode(byte[] data, int pos, boolean srgb, int[] out) {
        final int mode = MODE_LOOKUP[data[pos] & 0x7F];
        if (mode >= MODE_COUNT) return false;
        final Reader in = new Reader(data, pos);
        in.position = MODE_CODES[mode][1];

        if (mode == MODE_SOLID_COLOR) {
            final int r = in.read(8);
            final int g = in.read(8);
            final int b = in.read(8);
            final int a = in.read(8);
            java.util.Arrays.fill(out, 0, 16, (a << 24) | (r << 16) | (g << 8) | b);
            return true;
        }
        in.position += HINT_BITS[mode];

        // 分け方
        final int subsets = SUBSETS[mode];
        String pattern = null;
        if (subsets > 1) {
            final int index = in.read(subsets == 3 ? 4 : 5);
            final String[] table = subsets == 3 ? PATTERNS3 : mode == 7 ? PATTERNS2_MODE7 : PATTERNS2;
            if (index >= table.length) return false;
            pattern = table[index];
        }
        // 2 つ目の重みで補間するチャンネル
        final int planes = PLANES[mode];
        int dualChannel = -1;
        if (planes == 2) dualChannel = mode == 17 ? 3 : in.read(2);

        // 端点。3 進または 5 進の桁をまとめた値が先に、そのあとに各値の下位ビットが並ぶ
        final int components = COMPONENTS[mode];
        final int valueCount = components * 2 * subsets;
        final int range = ENDPOINT_RANGE[mode];
        final int bits = RANGES[range][0];
        final int base = RANGES[range][1] != 0 ? 3 : RANGES[range][2] != 0 ? 5 : 0;
        final int perGroup = base == 3 ? 5 : 3;
        final int groups = base == 0 ? 0 : (valueCount + perGroup - 1) / perGroup;
        final int[] packed = new int[groups];
        for (int g = 0; g < groups; g++) {
            final int digits = Math.min(perGroup, valueCount - g * perGroup);
            packed[g] = in.read(base == 3 ? TRIT_GROUP_BITS[digits] : QUINT_GROUP_BITS[digits]);
        }
        final int[] endpoint = new int[valueCount];
        final int[] table = ENDPOINT_VALUES[range];
        for (int i = 0; i < valueCount; i++) {
            int value = in.read(bits);
            if (base != 0) {
                int group = packed[i / perGroup];
                for (int k = i % perGroup; k > 0; k--) group /= base;
                value |= (group % base) << bits;
            }
            endpoint[i] = table[value];
        }

        // 重み。各部分で最初に現れる画素 (2 面のときは画素 0 の 2 つ) は 1 ビット少ない
        final int weightBits = WEIGHT_BITS[mode];
        final int[] weight = new int[32];
        final int[] weightTable = WEIGHT_VALUES[weightBits];
        if (planes == 2) {
            for (int i = 0; i < 32; i++) weight[i] = weightTable[in.read(i < 2 ? weightBits - 1 : weightBits)];
        } else {
            int seen = 0;
            for (int i = 0; i < 16; i++) {
                final int subset = pattern == null ? 0 : pattern.charAt(i) - '0';
                final boolean anchor = (seen & (1 << subset)) == 0;
                seen |= 1 << subset;
                weight[i] = weightTable[in.read(anchor ? weightBits - 1 : weightBits)];
            }
        }

        // 部分ごとの端点 (RGBA)。LA のモードは L を RGB に使う
        final int[] low = new int[12];
        final int[] high = new int[12];
        for (int s = 0; s < subsets; s++) {
            final int e = s * components * 2;
            for (int c = 0; c < 4; c++) {
                final int source = components == 2 ? (c == 3 ? 1 : 0) : c;
                if (source < components) {
                    low[s * 4 + c] = endpoint[e + source * 2];
                    high[s * 4 + c] = endpoint[e + source * 2 + 1];
                } else {
                    low[s * 4 + c] = 255;
                    high[s * 4 + c] = 255;
                }
            }
        }

        for (int i = 0; i < 16; i++) {
            final int s = pattern == null ? 0 : pattern.charAt(i) - '0';
            int argb = 0;
            for (int c = 0; c < 4; c++) {
                final int w = planes == 2 ? weight[i * 2 + (c == dualChannel ? 1 : 0)] : weight[i];
                final int value = interpolate(low[s * 4 + c], high[s * 4 + c], w, srgb);
                argb |= value << (c == 3 ? 24 : 16 - c * 8);
            }
            out[i] = argb;
        }
        return true;
    }
}
