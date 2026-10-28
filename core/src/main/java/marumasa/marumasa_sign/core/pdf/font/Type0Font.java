package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.Strict;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 複合フォント (Type0)。CMap で文字コードを CID にし、子の CID フォント (CIDFontType0 / CIDFontType2) でグリフを選ぶ。
 * 埋め込まれていない CJK のフォントは、CID を Unicode にして (ToUnicode か、定義済みの UCS2 の CMap)、パソコンのフォントで描く。
 */
final class Type0Font extends PdfFont {

    // /W と /W2 の範囲の数の上限
    private static final int MAX_WIDTH_RANGES = 1 << 20;

    private final CMap encoding;
    private final PdfDict cidFont;
    private final boolean type2;
    private final PdfDict descriptor;
    private final FontProgram program;
    private FontProgram substitute;
    private boolean substituteSearched;
    private final String registry;
    private final String ordering;
    // CIDToGIDMap (2 バイトずつ)。Identity なら null
    private final byte[] cidToGid;
    private CMap toUnicode;
    private boolean toUnicodeRead;
    private CMap ucs2;
    private boolean ucs2Read;
    // 幅の範囲 {最初の CID, 最後の CID, 幅...}
    private final List<double[]> widthRanges = new ArrayList<>();
    private final double defaultWidth;
    private final List<double[]> verticalRanges = new ArrayList<>();
    private final double defaultVerticalY;
    private final double defaultVerticalAdvance;
    private final Map<Long, Integer> gidCache = new HashMap<>();
    // 埋め込まれていない、Windows の標準のフォント (Arial、Times New Roman など) の CID を、そのフォントのグリフの番号とみなすときの、
    // グリフの並びの表 (STANDARD_GLYPH_ORDER か、CALIBRI_GLYPH_ORDER と STANDARD_GLYPH_ORDER)。使わなければ null
    private final int[][] glyphOrder;

    // Calibri で、STANDARD_GLYPH_ORDER と違うところ (pdf.js の SupplementalGlyphMapForCalibri)
    private static final int[] CALIBRI_GLYPH_ORDER = {
            1, 0x20, 1, 4, 0x41, 1, 5, 0xC0, 2, 9, 0xC4, 1, 17, 0x42, 2,
            21, 0x10C, 1, 24, 0x44, 1, 28, 0x45, 1, 29, 0xC8, 2, 32, 0x11A, 1,
            38, 0x46, 2, 44, 0x48, 1, 47, 0x49, 1, 48, 0xCC, 2, 58, 0x4A, 1,
            60, 0x4B, 1, 62, 0x4C, 1, 68, 0x4D, 2, 75, 0x4F, 1, 76, 0xD2, 1,
            80, 0xD6, 1, 87, 0x50, 1, 89, 0x51, 2, 92, 0x158, 1, 94, 0x53, 1,
            97, 0x160, 1, 100, 0x54, 1, 104, 0x55, 1, 109, 0xDC, 1, 115, 0x56, 2,
            121, 0x58, 2, 124, 0xDD, 1, 127, 0x5A, 1, 129, 0x17D, 1, 258, 0x61, 1,
            259, 0xE0, 2, 263, 0xE4, 1, 268, 0x105, 1, 271, 0x62, 2, 273, 0x107, 1,
            275, 0x10D, 1, 282, 0x64, 1, 286, 0x65, 1, 287, 0xE8, 2, 290, 0x11B, 1,
            295, 0x119, 1, 296, 0x66, 1, 336, 0x67, 1, 346, 0x68, 1, 349, 0x69, 1,
            350, 0xEC, 2, 361, 0x6A, 1, 364, 0x6B, 1, 367, 0x6C, 1, 371, 0x142, 1,
            373, 0x6D, 2, 381, 0x6F, 1, 382, 0xF2, 2, 386, 0xF6, 1, 393, 0x70, 1,
            395, 0x71, 2, 398, 0x159, 1, 400, 0x73, 1, 401, 0x15B, 1, 403, 0x161, 1,
            410, 0x74, 1, 437, 0x75, 1, 442, 0xFC, 1, 448, 0x76, 2, 454, 0x78, 2,
            457, 0xFD, 1, 460, 0x7A, 1, 462, 0x17E, 1, 463, 0x17C, 1, 853, 0x2C, 1,
            855, 0x3A, 1, 856, 0x2E, 1, 876, 0x2F, 1, 878, 0x2D, 1, 882, 0x2D, 1,
            894, 0x28, 2, 896, 0x5B, 1, 897, 0x5D, 1, 923, 0x40, 1, 940, 0xA3, 1,
            1004, 0x30, 10, 1081, 0x25, 1, 1085, 0x2B, 1, 1086, 0x2D, 1
    };

