package marumasa.marumasa_sign.core.image.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SVG の style 要素 (CSS) と style 属性を読んで、要素ごとに効く性質を決める。
 * 正規表現は使わず、どの文字も決まった回数しか見ない手書きの読み取りにする (壊れた CSS で長い時間かからないように)。
 * セレクターは、要素名・#id・.class・* と、子孫・子・兄弟の結合子に対応する。それ以外 (属性や疑似クラス) のセレクターは何にも当たらない。
 */
final class SvgStyleSheet {

    // 描くのに使う性質。これ以外の宣言は覚えない
    static final Set<String> PROPERTIES = new HashSet<String>(Arrays.asList(
            "fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "fill-rule", "clip-rule",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "color",
            "font-size", "font-family", "font-weight", "font-style", "text-anchor", "display", "visibility", "clip-path",
            "mask", "filter", "stop-color", "stop-opacity", "overflow", "color-interpolation-filters"));

    private static final int MAX_RULES = 4096;
    // 性質の値の長さの上限
    private static final int MAX_VALUE = 4096;
    // 1 つの要素の class の数の上限
    private static final int MAX_CLASSES = 32;
    // セレクターを要素に当てはめる手間の合計の上限 (これを超えたら、それ以降の規則は当てはめない)
    private static final long MAX_MATCH_WORK = 20000000;

    private final long deadline;
    private final Map<String, List<Rule>> byId = new HashMap<String, List<Rule>>();
    private final Map<String, List<Rule>> byClass = new HashMap<String, List<Rule>>();
    private final Map<String, List<Rule>> byTag = new HashMap<String, List<Rule>>();
    private final List<Rule> universal = new ArrayList<Rule>();
    private int rules;
    private long work = MAX_MATCH_WORK;

    SvgStyleSheet(long deadline) {
        this.deadline = deadline;
    }

    // セレクターの 1 つの部分 (div.a#b のような、結合子で区切られた部分)
    private static final class Compound {
        String tag;
        String id;
        final List<String> classes = new ArrayList<String>(2);

        boolean matches(SvgDocument.Node node, Set<String> nodeClasses) {
            if (tag != null && !tag.equals(node.name)) return false;
            if (id != null && !id.equals(node.get("id"))) return false;
            if (classes.isEmpty()) return true;
            final Set<String> have = nodeClasses != null ? nodeClasses : classes(node);
            return have.containsAll(classes);
        }
    }

    private static final class Rule {
        final Compound[] parts;
        // parts[i] と parts[i + 1] の間の結合子 (' '、'>'、'+'、'~')
        final char[] combinators;
        final int specificity;
        final int order;
        final String[] normal;
        final String[] important;

        Rule(Compound[] parts, char[] combinators, int specificity, int order, String[] normal, String[] important) {
            this.parts = parts;
            this.combinators = combinators;
            this.specificity = specificity;
            this.order = order;
            this.normal = normal;
            this.important = important;
        }
    }

    private static final Comparator<Rule> CASCADE = new Comparator<Rule>() {
        @Override
        public int compare(Rule a, Rule b) {
            final int c = Integer.compare(a.specificity, b.specificity);
            return c != 0 ? c : Integer.compare(a.order, b.order);
        }
    };

    // ---------- CSS を読む ----------

    void add(String css) {
        final String text = stripComments(css);
        final int n = text.length();
        int p = 0;
        while (p < n && rules < MAX_RULES) {
            if (System.nanoTime() - deadline > 0) return;
            // 規則の前置き (セレクターや @ で始まる規則) を、{ か ; か } まで読む
            final int q = find(text, p, "{;}");
            if (q < 0) return;
            final char c = text.charAt(q);
            final String prelude = text.substring(p, q).trim();
            p = q + 1;
            if (c == '}' || c == ';') {
                // @media の閉じかっこや、@import のような文
                continue;
            }
            if (prelude.startsWith("@")) {
                // @media と @supports の中の規則は使う。それ以外 (@font-face、@keyframes など) は中身ごと飛ばす
                if (prelude.startsWith("@media") || prelude.startsWith("@supports")) continue;
                p = skipBlock(text, p);
                continue;
            }
            int end = find(text, p, "}");
            if (end < 0) end = n;
            final String body = text.substring(p, end);
            p = end + 1;
            addRule(prelude, body);
        }
    }

    // /* から */ までを取り除く。閉じていなければ、そこから後ろを全部取り除く
    static String stripComments(String css) {
        int start = css.indexOf("/*");
        if (start < 0) return css;
        final StringBuilder out = new StringBuilder(css.length());
        int p = 0;
        while (start >= 0) {
            out.append(css, p, start);
            final int end = css.indexOf("*/", start + 2);
            if (end < 0) return out.toString();
            out.append(' ');
            p = end + 2;
            start = css.indexOf("/*", p);
        }
        out.append(css, p, css.length());
        return out.toString();
    }

