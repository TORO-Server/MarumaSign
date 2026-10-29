package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.cos.Keyword;
import marumasa.marumasa_sign.core.pdf.cos.Lexer;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type 1 フォント (PDF の FontFile と、パソコンの .pfb)。
 * 暗号化された部分 (eexec) を戻し、グリフの手続き (charstring) とサブルーチンを取り出す。輪郭は手続きを実行して作る。
 * <p>
 * 手続きの実行には、サブルーチンの入れ子と命令の数に上限を設ける。seac (アクセント付きの文字) の中の seac は使わない
 * (PDFBox 2.0 は、元の文字に自分自身を指定したグリフで、無限に呼び合って StackOverflowError になる)。
 */
public final class Type1Font implements FontProgram {

    private static final int MAX_SUBR_DEPTH = 10;
    private static final int MAX_OPERATIONS = 200000;
    private static final int MAX_STACK = 64;

    private final String name;
    private final double[] matrix;
    private final String[] encoding = new String[256];
    private final List<byte[]> subrs = new ArrayList<>();
    private final Map<String, byte[]> charStrings = new HashMap<>();
    private final List<String> glyphNames = new ArrayList<>();
    private final Map<String, Integer> nameToGid = new HashMap<>();

    /**
     * @param cleartext 暗号化されていない部分 (FontFile の Length1 の分。分からなければファイル全体)
     * @param encrypted 暗号化された部分 (null なら cleartext の "eexec" の後ろ)
     */
    private Type1Font(byte[] cleartext, byte[] encrypted) {
        final Lexer lexer = new Lexer(cleartext);
        String fontName = null;
        double[] fontMatrix = null;
        int eexec = -1;
        while (true) {
            final int before = lexer.position();
            final Object token = lexer.next();
            if (token == null) break;
            if (token instanceof Keyword && ((Keyword) token).is("eexec")) {
                eexec = lexer.position();
                break;
            }
            if (!(token instanceof PdfName)) continue;
            final String key = ((PdfName) token).name;
            if (key.equals("FontName") && fontName == null) {
                final Object value = lexer.next();
                if (value instanceof PdfName) fontName = ((PdfName) value).name;
            } else if (key.equals("FontMatrix") && fontMatrix == null) {
                fontMatrix = readMatrix(lexer);
            } else if (key.equals("Encoding")) {
                readEncoding(lexer);
            }
            if (lexer.position() <= before) break;
        }
        name = fontName;
        matrix = fontMatrix != null ? fontMatrix : new double[]{0.001, 0, 0, 0.001, 0, 0};
        byte[] secret = encrypted;
        if (secret == null || secret.length == 0) {
            if (eexec < 0) throw new FontBytes.FontFormatError("No eexec section");
            // "eexec" の後ろの改行を飛ばす
            int start = eexec;
            while (start < cleartext.length && Lexer.isWhitespace(cleartext[start])) start++;
            secret = java.util.Arrays.copyOfRange(cleartext, start, cleartext.length);
        }
        readPrivate(decrypt(secret, 55665, 4, true));
    }

    public static Type1Font parse(byte[] cleartext, byte[] encrypted) {
        return new Type1Font(cleartext, encrypted);
    }

    /**
     * .pfb (区切りの印 0x80 の付いた形) か、そのままの形 (.pfa、PDF の FontFile) を読む。
     */
    public static Type1Font parse(byte[] file) {
        if (file.length > 6 && (file[0] & 0xFF) == 0x80 && file[1] == 1) {
            final java.io.ByteArrayOutputStream clear = new java.io.ByteArrayOutputStream();
            final java.io.ByteArrayOutputStream secret = new java.io.ByteArrayOutputStream();
            int p = 0;
            while (p + 6 <= file.length && (file[p] & 0xFF) == 0x80) {
                final int type = file[p + 1];
                if (type == 3) break;
                final long length = (file[p + 2] & 0xFFL) | (file[p + 3] & 0xFFL) << 8 | (file[p + 4] & 0xFFL) << 16 | (file[p + 5] & 0xFFL) << 24;
                p += 6;
                final int n = (int) Math.min(length, file.length - p);
                if (type == 1) clear.write(file, p, n);
                else if (type == 2) secret.write(file, p, n);
                p += n;
            }
            return new Type1Font(clear.toByteArray(), secret.toByteArray());
        }
        return new Type1Font(file, null);
    }

