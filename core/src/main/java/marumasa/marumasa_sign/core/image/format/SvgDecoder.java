package marumasa.marumasa_sign.core.image.format;

import marumasa.marumasa_sign.core.image.ArgbPixels;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageBytes;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.MultipleGradientPaint;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * SVG を、Java 2D で絵にする。
 * 図形・パス・グループ・use・変換・色・不透明度・線の種類・線形と放射の勾配・切り抜き・文字・埋め込み画像・style 属性と style 要素に対応する。
 * フィルター・マスク・パターン・マーカー・アニメーション・外部の読み込みには対応しない (外部へは一切アクセスしない)。
 */
final class SvgDecoder {

    // 絵の長い辺は、小さい SVG は 1024 まで拡大し、大きいものは 2048 に収める
    private static final int TARGET_SIZE = 1024;
    private static final int MAX_SIZE = 2048;
    private static final int MAX_ELEMENTS = 200000;
    private static final int MAX_TEXT = 32 * 1024 * 1024;

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
        byte[] data = d;
        if (isGzip(d, d.length)) data = inflate(d);
        if (data.length > MAX_TEXT) throw new IOException("SVG is too large");
        final Document document = parse(data);
        final Element root = document.getDocumentElement();
        if (root == null || !"svg".equals(localName(root))) throw new IOException("Not an SVG image");
        return new Renderer(document, root).render();
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

