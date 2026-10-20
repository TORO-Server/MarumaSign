package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.image.ArgbPixels;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * KTX 2.0 (KHR_texture_basisu) の画像を読む。最も大きい段 (level 0) の、最初の層・面だけを使う。
 * Basis Universal の ETC1S (BasisLZ) と UASTC、圧縮していない 8 ビットの形式、BC1 - BC7 に対応する。
 * 段のデータの超圧縮は、BasisLZ、Zstandard、zlib に対応する。
 */
public final class Ktx2Decoder {

    private static final byte[] IDENTIFIER = {(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'};

    private static final int SUPERCOMPRESSION_NONE = 0;
    private static final int SUPERCOMPRESSION_BASISLZ = 1;
    private static final int SUPERCOMPRESSION_ZSTD = 2;
    private static final int SUPERCOMPRESSION_ZLIB = 3;

    // データ形式の記述 (DFD) の色のモデル
    private static final int MODEL_ETC1S = 163;
    private static final int MODEL_UASTC = 166;
    private static final int TRANSFER_SRGB = 2;

    // 画像の一辺と、展開したデータの大きさの上限
    private static final int MAX_SIZE = 8192;
    private static final int MAX_LEVEL_BYTES = 256 * 1024 * 1024;

    private Ktx2Decoder() {
    }

    public static boolean isKtx2(byte[] data) {
        if (data == null || data.length < IDENTIFIER.length) return false;
        for (int i = 0; i < IDENTIFIER.length; i++) {
            if (data[i] != IDENTIFIER[i]) return false;
        }
        return true;
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isKtx2(data)) throw new IOException("Not a KTX2 image");
        try {
            return read(data);
        } catch (RuntimeException e) {
            throw new IOException("Invalid KTX2 image: " + e, e);
        }
    }

    private static BufferedImage read(byte[] data) throws IOException {
        if (data.length < 80) throw new IOException("Truncated KTX2 header");
        final int vkFormat = readInt(data, 12);
        final int width = readInt(data, 20);
        final int height = Math.max(1, readInt(data, 24));
        final int layers = Math.max(1, readInt(data, 32));
        final int faces = Math.max(1, readInt(data, 36));
        final int levels = Math.max(1, readInt(data, 40));
        final int supercompression = readInt(data, 44);
        final int dfdOffset = readInt(data, 48);
        final int dfdLength = readInt(data, 52);
        final long sgdOffset = readLong(data, 64);
        final long sgdLength = readLong(data, 72);
        if (width <= 0 || width > MAX_SIZE || height > MAX_SIZE) throw new IOException("KTX2 image exceeds safety limits: " + width + "x" + height);
        if (levels > 32 || faces > 6 || layers > 1024) throw new IOException("Invalid KTX2 header");
        if (80L + levels * 24L > data.length) throw new IOException("Truncated KTX2 level index");

        // 段の一覧。最初が最も大きい段
        final long levelOffset = readLong(data, 80);
        final long levelLength = readLong(data, 88);
        final long uncompressedLength = readLong(data, 96);
        if (levelOffset < 0 || levelLength < 0 || levelOffset + levelLength > data.length) throw new IOException("KTX2 level out of range");

        // データ形式の記述から、色のモデルと、sRGB かどうか、透明度があるかを読む
        int colorModel = 0;
        int transfer = 0;
        int samples = 0;
        if (dfdLength >= 4 + 24 && dfdOffset > 0 && (long) dfdOffset + dfdLength <= data.length) {
            final int block = dfdOffset + 4;
            colorModel = data[block + 8] & 0xFF;
            transfer = data[block + 10] & 0xFF;
            final int blockSize = readShort(data, block + 6);
            samples = Math.max(0, (blockSize - 24) / 16);
        }
        final boolean srgb = transfer == TRANSFER_SRGB;

        final int[] argb = new int[width * height];
        if (supercompression == SUPERCOMPRESSION_BASISLZ || colorModel == MODEL_ETC1S) {
            if (supercompression != SUPERCOMPRESSION_BASISLZ) throw new IOException("ETC1S KTX2 must use BasisLZ");
            decodeEtc1s(data, sgdOffset, sgdLength, levelOffset, layers * faces * levels, width, height, samples > 1, argb);
        } else {
            final byte[] level;
            final int levelStart;
            if (supercompression == SUPERCOMPRESSION_NONE) {
                level = data;
                levelStart = (int) levelOffset;
            } else if (supercompression == SUPERCOMPRESSION_ZSTD) {
                if (uncompressedLength <= 0 || uncompressedLength > MAX_LEVEL_BYTES) throw new IOException("Invalid KTX2 level size");
                level = ZstdDecoder.decompress(data, (int) levelOffset, (int) levelLength, (int) uncompressedLength);
                levelStart = 0;
            } else if (supercompression == SUPERCOMPRESSION_ZLIB) {
                if (uncompressedLength <= 0 || uncompressedLength > MAX_LEVEL_BYTES) throw new IOException("Invalid KTX2 level size");
                level = inflate(data, (int) levelOffset, (int) levelLength, (int) uncompressedLength);
                levelStart = 0;
            } else {
                throw new IOException("Unsupported KTX2 supercompression: " + supercompression);
            }
            if (colorModel == MODEL_UASTC) {
                decodeUastc(level, levelStart, width, height, srgb, argb);
            } else {
                decodeFormat(level, levelStart, vkFormat, width, height, argb);
            }
        }

        // 配列をそのまま使う (setRGB は 1 ピクセルずつ変換するので遅い)
        return ArgbPixels.wrap(width, height, argb);
    }

    // ETC1S: 全体のデータ (端点と選択子の表) と、画像ごとの位置の一覧を読んで、最初の画像を展開する
    private static void decodeEtc1s(byte[] data, long sgdOffset, long sgdLength, long levelOffset, int imageCount,
                                    int width, int height, boolean hasAlpha, int[] argb) throws IOException {
        if (sgdOffset <= 0 || sgdLength < 20 || sgdOffset + sgdLength > data.length) throw new IOException("Invalid BasisLZ global data");
        final int p = (int) sgdOffset;
        final int endpointCount = readShort(data, p);
        final int selectorCount = readShort(data, p + 2);
        final int endpointsLength = readInt(data, p + 4);
        final int selectorsLength = readInt(data, p + 8);
        final int tablesLength = readInt(data, p + 12);
        final long extendedLength = readInt(data, p + 16) & 0xFFFFFFFFL;
        final long descsLength = 20L * imageCount;
        if (endpointsLength <= 0 || selectorsLength <= 0 || tablesLength <= 0
                || 20 + descsLength + endpointsLength + (long) selectorsLength + tablesLength + extendedLength > sgdLength) {
            throw new IOException("Invalid BasisLZ global data");
        }
        // 最初の画像 (段 0、層 0、面 0) の位置
        final int desc = p + 20;
        final long rgbOffset = levelOffset + (readInt(data, desc + 4) & 0xFFFFFFFFL);
        final long rgbLength = readInt(data, desc + 8) & 0xFFFFFFFFL;
        final long alphaOffset = levelOffset + (readInt(data, desc + 12) & 0xFFFFFFFFL);
        final long alphaLength = readInt(data, desc + 16) & 0xFFFFFFFFL;
        if (rgbLength == 0 || rgbOffset + rgbLength > data.length || alphaOffset + alphaLength > data.length) {
            throw new IOException("BasisLZ image out of range");
        }

        final int codebooks = (int) (p + 20 + descsLength);
        final BasisEtc1s etc1s = new BasisEtc1s(endpointCount, data, codebooks, endpointsLength,
                selectorCount, codebooks + endpointsLength, selectorsLength,
                codebooks + endpointsLength + selectorsLength, tablesLength);
        etc1s.decodeSlice(data, (int) rgbOffset, (int) rgbLength, width, height, argb, false);
        if (hasAlpha && alphaLength > 0) {
            etc1s.decodeSlice(data, (int) alphaOffset, (int) alphaLength, width, height, argb, true);
        }
    }

    private static void decodeUastc(byte[] data, int offset, int width, int height, boolean srgb, int[] argb) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        if ((long) offset + (long) blocksX * blocksY * 16 > data.length) throw new IOException("Truncated UASTC data");
        final int[] block = new int[16];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int pos = offset + (by * blocksX + bx) * 16;
                // 不正なブロックは、元の実装と同じく紫にする
                if (!Uastc.decode(data, pos, srgb, block)) java.util.Arrays.fill(block, 0xFFFF00FF);
                for (int y = 0; y < 4; y++) {
                    final int py = by * 4 + y;
                    if (py >= height) break;
                    for (int x = 0; x < 4; x++) {
                        final int px = bx * 4 + x;
                        if (px >= width) break;
                        argb[py * width + px] = block[y * 4 + x];
                    }
                }
            }
        }
    }

    // Vulkan の形式番号ごとの読み方
    private static void decodeFormat(byte[] data, int offset, int vkFormat, int width, int height, int[] argb) throws IOException {
        switch (vkFormat) {
            case 9: case 15: // R8
                uncompressed(data, offset, width, height, 1, argb);
                return;
            case 16: case 22: // R8G8
                uncompressed(data, offset, width, height, 2, argb);
                return;
            case 23: case 29: // R8G8B8
                uncompressed(data, offset, width, height, 3, argb);
                return;
            case 30: case 36: // B8G8R8
                uncompressed(data, offset, width, height, -3, argb);
                return;
            case 37: case 43: // R8G8B8A8
                uncompressed(data, offset, width, height, 4, argb);
                return;
            case 44: case 50: // B8G8R8A8
                uncompressed(data, offset, width, height, -4, argb);
                return;
            default:
                break;
        }
        // BC1 - BC7 は DDS と同じ展開を使う
        final int dxgi = bcToDxgi(vkFormat);
        if (dxgi == 0) throw new IOException("Unsupported KTX2 vkFormat: " + vkFormat);
        DdsDecoder.decodeBlocks(data, offset, width, height, dxgi, argb);
    }

    // Vulkan の BC の形式番号と、対応する DXGI の形式番号
    private static final int[][] BC_FORMATS = {
            {131, 71}, {132, 72}, {133, 71}, {134, 72}, // BC1 RGB / RGBA
            {135, 74}, {136, 75}, {137, 77}, {138, 78}, // BC2, BC3
            {139, 80}, {140, 81}, {141, 83}, {142, 84}, // BC4, BC5
            {143, 95}, {144, 96}, {145, 98}, {146, 99}  // BC6H, BC7
    };

    private static int bcToDxgi(int vkFormat) {
        for (int[] pair : BC_FORMATS) {
            if (pair[0] == vkFormat) return pair[1];
        }
        return 0;
    }

    // 圧縮していない 8 ビットの画素。channels が負なら B と R が入れ替わった並び
    private static void uncompressed(byte[] data, int offset, int width, int height, int channels, int[] argb) throws IOException {
        final boolean bgr = channels < 0;
        final int n = Math.abs(channels);
        if ((long) offset + (long) width * height * n > data.length) throw new IOException("Truncated KTX2 data");
        for (int i = 0; i < width * height; i++) {
            final int p = offset + i * n;
            int r = data[p] & 0xFF;
            int g = n >= 2 ? data[p + 1] & 0xFF : r;
            int b = n >= 3 ? data[p + 2] & 0xFF : (n == 1 ? r : 0);
            final int a = n == 4 ? data[p + 3] & 0xFF : 255;
            if (bgr) {
                final int t = r;
                r = b;
                b = t;
            }
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static byte[] inflate(byte[] data, int offset, int length, int size) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            final byte[] buffer = new byte[65536];
            while (!inflater.finished()) {
                final int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buffer, 0, n);
                if (out.size() > size) throw new IOException("KTX2 zlib data is too large");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Invalid KTX2 zlib data", e);
        } finally {
            inflater.end();
        }
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLong(byte[] data, int offset) {
        return (readInt(data, offset) & 0xFFFFFFFFL) | ((long) readInt(data, offset + 4) << 32);
    }
}
