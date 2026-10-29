package marumasa.marumasa_sign.core.image.format;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SVG の XML を読んで、描くのに使う要素と属性だけの木にする。
 * SVG はどこかの URL から来るので、悪意のあるファイルとして扱う。DOM は使わずに SAX で読み、要素の数・深さ・文字数に
 * 上限を設けて、メモリやスタックを使い切らせない (深すぎる要素は読み飛ばし、多すぎる要素はそこまでで読むのをやめる)。
 * 外部の DTD や実体は一切読まない (ファイルやネットワークにアクセスさせない)。
 */
final class SvgDocument {

    // 要素の数と深さの上限。深すぎる要素は、その中も含めて無視する
    static final int MAX_ELEMENTS = 200000;
    static final int MAX_DEPTH = 128;
    // 覚えておく文字 (属性の値と文字列) の合計の上限
    private static final long MAX_CHARS = 24L * 1024 * 1024;
    // d・points・href 以外の属性の値の長さの上限 (これより長い値は無視する)
    private static final int MAX_VALUE = 65536;
    // 内部の実体の展開の上限 (JDK の XML パーサーの既定値より小さくする)
    private static final String ENTITY_EXPANSIONS = "10000";
    private static final String ENTITY_SIZE = "4000000";

    static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    // 覚えておく属性の名前。それ以外の属性は読まない (描くたびに全部の属性を調べるので、数を限る)
    private static final Map<String, String> ATTRIBUTES = new HashMap<String, String>();

    static {
        final String[] names = {
                "id", "class", "style", "transform", "x", "y", "width", "height", "rx", "ry", "r", "cx", "cy", "fx", "fy",
                "x1", "y1", "x2", "y2", "d", "points", "dx", "dy", "viewBox", "preserveAspectRatio", "href", "offset",
                "gradientUnits", "gradientTransform", "spreadMethod", "clipPathUnits", "maskUnits", "maskContentUnits",
                "requiredExtensions", "requiredFeatures", "systemLanguage", "space", "filterUnits", "type", "values", "in", "result"};
        for (String name : names) ATTRIBUTES.put(name, name);
        for (String name : SvgStyleSheet.PROPERTIES) ATTRIBUTES.put(name, name);
    }

    final Node root;
    final Map<String, Node> ids;
    final SvgStyleSheet styles;

    private SvgDocument(Node root, Map<String, Node> ids, SvgStyleSheet styles) {
        this.root = root;
        this.ids = ids;
        this.styles = styles;
    }

    /**
     * 要素。名前は SVG の要素名で、SVG 以外の名前空間の要素は "" (描かない)。
     * 描くときに 1 度だけ作るもの (スタイル、変換、図形など) もここに覚えておく。
     */
    static final class Node {
        final String name;
        final Node parent;
        // 1 つ前の兄弟の要素 (兄弟のセレクター用)
        final Node previous;
        final int depth;
        private String[] attributes = NO_ATTRIBUTES;
        private int attributeCount;
        // 子の要素 (Node) と文字列 (String。text と style の中のものだけ)。なければ null
        List<Object> children;
        private Node lastElement;

        // 描くときに作って覚えておくもの
        String[] properties;
        java.util.Set<String> classes;
        AffineTransform transform;
        boolean transformRead;
        Object geometry;
        Object resource;

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
            this.previous = parent != null ? parent.lastElement : null;
            this.depth = parent != null ? parent.depth + 1 : 0;
        }

        String get(String attribute) {
            for (int i = 0; i < attributeCount; i += 2) {
                if (attributes[i].equals(attribute)) return attributes[i + 1];
            }
            return null;
        }

        String get(String attribute, String fallback) {
            final String value = get(attribute);
            return value != null ? value : fallback;
        }

        boolean has(String attribute) {
            return get(attribute) != null;
        }

        int attributeCount() {
            return attributeCount / 2;
        }

        String attributeName(int i) {
            return attributes[i * 2];
        }

        String attributeValue(int i) {
            return attributes[i * 2 + 1];
        }

        private void set(String attribute, String value, boolean replace) {
            for (int i = 0; i < attributeCount; i += 2) {
                if (attributes[i].equals(attribute)) {
                    if (replace) attributes[i + 1] = value;
                    return;
                }
            }
            if (attributeCount == attributes.length) attributes = java.util.Arrays.copyOf(attributes, Math.max(8, attributeCount * 2));
            attributes[attributeCount++] = attribute;
            attributes[attributeCount++] = value;
        }

        private void add(Object child) {
            if (children == null) children = new ArrayList<Object>(4);
            children.add(child);
            if (child instanceof Node) lastElement = (Node) child;
        }

        List<Object> children() {
            return children != null ? children : Collections.emptyList();
        }