    // 外部の DTD や実体を読まない設定で XML を読む (ネットワークやファイルにアクセスさせない)
    private static Document parse(byte[] data) throws IOException {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new java.io.StringReader(""));
                }
            });
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(data));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid SVG: " + e.getMessage(), e);
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException e) {
            // その設定を知らない XML パーサーでは、外部を読む機能もないので、そのまま進める
        }
    }

    private static String localName(Node node) {
        final String name = node.getLocalName();
        return name != null ? name : node.getNodeName();
    }

    // ---------- 色と長さ ----------

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private static double[] numbers(String text) {
        final List<Double> list = new ArrayList<Double>();
        final Matcher m = NUMBER.matcher(text);
        while (m.find()) list.add(Double.parseDouble(m.group()));
        final double[] result = new double[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
    }

    // 色を ARGB にする。色として読めなければ null
    static Integer color(String spec, int current) {
        if (spec == null) return null;
        final String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
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
            final String[] parts = s.substring(open + 1, s.length() - 1).split("[,\\s/]+");
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
                    return (parts.length > 3 ? alpha(parts[3]) << 24 : 0xFF000000) | (hsl(hue, sat, light) & 0xFFFFFF);
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
        return FormatUtil.clamp8((int) Math.round(v * 255));
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
        boolean evenOdd;
        int cap = BasicStroke.CAP_BUTT, join = BasicStroke.JOIN_MITER;
        float miterLimit = 4;
        float[] dash;
        float dashOffset;
        int color = 0xFF000000;
        double fontSize = 16;
        String fontFamily = "sans-serif";
        boolean bold, italic;
        String textAnchor = "start";
        boolean visible = true;
        boolean hidden;
        String clipPath;

        Style copy() {
            try {
                final Style s = (Style) clone();
                // 継承しない性質は元に戻す
                s.opacity = 1;
                s.hidden = false;
                s.clipPath = null;
                return s;
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Rule {
        final String selector;
        final String body;
        final int specificity;

        Rule(String selector, String body, int specificity) {
            this.selector = selector;
            this.body = body;
            this.specificity = specificity;
        }
    }

    // ---------- 描画 ----------

    private static final class Renderer {
        private final Element root;
        private final Map<String, Element> ids = new HashMap<String, Element>();
        private final List<Rule> rules = new ArrayList<Rule>();
        private final FontRenderContext fontContext = new FontRenderContext(null, true, true);
        private Graphics2DHolder holder;
        private int width, height;
        private double viewportWidth, viewportHeight;
        private int elements;

        Renderer(Document document, Element root) {
            this.root = root;
            index(root);
        }

        private void index(Element e) {
            final String id = e.getAttribute("id");
            if (id != null && !id.isEmpty() && !ids.containsKey(id)) ids.put(id, e);
            if ("style".equals(localName(e))) addRules(e.getTextContent());
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) index((Element) n);
            }
        }

        private void addRules(String css) {
            final String text = css.replaceAll("(?s)/\\*.*?\\*/", "");
            final Matcher m = Pattern.compile("([^{}@]+)\\{([^{}]*)\\}").matcher(text);
            while (m.find()) {
                for (String selector : m.group(1).split(",")) {
                    final String s = selector.trim();
                    if (s.isEmpty()) continue;
                    // 子孫や子のセレクターは、いちばん後ろの要素だけで判定する
                    final String[] parts = s.split("[\\s>+~]+");
                    final String last = parts[parts.length - 1];
                    int specificity = 0;
                    if (last.contains("#")) specificity += 100;
                    specificity += 10 * (last.length() - last.replace(".", "").length());
                    if (!last.startsWith(".") && !last.startsWith("#") && !last.equals("*")) specificity += 1;
                    rules.add(new Rule(last, m.group(2), specificity));
                }
            }
            Collections.sort(rules, new Comparator<Rule>() {
                @Override
                public int compare(Rule a, Rule b) {
                    return Integer.compare(a.specificity, b.specificity);
                }
            });
        }

        private boolean matches(Rule rule, Element e) {
            final String sel = rule.selector;
            if (sel.equals("*")) return true;
            String tag = null;
            String id = null;
            final List<String> classes = new ArrayList<String>();
            final Matcher m = Pattern.compile("([.#]?)([\\w-]+)").matcher(sel);
            while (m.find()) {
                if (m.group(1).equals("#")) id = m.group(2);
                else if (m.group(1).equals(".")) classes.add(m.group(2));
                else tag = m.group(2);
            }
            if (sel.contains(":") || sel.contains("[")) return false;
            if (tag != null && !tag.equals(localName(e))) return false;
            if (id != null && !id.equals(e.getAttribute("id"))) return false;
            final List<String> have = Arrays.asList(e.getAttribute("class").trim().split("\\s+"));
            return have.containsAll(classes);
        }

        // 要素のスタイルを、親から引き継いだものに、属性・style 要素・style 属性の順に重ねて作る
        private Style styleOf(Element e, Style parent) {
            final Style s = parent.copy();
            final NamedNodeMap attributes = e.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                final Node a = attributes.item(i);
                apply(s, localName(a), a.getNodeValue());
            }
            for (Rule rule : rules) {
                if (matches(rule, e)) applyDeclarations(s, rule.body);
            }
            applyDeclarations(s, e.getAttribute("style"));
            return s;
        }

        private void applyDeclarations(Style s, String body) {
            if (body == null || body.isEmpty()) return;
            for (String declaration : body.split(";")) {
                final int colon = declaration.indexOf(':');
                if (colon <= 0) continue;
                apply(s, declaration.substring(0, colon).trim(), declaration.substring(colon + 1).replace("!important", "").trim());
            }
        }

        private void apply(Style s, String name, String value) {
            if (value == null || value.isEmpty() || value.equals("inherit")) return;
            try {
                switch (name) {
                    case "fill": s.fill = value; break;
                    case "stroke": s.stroke = value; break;
                    case "stroke-width": s.strokeWidth = length(value, viewportWidth, s.fontSize); break;
                    case "fill-opacity": s.fillOpacity = opacity(value); break;
                    case "stroke-opacity": s.strokeOpacity = opacity(value); break;
                    case "opacity": s.opacity = opacity(value); break;
                    case "fill-rule": s.evenOdd = value.equals("evenodd"); break;
                    case "stroke-linecap":
                        s.cap = value.equals("round") ? BasicStroke.CAP_ROUND : (value.equals("square") ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT);
                        break;
                    case "stroke-linejoin":
                        s.join = value.equals("round") ? BasicStroke.JOIN_ROUND : (value.equals("bevel") ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER);
                        break;
                    case "stroke-miterlimit": s.miterLimit = (float) Math.max(1, Double.parseDouble(value)); break;
                    case "stroke-dasharray": s.dash = dash(value, s); break;
                    case "stroke-dashoffset": s.dashOffset = (float) length(value, viewportWidth, s.fontSize); break;
                    case "color": {
                        final Integer c = color(value, s.color);
                        if (c != null) s.color = c;
                        break;
                    }
                    case "font-size": s.fontSize = length(value, 16, s.fontSize); break;
                    case "font-family": s.fontFamily = value; break;
                    case "font-weight": s.bold = value.equals("bold") || value.equals("bolder") || (NUMBER.matcher(value).matches() && Double.parseDouble(value) >= 600); break;
                    case "font-style": s.italic = value.equals("italic") || value.equals("oblique"); break;
                    case "text-anchor": s.textAnchor = value; break;
                    case "display": s.hidden = value.equals("none"); break;
                    case "visibility": s.visible = value.equals("visible"); break;
                    case "clip-path": s.clipPath = value; break;
                    default: break;
                }
            } catch (RuntimeException e) {
                // 値が読めない指定は無視する
            }
        }

        private double opacity(String value) {
            final String v = value.trim();
            final double x = v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100 : Double.parseDouble(v);
            return Math.max(0, Math.min(1, x));
        }

        private float[] dash(String value, Style s) {
            if (value.equals("none")) return null;
            final String[] parts = value.trim().split("[\\s,]+");
            final float[] result = new float[parts.length % 2 == 0 ? parts.length : parts.length * 2];
            float sum = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] = (float) length(parts[i % parts.length], viewportWidth, s.fontSize);
                if (result[i] < 0) return null;
                sum += result[i];
            }
            return sum > 0 ? result : null;
        }

        // 長さを px にする。relative は % の基準
        private double length(String value, double relative, double fontSize) {
            final String v = value.trim();
            final Matcher m = NUMBER.matcher(v);
            if (!m.lookingAt()) return 0;
            final double n = Double.parseDouble(m.group());
            final String unit = v.substring(m.end()).trim();
            switch (unit) {
                case "pt": return n * 4 / 3;
                case "pc": return n * 16;
                case "mm": return n * 96 / 25.4;
                case "cm": return n * 96 / 2.54;
                case "in": return n * 96;
                case "em": return n * fontSize;
                case "ex": return n * fontSize / 2;
                case "%": return n * relative / 100;
                default: return n;
            }
        }

        private double attr(Element e, String name, double relative, double fallback, Style s) {
            if (!e.hasAttribute(name)) return fallback;
            return length(e.getAttribute(name), relative, s.fontSize);
        }

        // ---------- 全体 ----------

        DecodedImage render() throws IOException {
            double[] viewBox = null;
            if (root.hasAttribute("viewBox")) {
                final double[] v = numbers(root.getAttribute("viewBox"));
                if (v.length == 4 && v[2] > 0 && v[3] > 0) viewBox = v;
            }
            final Style base = new Style();
            double w = Double.NaN, h = Double.NaN;
            final String ws = root.getAttribute("width"), hs = root.getAttribute("height");
            if (!ws.isEmpty() && !ws.endsWith("%")) w = length(ws, 0, 16);
            if (!hs.isEmpty() && !hs.endsWith("%")) h = length(hs, 0, 16);
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
            if (!(w > 0) || !(h > 0)) throw new IOException("SVG has no size");

            final double longSide = Math.max(w, h);
            final double scale = longSide < TARGET_SIZE ? TARGET_SIZE / longSide : (longSide > MAX_SIZE ? MAX_SIZE / longSide : 1);
            width = Math.max(1, (int) Math.round(w * scale));
            height = Math.max(1, (int) Math.round(h * scale));
            FormatUtil.checkSize(width, height);

            final BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            holder = new Graphics2DHolder(canvas);
            final AffineTransform ctm = AffineTransform.getScaleInstance(width / w, height / h);
            if (viewBox != null) {
                ctm.concatenate(viewBoxTransform(viewBox, w, h, root.getAttribute("preserveAspectRatio")));
                viewportWidth = viewBox[2];
                viewportHeight = viewBox[3];
            } else {
                viewportWidth = w;
                viewportHeight = h;
            }
            drawChildren(root, styleOf(root, base), ctm);
            holder.graphics().dispose();
            return FormatUtil.still(width, height, ArgbPixels.readDisplay(canvas));
        }

        private AffineTransform viewBoxTransform(double[] vb, double w, double h, String aspect) {
            final String[] parts = aspect.trim().split("\\s+");
            final String align = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "xMidYMid";
            final boolean slice = parts.length > 1 && parts[1].equals("slice");
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

        private void drawChildren(Element parent, Style style, AffineTransform ctm) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) draw((Element) n, style, ctm, 0);
            }
        }

        private void draw(Element e, Style parentStyle, AffineTransform ctm, int depth) {
            if (++elements > MAX_ELEMENTS || depth > 24) return;
            final String name = localName(e);
            switch (name) {
                case "g": case "a": case "switch": case "svg": case "use": case "symbol":
                case "rect": case "circle": case "ellipse": case "line": case "polyline": case "polygon": case "path":
                case "text": case "image":
                    break;
                default:
                    return;
            }
            final Style style = styleOf(e, parentStyle);
            if (style.hidden) return;
            final AffineTransform t = new AffineTransform(ctm);
            final AffineTransform own = transform(e.getAttribute("transform"));
            if (own != null) t.concatenate(own);

            if (style.opacity >= 1) {
                paint(e, name, style, t, depth);
                return;
            }
            // 不透明度は、グループ全体を一度描いてから重ねる
            final Graphics2DHolder saved = holder;
            final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            holder = new Graphics2DHolder(image);
            try {
                paint(e, name, style, t, depth);
            } finally {
                holder.graphics().dispose();
                holder = saved;
            }
            final java.awt.Graphics2D g = holder.graphics();
            final Shape oldClip = g.getClip();
            final AffineTransform oldTransform = g.getTransform();
            g.setTransform(new AffineTransform());
            g.setClip(null);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) style.opacity));
            g.drawImage(image, 0, 0, null);
            g.setComposite(AlphaComposite.SrcOver);
            g.setTransform(oldTransform);
            g.setClip(oldClip);
        }

        private void paint(Element e, String name, Style style, AffineTransform t, int depth) {
            final java.awt.Graphics2D g = holder.graphics();
            final Shape oldClip = g.getClip();
            if (style.clipPath != null && !style.clipPath.equals("none")) {
                final Shape clip = clipShape(style.clipPath, t, e);
                if (clip != null) {
                    g.setTransform(new AffineTransform());
                    g.clip(clip);
                }
            }
            try {
                switch (name) {
                    case "g": case "a": case "switch": case "symbol":
                        drawChildren2(e, style, t, depth);
                        break;
                    case "svg": {
                        // 入れ子の svg は、位置をずらして中身を描く
                        final AffineTransform inner = new AffineTransform(t);
                        inner.translate(attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style));
                        if (e.hasAttribute("viewBox")) {
                            final double[] vb = numbers(e.getAttribute("viewBox"));
                            final double w = attr(e, "width", viewportWidth, vb.length == 4 ? vb[2] : 0, style);
                            final double h = attr(e, "height", viewportHeight, vb.length == 4 ? vb[3] : 0, style);
                            if (vb.length == 4 && vb[2] > 0 && vb[3] > 0 && w > 0 && h > 0) {
                                inner.concatenate(viewBoxTransform(vb, w, h, e.getAttribute("preserveAspectRatio")));
                            }
                        }
                        drawChildren2(e, style, inner, depth);
                        break;
                    }
                    case "use": {
                        final String href = href(e);
                        final Element target = href != null && href.startsWith("#") ? ids.get(href.substring(1)) : null;
                        if (target != null && target != e) {
                            final AffineTransform inner = new AffineTransform(t);
                            inner.translate(attr(e, "x", viewportWidth, 0, style), attr(e, "y", viewportHeight, 0, style));
                            draw(target, style, inner, depth + 1);
                        }
                        break;
                    }
                    case "text":
                        drawText(e, style, t);
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
            } finally {
                g.setClip(oldClip);
            }
        }

        private void drawChildren2(Element e, Style style, AffineTransform t, int depth) {
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) draw((Element) n, style, t, depth + 1);
            }
        }

        private String href(Element e) {
            String h = e.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            if (h == null || h.isEmpty()) h = e.getAttribute("href");
            if (h == null || h.isEmpty()) h = e.getAttribute("xlink:href");
            return h == null || h.isEmpty() ? null : h.trim();
        }

        // ---------- 図形 ----------

        private Shape shape(Element e, String name, Style s) {
            switch (name) {
                case "rect": {
                    final double x = attr(e, "x", viewportWidth, 0, s), y = attr(e, "y", viewportHeight, 0, s);
                    final double w = attr(e, "width", viewportWidth, 0, s), h = attr(e, "height", viewportHeight, 0, s);
                    if (!(w > 0) || !(h > 0)) return null;
                    double rx = attr(e, "rx", viewportWidth, -1, s), ry = attr(e, "ry", viewportHeight, -1, s);
                    if (rx < 0 && ry < 0) return new Rectangle2D.Double(x, y, w, h);
                    if (rx < 0) rx = ry;
                    if (ry < 0) ry = rx;
                    return new RoundRectangle2D.Double(x, y, w, h, Math.min(rx, w / 2) * 2, Math.min(ry, h / 2) * 2);
                }
                case "circle": {
                    final double r = attr(e, "r", Math.sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2), 0, s);
                    if (!(r > 0)) return null;
                    final double cx = attr(e, "cx", viewportWidth, 0, s), cy = attr(e, "cy", viewportHeight, 0, s);
                    return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
                }
                case "ellipse": {
                    final double rx = attr(e, "rx", viewportWidth, 0, s), ry = attr(e, "ry", viewportHeight, 0, s);
                    if (!(rx > 0) || !(ry > 0)) return null;
                    final double cx = attr(e, "cx", viewportWidth, 0, s), cy = attr(e, "cy", viewportHeight, 0, s);
                    return new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
                }
                case "line":
                    return new Line2D.Double(attr(e, "x1", viewportWidth, 0, s), attr(e, "y1", viewportHeight, 0, s),
                            attr(e, "x2", viewportWidth, 0, s), attr(e, "y2", viewportHeight, 0, s));
                case "polyline":
                case "polygon": {
                    final double[] p = numbers(e.getAttribute("points"));
                    if (p.length < 4) return null;
                    final Path2D.Double path = new Path2D.Double(s.evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                    path.moveTo(p[0], p[1]);
                    for (int i = 2; i + 1 < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
                    if (name.equals("polygon")) path.closePath();
                    return path;
                }
                case "path":
                    return path(e.getAttribute("d"), s.evenOdd);
                default:
                    return null;
            }
        }

        // パスのデータ (d) を Path2D にする
        private Path2D path(String d, boolean evenOdd) {
            final Path2D.Double path = new Path2D.Double(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            final PathReader in = new PathReader(d);
            double x = 0, y = 0, startX = 0, startY = 0, lastCx = 0, lastCy = 0;
            char command = 0, previous = 0;
            try {
                while (in.skipSeparators()) {
                    if (in.isCommand()) command = in.command();
                    else if (command == 0) break;
                    else if (command == 'M') command = 'L';
                    else if (command == 'm') command = 'l';
                    final boolean relative = Character.isLowerCase(command);
                    final double ox = relative ? x : 0, oy = relative ? y : 0;
                    switch (Character.toUpperCase(command)) {
                        case 'M': {
                            x = ox + in.number();
                            y = oy + in.number();
                            path.moveTo(x, y);
                            startX = x;
                            startY = y;
                            break;
                        }
                        case 'L':
                            x = ox + in.number();
                            y = oy + in.number();
                            path.lineTo(x, y);
                            break;
                        case 'H':
                            x = ox + in.number();
                            path.lineTo(x, y);
                            break;
                        case 'V':
                            y = oy + in.number();
                            path.lineTo(x, y);
                            break;
                        case 'C': {
                            final double x1 = ox + in.number(), y1 = oy + in.number();
                            final double x2 = ox + in.number(), y2 = oy + in.number();
                            x = ox + in.number();
                            y = oy + in.number();
                            path.curveTo(x1, y1, x2, y2, x, y);
                            lastCx = x2;
                            lastCy = y2;
                            break;
                        }
                        case 'S': {
                            final boolean smooth = Character.toUpperCase(previous) == 'C' || Character.toUpperCase(previous) == 'S';
                            final double x1 = smooth ? 2 * x - lastCx : x, y1 = smooth ? 2 * y - lastCy : y;
                            final double x2 = ox + in.number(), y2 = oy + in.number();
                            x = ox + in.number();
                            y = oy + in.number();
                            path.curveTo(x1, y1, x2, y2, x, y);
                            lastCx = x2;
                            lastCy = y2;
                            break;
                        }
                        case 'Q': {
                            final double x1 = ox + in.number(), y1 = oy + in.number();
                            x = ox + in.number();
                            y = oy + in.number();
                            path.quadTo(x1, y1, x, y);
                            lastCx = x1;
                            lastCy = y1;
                            break;
                        }
                        case 'T': {
                            final boolean smooth = Character.toUpperCase(previous) == 'Q' || Character.toUpperCase(previous) == 'T';
                            final double x1 = smooth ? 2 * x - lastCx : x, y1 = smooth ? 2 * y - lastCy : y;
                            x = ox + in.number();
                            y = oy + in.number();
                            path.quadTo(x1, y1, x, y);
                            lastCx = x1;
                            lastCy = y1;
                            break;
                        }
                        case 'A': {
                            final double rx = in.number(), ry = in.number(), rotation = in.number();
                            final boolean large = in.flag(), sweep = in.flag();
                            final double nx = ox + in.number(), ny = oy + in.number();
                            arc(path, x, y, rx, ry, rotation, large, sweep, nx, ny);
                            x = nx;
                            y = ny;
                            break;
                        }
                        case 'Z':
                            path.closePath();
                            x = startX;
                            y = startY;
                            break;
                        default:
                            return path;
                    }
                    previous = command;
                    if (Character.toUpperCase(command) == 'Z') command = 0;
                }
            } catch (RuntimeException e) {
                // 途中までのパスを使う
            }
            return path;
        }

        // 端点で決まる弧 (SVG の A) を、中心で決まる弧に直して追加する
        private void arc(Path2D.Double path, double x0, double y0, double rxIn, double ryIn, double angle, boolean large, boolean sweep,
                         double x, double y) {
            double rx = Math.abs(rxIn), ry = Math.abs(ryIn);
            if (x0 == x && y0 == y) return;
            if (rx == 0 || ry == 0) {
                path.lineTo(x, y);
                return;
            }
            final double phi = Math.toRadians(angle % 360);
            final double cos = Math.cos(phi), sin = Math.sin(phi);
            final double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
            final double x1 = cos * dx + sin * dy, y1 = -sin * dx + cos * dy;
            final double lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry);
            if (lambda > 1) {
                rx *= Math.sqrt(lambda);
                ry *= Math.sqrt(lambda);
            }
            final double numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
            final double denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1;
            double factor = denominator == 0 ? 0 : Math.sqrt(Math.max(0, numerator / denominator));
            if (large == sweep) factor = -factor;
            final double cx1 = factor * rx * y1 / ry, cy1 = -factor * ry * x1 / rx;
            final double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2, cy = sin * cx1 + cos * cy1 + (y0 + y) / 2;
            final double start = Math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx);
            double extent = Math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx) - start;
            if (sweep && extent < 0) extent += 2 * Math.PI;
            else if (!sweep && extent > 0) extent -= 2 * Math.PI;
            // Arc2D は y が下向きの角度 (反時計回りが正) なので、符号を反転して使う
            final Arc2D.Double arc = new Arc2D.Double(cx - rx, cy - ry, rx * 2, ry * 2, -Math.toDegrees(start), -Math.toDegrees(extent), Arc2D.OPEN);
            final AffineTransform rotate = AffineTransform.getRotateInstance(phi, cx, cy);
            path.append(rotate.createTransformedShape(arc), true);
            path.lineTo(x, y);
        }

        // ---------- 塗りと線 ----------

        private void fillAndStroke(Shape shape, Style s, AffineTransform t) {
            if (!s.visible) return;
            final java.awt.Graphics2D g = holder.graphics();
            g.setTransform(t);
            final Rectangle2D box = shape.getBounds2D();
            final Paint fill = paint(s.fill, s, box, s.fillOpacity);
            if (fill != null) {
                g.setPaint(fill);
                if (shape instanceof Path2D) ((Path2D) shape).setWindingRule(s.evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                g.fill(shape);
            }
            if (s.strokeWidth > 0) {
                final Paint stroke = paint(s.stroke, s, box, s.strokeOpacity);
                if (stroke != null) {
                    g.setPaint(stroke);
                    g.setStroke(new BasicStroke((float) s.strokeWidth, s.cap, s.join, s.miterLimit, s.dash, s.dashOffset));
                    g.draw(shape);
                }
            }
        }

        // 色や url(#勾配) を Paint にする。塗らないなら null
        private Paint paint(String spec, Style s, Rectangle2D box, double opacity) {
            final String v = spec.trim();
            if (v.equals("none")) return null;
            if (v.startsWith("url(")) {
                final int close = v.indexOf(')');
                String id = v.substring(4, close < 0 ? v.length() : close).trim();
                if (id.startsWith("'") || id.startsWith("\"")) id = id.substring(1, id.length() - 1);
                final Element server = id.startsWith("#") ? ids.get(id.substring(1)) : null;
                if (server != null) {
                    final Paint gradient = gradient(server, s, box, opacity);
                    if (gradient != null) return gradient;
                }
                // 見つからないときは、あとに書かれた代わりの色を使う
                final String fallback = close >= 0 ? v.substring(close + 1).trim() : "";
                return fallback.isEmpty() ? null : paint(fallback, s, box, opacity);
            }
            final Integer c = color(v, s.color);
            if (c == null) return null;
            final int a = (int) Math.round((c >>> 24) * opacity);
            return new Color((c & 0xFFFFFF) | a << 24, true);
        }

        // 勾配の指定は、href でたどった先から受け継ぐ
        private String inherited(Element gradient, String name) {
            Element e = gradient;
            for (int i = 0; i < 16 && e != null; i++) {
                if (e.hasAttribute(name)) return e.getAttribute(name);
                final String href = href(e);
                e = href != null && href.startsWith("#") ? ids.get(href.substring(1)) : null;
            }
            return null;
        }

        private Element stopsOwner(Element gradient) {
            Element e = gradient;
            for (int i = 0; i < 16 && e != null; i++) {
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element && "stop".equals(localName((Element) n))) return e;
                }
                final String href = href(e);
                e = href != null && href.startsWith("#") ? ids.get(href.substring(1)) : null;
            }
            return null;
        }

        private Paint gradient(Element server, Style s, Rectangle2D box, double opacity) {
            final String kind = localName(server);
            final boolean linear = kind.equals("linearGradient");
            if (!linear && !kind.equals("radialGradient")) return null;

            final Element owner = stopsOwner(server);
            if (owner == null) return null;
            final List<Float> offsets = new ArrayList<Float>();
            final List<Color> colors = new ArrayList<Color>();
            float last = 0;
            for (Node n = owner.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element) || !"stop".equals(localName(n))) continue;
                final Element stop = (Element) n;
                final Style st = styleOf(stop, new Style());
                final String off = stop.getAttribute("offset").trim();
                float offset = 0;
                try {
                    offset = off.isEmpty() ? 0 : (off.endsWith("%") ? Float.parseFloat(off.substring(0, off.length() - 1)) / 100 : Float.parseFloat(off));
                } catch (NumberFormatException e) {
                    offset = 0;
                }
                offset = Math.max(last, Math.min(1, offset));
                last = offset;
                String stopColor = stop.getAttribute("stop-color");
                String stopOpacity = stop.getAttribute("stop-opacity");
                final String style = stop.getAttribute("style");
                for (String declaration : style.split(";")) {
                    final int colon = declaration.indexOf(':');
                    if (colon <= 0) continue;
                    final String key = declaration.substring(0, colon).trim(), value = declaration.substring(colon + 1).trim();
                    if (key.equals("stop-color")) stopColor = value;
                    else if (key.equals("stop-opacity")) stopOpacity = value;
                }
                Integer c = stopColor.isEmpty() ? Integer.valueOf(0xFF000000) : color(stopColor, st.color);
                if (c == null) c = 0xFF000000;
                double stopAlpha = 1;
                try {
                    if (!stopOpacity.isEmpty()) stopAlpha = opacity(stopOpacity);
                } catch (RuntimeException e) {
                    stopAlpha = 1;
                }
                final int a = (int) Math.round((c >>> 24) * stopAlpha * opacity);
                offsets.add(offset);
                colors.add(new Color((c & 0xFFFFFF) | a << 24, true));
            }
            if (colors.isEmpty()) return null;
            if (colors.size() == 1) return colors.get(0);

            // Java の勾配は、位置が真っ直ぐ増えていく必要がある。同じ位置の停止点は、ごくわずかにずらす
            final float[] fractions = new float[offsets.size()];
            for (int i = 0; i < fractions.length; i++) {
                fractions[i] = offsets.get(i);
                if (i > 0 && fractions[i] <= fractions[i - 1]) fractions[i] = Math.nextUp(fractions[i - 1]);
            }
            if (fractions[fractions.length - 1] > 1) {
                // 1 を超えた分は、手前に詰める
                for (int i = fractions.length - 1; i > 0; i--) {
                    fractions[i] = Math.min(fractions[i], 1 - (fractions.length - 1 - i) * 1e-6f);
                }
            }

            final String units = inherited(server, "gradientUnits");
            final boolean userSpace = "userSpaceOnUse".equals(units);
            final AffineTransform transform = new AffineTransform();
            if (!userSpace) {
                if (box.getWidth() <= 0 || box.getHeight() <= 0) return null;
                transform.translate(box.getX(), box.getY());
                transform.scale(box.getWidth(), box.getHeight());
            }
            final String gradientTransform = inherited(server, "gradientTransform");
            if (gradientTransform != null) {
                final AffineTransform extra = transform(gradientTransform);
                if (extra != null) transform.concatenate(extra);
            }
            final String spread = inherited(server, "spreadMethod");
            final MultipleGradientPaint.CycleMethod cycle = "reflect".equals(spread) ? MultipleGradientPaint.CycleMethod.REFLECT
                    : ("repeat".equals(spread) ? MultipleGradientPaint.CycleMethod.REPEAT : MultipleGradientPaint.CycleMethod.NO_CYCLE);
            final Color[] colorArray = colors.toArray(new Color[0]);
            try {
                if (linear) {
                    final Point2D start = new Point2D.Double(coordinate(server, "x1", userSpace, viewportWidth, 0), coordinate(server, "y1", userSpace, viewportHeight, 0));
                    final Point2D end = new Point2D.Double(coordinate(server, "x2", userSpace, viewportWidth, userSpace ? viewportWidth : 1), coordinate(server, "y2", userSpace, viewportHeight, 0));
                    if (start.distance(end) == 0) return colorArray[colorArray.length - 1];
                    return new LinearGradientPaint(start, end, fractions, colorArray, cycle, MultipleGradientPaint.ColorSpaceType.SRGB, transform);
                }
                final double diagonal = Math.sqrt((viewportWidth * viewportWidth + viewportHeight * viewportHeight) / 2);
                final double cx = coordinate(server, "cx", userSpace, viewportWidth, userSpace ? viewportWidth / 2 : 0.5);
                final double cy = coordinate(server, "cy", userSpace, viewportHeight, userSpace ? viewportHeight / 2 : 0.5);
                final double r = coordinate(server, "r", userSpace, diagonal, userSpace ? diagonal / 2 : 0.5);
                final double fx = coordinate(server, "fx", userSpace, viewportWidth, cx);
                final double fy = coordinate(server, "fy", userSpace, viewportHeight, cy);
                if (!(r > 0)) return colorArray[colorArray.length - 1];
                return new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new Point2D.Double(fx, fy), fractions, colorArray, cycle,
                        MultipleGradientPaint.ColorSpaceType.SRGB, transform);
            } catch (IllegalArgumentException e) {
                return colorArray[0];
            }
        }

        // 勾配の座標。物体の大きさに対する割合のときは、% を 0 から 1 にする
        private double coordinate(Element server, String name, boolean userSpace, double relative, double fallback) {
            final String value = inherited(server, name);
            if (value == null || value.trim().isEmpty()) return fallback;
            final String v = value.trim();
            if (userSpace) return length(v, relative, 16);
            return v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100 : length(v, 1, 16);
        }

        // ---------- 切り抜き ----------

        private Shape clipShape(String spec, AffineTransform t, Element owner) {
            final int open = spec.indexOf('#');
            final int close = spec.indexOf(')');
            if (open < 0) return null;
            final Element clip = ids.get(spec.substring(open + 1, close < 0 ? spec.length() : close).replace("'", "").replace("\"", "").trim());
            if (clip == null || !"clipPath".equals(localName(clip))) return null;
            final Area area = new Area();
            final AffineTransform base = new AffineTransform(t);
            final AffineTransform clipTransform = transform(clip.getAttribute("transform"));
            if (clipTransform != null) base.concatenate(clipTransform);
            for (Node n = clip.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                final Element child = (Element) n;
                final Style style = styleOf(child, new Style());
                final Shape shape = shape(child, localName(child), style);
                if (shape == null) continue;
                final AffineTransform childTransform = new AffineTransform(base);
                final AffineTransform own = transform(child.getAttribute("transform"));
                if (own != null) childTransform.concatenate(own);
                area.add(new Area(childTransform.createTransformedShape(shape)));
            }
            return area;
        }

        // ---------- 文字と画像 ----------

        private void drawText(Element e, Style s, AffineTransform t) {
            final double[] pen = {attr(e, "x", viewportWidth, 0, s), attr(e, "y", viewportHeight, 0, s)};
            pen[0] += attr(e, "dx", viewportWidth, 0, s);
            pen[1] += attr(e, "dy", viewportHeight, 0, s);
            drawTextRun(e, s, t, pen);
        }

        private void drawTextRun(Element e, Style s, AffineTransform t, double[] pen) {
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element && "tspan".equals(localName(n))) {
                    final Element span = (Element) n;
                    final Style style = styleOf(span, s);
                    if (style.hidden) continue;
                    if (span.hasAttribute("x")) pen[0] = attr(span, "x", viewportWidth, pen[0], style);
                    if (span.hasAttribute("y")) pen[1] = attr(span, "y", viewportHeight, pen[1], style);
                    pen[0] += attr(span, "dx", viewportWidth, 0, style);
                    pen[1] += attr(span, "dy", viewportHeight, 0, style);
                    drawTextRun(span, style, t, pen);
                } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                    final String text = n.getNodeValue().replaceAll("\\s+", " ");
                    if (text.trim().isEmpty() && pen[0] == 0) continue;
                    final Font font = font(s);
                    final GlyphVector glyphs = font.createGlyphVector(fontContext, text);
                    final double advance = glyphs.getLogicalBounds().getWidth();
                    double x = pen[0];
                    if (s.textAnchor.equals("middle")) x -= advance / 2;
                    else if (s.textAnchor.equals("end")) x -= advance;
                    fillAndStroke(glyphs.getOutline((float) x, (float) pen[1]), s, t);
                    pen[0] += advance;
                }
            }
        }

        private Font font(Style s) {
            String family = "SansSerif";
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
            return new Font(family, (s.bold ? Font.BOLD : Font.PLAIN) | (s.italic ? Font.ITALIC : 0), 1).deriveFont((float) s.fontSize);
        }

        // data: URI で埋め込まれた画像だけを描く (外部のファイルや URL は読まない)
        private void drawImage(Element e, Style s, AffineTransform t) {
            final String href = href(e);
            if (href == null || !href.startsWith("data:")) return;
            final int comma = href.indexOf(',');
            if (comma < 0 || !href.substring(0, comma).contains(";base64")) return;
            try {
                final byte[] data = Base64.getMimeDecoder().decode(href.substring(comma + 1));
                final BufferedImage image = ImageBytes.read(data);
                if (image == null) return;
                final double x = attr(e, "x", viewportWidth, 0, s), y = attr(e, "y", viewportHeight, 0, s);
                double w = attr(e, "width", viewportWidth, image.getWidth(), s), h = attr(e, "height", viewportHeight, image.getHeight(), s);
                if (!(w > 0) || !(h > 0)) return;
                // 縦横比を保って、枠に収める
                final double scale = Math.min(w / image.getWidth(), h / image.getHeight());
                final double dw = image.getWidth() * scale, dh = image.getHeight() * scale;
                final java.awt.Graphics2D g = holder.graphics();
                g.setTransform(t);
                g.drawImage(image, (int) Math.round(x + (w - dw) / 2), (int) Math.round(y + (h - dh) / 2), (int) Math.round(dw), (int) Math.round(dh), null);
            } catch (IOException | RuntimeException ex) {
                // 読めない画像は描かない
            }
        }
    }

    // ---------- パスの字句 ----------

    private static final class PathReader {
        private final String s;
        private int p;

        PathReader(String s) {
            this.s = s;
        }

        boolean skipSeparators() {
            while (p < s.length() && (Character.isWhitespace(s.charAt(p)) || s.charAt(p) == ',')) p++;
            return p < s.length();
        }

        boolean isCommand() {
            return Character.isLetter(s.charAt(p)) && s.charAt(p) != 'e' && s.charAt(p) != 'E';
        }

        char command() {
            return s.charAt(p++);
        }

        double number() {
            skipSeparators();
            final Matcher m = NUMBER.matcher(s);
            if (!m.find(p) || m.start() != p) throw new IllegalArgumentException("number expected");
            p = m.end();
            return Double.parseDouble(m.group());
        }

        // 弧の旗 (0 か 1)。次の数と区切りなしでつながることがある
        boolean flag() {
            skipSeparators();
            final char c = s.charAt(p++);
            if (c != '0' && c != '1') throw new IllegalArgumentException("flag expected");
            return c == '1';
        }
    }

    private static final class Graphics2DHolder {
        private final java.awt.Graphics2D g;

        Graphics2DHolder(BufferedImage image) {
            g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }

        java.awt.Graphics2D graphics() {
            return g;
        }
    }

    // transform 属性 (matrix、translate、scale、rotate、skewX、skewY の並び)。なければ null
    static AffineTransform transform(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        final AffineTransform result = new AffineTransform();
        final Matcher m = Pattern.compile("(\\w+)\\s*\\(([^)]*)\\)").matcher(text);
        while (m.find()) {
            final double[] a = numbers(m.group(2));
            switch (m.group(1)) {
                case "matrix":
                    if (a.length == 6) result.concatenate(new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]));
                    break;
                case "translate":
                    if (a.length >= 1) result.translate(a[0], a.length > 1 ? a[1] : 0);
                    break;
                case "scale":
                    if (a.length >= 1) result.scale(a[0], a.length > 1 ? a[1] : a[0]);
                    break;
                case "rotate":
                    if (a.length == 1) result.rotate(Math.toRadians(a[0]));
                    else if (a.length == 3) result.rotate(Math.toRadians(a[0]), a[1], a[2]);
                    break;
                case "skewX":
                    if (a.length == 1) result.concatenate(new AffineTransform(1, 0, Math.tan(Math.toRadians(a[0])), 1, 0, 0));
                    break;
                case "skewY":
                    if (a.length == 1) result.concatenate(new AffineTransform(1, Math.tan(Math.toRadians(a[0])), 0, 1, 0, 0));
                    break;
                default:
                    break;
            }
        }
        return result;
    }
}
