package marumasa.marumasa_sign.core.pdf.color;

/**
 * PDF の色空間 (8.6)。成分の値を sRGB にする。
 * <p>
 * PDFBox は ICCBased の色空間に埋め込まれた ICC プロファイルを、Java の色管理 (C で書かれた LittleCMS) にそのまま渡す。
 * 誰でも作れる PDF の中のプロファイルをネイティブのコードに読ませると、その不具合でメモリを壊される (任意のコードを実行される)
 * おそれがあるので、ここでは埋め込みのプロファイルは使わず、代わりの色空間 (/Alternate か、成分の数に合う Device の色空間) で色を作る。
 */
public abstract class PdfColorSpace {

    public abstract int components();

    /**
     * 成分の値 (色空間ごとの範囲。Indexed なら番号) を、0xRRGGBB にする。
     */
    public abstract int toRgb(float[] value);

    /**
     * 描くたびに何も塗らない色空間か (Separation /None)。
     */
    public boolean isNone() {
        return false;
    }

    /**
     * 色空間を設定したときの、最初の色 (8.6.8 の表)。
     */
    public float[] initialColor() {
        return new float[components()];
    }

    /**
     * 画像の Decode の既定値。bits は 1 成分のビット数。
     */
    public double[] defaultDecode(int bits) {
        final double[] decode = new double[2 * components()];
        for (int i = 0; i < components(); i++) decode[2 * i + 1] = 1;
        return decode;
    }

    protected static int rgb(double r, double g, double b) {
        return (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    protected static int to8(double value) {
        if (!(value > 0)) return 0;
        if (value >= 1) return 255;
        return (int) (value * 255 + 0.5);
    }

    public static int gray(double value) {
        final int v = to8(value);
        return (v << 16) | (v << 8) | v;
    }
}
