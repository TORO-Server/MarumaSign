package marumasa.marumasa_sign.core.pdf.cos;

/**
 * PDF の名前 (/Type など)。文字列 (PdfString) と区別するために包む。
 * 名前の中身は、#xx を戻したバイト列を ISO-8859-1 で文字にしたもの (1 バイトが 1 文字)。
 */
public final class PdfName {

    public final String name;

    public PdfName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfName && ((PdfName) other).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "/" + name;
    }
}
