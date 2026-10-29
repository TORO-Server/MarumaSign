package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.format.SvgDocument.Node;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * SVG を、Java 2D で絵にする。
 * 図形・パス・グループ・use・symbol・入れ子の svg・switch・変換・色・不透明度・線の種類・線形と放射の勾配・切り抜き・マスク・文字・
 * 埋め込み画像・style 属性と style 要素・色の行列のフィルター (feColorMatrix) に対応する。ほかのフィルター・パターン・マーカー・
 * アニメーション・外部の読み込みには対応しない (対応しないフィルターは、掛けずに描く。外部へは一切アクセスしない)。
 * SVG はどこかの URL から来るので、悪意のあるファイルとして扱う。要素の深さ・描く回数・パスの区間の数・文字の数・重ねる層の大きさ・
 * 埋め込み画像の大きさに上限を設け、描く手間を見積もって合計が予算を超えたら、そこで描くのをやめる (それまでに描いた絵を返す)。
 */
final class SvgDecoder {

    // 絵の長い辺は、小さい SVG は 1024 まで拡大し、大きいものは 2048 に収める
    private static final int TARGET_SIZE = 1024;
    private static final int MAX_SIZE = 2048;
    private static final int MAX_TEXT = 32 * 1024 * 1024;
    // 読んで描くまでの時間の上限 (これを過ぎたら、それまでに描いたものを返す)
    private static final long TIME_LIMIT = 2000000000L;
    // 要素を描く回数 (use で何度も描くものも数える) と、入れ子の深さの上限
    private static final int MAX_DRAWS = 200000;
    private static final int MAX_DRAW_DEPTH = 160;
    // 描く手間の予算。おおよそナノ秒 (Java 2D で塗る区間の数・横切る行の数・ピクセルの数から見積もる)
    private static final long MAX_WORK = 1000000000L;
    // 読んで覚えておくパスの区間の数の合計
    private static final int MAX_SEGMENTS = 2000000;
    // 不透明度・切り抜き・マスクのために重ねる層のピクセル数 (同時に使うものと、合計)
    private static final long MAX_LIVE_LAYER_PIXELS = 3L * MAX_SIZE * MAX_SIZE;
    private static final long MAX_LAYER_PIXELS = 16L * MAX_SIZE * MAX_SIZE;
    // 文字の数、フォントの種類、勾配の色の数、破線の区切りの数の上限
    private static final int MAX_GLYPHS = 20000;
    private static final int MAX_FONTS = 16;
    private static final int MAX_STOPS = 256;
    private static final int MAX_DASHES = 1000;
    // 埋め込み画像のデータの大きさと、展開するピクセル数の合計
    private static final int MAX_IMAGE_DATA = 24 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 4096L * 4096;
    // 線の太さは、絵の大きさの数倍までにする (それより太い線は、どのみち絵の全体を覆う)
    private static final double MAX_STROKE_PIXELS = 4.0 * MAX_SIZE;
    // 絵の上の座標がこれより遠い図形は、絵の周りで切り取ってから Java 2D に渡す
    private static final double FAR = 100000;
    // フォントの外形は、この大きさで作ってから拡大・縮小する (極端な大きさをフォントの仕組みに渡さない)
    private static final float FONT_UNITS = 1000;
    // 文字は、この数ずつまとめて塗る (Java 8 の Java 2D は、1 行に辺が多い図形を塗るのに、辺の数の 2 乗の時間がかかる)
    private static final int GLYPHS_PER_FILL = 64;
    // Java 8 の Java 2D (Pisces) は、1 行を横切る辺の数の 2 乗に比例して遅くなる。Java 9 からの Marlin は、ほぼ比例で済む
    private static final boolean QUADRATIC_RASTERIZER = System.getProperty("java.specification.version", "").startsWith("1.");

    private SvgDecoder() {
    }

    static boolean looksLikeSvg(byte[] h, int n) {
        final String head = new String(h, 0, Math.min(n, h.length), StandardCharsets.ISO_8859_1);
        final int svg = head.indexOf("<svg");
        if (svg < 0) return false;
        // XML として始まるもの。HTML の中に埋め込まれた SVG (ウェブページ) は対象にしない
        final String trimmed = head.replace("ï»¿", "").trim();
        final int html = head.toLowerCase(Locale.ROOT).indexOf("<html");
        return trimmed.startsWith("<") && (html < 0 || svg < html);
    }

    static boolean isGzip(byte[] h, int n) {
        return n >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B;
    }

    static DecodedImage decode(byte[] d) throws IOException {
        final long deadline = System.nanoTime() + TIME_LIMIT;
        byte[] data = d;
        if (isGzip(d, d.length)) data = inflate(d);
        if (data.length > MAX_TEXT) throw new IOException("SVG is too large");
        final SvgDocument document = SvgDocument.parse(data, deadline);
        if (!"svg".equals(document.root.name)) throw new IOException("Not an SVG image");
        return new Renderer(document, deadline).render();
    }

    private static byte[] inflate(byte[] d) throws IOException {
        final InputStream in = new GZIPInputStream(new ByteArrayInputStream(d));
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_TEXT) throw new IOException("SVG is too large");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---------- 数の読み取り ----------

    /**
     * 文字列の中の数を、前から順に読む。正規表現は使わず、どの文字も 1 度しか見ない。
     * 読めない数・無限大になる数は読めないものとして扱う (NaN を返し、位置は進めない)。
     */
    static final class Cursor {
        final String s;
        int p;

        Cursor(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return p >= s.length();
        }

        // 空白とカンマを飛ばす。まだ文字が残っていれば true
        boolean skipSeparators() {
            while (p < s.length() && (isSpace(s.charAt(p)) || s.charAt(p) == ',')) p++;
            return p < s.length();
        }

        void skipSpaces() {
            while (p < s.length() && isSpace(s.charAt(p))) p++;
        }

        char peek() {
            return s.charAt(p);
        }

        double number() {
            final int n = s.length();
            int i = p;
            final boolean negative = i < n && s.charAt(i) == '-';
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            // 桁を整数として集める。桁が多すぎれば、あとで文字列から読み直す
            long mantissa = 0;
            int digits = 0, scale = 0;
            boolean exact = true;
            while (i < n && isDigit(s.charAt(i))) {
                final int digit = s.charAt(i) - '0';
                if (mantissa < 100000000000000L) {
                    mantissa = mantissa * 10 + digit;
                } else {
                    scale++;
                    if (digit != 0) exact = false;
                }
                i++;
                digits++;
            }
            if (i < n && s.charAt(i) == '.') {
                i++;
                while (i < n && isDigit(s.charAt(i))) {
                    if (mantissa < 100000000000000L) {
                        mantissa = mantissa * 10 + (s.charAt(i) - '0');
                        scale--;
                    } else if (s.charAt(i) != '0') {
                        exact = false;
                    }
                    i++;
                    digits++;
                }
            }
            if (digits == 0) return Double.NaN;
            int exponent = 0;
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int j = i + 1;
                final boolean negativeExponent = j < n && s.charAt(j) == '-';
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                final int start = j;
                while (j < n && isDigit(s.charAt(j))) {
                    if (exponent < 100000) exponent = exponent * 10 + (s.charAt(j) - '0');
                    j++;
                }
                if (j > start) {
                    i = j;
                    if (negativeExponent) exponent = -exponent;
                }
            }
            final int power = scale + exponent;
            double value;
            if (exact && power >= -22 && power <= 22) {
                // 15 桁までの整数と、22 乗までの 10 の累乗は double で正確なので、1 度の丸めで正しい値になる
                value = power >= 0 ? mantissa * POWERS[power] : mantissa / POWERS[-power];
                if (negative) value = -value;
            } else {
                try {
                    value = Double.parseDouble(s.substring(p, i));
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
            if (Double.isInfinite(value) || Double.isNaN(value)) return Double.NaN;
            p = i;
            return value;
        }

        // 区切りを飛ばしてから数を読む
        double nextNumber() {
            skipSeparators();
            return number();
        }

        // 弧の旗 (0 か 1)。次の数と区切りなしでつながることがある。読めなければ -1
        int flag() {
            skipSeparators();
            if (p >= s.length()) return -1;
            final char c = s.charAt(p);
            if (c != '0' && c != '1') return -1;
            p++;
            return c - '0';
        }
    }

    private static final double[] POWERS = new double[23];

