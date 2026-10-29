package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.sign.SignImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/**
 * スプラットを描く部分 (SplatScene) が、3D Gaussian Splatting の参照実装 (graphdeco-inria の
 * forward.cu の computeColorFromSH と computeCov3D) と同じ値を出すことを確かめる。RuntimeLogicCheck から呼ぶ。
 * (球面調和関数の色、共分散と四角形の大きさ・向き、奥から手前への並べ替え、不透明度)
 */
public final class SplatRuntimeCheck {

    private static final int STRIDE = SignImage.Batch.STRIDE;

    // 参照実装の定数
    private static final double C1 = 0.4886025119029199;
    private static final double[] C2 = {1.0925484305920792, -1.0925484305920792, 0.31539156525252005,
            -1.0925484305920792, 0.5462742152960396};
    private static final double[] C3 = {-0.5900435899266435, 2.890611442640554, -0.4570457994644658,
            0.3731763325901154, -0.4570457994644658, 1.445305721320277, -0.5900435899266435};

    private static int failures;

    private SplatRuntimeCheck() {
    }

    // 失敗の数を返す
    public static int run() throws IOException {
        failures = 0;
        splatFormat();
        plyFormat();
        sphericalHarmonics();
        rotatedSignViewDirection();
        covariance();
        placementScale();
        sorting();
        opacity();
        return failures;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + "splat: " + message);
        if (!condition) failures++;
    }

    private static boolean near(float[] actual, float tolerance, float... expected) {
        if (actual.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!(Math.abs(actual[i] - expected[i]) <= tolerance)) return false;
        }
        return true;
    }

    // Z 軸まわりに 45 度の回転 (x, y, z, w) を、+Y が下の座標系から X 軸まわりの半回転で直したもの
    // (1, 0, 0, 0) * (0, 0, s, c) = (c, -s, 0, 0)。位置は (x, -y, -z)
    private static final float C225 = (float) Math.cos(Math.PI / 8), S225 = (float) Math.sin(Math.PI / 8);

    // .splat (antimatter15): 位置と大きさは float、色と不透明度は u8 (不透明度はシグモイドを通したあと)、回転は u8 の w, x, y, z
    private static void splatFormat() throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(1).putFloat(2).putFloat(3).putFloat(0.5f).putFloat(1).putFloat(2);
        buffer.put((byte) 255).put((byte) 0).put((byte) 128).put((byte) 64);
        buffer.put((byte) Math.round(C225 * 128 + 128)).put((byte) 128).put((byte) 128).put((byte) Math.round(S225 * 128 + 128));
        final SplatCloud cloud = SplatLoader.load(buffer.array(), "a.splat");
        check(near(cloud.positions, 1e-6f, 1, -2, -3) && near(cloud.scales, 1e-6f, 0.5f, 1, 2)
                        && near(cloud.colors, 1e-3f, 1, 0, 128 / 255f) && near(cloud.opacities, 1e-6f, 64 / 255f)
                        && near(cloud.rotations, 0.01f, C225, -S225, 0, 0),
                ".splat conventions: " + Arrays.toString(cloud.rotations));
    }

    // .ply (3DGS): 不透明度はシグモイドの前、大きさは対数、色は f_dc (0.5 + C0 * f_dc)、回転は rot_0 が w
    private static void plyFormat() throws IOException {
        final String[] names = {"x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2",
                "rot_0", "rot_1", "rot_2", "rot_3"};
        final StringBuilder header = new StringBuilder("ply\nformat binary_little_endian 1.0\nelement vertex 1\n");
        for (String name : names) header.append("property float ").append(name).append('\n');
        header.append("end_header\n");
        final ByteBuffer buffer = ByteBuffer.allocate(names.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(1).putFloat(2).putFloat(3).putFloat(1).putFloat(0).putFloat(-1);
        buffer.putFloat((float) Math.log(3)).putFloat((float) Math.log(2)).putFloat(0).putFloat((float) Math.log(0.5));
        buffer.putFloat(C225).putFloat(0).putFloat(0).putFloat(S225);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] text = header.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(text, 0, text.length);
        out.write(buffer.array(), 0, buffer.capacity());
        final SplatCloud cloud = SplatLoader.load(out.toByteArray(), "a.ply");
        final float c0 = SplatCloud.SH_C0;
        check(near(cloud.positions, 1e-6f, 1, -2, -3) && near(cloud.scales, 1e-5f, 2, 1, 0.5f)
                        && near(cloud.colors, 1e-6f, 0.5f + c0, 0.5f, 0.5f - c0) && near(cloud.opacities, 1e-6f, 0.75f)
                        && near(cloud.rotations, 1e-6f, C225, -S225, 0, 0),
                ".ply conventions: opacity " + cloud.opacities[0] + ", rotation " + Arrays.toString(cloud.rotations));
    }

    private static SplatCloud single(float[] position, float[] rotation, float[] scale, float opacity, float[] color,
                                     int degree, float[] sh) {
        return new SplatCloud(1, position.clone(), rotation.clone(), scale.clone(), new float[]{opacity}, color.clone(), degree, sh);
    }

    private static final class Output {
        final float[] vertices;
        final int[] colors;

        Output(int count) {
            vertices = new float[count * 4 * STRIDE];
            colors = new int[count * 4];
        }

        float[] corner(int splat, int corner) {
            final int o = (splat * 4 + corner) * STRIDE;
            return new float[]{vertices[o], vertices[o + 1], vertices[o + 2]};
        }
    }

    private static Output emit(SplatCloud cloud, float[] placement, float cameraX, float cameraY, float cameraZ) {
        final Output out = new Output(cloud.count);
        new SplatScene(cloud).emit(placement, cameraX, cameraY, cameraZ, out.vertices, out.colors);
        return out;
    }

    private static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    // Y 軸まわりの回転と、一様な拡大と、移動 (列優先)
    private static float[] placement(double yaw, float scale, float tx, float ty, float tz) {
        final float c = (float) Math.cos(yaw) * scale, s = (float) Math.sin(yaw) * scale;
        return new float[]{c, 0, -s, 0, 0, scale, 0, 0, s, 0, c, 0, tx, ty, tz, 1};
    }

    // 3x3 の逆行列でカメラをモデルの座標に戻す (倍精度)
    private static double[] toModel(float[] p, double x, double y, double z) {
        final double a = p[0], b = p[4], c = p[8], d = p[1], e = p[5], f = p[9], g = p[2], h = p[6], i = p[10];
        final double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        final double tx = x - p[12], ty = y - p[13], tz = z - p[14];
        return new double[]{
                ((e * i - f * h) * tx + (c * h - b * i) * ty + (b * f - c * e) * tz) / det,
                ((f * g - d * i) * tx + (a * i - c * g) * ty + (c * d - a * f) * tz) / det,
                ((d * h - e * g) * tx + (b * g - a * h) * ty + (a * e - b * d) * tz) / det};
    }

    // 参照実装の computeColorFromSH。color は 0.5 + C0 * f_dc を済ませたもの。sh は SplatCloud と同じ並び
    private static double[] referenceColor(float[] position, double[] camera, float[] color, int degree, float[] sh) {
        double x = position[0] - camera[0], y = position[1] - camera[1], z = position[2] - camera[2];
        final double length = Math.sqrt(x * x + y * y + z * z);
        x /= length;
        y /= length;
        z /= length;
        final double xx = x * x, yy = y * y, zz = z * z, xy = x * y, yz = y * z, xz = x * z;
        final double[] basis = {
                -C1 * y, C1 * z, -C1 * x,
                C2[0] * xy, C2[1] * yz, C2[2] * (2 * zz - xx - yy), C2[3] * xz, C2[4] * (xx - yy),
                C3[0] * y * (3 * xx - yy), C3[1] * xy * z, C3[2] * y * (4 * zz - xx - yy), C3[3] * z * (2 * zz - 3 * xx - 3 * yy),
                C3[4] * x * (4 * zz - xx - yy), C3[5] * z * (xx - yy), C3[6] * x * (xx - 3 * yy)};
        final int rest = SplatCloud.restCoefficients(degree);
        final double[] result = {color[0], color[1], color[2]};
        for (int k = 0; k < rest; k++) {
            for (int c = 0; c < 3; c++) result[c] += basis[k] * sh[k * 3 + c];
        }
        return result;
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    // 1 - 3 次の球面調和関数の色を、看板の向き (Y 軸まわりの回転) と拡大とカメラの位置をいろいろに変えて比べる
    private static void sphericalHarmonics() {
        final Random random = new Random(1234);
        for (int degree = 1; degree <= 3; degree++) {
            int worst = 0;
            String example = "";
            for (int n = 0; n < 300; n++) {
                final int rest = SplatCloud.restCoefficients(degree);
                final float[] sh = new float[rest * 3];
                for (int i = 0; i < sh.length; i++) sh[i] = (float) (random.nextGaussian() * 0.3);
                final float[] position = {(float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()};
                final float[] color = {0.3f + random.nextFloat() * 0.4f, 0.3f + random.nextFloat() * 0.4f, 0.3f + random.nextFloat() * 0.4f};
                final float[] p = placement(random.nextDouble() * Math.PI * 2, 0.2f + random.nextFloat() * 2, (float) random.nextGaussian(),
                        (float) random.nextGaussian(), (float) random.nextGaussian());
                final float cx = (float) random.nextGaussian() * 8, cy = (float) random.nextGaussian() * 8, cz = (float) random.nextGaussian() * 8;
                final Output out = emit(single(position, new float[]{0, 0, 0, 1}, new float[]{0.1f, 0.1f, 0.1f}, 1, color, degree, sh),
                        p, cx, cy, cz);
                final double[] expected = referenceColor(position, toModel(p, cx, cy, cz), color, degree, sh);
                final int argb = out.colors[0];
                final int[] actual = {(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
                for (int c = 0; c < 3; c++) {
                    final int difference = Math.abs(actual[c] - channel(expected[c]));
                    if (difference > worst) {
                        worst = difference;
                        example = "case " + n + " channel " + c + ": " + actual[c] + " (expected " + channel(expected[c]) + ")";
                    }
                }
            }
            check(worst <= 1, "spherical harmonics degree " + degree + " match the reference" + (worst > 1 ? ": " + example : ""));
        }
    }

    // 90 度回した看板を、モデルの +X 側から見る。1 次の係数 (-C1 * x の項) だけを持つ赤は、x = +1 の向きで暗くなる
    private static void rotatedSignViewDirection() {
        final float[] sh = new float[9];
        sh[6] = 1;
        final float[] p = placement(Math.PI / 2, 1, 0, 0, 0);
        // モデルの +X は、描く座標では -Z (列 0 が (0, 0, -1))。カメラはモデルの -X 側 (描く座標の +Z) に置く
        final Output out = emit(single(new float[3], new float[]{0, 0, 0, 1}, new float[]{0.1f, 0.1f, 0.1f}, 1,
                new float[]{0.5f, 0.5f, 0.5f}, 1, sh), p, 0, 0, 10);
        final int red = (out.colors[0] >> 16) & 0xFF;
        // 視線 (ガウス分布 - カメラ) はモデルの +X なので 0.5 - C1
        check(Math.abs(red - channel(0.5 - C1)) <= 1, "view direction for SH on a rotated sign: red " + red + " (expected " + channel(0.5 - C1) + ")");
    }

    private static float length(float[] a, float[] b) {
        final float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    // Sigma = R S S^T R^T。四元数は (x, y, z, w)。カメラに正対する面では、楕円の半径は 2.5 * 標準偏差
    private static void covariance() {
        final double angle = Math.toRadians(30);
        final float[] rotation = {0, 0, (float) Math.sin(angle / 2), (float) Math.cos(angle / 2)};
        final Output out = emit(single(new float[3], rotation, new float[]{2, 0.5f, 0.1f}, 1, new float[]{1, 1, 1}, 0, new float[0]),
                identity(), 0, 0, 10);
        final float[] c0 = out.corner(0, 0), c1 = out.corner(0, 1), c3 = out.corner(0, 3);
        final float major = length(c0, c1), minor = length(c0, c3);
        // 長軸の向き (c0 → c1) は (cos 30, sin 30, 0)
        final float dx = (c1[0] - c0[0]) / major, dy = (c1[1] - c0[1]) / major;
        final boolean along = Math.abs(Math.abs(dx * (float) Math.cos(angle) + dy * (float) Math.sin(angle)) - 1) < 1e-4f;
        check(Math.abs(major - 10) < 1e-3f && Math.abs(minor - 2.5f) < 1e-3f && along,
                "covariance from scale and rotation: axes " + major + ", " + minor + " direction " + dx + ", " + dy);
    }

    private static void placementScale() {
        final Output out = emit(single(new float[3], new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, 1, new float[]{1, 1, 1}, 0, new float[0]),
                placement(0.7, 3, 5, 6, 7), 5, 6, 30);
        final float[] c0 = out.corner(0, 0), c1 = out.corner(0, 1), c2 = out.corner(0, 2);
        final float[] center = {(c0[0] + c2[0]) / 2, (c0[1] + c2[1]) / 2, (c0[2] + c2[2]) / 2};
        check(Math.abs(length(c0, c1) - 15) < 1e-3f && length(center, new float[]{5, 6, 7}) < 1e-4f,
                "placement scales and moves the splat: " + length(c0, c1));
    }

    // 奥 (カメラから遠い) から手前へ並べる。同じ距離のものは元の順を保つ
    private static void sorting() {
        final int count = 7;
        final float[] distances = {3, 9, 1, 9, 5, 2, 9};
        final float[] positions = new float[count * 3];
        final float[] colors = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        for (int i = 0; i < count; i++) {
            // カメラ (0, 0, 20) からの距離が distances[i] になる位置。横にずらしても距離は変えない
            final double side = i * 0.3;
            positions[i * 3] = (float) (Math.sin(side) * distances[i]);
            positions[i * 3 + 2] = (float) (20 - Math.cos(side) * distances[i]);
            colors[i * 3] = i / 255f;
            rotations[i * 4 + 3] = 1;
            scales[i * 3] = scales[i * 3 + 1] = scales[i * 3 + 2] = 0.01f;
            opacities[i] = 1;
        }
        final Output out = emit(new SplatCloud(count, positions, rotations, scales, opacities, colors, 0, new float[0]), identity(), 0, 0, 20);
        final StringBuilder order = new StringBuilder();
        for (int q = 0; q < count; q++) order.append((out.colors[q * 4] >> 16) & 0xFF);
        check("1364052".equals(order.toString()), "back-to-front order, stable for equal distances: " + order);
    }

    // 不透明度は SplatCloud の値 (シグモイドを通したあと) をそのまま頂点の色の A にする
    private static void opacity() {
        final Output out = emit(single(new float[3], new float[]{0, 0, 0, 1}, new float[]{1, 1, 1}, 0.5f, new float[]{1.2f, -0.1f, 0.5f}, 0, new float[0]),
                identity(), 0, 0, 10);
        final int argb = out.colors[0];
        check(argb == ((128 << 24) | (255 << 16) | 128), "opacity and clamped color: " + Integer.toHexString(argb));
    }
}