        // 先祖 (自分も含む) に node があるか
        boolean isInside(Node node) {
            for (Node n = this; n != null; n = n.parent) {
                if (n == node) return true;
            }
            return false;
        }
    }

    private static final String[] NO_ATTRIBUTES = new String[0];

    static SvgDocument parse(byte[] data, long deadline) throws IOException {
        checkDoctype(data);
        final Builder builder = new Builder(deadline);
        try {
            final XMLReader reader = newReader();
            reader.setContentHandler(builder);
            reader.setEntityResolver(builder);
            reader.setErrorHandler(builder);
            reader.parse(new InputSource(new ByteArrayInputStream(data)));
        } catch (Stop e) {
            // 上限に届いたので、そこまでの要素で描く
        } catch (SAXException e) {
            if (!(e.getException() instanceof Stop)) throw new IOException("Invalid SVG: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IOException("Invalid SVG: " + e.getMessage(), e);
        }
        if (builder.root == null) throw new IOException("Not an SVG image");
        final SvgStyleSheet styles = new SvgStyleSheet(deadline);
        for (String css : builder.css) styles.add(css);
        return new SvgDocument(builder.root, builder.ids, styles);
    }

    // 外部の DTD や実体を読まない設定の XML パーサー
    private static XMLReader newReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory;
        try {
            // ほかの MOD が入れた XML パーサーではなく、設定の効き方が分かっている JDK のものを使う
            factory = SAXParserFactory.newInstance("com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl", null);
        } catch (FactoryConfigurationError | RuntimeException e) {
            factory = SAXParserFactory.newInstance();
        }
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        // 外部の実体を読まない設定は、できなければ読むのをやめる
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        final SAXParser parser = factory.newSAXParser();
        setProperty(parser, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setProperty(parser, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        setProperty(parser, "http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit", ENTITY_EXPANSIONS);
        setProperty(parser, "http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", ENTITY_SIZE);
        setProperty(parser, "http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit", ENTITY_SIZE);
        setProperty(parser, "http://www.oracle.com/xml/jaxp/properties/maxParameterEntitySizeLimit", ENTITY_SIZE);
        // 読み手そのものにも、外部の実体を読まない設定をする (工場の設定が読み手に伝わらない XML パーサーのため)
        final XMLReader reader = parser.getXMLReader();
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        try {
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (SAXException e) {
            // この設定を知らない XML パーサー。外部の DTD は、resolveEntity が空の中身を返すので読まれない
        }
        return reader;
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | SAXException e) {
            // その設定を知らない XML パーサーでは、外部を読む機能もないので、そのまま進める
        }
    }

    private static void setProperty(SAXParser parser, String name, String value) {
        try {
            parser.setProperty(name, value);
        } catch (SAXException | RuntimeException e) {
            // その設定を知らない XML パーサーでは、外部の実体を読まない設定とエラーの扱いで足りる
        }
    }

    /**
     * DOCTYPE の中 ([ から ]) が途中で切れていたら、XML パーサーに渡す前に断る。
     * JDK の XML パーサーは、DTD の途中でファイルが終わると、エラーとは別にスタックトレースを標準エラーに書いてしまう。
     */
    static void checkDoctype(byte[] data) throws IOException {
        final CharSequence text;
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF || (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE)) {
            text = new String(data, StandardCharsets.UTF_16);
        } else {
            // UTF-8 などは、ASCII の部分だけを見ればよいので、バイトをそのまま文字として見る (大きなファイルを写さない)
            text = new Latin1(data);
        }
        final int n = text.length();
        int p = 0;
        // 先頭の XML 宣言・コメント・処理命令・空白を飛ばす
        while (p < n) {
            final char c = text.charAt(p);
            if (c <= ' ' || c == '\uFEFF' || c == 0xEF || c == 0xBB || c == 0xBF) {
                p++;
            } else if (startsWith(text, "<?", p)) {
                final int end = indexOf(text, "?>", p + 2);
                if (end < 0) return;
                p = end + 2;
            } else if (startsWith(text, "<!--", p)) {
                final int end = indexOf(text, "-->", p + 4);
                if (end < 0) return;
                p = end + 3;
            } else {
                break;
            }
        }
        if (!startsWith(text, "<!DOCTYPE", p)) return;
        p += 9;
        // 引用符の中・コメントの中・処理命令の中を飛ばしながら、DOCTYPE の終わりの > を探す
        boolean subset = false;
        while (p < n) {
            final char c = text.charAt(p);
            if (c == '"' || c == '\'') {
                final int end = indexOf(text, c == '"' ? "\"" : "'", p + 1);
                if (end < 0) break;
                p = end + 1;
            } else if (subset && startsWith(text, "<!--", p)) {
                final int end = indexOf(text, "-->", p + 4);
                if (end < 0) break;
                p = end + 3;
            } else if (subset && startsWith(text, "<?", p)) {
                final int end = indexOf(text, "?>", p + 2);
                if (end < 0) break;
                p = end + 2;
            } else if (c == '[' && !subset) {
                subset = true;
                p++;
            } else if (c == ']' && subset) {
                subset = false;
                p++;
            } else if (c == '>' && !subset) {
                return;
            } else {
                p++;
            }
        }
        throw new IOException("Invalid SVG: truncated DOCTYPE");
    }

    private static boolean startsWith(CharSequence text, String prefix, int at) {
        if (at < 0 || at + prefix.length() > text.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(at + i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    // 探す文字列は短いので、素直に探しても長さに比例した時間で終わる
    private static int indexOf(CharSequence text, String target, int from) {
        for (int i = Math.max(0, from); i + target.length() <= text.length(); i++) {
            if (startsWith(text, target, i)) return i;
        }
        return -1;
    }

    // バイトの並びを、ISO 8859-1 の文字の並びとして見る
    private static final class Latin1 implements CharSequence {
        private final byte[] data;

        Latin1(byte[] data) {
            this.data = data;
        }

        @Override
        public int length() {
            return data.length;
        }

        @Override
        public char charAt(int index) {
            return (char) (data[index] & 0xFF);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(data, start, end - start, StandardCharsets.ISO_8859_1);
        }

        @Override
        public String toString() {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    // 上限に届いたので、読むのをやめる
    private static final class Stop extends SAXException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super("SVG limit reached");
        }
    }

    private static final class Builder extends DefaultHandler {
        private final long deadline;
        Node root;
        private Node current;
        final Map<String, Node> ids = new HashMap<String, Node>();
        final List<String> css = new ArrayList<String>();
        private int elements;
        private long chars;
        // 深すぎて無視している要素の深さ
        private int skipped;
        // 開いている text 要素の数 (この中の文字だけを覚える)
        private int inText;
        private final StringBuilder text = new StringBuilder();
        private StringBuilder style;

        Builder(long deadline) {
            this.deadline = deadline;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (skipped > 0 || current != null && current.depth >= MAX_DEPTH) {
                skipped++;
                return;
            }
            flushText();
            if (++elements > MAX_ELEMENTS) throw new Stop();
            if ((elements & 1023) == 0 && System.nanoTime() - deadline > 0) throw new Stop();
            final String local = localName == null || localName.isEmpty() ? qName : localName;
            final String name = uri == null || uri.isEmpty() || uri.equals(SVG_NS) ? local : "";
            final Node node = new Node(name, current);
            for (int i = 0; i < attributes.getLength(); i++) {
                final String attributeUri = attributes.getURI(i);
                String attribute = attributes.getLocalName(i);
                if (attribute == null || attribute.isEmpty()) attribute = attributes.getQName(i);
                final boolean plain = attributeUri == null || attributeUri.isEmpty();
                if (!plain && !(XLINK_NS.equals(attributeUri) && attribute.equals("href")) && !(XML_NS.equals(attributeUri) && attribute.equals("space"))) {
                    continue;
                }
                final String canonical = ATTRIBUTES.get(attribute);
                if (canonical == null) continue;
                final String value = attributes.getValue(i);
                if (value.length() > MAX_VALUE && !canonical.equals("d") && !canonical.equals("points") && !canonical.equals("href")) continue;
                count(value.length());
                // href と xlink:href の両方があれば、href を使う
                node.set(canonical, value, plain);
            }
            if (root == null) {
                root = node;
            } else {
                current.add(node);
            }
            final String id = node.get("id");
            if (id != null && !id.isEmpty() && !ids.containsKey(id)) ids.put(id, node);
            if (name.equals("text")) inText++;
            if (name.equals("style")) style = new StringBuilder();
            current = node;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (skipped > 0) {
                skipped--;
                return;
            }
            flushText();
            if (current == null) return;
            if (current.name.equals("style") && style != null) {
                css.add(style.toString());
                style = null;
            }
            if (current.name.equals("text")) inText--;
            current = current.parent;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (skipped > 0 || current == null) return;
            if (style != null && current.name.equals("style")) {
                count(length);
                style.append(ch, start, length);
            } else if (inText > 0) {
                count(length);
                text.append(ch, start, length);
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            characters(ch, start, length);
        }

        private void flushText() {
            if (text.length() == 0) return;
            current.add(text.toString());
            text.setLength(0);
        }

        private void count(int length) throws Stop {
            chars += length;
            if (chars > MAX_CHARS) throw new Stop();
        }

        // 外部の実体や DTD は、読まずに空として扱う
        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        // 警告と回復できるエラーは無視する (標準エラーに書かせない)
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) {
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}
