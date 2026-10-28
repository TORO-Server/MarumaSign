package marumasa.marumasa_sign.core.texture;

import java.util.Arrays;

/**
 * BC6H と BC7 (BPTC) の 4x4 画素のブロックを展開する。
 * Khronos Data Format Specification の「BPTC Compressed Texture Image Formats」の節
 * (と、そのもとになった Microsoft の BC6H / BC7 の説明) に沿って書いたもの。
 * 128 ビットのどんな並びでも展開でき、例外は出さない (使わないモードは仕様どおり黒にする)。
 */
final class Bptc {

    private Bptc() {
    }

    // ---------- 共通 ----------

    // 番号のビット数ごとの重み (0 - 64)。仕様にある式で作る
    private static final int[][] WEIGHTS = new int[5][];

    static {
        WEIGHTS[2] = new int[4];
        WEIGHTS[3] = new int[8];
        WEIGHTS[4] = new int[16];
        for (int i = 0; i < 4; i++) WEIGHTS[2][i] = i * 21 + (i >> 1);
        for (int i = 0; i < 8; i++) WEIGHTS[3][i] = i * 9 + (i >> 2);
        for (int i = 0; i < 16; i++) WEIGHTS[4][i] = (i << 2) + (i >> 2) + ((i >> 1) & 1);
    }

    // 2 つに分ける分け方 (64 通り)。画素 i (行ごとに左から) の部分の番号をビット i に置いてある
    private static final int[] PARTITIONS2 = {
            0xCCCC, 0x8888, 0xEEEE, 0xECC8, 0xC880, 0xFEEC, 0xFEC8, 0xEC80,
            0xC800, 0xFFEC, 0xFE80, 0xE800, 0xFFE8, 0xFF00, 0xFFF0, 0xF000,
            0xF710, 0x008E, 0x7100, 0x08CE, 0x008C, 0x7310, 0x3100, 0x8CCE,
            0x088C, 0x3110, 0x6666, 0x366C, 0x17E8, 0x0FF0, 0x718E, 0x399C,
            0xAAAA, 0xF0F0, 0x5A5A, 0x33CC, 0x3C3C, 0x55AA, 0x9696, 0xA55A,
            0x73CE, 0x13C8, 0x324C, 0x3BDC, 0x6996, 0xC33C, 0x9966, 0x0660,
            0x0272, 0x04E4, 0x4E40, 0x2720, 0xC936, 0x936C, 0x39C6, 0x639C,
            0x9336, 0x9CC6, 0x817E, 0xE718, 0xCCF0, 0x0FCC, 0x7744, 0xEE22
    };
    // 3 つに分ける分け方 (64 通り)。画素 i の部分の番号を 2i ビット目から 2 ビットで置いてある
    private static final int[] PARTITIONS3 = {
            0xAA685050, 0x6A5A5040, 0x5A5A4200, 0x5450A0A8, 0xA5A50000, 0xA0A05050, 0x5555A0A0, 0x5A5A5050,
            0xAA550000, 0xAA555500, 0xAAAA5500, 0x90909090, 0x94949494, 0xA4A4A4A4, 0xA9A59450, 0x2A0A4250,
            0xA5945040, 0x0A425054, 0xA5A5A500, 0x55A0A0A0, 0xA8A85454, 0x6A6A4040, 0xA4A45000, 0x1A1A0500,
            0x0050A4A4, 0xAAA59090, 0x14696914, 0x69691400, 0xA08585A0, 0xAA821414, 0x50A4A450, 0x6A5A0200,
            0xA9A58000, 0x5090A0A8, 0xA8A09050, 0x24242424, 0x00AA5500, 0x24924924, 0x24499224, 0x50A50A50,
            0x500AA550, 0xAAAA4444, 0x66660000, 0xA5A0A5A0, 0x50A050A0, 0x69286928, 0x44AAAA44, 0x66666600,
            0xAA444444, 0x54A854A8, 0x95809580, 0x96969600, 0xA85454A8, 0x80959580, 0xAA141414, 0x96960000,
            0xAAAA1414, 0xA05050A0, 0xA0A5A5A0, 0x96000000, 0x40804080, 0xA9A8A9A8, 0xAAAAAA44, 0x2A4A5254
    };
    // 番号が 1 ビット少ない画素 (部分ごとの目印)。部分 0 はいつも画素 0
    private static final byte[] ANCHOR2 = {
            15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15,
            15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2,
            15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6,
            6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15
    };
    private static final byte[] ANCHOR3_1 = {
            3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3,
            3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15,
            8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15,
            3, 15, 5, 5, 5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3
    };
    private static final byte[] ANCHOR3_2 = {
            15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8,
            15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8,
            15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8,
            15, 3, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8
    };

