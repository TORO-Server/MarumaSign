package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.sign.SignImage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Random;

/**
 * SplatScene が出す四角形が、docs/reference.md の「描画の計算と、その正しさ」 に書いた性質を満たすことを確かめる。SplatRuntimeCheck から呼ぶ。
 * (楕円に外接する平行四辺形であること、薄いガウス分布でも形が崩れないこと、アルファテストで捨てられる部分だけを
 * 切り詰めていること、並べ替えずに求めたパーセンタイルが並べ替えたときと同じであること)
 */
final class SplatGeometryCheck {

    private static final int STRIDE = SignImage.Batch.STRIDE;
    private static final double CUTOFF = 2.5;
    private static final double MIN_SIGMA = 5e-4f;
    private static final MathContext EXACT = new MathContext(80);

    private static int failures;

    private SplatGeometryCheck() {
    }

    // 失敗の数を返す
    static int run() {
        failures = 0;
        conjugateDiameters();
        degenerateViews();
        shrinkTables();
        shrinkGeometry();
        neverDrawn();
        percentileBounds();
        backToFront();
        return failures;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + "splat: " + message);
        if (!condition) failures++;
    }

    private static SplatCloud single(float[] position, float[] rotation, float[] scale, float opacity) {
        return new SplatCloud(1, position.clone(), rotation.clone(), scale.clone(), new float[]{opacity},
                new float[]{1, 1, 1}, 0, new float[0]);
    }

    // ---------- 参照の計算 (倍精度と、桁落ちのない多倍長) ----------

    // 四元数 (x, y, z, w) から回転行列 (行優先の 3x3)
    private static double[] rotation(float[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        final double length = Math.sqrt(x * x + y * y + z * z + w * w);
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)};
    }

    // 配置したあとの共分散 A R S^2 R^T A^T + MIN_SIGMA^2 I (行優先の 3x3)。多倍長で求めるので、薄い分布でも桁が落ちない
    private static BigDecimal[] worldCovariance(float[] p, float[] q, float[] scale) {
        final double[] r = rotation(q);
        final BigDecimal[] ar = new BigDecimal[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int k = 0; k < 3; k++) {
                    sum = sum.add(new BigDecimal(p[k * 4 + row]).multiply(new BigDecimal(r[k * 3 + column]), EXACT), EXACT);
                }
                ar[row * 3 + column] = sum;
            }
        }
        final BigDecimal floor = new BigDecimal(MIN_SIGMA).multiply(new BigDecimal(MIN_SIGMA), EXACT);
        final BigDecimal[] sigma = new BigDecimal[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                BigDecimal sum = i == j ? floor : BigDecimal.ZERO;
                for (int k = 0; k < 3; k++) {
                    final BigDecimal variance = new BigDecimal(scale[k]).multiply(new BigDecimal(scale[k]), EXACT);
                    sum = sum.add(ar[i * 3 + k].multiply(ar[j * 3 + k], EXACT).multiply(variance, EXACT), EXACT);
                }
                sigma[i * 3 + j] = sum;
            }
        }
        return sigma;
    }

    // s^T adj(Sigma) s
    private static BigDecimal adjugateForm(BigDecimal[] m, double[] s) {
        final BigDecimal[] adj = new BigDecimal[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final int r1 = (j + 1) % 3, r2 = (j + 2) % 3, c1 = (i + 1) % 3, c2 = (i + 2) % 3;
                adj[i * 3 + j] = m[r1 * 3 + c1].multiply(m[r2 * 3 + c2], EXACT).subtract(m[r1 * 3 + c2].multiply(m[r2 * 3 + c1], EXACT), EXACT);
            }
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                sum = sum.add(adj[i * 3 + j].multiply(new BigDecimal(s[i]), EXACT).multiply(new BigDecimal(s[j]), EXACT), EXACT);
            }
        }
        return sum;
    }

    private static double[] worldCenter(float[] p, float[] position) {
        final double[] w = new double[3];
        for (int row = 0; row < 3; row++) {
            w[row] = (double) p[row] * position[0] + (double) p[4 + row] * position[1] + (double) p[8 + row] * position[2] + p[12 + row];
        }
        return w;
    }

    // 出力の 1 枚目の四角形から、中心と 2 本の辺の半分 (b1, b2) を取り出す
    private static double[][] quad(float[] vertices, int index) {
        final double[][] corner = new double[4][3];
        for (int k = 0; k < 4; k++) {
            for (int axis = 0; axis < 3; axis++) corner[k][axis] = vertices[(index * 4 + k) * STRIDE + axis];
        }
        final double[] center = new double[3], b1 = new double[3], b2 = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            center[axis] = (corner[0][axis] + corner[2][axis]) / 2;
            b1[axis] = (corner[1][axis] - corner[0][axis]) / 2;
            b2[axis] = (corner[3][axis] - corner[0][axis]) / 2;
        }
        return new double[][]{center, b1, b2};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * 四角形が、射影した共分散の楕円に外接する平行四辺形になっているかを調べ、ずれの大きさ (相対) を返す。
     * b1 b1^T + b2 b2^T = (CUTOFF k)^2 P Sigma P (P は視線に垂直な平面への射影) と、
     * |b1 x b2|^2 = (CUTOFF k)^4 s^T adj(Sigma) s / |s|^2 (行列式の式) の両方を見る。
     * 戻り値は {共分散のずれ, 行列式のずれ, 視線との垂直からのずれ, 向き (正なら反時計回り)}
     */
    private static double[] ellipseError(float[] p, float[] position, float[] q, float[] scale, double k,
                                         double[] view, float[] vertices) {
        final BigDecimal[] exact = worldCovariance(p, q, scale);
        final double[] sigma = new double[9];
        for (int i = 0; i < 9; i++) sigma[i] = exact[i].doubleValue();
        final double length = Math.sqrt(dot(view, view));
        final double[] s = {view[0] / length, view[1] / length, view[2] / length};
        final double radius2 = CUTOFF * CUTOFF * k * k;

        final double[][] parts = quad(vertices, 0);
        final double[] b1 = parts[1], b2 = parts[2];
        // P Sigma P
        final double[] projector = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) projector[i * 3 + j] = (i == j ? 1 : 0) - s[i] * s[j];
        }
        double worst = 0, largest = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double expected = 0;
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) expected += projector[i * 3 + a] * sigma[a * 3 + b] * projector[b * 3 + j];
                }
                expected *= radius2;
                worst = Math.max(worst, Math.abs(b1[i] * b1[j] + b2[i] * b2[j] - expected));
                largest = Math.max(largest, Math.abs(expected));
            }
        }
        final double[] normal = cross(b1, b2);
        final double determinant = adjugateForm(exact, s).doubleValue() * radius2 * radius2;
        final double perpendicular = Math.max(Math.abs(dot(b1, s)) / Math.sqrt(dot(b1, b1)), Math.abs(dot(b2, s)) / Math.sqrt(dot(b2, b2)));
        return new double[]{worst / largest, Math.abs(dot(normal, normal) - determinant) / determinant, perpendicular, dot(normal, s)};
    }

    private static float[] randomQuaternion(Random random) {
        return new float[]{(float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()};
    }

    // 回転と、軸ごとに違う拡大 (裏返しを含む) と、移動
    private static float[] randomPlacement(Random random) {
        final double[] r = rotation(randomQuaternion(random));
        final float[] p = new float[16];
        for (int column = 0; column < 3; column++) {
            final double scale = (0.2 + random.nextDouble() * 2.8) * (random.nextInt(4) == 0 ? -1 : 1);
            for (int row = 0; row < 3; row++) p[column * 4 + row] = (float) (r[row * 3 + column] * scale);
        }
        p[12] = (float) (random.nextGaussian() * 0.05);
        p[13] = (float) (random.nextGaussian() * 0.05);
        p[14] = (float) (random.nextGaussian() * 0.05);
        p[15] = 1;
        return p;
    }

    // いろいろな形 (大きさの比が 5 万倍まで) と配置とカメラで、外接する平行四辺形になっていることを確かめる
    private static void conjugateDiameters() {
        final Random random = new Random(20261124);
        final double[] worst = new double[3];
        final String[] example = {"", ""};
        boolean counterClockwise = true, centered = true, finite = true;
        for (int n = 0; n < 4000; n++) {
            final float[] position = {(float) (random.nextGaussian() * 0.02), (float) (random.nextGaussian() * 0.02), (float) (random.nextGaussian() * 0.02)};
            final float[] q = randomQuaternion(random);
            final float[] scale = new float[3];
            for (int k = 0; k < 3; k++) scale[k] = (float) Math.exp(Math.log(1e-4) + random.nextDouble() * Math.log(5e4));
            // 薄い円盤 (厚さ 0) と針 (2 軸が 0) も混ぜる
            if (n % 10 == 0) scale[random.nextInt(3)] = 0;
            if (n % 37 == 0) {
                final int keep = random.nextInt(3);
                for (int k = 0; k < 3; k++) if (k != keep) scale[k] = 0;
            }
            final float[] p = randomPlacement(random);
            final float[] camera = {(float) (random.nextGaussian() * 4), (float) (random.nextGaussian() * 4), (float) (random.nextGaussian() * 4)};
            final float[] vertices = new float[4 * STRIDE];
            final int[] colors = new int[4];
            new SplatScene(single(position, q, scale, 1)).emit(p, camera[0], camera[1], camera[2], vertices, colors);

            final double[] w = worldCenter(p, position);
            final double[] view = {camera[0] - w[0], camera[1] - w[1], camera[2] - w[2]};
            if (Math.sqrt(dot(view, view)) < 0.5) continue;
            for (float value : vertices) finite &= !Float.isNaN(value) && !Float.isInfinite(value);
            final double[] error = ellipseError(p, position, q, scale, 1, view, vertices);
            for (int k = 0; k < 3; k++) {
                if (error[k] > worst[k]) {
                    worst[k] = error[k];
                    if (k < 2) {
                        example[k] = "case " + n + ": scale " + Arrays.toString(scale) + ", rotation " + Arrays.toString(q)
                                + ", placement " + Arrays.toString(p) + ", camera " + Arrays.toString(camera);
                    }
                }
            }
            counterClockwise &= error[3] > 0;
            final double[] center = quad(vertices, 0)[0];
            centered &= Math.abs(center[0] - w[0]) + Math.abs(center[1] - w[1]) + Math.abs(center[2] - w[2]) < 1e-5;
        }
        final boolean shape = finite && worst[0] < 1e-4 && worst[2] < 1e-4 && counterClockwise && centered;
        check(shape, "quads are parallelograms of conjugate diameters of the projected ellipse: covariance error " + worst[0]
                + ", off-plane " + worst[2] + ", counter-clockwise " + counterClockwise + ", centered " + centered
                + (shape ? "" : " (" + example[0] + ")"));
        check(worst[1] < 2e-3, "the ellipse area follows s^T adj(Sigma) s even for needles and flat disks: error " + worst[1]
                + (worst[1] < 2e-3 ? "" : " (" + example[1] + ")"));
    }

    // 真上・真下からと、カメラがガウス分布の中心に重なるとき
    private static void degenerateViews() {
        final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        final float[] q = {0.3f, -0.2f, 0.5f, 0.8f};
        final float[] scale = {0.7f, 0.05f, 0.2f};
        double worst = 0;
        boolean front = true;
        for (float y : new float[]{6, -6}) {
            for (float side : new float[]{0, 1e-6f, -1e-5f}) {
                final float[] vertices = new float[4 * STRIDE];
                new SplatScene(single(new float[3], q, scale, 1)).emit(identity, side, y, side * 0.5f, vertices, new int[4]);
                final double[] error = ellipseError(identity, new float[3], q, scale, 1, new double[]{side, y, side * 0.5f}, vertices);
                worst = Math.max(worst, Math.max(error[0], Math.max(error[1], error[2])));
                front &= error[3] > 0;
            }
        }
        check(worst < 1e-4 && front, "looking straight down or up: error " + worst + ", counter-clockwise " + front);

        // カメラが中心に重なるときは、モデルの中心から見た向きを使う (1 つだけのときは +Z)。値が壊れないこと
        final float[] vertices = new float[4 * STRIDE];
        new SplatScene(single(new float[3], q, scale, 1)).emit(identity, 0, 0, 0, vertices, new int[4]);
        boolean finite = true;
        for (float value : vertices) finite &= !Float.isNaN(value) && !Float.isInfinite(value);
        final double[] error = ellipseError(identity, new float[3], q, scale, 1, new double[]{0, 0, 1}, vertices);
        check(finite && error[0] < 1e-4 && error[3] > 0, "camera at the centre of a splat: finite " + finite + ", error " + error[0]);
    }

    // ---------- アルファテストで捨てられる部分 ----------

    private static int texel(int[] texture, int x, int y) {
        final int size = SplatScene.TEXTURE_SIZE;
        return texture[Math.max(0, Math.min(size - 1, y)) * size + Math.max(0, Math.min(size - 1, x))] >>> 24;
    }

    // テクスチャ座標 (テクセル単位) での A。最近傍と、線形補間 (端は引き伸ばす) の大きいほう
    private static double sample(int[] texture, double x, double y) {
        final double nearest = texel(texture, (int) Math.floor(x), (int) Math.floor(y));
        final int x0 = (int) Math.floor(x - 0.5), y0 = (int) Math.floor(y - 0.5);
        final double fx = x - 0.5 - x0, fy = y - 0.5 - y0;
        final double linear = (texel(texture, x0, y0) * (1 - fx) + texel(texture, x0 + 1, y0) * fx) * (1 - fy)
                + (texel(texture, x0, y0 + 1) * (1 - fx) + texel(texture, x0 + 1, y0 + 1) * fx) * fy;
        return Math.max(nearest, linear);
    }

    /**
     * 切り詰めた四角形の外にある画素は、どの頂点の A でも、必ずアルファテストで捨てられる
     * (頂点の A とテクスチャの A の積が基準より小さい)。テクスチャ全体を細かく調べて確かめる。
     */
    private static void shrinkTables() {
        final int[] texture = SplatScene.gaussianTexture();
        final int size = SplatScene.TEXTURE_SIZE;
        final double half = size / 2.0;
        for (int product : new int[]{SplatScene.PRODUCT_TENTH, SplatScene.PRODUCT_MINIMAL}) {
            final SplatScene.Shrink shrink = SplatScene.shrink(product);
            int leaks = 0, hidden = 0, useless = 0;
            boolean ordered = true;
            for (int a = 0; a < 256; a++) {
                final double k = shrink.scale[a];
                if (a > 0) ordered &= shrink.scale[a] >= shrink.scale[a - 1];
                ordered &= shrink.uvLow[a] == 0.5f - 0.5f * shrink.scale[a] && shrink.uvHigh[a] == 0.5f + 0.5f * shrink.scale[a]
                        && shrink.radius[a] == CUTOFF * shrink.scale[a];
                boolean anyInside = false;
                for (double y = 0.125; y < size; y += 0.25) {
                    for (double x = 0.125; x < size; x += 0.25) {
                        final boolean inside = Math.max(Math.abs(x - half), Math.abs(y - half)) <= k * half;
                        final boolean drawn = a * sample(texture, x, y) >= product;
                        if (drawn && !inside) leaks++;
                        anyInside |= drawn && inside;
                    }
                }
                if (k == 0) hidden++;
                else if (!anyInside) useless++;
            }
            check(leaks == 0 && useless == 0 && ordered, "no drawn pixel lies outside the shrunk quad (cutoff product " + product + "): "
                    + leaks + " leaks, " + hidden + " alpha values are never drawn");
        }
        // 基準 0.1 では、不透明なものでも 2 割ほど、半分の濃さなら 4 割ほど面積が減る
        final SplatScene.Shrink tenth = SplatScene.shrink(SplatScene.PRODUCT_TENTH);
        check(tenth.scale[255] < 0.95f && tenth.scale[128] < 0.8f && tenth.scale[25] == 0f && tenth.scale[26] > 0f
                        && SplatScene.shrink(SplatScene.PRODUCT_MINIMAL).scale[255] == 1f,
                "shrink factors: opaque " + tenth.scale[255] + ", half " + tenth.scale[128]);
    }

    // 切り詰めた四角形は、位置とテクスチャ座標が同じ割合で縮む (どの点でも、切り詰める前と同じテクセルに当たる)
    private static void shrinkGeometry() {
        final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        final Random random = new Random(7);
        double worst = 0;
        for (int n = 0; n < 200; n++) {
            final float opacity = 0.11f + random.nextFloat() * 0.89f;
            final float[] q = randomQuaternion(random);
            final float[] scale = {0.05f + random.nextFloat(), 0.05f + random.nextFloat(), 0.05f + random.nextFloat()};
            final SplatCloud cloud = single(new float[3], q, scale, opacity);
            final float[] full = new float[4 * STRIDE], cut = new float[4 * STRIDE];
            // 基準を大きくしすぎない限り、MINIMAL では縮まない (不透明度が 0.11 以上)
            new SplatScene(cloud, SplatScene.PRODUCT_MINIMAL).emit(identity, 1, 2, 9, full, new int[4]);
            new SplatScene(cloud, SplatScene.PRODUCT_TENTH).emit(identity, 1, 2, 9, cut, new int[4]);
            final int a = Math.round(opacity * 255f);
            final double k = SplatScene.shrink(SplatScene.PRODUCT_TENTH).scale[a];
            if (SplatScene.shrink(SplatScene.PRODUCT_MINIMAL).scale[a] != 1f) continue;
            for (int corner = 0; corner < 4; corner++) {
                final int o = corner * STRIDE;
                for (int axis = 0; axis < 3; axis++) {
                    // 位置は、切り詰める前の頂点を中心 (原点) へ k 倍に縮めたもの
                    worst = Math.max(worst, Math.abs(cut[o + axis] - full[o + axis] * k));
                }
                // テクスチャ座標も、中心 (0.5, 0.5) へ k 倍に縮めたもの
                worst = Math.max(worst, Math.abs((cut[o + 3] - 0.5) - (full[o + 3] - 0.5) * k));
                worst = Math.max(worst, Math.abs((cut[o + 4] - 0.5) - (full[o + 4] - 0.5) * k));
            }
        }
        check(worst < 1e-5, "shrinking scales positions and texture coordinates together: " + worst);
    }

    // どの画素も描かれないガウス分布は、頂点を作らない。残りは元の順のまま、奥から手前へ並ぶ
    private static void neverDrawn() {
        final int count = 5;
        final float[] opacities = {1f, 0.05f, 0.6f, 0.098f, 0.3f};
        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] colors = new float[count * 3];
        for (int i = 0; i < count; i++) {
            positions[i * 3 + 2] = i;
            rotations[i * 4 + 3] = 1;
            scales[i * 3] = scales[i * 3 + 1] = scales[i * 3 + 2] = 0.1f;
            colors[i * 3] = (i + 1) / 255f;
        }
        final SplatCloud cloud = new SplatCloud(count, positions, rotations, scales, opacities, colors, 0, new float[0]);
        final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        final SplatScene tenth = new SplatScene(cloud, SplatScene.PRODUCT_TENTH);
        final float[] vertices = new float[count * 4 * STRIDE];
        final int[] argb = new int[count * 4];
        Arrays.fill(vertices, 9f);
        Arrays.fill(argb, -1);
        tenth.emit(identity, 0, 0, 20, vertices, argb);
        final StringBuilder order = new StringBuilder();
        for (int q = 0; q < count; q++) order.append((argb[q * 4] >> 16) & 0xFF);
        boolean cleared = true;
        for (int i = 3 * 4 * STRIDE; i < vertices.length; i++) cleared &= vertices[i] == 0f;
        check(tenth.visibleCount() == 3 && "13500".equals(order.toString()) && cleared,
                "splats that can never pass the alpha test are skipped: " + tenth.visibleCount() + " drawn, order " + order);
        check(new SplatScene(cloud, SplatScene.PRODUCT_MINIMAL).visibleCount() == 5 && new SplatScene(cloud).visibleCount() == 5,
                "nothing is skipped where the alpha test is 1/255 or the version is unknown");
    }

    // ---------- モデルの範囲と並べ替え ----------

    private static void percentileBounds() {
        final Random random = new Random(99);
        boolean same = true;
        String example = "";
        for (int count : new int[]{1, 2, 3, 7, 100, 101, 1000, 4097, 60000}) {
            for (int variant = 0; variant < 4; variant++) {
                final float[] positions = new float[count * 3];
                for (int i = 0; i < positions.length; i++) {
                    switch (variant) {
                        case 0:
                            positions[i] = (float) random.nextGaussian();
                            break;
                        case 1:
                            // 同じ値がたくさんある
                            positions[i] = random.nextInt(5) - 2;
                            break;
                        case 2:
                            // 桁の違う値と、符号つきの 0
                            positions[i] = random.nextInt(8) == 0 ? (random.nextBoolean() ? 0f : -0f)
                                    : (float) (random.nextGaussian() * Math.pow(10, random.nextInt(20) - 10));
                            break;
                        default:
                            positions[i] = Float.intBitsToFloat(random.nextInt() & 0xFF7FFFFF);
                            break;
                    }
                }
                final SplatCloud cloud = new SplatCloud(count, positions, new float[count * 4], new float[count * 3], new float[count],
                        new float[count * 3], 0, new float[0]);
                final float[] actual = new SplatScene(cloud).restBounds();
                final int lo = (int) Math.floor((count - 1) * 0.01), hi = (int) Math.ceil((count - 1) * 0.99);
                for (int axis = 0; axis < 3; axis++) {
                    final float[] sorted = new float[count];
                    for (int i = 0; i < count; i++) sorted[i] = positions[i * 3 + axis];
                    Arrays.sort(sorted);
                    if (Float.floatToIntBits(actual[axis]) != Float.floatToIntBits(sorted[lo])
                            || Float.floatToIntBits(actual[axis + 3]) != Float.floatToIntBits(sorted[hi])) {
                        same = false;
                        example = count + " values, variant " + variant + ", axis " + axis + ": " + actual[axis] + " / " + actual[axis + 3]
                                + " (expected " + sorted[lo] + " / " + sorted[hi] + ")";
                    }
                }
            }
        }
        check(same, "percentile bounds without sorting equal the sorted ones" + (same ? "" : ": " + example));
    }

    // たくさんのガウス分布が、奥から手前の順 (バケツ 1 つ分の幅までの誤差) に並ぶ
    private static void backToFront() {
        final Random random = new Random(5);
        final int count = 20000;
        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        for (int i = 0; i < count; i++) {
            for (int axis = 0; axis < 3; axis++) {
                positions[i * 3 + axis] = (float) random.nextGaussian();
                scales[i * 3 + axis] = 0.01f;
            }
            rotations[i * 4 + 3] = 1;
            opacities[i] = 1;
        }
        final SplatCloud cloud = new SplatCloud(count, positions, rotations, scales, opacities, new float[count * 3], 0, new float[0]);
        final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        final float[] vertices = new float[count * 4 * STRIDE];
        final float[] camera = {0.3f, -0.2f, 1.5f};
        new SplatScene(cloud).emit(identity, camera[0], camera[1], camera[2], vertices, new int[count * 4]);
        double nearest = Double.MAX_VALUE, farthest = 0;
        final double[] distance = new double[count];
        for (int q = 0; q < count; q++) {
            final double[] center = quad(vertices, q)[0];
            final double dx = camera[0] - center[0], dy = camera[1] - center[1], dz = camera[2] - center[2];
            distance[q] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            nearest = Math.min(nearest, distance[q]);
            farthest = Math.max(farthest, distance[q]);
        }
        final double bucket = (farthest - nearest) / 65535;
        double worst = 0;
        double behind = distance[0];
        for (int q = 1; q < count; q++) {
            // これまでに描いたどれよりも、バケツ 1 つ分より遠いものが後から出てはいけない
            worst = Math.max(worst, distance[q] - behind);
            behind = Math.min(behind, distance[q]);
        }
        check(worst <= bucket * 1.01 + 1e-5, "20000 splats come out back to front: worst inversion " + worst + " (bucket " + bucket + ")");
    }
}