    private static double[] readMatrix(Lexer lexer) {
        final Object open = lexer.next();
        if (open != Keyword.ARRAY_START && open != Keyword.PROC_START) return null;
        final double[] values = new double[6];
        for (int i = 0; i < 6; i++) {
            final Object value = lexer.next();
            if (!(value instanceof Number)) return null;
            values[i] = ((Number) value).doubleValue();
        }
        return values;
    }

    private void readEncoding(Lexer lexer) {
        final Object first = lexer.next();
        if (first instanceof Keyword && ((Keyword) first).is("StandardEncoding")) {
            final String[] standard = FontData.encoding("Standard");
            if (standard != null) System.arraycopy(standard, 0, encoding, 0, 256);
            return;
        }
        // 「dup コード /名前 put」の並び。def で終わる
        Object previous2 = null, previous1 = null;
        for (int guard = 0; guard < 100000; guard++) {
            final Object token = lexer.next();
            if (token == null) return;
            if (token instanceof Keyword) {
                final String text = ((Keyword) token).text;
                if (text.equals("put") && previous2 instanceof Integer && previous1 instanceof PdfName) {
                    final int code = (Integer) previous2;
                    if (code >= 0 && code < 256) encoding[code] = ((PdfName) previous1).name;
                } else if (text.equals("def") || text.equals("readonly")) {
                    return;
                }
            }
            previous2 = previous1;
            previous1 = token;
        }
    }

    /**
     * eexec と charstring の暗号を戻す。
     *
     * @param skip 先頭の、捨てるバイトの数
     */
    static byte[] decrypt(byte[] data, int key, int skip, boolean allowHex) {
        byte[] input = data;
        if (allowHex && isHex(data)) input = hexDecode(data);
        int r = key;
        final byte[] out = new byte[Math.max(0, input.length - skip)];
        for (int i = 0; i < input.length; i++) {
            final int cipher = input[i] & 0xFF;
            final int plain = cipher ^ (r >> 8);
            r = ((cipher + r) * 52845 + 22719) & 0xFFFF;
            if (i >= skip) out[i - skip] = (byte) plain;
        }
        return out;
    }

