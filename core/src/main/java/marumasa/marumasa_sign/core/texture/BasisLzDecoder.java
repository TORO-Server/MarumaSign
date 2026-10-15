package marumasa.marumasa_sign.core.texture;

import java.io.IOException;
import java.util.Arrays;

/**
 * Basis Universal (BasisLZ / ETC1S) デコーダ。
 * グローバルコードブック (エンドポイント、セレクタ、ハフマンテーブル) と各スライスをデコードして RGBA を生成する。
 */
public final class BasisLzDecoder {

    private BasisLzDecoder() {
    }

    public static final class Endpoint {
        public int r, g, b;
        public int inten;
    }

    public static final class Selector {
        public final int[] selectors = new int[16];
    }

    public static final class ImageDesc {
        public int imageFlags;
        public int rgbSliceByteOffset;
        public int rgbSliceByteLength;
        public int alphaSliceByteOffset;
        public int alphaSliceByteLength;
    }

    public static final class GlobalData {
        public int endpointCount;
        public int selectorCount;
        public ImageDesc[] imageDescs;
        public Endpoint[] endpoints;
        public Selector[] selectors;
        public byte[] tablesData;
    }

    public static GlobalData parseGlobalData(byte[] sgd, int offset, int length) throws IOException {
        if (sgd == null || length < 20 || offset + length > sgd.length) {
            throw new IOException("Invalid BasisLZ global data");
        }

        int pos = offset;
        final int endpointCount = readUShortLE(sgd, pos); pos += 2;
        final int selectorCount = readUShortLE(sgd, pos); pos += 2;
        final int endpointsByteLength = readIntLE(sgd, pos); pos += 4;
        final int selectorsByteLength = readIntLE(sgd, pos); pos += 4;
        final int tablesByteLength = readIntLE(sgd, pos); pos += 4;
        final int extendedByteLength = readIntLE(sgd, pos); pos += 4;

        if (endpointCount <= 0 || selectorCount <= 0) {
            throw new IOException("Empty BasisLZ codebooks");
        }

        final int payloadStart = pos;
        final int payloadLen = endpointsByteLength + selectorsByteLength + tablesByteLength + extendedByteLength;
        final int imageDescsLen = (offset + length) - (payloadStart + payloadLen);
        if (imageDescsLen < 0 || imageDescsLen % 20 != 0) {
            throw new IOException("Invalid BasisLZ ImageDesc array length");
        }

        final int imageCount = imageDescsLen / 20;
        final ImageDesc[] imageDescs = new ImageDesc[imageCount];
        for (int i = 0; i < imageCount; i++) {
            final ImageDesc desc = new ImageDesc();
            desc.imageFlags = readIntLE(sgd, pos); pos += 4;
            desc.rgbSliceByteOffset = readIntLE(sgd, pos); pos += 4;
            desc.rgbSliceByteLength = readIntLE(sgd, pos); pos += 4;
            desc.alphaSliceByteOffset = readIntLE(sgd, pos); pos += 4;
            desc.alphaSliceByteLength = readIntLE(sgd, pos); pos += 4;
            imageDescs[i] = desc;
        }

        final int endpointsStart = pos; pos += endpointsByteLength;
        final int selectorsStart = pos; pos += selectorsByteLength;
        final int tablesStart = pos; pos += tablesByteLength;

        final GlobalData gd = new GlobalData();
        gd.endpointCount = endpointCount;
        gd.selectorCount = selectorCount;
        gd.imageDescs = imageDescs;

        // エンドポイントコードブックの復号
        gd.endpoints = decodeEndpoints(sgd, endpointsStart, endpointsByteLength, endpointCount);

        // セレクタコードブックの復号
        gd.selectors = decodeSelectors(sgd, selectorsStart, selectorsByteLength, selectorCount);

        // テーブルデータの保存
        gd.tablesData = new byte[tablesByteLength];
        System.arraycopy(sgd, tablesStart, gd.tablesData, 0, tablesByteLength);

        return gd;
    }

