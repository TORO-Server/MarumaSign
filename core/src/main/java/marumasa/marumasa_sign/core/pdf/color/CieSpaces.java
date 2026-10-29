package marumasa.marumasa_sign.core.pdf.color;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;

/**
 * CIE に基づく色空間 (CalGray、CalRGB、Lab)。XYZ を経て sRGB にする。
 * 白色点 (WhitePoint) の違いは、Bradford の方法で sRGB の白 (D65) に合わせる。
 */
final class CieSpaces {

    private CieSpaces() {
    }

    // XYZ (白色点 white に対するもの) を sRGB に
    static int xyzToRgb(double x, double y, double z, double[] white) {
        final double[] adapted = adapt(x, y, z, white);
        x = adapted[0];
        y = adapted[1];
        z = adapted[2];
        final double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        final double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        final double b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        return PdfColorSpace.rgb(encode(r), encode(g), encode(b));
    }

    // sRGB の階調にする
    static double encode(double linear) {
        if (linear <= 0) return 0;
        if (linear >= 1) return 1;
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }

    private static final double[] D65 = {0.95047, 1.0, 1.08883};

    // Bradford の色順応 (white から D65 へ)
    private static double[] adapt(double x, double y, double z, double[] white) {
        if (white == null || white[1] <= 0) return new double[]{x, y, z};
        final double[] source = cone(white[0], white[1], white[2]);
        final double[] target = cone(D65[0], D65[1], D65[2]);
        final double[] value = cone(x, y, z);
        for (int i = 0; i < 3; i++) value[i] = source[i] == 0 ? value[i] : value[i] * target[i] / source[i];
        // 錐体の応答から XYZ に戻す
        return new double[]{
                0.9869929 * value[0] - 0.1470543 * value[1] + 0.1599627 * value[2],
                0.4323053 * value[0] + 0.5183603 * value[1] + 0.0492912 * value[2],
                -0.0085287 * value[0] + 0.0400428 * value[1] + 0.9684867 * value[2]};
    }

    private static double[] cone(double x, double y, double z) {
        return new double[]{
                0.8951 * x + 0.2664 * y - 0.1614 * z,
                -0.7502 * x + 1.7135 * y + 0.0367 * z,
                0.0389 * x - 0.0685 * y + 1.0296 * z};
    }

    static double[] whitePoint(PdfDict dict) {
        final PdfArray array = dict == null ? null : dict.array("WhitePoint");
        final double[] white = array == null ? null : array.numbers();
        if (white == null || white.length < 3 || white[1] <= 0) return D65.clone();
        return white;
    }

    static final class CalGray extends PdfColorSpace {
        private final double gamma;
        private final double[] white;

        CalGray(PdfDict dict) {
            final double g = dict == null ? 1 : dict.number("Gamma", 1);
            gamma = g > 0 ? g : 1;
            white = whitePoint(dict);
        }

        @Override
        public int components() {
            return 1;
        }

        @Override
        public int toRgb(float[] value) {
            final double a = Math.pow(Math.max(0, Math.min(1, value[0])), gamma);
            return xyzToRgb(white[0] * a, white[1] * a, white[2] * a, white);
        }
    }

    static final class CalRgb extends PdfColorSpace {
        private final double[] gamma;
        private final double[] matrix;
        private final double[] white;

        CalRgb(PdfDict dict) {
            final PdfArray g = dict == null ? null : dict.array("Gamma");
            double[] values = g == null ? null : g.numbers();
            gamma = values != null && values.length >= 3 ? values : new double[]{1, 1, 1};
            final PdfArray m = dict == null ? null : dict.array("Matrix");
            values = m == null ? null : m.numbers();
            matrix = values != null && values.length >= 9 ? values : new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            white = whitePoint(dict);
        }

        @Override
        public int components() {
            return 3;
        }

        @Override
        public int toRgb(float[] value) {
            final double a = Math.pow(Math.max(0, Math.min(1, value[0])), gamma[0]);
            final double b = Math.pow(Math.max(0, Math.min(1, value[1])), gamma[1]);
            final double c = Math.pow(Math.max(0, Math.min(1, value[2])), gamma[2]);
            return xyzToRgb(matrix[0] * a + matrix[3] * b + matrix[6] * c,
                    matrix[1] * a + matrix[4] * b + matrix[7] * c,
                    matrix[2] * a + matrix[5] * b + matrix[8] * c, white);
        }
    }

    static final class Lab extends PdfColorSpace {
        private final double[] white;
        private final double[] range;

        Lab(PdfDict dict) {
            white = whitePoint(dict);
            final PdfArray r = dict == null ? null : dict.array("Range");
            final double[] values = r == null ? null : r.numbers();
            range = values != null && values.length >= 4 ? values : new double[]{-100, 100, -100, 100};
        }

        @Override
        public int components() {
            return 3;
        }

        @Override
        public float[] initialColor() {
            // 0 を範囲に収めたもの
            return new float[]{0, (float) clip(0, range[0], range[1]), (float) clip(0, range[2], range[3])};
        }

        @Override
        public double[] defaultDecode(int bits) {
            return new double[]{0, 100, range[0], range[1], range[2], range[3]};
        }

        private static double clip(double value, double min, double max) {
            return value < min ? min : value > max ? max : value;
        }

        @Override
        public int toRgb(float[] value) {
            final double l = clip(value[0], 0, 100);
            final double a = clip(value[1], range[0], range[1]);
            final double b = clip(value[2], range[2], range[3]);
            final double m = (l + 16) / 116;
            final double x = white[0] * g(m + a / 500);
            final double y = white[1] * g(m);
            final double z = white[2] * g(m - b / 200);
            return xyzToRgb(x, y, z, white);
        }

        private static double g(double x) {
            return x >= 6.0 / 29 ? x * x * x : 108.0 / 841 * (x - 4.0 / 29);
        }
    }
}
