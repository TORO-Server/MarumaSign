package marumasa.marumasa_sign.core.pdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.HashMap;
import java.util.Map;

/**
 * TrueType と OpenType のフォント。輪郭は glyf 表 (2 次ベジェ) か、CFF 表 (OpenType の CFF 版) から作る。
 * <p>
 * どの表も、位置と長さがデータの中に収まるかを確かめてから読む。複合グリフ (ほかのグリフを組み合わせたもの) は
 * 入れ子の深さと点の数に上限を設け、自分自身を含むグリフで止まらないようにする
 * (PDFBox 2.0 は複合グリフの輪を検出せず、StackOverflowError になる)。
 */
public final class TrueTypeFont implements UnicodeFont {

    // 複合グリフの入れ子の深さの上限
    private static final int MAX_COMPOSITE_DEPTH = 8;
    // 1 つのグリフの点の数の上限
    private static final int MAX_POINTS = 65536;
    // TrueType の集合 (.ttc) の中で調べるフォントの数の上限
    private static final long MAX_COLLECTION_FONTS = 64;

    private static final String[] MAC_GLYPH_NAMES = (".notdef .null nonmarkingreturn space exclam quotedbl numbersign dollar percent "
            + "ampersand quotesingle parenleft parenright asterisk plus comma hyphen period slash zero one two three four five six "
            + "seven eight nine colon semicolon less equal greater question at A B C D E F G H I J K L M N O P Q R S T U V W X Y Z "
            + "bracketleft backslash bracketright asciicircum underscore grave a b c d e f g h i j k l m n o p q r s t u v w x y z "
            + "braceleft bar braceright asciitilde Adieresis Aring Ccedilla Eacute Ntilde Odieresis Udieresis aacute agrave "
            + "acircumflex adieresis atilde aring ccedilla eacute egrave ecircumflex edieresis iacute igrave icircumflex idieresis "
            + "ntilde oacute ograve ocircumflex odieresis otilde uacute ugrave ucircumflex udieresis dagger degree cent sterling "
            + "section bullet paragraph germandbls registered copyright trademark acute dieresis notequal AE Oslash infinity "
            + "plusminus lessequal greaterequal yen mu partialdiff summation product pi integral ordfeminine ordmasculine Omega ae "
            + "oslash questiondown exclamdown logicalnot radical florin approxequal Delta guillemotleft guillemotright ellipsis "
            + "nonbreakingspace Agrave Atilde Otilde OE oe endash emdash quotedblleft quotedblright quoteleft quoteright divide "
            + "lozenge ydieresis Ydieresis fraction currency guilsinglleft guilsinglright fi fl daggerdbl periodcentered "
            + "quotesinglbase quotedblbase perthousand Acircumflex Ecircumflex Aacute Edieresis Egrave Iacute Icircumflex Idieresis "
            + "Igrave Oacute Ocircumflex apple Ograve Uacute Ucircumflex Ugrave dotlessi circumflex tilde macron breve dotaccent "
            + "ring cedilla hungarumlaut ogonek caron Lslash lslash Scaron scaron Zcaron zcaron brokenbar Eth eth Yacute yacute "
            + "Thorn thorn minus multiply onesuperior twosuperior threesuperior onehalf onequarter threequarters franc Gbreve gbreve "
            + "Idotaccent Scedilla scedilla Cacute cacute Ccaron ccaron dcroat").split(" ");

    private final FontBytes data;
    private final Map<String, FontBytes> tables = new HashMap<>();
    private final int unitsPerEm;
    private final int glyphCount;
    private final boolean longLoca;
    private final int metricsCount;
    // OpenType の CFF 版なら、その CFF
    private final CffFont cff;
    private final String postScriptName;
    // post 表のグリフの名前 (なければ null)
    private String[] glyphNames;
    private Map<String, Integer> nameToGid;
    // 使える cmap の小表。{platform, encoding} -> 表の位置
    private final Map<Long, FontBytes> cmaps = new HashMap<>();