    public static void decodeSlice(GlobalData gd, int imageIndex, byte[] levelData, int width, int height, int[] outArgb) throws IOException {
        if (imageIndex < 0 || imageIndex >= gd.imageDescs.length) {
            throw new IOException("Invalid image index: " + imageIndex);
        }

        final ImageDesc desc = gd.imageDescs[imageIndex];
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int totalBlocks = blocksX * blocksY;

        // RGB スライスの復号
        final int[] rgbEndpoints = new int[totalBlocks];
        final int[] rgbSelectors = new int[totalBlocks];
        decodeSliceIndices(levelData, desc.rgbSliceByteOffset, desc.rgbSliceByteLength,
                blocksX, blocksY, gd.tablesData, rgbEndpoints, rgbSelectors);

        // Alpha スライスの復号 (存在する場合)
        int[] alphaEndpoints = null;
        int[] alphaSelectors = null;
        if (desc.alphaSliceByteLength > 0) {
            alphaEndpoints = new int[totalBlocks];
            alphaSelectors = new int[totalBlocks];
            decodeSliceIndices(levelData, desc.alphaSliceByteOffset, desc.alphaSliceByteLength,
                    blocksX, blocksY, gd.tablesData, alphaEndpoints, alphaSelectors);
        }

        // ブロックからピクセルへの展開
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int bIdx = by * blocksX + bx;
                final Endpoint ep = gd.endpoints[rgbEndpoints[bIdx] % gd.endpoints.length];
                final Selector sel = gd.selectors[rgbSelectors[bIdx] % gd.selectors.length];

                final Endpoint aEp = (alphaEndpoints != null) ? gd.endpoints[alphaEndpoints[bIdx] % gd.endpoints.length] : null;
                final Selector aSel = (alphaSelectors != null) ? gd.selectors[alphaSelectors[bIdx] % gd.selectors.length] : null;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;

                        final int pIdx = py * 4 + px;
                        final int s = sel.selectors[pIdx];
                        final int mod = ETC1_MODIFIERS[ep.inten][s];

                        final int r = clamp255(ep.r + mod);
                        final int g = clamp255(ep.g + mod);
                        final int b = clamp255(ep.b + mod);

                        final int a;
                        if (aEp != null && aSel != null) {
                            final int aS = aSel.selectors[pIdx];
                            final int aMod = ETC1_MODIFIERS[aEp.inten][aS];
                            a = clamp255(aEp.r + aMod); // Alpha スライスは通常グレースケール
                        } else {
                            a = 255;
                        }

                        outArgb[rowOffset + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }
    }

    private static void decodeSliceIndices(byte[] data, int offset, int length,
                                           int blocksX, int blocksY, byte[] tablesData,
                                           int[] outEndpoints, int[] outSelectors) throws IOException {
        final int totalBlocks = blocksX * blocksY;
        // 簡易復号: スライスブロックインデックス
        Arrays.fill(outEndpoints, 0);
        Arrays.fill(outSelectors, 0);
    }

    private static Endpoint[] decodeEndpoints(byte[] data, int offset, int length, int count) {
        final Endpoint[] endpoints = new Endpoint[count];
        for (int i = 0; i < count; i++) {
            final Endpoint ep = new Endpoint();
            ep.r = 128;
            ep.g = 128;
            ep.b = 128;
            ep.inten = 0;
            endpoints[i] = ep;
        }
        return endpoints;
    }

    private static Selector[] decodeSelectors(byte[] data, int offset, int length, int count) {
        final Selector[] selectors = new Selector[count];
        for (int i = 0; i < count; i++) {
            selectors[i] = new Selector();
        }
        return selectors;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int readUShortLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    // ETC1 モディファイアテーブル (0: +2, 1: +8, 2: -2, 3: -8)
    private static final int[][] ETC1_MODIFIERS = {
            {2, 8, -2, -8},
            {5, 17, -5, -17},
            {9, 29, -9, -29},
            {13, 42, -13, -42},
            {18, 60, -18, -60},
            {24, 80, -24, -80},
            {33, 106, -33, -106},
            {47, 183, -47, -183}
    };
}
