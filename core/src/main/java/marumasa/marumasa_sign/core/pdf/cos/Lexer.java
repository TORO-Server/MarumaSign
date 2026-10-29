package marumasa.marumasa_sign.core.pdf.cos;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * PDF のバイト列を語 (トークン) に分ける。ファイルの本体とページの内容 (content stream) の両方に使う。
 * <p>
 * 返す語: 数 (Integer / Double)、Boolean、PdfName、PdfString、Keyword (区切りと命令)、終わりなら null。
 * PDF の null は {@link #NULL} で返す (終わりと区別するため)。
 * 壊れたファイルでも止まらないよう、どの入力でも必ず先に進む (同じ位置で同じ語を返し続けない)。
 */
public final class Lexer {

    public static final Keyword NULL = new Keyword("null");

    // 名前と命令の語の長さの上限 (バイト)。それより後ろは読み進めるが、覚えない。仕様の実装上の限界は名前が 127 バイトで、
    // 命令は数文字。100 MB を超える 1 つの語を圧縮して入れると、Java 8 では文字列 (1 文字 2 バイト) と作業用の領域で
    // 500 MB を超えるメモリを使っていた
    private static final int MAX_NAME = 65536;
    private static final int MAX_KEYWORD = 256;

    private static final boolean[] WHITESPACE = new boolean[256];
    private static final boolean[] DELIMITER = new boolean[256];

    static {
        for (char c : new char[]{0, 9, 10, 12, 13, 32}) WHITESPACE[c] = true;
        for (char c : "()<>[]{}/%".toCharArray()) DELIMITER[c] = true;
    }

    private final byte[] data;
    private final int end;
    private int position;
    // よく出る命令は同じものを使い回す
    private final Map<String, Keyword> keywords = new HashMap<>();

    public Lexer(byte[] data) {
        this(data, 0, data.length);
    }

    public Lexer(byte[] data, int start, int end) {
        this.data = data;
        this.position = Math.max(0, start);
        this.end = Math.min(end, data.length);
    }

    public static boolean isWhitespace(int b) {
        return WHITESPACE[b & 0xFF];
    }

    public static boolean isDelimiter(int b) {
        return DELIMITER[b & 0xFF];
    }

    public static boolean isRegular(int b) {
        return !WHITESPACE[b & 0xFF] && !DELIMITER[b & 0xFF];
    }

    public byte[] data() {
        return data;
    }

    public int position() {
        return position;
    }

    public void seek(int position) {
        this.position = Math.max(0, Math.min(end, position));
    }

    public int end() {
        return end;
    }

    // 空白とコメントを飛ばす
    public void skipWhitespace() {
        while (position < end) {
            final int b = data[position] & 0xFF;
            if (WHITESPACE[b]) {
                position++;
            } else if (b == '%') {
                while (position < end && data[position] != '\n' && data[position] != '\r') position++;
            } else {
                break;
            }
        }
    }

    /**
     * 次の語。終わりなら null。
     */
    public Object next() {
        while (true) {
            skipWhitespace();
            if (position >= end) return null;
            final int b = data[position] & 0xFF;
            switch (b) {
                case '[':
                    position++;
                    return Keyword.ARRAY_START;
                case ']':
                    position++;
                    return Keyword.ARRAY_END;
                case '{':
                    position++;
                    return Keyword.PROC_START;
                case '}':
                    position++;
                    return Keyword.PROC_END;
                case '(':
                    position++;
                    return literalString();
                case '/':
                    position++;
                    return name();
                case '<':
                    if (position + 1 < end && data[position + 1] == '<') {
                        position += 2;
                        return Keyword.DICT_START;
                    }
                    position++;
                    return hexString();
                case '>':
                    if (position + 1 < end && data[position + 1] == '>') {
                        position += 2;
                        return Keyword.DICT_END;
                    }
                    // 対応のない '>' は読み飛ばす
                    position++;
                    continue;
                case ')':
                    position++;
                    continue;
                default:
                    break;
            }
            if (b == '+' || b == '-' || b == '.' || (b >= '0' && b <= '9')) {
                final Object number = number();
                if (number != null) return number;
            }
            final int start = position;
            while (position < end && isRegular(data[position])) position++;
            if (position == start) {
                // ここには来ないはずだが、念のため必ず進む
                position++;
                continue;
            }
            final String text = new String(data, start, Math.min(position - start, MAX_KEYWORD), StandardCharsets.ISO_8859_1);
            if (text.equals("true")) return Boolean.TRUE;
            if (text.equals("false")) return Boolean.FALSE;
            if (text.equals("null")) return NULL;
            Keyword keyword = keywords.get(text);
            if (keyword == null) {
                keyword = new Keyword(text);
                if (keywords.size() < 256) keywords.put(text, keyword);
            }
            return keyword;
        }
    }

    /**
     * 数。符号が重なったもの (--5) や、数字のない符号だけのもの (-) も、ほかの PDF の読み手と同じく読む。
     * 数として読めなければ null を返し、位置は動かさない。
     */
    private Object number() {
        final int start = position;
        int p = position;
        boolean negative = false;
        while (p < end && (data[p] == '+' || data[p] == '-')) {
            if (data[p] == '-') negative = true;
            p++;
        }
        long integer = 0;
        boolean overflow = false;
        int digits = 0;
        double value = 0;
        while (p < end && data[p] >= '0' && data[p] <= '9') {
            final int digit = data[p] - '0';
            if (integer > (Long.MAX_VALUE - digit) / 10) overflow = true;
            if (!overflow) integer = integer * 10 + digit;
            value = value * 10 + digit;
            digits++;
            p++;
        }
        boolean real = false;
        if (p < end && data[p] == '.') {
            real = true;
            p++;
            double scale = 0.1;
            while (p < end && data[p] >= '0' && data[p] <= '9') {
                value += (data[p] - '0') * scale;
                scale /= 10;
                digits++;
                p++;
            }
        }
        if (digits == 0) {
            // 符号や点だけ。続きが普通の文字なら命令の一部 (ありえないが) なので数ではない
            if (p < end && isRegular(data[p]) && data[p] != '.' && data[p] != '+' && data[p] != '-') return null;
            // "-" や "." だけのものは 0 として読む
            position = Math.max(p, start + 1);
            return 0;
        }
        // 指数 (1e-05)。仕様にはないが、書き出すソフトがある
        if (p + 1 < end && (data[p] == 'e' || data[p] == 'E')) {
            int q = p + 1;
            boolean negativeExponent = false;
            if (data[q] == '+' || data[q] == '-') {
                negativeExponent = data[q] == '-';
                q++;
            }
            if (q < end && data[q] >= '0' && data[q] <= '9') {
                int exponent = 0;
                while (q < end && data[q] >= '0' && data[q] <= '9') {
                    if (exponent < 1000) exponent = exponent * 10 + (data[q] - '0');
                    q++;
                }
                value *= Math.pow(10, negativeExponent ? -exponent : exponent);
                real = true;
                p = q;
            }
        }
        // 2 つ目の点や、数のすぐ後ろの符号 (0.00-1 など) は、この数の一部として読み捨てる
        while (p < end && (data[p] == '.' || data[p] == '-' || data[p] == '+' || (data[p] >= '0' && data[p] <= '9'))) p++;
        position = p;
        if (!real && !overflow && integer <= Integer.MAX_VALUE) {
            return negative ? (int) -integer : (int) integer;
        }
        return negative ? -value : value;
    }

    private PdfName name() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        while (position < end && isRegular(data[position])) {
            final int b = data[position] & 0xFF;
            final boolean keep = bytes.size() < MAX_NAME;
            if (b == '#' && position + 2 < end && hex(data[position + 1]) >= 0 && hex(data[position + 2]) >= 0) {
                if (keep) bytes.write(hex(data[position + 1]) * 16 + hex(data[position + 2]));
                position += 3;
            } else {
                if (keep) bytes.write(b);
                position++;
            }
        }
        return new PdfName(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1));
    }

    private static int hex(int b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }

    private PdfString literalString() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        int depth = 1;
        while (position < end) {
            int b = data[position++] & 0xFF;
            if (b == '(') {
                depth++;
            } else if (b == ')') {
                if (--depth == 0) break;
            } else if (b == '\\') {
                if (position >= end) break;
                b = data[position++] & 0xFF;
                switch (b) {
                    case 'n':
                        b = '\n';
                        break;
                    case 'r':
                        b = '\r';
                        break;
                    case 't':
                        b = '\t';
                        break;
                    case 'b':
                        b = '\b';
                        break;
                    case 'f':
                        b = '\f';
                        break;
                    case '\r':
                        // 行の続き: 改行を読み捨てる
                        if (position < end && data[position] == '\n') position++;
                        continue;
                    case '\n':
                        continue;
                    default:
                        if (b >= '0' && b <= '7') {
                            int value = b - '0';
                            for (int i = 0; i < 2 && position < end && data[position] >= '0' && data[position] <= '7'; i++) {
                                value = value * 8 + (data[position++] - '0');
                            }
                            b = value & 0xFF;
                        }
                        // それ以外 (\( \) \\ と、知らない文字) は、その文字そのもの
                        break;
                }
            } else if (b == '\r') {
                // 文字列の中の改行は LF にそろえる
                if (position < end && data[position] == '\n') position++;
                b = '\n';
            }
            bytes.write(b);
        }
        return new PdfString(bytes.toByteArray());
    }

    private PdfString hexString() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        int high = -1;
        while (position < end) {
            final int b = data[position++] & 0xFF;
            if (b == '>') break;
            final int value = hex(b);
            // 16 進数でない文字 (空白など) は飛ばす
            if (value < 0) continue;
            if (high < 0) {
                high = value;
            } else {
                bytes.write(high * 16 + value);
                high = -1;
            }
        }
        // 桁が奇数なら、最後の桁の後ろに 0 があるものとする
        if (high >= 0) bytes.write(high * 16);
        return new PdfString(bytes.toByteArray());
    }

    /**
     * 次の語がこの命令か (位置は動かさない)。
     */
    public boolean peekKeyword(String text) {
        final int saved = position;
        final Object token = next();
        position = saved;
        return token instanceof Keyword && ((Keyword) token).is(text);
    }
}
