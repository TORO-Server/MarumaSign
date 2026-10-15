package marumasa.marumasa_sign.core.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Inflater;

/**
 * KTX 2.0 (KHR_texture_basisu) テクスチャコンテナデコーダ。
 * レベル 0 の画像を ARGB に展開する。
 * BasisLZ (ETC1S), UASTC (Zstandard / 非圧縮), Zlib, 各種 vkFormat に対応。
 */
public final class Ktx2Decoder {

    private static final byte[] KTX2_IDENTIFIER = {
            (byte) 0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xBB, 0x0D, 0x0A, 0x1A, 0x0A
    };

    // Supercompression schemes
    private static final int SCHEME_NONE = 0;
    private static final int SCHEME_BASISLZ = 1;
    private static final int SCHEME_ZSTD = 2;
    private static final int SCHEME_ZLIB = 3;

    // Vulkan Formats
    private static final int VK_FORMAT_R8_UNORM = 9;
    private static final int VK_FORMAT_R8_SRGB = 15;
    private static final int VK_FORMAT_R8G8_UNORM = 16;
    private static final int VK_FORMAT_R8G8_SRGB = 22;
    private static final int VK_FORMAT_R8G8B8_UNORM = 23;
    private static final int VK_FORMAT_R8G8B8_SRGB = 29;
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int VK_FORMAT_R8G8B8A8_SRGB = 43;
    private static final int VK_FORMAT_B8G8R8A8_UNORM = 44;
    private static final int VK_FORMAT_B8G8R8A8_SRGB = 50;

    // DFD Models
    private static final int KHR_DF_MODEL_ETC1S = 163;
    private static final int KHR_DF_MODEL_UASTC = 166;

    private static final int MAX_DIMENSION = 8192;

    private Ktx2Decoder() {
    }

    public static boolean isKtx2(byte[] data) {
        if (data == null || data.length < 12) return false;
        for (int i = 0; i < 12; i++) {
            if (data[i] != KTX2_IDENTIFIER[i]) return false;
        }
        return true;
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isKtx2(data)) {
            throw new IOException("Not a KTX2 image");
        }

        if (data.length < 80) {
            throw new IOException("KTX2 header truncated");
        }

        int pos = 12;
        final int vkFormat = readIntLE(data, pos); pos += 4;
        final int typeSize = readIntLE(data, pos); pos += 4;
        final int pixelWidth = readIntLE(data, pos); pos += 4;
        final int pixelHeight = readIntLE(data, pos); pos += 4;
        final int pixelDepth = readIntLE(data, pos); pos += 4;
        final int layerCount = readIntLE(data, pos); pos += 4;
        final int faceCount = readIntLE(data, pos); pos += 4;
        final int levelCount = readIntLE(data, pos); pos += 4;
        final int supercompressionScheme = readIntLE(data, pos); pos += 4;
        final int dfdByteOffset = readIntLE(data, pos); pos += 4;
        final int dfdByteLength = readIntLE(data, pos); pos += 4;
        final int kvdByteOffset = readIntLE(data, pos); pos += 4;
        final int kvdByteLength = readIntLE(data, pos); pos += 4;
        final long sgdByteOffset = readLongLE(data, pos); pos += 8;
        final long sgdByteLength = readLongLE(data, pos); pos += 8;

        if (pixelWidth <= 0 || pixelHeight <= 0 || pixelWidth > MAX_DIMENSION || pixelHeight > MAX_DIMENSION) {
            throw new IOException("Invalid KTX2 dimensions: " + pixelWidth + "x" + pixelHeight);
        }
        if (levelCount <= 0) {
            throw new IOException("Invalid levelCount: " + levelCount);
        }

        // レベル 0 のインデックス読み出し (オフセット 80 から levelCount 個)
        // struct { uint64 byteOffset, uint64 byteLength, uint64 uncompressedByteLength }
        if (80 + 24 > data.length) {
            throw new IOException("KTX2 level index truncated");
        }
        final long level0Offset = readLongLE(data, 80);
        final long level0Length = readLongLE(data, 88);
        final long level0UncompLength = readLongLE(data, 96);

        if (level0Offset < 0 || level0Length < 0 || level0Offset + level0Length > data.length) {
            throw new IOException("Level 0 data exceeds file boundary");
        }

        // Supercompression の解除
        final byte[] uncompressedLevel;
        if (supercompressionScheme == SCHEME_NONE) {
            uncompressedLevel = new byte[(int) level0Length];
            System.arraycopy(data, (int) level0Offset, uncompressedLevel, 0, (int) level0Length);
        } else if (supercompressionScheme == SCHEME_ZSTD) {
            final int maxUncomp = (level0UncompLength > 0 && level0UncompLength <= 64 * 1024 * 1024)
                    ? (int) level0UncompLength : 64 * 1024 * 1024;
            uncompressedLevel = ZstdDecoder.decompress(data, (int) level0Offset, (int) level0Length, maxUncomp);
        } else if (supercompressionScheme == SCHEME_ZLIB) {
            uncompressedLevel = decompressZlib(data, (int) level0Offset, (int) level0Length, (int) level0UncompLength);
        } else if (supercompressionScheme == SCHEME_BASISLZ) {
            uncompressedLevel = new byte[(int) level0Length];
            System.arraycopy(data, (int) level0Offset, uncompressedLevel, 0, (int) level0Length);
        } else {
            throw new IOException("Unsupported supercompressionScheme: " + supercompressionScheme);
        }

