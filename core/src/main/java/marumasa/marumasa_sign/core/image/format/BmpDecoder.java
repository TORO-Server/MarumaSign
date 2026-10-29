package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.io.IOException;

/**
 * BMP / DIB。ImageIO が読めない種類 (透明度つき、OS/2 形式、RLE や特殊なビット配置など) と、アイコンの中身に使う。
 */
final class BmpDecoder {

    private BmpDecoder() {
    }

    // ImageIO に渡す前に、見出しの大きさが上限の中か確かめる (ImageIO は大きさを確かめずに領域を確保して、メモリ不足になることがある)
    static void checkHeader(byte[] d) throws IOException {
        if (d.length < 26) throw new IOException("Truncated BMP");
        final int headerSize = FormatUtil.s32le(d, 14);
        if (headerSize == 12) {
            FormatUtil.checkSize(FormatUtil.u16le(d, 18), FormatUtil.u16le(d, 20));
        } else if (headerSize >= 40 && d.length >= 34) {
            final long height = Math.abs((long) FormatUtil.s32le(d, 22));
            FormatUtil.checkSize(FormatUtil.s32le(d, 18), height);
            final long colors = FormatUtil.u32le(d, 46 > d.length - 4 ? 0 : 46);
            if (colors > 65536) throw new IOException("Invalid BMP color count");
            // 中に JPEG や PNG が入った BMP は読まない。ImageIO は、中の JPEG を libjpeg で、PNG を大きさを確かめずに読むため
            final int compression = FormatUtil.s32le(d, 30);
            if (compression == 4 || compression == 5) throw new IOException("Embedded image in BMP");
        } else {
            throw new IOException("Unsupported BMP header: " + headerSize);
        }
    }

    // BMP ファイル (先頭が "BM")
    static DecodedImage decodeFile(byte[] d) throws IOException {
        if (d.length < 26 || d[0] != 'B' || d[1] != 'M') throw new IOException("Not a BMP image");
        return decodeDib(d, 14, (int) Math.min(FormatUtil.u32le(d, 10), Integer.MAX_VALUE), false);
    }

