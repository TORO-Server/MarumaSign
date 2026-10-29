package marumasa.marumasa_sign.core.pdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CFF (Compact Font Format) のフォント。PDF の FontFile3 (Type1C / CIDFontType0C) と、OpenType の CFF 表に使われる。
 * 輪郭は Type 2 のグリフの手続き (charstring) を実行して作る。
 * <p>
 * 手続きの実行には、スタックの深さ (48)、サブルーチンの入れ子 (10)、命令の数に上限を設ける。
 * 自分を呼ぶサブルーチンや、巨大な数の命令で止まらないようにするため。
 */
public final class CffFont implements FontProgram {

    private static final int MAX_STACK = 48;
    private static final int MAX_SUBR_DEPTH = 10;
    private static final int MAX_OPERATIONS = 200000;

    // INDEX: 各項目の始まりと終わり (データの中の位置)
    private static final class Index {
        final long[] offsets;
        final long end;

        Index(long[] offsets, long end) {
            this.offsets = offsets;
            this.end = end;
        }

        int count() {
            return Math.max(0, offsets.length - 1);
        }

        FontBytes item(FontBytes data, int i) {
            if (i < 0 || i >= count()) return null;
            return data.slice(offsets[i], offsets[i + 1] - offsets[i]);
        }
    }

    // フォントの辞書 (Private) の値
    private static final class PrivateDict {
        Index subrs;
        double defaultWidth;
        double nominalWidth;
    }

    private final FontBytes data;
    private final String name;
    private final Index strings;
    private final Index globalSubrs;
    private final Index charStrings;
    private final boolean cid;
    // GID ごとの SID (CID フォントなら CID)
    private final int[] charset;
    // 文字コードごとの GID (CID フォントでなければ。フォントが持つ文字コードの表)
    private final int[] encoding = new int[256];
    private final String[] encodingNames = new String[256];
    private final PrivateDict[] privates;
    // GID ごとの Private の番号 (CID フォント)
    private final int[] fdSelect;
    private final double[][] matrices;
    private Map<String, Integer> nameToGid;
    private Map<Integer, Integer> cidToGid;

    /**
     * @param fontName 複数のフォントを含む CFF で使うフォントの名前 (null なら最初のもの)
     */
    public CffFont(FontBytes data, String fontName) {
        this.data = data;
        final int headerSize = data.u8(2);
        final Index names = index(headerSize);
        final Index topDicts = index(names.end);
        strings = index(topDicts.end);
        globalSubrs = index(strings.end);
        int which = 0;
        if (fontName != null) {
            for (int i = 0; i < names.count(); i++) {
                final FontBytes item = names.item(data, i);
                if (item != null && item.ascii(0, item.length()).equals(fontName)) which = i;
            }
        }
        final FontBytes nameBytes = names.item(data, which);
        name = nameBytes == null ? null : nameBytes.ascii(0, Math.min(nameBytes.length(), 256));
        final FontBytes topBytes = topDicts.item(data, which);
        if (topBytes == null) throw new FontBytes.FontFormatError("No top dict");
        final Map<Integer, double[]> top = dict(topBytes);
        final double[] charStringsOffset = top.get(17);
        if (charStringsOffset == null || charStringsOffset.length == 0) throw new FontBytes.FontFormatError("No CharStrings");
        charStrings = index((long) charStringsOffset[0]);
        final int glyphs = charStrings.count();
        cid = top.containsKey(1230); // ROS (12 30)
        final double[] topMatrix = top.get(1207);
        // 文字集合 (GID ごとの SID か CID)
        final double[] charsetOffset = top.get(15);
        charset = readCharset((long) first(charsetOffset, 0), glyphs);
        if (cid) {
            final double[] fdArrayOffset = top.get(1236);
            final double[] fdSelectOffset = top.get(1237);
            if (fdArrayOffset == null || fdArrayOffset.length == 0) throw new FontBytes.FontFormatError("CID font without FDArray");
            final Index fdArray = index((long) fdArrayOffset[0]);
            final int fdCount = Math.min(fdArray.count(), 256);
            privates = new PrivateDict[Math.max(1, fdCount)];
            matrices = new double[privates.length][];
            privates[0] = new PrivateDict();
            matrices[0] = combine(topMatrix, null);
            for (int i = 0; i < fdCount; i++) {
                final FontBytes fd = fdArray.item(data, i);
                final Map<Integer, double[]> fdDict = fd == null ? new HashMap<Integer, double[]>() : dict(fd);
                privates[i] = readPrivate(fdDict.get(18));
                matrices[i] = combine(topMatrix, fdDict.get(1207));
            }
            fdSelect = fdSelectOffset == null || fdSelectOffset.length == 0 ? new int[glyphs]
                    : readFdSelect((long) fdSelectOffset[0], glyphs, privates.length);
        } else {
            privates = new PrivateDict[]{readPrivate(top.get(18))};
            matrices = new double[][]{topMatrix != null && topMatrix.length >= 6 ? topMatrix : new double[]{0.001, 0, 0, 0.001, 0, 0}};
            fdSelect = null;
            final double[] encodingOffset = top.get(16);
            readEncoding((long) first(encodingOffset, 0));
        }
    }

