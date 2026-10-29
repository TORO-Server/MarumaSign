package marumasa.marumasa_sign.core.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SVG の作りを知ったうえで壊すファジング。深い入れ子、use の爆弾と輪、極端な CSS や transform、極端な数、
 * DOCTYPE と外部の実体 (XXE)、壊した実在のファイルを作って ImageLoader.decode に読ませる。
 * IOException 以外の例外、メモリの使い切り、長い時間 (1 つあたり 8 秒) を失敗にする。失敗した入力は build/svg-fuzz に保存する。
 * <p>
 * 引数: 回数 [種の SVG のファイルまたはフォルダ...]。乱数の種はシステムプロパティ fuzz.seed で決められる。
 */
public final class SvgFuzz {
    private static final long SLOW_MILLIS = 8000;
    private static final long CASE_TIMEOUT_SECONDS = 30;

    static final String[] CONTAINERS = {"g", "svg", "symbol", "defs", "a", "switch", "clipPath", "mask", "pattern", "marker", "filter", "text", "tspan", "linearGradient", "radialGradient", "style"};
    static final String[] LEAVES = {"rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "use", "image", "stop", "feColorMatrix", "feGaussianBlur", "title", "foreignObject"};
    static final String[] ATTRS = {"x", "y", "width", "height", "rx", "ry", "r", "cx", "cy", "fx", "fy", "x1", "y1", "x2", "y2", "dx", "dy", "offset",
            "fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "stroke-dasharray", "stroke-dashoffset", "stroke-linejoin",
            "stroke-linecap", "stroke-miterlimit", "fill-rule", "clip-rule", "font-size", "font-family", "font-weight", "text-anchor", "display",
            "visibility", "clip-path", "mask", "filter", "stop-color", "stop-opacity", "overflow", "color", "transform", "gradientTransform",
            "gradientUnits", "clipPathUnits", "maskUnits", "maskContentUnits", "filterUnits", "spreadMethod", "viewBox", "preserveAspectRatio",
            "points", "d", "style", "class", "href", "xlink:href", "requiredExtensions", "requiredFeatures", "systemLanguage", "xml:space", "type",
            "values", "in", "result", "color-interpolation-filters"};
    // 埋め込む画像 (data: の URI) に使う PNG
    static String png;

    private SvgFuzz() {
    }

    public static void main(String[] args) throws Exception {
        final int cases = Integer.parseInt(args[0]);
        final List<byte[]> seeds = new ArrayList<byte[]>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), seeds);
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        png = Base64.getEncoder().encodeToString(samplePng());
        System.out.println("SvgFuzz: " + cases + " cases, " + seeds.size() + " seed files, seed=" + seed);
        ExecutorService executor = newExecutor();
        int problems = 0;
        long rendered = 0, rejected = 0, slowest = 0;
        final long start = System.nanoTime();
        for (int n = 0; n < cases; n++) {
            final Random random = new Random(seed * 1000003L + n);
            final byte[] data = !seeds.isEmpty() && random.nextInt(3) == 0 ? mutate(seeds.get(random.nextInt(seeds.size())), random)
                    : new Gen(random).document().getBytes(StandardCharsets.UTF_8);
            final long t0 = System.nanoTime();
            final Future<Object> future = executor.submit(() -> ImageLoader.decode(data, "fuzz.svg"));
            String problem = null;
            try {
                future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                rendered++;
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                executor = newExecutor();
                problem = "timeout (" + CASE_TIMEOUT_SECONDS + " s)";
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    rejected++;
                } else {
                    final java.io.StringWriter trace = new java.io.StringWriter();
                    e.getCause().printStackTrace(new java.io.PrintWriter(trace));
                    problem = trace.toString();
                    if (problem.length() > 3000) problem = problem.substring(0, 3000);
                }
            }
            final long ms = (System.nanoTime() - t0) / 1000000;
            slowest = Math.max(slowest, ms);
            if (problem == null && ms > SLOW_MILLIS) problem = "slow: " + ms + " ms";
            if (problem != null) {
                problems++;
                final File dir = new File("build/svg-fuzz");
                dir.mkdirs();
                final File file = new File(dir, "case-" + seed + "-" + n + ".svg");
                Files.write(file.toPath(), data);
                System.out.println("FAIL case " + n + ": " + problem + " -> " + file);
            }
        }
        executor.shutdownNow();
        System.out.println("SvgFuzz finished in " + (System.nanoTime() - start) / 1000000000 + " s, rendered " + rendered
                + ", rejected " + rejected + ", slowest " + slowest + " ms, failures: " + problems);
        System.exit(problems == 0 ? 0 : 1);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "SvgFuzz");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void collect(File file, List<byte[]> seeds) throws IOException {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) collect(child, seeds);
        } else if (file.isFile() && file.length() < 300000 && file.getName().endsWith(".svg")) {
            seeds.add(Files.readAllBytes(file.toPath()));
        }
    }

    // 小さな色付きの PNG
    private static byte[] samplePng() throws IOException {
        final BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xFF000000 | (x * 16) << 16 | (y * 20) << 8 | 0x80);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // テキストとして壊す: 数を極端な値に、部分を複製・削除・入れ替える
    static byte[] mutate(byte[] original, Random r) {
        String s = new String(original, StandardCharsets.UTF_8);
        final int count = 1 + r.nextInt(6);
        for (int k = 0; k < count && !s.isEmpty(); k++) {
            final int p = r.nextInt(s.length());
            switch (r.nextInt(7)) {
                case 0: {
                    // 数を置き換える
                    int a = p;
                    while (a < s.length() && !Character.isDigit(s.charAt(a))) a++;
                    int b = a;
                    while (b < s.length() && (Character.isDigit(s.charAt(b)) || s.charAt(b) == '.')) b++;
                    if (a < s.length()) s = s.substring(0, a) + Gen.number(r) + s.substring(b);
                    break;
                }
                case 1: {
                    final int len = Math.min(s.length() - p, 1 + r.nextInt(200));
                    final String chunk = s.substring(p, p + len);
                    final int times = r.nextInt(4) == 0 ? 1000 + r.nextInt(5000) : 1 + r.nextInt(10);
                    final StringBuilder b = new StringBuilder(s.substring(0, p));
                    for (int i = 0; i < times && b.length() < 3000000; i++) b.append(chunk);
                    s = b + s.substring(p);
                    break;
                }
                case 2:
                    s = s.substring(0, p) + s.substring(Math.min(s.length(), p + 1 + r.nextInt(50)));
                    break;
                case 3: {
                    final String[] junk = {"<", ">", "\"", "'", "&", "&amp;", "<g>", "</g>", "]]>", "<![CDATA[", "url(#", ")", "(", "/*", "*/", "{", "}", ";", ":", "#", "e", "-", ".", "1e999", "NaN", "\u0000", "￿", "\uD800"};
                    s = s.substring(0, p) + junk[r.nextInt(junk.length)] + s.substring(p);
                    break;
                }
                case 4: {
                    final int q = r.nextInt(s.length());
                    final int len = Math.min(s.length() - q, 1 + r.nextInt(300));
                    s = s.substring(0, p) + s.substring(q, q + len) + s.substring(p);
                    break;
                }
                case 5:
                    s = s.substring(0, p);
                    break;
                default: {
                    final int tag = s.indexOf('>', p);
                    if (tag > 0) s = s.substring(0, tag) + " " + new Gen(r).attribute() + s.substring(tag);
                    break;
                }
            }
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final class Gen {
        final Random r;
        final StringBuilder b = new StringBuilder();
        final List<String> ids = new ArrayList<String>();
        int elements;

        Gen(Random r) {
            this.r = r;
            for (int i = 0; i < 6; i++) ids.add("i" + i);
        }

        String document() {
            if (r.nextInt(10) == 0) b.append("<?xml version=\"1.0\"?>");
            if (r.nextInt(12) == 0) doctype();
            b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"");
            if (r.nextInt(4) != 0) b.append(" width=\"").append(r.nextInt(3) == 0 ? number(r) : String.valueOf(1 + r.nextInt(300))).append('"');
            if (r.nextInt(4) != 0) b.append(" height=\"").append(r.nextInt(3) == 0 ? number(r) : String.valueOf(1 + r.nextInt(300))).append('"');
            if (r.nextInt(2) == 0) b.append(" viewBox=\"").append(list(4)).append('"');
            if (r.nextInt(5) == 0) b.append(" preserveAspectRatio=\"").append(pick("none", "xMinYMax slice", "xMidYMid meet", "defer xMaxYMin", "junk")).append('"');
            b.append('>');
            final int mode = r.nextInt(10);
            if (mode == 0) deep();
            else if (mode == 1) bomb();
            else {
                final int n = 1 + r.nextInt(12);
                for (int i = 0; i < n; i++) element(0);
            }
            if (r.nextInt(10) != 0) b.append("</svg>");
            return b.toString();
        }

        void doctype() {
            b.append("<!DOCTYPE svg ");
            switch (r.nextInt(5)) {
                case 0:
                    b.append("PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://127.0.0.1:18888/svg11.dtd\">");
                    break;
                case 1:
                    b.append("[<!ENTITY x SYSTEM \"file:///etc/hostname\"><!ENTITY y SYSTEM \"http://127.0.0.1:18888/y\">]>");
                    break;
                case 2:
                    b.append("[<!ENTITY a \"").append(repeat("ab", r.nextInt(2000))).append("\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]>");
                    break;
                case 3:
                    b.append("[<!ENTITY % p SYSTEM \"http://127.0.0.1:18888/p\"> %p; <!ENTITY ns \"http://www.w3.org/2000/svg\">]>");
                    break;
                default:
                    b.append("[<!ELEMENT svg ANY><!ATTLIST svg width CDATA \"50\"><!-- c --><?pi x?>");
                    if (r.nextBoolean()) b.append("]>");
                    break;
            }
        }

        void deep() {
            final String[] kinds = {"g", "svg", "tspan", "a", "clipPath", "mask", "switch", "symbol", "text", "style"};
            final String kind = kinds[r.nextInt(kinds.length)];
            final int depth = 100 + r.nextInt(r.nextInt(3) == 0 ? 50000 : 2000);
            final String open = "<" + kind + (r.nextBoolean() ? " opacity=\"0.9\"" : "") + (r.nextBoolean() ? " id=\"d\" clip-path=\"url(#d)\"" : "") + ">";
            for (int i = 0; i < depth; i++) {
                b.append(open);
                if (r.nextInt(50) == 0) b.append("x<use href=\"#d\"/>");
            }
            element(0);
            for (int i = 0; i < depth; i++) b.append("</").append(kind).append('>');
        }

        void bomb() {
            b.append("<defs><rect id=\"b0\" width=\"").append(number(r)).append("\" height=\"100\" opacity=\"0.5\" stroke=\"red\" stroke-dasharray=\"1\"/>");
            final int levels = 2 + r.nextInt(8);
            for (int i = 1; i <= levels; i++) {
                b.append("<g id=\"b").append(i).append("\"").append(r.nextBoolean() ? " opacity=\"0.7\"" : "").append(r.nextBoolean() ? " clip-path=\"url(#cp)\"" : "").append('>');
                final int fan = 2 + r.nextInt(10);
                for (int k = 0; k < fan; k++) b.append("<use href=\"#b").append(r.nextInt(i + 1)).append("\" x=\"").append(r.nextInt(10)).append("\"/>");
                b.append("</g>");
            }
            b.append("<clipPath id=\"cp\"><circle r=\"50\" cx=\"50\" cy=\"50\"/><use href=\"#b").append(levels).append("\"/></clipPath>");
            b.append("</defs><use href=\"#b").append(levels).append("\"/>");
            if (r.nextBoolean()) b.append("<g mask=\"url(#m)\"><use href=\"#b").append(levels).append("\"/></g><mask id=\"m\"><use href=\"#b").append(levels).append("\"/></mask>");
        }

        void element(int depth) {
            if (++elements > 400 || b.length() > 2000000) return;
            final boolean container = depth < 8 && r.nextInt(3) == 0;
            final String name = container ? CONTAINERS[r.nextInt(CONTAINERS.length)] : LEAVES[r.nextInt(LEAVES.length)];
            final int repeat = r.nextInt(40) == 0 ? 100 + r.nextInt(3000) : 1;
            final int start = b.length();
            b.append('<').append(name);
            if (r.nextInt(3) == 0) {
                final String id = r.nextInt(4) == 0 ? ids.get(r.nextInt(ids.size())) : "i" + ids.size();
                ids.add(id);
                b.append(" id=\"").append(id).append('"');
            }
            final java.util.Set<String> used = new java.util.HashSet<String>();
            used.add("id");
            final int attributes = r.nextInt(7);
            for (int i = 0; i < attributes; i++) {
                final String a = attribute();
                if (used.add(a.substring(0, a.indexOf('=')))) b.append(' ').append(a);
            }
            if ((name.equals("use") || name.equals("image") || r.nextInt(8) == 0) && !used.contains("href") && !used.contains("xlink:href")) {
                b.append(' ').append(r.nextBoolean() ? "href" : "xlink:href").append("=\"").append(href(name).replace("<", "&lt;")).append('"');
            }
            if ((name.equals("path") || r.nextInt(10) == 0) && !used.contains("d")) b.append(" d=\"").append(path()).append('"');
            if (container) {
                b.append('>');
                if (name.equals("style")) b.append(css());
                else if (name.equals("text") || name.equals("tspan")) b.append(text());
                final int n = r.nextInt(6);
                for (int i = 0; i < n; i++) element(depth + 1);
                if (r.nextInt(3) == 0) b.append(text());
                b.append("</").append(name).append('>');
            } else {
                b.append("/>");
            }
            if (repeat > 1) {
                final String one = b.substring(start);
                for (int i = 1; i < repeat && b.length() < 3000000; i++) b.append(one);
            }
        }

        String attribute() {
            final String name = ATTRS[r.nextInt(ATTRS.length)];
            return name + "=\"" + value(name).replace("\"", "&quot;").replace("<", "&lt;").replace("&", r.nextInt(20) == 0 ? "&" : "&amp;") + "\"";
        }

        String value(String name) {
            switch (name) {
                case "fill": case "stroke": case "stop-color": case "color":
                    return paint();
                case "clip-path": case "mask": case "filter":
                    return r.nextInt(5) == 0 ? "none" : "url(#" + ids.get(r.nextInt(ids.size())) + ")";
                case "transform": case "gradientTransform":
                    return transform();
                case "style":
                    return declarations();
                case "points": case "viewBox": case "values": case "stroke-dasharray":
                    return list(r.nextInt(4) == 0 ? r.nextInt(5000) : r.nextInt(25));
                case "d":
                    return path();
                case "class":
                    return pick("a", "b c", "a b c d", repeat("a ", 500), "");
                case "font-family":
                    return pick("serif", "sans-serif", "monospace", "'Bitstream Vera Sans'", "F" + r.nextInt(100), "x,y,z", repeat("f", 2000));
                case "preserveAspectRatio":
                    return pick("none", "xMinYMin slice", "xMaxYMax", "", "junk junk");
                case "xml:space":
                    return pick("preserve", "default");
                case "type":
                    return pick("matrix", "saturate", "hueRotate", "luminanceToAlpha", "x");
                case "in":
                    return pick("SourceGraphic", "SourceAlpha", "r", "");
                default:
                    if (r.nextInt(4) == 0) return pick("none", "inherit", "evenodd", "round", "bevel", "square", "middle", "end", "hidden", "visible",
                            "objectBoundingBox", "userSpaceOnUse", "reflect", "repeat", "bold", "linearRGB", "sRGB", "en", "xx", "");
                    return number(r) + (r.nextInt(5) == 0 ? pick("%", "px", "em", "ex", "mm", "in", "pt", "pc", "cm", "q") : "");
            }
        }

        String href(String element) {
            switch (r.nextInt(8)) {
                case 0:
                    return "data:image/png;base64," + png;
                case 1:
                    return "data:image/png;base64," + png.substring(0, r.nextInt(png.length()));
                case 2:
                    return pick("http://127.0.0.1:18888/i.png", "file:///etc/passwd", "/etc/hostname", "data:image/svg+xml;utf8,<svg/>", "data:,x", "data:image/png;base64,", "#");
                default:
                    return "#" + ids.get(r.nextInt(ids.size()));
            }
        }

        String paint() {
            switch (r.nextInt(8)) {
                case 0: return "url(#" + ids.get(r.nextInt(ids.size())) + ")" + (r.nextBoolean() ? " red" : "");
                case 1: return "#" + Integer.toHexString(r.nextInt());
                case 2: return "rgb(" + number(r) + "," + number(r) + "%," + number(r) + ")";
                case 3: return "hsla(" + number(r) + "," + number(r) + "%," + number(r) + "%," + number(r) + ")";
                case 4: return pick("none", "currentColor", "transparent", "red", "rebeccapurple", "junk", "url(", "url(#", repeat("url(#x) ", 300));
                default: return pick("black", "white", "blue", "#0f08", "#123456");
            }
        }

        String transform() {
            final StringBuilder t = new StringBuilder();
            final int n = r.nextInt(10) == 0 ? 1000 + r.nextInt(20000) : 1 + r.nextInt(4);
            for (int i = 0; i < n; i++) {
                final String f = pick("matrix", "translate", "scale", "rotate", "skewX", "skewY", "junk", "a(");
                t.append(f).append('(');
                final int args = r.nextInt(8);
                for (int k = 0; k < args; k++) t.append(k > 0 ? (r.nextBoolean() ? "," : " ") : "").append(number(r));
                if (r.nextInt(20) != 0) t.append(')');
                t.append(' ');
            }
            return t.toString();
        }

        String declarations() {
            final StringBuilder d = new StringBuilder();
            final int n = r.nextInt(8);
            for (int i = 0; i < n; i++) {
                final String name = ATTRS[r.nextInt(ATTRS.length)];
                d.append(name).append(':').append(value(name)).append(r.nextInt(10) == 0 ? " !important" : "").append(';');
            }
            if (r.nextInt(10) == 0) d.append(repeat(";:", 5000));
            return d.toString();
        }

        String css() {
            final StringBuilder c = new StringBuilder("<![CDATA[");
            final int n = r.nextInt(10) == 0 ? 1000 + r.nextInt(5000) : r.nextInt(10);
            for (int i = 0; i < n && c.length() < 1000000; i++) {
                switch (r.nextInt(12)) {
                    case 0: c.append("/*"); break;
                    case 1: c.append("@media screen {"); break;
                    case 2: c.append("@font-face { src: url('x') }"); break;
                    case 3: c.append("}"); break;
                    case 4: c.append(repeat("a ", 200)); break;
                    case 5: c.append("'"); break;
                    default: {
                        final String[] sel = {"*", "rect", ".a", "#i1", "g > .b", ".a .b .c", "g ~ rect", "rect + circle", ".a:hover", "[x]", "svg|rect", "a,b,,", "#i2.a.b"};
                        c.append(sel[r.nextInt(sel.length)]).append(" {").append(declarations()).append("}\n");
                    }
                }
            }
            return c.append("]]>").toString();
        }

        String text() {
            final String[] t = {"Hello", " world ", "\n  multi\tline  ", "日本語のテキスト", "مرحبا", "é", "😀", "&amp;", "&#x10FFFF;", "&lt;", r.nextInt(10) == 0 ? "&c;" : "W"};
            final String s = t[r.nextInt(t.length)];
            return r.nextInt(30) == 0 ? repeat(s, 1000 + r.nextInt(10000)) : s;
        }

        String path() {
            final StringBuilder p = new StringBuilder(r.nextInt(5) == 0 ? "" : "M" + number(r) + " " + number(r));
            final int n = r.nextInt(15) == 0 ? 10000 + r.nextInt(100000) : r.nextInt(20);
            final String commands = "MmLlHhVvCcSsQqTtAaZzX";
            for (int i = 0; i < n && p.length() < 2000000; i++) {
                final char c = commands.charAt(r.nextInt(commands.length()));
                p.append(c);
                final int args = c == 'A' || c == 'a' ? 7 : 1 + r.nextInt(6);
                for (int k = 0; k < args; k++) {
                    if ((c == 'A' || c == 'a') && (k == 3 || k == 4)) p.append(r.nextBoolean() ? "1" : "0");
                    else p.append(r.nextBoolean() ? " " : ",").append(r.nextInt(3) == 0 ? number(r) : String.valueOf(r.nextInt(200) - 50));
                }
            }
            return p.toString();
        }

        String list(int n) {
            final StringBuilder l = new StringBuilder();
            for (int i = 0; i < n; i++) l.append(i > 0 ? (r.nextBoolean() ? " " : ",") : "").append(r.nextInt(3) == 0 ? number(r) : String.valueOf(r.nextInt(100)));
            return l.toString();
        }

        String pick(String... options) {
            return options[r.nextInt(options.length)];
        }

        static String number(Random r) {
            switch (r.nextInt(12)) {
                case 0: return "1e308";
                case 1: return "-1e308";
                case 2: return "1e-308";
                case 3: return "1e999";
                case 4: return r.nextBoolean() ? "NaN" : "Infinity";
                case 5: return "0";
                case 6: return "-" + r.nextInt(100000);
                case 7: return repeat("9", 1 + r.nextInt(400));
                case 8: return r.nextBoolean() ? "3.4e38" : "-.5e-3";
                case 9: return r.nextBoolean() ? "." : "1e";
                default: return String.valueOf(r.nextDouble() * 200 - 50);
            }
        }

        static String repeat(String s, int n) {
            final StringBuilder b = new StringBuilder(s.length() * Math.max(0, n));
            for (int i = 0; i < n; i++) b.append(s);
            return b.toString();
        }
    }
}