    /**
     * フォントを読む。
     *
     * @param data   ファイル全体
     * @param offset フォントの始まり (TrueType の集合 .ttc の中のフォントなら、その位置)
     */
    public TrueTypeFont(FontBytes data, long offset) {
        this.data = data;
        final long version = data.u32(offset);
        if (version == 0x74746366L) throw new FontBytes.FontFormatError("Font collection"); // "ttcf"
        final int tableCount = data.u16(offset + 4);
        if (tableCount > 1024) throw new FontBytes.FontFormatError("Too many tables");
        for (int i = 0; i < tableCount; i++) {
            final long record = offset + 12 + 16L * i;
            final String tag = data.ascii(record, 4);
            final long tableOffset = data.u32(record + 8);
            final long tableLength = data.u32(record + 12);
            if (tableOffset >= data.length()) continue;
            tables.put(tag, data.slice(tableOffset, tableLength));
        }
        final FontBytes head = tables.get("head");
        final int units = head != null && head.length() >= 54 ? head.u16(18) : 1000;
        // 単位の大きさのおかしいフォントは、壊れている (表の位置がずれているなど)。FreeType と同じく読まない
        if (units < 16 || units > 16384) throw new FontBytes.FontFormatError("Bad unitsPerEm");
        unitsPerEm = units;
        longLoca = head != null && head.length() >= 54 && head.s16(50) != 0;
        final FontBytes maxp = tables.get("maxp");
        int count = maxp != null && maxp.length() >= 6 ? maxp.u16(4) : 0;
        final FontBytes cffTable = tables.get("CFF ");
        CffFont parsed = null;
        if (cffTable != null) {
            parsed = new CffFont(cffTable, null);
            if (count == 0) count = parsed.glyphCount();
        } else {
            // loca の項目の数を超えるグリフは読めない
            final FontBytes loca = tables.get("loca");
            final int locaEntries = loca == null ? 0 : loca.length() / (longLoca ? 4 : 2);
            count = Math.min(count, Math.max(0, locaEntries - 1));
        }
        cff = parsed;
        glyphCount = count;
        final FontBytes hhea = tables.get("hhea");
        final FontBytes hmtx = tables.get("hmtx");
        int metrics = hhea != null && hhea.length() >= 36 ? hhea.u16(34) : 0;
        if (hmtx != null) metrics = Math.min(metrics, hmtx.length() / 4);
        metricsCount = metrics;
        postScriptName = readName();
        readCmaps();
    }

    public static TrueTypeFont parse(byte[] bytes) {
        return new TrueTypeFont(new FontBytes(bytes), 0);
    }

    /**
     * フォントを読む。TrueType の集合 (.ttc) なら、PostScript 名が name のフォント (なければ最初に読めたもの) を選ぶ。
     *
     * @param name 欲しいフォントの PostScript 名 (なければ null)
     */
    public static TrueTypeFont parse(byte[] bytes, String name) {
        final FontBytes data = new FontBytes(bytes);
        if (data.length() < 12 || data.u32(0) != 0x74746366L) return new TrueTypeFont(data, 0); // "ttcf"
        final int count = (int) Math.min(data.u32(8), MAX_COLLECTION_FONTS);
        TrueTypeFont first = null;
        for (int i = 0; i < count; i++) {
            final TrueTypeFont font;
            try {
                font = new TrueTypeFont(data, data.u32(12 + 4L * i));
            } catch (FontBytes.FontFormatError | ArrayIndexOutOfBoundsException e) {
                continue;
            }
            if (name != null && name.equals(font.postScriptName())) return font;
            if (first == null) first = font;
        }
        if (first == null) throw new FontBytes.FontFormatError("No font in the collection");
        return first;
    }

    public boolean hasTable(String tag) {
        return tables.containsKey(tag);
    }

    FontBytes table(String tag) {
        return tables.get(tag);
    }

    @Override
    public int glyphCount() {
        return glyphCount;
    }

    public int unitsPerEm() {
        return unitsPerEm;
    }

