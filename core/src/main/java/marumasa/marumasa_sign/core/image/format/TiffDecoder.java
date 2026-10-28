package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.image.jpeg.JpegReader;
import marumasa.marumasa_sign.core.texture.ZstdDecoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * TIFF と BigTIFF。ストリップとタイルの両方、非圧縮・LZW・Deflate・PackBits・JPEG・Zstandard、
 * 1 から 64 ビットの整数と浮動小数点、グレー・RGB・色の一覧・CMYK・YCbCr、予測 (差分) に対応する。
 * DNG などの RAW 形式は、中に入っている読める絵のうち最も大きいものを使う。
 */
final class TiffDecoder {

    private static final int MAX_RAW = 256 * 1024 * 1024;

    private final byte[] d;
    private final boolean little;
    private final boolean big;
    // すべての IFD で読んでよい、項目の値の数の残り (たくさんの IFD から同じ大きな値を指して、メモリを使い切らせないように)
    private long valueBudget = 1 << 22;

    private TiffDecoder(byte[] d, boolean little, boolean big) {
        this.d = d;
        this.little = little;
        this.big = big;
    }

    static boolean looksLikeTiff(byte[] h, int n) {
        if (n < 8) return false;
        final boolean ii = h[0] == 'I' && h[1] == 'I';
        final boolean mm = h[0] == 'M' && h[1] == 'M';
        if (!ii && !mm) return false;
        final int version = ii ? (h[2] & 0xFF) | (h[3] & 0xFF) << 8 : (h[2] & 0xFF) << 8 | (h[3] & 0xFF);
        return version == 42 || version == 43;
    }

    private static final class Ifd {
        final Map<Integer, long[]> values = new HashMap<Integer, long[]>();

        long get(int tag, long fallback) {
            final long[] v = values.get(tag);
            return v == null || v.length == 0 ? fallback : v[0];
        }

        long[] array(int tag) {
            return values.get(tag);
        }
    }

    static DecodedImage decode(byte[] d) throws IOException {
        if (!looksLikeTiff(d, d.length)) throw new IOException("Not a TIFF image");
        final boolean little = d[0] == 'I';
        final boolean big = (little ? d[2] : d[3]) == 43;
        final TiffDecoder tiff = new TiffDecoder(d, little, big);
        return tiff.decodeAll();
    }

    private DecodedImage decodeAll() throws IOException {
        final long first = big ? u64(8) : u32(4);
        final List<Ifd> ifds = new ArrayList<Ifd>();
        final Set<Long> seen = new HashSet<Long>();
        long offset = first;
        while (offset > 0 && offset < d.length && ifds.size() < 64 && seen.add(offset)) {
            final Ifd ifd = new Ifd();
            offset = readIfd(offset, ifd);
            ifds.add(ifd);
            final long[] subs = ifd.array(330);
            if (subs != null) {
                for (int i = 0; i < subs.length && i < 8; i++) {
                    if (subs[i] > 0 && subs[i] < d.length && seen.add(subs[i])) {
                        final Ifd sub = new Ifd();
                        readIfd(subs[i], sub);
                        ifds.add(sub);
                    }
                }
            }
        }
        if (ifds.isEmpty()) throw new IOException("TIFF has no image");

        // 普通の TIFF なら最初の絵。RAW の縮小した絵や、読めない絵のときは、読める中でいちばん大きいものを使う
        IOException failure = null;
        final Ifd head = ifds.get(0);
        if ((head.get(254, 0) & 1) == 0 && head.get(262, 1) != 32803 && head.get(262, 1) != 34892) {
            try {
                return decodeIfd(head);
            } catch (IOException e) {
                failure = e;
            }
        }
        Ifd best = null;
        long bestArea = -1;
        for (Ifd ifd : ifds) {
            final long area = ifd.get(256, 0) * ifd.get(257, 0);
            final long photometric = ifd.get(262, 1);
            if (area > bestArea && photometric != 32803 && photometric != 34892 && photometric != 32892) {
                best = ifd;
                bestArea = area;
            }
        }
        if (best == null) throw failure != null ? failure : new IOException("TIFF has no readable image");
        try {
            return decodeIfd(best);
        } catch (IOException e) {
            throw failure != null ? failure : e;
        }
    }