    /**
     * Windows の標準のフォント (Arial、Times New Roman、Courier New、Verdana など) のグリフの並び:
     * {最初の GID, 最初の Unicode, 数} の繰り返し (pdf.js の GlyphMapForStandardFonts の、ラテン、ギリシャ、キリル、ヘブライ文字の分)。
     * 埋め込まずに Identity-H で書いた CID は、元のフォントのグリフの番号なので、これで文字に戻す
     */
    private static final int[] STANDARD_GLYPH_ORDER = {
            2, 0xA, 1, 3, 0x20, 95, 98, 0xC4, 2,
            100, 0xC7, 1, 101, 0xC9, 1, 102, 0xD1, 1, 103, 0xD6, 1, 104, 0xDC, 1,
            105, 0xE1, 1, 106, 0xE0, 1, 107, 0xE2, 1, 108, 0xE4, 1, 109, 0xE3, 1,
            110, 0xE5, 1, 111, 0xE7, 1, 112, 0xE9, 1, 113, 0xE8, 1, 114, 0xEA, 2,
            116, 0xED, 1, 117, 0xEC, 1, 118, 0xEE, 2, 120, 0xF1, 1, 121, 0xF3, 1,
            122, 0xF2, 1, 123, 0xF4, 1, 124, 0xF6, 1, 125, 0xF5, 1, 126, 0xFA, 1,
            127, 0xF9, 1, 128, 0xFB, 2, 130, 0x2020, 1, 131, 0xB0, 1, 132, 0xA2, 2,
            134, 0xA7, 1, 135, 0x2022, 1, 136, 0xB6, 1, 137, 0xDF, 1, 138, 0xAE, 1,
            139, 0xA9, 1, 140, 0x2122, 1, 141, 0xB4, 1, 142, 0xA8, 1, 143, 0x2260, 1,
            144, 0xC6, 1, 145, 0xD8, 1, 146, 0x221E, 1, 147, 0xB1, 1, 148, 0x2264, 2,
            150, 0xA5, 1, 151, 0xB5, 1, 152, 0x2202, 1, 153, 0x2211, 1, 154, 0x220F, 1,
            156, 0x222B, 1, 157, 0xAA, 1, 158, 0xBA, 1, 159, 0x2126, 1, 160, 0xE6, 1,
            161, 0xF8, 1, 162, 0xBF, 1, 163, 0xA1, 1, 164, 0xAC, 1, 165, 0x221A, 1,
            166, 0x192, 1, 167, 0x2248, 1, 168, 0x2206, 1, 169, 0xAB, 1, 170, 0xBB, 1,
            171, 0x2026, 1, 179, 0x201C, 2, 181, 0x2018, 2, 200, 0xC1, 1, 203, 0xCD, 1,
            207, 0xD3, 1, 210, 0xDA, 1, 223, 0x2C7, 1, 224, 0x141, 2, 226, 0x160, 2,
            228, 0x17D, 2, 233, 0xDD, 1, 234, 0xFD, 1, 252, 0x107, 1, 253, 0x10C, 2,
            258, 0x102, 1, 260, 0x104, 2, 265, 0x118, 4, 269, 0x139, 1, 275, 0x143, 2,
            278, 0x148, 1, 283, 0x158, 4, 292, 0x16F, 1, 295, 0x179, 2, 298, 0x17C, 1,
            305, 0x3C3, 2, 307, 0x3C6, 1, 308, 0x2017, 1, 309, 0x203C, 1, 310, 0x207F, 1,
            311, 0x20A7, 1, 312, 0x2190, 2, 337, 0x2550, 1, 493, 0x40F, 2, 570, 0x410, 64,
            672, 0x5D0, 27, 705, 0x5F4, 1, 706, 0x20AA, 1
    };