    // 辞書の値の最初の数。値がない (数のない演算子が書かれている) ときは fallback
    private static double first(double[] operands, double fallback) {
        return operands == null || operands.length == 0 ? fallback : operands[0];
    }

    // CID フォントの行列: 上の辞書と FD の行列を掛け合わせる (片方しかなければそれ)
    private static double[] combine(double[] top, double[] fd) {
        final double[] defaultMatrix = {0.001, 0, 0, 0.001, 0, 0};
        final boolean hasTop = top != null && top.length >= 6;
        final boolean hasFd = fd != null && fd.length >= 6;
        if (!hasTop && !hasFd) return defaultMatrix;
        if (!hasFd) return top;
        if (!hasTop) return fd;
        final double[] m = {
                fd[0] * top[0] + fd[1] * top[2], fd[0] * top[1] + fd[1] * top[3],
                fd[2] * top[0] + fd[3] * top[2], fd[2] * top[1] + fd[3] * top[3],
                fd[4] * top[0] + fd[5] * top[2] + top[4], fd[4] * top[1] + fd[5] * top[3] + top[5]};
        // 両方に 1/1000 が書かれたフォントがある。小さすぎれば FD のものだけを使う
        if (Math.abs(m[0]) < 1e-5 && Math.abs(m[1]) < 1e-5) return fd;
        return m;
    }

    @Override
    public String postScriptName() {
        return name;
    }

    public boolean isCid() {
        return cid;
    }

    @Override
    public int glyphCount() {
        return charStrings.count();
    }

    // ---------- 構造 ----------

    private Index index(long position) {
        final int count = data.u16(position);
        if (count == 0) return new Index(new long[0], position + 2);
        final int offSize = data.u8(position + 2);
        if (offSize < 1 || offSize > 4) throw new FontBytes.FontFormatError("Bad offSize");
        final long base = position + 2 + (long) (count + 1) * offSize;
        final long[] offsets = new long[count + 1];
        for (int i = 0; i <= count; i++) {
            offsets[i] = base + data.unsigned(position + 3 + (long) i * offSize, offSize);
            if (offsets[i] > data.length() || (i > 0 && offsets[i] < offsets[i - 1])) {
                throw new FontBytes.FontFormatError("Bad INDEX offset");
            }
        }
        return new Index(offsets, offsets[count]);
    }

