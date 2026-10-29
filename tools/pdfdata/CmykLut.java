import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * CMYK を sRGB にする表 (core/src/main/resources/marumasa_sign/pdf/cmyk.lut) を作る。
 * 印刷用の色の見え方 (ISO Coated v2 300% (ECI) の ICC プロファイル) を、9 x 9 x 9 x 9 の格子の点で求めて、
 * C, M, Y, K の順 (K が一番内側) に R, G, B の 3 バイトずつ並べる。読む側は core/pdf/color/CmykConverter.java。
 * <p>
 * 使い方: java tools/pdfdata/CmykLut.java ISOcoated_v2_300_bas.icc core/src/main/resources/marumasa_sign/pdf/cmyk.lut
 * (JDK だけで動く。プロファイルは開発のときに使うだけで、配布する jar には入れない)
 */
public final class CmykLut {

    private static final int GRID = 9;

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java CmykLut.java <CMYK ICC profile> <output>");
            System.exit(2);
        }
        final ICC_ColorSpace space;
        try (InputStream input = new FileInputStream(args[0])) {
            space = new ICC_ColorSpace(ICC_Profile.getInstance(input));
        }
        final byte[] table = new byte[GRID * GRID * GRID * GRID * 3];
        int p = 0;
        for (int c = 0; c < GRID; c++) {
            for (int m = 0; m < GRID; m++) {
                for (int y = 0; y < GRID; y++) {
                    for (int k = 0; k < GRID; k++) {
                        final float[] rgb = space.toRGB(new float[]{c / (GRID - 1f), m / (GRID - 1f), y / (GRID - 1f), k / (GRID - 1f)});
                        for (int i = 0; i < 3; i++) table[p++] = (byte) Math.round(rgb[i] * 255);
                    }
                }
            }
        }
        try (OutputStream output = new FileOutputStream(args[1])) {
            output.write(table);
        }
        System.out.println(args[1] + " " + table.length);
    }
}
