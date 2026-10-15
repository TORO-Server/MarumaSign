package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.texture.webp.lossless.VP8LDecoder;
import marumasa.marumasa_sign.core.texture.webp.vp8.VP8Frame;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;

/**
 * WebP (EXT_texture_webp) の画像を読む。
 * 非可逆 (VP8)、可逆 (VP8L)、拡張形式 (VP8X) の透明度 (ALPH) とアニメーション (最初のフレーム) に対応する。
 * 中身の展開は、TwelveMonkeys ImageIO (BSD 3-Clause License) のデコーダを使う (webp パッケージ)。
 */
public final class WebpDecoder {

    private static final int RIFF = fourCC("RIFF");
    private static final int WEBP = fourCC("WEBP");
    private static final int VP8 = fourCC("VP8 ");
    private static final int VP8L = fourCC("VP8L");
    private static final int VP8X = fourCC("VP8X");
    private static final int ALPH = fourCC("ALPH");
    private static final int ANMF = fourCC("ANMF");

    // 画像の一辺の上限
    private static final int MAX_SIZE = 8192;

    private WebpDecoder() {
    }

    private static int fourCC(String name) {
        return name.charAt(0) | name.charAt(1) << 8 | name.charAt(2) << 16 | name.charAt(3) << 24;
    }