    @Override
    public String postScriptName() {
        return postScriptName;
    }

    public boolean isCff() {
        return cff != null;
    }

    public CffFont cff() {
        return cff;
    }

    @Override
    public double advance(int gid) {
        final FontBytes hmtx = tables.get("hmtx");
        if (hmtx == null || metricsCount == 0 || gid < 0) return cff != null ? cff.advance(gid) : 0;
        final int index = Math.min(gid, metricsCount - 1);
        try {
            return hmtx.u16(4L * index) / (double) unitsPerEm;
        } catch (FontBytes.FontFormatError e) {
            return 0;
        }
    }

    // ---------- 名前 ----------

    private String readName() {
        final FontBytes name = tables.get("name");
        if (name == null) return null;
        try {
            final int count = name.u16(2);
            final int stringOffset = name.u16(4);
            String windows = null;
            for (int i = 0; i < count && i < 4096; i++) {
                final long record = 6 + 12L * i;
                final int platform = name.u16(record), encoding = name.u16(record + 2), language = name.u16(record + 4);
                final int nameId = name.u16(record + 6), length = name.u16(record + 8), offset = name.u16(record + 10);
                if (nameId != 6) continue;
                if (platform == 1 && encoding == 0 && language == 0) {
                    return name.ascii(stringOffset + offset, length).trim();
                }
                if (platform == 3 && (encoding == 1 || encoding == 0) && windows == null) {
                    windows = new String(name.bytes(stringOffset + offset, length), java.nio.charset.StandardCharsets.UTF_16BE).trim();
                }
            }
            return windows;
        } catch (FontBytes.FontFormatError e) {
            return null;
        }
    }

    private void readPostNames() {
        if (glyphNames != null) return;
        glyphNames = new String[0];
        nameToGid = new HashMap<>();
        final FontBytes post = tables.get("post");
        if (post == null || post.length() < 32) return;
        try {
            final long format = post.u32(0);
            if (format == 0x00010000L) {
                final int n = Math.min(glyphCount, MAC_GLYPH_NAMES.length);
                glyphNames = new String[n];
                System.arraycopy(MAC_GLYPH_NAMES, 0, glyphNames, 0, n);
            } else if (format == 0x00020000L) {
                final int n = Math.min(post.u16(32), glyphCount);
                final int[] indices = new int[n];
                int maxIndex = 0;
                for (int i = 0; i < n; i++) {
                    indices[i] = post.u16(34 + 2L * i);
                    maxIndex = Math.max(maxIndex, indices[i]);
                }
                // 258 番からは、表の後ろに並んだ文字列 (長さ 1 バイト + 中身)
                final int extra = Math.max(0, maxIndex - 257);
                final String[] strings = new String[Math.min(extra, 65536)];
                long p = 34 + 2L * post.u16(32);
                for (int i = 0; i < strings.length && p < post.length(); i++) {
                    final int length = post.u8(p);
                    strings[i] = post.ascii(p + 1, Math.min(length, (int) (post.length() - p - 1)));
                    p += 1 + length;
                }
                glyphNames = new String[n];
                for (int i = 0; i < n; i++) {
                    final int index = indices[i];
                    glyphNames[i] = index < 258 ? MAC_GLYPH_NAMES[index]
                            : index - 258 < strings.length ? strings[index - 258] : null;
                }
            }
        } catch (FontBytes.FontFormatError e) {
            // 名前が読めなくても、ほかの方法でグリフを選べる
        }
        for (int i = glyphNames.length - 1; i >= 0; i--) {
            if (glyphNames[i] != null) nameToGid.put(glyphNames[i], i);
        }
    }

    @Override
    public int gidForName(String name) {
        if (name == null) return -1;
        if (cff != null && cff.glyphCount() > 0) {
            final int gid = cff.gidForName(name);
            if (gid >= 0) return gid;
        }
        readPostNames();
        final Integer gid = nameToGid.get(name);
        return gid == null ? -1 : gid;
    }