    Type0Font(PdfDict dict) throws IOException {
        super(dict);
        final PdfArray descendants = dict.array("DescendantFonts");
        cidFont = descendants == null ? dict.dict("DescendantFonts") : descendants.dict(0);
        if (cidFont == null) throw new IOException("Type0 font without a CID font");
        type2 = "CIDFontType2".equals(cidFont.name("Subtype"));
        descriptor = cidFont.dict("FontDescriptor");
        final Object encodingValue = dict.get("Encoding");
        CMap cmap = null;
        if (encodingValue instanceof PdfName) {
            cmap = CMaps.predefined(((PdfName) encodingValue).name);
        } else if (encodingValue instanceof PdfStream) {
            final PdfStream stream = (PdfStream) encodingValue;
            cmap = CMaps.parse(stream.decode(4 * 1024 * 1024), false);
            final String parent = stream.dict.name("UseCMap");
            if (parent != null && !cmap.hasCodespace()) cmap.setParent(CMaps.predefined(parent));
            if (stream.dict.integer("WMode", -1) == 1) cmap.setWmode(1);
        }
        encoding = cmap != null ? cmap : CMaps.identity(false);
        final PdfDict info = cidFont.dict("CIDSystemInfo");
        registry = info == null ? null : text(info.get("Registry"));
        ordering = info == null ? null : text(info.get("Ordering"));
        program = embedded(descriptor, true);
        final Object map = cidFont.get("CIDToGIDMap");
        byte[] table = null;
        if (map instanceof PdfStream) {
            try {
                table = ((PdfStream) map).decode(2 * 65536 * 4);
            } catch (IOException e) {
                table = null;
            }
        }
        cidToGid = table;
        defaultWidth = cidFont.number("DW", 1000);
        readWidths(cidFont.array("W"), widthRanges, 1);
        final PdfArray dw2 = cidFont.array("DW2");
        defaultVerticalY = dw2 != null && dw2.size() >= 2 ? dw2.number(0, 880) : 880;
        defaultVerticalAdvance = dw2 != null && dw2.size() >= 2 ? dw2.number(1, -1000) : -1000;
        readWidths(cidFont.array("W2"), verticalRanges, 3);
        final String baseName = FontData.stripSubset(String.valueOf(dict.name("BaseFont")));
        glyphOrder = program == null && type2 && encodingValue instanceof PdfName
                && ((PdfName) encodingValue).name.startsWith("Identity-") && cidToGid == null
                && hasStandardGlyphOrder(baseName, dict.get("ToUnicode") == null)
                ? baseName.replace(" ", "").startsWith("Calibri") ? new int[][]{CALIBRI_GLYPH_ORDER, STANDARD_GLYPH_ORDER} : new int[][]{STANDARD_GLYPH_ORDER}
                : null;
    }

    // Windows の標準のフォントと同じグリフの並びのフォントか (pdf.js と同じ選び方)。
    // Tahoma と Verdana は、ToUnicode がないときだけ (ほかの並びのこともあるため)
    private static boolean hasStandardGlyphOrder(String fontName, boolean noToUnicode) {
        // 名前に空白を入れたもの (Times New Roman) もある
        final String name = fontName.replace(" ", "");
        final String standard = FontData.standard14Name(name);
        if (standard != null) return !standard.equals("Symbol") && !standard.equals("ZapfDingbats");
        final String family = name.replace(',', '-');
        for (String prefix : new String[]{"Arial", "TimesNewRoman", "CourierNew", "Calibri", "CenturyGothic", "GillSansMT", "Impact",
                "LucidaConsole"}) {
            if (family.startsWith(prefix)) return true;
        }
        return noToUnicode && (family.startsWith("Tahoma") || family.startsWith("Verdana"));
    }

    // グリフの並びの表で、グリフの番号の文字。なければ -1
    private static int glyphUnicode(int[][] tables, int gid) {
        for (int[] runs : tables) {
            for (int i = 0; i + 2 < runs.length; i += 3) {
                if (gid >= runs[i] && gid < runs[i] + runs[i + 2]) return runs[i + 1] + gid - runs[i];
            }
        }
        return -1;
    }

    private static String text(Object value) {
        if (value instanceof marumasa.marumasa_sign.core.pdf.cos.PdfString) {
            return ((marumasa.marumasa_sign.core.pdf.cos.PdfString) value).text();
        }
        return value instanceof PdfName ? ((PdfName) value).name : null;
    }

