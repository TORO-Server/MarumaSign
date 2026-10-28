package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.Strict;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.Arrays;

/**
 * 1 バイトの文字コードのフォント (Type1、MMType1、TrueType)。
 * 文字コードからグリフを選ぶ規則は、PDF の仕様 (9.6.5、9.6.6) と、よく使われる PDF の読み手の振る舞いに合わせる。
 */
final class SimpleFont extends PdfFont {

    private final String baseFont;
    private final boolean trueType;
    private final String standard14;
    private final FontData.Metrics metrics;
    private final PdfDict descriptor;
    private final int flags;
    private final boolean symbolic;
    private final boolean dingbats;
    // 埋め込まれたフォント (なければ null)
    private final FontProgram program;
    // 埋め込まれていないときの代わり
    private FontProgram substitute;
    private boolean substituteSearched;
    // コードごとのグリフの名前
    private final String[] names = new String[256];
    // /Widths
    private final int firstChar;
    private final double[] widths;
    private final double missingWidth;
    private final boolean hasMissingWidth;
    // コードごとの GID (-1 はまだ求めていない)
    private final int[] gids = new int[256];

    SimpleFont(PdfDict dict) {
        super(dict);
        final String base = dict.name("BaseFont");
        baseFont = base == null ? "" : base;
        trueType = "TrueType".equals(dict.name("Subtype"));
        descriptor = dict.dict("FontDescriptor");
        flags = flags(descriptor);
        program = embedded(descriptor, false);
        // 標準 14 フォント: 埋め込まれていないときだけ、寸法の表を使う
        final String standardName = FontData.standard14Name(baseFont);
        standard14 = program == null ? standardName : null;
        metrics = FontData.standard14(standardName);
        dingbats = "ZapfDingbats".equals(standardName) || FontData.stripSubset(baseFont).contains("Dingbats");

        final Object encoding = dict.get("Encoding");
        final boolean hasFlag = descriptor != null && descriptor.has("Flags");
        if (hasFlag && (flags & 4) != 0) {
            symbolic = true;
        } else if (hasFlag && (flags & 32) != 0) {
            symbolic = false;
        } else if (standardName != null) {
            symbolic = standardName.equals("Symbol") || standardName.equals("ZapfDingbats");
        } else {
            symbolic = encoding == null;
        }
        buildEncoding(encoding);

        final PdfArray w = dict.array("Widths");
        firstChar = dict.integer("FirstChar", 0);
        widths = w == null ? null : numbers(w);
        hasMissingWidth = descriptor != null && descriptor.has("MissingWidth");
        missingWidth = descriptor == null ? 0 : descriptor.number("MissingWidth", 0);
        Arrays.fill(gids, -1);
    }

    private static double[] numbers(PdfArray array) {
        final double[] result = new double[Math.min(array.size(), 65536)];
        for (int i = 0; i < result.length; i++) result[i] = array.number(i, 0);
        return result;
    }

    // ---------- 文字コードの表 ----------

    private String[] builtInEncoding() {
        if (program instanceof Type1Font) return ((Type1Font) program).builtInEncoding();
        if (program instanceof CffFont) return ((CffFont) program).builtInEncoding();
        if (program instanceof TrueTypeFont && ((TrueTypeFont) program).isCff()) return ((TrueTypeFont) program).cff().builtInEncoding();
        if (program == null && metrics != null && (metrics.fontName.equals("Symbol") || metrics.fontName.equals("ZapfDingbats"))) {
            return metrics.encoding;
        }
        return null;
    }

    private static String[] named(String name) {
        if (name == null) return null;
        switch (name) {
            case "WinAnsiEncoding":
                return FontData.encoding("WinAnsi");
            case "MacRomanEncoding":
                return FontData.encoding("MacRoman");
            case "MacExpertEncoding":
                return FontData.encoding("MacExpert");
            case "StandardEncoding":
                return FontData.encoding("Standard");
            default:
                return null;
        }
    }