    // DICT を読む。鍵は演算子 (12 x は 1200 + x)、値は直前の数の並び
    private static Map<Integer, double[]> dict(FontBytes d) {
        final Map<Integer, double[]> result = new HashMap<>();
        final List<Double> operands = new ArrayList<>();
        int p = 0;
        final int end = d.length();
        while (p < end) {
            final int b0 = d.u8(p);
            if (b0 <= 21) {
                int op = b0;
                p++;
                if (b0 == 12) {
                    op = 1200 + d.u8(p);
                    p++;
                }
                final double[] values = new double[operands.size()];
                for (int i = 0; i < values.length; i++) values[i] = operands.get(i);
                result.put(op, values);
                operands.clear();
            } else if (b0 == 28) {
                operands.add((double) d.s16(p + 1));
                p += 3;
            } else if (b0 == 29) {
                operands.add((double) d.s32(p + 1));
                p += 5;
            } else if (b0 == 30) {
                // 実数: 4 ビットずつの数字
                final StringBuilder text = new StringBuilder();
                p++;
                boolean done = false;
                while (!done && p < end) {
                    final int b = d.u8(p++);
                    for (int nibble : new int[]{b >> 4, b & 15}) {
                        if (nibble <= 9) text.append((char) ('0' + nibble));
                        else if (nibble == 0xA) text.append('.');
                        else if (nibble == 0xB) text.append('E');
                        else if (nibble == 0xC) text.append("E-");
                        else if (nibble == 0xE) text.append('-');
                        else if (nibble == 0xF) {
                            done = true;
                            break;
                        }
                    }
                }
                double value;
                try {
                    value = text.length() == 0 ? 0 : Double.parseDouble(text.toString());
                } catch (NumberFormatException e) {
                    value = 0;
                }
                operands.add(value);
            } else if (b0 >= 32 && b0 <= 246) {
                operands.add((double) (b0 - 139));
                p++;
            } else if (b0 >= 247 && b0 <= 250) {
                operands.add((double) ((b0 - 247) * 256 + d.u8(p + 1) + 108));
                p += 2;
            } else if (b0 >= 251 && b0 <= 254) {
                operands.add((double) (-(b0 - 251) * 256 - d.u8(p + 1) - 108));
                p += 2;
            } else {
                // 使われていない値
                p++;
            }
            if (operands.size() > MAX_STACK) operands.clear();
        }
        return result;
    }

    private PrivateDict readPrivate(double[] sizeAndOffset) {
        final PrivateDict result = new PrivateDict();
        if (sizeAndOffset == null || sizeAndOffset.length < 2) return result;
        final long size = (long) sizeAndOffset[0];
        final long offset = (long) sizeAndOffset[1];
        if (offset < 0 || offset >= data.length() || size < 0) return result;
        final Map<Integer, double[]> dict = dict(data.slice(offset, size));
        final double[] subrs = dict.get(19);
        if (subrs != null && subrs.length > 0 && subrs[0] > 0) {
            try {
                result.subrs = index(offset + (long) subrs[0]);
            } catch (FontBytes.FontFormatError e) {
                // サブルーチンがなくても、使わないグリフは描ける
            }
        }
        final double[] defaultWidth = dict.get(20);
        if (defaultWidth != null && defaultWidth.length > 0) result.defaultWidth = defaultWidth[0];
        final double[] nominalWidth = dict.get(21);
        if (nominalWidth != null && nominalWidth.length > 0) result.nominalWidth = nominalWidth[0];
        return result;
    }

    private int[] readCharset(long offset, int glyphs) {
        final int[] result = new int[glyphs];
        if (offset == 0) {
            // ISOAdobe: GID と SID が同じ
            for (int i = 0; i < glyphs; i++) result[i] = i <= 228 ? i : 0;
            return result;
        }
        if (offset == 1 || offset == 2) {
            final int[] sids = FontData.cffTable(offset == 1 ? "cffexpertcharset" : "cffexpertsubsetcharset");
            for (int i = 0; i < glyphs && sids != null && i < sids.length; i++) result[i] = sids[i];
            return result;
        }
        try {
            final int format = data.u8(offset);
            long p = offset + 1;
            int gid = 1;
            if (format == 0) {
                for (; gid < glyphs; gid++, p += 2) result[gid] = data.u16(p);
            } else if (format == 1 || format == 2) {
                while (gid < glyphs) {
                    final int first = data.u16(p);
                    final int left = format == 1 ? data.u8(p + 2) : data.u16(p + 2);
                    p += format == 1 ? 3 : 4;
                    for (int i = 0; i <= left && gid < glyphs; i++) result[gid++] = first + i;
                }
            }
        } catch (FontBytes.FontFormatError e) {
            // 読めた分だけ使う
        }
        return result;
    }

