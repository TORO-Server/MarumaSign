package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ICO と CUR (Windows のアイコンとカーソル) と、ANI (アニメーションするカーソル)。
 * ICO と CUR は、いちばん大きい絵を使う。
 */
final class IcoDecoder {

    private IcoDecoder() {
    }

    static boolean looksLikeIco(byte[] h, int n) {
        if (n < 22 || h[0] != 0 || h[1] != 0 || (h[2] != 1 && h[2] != 2) || h[3] != 0) return false;
        final int count = FormatUtil.u16le(h, 4);
        if (count < 1 || count > 1024) return false;
        // 最初の項目の、予約の欄は 0 で、データは見出しのあとから始まる
        return h[9] == 0 && FormatUtil.u32le(h, 18) >= 6 + count * 16L;
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (d.length < 22) throw new IOException("Truncated ICO");
        final int count = FormatUtil.u16le(d, 4);
        int bestWidth = -1, bestBits = -1, bestOffset = -1, bestSize = 0;
        for (int i = 0; i < count && 6 + i * 16 + 16 <= d.length; i++) {
            final int entry = 6 + i * 16;
            int width = d[entry] & 0xFF;
            if (width == 0) width = 256;
            final int bits = FormatUtil.u16le(d, entry + 6);
            final long size = FormatUtil.u32le(d, entry + 8);
            final long offset = FormatUtil.u32le(d, entry + 12);
            if (offset >= d.length || size <= 0 || offset + size > d.length) continue;
            if (width > bestWidth || (width == bestWidth && bits > bestBits)) {
                bestWidth = width;
                bestBits = bits;
                bestOffset = (int) offset;
                bestSize = (int) size;
            }
        }
        if (bestOffset < 0) throw new IOException("ICO has no image");
        return decodeEntry(d, bestOffset, bestSize);
    }

    private static DecodedImage decodeEntry(byte[] d, int offset, int size) throws IOException {
        if (size >= 8 && (d[offset] & 0xFF) == 0x89 && d[offset + 1] == 'P') {
            final byte[] png = java.util.Arrays.copyOfRange(d, offset, offset + size);
            final BufferedImage image = ImageBytes.read(png);
            if (image == null) throw new IOException("Invalid PNG in ICO");
            FormatUtil.checkSize(image.getWidth(), image.getHeight());
            return FormatUtil.still(image.getWidth(), image.getHeight(), ArgbPixels.readDisplay(image));
        }
        return BmpDecoder.decodeDib(d, offset, -1, true);
    }

    // ANI (RIFF "ACON")。フレームごとに ICO か CUR が入っている
    static DecodedImage decodeAni(byte[] d) throws IOException {
        if (d.length < 12 || !FormatUtil.matchesAt(d, d.length, 8, "ACON")) throw new IOException("Not an ANI file");
        // 既定の表示時間 (1/60 秒)。anih の項目で上書きされる
        final long[] defaultJiffies = {6};
        final List<Long> rates = new ArrayList<Long>();
        final List<Integer> sequence = new ArrayList<Integer>();
        final List<DecodedImage> frames = new ArrayList<DecodedImage>();
        parse(d, 12, d.length, defaultJiffies, rates, sequence, frames);
        if (frames.isEmpty()) throw new IOException("ANI has no frames");

        // 並びの指定 (seq) があればその順に、なければ入っている順に出す
        final int steps = sequence.isEmpty() ? frames.size() : sequence.size();
        int width = 1, height = 1;
        for (DecodedImage frame : frames) {
            width = Math.max(width, frame.width);
            height = Math.max(height, frame.height);
        }
        final int[][] pixels = new int[steps][];
        final int[] delays = new int[steps];
        for (int i = 0; i < steps; i++) {
            final int index = sequence.isEmpty() ? i : Math.min(sequence.get(i), frames.size() - 1);
            final DecodedImage frame = frames.get(Math.max(index, 0));
            pixels[i] = place(frame, width, height);
            final long jiffies = i < rates.size() ? rates.get(i) : (rates.size() == 1 ? rates.get(0) : defaultJiffies[0]);
            delays[i] = (int) Math.max(20, jiffies * 1000 / 60);
        }
        return new DecodedImage(width, height, pixels, delays, 0);
    }

    private static int[] place(DecodedImage frame, int width, int height) {
        if (frame.width == width && frame.height == height) return frame.frames[0];
        final int[] result = new int[width * height];
        for (int y = 0; y < frame.height; y++) {
            System.arraycopy(frame.frames[0], y * frame.width, result, y * width, frame.width);
        }
        return result;
    }

    private static void parse(byte[] d, int start, int end, long[] defaultJiffies, List<Long> rates, List<Integer> sequence,
                              List<DecodedImage> frames) throws IOException {
        int p = start;
        while (p + 8 <= end) {
            final String id = new String(d, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            final long size = FormatUtil.u32le(d, p + 4);
            final int body = p + 8;
            final int bodyEnd = (int) Math.min(end, body + size);
            if (id.equals("anih") && size >= 36) {
                defaultJiffies[0] = FormatUtil.u32le(d, body + 28);
            } else if (id.equals("rate")) {
                for (int q = body; q + 4 <= bodyEnd; q += 4) rates.add(FormatUtil.u32le(d, q));
            } else if (id.equals("seq ")) {
                for (int q = body; q + 4 <= bodyEnd; q += 4) sequence.add((int) Math.min(FormatUtil.u32le(d, q), Integer.MAX_VALUE));
            } else if (id.equals("LIST")) {
                if (FormatUtil.matchesAt(d, end, body, "fram")) parse(d, body + 4, bodyEnd, defaultJiffies, rates, sequence, frames);
            } else if (id.equals("icon")) {
                if (frames.size() >= 512) throw new IOException("ANI has too many frames");
                frames.add(decode(java.util.Arrays.copyOfRange(d, body, bodyEnd)));
            }
            if (size + (size & 1) > end - body) break;
            p = body + (int) size + (int) (size & 1);
        }
    }
}