    private void buildEncoding(Object encoding) {
        final String[] builtIn = builtInEncoding();
        String[] base = null;
        PdfArray differences = null;
        if (encoding instanceof PdfName) {
            base = named(((PdfName) encoding).name);
            // 記号のフォントに文字の表を指定したものは、フォントの持つ表を使う (標準 14 の Symbol など)
            if (base == null) base = builtIn;
        } else if (encoding instanceof PdfDict) {
            final PdfDict encodingDict = (PdfDict) encoding;
            base = named(encodingDict.name("BaseEncoding"));
            if (base == null) base = builtIn != null ? builtIn : symbolic && !trueType ? null : FontData.encoding("Standard");
            differences = encodingDict.array("Differences");
        } else {
            base = builtIn;
            if (base == null && !(trueType && symbolic)) base = FontData.encoding("Standard");
        }
        if (base != null) System.arraycopy(base, 0, names, 0, 256);
        if (differences != null) {
            int code = 0;
            for (int i = 0; i < differences.size(); i++) {
                final Object item = differences.get(i);
                if (item instanceof Number) {
                    code = ((Number) item).intValue();
                } else if (item instanceof PdfName) {
                    if (code >= 0 && code < 256) names[code] = ((PdfName) item).name;
                    code++;
                }
            }
        }
    }

    // ---------- 文字 ----------

    @Override
    public long readCode(byte[] bytes, int position, int[] length) {
        length[0] = 1;
        return bytes[position] & 0xFF;
    }

    private boolean hasExplicitWidth(int code) {
        // FirstChar が極端な値でもあふれないように long で比べる
        return widths != null && code >= firstChar && (long) code - firstChar < widths.length;
    }

    @Override
    public double width(long character) {
        final int code = (int) character & 0xFF;
        if (hasExplicitWidth(code)) return widths[code - firstChar] / 1000;
        if (hasMissingWidth) return missingWidth / 1000;
        if (standard14 != null && metrics != null) {
            final String name = names[code & 0xFF];
            final Double width = name == null ? null : metrics.widths.get(name);
            return width == null ? 0 : width / 1000;
        }
        final FontProgram font = font();
        if (font == null) return 0;
        final int gid = gid(code);
        return gid > 0 || font instanceof TrueTypeFont ? font.advance(gid) : 0;
    }

    private FontProgram font() {
        if (program != null) return program;
        if (!substituteSearched) {
            substituteSearched = true;
            substitute = findSubstitute();
        }
        return substitute;
    }

    private FontProgram findSubstitute() {
        final SystemFonts system = SystemFonts.get();
        final String name = FontData.stripSubset(baseFont);
        FontProgram font = system.findByName(name, trueType);
        if (font == null) {
            final String fallback = SystemFonts.fallbackName(descriptor == null ? baseFont : descriptor.name("FontName") != null
                            ? descriptor.name("FontName") : baseFont, descriptor != null,
                    isFixedPitch(flags), isSerif(flags), isItalic(flags));
            font = system.findByName(fallback, trueType);
        }
        if (font == null) font = system.lastResort();
        if (font == null) {
            final boolean bold = baseFont.toLowerCase(java.util.Locale.ROOT).contains("bold");
            final int style = (bold ? java.awt.Font.BOLD : 0) | (isItalic(flags) ? java.awt.Font.ITALIC : 0);
            font = new AwtFallbackFont(isFixedPitch(flags) ? "Monospaced" : isSerif(flags) ? "Serif" : "SansSerif", style);
        }
        return font;
    }

    private synchronized int gid(int code) {
        final int c = code & 0xFF;
        if (gids[c] >= 0) return gids[c];
        int gid;
        try {
            gid = computeGid(c);
        } catch (RuntimeException e) {
            Strict.check(e);
            gid = 0;
        }
        gids[c] = Math.max(0, gid);
        return gids[c];
    }

    private int computeGid(int code) {
        final FontProgram font = font();
        if (font == null) return 0;
        final String name = names[code];
        if (font instanceof TrueTypeFont && !((TrueTypeFont) font).isCff()) {
            return program != null || trueType ? trueTypeGid((TrueTypeFont) font, code, name) : byName((TrueTypeFont) font, code, name);
        }
        if (font instanceof AwtFallbackFont) {
            final int byName = name == null ? -1 : font.gidForName(name);
            if (byName > 0) return byName;
            return symbolic ? ((AwtFallbackFont) font).unicodeLookup(code) : 0;
        }
        // Type 1 と CFF: グリフの名前で選ぶ
        int gid = font.gidForName(name == null ? ".notdef" : name);
        if (gid < 0 && font instanceof CffFont && ((CffFont) font).isCid()) gid = ((CffFont) font).gidForCid(code);
        if (gid < 0 && font instanceof TrueTypeFont) gid = byName((TrueTypeFont) font, code, name);
        if (gid < 0 && name != null) {
            // 名前がフォントにないとき: 同じ文字の別の名前 (uniXXXX など) を試す
            final String text = FontData.unicode(name, dingbats);
            if (text != null && !text.isEmpty()) {
                final String other = FontData.glyphName(text.codePointAt(0));
                if (other != null) gid = font.gidForName(other);
                if (gid < 0) gid = font.gidForName(String.format("uni%04X", text.codePointAt(0)));
            }
        }
        return Math.max(0, gid);
    }

