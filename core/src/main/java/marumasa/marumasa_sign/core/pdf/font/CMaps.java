package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.cos.Keyword;
import marumasa.marumasa_sign.core.pdf.cos.Lexer;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * CMap を作る: PDF に埋め込まれたもの (文字で書かれた形) と、定義済みのもの (marumasa_sign/pdf/cmaps.bin.gz)。
 * <p>
 * 定義済みのものの形 (tools/pdfdata/gen_pdf_data.py が作る): "MSCM"、数 (2 バイト)、CMap ごとに
 * 名前 (2 バイトの長さ + UTF-8)、WMode (1)、Unicode への対応か (1)、usecmap の名前、
 * 範囲の数 (可変長) と {バイト数 (1)、下限、上限}、対応の数と {バイト数 (1)、始まりの差、範囲の長さ - 1、値の差}。
 * 差はジグザグ符号の可変長の整数。
 */
public final class CMaps {

    private static final Map<String, CMap> CACHE = new HashMap<>();
    private static byte[] data;
    private static Map<String, Integer> offsets;

    private CMaps() {
    }

    public static CMap identity(boolean vertical) {
        final CMap cmap = new CMap(vertical ? "Identity-V" : "Identity-H");
        cmap.addCodespace(2, 0, 0xFFFF);
        cmap.add(2, 0, 0xFFFF, 0);
        cmap.setWmode(vertical ? 1 : 0);
        return cmap;
    }

    /**
     * 定義済みの CMap。なければ null。
     */
    public static synchronized CMap predefined(String name) {
        return predefined(name, 0);
    }

    private static CMap predefined(String name, int depth) {
        if (name == null) return null;
        if (name.equals("Identity-H")) return identity(false);
        if (name.equals("Identity-V")) return identity(true);
        if (CACHE.containsKey(name)) return CACHE.get(name);
        CMap result = null;
        try {
            result = load(name, depth);
        } catch (IOException | RuntimeException e) {
            result = null;
        }
        CACHE.put(name, result);
        return result;
    }

    private static CMap load(String name, int depth) throws IOException {
        if (depth > CMap.MAX_USE_DEPTH) return null;
        if (data == null) {
            final InputStream input = CMaps.class.getResourceAsStream("/marumasa_sign/pdf/cmaps.bin.gz");
            if (input == null) return null;
            try (GZIPInputStream gzip = new GZIPInputStream(input)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                final byte[] buffer = new byte[65536];
                int n;
                while ((n = gzip.read(buffer)) > 0) out.write(buffer, 0, n);
                data = out.toByteArray();
            }
            offsets = new HashMap<>();
            final int[] p = {4};
            final int count = u16(p);
            for (int i = 0; i < count; i++) {
                final int start = p[0];
                final String cmapName = utf(p);
                offsets.put(cmapName, start);
                skipBody(p);
            }
        }
        final Integer start = offsets.get(name);
        if (start == null) return null;
        final int[] p = {start};
        final CMap cmap = new CMap(utf(p));
        cmap.setWmode(data[p[0]++]);
        p[0]++; // Unicode への対応か (使わない)
        final String use = utf(p);
        if (!use.isEmpty()) cmap.setParent(predefined(use, depth + 1));
        final int ranges = varint(p);
        for (int i = 0; i < ranges; i++) {
            final int length = data[p[0]++];
            cmap.addCodespace(length, varint(p), varint(p));
        }
        final int entries = varint(p);
        int previousCode = 0, previousValue = 0, previousLength = 0;
        for (int i = 0; i < entries; i++) {
            final int length = data[p[0]++];
            if (length != previousLength) {
                previousCode = 0;
                previousLength = length;
            }
            final int low = previousCode + zigzag(varint(p));
            final int high = low + varint(p);
            final int value = previousValue + zigzag(varint(p));
            cmap.add(length, low, high, value);
            previousCode = high;
            previousValue = value + (high - low);
        }
        return cmap;
    }

    private static void skipBody(int[] p) {
        p[0] += 2;
        utf(p);
        final int ranges = varint(p);
        for (int i = 0; i < ranges; i++) {
            p[0]++;
            varint(p);
            varint(p);
        }
        final int entries = varint(p);
        for (int i = 0; i < entries; i++) {
            p[0]++;
            varint(p);
            varint(p);
            varint(p);
        }
    }

    private static int u16(int[] p) {
        final int value = ((data[p[0]] & 0xFF) << 8) | (data[p[0] + 1] & 0xFF);
        p[0] += 2;
        return value;
    }

    private static String utf(int[] p) {
        final int length = u16(p);
        final String text = new String(data, p[0], length, StandardCharsets.UTF_8);
        p[0] += length;
        return text;
    }

