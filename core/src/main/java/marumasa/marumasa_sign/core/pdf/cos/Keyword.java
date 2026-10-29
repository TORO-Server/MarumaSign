package marumasa.marumasa_sign.core.pdf.cos;

/**
 * 名前でも文字列でも数でもない語 (obj, R, 内容の命令など) と、区切り ([ ] &lt;&lt; &gt;&gt; { })。
 */
public final class Keyword {

    public static final Keyword ARRAY_START = new Keyword("[");
    public static final Keyword ARRAY_END = new Keyword("]");
    public static final Keyword DICT_START = new Keyword("<<");
    public static final Keyword DICT_END = new Keyword(">>");
    public static final Keyword PROC_START = new Keyword("{");
    public static final Keyword PROC_END = new Keyword("}");

    public final String text;

    public Keyword(String text) {
        this.text = text;
    }

    public boolean is(String expected) {
        return text.equals(expected);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Keyword && ((Keyword) other).text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
