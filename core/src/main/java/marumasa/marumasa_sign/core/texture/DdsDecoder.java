package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.image.ArgbPixels;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * DDS (DirectDraw Surface) 形式の画像を読み込む。
 * BC1 (DXT1), BC2 (DXT2/3), BC3 (DXT4/5), BC4, BC5, BC7 および非圧縮 (RGBA/BGRA) に対応。
 */
public final class DdsDecoder {

    private static final int DDS_MAGIC = 0x20534444; // "DDS "
    private static final int DXT1 = 0x31545844;
    private static final int DXT2 = 0x32545844;
    private static final int DXT3 = 0x33545844;
    private static final int DXT4 = 0x34545844;
    private static final int DXT5 = 0x35545844;
    private static final int ATI1 = 0x31495441;
    private static final int BC4U = 0x55344342;
    private static final int BC4S = 0x53344342;
    private static final int ATI2 = 0x32495441;
    private static final int BC5U = 0x55354342;
    private static final int BC5S = 0x53354342;
    private static final int DX10 = 0x30315844;

    private static final int DDPF_ALPHAPIXELS = 0x1;
    private static final int DDPF_FOURCC = 0x4;
    private static final int DDPF_PALETTEINDEXED8 = 0x20;
    private static final int DDPF_RGB = 0x40;
    private static final int DDPF_LUMINANCE = 0x20000;

    // DXGI 形式
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM_SRGB = 29;
    private static final int DXGI_FORMAT_BC1_TYPELESS = 70;
    private static final int DXGI_FORMAT_BC2_TYPELESS = 73;
    private static final int DXGI_FORMAT_BC3_TYPELESS = 76;
    private static final int DXGI_FORMAT_BC4_TYPELESS = 79;
    private static final int DXGI_FORMAT_BC5_TYPELESS = 82;
    private static final int DXGI_FORMAT_BC6H_TYPELESS = 94;
    private static final int DXGI_FORMAT_BC6H_UF16 = 95;
    private static final int DXGI_FORMAT_BC6H_SF16 = 96;
    private static final int DXGI_FORMAT_BC7_TYPELESS = 97;
    private static final int DXGI_FORMAT_BC1_UNORM = 71;
    private static final int DXGI_FORMAT_BC1_UNORM_SRGB = 72;
    private static final int DXGI_FORMAT_BC2_UNORM = 74;
    private static final int DXGI_FORMAT_BC2_UNORM_SRGB = 75;
    private static final int DXGI_FORMAT_BC3_UNORM = 77;
    private static final int DXGI_FORMAT_BC3_UNORM_SRGB = 78;
    private static final int DXGI_FORMAT_BC4_UNORM = 80;
    private static final int DXGI_FORMAT_BC4_SNORM = 81;
    private static final int DXGI_FORMAT_BC5_UNORM = 83;
    private static final int DXGI_FORMAT_BC5_SNORM = 84;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int DXGI_FORMAT_B8G8R8X8_UNORM = 88;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM_SRGB = 91;
    private static final int DXGI_FORMAT_B8G8R8X8_UNORM_SRGB = 93;
    private static final int DXGI_FORMAT_BC7_UNORM = 98;
    private static final int DXGI_FORMAT_BC7_UNORM_SRGB = 99;

    private static final int MAX_DIMENSION = 8192;

    private DdsDecoder() {
    }

    public static boolean isDds(byte[] data) {
        return data != null && data.length >= 128 && readIntLE(data, 0) == DDS_MAGIC;
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isDds(data)) {
            throw new IOException("Not a DDS image");
        }

        final int headerSize = readIntLE(data, 4);
        if (headerSize != 124) {
            throw new IOException("Invalid DDS header size: " + headerSize);
        }

