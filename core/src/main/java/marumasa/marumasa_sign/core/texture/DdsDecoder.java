package marumasa.marumasa_sign.core.texture;

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
    private static final int DDPF_RGB = 0x40;
    private static final int DDPF_LUMINANCE = 0x20000;

    // DXGI 形式
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM = 28;
    private static final int DXGI_FORMAT_R8G8B8A8_UNORM_SRGB = 29;
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
            decodeUncompressed(data, offset, width, height, rgbBitCount, rMask, gMask, bMask, aMask, (pfFlags & DDPF_ALPHAPIXELS) != 0, argb);
        } else {
            throw new IOException("Unsupported DDS pixel format (flags=" + pfFlags + ", fourCC=" + fourCC + ")");
        }

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
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

    private static void decodeDxgi(byte[] data, int offset, int width, int height, int dxgiFormat, int[] out) throws IOException {
        switch (dxgiFormat) {
            case DXGI_FORMAT_BC1_UNORM:
            case DXGI_FORMAT_BC1_UNORM_SRGB:
                decodeBc1(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC2_UNORM:
            case DXGI_FORMAT_BC2_UNORM_SRGB:
                decodeBc2(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC3_UNORM:
            case DXGI_FORMAT_BC3_UNORM_SRGB:
                decodeBc3(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_BC4_UNORM:
                decodeBc4(data, offset, width, height, false, out);
                break;
            case DXGI_FORMAT_BC4_SNORM:
                decodeBc4(data, offset, width, height, true, out);
                break;
            case DXGI_FORMAT_BC5_UNORM:
                decodeBc5(data, offset, width, height, false, out);
                break;
            case DXGI_FORMAT_BC5_SNORM:
                decodeBc5(data, offset, width, height, true, out);
                break;
            case DXGI_FORMAT_BC7_UNORM:
            case DXGI_FORMAT_BC7_UNORM_SRGB:
                decodeBc7(data, offset, width, height, out);
                break;
            case DXGI_FORMAT_R8G8B8A8_UNORM:
            case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000, true, out);
                break;
            case DXGI_FORMAT_B8G8R8A8_UNORM:
            case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000, true, out);
                break;
            case DXGI_FORMAT_B8G8R8X8_UNORM:
            case DXGI_FORMAT_B8G8R8X8_UNORM_SRGB:
                decodeUncompressed(data, offset, width, height, 32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0, false, out);
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
                decodeBc7Block(data, pos, blockPixels);
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

    // BC7 モード仕様
    private static final int[] BC7_SUBSETS = {3, 2, 3, 2, 1, 1, 1, 2};
    private static final int[] BC7_PARTITION_BITS = {4, 6, 6, 6, 0, 0, 0, 6};
    private static final int[] BC7_ROTATION_BITS = {0, 0, 0, 0, 2, 2, 0, 0};
    private static final int[] BC7_INDEX_MODE_BITS = {0, 0, 0, 0, 1, 0, 0, 0};
    private static final int[] BC7_INDEX_PREC = {3, 3, 2, 2, 2, 2, 4, 2};
    private static final int[] BC7_INDEX_PREC2 = {0, 0, 0, 0, 3, 2, 0, 0};
    private static final int[] BC7_PBITS = {6, 2, 0, 4, 0, 0, 2, 4};
    private static final int[][] BC7_RGBA_PREC = {
            {4, 4, 4, 0}, {6, 6, 6, 0}, {5, 5, 5, 0}, {7, 7, 7, 0},
            {5, 5, 5, 6}, {7, 7, 7, 8}, {7, 7, 7, 7}, {5, 5, 5, 5}
    };

    private static final int[] WEIGHTS2 = {0, 21, 43, 64};
    private static final int[] WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    private static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    private static void decodeBc7Block(byte[] data, int pos, int[] outPixels) {
        // 先頭ビットからモード番号 (0〜7) を特定
        int mode = -1;
        for (int i = 0; i < 8; i++) {
            if (getBit(data, pos, i) != 0) {
                mode = i;
                break;
            }
        }
        if (mode == -1) {
            // 不正なブロック
            for (int i = 0; i < 16; i++) outPixels[i] = 0;
            return;
        }

        int bitPos = mode + 1;
        final int numSubsets = BC7_SUBSETS[mode];
        final int numEndPts = numSubsets * 2;
        final int partition = BC7_PARTITION_BITS[mode] > 0 ? getBits(data, pos, bitPos, BC7_PARTITION_BITS[mode]) : 0;
        bitPos += BC7_PARTITION_BITS[mode];

        final int rotation = BC7_ROTATION_BITS[mode] > 0 ? getBits(data, pos, bitPos, BC7_ROTATION_BITS[mode]) : 0;
        bitPos += BC7_ROTATION_BITS[mode];

        final int indexMode = BC7_INDEX_MODE_BITS[mode] > 0 ? getBits(data, pos, bitPos, BC7_INDEX_MODE_BITS[mode]) : 0;
        bitPos += BC7_INDEX_MODE_BITS[mode];

        final int[] rPrec = BC7_RGBA_PREC[mode];
        final int[][] ep = new int[numEndPts][4];

        // R, G, B, A チャンネル読み出し
        for (int ch = 0; ch < 4; ch++) {
            final int prec = rPrec[ch];
            if (prec > 0) {
                for (int e = 0; e < numEndPts; e++) {
                    ep[e][ch] = getBits(data, pos, bitPos, prec);
                    bitPos += prec;
                }
            } else {
                for (int e = 0; e < numEndPts; e++) {
                    ep[e][ch] = 255;
                }
            }
        }

        // P-bits 読み出し
        final int pBitsCount = BC7_PBITS[mode];
        if (pBitsCount > 0) {
            final int[] pBits = new int[pBitsCount];
            for (int p = 0; p < pBitsCount; p++) {
                pBits[p] = getBit(data, pos, bitPos++);
            }
            for (int e = 0; e < numEndPts; e++) {
                final int pi = e * pBitsCount / numEndPts;
                final int p = pBits[pi];
                for (int ch = 0; ch < 4; ch++) {
                    if (rPrec[ch] > 0) {
                        ep[e][ch] = (ep[e][ch] << 1) | p;
                    }
                }
            }
        }

        // 8 ビット値へアンクォンタイズ
        for (int e = 0; e < numEndPts; e++) {
            for (int ch = 0; ch < 4; ch++) {
                if (rPrec[ch] > 0) {
                    final int effPrec = rPrec[ch] + (pBitsCount > 0 ? 1 : 0);
                    ep[e][ch] = unquantizeBc7(ep[e][ch], effPrec);
                }
            }
        }

        // インデックス読み出し
        final int indexPrec = BC7_INDEX_PREC[mode];
        final int indexPrec2 = BC7_INDEX_PREC2[mode];
        final int[] w1 = new int[16];
        final int[] w2 = new int[16];

        for (int i = 0; i < 16; i++) {
            final int numBits = isBc7FixUp(numSubsets - 1, partition, i) ? indexPrec - 1 : indexPrec;
            w1[i] = getBits(data, pos, bitPos, numBits);
            bitPos += numBits;
        }

        if (indexPrec2 > 0) {
            for (int i = 0; i < 16; i++) {
                final int numBits = (i == 0) ? indexPrec2 - 1 : indexPrec2;
                w2[i] = getBits(data, pos, bitPos, numBits);
                bitPos += numBits;
            }
        }

        final byte[] partitionMap = PARTITIONS[numSubsets - 1][partition];
        final int[] weightsTable1 = getWeightsTable(indexPrec);
        final int[] weightsTable2 = indexPrec2 > 0 ? getWeightsTable(indexPrec2) : null;

        for (int i = 0; i < 16; i++) {
            final int subset = partitionMap[i];
            final int ep0 = subset * 2;
            final int ep1 = ep0 + 1;

            final int weightC = weightsTable1[w1[i]];
            final int weightA = (weightsTable2 != null)
                    ? (indexMode == 0 ? weightsTable2[w2[i]] : weightsTable1[w1[i]])
                    : weightC;
            final int weightColor = (weightsTable2 != null && indexMode != 0) ? weightsTable2[w2[i]] : weightC;

            int r = interpolateBc7(ep[ep0][0], ep[ep1][0], weightColor);
            int g = interpolateBc7(ep[ep0][1], ep[ep1][1], weightColor);
            int b = interpolateBc7(ep[ep0][2], ep[ep1][2], weightColor);
            int a = interpolateBc7(ep[ep0][3], ep[ep1][3], weightA);

            if (rotation != 0) {
                switch (rotation) {
                    case 1: // Swap A and R
                        final int tr = r; r = a; a = tr; break;
                    case 2: // Swap A and G
                        final int tg = g; g = a; a = tg; break;
                    case 3: // Swap A and B
                        final int tb = b; b = a; a = tb; break;
                }
            }

            outPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int[] getWeightsTable(int prec) {
        if (prec == 2) return WEIGHTS2;
        if (prec == 3) return WEIGHTS3;
        return WEIGHTS4;
    }

    private static int unquantizeBc7(int comp, int prec) {
        comp = (comp << (8 - prec)) & 0xFF;
        return comp | (comp >>> prec);
    }

    private static int interpolateBc7(int e0, int e1, int weight) {
        return ((64 - weight) * e0 + weight * e1 + 32) >>> 6;
    }

    private static boolean isBc7FixUp(int partitionIndex, int shape, int offset) {
        final byte[] fixups = FIXUPS[partitionIndex][shape];
        for (byte f : fixups) {
            if ((f & 0xFF) == offset) return true;
        }
        return false;
    }

    // 非圧縮 (RGBA/BGRA/RGB/BGR/Luminance 等)
    private static void decodeUncompressed(byte[] data, int offset, int width, int height,
                                           int rgbBitCount, int rMask, int gMask, int bMask, int aMask,
                                           boolean hasAlpha, int[] out) throws IOException {
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
                final int g = gMax != 0 ? ((pixel & gMask) >>> gShift) * 255 / gMax : 0;
                final int b = bMax != 0 ? ((pixel & bMask) >>> bShift) * 255 / bMax : 0;
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

    private static int getBit(byte[] data, int pos, int bitIndex) {
        final int byteIdx = pos + (bitIndex >>> 3);
        final int bitInByte = bitIndex & 7;
        return (data[byteIdx] >>> bitInByte) & 1;
    }

    private static int getBits(byte[] data, int pos, int bitIndex, int count) {
        int res = 0;
        for (int i = 0; i < count; i++) {
            res |= (getBit(data, pos, bitIndex + i) << i);
        }
        return res;
    }

    // BC7 パーティションテーブル (DirectXTex 準拠)
    private static final byte[][][] PARTITIONS = {
            // 1 Region (Mode 4, 5, 6)
            {
                    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
            },
            // 2 Regions (Mode 1, 3, 7) - 64 partitions
            {
                    {0,0,1,1,0,0,1,1,0,0,1,1,0,0,1,1}, {0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1}, {0,1,1,1,0,1,1,1,0,1,1,1,0,1,1,1}, {0,0,0,1,0,0,1,1,0,0,1,1,0,1,1,1},
                    {0,0,0,0,0,0,0,1,0,0,0,1,0,0,1,1}, {0,0,1,1,0,1,1,1,0,1,1,1,1,1,1,1}, {0,0,0,1,0,0,1,1,0,1,1,1,1,1,1,1}, {0,0,0,0,0,0,0,1,0,0,1,1,0,1,1,1},
                    {0,0,0,0,0,0,0,0,0,0,0,1,0,0,1,1}, {0,0,1,1,0,1,1,1,1,1,1,1,1,1,1,1}, {0,0,0,0,0,0,0,1,0,1,1,1,1,1,1,1}, {0,0,0,0,0,0,0,0,0,0,0,1,0,1,1,1},
                    {0,0,0,1,0,1,1,1,1,1,1,1,1,1,1,1}, {0,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1}, {0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1}, {0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,1},
                    {0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0}, {0,0,0,0,1,0,0,0,1,1,0,0,1,1,1,0}, {0,1,1,1,0,0,1,1,0,0,0,1,0,0,0,0}, {0,0,1,1,0,0,0,1,0,0,0,0,0,0,0,0},
                    {0,0,0,0,1,0,0,0,1,1,0,0,1,1,1,0}, {0,0,0,0,0,0,0,0,1,0,0,0,1,1,0,0}, {0,0,1,1,0,1,1,0,0,1,1,0,1,1,0,0}, {0,0,0,1,0,1,1,0,0,1,1,0,1,0,0,0},
                    {0,0,0,1,0,0,1,1,0,1,1,0,1,0,0,0}, {0,1,1,0,0,1,1,0,0,1,1,0,0,1,1,0}, {0,0,1,1,1,1,0,0,1,1,0,0,0,0,1,1}, {0,1,0,1,0,1,0,1,0,1,0,1,0,1,0,1},
                    {0,0,0,0,1,1,1,1,0,0,0,0,1,1,1,1}, {0,1,0,1,1,0,1,0,0,1,0,1,1,0,1,0}, {0,0,1,1,0,0,1,1,1,1,0,0,1,1,0,0}, {0,0,1,1,1,1,0,0,0,0,1,1,1,1,0,0},
                    {0,1,1,0,0,1,1,0,1,0,0,1,1,0,0,1}, {0,1,0,1,0,1,0,1,1,0,1,0,1,0,1,0}, {0,1,1,0,1,0,0,1,0,1,1,0,1,0,0,1}, {0,1,0,1,1,0,1,0,1,0,1,0,0,1,0,1},
                    {0,1,1,1,0,0,1,1,1,1,0,0,1,1,1,0}, {0,0,0,1,0,0,1,1,1,1,0,0,1,0,0,0}, {0,0,1,1,0,0,1,0,0,1,0,0,1,1,0,0}, {0,0,1,1,1,0,1,1,1,1,0,1,1,1,0,0},
                    {0,1,1,0,1,0,0,1,1,0,0,1,0,1,1,0}, {0,0,1,1,1,1,0,0,1,1,0,0,1,1,0,0}, {0,1,1,0,0,1,1,0,1,1,0,0,1,1,0,0}, {0,0,1,1,0,1,1,0,0,1,1,0,1,1,0,0},
                    {0,0,0,1,0,1,1,1,1,1,1,0,1,0,0,0}, {0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0}, {0,1,1,1,0,0,0,1,1,0,0,0,1,1,1,0}, {0,0,1,1,1,0,0,1,1,0,0,1,1,1,0,0},
                    {0,1,0,1,1,0,0,0,0,0,0,1,1,0,1,0}, {0,0,1,1,0,0,0,1,1,0,0,0,1,1,0,0}, {0,1,0,1,0,0,0,0,0,0,0,0,1,0,1,0}, {0,1,1,0,0,1,1,0,0,0,0,0,0,0,0,0},
                    {0,0,0,0,0,0,0,0,0,1,1,0,0,1,1,0}, {0,1,1,0,0,1,1,0,1,1,0,0,1,1,0,0}, {0,0,1,1,0,1,1,0,0,1,1,0,1,1,0,0}, {0,0,0,1,0,1,1,1,1,1,1,0,1,0,0,0},
                    {0,0,0,0,1,1,1,1,1,1,1,1,0,0,0,0}, {0,1,1,1,0,0,0,1,1,0,0,0,1,1,1,0}, {0,0,1,1,1,0,0,1,1,0,0,1,1,1,0,0}, {0,1,0,1,1,0,0,0,0,0,0,1,1,0,1,0},
                    {0,0,1,1,0,0,0,1,1,0,0,0,1,1,0,0}, {0,1,0,1,0,0,0,0,0,0,0,0,1,0,1,0}, {0,1,1,0,0,1,1,0,0,0,0,0,0,0,0,0}, {0,0,0,0,0,0,0,0,0,1,1,0,0,1,1,0}
            },
            // 3 Regions (Mode 0, 2) - 64 partitions
            {
                    {0,0,1,1,0,0,1,1,0,2,2,1,2,2,2,2}, {0,0,0,1,0,0,1,1,2,2,1,1,2,2,2,1}, {0,0,0,0,2,0,0,1,2,2,1,1,2,2,1,1}, {0,2,2,2,0,0,2,2,0,0,1,1,0,1,1,1},
                    {0,0,0,0,0,0,0,0,1,1,2,2,1,1,2,2}, {0,0,1,1,0,0,1,1,0,0,2,2,0,0,2,2}, {0,0,2,2,0,0,2,2,1,1,1,1,1,1,1,1}, {0,0,1,1,0,0,1,1,2,2,1,1,2,2,1,1},
                    {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2}, {0,0,0,0,1,1,1,1,1,1,1,1,2,2,2,2}, {0,0,0,0,1,1,1,1,2,2,2,2,2,2,2,2}, {0,0,1,2,0,0,1,2,0,0,1,2,0,0,1,2},
                    {0,1,1,2,0,1,1,2,0,1,1,2,0,1,1,2}, {0,1,2,2,0,1,2,2,0,1,2,2,0,1,2,2}, {0,0,1,1,0,1,1,2,1,1,2,2,1,2,2,2}, {0,0,1,1,2,0,0,1,2,2,0,0,2,2,2,0},
                    {0,0,0,1,0,0,1,1,0,1,1,2,1,1,2,2}, {0,1,1,1,0,0,1,1,2,0,0,1,2,2,0,0}, {0,0,0,0,1,1,2,2,1,1,2,2,1,1,2,2}, {0,0,2,2,0,0,2,2,0,0,2,2,1,1,1,1},
                    {0,1,1,1,0,1,1,1,0,2,2,2,0,2,2,2}, {0,0,0,1,0,0,0,1,2,2,2,1,2,2,2,1}, {0,0,0,0,1,1,1,1,2,2,0,0,2,2,1,1}, {0,1,2,2,0,1,2,2,0,0,1,1,0,0,0,0},
                    {0,0,1,2,0,0,1,2,1,1,2,2,2,2,2,2}, {0,1,1,0,1,2,2,1,2,2,2,2,2,2,2,2}, {0,0,0,0,2,1,1,2,2,2,2,2,2,1,1,2}, {0,1,1,0,0,1,1,0,2,2,2,2,2,2,2,2},
                    {0,0,1,1,0,1,2,2,0,1,2,2,0,0,1,1}, {0,0,0,0,2,0,0,0,2,2,1,1,2,2,2,1}, {0,0,0,0,0,0,0,2,1,1,2,2,1,2,2,2}, {0,2,2,2,0,0,2,2,0,0,1,2,0,0,1,1},
                    {0,0,1,1,0,0,1,2,0,0,2,2,0,2,2,2}, {0,1,2,0,0,1,2,0,0,1,2,0,0,1,2,0}, {0,0,0,0,1,1,1,1,2,2,2,2,0,0,0,0}, {0,1,2,0,1,2,0,1,2,0,1,2,0,1,2,0},
                    {0,1,2,0,2,0,1,2,1,2,0,1,0,1,2,0}, {0,0,1,1,2,2,0,0,1,1,2,2,0,0,1,1}, {0,0,1,1,1,1,2,2,2,2,0,0,0,0,1,1}, {0,1,0,1,0,1,0,1,2,2,2,2,2,2,2,2},
                    {0,0,0,0,0,0,0,0,2,1,2,1,2,1,2,1}, {0,0,2,2,1,1,2,2,0,0,2,2,1,1,2,2}, {0,0,2,2,0,0,1,1,0,0,2,2,0,0,1,1}, {0,2,2,0,1,2,2,1,0,2,2,0,1,2,2,1},
                    {0,1,0,1,2,2,2,2,2,2,2,2,0,1,0,1}, {0,0,0,0,2,1,2,1,2,1,2,1,2,1,2,1}, {0,1,0,1,0,1,0,1,0,1,0,1,2,2,2,2}, {0,1,2,0,0,1,2,0,1,2,0,1,1,2,0,1},
                    {0,1,0,1,2,2,2,2,0,1,0,1,2,2,2,2}, {0,1,2,0,1,2,0,1,0,1,2,0,1,2,0,1}, {0,1,2,0,2,0,1,2,2,0,1,2,0,1,2,0}, {0,1,2,0,0,1,2,0,2,0,1,2,2,0,1,2},
                    {0,1,2,0,1,2,0,1,1,2,0,1,2,0,1,2}, {0,1,2,0,2,0,1,2,0,1,2,0,2,0,1,2}, {0,0,1,1,2,2,0,0,2,2,1,1,0,0,2,2}, {0,0,0,0,1,2,1,2,1,2,1,2,0,0,0,0},
                    {0,1,0,1,0,1,0,1,2,2,2,2,0,1,0,1}, {0,1,2,0,0,0,0,0,2,2,2,2,0,1,2,0}, {0,0,0,0,2,1,1,2,2,1,1,2,0,0,0,0}, {0,1,2,0,1,2,0,1,2,0,1,2,1,2,0,1},
                    {0,0,0,0,1,1,1,1,2,2,2,2,1,1,1,1}, {0,1,2,0,0,1,2,0,0,1,2,0,1,2,0,1}, {0,0,0,0,2,2,2,2,1,1,1,1,2,2,2,2}, {0,0,1,1,0,0,1,1,2,2,2,2,2,2,2,2}
            }
    };

    private static final byte[][][] FIXUPS = {
            {{0}},
            {
                    {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15},
                    {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15},
                    {0, 15}, {0, 2}, {0, 8}, {0, 2}, {0, 2}, {0, 8}, {0, 8}, {0, 15},
                    {0, 2}, {0, 8}, {0, 2}, {0, 2}, {0, 8}, {0, 8}, {0, 2}, {0, 2},
                    {0, 15}, {0, 15}, {0, 6}, {0, 8}, {0, 2}, {0, 8}, {0, 15}, {0, 15},
                    {0, 2}, {0, 8}, {0, 2}, {0, 2}, {0, 2}, {0, 15}, {0, 15}, {0, 6},
                    {0, 6}, {0, 2}, {0, 6}, {0, 8}, {0, 15}, {0, 15}, {0, 2}, {0, 2},
                    {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 15}, {0, 2}, {0, 2}, {0, 15}
            },
            {
                    {0, 3, 15}, {0, 3, 8}, {0, 15, 8}, {0, 15, 3}, {0, 8, 15}, {0, 3, 8}, {0, 15, 3}, {0, 15, 6},
                    {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 15, 8}, {0, 15, 8}, {0, 8, 15}, {0, 8, 15},
                    {0, 3, 15}, {0, 15, 3}, {0, 3, 8}, {0, 15, 2}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 15, 8},
                    {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 11, 15}, {0, 15, 3},
                    {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15},
                    {0, 3, 15}, {0, 3, 8}, {0, 3, 8}, {0, 3, 8}, {0, 15, 3}, {0, 15, 3}, {0, 11, 15}, {0, 11, 15},
                    {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}, {0, 2, 15}, {0, 15, 3}, {0, 3, 15}, {0, 3, 8},
                    {0, 3, 15}, {0, 8, 15}, {0, 3, 15}, {0, 15, 3}, {0, 15, 3}, {0, 8, 15}, {0, 8, 15}, {0, 8, 15}
            }
    };
}