    private static long readLong(byte[] data, int pos) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (data[pos + i] & 0xFFL);
        return value;
    }

    // ブロックの start ビット目から count ビット (32 まで) を、下位のビットから順に読む。lo が 0 - 63 ビット目、hi が 64 - 127 ビット目
    private static int bits(long lo, long hi, int start, int count) {
        if (count == 0) return 0;
        final long v;
        if (start >= 64) v = hi >>> (start - 64);
        else if (start == 0) v = lo;
        else v = (lo >>> start) | (hi << (64 - start));
        return (int) (v & ((1L << count) - 1));
    }

    // 画素 i が属する部分の番号
    private static int subsetOf(int subsets, int partition, int i) {
        if (subsets == 2) return (PARTITIONS2[partition] >>> i) & 1;
        if (subsets == 3) return (PARTITIONS3[partition] >>> (i * 2)) & 3;
        return 0;
    }

    // 画素 i が、部分の目印 (番号の最上位のビットを省いてある画素) か
    private static boolean isAnchor(int subsets, int partition, int i) {
        if (i == 0) return true;
        if (subsets == 2) return i == ANCHOR2[partition];
        if (subsets == 3) return i == ANCHOR3_1[partition] || i == ANCHOR3_2[partition];
        return false;
    }

    private static int interpolate(int e0, int e1, int weight) {
        return (e0 * (64 - weight) + e1 * weight + 32) >> 6;
    }

    // ---------- BC7 ----------

    // モードごとの値。部分の数、分け方のビット、回転のビット、番号の選び方のビット、色のビット、透明度のビット、
    // 端点ごとの P ビットがあるか、部分ごとに共有する P ビットがあるか、番号のビット、2 つ目の番号のビット
    private static final int[][] BC7_MODES = {
            {3, 4, 0, 0, 4, 0, 1, 0, 3, 0},
            {2, 6, 0, 0, 6, 0, 0, 1, 3, 0},
            {3, 6, 0, 0, 5, 0, 0, 0, 2, 0},
            {2, 6, 0, 0, 7, 0, 1, 0, 2, 0},
            {1, 0, 2, 1, 5, 6, 0, 0, 2, 3},
            {1, 0, 2, 0, 7, 8, 0, 0, 2, 2},
            {1, 0, 0, 0, 7, 7, 1, 0, 4, 0},
            {2, 6, 0, 0, 5, 5, 1, 0, 2, 0}
    };

    // n ビットの値を、上位のビットを下に繰り返して 8 ビットにする (n は 4 - 8)
    private static int expandTo8(int value, int n) {
        final int v = value << (8 - n);
        return v | (v >> n);
    }

    /**
     * BC7 のブロックを展開する。out には 16 画素分の ARGB を、行ごとに並べて書く。
     */
    static void bc7(byte[] data, int pos, int[] out) {
        final long lo = readLong(data, pos);
        final long hi = readLong(data, pos + 8);
        // モードは、最下位から続く 0 の数。最初のバイトが 0 なら使わないモードで、すべて 0 にする
        final int mode = Long.numberOfTrailingZeros(lo);
        if (mode >= 8) {
            Arrays.fill(out, 0, 16, 0);
            return;
        }
        final int[] m = BC7_MODES[mode];
        final int subsets = m[0];
        final int endpointCount = subsets * 2;
        int p = mode + 1;
        final int partition = bits(lo, hi, p, m[1]);
        p += m[1];
        final int rotation = bits(lo, hi, p, m[2]);
        p += m[2];
        final int selection = bits(lo, hi, p, m[3]);
        p += m[3];

        // 端点は、チャンネルごとに、部分と端点の順に並んでいる。[端点 * 4 + チャンネル]
        final int[] ep = new int[24];
        for (int c = 0; c < 3; c++) {
            for (int e = 0; e < endpointCount; e++) {
                ep[e * 4 + c] = bits(lo, hi, p, m[4]);
                p += m[4];
            }
        }
        for (int e = 0; e < endpointCount; e++) {
            ep[e * 4 + 3] = bits(lo, hi, p, m[5]);
            p += m[5];
        }
        int colorBits = m[4];
        int alphaBits = m[5];
        if (m[6] != 0 || m[7] != 0) {
            // P ビットは、各チャンネルの最下位に足す
            for (int e = 0; e < endpointCount; e++) {
                final int pbit;
                if (m[6] != 0) {
                    pbit = bits(lo, hi, p + e, 1);
                } else {
                    pbit = bits(lo, hi, p + (e >> 1), 1);
                }
                for (int c = 0; c < 4; c++) ep[e * 4 + c] = (ep[e * 4 + c] << 1) | pbit;
            }
            p += m[6] != 0 ? endpointCount : subsets;
            colorBits++;
            if (alphaBits > 0) alphaBits++;
        }
        for (int e = 0; e < endpointCount; e++) {
            for (int c = 0; c < 3; c++) ep[e * 4 + c] = expandTo8(ep[e * 4 + c], colorBits);
            ep[e * 4 + 3] = alphaBits == 0 ? 255 : expandTo8(ep[e * 4 + 3], alphaBits);
        }

        // 1 つ目の番号、続いて (モード 4, 5 では) 2 つ目の番号
        final int[] index1 = new int[16];
        final int[] index2 = new int[16];
        for (int i = 0; i < 16; i++) {
            final int n = isAnchor(subsets, partition, i) ? m[8] - 1 : m[8];
            index1[i] = bits(lo, hi, p, n);
            p += n;
        }
        if (m[9] != 0) {
            for (int i = 0; i < 16; i++) {
                final int n = i == 0 ? m[9] - 1 : m[9];
                index2[i] = bits(lo, hi, p, n);
                p += n;
            }
        }

        // 色と透明度に使う番号。2 つ目の番号があるときは、選び方のビットで入れ替える
        final boolean swapIndices = m[9] != 0 && selection != 0;
        final int[] colorIndex = swapIndices ? index2 : index1;
        final int[] alphaIndex = m[9] == 0 || swapIndices ? index1 : index2;
        final int[] colorWeights = WEIGHTS[swapIndices ? m[9] : m[8]];
        final int[] alphaWeights = WEIGHTS[m[9] == 0 || swapIndices ? m[8] : m[9]];

        for (int i = 0; i < 16; i++) {
            final int e = subsetOf(subsets, partition, i) * 8;
            final int cw = colorWeights[colorIndex[i]];
            final int aw = alphaWeights[alphaIndex[i]];
            int r = interpolate(ep[e], ep[e + 4], cw);
            int g = interpolate(ep[e + 1], ep[e + 5], cw);
            int b = interpolate(ep[e + 2], ep[e + 6], cw);
            int a = interpolate(ep[e + 3], ep[e + 7], aw);
            // 回転: 透明度と入れ替えて持っているチャンネルを戻す
            int t;
            switch (rotation) {
                case 1:
                    t = a;
                    a = r;
                    r = t;
                    break;
                case 2:
                    t = a;
                    a = g;
                    g = t;
                    break;
                case 3:
                    t = a;
                    a = b;
                    b = t;
                    break;
                default:
                    break;
            }
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // ---------- BC6H ----------

    // モードの番号 (下位 2 ビットが 0, 1 ならその 2 ビット、そうでなければ下位 5 ビット) ごとの値。使わない番号は null
    private static final Bc6hMode[] BC6H_MODES = new Bc6hMode[32];

    static {
        // 端点のビットの並び (仕様の表の写し)。R0 は 1 つ目の部分の端点 0 の赤、D は分け方の番号。
        // 「9..0」は先に読むビットが 0 ビット目、「10..15」は先に読むビットが 15 ビット目になる
        BC6H_MODES[0] = new Bc6hMode(0, true, 10, 5, 5, 5,
                "G2:4 B2:4 B3:4 R0:9..0 G0:9..0 B0:9..0 R1:4..0 G3:4 G2:3..0 G1:4..0 B3:0 G3:3..0 B1:4..0 B3:1 B2:3..0 R2:4..0 B3:2 R3:4..0 B3:3 D:4..0");
        BC6H_MODES[1] = new Bc6hMode(1, true, 7, 6, 6, 6,
                "G2:5 G3:4 G3:5 R0:6..0 B3:0 B3:1 B2:4 G0:6..0 B2:5 B3:2 G2:4 B0:6..0 B3:3 B3:5 B3:4 R1:5..0 G2:3..0 G1:5..0 G3:3..0 B1:5..0 B2:3..0 R2:5..0 R3:5..0 D:4..0");
        BC6H_MODES[2] = new Bc6hMode(2, true, 11, 5, 4, 4,
                "R0:9..0 G0:9..0 B0:9..0 R1:4..0 R0:10 G2:3..0 G1:3..0 G0:10 B3:0 G3:3..0 B1:3..0 B0:10 B3:1 B2:3..0 R2:4..0 B3:2 R3:4..0 B3:3 D:4..0");
        BC6H_MODES[6] = new Bc6hMode(6, true, 11, 4, 5, 4,
                "R0:9..0 G0:9..0 B0:9..0 R1:3..0 R0:10 G3:4 G2:3..0 G1:4..0 G0:10 G3:3..0 B1:3..0 B0:10 B3:1 B2:3..0 R2:3..0 B3:0 B3:2 R3:3..0 G2:4 B3:3 D:4..0");
        BC6H_MODES[10] = new Bc6hMode(10, true, 11, 4, 4, 5,
                "R0:9..0 G0:9..0 B0:9..0 R1:3..0 R0:10 B2:4 G2:3..0 G1:3..0 G0:10 B3:0 G3:3..0 B1:4..0 B0:10 B2:3..0 R2:3..0 B3:1 B3:2 R3:3..0 B3:4 B3:3 D:4..0");
        BC6H_MODES[14] = new Bc6hMode(14, true, 9, 5, 5, 5,
                "R0:8..0 B2:4 G0:8..0 G2:4 B0:8..0 B3:4 R1:4..0 G3:4 G2:3..0 G1:4..0 B3:0 G3:3..0 B1:4..0 B3:1 B2:3..0 R2:4..0 B3:2 R3:4..0 B3:3 D:4..0");
        BC6H_MODES[18] = new Bc6hMode(18, true, 8, 6, 5, 5,
                "R0:7..0 G3:4 B2:4 G0:7..0 B3:2 G2:4 B0:7..0 B3:3 B3:4 R1:5..0 G2:3..0 G1:4..0 B3:0 G3:3..0 B1:4..0 B3:1 B2:3..0 R2:5..0 R3:5..0 D:4..0");
        BC6H_MODES[22] = new Bc6hMode(22, true, 8, 5, 6, 5,
                "R0:7..0 B3:0 B2:4 G0:7..0 G2:5 G2:4 B0:7..0 G3:5 B3:4 R1:4..0 G3:4 G2:3..0 G1:5..0 G3:3..0 B1:4..0 B3:1 B2:3..0 R2:4..0 B3:2 R3:4..0 B3:3 D:4..0");
        BC6H_MODES[26] = new Bc6hMode(26, true, 8, 5, 5, 6,
                "R0:7..0 B3:1 B2:4 G0:7..0 B2:5 G2:4 B0:7..0 B3:5 B3:4 R1:4..0 G3:4 G2:3..0 G1:4..0 B3:0 G3:3..0 B1:5..0 B2:3..0 R2:4..0 B3:2 R3:4..0 B3:3 D:4..0");
        BC6H_MODES[30] = new Bc6hMode(30, false, 6, 6, 6, 6,
                "R0:5..0 G3:4 B3:0 B3:1 B2:4 G0:5..0 G2:5 B2:5 B3:2 G2:4 B0:5..0 G3:5 B3:3 B3:5 B3:4 R1:5..0 G2:3..0 G1:5..0 G3:3..0 B1:5..0 B2:3..0 R2:5..0 R3:5..0 D:4..0");
        BC6H_MODES[3] = new Bc6hMode(3, false, 10, 10, 10, 10,
                "R0:9..0 G0:9..0 B0:9..0 R1:9..0 G1:9..0 B1:9..0");
        BC6H_MODES[7] = new Bc6hMode(7, true, 11, 9, 9, 9,
                "R0:9..0 G0:9..0 B0:9..0 R1:8..0 R0:10 G1:8..0 G0:10 B1:8..0 B0:10");
        BC6H_MODES[11] = new Bc6hMode(11, true, 12, 8, 8, 8,
                "R0:9..0 G0:9..0 B0:9..0 R1:7..0 R0:10..11 G1:7..0 G0:10..11 B1:7..0 B0:10..11");
        BC6H_MODES[15] = new Bc6hMode(15, true, 16, 4, 4, 4,
                "R0:9..0 G0:9..0 B0:9..0 R1:3..0 R0:10..15 G1:3..0 G0:10..15 B1:3..0 B0:10..15");
    }

    private static final class Bc6hMode {
        final boolean transformed;
        // 端点 0 のビット数と、ほかの端点 (差分) のチャンネルごとのビット数
        final int endpointBits;
        final int[] deltaBits;
        final boolean twoSubsets;
        // ビットの並び。1 つの項目ごとに {値の番号, 置く最下位のビット, ビット数, 逆順なら 1}。値の番号は 端点 * 3 + チャンネル、12 が分け方
        final int[] fields;

        Bc6hMode(int number, boolean transformed, int endpointBits, int deltaR, int deltaG, int deltaB, String layout) {
            this.transformed = transformed;
            this.endpointBits = endpointBits;
            this.deltaBits = new int[]{deltaR, deltaG, deltaB};
            this.twoSubsets = layout.contains("D:");
            final String[] items = layout.split(" ");
            fields = new int[items.length * 4];
            int total = 0;
            for (int i = 0; i < items.length; i++) {
                final String item = items[i];
                final int colon = item.indexOf(':');
                final String name = item.substring(0, colon);
                final int target = name.equals("D") ? 12 : (name.charAt(1) - '0') * 3 + "RGB".indexOf(name.charAt(0));
                final String range = item.substring(colon + 1);
                final int dots = range.indexOf("..");
                final int from = Integer.parseInt(dots < 0 ? range : range.substring(0, dots));
                final int to = dots < 0 ? from : Integer.parseInt(range.substring(dots + 2));
                fields[i * 4] = target;
                fields[i * 4 + 1] = Math.min(from, to);
                fields[i * 4 + 2] = Math.abs(from - to) + 1;
                fields[i * 4 + 3] = from < to ? 1 : 0;
                total += fields[i * 4 + 2];
            }
            // モードのビットと合わせて、2 つの部分なら 82 ビット、1 つなら 65 ビットになる
            if ((number < 2 ? 2 : 5) + total != (twoSubsets ? 82 : 65)) throw new IllegalStateException("BC6H layout " + number);
        }
    }

    private static int signExtend(int value, int bits) {
        return (value << (32 - bits)) >> (32 - bits);
    }

    private static int reverseBits(int value, int count) {
        return Integer.reverse(value) >>> (32 - count);
    }

    // 端点を 16 ビットの範囲に広げる
    private static int unquantize(int x, int bits, boolean signed) {
        if (!signed) {
            if (bits >= 15 || x == 0) return x;
            if (x == (1 << bits) - 1) return 0xFFFF;
            return ((x << 15) + 0x4000) >> (bits - 1);
        }
        if (bits >= 16) return x;
        final boolean negative = x < 0;
        if (negative) x = -x;
        int unq;
        if (x == 0) unq = 0;
        else if (x >= (1 << (bits - 1)) - 1) unq = 0x7FFF;
        else unq = ((x << 15) + 0x4000) >> (bits - 1);
        return negative ? -unq : unq;
    }

    // 補間したあとの値を、16 ビットの浮動小数点数 (half) のビット列にする。
    // 符号付きで大きさが 0 になったときは、Microsoft の説明のとおり +0 にする (Khronos の式では -0 になるが、値は同じ)
    private static int finishUnquantize(int value, boolean signed) {
        if (!signed) return (value * 31) >> 6;
        if (value >= 0) return (value * 31) >> 5;
        final int magnitude = ((-value) * 31) >> 5;
        return magnitude == 0 ? 0 : magnitude | 0x8000;
    }

    private static float halfToFloat(int half) {
        final int sign = (half & 0x8000) << 16;
        final int exponent = (half >> 10) & 0x1F;
        final int mantissa = half & 0x3FF;
        if (exponent == 0) {
            final float v = mantissa * (1f / (1 << 24));
            return sign != 0 ? -v : v;
        }
        if (exponent == 31) {
            if (mantissa != 0) return Float.NaN;
            return sign != 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
    }

    /**
     * BC6H のブロックを展開する。out には 16 画素分の RGB (リニアの値) を、行ごとに並べて書く。
     */
    static void bc6h(byte[] data, int pos, boolean signed, float[] out) {
        final long lo = readLong(data, pos);
        final long hi = readLong(data, pos + 8);
        int modeNumber = bits(lo, hi, 0, 2);
        int p = 2;
        if (modeNumber >= 2) {
            modeNumber = bits(lo, hi, 0, 5);
            p = 5;
        }
        final Bc6hMode mode = BC6H_MODES[modeNumber];
        if (mode == null) {
            // 使わないモードは黒にする
            Arrays.fill(out, 0, 48, 0f);
            return;
        }

        // 端点 (と分け方の番号) を、表のとおりに集める
        final int[] v = new int[13];
        final int[] fields = mode.fields;
        for (int f = 0; f < fields.length; f += 4) {
            final int count = fields[f + 2];
            int value = bits(lo, hi, p, count);
            p += count;
            if (fields[f + 3] != 0) value = reverseBits(value, count);
            v[fields[f]] |= value << fields[f + 1];
        }
        final int partition = v[12];
        final int endpoints = mode.twoSubsets ? 4 : 2;
        final int bits = mode.endpointBits;

        // 符号を広げる。端点 0 は符号付きの形式のときだけ、ほかは差分のときも
        for (int c = 0; c < 3; c++) {
            if (signed) v[c] = signExtend(v[c], bits);
            for (int e = 1; e < endpoints; e++) {
                if (signed || mode.transformed) v[e * 3 + c] = signExtend(v[e * 3 + c], mode.deltaBits[c]);
            }
        }
        // 差分を、端点 0 に足して戻す (endpointBits のビット数で回り込む)
        if (mode.transformed) {
            final int mask = (1 << bits) - 1;
            for (int e = 1; e < endpoints; e++) {
                for (int c = 0; c < 3; c++) {
                    int value = (v[c] + v[e * 3 + c]) & mask;
                    if (signed) value = signExtend(value, bits);
                    v[e * 3 + c] = value;
                }
            }
        }
        for (int i = 0; i < endpoints * 3; i++) v[i] = unquantize(v[i], bits, signed);

        final int subsets = mode.twoSubsets ? 2 : 1;
        final int indexBits = mode.twoSubsets ? 3 : 4;
        final int[] weights = WEIGHTS[indexBits];
        for (int i = 0; i < 16; i++) {
            final int n = isAnchor(subsets, partition, i) ? indexBits - 1 : indexBits;
            final int w = weights[bits(lo, hi, p, n)];
            p += n;
            final int e = subsetOf(subsets, partition, i) * 6;
            for (int c = 0; c < 3; c++) {
                out[i * 3 + c] = halfToFloat(finishUnquantize(interpolate(v[e + c], v[e + 3 + c], w), signed));
            }
        }
    }
}