    // 引用符の中を飛ばしながら、stops のどれかの文字を探す
    private static int find(String text, int from, String stops) {
        for (int i = from; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                final int end = text.indexOf(c, i + 1);
                if (end < 0) return -1;
                i = end;
            } else if (stops.indexOf(c) >= 0) {
                return i;
            }
        }
        return -1;
    }

    // { の直後から、対応する } の直後までを飛ばす
    private static int skipBlock(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                final int end = text.indexOf(c, i + 1);
                if (end < 0) return text.length();
                i = end;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    private void addRule(String prelude, String body) {
        final Map<String, String> normal = new LinkedHashMap<String, String>();
        final Map<String, String> important = new LinkedHashMap<String, String>();
        declarations(body, normal, important);
        if (normal.isEmpty() && important.isEmpty()) return;
        final String[] normalArray = pairs(normal), importantArray = pairs(important);
        int start = 0;
        while (start <= prelude.length() && rules < MAX_RULES) {
            int comma = prelude.indexOf(',', start);
            if (comma < 0) comma = prelude.length();
            final Rule rule = parseSelector(prelude.substring(start, comma), rules, normalArray, importantArray);
            start = comma + 1;
            if (rule == null) continue;
            rules++;
            final Compound last = rule.parts[rule.parts.length - 1];
            if (last.id != null) {
                bucket(byId, last.id).add(rule);
            } else if (!last.classes.isEmpty()) {
                bucket(byClass, last.classes.get(0)).add(rule);
            } else if (last.tag != null) {
                bucket(byTag, last.tag).add(rule);
            } else {
                universal.add(rule);
            }
        }
    }

    private static List<Rule> bucket(Map<String, List<Rule>> map, String key) {
        List<Rule> list = map.get(key);
        if (list == null) {
            list = new ArrayList<Rule>(2);
            map.put(key, list);
        }
        return list;
    }

    // セレクターを読む。対応しない書き方なら null (その規則は何にも当たらない)
    private static Rule parseSelector(String text, int order, String[] normal, String[] important) {
        final List<Compound> parts = new ArrayList<Compound>();
        final StringBuilder combinators = new StringBuilder();
        int ids = 0, classes = 0, tags = 0;
        char pending = 0;
        final int n = text.length();
        int p = 0;
        while (true) {
            while (p < n && isSpace(text.charAt(p))) p++;
            if (p >= n) break;
            char c = text.charAt(p);
            if (c == '>' || c == '+' || c == '~') {
                if (parts.isEmpty() || pending != 0) return null;
                pending = c;
                p++;
                continue;
            }
            if (!parts.isEmpty()) combinators.append(pending != 0 ? pending : ' ');
            pending = 0;
            final Compound compound = new Compound();
            boolean any = false;
            if (c == '*') {
                p++;
                any = true;
            } else if (isNameChar(c)) {
                final int start = p;
                p = name(text, p);
                compound.tag = text.substring(start, p);
                tags++;
            }
            while (p < n) {
                c = text.charAt(p);
                if (c == '#' || c == '.') {
                    final int start = ++p;
                    p = name(text, p);
                    if (p == start) return null;
                    final String value = text.substring(start, p);
                    if (c == '#') {
                        if (compound.id != null && !compound.id.equals(value)) return null;
                        compound.id = value;
                        ids++;
                    } else {
                        compound.classes.add(value);
                        classes++;
                    }
                } else if (isSpace(c) || c == '>' || c == '+' || c == '~') {
                    break;
                } else {
                    // :hover や [attr] など
                    return null;
                }
            }
            if (!any && compound.tag == null && compound.id == null && compound.classes.isEmpty()) return null;
            parts.add(compound);
        }
        if (parts.isEmpty() || pending != 0) return null;
        final int specificity = Math.min(ids, 99) * 10000 + Math.min(classes, 99) * 100 + Math.min(tags, 99);
        return new Rule(parts.toArray(new Compound[0]), combinators.toString().toCharArray(), specificity, order, normal, important);
    }

    private static int name(String text, int p) {
        int i = p;
        while (i < text.length() && isNameChar(text.charAt(i))) i++;
        return i;
    }

    private static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c >= 0x80;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     * 宣言の並び (fill: red; stroke: blue) を読んで、知っている性質だけを覚える。
     * 引用符と丸かっこの中の ; では区切らない。
     */
    static void declarations(String body, Map<String, String> normal, Map<String, String> important) {
        if (body == null) return;
        final int n = body.length();
        int start = 0;
        while (start < n) {
            int end = start;
            int parentheses = 0;
            while (end < n) {
                final char c = body.charAt(end);
                if (c == '"' || c == '\'') {
                    final int close = body.indexOf(c, end + 1);
                    end = close < 0 ? n : close + 1;
                    continue;
                }
                if (c == '(') parentheses++;
                else if (c == ')' && parentheses > 0) parentheses--;
                else if (c == ';' && parentheses == 0) break;
                end++;
            }
            declaration(body, start, Math.min(end, n), normal, important);
            start = end + 1;
        }
    }

    private static void declaration(String body, int start, int end, Map<String, String> normal, Map<String, String> important) {
        int colon = start;
        while (colon < end && body.charAt(colon) != ':') colon++;
        if (colon >= end) return;
        final String name = body.substring(start, colon).trim().toLowerCase(Locale.ROOT);
        if (!PROPERTIES.contains(name)) return;
        String value = body.substring(colon + 1, end).trim();
        boolean isImportant = false;
        final int bang = value.lastIndexOf('!');
        if (bang >= 0 && value.substring(bang + 1).trim().equalsIgnoreCase("important")) {
            isImportant = true;
            value = value.substring(0, bang).trim();
        }
        if (value.isEmpty() || value.length() > MAX_VALUE) return;
        (isImportant ? important : normal).put(name, value);
    }

    private static String[] pairs(Map<String, String> map) {
        final String[] result = new String[map.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            result[i++] = entry.getKey();
            result[i++] = entry.getValue();
        }
        return result;
    }

    // ---------- 要素に当てはめる ----------

    /**
     * 要素に効く性質を、名前と値を交互に並べて返す。
     * 低いものから順に、属性 (fill="red" など)・style 要素の規則・style 属性・!important の規則・!important の style 属性。
     */
    String[] cascade(SvgDocument.Node node) {
        final Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < node.attributeCount(); i++) {
            final String name = node.attributeName(i);
            final String value = node.attributeValue(i).trim();
            if (PROPERTIES.contains(name) && !value.isEmpty() && value.length() <= MAX_VALUE) result.put(name, value);
        }
        final List<Rule> matched = matching(node);
        for (Rule rule : matched) put(result, rule.normal);
        final Map<String, String> styleNormal = new LinkedHashMap<String, String>();
        final Map<String, String> styleImportant = new LinkedHashMap<String, String>();
        declarations(node.get("style"), styleNormal, styleImportant);
        result.putAll(styleNormal);
        for (Rule rule : matched) put(result, rule.important);
        result.putAll(styleImportant);
        return pairs(result);
    }

    private static void put(Map<String, String> map, String[] pairs) {
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
    }

    private List<Rule> matching(SvgDocument.Node node) {
        if (rules == 0 || work <= 0) return Collections.emptyList();
        if (System.nanoTime() - deadline > 0) {
            work = 0;
            return Collections.emptyList();
        }
        final Set<String> classes = classes(node);
        final List<Rule> candidates = new ArrayList<Rule>();
        final String id = node.get("id");
        if (id != null) addAll(candidates, byId.get(id));
        for (String c : classes) addAll(candidates, byClass.get(c));
        addAll(candidates, byTag.get(node.name));
        candidates.addAll(universal);
        final List<Rule> matched = new ArrayList<Rule>();
        for (Rule rule : candidates) {
            if (--work <= 0) break;
            if (matches(rule, node, classes)) matched.add(rule);
        }
        Collections.sort(matched, CASCADE);
        return matched;
    }

    private static void addAll(List<Rule> to, List<Rule> from) {
        if (from != null) to.addAll(from);
    }

    // 右から左へ、近いものから順に当てはめる (後戻りはしない)
    private boolean matches(Rule rule, SvgDocument.Node node, Set<String> nodeClasses) {
        int i = rule.parts.length - 1;
        if (!rule.parts[i].matches(node, nodeClasses)) return false;
        SvgDocument.Node current = node;
        for (i--; i >= 0; i--) {
            final Compound part = rule.parts[i];
            switch (rule.combinators[i]) {
                case '>':
                    current = current.parent;
                    if (current == null || !part.matches(current, null)) return false;
                    break;
                case '+':
                    current = current.previous;
                    if (current == null || !part.matches(current, null)) return false;
                    break;
                case '~':
                    do {
                        current = current.previous;
                        if (--work <= 0) return false;
                    } while (current != null && !part.matches(current, null));
                    if (current == null) return false;
                    break;
                default:
                    do {
                        current = current.parent;
                        if (--work <= 0) return false;
                    } while (current != null && !part.matches(current, null));
                    if (current == null) return false;
                    break;
            }
        }
        return true;
    }

    // class 属性の名前の集まり。要素ごとに 1 度だけ作る
    private static Set<String> classes(SvgDocument.Node node) {
        if (node.classes == null) node.classes = readClasses(node.get("class"));
        return node.classes;
    }

    private static Set<String> readClasses(String value) {
        if (value == null || value.isEmpty()) return Collections.<String>emptySet();
        final Set<String> result = new LinkedHashSet<String>();
        int p = 0;
        final int n = value.length();
        while (p < n && result.size() < MAX_CLASSES) {
            while (p < n && isSpace(value.charAt(p))) p++;
            final int start = p;
            while (p < n && !isSpace(value.charAt(p))) p++;
            if (p > start) result.add(value.substring(start, p));
        }
        return result;
    }
}