    private int[] readFdSelect(long offset, int glyphs, int fdCount) {
        final int[] result = new int[glyphs];
        try {
            final int format = data.u8(offset);
            if (format == 0) {
                for (int i = 0; i < glyphs; i++) result[i] = data.u8(offset + 1 + i);
            } else if (format == 3) {
                final int ranges = data.u16(offset + 1);
                for (int r = 0; r < ranges; r++) {
                    final long p = offset + 3 + 3L * r;
                    final int first = data.u16(p);
                    final int fd = data.u8(p + 2);
                    final int next = data.u16(p + 3);
                    for (int gid = first; gid < next && gid < glyphs; gid++) result[gid] = fd;
                }
            }
        } catch (FontBytes.FontFormatError e) {
            // 読めた分だけ使う
        }
        for (int i = 0; i < glyphs; i++) {
            if (result[i] < 0 || result[i] >= fdCount) result[i] = 0;
        }
        return result;
    }

    private void readEncoding(long offset) {
        if (offset == 0 || offset == 1) {
            final String[] names;
            if (offset == 0) {
                names = FontData.encoding("Standard");
            } else {
                names = new String[256];
                final int[] sids = FontData.cffTable("cffexpertencoding");
                for (int code = 0; code < 256 && sids != null && code < sids.length; code++) {
                    names[code] = sids[code] == 0 ? null : sidName(sids[code]);
                }
            }
            for (int code = 0; code < 256 && names != null; code++) {
                encodingNames[code] = names[code];
                encoding[code] = names[code] == null ? 0 : Math.max(0, gidForName(names[code]));
            }
            return;
        }
        try {
            final int format = data.u8(offset);
            long p = offset + 1;
            if ((format & 0x7F) == 0) {
                final int count = data.u8(p++);
                for (int gid = 1; gid <= count; gid++) setCode(data.u8(p++), gid);
            } else if ((format & 0x7F) == 1) {
                final int ranges = data.u8(p++);
                int gid = 1;
                for (int r = 0; r < ranges; r++) {
                    final int first = data.u8(p);
                    final int left = data.u8(p + 1);
                    p += 2;
                    for (int i = 0; i <= left; i++) setCode(first + i, gid++);
                }
            }
            if ((format & 0x80) != 0) {
                // 追加の割り当て (コードと SID)
                final int count = data.u8(p++);
                for (int i = 0; i < count; i++, p += 3) {
                    final int code = data.u8(p);
                    final String glyph = sidName(data.u16(p + 1));
                    final int gid = gidForName(glyph);
                    if (gid > 0) setCode(code, gid);
                }
            }
        } catch (FontBytes.FontFormatError e) {
            // 読めた分だけ使う
        }
    }

    private void setCode(int code, int gid) {
        if (code < 0 || code > 255 || gid >= glyphCount()) return;
        encoding[code] = gid;
        encodingNames[code] = nameForGid(gid);
    }

    /**
     * フォントが持つ文字コードの表 (コードごとのグリフの名前)。CID フォントなら null。
     */
    public String[] builtInEncoding() {
        return cid ? null : encodingNames.clone();
    }

    private String sidName(int sid) {
        final String[] standard = FontData.cffStandardStrings();
        if (sid < standard.length) return standard[sid];
        final FontBytes item = strings.item(data, sid - standard.length);
        return item == null ? null : item.ascii(0, Math.min(item.length(), 256));
    }

    @Override
    public synchronized int gidForName(String glyphName) {
        if (cid || glyphName == null) return -1;
        if (nameToGid == null) {
            nameToGid = new HashMap<>();
            for (int gid = charset.length - 1; gid >= 0; gid--) {
                final String n = sidName(charset[gid]);
                if (n != null) nameToGid.put(n, gid);
            }
        }
        final Integer gid = nameToGid.get(glyphName);
        return gid == null ? -1 : gid;
    }

    @Override
    public String nameForGid(int gid) {
        if (cid || gid < 0 || gid >= charset.length) return null;
        return sidName(charset[gid]);
    }

    /**
     * CID のグリフの番号 (CID フォント)。なければ 0。CID フォントでなければ、CID をそのまま GID とする。
     */
    public synchronized int gidForCid(int cidValue) {
        if (!cid) return cidValue >= 0 && cidValue < glyphCount() ? cidValue : 0;
        if (cidToGid == null) {
            cidToGid = new HashMap<>();
            for (int gid = charset.length - 1; gid >= 0; gid--) cidToGid.put(charset[gid], gid);
        }
        final Integer gid = cidToGid.get(cidValue);
        return gid == null ? 0 : gid;
    }

