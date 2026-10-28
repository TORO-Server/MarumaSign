package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.pdf.color.ColorSpaces;
import marumasa.marumasa_sign.core.pdf.color.DeviceSpaces;
import marumasa.marumasa_sign.core.pdf.color.PdfColorSpace;
import marumasa.marumasa_sign.core.pdf.color.SpecialSpaces;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;
import marumasa.marumasa_sign.core.pdf.cos.PdfString;
import marumasa.marumasa_sign.core.pdf.font.PdfFont;
import marumasa.marumasa_sign.core.pdf.font.Type3Font;
import marumasa.marumasa_sign.core.pdf.function.PdfFunction;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ページを画像に描く。内容の命令 (content stream) を順に実行し、Java2D で描く。
 * <p>
 * 悪意のある PDF で止まらないよう、次のものに上限を設ける:
 * 命令の数と描く時間 (超えたらそこまでの絵を返す)、フォーム・パターン・Type 3 のグリフ・ソフトマスクの入れ子の深さ、
 * 自分自身を描くフォーム、q の積み重ね、細かすぎる点線 (Java2D が膨大な図形を作って止まる)。
 */
public final class PageRenderer {

    // 1 ページで実行する命令の数の上限
    private static final long MAX_OPERATIONS = 20_000_000L;
    // フォームなどの入れ子の深さの上限
    private static final int MAX_DEPTH = 24;
    // q の積み重ねの上限
    private static final int MAX_STATES = 1024;
    // 点線の区切りの数の上限 (これより細かい点線は実線で描く)
    private static final double MAX_DASHES = 200_000;
    // パターンの 1 周期の絵の、一辺の画素の数の上限
    private static final int MAX_CELL_SIDE = 2048;

