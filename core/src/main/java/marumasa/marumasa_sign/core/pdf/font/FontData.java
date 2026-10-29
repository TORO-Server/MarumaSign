package marumasa.marumasa_sign.core.pdf.font;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * フォントに使う表 (marumasa_sign/pdf の encodings.txt、glyphlist.txt.gz、afm.txt.gz)。
 * どれも tools/pdfdata/gen_pdf_data.py で作ったもの。初めて使うときに読み込む。
 */
public final class FontData {

    private static final String BASE = "/marumasa_sign/pdf/";

    private FontData() {
    }

    // ---------- 文字コードの表 ----------

    private static final class Tables {
        static final Map<String, String[]> ENCODINGS = new HashMap<>();
        static final Map<String, int[]> NUMBERS = new HashMap<>();
        static String[] cffStrings = new String[0];

        static {
            try (BufferedReader reader = open("encodings.txt", false)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    final String body = reader.readLine();
                    if (body == null) break;
                    if (line.startsWith("encoding ")) {
                        final String[] names = body.split(" ", -1);
                        for (int i = 0; i < names.length; i++) {
                            if (names[i].equals(".")) names[i] = null;
                        }
                        ENCODINGS.put(line.substring(9), names);
                    } else if (line.equals("cffstrings")) {
                        cffStrings = body.split(" ", -1);
                    } else {
                        final String[] parts = body.split(" ");
                        final int[] values = new int[parts.length];
                        for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i]);
                        NUMBERS.put(line, values);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // 表がなければ、文字コードの表を使うフォントは代わりの文字で描かれる
            }
        }
    }

    /**
     * 名前の付いた文字コードの表 (Standard、WinAnsi、MacRoman、MacOSRoman、MacExpert、Symbol、ZapfDingbats)。
     * 256 個のグリフの名前 (割り当てのないコードは null)。知らない名前なら null。
     */
    public static String[] encoding(String name) {
        final String[] names = Tables.ENCODINGS.get(name);
        return names == null || names.length < 256 ? null : names;
    }

    // CFF の標準の文字列 (SID 0 から 390)
    static String[] cffStandardStrings() {
        return Tables.cffStrings;
    }

    // CFF の定義済みの文字集合 (Expert と ExpertSubset) の SID の並びと、Expert の文字コードの表 (コードから SID)
    static int[] cffTable(String name) {
        return Tables.NUMBERS.get(name);
    }

    // ---------- グリフの名前と Unicode ----------

    private static final class Glyphs {
        static final Map<String, String> NAMES = new HashMap<>();
        static final Map<String, String> DINGBATS = new HashMap<>();
        static final Map<Integer, String> REVERSE = new HashMap<>();

        static {
            try (BufferedReader reader = open("glyphlist.txt.gz", true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int semicolon = line.indexOf(';');
                    if (semicolon < 0) continue;
                    final boolean dingbat = line.startsWith("Z ");
                    final String name = line.substring(dingbat ? 2 : 0, semicolon);
                    final StringBuilder text = new StringBuilder();
                    for (String hex : line.substring(semicolon + 1).trim().split(" ")) {
                        if (!hex.isEmpty()) text.appendCodePoint(Integer.parseInt(hex, 16));
                    }
                    (dingbat ? DINGBATS : NAMES).put(name, text.toString());
                    if (!dingbat && text.codePointCount(0, text.length()) == 1) {
                        final int code = text.codePointAt(0);
                        if (!REVERSE.containsKey(code)) REVERSE.put(code, name);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // 表がなければ、uniXXXX の形の名前だけが分かる
            }
        }
    }

    /**
     * グリフの名前を Unicode の文字にする (Adobe Glyph List の決まり)。分からなければ null。
     *
     * @param dingbats ZapfDingbats のフォントか (a1 などの名前の意味が違う)
     */
    public static String unicode(String name, boolean dingbats) {
        if (name == null) return null;
        if (dingbats) {
            final String value = Glyphs.DINGBATS.get(name);
            if (value != null) return value;
        }
        final String value = Glyphs.NAMES.get(name);
        if (value != null) return value;
        // 「名前.接尾辞」(a.sc など) は、点の前の部分で探す
        final int dot = name.indexOf('.');
        if (dot > 0) return unicode(name.substring(0, dot), dingbats);
        // uniXXXX (4 桁ずつ、複数可) と uXXXX[XX]
        if (name.startsWith("uni") && name.length() >= 7 && (name.length() - 3) % 4 == 0) {
            final StringBuilder text = new StringBuilder();
            for (int i = 3; i < name.length(); i += 4) {
                final int code = hex(name.substring(i, i + 4));
                if (code < 0 || (code >= 0xD800 && code <= 0xDFFF)) return null;
                text.append((char) code);
            }
            return text.toString();
        }
        if (name.startsWith("u") && name.length() >= 5 && name.length() <= 7) {
            final int code = hex(name.substring(1));
            if (code >= 0 && code <= 0x10FFFF && !(code >= 0xD800 && code <= 0xDFFF)) return new String(Character.toChars(code));
        }
        return null;
    }

    // Unicode の 1 文字からグリフの名前 (Adobe Glyph List で最初に出てくるもの)。なければ null
    public static String glyphName(int codePoint) {
        return Glyphs.REVERSE.get(codePoint);
    }

    private static int hex(String text) {
        if (text.isEmpty() || text.length() > 6) return -1;
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }

    // ---------- 標準 14 フォントの寸法 ----------

    /**
     * 標準 14 フォントの 1 つの寸法 (AFM の必要な項目)。
     */
    public static final class Metrics {
        public final String fontName;
        public final String familyName;
        public final String weight;
        public final double italicAngle;
        public final boolean fixedPitch;
        public final double[] fontBox;
        public final double capHeight, xHeight, ascent, descent;
        // 名前ごとの幅
        public final Map<String, Double> widths = new HashMap<>();
        // コードごとのグリフの名前 (フォントが持つ文字コードの表)
        public final String[] encoding = new String[256];

        Metrics(Map<String, String> fields) {
            fontName = fields.get("FontName");
            familyName = fields.containsKey("FamilyName") ? fields.get("FamilyName") : fontName;
            weight = fields.containsKey("Weight") ? fields.get("Weight") : "Medium";
            italicAngle = number(fields.get("ItalicAngle"));
            fixedPitch = "true".equals(fields.get("IsFixedPitch"));
            final String[] box = fields.containsKey("FontBBox") ? fields.get("FontBBox").trim().split("\\s+") : new String[0];
            fontBox = new double[4];
            for (int i = 0; i < 4 && i < box.length; i++) fontBox[i] = number(box[i]);
            capHeight = number(fields.get("CapHeight"));
            xHeight = number(fields.get("XHeight"));
            ascent = number(fields.get("Ascender"));
            descent = number(fields.get("Descender"));
        }

        private static double number(String text) {
            try {
                return text == null ? 0 : Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static final class Afm {
        static final Map<String, Metrics> FONTS = new HashMap<>();

        static {
            try (BufferedReader reader = open("afm.txt.gz", true)) {
                Map<String, String> fields = null;
                final java.util.List<String[]> chars = new java.util.ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("StartFont")) {
                        fields = new HashMap<>();
                        chars.clear();
                    } else if (line.equals("EndFont") && fields != null) {
                        final Metrics metrics = new Metrics(fields);
                        for (String[] c : chars) {
                            final int code = Integer.parseInt(c[1]);
                            metrics.widths.put(c[3], Double.parseDouble(c[2]));
                            if (code >= 0 && code < 256) metrics.encoding[code] = c[3];
                        }
                        FONTS.put(metrics.fontName, metrics);
                        fields = null;
                    } else if (fields != null) {
                        if (line.startsWith("C ")) {
                            final String[] parts = line.split(" ");
                            if (parts.length >= 4) chars.add(parts);
                        } else {
                            final int space = line.indexOf(' ');
                            if (space > 0) fields.put(line.substring(0, space), line.substring(space + 1));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // 表がなければ、標準 14 フォントの幅はフォントの代わりのものから求める
            }
        }
    }

    // 標準 14 フォントの別名 (Acrobat が受け付ける名前)
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        final String[][] aliases = {
                {"Arial", "Helvetica"}, {"Arial,Bold", "Helvetica-Bold"}, {"Arial,Italic", "Helvetica-Oblique"},
                {"Arial,BoldItalic", "Helvetica-BoldOblique"}, {"ArialMT", "Helvetica"}, {"Arial-BoldMT", "Helvetica-Bold"},
                {"Arial-ItalicMT", "Helvetica-Oblique"}, {"Arial-BoldItalicMT", "Helvetica-BoldOblique"},
                {"Helvetica,Bold", "Helvetica-Bold"}, {"Helvetica,Italic", "Helvetica-Oblique"},
                {"Helvetica,BoldItalic", "Helvetica-BoldOblique"}, {"Helvetica-Italic", "Helvetica-Oblique"},
                {"Helvetica-BoldItalic", "Helvetica-BoldOblique"},
                {"CourierNew", "Courier"}, {"CourierNew,Bold", "Courier-Bold"}, {"CourierNew,Italic", "Courier-Oblique"},
                {"CourierNew,BoldItalic", "Courier-BoldOblique"}, {"CourierNewPSMT", "Courier"},
                {"CourierNewPS-BoldMT", "Courier-Bold"}, {"CourierNewPS-ItalicMT", "Courier-Oblique"},
                {"CourierNewPS-BoldItalicMT", "Courier-BoldOblique"}, {"Courier,Bold", "Courier-Bold"},
                {"Courier,Italic", "Courier-Oblique"}, {"Courier,BoldItalic", "Courier-BoldOblique"},
                {"TimesNewRoman", "Times-Roman"}, {"TimesNewRoman,Bold", "Times-Bold"},
                {"TimesNewRoman,Italic", "Times-Italic"}, {"TimesNewRoman,BoldItalic", "Times-BoldItalic"},
                {"TimesNewRomanPSMT", "Times-Roman"}, {"TimesNewRomanPS-BoldMT", "Times-Bold"},
                {"TimesNewRomanPS-ItalicMT", "Times-Italic"}, {"TimesNewRomanPS-BoldItalicMT", "Times-BoldItalic"},
                {"Times", "Times-Roman"}, {"Times,Bold", "Times-Bold"}, {"Times,Italic", "Times-Italic"},
                {"Times,BoldItalic", "Times-BoldItalic"},
                {"Symbol,Bold", "Symbol"}, {"Symbol,Italic", "Symbol"}, {"Symbol,BoldItalic", "Symbol"},
                {"SymbolMT", "Symbol"}, {"ZapfDingbats", "ZapfDingbats"}, {"Dingbats", "ZapfDingbats"}};
        for (String[] alias : aliases) ALIASES.put(alias[0], alias[1]);
    }

    /**
     * 標準 14 フォントの名前 (別名なら正式な名前にしたもの)。標準 14 フォントでなければ null。
     */
    public static String standard14Name(String baseFont) {
        if (baseFont == null) return null;
        final String name = stripSubset(baseFont);
        if (Afm.FONTS.containsKey(name)) return name;
        return ALIASES.get(name);
    }

    public static Metrics standard14(String name) {
        return name == null ? null : Afm.FONTS.get(name);
    }

    /**
     * 埋め込みのために一部の文字だけにしたフォントの名前の前置き (ABCDEF+) を除く。
     */
    public static String stripSubset(String name) {
        if (name.length() > 7 && name.charAt(6) == '+') {
            for (int i = 0; i < 6; i++) {
                if (name.charAt(i) < 'A' || name.charAt(i) > 'Z') return name;
            }
            return name.substring(7);
        }
        return name;
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static BufferedReader open(String name, boolean gzip) throws IOException {
        final InputStream input = FontData.class.getResourceAsStream(BASE + name);
        if (input == null) throw new IOException("Missing " + name);
        return new BufferedReader(new InputStreamReader(gzip ? new GZIPInputStream(input) : input, StandardCharsets.UTF_8));
    }
}