    @Override
    public String nameForGid(int gid) {
        if (cff != null) {
            final String name = cff.nameForGid(gid);
            if (name != null) return name;
        }
        readPostNames();
        return gid >= 0 && gid < glyphNames.length ? glyphNames[gid] : null;
    }

    // ---------- cmap ----------

    private void readCmaps() {
        final FontBytes cmap = tables.get("cmap");
        if (cmap == null) return;
        try {
            final int count = cmap.u16(2);
            for (int i = 0; i < count && i < 256; i++) {
                final int platform = cmap.u16(4 + 8L * i);
                final int encoding = cmap.u16(6 + 8L * i);
                final long offset = cmap.u32(8 + 8L * i);
                if (offset >= cmap.length()) continue;
                final long key = ((long) platform << 32) | encoding;
                if (!cmaps.containsKey(key)) cmaps.put(key, cmap.slice(offset, cmap.length() - offset));
            }
        } catch (FontBytes.FontFormatError e) {
            // 読めた分だけ使う
        }
    }

    public boolean hasCmap(int platform, int encoding) {
        return cmaps.containsKey(((long) platform << 32) | encoding);
    }

    public boolean hasAnyCmap() {
        return !cmaps.isEmpty();
    }

    /**
     * cmap の小表 (platform, encoding) で、文字コードのグリフを探す。なければ 0。
     */
    public int cmapLookup(int platform, int encoding, int code) {
        final FontBytes table = cmaps.get(((long) platform << 32) | encoding);
        if (table == null) return 0;
        try {
            final int gid = lookup(table, code);
            return gid >= 0 && gid < Math.max(glyphCount, 1) ? gid : 0;
        } catch (FontBytes.FontFormatError e) {
            return 0;
        }
    }

    // Unicode のグリフ: (3,10)、(3,1)、(0,*) の順に探す
    @Override
    public int unicodeLookup(int codePoint) {
        int gid = 0;
        if (codePoint > 0xFFFF || hasCmap(3, 10)) gid = cmapLookup(3, 10, codePoint);
        if (gid == 0) gid = cmapLookup(3, 1, codePoint);
        for (int e = 0; gid == 0 && e <= 6; e++) gid = cmapLookup(0, e, codePoint);
        return gid;
    }

    private static int lookup(FontBytes t, int code) {
        final int format = t.u16(0);
        switch (format) {
            case 0:
                return code >= 0 && code < 256 ? t.u8(6 + code) : 0;
            case 2: {
                // 上位バイトで小表を選ぶ (古い CJK のフォント)
                final int high = (code >> 8) & 0xFF;
                final int low = code & 0xFF;
                final int key = code > 0xFF ? t.u16(6 + 2L * high) / 8 : 0;
                final long subHeader = 6 + 512 + 8L * key;
                final int firstCode = t.u16(subHeader);
                final int entryCount = t.u16(subHeader + 2);
                final int idDelta = t.s16(subHeader + 4);
                final int idRangeOffset = t.u16(subHeader + 6);
                final int byteCode = code > 0xFF ? low : code & 0xFF;
                if (byteCode < firstCode || byteCode >= firstCode + entryCount) return 0;
                final long glyphIndexAddress = subHeader + 6 + idRangeOffset + 2L * (byteCode - firstCode);
                final int gid = t.u16(glyphIndexAddress);
                return gid == 0 ? 0 : (gid + idDelta) & 0xFFFF;
            }
            case 4: {
                if (code < 0 || code > 0xFFFF) return 0;
                final int segments = t.u16(6) / 2;
                final long endCodes = 14;
                final long startCodes = endCodes + 2L * segments + 2;
                final long deltas = startCodes + 2L * segments;
                final long rangeOffsets = deltas + 2L * segments;
                // 終わりのコードは昇順なので二分探索する
                int lo = 0, hi = segments - 1, found = -1;
                while (lo <= hi) {
                    final int mid = (lo + hi) >>> 1;
                    if (t.u16(endCodes + 2L * mid) < code) {
                        lo = mid + 1;
                    } else {
                        found = mid;
                        hi = mid - 1;
                    }
                }
                if (found < 0) return 0;
                final int start = t.u16(startCodes + 2L * found);
                if (code < start) return 0;
                final int delta = t.u16(deltas + 2L * found);
                final int rangeOffset = t.u16(rangeOffsets + 2L * found);
                if (rangeOffset == 0) return (code + delta) & 0xFFFF;
                final long address = rangeOffsets + 2L * found + rangeOffset + 2L * (code - start);
                final int gid = t.u16(address);
                return gid == 0 ? 0 : (gid + delta) & 0xFFFF;
            }
            case 6: {
                final int first = t.u16(6);
                final int count = t.u16(8);
                return code >= first && code < first + count ? t.u16(10 + 2L * (code - first)) : 0;
            }
            case 10: {
                final long first = t.u32(12);
                final long count = t.u32(16);
                return code >= first && code < first + count ? t.u16(20 + 2 * (code - first)) : 0;
            }
            case 12:
            case 13: {
                final long groups = Math.min(t.u32(12), (t.length() - 16) / 12);
                long lo = 0, hi = groups - 1;
                while (lo <= hi) {
                    final long mid = (lo + hi) >>> 1;
                    final long base = 16 + 12 * mid;
                    final long start = t.u32(base), end = t.u32(base + 4);
                    if (code < start) {
                        hi = mid - 1;
                    } else if (code > end) {
                        lo = mid + 1;
                    } else {
                        final long glyph = t.u32(base + 8);
                        final long gid = format == 12 ? glyph + (code - start) : glyph;
                        return gid > Integer.MAX_VALUE ? 0 : (int) gid;
                    }
                }
                return 0;
            }
            default:
                return 0;
        }
    }

