package marumasa.marumasa_sign.core.texture;

import java.io.IOException;
import java.util.Arrays;

/**
 * UASTC 4x4 ブロック形式のテクスチャデコーダ。
 * 全 19 モード (Mode 0〜18) を RGBA にデコードする。
 */
public final class UastcDecoder {

    private static final int TOTAL_UASTC_MODES = 19;
    private static final int UASTC_MODE_INDEX_SOLID_COLOR = 8;

    private UastcDecoder() {
    }

    public static void decode(byte[] blockData, int offset, int width, int height, boolean srgb, int[] outArgb) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 16;
        if (required > blockData.length || required < 0) {
            throw new IOException("UASTC data truncated: required=" + required + ", available=" + blockData.length);
        }

        final int[] blockPixels = new int[16];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                decodeBlock(blockData, pos, srgb, blockPixels);
                pos += 16;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        outArgb[rowOffset + x] = blockPixels[py * 4 + px];
                    }
                }
            }
        }
    }

    private static void decodeBlock(byte[] blk, int pos, boolean srgb, int[] outPixels) {
        final int mode = G_UASTC_HUFF_MODES[blk[pos] & 127];
        if (mode >= TOTAL_UASTC_MODES) {
            Arrays.fill(outPixels, 0);
            return;
        }

        int bitOfs = G_UASTC_MODE_HUFF_CODES[mode][1];

        if (mode == UASTC_MODE_INDEX_SOLID_COLOR) {
            final int r = readBits(blk, pos, bitOfs, 8); bitOfs += 8;
            final int g = readBits(blk, pos, bitOfs, 8); bitOfs += 8;
            final int b = readBits(blk, pos, bitOfs, 8); bitOfs += 8;
            final int a = readBits(blk, pos, bitOfs, 8);
            final int color = (a << 24) | (r << 16) | (g << 8) | b;
            Arrays.fill(outPixels, color);
            return;
        }

        // ヒントビットのスキップ
        bitOfs += G_UASTC_MODE_TOTAL_HINT_BITS[mode];

        int commonPattern = 0;
        int subsets = 1;
        switch (mode) {
            case 2:
            case 4:
            case 7:
            case 9:
            case 16:
                commonPattern = readBits(blk, pos, bitOfs, 5); bitOfs += 5;
                subsets = 2;
                break;
            case 3:
                commonPattern = readBits(blk, pos, bitOfs, 4); bitOfs += 4;
                subsets = 3;
                break;
            default:
                break;
        }

        int totalPlanes = 1;
        int ccs = 0;
        switch (mode) {
            case 6:
            case 11:
            case 13:
                ccs = readBits(blk, pos, bitOfs, 2); bitOfs += 2;
                totalPlanes = 2;
                break;
            case 17:
                ccs = 3;
                totalPlanes = 2;
                break;
            default:
                break;
        }

        final int totalComps = G_UASTC_MODE_COMPS[mode];
        final int weightBits = G_UASTC_MODE_WEIGHT_BITS[mode];
        final int weightLevels = 1 << weightBits;
        final int endpointRange = G_UASTC_MODE_ENDPOINT_RANGES[mode];

        // エンドポイントのデコード
        final int[][][] endpoints = new int[subsets][2][4];
        for (int s = 0; s < subsets; s++) {
            for (int comp = 0; comp < totalComps; comp++) {
                final int v0 = readBits(blk, pos, bitOfs, 8); bitOfs += 8;
                final int v1 = readBits(blk, pos, bitOfs, 8); bitOfs += 8;
                endpoints[s][0][comp] = v0;
                endpoints[s][1][comp] = v1;
            }
            for (int comp = totalComps; comp < 4; comp++) {
                endpoints[s][0][comp] = 255;
                endpoints[s][1][comp] = 255;
            }
        }

        // 重みテーブルの取得
        final int[] weightsTable = getAstcWeightTable(weightBits);

        // 各サブセット・重みレベルの補間色を事前計算
        final int[][][] blockColors = new int[subsets][weightLevels][4];
        for (int s = 0; s < subsets; s++) {
            for (int l = 0; l < weightLevels; l++) {
                final int w = weightsTable[l];
                for (int comp = 0; comp < 4; comp++) {
                    blockColors[s][l][comp] = astcInterpolate(endpoints[s][0][comp], endpoints[s][1][comp], w, srgb);
                }
            }
        }

        // パーティションパターンの決定
        final byte[] partitionPattern = (subsets == 3) ? G_ASTC_BC7_PATTERNS3[commonPattern]
                : (subsets == 2 ? G_ASTC_BC7_PATTERNS2[commonPattern] : ZERO_PATTERN);

        // ピクセルごとの重みインデックス読み取りと色合成
        if (totalPlanes == 1) {
            for (int i = 0; i < 16; i++) {
                final int wIdx = readBits(blk, pos, bitOfs, weightBits); bitOfs += weightBits;
                final int subset = partitionPattern[i];
                final int[] c = blockColors[subset][wIdx];
                outPixels[i] = (c[3] << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
            }
        } else {
            // デュアルプレーン (RGB + Alpha または LA)
            for (int i = 0; i < 16; i++) {
                final int w0 = readBits(blk, pos, bitOfs, weightBits); bitOfs += weightBits;
                final int w1 = readBits(blk, pos, bitOfs, weightBits); bitOfs += weightBits;
                final int subset = partitionPattern[i];
                final int[] c0 = blockColors[subset][w0];
                final int[] c1 = blockColors[subset][w1];

                final int r = (ccs == 0) ? c1[0] : c0[0];
                final int g = (ccs == 1) ? c1[1] : c0[1];
                final int b = (ccs == 2) ? c1[2] : c0[2];
                final int a = (ccs == 3) ? c1[3] : c0[3];
                outPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int astcInterpolate(int l, int h, int w, boolean srgb) {
        if (srgb) {
            l = (l << 8) | 0x80;
            h = (h << 8) | 0x80;
        } else {
            l = (l << 8) | l;
            h = (h << 8) | h;
        }
        final int k = (l * (64 - w) + h * w + 32) >>> 6;
        return k >>> 8;
    }

    private static int readBits(byte[] data, int pos, int bitOfs, int count) {
        int res = 0;
        for (int i = 0; i < count; i++) {
            final int totalBit = bitOfs + i;
            final int byteIdx = pos + (totalBit >>> 3);
            if (byteIdx < data.length) {
                final int bit = (data[byteIdx] >>> (totalBit & 7)) & 1;
                res |= (bit << i);
            }
        }
        return res;
    }

    private static int[] getAstcWeightTable(int weightBits) {
        if (weightBits == 1) return ASTC_WEIGHTS1;
        if (weightBits == 2) return ASTC_WEIGHTS2;
        if (weightBits == 3) return ASTC_WEIGHTS3;
        if (weightBits == 4) return ASTC_WEIGHTS4;
        return ASTC_WEIGHTS5;
    }

    private static final int[] ASTC_WEIGHTS1 = {0, 64};
    private static final int[] ASTC_WEIGHTS2 = {0, 21, 43, 64};
    private static final int[] ASTC_WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] ASTC_WEIGHTS4 = {0, 4, 8, 12, 17, 21, 25, 29, 35, 39, 43, 47, 52, 56, 60, 64};
    private static final int[] ASTC_WEIGHTS5 = {
            0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30,
            33, 35, 37, 39, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 64
    };

    private static final byte[] ZERO_PATTERN = new byte[16];

    private static final int[] G_UASTC_HUFF_MODES = {
            11,0,10,3,11,15,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,16,12,8,11,18,10,6,11,2,12,13,
            11,0,10,3,11,17,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,1,12,8,11,18,10,6,11,2,12,13,
            11,0,10,3,11,19,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,16,12,8,11,18,10,6,11,2,12,13,
            11,0,10,3,11,17,12,7,11,18,10,5,11,14,12,9,11,0,10,4,11,1,12,8,11,18,10,6,11,2,12,13
    };

    private static final int[][] G_UASTC_MODE_HUFF_CODES = {
            {0x1, 4}, {0x35, 6}, {0x1D, 5}, {0x3, 5},
            {0x13, 5}, {0xB, 5}, {0x1B, 5}, {0x7, 5},
            {0x17, 5}, {0xF, 5}, {0x2, 3}, {0x0, 2},
            {0x6, 3}, {0x1F, 5}, {0xD, 5}, {0x5, 7},
            {0x15, 6}, {0x25, 6}, {0x9, 4}, {0x45, 7}
    };

    private static final int[] G_UASTC_MODE_WEIGHT_BITS = {4, 2, 3, 2, 2, 3, 2, 2, 0, 2, 4, 2, 3, 1, 2, 4, 2, 2, 5};
    private static final int[] G_UASTC_MODE_ENDPOINT_RANGES = {19, 20, 8, 7, 12, 20, 18, 12, 0, 8, 13, 13, 19, 20, 20, 20, 20, 20, 11};
    private static final int[] G_UASTC_MODE_COMPS = {3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 2, 2, 2, 3};
    private static final int[] G_UASTC_MODE_TOTAL_HINT_BITS = {
            10, 10, 10, 10, 10, 10, 10, 10, 0, 18, 18, 18, 18, 18, 18, 18, 18, 18, 10
    };

    private static final byte[][] G_ASTC_BC7_PATTERNS2 = {
            {0,0,1,1,0,0,1,1,0,0,1,1,0,0,1,1}, {0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1}, {1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0}, {0,0,0,1,0,0,1,1,0,0,1,1,0,1,1,1},
            {1,1,1,1,1,1,1,0,1,1,1,0,1,1,0,0}, {0,0,1,1,0,1,1,1,0,1,1,1,1,1,1,1}, {1,1,1,0,1,1,0,0,1,0,0,0,0,0,0,0}, {1,1,1,1,1,1,1,0,1,1,0,0,1,0,0,0},
            {0,0,0,0,0,0,0,0,0,0,0,1,0,0,1,1}, {1,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0}, {0,0,0,0,0,0,0,1,0,1,1,1,1,1,1,1}, {1,1,1,1,1,1,1,1,1,1,1,0,1,0,0,0},
            {1,1,1,0,1,0,0,0,0,0,0,0,0,0,0,0}, {1,1,1,1,1,1,1,1,0,0,0,0,0,0,0,0}, {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1}, {1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0},
            {1,0,0,0,1,1,1,0,1,1,1,1,1,1,1,1}, {1,1,1,1,1,1,1,1,0,1,1,1,0,0,0,1}, {0,1,1,1,0,0,1,1,0,0,0,1,0,0,0,0}, {0,0,1,1,0,0,0,1,0,0,0,0,0,0,0,0},
            {0,0,0,0,1,0,0,0,1,1,0,0,1,1,1,0}, {1,1,1,1,1,1,1,1,0,1,1,1,0,0,1,1}, {1,0,0,0,1,1,0,0,1,1,0,0,1,1,1,0}, {0,0,1,1,0,0,0,1,0,0,0,1,0,0,0,0},
            {1,1,1,1,0,1,1,1,0,1,1,1,0,0,1,1}, {0,1,1,0,0,1,1,0,0,1,1,0,0,1,1,0}, {1,1,1,1,0,0,0,0,0,0,0,0,1,1,1,1}, {1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0},
            {1,1,1,1,0,0,0,0,1,1,1,1,0,0,0,0}, {1,0,0,1,0,0,1,1,0,1,1,0,1,1,0,0}
    };

    private static final byte[][] G_ASTC_BC7_PATTERNS3 = {
            {0,0,0,0,0,0,0,0,1,1,2,2,1,1,2,2}, {1,1,1,1,1,1,1,1,0,0,0,0,2,2,2,2}, {1,1,1,1,0,0,0,0,0,0,0,0,2,2,2,2}, {1,1,1,1,2,2,2,2,0,0,0,0,0,0,0,0},
            {1,1,2,0,1,1,2,0,1,1,2,0,1,1,2,0}, {0,1,1,2,0,1,1,2,0,1,1,2,0,1,1,2}, {2,1,1,0,2,1,1,0,2,1,1,0,2,1,1,0}, {0,0,1,1,2,2,1,1,2,2,1,1,0,0,1,1},
            {0,2,2,0,1,2,2,1,1,2,2,1,0,2,2,0}, {0,0,0,0,1,1,1,1,2,2,2,2,0,0,0,0}, {0,1,2,0,0,1,2,0,0,1,2,0,0,1,2,0}
    };
}
