package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfName;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.awt.geom.GeneralPath;

/**
 * Type 3 フォント: グリフをページの内容と同じ命令の並び (CharProcs) で描くフォント。
 * 描くのは描画の側 (PageRenderer) で、ここでは手続きと行列と幅を返す。
 */
public final class Type3Font extends PdfFont {

    private final double[] matrix;
    private final String[] names = new String[256];
    private final PdfDict charProcs;
    private final PdfDict resources;
    private final int firstChar;
    private final double[] widths;

    Type3Font(PdfDict dict) {
        super(dict);
        final double[] m = matrix(dict.array("FontMatrix"));
        matrix = m != null ? m : new double[]{0.001, 0, 0, 0.001, 0, 0};
        charProcs = dict.dict("CharProcs");
        resources = dict.dict("Resources");
        firstChar = dict.integer("FirstChar", 0);
        final PdfArray w = dict.array("Widths");
        widths = w == null ? new double[0] : new double[Math.min(w.size(), 65536)];
        for (int i = 0; i < widths.length; i++) widths[i] = w.number(i, 0);
        final Object encoding = dict.get("Encoding");
        if (encoding instanceof PdfDict) {
            final PdfArray differences = ((PdfDict) encoding).array("Differences");
            if (differences != null) {
                int code = 0;
                for (int i = 0; i < differences.size(); i++) {
                    final Object item = differences.get(i);
                    if (item instanceof Number) {
                        code = ((Number) item).intValue();
                    } else if (item instanceof PdfName) {
                        if (code >= 0 && code < 256) names[code] = ((PdfName) item).name;
                        code++;
                    }
                }
            }
        }
    }

    @Override
    public boolean isType3() {
        return true;
    }

    @Override
    public long readCode(byte[] bytes, int position, int[] length) {
        length[0] = 1;
        return bytes[position] & 0xFF;
    }

    /**
     * 幅 (文字空間の単位): グリフ空間の幅に FontMatrix をかけたもの。
     */
    @Override
    public double width(long character) {
        // FirstChar が極端な値でもあふれないように long で求める
        final long index = character - firstChar;
        final double w = index >= 0 && index < widths.length ? widths[(int) index] : 0;
        return w * matrix[0];
    }

    public double[] fontMatrix() {
        return matrix.clone();
    }

    public PdfDict resources() {
        return resources;
    }

    /**
     * 文字コードのグリフを描く手続き。なければ null。
     */
    public PdfStream charProc(int code) {
        if (charProcs == null || code < 0 || code > 255 || names[code] == null) return null;
        return charProcs.stream(names[code]);
    }

    @Override
    protected GeneralPath createPath(long character) {
        return new GeneralPath();
    }
}