    // TrueType の文字コードからグリフ (9.6.6.4)
    private int trueTypeGid(TrueTypeFont ttf, int code, String name) {
        int gid = 0;
        if (!symbolic) {
            if (name == null || name.equals(".notdef")) return 0;
            final String text = FontData.unicode(name, dingbats);
            if (text != null && !text.isEmpty()) gid = ttf.unicodeLookup(text.codePointAt(0));
            if (gid == 0 && ttf.hasCmap(1, 0)) {
                final int macCode = macRomanCode(name);
                if (macCode >= 0) gid = ttf.cmapLookup(1, 0, macCode);
            }
            if (gid == 0) gid = Math.max(0, ttf.gidForName(name));
            // 記号の印がないのに、記号の cmap しか持たないフォントがある
            if (gid == 0 && ttf.hasCmap(3, 0)) gid = symbolCmap(ttf, code);
            return gid;
        }
        if (ttf.hasCmap(3, 0)) gid = symbolCmap(ttf, code);
        if (gid == 0 && ttf.hasCmap(1, 0)) gid = ttf.cmapLookup(1, 0, code);
        if (gid == 0 && name != null && !name.equals(".notdef")) {
            // 記号の印が付いているのに、記号でないフォントがある
            final String text = FontData.unicode(name, dingbats);
            if (text != null && !text.isEmpty()) gid = ttf.unicodeLookup(text.codePointAt(0));
            if (gid == 0) gid = Math.max(0, ttf.gidForName(name));
        }
        // Unicode の cmap に、文字コードをそのまま入れたフォントがある
        if (gid == 0) gid = ttf.unicodeLookup(code);
        // cmap のないフォントは、コードをそのままグリフの番号とする
        if (gid == 0 && !ttf.hasAnyCmap()) gid = code < ttf.glyphCount() ? code : 0;
        return gid;
    }

    private static int symbolCmap(TrueTypeFont ttf, int code) {
        int gid = ttf.cmapLookup(3, 0, code);
        if (gid == 0) gid = ttf.cmapLookup(3, 0, 0xF000 + code);
        if (gid == 0) gid = ttf.cmapLookup(3, 0, 0xF100 + code);
        if (gid == 0) gid = ttf.cmapLookup(3, 0, 0xF200 + code);
        return gid;
    }

    // 代わりの TrueType で、名前からグリフを選ぶ (Type 1 のフォントの代わりとして使うとき)
    private int byName(TrueTypeFont ttf, int code, String name) {
        int gid = name == null ? -1 : ttf.gidForName(name);
        if (gid <= 0 && name != null) {
            final String text = FontData.unicode(name, dingbats);
            if (text != null && !text.isEmpty()) gid = ttf.unicodeLookup(text.codePointAt(0));
        }
        if (gid <= 0 && ttf.hasCmap(3, 0)) gid = symbolCmap(ttf, code);
        return Math.max(0, gid);
    }

    private static int macRomanCode(String name) {
        final String[] mac = FontData.encoding("MacOSRoman");
        if (mac == null) return -1;
        for (int i = 0; i < 256; i++) {
            if (name.equals(mac[i])) return i;
        }
        return -1;
    }

    @Override
    protected GeneralPath createPath(long character) {
        final int code = (int) character & 0xFF;
        final FontProgram font = font();
        if (font == null) return new GeneralPath();
        final int gid = gid(code);
        GeneralPath path = font.path(gid);
        // 埋め込まれていないフォントは、PDF の幅に合うように横に伸び縮みさせる (標準 14 フォントは除く)
        if (program == null && standard14 == null && hasExplicitWidth(code)) {
            final double fontWidth = font.advance(gid);
            final double pdfWidth = width(code);
            if (fontWidth > 0 && pdfWidth > 0 && Math.abs(fontWidth - pdfWidth) > 1e-4) {
                path = (GeneralPath) path.clone();
                path.transform(AffineTransform.getScaleInstance(pdfWidth / fontWidth, 1));
            }
        }
        return path;
    }
}
