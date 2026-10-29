package marumasa.marumasa_sign.core.pdf.color;

import marumasa.marumasa_sign.core.pdf.function.PdfFunction;

import java.util.HashMap;
import java.util.Map;

/**
 * 特別な色空間 (Indexed、Separation、DeviceN、Pattern)。
 */
public final class SpecialSpaces {

    private SpecialSpaces() {
    }

    /**
     * 番号で色を選ぶ色空間。表の色は、あらかじめすべて sRGB にしておく。
     */
    public static final class Indexed extends PdfColorSpace {
        final PdfColorSpace base;
        // 番号ごとの 0xRRGGBB
        final int[] palette;

        Indexed(PdfColorSpace base, int hival, byte[] lookup) {
            this.base = base;
            final int count = Math.max(1, Math.min(256, hival + 1));
            palette = new int[count];
            final int n = base.components();
            final double[] decode = base.defaultDecode(8);
            final float[] value = new float[n];
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < n; c++) {
                    final int index = i * n + c;
                    // 表が短ければ、足りない分は 0
                    final int v = index < lookup.length ? lookup[index] & 0xFF : 0;
                    value[c] = (float) (decode[2 * c] + v * (decode[2 * c + 1] - decode[2 * c]) / 255);
                }
                palette[i] = base.toRgb(value);
            }
        }

        @Override
        public int components() {
            return 1;
        }

        @Override
        public int toRgb(float[] value) {
            final int index = Math.max(0, Math.min(palette.length - 1, Math.round(value[0])));
            return palette[index];
        }

        public int paletteSize() {
            return palette.length;
        }

        @Override
        public double[] defaultDecode(int bits) {
            return new double[]{0, (1 << bits) - 1};
        }
    }

    /**
     * 特色 (Separation) と、複数の特色 (DeviceN)。色の濃さを変換の関数で代わりの色空間にする。
     */
    public static final class Tint extends PdfColorSpace {
        private final int components;
        private final PdfColorSpace alternate;
        private final PdfFunction function;
        private final boolean none;
        // 1 成分のとき、濃さ (256 段階) ごとの色
        private int[] table;
        // 変換の結果の覚え (同じ色を何度も計算しないように)
        private final Map<Long, Integer> cache = new HashMap<>();

        Tint(int components, PdfColorSpace alternate, PdfFunction function, boolean none) {
            this.components = components;
            this.alternate = alternate;
            this.function = function;
            this.none = none;
        }

        @Override
        public int components() {
            return components;
        }

        @Override
        public boolean isNone() {
            return none;
        }

        @Override
        public float[] initialColor() {
            final float[] value = new float[components];
            java.util.Arrays.fill(value, 1);
            return value;
        }

        @Override
        public synchronized int toRgb(float[] value) {
            if (components == 1) {
                if (table == null) {
                    table = new int[256];
                    for (int i = 0; i < 256; i++) table[i] = compute(new float[]{i / 255f});
                }
                return table[Math.max(0, Math.min(255, Math.round(value[0] * 255)))];
            }
            // 8 ビットにそろえた値で覚える (成分が 8 個までなら long に収まる)
            if (components <= 8) {
                long key = 0;
                for (int i = 0; i < components; i++) key = (key << 8) | Math.max(0, Math.min(255, Math.round(value[i] * 255)));
                final Integer cached = cache.get(key);
                if (cached != null) return cached;
                final int result = compute(value);
                if (cache.size() < 65536) cache.put(key, result);
                return result;
            }
            return compute(value);
        }

        private int compute(float[] value) {
            final double[] input = new double[components];
            for (int i = 0; i < components; i++) input[i] = value[i];
            final double[] output = new double[Math.max(function.outputs(), alternate.components())];
            function.evaluate(input, output);
            final float[] alt = new float[alternate.components()];
            for (int i = 0; i < alt.length; i++) alt[i] = (float) output[i];
            return alternate.toRgb(alt);
        }
    }

    /**
     * パターンの色空間。色のないパターン (PaintType 2) なら、下の色空間 (base) で色を決める。
     */
    public static final class Pattern extends PdfColorSpace {
        public final PdfColorSpace base;

        Pattern(PdfColorSpace base) {
            this.base = base;
        }

        @Override
        public int components() {
            return base == null ? 0 : base.components();
        }

        @Override
        public int toRgb(float[] value) {
            return base == null ? 0 : base.toRgb(value);
        }
    }
}