    static {
        POWERS[0] = 1;
        for (int i = 1; i < POWERS.length; i++) POWERS[i] = POWERS[i - 1] * 10;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    // 空白やカンマで区切った数の並び。読めない所があれば、そこまで (多くても max 個)
    static double[] numbers(String text, int max) {
        if (text == null) return new double[0];
        final Cursor in = new Cursor(text);
        double[] result = new double[8];
        int count = 0;
        while (count < max && in.skipSeparators()) {
            final double v = in.number();
            if (Double.isNaN(v)) break;
            if (count == result.length) result = Arrays.copyOf(result, Math.min(max, count * 2));
            result[count++] = v;
        }
        return Arrays.copyOf(result, count);
    }

    // transform 属性 (matrix、translate、scale、rotate、skewX、skewY の並び)。なければ null。書き方が間違っていれば全体を無視して null
    static AffineTransform transform(String text) {
        if (text == null) return null;
        final Cursor in = new Cursor(text);
        if (!in.skipSeparators()) return null;
        final AffineTransform result = new AffineTransform();
        final double[] a = new double[6];
        while (in.skipSeparators()) {
            final int start = in.p;
            while (!in.atEnd() && Character.isLetter(in.peek())) in.p++;
            final String name = text.substring(start, in.p);
            in.skipSpaces();
            if (in.atEnd() || in.peek() != '(') return null;
            in.p++;
            int count = 0;
            while (true) {
                in.skipSeparators();
                if (in.atEnd()) return null;
                if (in.peek() == ')') break;
                if (count == a.length) return null;
                final double v = in.number();
                if (Double.isNaN(v)) return null;
                a[count++] = v;
            }
            in.p++;
            switch (name) {
                case "matrix":
                    if (count != 6) return null;
                    result.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                    break;
                case "translate":
                    if (count != 1 && count != 2) return null;
                    result.translate(a[0], count > 1 ? a[1] : 0);
                    break;
                case "scale":
                    if (count != 1 && count != 2) return null;
                    result.scale(a[0], count > 1 ? a[1] : a[0]);
                    break;
                case "rotate":
                    if (count == 1) result.rotate(Math.toRadians(a[0]));
                    else if (count == 3) result.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    else return null;
                    break;
                case "skewX":
                    if (count != 1) return null;
                    result.concatenate(new AffineTransform(1, 0, Math.tan(Math.toRadians(a[0])), 1, 0, 0));
                    break;
                case "skewY":
                    if (count != 1) return null;
                    result.concatenate(new AffineTransform(1, Math.tan(Math.toRadians(a[0])), 0, 1, 0, 0));
                    break;
                default:
                    return null;
            }
        }
        return result;
    }

    static boolean isFinite(AffineTransform t) {
        return isFinite(t.getScaleX()) && isFinite(t.getShearX()) && isFinite(t.getTranslateX())
                && isFinite(t.getShearY()) && isFinite(t.getScaleY()) && isFinite(t.getTranslateY());
    }

    /**
     * 描くのに使える変換か。無限大や潰れた変換のほか、拡大率が極端に小さいか大きい変換も使わない。
     * Java 2D (Marlin) は、変換の成分の 2 乗で線の拡大率を求めるので、2 乗が 0 になる (アンダーフローする) ほど小さいと、
     * 破線の長さが 0 になって、破線を作る処理が終わらなくなる
     */
    static boolean usable(AffineTransform t) {
        if (!isFinite(t)) return false;
        final double sx = Math.hypot(t.getScaleX(), t.getShearY()), sy = Math.hypot(t.getShearX(), t.getScaleY());
        return sx > 1e-100 && sx < 1e100 && sy > 1e-100 && sy < 1e100 && Math.abs(t.getDeterminant()) > 1e-200;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    // ---------- 色 ----------

    // 色を ARGB にする。色として読めなければ null
    static Integer color(String spec, int current) {
        if (spec == null) return null;
        final String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 256) return null;
        if (s.equals("currentcolor")) return current;
        if (s.startsWith("#")) {
            final String hex = s.substring(1);
            try {
                if (hex.length() == 3 || hex.length() == 4) {
                    final int[] v = new int[4];
                    v[3] = 255;
                    for (int i = 0; i < hex.length(); i++) v[i] = Integer.parseInt(hex.substring(i, i + 1), 16) * 17;
                    return FormatUtil.argb(v[3], v[0], v[1], v[2]);
                }
                if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16);
                if (hex.length() == 8) {
                    final long v = Long.parseLong(hex, 16);
                    return (int) ((v & 0xFF) << 24 | v >> 8);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return null;
        }
        final int open = s.indexOf('(');
        if (open > 0 && s.endsWith(")")) {
            final String function = s.substring(0, open).trim();
            final String[] parts = s.substring(open + 1, s.length() - 1).trim().split("[,\\s/]+");
            try {
                if (function.equals("rgb") || function.equals("rgba")) {
                    final int[] c = new int[3];
                    for (int i = 0; i < 3; i++) {
                        final String p = parts[i].trim();
                        c[i] = p.endsWith("%") ? FormatUtil.clamp8((int) Math.round(Double.parseDouble(p.substring(0, p.length() - 1)) * 2.55))
                                : FormatUtil.clamp8((int) Math.round(Double.parseDouble(p)));
                    }
                    return FormatUtil.argb(parts.length > 3 ? alpha(parts[3]) : 255, c[0], c[1], c[2]);
                }
                if (function.equals("hsl") || function.equals("hsla")) {
                    final double hue = Double.parseDouble(parts[0].replace("deg", ""));
                    final double sat = Double.parseDouble(parts[1].replace("%", "")) / 100;
                    final double light = Double.parseDouble(parts[2].replace("%", "")) / 100;
                    if (!isFinite(hue) || !isFinite(sat) || !isFinite(light)) return null;
                    return (parts.length > 3 ? alpha(parts[3]) << 24 : 0xFF000000)
                            | (hsl(hue, Math.max(0, Math.min(1, sat)), Math.max(0, Math.min(1, light))) & 0xFFFFFF);
                }
            } catch (RuntimeException e) {
                return null;
            }
            return null;
        }
        if (s.equals("none")) return null;
        return NamedColors.getCss(s);
    }

    private static int alpha(String text) {
        final String p = text.trim();
        final double v = p.endsWith("%") ? Double.parseDouble(p.substring(0, p.length() - 1)) / 100 : Double.parseDouble(p);
        if (Double.isNaN(v)) throw new NumberFormatException("NaN");
        return FormatUtil.clamp8((int) Math.round(Math.max(0, Math.min(1, v)) * 255));
    }

    private static int hsl(double hue, double s, double l) {
        final double h = ((hue % 360) + 360) % 360 / 360;
        final double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        final double p = 2 * l - q;
        return FormatUtil.argb(255, channel(p, q, h + 1.0 / 3), channel(p, q, h), channel(p, q, h - 1.0 / 3));
    }

    private static int channel(double p, double q, double t) {
        double x = t;
        if (x < 0) x += 1;
        if (x > 1) x -= 1;
        final double v;
        if (x < 1.0 / 6) v = p + (q - p) * 6 * x;
        else if (x < 0.5) v = q;
        else if (x < 2.0 / 3) v = p + (q - p) * (2.0 / 3 - x) * 6;
        else v = p;
        return FormatUtil.clamp8((int) Math.round(v * 255));
    }

    // ---------- スタイル ----------

    private static final class Style implements Cloneable {
        String fill = "black";
        String stroke = "none";
        double strokeWidth = 1;
        double fillOpacity = 1, strokeOpacity = 1;
        double opacity = 1;
        // 層を作れなかったときに、不透明度を中の物に掛けて近づけるための値 (受け継ぐ)
        double alpha = 1;
        boolean evenOdd, clipEvenOdd;
        int cap = BasicStroke.CAP_BUTT, join = BasicStroke.JOIN_MITER;
        float miterLimit = 4;
        float[] dash;
        float dashOffset;
        int color = 0xFF000000;
        double fontSize = 16;
        // ブラウザと同じく、指定がなければ明朝体
        String fontFamily = "serif";
        boolean bold, italic;
        String textAnchor = "start";
        boolean visible = true;
        boolean hidden;
        String clipPath, mask, filter;
        String stopColor;
        double stopOpacity = 1;
        boolean overflowVisible;

        Style copy() {
            try {
                final Style s = (Style) clone();
                // 継承しない性質は元に戻す
                s.opacity = 1;
                s.hidden = false;
                s.clipPath = null;
                s.mask = null;
                s.filter = null;
                s.stopColor = null;
                s.stopOpacity = 1;
                s.overflowVisible = false;
                return s;
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------- 描画 ----------

    // 描く先の画像。不透明度などのための層は、絵の一部 (x, y から幅と高さ) だけを持つ
    private static final class Canvas {
        final BufferedImage image;
        final Graphics2D g;
        final int x, y, width, height;
        // 描ける範囲 (絵全体の座標)
        Rectangle clip;

        Canvas(int x, int y, int width, int height, int type) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            image = new BufferedImage(width, height, type);
            g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            clip = new Rectangle(x, y, width, height);
        }

        void setTransform(AffineTransform t) {
            final AffineTransform device = AffineTransform.getTranslateInstance(-x, -y);
            device.concatenate(t);
            g.setTransform(device);
        }

        void setClip(Rectangle r) {
            clip = r;
            g.setTransform(new AffineTransform());
            g.setClip(r.x - x, r.y - y, r.width, r.height);
        }
    }

    // パスなどの図形と、その区間の数
    private static final class Geometry {
        final Shape shape;
        final int segments;

        Geometry(Shape shape, int segments) {
            this.shape = shape;
            this.segments = segments;
        }
    }

    private static final Geometry NO_GEOMETRY = new Geometry(null, 0);

    // 勾配の色 (href でたどった先の stop を読んだもの)
    private static final class Stops {
        final float[] fractions;
        final int[] colors;

        Stops(float[] fractions, int[] colors) {
            this.fractions = fractions;
            this.colors = colors;
        }
    }

    private static final Stops NO_STOPS = new Stops(new float[0], new int[0]);
    private static final Object NO_IMAGE = new Object();

    // 文字の 1 続き (同じスタイルで、続けて並べるもの)
    private static final class Piece {
        String text;
        final Style style;
        double x = Double.NaN, y = Double.NaN, dx, dy;

        Piece(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    private static final Set<String> DRAWN = new HashSet<String>(Arrays.asList(
            "g", "a", "switch", "svg", "use", "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "image"));

    private static final class Renderer {
        private final Node root;
        private final Map<String, Node> ids;
        private final SvgStyleSheet styles;
        private final long deadline;
        private final FontRenderContext fontContext = new FontRenderContext(null, true, true);
        private final Map<String, Font> fonts = new HashMap<String, Font>();
        // use で描いている途中の要素と、使っている途中の切り抜き・マスク (循環を断つ)
        private final Map<Node, Boolean> active = new IdentityHashMap<Node, Boolean>();
        private final double[] coords = new double[6];
        private Canvas canvas;
        private int width, height;
        private double viewportWidth, viewportHeight;
        // 予算
        private int draws;
        private long work = MAX_WORK;
        private int segments = MAX_SEGMENTS;
        private long livePixels, layerPixels = MAX_LAYER_PIXELS;
        private int glyphs = MAX_GLYPHS;
        private long imagePixels = MAX_IMAGE_PIXELS;
        private boolean stopped;
        // use が入れ子の svg を指すときの、use の幅と高さ
        private Node viewportOwner;
        private double viewportOwnerWidth, viewportOwnerHeight;

        Renderer(SvgDocument document, long deadline) {
            this.root = document.root;
            this.ids = document.ids;
            this.styles = document.styles;
            this.deadline = deadline;
        }

        // 手間を予算から引く。予算か時間が尽きていれば false を返し、それ以降は何も描かない
        private boolean spend(double cost) {
            if (stopped) return false;
            if (!(cost <= work) || System.nanoTime() - deadline > 0) {
                stopped = true;
                return false;
            }
            work -= (long) cost;
            return true;
        }

        // ---------- スタイル ----------

        // 要素のスタイルを、親から引き継いだものに、属性・style 要素・style 属性の順に重ねて作る
        private Style styleOf(Node e, Style parent) {
            final Style s = parent.copy();
            if (e.properties == null) e.properties = styles.cascade(e);
            final String[] properties = e.properties;
            for (int i = 0; i < properties.length; i += 2) apply(s, properties[i], properties[i + 1]);
            return s;
        }

        private void apply(Style s, String name, String value) {
            if (value.equals("inherit")) return;
            try {
                switch (name) {
                    case "fill":
                        if (isPaint(value)) s.fill = value;
                        break;
                    case "stroke":
                        if (isPaint(value)) s.stroke = value;
                        break;
                    case "stroke-width": {
                        final double w = length(value, Math.sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2), s.fontSize);
                        if (w >= 0) s.strokeWidth = w;
                        break;
                    }
                    case "fill-opacity": s.fillOpacity = opacity(value, s.fillOpacity); break;
                    case "stroke-opacity": s.strokeOpacity = opacity(value, s.strokeOpacity); break;
                    case "opacity": s.opacity = opacity(value, 1); break;
                    case "fill-rule": s.evenOdd = value.equals("evenodd"); break;
                    case "clip-rule": s.clipEvenOdd = value.equals("evenodd"); break;
                    case "stroke-linecap":
                        s.cap = value.equals("round") ? BasicStroke.CAP_ROUND : (value.equals("square") ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT);
                        break;
                    case "stroke-linejoin":
                        s.join = value.equals("round") ? BasicStroke.JOIN_ROUND : (value.equals("bevel") ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER);
                        break;
                    case "stroke-miterlimit": {
                        final double v = new Cursor(value.trim()).number();
                        if (v >= 1) s.miterLimit = (float) Math.min(v, 1e6);
                        break;
                    }
                    case "stroke-dasharray": s.dash = dash(value, s); break;
                    case "stroke-dashoffset": {
                        final double v = length(value, viewportWidth, s.fontSize);
                        if (Math.abs(v) < 1e9) s.dashOffset = (float) v;
                        break;
                    }
                    case "color": {
                        final Integer c = color(value, s.color);
                        if (c != null) s.color = c;
                        break;
                    }
                    case "font-size": {
                        final double v = fontSize(value, s.fontSize);
                        if (v >= 0) s.fontSize = v;
                        break;
                    }
                    case "font-family": s.fontFamily = value; break;
                    case "font-weight": {
                        final double v = new Cursor(value).number();
                        s.bold = value.equals("bold") || value.equals("bolder") || v >= 600;
                        break;
                    }
                    case "font-style": s.italic = value.equals("italic") || value.equals("oblique"); break;
                    case "text-anchor": s.textAnchor = value; break;
                    case "display": s.hidden = value.equals("none"); break;
                    case "visibility": s.visible = value.equals("visible"); break;
                    case "clip-path": s.clipPath = value; break;
                    case "mask": s.mask = value; break;
                    case "filter": s.filter = value; break;
                    case "stop-color": s.stopColor = value; break;
                    case "stop-opacity": s.stopOpacity = opacity(value, 1); break;
                    case "overflow": s.overflowVisible = value.equals("visible") || value.equals("auto"); break;
                    default: break;
                }
            } catch (RuntimeException e) {
                // 値が読めない指定は無視する
            }
        }

        // 塗りの指定として読めるか (読めない指定は無視して、親から受け継いだものを使う)
        private boolean isPaint(String value) {
            final String v = value.trim();
            return v.equals("none") || v.startsWith("url(") || v.equalsIgnoreCase("currentColor") || color(v, 0) != null;
        }

        private double opacity(String value, double fallback) {
            final String v = value.trim();
            final double x = v.endsWith("%") ? new Cursor(v.substring(0, v.length() - 1)).number() / 100 : new Cursor(v).number();
            if (Double.isNaN(x)) return fallback;
            return Math.max(0, Math.min(1, x));
        }

        private double fontSize(String value, double parent) {
            switch (value.trim()) {
                case "xx-small": return 9;
                case "x-small": return 10;
                case "small": return 13;
                case "medium": return 16;
                case "large": return 18;
                case "x-large": return 24;
                case "xx-large": return 32;
                case "larger": return parent * 1.2;
                case "smaller": return parent / 1.2;
                default: return length(value, parent, parent);
            }
        }

        private float[] dash(String value, Style s) {
            if (value.trim().equals("none")) return null;
            final List<Float> list = new ArrayList<Float>();
            for (String part : value.trim().split("[\\s,]+")) {
                if (part.isEmpty()) continue;
                if (list.size() >= MAX_DASHES) return null;
                final double v = length(part, viewportWidth, s.fontSize);
                if (!(v >= 0) || v > 1e9) return null;
                list.add((float) v);
            }
            final float[] result = new float[list.size() % 2 == 0 ? list.size() : list.size() * 2];
            double sum = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] = list.get(i % list.size());
                sum += result[i];
            }
            return sum > 0 ? result : null;
        }

        // 長さを px にする。relative は % の基準。読めなければ NaN
        private double length(String value, double relative, double fontSize) {
            final Cursor in = new Cursor(value.trim());
            final double n = in.number();
            if (Double.isNaN(n)) return Double.NaN;
            final String unit = value.trim().substring(in.p).trim();
            final double v;
            switch (unit) {
                case "": case "px": v = n; break;
                case "pt": v = n * 4 / 3; break;
                case "pc": v = n * 16; break;
                case "mm": v = n * 96 / 25.4; break;
                case "cm": v = n * 96 / 2.54; break;
                case "in": v = n * 96; break;
                case "em": v = n * fontSize; break;
                case "ex": v = n * fontSize / 2; break;
                case "%": v = n * relative / 100; break;
                default: return Double.NaN;
            }
            return isFinite(v) ? v : Double.NaN;
        }

        // 長さの属性。なければ (読めなければ) fallback
        private double attr(Node e, String name, double relative, double fallback, Style s) {
            final String value = e.get(name);
            if (value == null) return fallback;
            final double v = length(value, relative, s.fontSize);
            return Double.isNaN(v) ? fallback : v;
        }

        private AffineTransform transformOf(Node e) {
            if (!e.transformRead) {
                e.transform = transform(e.get("transform"));
                e.transformRead = true;
            }
            return e.transform;
        }

        // url(#id) が指す要素。kind と違う種類なら null
        private Node reference(String spec, String kind) {
            final String v = spec.trim();
            if (!v.startsWith("url(")) return null;
            final int close = v.indexOf(')');
            String id = v.substring(4, close < 0 ? v.length() : close).trim();
            if (id.length() >= 2 && (id.startsWith("'") || id.startsWith("\""))) id = id.substring(1, id.length() - 1);
            if (!id.startsWith("#")) return null;
            final Node target = ids.get(id.substring(1));
            return target != null && (kind == null || kind.equals(target.name)) ? target : null;
        }

        private Node href(Node e) {
            final String h = e.get("href");
            if (h == null) return null;
            final String v = h.trim();
            return v.startsWith("#") ? ids.get(v.substring(1)) : null;
        }

        // requiredExtensions・requiredFeatures・systemLanguage
        private boolean conditionsPass(Node e) {
            // 拡張 (foreignObject の中の HTML など) には対応しない
            if (e.has("requiredExtensions")) return false;
            final String features = e.get("requiredFeatures");
            if (features != null && features.contains("#Extensibility")) return false;
            final String languages = e.get("systemLanguage");
            if (languages != null) {
                final String mine = Locale.getDefault().getLanguage();
                for (String language : languages.split(",")) {
                    final String l = language.trim().toLowerCase(Locale.ROOT);
                    final int dash = l.indexOf('-');
                    final String primary = dash >= 0 ? l.substring(0, dash) : l;
                    if (primary.equals("en") || primary.equals(mine)) return true;
                }
                return false;
            }
            return true;
        }

        // ---------- 全体 ----------

        DecodedImage render() throws IOException {
            double[] viewBox = numbers(root.get("viewBox"), 4);
            if (viewBox.length != 4 || !(viewBox[2] > 0) || !(viewBox[3] > 0)) viewBox = null;
            final Style base = new Style();
            double w = Double.NaN, h = Double.NaN;
            final String ws = root.get("width", ""), hs = root.get("height", "");
            if (!ws.isEmpty() && !ws.trim().endsWith("%")) w = length(ws, 0, 16);
            if (!hs.isEmpty() && !hs.trim().endsWith("%")) h = length(hs, 0, 16);
            if (Double.isNaN(w) && Double.isNaN(h)) {
                if (viewBox != null) {
                    w = viewBox[2];
                    h = viewBox[3];
                } else {
                    w = 300;
                    h = 150;
                }
            } else if (Double.isNaN(w)) {
                w = viewBox != null ? h * viewBox[2] / viewBox[3] : 300;
            } else if (Double.isNaN(h)) {
                h = viewBox != null ? w * viewBox[3] / viewBox[2] : 150;
            }
            if (!(w > 0) || !(h > 0) || !isFinite(w) || !isFinite(h)) throw new IOException("SVG has no size");

            final double longSide = Math.max(w, h);
            final double scale = longSide < TARGET_SIZE ? TARGET_SIZE / longSide : (longSide > MAX_SIZE ? MAX_SIZE / longSide : 1);
            width = (int) Math.max(1, Math.min(MAX_SIZE, Math.round(w * scale)));
            height = (int) Math.max(1, Math.min(MAX_SIZE, Math.round(h * scale)));
            FormatUtil.checkSize(width, height);

            canvas = new Canvas(0, 0, width, height, BufferedImage.TYPE_INT_ARGB);
            final AffineTransform ctm = AffineTransform.getScaleInstance(width / w, height / h);
            if (viewBox != null) {
                ctm.concatenate(viewBoxTransform(viewBox, w, h, root.get("preserveAspectRatio", "")));
                viewportWidth = viewBox[2];
                viewportHeight = viewBox[3];
            } else {
                viewportWidth = w;
                viewportHeight = h;
            }
            if (usable(ctm)) drawChildren(root, styleOf(root, base), ctm, 1);
            canvas.g.dispose();
            return FormatUtil.still(width, height, ArgbPixels.readDisplay(canvas.image));
        }

        // viewBox の範囲を、幅 w と高さ h の枠に合わせる変換
        private AffineTransform viewBoxTransform(double[] vb, double w, double h, String aspect) {
            final String[] parts = aspect.trim().split("\\s+");
            int k = 0;
            if (k < parts.length && parts[k].equals("defer")) k++;
            final String align = k < parts.length && !parts[k].isEmpty() ? parts[k] : "xMidYMid";
            final boolean slice = k + 1 < parts.length && parts[k + 1].equals("slice");
            double sx = w / vb[2], sy = h / vb[3];
            double tx = 0, ty = 0;
            if (!align.equals("none")) {
                final double s = slice ? Math.max(sx, sy) : Math.min(sx, sy);
                sx = s;
                sy = s;
                final double extraX = w - vb[2] * s, extraY = h - vb[3] * s;
                tx = align.contains("xMid") ? extraX / 2 : (align.contains("xMax") ? extraX : 0);
                ty = align.contains("YMid") ? extraY / 2 : (align.contains("YMax") ? extraY : 0);
            }
            final AffineTransform t = new AffineTransform();
            t.translate(tx, ty);
            t.scale(sx, sy);
            t.translate(-vb[0], -vb[1]);
            return t;
        }

        private void drawChildren(Node parent, Style style, AffineTransform ctm, int depth) {
            for (Object child : parent.children()) {
                if (stopped) return;
                if (child instanceof Node) draw((Node) child, style, ctm, depth);
            }
        }

        private void draw(Node e, Style parentStyle, AffineTransform ctm, int depth) {
            if (stopped || depth > MAX_DRAW_DEPTH) return;
            if (++draws > MAX_DRAWS) {
                stopped = true;
                return;
            }
            final String name = e.name;
            if (!DRAWN.contains(name) || !conditionsPass(e)) return;
            final Style style = styleOf(e, parentStyle);
            if (style.hidden) return;
            final AffineTransform t = new AffineTransform(ctm);
            final AffineTransform own = transformOf(e);
            if (own != null) t.concatenate(own);
            if (!usable(t)) return;

            final Node clip = style.clipPath != null ? reference(style.clipPath, "clipPath") : null;
            final Node mask = style.mask != null ? reference(style.mask, "mask") : null;
            final Node filter = style.filter != null ? reference(style.filter, "filter") : null;
            final List<float[]> matrices = filter != null ? colorMatrices(filter) : null;
            if (clip == null && mask == null && matrices == null && style.opacity >= 1) {
                paint(e, name, style, t, depth);
                return;
            }
            if (style.opacity <= 0) return;
            if (clip != null && active.containsKey(clip) || mask != null && active.containsKey(mask)) return;

            // 切り抜きとマスクの範囲の外には何も描かれないので、層はその範囲だけにする
            Rectangle bounds = canvas.clip;
            List<Object[]> clipItems = null;
            if (clip != null) {
                clipItems = clipItems(clip, e, name, style, t, depth);
                if (clipItems == null) return;
                final Rectangle rect = alignedRect(clipItems);
                bounds = bounds.intersection(rect != null ? rect : deviceBounds(clipItems));
                // ピクセルの境目に揃った四角形 1 つの切り抜きは、層を使わずに、描ける範囲を狭めるだけにする
                if (rect != null) clipItems = null;
            }
            double[] region = null;
            if (mask != null) {
                region = maskRegion(mask, e, name, style, depth);
                if (region == null) return;
                final Rectangle r = deviceRect(t, region[0], region[1], region[2], region[3]);
                if (r != null) bounds = bounds.intersection(r);
            }
            if (bounds.isEmpty()) return;

            // 塗りか線の片方だけの図形は、不透明度を色に掛ければ同じになるので、層を使わない
            final boolean foldable = isLeaf(name) && !(paints(style.fill) && paints(style.stroke) && style.strokeWidth > 0);
            final boolean group = clipItems != null || mask != null || matrices != null || style.opacity < 1 && !foldable;
            final Canvas layer = group ? newLayer(bounds) : null;
            if (layer == null) {
                // 層を作る予算がなければ、切り抜きやマスクのあるものは描かず、不透明度は中の物に掛ける
                if (clipItems != null || mask != null || matrices != null) return;
                style.alpha *= style.opacity;
                final Rectangle savedClip = canvas.clip;
                if (!bounds.equals(savedClip)) canvas.setClip(bounds);
                try {
                    paint(e, name, style, t, depth);
                } finally {
                    if (canvas.clip != savedClip) canvas.setClip(savedClip);
                }
                return;
            }
            final Canvas saved = canvas;
            canvas = layer;
            try {
                paint(e, name, style, t, depth);
            } finally {
                canvas = saved;
            }
            boolean visible = true;
            if (matrices != null) visible = filterLayer(layer, filter, matrices, e, name, style, t, depth);
            if (visible && clipItems != null) visible = clipLayer(layer, clipItems);
            if (visible && mask != null) visible = maskLayer(layer, mask, region, t, depth);
            if (visible) composite(layer, style.opacity);
            releaseLayer(layer);
        }

        private boolean isLeaf(String name) {
            return !name.equals("g") && !name.equals("a") && !name.equals("switch") && !name.equals("svg") && !name.equals("use");
        }

        private boolean paints(String spec) {
            return !spec.trim().equals("none");
        }

        private void paint(Node e, String name, Style style, AffineTransform t, int depth) {
            switch (name) {
                case "g": case "a":
                    drawChildren(e, style, t, depth + 1);
                    break;
                case "switch":
                    // 条件に合う最初の子だけを描く
                    for (Object child : e.children()) {
                        if (!(child instanceof Node)) continue;
                        if (++draws > MAX_DRAWS) {
                            stopped = true;
                            return;
                        }
                        final Node n = (Node) child;
                        if (!conditionsPass(n)) continue;
                        draw(n, style, t, depth + 1);
                        break;
                    }
                    break;
                case "svg": {
                    double w = attr(e, "width", viewportWidth, viewportWidth, style), h = attr(e, "height", viewportHeight, viewportHeight, style);
                    if (viewportOwner == e) {
                        w = viewportOwnerWidth;
                        h = viewportOwnerHeight;
                        viewportOwner = null;
                    }
                    drawViewport(e, style, t, attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style), w, h, depth);
                    break;
                }
                case "use":
                    drawUse(e, style, t, depth);
                    break;
                case "text":
                    drawText(e, style, t, depth);
                    break;
                case "image":
                    drawImage(e, style, t);
                    break;
                default: {
                    final Shape shape = shape(e, name, style);
                    if (shape != null) fillAndStroke(shape, style, t);
                    break;
                }
            }
        }

        // 入れ子の svg と、use で描く symbol。枠 (x, y, w, h) の中に viewBox を合わせ、枠の外は切り抜く
        private void drawViewport(Node e, Style style, AffineTransform t, double x, double y, double w, double h, int depth) {
            if (!(w > 0) || !(h > 0)) return;
            final AffineTransform inner = new AffineTransform(t);
            inner.translate(x, y);
            final double[] vb = numbers(e.get("viewBox"), 4);
            final boolean hasViewBox = vb.length == 4 && vb[2] > 0 && vb[3] > 0;
            if (hasViewBox) inner.concatenate(viewBoxTransform(vb, w, h, e.get("preserveAspectRatio", "")));
            if (!usable(inner)) return;
            final Rectangle savedClip = canvas.clip;
            if (!style.overflowVisible) {
                final Rectangle r = deviceRect(t, x, y, w, h);
                if (r != null) {
                    final Rectangle clip = r.intersection(canvas.clip);
                    if (clip.isEmpty()) return;
                    canvas.setClip(clip);
                }
            }
            final double savedWidth = viewportWidth, savedHeight = viewportHeight;
            viewportWidth = hasViewBox ? vb[2] : w;
            viewportHeight = hasViewBox ? vb[3] : h;
            try {
                drawChildren(e, style, inner, depth + 1);
            } finally {
                viewportWidth = savedWidth;
                viewportHeight = savedHeight;
                if (canvas.clip != savedClip) canvas.setClip(savedClip);
            }
        }

        // 変換が回転を含まなければ、四角形を絵の上の四角形 (外側に丸めたもの) にする。含むなら null
        private Rectangle deviceRect(AffineTransform t, double x, double y, double w, double h) {
            if (t.getShearX() != 0 || t.getShearY() != 0) return null;
            final double x0 = t.getScaleX() * x + t.getTranslateX(), x1 = t.getScaleX() * (x + w) + t.getTranslateX();
            final double y0 = t.getScaleY() * y + t.getTranslateY(), y1 = t.getScaleY() * (y + h) + t.getTranslateY();
            final double left = Math.max(Math.min(x0, x1), -1e6), right = Math.min(Math.max(x0, x1), 1e6);
            final double top = Math.max(Math.min(y0, y1), -1e6), bottom = Math.min(Math.max(y0, y1), 1e6);
            if (!(right > left) || !(bottom > top)) return new Rectangle();
            final int ix = (int) Math.floor(left + 1e-6), iy = (int) Math.floor(top + 1e-6);
            return new Rectangle(ix, iy, (int) Math.ceil(right - 1e-6) - ix, (int) Math.ceil(bottom - 1e-6) - iy);
        }

        private void drawUse(Node e, Style style, AffineTransform t, int depth) {
            final Node target = href(e);
            // 自分や先祖を指すもの、描いている途中のものを指すもの (循環) は描かない
            if (target == null || e.isInside(target) || active.containsKey(target)) return;
            final AffineTransform inner = new AffineTransform(t);
            inner.translate(attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style));
            active.put(target, Boolean.TRUE);
            try {
                if (target.name.equals("symbol")) {
                    if (!conditionsPass(target)) return;
                    final Style symbolStyle = styleOf(target, style);
                    if (symbolStyle.hidden) return;
                    drawViewport(target, symbolStyle, inner, 0, 0, attr(e, "width", viewportWidth, viewportWidth, style),
                            attr(e, "height", viewportHeight, viewportHeight, style), depth + 1);
                } else {
                    if (target.name.equals("svg") && (e.has("width") || e.has("height"))) {
                        viewportOwner = target;
                        viewportOwnerWidth = attr(e, "width", viewportWidth, attr(target, "width", viewportWidth, viewportWidth, style), style);
                        viewportOwnerHeight = attr(e, "height", viewportHeight, attr(target, "height", viewportHeight, viewportHeight, style), style);
                    }
                    draw(target, style, inner, depth + 1);
                    viewportOwner = null;
                }
            } finally {
                active.remove(target);
            }
        }

        // ---------- 層 ----------

        // 絵の上の範囲 r の層を作る。予算を超えるなら null
        private Canvas newLayer(Rectangle r) {
            if (r.isEmpty()) return null;
            final long pixels = (long) r.width * r.height;
            if (livePixels + pixels > MAX_LIVE_LAYER_PIXELS || pixels > layerPixels || !spend(pixels * 3.0)) return null;
            livePixels += pixels;
            layerPixels -= pixels;
            final Canvas layer = new Canvas(r.x, r.y, r.width, r.height, BufferedImage.TYPE_INT_ARGB_PRE);
            layer.clip = new Rectangle(r);
            return layer;
        }

        private void releaseLayer(Canvas layer) {
            layer.g.dispose();
            livePixels -= (long) layer.width * layer.height;
        }

        private void composite(Canvas layer, double opacity) {
            if (!spend(layer.width * (double) layer.height * 2)) return;
            final Graphics2D g = canvas.g;
            g.setTransform(new AffineTransform());
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity));
            g.drawImage(layer.image, layer.x - canvas.x, layer.y - canvas.y, null);
            g.setComposite(AlphaComposite.SrcOver);
        }

        // 層に、覆いの層の不透明度を掛ける
        private void multiply(Canvas layer, Canvas cover) {
            layer.g.setTransform(new AffineTransform());
            layer.g.setComposite(AlphaComposite.DstIn);
            layer.g.drawImage(cover.image, 0, 0, null);
            layer.g.setComposite(AlphaComposite.SrcOver);
        }

        /**
         * clipPath の中の形を、{形, 変換, スタイル} の並びにする (use は指す先の図形か文字、text は文字の外形にする)。
         * 何も残らなければ (中が空なら、何も描かれないので) null
         */
        private List<Object[]> clipItems(Node clip, Node e, String name, Style style, AffineTransform t, int depth) {
            final AffineTransform base = new AffineTransform(t);
            final AffineTransform clipTransform = transformOf(clip);
            if (clipTransform != null) base.concatenate(clipTransform);
            if ("objectBoundingBox".equals(clip.get("clipPathUnits", "").trim())) {
                final Rectangle2D box = bbox(e, name, style, depth);
                if (box == null || box.isEmpty()) return null;
                base.translate(box.getX(), box.getY());
                base.scale(box.getWidth(), box.getHeight());
            }
            if (!usable(base)) return null;
            final Style clipStyle = styleOf(clip, new Style());
            final List<Object[]> items = new ArrayList<Object[]>();
            active.put(clip, Boolean.TRUE);
            try {
                for (Object child : clip.children()) {
                    if (stopped) return null;
                    if (!(child instanceof Node)) continue;
                    if (++draws > MAX_DRAWS) {
                        stopped = true;
                        return null;
                    }
                    Node n = (Node) child;
                    if (!conditionsPass(n)) continue;
                    Style s = styleOf(n, clipStyle);
                    final AffineTransform ct = new AffineTransform(base);
                    final AffineTransform own = transformOf(n);
                    if (own != null) ct.concatenate(own);
                    if (n.name.equals("use")) {
                        // 切り抜きの中の use は、図形か文字を直接指すものだけ
                        final Node target = href(n);
                        if (target == null || s.hidden || target.isInside(clip)) continue;
                        ct.translate(attr(n, "x", viewportWidth, 0, s), attr(n, "y", viewportHeight, 0, s));
                        final AffineTransform targetTransform = transformOf(target);
                        if (targetTransform != null) ct.concatenate(targetTransform);
                        n = target;
                        s = styleOf(n, s);
                    }
                    if (s.hidden || !s.visible || !usable(ct)) continue;
                    if (n.name.equals("text")) {
                        for (Object[] run : layoutText(n, s, depth + 1)) items.add(new Object[]{run[0], ct, coverStyle((Style) run[1])});
                    } else if (isShape(n.name)) {
                        final Shape shape = shape(n, n.name, s);
                        if (shape != null) items.add(new Object[]{shape, ct, coverStyle(s)});
                    }
                }
            } finally {
                active.remove(clip);
            }
            return items.isEmpty() || stopped ? null : items;
        }

        // 切り抜きの形を塗るスタイル (不透明な黒で、clip-rule で塗る)
        private Style coverStyle(Style s) {
            s.fill = "black";
            s.fillOpacity = 1;
            s.alpha = 1;
            s.stroke = "none";
            s.evenOdd = s.clipEvenOdd;
            return s;
        }

        // 切り抜きが、ピクセルの境目に揃った四角形 1 つなら、その範囲。そうでなければ null
        private Rectangle alignedRect(List<Object[]> items) {
            if (items.size() != 1) return null;
            final Shape shape = (Shape) items.get(0)[0];
            final AffineTransform ct = (AffineTransform) items.get(0)[1];
            if (ct.getShearX() != 0 || ct.getShearY() != 0) return null;
            if (!(shape instanceof Rectangle2D) && !(shape instanceof Path2D && isRectangle((Path2D) shape))) return null;
            final Rectangle2D r = shape.getBounds2D();
            final double[] v = {
                    ct.getScaleX() * r.getMinX() + ct.getTranslateX(), ct.getScaleX() * r.getMaxX() + ct.getTranslateX(),
                    ct.getScaleY() * r.getMinY() + ct.getTranslateY(), ct.getScaleY() * r.getMaxY() + ct.getTranslateY()};
            for (double x : v) {
                if (!(Math.abs(x) < 1e6) || Math.abs(x - Math.rint(x)) > 1e-3) return null;
            }
            final int x0 = (int) Math.rint(Math.min(v[0], v[1])), x1 = (int) Math.rint(Math.max(v[0], v[1]));
            final int y0 = (int) Math.rint(Math.min(v[2], v[3])), y1 = (int) Math.rint(Math.max(v[2], v[3]));
            return new Rectangle(x0, y0, x1 - x0, y1 - y0);
        }

        // パスが、軸に沿った四角形 1 つか (cairo などが書き出す「m x y h w v h h -w z」の形)
        private boolean isRectangle(Path2D path) {
            final double[] xs = new double[6], ys = new double[6];
            int count = 0;
            boolean closed = false;
            for (final PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
                final int type = it.currentSegment(coords);
                if (type == PathIterator.SEG_CLOSE) {
                    closed = true;
                } else if (type == PathIterator.SEG_MOVETO) {
                    // 閉じたあとの、何も描かない moveTo は無視する
                    if (count > 0 && !closed) return false;
                    if (count == 0) {
                        xs[0] = coords[0];
                        ys[0] = coords[1];
                        count = 1;
                    }
                } else if (type == PathIterator.SEG_LINETO && !closed) {
                    if (count >= xs.length) return false;
                    xs[count] = coords[0];
                    ys[count] = coords[1];
                    count++;
                } else {
                    return false;
                }
            }
            if (count == 5 && xs[4] == xs[0] && ys[4] == ys[0]) count = 4;
            if (count != 4) return false;
            boolean horizontal = ys[0] == ys[1];
            for (int i = 0; i < 4; i++) {
                final int j = (i + 1) % 4;
                final boolean h = ys[i] == ys[j] && xs[i] != xs[j], vertical = xs[i] == xs[j] && ys[i] != ys[j];
                if (!(horizontal ? h : vertical)) return false;
                horizontal = !horizontal;
            }
            return true;
        }

        // 形の並びを描く、絵の上の範囲 (外側に丸める)
        private Rectangle deviceBounds(List<Object[]> items) {
            Rectangle2D union = null;
            for (Object[] item : items) {
                union = union(union, ((AffineTransform) item[1]).createTransformedShape(((Shape) item[0]).getBounds2D()).getBounds2D());
            }
            if (union == null) return new Rectangle();
            final double left = Math.max(union.getMinX(), -1e6), right = Math.min(union.getMaxX(), 1e6);
            final double top = Math.max(union.getMinY(), -1e6), bottom = Math.min(union.getMaxY(), 1e6);
            if (!(right > left) || !(bottom > top)) return new Rectangle();
            final int x = (int) Math.floor(left), y = (int) Math.floor(top);
            return new Rectangle(x, y, (int) Math.ceil(right) - x, (int) Math.ceil(bottom) - y);
        }

        /**
         * 層を clipPath の形で切り抜く (形の中だけを残す)。形は不透明な黒で別の層に塗って、その不透明度を掛ける。
         * Java 2D の切り抜き (Area) は、複雑な形で非常に遅くなるので使わない。何も残らなければ false
         */
        private boolean clipLayer(Canvas layer, List<Object[]> items) {
            final Canvas cover = newLayer(layer.clip);
            if (cover == null) return false;
            final Canvas saved = canvas;
            canvas = cover;
            try {
                for (Object[] item : items) {
                    if (stopped) break;
                    fillAndStroke((Shape) item[0], (Style) item[2], (AffineTransform) item[1]);
                }
            } finally {
                canvas = saved;
            }
            if (!stopped) multiply(layer, cover);
            releaseLayer(cover);
            return !stopped;
        }

        private boolean isShape(String name) {
            switch (name) {
                case "rect": case "circle": case "ellipse": case "line": case "polyline": case "polygon": case "path":
                    return true;
                default:
                    return false;
            }
        }

        /**
         * mask の範囲 (利用者座標の x, y, 幅, 高さ) と、中身を物の大きさに合わせるときの物の範囲 (x, y, 幅, 高さ)。
         * 既定の範囲は、物の大きさの -10% から 120%。何も描かれないなら null
         */
        private double[] maskRegion(Node mask, Node e, String name, Style style, int depth) {
            final boolean userUnits = "userSpaceOnUse".equals(mask.get("maskUnits", "").trim());
            final boolean contentBox = "objectBoundingBox".equals(mask.get("maskContentUnits", "").trim());
            Rectangle2D box = null;
            if (!userUnits || contentBox) {
                box = bbox(e, name, style, depth);
                if (box == null || box.isEmpty()) return null;
            }
            final double[] r = new double[8];
            if (userUnits) {
                r[0] = attr(mask, "x", viewportWidth, -0.1 * viewportWidth, style);
                r[1] = attr(mask, "y", viewportHeight, -0.1 * viewportHeight, style);
                r[2] = attr(mask, "width", viewportWidth, 1.2 * viewportWidth, style);
                r[3] = attr(mask, "height", viewportHeight, 1.2 * viewportHeight, style);
            } else {
                r[0] = box.getX() + fraction(mask.get("x"), -0.1) * box.getWidth();
                r[1] = box.getY() + fraction(mask.get("y"), -0.1) * box.getHeight();
                r[2] = fraction(mask.get("width"), 1.2) * box.getWidth();
                r[3] = fraction(mask.get("height"), 1.2) * box.getHeight();
            }
            if (!(r[2] > 0) || !(r[3] > 0)) return null;
            if (contentBox) {
                r[4] = box.getX();
                r[5] = box.getY();
                r[6] = box.getWidth();
                r[7] = box.getHeight();
            }
            return r;
        }

        // 層に mask を掛ける (中身を描いた明るさを不透明度にする)。何も残らなければ false
        private boolean maskLayer(Canvas layer, Node mask, double[] region, AffineTransform t, int depth) {
            final Canvas cover = newLayer(layer.clip);
            if (cover == null) return false;
            active.put(mask, Boolean.TRUE);
            final Canvas saved = canvas;
            canvas = cover;
            try {
                final Rectangle r = deviceRect(t, region[0], region[1], region[2], region[3]);
                if (r != null) {
                    final Rectangle clip = r.intersection(cover.clip);
                    if (clip.isEmpty()) return false;
                    cover.setClip(clip);
                }
                final AffineTransform content = new AffineTransform(t);
                if (region[6] > 0) {
                    content.translate(region[4], region[5]);
                    content.scale(region[6], region[7]);
                }
                if (!usable(content)) return false;
                drawChildren(mask, styleOf(mask, new Style()), content, depth + 1);
            } finally {
                canvas = saved;
                active.remove(mask);
            }
            if (!stopped && spend(cover.width * (double) cover.height * 4)) {
                // 明るさ (sRGB の係数) に不透明度を掛けたものを、覆いの不透明度にする
                final int[] pixels = ((DataBufferInt) cover.image.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < pixels.length; i++) {
                    final int p = pixels[i];
                    final int luminance = (int) Math.round(0.2125 * (p >> 16 & 0xFF) + 0.7154 * (p >> 8 & 0xFF) + 0.0721 * (p & 0xFF));
                    pixels[i] = Math.min(255, luminance) << 24;
                }
                multiply(layer, cover);
            }
            releaseLayer(cover);
            return !stopped;
        }

        /**
         * filter の中が feColorMatrix だけ (前の結果を順に受け取るもの) なら、その行列 (5 列 4 行の 20 個の数) の並び。
         * それ以外のフィルターには対応しないので null (フィルターを掛けずに描く)。要素ごとに 1 度だけ読む
         */
        @SuppressWarnings("unchecked")
        private List<float[]> colorMatrices(Node filter) {
            if (filter.resource == null) filter.resource = readColorMatrices(filter);
            return filter.resource instanceof List ? (List<float[]>) filter.resource : null;
        }

        private Object readColorMatrices(Node filter) {
            final List<float[]> result = new ArrayList<float[]>();
            String previous = "";
            for (Object child : filter.children()) {
                if (!(child instanceof Node)) continue;
                final Node primitive = (Node) child;
                if (!primitive.name.equals("feColorMatrix") || result.size() >= 8) return NO_IMAGE;
                final String in = primitive.get("in", "").trim();
                if (!(in.isEmpty() || (result.isEmpty() ? in.equals("SourceGraphic") : in.equals(previous)))) return NO_IMAGE;
                previous = primitive.get("result", "").trim();
                final float[] m = colorMatrix(primitive.get("type", "matrix").trim(), numbers(primitive.get("values"), 21));
                // 色を線形の空間で計算するかどうかを、21 番目に覚える (既定は線形)
                final String space = primitive.get("color-interpolation-filters", filter.get("color-interpolation-filters", "linearRGB")).trim();
                m[20] = space.equals("sRGB") ? 0 : 1;
                result.add(m);
            }
            return result.isEmpty() ? NO_IMAGE : result;
        }

        // feColorMatrix の行列。値が足りなければ、何もしない行列
        private float[] colorMatrix(String type, double[] v) {
            final float[] m = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0};
            switch (type) {
                case "matrix":
                    if (v.length == 20) {
                        for (int i = 0; i < 20; i++) m[i] = (float) v[i];
                    }
                    break;
                case "saturate": {
                    final double a = v.length > 0 ? v[0] : 1;
                    final double[] r = {
                            0.213 + 0.787 * a, 0.715 - 0.715 * a, 0.072 - 0.072 * a,
                            0.213 - 0.213 * a, 0.715 + 0.285 * a, 0.072 - 0.072 * a,
                            0.213 - 0.213 * a, 0.715 - 0.715 * a, 0.072 + 0.928 * a};
                    for (int i = 0; i < 9; i++) m[i / 3 * 5 + i % 3] = (float) r[i];
                    break;
                }
                case "hueRotate": {
                    final double a = Math.toRadians(v.length > 0 ? v[0] : 0), cos = Math.cos(a), sin = Math.sin(a);
                    final double[] r = {
                            0.213 + cos * 0.787 - sin * 0.213, 0.715 - cos * 0.715 - sin * 0.715, 0.072 - cos * 0.072 + sin * 0.928,
                            0.213 - cos * 0.213 + sin * 0.143, 0.715 + cos * 0.285 + sin * 0.140, 0.072 - cos * 0.072 - sin * 0.283,
                            0.213 - cos * 0.213 - sin * 0.787, 0.715 - cos * 0.715 + sin * 0.715, 0.072 + cos * 0.928 + sin * 0.072};
                    for (int i = 0; i < 9; i++) m[i / 3 * 5 + i % 3] = (float) r[i];
                    break;
                }
                case "luminanceToAlpha":
                    Arrays.fill(m, 0, 20, 0);
                    m[15] = 0.2125f;
                    m[16] = 0.7154f;
                    m[17] = 0.0721f;
                    break;
                default:
                    break;
            }
            return m;
        }

        // 層に色の行列を掛ける。透明な所に色を付ける行列なら、フィルターの範囲の外は透明にする。何も残らなければ false
        private boolean filterLayer(Canvas layer, Node filter, List<float[]> matrices, Node e, String name, Style style, AffineTransform t, int depth) {
            if (!spend(layer.width * (double) layer.height * 30)) return false;
            boolean fillsEmpty = false;
            for (float[] m : matrices) fillsEmpty |= m[19] > 0;
            Rectangle region = new Rectangle(layer.x, layer.y, layer.width, layer.height);
            if (fillsEmpty) {
                final double x, y, w, h;
                if ("userSpaceOnUse".equals(filter.get("filterUnits", "").trim())) {
                    x = attr(filter, "x", viewportWidth, -0.1 * viewportWidth, style);
                    y = attr(filter, "y", viewportHeight, -0.1 * viewportHeight, style);
                    w = attr(filter, "width", viewportWidth, 1.2 * viewportWidth, style);
                    h = attr(filter, "height", viewportHeight, 1.2 * viewportHeight, style);
                } else {
                    final Rectangle2D box = bbox(e, name, style, depth);
                    if (box == null || box.isEmpty()) return false;
                    x = box.getX() + fraction(filter.get("x"), -0.1) * box.getWidth();
                    y = box.getY() + fraction(filter.get("y"), -0.1) * box.getHeight();
                    w = fraction(filter.get("width"), 1.2) * box.getWidth();
                    h = fraction(filter.get("height"), 1.2) * box.getHeight();
                }
                if (!(w > 0) || !(h > 0)) return false;
                final Rectangle r = deviceRect(t, x, y, w, h);
                if (r != null) region = region.intersection(r);
            }
            final int[] pixels = ((DataBufferInt) layer.image.getRaster().getDataBuffer()).getData();
            final float[] c = new float[4];
            for (int py = 0; py < layer.height; py++) {
                final boolean rowInside = py + layer.y >= region.y && py + layer.y < region.y + region.height;
                for (int px = 0; px < layer.width; px++) {
                    final int i = py * layer.width + px;
                    if (!rowInside || px + layer.x < region.x || px + layer.x >= region.x + region.width) {
                        pixels[i] = 0;
                        continue;
                    }
                    final int p = pixels[i];
                    final int a = p >>> 24;
                    if (a == 0 && !fillsEmpty) continue;
                    // 層は不透明度を掛けた色なので、掛ける前の色に戻してから計算する
                    c[3] = a / 255f;
                    for (int k = 0; k < 3; k++) c[k] = a == 0 ? 0 : Math.min(1, (p >> (16 - 8 * k) & 0xFF) / (float) a);
                    for (float[] m : matrices) applyMatrix(m, c);
                    final int na = Math.round(c[3] * 255);
                    pixels[i] = na << 24 | Math.round(c[0] * na) << 16 | Math.round(c[1] * na) << 8 | Math.round(c[2] * na);
                }
            }
            return true;
        }

        private void applyMatrix(float[] m, float[] c) {
            final boolean linear = m[20] != 0;
            final float r = linear ? toLinear(c[0]) : c[0], g = linear ? toLinear(c[1]) : c[1], b = linear ? toLinear(c[2]) : c[2], a = c[3];
            for (int k = 0; k < 4; k++) {
                final float v = m[k * 5] * r + m[k * 5 + 1] * g + m[k * 5 + 2] * b + m[k * 5 + 3] * a + m[k * 5 + 4];
                c[k] = v > 0 ? (v < 1 ? v : 1) : 0;
            }
            if (linear) {
                for (int k = 0; k < 3; k++) c[k] = FormatUtil.srgb(c[k]) / 255f;
            }
        }

        private float toLinear(float v) {
            return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
        }

        // 物の大きさに対する割合 (0.5 や 50%)
        private double fraction(String value, double fallback) {
            if (value == null) return fallback;
            final String v = value.trim();
            final double n = new Cursor(v).number();
            if (Double.isNaN(n)) return fallback;
            return v.endsWith("%") ? n / 100 : n;
        }

        /**
         * 要素の利用者座標での大きさ (変換は含まない)。切り抜きやマスクの objectBoundingBox に使う。
         * グループは、子の大きさを合わせる (描く回数の予算を使う)。分からなければ null
         */
        private Rectangle2D bbox(Node e, String name, Style style, int depth) {
            if (stopped || depth > MAX_DRAW_DEPTH) return null;
            if (++draws > MAX_DRAWS) {
                stopped = true;
                return null;
            }
            if (isShape(name)) {
                final Shape shape = shape(e, name, style);
                return shape != null ? shape.getBounds2D() : null;
            }
            switch (name) {
                case "image": {
                    final double w = attr(e, "width", viewportWidth, 0, style), h = attr(e, "height", viewportHeight, 0, style);
                    return w > 0 && h > 0 ? new Rectangle2D.Double(attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style), w, h) : null;
                }
                case "text": {
                    Rectangle2D result = null;
                    for (Object[] run : layoutText(e, style, depth)) result = union(result, ((Shape) run[0]).getBounds2D());
                    return result;
                }
                case "use": {
                    final Node target = href(e);
                    if (target == null || e.isInside(target) || active.containsKey(target) || !DRAWN.contains(target.name)) return null;
                    active.put(target, Boolean.TRUE);
                    try {
                        final AffineTransform t = AffineTransform.getTranslateInstance(attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style));
                        final AffineTransform own = transformOf(target);
                        if (own != null) t.concatenate(own);
                        final Rectangle2D box = bbox(target, target.name, styleOf(target, style), depth + 1);
                        return box != null ? t.createTransformedShape(box).getBounds2D() : null;
                    } finally {
                        active.remove(target);
                    }
                }
                case "g": case "a": case "switch": case "svg": {
                    Rectangle2D result = null;
                    for (Object child : e.children()) {
                        if (!(child instanceof Node)) continue;
                        final Node n = (Node) child;
                        if (!DRAWN.contains(n.name) || !conditionsPass(n)) continue;
                        final Style s = styleOf(n, style);
                        if (s.hidden) continue;
                        final Rectangle2D box = bbox(n, n.name, s, depth + 1);
                        if (box == null) continue;
                        final AffineTransform own = transformOf(n);
                        result = union(result, own != null ? own.createTransformedShape(box).getBounds2D() : box);
                    }
                    return result;
                }
                default:
                    return null;
            }
        }

