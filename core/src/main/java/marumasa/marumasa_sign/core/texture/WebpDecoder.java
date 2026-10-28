package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.texture.webp.AlphaDecoder;
import marumasa.marumasa_sign.core.texture.webp.LosslessDecoder;
import marumasa.marumasa_sign.core.texture.webp.Vp8Decoder;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WebP (EXT_texture_webp) の画像を読む (RFC 9649)。
 * 非可逆 (VP8)、可逆 (VP8L)、拡張形式 (VP8X) の透明度 (ALPH) とアニメーション (ANIM / ANMF) に対応する。
 * 中身の展開は webp パッケージで行う。
 */
public final class WebpDecoder {

    private static final int RIFF = fourCC("RIFF");
    private static final int WEBP = fourCC("WEBP");
    private static final int VP8 = fourCC("VP8 ");
    private static final int VP8L = fourCC("VP8L");
    private static final int VP8X = fourCC("VP8X");
    private static final int ALPH = fourCC("ALPH");
    private static final int ANMF = fourCC("ANMF");
    private static final int ANIM = fourCC("ANIM");

    // 画像の一辺の上限
    private static final int MAX_SIZE = 8192;
    // アニメーションのフレーム数と、全フレームのピクセル数の上限
    private static final int MAX_ANIMATION_FRAMES = 512;
    private static final int MAX_ANIMATION_SIZE = 2048;
    private static final long MAX_ANIMATION_PIXELS = 64L * 1000 * 1000;

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

    /**
     * 1 枚の画像として読む。アニメーションなら、最初のフレームを描いたキャンバスを返す。
     */
    public static BufferedImage decode(byte[] data) throws IOException {
        if (!isWebp(data)) throw new IOException("Not a WebP image");
        try {
            final Container container = new Container(data);
            final int[] argb;
            final int width;
            final int height;
            final int first = container.chunkAt(12);
            if (first == VP8 || first == VP8L) {
                final Chunk chunk = container.read(12);
                final int[] size = bitstreamSize(data, chunk);
                width = size[0];
                height = size[1];
                checkSize(width, height, MAX_SIZE);
                argb = decodeBitstream(data, chunk, null, width, height);
            } else if (first == VP8X) {
                final Chunk vp8x = container.read(12);
                if (vp8x.length < 10) throw new IOException("Truncated VP8X chunk");
                final int flags = data[vp8x.start] & 0xFF;
                width = 1 + u24(data, vp8x.start + 4);
                height = 1 + u24(data, vp8x.start + 7);
                checkSize(width, height, MAX_SIZE);
                if ((flags & 0x02) != 0) {
                    argb = readAnimation(data, container, vp8x.next, width, height, true).frames[0];
                } else {
                    argb = readFrame(data, container, vp8x.next, container.end, width, height);
                }
            } else {
                throw new IOException("Unknown WebP chunk: " + first);
            }
            return toImage(width, height, argb);
        } catch (RuntimeException e) {
            // 想定していない壊れ方 (範囲外の参照など) も、読めない画像として扱う
            throw new IOException("Invalid WebP image: " + e, e);
        }
    }

    /**
     * アニメーションの WebP を、全フレームを重ねた絵のコマにして返す。
     *
     * @return アニメーションでなければ null
     */
    public static DecodedImage decodeAnimated(byte[] data) throws IOException {
        if (!isWebp(data) || data.length < 30 || u32(data, 12) != VP8X || (data[20] & 0x02) == 0) {
            return null;
        }
        try {
            final Container container = new Container(data);
            final Chunk vp8x = container.read(12);
            if (vp8x.length < 10) throw new IOException("Truncated VP8X chunk");
            final int width = 1 + u24(data, vp8x.start + 4);
            final int height = 1 + u24(data, vp8x.start + 7);
            if (width > MAX_ANIMATION_SIZE || height > MAX_ANIMATION_SIZE || (long) width * height > MAX_ANIMATION_PIXELS) {
                throw new IOException("WebP image exceeds safety limits: " + width + "x" + height);
            }
            return readAnimation(data, container, vp8x.next, width, height, false);
        } catch (RuntimeException e) {
            throw new IOException("Invalid WebP image: " + e, e);
        }
    }

    // ---------- RIFF のチャンク ----------

    // チャンク 1 つ。start から length バイトが中身 (ファイルが途中で切れていれば、ある分だけ)、next が次のチャンクの位置
    private static final class Chunk {
        final int type;
        final int start;
        final int length;
        final long next;

