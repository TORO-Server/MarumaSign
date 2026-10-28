package marumasa.marumasa_sign.core.image.jpeg;

import java.io.IOException;

/**
 * JPEG (ITU-T T.81) を Java だけで戻す。ベースラインとプログレッシブ (ハフマン符号)、8 ビットと 12 ビットに対応する。
 * <p>
 * Java の ImageIO の JPEG の読み込みは C で書かれた libjpeg を使っている。誰でも作れるファイルを読ませると、
 * その不具合でメモリを壊される (任意のコードを実行される) おそれがあるので、JPEG はすべてこちらで読む
 * (PDF の画像、看板の画像、3D モデルのテクスチャ、TIFF の中の JPEG)。
 * 大きさや係数の数には上限を設け、壊れたデータは読めたところまでを使う。
 * 縮小して読む指定 (1/2、1/4、1/8) では、DCT の低い周波数だけを使って速く読む。
 */
public final class JpegReader {

    /** decode の colorTransform に渡すと、色の変換 (YCbCr から RGB、YCCK から CMYK) をせずに、符号のままの値を返す */
    public static final int NO_COLOR_TRANSFORM = -2;

    // 係数を持つためのメモリの上限 (short の数)
    private static final long MAX_COEFFICIENTS = 160L * 1024 * 1024;

    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
            28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61,
            54, 47, 55, 62, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63};

    /**
     * 戻した画像。samples は 1 画素ごとに components 個のバイト (灰色、RGB、CMYK のどれか)。
     */
    public static final class Image {
        public final int width;
        public final int height;
        public final int components;
        public final byte[] samples;
        // Adobe の印 (APP14) があったか、その変換の種類
        public final boolean adobe;
        public final int transform;

        Image(int width, int height, int components, byte[] samples, boolean adobe, int transform) {
            this.width = width;
            this.height = height;
            this.components = components;
            this.samples = samples;
            this.adobe = adobe;
            this.transform = transform;
        }
    }

    private static final class Component {
        int id;
        int h, v;
        int quantTable;
        int dcTable, acTable;
        int blocksPerLine, blocksPerColumn;
        short[] coefficients;
        int prediction;
        // 縮小したあとの、この成分の画素
        byte[] pixels;
        int pixelWidth, pixelHeight;
    }

    private static final class Huffman {
        final int[] lookup = new int[1 << 9];
        final int[] maxCode = new int[18];
        final int[] valPtr = new int[17];
        final int[] minCode = new int[17];
        final int[] values;

        Huffman(int[] counts, int[] values) throws IOException {
            this.values = values;
            int code = 0;
            int k = 0;
            for (int length = 1; length <= 16; length++) {
                valPtr[length] = k;
                minCode[length] = code;
                for (int i = 0; i < counts[length]; i++) {
                    if (length <= 9) {
                        final int shift = 9 - length;
                        final int base = code << shift;
                        for (int s = 0; s < 1 << shift; s++) {
                            if (base + s < lookup.length) lookup[base + s] = (length << 16) | values[k] | 0x8000_0000;
                        }
                    }
                    code++;
                    k++;
                }
                maxCode[length] = counts[length] > 0 ? code - 1 : -1;
                if (code > (1 << length)) throw new IOException("Bad Huffman table");
                code <<= 1;
            }
            maxCode[17] = Integer.MAX_VALUE;
        }
    }

    private final byte[] data;
    private int position;
    private final int[][] quantTables = new int[4][];
    private final Huffman[] dcTables = new Huffman[4];
    private final Huffman[] acTables = new Huffman[4];
    private Component[] components;
    private int width, height, precision;
    private boolean progressive;
    private int maxH, maxV;
    private int mcusPerLine, mcusPerColumn;
    private int restartInterval;
    private boolean adobe;
    private int adobeTransform = -1;
    private boolean jfif;
    private int eobRun;

    // ビットの読み取り
    private int bitBuffer;
    private int bitCount;
    private boolean markerHit;

    private JpegReader(byte[] data) {
        this.data = data;
    }

    /**
     * 戻す。
     *
     * @param scale     縮小の分母 (1、2、4、8)
     * @param maxPixels 戻した画像の画素の数の上限
     * @param colorTransform PDF の ColorTransform (-1 なら指定なし。NO_COLOR_TRANSFORM なら色を変換しない)
     */
    public static Image decode(byte[] data, int scale, long maxPixels, int colorTransform) throws IOException {
        if (scale != 1 && scale != 2 && scale != 4 && scale != 8) scale = 1;
        try {
            return new JpegReader(data).read(scale, maxPixels, colorTransform);
        } catch (EndOfData e) {
            throw new IOException("Truncated JPEG data", e);
        }
    }

    // 見出しの途中でデータが終わった (壊れたファイル)。decode で IOException にする
    private static final class EndOfData extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EndOfData() {
            super("End of data", null, false, false);
        }
    }

    /**
     * 戻した画像 (色の変換をしたもの) を、1 画素 1 つの ARGB (不透明) にする。
     * CMYK は単純な式で RGB にする。Adobe の印があるものは、値が反転している (Photoshop の書き方) ものとして扱う。
     */
    public static int[] toArgb(Image image) throws IOException {
        final int n = image.components;
        final byte[] s = image.samples;
        final int[] pixels = new int[image.width * image.height];
        for (int i = 0, p = 0; i < pixels.length; i++, p += n) {
            final int r, g, b;
            if (n == 1) {
                r = g = b = s[p] & 0xFF;
            } else if (n == 3) {
                r = s[p] & 0xFF;
                g = s[p + 1] & 0xFF;
                b = s[p + 2] & 0xFF;
            } else if (n == 4) {
                // インクの量 (0 から 255)
                final int flip = image.adobe ? 255 : 0;
                final int c = (s[p] & 0xFF) ^ flip, m = (s[p + 1] & 0xFF) ^ flip, y = (s[p + 2] & 0xFF) ^ flip, k = (s[p + 3] & 0xFF) ^ flip;
                r = (255 - c) * (255 - k) / 255;
                g = (255 - m) * (255 - k) / 255;
                b = (255 - y) * (255 - k) / 255;
            } else {
                throw new IOException("Unsupported JPEG components: " + n);
            }
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    /**
     * 先頭を読んで、画像の大きさと成分の数だけを返す ({幅, 高さ, 成分の数})。読めなければ null。
     */
    public static int[] size(byte[] data) {
        final JpegReader reader = new JpegReader(data);
        try {
            reader.skipToStart();
            while (reader.position + 4 <= data.length) {
                final int marker = reader.nextMarker();
                if (marker < 0) return null;
                final int length = reader.u16(reader.position);
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return new int[]{reader.u16(reader.position + 5), reader.u16(reader.position + 3), reader.data[reader.position + 7] & 0xFF};
                }
                reader.position += length;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private int u16(int p) {
        if (p + 1 >= data.length) throw new EndOfData();
        return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
    }

    private void skipToStart() throws IOException {
        // 前に余計なバイト (改行など) が付いていてもよい
        for (int i = 0; i + 1 < data.length && i < 1024; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8) {
                position = i + 2;
                return;
            }
        }
        throw new IOException("Not a JPEG");
    }

    // 次のマーカー。位置はマーカーの後ろ (長さの前)。終わりなら -1
    private int nextMarker() {
        while (position + 1 < data.length) {
            if ((data[position] & 0xFF) != 0xFF) {
                position++;
                continue;
            }
            int marker = data[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            position += 2;
            if (marker == 0 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            return marker;
        }
        return -1;
    }

    private Image read(int scale, long maxPixels, int colorTransform) throws IOException {
        skipToStart();
        boolean frameRead = false;
        boolean anyScan = false;
        while (true) {
            final int marker = nextMarker();
            if (marker < 0 || marker == 0xD9) break;
            if (marker == 0xD8 || marker == 0x01) continue;
            final int length = u16(position);
            if (length < 2) throw new IOException("Bad marker length");
            final int segment = position + 2;
            // 区切りの中身がデータの終わりを越えるものは壊れている (中身を読むときに、終わりより先を読まないように)
            if ((long) position + length > data.length) throw new IOException("Truncated JPEG segment");
            final int end = position + length;
            switch (marker) {
                case 0xDB:
                    readQuantTables(segment, end);
                    break;
                case 0xC4:
                    readHuffmanTables(segment, end);
                    break;
                case 0xC0:
                case 0xC1:
                case 0xC2:
                    if (frameRead) throw new IOException("Multiple frames");
                    progressive = marker == 0xC2;
                    readFrame(segment, end, maxPixels, scale);
                    frameRead = true;
                    break;
                case 0xC3:
                case 0xC5: case 0xC6: case 0xC7:
                case 0xC9: case 0xCA: case 0xCB:
                case 0xCD: case 0xCE: case 0xCF:
                    throw new IOException("Unsupported JPEG type " + Integer.toHexString(marker));
                case 0xDD:
                    restartInterval = u16(segment);
                    break;
                case 0xDA:
                    if (!frameRead) throw new IOException("Scan before frame");
                    position = end;
                    try {
                        readScan(segment, end);
                        anyScan = true;
                    } catch (IOException | RuntimeException e) {
                        // 壊れたデータ: 読めたところまでを使う
                        if (!anyScan && !progressive) {
                            anyScan = true;
                        }
                        return finish(scale, colorTransform);
                    }
                    continue;
                case 0xE0:
                    if (length >= 7 && matches(segment, "JFIF\0")) jfif = true;
                    break;
                case 0xEE:
                    if (length >= 14 && matches(segment, "Adobe")) {
                        adobe = true;
                        adobeTransform = data[segment + 11] & 0xFF;
                    }
                    break;
                default:
                    break;
            }
            position = end;
        }
        if (!frameRead) throw new IOException("No JPEG frame");
        return finish(scale, colorTransform);
    }

    private boolean matches(int p, String text) {
        if (p + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) {
            if (data[p + i] != text.charAt(i)) return false;
        }
        return true;
    }

    private void readQuantTables(int p, int end) throws IOException {
        while (p < end) {
            final int info = data[p++] & 0xFF;
            final int id = info & 15;
            final boolean sixteen = (info >> 4) != 0;
            if (id > 3 || p + (sixteen ? 128 : 64) > end) throw new IOException("Bad quantization table");
            final int[] table = new int[64];
            for (int k = 0; k < 64; k++) {
                if (sixteen) {
                    table[ZIGZAG[k]] = u16(p);
                    p += 2;
                } else {
                    table[ZIGZAG[k]] = data[p++] & 0xFF;
                }
            }
            quantTables[id] = table;
        }
    }

    private void readHuffmanTables(int p, int end) throws IOException {
        while (p + 17 <= end) {
            final int info = data[p++] & 0xFF;
            final int[] counts = new int[17];
            int total = 0;
            for (int i = 1; i <= 16; i++) {
                counts[i] = data[p++] & 0xFF;
                total += counts[i];
            }
            if (total > 256 || p + total > end) throw new IOException("Bad Huffman table");
            final int[] values = new int[total];
            for (int i = 0; i < total; i++) values[i] = data[p++] & 0xFF;
            final int id = info & 15;
            if (id > 3) throw new IOException("Bad Huffman table id");
            if ((info >> 4) == 0) dcTables[id] = new Huffman(counts, values);
            else acTables[id] = new Huffman(counts, values);
        }
    }

    private void readFrame(int p, int end, long maxPixels, int scale) throws IOException {
        if (p + 6 > end || p + 6 + 3 * (data[p + 5] & 0xFF) > end) throw new IOException("Bad JPEG frame header");
        precision = data[p] & 0xFF;
        if (precision != 8 && precision != 12) throw new IOException("Unsupported JPEG precision " + precision);
        height = u16(p + 1);
        width = u16(p + 3);
        final int count = data[p + 5] & 0xFF;
        if (width == 0 || height == 0) throw new IOException("Bad JPEG size");
        if (count < 1 || count > 4) throw new IOException("Unsupported JPEG component count " + count);
        components = new Component[count];
        maxH = 1;
        maxV = 1;
        for (int i = 0; i < count; i++) {
            final Component c = new Component();
            c.id = data[p + 6 + 3 * i] & 0xFF;
            final int sampling = data[p + 7 + 3 * i] & 0xFF;
            c.h = sampling >> 4;
            c.v = sampling & 15;
            c.quantTable = data[p + 8 + 3 * i] & 3;
            if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4) throw new IOException("Bad sampling factors");
            maxH = Math.max(maxH, c.h);
            maxV = Math.max(maxV, c.v);
            components[i] = c;
        }
        final long outputPixels = (long) ((width + scale - 1) / scale) * ((height + scale - 1) / scale);
        if (outputPixels > maxPixels) throw new IOException("JPEG image is too large");
        mcusPerLine = (width + 8 * maxH - 1) / (8 * maxH);
        mcusPerColumn = (height + 8 * maxV - 1) / (8 * maxV);
        long total = 0;
        for (Component c : components) {
            c.blocksPerLine = mcusPerLine * c.h;
            c.blocksPerColumn = mcusPerColumn * c.v;
            total += (long) c.blocksPerLine * c.blocksPerColumn * 64;
        }
        if (total > MAX_COEFFICIENTS) throw new IOException("JPEG image is too large");
        for (Component c : components) c.coefficients = new short[c.blocksPerLine * c.blocksPerColumn * 64];
    }

    // ---------- スキャン ----------

    private void readScan(int p, int end) throws IOException {
        if (p >= end) throw new IOException("Bad scan");
        final int count = data[p] & 0xFF;
        if (count < 1 || count > 4 || p + 4 + 2 * count > end) throw new IOException("Bad scan");
        final Component[] scan = new Component[count];
        for (int i = 0; i < count; i++) {
            final int id = data[p + 1 + 2 * i] & 0xFF;
            final int tables = data[p + 2 + 2 * i] & 0xFF;
            Component found = null;
            for (Component c : components) {
                if (c.id == id) found = c;
            }
            if (found == null) {
                // 番号が合わなければ、順番で選ぶ
                found = components[Math.min(i, components.length - 1)];
            }
            found.dcTable = tables >> 4 & 3;
            found.acTable = tables & 3;
            scan[i] = found;
        }
        final int q = p + 1 + 2 * count;
        final int spectralStart = data[q] & 0xFF;
        final int spectralEnd = data[q + 1] & 0xFF;
        final int approximation = data[q + 2] & 0xFF;
        final int ah = approximation >> 4;
        final int al = approximation & 15;
        if (spectralEnd > 63 || spectralStart > spectralEnd || al > 13) throw new IOException("Bad spectral selection");

        bitBuffer = 0;
        bitCount = 0;
        markerHit = false;
        eobRun = 0;
        for (Component c : scan) c.prediction = 0;

        final boolean single = count == 1;
        final int totalMcus = single ? componentBlocksWide(scan[0]) * componentBlocksHigh(scan[0]) : mcusPerLine * mcusPerColumn;
        int mcu = 0;
        while (mcu < totalMcus) {
            final int interval = restartInterval > 0 ? Math.min(restartInterval, totalMcus - mcu) : totalMcus - mcu;
            for (int n = 0; n < interval; n++, mcu++) {
                if (single) {
                    final Component c = scan[0];
                    final int wide = componentBlocksWide(c);
                    final int row = mcu / wide, column = mcu % wide;
                    decodeBlock(c, row, column, spectralStart, spectralEnd, ah, al);
                } else {
                    final int mcuRow = mcu / mcusPerLine, mcuColumn = mcu % mcusPerLine;
                    for (Component c : scan) {
                        for (int v = 0; v < c.v; v++) {
                            for (int h = 0; h < c.h; h++) {
                                decodeBlock(c, mcuRow * c.v + v, mcuColumn * c.h + h, spectralStart, spectralEnd, ah, al);
                            }
                        }
                    }
                }
            }
            if (mcu >= totalMcus) break;
            // 再開マーカー: ビットを捨て、予測をやり直す
            bitBuffer = 0;
            bitCount = 0;
            markerHit = false;
            eobRun = 0;
            for (Component c : scan) c.prediction = 0;
            while (position + 1 < data.length) {
                if ((data[position] & 0xFF) == 0xFF && (data[position + 1] & 0xFF) >= 0xD0 && (data[position + 1] & 0xFF) <= 0xD7) {
                    position += 2;
                    break;
                }
                if ((data[position] & 0xFF) == 0xFF && data[position + 1] != 0 && (data[position + 1] & 0xFF) != 0xFF) {
                    // 再開マーカーでないマーカー: データが欠けている
                    return;
                }
                position++;
            }
        }
        // 次のマーカーへ
        while (position + 1 < data.length) {
            if ((data[position] & 0xFF) == 0xFF && data[position + 1] != 0 && !((data[position + 1] & 0xFF) >= 0xD0 && (data[position + 1] & 0xFF) <= 0xD7)) {
                break;
            }
            position++;
        }
    }

    // 1 つの成分だけのスキャンでの、ブロックの数 (MCU の詰め物を含まない)
    private int componentBlocksWide(Component c) {
        return (int) Math.ceil(Math.ceil(width * (double) c.h / maxH) / 8);
    }

    private int componentBlocksHigh(Component c) {
        return (int) Math.ceil(Math.ceil(height * (double) c.v / maxV) / 8);
    }

    private void decodeBlock(Component c, int row, int column, int ss, int se, int ah, int al) throws IOException {
        if (row >= c.blocksPerColumn || column >= c.blocksPerLine) {
            // 詰め物の外: 読み捨てるために一時的な場所に戻す
            final short[] dummy = new short[64];
            decode(c, dummy, 0, ss, se, ah, al);
            return;
        }
        decode(c, c.coefficients, (row * c.blocksPerLine + column) * 64, ss, se, ah, al);
    }

    private void decode(Component c, short[] block, int offset, int ss, int se, int ah, int al) throws IOException {
        if (!progressive) {
            final Huffman dc = table(dcTables, c.dcTable);
            final Huffman ac = table(acTables, c.acTable);
            final int t = decodeHuffman(dc);
            final int diff = t == 0 ? 0 : extend(receive(t), t);
            c.prediction += diff;
            block[offset] = (short) c.prediction;
            int k = 1;
            while (k < 64) {
                final int rs = decodeHuffman(ac);
                final int s = rs & 15, r = rs >> 4;
                if (s == 0) {
                    if (r < 15) break;
                    k += 16;
                    continue;
                }
                k += r;
                if (k > 63) break;
                block[offset + ZIGZAG[k]] = (short) extend(receive(s), s);
                k++;
            }
            return;
        }
        if (ss == 0) {
            // DC の段階
            if (ah == 0) {
                final int t = decodeHuffman(table(dcTables, c.dcTable));
                final int diff = t == 0 ? 0 : extend(receive(t), t);
                c.prediction += diff;
                block[offset] = (short) (c.prediction << al);
            } else if (readBit() != 0) {
                block[offset] |= (short) (1 << al);
            }
            return;
        }
        final Huffman ac = table(acTables, c.acTable);
        if (ah == 0) {
            // AC の最初の段階
            if (eobRun > 0) {
                eobRun--;
                return;
            }
            int k = ss;
            while (k <= se) {
                final int rs = decodeHuffman(ac);
                final int s = rs & 15, r = rs >> 4;
                if (s == 0) {
                    if (r < 15) {
                        eobRun = (1 << r) - 1;
                        if (r > 0) eobRun += receive(r);
                        break;
                    }
                    k += 16;
                    continue;
                }
                k += r;
                if (k > 63) break;
                block[offset + ZIGZAG[k]] = (short) (extend(receive(s), s) * (1 << al));
                k++;
            }
            return;
        }
        // AC の精度を上げる段階
        final int p1 = 1 << al;
        final int m1 = -1 << al;
        int k = ss;
        if (eobRun <= 0) {
            while (k <= se) {
                final int rs = decodeHuffman(ac);
                final int s = rs & 15;
                int r = rs >> 4;
                int value = 0;
                if (s == 0) {
                    if (r < 15) {
                        eobRun = 1 << r;
                        if (r > 0) eobRun += receive(r);
                        break;
                    }
                } else {
                    value = readBit() != 0 ? p1 : m1;
                }
                // r 個の 0 の係数を飛ばし (0 でない係数は精度を上げながら)、値を置く
                while (k <= se) {
                    final int z = offset + ZIGZAG[k];
                    if (block[z] != 0) {
                        refine(block, z, p1, m1);
                    } else {
                        if (r == 0) {
                            if (value != 0) block[z] = (short) value;
                            k++;
                            break;
                        }
                        r--;
                    }
                    k++;
                }
            }
        }
        if (eobRun > 0) {
            // 帯の終わり: 残りの 0 でない係数の精度を上げる
            while (k <= se) {
                final int z = offset + ZIGZAG[k];
                if (block[z] != 0) refine(block, z, p1, m1);
                k++;
            }
            eobRun--;
        }
    }

    private void refine(short[] block, int z, int p1, int m1) throws IOException {
        if (readBit() != 0 && (block[z] & p1) == 0) {
            block[z] = (short) (block[z] >= 0 ? block[z] + p1 : block[z] + m1);
        }
    }

    private static Huffman table(Huffman[] tables, int id) throws IOException {
        final Huffman table = tables[id];
        if (table == null) throw new IOException("Missing Huffman table");
        return table;
    }

    // ---------- ビット ----------

    private void fill() {
        while (bitCount <= 24) {
            int b = 0;
            if (!markerHit && position < data.length) {
                b = data[position] & 0xFF;
                if (b == 0xFF) {
                    final int next = position + 1 < data.length ? data[position + 1] & 0xFF : 0xD9;
                    if (next == 0) {
                        position += 2;
                    } else {
                        // マーカー: ここで止まり、残りは 0 とする
                        markerHit = true;
                        b = 0;
                    }
                } else {
                    position++;
                }
            }
            bitBuffer |= b << (24 - bitCount);
            bitCount += 8;
        }
    }

    private int readBit() {
        if (bitCount < 1) fill();
        final int bit = bitBuffer >>> 31;
        bitBuffer <<= 1;
        bitCount--;
        return bit;
    }

    private int receive(int n) {
        if (n == 0) return 0;
        if (bitCount < n) fill();
        final int value = bitBuffer >>> (32 - n);
        bitBuffer <<= n;
        bitCount -= n;
        return value;
    }

    private static int extend(int value, int bits) {
        return value < (1 << (bits - 1)) ? value - (1 << bits) + 1 : value;
    }

    private int decodeHuffman(Huffman table) throws IOException {
        if (bitCount < 16) fill();
        final int entry = table.lookup[bitBuffer >>> 23];
        if (entry != 0) {
            final int length = (entry >> 16) & 0x1F;
            bitBuffer <<= length;
            bitCount -= length;
            return entry & 0xFF;
        }
        int code = 0;
        for (int length = 1; length <= 16; length++) {
            code = (code << 1) | readBit();
            if (table.maxCode[length] >= 0 && code <= table.maxCode[length] && code >= table.minCode[length]) {
                final int index = table.valPtr[length] + code - table.minCode[length];
                if (index < 0 || index >= table.values.length) throw new IOException("Bad Huffman code");
                return table.values[index];
            }
        }
        throw new IOException("Bad Huffman code");
    }

    // ---------- 画素にする ----------

    private Image finish(int scale, int colorTransform) throws IOException {
        if (components == null) throw new IOException("No JPEG frame");
        final int block = 8 / scale;
        final double[][] basis = basis(block);
        for (Component c : components) {
            final int[] quant = quantTables[c.quantTable];
            if (quant == null) throw new IOException("Missing quantization table");
            c.pixelWidth = c.blocksPerLine * block;
            c.pixelHeight = c.blocksPerColumn * block;
            c.pixels = new byte[c.pixelWidth * c.pixelHeight];
            final double[] coefficients = new double[64];
            final double[] temp = new double[64];
            final double[] out = new double[block * block];
            final int shift = precision == 12 ? 4 : 0;
            final double levelShift = precision == 12 ? 2048 : 128;
            for (int row = 0; row < c.blocksPerColumn; row++) {
                for (int column = 0; column < c.blocksPerLine; column++) {
                    final int offset = (row * c.blocksPerLine + column) * 64;
                    for (int i = 0; i < 64; i++) coefficients[i] = c.coefficients[offset + i] * quant[i];
                    idct(coefficients, temp, out, basis, block);
                    for (int y = 0; y < block; y++) {
                        final int base = (row * block + y) * c.pixelWidth + column * block;
                        for (int x = 0; x < block; x++) {
                            int value = (int) Math.round(out[y * block + x] + levelShift);
                            value >>= shift;
                            c.pixels[base + x] = (byte) (value < 0 ? 0 : value > 255 ? 255 : value);
                        }
                    }
                }
            }
            c.coefficients = null;
        }
        final int outWidth = (width + scale - 1) / scale;
        final int outHeight = (height + scale - 1) / scale;
        final int n = components.length;
        final byte[] samples = new byte[outWidth * outHeight * n];
        for (int i = 0; i < n; i++) upsample(components[i], samples, i, n, outWidth, outHeight);
        convert(samples, n, colorTransform);
        return new Image(outWidth, outHeight, n, samples, adobe, adobeTransform);
    }

    // IDCT の基底: basis[x][u] = C(u) cos((2x+1)uπ/2N) / 2。
    // N が 8 より小さいときは、低い周波数の N x N の係数だけで N 点の IDCT をする (8/N 画素ごとの平均に近い値になる)
    private static double[][] basis(int n) {
        final double[][] result = new double[n][n];
        for (int x = 0; x < n; x++) {
            for (int u = 0; u < n; u++) {
                final double cu = u == 0 ? Math.sqrt(0.5) : 1;
                result[x][u] = cu * Math.cos((2 * x + 1) * u * Math.PI / (2 * n)) / 2;
            }
        }
        return result;
    }

    private static void idct(double[] in, double[] temp, double[] out, double[][] basis, int n) {
        // 周波数の行ごとに、横の向きを戻す
        for (int v = 0; v < n; v++) {
            boolean zero = true;
            for (int u = 1; u < n; u++) {
                if (in[v * 8 + u] != 0) {
                    zero = false;
                    break;
                }
            }
            for (int x = 0; x < n; x++) {
                double sum;
                if (zero) {
                    sum = in[v * 8] * basis[x][0];
                } else {
                    sum = 0;
                    for (int u = 0; u < n; u++) sum += basis[x][u] * in[v * 8 + u];
                }
                temp[v * 8 + x] = sum;
            }
        }
        // 縦の向きを戻す
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                double sum = 0;
                for (int v = 0; v < n; v++) sum += basis[y][v] * temp[v * 8 + x];
                out[y * n + x] = sum;
            }
        }
    }

    // 成分の画素を画像の大きさに広げて並べる。間引かれた成分は、画素の中心をそろえた線形補間で広げる
    private void upsample(Component c, byte[] samples, int index, int n, int outWidth, int outHeight) {
        final double rx = (double) c.h / maxH, ry = (double) c.v / maxV;
        final int[] x0 = new int[outWidth], x1 = new int[outWidth];
        final double[] fx = new double[outWidth];
        for (int x = 0; x < outWidth; x++) {
            final double sx = Math.max(0, (x + 0.5) * rx - 0.5);
            x0[x] = Math.min(c.pixelWidth - 1, (int) sx);
            x1[x] = Math.min(c.pixelWidth - 1, x0[x] + 1);
            fx[x] = sx - (int) sx;
        }
        for (int y = 0; y < outHeight; y++) {
            final double sy = Math.max(0, (y + 0.5) * ry - 0.5);
            final int y0 = Math.min(c.pixelHeight - 1, (int) sy);
            final int y1 = Math.min(c.pixelHeight - 1, y0 + 1);
            final double fy = sy - (int) sy;
            final int row0 = y0 * c.pixelWidth, row1 = y1 * c.pixelWidth;
            for (int x = 0; x < outWidth; x++) {
                final int value;
                if (fx[x] == 0 && fy == 0) {
                    value = c.pixels[row0 + x0[x]] & 0xFF;
                } else {
                    final double top = (c.pixels[row0 + x0[x]] & 0xFF) * (1 - fx[x]) + (c.pixels[row0 + x1[x]] & 0xFF) * fx[x];
                    final double bottom = (c.pixels[row1 + x0[x]] & 0xFF) * (1 - fx[x]) + (c.pixels[row1 + x1[x]] & 0xFF) * fx[x];
                    value = (int) (top * (1 - fy) + bottom * fy + 0.5);
                }
                samples[(y * outWidth + x) * n + index] = (byte) value;
            }
        }
    }

    // YCbCr から RGB、YCCK から CMYK
    private void convert(byte[] samples, int n, int colorTransform) {
        boolean transform;
        if (colorTransform == NO_COLOR_TRANSFORM) {
            return;
        } else if (n == 3) {
            if (adobe) transform = adobeTransform != 0;
            else if (colorTransform >= 0) transform = colorTransform != 0;
            else transform = !(components[0].id == 'R' && components[1].id == 'G' && components[2].id == 'B');
        } else if (n == 4) {
            transform = adobe ? adobeTransform != 0 : colorTransform > 0;
        } else {
            return;
        }
        if (!transform) return;
        for (int p = 0; p + n <= samples.length; p += n) {
            final int y = samples[p] & 0xFF, cb = (samples[p + 1] & 0xFF) - 128, cr = (samples[p + 2] & 0xFF) - 128;
            final int r = clamp(y + 1.402 * cr), g = clamp(y - 0.344136 * cb - 0.714136 * cr), b = clamp(y + 1.772 * cb);
            if (n == 3) {
                samples[p] = (byte) r;
                samples[p + 1] = (byte) g;
                samples[p + 2] = (byte) b;
            } else {
                samples[p] = (byte) (255 - r);
                samples[p + 1] = (byte) (255 - g);
                samples[p + 2] = (byte) (255 - b);
            }
        }
    }

    private static int clamp(double value) {
        final int v = (int) Math.round(value);
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