        private Rectangle2D union(Rectangle2D a, Rectangle2D b) {
            if (b == null || !isFinite(b.getX()) || !isFinite(b.getY()) || !isFinite(b.getWidth()) || !isFinite(b.getHeight())) return a;
            if (a == null) return b;
            return a.createUnion(b);
        }

        // ---------- 図形 ----------

        private Shape shape(Node e, String name, Style s) {
            switch (name) {
                case "rect": {
                    final double x = attr(e, "x", viewportWidth, 0, s), y = attr(e, "y", viewportHeight, 0, s);
                    final double w = attr(e, "width", viewportWidth, 0, s), h = attr(e, "height", viewportHeight, 0, s);
                    if (!(w > 0) || !(h > 0)) return null;
                    double rx = attr(e, "rx", viewportWidth, -1, s), ry = attr(e, "ry", viewportHeight, -1, s);
                    if (rx < 0 && ry < 0) return new Rectangle2D.Double(x, y, w, h);
                    if (rx < 0) rx = ry;
                    if (ry < 0) ry = rx;
                    if (rx == 0 || ry == 0) return new Rectangle2D.Double(x, y, w, h);
                    return new RoundRectangle2D.Double(x, y, w, h, Math.min(rx, w / 2) * 2, Math.min(ry, h / 2) * 2);
                }
                case "circle": {
                    final double r = attr(e, "r", Math.sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2), 0, s);
                    if (!(r > 0)) return null;
                    final double cx = attr(e, "cx", viewportWidth, 0, s), cy = attr(e, "cy", viewportHeight, 0, s);
                    return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
                }
                case "ellipse": {
                    double rx = attr(e, "rx", viewportWidth, -1, s), ry = attr(e, "ry", viewportHeight, -1, s);
                    // 片方だけなら、もう片方も同じ (SVG 2 の auto)
                    if (rx < 0) rx = ry;
                    if (ry < 0) ry = rx;
                    if (!(rx > 0) || !(ry > 0)) return null;
                    final double cx = attr(e, "cx", viewportWidth, 0, s), cy = attr(e, "cy", viewportHeight, 0, s);
                    return new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
                }
                case "line":
                    return new Line2D.Double(attr(e, "x1", viewportWidth, 0, s), attr(e, "y1", viewportHeight, 0, s),
                            attr(e, "x2", viewportWidth, 0, s), attr(e, "y2", viewportHeight, 0, s));
                case "polyline":
                case "polygon":
                case "path": {
                    // 区間の多い図形は、1 度だけ読んで覚えておく (use で何度も描かれても、読み直さない)
                    if (e.geometry == null) e.geometry = name.equals("path") ? path(e.get("d", "")) : polygon(e.get("points", ""), name.equals("polygon"));
                    final Geometry geometry = (Geometry) e.geometry;
                    if (geometry.shape instanceof Path2D) ((Path2D) geometry.shape).setWindingRule(s.evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                    return geometry.shape;
                }
                default:
                    return null;
            }
        }