        Chunk(int type, int start, int length, long next) {
            this.type = type;
            this.start = start;
            this.length = length;
            this.next = next;
        }
    }

    private static final class Container {
        final byte[] data;
        // RIFF の大きさが示す終わり (ファイルより後ろなら、ファイルの終わり)
        final int end;

        Container(byte[] data) throws IOException {
            this.data = data;
            if (u32(data, 0) != RIFF || u32(data, 8) != WEBP) throw new IOException("Not a WebP image");
            final long riffSize = u32(data, 4) & 0xFFFFFFFFL;
            this.end = (int) Math.min(data.length, 8 + riffSize);
        }

        // position にあるチャンクの種類。チャンクの頭が収まっていなければ 0
        int chunkAt(long position) {
            return position + 8 <= end ? u32(data, (int) position) : 0;
        }

        Chunk read(long position) throws IOException {
            return read(position, end);
        }

        // limit までの範囲で、position にあるチャンクを読む
        Chunk read(long position, long limit) throws IOException {
            if (position + 8 > limit) throw new IOException("Truncated WebP chunk");
            final int p = (int) position;
            final int type = u32(data, p);
            final long size = u32(data, p + 4) & 0xFFFFFFFFL;
            final int start = p + 8;
            final int length = (int) Math.min(size, limit - start);
            // 中身の長さが奇数なら、1 バイトの詰め物が続く
            return new Chunk(type, start, length, start + size + (size & 1));
        }
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private static int u24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
    }

    private static void checkSize(int width, int height, int max) throws IOException {
        if (width <= 0 || height <= 0 || width > max || height > max) {
            throw new IOException("WebP image exceeds safety limits: " + width + "x" + height);
        }
    }

    // ---------- フレーム ----------

    // VP8 または VP8L のチャンクの、画像の大きさ
    private static int[] bitstreamSize(byte[] data, Chunk chunk) throws IOException {
        return chunk.type == VP8
                ? Vp8Decoder.readSize(data, chunk.start, chunk.length)
                : LosslessDecoder.readSize(data, chunk.start, chunk.length);
    }

    // VP8 / VP8L のチャンクを展開する。VP8 なら、alpha (ALPH のチャンク、なければ null) の透明度を付ける
    private static int[] decodeBitstream(byte[] data, Chunk chunk, Chunk alpha, int width, int height) throws IOException {
        final int[] size = bitstreamSize(data, chunk);
        if (size[0] != width || size[1] != height) {
            throw new IOException("WebP frame size mismatch: " + size[0] + "x" + size[1] + " (expected " + width + "x" + height + ")");
        }
        if (chunk.type == VP8L) return LosslessDecoder.decode(data, chunk.start, chunk.length);
        final int[] argb = Vp8Decoder.decode(data, chunk.start, chunk.length);
        if (alpha != null) {
            final byte[] a = AlphaDecoder.decode(data, alpha.start, alpha.length, width, height);
            for (int i = 0; i < argb.length; i++) argb[i] = (argb[i] & 0x00FFFFFF) | (a[i] & 0xFF) << 24;
        }
        return argb;
    }

    /**
     * position から limit までのチャンクの並びから、1 つのフレーム (透明度の ALPH と、画像の VP8 / VP8L) を読む。
     * ALPH が複数あれば最後のものを使う。VP8L には透明度が含まれるので ALPH は使わない。
     */
    private static int[] readFrame(byte[] data, Container container, long position, long limit, int width, int height) throws IOException {
        Chunk alpha = null;
        while (position + 8 <= limit) {
            final Chunk chunk = container.read(position, limit);
            if (chunk.type == ALPH) {
                alpha = chunk;
            } else if (chunk.type == VP8 || chunk.type == VP8L) {
                return decodeBitstream(data, chunk, chunk.type == VP8 ? alpha : null, width, height);
            }
            position = chunk.next;
        }
        throw new IOException("WebP frame has no image data");
    }

    // ---------- アニメーション (RFC 9649 2.7.1.1, 2.7.2) ----------

