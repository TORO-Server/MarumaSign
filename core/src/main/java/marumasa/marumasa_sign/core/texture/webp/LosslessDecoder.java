package marumasa.marumasa_sign.core.texture.webp;

import java.io.IOException;
import java.util.Arrays;

/**
 * WebP の可逆圧縮 (VP8L、RFC 9649 3 節) を展開する。
 * 変換 (予測、色の相関、緑の引き算、色の番号) と、LZ77 の参照、色のキャッシュ、場所ごとに切り替わるハフマン符号に対応する。
 */
public final class LosslessDecoder {

    private static final int SIGNATURE = 0x2f;

    private static final int PREDICTOR_TRANSFORM = 0;
    private static final int COLOR_TRANSFORM = 1;
    private static final int SUBTRACT_GREEN = 2;
    private static final int COLOR_INDEXING = 3;

    private static final int LITERALS = 256;
    private static final int LENGTH_CODES = 24;
    private static final int DISTANCE_CODES = 40;
    private static final int MAX_CACHE_BITS = 11;

    // 符号長を表す符号の、符号長の並び順
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    // 近くの画素を指す距離の番号 (1〜120) と、(横, 縦) のずれ (RFC 9649 3.6.2.2.1)
    private static final int[] DISTANCE_MAP = {
            0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2,
            -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0,
            1, 3, -1, 3, 3, 1, -3, 1, 2, 3, -2, 3, 3, 2,
            -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1,
            3, 3, -3, 3, 2, 4, -2, 4, 4, 2, -4, 2, 0, 5,
            3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5,
            5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2, 4, 4,
            -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0,
            1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2,
            -6, 2, 4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6,
            6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5,
            -5, 5, 7, 1, -7, 1, 4, 6, -4, 6, 6, 4, -6, 4,
            2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7, 7, 3,
            -7, 3, 5, 6, -5, 6, 6, 5, -6, 5, 8, 0, 4, 7,
            -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6,
            8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
            -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6,
            8, 7,
    };

    // 1 つの画像で作るハフマン符号の表の大きさの合計の上限 (int の数)。壊れたデータで大量の表を作らせないため
    private static final long MAX_TABLE_FOOTPRINT = 16L << 20;
    // 展開する画像の画素数の上限 (8192 x 8192)。呼び出し側でも大きさを確かめるが、念のためここでも止める
    private static final long MAX_PIXELS = 8192L * 8192;

    private final LosslessBits bits;
    private long tableFootprint;

    private LosslessDecoder(byte[] data, int start, int end) {
        this.bits = new LosslessBits(data, start, end);
    }

    /**
     * VP8L のデータの先頭 (署名 1 バイトと、幅・高さ各 14 ビット) から、画像の幅と高さを読む。
     */
    public static int[] readSize(byte[] data, int offset, int length) throws IOException {
        if (length < 5) throw new IOException("Truncated VP8L header");
        final LosslessBits header = new LosslessBits(data, offset, offset + 5);
        return readHeader(header);
    }

    private static int[] readHeader(LosslessBits header) throws IOException {
        if (header.read(8) != SIGNATURE) throw new IOException("Invalid VP8L signature");
        final int width = header.read(14) + 1;
        final int height = header.read(14) + 1;
        // 透明度を使っているかの目安。展開には影響しない
        header.read(1);
        if (header.read(3) != 0) throw new IOException("Unknown VP8L version");
        return new int[]{width, height};
    }

    /**
     * VP8L のデータを展開し、ARGB のピクセルを左上から行ごとに並べて返す。
     */
    public static int[] decode(byte[] data, int offset, int length) throws IOException {
        final LosslessDecoder decoder = new LosslessDecoder(data, offset, offset + length);
        final int[] size = readHeader(decoder.bits);
        return decoder.decodeImage(size[0], size[1]);
    }

    /**
     * ヘッダーのない VP8L の画像 (ALPH の中身) を、幅と高さを指定して展開し、ARGB のピクセルを返す。
     */
    public static int[] decodeHeaderless(byte[] data, int offset, int length, int width, int height) throws IOException {
        return new LosslessDecoder(data, offset, offset + length).decodeImage(width, height);
    }

    // ---------- 画像 ----------

    // 変換 1 つ分
    private static final class Transform {
        final int type;
        // 変換をかける前 (元の画像側) の幅
        final int width;
        final int bits;
        final int[] data;