    // 先頭の 4 バイトがすべて 16 進数の数字なら、16 進数で書かれた形
    private static boolean isHex(byte[] data) {
        if (data.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            final int b = data[i];
            final boolean hex = (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static byte[] hexDecode(byte[] data) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length / 2);
        int high = -1;
        for (byte b : data) {
            final int v = b >= '0' && b <= '9' ? b - '0' : b >= 'a' && b <= 'f' ? b - 'a' + 10 : b >= 'A' && b <= 'F' ? b - 'A' + 10 : -1;
            if (v < 0) continue;
            if (high < 0) {
                high = v;
            } else {
                out.write(high * 16 + v);
                high = -1;
            }
        }
        return out.toByteArray();
    }

    // 暗号を戻した部分から、/lenIV、/Subrs、/CharStrings を読む
    private void readPrivate(byte[] data) {
        final Lexer lexer = new Lexer(data);
        int lenIV = 4;
        while (true) {
            final Object token = lexer.next();
            if (token == null) break;
            if (!(token instanceof PdfName)) continue;
            final String key = ((PdfName) token).name;
            if (key.equals("lenIV")) {
                final Object value = lexer.next();
                if (value instanceof Integer) lenIV = (Integer) value;
            } else if (key.equals("Subrs")) {
                readSubrs(lexer, data, lenIV);
            } else if (key.equals("CharStrings")) {
                readCharStrings(lexer, data, lenIV);
                break;
            }
        }
        // .notdef をグリフ 0 にする (CharStrings の途中にあっても。グリフ 0 は、ない文字に使う)
        if (!charStrings.containsKey(".notdef")) charStrings.put(".notdef", new byte[0]);
        glyphNames.remove(".notdef");
        glyphNames.add(0, ".notdef");
        for (int i = 0; i < glyphNames.size(); i++) {
            if (!nameToGid.containsKey(glyphNames.get(i))) nameToGid.put(glyphNames.get(i), i);
        }
    }

    // 「長さ RD 中身」の中身を読む。RD の後ろの 1 バイト (空白) の次からが中身
    private static byte[] binary(Lexer lexer, byte[] data, int length, int lenIV) {
        final Object rd = lexer.next();
        if (!(rd instanceof Keyword)) return null;
        final int start = lexer.position() + 1;
        if (length < 0 || start + (long) length > data.length) return null;
        lexer.seek(start + length);
        final byte[] raw = java.util.Arrays.copyOfRange(data, start, start + length);
        return lenIV < 0 ? raw : decrypt(raw, 4330, lenIV, false);
    }

    private void readSubrs(Lexer lexer, byte[] data, int lenIV) {
        final Object count = lexer.next();
        if (!(count instanceof Integer)) return;
        final int n = Math.min((Integer) count, 65536);
        for (int i = 0; i < n; i++) subrs.add(null);
        // 「dup 番号 長さ RD 中身 NP」の並び
        for (int guard = 0; guard < 200000; guard++) {
            final int before = lexer.position();
            final Object token = lexer.next();
            if (token == null) return;
            if (token instanceof Keyword && ((Keyword) token).is("dup")) {
                final Object index = lexer.next();
                final Object length = lexer.next();
                if (!(index instanceof Integer) || !(length instanceof Integer)) return;
                final byte[] code = binary(lexer, data, (Integer) length, lenIV);
                if (code == null) return;
                final int i = (Integer) index;
                if (i >= 0 && i < subrs.size()) subrs.set(i, code);
            } else if (token instanceof Keyword && (((Keyword) token).is("ND") || ((Keyword) token).is("|-")
                    || ((Keyword) token).is("def") || ((Keyword) token).is("readonly"))) {
                return;
            } else if (token instanceof PdfName) {
                // 次の項目 (/CharStrings など) が来たら終わり
                lexer.seek(before);
                return;
            }
        }
    }

    private void readCharStrings(Lexer lexer, byte[] data, int lenIV) {
        // 「/名前 長さ RD 中身 ND」の並び。end で終わる
        for (int guard = 0; guard < 200000; guard++) {
            final Object token = lexer.next();
            if (token == null) return;
            if (token instanceof Keyword && ((Keyword) token).is("end")) return;
            if (!(token instanceof PdfName)) continue;
            final Object length = lexer.next();
            if (!(length instanceof Integer)) continue;
            final byte[] code = binary(lexer, data, (Integer) length, lenIV);
            if (code == null) return;
            final String glyph = ((PdfName) token).name;
            if (!charStrings.containsKey(glyph)) glyphNames.add(glyph);
            charStrings.put(glyph, code);
        }
    }

    @Override
    public String postScriptName() {
        return name;
    }

    public String[] builtInEncoding() {
        return encoding.clone();
    }

    public double[] fontMatrix() {
        return matrix.clone();
    }

    @Override
    public int glyphCount() {
        return glyphNames.size();
    }

    @Override
    public int gidForName(String glyph) {
        final Integer gid = nameToGid.get(glyph);
        return gid == null ? -1 : gid;
    }

    @Override
    public String nameForGid(int gid) {
        return gid >= 0 && gid < glyphNames.size() ? glyphNames.get(gid) : null;
    }

    @Override
    public GeneralPath path(int gid) {
        final Glyph glyph = run(nameForGid(gid), 0);
        glyph.path.transform(new AffineTransform(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]));
        return glyph.path;
    }

    @Override
    public double advance(int gid) {
        return run(nameForGid(gid), 0).width * matrix[0];
    }

    private static final class Glyph {
        final GeneralPath path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
        double width;
    }

    // 実行中の状態
    private static final class State {
        final double[] stack = new double[MAX_STACK];
        int size;
        // callothersubr で渡された値 (pop で取り出す)
        final double[] other = new double[MAX_STACK];
        int otherSize;
        final Glyph glyph = new Glyph();
        double x, y;
        double sideBearingX, sideBearingY;
        boolean open;
        boolean flex;
        final List<double[]> flexPoints = new ArrayList<>();
        int operations;
        boolean ended;

        void push(double value) {
            if (size >= MAX_STACK) throw new FontBytes.FontFormatError("Stack overflow");
            stack[size++] = value;
        }

        void moveTo(double dx, double dy) {
            if (flex) {
                flexPoints.add(new double[]{dx, dy});
                return;
            }
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
            final double x1 = x + dx1, y1 = y + dy1, x2 = x1 + dx2, y2 = y1 + dy2;
            x = x2 + dx3;
            y = y2 + dy3;
            glyph.path.curveTo(x1, y1, x2, y2, x, y);
        }