    // ---------- 輪郭 ----------

    // 読んだグリフの点 (複合グリフを組み立てるのに使う)
    private static final class Points {
        double[] x = new double[64];
        double[] y = new double[64];
        boolean[] on = new boolean[64];
        int[] ends = new int[8];
        int count;
        int contours;

        void add(double px, double py, boolean onCurve) {
            if (count >= MAX_POINTS) throw new FontBytes.FontFormatError("Too many points");
            if (count == x.length) {
                x = java.util.Arrays.copyOf(x, count * 2);
                y = java.util.Arrays.copyOf(y, count * 2);
                on = java.util.Arrays.copyOf(on, count * 2);
            }
            x[count] = px;
            y[count] = py;
            on[count++] = onCurve;
        }

        void endContour() {
            if (contours == ends.length) ends = java.util.Arrays.copyOf(ends, contours * 2);
            ends[contours++] = count - 1;
        }
    }

    @Override
    public GeneralPath path(int gid) {
        if (cff != null) return cff.path(gid);
        final GeneralPath path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
        if (gid < 0 || gid >= glyphCount) return path;
        try {
            final Points points = new Points();
            readGlyph(gid, points, 0);
            final double scale = 1.0 / unitsPerEm;
            int start = 0;
            for (int c = 0; c < points.contours; c++) {
                final int end = points.ends[c];
                if (end >= start) contour(path, points, start, end, scale);
                start = end + 1;
            }
            // 原点は、グリフの左端 (xMin) から hmtx の左の余白 (lsb) だけ左 (TrueType の決まり。FreeType などと同じ)。
            // ふつうは xMin と lsb が同じなので、ずらさない
            final int shift = leftSideBearing(gid) - xMin(gid);
            if (shift != 0) path.transform(AffineTransform.getTranslateInstance(shift * scale, 0));
        } catch (FontBytes.FontFormatError | ArrayIndexOutOfBoundsException e) {
            // 壊れたグリフは、読めたところまで (または空) にする
        }
        return path;
    }

    // hmtx の左の余白 (フォントの単位)。なければ、グリフの xMin (ずらさない)
    private int leftSideBearing(int gid) {
        final FontBytes hmtx = tables.get("hmtx");
        if (hmtx == null || metricsCount == 0) return xMin(gid);
        final long offset = gid < metricsCount ? 4L * gid + 2 : 4L * metricsCount + 2L * (gid - metricsCount);
        if (offset + 2 > hmtx.length()) return xMin(gid);
        return hmtx.s16(offset);
    }