        Transform(int type, int width, int bits, int[] data) {
            this.type = type;
            this.width = width;
            this.bits = bits;
            this.data = data;
        }
    }

    private int[] decodeImage(int width, int height) throws IOException {
        if ((long) width * height > MAX_PIXELS) throw new IOException("VP8L image exceeds safety limits: " + width + "x" + height);
        // 変換 (RFC 9649 3.5)。各種類は 1 回まで
        final Transform[] transforms = new Transform[4];
        int transformCount = 0;
        int seen = 0;
        int codedWidth = width;
        while (bits.read(1) != 0) {
            final int type = bits.read(2);
            if ((seen & (1 << type)) != 0) throw new IOException("Duplicate VP8L transform");
            seen |= 1 << type;
            final Transform transform;
            switch (type) {
                case PREDICTOR_TRANSFORM:
                case COLOR_TRANSFORM: {
                    final int blockBits = bits.read(3) + 2;
                    final int[] blocks = decodeSubImage(divRoundUp(codedWidth, blockBits), divRoundUp(height, blockBits));
                    transform = new Transform(type, codedWidth, blockBits, blocks);
                    break;
                }
                case SUBTRACT_GREEN:
                    transform = new Transform(type, codedWidth, 0, null);
                    break;
                default: {
                    final int colors = bits.read(8) + 1;
                    final int packBits = colors > 16 ? 0 : colors > 4 ? 1 : colors > 2 ? 2 : 3;
                    final int[] palette = decodeSubImage(colors, 1);
                    // 色の表は前の色との差で入っている。番号が表の外なら透明な黒にするため、詰めた番号の範囲まで 0 で埋める
                    final int[] table = new int[Math.max(colors, 1 << (8 >> packBits))];
                    table[0] = palette[0];
                    for (int i = 1; i < colors; i++) table[i] = addPixels(palette[i], table[i - 1]);
                    transform = new Transform(type, codedWidth, packBits, table);
                    codedWidth = divRoundUp(codedWidth, packBits);
                    break;
                }
            }
            transforms[transformCount++] = transform;
            checkOverrun();
        }

        int[] pixels = decodeEntropyImage(codedWidth, height, true);

        // 読んだのと逆の順に、変換を戻す
        for (int i = transformCount - 1; i >= 0; i--) {
            final Transform t = transforms[i];
            switch (t.type) {
                case PREDICTOR_TRANSFORM:
                    inversePredictor(pixels, t.width, height, t.bits, t.data);
                    break;
                case COLOR_TRANSFORM:
                    inverseColorTransform(pixels, t.width, height, t.bits, t.data);
                    break;
                case SUBTRACT_GREEN:
                    addGreen(pixels);
                    break;
                default:
                    pixels = inverseColorIndexing(pixels, t.width, height, t.bits, t.data);
                    break;
            }
        }
        return pixels;
    }

    // 変換やハフマン符号の選び方を表す小さな画像。変換と場所ごとの符号は持たない
    private int[] decodeSubImage(int width, int height) throws IOException {
        return decodeEntropyImage(width, height, false);
    }

    private static int divRoundUp(int value, int bits) {
        return (value + (1 << bits) - 1) >> bits;
    }

    private void checkOverrun() throws IOException {
        if (bits.overrun()) throw new IOException("Truncated VP8L data");
    }

    // ---------- 符号化された画素 (RFC 9649 3.6, 3.7) ----------

