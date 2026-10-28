package marumasa.marumasa_sign.core.pdf.render;

/**
 * 合成の方法 (ブレンドモード、11.3.5)。色は乗算していない ARGB で受け取る。
 */
abstract class Blend {

    static Blend of(String name) {
        switch (name) {
            case "Multiply": return new Separable() { double mix(double b, double s) { return b * s; } };
            case "Screen": return new Separable() { double mix(double b, double s) { return b + s - b * s; } };
            case "Overlay": return new Separable() { double mix(double b, double s) { return hardLight(s, b); } };
            case "Darken": return new Separable() { double mix(double b, double s) { return Math.min(b, s); } };
            case "Lighten": return new Separable() { double mix(double b, double s) { return Math.max(b, s); } };
            case "ColorDodge": return new Separable() {
                double mix(double b, double s) { return b == 0 ? 0 : s >= 1 ? 1 : Math.min(1, b / (1 - s)); } };
            case "ColorBurn": return new Separable() {
                double mix(double b, double s) { return b >= 1 ? 1 : s <= 0 ? 0 : 1 - Math.min(1, (1 - b) / s); } };
            case "HardLight": return new Separable() { double mix(double b, double s) { return hardLight(b, s); } };
            case "SoftLight": return new Separable() {
                double mix(double b, double s) {
                    if (s <= 0.5) return b - (1 - 2 * s) * b * (1 - b);
                    final double d = b <= 0.25 ? ((16 * b - 12) * b + 4) * b : Math.sqrt(b);
                    return b + (2 * s - 1) * (d - b);
                } };
            case "Difference": return new Separable() { double mix(double b, double s) { return Math.abs(b - s); } };
            case "Exclusion": return new Separable() { double mix(double b, double s) { return b + s - 2 * b * s; } };
            case "Hue": return new NonSeparable(0);
            case "Saturation": return new NonSeparable(1);
            case "Color": return new NonSeparable(2);
            case "Luminosity": return new NonSeparable(3);
            default: return null;
        }
    }

    private static double hardLight(double b, double s) {
        return s <= 0.5 ? b * 2 * s : b + (2 * s - 1) - b * (2 * s - 1);
    }

    /**
     * source を、不透明度 sourceAlpha (0 から 255) で backdrop に重ねた結果。
     */
    final int apply(int source, int sourceAlpha, int backdrop) {
        final double as = sourceAlpha / 255.0;
        final double ab = (backdrop >>> 24) / 255.0;
        final double[] cs = {((source >> 16) & 0xFF) / 255.0, ((source >> 8) & 0xFF) / 255.0, (source & 0xFF) / 255.0};
        final double[] cb = {((backdrop >> 16) & 0xFF) / 255.0, ((backdrop >> 8) & 0xFF) / 255.0, (backdrop & 0xFF) / 255.0};
        final double[] mixed = blend(cb, cs);
        final double ar = as + ab * (1 - as);
        if (ar <= 0) return 0;
        int result = (int) Math.round(ar * 255) << 24;
        for (int i = 0; i < 3; i++) {
            final double c = ((1 - as) * ab * cb[i] + as * ((1 - ab) * cs[i] + ab * mixed[i])) / ar;
            result |= (int) Math.round(Math.max(0, Math.min(1, c)) * 255) << (16 - 8 * i);
        }
        return result;
    }

    abstract double[] blend(double[] backdrop, double[] source);

    private abstract static class Separable extends Blend {
        abstract double mix(double backdrop, double source);

        @Override
        double[] blend(double[] b, double[] s) {
            return new double[]{mix(b[0], s[0]), mix(b[1], s[1]), mix(b[2], s[2])};
        }
    }

    // 色相・彩度・色・明度 (11.3.5.3)
    private static final class NonSeparable extends Blend {
        private final int kind;

        NonSeparable(int kind) {
            this.kind = kind;
        }

        @Override
        double[] blend(double[] b, double[] s) {
            switch (kind) {
                case 0: return setLum(setSat(s, sat(b)), lum(b));
                case 1: return setLum(setSat(b, sat(s)), lum(b));
                case 2: return setLum(s, lum(b));
                default: return setLum(b, lum(s));
            }
        }

        private static double lum(double[] c) {
            return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
        }

        private static double[] clipColor(double[] c) {
            final double l = lum(c);
            final double n = Math.min(c[0], Math.min(c[1], c[2]));
            final double x = Math.max(c[0], Math.max(c[1], c[2]));
            final double[] r = c.clone();
            for (int i = 0; i < 3; i++) {
                if (n < 0 && l != n) r[i] = l + (r[i] - l) * l / (l - n);
                if (x > 1 && x != l) r[i] = l + (r[i] - l) * (1 - l) / (x - l);
            }
            return r;
        }

        private static double[] setLum(double[] c, double l) {
            final double d = l - lum(c);
            return clipColor(new double[]{c[0] + d, c[1] + d, c[2] + d});
        }

        private static double sat(double[] c) {
            return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
        }

        private static double[] setSat(double[] c, double s) {
            final double max = Math.max(c[0], Math.max(c[1], c[2]));
            final double min = Math.min(c[0], Math.min(c[1], c[2]));
            final double[] r = new double[3];
            if (max > min) {
                for (int i = 0; i < 3; i++) {
                    if (c[i] == max) r[i] = s;
                    else if (c[i] == min) r[i] = 0;
                    else r[i] = (c[i] - min) * s / (max - min);
                }
            }
            return r;
        }
    }
}