    // glyf のグリフの xMin (フォントの単位)。中身のないグリフは 0
    private int xMin(int gid) {
        final FontBytes g = glyphData(gid);
        return g == null || g.length() < 10 ? 0 : g.s16(2);
    }

    private FontBytes glyphData(int gid) {
        final FontBytes loca = tables.get("loca");
        final FontBytes glyf = tables.get("glyf");
        if (loca == null || glyf == null) return null;
        final long start = longLoca ? loca.u32(4L * gid) : 2L * loca.u16(2L * gid);
        final long end = longLoca ? loca.u32(4L * gid + 4) : 2L * loca.u16(2L * gid + 2);
        if (end <= start || start >= glyf.length()) return null;
        return glyf.slice(start, Math.min(end, glyf.length()) - start);
    }

    private void readGlyph(int gid, Points points, int depth) {
        if (depth > MAX_COMPOSITE_DEPTH) throw new FontBytes.FontFormatError("Composite glyph is nested too deeply");
        final FontBytes g = glyphData(gid);
        if (g == null || g.length() < 10) return;
        final int contours = g.s16(0);
        if (contours >= 0) {
            simpleGlyph(g, contours, points);
        } else {
            compositeGlyph(g, points, depth);
        }
    }

    private static void simpleGlyph(FontBytes g, int contours, Points points) {
        final int[] ends = new int[contours];
        int pointCount = 0;
        for (int i = 0; i < contours; i++) {
            ends[i] = g.u16(10 + 2L * i);
            if (ends[i] < pointCount - 1 || ends[i] >= MAX_POINTS) throw new FontBytes.FontFormatError("Bad contour end");
            pointCount = ends[i] + 1;
        }
        final long instructionLength = g.u16(10 + 2L * contours);
        long p = 12 + 2L * contours + instructionLength;
        final int[] flags = new int[pointCount];
        for (int i = 0; i < pointCount; ) {
            final int flag = g.u8(p++);
            flags[i++] = flag;
            if ((flag & 8) != 0) {
                int repeat = g.u8(p++);
                while (repeat-- > 0 && i < pointCount) flags[i++] = flag;
            }
        }
        final int[] xs = new int[pointCount];
        final int[] ys = new int[pointCount];
        int value = 0;
        for (int i = 0; i < pointCount; i++) {
            final int flag = flags[i];
            if ((flag & 2) != 0) {
                final int d = g.u8(p++);
                value += (flag & 16) != 0 ? d : -d;
            } else if ((flag & 16) == 0) {
                value += g.s16(p);
                p += 2;
            }
            xs[i] = value;
        }
        value = 0;
        for (int i = 0; i < pointCount; i++) {
            final int flag = flags[i];
            if ((flag & 4) != 0) {
                final int d = g.u8(p++);
                value += (flag & 32) != 0 ? d : -d;
            } else if ((flag & 32) == 0) {
                value += g.s16(p);
                p += 2;
            }
            ys[i] = value;
        }
        int start = 0;
        for (int c = 0; c < contours; c++) {
            for (int i = start; i <= ends[c]; i++) points.add(xs[i], ys[i], (flags[i] & 1) != 0);
            points.endContour();
            start = ends[c] + 1;
        }
    }

