package marumasa.marumasa_sign.core.pdf.function;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;

import java.io.IOException;

/**
 * PDF の関数 (7.10)。色の変換 (Separation など) とグラデーション (シェーディング) に使う。
 * 読むときに形を確かめ、評価では例外を出さない。
 */
public abstract class PdfFunction {

    // 関数の入れ子 (Type 3 の中の関数など) の深さの上限。自分自身を含む関数で止まらないように
    private static final int MAX_DEPTH = 8;

    protected double[] domain;
    protected double[] range;

    public int inputs() {
        return domain.length / 2;
    }

    // 出力の数。Range がなければ (Type 2 と 3 では省略できる) 実装ごとに決まる
    public abstract int outputs();

    /**
     * 評価する。input は inputs() 個、output は outputs() 個。
     */
    public final void evaluate(double[] input, double[] output) {
        final double[] clipped = new double[inputs()];
        for (int i = 0; i < clipped.length; i++) {
            final double value = i < input.length ? input[i] : 0;
            clipped[i] = clip(Double.isNaN(value) ? domain[2 * i] : value, domain[2 * i], domain[2 * i + 1]);
        }
        compute(clipped, output);
        if (range != null) {
            for (int i = 0; i < output.length && 2 * i + 1 < range.length; i++) {
                output[i] = clip(output[i], range[2 * i], range[2 * i + 1]);
            }
        }
        for (int i = 0; i < output.length; i++) {
            if (Double.isNaN(output[i])) output[i] = 0;
        }
    }

    protected abstract void compute(double[] input, double[] output);

    protected static double clip(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }

    protected static double interpolate(double x, double xMin, double xMax, double yMin, double yMax) {
        return xMax == xMin ? yMin : yMin + (x - xMin) * (yMax - yMin) / (xMax - xMin);
    }

    // 1 つの関数の中の関数の数の上限。Type 3 の中に多くの Type 3 を重ねて、組み合わせの数で止まらないように
    private static final int MAX_FUNCTIONS = 4096;

    public static PdfFunction create(Object value) throws IOException {
        return create(value, 0, new int[1]);
    }

    static PdfFunction create(Object value, int depth, int[] count) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Functions are nested too deeply");
        if (++count[0] > MAX_FUNCTIONS) throw new IOException("Too many functions");
        final PdfDict dict;
        PdfStream stream = null;
        if (value instanceof PdfStream) {
            stream = (PdfStream) value;
            dict = stream.dict;
        } else if (value instanceof PdfDict) {
            dict = (PdfDict) value;
        } else {
            throw new IOException("Not a function");
        }
        final PdfFunction function;
        final int type = dict.integer("FunctionType", -1);
        switch (type) {
            case 0:
                if (stream == null) throw new IOException("Sampled function needs a stream");
                function = new SampledFunction();
                break;
            case 2:
                function = new ExponentialFunction();
                break;
            case 3:
                function = new StitchingFunction();
                break;
            case 4:
                if (stream == null) throw new IOException("PostScript function needs a stream");
                function = new PostScriptFunction();
                break;
            default:
                throw new IOException("Unknown function type " + type);
        }
        function.domain = pairs(dict.array("Domain"));
        if (function.domain == null) throw new IOException("Function without Domain");
        if (function.inputs() > 16) throw new IOException("Function has too many inputs");
        function.range = pairs(dict.array("Range"));
        if (function.range != null && function.range.length / 2 > 64) throw new IOException("Function has too many outputs");
        function.read(dict, stream, depth, count);
        return function;
    }

    protected abstract void read(PdfDict dict, PdfStream stream, int depth, int[] count) throws IOException;

    // 偶数個の数 (最小と最大の組) の並び
    protected static double[] pairs(PdfArray array) {
        if (array == null) return null;
        final double[] values = array.numbers();
        if (values == null || values.length < 2) return null;
        return values.length % 2 == 0 ? values : java.util.Arrays.copyOf(values, values.length - 1);
    }

    protected static int limit() {
        return PdfDocument.MAX_DECODED;
    }
}