    // ---------- 見出し ----------

    private static final int[] TYPE_SIZE = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 0, 0, 8, 8, 8};

    // IFD を読んで、次の IFD の位置を返す
    private long readIfd(long offset, Ifd ifd) throws IOException {
        final int countSize = big ? 8 : 2;
        final int entrySize = big ? 20 : 12;
        if (offset + countSize > d.length) throw new IOException("Invalid TIFF directory");
        final long count = big ? u64((int) offset) : u16((int) offset);
        if (count > 4096 || offset + countSize + count * entrySize > d.length) throw new IOException("Invalid TIFF directory");
        final int inline = big ? 8 : 4;
        for (int i = 0; i < count; i++) {
            final int entry = (int) offset + countSize + i * entrySize;
            final int tag = u16(entry);
            final int type = u16(entry + 2);
            final long n = big ? u64(entry + 4) : u32(entry + 4);
            if (type < 1 || type >= TYPE_SIZE.length || TYPE_SIZE[type] == 0 || !isNeeded(tag)) continue;
            // 有理数や小数の項目は、画像を読むのには使わない
            if (type == 5 || type == 10 || type == 11 || type == 12) continue;
            final long total = n * TYPE_SIZE[type];
            if (n < 0 || n > (1 << 22) || total > d.length) continue;
            final int valueField = entry + (big ? 12 : 8);
            final long where = total <= inline ? valueField : (big ? u64(valueField) : u32(valueField));
            if (where < 0 || where + total > d.length) continue;
            if (tag == 347) {
                // JPEG の共通の表は、バイト列のまま残す (位置と長さを覚える)
                ifd.values.put(-347, new long[]{where, n});
                continue;
            }
            if (n > valueBudget) continue;
            valueBudget -= n;
            final long[] values = new long[(int) n];
            for (int k = 0; k < values.length; k++) {
                final int p = (int) where + k * TYPE_SIZE[type];
                switch (type) {
                    case 1: case 2: case 7: values[k] = d[p] & 0xFF; break;
                    case 6: values[k] = d[p]; break;
                    case 3: values[k] = u16(p); break;
                    case 8: values[k] = (short) u16(p); break;
                    case 4: case 13: values[k] = u32(p); break;
                    case 9: values[k] = (int) u32(p); break;
                    default: values[k] = u64(p); break;
                }
            }
            ifd.values.put(tag, values);
        }
        final int next = (int) offset + countSize + (int) count * entrySize;
        if (next + (big ? 8 : 4) > d.length) return 0;
        return big ? u64(next) : u32(next);
    }

    // 画像を読むのに使う項目だけ覚える
    private static boolean isNeeded(int tag) {
        switch (tag) {
            case 254: case 256: case 257: case 258: case 259: case 262: case 266: case 273: case 274: case 277: case 278:
            case 279: case 284: case 317: case 320: case 322: case 323: case 324: case 325: case 330: case 338: case 339:
            case 347:
                return true;
            default:
                return false;
        }
    }

    private int u16(int p) {
        return little ? (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 : (d[p] & 0xFF) << 8 | (d[p + 1] & 0xFF);
    }

    private long u32(int p) {
        return little ? FormatUtil.u32le(d, p) : FormatUtil.u32be(d, p);
    }

    private long u64(int p) {
        final long a = u32(p), b = u32(p + 4);
        return little ? a | b << 32 : b | a << 32;
    }

    // ---------- 1 つの絵を読む ----------

    private DecodedImage decodeIfd(Ifd ifd) throws IOException {
        final long width = ifd.get(256, 0);
        final long height = ifd.get(257, 0);
        FormatUtil.checkSize(width, height);
        final int w = (int) width, h = (int) height;
        final int compression = (int) ifd.get(259, 1);
        final int photometric = (int) ifd.get(262, 1);
        final int spp = (int) ifd.get(277, 1);
        final boolean planar = ifd.get(284, 1) == 2;
        final int predictor = (int) ifd.get(317, 1);
        final int sampleFormat = (int) ifd.get(339, 1);
        final long[] bpsArray = ifd.array(258);
        final int bps = bpsArray == null || bpsArray.length == 0 ? 1 : (int) bpsArray[0];
        if (spp < 1 || spp > 16) throw new IOException("Unsupported TIFF samples per pixel: " + spp);
        if (bpsArray != null) {
            for (long v : bpsArray) {
                if (v != bps) throw new IOException("Unsupported TIFF: mixed bits per sample");
            }
        }
        if (bps < 1 || bps > 64 || (bps > 8 && bps % 8 != 0 && bps != 12)) throw new IOException("Unsupported TIFF bits per sample: " + bps);
        if (sampleFormat == 3 && bps != 16 && bps != 32 && bps != 64) throw new IOException("Unsupported TIFF float size");
        if (bps == 64 && sampleFormat != 3) throw new IOException("Unsupported TIFF 64-bit integers");
        if (photometric == 6 && compression != 7 && compression != 34892) throw new IOException("Unsupported TIFF YCbCr");
        if (photometric != 0 && photometric != 1 && photometric != 2 && photometric != 3 && photometric != 5 && photometric != 6) {
            throw new IOException("Unsupported TIFF photometric: " + photometric);
        }
        if (!supported(compression)) throw new IOException("Unsupported TIFF compression: " + compression);

        final boolean tiled = ifd.array(324) != null;
        final long[] offsets = tiled ? ifd.array(324) : ifd.array(273);
        long[] counts = tiled ? ifd.array(325) : ifd.array(279);
        if (offsets == null) throw new IOException("TIFF has no image data");
        final int chunkWidth = tiled ? (int) ifd.get(322, 0) : w;
        final long rowsPerStrip = ifd.get(278, h);
        final int chunkHeight = tiled ? (int) ifd.get(323, 0) : (int) Math.max(1, Math.min(rowsPerStrip, h));
        if (tiled && (chunkWidth <= 0 || chunkHeight <= 0 || chunkWidth > 16384 || chunkHeight > 16384)) throw new IOException("Invalid TIFF tile size");

        // 1 つの面に入っているサンプルの数 (色が面ごとに分かれているときは 1)
        final int perPixel = planar ? 1 : spp;
        final int planes = planar ? spp : 1;
        final int rowBytes = FormatUtil.checkedProduct((long) w * perPixel * bps + 7 >> 3, 1, MAX_RAW);
        final int imageBytes = FormatUtil.checkedProduct(rowBytes, h, MAX_RAW / planes);
        final byte[][] image = new byte[planes][imageBytes];

        final int across = (w + chunkWidth - 1) / chunkWidth;
        final int down = (h + chunkHeight - 1) / chunkHeight;
        final int perPlane = across * down;
        if ((long) perPlane * planes > offsets.length) throw new IOException("TIFF is missing image data");
        final boolean reverseBits = ifd.get(266, 1) == 2;
        final int chunkRowBytes = (int) (((long) chunkWidth * perPixel * bps + 7) >> 3);
        final byte[] tables = compression == 7 ? jpegTables(ifd) : null;

        for (int plane = 0; plane < planes; plane++) {
            for (int index = 0; index < perPlane; index++) {
                final int chunk = plane * perPlane + index;
                final long start = offsets[chunk];
                long length = counts != null && chunk < counts.length ? counts[chunk] : d.length - start;
                if (start < 0 || start >= d.length || length < 0) throw new IOException("Invalid TIFF strip offset");
                if (start + length > d.length) length = d.length - start;
                final int tx = index % across, ty = index / across;
                // 絵に入る行だけを展開する (絵より大きなタイルで、使わない部分のために大きな領域を確保しないように)
                final int rows = Math.min(chunkHeight, h - ty * chunkHeight);
                final int expected = FormatUtil.checkedProduct(chunkRowBytes, rows, MAX_RAW);
                final int fullExpected = FormatUtil.checkedProduct(chunkRowBytes, chunkHeight, MAX_RAW);
                byte[] source = d;
                int sourceStart = (int) start;
                if (reverseBits && compression != 7) {
                    // FillOrder が 2 なら、バイトの中のビットの並びが逆になっている (libtiff と同じく、JPEG 以外は展開の前に戻す)
                    source = reverseBits(d, (int) start, (int) length);
                    sourceStart = 0;
                }
                byte[] raw = unpack(compression, source, sourceStart, (int) length, expected, fullExpected, chunkWidth, chunkHeight, rows, perPixel,
                        tables);
                if (predictor == 2) horizontal(raw, chunkWidth, rows, perPixel, bps);
                else if (predictor == 3) floating(raw, chunkWidth, rows, perPixel, bps);
                if (raw.length < expected) raw = java.util.Arrays.copyOf(raw, expected);
                final int y0 = ty * chunkHeight;
                final int xByte = tiled ? tx * chunkRowBytes : 0;
                final int copy = Math.min(chunkRowBytes, rowBytes - xByte);
                for (int r = 0; r < rows && y0 + r < h; r++) {
                    System.arraycopy(raw, r * chunkRowBytes, image[plane], (y0 + r) * rowBytes + xByte, copy);
                }
            }
        }

        final int[] argb = convert(ifd, image, w, h, spp, planar, bps, sampleFormat, photometric, rowBytes);
        final int orientation = (int) ifd.get(274, 1);
        return Orientation.apply(orientation, w, h, argb);
    }

    private static boolean supported(int compression) {
        switch (compression) {
            case 1: case 5: case 7: case 8: case 32946: case 32773: case 50000:
                return true;
            default:
                return false;
        }
    }

    // ---------- 展開 ----------

    private static byte[] reverseBits(byte[] d, int start, int length) {
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) (Integer.reverse(d[start + i] & 0xFF) >>> 24);
        return out;
    }

    /**
     * 1 つのストリップかタイルを展開する。
     *
     * @param expected     使う行 (rows) の分のバイト数。これより先は展開しない
     * @param fullExpected ストリップやタイルの本来の行数 (chunkHeight) の分のバイト数
     */
    private byte[] unpack(int compression, byte[] src, int start, int length, int expected, int fullExpected, int width, int chunkHeight,
                          int rows, int perPixel, byte[] tables) throws IOException {
        switch (compression) {
            case 1: {
                final byte[] raw = new byte[Math.min(length, expected)];
                System.arraycopy(src, start, raw, 0, raw.length);
                return raw;
            }
            case 5:
                return lzw(src, start, length, expected);
            case 8:
            case 32946: {
                final Inflater inflater = new Inflater();
                try {
                    inflater.setInput(src, start, length);
                    final byte[] out = new byte[expected];
                    int filled = 0;
                    while (filled < expected && !inflater.finished()) {
                        final int n = inflater.inflate(out, filled, expected - filled);
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                        filled += n;
                    }
                    return out;
                } catch (DataFormatException e) {
                    throw new IOException("Invalid TIFF deflate data", e);
                } finally {
                    inflater.end();
                }
            }
            case 32773:
                return packBits(src, start, length, expected);
            case 50000:
                // Zstandard は途中で止められないので、本来の大きさまで展開する (領域は展開した分だけ確保される)
                return ZstdDecoder.decompress(src, start, length, fullExpected);
            case 7:
                return jpeg(src, start, length, tables, expected, width, chunkHeight, rows, perPixel);
            default:
                throw new IOException("Unsupported TIFF compression: " + compression);
        }
    }

    private static byte[] packBits(byte[] d, int start, int length, int expected) throws IOException {
        final byte[] out = new byte[expected];
        int p = start;
        final int end = start + length;
        int o = 0;
        while (o < expected && p < end) {
            final int n = d[p++];
            if (n >= 0) {
                final int count = n + 1;
                if (p + count > end) throw new IOException("Truncated TIFF PackBits data");
                final int copy = Math.min(count, expected - o);
                System.arraycopy(d, p, out, o, copy);
                p += count;
                o += copy;
            } else if (n != -128) {
                final int count = 1 - n;
                if (p >= end) throw new IOException("Truncated TIFF PackBits data");
                final byte value = d[p++];
                for (int i = 0; i < count && o < expected; i++) out[o++] = value;
            }
        }
        return out;
    }

    // TIFF の LZW (上位のビットから詰める。符号の長さは、表が埋まる 1 つ手前で増える)
    private static byte[] lzw(byte[] d, int start, int length, int expected) throws IOException {
        final byte[] out = new byte[expected];
        final int end = start + length;
        final int[] prefix = new int[4096];
        final byte[] suffix = new byte[4096];
        final int[] size = new int[4096];
        for (int i = 0; i < 256; i++) {
            suffix[i] = (byte) i;
            size[i] = 1;
        }
        final byte[] stack = new byte[4097];
        int next = 258, bits = 9;
        int old = -1;
        int o = 0;
        long buffer = 0;
        int have = 0;
        int p = start;
        while (o < expected) {
            while (have < bits && p < end) {
                buffer = buffer << 8 | (d[p++] & 0xFF);
                have += 8;
            }
            if (have < bits) break;
            final int code = (int) (buffer >> (have - bits)) & ((1 << bits) - 1);
            have -= bits;
            buffer &= (1L << have) - 1;
            if (code == 257) break;
            if (code == 256) {
                next = 258;
                bits = 9;
                old = -1;
                continue;
            }
            int current = code;
            int top = 0;
            if (code >= next) {
                if (old < 0 || code > next) throw new IOException("Invalid TIFF LZW data");
                // 直前の文字列に、その先頭の文字をつけたもの
                int first = old;
                while (first >= 258) first = prefix[first];
                stack[top++] = (byte) first;
                current = old;
            }
            while (current >= 258) {
                stack[top++] = suffix[current];
                current = prefix[current];
            }
            stack[top++] = (byte) current;
            final byte firstChar = stack[top - 1];
            for (int i = top - 1; i >= 0 && o < expected; i--) out[o++] = stack[i];
            if (old >= 0 && next < 4096) {
                prefix[next] = old;
                suffix[next] = firstChar;
                next++;
                if (next == 511) bits = 10;
                else if (next == 1023) bits = 11;
                else if (next == 2047) bits = 12;
            }
            old = code;
        }
        return out;
    }

    private byte[] jpegTables(Ifd ifd) {
        final long[] where = ifd.array(-347);
        if (where == null || where[1] < 4) return null;
        return java.util.Arrays.copyOfRange(d, (int) where[0], (int) (where[0] + where[1]));
    }

    // JPEG の 1 つ分 (ストリップやタイル) を展開して、8 ビットの色を交互に並べたバイト列にする
    private static byte[] jpeg(byte[] d, int start, int length, byte[] tables, int expected, int width, int chunkHeight, int rows, int perPixel)
            throws IOException {
        byte[] data;
        if (tables != null && length >= 2) {
            // 表 (SOI から EOI まで) の EOI を除き、本体の SOI を除いてつなぐ
            final int tableEnd = tables.length >= 2 && (tables[tables.length - 2] & 0xFF) == 0xFF && (tables[tables.length - 1] & 0xFF) == 0xD9
                    ? tables.length - 2 : tables.length;
            data = new byte[tableEnd + length - 2];
            System.arraycopy(tables, 0, data, 0, tableEnd);
            System.arraycopy(d, start + 2, data, tableEnd, length - 2);
        } else {
            data = java.util.Arrays.copyOfRange(d, start, start + length);
        }
        // libjpeg (ImageIO) には渡さず、色を変えない生の値で読む (色の変換は TIFF の指定に従って後で行う)。
        // 中の JPEG はストリップやタイルの大きさまで (たくさんのストリップから同じ大きな JPEG を指して、時間を使わせないように)
        final JpegReader.Image jpeg;
        try {
            jpeg = JpegReader.decode(data, 1, Math.min((long) width * chunkHeight, (long) ImageLoader.MAX_STATIC_SIZE * ImageLoader.MAX_STATIC_SIZE),
                    JpegReader.NO_COLOR_TRANSFORM);
        } catch (RuntimeException e) {
            throw new IOException("Invalid TIFF JPEG data", e);
        }
        final int bands = jpeg.components;
        if (bands != perPixel) throw new IOException("Unexpected TIFF JPEG components: " + bands);
        final int rowBytes = width * bands;
        final byte[] out = new byte[expected];
        final int copy = Math.min(jpeg.width, width) * bands;
        for (int y = 0; y < Math.min(jpeg.height, rows) && y * rowBytes + copy <= expected; y++) {
            System.arraycopy(jpeg.samples, y * jpeg.width * bands, out, y * rowBytes, copy);
        }
        return out;
    }

    // ---------- 予測 ----------

    // 水平方向の差分 (predictor 2)。サンプルごとに左との差で入っている
    private void horizontal(byte[] raw, int width, int rows, int perPixel, int bps) {
        final int rowBytes = (int) (((long) width * perPixel * bps + 7) >> 3);
        if (bps == 8) {
            for (int r = 0; r < rows && (r + 1) * rowBytes <= raw.length; r++) {
                final int base = r * rowBytes;
                for (int i = perPixel; i < rowBytes; i++) raw[base + i] += raw[base + i - perPixel];
            }
        } else if (bps == 16) {
            for (int r = 0; r < rows && (r + 1) * rowBytes <= raw.length; r++) {
                final int base = r * rowBytes;
                for (int i = perPixel; i < width * perPixel; i++) {
                    final int a = base + i * 2, b = a - perPixel * 2;
                    final int v = (get16(raw, a) + get16(raw, b)) & 0xFFFF;
                    put16(raw, a, v);
                }
            }
        } else if (bps == 32) {
            for (int r = 0; r < rows && (r + 1) * rowBytes <= raw.length; r++) {
                final int base = r * rowBytes;
                for (int i = perPixel; i < width * perPixel; i++) {
                    final int a = base + i * 4, b = a - perPixel * 4;
                    put32(raw, a, get32(raw, a) + get32(raw, b));
                }
            }
        }
    }

    // 浮動小数点の予測 (predictor 3)。各行でバイトを上位から並べ替え、さらにバイトごとの差分にしてある
    private void floating(byte[] raw, int width, int rows, int perPixel, int bps) {
        final int bytes = bps / 8;
        final int samples = width * perPixel;
        final int rowBytes = samples * bytes;
        final byte[] tmp = new byte[rowBytes];
        for (int r = 0; r < rows && (r + 1) * rowBytes <= raw.length; r++) {
            final int base = r * rowBytes;
            for (int i = perPixel; i < rowBytes; i++) raw[base + i] += raw[base + i - perPixel];
            System.arraycopy(raw, base, tmp, 0, rowBytes);
            for (int s = 0; s < samples; s++) {
                for (int b = 0; b < bytes; b++) {
                    // 上位のバイトが先に並んでいる。ファイルのバイト順に直す
                    final int source = (little ? bytes - 1 - b : b) * samples + s;
                    raw[base + s * bytes + b] = tmp[source];
                }
            }
        }
    }

    private int get16(byte[] a, int p) {
        return little ? (a[p] & 0xFF) | (a[p + 1] & 0xFF) << 8 : (a[p] & 0xFF) << 8 | (a[p + 1] & 0xFF);
    }

    private void put16(byte[] a, int p, int v) {
        if (little) {
            a[p] = (byte) v;
            a[p + 1] = (byte) (v >> 8);
        } else {
            a[p] = (byte) (v >> 8);
            a[p + 1] = (byte) v;
        }
    }

    private int get32(byte[] a, int p) {
        return little ? FormatUtil.s32le(a, p) : FormatUtil.s32be(a, p);
    }

    private void put32(byte[] a, int p, int v) {
        for (int i = 0; i < 4; i++) a[p + i] = (byte) (v >> (little ? i * 8 : 24 - i * 8));
    }

    // ---------- 色の変換 ----------

    private double sample(byte[][] image, boolean planar, int spp, int rowBytes, int bps, int format, int x, int y, int s) {
        final byte[] buffer = image[planar ? s : 0];
        final int index = planar ? x : x * spp + s;
        final int rowStart = y * rowBytes;
        if (bps < 8 || bps == 12) {
            final int bit = index * bps;
            final int p = rowStart + (bit >> 3);
            if (bps == 12) {
                final int v = (buffer[p] & 0xFF) << 8 | (buffer[p + 1 < buffer.length ? p + 1 : p] & 0xFF);
                return (bit & 7) == 0 ? v >> 4 : v & 0xFFF;
            }
            return ((buffer[p] & 0xFF) >> (8 - bps - (bit & 7))) & ((1 << bps) - 1);
        }
        final int p = rowStart + index * (bps >> 3);
        switch (bps) {
            case 8: {
                final int v = buffer[p] & 0xFF;
                return format == 2 ? v ^ 0x80 : v;
            }
            case 16: {
                final int v = get16(buffer, p);
                if (format == 3) return halfToFloat(v);
                return format == 2 ? v ^ 0x8000 : v;
            }
            case 32: {
                final int v = get32(buffer, p);
                if (format == 3) return Float.intBitsToFloat(v);
                return format == 2 ? (v ^ 0x80000000) & 0xFFFFFFFFL : v & 0xFFFFFFFFL;
            }
            case 64: {
                final long a = get32(buffer, p) & 0xFFFFFFFFL, b = get32(buffer, p + 4) & 0xFFFFFFFFL;
                return Double.longBitsToDouble(little ? b << 32 | a : a << 32 | b);
            }
            default: {
                // 24・40・48・56 ビットの整数
                final int bytes = bps >> 3;
                long v = 0;
                for (int i = 0; i < bytes; i++) v |= (buffer[p + i] & 0xFFL) << (little ? 8 * i : 8 * (bytes - 1 - i));
                return format == 2 ? v ^ (1L << (bps - 1)) : v;
            }
        }
    }

    private static double halfToFloat(int h) {
        final int exp = (h >> 10) & 31, mant = h & 1023;
        final double sign = (h & 0x8000) != 0 ? -1 : 1;
        if (exp == 0) return sign * mant * Math.pow(2, -24);
        if (exp == 31) return mant == 0 ? sign * Double.POSITIVE_INFINITY : Double.NaN;
        return sign * (1 + mant / 1024.0) * Math.pow(2, exp - 15);
    }

    private int[] convert(Ifd ifd, byte[][] image, int w, int h, int spp, boolean planar, int bps, int format, int photometric,
                          int rowBytes) throws IOException {
        final long[] extra = ifd.array(338);
        final int colorSamples = photometric == 2 || photometric == 6 ? 3 : (photometric == 5 ? 4 : 1);
        if (spp < colorSamples) throw new IOException("Not enough TIFF samples for the color model");
        // 余分なサンプルの最初のものが透明度のときだけ使う (1 は乗算済み、2 は乗算なし)
        final int extraKind = extra != null && extra.length > 0 && spp > colorSamples ? (int) extra[0] : 0;
        final boolean hasAlpha = extraKind == 1 || extraKind == 2;
        final boolean premultiplied = extraKind == 1;
        final boolean isFloat = format == 3;
        final long max = bps >= 64 ? 1 : (1L << Math.min(bps, 62)) - 1;

        int[] palette = null;
        if (photometric == 3) {
            final long[] map = ifd.array(320);
            final int entries = 1 << Math.min(bps, 16);
            if (map == null || map.length < entries * 3) throw new IOException("TIFF has no color map");
            palette = new int[entries];
            for (int i = 0; i < entries; i++) {
                palette[i] = FormatUtil.argb(255, (int) (map[i] >> 8), (int) (map[entries + i] >> 8), (int) (map[2 * entries + i] >> 8));
            }
        }

        // 浮動小数点は、1 を超える値があれば最大が 1 になるように縮める
        double floatScale = 1;
        if (isFloat) {
            double top = 0;
            for (int y = 0; y < h; y += Math.max(1, h / 256)) {
                for (int x = 0; x < w; x++) {
                    for (int s = 0; s < spp; s++) {
                        final double v = sample(image, planar, spp, rowBytes, bps, format, x, y, s);
                        if (!Double.isNaN(v) && !Double.isInfinite(v) && v > top) top = v;
                    }
                }
            }
            if (top > 1) floatScale = 1 / top;
        }

        final int[] result = new int[w * h];
        final int[] c = new int[Math.max(spp, 4)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (palette != null) {
                    final int index = (int) sample(image, planar, spp, rowBytes, bps, format, x, y, 0);
                    result[y * w + x] = palette[Math.min(index, palette.length - 1)];
                    continue;
                }
                for (int s = 0; s < spp; s++) {
                    final double v = sample(image, planar, spp, rowBytes, bps, format, x, y, s);
                    if (isFloat) c[s] = FormatUtil.clamp8((int) Math.round((Double.isNaN(v) ? 0 : v) * floatScale * 255));
                    // 32 ビットより大きい整数は、掛け算があふれないように上位の 8 ビットを使う
                    else c[s] = bps > 32 ? (int) ((long) v >>> (bps - 8)) : FormatUtil.to8((long) v, max);
                }
                int a = hasAlpha ? c[colorSamples] : 255;
                int r, g, b;
                switch (photometric) {
                    case 0:
                        r = g = b = 255 - c[0];
                        break;
                    case 1:
                        r = g = b = c[0];
                        break;
                    case 5: {
                        final int rgb = FormatUtil.cmyk(c[0], c[1], c[2], c[3]);
                        r = (rgb >> 16) & 0xFF;
                        g = (rgb >> 8) & 0xFF;
                        b = rgb & 0xFF;
                        break;
                    }
                    case 6: {
                        final double yy = c[0], cb = c[1] - 128, cr = c[2] - 128;
                        r = FormatUtil.clamp8((int) Math.round(yy + 1.402 * cr));
                        g = FormatUtil.clamp8((int) Math.round(yy - 0.344136 * cb - 0.714136 * cr));
                        b = FormatUtil.clamp8((int) Math.round(yy + 1.772 * cb));
                        break;
                    }
                    default:
                        r = c[0];
                        g = c[1];
                        b = c[2];
                        break;
                }
                if (premultiplied && a > 0 && a < 255) {
                    r = Math.min(255, r * 255 / a);
                    g = Math.min(255, g * 255 / a);
                    b = Math.min(255, b * 255 / a);
                }
                result[y * w + x] = FormatUtil.argb(a, r, g, b);
            }
        }
        return result;
    }
}