        // DFD の解析
        boolean isSrgb = true;
        int colorModel = 0;
        if (dfdByteOffset > 0 && dfdByteLength >= 16 && dfdByteOffset + dfdByteLength <= data.length) {
            colorModel = data[dfdByteOffset + 12] & 0xFF;
            final int transfer = data[dfdByteOffset + 14] & 0xFF;
            // 1=LINEAR, 2=SRGB (glTF base color は sRGB、出力は格納通りの ARGB)
            isSrgb = (transfer != 1);
        }

        final int[] argb = new int[pixelWidth * pixelHeight];

        if (vkFormat != 0) {
            // 非圧縮 Vulkan フォーマット
            decodeVkFormat(uncompressedLevel, pixelWidth, pixelHeight, vkFormat, argb);
        } else if (supercompressionScheme == SCHEME_BASISLZ || colorModel == KHR_DF_MODEL_ETC1S) {
            // BasisLZ / ETC1S
            if (sgdByteOffset < 0 || sgdByteLength <= 0 || sgdByteOffset + sgdByteLength > data.length) {
                throw new IOException("Missing or invalid BasisLZ global data");
            }
            final BasisLzDecoder.GlobalData gd = BasisLzDecoder.parseGlobalData(data, (int) sgdByteOffset, (int) sgdByteLength);
            BasisLzDecoder.decodeSlice(gd, 0, uncompressedLevel, pixelWidth, pixelHeight, argb);
        } else if (colorModel == KHR_DF_MODEL_UASTC) {
            // UASTC
            UastcDecoder.decode(uncompressedLevel, 0, pixelWidth, pixelHeight, isSrgb, argb);
        } else {
            throw new IOException("Unsupported KTX2 payload (vkFormat=" + vkFormat + ", colorModel=" + colorModel + ")");
        }

        final BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixelWidth, pixelHeight, argb, 0, pixelWidth);
        return image;
    }

    private static void decodeVkFormat(byte[] data, int width, int height, int vkFormat, int[] out) throws IOException {
        switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM:
            case VK_FORMAT_R8G8B8A8_SRGB: {
                int pos = 0;
                for (int i = 0; i < out.length; i++) {
                    final int r = data[pos++] & 0xFF;
                    final int g = data[pos++] & 0xFF;
                    final int b = data[pos++] & 0xFF;
                    final int a = data[pos++] & 0xFF;
                    out[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                break;
            }
            case VK_FORMAT_B8G8R8A8_UNORM:
            case VK_FORMAT_B8G8R8A8_SRGB: {
                int pos = 0;
                for (int i = 0; i < out.length; i++) {
                    final int b = data[pos++] & 0xFF;
                    final int g = data[pos++] & 0xFF;
                    final int r = data[pos++] & 0xFF;
                    final int a = data[pos++] & 0xFF;
                    out[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                break;
            }
            case VK_FORMAT_R8G8B8_UNORM:
            case VK_FORMAT_R8G8B8_SRGB: {
                int pos = 0;
                for (int i = 0; i < out.length; i++) {
                    final int r = data[pos++] & 0xFF;
                    final int g = data[pos++] & 0xFF;
                    final int b = data[pos++] & 0xFF;
                    out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                break;
            }
            case VK_FORMAT_R8_UNORM:
            case VK_FORMAT_R8_SRGB: {
                int pos = 0;
                for (int i = 0; i < out.length; i++) {
                    final int r = data[pos++] & 0xFF;
                    out[i] = 0xFF000000 | (r << 16) | (r << 8) | r;
                }
                break;
            }
            case VK_FORMAT_R8G8_UNORM:
            case VK_FORMAT_R8G8_SRGB: {
                int pos = 0;
                for (int i = 0; i < out.length; i++) {
                    final int r = data[pos++] & 0xFF;
                    final int g = data[pos++] & 0xFF;
                    out[i] = 0xFF000000 | (r << 16) | (g << 8);
                }
                break;
            }
            default:
                throw new IOException("Unsupported vkFormat: " + vkFormat);
        }
    }

    private static byte[] decompressZlib(byte[] data, int offset, int length, int uncompressedSize) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream((uncompressedSize > 0) ? uncompressedSize : 65536);
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) break;
                baos.write(buffer, 0, count);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IOException("Zlib decompression failed: " + e, e);
        } finally {
            inflater.end();
        }
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        return (readIntLE(data, offset) & 0xFFFFFFFFL) | (((long) readIntLE(data, offset + 4)) << 32);
    }
}