    /**
     * position から始まるチャンクの並び (ANIM と ANMF) を読み、キャンバスに順に重ねたコマにする。
     * キャンバスは透明から始め、前のフレームが「背景に戻す」なら、その範囲を透明にしてから次を重ねる。
     * firstOnly なら、最初のフレームを描いたところで終える。
     */
    private static DecodedImage readAnimation(byte[] data, Container container, long position, int width, int height,
                                              boolean firstOnly) throws IOException {
        final List<int[]> frames = new ArrayList<int[]>();
        final List<Integer> delays = new ArrayList<Integer>();
        int repetitions = 0;
        final int[] canvas = new int[width * height];
        // 前のフレームの範囲 (x, y, 幅, 高さ)。描いたあとに背景に戻すときだけ持つ
        int[] dispose = null;
        while (position + 8 <= container.end && !(firstOnly && frames.size() == 1)) {
            final Chunk chunk = container.read(position);
            position = chunk.next;
            if (chunk.type == ANIM) {
                // 背景色 (4 バイト) は使わず、繰り返しの回数だけを読む
                if (chunk.length >= 6) repetitions = (data[chunk.start + 4] & 0xFF) | (data[chunk.start + 5] & 0xFF) << 8;
                continue;
            }
            if (chunk.type != ANMF) continue;
            if (chunk.length < 16) throw new IOException("Truncated WebP animation frame");
            final int p = chunk.start;
            final int x = 2 * u24(data, p);
            final int y = 2 * u24(data, p + 3);
            final int frameWidth = 1 + u24(data, p + 6);
            final int frameHeight = 1 + u24(data, p + 9);
            final int duration = u24(data, p + 12);
            final int flags = data[p + 15] & 0xFF;
            if ((long) x + frameWidth > width || (long) y + frameHeight > height) {
                throw new IOException("WebP frame is outside the canvas");
            }
            if (!firstOnly && (frames.size() >= MAX_ANIMATION_FRAMES
                    || (long) (frames.size() + 1) * width * height > MAX_ANIMATION_PIXELS)) {
                throw new IOException("WebP animation exceeds safety limits");
            }
            final int[] pixels = readFrame(data, container, p + 16, (long) chunk.start + chunk.length, frameWidth, frameHeight);

            if (dispose != null) {
                for (int row = 0; row < dispose[3]; row++) {
                    final int from = (dispose[1] + row) * width + dispose[0];
                    Arrays.fill(canvas, from, from + dispose[2], 0);
                }
            }
            // 描き方: 0 なら透明度で重ね、1 なら上書きする
            final boolean blend = (flags & 0x02) == 0;
            for (int row = 0; row < frameHeight; row++) {
                final int to = (y + row) * width + x;
                final int from = row * frameWidth;
                if (blend) {
                    for (int column = 0; column < frameWidth; column++) {
                        canvas[to + column] = over(pixels[from + column], canvas[to + column]);
                    }
                } else {
                    System.arraycopy(pixels, from, canvas, to, frameWidth);
                }
            }
            frames.add(firstOnly ? canvas : canvas.clone());
            delays.add(duration == 0 ? 100 : Math.max(duration, 20));
            dispose = (flags & 0x01) != 0 ? new int[]{x, y, frameWidth, frameHeight} : null;
        }
        if (frames.isEmpty()) throw new IOException("WebP image has no frame");
        final int[] times = new int[delays.size()];
        for (int i = 0; i < times.length; i++) times[i] = delays.get(i);
        return new DecodedImage(width, height, frames.toArray(new int[0][]), times, repetitions);
    }

    // 透明度つきの色 source を、色 destination の上に重ねる (色は透明度を掛けていない値)
    private static int over(int source, int destination) {
        final int sa = source >>> 24;
        if (sa == 255) return source;
        if (sa == 0) return destination;
        final int da = destination >>> 24;
        final int total = sa * 255 + da * (255 - sa);
        final int alpha = (total + 127) / 255;
        int result = alpha << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            final int s = (source >> shift) & 0xFF, d = (destination >> shift) & 0xFF;
            result |= ((s * sa * 255 + d * da * (255 - sa)) / total) << shift;
        }
        return result;
    }

    // ARGB の配列を、TYPE_4BYTE_ABGR (1 画素が A, B, G, R の 4 バイト) の画像にする
    private static BufferedImage toImage(int width, int height, int[] argb) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        final byte[] bytes = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0, j = 0; i < argb.length; i++, j += 4) {
            final int c = argb[i];
            bytes[j] = (byte) (c >>> 24);
            bytes[j + 1] = (byte) c;
            bytes[j + 2] = (byte) (c >> 8);
            bytes[j + 3] = (byte) (c >> 16);
        }
        return image;
    }
}
