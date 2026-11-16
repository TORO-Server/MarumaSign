package marumasa.marumasa_sign.core.sign;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SignImage (正本) の頂点の計算を、共有の基準データ (src/test/resources/sign/reference-mesh.json) と突き合わせる。
 * 同じファイルを、MarumaSign-Companion の 3D 表示 (marumasign3d.js) の試験が読む。
 * SignImage の計算を変えたら、このファイルを作り直して (--update)、JS 側の写しにも入れる (--companion)。
 *
 * <pre>
 * java SignVectorCheck [基準データ]                       突き合わせる (違えば 1 で終わる)
 * java SignVectorCheck [基準データ] --update              mesh を SignImage の今の結果で書き直す (入力の v, w, h, orient は変えない)
 * java SignVectorCheck [基準データ] --companion パス       パスのファイルが基準データとバイト単位で同じことも確かめる
 *                                                          (--update と一緒なら、そこへも書き出す)
 * </pre>
 */
public final class SignVectorCheck {

    private static final Pattern CASE = Pattern.compile(
            "\\{\"v\":\\[([^\\]]*)\\],\"w\":(\\d+),\"h\":(\\d+),\"orient\":(\\d+),\"mesh\":\\[([^\\]]*)\\]\\}");
    private static final double TOLERANCE = 1e-6;

    private SignVectorCheck() {
    }

    private static float[] floats(String list) {
        final String[] parts = list.split(",");
        final float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
        return out;
    }

    private static String join(float[] values) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static float[] compute(float[] v, int w, int h, int orient) {
        final SignSpec spec = new SignSpec("", v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
        final boolean wall = orient >= 16;
        final float signYaw = wall ? (orient - 16) * 90f : orient * 22.5f;
        return SignImage.detachedImageMesh(spec, w, h, signYaw, wall);
    }

    public static void main(String[] args) throws IOException {
        File file = new File("src/test/resources/sign/reference-mesh.json");
        File companion = null;
        boolean update = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--update")) update = true;
            else if (args[i].equals("--companion")) companion = new File(args[++i]);
            else file = new File(args[i]);
        }
        final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        final Matcher m = CASE.matcher(text);
        final List<String> out = new ArrayList<String>();
        int count = 0, bad = 0;
        double worst = 0;
        while (m.find()) {
            count++;
            final float[] v = floats(m.group(1));
            final int w = Integer.parseInt(m.group(2)), h = Integer.parseInt(m.group(3)), orient = Integer.parseInt(m.group(4));
            final float[] expected = floats(m.group(5));
            final float[] actual = compute(v, w, h, orient);
            out.add("{\"v\":[" + m.group(1) + "],\"w\":" + w + ",\"h\":" + h + ",\"orient\":" + orient + ",\"mesh\":[" + join(actual) + "]}");
            if (expected.length != actual.length) {
                bad++;
                continue;
            }
            for (int i = 0; i < actual.length; i++) {
                final double d = Math.abs(expected[i] - actual[i]);
                worst = Math.max(worst, d);
                if (d > TOLERANCE) {
                    bad++;
                    System.err.println("DRIFT v=" + Arrays.toString(v) + " w=" + w + " h=" + h + " orient=" + orient + " index " + i
                            + ": file " + expected[i] + " / SignImage " + actual[i]);
                    break;
                }
            }
        }
        if (count == 0) {
            System.err.println("基準データに事例が無い: " + file);
            System.exit(1);
        }
        if (update) {
            final String json = "[" + String.join(",\n", out) + "]";
            Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (companion != null) Files.write(companion.toPath(), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("updated " + count + " cases");
            return;
        }
        if (companion != null) {
            if (!Arrays.equals(Files.readAllBytes(file.toPath()), Files.readAllBytes(companion.toPath()))) {
                System.err.println("写しが違う: " + companion);
                bad++;
            }
        }
        if (bad > 0) {
            System.err.println("SignVectorCheck: " + bad + " 件が合わない (" + count + " 件中)");
            System.exit(1);
        }
        System.out.println("SignVectorCheck: " + count + " cases OK (worst diff " + worst + ")");
    }
}