    private int[] decodeEntropyImage(int width, int height, boolean main) throws IOException {
        int cacheBits = 0;
        if (bits.read(1) != 0) {
            cacheBits = bits.read(4);
            if (cacheBits < 1 || cacheBits > MAX_CACHE_BITS) throw new IOException("Invalid VP8L color cache size");
        }

        // 場所ごとのハフマン符号の選び方 (エントロピー画像)。使われている組だけを詰めた番号にする
        int prefixBits = 0;
        int prefixWidth = 0;
        int[] groupMap = null;
        int groupCount = 1;
        int[] usedIndex = null;
        if (main && bits.read(1) != 0) {
            prefixBits = bits.read(3) + 2;
            prefixWidth = divRoundUp(width, prefixBits);
            final int[] entropy = decodeSubImage(prefixWidth, divRoundUp(height, prefixBits));
            int max = 0;
            for (int i = 0; i < entropy.length; i++) {
                entropy[i] = (entropy[i] >> 8) & 0xffff;
                max = Math.max(max, entropy[i]);
            }
            groupCount = max + 1;
            usedIndex = new int[groupCount];
            Arrays.fill(usedIndex, -1);
            int used = 0;
            for (int i = 0; i < entropy.length; i++) {
                if (usedIndex[entropy[i]] < 0) usedIndex[entropy[i]] = used++;
                entropy[i] = usedIndex[entropy[i]];
            }
            groupMap = entropy;
        }

        // 組ごとに 5 つの符号があり、どの符号も少なくとも 4 ビットを使う。残りのデータで足りない数なら壊れている
        if (bits.remaining() < (long) groupCount * 20) throw new IOException("Truncated VP8L prefix codes");
        final int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
        final int[] alphabet = {LITERALS + LENGTH_CODES + cacheSize, LITERALS, LITERALS, LITERALS, DISTANCE_CODES};
        final int[] lengths = new int[alphabet[0]];
        int stored = 0;
        for (int i = 0; i < groupCount; i++) {
            if (usedIndex != null && usedIndex[i] >= 0) stored++;
        }
        final PrefixCode[][] groups = new PrefixCode[usedIndex == null ? 1 : stored][];
        for (int i = 0; i < groupCount; i++) {
            final PrefixCode[] group = new PrefixCode[5];
            for (int j = 0; j < 5; j++) {
                group[j] = readPrefixCode(alphabet[j], lengths);
                checkOverrun();
            }
            // エントロピー画像で使われない組は、正しいことだけを確かめて捨てる
            if (usedIndex == null) {
                groups[0] = group;
            } else if (usedIndex[i] >= 0) {
                groups[usedIndex[i]] = group;
                for (PrefixCode code : group) tableFootprint += code.footprint();
                if (tableFootprint > MAX_TABLE_FOOTPRINT) throw new IOException("VP8L prefix codes exceed safety limits");
            }
        }

        final int[] pixels = new int[width * height];
        decodePixels(pixels, width, height, groups, groupMap, prefixBits, prefixWidth, cacheBits);
        return pixels;
    }

    // ハフマン符号を 1 つ読む (RFC 9649 3.7.2.1)
    private PrefixCode readPrefixCode(int alphabetSize, int[] lengths) throws IOException {
        Arrays.fill(lengths, 0, alphabetSize, 0);
        if (bits.read(1) != 0) {
            // 記号が 1 つか 2 つだけの、短い書き方。範囲外の記号は無いものとして扱う
            final int symbols = bits.read(1) + 1;
            final int first = bits.read(bits.read(1) == 0 ? 1 : 8);
            if (first < alphabetSize) lengths[first] = 1;
            if (symbols == 2) {
                final int second = bits.read(8);
                if (second < alphabetSize) lengths[second] = 1;
            }
            return PrefixCode.build(lengths, alphabetSize);
        }

        // 符号長そのものも、ハフマン符号で書かれている
        final int[] codeLengthLengths = new int[CODE_LENGTH_ORDER.length];
        final int count = bits.read(4) + 4;
        for (int i = 0; i < count; i++) codeLengthLengths[CODE_LENGTH_ORDER[i]] = bits.read(3);
        final PrefixCode codeLengthCode = PrefixCode.build(codeLengthLengths, codeLengthLengths.length);

        int maxSymbol = alphabetSize;
        if (bits.read(1) != 0) {
            final int lengthBits = 2 + 2 * bits.read(3);
            maxSymbol = 2 + bits.read(lengthBits);
            if (maxSymbol > alphabetSize) throw new IOException("Invalid VP8L code length count");
        }
        int symbol = 0;
        int previous = 8;
        while (symbol < alphabetSize && maxSymbol-- > 0) {
            final int code = codeLengthCode.read(bits);
            if (code < 16) {
                lengths[symbol++] = code;
                if (code != 0) previous = code;
                continue;
            }
            // 16: 直前の 0 でない長さを 3〜6 回、17: 0 を 3〜10 回、18: 0 を 11〜138 回
            final int repeat;
            final int value;
            if (code == 16) {
                repeat = 3 + bits.read(2);
                value = previous;
            } else if (code == 17) {
                repeat = 3 + bits.read(3);
                value = 0;
            } else {
                repeat = 11 + bits.read(7);
                value = 0;
            }
            if (symbol + repeat > alphabetSize) throw new IOException("Invalid VP8L code length repeat");
            Arrays.fill(lengths, symbol, symbol + repeat, value);
            symbol += repeat;
        }
        checkOverrun();
        return PrefixCode.build(lengths, alphabetSize);
    }

