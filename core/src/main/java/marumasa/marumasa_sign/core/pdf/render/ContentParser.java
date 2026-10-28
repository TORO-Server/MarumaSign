package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.pdf.cos.Keyword;
import marumasa.marumasa_sign.core.pdf.cos.Lexer;
import marumasa.marumasa_sign.core.pdf.cos.ObjectParser;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;
import marumasa.marumasa_sign.core.pdf.cos.Resolver;
import marumasa.marumasa_sign.core.pdf.filter.Filters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ページの内容 (content stream) を、命令と引数の組に分ける。
 * インライン画像 (BI ... ID データ EI) は、データの長さを求めて 1 つの画像のストリームにする。
 */
final class ContentParser {

    // 1 つの命令の引数の数の上限 (これより多いものは壊れたデータなので捨てる)
    private static final int MAX_OPERANDS = 1024;

    private final Lexer lexer;
    private final ObjectParser parser;
    private final Resolver resolver;
    private final List<Object> operands = new ArrayList<>();
    private String operator;
    private PdfStream inlineImage;

    ContentParser(byte[] data, Resolver resolver) {
        this.lexer = new Lexer(data);
        this.resolver = resolver;
        this.parser = new ObjectParser(lexer, resolver, false);
    }

    /**
     * 次の命令に進む。終わりなら false。
     */
    boolean next() {
        operands.clear();
        inlineImage = null;
        while (true) {
            final Object token = lexer.next();
            if (token == null) return false;
            Object value;
            try {
                value = parser.value(token, 0);
            } catch (IOException e) {
                // 深すぎる入れ子など: この命令は捨てて続ける
                operands.clear();
                continue;
            }
            if (value instanceof Keyword && value != Keyword.ARRAY_END && value != Keyword.DICT_END
                    && value != Keyword.PROC_START && value != Keyword.PROC_END) {
                operator = ((Keyword) value).text;
                if (operator.equals("BI")) {
                    inlineImage = readInlineImage();
                    operator = "BI";
                }
                return true;
            }
            if (value instanceof Keyword) continue;
            if (operands.size() >= MAX_OPERANDS) operands.clear();
            operands.add(value);
        }
    }

    String operator() {
        return operator;
    }

    List<Object> operands() {
        return operands;
    }

    PdfStream inlineImage() {
        return inlineImage;
    }

    // BI の後ろ: 鍵と値の並び、ID、データ、EI
    private PdfStream readInlineImage() {
        final PdfDict dict = new PdfDict(resolver);
        while (true) {
            final Object token = lexer.next();
            if (token == null) return null;
            if (token instanceof Keyword && ((Keyword) token).is("ID")) break;
            if (token instanceof PdfName) {
                final Object valueToken = lexer.next();
                if (valueToken == null) return null;
                try {
                    final Object value = parser.value(valueToken, 0);
                    if (!(value instanceof Keyword)) dict.put(((PdfName) token).name, value);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        // ID の後ろの空白 1 つの次からがデータ
        final byte[] data = lexer.data();
        int start = lexer.position();
        if (start < data.length && Lexer.isWhitespace(data[start])) start++;
        final int end = findEnd(dict, data, start);
        final byte[] bytes = java.util.Arrays.copyOfRange(data, start, Math.max(start, end));
        // EI の後ろへ進む
        int after = end;
        while (after < data.length && Lexer.isWhitespace(data[after])) after++;
        lexer.seek(after + 2 <= data.length && data[after] == 'E' && data[after + 1] == 'I' ? after + 2 : after);
        return new PdfStream(dict, bytes);
    }

    /**
     * インライン画像のデータの終わり (EI の前の空白の位置)。
     * 圧縮していない画像は大きさから長さが分かる。圧縮したものは、「空白 EI 空白」で、後ろが命令らしいものを探す。
     */
    private static int findEnd(PdfDict dict, byte[] data, int start) {
        final List<Filters.Step> steps = Filters.steps(dict);
        if (steps.isEmpty()) {
            final long length = uncompressedLength(dict);
            if (length >= 0 && start + length <= data.length) {
                // 長さの直後に EI があれば、それで決まり
                int p = (int) (start + length);
                while (p < data.length && Lexer.isWhitespace(data[p])) p++;
                if (p + 1 < data.length && data[p] == 'E' && data[p + 1] == 'I') return (int) (start + length);
            }
        }
        for (int i = start; i + 1 < data.length; i++) {
            if (data[i] != 'E' || data[i + 1] != 'I') continue;
            final boolean before = i == start || Lexer.isWhitespace(data[i - 1]);
            final boolean after = i + 2 >= data.length || Lexer.isWhitespace(data[i + 2]) || Lexer.isDelimiter(data[i + 2]);
            if (!before || !after) continue;
            if (followedByOperator(data, i + 2)) return i > start && Lexer.isWhitespace(data[i - 1]) ? i - 1 : i;
        }
        return data.length;
    }

    // EI の後ろが、内容の命令として読める文字か (画像のデータの中の偶然の "EI" を除くため)
    private static boolean followedByOperator(byte[] data, int position) {
        final int end = Math.min(data.length, position + 64);
        for (int i = position; i < end; i++) {
            final int b = data[i] & 0xFF;
            if (b > 0x7E || (b < 0x20 && !Lexer.isWhitespace(b))) return false;
        }
        return true;
    }

    // 圧縮していない画像のデータの長さ。分からなければ -1
    private static long uncompressedLength(PdfDict dict) {
        final Object w = dict.get("Width", "W");
        final Object h = dict.get("Height", "H");
        if (!(w instanceof Number) || !(h instanceof Number)) return -1;
        final long width = ((Number) w).longValue(), height = ((Number) h).longValue();
        final Object mask = dict.get("ImageMask", "IM");
        final boolean imageMask = Boolean.TRUE.equals(mask);
        final Object bpcValue = dict.get("BitsPerComponent", "BPC");
        final int bpc = imageMask ? 1 : bpcValue instanceof Number ? ((Number) bpcValue).intValue() : 8;
        int components = 1;
        final Object cs = dict.get("ColorSpace", "CS");
        if (!imageMask && cs instanceof PdfName) {
            final String name = ((PdfName) cs).name;
            if (name.equals("DeviceRGB") || name.equals("RGB")) components = 3;
            else if (name.equals("DeviceCMYK") || name.equals("CMYK")) components = 4;
            else if (!name.equals("DeviceGray") && !name.equals("G") && !name.equals("Indexed") && !name.equals("I")) return -1;
        } else if (!imageMask && cs != null) {
            return -1;
        }
        if (width <= 0 || height <= 0 || bpc <= 0 || bpc > 16) return -1;
        return (width * components * bpc + 7) / 8 * height;
    }
}