    /**
     * /W ([c [w1 w2 ...]] か [c1 c2 w]) と /W2 (値が 3 つずつ) を読む。
     */
    private static void readWidths(PdfArray array, List<double[]> ranges, int stride) {
        if (array == null) return;
        int i = 0;
        while (i < array.size() && ranges.size() < MAX_WIDTH_RANGES) {
            final Object first = array.get(i);
            if (!(first instanceof Number)) break;
            final int start = ((Number) first).intValue();
            final Object next = array.get(i + 1);
            if (next instanceof PdfArray) {
                final PdfArray list = (PdfArray) next;
                final int count = list.size() / stride;
                final double[] range = new double[2 + count * stride];
                range[0] = start;
                range[1] = start + count - 1;
                for (int k = 0; k < count * stride; k++) range[2 + k] = list.number(k, 0);
                if (count > 0) ranges.add(range);
                i += 2;
            } else if (next instanceof Number) {
                final int end = ((Number) next).intValue();
                final double[] range = new double[2 + stride];
                range[0] = start;
                range[1] = end;
                for (int k = 0; k < stride; k++) range[2 + k] = array.number(i + 2 + k, 0);
                ranges.add(range);
                i += 2 + stride;
            } else {
                break;
            }
        }
    }

    // CID の値 (offset 番目)。範囲になければ null
    private static double[] find(List<double[]> ranges, int cid, int stride) {
        // 後に書かれたものを優先する
        for (int r = ranges.size() - 1; r >= 0; r--) {
            final double[] range = ranges.get(r);
            if (cid < range[0] || cid > range[1]) continue;
            final int count = (range.length - 2) / stride;
            final int index = count == 1 ? 0 : (int) (cid - range[0]);
            if (index >= count) continue;
            final double[] value = new double[stride];
            System.arraycopy(range, 2 + index * stride, value, 0, stride);
            return value;
        }
        return null;
    }

    // 文字の番号: 文字コード (4 バイトまで) とバイト数
    private static long character(int code, int length) {
        return ((code & 0xFFFFFFFFL) << 3) | length;
    }

    private static int code(long character) {
        return (int) (character >>> 3);
    }

    private static int length(long character) {
        return (int) (character & 7);
    }

    @Override
    public long readCode(byte[] bytes, int position, int[] length) {
        final int[] result = new int[2];
        encoding.readCode(bytes, position, result);
        length[0] = result[1];
        return character(result[0], result[1]);
    }

    @Override
    public boolean isWordSpace(long character) {
        return code(character) == 32 && length(character) == 1;
    }

    private int cid(long character) {
        final int cid = encoding.lookup(code(character), length(character));
        return cid < 0 ? 0 : cid;
    }

    @Override
    public double width(long character) {
        final double[] w = find(widthRanges, cid(character), 1);
        return (w != null ? w[0] : defaultWidth) / 1000;
    }

    @Override
    public boolean isVertical() {
        return encoding.isVertical();
    }

    @Override
    public double verticalAdvance(long character) {
        final double[] v = find(verticalRanges, cid(character), 3);
        return (v != null ? v[0] : defaultVerticalAdvance) / 1000;
    }

    @Override
    public double[] verticalOrigin(long character) {
        final int cid = cid(character);
        final double[] v = find(verticalRanges, cid, 3);
        if (v != null) return new double[]{v[1] / 1000, v[2] / 1000};
        final double[] w = find(widthRanges, cid, 1);
        return new double[]{(w != null ? w[0] : defaultWidth) / 2000, defaultVerticalY / 1000};
    }

    // ---------- グリフ ----------

    private FontProgram font() {
        if (program != null) return program;
        if (!substituteSearched) {
            substituteSearched = true;
            final SystemFonts system = SystemFonts.get();
            final String baseFont = FontData.stripSubset(String.valueOf(dict.name("BaseFont")));
            // 子のフォントの名前は「名前-CMap の名前」のことがある
            String name = cidFont.name("BaseFont");
            name = name == null ? baseFont : FontData.stripSubset(name);
            byte[] panose = null;
            final PdfDict style = descriptor == null ? null : descriptor.dict("Style");
            final byte[] panoseBytes = style == null ? null : style.string("Panose");
            if (panoseBytes != null && panoseBytes.length >= 12) panose = java.util.Arrays.copyOfRange(panoseBytes, 2, 12);
            final int weight = descriptor == null ? 0 : descriptor.integer("FontWeight", 0);
            final String family = descriptor == null ? null : text(descriptor.get("FontFamily"));
            FontProgram font = system.findCid(name, weight, panose, family, registry, ordering);
            if (font == null) font = system.findByName(name, true);
            if (font == null) {
                // 欧文のフォントとして、性質 (固定幅、セリフ、太字、斜体) の近い標準のフォントの代わりを探す
                final int flags = flags(descriptor);
                font = system.findByName(SystemFonts.fallbackName(name, descriptor != null, isFixedPitch(flags), isSerif(flags), isItalic(flags)), true);
            }
            if (font == null) font = system.lastResort();
            if (font == null) {
                final boolean serif = descriptor != null && isSerif(flags(descriptor));
                font = new AwtFallbackFont(serif ? "Serif" : "SansSerif", 0);
            }
            substitute = font;
        }
        return substitute;
    }

