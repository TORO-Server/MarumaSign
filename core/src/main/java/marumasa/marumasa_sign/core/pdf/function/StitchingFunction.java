package marumasa.marumasa_sign.core.pdf.function;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.io.IOException;

/**
 * Type 3 (つなぎ合わせ): 入力の範囲を Bounds で区切り、区間ごとに別の関数を使う。
 */
final class StitchingFunction extends PdfFunction {

    private PdfFunction[] functions;
    private double[] bounds;
    private double[] encode;

    @Override
    protected void read(PdfDict dict, PdfStream stream, int depth, int[] count) throws IOException {
        final PdfArray list = dict.array("Functions");
        if (list == null || list.size() == 0 || list.size() > 1024) throw new IOException("Bad Functions");
        functions = new PdfFunction[list.size()];
        for (int i = 0; i < functions.length; i++) functions[i] = PdfFunction.create(list.get(i), depth + 1, count);
        final PdfArray b = dict.array("Bounds");
        bounds = b == null ? new double[0] : b.numbers();
        if (bounds == null || bounds.length != functions.length - 1) throw new IOException("Bad Bounds");
        final PdfArray e = dict.array("Encode");
        encode = e == null ? null : e.numbers();
        if (encode == null || encode.length < 2 * functions.length) {
            encode = new double[2 * functions.length];
            for (int i = 0; i < functions.length; i++) encode[2 * i + 1] = 1;
        }
    }

    @Override
    public int outputs() {
        return range != null ? range.length / 2 : functions[0].outputs();
    }

    @Override
    protected void compute(double[] input, double[] output) {
        final double x = input[0];
        int k = 0;
        while (k < bounds.length && x >= bounds[k]) k++;
        final double low = k == 0 ? domain[0] : bounds[k - 1];
        final double high = k == bounds.length ? domain[1] : bounds[k];
        final double t = interpolate(x, low, high, encode[2 * k], encode[2 * k + 1]);
        final double[] sub = new double[functions[k].outputs()];
        functions[k].evaluate(new double[]{t}, sub);
        for (int i = 0; i < output.length; i++) output[i] = i < sub.length ? sub[i] : 0;
    }
}