    private void decodePixels(int[] pixels, int width, int height, PrefixCode[][] groups, int[] groupMap,
                              int prefixBits, int prefixWidth, int cacheBits) throws IOException {
        final int[] cache = cacheBits > 0 ? new int[1 << cacheBits] : null;
        final int cacheShift = 32 - cacheBits;
        final int total = pixels.length;
        // 色のキャッシュにまだ入れていない画素の位置
        int cached = 0;
        int position = 0;
        int x = 0;
        int y = 0;
        PrefixCode[] group = groups[0];
        int groupTile = -1;
        while (position < total) {
            if (groupMap != null) {
                final int tile = (y >> prefixBits) * prefixWidth + (x >> prefixBits);
                if (tile != groupTile) {
                    groupTile = tile;
                    group = groups[groupMap[tile]];
                }
            }
            final int code = group[0].read(bits);
            if (code < LITERALS) {
                final int red = group[1].read(bits);
                final int blue = group[2].read(bits);
                final int alpha = group[3].read(bits);
                pixels[position++] = alpha << 24 | red << 16 | code << 8 | blue;
                x++;
            } else if (code < LITERALS + LENGTH_CODES) {
                // LZ77 の参照: 長さと距離
                final int length = prefixValue(code - LITERALS);
                final int distanceCode = prefixValue(group[4].read(bits));
                final int distance = planeDistance(width, distanceCode);
                if (distance > position || length > total - position) throw new IOException("Invalid VP8L backward reference");
                for (int i = 0; i < length; i++) {
                    pixels[position + i] = pixels[position + i - distance];
                }
                position += length;
                x += length;
            } else {
                final int index = code - LITERALS - LENGTH_CODES;
                if (cache == null || index >= cache.length) throw new IOException("Invalid VP8L color cache index");
                while (cached < position) insertCache(cache, cacheShift, pixels[cached++]);
                pixels[position++] = cache[index];
                x++;
            }
            if (cache != null) {
                while (cached < position) insertCache(cache, cacheShift, pixels[cached++]);
            }
            if (x >= width) {
                y += x / width;
                x %= width;
                // 行が変わるごとに、データの終わりを超えていないかを確かめる (壊れたデータを早くやめる)
                checkOverrun();
            }
        }
        checkOverrun();
    }

    private static void insertCache(int[] cache, int shift, int argb) {
        cache[(0x1e35a7bd * argb) >>> shift] = argb;
    }

    // 長さと距離の、符号と追加ビットから値を作る
    private int prefixValue(int symbol) {
        if (symbol < 4) return symbol + 1;
        final int extraBits = (symbol - 2) >> 1;
        final int offset = (2 + (symbol & 1)) << extraBits;
        return offset + bits.read(extraBits) + 1;
    }

    private static int planeDistance(int width, int code) {
        if (code > 120) return code - 120;
        final int dx = DISTANCE_MAP[2 * (code - 1)];
        final int dy = DISTANCE_MAP[2 * (code - 1) + 1];
        final int distance = dx + dy * width;
        return distance < 1 ? 1 : distance;
    }

    // ---------- 変換を戻す (RFC 9649 3.5) ----------

    // 色の成分ごとに足す (各 8 ビットで桁あふれは捨てる)
    private static int addPixels(int a, int b) {
        final int alphaGreen = (a & 0xff00ff00) + (b & 0xff00ff00);
        final int redBlue = (a & 0x00ff00ff) + (b & 0x00ff00ff);
        return (alphaGreen & 0xff00ff00) | (redBlue & 0x00ff00ff);
    }

    // 成分ごとの平均 (切り捨て)
    private static int average2(int a, int b) {
        return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    private static int select(int left, int top, int topLeft) {
        int distanceLeft = 0;
        int distanceTop = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            final int l = (left >>> shift) & 0xff, t = (top >>> shift) & 0xff, tl = (topLeft >>> shift) & 0xff;
            final int estimate = l + t - tl;
            distanceLeft += Math.abs(estimate - l);
            distanceTop += Math.abs(estimate - t);
        }
        return distanceLeft < distanceTop ? left : top;
    }

