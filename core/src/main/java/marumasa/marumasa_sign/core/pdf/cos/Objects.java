package marumasa.marumasa_sign.core.pdf.cos;

/**
 * PDF の値を扱う小さな道具。
 */
public final class Objects {

    private Objects() {
    }

    // int に収まらない数は、収まる範囲の端にする (壊れたファイルの巨大な数で、桁あふれして負の数にならないように)
    public static int toInt(Number number) {
        if (number instanceof Integer) return (Integer) number;
        final double value = number.doubleValue();
        if (Double.isNaN(value)) return 0;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    // 有限の数なら double、そうでなければ fallback
    public static double toDouble(Object value, double fallback) {
        if (!(value instanceof Number)) return fallback;
        final double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) ? fallback : number;
    }

    public static String name(Object value) {
        return value instanceof PdfName ? ((PdfName) value).name : null;
    }
}
