package marumasa.marumasa_sign.core.splat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * ガウシアンスプラッティングのファイル (.ply .spz .sog .splat .ksplat) を読み込む。
 */
public final class SplatLoader {

    // 読み込むガウス分布の数の上限。これより多いファイルは、目立つものから順にこの数だけ残す
    public static final int MAX_SPLATS = 150000;

    private SplatLoader() {
    }

    /**
     * 先頭のバイト列と名前から、ガウシアンスプラッティングのファイルらしいかどうかを判定する。
     *
     * @param head   ファイルの先頭
     * @param length head のうち有効な長さ
     * @param name   ファイル名か URL (拡張子の判定に使う)。わからなければ null
     */
    public static boolean isSplat(byte[] head, int length, String name) {
        return ".splat".equals(extension(name)) && length >= 32;
    }

    /**
     * ファイルの内容を読み込む。ガウス分布が MAX_SPLATS より多ければ、目立つものから順に残す。
     *
     * @param content ファイルの内容
     * @param name    ファイル名か URL (拡張子の判定に使う)。わからなければ null
     */
    public static SplatCloud load(byte[] content, String name) throws IOException {
        if (".splat".equals(extension(name))) return loadSplat(content);
        throw new IOException("Not a Gaussian splatting file");
    }

    // 名前の拡張子を、小文字にして "." 付きで返す。URL のクエリやフラグメントは無視する。なければ ""
    static String extension(String name) {
        if (name == null) return "";
        int end = name.length();
        final int query = name.indexOf('?');
        if (query >= 0) end = query;
        final int fragment = name.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        final String path = name.substring(0, end);
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        final int dot = path.lastIndexOf('.');
        if (dot <= slash) return "";
        return path.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * 目立つ度合いの大きいものから max 個を選び、元の順に並べた添字を返す。
     * 全体が max 個以下なら、すべての添字を返す。
     */
    static int[] selectTop(float[] importance, int count, int max) {
        if (count <= max) {
            final int[] all = new int[count];
            for (int i = 0; i < count; i++) all[i] = i;
            return all;
        }
        final float[] sorted = Arrays.copyOf(importance, count);
        Arrays.sort(sorted);
        final float threshold = sorted[count - max];
        final int[] selected = new int[max];
        int n = 0;
        // しきい値より大きいものを先に入れ、残りをしきい値と同じものから入れる
        for (int i = 0; i < count && n < max; i++) {
            if (importance[i] > threshold) selected[n++] = i;
        }
        for (int i = 0; i < count && n < max; i++) {
            if (importance[i] == threshold) selected[n++] = i;
        }
        Arrays.sort(selected);
        return selected;
    }

    // 目立つ度合い。不透明度と、分布の広がり (おおよその表面積) の積
    static float importance(float opacity, float sx, float sy, float sz) {
        return opacity * (sx * sy + sy * sz + sz * sx);
    }

    /**
     * +Y が下で正面が -Z の座標系 (多くの .ply の座標系) から、+Y が上で正面が +Z の座標系に直す。
     * X 軸のまわりに半回転させる。positions と rotations と shCoefficients をその場で書き換える。
     */
    static void flipYZ(int count, float[] positions, float[] rotations, int shDegree, float[] sh) {
        for (int i = 0; i < count; i++) {
            positions[i * 3 + 1] = -positions[i * 3 + 1];
            positions[i * 3 + 2] = -positions[i * 3 + 2];
            // (1, 0, 0, 0) を左から掛ける
            final int o = i * 4;
            final float x = rotations[o], y = rotations[o + 1], z = rotations[o + 2], w = rotations[o + 3];
            rotations[o] = w;
            rotations[o + 1] = -z;
            rotations[o + 2] = y;
            rotations[o + 3] = -x;
        }
        if (shDegree <= 0) return;
        // 半回転で符号が変わる基底 (y と z について奇数次の項)
        final float[] sign = {
                -1, -1, 1,
                -1, 1, 1, -1, 1,
                -1, 1, -1, -1, 1, -1, 1
        };
        final int rest = SplatCloud.restCoefficients(shDegree);
        for (int i = 0; i < count; i++) {
            for (int k = 0; k < rest; k++) {
                if (sign[k] > 0) continue;
                final int o = (i * rest + k) * 3;
                sh[o] = -sh[o];
                sh[o + 1] = -sh[o + 1];
                sh[o + 2] = -sh[o + 2];
            }
        }
    }

    // 1 つにつき 32 バイト: 位置 (float x3)、大きさ (float x3)、色と不透明度 (u8 x4)、回転 (u8 x4、w x y z の順)
    private static SplatCloud loadSplat(byte[] content) throws IOException {
        if (content.length % 32 != 0 || content.length == 0) throw new IOException("Invalid .splat size");
        final int total = content.length / 32;
        final ByteBuffer buffer = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

        final float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int o = i * 32;
            importance[i] = importance((content[o + 27] & 0xFF) / 255f,
                    buffer.getFloat(o + 12), buffer.getFloat(o + 16), buffer.getFloat(o + 20));
        }
        final int[] selected = selectTop(importance, total, MAX_SPLATS);
        final int count = selected.length;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        for (int n = 0; n < count; n++) {
            final int o = selected[n] * 32;
            for (int c = 0; c < 3; c++) {
                positions[n * 3 + c] = buffer.getFloat(o + c * 4);
                scales[n * 3 + c] = Math.abs(buffer.getFloat(o + 12 + c * 4));
                colors[n * 3 + c] = (content[o + 24 + c] & 0xFF) / 255f;
            }
            opacities[n] = (content[o + 27] & 0xFF) / 255f;
            final float w = ((content[o + 28] & 0xFF) - 128) / 128f;
            final float x = ((content[o + 29] & 0xFF) - 128) / 128f;
            final float y = ((content[o + 30] & 0xFF) - 128) / 128f;
            final float z = ((content[o + 31] & 0xFF) - 128) / 128f;
            final float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (length > 0) {
                rotations[n * 4] = x / length;
                rotations[n * 4 + 1] = y / length;
                rotations[n * 4 + 2] = z / length;
                rotations[n * 4 + 3] = w / length;
            } else {
                rotations[n * 4 + 3] = 1;
            }
            for (int c = 0; c < 3; c++) {
                if (!Float.isFinite(positions[n * 3 + c]) || !Float.isFinite(scales[n * 3 + c])) {
                    throw new IOException("Invalid .splat values");
                }
            }
        }
        flipYZ(count, positions, rotations, 0, new float[0]);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, 0, new float[0]);
    }
}