    private synchronized int gid(long character) {
        final Integer cached = gidCache.get(character);
        if (cached != null) return cached;
        int gid;
        try {
            gid = computeGid(character);
        } catch (RuntimeException e) {
            Strict.check(e);
            gid = 0;
        }
        if (gidCache.size() < 65536) gidCache.put(character, gid);
        return gid;
    }

    private int computeGid(long character) {
        final int cid = cid(character);
        final FontProgram font = font();
        if (font == null) return 0;
        if (program != null) {
            final CffFont cff = program instanceof CffFont ? (CffFont) program
                    : program instanceof TrueTypeFont && ((TrueTypeFont) program).isCff() ? ((TrueTypeFont) program).cff() : null;
            // CID の決まった CFF は、文字集合で CID からグリフを選ぶ。
            // CID の決まっていない、そのままの CFF は、CID がそのまま GID (CIDToGIDMap は使わない。poppler と pdf.js と同じ)
            if (cff != null && (cff.isCid() || program instanceof CffFont)) return cff.gidForCid(cid);
            // TrueType と、OpenType に入った CID の決まっていない CFF: CIDToGIDMap (なければ CID がそのまま GID)。
            // 仕様では TrueType のためのものだが、OpenType の CFF にも付けたものがある (poppler も使う)
            if (cidToGid != null) {
                final int p = 2 * cid;
                return p + 1 < cidToGid.length ? ((cidToGid[p] & 0xFF) << 8) | (cidToGid[p + 1] & 0xFF) : 0;
            }
            return cff != null ? cff.gidForCid(cid) : cid;
        }
        // 代わりのフォントが同じ文字の集まりの CID フォントなら、CID でそのまま選べる
        if (font instanceof TrueTypeFont && ((TrueTypeFont) font).isCff() && ((TrueTypeFont) font).cff().isCid()) {
            final int gid = ((TrueTypeFont) font).cff().gidForCid(cid);
            if (gid > 0) return gid;
        }
        if (font instanceof CffFont && ((CffFont) font).isCid()) {
            final int gid = ((CffFont) font).gidForCid(cid);
            if (gid > 0) return gid;
        }
        if (glyphOrder != null && font instanceof UnicodeFont) {
            final int standard = glyphUnicode(glyphOrder, cid);
            final int gid = standard > 0 ? ((UnicodeFont) font).unicodeLookup(standard) : 0;
            if (gid > 0) return gid;
        }
        final int unicode = unicode(code(character), length(character), cid);
        if (unicode > 0 && font instanceof UnicodeFont) return ((UnicodeFont) font).unicodeLookup(unicode);
        return 0;
    }

    // 文字の Unicode: ToUnicode を優先し、なければ文字の集まりの UCS2 の表
    private int unicode(int code, int length, int cid) {
        if (!toUnicodeRead) {
            toUnicodeRead = true;
            final PdfStream stream = dict.stream("ToUnicode");
            if (stream != null) {
                try {
                    toUnicode = CMaps.parse(stream.decode(4 * 1024 * 1024), true);
                } catch (IOException | RuntimeException e) {
                    Strict.check(e);
                    toUnicode = null;
                }
            }
        }
        if (toUnicode != null) {
            final int value = toUnicode.lookup(code, length);
            if (value > 0) return value;
        }
        if (!ucs2Read) {
            ucs2Read = true;
            if (registry != null && ordering != null) ucs2 = CMaps.predefined(registry + "-" + ordering + "-UCS2");
        }
        if (ucs2 != null) {
            final int value = ucs2.lookup(cid, 2);
            if (value > 0) return value;
        }
        return -1;
    }

    @Override
    protected GeneralPath createPath(long character) {
        final FontProgram font = font();
        if (font == null) return new GeneralPath();
        final int gid = gid(character);
        // 代わりのフォントにない文字は、.notdef のグリフ (四角など) を描かずに、何も描かない
        if (gid == 0 && program == null) return new GeneralPath();
        return font.path(gid);
    }
}