        void close() {
            if (open) glyph.path.closePath();
            open = false;
        }
    }

    private Glyph run(String glyphName, int seacDepth) {
        final State state = new State();
        final byte[] code = glyphName == null ? null : charStrings.get(glyphName);
        if (code != null) {
            try {
                execute(code, state, 0, seacDepth);
            } catch (FontBytes.FontFormatError | ArrayIndexOutOfBoundsException e) {
                // 壊れた手続き: そこまでの輪郭を使う
            }
        }
        state.close();
        return state.glyph;
    }

    private void execute(byte[] code, State s, int depth, int seacDepth) {
        if (depth > MAX_SUBR_DEPTH) throw new FontBytes.FontFormatError("Subroutines are nested too deeply");
        int p = 0;
        while (p < code.length && !s.ended) {
            if (++s.operations > MAX_OPERATIONS) throw new FontBytes.FontFormatError("Too many operations");
            final int b0 = code[p] & 0xFF;
            if (b0 >= 32) {
                if (b0 <= 246) {
                    s.push(b0 - 139);
                    p++;
                } else if (b0 <= 250) {
                    s.push((b0 - 247) * 256 + (code[p + 1] & 0xFF) + 108);
                    p += 2;
                } else if (b0 <= 254) {
                    s.push(-(b0 - 251) * 256 - (code[p + 1] & 0xFF) - 108);
                    p += 2;
                } else {
                    s.push(((code[p + 1] & 0xFF) << 24) | ((code[p + 2] & 0xFF) << 16) | ((code[p + 3] & 0xFF) << 8) | (code[p + 4] & 0xFF));
                    p += 5;
                }
                continue;
            }
            p++;
            final double[] st = s.stack;
            switch (b0) {
                case 13: // hsbw
                    if (s.size >= 2) {
                        s.sideBearingX = st[0];
                        s.sideBearingY = 0;
                        s.glyph.width = st[1];
                        s.x = st[0];
                        s.y = 0;
                    }
                    s.size = 0;
                    break;
                case 9: // closepath
                    s.close();
                    s.size = 0;
                    break;
                case 21: // rmoveto
                    if (s.size >= 2) s.moveTo(st[0], st[1]);
                    s.size = 0;
                    break;
                case 22: // hmoveto
                    if (s.size >= 1) s.moveTo(st[0], 0);
                    s.size = 0;
                    break;
                case 4: // vmoveto
                    if (s.size >= 1) s.moveTo(0, st[0]);
                    s.size = 0;
                    break;
                case 5: // rlineto
                    if (s.size >= 2) s.lineTo(st[0], st[1]);
                    s.size = 0;
                    break;
                case 6: // hlineto
                    if (s.size >= 1) s.lineTo(st[0], 0);
                    s.size = 0;
                    break;
                case 7: // vlineto
                    if (s.size >= 1) s.lineTo(0, st[0]);
                    s.size = 0;
                    break;
                case 8: // rrcurveto
                    if (s.size >= 6) s.curveTo(st[0], st[1], st[2], st[3], st[4], st[5]);
                    s.size = 0;
                    break;
                case 30: // vhcurveto
                    if (s.size >= 4) s.curveTo(0, st[0], st[1], st[2], st[3], 0);
                    s.size = 0;
                    break;
                case 31: // hvcurveto
                    if (s.size >= 4) s.curveTo(st[0], 0, st[1], st[2], 0, st[3]);
                    s.size = 0;
                    break;
                case 10: { // callsubr
                    if (s.size == 0) throw new FontBytes.FontFormatError("Stack underflow");
                    final int number = (int) st[--s.size];
                    final byte[] subr = number >= 0 && number < subrs.size() ? subrs.get(number) : null;
                    if (subr == null) throw new FontBytes.FontFormatError("Missing subroutine");
                    execute(subr, s, depth + 1, seacDepth);
                    break;
                }
                case 11: // return
                    return;
                case 14: // endchar
                    s.close();
                    s.ended = true;
                    s.size = 0;
                    return;
                case 1: // hstem
                case 3: // vstem
                    s.size = 0;
                    break;
                case 12:
                    p = escape(code, p, s, depth, seacDepth);
                    break;
                default:
                    s.size = 0;
                    break;
            }
        }
    }

