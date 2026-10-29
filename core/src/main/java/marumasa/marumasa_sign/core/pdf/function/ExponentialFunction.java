package marumasa.marumasa_sign.core.pdf.function;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.io.IOException;

/**
 * Type 2 (指数補間): y = C0 + x^N * (C1 - C0)。
 */
final class ExponentialFunction extends PdfFunction {

    private double[] c0;
    private double[] c1;
    private double n;

    @Override
    protected void read(PdfDict dict, PdfStream stream, int depth, int[] count) throws IOException {
        final PdfArray a0 = dict.array("C0");
        final PdfArray a1 = dict.array("C1");
        c0 = a0 == null ? new double[]{0} : a0.numbers();
        c1 = a1 == null ? new double[]{1} : a1.numbers();
        if (c0 == null || c1 == null) throw new IOException("Bad C0 / C1");
        if (c0.length != c1.length) {
            final int length = Math.min(c0.length, c1.length);
            c0 = java.util.Arrays.copyOf(c0, length);
            c1 = java.util.Arrays.copyOf(c1, length);
        }
        n = dict.number("N", 1);
    }

    @Override
    public int outputs() {
        return c0.length;
    }

    @Override
    protected void compute(double[] input, double[] output) {
        final double x = input.length > 0 ? input[0] : 0;
        final double power = n == 1 ? x : Math.pow(x, n);
        for (int i = 0; i < output.length && i < c0.length; i++) output[i] = c0[i] + power * (c1[i] - c0[i]);
    }
}
