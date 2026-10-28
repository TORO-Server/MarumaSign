package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.Strict;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * PDF のフォント (/Font のリソース)。文字列を文字に区切り、文字ごとの輪郭と送り幅を返す。
 * 輪郭と幅の単位は、文字の大きさ 1 のときの文字空間の単位 (グリフ空間の 1000 分の 1 をかけたもの)。
 * <p>
 * 文字は「文字の番号」(readCode が返す long) で指す。複合フォントでは同じ値でもバイト数が違えば別の文字
 * (&lt;20&gt; と &lt;0020&gt;) なので、番号には文字コードとバイト数の両方を入れる。
 */
public abstract class PdfFont {

    // 覚えておく輪郭の数の上限 (フォントごと)
    private static final int MAX_CACHED_PATHS = 8192;
    // 埋め込まれたフォントのデータの大きさの上限
    static final int MAX_FONT_FILE = 64 * 1024 * 1024;

    protected final PdfDict dict;
    private final Map<Long, GeneralPath> paths = new HashMap<>();

    protected PdfFont(PdfDict dict) {
        this.dict = dict;
    }

    /**
     * フォントの辞書から作る。
     */
    public static PdfFont create(PdfDict dict) throws IOException {
        final String subtype = dict.name("Subtype");
        if ("Type0".equals(subtype)) return new Type0Font(dict);
        if ("Type3".equals(subtype)) return new Type3Font(dict);
        // Type1、MMType1、TrueType と、/Subtype のない壊れたもの
        return new SimpleFont(dict);
    }

    /**
     * 文字列の position から 1 つの文字を読み、文字の番号を返す。length[0] に読んだバイト数を入れる。
     */
    public abstract long readCode(byte[] bytes, int position, int[] length);

    /**
     * 単語の間隔 (Tw) を足す文字か: 1 バイトの文字コード 32。
     */
    public boolean isWordSpace(long character) {
        return character == 32;
    }

    /**
     * 横書きの送り幅 (文字空間の単位)。
     */
    public abstract double width(long character);

    public boolean isVertical() {
        return false;
    }

    /**
     * 縦書きの送り w1 (文字空間の単位。下へ進むので、ふつうは負の数)。
     */
    public double verticalAdvance(long character) {
        return 0;
    }

    /**
     * 縦書きの位置の差 {vx, vy} (文字空間の単位)。グリフはこの分だけ左下にずらして描く。
     */
    public double[] verticalOrigin(long character) {
        return new double[]{0, 0};
    }

    /**
     * 文字の輪郭 (文字空間の単位)。何もないグリフは空の輪郭。返したものは変更しないこと (覚えておいて使い回す)。
     */
    public final synchronized GeneralPath path(long character) {
        final GeneralPath cached = paths.get(character);
        if (cached != null) return cached;
        GeneralPath path;
        try {
            path = createPath(character);
        } catch (RuntimeException e) {
            Strict.check(e);
            path = new GeneralPath();
        }
        if (path == null) path = new GeneralPath();
        if (paths.size() < MAX_CACHED_PATHS) paths.put(character, path);
        return path;
    }

    protected abstract GeneralPath createPath(long character);

    public boolean isType3() {
        return false;
    }

    // ---------- 共通の道具 ----------

    /**
     * FontDescriptor の Flags。
     */
    protected static int flags(PdfDict descriptor) {
        return descriptor == null ? 0 : descriptor.integer("Flags", 0);
    }

    protected static boolean isFixedPitch(int flags) {
        return (flags & 1) != 0;
    }

    protected static boolean isSerif(int flags) {
        return (flags & 2) != 0;
    }

    protected static boolean isSymbolic(int flags) {
        return (flags & 4) != 0;
    }

    protected static boolean isItalic(int flags) {
        return (flags & 64) != 0;
    }

    /**
     * 埋め込まれたフォントを読む。読めなければ null (代わりのフォントを使う)。
     */
    protected static FontProgram embedded(PdfDict descriptor, boolean cidFont) {
        if (descriptor == null) return null;
        try {
            final PdfStream type1 = descriptor.stream("FontFile");
            if (type1 != null) {
                final byte[] data = type1.decode(MAX_FONT_FILE);
                final int length1 = type1.dict.integer("Length1", 0);
                final int length2 = type1.dict.integer("Length2", 0);
                if (length1 > 0 && length2 > 0 && length1 + (long) length2 <= data.length && data.length > 0 && (data[0] & 0xFF) != 0x80) {
                    try {
                        return Type1Font.parse(java.util.Arrays.copyOf(data, length1),
                                java.util.Arrays.copyOfRange(data, length1, length1 + length2));
                    } catch (RuntimeException e) {
                        Strict.check(e);
                        // Length1 / Length2 が間違っていることがある。全体から探す
                    }
                }
                return Type1Font.parse(data);
            }
            final PdfStream trueType = descriptor.stream("FontFile2");
            if (trueType != null) return TrueTypeFont.parse(trueType.decode(MAX_FONT_FILE));
            final PdfStream other = descriptor.stream("FontFile3");
            if (other != null) {
                final byte[] data = other.decode(MAX_FONT_FILE);
                final String subtype = other.dict.name("Subtype");
                if ("OpenType".equals(subtype) || (data.length >= 4 && (startsWith(data, "OTTO") || startsWith(data, "true")
                        || (data[0] == 0 && data[1] == 1 && data[2] == 0 && data[3] == 0)))) {
                    return TrueTypeFont.parse(data);
                }
                return new CffFont(new FontBytes(data), null);
            }
        } catch (IOException | RuntimeException e) {
            Strict.check(e);
            // 壊れたフォント: 代わりを使う
        }
        return null;
    }

    private static boolean startsWith(byte[] data, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (data[i] != text.charAt(i)) return false;
        }
        return true;
    }

    // [a b c d e f] の 6 つの数。形が合わなければ null
    protected static double[] matrix(PdfArray array) {
        if (array == null || array.size() < 6) return null;
        final double[] values = array.numbers();
        if (values == null) return null;
        return java.util.Arrays.copyOf(values, 6);
    }
}
