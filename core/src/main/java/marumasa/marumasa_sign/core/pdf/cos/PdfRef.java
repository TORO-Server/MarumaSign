package marumasa.marumasa_sign.core.pdf.cos;

/**
 * 間接参照 (「12 0 R」)。中身は PdfDocument.resolve で取り出す。
 */
public final class PdfRef {

    public final int number;
    public final int generation;

    public PdfRef(int number, int generation) {
        this.number = number;
        this.generation = generation;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfRef && ((PdfRef) other).number == number && ((PdfRef) other).generation == generation;
    }

    @Override
    public int hashCode() {
        return number * 31 + generation;
    }

    @Override
    public String toString() {
        return number + " " + generation + " R";
    }
}