    /**
     * 命令の数か時間の上限に達した。
     */
    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LimitReached() {
            super(null, null, false, false);
        }
    }

    private final DocumentCache cache;
    private final PdfDocument document;
    private final long deadline;
    private long operations;
    private int depth;

    private Canvas canvas;
    private GraphicsState gs = new GraphicsState();
    private final Deque<GraphicsState> stack = new ArrayDeque<>();
    private PdfDict resources;
    // 今の内容の、パターンの基準の空間 (ページか、フォームを呼んだときの空間)
    private AffineTransform patternBase = new AffineTransform();

    // 作っている途中の道 (ユーザー空間)
    private Path2D.Double path = new Path2D.Double();
    private double currentX, currentY;
    private int pendingClip; // 0: なし、1: 非ゼロ、2: 偶奇
    // 文字
    private AffineTransform textMatrix = new AffineTransform();
    private AffineTransform lineMatrix = new AffineTransform();
    private GeneralPath textClip;
    // 表示しない層の中にいる深さ
    private int hiddenDepth;
    private final Deque<Boolean> markedContent = new ArrayDeque<>();
    // Type 3 のグリフの中で、色が決められている (d1)
    private boolean uncolored;
    // 描いている途中のフォーム (自分自身を描くものを除くため)
    private final Map<Object, Boolean> activeForms = new IdentityHashMap<>();
    // 同じ画像を何度も読まないための覚え
    private final Map<PdfStream, ImageDecoder.Result> images = new IdentityHashMap<>();
    // 同じパターンの絵を何度も描かないための覚え (パターン、行列、色ごと)
    private final Map<PdfStream, Map<String, Paint>> tilings = new IdentityHashMap<>();

    private PageRenderer(DocumentCache cache, long timeoutMillis) {
        this.cache = cache;
        this.document = cache.document();
        this.deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    /**
     * ページを描く。
     *
     * @param scale         1 ポイントあたりの画素の数
     * @param timeoutMillis 描く時間の上限。超えたら、そこまでに描いた絵を返す
     */
    public static BufferedImage render(DocumentCache cache, PdfDocument.Page page, double scale, long timeoutMillis) {
        final double[] media = page.mediaBox != null ? page.mediaBox : new double[]{0, 0, 612, 792};
        double[] crop = page.cropBox != null ? intersect(page.cropBox, media) : media;
        if (crop == null || crop[2] - crop[0] <= 0 || crop[3] - crop[1] <= 0) crop = media;
        final double cropWidth = Math.max(1, crop[2] - crop[0]), cropHeight = Math.max(1, crop[3] - crop[1]);
        final int rotate = ((page.rotate % 360) + 360) % 360 / 90 * 90;
        final boolean swap = rotate == 90 || rotate == 270;
        final int width = (int) Math.max(1, Math.floor((swap ? cropHeight : cropWidth) * scale));
        final int height = (int) Math.max(1, Math.floor((swap ? cropWidth : cropHeight) * scale));

        // ページの空間から画素へ: 拡大、回転、上下の反転、切り抜く範囲の左下を原点に
        final AffineTransform base = AffineTransform.getScaleInstance(scale, scale);
        if (rotate != 0) {
            final double tx = rotate == 90 ? cropHeight : rotate == 180 ? cropWidth : 0;
            final double ty = rotate == 270 ? cropWidth : rotate == 180 ? cropHeight : 0;
            base.translate(tx, ty);
            base.rotate(Math.toRadians(rotate));
        }
        base.translate(0, cropHeight);
        base.scale(1, -1);
        base.translate(-crop[0], -crop[1]);

        final PageRenderer renderer = new PageRenderer(cache, timeoutMillis);
        renderer.canvas = new Canvas(width, height, 0, 0, base, true, true);
        renderer.gs.clip = new Area(new Rectangle2D.Double(crop[0], crop[1], cropWidth, cropHeight));
        try {
            renderer.resources = page.resources;
            renderer.run(contents(page.dict.get("Contents")));
            renderer.annotations(page);
        } catch (LimitReached e) {
            // 時間か命令の数の上限: ここまでの絵を返す
        } finally {
            renderer.canvas.dispose();
        }
        // 白の上に描いたので、不透明な RGB にする
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        final int[] in = renderer.canvas.pixels;
        for (int i = 0; i < out.length; i++) out[i] = Canvas.over(in[i], in[i] >>> 24, 0xFFFFFFFF) & 0xFFFFFF;
        return result;
    }

    private static double[] intersect(double[] a, double[] b) {
        final double[] r = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.min(a[2], b[2]), Math.min(a[3], b[3])};
        return r[2] > r[0] && r[3] > r[1] ? r : null;
    }

    // /Contents (ストリームか、ストリームの配列) をつなげたもの
    private static byte[] contents(Object value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<PdfStream> streams = new ArrayList<>();
        if (value instanceof PdfStream) {
            streams.add((PdfStream) value);
        } else if (value instanceof PdfArray) {
            final PdfArray array = (PdfArray) value;
            for (int i = 0; i < array.size(); i++) {
                final PdfStream stream = array.stream(i);
                if (stream != null) streams.add(stream);
            }
        }
        long total = 0;
        for (PdfStream stream : streams) {
            try {
                final byte[] data = stream.decode(PdfDocument.MAX_DECODED);
                total += data.length;
                if (total > PdfDocument.MAX_DECODED) break;
                out.write(data, 0, data.length);
                // ストリームの境目で語がつながらないように
                out.write('\n');
            } catch (IOException | RuntimeException e) {
                // 読めないストリームは飛ばす
            }
        }
        return out.toByteArray();
    }

    // ---------- 実行 ----------

    private void tick() {
        if (++operations > MAX_OPERATIONS) throw new LimitReached();
        if ((operations & 1023) == 0) {
            if (System.nanoTime() > deadline) throw new LimitReached();
            if (Thread.currentThread().isInterrupted()) throw new LimitReached();
        }
    }

    private void run(byte[] content) {
        final ContentParser parser = new ContentParser(content, document);
        final int savedStates = stack.size();
        final int savedMarked = markedContent.size();
        // 入れ子の内容 (パターン、フォームなど) の中で、外側の道とクリップの指定を使ってしまわないように
        final int savedClip = pendingClip;
        final Path2D.Double savedPath = path;
        final double savedX = currentX, savedY = currentY;
        pendingClip = 0;
        path = new Path2D.Double();
        try {
            runOperators(parser);
        } finally {
            pendingClip = savedClip;
            path = savedPath;
            currentX = savedX;
            currentY = savedY;
        }
        // 閉じられていない q と BDC を戻す
        while (stack.size() > savedStates) gs = stack.pop();
        while (markedContent.size() > savedMarked) {
            if (markedContent.pop()) hiddenDepth--;
        }
    }

    private void runOperators(ContentParser parser) {
        while (parser.next()) {
            tick();
            try {
                execute(parser.operator(), parser.operands(), parser.inlineImage());
            } catch (LimitReached e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // 1 つの命令の失敗 (壊れた引数など) では止めない
            }
        }
    }

    private static double num(List<Object> operands, int index) throws IOException {
        if (index >= operands.size()) throw new IOException("Missing operand");
        final Object value = operands.get(index);
        if (!(value instanceof Number)) throw new IOException("Operand is not a number");
        final double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) throw new IOException("Bad number");
        return number;
    }

    // 後ろから数えた引数 (余分な引数が前にあってもよいように)
    private static double last(List<Object> operands, int fromEnd, int count) throws IOException {
        if (operands.size() < count) throw new IOException("Missing operand");
        return num(operands, operands.size() - count + fromEnd);
    }

    private static String name(List<Object> operands, int index) {
        if (index < 0 || index >= operands.size()) return null;
        final Object value = operands.get(index);
        return value instanceof PdfName ? ((PdfName) value).name : null;
    }

    private void execute(String op, List<Object> operands, PdfStream inlineImage) throws IOException {
        switch (op) {
            // グラフィックス状態
            case "q":
                if (stack.size() < MAX_STATES) {
                    stack.push(gs);
                    gs = gs.clone();
                }
                break;
            case "Q":
                if (!stack.isEmpty()) gs = stack.pop();
                break;
            case "cm": {
                final AffineTransform m = new AffineTransform(last(operands, 0, 6), last(operands, 1, 6), last(operands, 2, 6),
                        last(operands, 3, 6), last(operands, 4, 6), last(operands, 5, 6));
                gs.ctm.concatenate(m);
                break;
            }
            case "w":
                gs.lineWidth = Math.abs(last(operands, 0, 1));
                break;
            case "J":
                gs.lineCap = (int) last(operands, 0, 1);
                break;
            case "j":
                gs.lineJoin = (int) last(operands, 0, 1);
                break;
            case "M":
                gs.miterLimit = last(operands, 0, 1);
                break;
            case "d":
                if (operands.size() >= 2 && operands.get(operands.size() - 2) instanceof PdfArray) {
                    gs.dashArray = ((PdfArray) operands.get(operands.size() - 2)).numbers();
                    gs.dashPhase = last(operands, 0, 1);
                }
                break;
            case "ri":
            case "i":
                break;
            case "gs":
                extGState(name(operands, operands.size() - 1));
                break;
            // 道
            case "m":
                currentX = last(operands, 0, 2);
                currentY = last(operands, 1, 2);
                path.moveTo(currentX, currentY);
                break;
            case "l":
                ensureStarted();
                currentX = last(operands, 0, 2);
                currentY = last(operands, 1, 2);
                path.lineTo(currentX, currentY);
                break;
            case "c": {
                ensureStarted();
                final double x3 = last(operands, 4, 6), y3 = last(operands, 5, 6);
                path.curveTo(last(operands, 0, 6), last(operands, 1, 6), last(operands, 2, 6), last(operands, 3, 6), x3, y3);
                currentX = x3;
                currentY = y3;
                break;
            }
            case "v": {
                ensureStarted();
                final double x3 = last(operands, 2, 4), y3 = last(operands, 3, 4);
                path.curveTo(currentX, currentY, last(operands, 0, 4), last(operands, 1, 4), x3, y3);
                currentX = x3;
                currentY = y3;
                break;
            }
            case "y": {
                ensureStarted();
                final double x3 = last(operands, 2, 4), y3 = last(operands, 3, 4);
                path.curveTo(last(operands, 0, 4), last(operands, 1, 4), x3, y3, x3, y3);
                currentX = x3;
                currentY = y3;
                break;
            }
            case "h":
                if (path.getCurrentPoint() != null) path.closePath();
                break;
            case "re": {
                final double x = last(operands, 0, 4), y = last(operands, 1, 4), w = last(operands, 2, 4), h = last(operands, 3, 4);
                path.moveTo(x, y);
                path.lineTo(x + w, y);
                path.lineTo(x + w, y + h);
                path.lineTo(x, y + h);
                path.closePath();
                currentX = x;
                currentY = y;
                break;
            }
            // 塗りと線
            case "S":
                paintPath(false, true, Path2D.WIND_NON_ZERO);
                break;
            case "s":
                closeIfOpen();
                paintPath(false, true, Path2D.WIND_NON_ZERO);
                break;
            case "f":
            case "F":
                paintPath(true, false, Path2D.WIND_NON_ZERO);
                break;
            case "f*":
                paintPath(true, false, Path2D.WIND_EVEN_ODD);
                break;
            case "B":
                paintPath(true, true, Path2D.WIND_NON_ZERO);
                break;
            case "B*":
                paintPath(true, true, Path2D.WIND_EVEN_ODD);
                break;
            case "b":
                closeIfOpen();
                paintPath(true, true, Path2D.WIND_NON_ZERO);
                break;
            case "b*":
                closeIfOpen();
                paintPath(true, true, Path2D.WIND_EVEN_ODD);
                break;
            case "n":
                paintPath(false, false, Path2D.WIND_NON_ZERO);
                break;
            case "W":
                pendingClip = 1;
                break;
            case "W*":
                pendingClip = 2;
                break;
            // 色
            case "CS":
                setSpace(true, operands);
                break;
            case "cs":
                setSpace(false, operands);
                break;
            case "SC":
            case "SCN":
                setColor(true, operands);
                break;
            case "sc":
            case "scn":
                setColor(false, operands);
                break;
            case "G":
            case "g":
                setDevice(op.equals("G"), DeviceSpaces.GRAY, operands);
                break;
            case "RG":
            case "rg":
                setDevice(op.equals("RG"), DeviceSpaces.RGB, operands);
                break;
            case "K":
            case "k":
                setDevice(op.equals("K"), DeviceSpaces.CMYK, operands);
                break;
            // 文字
            case "BT":
                textMatrix = new AffineTransform();
                lineMatrix = new AffineTransform();
                textClip = null;
                break;
            case "ET":
                endText();
                break;
            case "Tc":
                gs.charSpacing = last(operands, 0, 1);
                break;
            case "Tw":
                gs.wordSpacing = last(operands, 0, 1);
                break;
            case "Tz":
                gs.horizontalScale = last(operands, 0, 1) / 100;
                break;
            case "TL":
                gs.leading = last(operands, 0, 1);
                break;
            case "Tf": {
                gs.fontSize = last(operands, 0, 1);
                final String fontName = name(operands, operands.size() - 2);
                final PdfDict fonts = resources == null ? null : resources.dict("Font");
                final PdfDict fontDict = fonts == null || fontName == null ? null : fonts.dict(fontName);
                gs.font = fontDict == null ? null : cache.font(fontDict);
                break;
            }
            case "Tr":
                gs.renderMode = (int) last(operands, 0, 1);
                break;
            case "Ts":
                gs.rise = last(operands, 0, 1);
                break;
            case "Td":
                moveText(last(operands, 0, 2), last(operands, 1, 2));
                break;
            case "TD": {
                final double ty = last(operands, 1, 2);
                gs.leading = -ty;
                moveText(last(operands, 0, 2), ty);
                break;
            }
            case "Tm":
                lineMatrix = new AffineTransform(last(operands, 0, 6), last(operands, 1, 6), last(operands, 2, 6),
                        last(operands, 3, 6), last(operands, 4, 6), last(operands, 5, 6));
                textMatrix = new AffineTransform(lineMatrix);
                break;
            case "T*":
                moveText(0, -gs.leading);
                break;
            case "Tj":
                if (!operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfString) {
                    showText(((PdfString) operands.get(operands.size() - 1)).bytes);
                }
                break;
            case "'":
                moveText(0, -gs.leading);
                if (!operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfString) {
                    showText(((PdfString) operands.get(operands.size() - 1)).bytes);
                }
                break;
            case "\"":
                if (operands.size() >= 3) {
                    gs.wordSpacing = num(operands, operands.size() - 3);
                    gs.charSpacing = num(operands, operands.size() - 2);
                    moveText(0, -gs.leading);
                    if (operands.get(operands.size() - 1) instanceof PdfString) {
                        showText(((PdfString) operands.get(operands.size() - 1)).bytes);
                    }
                }
                break;
            case "TJ":
                if (!operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfArray) {
                    showArray((PdfArray) operands.get(operands.size() - 1));
                }
                break;
            case "d0":
                break;
            case "d1":
                // 色のない Type 3 のグリフ (色は文字を描くときの色を使う)
                uncolored = true;
                break;
            // 画像とシェーディングと XObject
            case "BI":
                if (inlineImage != null && hiddenDepth == 0) drawImage(inlineImage);
                break;
            case "sh":
                if (hiddenDepth == 0) shade(name(operands, operands.size() - 1));
                break;
            case "Do":
                xObject(name(operands, operands.size() - 1));
                break;
            // 内容の印
            case "BMC":
                markedContent.push(false);
                break;
            case "BDC":
                beginMarkedContent(operands);
                break;
            case "EMC":
                if (!markedContent.isEmpty() && markedContent.pop()) hiddenDepth--;
                break;
            default:
                // MP、DP、BX、EX と、知らない命令は無視する
                break;
        }
    }

    private void ensureStarted() {
        if (path.getCurrentPoint() == null) path.moveTo(currentX, currentY);
    }

    private void closeIfOpen() {
        if (path.getCurrentPoint() != null) path.closePath();
    }

    // ---------- グラフィックス状態の辞書 ----------

    private void extGState(String key) throws IOException {
        final PdfDict states = resources == null ? null : resources.dict("ExtGState");
        final PdfDict state = states == null || key == null ? null : states.dict(key);
        if (state == null) return;
        for (String entry : state.keys()) {
            final Object value = state.get(entry);
            switch (entry) {
                case "LW":
                    if (value instanceof Number) gs.lineWidth = Math.abs(((Number) value).doubleValue());
                    break;
                case "LC":
                    if (value instanceof Number) gs.lineCap = ((Number) value).intValue();
                    break;
                case "LJ":
                    if (value instanceof Number) gs.lineJoin = ((Number) value).intValue();
                    break;
                case "ML":
                    if (value instanceof Number) gs.miterLimit = ((Number) value).doubleValue();
                    break;
                case "D":
                    if (value instanceof PdfArray && ((PdfArray) value).size() >= 2) {
                        final PdfArray dash = ((PdfArray) value).array(0);
                        gs.dashArray = dash == null ? null : dash.numbers();
                        gs.dashPhase = ((PdfArray) value).number(1, 0);
                    }
                    break;
                case "CA":
                    if (value instanceof Number) gs.strokeAlpha = clamp01(((Number) value).doubleValue());
                    break;
                case "ca":
                    if (value instanceof Number) gs.fillAlpha = clamp01(((Number) value).doubleValue());
                    break;
                case "BM":
                    gs.blendMode = blendMode(value);
                    break;
                case "AIS":
                    gs.alphaIsShape = Boolean.TRUE.equals(value);
                    break;
                case "Font":
                    if (value instanceof PdfArray && ((PdfArray) value).size() >= 2) {
                        final PdfDict fontDict = ((PdfArray) value).dict(0);
                        gs.font = fontDict == null ? null : cache.font(fontDict);
                        gs.fontSize = ((PdfArray) value).number(1, gs.fontSize);
                    }
                    break;
                case "SMask":
                    gs.softMask = value instanceof PdfDict ? softMask((PdfDict) value) : null;
                    break;
                default:
                    break;
            }
        }
    }

    private static double clamp01(double value) {
        return Double.isNaN(value) ? 1 : Math.max(0, Math.min(1, value));
    }

    private static String blendMode(Object value) {
        if (value instanceof PdfName) return Blend.of(((PdfName) value).name) != null ? ((PdfName) value).name : "Normal";
        if (value instanceof PdfArray) {
            final PdfArray list = (PdfArray) value;
            for (int i = 0; i < list.size(); i++) {
                final String name = list.name(i);
                if (name != null && (name.equals("Normal") || Blend.of(name) != null)) return name;
            }
        }
        return "Normal";
    }

    // ---------- 色 ----------

    private void setSpace(boolean stroke, List<Object> operands) throws IOException {
        if (operands.isEmpty() || uncolored) return;
        final PdfColorSpace space = ColorSpaces.create(operands.get(operands.size() - 1), resources);
        if (stroke) {
            gs.strokeSpace = space;
            gs.strokeColor = space.initialColor();
            gs.strokePattern = null;
        } else {
            gs.fillSpace = space;
            gs.fillColor = space.initialColor();
            gs.fillPattern = null;
        }
    }

    private void setColor(boolean stroke, List<Object> operands) {
        if (uncolored) return;
        final PdfColorSpace space = stroke ? gs.strokeSpace : gs.fillSpace;
        Object pattern = null;
        final List<Float> values = new ArrayList<>();
        for (Object operand : operands) {
            if (operand instanceof Number) values.add(((Number) operand).floatValue());
            else if (operand instanceof PdfName && space instanceof SpecialSpaces.Pattern) {
                final PdfDict patterns = resources == null ? null : resources.dict("Pattern");
                pattern = patterns == null ? null : patterns.get(((PdfName) operand).name);
            }
        }
        final float[] color = new float[Math.max(space.components(), 0)];
        // 数が足りなければ、前の値を残す
        final float[] previous = stroke ? gs.strokeColor : gs.fillColor;
        for (int i = 0; i < color.length; i++) {
            color[i] = i < values.size() ? values.get(i) : i < previous.length ? previous[i] : 0;
        }
        if (stroke) {
            gs.strokeColor = color;
            if (space instanceof SpecialSpaces.Pattern) gs.strokePattern = pattern;
        } else {
            gs.fillColor = color;
            if (space instanceof SpecialSpaces.Pattern) gs.fillPattern = pattern;
        }
    }

    private void setDevice(boolean stroke, PdfColorSpace space, List<Object> operands) throws IOException {
        if (uncolored) return;
        final float[] color = new float[space.components()];
        for (int i = 0; i < color.length; i++) color[i] = (float) last(operands, i, color.length);
        if (stroke) {
            gs.strokeSpace = space;
            gs.strokeColor = color;
            gs.strokePattern = null;
        } else {
            gs.fillSpace = space;
            gs.fillColor = color;
            gs.fillPattern = null;
        }
    }

    // 塗りか線の Paint。塗らないもの (Separation の None、使えないパターン) なら null
    private Paint paint(boolean stroke) {
        final PdfColorSpace space = stroke ? gs.strokeSpace : gs.fillSpace;
        final float[] color = stroke ? gs.strokeColor : gs.fillColor;
        if (space.isNone()) return null;
        if (space instanceof SpecialSpaces.Pattern) {
            final Object pattern = stroke ? gs.strokePattern : gs.fillPattern;
            return patternPaint(pattern, ((SpecialSpaces.Pattern) space).base, color);
        }
        try {
            return new Color(space.toRgb(color));
        } catch (RuntimeException e) {
            return Color.BLACK;
        }
    }

    // ---------- 道を描く ----------

    private void paintPath(boolean fill, boolean stroke, int windingRule) {
        final Path2D.Double userPath = path;
        path = new Path2D.Double();
        userPath.setWindingRule(windingRule);
        final Shape pageShape = gs.ctm.createTransformedShape(userPath);
        if (hiddenDepth == 0 && userPath.getCurrentPoint() != null) {
            if (fill) fillShape(pageShape, windingRule);
            if (stroke) strokeShape(pageShape);
        }
        if (pendingClip != 0) {
            final Path2D.Double clipPath = new Path2D.Double(pageShape);
            clipPath.setWindingRule(pendingClip == 2 ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            intersectClip(clipPath);
            pendingClip = 0;
        }
    }

    private void intersectClip(Shape pageShape) {
        final Area area = new Area(pageShape);
        if (gs.clip == null) {
            gs.clip = area;
        } else {
            final Area clip = new Area(gs.clip);
            clip.intersect(area);
            gs.clip = clip;
        }
    }

    private void fillShape(Shape pageShape, int windingRule) {
        final Paint paint = paint(false);
        if (paint == null) return;
        final Shape shape = windingRule == Path2D.WIND_EVEN_ODD ? withRule(pageShape, windingRule) : pageShape;
        canvas.paint(new Canvas.Painter() {
            @Override
            public void paint(Graphics2D g) {
                g.setPaint(paint);
                g.fill(shape);
            }
        }, shape.getBounds2D(), gs.fillAlpha, gs.softMask, gs.blendMode, gs.clip);
    }

    private static Shape withRule(Shape shape, int rule) {
        final Path2D.Double copy = new Path2D.Double(shape);
        copy.setWindingRule(rule);
        return copy;
    }

    private void strokeShape(Shape pageShape) {
        final Paint paint = paint(true);
        if (paint == null) return;
        final BasicStroke stroke = stroke(pageShape);
        final Shape outline;
        try {
            outline = stroke.createStrokedShape(pageShape);
        } catch (RuntimeException e) {
            return;
        }
        canvas.paint(new Canvas.Painter() {
            @Override
            public void paint(Graphics2D g) {
                g.setPaint(paint);
                g.fill(outline);
            }
        }, outline.getBounds2D(), gs.strokeAlpha, gs.softMask, gs.blendMode, gs.clip);
    }

    // 線の太さなどをページの空間にしたもの。太さは CTM の平均の拡大率で変える
    private BasicStroke stroke(Shape pageShape) {
        final double scale = averageScale(gs.ctm);
        float width = (float) (gs.lineWidth * scale);
        // 細い線 (太さ 0 を含む) でも見えるように、0.25 ポイントより細くしない (Adobe Reader と同じ)
        if (!(width >= 0.25f) || Float.isInfinite(width)) width = 0.25f;
        final double deviceScale = averageScale(canvas.base);
        final int cap = gs.lineCap == 1 ? BasicStroke.CAP_ROUND : gs.lineCap == 2 ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        final int join = gs.lineJoin == 1 ? BasicStroke.JOIN_ROUND : gs.lineJoin == 2 ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
        final float miter = (float) Math.max(1, Math.min(gs.miterLimit, 1000));
        final float[] dash = dash(scale, pageShape, deviceScale);
        if (dash == null) return new BasicStroke(width, cap, join, miter);
        float phase = (float) (gs.dashPhase * scale);
        if (!(phase >= 0) || Float.isInfinite(phase)) phase = 0;
        return new BasicStroke(width, cap, join, miter, dash, phase);
    }

    /**
     * 点線の区切り (ページの空間)。実線で描くべきもの (空、0 だけ、負の値、細かすぎて区切りが多すぎるもの) は null。
     */
    private float[] dash(double scale, Shape pageShape, double deviceScale) {
        final double[] array = gs.dashArray;
        if (array == null || array.length == 0 || array.length > 64) return null;
        double total = 0;
        for (double value : array) {
            if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) return null;
            total += value;
        }
        if (total <= 0) return null;
        final double period = total * scale * (array.length % 2 == 1 ? 2 : 1);
        // 1 周期が 1 画素より短い点線は、見た目は実線と変わらない
        if (period * deviceScale < 1) return null;
        // 道の長さに対して区切りが多すぎると、Java2D が膨大な図形を作って止まる
        if (pathLength(pageShape) / period > MAX_DASHES) return null;
        final float[] dash = new float[array.length];
        for (int i = 0; i < dash.length; i++) dash[i] = (float) Math.max(array[i] * scale, 1e-4);
        return dash;
    }

    private static double pathLength(Shape shape) {
        final PathIterator it = shape.getPathIterator(null, 1.0);
        final double[] c = new double[6];
        double length = 0, x = 0, y = 0, startX = 0, startY = 0;
        int segments = 0;
        while (!it.isDone() && segments++ < 1_000_000) {
            final int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                x = startX = c[0];
                y = startY = c[1];
            } else if (type == PathIterator.SEG_LINETO) {
                length += Math.hypot(c[0] - x, c[1] - y);
                x = c[0];
                y = c[1];
            } else if (type == PathIterator.SEG_CLOSE) {
                length += Math.hypot(startX - x, startY - y);
                x = startX;
                y = startY;
            }
            it.next();
        }
        return length;
    }

    private static double averageScale(AffineTransform m) {
        final double x = m.getScaleX() + m.getShearX();
        final double y = m.getScaleY() + m.getShearY();
        return Math.sqrt((x * x + y * y) * 0.5);
    }

    // ---------- 文字 ----------

    private void moveText(double tx, double ty) {
        lineMatrix.translate(tx, ty);
        textMatrix = new AffineTransform(lineMatrix);
    }

    private void endText() {
        if (textClip != null) {
            intersectClip(textClip);
            textClip = null;
        }
    }

    private void showArray(PdfArray array) {
        final PdfFont font = gs.font;
        for (int i = 0; i < array.size(); i++) {
            final Object item = array.get(i);
            if (item instanceof PdfString) {
                showText(((PdfString) item).bytes);
            } else if (item instanceof Number) {
                final double adjust = ((Number) item).doubleValue() / 1000 * gs.fontSize;
                if (Double.isNaN(adjust) || Double.isInfinite(adjust)) continue;
                if (font != null && font.isVertical()) {
                    textMatrix.translate(0, -adjust);
                } else {
                    textMatrix.translate(-adjust * gs.horizontalScale, 0);
                }
            }
        }
    }

    private void showText(byte[] text) {
        final PdfFont font = gs.font;
        if (font == null) return;
        final int[] length = new int[1];
        int position = 0;
        while (position < text.length) {
            tick();
            final long character = font.readCode(text, position, length);
            position += Math.max(1, length[0]);
            final boolean wordSpace = font.isWordSpace(character);
            if (font.isVertical()) {
                drawGlyph(font, character, font.verticalOrigin(character));
                final double ty = font.verticalAdvance(character) * gs.fontSize + gs.charSpacing + (wordSpace ? gs.wordSpacing : 0);
                textMatrix.translate(0, ty);
            } else {
                drawGlyph(font, character, null);
                final double tx = (font.width(character) * gs.fontSize + gs.charSpacing + (wordSpace ? gs.wordSpacing : 0)) * gs.horizontalScale;
                textMatrix.translate(tx, 0);
            }
        }
    }

    // 文字空間 (大きさ 1) からページの空間への変換
    private AffineTransform glyphTransform(double[] verticalOrigin) {
        final AffineTransform m = new AffineTransform(gs.ctm);
        m.concatenate(textMatrix);
        m.concatenate(new AffineTransform(gs.fontSize * gs.horizontalScale, 0, 0, gs.fontSize, 0, gs.rise));
        if (verticalOrigin != null) m.translate(-verticalOrigin[0], -verticalOrigin[1]);
        return m;
    }

    private void drawGlyph(PdfFont font, long character, double[] verticalOrigin) {
        if (font.isType3()) {
            if (hiddenDepth == 0 && gs.renderMode != 3 && gs.renderMode != 7) drawType3((Type3Font) font, (int) character);
            return;
        }
        final int mode = gs.renderMode;
        final boolean clip = mode >= 4 && mode <= 7;
        if (hiddenDepth > 0 && !clip) return;
        final GeneralPath glyph = font.path(character);
        if (glyph.getCurrentPoint() == null) return;
        final Shape pageShape = glyphTransform(verticalOrigin).createTransformedShape(glyph);
        if (hiddenDepth == 0) {
            if (mode == 0 || mode == 2 || mode == 4 || mode == 6) fillShape(pageShape, Path2D.WIND_NON_ZERO);
            if (mode == 1 || mode == 2 || mode == 5 || mode == 6) strokeShape(pageShape);
        }
        if (clip) {
            if (textClip == null) textClip = new GeneralPath(GeneralPath.WIND_NON_ZERO);
            textClip.append(pageShape, false);
        }
    }

    private void drawType3(Type3Font font, int code) {
        final PdfStream proc = font.charProc(code);
        if (proc == null || depth >= MAX_DEPTH || activeForms.containsKey(proc)) return;
        final double[] fm = font.fontMatrix();
        final GraphicsState saved = gs;
        final PdfDict savedResources = resources;
        final AffineTransform savedText = textMatrix, savedLine = lineMatrix;
        final Path2D.Double savedPath = path;
        final boolean savedUncolored = uncolored;
        final AffineTransform savedBase = patternBase;
        gs = gs.clone();
        gs.ctm = glyphTransform(null);
        gs.ctm.concatenate(new AffineTransform(fm[0], fm[1], fm[2], fm[3], fm[4], fm[5]));
        if (font.resources() != null) resources = font.resources();
        patternBase = new AffineTransform(gs.ctm);
        path = new Path2D.Double();
        uncolored = false;
        depth++;
        activeForms.put(proc, Boolean.TRUE);
        try {
            run(proc.decode(PdfDocument.MAX_DECODED));
        } catch (IOException e) {
            // 読めないグリフは描かない
        } finally {
            activeForms.remove(proc);
            depth--;
            gs = saved;
            resources = savedResources;
            textMatrix = savedText;
            lineMatrix = savedLine;
            path = savedPath;
            uncolored = savedUncolored;
            patternBase = savedBase;
        }
    }

    // ---------- 内容の印と、表示しない層 ----------

    private void beginMarkedContent(List<Object> operands) {
        boolean hidden = false;
        if (operands.size() >= 2 && "OC".equals(name(operands, 0))) {
            final Object properties = operands.get(1);
            PdfDict dict = null;
            if (properties instanceof PdfName) {
                final PdfDict all = resources == null ? null : resources.dict("Properties");
                dict = all == null ? null : all.dict(((PdfName) properties).name);
            } else if (properties instanceof PdfDict) {
                dict = (PdfDict) properties;
            }
            hidden = cache.isHiddenContent(dict);
        }
        markedContent.push(hidden);
        if (hidden) hiddenDepth++;
    }

    // ---------- XObject ----------

    private void xObject(String key) {
        final PdfDict objects = resources == null ? null : resources.dict("XObject");
        final PdfStream stream = objects == null || key == null ? null : objects.stream(key);
        if (stream == null) return;
        final String subtype = stream.dict.name("Subtype");
        if (cache.isHiddenContent(stream.dict.dict("OC"))) return;
        if (hiddenDepth > 0) return;
        if ("Image".equals(subtype)) {
            drawImage(stream);
        } else if ("Form".equals(subtype)) {
            drawForm(stream, null);
        }
    }

    /**
     * フォームを描く。
     *
     * @param extra フォームの行列の前にかける変換 (注釈の外観で使う。なければ null)
     */
    private void drawForm(PdfStream form, AffineTransform extra) {
        if (depth >= MAX_DEPTH || activeForms.containsKey(form)) return;
        final GraphicsState saved = gs;
        final PdfDict savedResources = resources;
        final Path2D.Double savedPath = path;
        final AffineTransform savedBase = patternBase;
        final AffineTransform savedText = textMatrix, savedLine = lineMatrix;
        final GeneralPath savedTextClip = textClip;
        gs = gs.clone();
        if (extra != null) gs.ctm.concatenate(extra);
        final PdfArray matrix = form.dict.array("Matrix");
        final double[] m = matrix == null ? null : matrix.numbers();
        if (m != null && m.length >= 6) gs.ctm.concatenate(new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]));
        final double[] bbox = PdfDocument.rectangle(form.dict.array("BBox"));
        if (bbox != null) intersectClip(gs.ctm.createTransformedShape(new Rectangle2D.Double(bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1])));
        final PdfDict formResources = form.dict.dict("Resources");
        if (formResources != null) resources = formResources;
        patternBase = new AffineTransform(gs.ctm);
        path = new Path2D.Double();
        depth++;
        activeForms.put(form, Boolean.TRUE);
        try {
            final byte[] content = form.decode(PdfDocument.MAX_DECODED);
            final PdfDict group = form.dict.dict("Group");
            final boolean transparencyGroup = group != null && group.isName("S", "Transparency");
            final boolean needsLayer = transparencyGroup && (gs.fillAlpha < 1 || gs.softMask != null
                    || !(gs.blendMode.equals("Normal") || gs.blendMode.equals("Compatible")));
            if (needsLayer) {
                drawGroupLayer(content, gs.clip);
            } else {
                run(content);
            }
        } catch (IOException e) {
            // 読めないフォームは描かない
        } finally {
            activeForms.remove(form);
            depth--;
            gs = saved;
            resources = savedResources;
            path = savedPath;
            patternBase = savedBase;
            textMatrix = savedText;
            lineMatrix = savedLine;
            textClip = savedTextClip;
        }
    }

    // 透明のグループを別の画像に描いてから、不透明度・ソフトマスク・合成の方法で重ねる
    private void drawGroupLayer(byte[] content, Area clip) {
        final Rectangle area = canvas.deviceBounds(clip == null ? null : clip.getBounds2D(), clip);
        if (area == null) return;
        final double alpha = gs.fillAlpha;
        final SoftMask mask = gs.softMask;
        final String blend = gs.blendMode;
        final Canvas parent = canvas;
        final Canvas layer = new Canvas(area.width, area.height, parent.originX + area.x, parent.originY + area.y,
                pageToDevice(parent), false, true);
        // グループの中は、不透明度とソフトマスクと合成の方法を初めの値に戻して描く
        gs.fillAlpha = 1;
        gs.strokeAlpha = 1;
        gs.softMask = null;
        gs.blendMode = "Normal";
        canvas = layer;
        try {
            run(content);
        } finally {
            canvas = parent;
            layer.dispose();
        }
        parent.compositeLayer(layer, alpha, mask, blend);
    }

    // 画像の base から、原点の分を除いたもの (ページの空間から、ページ全体の画素の座標へ)
    private static AffineTransform pageToDevice(Canvas canvas) {
        final AffineTransform t = AffineTransform.getTranslateInstance(canvas.originX, canvas.originY);
        t.concatenate(canvas.base);
        return t;
    }

    // ---------- ソフトマスク ----------

    private SoftMask softMask(PdfDict dict) {
        if (dict == null || depth >= MAX_DEPTH) return null;
        final PdfStream group = dict.stream("G");
        if (group == null || activeForms.containsKey(group)) return null;
        final boolean luminosity = !dict.isName("S", "Alpha");
        // 背景の色 (グループの色空間の値)
        int backdrop = 0;
        if (luminosity) {
            final PdfArray bc = dict.array("BC");
            final PdfDict groupDict = group.dict.dict("Group");
            PdfColorSpace space = null;
            try {
                if (groupDict != null && groupDict.get("CS") != null) space = ColorSpaces.create(groupDict.get("CS"), resources);
            } catch (IOException e) {
                space = null;
            }
            if (bc != null && space != null) {
                final double[] values = bc.numbers();
                if (values != null && values.length >= space.components()) {
                    final float[] color = new float[space.components()];
                    for (int i = 0; i < color.length; i++) color[i] = (float) values[i];
                    backdrop = space.toRgb(color);
                }
            }
        }
        PdfFunction transfer = null;
        final Object tr = dict.get("TR");
        if (tr != null && !(tr instanceof PdfName)) {
            try {
                transfer = PdfFunction.create(tr);
            } catch (IOException e) {
                transfer = null;
            }
        }
        // マスクを描く範囲: 今の画像のうち、クリップの中
        final Rectangle area = canvas.deviceBounds(gs.clip == null ? null : gs.clip.getBounds2D(), gs.clip);
        final Canvas parent = canvas;
        final int originX = parent.originX + (area == null ? 0 : area.x);
        final int originY = parent.originY + (area == null ? 0 : area.y);
        final int width = area == null ? 1 : area.width, height = area == null ? 1 : area.height;
        final Canvas layer = new Canvas(width, height, originX, originY, pageToDevice(parent), false, true);
        if (luminosity) java.util.Arrays.fill(layer.pixels, 0xFF000000 | backdrop);
        final GraphicsState saved = gs;
        gs = gs.clone();
        gs.fillAlpha = 1;
        gs.strokeAlpha = 1;
        gs.softMask = null;
        gs.blendMode = "Normal";
        canvas = layer;
        try {
            drawForm(group, null);
        } finally {
            canvas = parent;
            gs = saved;
            layer.dispose();
        }
        final byte[] values = new byte[width * height];
        for (int i = 0; i < values.length; i++) {
            final int p = layer.pixels[i];
            values[i] = (byte) (luminosity ? luminosity(Canvas.over(p, p >>> 24, 0xFF000000 | backdrop)) : p >>> 24);
        }
        int outside = luminosity ? luminosity(backdrop) : 0;
        if (transfer != null) {
            final int[] table = new int[256];
            final double[] in = new double[1], out = new double[Math.max(1, transfer.outputs())];
            for (int v = 0; v < 256; v++) {
                in[0] = v / 255.0;
                transfer.evaluate(in, out);
                table[v] = (int) Math.round(clamp01(out[0]) * 255);
            }
            for (int i = 0; i < values.length; i++) values[i] = (byte) table[values[i] & 0xFF];
            outside = table[outside];
        }
        return new SoftMask(originX, originY, width, height, values, outside);
    }

    private static int luminosity(int rgb) {
        final int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.min(255, (int) Math.round(0.3 * r + 0.59 * g + 0.11 * b));
    }

    // ---------- 画像 ----------

    private void drawImage(PdfStream stream) {
        // 単位の正方形を、画素の大きさにしたもの
        final AffineTransform toDevice = new AffineTransform(canvas.base);
        toDevice.concatenate(gs.ctm);
        final double deviceWidth = Math.hypot(toDevice.getScaleX(), toDevice.getShearY());
        final double deviceHeight = Math.hypot(toDevice.getShearX(), toDevice.getScaleY());
        final int targetWidth = (int) Math.max(1, Math.min(1 << 15, Math.ceil(deviceWidth)));
        final int targetHeight = (int) Math.max(1, Math.min(1 << 15, Math.ceil(deviceHeight)));
        ImageDecoder.Result result = images.get(stream);
        if (result == null || result.image.getWidth() < Math.min(result.sourceWidth, targetWidth)
                || result.image.getHeight() < Math.min(result.sourceHeight, targetHeight)) {
            try {
                result = ImageDecoder.decode(stream, resources, targetWidth, targetHeight);
            } catch (IOException | RuntimeException e) {
                return;
            }
            if (images.size() > 16) images.clear();
            images.put(stream, result);
        }
        BufferedImage image = result.image;
        // 描く大きさより十分に大きい画像は、平均をとって縮めておく (Java2D の縮小は粗い)
        if (image.getWidth() > 2 * targetWidth || image.getHeight() > 2 * targetHeight) {
            image = downscale(image, Math.min(image.getWidth(), Math.max(1, targetWidth)), Math.min(image.getHeight(), Math.max(1, targetHeight)));
        }
        final int w = image.getWidth(), h = image.getHeight();
        // 画像の空間 (左上が原点、画素の単位) から、ページの空間へ
        final AffineTransform imageToPage = new AffineTransform(gs.ctm);
        imageToPage.translate(0, 1);
        imageToPage.scale(1.0 / w, -1.0 / h);
        final boolean smooth = result.interpolate || deviceWidth < result.sourceWidth || deviceHeight < result.sourceHeight;
        final Shape unit = gs.ctm.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1));
        if (result.stencil) {
            final Paint paint = paint(false);
            if (paint == null) return;
            if (paint instanceof Color) {
                final BufferedImage colored = colorize(image, ((Color) paint).getRGB());
                drawBufferedImage(colored, imageToPage, unit, smooth);
            } else {
                drawStencilWithPaint(image, imageToPage, unit, paint);
            }
            return;
        }
        drawBufferedImage(image, imageToPage, unit, smooth);
    }

    private void drawBufferedImage(final BufferedImage image, final AffineTransform imageToPage, Shape bounds, final boolean smooth) {
        canvas.paint(new Canvas.Painter() {
            @Override
            public void paint(Graphics2D g) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, imageToPage, null);
            }
        }, bounds.getBounds2D(), gs.fillAlpha, gs.softMask, gs.blendMode, gs.clip);
    }

    private static BufferedImage colorize(BufferedImage mask, int rgb) {
        final BufferedImage result = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final int[] in = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        final int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < in.length; i++) out[i] = (in[i] & 0xFF000000) | (rgb & 0xFFFFFF);
        return result;
    }

    // パターンで塗るステンシル: パターンを塗った画像に、マスクの不透明度をかけてから重ねる
    private void drawStencilWithPaint(BufferedImage mask, AffineTransform imageToPage, Shape bounds, final Paint paint) {
        final Rectangle area = canvas.deviceBounds(bounds.getBounds2D(), gs.clip);
        if (area == null) return;
        final AffineTransform toLayer = AffineTransform.getTranslateInstance(-area.x, -area.y);
        toLayer.concatenate(canvas.base);
        final BufferedImage coverage = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage painted = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = coverage.createGraphics();
        try {
            Canvas.hints(g, true);
            g.setTransform(toLayer);
            g.drawImage(mask, imageToPage, null);
        } finally {
            g.dispose();
        }
        g = painted.createGraphics();
        try {
            Canvas.hints(g, true);
            g.setTransform(toLayer);
            g.setClip(gs.clip);
            g.setPaint(paint);
            g.fill(bounds);
        } finally {
            g.dispose();
        }
        final int[] c = ((DataBufferInt) coverage.getRaster().getDataBuffer()).getData();
        final int[] p = ((DataBufferInt) painted.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < p.length; i++) {
            final int alpha = (p[i] >>> 24) * (c[i] >>> 24) / 255;
            p[i] = (alpha << 24) | (p[i] & 0xFFFFFF);
        }
        canvas.composite(p, area, gs.fillAlpha, gs.softMask,
                gs.blendMode.equals("Normal") || gs.blendMode.equals("Compatible") ? null : gs.blendMode);
    }

    // 面積の平均で縮める
    private static BufferedImage downscale(BufferedImage source, int width, int height) {
        final int sw = source.getWidth(), sh = source.getHeight();
        final int[] in = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] out = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            final int y0 = (int) ((long) y * sh / height), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / height));
            for (int x = 0; x < width; x++) {
                final int x0 = (int) ((long) x * sw / width), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / width));
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        final int p = in[yy * sw + xx];
                        final int pa = p >>> 24;
                        a += pa;
                        // 不透明度で重みを付けて、透明な画素の色が混ざらないようにする
                        r += ((p >> 16) & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                        n++;
                    }
                }
                final int alpha = (int) (a / n);
                out[y * width + x] = a == 0 ? 0 : (alpha << 24) | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        }
        return result;
    }

    // ---------- シェーディングとパターン ----------

    private void shade(String key) {
        final PdfDict shadings = resources == null ? null : resources.dict("Shading");
        final Object value = shadings == null || key == null ? null : shadings.get(key);
        if (value == null) return;
        final Shading shading;
        try {
            shading = Shading.create(value, resources);
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (shading.space.isNone()) return;
        final Paint paint = new ShadingPaint(shading, new AffineTransform(gs.ctm));
        // 塗る範囲: クリップの中 (シェーディングの BBox があれば、その中)
        Shape region = gs.clip != null ? gs.clip : canvasBoundsInPage();
        if (region == null) return;
        if (shading.bbox != null) {
            final Area area = new Area(region);
            area.intersect(new Area(gs.ctm.createTransformedShape(new Rectangle2D.Double(shading.bbox[0], shading.bbox[1],
                    shading.bbox[2] - shading.bbox[0], shading.bbox[3] - shading.bbox[1]))));
            region = area;
        }
        final Shape fill = region;
        canvas.paint(new Canvas.Painter() {
            @Override
            public void paint(Graphics2D g) {
                g.setPaint(paint);
                g.fill(fill);
            }
        }, fill.getBounds2D(), gs.fillAlpha, gs.softMask, gs.blendMode, gs.clip);
    }

    private Shape canvasBoundsInPage() {
        try {
            return canvas.base.createInverse().createTransformedShape(new Rectangle(0, 0, canvas.width, canvas.height));
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    private Paint patternPaint(Object pattern, PdfColorSpace base, float[] color) {
        if (pattern == null) return null;
        final PdfDict dict = pattern instanceof PdfStream ? ((PdfStream) pattern).dict : pattern instanceof PdfDict ? (PdfDict) pattern : null;
        if (dict == null) return null;
        final AffineTransform toPage = new AffineTransform(patternBase);
        final PdfArray matrix = dict.array("Matrix");
        final double[] m = matrix == null ? null : matrix.numbers();
        if (m != null && m.length >= 6) toPage.concatenate(new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]));
        if (dict.integer("PatternType", 1) == 2) {
            try {
                final Shading shading = Shading.create(dict.get("Shading"), resources);
                return shading.space.isNone() ? null : new ShadingPaint(shading, toPage);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        if (!(pattern instanceof PdfStream)) return null;
        Color uncoloredColor = null;
        if (dict.integer("PaintType", 1) == 2) {
            if (base == null) return null;
            try {
                uncoloredColor = new Color(base.toRgb(color));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return tilingPaint((PdfStream) pattern, toPage, uncoloredColor);
    }

    private Paint tilingPaint(PdfStream pattern, AffineTransform toPage, Color uncoloredColor) {
        if (depth >= MAX_DEPTH || activeForms.containsKey(pattern)) return null;
        final double[] m = new double[6];
        toPage.getMatrix(m);
        final String key = java.util.Arrays.toString(m) + (uncoloredColor == null ? "" : Integer.toHexString(uncoloredColor.getRGB()))
                + "@" + System.identityHashCode(canvas);
        Map<String, Paint> cached = tilings.get(pattern);
        if (cached != null && cached.containsKey(key)) return cached.get(key);
        final Paint paint = createTilingPaint(pattern, toPage, uncoloredColor);
        if (cached == null) {
            if (tilings.size() > 64) tilings.clear();
            cached = new java.util.HashMap<>();
            tilings.put(pattern, cached);
        }
        if (cached.size() < 16) cached.put(key, paint);
        return paint;
    }

    private Paint createTilingPaint(PdfStream pattern, AffineTransform toPage, Color uncoloredColor) {
        final PdfDict dict = pattern.dict;
        final double[] bbox = PdfDocument.rectangle(dict.array("BBox"));
        double xStep = Math.abs(dict.number("XStep", 0)), yStep = Math.abs(dict.number("YStep", 0));
        if (bbox == null) return null;
        if (xStep == 0) xStep = bbox[2] - bbox[0];
        if (yStep == 0) yStep = bbox[3] - bbox[1];
        if (!(xStep > 0) || !(yStep > 0)) return null;
        // タイルの絵の範囲 (パターンの空間): 周期と BBox の小さいほう
        final double regionWidth = Math.min(xStep, bbox[2] - bbox[0]);
        final double regionHeight = Math.min(yStep, bbox[3] - bbox[1]);
        if (!(regionWidth > 0) || !(regionHeight > 0)) return null;
        // 絵の大きさ: パターンの空間の単位が、画素でどれだけになるか
        final AffineTransform toDevice = new AffineTransform(canvas.base);
        toDevice.concatenate(toPage);
        final double unitX = Math.hypot(toDevice.getScaleX(), toDevice.getShearY());
        final double unitY = Math.hypot(toDevice.getShearX(), toDevice.getScaleY());
        final int cellWidth = (int) Math.max(1, Math.min(MAX_CELL_SIDE, Math.ceil(regionWidth * unitX)));
        final int cellHeight = (int) Math.max(1, Math.min(MAX_CELL_SIDE, Math.ceil(regionHeight * unitY)));
        // パターンの空間から、絵の画素へ (左上が (bbox.x0, bbox.y0 + regionHeight))
        final AffineTransform cellBase = AffineTransform.getScaleInstance(cellWidth / regionWidth, -cellHeight / regionHeight);
        cellBase.translate(-bbox[0], -(bbox[1] + regionHeight));
        final Canvas cell = new Canvas(cellWidth, cellHeight, 0, 0, cellBase, false, true);
        final byte[] content;
        try {
            content = pattern.decode(PdfDocument.MAX_DECODED);
        } catch (IOException e) {
            return null;
        }
        final Canvas savedCanvas = canvas;
        final GraphicsState savedState = gs;
        final PdfDict savedResources = resources;
        final AffineTransform savedBase = patternBase;
        final Path2D.Double savedPath = path;
        final boolean savedUncolored = uncolored;
        final int savedHidden = hiddenDepth;
        canvas = cell;
        depth++;
        activeForms.put(pattern, Boolean.TRUE);
        try {
            // 隣のタイルからはみ出す分も描く (BBox が XStep / YStep より大きいとき)
            final int repeatX = (int) Math.min(4, Math.ceil((bbox[2] - bbox[0]) / xStep));
            final int repeatY = (int) Math.min(4, Math.ceil((bbox[3] - bbox[1]) / yStep));
            for (int i = -repeatX; i <= repeatX; i++) {
                for (int j = -repeatY; j <= repeatY; j++) {
                    gs = new GraphicsState();
                    gs.ctm = AffineTransform.getTranslateInstance(i * xStep, j * yStep);
                    gs.clip = new Area(new Rectangle2D.Double(bbox[0] + i * xStep, bbox[1] + j * yStep, bbox[2] - bbox[0], bbox[3] - bbox[1]));
                    if (uncoloredColor != null) {
                        gs.fillSpace = DeviceSpaces.RGB;
                        gs.strokeSpace = DeviceSpaces.RGB;
                        gs.fillColor = rgbComponents(uncoloredColor);
                        gs.strokeColor = rgbComponents(uncoloredColor);
                    }
                    final PdfDict patternResources = dict.dict("Resources");
                    resources = patternResources != null ? patternResources : savedResources;
                    patternBase = new AffineTransform(gs.ctm);
                    path = new Path2D.Double();
                    uncolored = uncoloredColor != null;
                    hiddenDepth = 0;
                    run(content);
                }
            }
        } finally {
            activeForms.remove(pattern);
            depth--;
            canvas = savedCanvas;
            gs = savedState;
            resources = savedResources;
            patternBase = savedBase;
            path = savedPath;
            uncolored = savedUncolored;
            hiddenDepth = savedHidden;
            cell.dispose();
        }
        return new TilingPaint(cell.image, bbox[0], bbox[1], xStep, yStep, regionWidth, regionHeight, toPage);
    }

    private static float[] rgbComponents(Color color) {
        return new float[]{color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f};
    }

    // ---------- 注釈 ----------

    private void annotations(PdfDocument.Page page) {
        final PdfArray annotations = page.dict.array("Annots");
        if (annotations == null) return;
        for (int i = 0; i < annotations.size() && i < 10000; i++) {
            final PdfDict annotation = annotations.dict(i);
            if (annotation == null) continue;
            try {
                annotation(annotation);
            } catch (LimitReached e) {
                throw e;
            } catch (RuntimeException e) {
                // 壊れた注釈は飛ばす
            }
        }
    }

    private void annotation(PdfDict annotation) {
        final int flags = annotation.integer("F", 0);
        // Hidden (2) と NoView (32) は描かない
        if ((flags & 2) != 0 || (flags & 32) != 0) return;
        if (cache.isHiddenContent(annotation.dict("OC"))) return;
        final double[] rect = PdfDocument.rectangle(annotation.array("Rect"));
        if (rect == null || rect[2] - rect[0] <= 0 || rect[3] - rect[1] <= 0) return;
        final PdfDict appearances = annotation.dict("AP");
        if (appearances == null) return;
        final Object normal = appearances.get("N");
        PdfStream form = null;
        if (normal instanceof PdfStream) {
            form = (PdfStream) normal;
        } else if (normal instanceof PdfDict) {
            final String state = annotation.name("AS");
            if (state != null) form = ((PdfDict) normal).stream(state);
        }
        if (form == null) return;
        final double[] bbox = PdfDocument.rectangle(form.dict.array("BBox"));
        if (bbox == null) return;
        // 12.5.5: 外観の BBox を Matrix で変換した範囲を、注釈の Rect に合わせる
        final PdfArray matrixArray = form.dict.array("Matrix");
        final double[] m = matrixArray == null ? null : matrixArray.numbers();
        final AffineTransform matrix = m != null && m.length >= 6 ? new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]) : new AffineTransform();
        final Rectangle2D transformed = matrix.createTransformedShape(new Rectangle2D.Double(bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1])).getBounds2D();
        if (transformed.getWidth() <= 0 || transformed.getHeight() <= 0) return;
        final AffineTransform a = new AffineTransform();
        a.translate(rect[0], rect[1]);
        a.scale((rect[2] - rect[0]) / transformed.getWidth(), (rect[3] - rect[1]) / transformed.getHeight());
        a.translate(-transformed.getX(), -transformed.getY());
        final GraphicsState saved = gs;
        gs = new GraphicsState();
        gs.clip = saved.clip;
        try {
            drawForm(form, a);
        } finally {
            gs = saved;
        }
    }
}