        private Geometry polygon(String points, boolean close) {
            final double[] p = numbers(points, (int) Math.min(Integer.MAX_VALUE - 8, 2L * segments + 2));
            if (p.length < 4) return NO_GEOMETRY;
            final Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO, p.length / 2 + 1);
            path.moveTo(p[0], p[1]);
            int count = 1;
            for (int i = 2; i + 1 < p.length; i += 2) {
                path.lineTo(p[i], p[i + 1]);
                count++;
            }
            if (close) path.closePath();
            segments = Math.max(0, segments - count);
            return new Geometry(path, count);
        }

        // パスのデータ (d) を Path2D にする。間違いがあれば、そこまでの図形にする
        private Geometry path(String d) {
            final Path2D.Float path = new Path2D.Float();
            final Cursor in = new Cursor(d);
            double x = 0, y = 0, startX = 0, startY = 0, lastCx = 0, lastCy = 0;
            char command = 0, previous = 0;
            int count = 0;
            boolean empty = true;
            while (in.skipSeparators()) {
                if (count >= segments) {
                    stopped = true;
                    break;
                }
                if ((++count & 4095) == 0 && System.nanoTime() - deadline > 0) {
                    stopped = true;
                    break;
                }
                final char c = in.peek();
                if (Character.isLetter(c) && c != 'e' && c != 'E') {
                    command = c;
                    in.p++;
                } else if (command == 0) {
                    break;
                } else if (command == 'M') {
                    command = 'L';
                } else if (command == 'm') {
                    command = 'l';
                } else if (command == 'Z' || command == 'z') {
                    // Z のあとに数が続くのは間違い
                    break;
                }
                if (empty && command != 'M' && command != 'm') break;
                final boolean relative = Character.isLowerCase(command);
                final double ox = relative ? x : 0, oy = relative ? y : 0;
                boolean ok = true;
                switch (Character.toUpperCase(command)) {
                    case 'M': {
                        final double nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(nx) && !Double.isNaN(ny);
                        if (ok) {
                            endX = ox + nx;
                            endY = oy + ny;
                            path.moveTo(endX, endY);
                            startX = endX;
                            startY = endY;
                            empty = false;
                        }
                        break;
                    }
                    case 'L': case 'H': case 'V': {
                        final char upper = Character.toUpperCase(command);
                        final double nx = upper == 'V' ? 0 : in.nextNumber(), ny = upper == 'H' ? 0 : in.nextNumber();
                        ok = !Double.isNaN(nx) && !Double.isNaN(ny);
                        if (ok) {
                            end(upper == 'V' ? x : ox + nx, upper == 'H' ? y : oy + ny, x, y, startX, startY);
                            path.lineTo(endX, endY);
                        }
                        break;
                    }
                    case 'C': {
                        final double x1 = in.nextNumber(), y1 = in.nextNumber(), x2 = in.nextNumber(), y2 = in.nextNumber();
                        final double nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(x1 + y1 + x2 + y2 + nx + ny);
                        if (ok) {
                            end(ox + nx, oy + ny, x, y, startX, startY);
                            path.curveTo(ox + x1, oy + y1, ox + x2, oy + y2, endX, endY);
                            lastCx = ox + x2;
                            lastCy = oy + y2;
                        }
                        break;
                    }
                    case 'S': {
                        final boolean smooth = Character.toUpperCase(previous) == 'C' || Character.toUpperCase(previous) == 'S';
                        final double x1 = smooth ? 2 * x - lastCx : x, y1 = smooth ? 2 * y - lastCy : y;
                        final double x2 = in.nextNumber(), y2 = in.nextNumber(), nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(x2 + y2 + nx + ny);
                        if (ok) {
                            end(ox + nx, oy + ny, x, y, startX, startY);
                            path.curveTo(x1, y1, ox + x2, oy + y2, endX, endY);
                            lastCx = ox + x2;
                            lastCy = oy + y2;
                        }
                        break;
                    }
                    case 'Q': {
                        final double x1 = in.nextNumber(), y1 = in.nextNumber(), nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(x1 + y1 + nx + ny);
                        if (ok) {
                            end(ox + nx, oy + ny, x, y, startX, startY);
                            path.quadTo(ox + x1, oy + y1, endX, endY);
                            lastCx = ox + x1;
                            lastCy = oy + y1;
                        }
                        break;
                    }
                    case 'T': {
                        final boolean smooth = Character.toUpperCase(previous) == 'Q' || Character.toUpperCase(previous) == 'T';
                        final double x1 = smooth ? 2 * x - lastCx : x, y1 = smooth ? 2 * y - lastCy : y;
                        final double nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(nx + ny);
                        if (ok) {
                            end(ox + nx, oy + ny, x, y, startX, startY);
                            path.quadTo(x1, y1, endX, endY);
                            lastCx = x1;
                            lastCy = y1;
                        }
                        break;
                    }
                    case 'A': {
                        final double rx = in.nextNumber(), ry = in.nextNumber(), rotation = in.nextNumber();
                        final int large = in.flag(), sweep = in.flag();
                        final double nx = in.nextNumber(), ny = in.nextNumber();
                        ok = !Double.isNaN(rx + ry + rotation + nx + ny) && large >= 0 && sweep >= 0;
                        if (ok) {
                            end(ox + nx, oy + ny, x, y, startX, startY);
                            count += arc(path, x, y, rx, ry, rotation, large == 1, sweep == 1, endX, endY);
                        }
                        break;
                    }
                    case 'Z':
                        path.closePath();
                        endX = startX;
                        endY = startY;
                        break;
                    default:
                        ok = false;
                        break;
                }
                if (!ok || !isFinite(endX) || !isFinite(endY)) break;
                x = endX;
                y = endY;
                previous = command;
            }
            segments -= Math.min(count, segments);
            return new Geometry(path, count);
        }

        // パスの区間の終点 (end で決める)
        private double endX, endY;

        /**
         * 区間の終点を endX と endY にする。いまの部分パスの始点とのずれが、区間の長さに比べてごくわずかなら、始点に揃える。
         * 相対座標を足し合わせると、閉じる直前の点が始点からわずかにずれて、ごく短い辺ができる。Java 2D は、その短い辺の向きで
         * 角をつなぐので、尖った角が欠ける (cairo などは座標を丸めるので欠けない)
         */
        private void end(double x, double y, double fromX, double fromY, double startX, double startY) {
            final double tolerance = 1e-4 * Math.hypot(x - fromX, y - fromY);
            final boolean near = Math.abs(x - startX) <= tolerance && Math.abs(y - startY) <= tolerance;
            endX = near ? startX : x;
            endY = near ? startY : y;
        }

        /**
         * 端点で決まる弧 (SVG の A) を、3 次のベジエ曲線 (90 度ごと) にして加える。加えた区間の数を返す。
         * 半径が足りなければ広げる (SVG の仕様の F.6)。計算が無限大になるような極端な値なら、直線にする。
         */
        private int arc(Path2D path, double x0, double y0, double rxIn, double ryIn, double angle, boolean large, boolean sweep,
                        double x, double y) {
            if (x0 == x && y0 == y) return 0;
            double rx = Math.abs(rxIn), ry = Math.abs(ryIn);
            if (rx == 0 || ry == 0) {
                path.lineTo(x, y);
                return 1;
            }
            final double phi = Math.toRadians(angle % 360);
            final double cos = Math.cos(phi), sin = Math.sin(phi);
            final double hx = (x0 - x) / 2, hy = (y0 - y) / 2;
            final double x1 = cos * hx + sin * hy, y1 = -sin * hx + cos * hy;
            // 半径を 1 にした座標で考える
            double sx = x1 / rx, sy = y1 / ry;
            final double lambda = sx * sx + sy * sy;
            if (lambda > 1) {
                final double k = Math.sqrt(lambda);
                rx *= k;
                ry *= k;
                sx /= k;
                sy /= k;
            }
            final double d2 = sx * sx + sy * sy;
            double coefficient = d2 == 0 ? 0 : Math.sqrt(Math.max(0, (1 - d2) / d2));
            if (large == sweep) coefficient = -coefficient;
            final double ncx = coefficient * sy, ncy = -coefficient * sx;
            final double theta = Math.atan2(sy - ncy, sx - ncx);
            final double ux = sx - ncx, uy = sy - ncy, vx = -sx - ncx, vy = -sy - ncy;
            double delta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
            if (sweep && delta < 0) delta += 2 * Math.PI;
            else if (!sweep && delta > 0) delta -= 2 * Math.PI;
            final double mx = (x0 + x) / 2, my = (y0 + y) / 2;
            if (!isFinite(rx) || !isFinite(ry) || !isFinite(theta) || !isFinite(delta) || !isFinite(mx) || !isFinite(my)) {
                path.lineTo(x, y);
                return 1;
            }
            final int n = Math.max(1, Math.min(4, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9)));
            final double step = delta / n;
            final double k = 4.0 / 3 * Math.tan(step / 4);
            double a = theta;
            for (int i = 0; i < n; i++) {
                final double b = a + step;
                final double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
                final double[] c1 = arcPoint(ncx + ca - k * sa, ncy + sa + k * ca, rx, ry, cos, sin, mx, my);
                final double[] c2 = arcPoint(ncx + cb + k * sb, ncy + sb - k * cb, rx, ry, cos, sin, mx, my);
                final double[] end = i == n - 1 ? new double[]{x, y} : arcPoint(ncx + cb, ncy + sb, rx, ry, cos, sin, mx, my);
                path.curveTo(c1[0], c1[1], c2[0], c2[1], end[0], end[1]);
                a = b;
            }
            return n;
        }

        // 半径を 1 にした座標 (u, v) を、元の座標に戻す
        private double[] arcPoint(double u, double v, double rx, double ry, double cos, double sin, double mx, double my) {
            return new double[]{cos * rx * u - sin * ry * v + mx, sin * rx * u + cos * ry * v + my};
        }

        // ---------- 手間の見積もり ----------

        // 最後に measure した図形の、区間の数・絵の上で横切る行の数の合計・利用者座標での長さ・絵の上の範囲
        private int measuredSegments;
        private double crossings, userLength, minX, minY, maxX, maxY;

        /**
         * 図形を変換 t で描くときの手間を見積もるために、区間をたどる。
         * 曲線は制御点を結んだ折れ線で数える (実際の曲線より長いので、多めの見積もりになる)。座標が無限大になれば false
         */
        private boolean measure(Shape shape, AffineTransform t) {
            measuredSegments = 0;
            crossings = 0;
            userLength = 0;
            minX = minY = Double.POSITIVE_INFINITY;
            maxX = maxY = Double.NEGATIVE_INFINITY;
            final double top = canvas.clip.y, bottom = canvas.clip.y + canvas.clip.height;
            final double m00 = t.getScaleX(), m01 = t.getShearX(), m02 = t.getTranslateX();
            final double m10 = t.getShearY(), m11 = t.getScaleY(), m12 = t.getTranslateY();
            final double[] c = coords;
            double lastX = 0, lastY = 0, lastDeviceY = 0, startX = 0, startY = 0, startDeviceY = 0;
            for (final PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
                final int type = it.currentSegment(c);
                if ((++measuredSegments & 65535) == 0 && System.nanoTime() - deadline > 0) {
                    stopped = true;
                    return false;
                }
                if (type == PathIterator.SEG_CLOSE) {
                    userLength += Math.hypot(startX - lastX, startY - lastY);
                    crossings += span(lastDeviceY, startDeviceY, top, bottom);
                    lastX = startX;
                    lastY = startY;
                    lastDeviceY = startDeviceY;
                    continue;
                }
                final int points = type == PathIterator.SEG_CUBICTO ? 3 : (type == PathIterator.SEG_QUADTO ? 2 : 1);
                for (int i = 0; i < points; i++) {
                    final double ux = c[i * 2], uy = c[i * 2 + 1];
                    final double dx = m00 * ux + m01 * uy + m02, dy = m10 * ux + m11 * uy + m12;
                    if (!isFinite(dx) || !isFinite(dy)) return false;
                    if (dx < minX) minX = dx;
                    if (dx > maxX) maxX = dx;
                    if (dy < minY) minY = dy;
                    if (dy > maxY) maxY = dy;
                    if (type == PathIterator.SEG_MOVETO) {
                        startX = ux;
                        startY = uy;
                        startDeviceY = dy;
                    } else {
                        userLength += Math.hypot(ux - lastX, uy - lastY);
                        crossings += span(lastDeviceY, dy, top, bottom);
                    }
                    lastX = ux;
                    lastY = uy;
                    lastDeviceY = dy;
                }
            }
            return measuredSegments > 0 && isFinite(userLength);
        }

        // a から b までのうち、top から bottom の間にある長さ
        private double span(double a, double b, double top, double bottom) {
            final double low = Math.max(Math.min(a, b), top), high = Math.min(Math.max(a, b), bottom);
            return high > low ? high - low : 0;
        }

        // measure した範囲を margin だけ広げて、描ける範囲と重なる面積
        private double area(double margin) {
            final Rectangle clip = canvas.clip;
            final double w = Math.min(maxX + margin, clip.x + clip.width) - Math.max(minX - margin, clip.x);
            final double h = Math.min(maxY + margin, clip.y + clip.height) - Math.max(minY - margin, clip.y);
            return w > 0 && h > 0 ? w * h : 0;
        }

        /**
         * 塗る手間。区間ごとの手間と、絵の行を横切るごとの手間と、ピクセルごとの手間を足す。
         * 行を横切る手間は、1 行に辺がたくさん重なるほど (並べ替えのために) 大きくなる。margin は、measure した範囲を広げる幅
         */
        private double rasterCost(int segmentCount, double rowCrossings, double pixels, Paint paint, double margin) {
            final Rectangle clip = canvas.clip;
            final double rows = Math.max(1, Math.min(maxY + margin, clip.y + clip.height) - Math.max(minY - margin, clip.y));
            final double density = rowCrossings / rows;
            double perCrossing = 60 + 20 * Math.log(1 + density) / Math.log(2);
            if (QUADRATIC_RASTERIZER) perCrossing += 4 * density;
            // 勾配は、色の数が多いと 1 ピクセルごとに区間を探すので遅い
            final double perPixel = paint instanceof MultipleGradientPaint ? 8 + ((MultipleGradientPaint) paint).getFractions().length : 3;
            return 8000 + 100.0 * segmentCount + perCrossing * rowCrossings + pixels * perPixel;
        }

        // measure した範囲を margin だけ広げたものが、描ける範囲に掛かるか
        private boolean touches(double margin) {
            final Rectangle clip = canvas.clip;
            return maxX + margin > clip.x && minX - margin < clip.x + clip.width && maxY + margin > clip.y && minY - margin < clip.y + clip.height;
        }

        // ---------- 塗りと線 ----------

        private void fillAndStroke(Shape shape, Style s, AffineTransform t) {
            if (!s.visible || stopped || !usable(t)) return;
            final Paint fill = paint(s.fill, s, shape, s.fillOpacity * s.alpha);
            Paint stroke = null;
            if (s.strokeWidth > 0) stroke = paint(s.stroke, s, shape, s.strokeOpacity * s.alpha);
            if (fill == null && stroke == null) return;
            if (!measure(shape, t)) return;
            final Graphics2D g = canvas.g;
            // 描ける範囲に掛からない図形は、Java 2D に渡さない (範囲の外の辺も、Java 2D は処理する)
            if (fill != null && touches(1)) {
                if (shape instanceof Path2D) ((Path2D) shape).setWindingRule(s.evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                if (!spend(rasterCost(measuredSegments, crossings, area(0), fill, 0))) return;
                final Shape near = nearPart(shape, t, 2, false, s.evenOdd);
                if (near != null) {
                    canvas.setTransform(t);
                    g.setPaint(fill);
                    g.fill(near);
                }
            }
            if (stroke != null) {
                // 線の太さは、絵の上で大きすぎないようにする
                final double scale = Math.max(Math.hypot(t.getScaleX(), t.getShearY()), Math.hypot(t.getShearX(), t.getScaleY()));
                final double width = Math.min(s.strokeWidth, MAX_STROKE_PIXELS / scale);
                final double deviceWidth = width * scale;
                // 角の尖り (マイター) は、線の太さの半分の miterLimit 倍まで伸びる
                if (!touches(deviceWidth * Math.max(s.miterLimit, 1.5) + 1)) return;
                float[] dash = s.dash;
                double dashes = 0;
                if (dash != null) {
                    double period = 0;
                    for (float v : dash) period += v;
                    dashes = userLength / period * dash.length;
                    // 区切りが多すぎる破線は、普通の線にする (細かい破線は、遠目には線と同じ)
                    if (!(dashes < 200000) || !(dashes < work / 1000.0)) {
                        dash = null;
                        dashes = 0;
                    }
                }
                final double pieces = measuredSegments + dashes;
                final double joins = s.join == BasicStroke.JOIN_ROUND || s.cap == BasicStroke.CAP_ROUND ? 1500 : 400;
                final double rows = Math.min(deviceWidth, canvas.clip.height);
                final double cost = joins * pieces + rasterCost(0, 2 * crossings + 2 * pieces * rows, area(deviceWidth), stroke, deviceWidth);
                if (!spend(cost)) return;
                float offset = 0;
                if (dash != null) {
                    double period = 0;
                    for (float v : dash) period += v;
                    // Java 2D は負の位置を受け付けないので、周期の中に収める
                    offset = (float) (((s.dashOffset % period) + period) % period);
                }
                // 遠くを切り取ると破線の位置がずれるので、遠い図形の破線は普通の線にする
                final Shape near = nearPart(shape, t, deviceWidth * Math.max(s.miterLimit, 1.5) + 2, true, false);
                if (near == null) return;
                canvas.setTransform(t);
                g.setPaint(stroke);
                g.setStroke(new BasicStroke((float) width, s.cap, s.join, s.miterLimit, near == shape ? dash : null, offset));
                g.draw(near);
            }
        }

        /**
         * measure した図形が絵から遠くまで広がっていれば、絵の周り (描ける範囲を margin だけ広げた枠) で切り取った図形にする。
         * Java 8 の Java 2D (Pisces) は、極端に大きな曲線を細かく分けるのに終わらないほどの時間がかかり、切り取りもしない。
         * 曲線は、枠に掛かる部分だけを細かく分けて直線にする (枠に掛からない部分は、弦に置き換えても枠の中の塗りは変わらない)。
         * そのうえで、塗るものは部分パスごとに枠の中の多角形に (Sutherland-Hodgman)、線は区間ごとに枠の中に切り取る。
         * 近くにあればそのまま返し、何も残らないか分けすぎるときは null
         */
        private Shape nearPart(Shape shape, AffineTransform t, double margin, boolean stroke, boolean evenOdd) {
            if (minX >= -FAR && maxX <= FAR && minY >= -FAR && maxY <= FAR) return shape;
            final AffineTransform inverse;
            try {
                inverse = t.createInverse();
            } catch (NoninvertibleTransformException e) {
                return null;
            }
            final Rectangle clip = canvas.clip;
            final double[] window = {clip.x - margin, clip.y - margin, clip.x + clip.width + margin, clip.y + clip.height + margin};
            final Path2D.Double out = new Path2D.Double(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            final List<double[]> polygon = new ArrayList<double[]>();
            final double[] c = coords;
            double startX = 0, startY = 0;
            for (final PathIterator it = shape.getPathIterator(t); !it.isDone(); it.next()) {
                if (polygon.size() > 200000) return null;
                final int type = it.currentSegment(c);
                final double[] last = polygon.isEmpty() ? new double[]{startX, startY} : polygon.get(polygon.size() - 1);
                switch (type) {
                    case PathIterator.SEG_MOVETO:
                        addSubpath(out, polygon, false, stroke, window);
                        polygon.clear();
                        startX = c[0];
                        startY = c[1];
                        polygon.add(new double[]{c[0], c[1]});
                        break;
                    case PathIterator.SEG_LINETO:
                        if (polygon.isEmpty()) polygon.add(last);
                        polygon.add(new double[]{c[0], c[1]});
                        break;
                    case PathIterator.SEG_QUADTO:
                        if (polygon.isEmpty()) polygon.add(last);
                        cubic(polygon, last[0], last[1], last[0] + (c[0] - last[0]) * 2 / 3, last[1] + (c[1] - last[1]) * 2 / 3,
                                c[2] + (c[0] - c[2]) * 2 / 3, c[3] + (c[1] - c[3]) * 2 / 3, c[2], c[3], window, 0);
                        break;
                    case PathIterator.SEG_CUBICTO:
                        if (polygon.isEmpty()) polygon.add(last);
                        cubic(polygon, last[0], last[1], c[0], c[1], c[2], c[3], c[4], c[5], window, 0);
                        break;
                    default:
                        // 閉じたあとは、部分パスの始点から続ける
                        addSubpath(out, polygon, true, stroke, window);
                        polygon.clear();
                        break;
                }
            }
            addSubpath(out, polygon, false, stroke, window);
            if (out.getCurrentPoint() == null) return null;
            out.transform(inverse);
            return out;
        }

        // 3 次の曲線を直線に分けて、終点までの点を加える。枠に掛からない部分と、平らになった部分は分けない
        private void cubic(List<double[]> points, double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3,
                           double[] window, int depth) {
            final double left = Math.min(Math.min(x0, x1), Math.min(x2, x3)), right = Math.max(Math.max(x0, x1), Math.max(x2, x3));
            final double top = Math.min(Math.min(y0, y1), Math.min(y2, y3)), bottom = Math.max(Math.max(y0, y1), Math.max(y2, y3));
            final boolean outside = right < window[0] || left > window[2] || bottom < window[1] || top > window[3];
            if (outside || depth >= 48 || points.size() > 200000 || flat(x0, y0, x1, y1, x2, y2, x3, y3)) {
                points.add(new double[]{x3, y3});
                return;
            }
            // 半分に分ける (de Casteljau)
            final double ax = (x0 + x1) / 2, ay = (y0 + y1) / 2, bx = (x1 + x2) / 2, by = (y1 + y2) / 2, cx = (x2 + x3) / 2, cy = (y2 + y3) / 2;
            final double dx = (ax + bx) / 2, dy = (ay + by) / 2, ex = (bx + cx) / 2, ey = (by + cy) / 2;
            final double mx = (dx + ex) / 2, my = (dy + ey) / 2;
            cubic(points, x0, y0, ax, ay, dx, dy, mx, my, window, depth + 1);
            cubic(points, mx, my, ex, ey, cx, cy, x3, y3, window, depth + 1);
        }

        // 制御点が、始点と終点を結ぶ線から 0.1 ピクセル以内にあるか
        private boolean flat(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            final double dx = x3 - x0, dy = y3 - y0;
            final double length = Math.hypot(dx, dy);
            if (length < 1e-9) return Math.hypot(x1 - x0, y1 - y0) < 0.1 && Math.hypot(x2 - x0, y2 - y0) < 0.1;
            final double d1 = Math.abs((x1 - x0) * dy - (y1 - y0) * dx) / length, d2 = Math.abs((x2 - x0) * dy - (y2 - y0) * dx) / length;
            return d1 < 0.1 && d2 < 0.1;
        }

        // 直線に分けた部分パスを、枠で切り取って加える。枠の中に収まっている部分パスは、そのまま加える (閉じた線の角を保つ)
        private void addSubpath(Path2D out, List<double[]> points, boolean closed, boolean stroke, double[] window) {
            if (points.size() < 2) return;
            boolean inside = true;
            for (double[] point : points) {
                if (point[0] < window[0] || point[0] > window[2] || point[1] < window[1] || point[1] > window[3]) {
                    inside = false;
                    break;
                }
            }
            if (inside || !stroke) {
                if (!inside) {
                    addClippedPolygon(out, points, window);
                    return;
                }
                out.moveTo(points.get(0)[0], points.get(0)[1]);
                for (int i = 1; i < points.size(); i++) out.lineTo(points.get(i)[0], points.get(i)[1]);
                if (closed) out.closePath();
                return;
            }
            boolean pen = false;
            for (int i = 1; i < points.size(); i++) {
                pen = addClippedLine(out, points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1], window, pen);
            }
            if (closed) {
                final double[] a = points.get(points.size() - 1), b = points.get(0);
                addClippedLine(out, a[0], a[1], b[0], b[1], window, pen);
            }
        }

        // 多角形を枠で切り取って加える (枠は凸なので、中の塗り方は変わらない)
        private void addClippedPolygon(Path2D out, List<double[]> polygon, double[] window) {
            if (polygon.size() < 3) return;
            List<double[]> current = polygon;
            for (int edge = 0; edge < 4 && !current.isEmpty(); edge++) {
                final List<double[]> next = new ArrayList<double[]>(current.size() + 4);
                for (int i = 0; i < current.size(); i++) {
                    final double[] a = current.get(i), b = current.get((i + 1) % current.size());
                    final boolean ina = inside(a, edge, window), inb = inside(b, edge, window);
                    if (ina) next.add(a);
                    if (ina != inb) next.add(intersection(a, b, edge, window));
                }
                current = next;
            }
            if (current.size() < 3) return;
            out.moveTo(current.get(0)[0], current.get(0)[1]);
            for (int i = 1; i < current.size(); i++) out.lineTo(current.get(i)[0], current.get(i)[1]);
            out.closePath();
        }

        private boolean inside(double[] p, int edge, double[] window) {
            switch (edge) {
                case 0: return p[0] >= window[0];
                case 1: return p[1] >= window[1];
                case 2: return p[0] <= window[2];
                default: return p[1] <= window[3];
            }
        }

        private double[] intersection(double[] a, double[] b, int edge, double[] window) {
            final boolean vertical = edge == 0 || edge == 2;
            final double bound = window[edge];
            final double t = vertical ? (bound - a[0]) / (b[0] - a[0]) : (bound - a[1]) / (b[1] - a[1]);
            return vertical ? new double[]{bound, a[1] + (b[1] - a[1]) * t} : new double[]{a[0] + (b[0] - a[0]) * t, bound};
        }

        // 線分を枠で切り取って加える (Liang-Barsky)。前の線分とつながっていれば、続けて描く。続けて描ける状態なら true
        private boolean addClippedLine(Path2D out, double x0, double y0, double x1, double y1, double[] window, boolean pen) {
            final double dx = x1 - x0, dy = y1 - y0;
            double t0 = 0, t1 = 1;
            final double[] p = {-dx, dx, -dy, dy}, q = {x0 - window[0], window[2] - x0, y0 - window[1], window[3] - y0};
            for (int i = 0; i < 4; i++) {
                if (p[i] == 0) {
                    if (q[i] < 0) return false;
                } else {
                    final double r = q[i] / p[i];
                    if (p[i] < 0) t0 = Math.max(t0, r);
                    else t1 = Math.min(t1, r);
                }
            }
            if (t0 > t1) return false;
            if (!pen || t0 > 0) out.moveTo(x0 + dx * t0, y0 + dy * t0);
            out.lineTo(x0 + dx * t1, y0 + dy * t1);
            return t1 >= 1;
        }

        // 色や url(#勾配) を Paint にする。塗らないなら null
        private Paint paint(String spec, Style s, Shape shape, double opacity) {
            final String v = spec.trim();
            if (v.equals("none") || opacity <= 0) return null;
            if (v.startsWith("url(")) {
                final Node server = reference(v, null);
                if (server != null && (server.name.equals("linearGradient") || server.name.equals("radialGradient"))) {
                    return gradient(server, s, shape, opacity);
                }
                // 見つからないときは、あとに書かれた代わりの色を使う (なければ塗らない)
                final int close = v.indexOf(')');
                final String fallback = close >= 0 ? v.substring(close + 1).trim() : "";
                return fallback.isEmpty() ? null : solid(color(fallback, s.color), opacity);
            }
            return solid(color(v, s.color), opacity);
        }

        private Paint solid(Integer c, double opacity) {
            if (c == null) return null;
            final int a = (int) Math.round((c >>> 24) * opacity);
            return new Color((c & 0xFFFFFF) | a << 24, true);
        }

        // 勾配の指定は、href でたどった先から受け継ぐ
        private String inherited(Node gradient, String name) {
            Node e = gradient;
            for (int i = 0; i < 16 && e != null; i++) {
                final String value = e.get(name);
                if (value != null) return value;
                e = href(e);
            }
            return null;
        }

        // 勾配の色。stop を持つ要素 (href でたどった先) ごとに 1 度だけ読む
        private Stops stops(Node gradient) {
            Node owner = null;
            Node e = gradient;
            for (int i = 0; i < 16 && e != null && owner == null; i++) {
                for (Object child : e.children()) {
                    if (child instanceof Node && ((Node) child).name.equals("stop")) {
                        owner = e;
                        break;
                    }
                }
                if (owner == null) e = href(e);
            }
            if (owner == null) return NO_STOPS;
            if (owner.resource instanceof Stops) return (Stops) owner.resource;
            final List<Float> offsets = new ArrayList<Float>();
            final List<Integer> colors = new ArrayList<Integer>();
            float last = 0;
            for (Object child : owner.children()) {
                if (!(child instanceof Node) || !((Node) child).name.equals("stop")) continue;
                if (colors.size() >= MAX_STOPS) break;
                final Node stop = (Node) child;
                final Style st = styleOf(stop, new Style());
                final String off = stop.get("offset", "").trim();
                float offset = (float) fraction(off, 0);
                if (Float.isNaN(offset)) offset = 0;
                offset = Math.max(last, Math.min(1, offset));
                last = offset;
                Integer c = st.stopColor == null ? Integer.valueOf(0xFF000000) : color(st.stopColor, st.color);
                if (c == null) c = 0xFF000000;
                final int a = (int) Math.round((c >>> 24) * st.stopOpacity);
                offsets.add(offset);
                colors.add((c & 0xFFFFFF) | a << 24);
            }
            final float[] fractions = new float[offsets.size()];
            final int[] argb = new int[colors.size()];
            for (int i = 0; i < fractions.length; i++) {
                fractions[i] = offsets.get(i);
                argb[i] = colors.get(i);
            }
            final Stops stops = new Stops(fractions, argb);
            owner.resource = stops;
            return stops;
        }

        private Paint gradient(Node server, Style s, Shape shape, double opacity) {
            final boolean linear = server.name.equals("linearGradient");
            final Stops stops = stops(server);
            final int count = stops.colors.length;
            // 色がなければ塗らない。1 色なら、その色で塗る
            if (count == 0) return null;
            final Color[] colors = new Color[count];
            for (int i = 0; i < count; i++) {
                final int c = stops.colors[i];
                colors[i] = new Color((c & 0xFFFFFF) | (int) Math.round((c >>> 24) * opacity) << 24, true);
            }
            if (count == 1) return colors[0];

            // Java の勾配は、位置が真っ直ぐ増えていく必要がある。同じ位置の停止点は、ごくわずかにずらす
            final float[] fractions = stops.fractions.clone();
            for (int i = 1; i < fractions.length; i++) {
                if (fractions[i] <= fractions[i - 1]) fractions[i] = Math.nextUp(fractions[i - 1]);
            }
            if (fractions[fractions.length - 1] > 1) {
                // 1 を超えた分は、手前に詰める
                for (int i = fractions.length - 1; i > 0; i--) {
                    fractions[i] = Math.min(fractions[i], 1 - (fractions.length - 1 - i) * 1e-6f);
                }
            }

            final boolean userSpace = "userSpaceOnUse".equals(inherited(server, "gradientUnits"));
            final AffineTransform transform = new AffineTransform();
            if (!userSpace) {
                final Rectangle2D box = shape.getBounds2D();
                if (!(box.getWidth() > 0) || !(box.getHeight() > 0)) return null;
                transform.translate(box.getX(), box.getY());
                transform.scale(box.getWidth(), box.getHeight());
            }
            final AffineTransform extra = transform(inherited(server, "gradientTransform"));
            if (extra != null) transform.concatenate(extra);
            if (!isFinite(transform) || transform.getDeterminant() == 0) return null;
            final String spread = inherited(server, "spreadMethod");
            final MultipleGradientPaint.CycleMethod cycle = "reflect".equals(spread) ? MultipleGradientPaint.CycleMethod.REFLECT
                    : ("repeat".equals(spread) ? MultipleGradientPaint.CycleMethod.REPEAT : MultipleGradientPaint.CycleMethod.NO_CYCLE);
            try {
                if (linear) {
                    final Point2D start = new Point2D.Double(coordinate(server, "x1", userSpace, viewportWidth, 0), coordinate(server, "y1", userSpace, viewportHeight, 0));
                    final Point2D end = new Point2D.Double(coordinate(server, "x2", userSpace, viewportWidth, userSpace ? viewportWidth : 1), coordinate(server, "y2", userSpace, viewportHeight, 0));
                    if (!(start.distance(end) > 0)) return colors[count - 1];
                    return new LinearGradientPaint(start, end, fractions, colors, cycle, MultipleGradientPaint.ColorSpaceType.SRGB, transform);
                }
                final double diagonal = Math.sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2);
                final double cx = coordinate(server, "cx", userSpace, viewportWidth, userSpace ? viewportWidth / 2 : 0.5);
                final double cy = coordinate(server, "cy", userSpace, viewportHeight, userSpace ? viewportHeight / 2 : 0.5);
                final double r = coordinate(server, "r", userSpace, diagonal, userSpace ? diagonal / 2 : 0.5);
                final double fx = coordinate(server, "fx", userSpace, viewportWidth, cx);
                final double fy = coordinate(server, "fy", userSpace, viewportHeight, cy);
                if (!(r > 0) || !(r < 1e30)) return colors[count - 1];
                return new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new Point2D.Double(fx, fy), fractions, colors, cycle,
                        MultipleGradientPaint.ColorSpaceType.SRGB, transform);
            } catch (IllegalArgumentException e) {
                return colors[0];
            }
        }

        // 勾配の座標。物体の大きさに対する割合のときは、% を 0 から 1 にする
        private double coordinate(Node server, String name, boolean userSpace, double relative, double fallback) {
            final String value = inherited(server, name);
            if (value == null || value.trim().isEmpty()) return fallback;
            final double v = userSpace ? length(value, relative, 16) : fraction(value, Double.NaN);
            return isFinite(v) ? v : fallback;
        }

        // ---------- 文字 ----------

        private void drawText(Node e, Style s, AffineTransform t, int depth) {
            for (Object[] run : layoutText(e, s, depth)) {
                if (stopped) return;
                fillAndStroke((Shape) run[0], (Style) run[1], t);
            }
        }

        /**
         * 文字を並べて、{外形, スタイル} の並びにする。
         * 空白は、改行とタブを空白にして、続く空白を 1 つにまとめ、先頭と末尾の空白を取る (xml:space="preserve" なら、まとめない)。
         * text-anchor は、x で位置を決めた所から次に決めた所まで (文字の塊) をまとめて揃える。
         */
        private List<Object[]> layoutText(Node e, Style s, int depth) {
            final List<Piece> pieces = new ArrayList<Piece>();
            final Piece position = new Piece(null, s);
            readPosition(e, s, position);
            collectText(e, s, pieces, position, depth);
            boolean preserve = false;
            for (Node n = e; n != null; n = n.parent) {
                final String space = n.get("space");
                if (space != null) {
                    preserve = space.trim().equals("preserve");
                    break;
                }
            }
            normalizeSpaces(pieces, preserve);

            final List<Object[]> result = new ArrayList<Object[]>();
            final List<double[]> placed = new ArrayList<double[]>();
            double penX = 0, penY = 0;
            int chunkStart = 0;
            for (Piece piece : pieces) {
                if (stopped) break;
                if (!Double.isNaN(piece.x)) {
                    anchor(result, placed, chunkStart, penX);
                    chunkStart = result.size();
                    penX = piece.x;
                }
                if (!Double.isNaN(piece.y)) penY = piece.y;
                penX += piece.dx;
                penY += piece.dy;
                String text = piece.text;
                if (text.isEmpty() || !(piece.style.fontSize > 0)) continue;
                if (text.length() > glyphs) text = text.substring(0, glyphs);
                if (text.isEmpty() || !spend(text.length() * 5000.0)) {
                    stopped = true;
                    break;
                }
                glyphs -= text.length();
                final Font font = font(piece.style, text);
                final GlyphVector vector = font.createGlyphVector(fontContext, text);
                final double scale = piece.style.fontSize / FONT_UNITS;
                final double advance = vector.getGlyphPosition(vector.getNumGlyphs()).getX() * scale;
                final AffineTransform place = AffineTransform.getTranslateInstance(penX, penY);
                place.scale(scale, scale);
                if (!isFinite(place) || !isFinite(advance)) continue;
                // 文字の外形は、少しずつまとめて塗る
                for (int i = 0; i < vector.getNumGlyphs(); i += GLYPHS_PER_FILL) {
                    final Path2D.Float outline = new Path2D.Float();
                    for (int k = i; k < Math.min(vector.getNumGlyphs(), i + GLYPHS_PER_FILL); k++) outline.append(vector.getGlyphOutline(k), false);
                    result.add(new Object[]{place.createTransformedShape(outline), piece.style});
                    placed.add(new double[]{penX, advance});
                }
                penX += advance;
            }
            anchor(result, placed, chunkStart, penX);
            return result;
        }

        // 文字の塊 (start から最後まで) を、text-anchor に合わせてずらす
        private void anchor(List<Object[]> runs, List<double[]> placed, int start, double end) {
            if (start >= runs.size()) return;
            final String anchor = ((Style) runs.get(start)[1]).textAnchor;
            final double width = end - placed.get(start)[0];
            final double shift = anchor.equals("middle") ? -width / 2 : (anchor.equals("end") ? -width : 0);
            if (shift == 0 || !isFinite(shift)) return;
            final AffineTransform move = AffineTransform.getTranslateInstance(shift, 0);
            for (int i = start; i < runs.size(); i++) runs.get(i)[0] = move.createTransformedShape((Shape) runs.get(i)[0]);
        }

        // x・y・dx・dy (並びのときは最初の値) を、次の文字の位置として覚える
        private void readPosition(Node e, Style s, Piece position) {
            final double x = firstLength(e.get("x"), viewportWidth, s), y = firstLength(e.get("y"), viewportHeight, s);
            if (!Double.isNaN(x)) position.x = x;
            if (!Double.isNaN(y)) position.y = y;
            final double dx = firstLength(e.get("dx"), viewportWidth, s), dy = firstLength(e.get("dy"), viewportHeight, s);
            if (!Double.isNaN(dx)) position.dx += dx;
            if (!Double.isNaN(dy)) position.dy += dy;
        }

        private double firstLength(String value, double relative, Style s) {
            if (value == null) return Double.NaN;
            final String v = value.trim();
            int end = 0;
            while (end < v.length() && !isSpace(v.charAt(end)) && v.charAt(end) != ',') end++;
            return length(v.substring(0, end), relative, s.fontSize);
        }

        private void collectText(Node e, Style s, List<Piece> pieces, Piece position, int depth) {
            for (Object child : e.children()) {
                if (stopped) return;
                if (child instanceof String) {
                    final Piece piece = new Piece((String) child, s);
                    piece.x = position.x;
                    piece.y = position.y;
                    piece.dx = position.dx;
                    piece.dy = position.dy;
                    position.x = position.y = Double.NaN;
                    position.dx = position.dy = 0;
                    pieces.add(piece);
                    continue;
                }
                final Node n = (Node) child;
                if (depth > MAX_DRAW_DEPTH) continue;
                if (++draws > MAX_DRAWS) {
                    stopped = true;
                    return;
                }
                if (!n.name.equals("tspan") && !n.name.equals("a") && !n.name.equals("textPath")) continue;
                if (!conditionsPass(n)) continue;
                final Style style = styleOf(n, s);
                if (style.hidden) continue;
                readPosition(n, style, position);
                collectText(n, style, pieces, position, depth + 1);
            }
        }

        private void normalizeSpaces(List<Piece> pieces, boolean preserve) {
            boolean lastSpace = true;
            for (Piece piece : pieces) {
                final StringBuilder out = new StringBuilder(piece.text.length());
                for (int i = 0; i < piece.text.length(); i++) {
                    char c = piece.text.charAt(i);
                    if (c == '\n' || c == '\r' || c == '\t') c = ' ';
                    if (!preserve && c == ' ' && lastSpace) continue;
                    lastSpace = c == ' ';
                    out.append(c);
                }
                piece.text = out.toString();
            }
            if (preserve) return;
            // 末尾の空白を取る
            for (int i = pieces.size() - 1; i >= 0; i--) {
                final Piece piece = pieces.get(i);
                if (piece.text.isEmpty()) continue;
                if (piece.text.endsWith(" ")) piece.text = piece.text.substring(0, piece.text.length() - 1);
                break;
            }
        }

        // フォント。名前と太さ・斜体ごとに 1 度だけ作る
        private Font font(Style s, String text) {
            String family = Font.SERIF;
            for (String candidate : s.fontFamily.split(",")) {
                final String name = candidate.trim().replace("'", "").replace("\"", "");
                final String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("serif")) {
                    family = Font.SERIF;
                    break;
                }
                if (lower.equals("sans-serif")) {
                    family = Font.SANS_SERIF;
                    break;
                }
                if (lower.equals("monospace")) {
                    family = Font.MONOSPACED;
                    break;
                }
                if (!name.isEmpty()) {
                    family = name;
                    break;
                }
            }
            final int style = (s.bold ? Font.BOLD : Font.PLAIN) | (s.italic ? Font.ITALIC : 0);
            // 種類が多すぎれば、知らない名前のフォントを探さずに、論理フォントにする (フォントを探すのは遅い)
            if (fonts.size() >= MAX_FONTS && !fonts.containsKey(family + "\n" + style)) family = isLogical(family) ? family : Font.SERIF;
            final Font font = cachedFont(family, style);
            // 表示できない文字があれば、代わりの字体を持つ論理フォントにする
            if (!isLogical(font.getFamily()) && font.canDisplayUpTo(text) >= 0) return cachedFont(Font.SANS_SERIF, style);
            return font;
        }

        private Font cachedFont(String family, int style) {
            final String key = family + "\n" + style;
            Font font = fonts.get(key);
            if (font == null) {
                font = new Font(family, style, 1).deriveFont(FONT_UNITS);
                fonts.put(key, font);
            }
            return font;
        }

        private boolean isLogical(String family) {
            switch (family) {
                case Font.SANS_SERIF: case Font.SERIF: case Font.MONOSPACED: case Font.DIALOG: case Font.DIALOG_INPUT:
                    return true;
                default:
                    return false;
            }
        }

        // ---------- 画像 ----------

        // data: URI で埋め込まれた画像だけを描く (外部のファイルや URL は読まない)
        private void drawImage(Node e, Style s, AffineTransform t) {
            if (!s.visible) return;
            if (e.resource == null) e.resource = readImage(e.get("href"));
            if (!(e.resource instanceof BufferedImage)) return;
            final BufferedImage image = (BufferedImage) e.resource;
            final double x = attr(e, "x", viewportWidth, 0, s), y = attr(e, "y", viewportHeight, 0, s);
            final double w = attr(e, "width", viewportWidth, image.getWidth(), s), h = attr(e, "height", viewportHeight, image.getHeight(), s);
            if (!(w > 0) || !(h > 0)) return;
            final AffineTransform place = new AffineTransform(t);
            place.translate(x, y);
            final String aspect = e.get("preserveAspectRatio", "");
            place.concatenate(viewBoxTransform(new double[]{0, 0, image.getWidth(), image.getHeight()}, w, h, aspect));
            if (!usable(place)) return;
            final Rectangle savedClip = canvas.clip;
            if (aspect.contains("slice")) {
                final Rectangle r = deviceRect(t, x, y, w, h);
                if (r != null) {
                    final Rectangle clip = r.intersection(canvas.clip);
                    if (clip.isEmpty()) return;
                    canvas.setClip(clip);
                }
            }
            try {
                if (!measure(new Rectangle2D.Double(0, 0, image.getWidth(), image.getHeight()), place) || !spend(area(1) * 8)) return;
                final Graphics2D g = canvas.g;
                canvas.setTransform(place);
                if (s.alpha < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) s.alpha));
                g.drawImage(image, 0, 0, null);
                g.setComposite(AlphaComposite.SrcOver);
            } finally {
                if (canvas.clip != savedClip) canvas.setClip(savedClip);
            }
        }

        private Object readImage(String href) {
            if (href == null) return NO_IMAGE;
            final String h = href.trim();
            if (!h.regionMatches(true, 0, "data:", 0, 5)) return NO_IMAGE;
            final int comma = h.indexOf(',');
            if (comma < 0 || !h.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) return NO_IMAGE;
            if (h.length() - comma > MAX_IMAGE_DATA || imagePixels <= 0) return NO_IMAGE;
            try {
                // 先頭だけを展開して、読める形式 (PNG・JPEG・BMP・GIF) でなければ、全体は展開しない
                final StringBuilder head = new StringBuilder();
                for (int i = comma + 1; i < h.length() && head.length() < 16; i++) {
                    if (!isSpace(h.charAt(i))) head.append(h.charAt(i));
                }
                final byte[] magic = Base64.getMimeDecoder().decode(head.toString());
                if (!(magic.length >= 4 && ((magic[0] & 0xFF) == 0x89 && magic[1] == 'P' || (magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8
                        || magic[0] == 'B' && magic[1] == 'M' || magic[0] == 'G' && magic[1] == 'I' && magic[2] == 'F'))) {
                    return NO_IMAGE;
                }
                final byte[] data = Base64.getMimeDecoder().decode(h.substring(comma + 1));
                if (!spend(data.length * 20.0)) return NO_IMAGE;
                final BufferedImage image = FormatUtil.readEmbedded(data, imagePixels);
                if (image == null) return NO_IMAGE;
                final long pixels = (long) image.getWidth() * image.getHeight();
                imagePixels -= pixels;
                if (!spend(pixels * 10.0)) return NO_IMAGE;
                return image;
            } catch (IOException | RuntimeException ex) {
                // 読めない画像は描かない
                return NO_IMAGE;
            }
        }
    }
}