    private int fd(int gid) {
        return fdSelect == null || gid < 0 || gid >= fdSelect.length ? 0 : fdSelect[gid];
    }

    // ---------- グリフ ----------

    @Override
    public GeneralPath path(int gid) {
        final Glyph glyph = run(gid);
        final GeneralPath path = glyph.path;
        final double[] m = matrices[fd(gid)];
        path.transform(new AffineTransform(m[0], m[1], m[2], m[3], m[4], m[5]));
        return path;
    }

    @Override
    public double advance(int gid) {
        final Glyph glyph = run(gid);
        final double[] m = matrices[fd(gid)];
        return glyph.width * m[0];
    }

    private static final class Glyph {
        final GeneralPath path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
        double width;
    }

    // 1 つのグリフの実行中の状態
    private final class Interpreter {
        final double[] stack = new double[MAX_STACK + 4];
        int size;
        final double[] transient_ = new double[32];
        final Glyph glyph = new Glyph();
        final PrivateDict priv;
        double x, y;
        boolean open;
        int stems;
        boolean widthDone;
        int operations;
        boolean ended;
        final int depthForSeac;

        Interpreter(PrivateDict priv, int depthForSeac) {
            this.priv = priv;
            this.depthForSeac = depthForSeac;
            glyph.width = priv.defaultWidth;
        }

        void push(double value) {
            if (size >= MAX_STACK) throw new FontBytes.FontFormatError("Stack overflow");
            stack[size++] = value;
        }

        // 幅: 最初のスタックを空にする命令で、引数が 1 つ多ければその先頭が幅
        void width(boolean odd) {
            if (widthDone) return;
            widthDone = true;
            if (odd && size > 0) {
                glyph.width = priv.nominalWidth + stack[0];
                System.arraycopy(stack, 1, stack, 0, size - 1);
                size--;
            }
        }

        void moveTo(double dx, double dy) {
            close();
            x += dx;
            y += dy;
            glyph.path.moveTo(x, y);
            open = true;
        }

        void lineTo(double dx, double dy) {
            if (!open) moveTo(0, 0);
            x += dx;
            y += dy;
            glyph.path.lineTo(x, y);
        }

        void curveTo(double dx1, double dy1, double dx2, double dy2, double dx3, double dy3) {
            if (!open) moveTo(0, 0);
            final double x1 = x + dx1, y1 = y + dy1;
            final double x2 = x1 + dx2, y2 = y1 + dy2;
            x = x2 + dx3;
            y = y2 + dy3;
            glyph.path.curveTo(x1, y1, x2, y2, x, y);
        }

        void close() {
            if (open) glyph.path.closePath();
            open = false;
        }
    }

    private Glyph run(int gid) {
        final Interpreter state = new Interpreter(privates[fd(gid)], 0);
        execute(gid, state);
        return state.glyph;
    }

    private void execute(int gid, Interpreter state) {
        final FontBytes code = charStrings.item(data, gid);
        if (code == null) return;
        try {
            execute(code, state, 0);
        } catch (FontBytes.FontFormatError | ArrayIndexOutOfBoundsException e) {
            // 壊れた手続き: そこまでに描いた輪郭を使う
        }
        state.close();
    }

    private static int bias(Index subrs) {
        final int count = subrs == null ? 0 : subrs.count();
        return count < 1240 ? 107 : count < 33900 ? 1131 : 32768;
    }

