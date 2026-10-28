package marumasa.marumasa_sign.core.pdf.cos;

import java.io.IOException;

/**
 * 語の並びから PDF の値 (配列や辞書を含む) を組み立てる。
 * 入れ子の深さに上限を設け、深く入れ子にした配列で Java のスタックを使い切らないようにする
 * (PDFBox 2.0 は深さを数えないので、[[[[... が何万も続くファイルで StackOverflowError になる)。
 */
public final class ObjectParser {

    // 配列と辞書の入れ子の深さの上限
    public static final int MAX_DEPTH = 256;
    // 1 つの配列・辞書の要素の数の上限 (巨大なファイルの読み間違いで、いつまでも読み続けないように)
    private static final int MAX_ITEMS = 1 << 22;

    /**
     * 文字列を復号するもの。暗号化されたファイルの、間接オブジェクトの中の文字列に使う。
     */
    public interface StringDecryptor {
        byte[] decrypt(byte[] bytes);
    }

    private final Lexer lexer;
    private final Resolver resolver;
    private final boolean allowReferences;
    private StringDecryptor decryptor;

    public ObjectParser(Lexer lexer, Resolver resolver, boolean allowReferences) {
        this.lexer = lexer;
        this.resolver = resolver;
        this.allowReferences = allowReferences;
    }

    public Lexer lexer() {
        return lexer;
    }

    public void setDecryptor(StringDecryptor decryptor) {
        this.decryptor = decryptor;
    }

    /**
     * 次の値を読む。終わりなら IOException。命令 (Keyword) が来たらそれを返す。
     */
    public Object next() throws IOException {
        final Object token = lexer.next();
        if (token == null) throw new IOException("Unexpected end of data");
        return value(token, 0);
    }

    /**
     * 語 token から始まる値を読む。命令などの値でない語は、そのまま返す。
     */
    public Object value(Object token, int depth) throws IOException {
        if (token == Lexer.NULL) return null;
        if (token instanceof Integer && allowReferences) return reference((Integer) token);
        if (token instanceof PdfString) {
            return decryptor == null ? token : new PdfString(decryptor.decrypt(((PdfString) token).bytes));
        }
        if (token == Keyword.ARRAY_START) return array(depth + 1);
        if (token == Keyword.DICT_START) return dict(depth + 1);
        return token;
    }

    // 「番号 世代 R」なら参照、そうでなければ数
    private Object reference(Integer number) {
        final int saved = lexer.position();
        final Object generation = lexer.next();
        if (generation instanceof Integer) {
            final Object r = lexer.next();
            if (r instanceof Keyword && ((Keyword) r).is("R")) {
                if (number < 0 || (Integer) generation < 0) return null;
                return new PdfRef(number, (Integer) generation);
            }
        }
        lexer.seek(saved);
        return number;
    }

    private PdfArray array(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Objects are nested too deeply");
        final PdfArray array = new PdfArray(resolver);
        while (true) {
            final int before = lexer.position();
            final Object token = lexer.next();
            if (token == null || token == Keyword.ARRAY_END) return array;
            if (token == Keyword.DICT_END) {
                // 閉じ忘れた配列: 辞書の終わりは外側のものなので戻す
                lexer.seek(before);
                return array;
            }
            if (token instanceof Keyword && isObjectEnd((Keyword) token)) {
                lexer.seek(before);
                return array;
            }
            if (array.size() >= MAX_ITEMS) throw new IOException("Array is too large");
            final Object value = value(token, depth);
            // 配列の中の知らない語は、壊れたファイルの読み間違いなので null として残す (位置を保つ)
            array.add(value instanceof Keyword ? null : value);
        }
    }

    private PdfDict dict(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Objects are nested too deeply");
        final PdfDict dict = new PdfDict(resolver);
        while (true) {
            final int before = lexer.position();
            final Object token = lexer.next();
            if (token == null || token == Keyword.DICT_END) return dict;
            if (token instanceof PdfName) {
                if (dict.size() >= MAX_ITEMS) throw new IOException("Dictionary is too large");
                final int valueStart = lexer.position();
                final Object next = lexer.next();
                if (next == null || next == Keyword.DICT_END) return dict;
                if (next instanceof Keyword && isObjectEnd((Keyword) next)) {
                    lexer.seek(valueStart);
                    return dict;
                }
                final Object value = value(next, depth);
                // 値が命令などの語なら、壊れた項目として読み捨てる
                if (value instanceof Keyword) continue;
                dict.put(((PdfName) token).name, value);
            } else if (token instanceof Keyword && isObjectEnd((Keyword) token)) {
                lexer.seek(before);
                return dict;
            }
            // 鍵でないものは読み飛ばす
        }
    }

    // 閉じられていない配列や辞書を、オブジェクトの終わりで打ち切るための語
    private static boolean isObjectEnd(Keyword keyword) {
        return keyword.is("endobj") || keyword.is("stream") || keyword.is("obj") || keyword.is("xref")
                || keyword.is("trailer") || keyword.is("startxref") || keyword.is("endstream");
    }
}