    private void compositeGlyph(FontBytes g, Points points, int depth) {
        long p = 10;
        for (int components = 0; components < 1024; components++) {
            final int flags = g.u16(p);
            final int glyph = g.u16(p + 2);
            p += 4;
            final double arg1, arg2;
            if ((flags & 1) != 0) {
                arg1 = (flags & 2) != 0 ? g.s16(p) : g.u16(p);
                arg2 = (flags & 2) != 0 ? g.s16(p + 2) : g.u16(p + 2);
                p += 4;
            } else {
                arg1 = (flags & 2) != 0 ? g.s8(p) : g.u8(p);
                arg2 = (flags & 2) != 0 ? g.s8(p + 1) : g.u8(p + 1);
                p += 2;
            }
            double a = 1, b = 0, c = 0, d = 1;
            if ((flags & 8) != 0) {
                a = d = f2dot14(g.s16(p));
                p += 2;
            } else if ((flags & 0x40) != 0) {
                a = f2dot14(g.s16(p));
                d = f2dot14(g.s16(p + 2));
                p += 4;
            } else if ((flags & 0x80) != 0) {
                a = f2dot14(g.s16(p));
                b = f2dot14(g.s16(p + 2));
                c = f2dot14(g.s16(p + 4));
                d = f2dot14(g.s16(p + 6));
                p += 8;
            }
            final Points component = new Points();
            readGlyph(glyph, component, depth + 1);
            // 変換
            for (int i = 0; i < component.count; i++) {
                final double x = component.x[i], y = component.y[i];
                component.x[i] = a * x + c * y;
                component.y[i] = b * x + d * y;
            }
            double dx, dy;
            if ((flags & 2) != 0) {
                dx = arg1;
                dy = arg2;
            } else {
                // 点を合わせる: 組み立て中のグリフの点 arg1 に、部品の点 arg2 を重ねる
                final int parent = (int) arg1, child = (int) arg2;
                if (parent >= 0 && parent < points.count && child >= 0 && child < component.count) {
                    dx = points.x[parent] - component.x[child];
                    dy = points.y[parent] - component.y[child];
                } else {
                    dx = dy = 0;
                }
            }
            final int base = points.count;
            int start = 0;
            for (int contour = 0; contour < component.contours; contour++) {
                for (int i = start; i <= component.ends[contour]; i++) points.add(component.x[i] + dx, component.y[i] + dy, component.on[i]);
                points.endContour();
                start = component.ends[contour] + 1;
            }
            if (points.count - base != component.count) throw new FontBytes.FontFormatError("Bad composite");
            if ((flags & 0x20) == 0) break; // MORE_COMPONENTS
        }
    }

    private static double f2dot14(int value) {
        return value / 16384.0;
    }

    // 1 つの輪郭を、2 次ベジェの道にする。曲線外の点が 2 つ続けば、その中点が曲線上の点
    private static void contour(GeneralPath path, Points pts, int start, int end, double scale) {
        final int n = end - start + 1;
        if (n < 2) return;
        // 始まりは曲線上の点。なければ、最初の 2 点の中点
        int first = -1;
        for (int i = 0; i < n; i++) {
            if (pts.on[start + i]) {
                first = i;
                break;
            }
        }
        double startX, startY;
        if (first >= 0) {
            startX = pts.x[start + first];
            startY = pts.y[start + first];
        } else {
            startX = (pts.x[start] + pts.x[start + 1]) / 2;
            startY = (pts.y[start] + pts.y[start + 1]) / 2;
            first = 0;
        }
        path.moveTo(startX * scale, startY * scale);
        double controlX = 0, controlY = 0;
        // 始まりの点が曲線上の点でも中点でも、次の点から順に見る
        boolean hasControl = false;
        for (int k = 1; k <= n; k++) {
            final int i = start + (first + k) % n;
            final double x = pts.x[i], y = pts.y[i];
            if (pts.on[i]) {
                if (hasControl) {
                    path.quadTo(controlX * scale, controlY * scale, x * scale, y * scale);
                    hasControl = false;
                } else {
                    path.lineTo(x * scale, y * scale);
                }
            } else {
                if (hasControl) {
                    final double midX = (controlX + x) / 2, midY = (controlY + y) / 2;
                    path.quadTo(controlX * scale, controlY * scale, midX * scale, midY * scale);
                }
                controlX = x;
                controlY = y;
                hasControl = true;
            }
        }
        if (hasControl) path.quadTo(controlX * scale, controlY * scale, startX * scale, startY * scale);
        path.closePath();
    }
}