    private void execute(FontBytes code, Interpreter s, int depth) {
        if (depth > MAX_SUBR_DEPTH) throw new FontBytes.FontFormatError("Subroutines are nested too deeply");
        int p = 0;
        final int end = code.length();
        while (p < end && !s.ended) {
            if (++s.operations > MAX_OPERATIONS) throw new FontBytes.FontFormatError("Too many operations");
            final int b0 = code.u8(p);
            if (b0 >= 32 || b0 == 28) {
                if (b0 == 28) {
                    s.push(code.s16(p + 1));
                    p += 3;
                } else if (b0 <= 246) {
                    s.push(b0 - 139);
                    p++;
                } else if (b0 <= 250) {
                    s.push((b0 - 247) * 256 + code.u8(p + 1) + 108);
                    p += 2;
                } else if (b0 <= 254) {
                    s.push(-(b0 - 251) * 256 - code.u8(p + 1) - 108);
                    p += 2;
                } else {
                    // 16.16 の固定小数点数
                    s.push(code.s32(p + 1) / 65536.0);
                    p += 5;
                }
                continue;
            }
            p++;
            switch (b0) {
                case 1: // hstem
                case 3: // vstem
                case 18: // hstemhm
                case 23: // vstemhm
                    s.width(s.size % 2 == 1);
                    s.stems += s.size / 2;
                    s.size = 0;
                    break;
                case 19: // hintmask
                case 20: // cntrmask
                    // 直前の引数は、暗黙の vstem
                    s.width(s.size % 2 == 1);
                    s.stems += s.size / 2;
                    s.size = 0;
                    p += (s.stems + 7) / 8;
                    break;
                case 21: // rmoveto
                    s.width(s.size > 2);
                    if (s.size >= 2) s.moveTo(s.stack[s.size - 2], s.stack[s.size - 1]);
                    s.size = 0;
                    break;
                case 22: // hmoveto
                    s.width(s.size > 1);
                    if (s.size >= 1) s.moveTo(s.stack[s.size - 1], 0);
                    s.size = 0;
                    break;
                case 4: // vmoveto
                    s.width(s.size > 1);
                    if (s.size >= 1) s.moveTo(0, s.stack[s.size - 1]);
                    s.size = 0;
                    break;
                case 5: // rlineto
                    for (int i = 0; i + 1 < s.size; i += 2) s.lineTo(s.stack[i], s.stack[i + 1]);
                    s.size = 0;
                    break;
                case 6: // hlineto
                case 7: { // vlineto
                    boolean horizontal = b0 == 6;
                    for (int i = 0; i < s.size; i++) {
                        if (horizontal) s.lineTo(s.stack[i], 0);
                        else s.lineTo(0, s.stack[i]);
                        horizontal = !horizontal;
                    }
                    s.size = 0;
                    break;
                }
                case 8: // rrcurveto
                    for (int i = 0; i + 5 < s.size; i += 6) {
                        s.curveTo(s.stack[i], s.stack[i + 1], s.stack[i + 2], s.stack[i + 3], s.stack[i + 4], s.stack[i + 5]);
                    }
                    s.size = 0;
                    break;
                case 24: { // rcurveline
                    int i = 0;
                    for (; i + 5 < s.size - 2; i += 6) {
                        s.curveTo(s.stack[i], s.stack[i + 1], s.stack[i + 2], s.stack[i + 3], s.stack[i + 4], s.stack[i + 5]);
                    }
                    if (i + 1 < s.size) s.lineTo(s.stack[i], s.stack[i + 1]);
                    s.size = 0;
                    break;
                }
                case 25: { // rlinecurve
                    int i = 0;
                    for (; i + 1 < s.size - 6; i += 2) s.lineTo(s.stack[i], s.stack[i + 1]);
                    if (i + 5 < s.size) {
                        s.curveTo(s.stack[i], s.stack[i + 1], s.stack[i + 2], s.stack[i + 3], s.stack[i + 4], s.stack[i + 5]);
                    }
                    s.size = 0;
                    break;
                }
                case 26: { // vvcurveto
                    int i = 0;
                    double dx1 = 0;
                    if (s.size % 4 == 1) dx1 = s.stack[i++];
                    for (; i + 3 < s.size; i += 4) {
                        s.curveTo(dx1, s.stack[i], s.stack[i + 1], s.stack[i + 2], 0, s.stack[i + 3]);
                        dx1 = 0;
                    }
                    s.size = 0;
                    break;
                }
                case 27: { // hhcurveto
                    int i = 0;
                    double dy1 = 0;
                    if (s.size % 4 == 1) dy1 = s.stack[i++];
                    for (; i + 3 < s.size; i += 4) {
                        s.curveTo(s.stack[i], dy1, s.stack[i + 1], s.stack[i + 2], s.stack[i + 3], 0);
                        dy1 = 0;
                    }
                    s.size = 0;
                    break;
                }
                case 30: // vhcurveto
                case 31: { // hvcurveto
                    boolean horizontal = b0 == 31;
                    int i = 0;
                    while (i + 3 < s.size) {
                        // 最後の曲線だけ、5 つ目の引数 (終わりの点のもう一方の向きの差) があってもよい
                        final double extra = s.size - i == 5 ? s.stack[i + 4] : 0;
                        if (horizontal) {
                            s.curveTo(s.stack[i], 0, s.stack[i + 1], s.stack[i + 2], extra, s.stack[i + 3]);
                        } else {
                            s.curveTo(0, s.stack[i], s.stack[i + 1], s.stack[i + 2], s.stack[i + 3], extra);
                        }
                        horizontal = !horizontal;
                        i += 4;
                    }
                    s.size = 0;
                    break;
                }
                case 10: // callsubr
                case 29: { // callgsubr
                    if (s.size == 0) throw new FontBytes.FontFormatError("Stack underflow");
                    final Index subrs = b0 == 10 ? s.priv.subrs : globalSubrs;
                    final int number = (int) s.stack[--s.size] + bias(subrs);
                    final FontBytes subr = subrs == null ? null : subrs.item(data, number);
                    if (subr == null) throw new FontBytes.FontFormatError("Missing subroutine");
                    execute(subr, s, depth + 1);
                    break;
                }
                case 11: // return
                    return;
                case 14: // endchar
                    s.width(s.size == 1 || s.size == 5);
                    if (s.size >= 4) seac(s);
                    s.close();
                    s.ended = true;
                    s.size = 0;
                    return;
                case 12:
                    p = escape(code, p, s);
                    break;
                default:
                    // 予約された命令: 引数を捨てる
                    s.size = 0;
                    break;
            }
        }
    }

