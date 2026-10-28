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
            if (program instanceof CffFont) return ((CffFont) program).gidForCid(cid);
            if (program instanceof TrueTypeFont && ((TrueTypeFont) program).isCff()) return ((TrueTypeFont) program).cff().gidForCid(cid);
            // TrueType: CIDToGIDMap (なければ CID がそのまま GID)
            if (cidToGid != null) {
                final int p = 2 * cid;
                return p + 1 < cidToGid.length ? ((cidToGid[p] & 0xFF) << 8) | (cidToGid[p + 1] & 0xFF) : 0;
            }
            return cid;
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
        return font.path(gid(character));
    }
}