        final int height = readIntLE(data, 12);
        final int width = readIntLE(data, 16);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("Invalid DDS dimensions: " + width + "x" + height);
        }

        final int pfFlags = readIntLE(data, 80);
        final int fourCC = readIntLE(data, 84);
        final int rgbBitCount = readIntLE(data, 88);
        final int rMask = readIntLE(data, 92);
        final int gMask = readIntLE(data, 96);
        final int bMask = readIntLE(data, 100);
        final int aMask = readIntLE(data, 104);

        int offset = 128;
        int dxgiFormat = 0;

        if ((pfFlags & DDPF_FOURCC) != 0) {
            if (fourCC == DX10) {
                if (data.length < 148) {
                    throw new IOException("DDS DX10 header truncated");
                }
                dxgiFormat = readIntLE(data, 128);
                offset = 148;
            }
        }

        final int[] argb = new int[width * height];

        if (dxgiFormat != 0) {
            decodeDxgi(data, offset, width, height, dxgiFormat, argb);
        } else if ((pfFlags & DDPF_FOURCC) != 0) {
            decodeFourCC(data, offset, width, height, fourCC, argb);
        } else if ((pfFlags & (DDPF_RGB | DDPF_LUMINANCE)) != 0) {
            decodeUncompressed(data, offset, width, height, rgbBitCount, rMask, gMask, bMask, aMask, (pfFlags & DDPF_ALPHAPIXELS) != 0,
                    (pfFlags & DDPF_LUMINANCE) != 0, argb);
        } else if ((pfFlags & DDPF_PALETTEINDEXED8) != 0) {
            decodePalette(data, offset, width, height, argb);
        } else {
            throw new IOException("Unsupported DDS pixel format (flags=" + pfFlags + ", fourCC=" + fourCC + ")");
        }

        // 配列をそのまま使う (setRGB は 1 ピクセルずつ変換するので遅い)
        return ArgbPixels.wrap(width, height, argb);
    }

    private static void decodeFourCC(byte[] data, int offset, int width, int height, int fourCC, int[] out) throws IOException {
        switch (fourCC) {
            case DXT1:
                decodeBc1(data, offset, width, height, out);
                break;
            case DXT2:
            case DXT3:
                decodeBc2(data, offset, width, height, out);
                break;
            case DXT4:
            case DXT5:
                decodeBc3(data, offset, width, height, out);
                break;
            case ATI1:
            case BC4U:
                decodeBc4(data, offset, width, height, false, out);
                break;
            case BC4S:
                decodeBc4(data, offset, width, height, true, out);
                break;
            case ATI2:
            case BC5U:
                decodeBc5(data, offset, width, height, false, out);
                break;
            case BC5S:
                decodeBc5(data, offset, width, height, true, out);
                break;
            default:
                throw new IOException("Unsupported DDS FourCC: 0x" + Integer.toHexString(fourCC));
        }
    }

    // KTX2 の BC の形式にも使う
    static void decodeBlocks(byte[] data, int offset, int width, int height, int dxgiFormat, int[] out) throws IOException {
        decodeDxgi(data, offset, width, height, dxgiFormat, out);
    }

    private static void decodeDxgi(byte[] data, int offset, int width, int height, int dxgiFormat, int[] out) throws IOException {
        // 型を決めていない形式 (TYPELESS) は、符号なしとして読む
        switch (dxgiFormat) {
            case DXGI_FORMAT_BC1_TYPELESS:
            case DXGI_FORMAT_BC1_UNORM:
            case DXGI_FORMAT_BC1_UNORM_SRGB:
                decodeBc1(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC2_TYPELESS:
            case DXGI_FORMAT_BC2_UNORM:
            case DXGI_FORMAT_BC2_UNORM_SRGB:
                decodeBc2(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC3_TYPELESS:
            case DXGI_FORMAT_BC3_UNORM:
            case DXGI_FORMAT_BC3_UNORM_SRGB:
                decodeBc3(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC4_TYPELESS:
            case DXGI_FORMAT_BC4_UNORM:
                decodeBc4(data, offset, width, height, false, out);
                break;
            case DXGI_FORMAT_BC4_SNORM:
                decodeBc4(data, offset, width, height, true, out);
                break;
            case DXGI_FORMAT_BC5_TYPELESS:
            case DXGI_FORMAT_BC5_UNORM:
                decodeBc5(data, offset, width, height, false, out);
                break;
            case DXGI_FORMAT_BC5_SNORM:
                decodeBc5(data, offset, width, height, true, out);
                break;
            case DXGI_FORMAT_BC6H_TYPELESS:
            case DXGI_FORMAT_BC6H_UF16:
                decodeBc6h(data, offset, width, height, false, out);
                break;
            case DXGI_FORMAT_BC6H_SF16:
                decodeBc6h(data, offset, width, height, true, out);
                break;
            case DXGI_FORMAT_BC7_TYPELESS:
            case DXGI_FORMAT_BC7_UNORM:
            case DXGI_FORMAT_BC7_UNORM_SRGB:
                decodeBc7(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_R8G8B8A8_UNORM:
            case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000, true, false, out);
                break;
            case DXGI_FORMAT_B8G8R8A8_UNORM:
            case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000, true, false, out);
                break;
            case DXGI_FORMAT_B8G8R8X8_UNORM:
            case DXGI_FORMAT_B8G8R8X8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0, false, false, out);
                break;
            default:
                throw new IOException("Unsupported DDS DXGI format: " + dxgiFormat);
        }
    }

    // BC1 (DXT1)
    private static void decodeBc1(byte[] data, int offset, int width, int height, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 8;
        if (required > data.length || required < 0) {
            throw new IOException("BC1 data truncated");
        }

        final int[] colors = new int[4];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int c0 = readUShortLE(data, pos);
                final int c1 = readUShortLE(data, pos + 2);
                pos += 4;

                final int r0 = ((c0 >>> 11) & 31) * 255 / 31;
                final int g0 = ((c0 >>> 5) & 63) * 255 / 63;
                final int b0 = (c0 & 31) * 255 / 31;

                final int r1 = ((c1 >>> 11) & 31) * 255 / 31;
                final int g1 = ((c1 >>> 5) & 63) * 255 / 63;
                final int b1 = (c1 & 31) * 255 / 31;

                colors[0] = 0xFF000000 | (r0 << 16) | (g0 << 8) | b0;
                colors[1] = 0xFF000000 | (r1 << 16) | (g1 << 8) | b1;

                if (c0 > c1) {
                    colors[2] = 0xFF000000 | (((2 * r0 + r1) / 3) << 16) | (((2 * g0 + g1) / 3) << 8) | ((2 * b0 + b1) / 3);
                    colors[3] = 0xFF000000 | (((r0 + 2 * r1) / 3) << 16) | (((g0 + 2 * g1) / 3) << 8) | ((b0 + 2 * b1) / 3);
                } else {
                    colors[2] = 0xFF000000 | (((r0 + r1) / 2) << 16) | (((g0 + g1) / 2) << 8) | ((b0 + b1) / 2);
                    colors[3] = 0; // 完全透明
                }

                final int bits = readIntLE(data, pos);
                pos += 4;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int code = (bits >>> ((py * 4 + px) * 2)) & 3;
                        out[rowOffset + x] = colors[code];
                    }
                }
            }
        }
    }

    // BC2 (DXT2/3)
    private static void decodeBc2(byte[] data, int offset, int width, int height, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 16;
        if (required > data.length || required < 0) {
            throw new IOException("BC2 data truncated");
        }

        final int[] colors = new int[4];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final long alphaBits = readLongLE(data, pos);
                pos += 8;

                final int c0 = readUShortLE(data, pos);
                final int c1 = readUShortLE(data, pos + 2);
                pos += 4;

                final int r0 = ((c0 >>> 11) & 31) * 255 / 31;
                final int g0 = ((c0 >>> 5) & 63) * 255 / 63;
                final int b0 = (c0 & 31) * 255 / 31;

                final int r1 = ((c1 >>> 11) & 31) * 255 / 31;
                final int g1 = ((c1 >>> 5) & 63) * 255 / 63;
                final int b1 = (c1 & 31) * 255 / 31;

                colors[0] = (r0 << 16) | (g0 << 8) | b0;
                colors[1] = (r1 << 16) | (g1 << 8) | b1;
                colors[2] = (((2 * r0 + r1) / 3) << 16) | (((2 * g0 + g1) / 3) << 8) | ((2 * b0 + b1) / 3);
                colors[3] = (((r0 + 2 * r1) / 3) << 16) | (((g0 + 2 * g1) / 3) << 8) | ((b0 + 2 * b1) / 3);

                final int bits = readIntLE(data, pos);
                pos += 4;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int pIdx = py * 4 + px;
                        final int code = (bits >>> (pIdx * 2)) & 3;
                        final int a4 = (int) ((alphaBits >>> (pIdx * 4)) & 0xF);
                        final int alpha = a4 | (a4 << 4);
                        out[rowOffset + x] = (alpha << 24) | colors[code];
                    }
                }
            }
        }
    }

    // BC3 (DXT4/5)
    private static void decodeBc3(byte[] data, int offset, int width, int height, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 16;
        if (required > data.length || required < 0) {
            throw new IOException("BC3 data truncated");
        }

        final int[] colors = new int[4];
        final int[] alphas = new int[8];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int a0 = data[pos] & 0xFF;
                final int a1 = data[pos + 1] & 0xFF;
                alphas[0] = a0;
                alphas[1] = a1;
                if (a0 > a1) {
                    for (int i = 1; i <= 6; i++) {
                        alphas[i + 1] = ((7 - i) * a0 + i * a1) / 7;
                    }
                } else {
                    for (int i = 1; i <= 4; i++) {
                        alphas[i + 1] = ((5 - i) * a0 + i * a1) / 5;
                    }
                    alphas[6] = 0;
                    alphas[7] = 255;
                }

                // 48-bit alpha indices
                final long aIndices = ((data[pos + 2] & 0xFFL))
                        | ((data[pos + 3] & 0xFFL) << 8)
                        | ((data[pos + 4] & 0xFFL) << 16)
                        | ((data[pos + 5] & 0xFFL) << 24)
                        | ((data[pos + 6] & 0xFFL) << 32)
                        | ((data[pos + 7] & 0xFFL) << 40);
                pos += 8;

                final int c0 = readUShortLE(data, pos);
                final int c1 = readUShortLE(data, pos + 2);
                pos += 4;

                final int r0 = ((c0 >>> 11) & 31) * 255 / 31;
                final int g0 = ((c0 >>> 5) & 63) * 255 / 63;
                final int b0 = (c0 & 31) * 255 / 31;

                final int r1 = ((c1 >>> 11) & 31) * 255 / 31;
                final int g1 = ((c1 >>> 5) & 63) * 255 / 63;
                final int b1 = (c1 & 31) * 255 / 31;

                colors[0] = (r0 << 16) | (g0 << 8) | b0;
                colors[1] = (r1 << 16) | (g1 << 8) | b1;
                colors[2] = (((2 * r0 + r1) / 3) << 16) | (((2 * g0 + g1) / 3) << 8) | ((2 * b0 + b1) / 3);
                colors[3] = (((r0 + 2 * r1) / 3) << 16) | (((g0 + 2 * g1) / 3) << 8) | ((b0 + 2 * b1) / 3);

                final int bits = readIntLE(data, pos);
                pos += 4;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int pIdx = py * 4 + px;
                        final int cCode = (bits >>> (pIdx * 2)) & 3;
                        final int aCode = (int) ((aIndices >>> (pIdx * 3)) & 7);
                        out[rowOffset + x] = (alphas[aCode] << 24) | colors[cCode];
                    }
                }
            }
        }
    }

    // BC4 (1 チャンネル)
    private static void decodeBc4(byte[] data, int offset, int width, int height, boolean signed, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 8;
        if (required > data.length || required < 0) {
            throw new IOException("BC4 data truncated");
        }

        final int[] table = new int[8];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                decodeBc4Block(data, pos, signed, table);
                final long indices = ((data[pos + 2] & 0xFFL))
                        | ((data[pos + 3] & 0xFFL) << 8)
                        | ((data[pos + 4] & 0xFFL) << 16)
                        | ((data[pos + 5] & 0xFFL) << 24)
                        | ((data[pos + 6] & 0xFFL) << 32)
                        | ((data[pos + 7] & 0xFFL) << 40);
                pos += 8;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int pIdx = py * 4 + px;
                        final int code = (int) ((indices >>> (pIdx * 3)) & 7);
                        final int val = table[code];
                        out[rowOffset + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
                    }
                }
            }
        }
    }

    private static void decodeBc4Block(byte[] data, int pos, boolean signed, int[] table) {
        if (!signed) {
            final int e0 = data[pos] & 0xFF;
            final int e1 = data[pos + 1] & 0xFF;
            table[0] = e0;
            table[1] = e1;
            if (e0 > e1) {
                for (int i = 1; i <= 6; i++) {
                    table[i + 1] = ((7 - i) * e0 + i * e1) / 7;
                }
            } else {
                for (int i = 1; i <= 4; i++) {
                    table[i + 1] = ((5 - i) * e0 + i * e1) / 5;
                }
                table[6] = 0;
                table[7] = 255;
            }
        } else {
            final int e0 = data[pos];
            final int e1 = data[pos + 1];
            table[0] = clamp255(e0 + 128);
            table[1] = clamp255(e1 + 128);
            if (e0 > e1) {
                for (int i = 1; i <= 6; i++) {
                    table[i + 1] = clamp255((((7 - i) * e0 + i * e1) / 7) + 128);
                }
            } else {
                for (int i = 1; i <= 4; i++) {
                    table[i + 1] = clamp255((((5 - i) * e0 + i * e1) / 5) + 128);
                }
                table[6] = 0;
                table[7] = 255;
            }
        }
    }

    // BC5 (2 チャンネル: R + G, 法線マップなど)
    private static void decodeBc5(byte[] data, int offset, int width, int height, boolean signed, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 16;
        if (required > data.length || required < 0) {
            throw new IOException("BC5 data truncated");
        }

        final int[] rTable = new int[8];
        final int[] gTable = new int[8];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                decodeBc4Block(data, pos, signed, rTable);
                final long rIndices = ((data[pos + 2] & 0xFFL))
                        | ((data[pos + 3] & 0xFFL) << 8)
                        | ((data[pos + 4] & 0xFFL) << 16)
                        | ((data[pos + 5] & 0xFFL) << 24)
                        | ((data[pos + 6] & 0xFFL) << 32)
                        | ((data[pos + 7] & 0xFFL) << 40);
                pos += 8;

                decodeBc4Block(data, pos, signed, gTable);
                final long gIndices = ((data[pos + 2] & 0xFFL))
                        | ((data[pos + 3] & 0xFFL) << 8)
                        | ((data[pos + 4] & 0xFFL) << 16)
                        | ((data[pos + 5] & 0xFFL) << 24)
                        | ((data[pos + 6] & 0xFFL) << 32)
                        | ((data[pos + 7] & 0xFFL) << 40);
                pos += 8;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int pIdx = py * 4 + px;
                        final int rVal = rTable[(int) ((rIndices >>> (pIdx * 3)) & 7)];
                        final int gVal = gTable[(int) ((gIndices >>> (pIdx * 3)) & 7)];

                        // 法線の Z チャンネルを復元: z = sqrt(max(0, 1 - x^2 - y^2))
                        final float nx = (rVal / 255.0f) * 2.0f - 1.0f;
                        final float ny = (gVal / 255.0f) * 2.0f - 1.0f;
                        final float nz2 = Math.max(0.0f, 1.0f - nx * nx - ny * ny);
                        final int bVal = Math.min(255, Math.max(0, Math.round(((float) Math.sqrt(nz2) * 0.5f + 0.5f) * 255.0f)));

                        out[rowOffset + x] = 0xFF000000 | (rVal << 16) | (gVal << 8) | bVal;
                    }
                }
            }
        }
    }

    // BC7
    private static void decodeBc7(byte[] data, int offset, int width, int height, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final int required = offset + blocksX * blocksY * 16;
        if (required > data.length || required < 0) {
            throw new IOException("BC7 data truncated");
        }

        final int[] blockPixels = new int[16];
        int pos = offset;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                Bptc.bc7(data, pos, blockPixels);
                pos += 16;

                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    final int rowOffset = y * width;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        out[rowOffset + x] = blockPixels[py * 4 + px];
                    }
                }
            }
        }
    }

    // 非圧縮 (RGBA/BGRA/RGB/BGR/Luminance 等)
    private static void decodeUncompressed(byte[] data, int offset, int width, int height,
                                           int rgbBitCount, int rMask, int gMask, int bMask, int aMask,
                                           boolean hasAlpha, boolean luminance, int[] out) throws IOException {
        if (rgbBitCount <= 0 || rgbBitCount > 32) throw new IOException("Unsupported DDS bit count: " + rgbBitCount);
        if (luminance && rgbBitCount < 32 && (rMask >>> rgbBitCount) != 0) {
            // 画素より広いマスクが書かれていることがある。明るさは下位のビット、透明度は残りのビットとみなす
            final int lumaBits = (aMask != 0 && hasAlpha) ? rgbBitCount / 2 : rgbBitCount;
            rMask = (1 << lumaBits) - 1;
            if (hasAlpha && aMask != 0) aMask = ((1 << rgbBitCount) - 1) & ~rMask;
        }
        final int bytesPerPixel = (rgbBitCount + 7) / 8;
        final int rowPitch = ((width * rgbBitCount + 31) / 32) * 4;
        final int required = offset + (height - 1) * rowPitch + width * bytesPerPixel;
        if (required > data.length || required < 0) {
            throw new IOException("Uncompressed DDS data truncated");
        }

        final int rShift = getShift(rMask);
        final int gShift = getShift(gMask);
        final int bShift = getShift(bMask);
        final int aShift = getShift(aMask);

        final int rMax = rMask != 0 ? (rMask >>> rShift) : 0;
        final int gMax = gMask != 0 ? (gMask >>> gShift) : 0;
        final int bMax = bMask != 0 ? (bMask >>> bShift) : 0;
        final int aMax = aMask != 0 ? (aMask >>> aShift) : 0;

        for (int y = 0; y < height; y++) {
            int rowOffset = offset + y * rowPitch;
            final int outOffset = y * width;
            for (int x = 0; x < width; x++) {
                final int pixel;
                if (bytesPerPixel == 4) {
                    pixel = readIntLE(data, rowOffset);
                } else if (bytesPerPixel == 3) {
                    pixel = (data[rowOffset] & 0xFF) | ((data[rowOffset + 1] & 0xFF) << 8) | ((data[rowOffset + 2] & 0xFF) << 16);
                } else if (bytesPerPixel == 2) {
                    pixel = readUShortLE(data, rowOffset);
                } else {
                    pixel = data[rowOffset] & 0xFF;
                }
                rowOffset += bytesPerPixel;

                final int r = rMax != 0 ? ((pixel & rMask) >>> rShift) * 255 / rMax : 0;
                // 明るさだけの画像 (R のマスクだけがある) は、灰色にする
                final int g = luminance ? r : gMax != 0 ? ((pixel & gMask) >>> gShift) * 255 / gMax : 0;
                final int b = luminance ? r : bMax != 0 ? ((pixel & bMask) >>> bShift) * 255 / bMax : 0;
                final int a = (hasAlpha && aMax != 0) ? ((pixel & aMask) >>> aShift) * 255 / aMax : 255;

                out[outOffset + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int getShift(int mask) {
        if (mask == 0) return 0;
        return Integer.numberOfTrailingZeros(mask);
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

    private static long readLongLE(byte[] data, int offset) {
        return (readIntLE(data, offset) & 0xFFFFFFFFL) | (((long) readIntLE(data, offset + 4)) << 32);
    }

    // 256 色のパレット (RGBA の並び 1024 バイト) と、画素ごとの 1 バイトの番号
    private static void decodePalette(byte[] data, int offset, int width, int height, int[] out) throws IOException {
        final long required = offset + 1024L + (long) width * height;
        if (required > data.length) throw new IOException("Palette DDS data truncated");
        final int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            final int p = offset + i * 4;
            palette[i] = ((data[p + 3] & 0xFF) << 24) | ((data[p] & 0xFF) << 16) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] & 0xFF);
        }
        final int pixels = offset + 1024;
        for (int i = 0; i < width * height; i++) {
            out[i] = palette[data[pixels + i] & 0xFF];
        }
    }

    // BC6H (HDR)。明るさを 0 - 1 に収めてから、表示用 (sRGB) の 8 ビットにする
    private static void decodeBc6h(byte[] data, int offset, int width, int height, boolean signed, int[] out) throws IOException {
        final int blocksX = (width + 3) / 4;
        final int blocksY = (height + 3) / 4;
        final long required = offset + (long) blocksX * blocksY * 16;
        if (required > data.length) throw new IOException("BC6H data truncated");
        final float[] block = new float[48];
        int pos = offset;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                Bptc.bc6h(data, pos, signed, block);
                pos += 16;
                for (int py = 0; py < 4; py++) {
                    final int y = by * 4 + py;
                    if (y >= height) continue;
                    for (int px = 0; px < 4; px++) {
                        final int x = bx * 4 + px;
                        if (x >= width) continue;
                        final int o = (py * 4 + px) * 3;
                        out[y * width + x] = 0xFF000000 | (toDisplay(block[o]) << 16) | (toDisplay(block[o + 1]) << 8) | toDisplay(block[o + 2]);
                    }
                }
            }
        }
    }

    // リニアの値を、表示用 (sRGB) の 0 - 255 にする
    private static int toDisplay(float linear) {
        if (!(linear > 0)) return 0;
        if (linear >= 1) return 255;
        final double srgb = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.round(srgb * 255);
    }
}