    // endchar の 4 つの引数 (seac と同じ): アクセント付きの文字を、元の文字とアクセントの 2 つのグリフで作る
    private void seac(Interpreter s) {
        final double adx = s.stack[s.size - 4], ady = s.stack[s.size - 3];
        final int baseCode = (int) s.stack[s.size - 2], accentCode = (int) s.stack[s.size - 1];
        if (s.depthForSeac > 0 || cid) return;
        final String[] standard = FontData.encoding("Standard");
        if (standard == null || baseCode < 0 || baseCode > 255 || accentCode < 0 || accentCode > 255) return;
        final int baseGid = gidForName(standard[baseCode]);
        final int accentGid = gidForName(standard[accentCode]);
        s.close();
        if (baseGid > 0) {
            final Interpreter base = new Interpreter(s.priv, 1);
            execute(baseGid, base);
            s.glyph.path.append(base.glyph.path, false);
        }
        if (accentGid > 0) {
            final Interpreter accent = new Interpreter(s.priv, 1);
            execute(accentGid, accent);
            final GeneralPath moved = (GeneralPath) accent.glyph.path.clone();
            moved.transform(AffineTransform.getTranslateInstance(adx, ady));
            s.glyph.path.append(moved, false);
        }
    }

    private int escape(FontBytes code, int p, Interpreter s) {
        final int op = code.u8(p);
        p++;
        final double[] st = s.stack;
        switch (op) {
            case 35: // flex
                if (s.size >= 12) {
                    s.curveTo(st[0], st[1], st[2], st[3], st[4], st[5]);
                    s.curveTo(st[6], st[7], st[8], st[9], st[10], st[11]);
                }
                s.size = 0;
                break;
            case 34: // hflex
                if (s.size >= 7) {
                    final double y0 = s.y;
                    s.curveTo(st[0], 0, st[1], st[2], st[3], 0);
                    s.curveTo(st[4], 0, st[5], y0 - s.y, st[6], 0);
                }
                s.size = 0;
                break;
            case 36: // hflex1
                if (s.size >= 9) {
                    // 最後の点の高さは、始まりと同じ
                    final double dy6 = -(st[1] + st[3] + st[7]);
                    s.curveTo(st[0], st[1], st[2], st[3], st[4], 0);
                    s.curveTo(st[5], 0, st[6], st[7], st[8], dy6);
                }
                s.size = 0;
                break;
            case 37: // flex1
                if (s.size >= 11) {
                    double dx = 0, dy = 0;
                    for (int i = 0; i < 10; i += 2) {
                        dx += st[i];
                        dy += st[i + 1];
                    }
                    // 最後の点は、動きの大きいほうの向きだけ d6 で進み、もう一方は始まりに戻る
                    final double dx6 = Math.abs(dx) > Math.abs(dy) ? st[10] : -dx;
                    final double dy6 = Math.abs(dx) > Math.abs(dy) ? -dy : st[10];
                    s.curveTo(st[0], st[1], st[2], st[3], st[4], st[5]);
                    s.curveTo(st[6], st[7], st[8], st[9], dx6, dy6);
                }
                s.size = 0;
                break;
            case 3: binary(s, (a, b) -> a != 0 && b != 0 ? 1 : 0); break; // and
            case 4: binary(s, (a, b) -> a != 0 || b != 0 ? 1 : 0); break; // or
            case 5: unary(s, a -> a == 0 ? 1 : 0); break; // not
            case 9: unary(s, Math::abs); break;
            case 10: binary(s, (a, b) -> a + b); break;
            case 11: binary(s, (a, b) -> a - b); break;
            case 12: binary(s, (a, b) -> b == 0 ? 0 : a / b); break;
            case 14: unary(s, a -> -a); break;
            case 15: binary(s, (a, b) -> a == b ? 1 : 0); break;
            case 18: // drop
                if (s.size > 0) s.size--;
                break;
            case 20: { // put
                if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
                final int i = (int) st[--s.size];
                final double value = st[--s.size];
                if (i >= 0 && i < s.transient_.length) s.transient_[i] = value;
                break;
            }
            case 21: { // get
                if (s.size < 1) throw new FontBytes.FontFormatError("Stack underflow");
                final int i = (int) st[s.size - 1];
                st[s.size - 1] = i >= 0 && i < s.transient_.length ? s.transient_[i] : 0;
                break;
            }
            case 22: { // ifelse
                if (s.size < 4) throw new FontBytes.FontFormatError("Stack underflow");
                final double v2 = st[--s.size], v1 = st[--s.size], s2 = st[--s.size], s1 = st[--s.size];
                s.push(v1 <= v2 ? s1 : s2);
                break;
            }
            case 23: // random (表示が毎回変わらないよう、決まった値)
                s.push(0.5);
                break;
            case 24: binary(s, (a, b) -> a * b); break;
            case 26: unary(s, a -> Math.sqrt(Math.max(0, a))); break;
            case 27: // dup
                if (s.size < 1) throw new FontBytes.FontFormatError("Stack underflow");
                s.push(st[s.size - 1]);
                break;
            case 28: { // exch
                if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
                final double t = st[s.size - 1];
                st[s.size - 1] = st[s.size - 2];
                st[s.size - 2] = t;
                break;
            }
            case 29: { // index
                if (s.size < 1) throw new FontBytes.FontFormatError("Stack underflow");
                int i = (int) st[s.size - 1];
                if (i < 0) i = 0;
                if (i >= s.size - 1) throw new FontBytes.FontFormatError("Bad index");
                st[s.size - 1] = st[s.size - 2 - i];
                break;
            }
            case 30: { // roll
                if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
                int j = (int) st[--s.size];
                final int n = (int) st[--s.size];
                if (n <= 0 || n > s.size) break;
                j %= n;
                if (j < 0) j += n;
                final double[] part = new double[n];
                for (int i = 0; i < n; i++) part[(i + j) % n] = st[s.size - n + i];
                System.arraycopy(part, 0, st, s.size - n, n);
                break;
            }
            default:
                s.size = 0;
                break;
        }
        return p;
    }

    private interface Binary {
        double apply(double a, double b);
    }

    private interface Unary {
        double apply(double a);
    }

    private static void binary(Interpreter s, Binary op) {
        if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
        final double b = s.stack[--s.size];
        s.stack[s.size - 1] = op.apply(s.stack[s.size - 1], b);
    }

    private static void unary(Interpreter s, Unary op) {
        if (s.size < 1) throw new FontBytes.FontFormatError("Stack underflow");
        s.stack[s.size - 1] = op.apply(s.stack[s.size - 1]);
    }
}