    private static int clampAddSubtractFull(int a, int b, int c) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            result |= clamp255(((a >>> shift) & 0xff) + ((b >>> shift) & 0xff) - ((c >>> shift) & 0xff)) << shift;
        }
        return result;
    }

    private static int clampAddSubtractHalf(int a, int b) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            final int ca = (a >>> shift) & 0xff, cb = (b >>> shift) & 0xff;
            result |= clamp255(ca + (ca - cb) / 2) << shift;
        }
        return result;
    }

    private static int predict(int mode, int left, int top, int topLeft, int topRight) {
        switch (mode) {
            case 1:
                return left;
            case 2:
                return top;
            case 3:
                return topRight;
            case 4:
                return topLeft;
            case 5:
                return average2(average2(left, topRight), top);
            case 6:
                return average2(left, topLeft);
            case 7:
                return average2(left, top);
            case 8:
                return average2(topLeft, top);
            case 9:
                return average2(top, topRight);
            case 10:
                return average2(average2(left, topLeft), average2(top, topRight));
            case 11:
                return select(left, top, topLeft);
            case 12:
                return clampAddSubtractFull(left, top, topLeft);
            case 13:
                return clampAddSubtractHalf(average2(left, top), topLeft);
            default:
                // 0 (不透明な黒)。定義のない 14 と 15 も、参照デコーダと同じく 0 として扱う
                return 0xff000000;
        }
    }

    // 予測の変換。左上は黒、上の行は左、左の列は上から予測する。右端の右上は、同じ行の左端の画素になる
    private static void inversePredictor(int[] p, int width, int height, int blockBits, int[] modes) {
        p[0] = addPixels(p[0], 0xff000000);
        for (int x = 1; x < width; x++) p[x] = addPixels(p[x], p[x - 1]);
        final int tilesPerRow = divRoundUp(width, blockBits);
        for (int y = 1; y < height; y++) {
            final int row = y * width;
            p[row] = addPixels(p[row], p[row - width]);
            final int modeRow = (y >> blockBits) * tilesPerRow;
            for (int x = 1; x < width; x++) {
                final int i = row + x;
                final int mode = (modes[modeRow + (x >> blockBits)] >> 8) & 0xf;
                p[i] = addPixels(p[i], predict(mode, p[i - 1], p[i - width], p[i - width - 1], p[i - width + 1]));
            }
        }
    }

    // 符号付き 8 ビットどうしを掛けて 32 で割る
    private static int colorDelta(int multiplier, int color) {
        return ((byte) multiplier * (byte) color) >> 5;
    }

    private static void inverseColorTransform(int[] p, int width, int height, int blockBits, int[] elements) {
        final int tilesPerRow = divRoundUp(width, blockBits);
        for (int y = 0; y < height; y++) {
            final int elementRow = (y >> blockBits) * tilesPerRow;
            for (int x = 0; x < width; x++) {
                final int element = elements[elementRow + (x >> blockBits)];
                final int greenToRed = element & 0xff;
                final int greenToBlue = (element >> 8) & 0xff;
                final int redToBlue = (element >> 16) & 0xff;
                final int i = y * width + x;
                final int argb = p[i];
                final int green = (argb >> 8) & 0xff;
                final int red = (((argb >> 16) & 0xff) + colorDelta(greenToRed, green)) & 0xff;
                final int blue = ((argb & 0xff) + colorDelta(greenToBlue, green) + colorDelta(redToBlue, red)) & 0xff;
                p[i] = (argb & 0xff00ff00) | red << 16 | blue;
            }
        }
    }

    private static void addGreen(int[] p) {
        for (int i = 0; i < p.length; i++) {
            final int argb = p[i];
            final int green = (argb >> 8) & 0xff;
            final int redBlue = ((argb & 0x00ff00ff) + (green << 16 | green)) & 0x00ff00ff;
            p[i] = (argb & 0xff00ff00) | redBlue;
        }
    }

    // 色の番号を色に戻す。packBits が 1〜3 なら、1 つの画素の緑に 2、4、8 個の番号が下位ビットから詰めてある
    private static int[] inverseColorIndexing(int[] p, int width, int height, int packBits, int[] table) {
        if (packBits == 0) {
            for (int i = 0; i < p.length; i++) p[i] = table[(p[i] >> 8) & 0xff];
            return p;
        }
        final int packedWidth = divRoundUp(width, packBits);
        final int bitsPerIndex = 8 >> packBits;
        final int mask = (1 << bitsPerIndex) - 1;
        final int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int packed = (p[y * packedWidth + (x >> packBits)] >> 8) & 0xff;
                final int index = (packed >> ((x & ((1 << packBits) - 1)) * bitsPerIndex)) & mask;
                out[y * width + x] = table[index];
            }
        }
        return out;
    }
}