    public static boolean isWebp(byte[] data) {
        return data != null && data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isWebp(data)) throw new IOException("Not a WebP image");
        final ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        try {
            input.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            return read(input, data.length);
        } catch (RuntimeException e) {
            // 壊れたデータによる範囲外の参照など
            throw new IOException("Invalid WebP image: " + e, e);
        } finally {
            input.close();
        }
    }

    private static BufferedImage read(ImageInputStream input, long length) throws IOException {
        if (input.readInt() != RIFF) throw new IOException("Not a WebP image");
        final long end = Math.min(length, 8 + input.readUnsignedInt());
        if (input.readInt() != WEBP) throw new IOException("Not a WebP image");

        final int chunk = input.readInt();
        final long chunkLength = input.readUnsignedInt();
        final long chunkStart = input.getStreamPosition();
        if (chunk == VP8) {
            final int[] size = vp8Size(input);
            final BufferedImage image = create(size[0], size[1]);
            input.seek(chunkStart);
            decodeVp8(input, image, false);
            return image;
        }
        if (chunk == VP8L) {
            final int[] size = vp8lSize(input);
            final BufferedImage image = create(size[0], size[1]);
            input.seek(chunkStart);
            new VP8LDecoder(input, false).readVP8Lossless(image.getRaster(), true, null, size[0], size[1]);
            return image;
        }
        if (chunk != VP8X) throw new IOException("Unknown WebP chunk: " + chunk);

        // 拡張形式: フラグ (1 バイト) と予約 (3 バイト) のあとに、全体の幅と高さ (各 3 バイト)
        final int flags = input.readUnsignedByte();
        input.skipBytes(3);
        final int width = 1 + readUInt24(input);
        final int height = 1 + readUInt24(input);
        final boolean animated = (flags & 0x02) != 0;
        long position = chunkStart + chunkLength + (chunkLength & 1);

        while (position + 8 <= end) {
            input.seek(position);
            final int next = input.readInt();
            final long nextLength = input.readUnsignedInt();
            final long nextStart = input.getStreamPosition();
            if (animated && next == ANMF) {
                // 最初のフレームだけを使う。位置 (6 バイト) と幅・高さ (各 3 バイト)、表示時間と描き方 (4 バイト) のあとに中身
                input.skipBytes(6);
                final int frameWidth = 1 + readUInt24(input);
                final int frameHeight = 1 + readUInt24(input);
                return readFrame(input, nextStart + 16, Math.min(end, nextStart + nextLength), frameWidth, frameHeight);
            }
            if (!animated && (next == ALPH || next == VP8 || next == VP8L)) {
                return readFrame(input, position, end, width, height);
            }
            position = nextStart + nextLength + (nextLength & 1);
        }
        throw new IOException("WebP image has no frame");
    }

    // 透明度 (ALPH) と画像 (VP8 / VP8L) の並びを読む
    private static BufferedImage readFrame(ImageInputStream input, long start, long end, int width, int height) throws IOException {
        final BufferedImage image = create(width, height);
        boolean hasAlpha = false;
        long position = start;
        while (position + 8 <= end) {
            input.seek(position);
            final int chunk = input.readInt();
            final long length = input.readUnsignedInt();
            final long chunkStart = input.getStreamPosition();
            if (chunk == ALPH) {
                readAlpha(input, image, chunkStart, length, width, height);
                hasAlpha = true;
            } else if (chunk == VP8) {
                input.seek(chunkStart);
                decodeVp8(input, image, hasAlpha);
                return image;
            } else if (chunk == VP8L) {
                input.seek(chunkStart);
                new VP8LDecoder(input, false).readVP8Lossless(image.getRaster(), true, null, width, height);
                return image;
            }
            position = chunkStart + length + (length & 1);
        }
        throw new IOException("WebP frame has no image data");
    }

    private static void decodeVp8(ImageInputStream input, BufferedImage image, boolean keepAlpha) throws IOException {
        final WritableRaster rgb = image.getRaster().createWritableChild(0, 0, image.getWidth(), image.getHeight(), 0, 0, new int[]{0, 1, 2});
        if (!new VP8Frame(input, false).decode(rgb, null)) throw new IOException("Invalid VP8 frame");
        if (!keepAlpha) fillAlpha(image.getAlphaRaster());
    }

    private static void readAlpha(ImageInputStream input, BufferedImage image, long start, long length,
                                  int width, int height) throws IOException {
        input.seek(start);
        final int header = input.readUnsignedByte();
        final int compression = header & 3;
        final int filtering = (header >> 2) & 3;
        final WritableRaster alpha = image.getAlphaRaster();
        if (compression == 0) {
            // 圧縮なし: 幅 x 高さ バイトがそのまま並ぶ
            if (length - 1 < (long) width * height) throw new IOException("Truncated WebP alpha");
            final byte[] row = new byte[width];
            for (int y = 0; y < height; y++) {
                input.readFully(row);
                for (int x = 0; x < width; x++) {
                    alpha.setSample(x, y, 0, row[x] & 0xFF);
                }
            }
        } else if (compression == 1) {
            // 可逆圧縮: VP8L の画像の緑の値が透明度。デコーダは先頭の 5 バイト (VP8L のヘッダー) を読み飛ばすので、その分戻す
            input.seek(start + 1 - 5);
            final WritableRaster temporary = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
            new VP8LDecoder(input, false).readVP8Lossless(temporary, true, null, width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    alpha.setSample(x, y, 0, temporary.getSample(x, y, 1));
                }
            }
        } else {
            throw new IOException("Unknown WebP alpha compression: " + compression);
        }
        unfilterAlpha(alpha, filtering, width, height);
    }

    // 透明度の予測を元に戻す (0: なし、1: 左、2: 上、3: 勾配)
    private static void unfilterAlpha(WritableRaster alpha, int filtering, int width, int height) {
        if (filtering == 0) return;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int predictor;
                if (x == 0 && y == 0) {
                    predictor = 0;
                } else if (y == 0) {
                    predictor = alpha.getSample(x - 1, 0, 0);
                } else if (x == 0) {
                    predictor = alpha.getSample(0, y - 1, 0);
                } else if (filtering == 1) {
                    predictor = alpha.getSample(x - 1, y, 0);
                } else if (filtering == 2) {
                    predictor = alpha.getSample(x, y - 1, 0);
                } else {
                    final int left = alpha.getSample(x - 1, y, 0);
                    final int top = alpha.getSample(x, y - 1, 0);
                    final int topLeft = alpha.getSample(x - 1, y - 1, 0);
                    predictor = Math.max(0, Math.min(255, left + top - topLeft));
                }
                alpha.setSample(x, y, 0, (alpha.getSample(x, y, 0) + predictor) & 0xFF);
            }
        }
    }

    private static void fillAlpha(WritableRaster alpha) {
        for (int y = 0; y < alpha.getHeight(); y++) {
            for (int x = 0; x < alpha.getWidth(); x++) {
                alpha.setSample(x, y, 0, 255);
            }
        }
    }

    // VP8 の先頭 (フレームの種類 3 バイト、開始コード 3 バイト、幅と高さ 各 2 バイト) から大きさを読む
    private static int[] vp8Size(ImageInputStream input) throws IOException {
        input.skipBytes(3);
        if (input.readUnsignedByte() != 0x9d || input.readUnsignedByte() != 0x01 || input.readUnsignedByte() != 0x2a) {
            throw new IOException("Invalid VP8 start code");
        }
        return new int[]{input.readUnsignedShort() & 0x3fff, input.readUnsignedShort() & 0x3fff};
    }

    // VP8L の先頭 (署名 1 バイトと、幅 14 ビット・高さ 14 ビット) から大きさを読む
    private static int[] vp8lSize(ImageInputStream input) throws IOException {
        if (input.readUnsignedByte() != 0x2f) throw new IOException("Invalid VP8L signature");
        final long bits = input.readUnsignedInt();
        return new int[]{1 + (int) (bits & 0x3fff), 1 + (int) ((bits >> 14) & 0x3fff)};
    }

    private static int readUInt24(ImageInputStream input) throws IOException {
        return input.readUnsignedByte() | input.readUnsignedByte() << 8 | input.readUnsignedByte() << 16;
    }

    private static BufferedImage create(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_SIZE || height > MAX_SIZE) {
            throw new IOException("WebP image exceeds safety limits: " + width + "x" + height);
        }
        return new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
    }
}