    private static int varint(int[] p) {
        int value = 0;
        int shift = 0;
        while (true) {
            final int b = data[p[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) throw new IllegalStateException("Bad varint");
        }
    }

    private static int zigzag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    // ---------- 埋め込まれた CMap ----------

    /**
     * 文字で書かれた CMap を読む (Encoding の CMap と ToUnicode の両方)。
     *
     * @param unicode ToUnicode として読むか (bfchar / bfrange の値を Unicode にする)
     */
    public static CMap parse(byte[] text, boolean unicode) {
        return parse(text, unicode, 0);
    }

    private static CMap parse(byte[] text, boolean unicode, int depth) {
        final Lexer lexer = new Lexer(text);
        final CMap cmap = new CMap("embedded");
        final List<Object> operands = new ArrayList<>();
        for (int guard = 0; guard < 4 * CMap.MAX_ENTRIES; guard++) {
            final Object token = lexer.next();
            if (token == null) break;
            if (!(token instanceof Keyword)) {
                if (operands.size() > 64) operands.remove(0);
                operands.add(token);
                continue;
            }
            final String word = ((Keyword) token).text;
            switch (word) {
                case "usecmap":
                    if (!operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfName && depth < CMap.MAX_USE_DEPTH) {
                        cmap.setParent(predefined(((PdfName) operands.get(operands.size() - 1)).name));
                    }
                    break;
                case "def":
                    if (operands.size() >= 2 && operands.get(operands.size() - 2) instanceof PdfName
                            && ((PdfName) operands.get(operands.size() - 2)).name.equals("WMode")
                            && operands.get(operands.size() - 1) instanceof Integer) {
                        cmap.setWmode((Integer) operands.get(operands.size() - 1));
                    }
                    break;
                case "begincodespacerange":
                    while (true) {
                        final Object low = lexer.next();
                        if (!(low instanceof PdfString)) break;
                        final Object high = lexer.next();
                        if (!(high instanceof PdfString)) break;
                        cmap.addCodespace(((PdfString) low).bytes, ((PdfString) high).bytes);
                    }
                    break;
                case "begincidrange":
                case "beginbfrange":
                    readRanges(lexer, cmap, word.equals("beginbfrange") || unicode);
                    break;
                case "begincidchar":
                case "beginbfchar":
                    readChars(lexer, cmap, word.equals("beginbfchar") || unicode);
                    break;
                default:
                    break;
            }
            operands.clear();
        }
        return cmap;
    }

    private static int code(byte[] bytes) {
        int value = 0;
        for (int i = 0; i < bytes.length && i < 4; i++) value = (value << 8) | (bytes[i] & 0xFF);
        return value;
    }

    // Unicode の値 (UTF-16BE)。1 文字ならそのコードポイント、複数の文字なら -2
    private static int unicodeValue(byte[] bytes) {
        if (bytes.length == 1) return bytes[0] & 0xFF;
        final String text = new String(bytes, StandardCharsets.UTF_16BE);
        if (text.isEmpty()) return -1;
        return text.codePointCount(0, text.length()) == 1 ? text.codePointAt(0) : -2;
    }

    private static Object valueOf(Object token, boolean unicode) {
        if (token instanceof Integer) return token;
        if (token instanceof PdfString) {
            final byte[] bytes = ((PdfString) token).bytes;
            return unicode ? unicodeValue(bytes) : code(bytes);
        }
        if (token instanceof PdfName && unicode) {
            final String text = FontData.unicode(((PdfName) token).name, false);
            if (text == null || text.isEmpty()) return -1;
            return text.codePointCount(0, text.length()) == 1 ? text.codePointAt(0) : -2;
        }
        return null;
    }

    private static void readRanges(Lexer lexer, CMap cmap, boolean unicode) {
        while (true) {
            final Object low = lexer.next();
            if (!(low instanceof PdfString)) return;
            final Object high = lexer.next();
            if (!(high instanceof PdfString)) return;
            final byte[] lowBytes = ((PdfString) low).bytes;
            final int length = lowBytes.length;
            final int start = code(lowBytes), end = code(((PdfString) high).bytes);
            final Object target = lexer.next();
            if (target == Keyword.ARRAY_START) {
                // bfrange の値の並び: コードごとに別の値
                int code = start;
                while (true) {
                    final Object item = lexer.next();
                    if (item == null || item == Keyword.ARRAY_END) break;
                    final Object value = valueOf(item, unicode);
                    if (value instanceof Integer && code <= end) cmap.add(length, code, code, (Integer) value);
                    code++;
                }
                continue;
            }
            final Object value = valueOf(target, unicode);
            if (!(value instanceof Integer)) return;
            if (unicode && target instanceof PdfString && ((PdfString) target).bytes.length > 2 && (Integer) value == -2) {
                // 複数の文字への範囲は、文字ごとに分けて書く必要があるが、最初の値だけ使う
                cmap.add(length, start, end, -2);
            } else {
                cmap.add(length, start, end, (Integer) value);
            }
        }
    }

    private static void readChars(Lexer lexer, CMap cmap, boolean unicode) {
        while (true) {
            final Object source = lexer.next();
            if (!(source instanceof PdfString)) return;
            final Object target = lexer.next();
            final Object value = valueOf(target, unicode);
            if (!(value instanceof Integer)) return;
            final byte[] bytes = ((PdfString) source).bytes;
            final int code = code(bytes);
            cmap.add(bytes.length, code, code, (Integer) value);
        }
    }
}
