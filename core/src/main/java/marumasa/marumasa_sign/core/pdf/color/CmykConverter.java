package marumasa.marumasa_sign.core.pdf.color;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * CMYK を sRGB にする。印刷用の色の見え方 (ISO Coated v2) を、9 x 9 x 9 x 9 の格子の表 (marumasa_sign/pdf/cmyk.lut) から補間して求める。
 * 表は tools/pdfdata の道具で作ったもの。表が読めなければ、単純な式で代用する。
 */
public final class CmykConverter {

    private static final int GRID = 9;
    private static final byte[] TABLE = load();

    private CmykConverter() {
    }

    private static byte[] load() {
        final byte[] table = new byte[GRID * GRID * GRID * GRID * 3];
        try (InputStream input = CmykConverter.class.getResourceAsStream("/marumasa_sign/pdf/cmyk.lut")) {
            if (input == null) return null;
            new DataInputStream(input).readFully(table);
            return table;
        } catch (IOException e) {
            return null;
        }
    }

    public static int toRgb(double c, double m, double y, double k) {
        c = clip(c);
        m = clip(m);
        y = clip(y);
        k = clip(k);
        if (TABLE == null) {
            return PdfColorSpace.rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k));
        }
        final double[] value = {c, m, y, k};
        final int[] low = new int[4];
        final double[] fraction = new double[4];
        for (int i = 0; i < 4; i++) {
            final double x = value[i] * (GRID - 1);
            low[i] = Math.min((int) x, GRID - 2);
            fraction[i] = x - low[i];
        }
        double r = 0, g = 0, b = 0;
        for (int corner = 0; corner < 16; corner++) {
            double weight = 1;
            int index = 0;
            for (int i = 0; i < 4; i++) {
                final boolean up = ((corner >> i) & 1) != 0;
                weight *= up ? fraction[i] : 1 - fraction[i];
                index = index * GRID + low[i] + (up ? 1 : 0);
            }
            if (weight == 0) continue;
            r += weight * (TABLE[index * 3] & 0xFF);
            g += weight * (TABLE[index * 3 + 1] & 0xFF);
            b += weight * (TABLE[index * 3 + 2] & 0xFF);
        }
        return (round(r) << 16) | (round(g) << 8) | round(b);
    }

    private static int round(double value) {
        return Math.max(0, Math.min(255, (int) (value + 0.5)));
    }

    private static double clip(double value) {
        return value > 0 ? (value < 1 ? value : 1) : 0;
    }
}