    /**
     * @param start      情報ヘッダーの位置
     * @param pixelStart ピクセルの位置 (ファイルの先頭から)。負のときはヘッダーと色の一覧のすぐ後ろ
     * @param icon       アイコンの中身か (高さが 2 倍で、下に AND マスクがつく。32 ビットの透明度を使う)
     */
    static DecodedImage decodeDib(byte[] d, int start, int pixelStart, boolean icon) throws IOException {
        final int headerSize = FormatUtil.s32le(d, start);
        int width, height, planes, bits, compression = 0, colors = 0;
        int redMask = 0, greenMask = 0, blueMask = 0, alphaMask = 0;
        boolean hasMasks = false;
        int entrySize = 4;
        if (headerSize == 12) {
            width = FormatUtil.u16le(d, start + 4);
            height = (short) FormatUtil.u16le(d, start + 6);
            planes = FormatUtil.u16le(d, start + 8);
            bits = FormatUtil.u16le(d, start + 10);
            entrySize = 3;
        } else if (headerSize >= 40) {
            width = FormatUtil.s32le(d, start + 4);
            height = FormatUtil.s32le(d, start + 8);
            planes = FormatUtil.u16le(d, start + 12);
            bits = FormatUtil.u16le(d, start + 14);
            compression = FormatUtil.s32le(d, start + 16);
            colors = FormatUtil.s32le(d, start + 32);
            if (headerSize >= 52) {
                redMask = FormatUtil.s32le(d, start + 40);
                greenMask = FormatUtil.s32le(d, start + 44);
                blueMask = FormatUtil.s32le(d, start + 48);
                hasMasks = true;
                if (headerSize >= 56) alphaMask = FormatUtil.s32le(d, start + 52);
            } else if (compression == 3 || compression == 6) {
                // 40 バイトのヘッダーでは、ビットの配置がヘッダーのすぐ後ろに続く
                redMask = FormatUtil.s32le(d, start + 40);
                greenMask = FormatUtil.s32le(d, start + 44);
                blueMask = FormatUtil.s32le(d, start + 48);
                hasMasks = true;
                if (compression == 6) alphaMask = FormatUtil.s32le(d, start + 52);
            }
        } else {
            throw new IOException("Unsupported BMP header: " + headerSize);
        }
        // ビットの配置は BITFIELDS のときだけ使う (RGB のときはヘッダーに入っていても無視する)
        if (compression != 3 && compression != 6) {
            hasMasks = false;
            alphaMask = 0;
        }
        if (planes != 1) throw new IOException("Unsupported BMP planes");
        final boolean topDown = height < 0;
        int rows = Math.abs(height);
        if (icon) rows /= 2;
        FormatUtil.checkSize(width, rows);
        if (compression == 4 || compression == 5) throw new IOException("Embedded image in BMP");

        // 色の一覧
        int position = start + headerSize;
        if (headerSize == 40 && (compression == 3 || compression == 6)) position += compression == 6 ? 16 : 12;
        int[] palette = null;
        if (bits <= 8) {
            final int count = colors > 0 && colors <= (1 << bits) ? colors : 1 << bits;
            palette = new int[1 << bits];
            for (int i = 0; i < count; i++) {
                final int p = position + i * entrySize;
                palette[i] = FormatUtil.argb(255, d[p + 2], d[p + 1], d[p]);
            }
            position += count * entrySize;
        } else if (colors > 0 && headerSize <= 40) {
            position += colors * entrySize;
        }
        if (pixelStart >= 0) position = pixelStart;

        final int[] result = new int[width * rows];
        boolean anyAlpha = false;
        if (compression == 1 || compression == 2) {
            rle(d, position, palette, compression == 2, width, rows, topDown, result);
        } else {
            if (bits == 16 && !hasMasks) {
                redMask = 0x7C00;
                greenMask = 0x03E0;
                blueMask = 0x001F;
            } else if (bits == 32 && !hasMasks) {
                redMask = 0x00FF0000;
                greenMask = 0x0000FF00;
                blueMask = 0x000000FF;
            }
            final long rowBytesLong = ((long) width * bits + 31) / 32 * 4;
            if (rowBytesLong * rows > d.length - position) throw new IOException("Truncated BMP");
            final int rowBytes = (int) rowBytesLong;
            for (int y = 0; y < rows; y++) {
                final int source = position + (topDown ? y : rows - 1 - y) * rowBytes;
                final int target = y * width;
                for (int x = 0; x < width; x++) {
                    final int color;
                    switch (bits) {
                        case 1:
                        case 2:
                        case 4:
                        case 8: {
                            final int bitIndex = x * bits;
                            final int v = ((d[source + (bitIndex >> 3)] & 0xFF) >> (8 - bits - (bitIndex & 7))) & ((1 << bits) - 1);
                            color = palette[v];
                            break;
                        }
                        case 24:
                            color = FormatUtil.argb(255, d[source + x * 3 + 2], d[source + x * 3 + 1], d[source + x * 3]);
                            break;
                        case 16:
                        case 32: {
                            final int v = bits == 16 ? FormatUtil.u16le(d, source + x * 2) : FormatUtil.s32le(d, source + x * 4);
                            final int a = alphaMask != 0 ? channel(v, alphaMask) : 255;
                            color = FormatUtil.argb(a, channel(v, redMask), channel(v, greenMask), channel(v, blueMask));
                            if (a != 255) anyAlpha = true;
                            break;
                        }
                        default:
                            throw new IOException("Unsupported BMP depth: " + bits);
                    }
                    result[target + x] = color;
                }
            }
            position += rowBytes * rows;
        }

        // アイコンの 32 ビットは、4 つ目のバイトが透明度。BMP の 32 ビットで透明度のマスクがなければ、使っていない値
        if (bits == 32 && alphaMask == 0) {
            boolean any = false;
            if (icon) {
                for (int y = 0; y < rows && !any; y++) {
                    final int source = position - (rows - (topDown ? y : rows - 1 - y)) * (width * 4);
                    for (int x = 0; x < width && !any; x++) any = d[source + x * 4 + 3] != 0;
                }
                if (any) {
                    for (int y = 0; y < rows; y++) {
                        final int source = position - (rows - (topDown ? y : rows - 1 - y)) * (width * 4);
                        for (int x = 0; x < width; x++) {
                            result[y * width + x] = (result[y * width + x] & 0xFFFFFF) | (d[source + x * 4 + 3] & 0xFF) << 24;
                        }
                    }
                    anyAlpha = true;
                }
            }
        }
        if (icon && !anyAlpha) {
            // AND マスク (1 ビット、1 が透明) で透明度を決める
            final int maskRow = (width + 31) / 32 * 4;
            if ((long) maskRow * rows <= d.length - position) {
                for (int y = 0; y < rows; y++) {
                    final int source = position + (topDown ? y : rows - 1 - y) * maskRow;
                    for (int x = 0; x < width; x++) {
                        if (((d[source + (x >> 3)] >> (7 - (x & 7))) & 1) != 0) result[y * width + x] &= 0x00FFFFFF;
                    }
                }
            }
        }
        return FormatUtil.still(width, rows, result);
    }

    // マスクで取り出したビットを 0 から 255 にする
    private static int channel(int value, int mask) {
        if (mask == 0) return 0;
        final int shift = Integer.numberOfTrailingZeros(mask);
        final long max = (mask >>> shift) & 0xFFFFFFFFL;
        return FormatUtil.to8(((value & mask) >>> shift) & 0xFFFFFFFFL, max);
    }

    private static void rle(byte[] d, int position, int[] palette, boolean four, int width, int rows, boolean topDown, int[] result)
            throws IOException {
        if (palette == null) throw new IOException("Unsupported BMP RLE");
        // 指定されなかった所は、番号 0 の色にする
        final int background = palette[0];
        java.util.Arrays.fill(result, background);
        int x = 0, y = 0;
        int p = position;
        while (p + 1 < d.length && y < rows) {
            final int count = d[p++] & 0xFF;
            final int value = d[p++] & 0xFF;
            if (count > 0) {
                for (int i = 0; i < count; i++) {
                    final int index = four ? (i % 2 == 0 ? value >> 4 : value & 15) : value;
                    put(result, palette, x++, y, index, width, rows, topDown);
                }
            } else if (value == 0) {
                x = 0;
                y++;
            } else if (value == 1) {
                break;
            } else if (value == 2) {
                x += d[p++] & 0xFF;
                y += d[p++] & 0xFF;
            } else {
                for (int i = 0; i < value; i++) {
                    final int b = d[p + (four ? i / 2 : i)] & 0xFF;
                    final int index = four ? (i % 2 == 0 ? b >> 4 : b & 15) : b;
                    put(result, palette, x++, y, index, width, rows, topDown);
                }
                final int used = four ? (value + 1) / 2 : value;
                p += used + (used & 1);
            }
        }
    }

    private static void put(int[] result, int[] palette, int x, int y, int index, int width, int rows, boolean topDown) {
        if (x < 0 || x >= width || y < 0 || y >= rows) return;
        final int row = topDown ? y : rows - 1 - y;
        result[row * width + x] = palette[index & (palette.length - 1)];
    }
}