    private int escape(byte[] code, int p, State s, int depth, int seacDepth) {
        if (p >= code.length) return p;
        final int op = code[p] & 0xFF;
        p++;
        final double[] st = s.stack;
        switch (op) {
            case 7: // sbw
                if (s.size >= 4) {
                    s.sideBearingX = st[0];
                    s.sideBearingY = st[1];
                    s.glyph.width = st[2];
                    s.x = st[0];
                    s.y = st[1];
                }
                s.size = 0;
                break;
            case 6: // seac
                if (s.size >= 5) seac(s, st[0], st[1], st[2], (int) st[3], (int) st[4], seacDepth);
                s.ended = true;
                s.size = 0;
                break;
            case 12: { // div
                if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
                final double b = st[--s.size];
                st[s.size - 1] = b == 0 ? 0 : st[s.size - 1] / b;
                break;
            }
            case 16: { // callothersubr
                if (s.size < 2) throw new FontBytes.FontFormatError("Stack underflow");
                final int number = (int) st[--s.size];
                final int count = (int) st[--s.size];
                if (count < 0 || count > s.size) throw new FontBytes.FontFormatError("Bad callothersubr");
                // 引数は、pop で逆の順に取り出せるように積む
                s.otherSize = 0;
                for (int i = 0; i < count; i++) s.other[s.otherSize++] = st[--s.size];
                otherSubr(s, number);
                break;
            }
            case 17: // pop
                if (s.otherSize > 0) {
                    s.push(s.other[--s.otherSize]);
                } else {
                    s.push(0);
                }
                break;
            case 33: // setcurrentpoint
                if (s.size >= 2) {
                    s.x = st[0];
                    s.y = st[1];
                }
                s.size = 0;
                break;
            default:
                // dotsection, vstem3, hstem3 など (ヒント) は捨てる
                s.size = 0;
                break;
        }
        return p;
    }

    // OtherSubrs: 0 (flex の終わり)、1 (flex の始まり)、2 (flex の点)、3 (ヒントの差し替え)
    private static void otherSubr(State s, int number) {
        if (number == 1) {
            s.flex = true;
            s.flexPoints.clear();
        } else if (number == 0) {
            s.flex = false;
            final List<double[]> pts = s.flexPoints;
            if (pts.size() >= 7) {
                // 最初の点は基準点 (今の点から)、2 つ目は基準点から、残りは 1 つ前の点からの差
                final double refX = pts.get(0)[0], refY = pts.get(0)[1];
                final double firstX = refX + pts.get(1)[0], firstY = refY + pts.get(1)[1];
                s.curveTo(firstX, firstY, pts.get(2)[0], pts.get(2)[1], pts.get(3)[0], pts.get(3)[1]);
                s.curveTo(pts.get(4)[0], pts.get(4)[1], pts.get(5)[0], pts.get(5)[1], pts.get(6)[0], pts.get(6)[1]);
            }
            pts.clear();
            // 続く pop pop setcurrentpoint のために、終わりの点を返す (引数の順を保つ)
            s.otherSize = 0;
            s.other[s.otherSize++] = s.y;
            s.other[s.otherSize++] = s.x;
        } else if (number == 3) {
            // ヒントの差し替え: 渡されたサブルーチンの番号をそのまま返す (other に残っている)
            if (s.otherSize == 0) s.other[s.otherSize++] = 3;
        }
        // 2 とそのほかは、引数をそのまま返す
    }

    private void seac(State s, double asb, double adx, double ady, int baseCode, int accentCode, int seacDepth) {
        if (seacDepth > 0) return;
        final String[] standard = FontData.encoding("Standard");
        if (standard == null || baseCode < 0 || baseCode > 255 || accentCode < 0 || accentCode > 255) return;
        s.close();
        final Glyph base = run(standard[baseCode], seacDepth + 1);
        s.glyph.path.append(base.path, false);
        final Glyph accent = run(standard[accentCode], seacDepth + 1);
        final GeneralPath moved = (GeneralPath) accent.path.clone();
        moved.transform(AffineTransform.getTranslateInstance(s.sideBearingX + adx - asb, s.sideBearingY + ady));
        s.glyph.path.append(moved, false);
    }
}
