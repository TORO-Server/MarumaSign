package marumasa.marumasa_sign.core.splat;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Locale;

/**
 * ファイルの座標の向き (RUB、RDF、RFU などの 3 文字) と、その向きから SplatCloud の座標 (RUB) への変換。
 *
 * <p>名前は 1 文字目が +X、2 文字目が +Y、3 文字目が +Z の向き (R 右、L 左、U 上、D 下、F 奥、B 手前)。
 * .spz は仕様では RUB だが、変換せずに書く道具 (splat-transform 3.x、Spark など) は .ply の座標 (RDF) のまま書くので、
 * (1) バージョン 4 の拡張 SPZ_ADOBE_coordinate_system があればそれに従い、(2) なければ中身から上を推定し (guessUp)、
 * (3) それでも分からなければ仕様どおり RUB とする。看板の URL の「#coord=RDF」は、どの形式でもこれらより優先する。</p>
 */
final class SplatCoordinates {

    /** SPZ の CoordinateSystem (nianticlabs/spz の splat-types.h) の値の名前。添字が値 (0 は UNSPECIFIED) */
    static final String[] COORDINATE_SYSTEMS = {
            "", "LDB", "RDB", "LUB", "RUB", "LDF", "RDF", "LUF", "RUF",
            "LFD", "RFD", "LFU", "RFU", "LBD", "RBD", "LBU", "RBU",
    };

    // 方向変換の SH の係数を求めるときに使う向きの数 (どの次数でも、係数の数 7 より十分に多く)
    private static final int SAMPLE_DIRECTIONS = 32;

    private SplatCoordinates() {
    }

    /**
     * 座標の名前から、その座標の点を RUB に移す 3x3 の行列 (列優先、9 個)。正しくない名前なら null。
     * 列 i は、ファイルの i 番目の軸が RUB でどちらを向くか。左右が逆の座標では行列式が -1 (鏡像) になる
     */
    static double[] matrix(String name) {
        if (name == null || name.length() != 3) return null;
        final String up = name.toUpperCase(Locale.ROOT);
        if (up.charAt(0) != 'R' && up.charAt(0) != 'L') return null;
        final double[] m = new double[9];
        int used = 0;
        for (int i = 0; i < 3; i++) {
            final int axis;
            final double sign;
            switch (up.charAt(i)) {
                case 'R': axis = 0; sign = 1; break;
                case 'L': axis = 0; sign = -1; break;
                case 'U': axis = 1; sign = 1; break;
                case 'D': axis = 1; sign = -1; break;
                case 'B': axis = 2; sign = 1; break;
                case 'F': axis = 2; sign = -1; break;
                default: return null;
            }
            // 同じ軸 (UD、FB) を 2 回使うと座標にならない
            if ((used & (1 << axis)) != 0) return null;
            used |= 1 << axis;
            m[i * 3 + axis] = sign;
        }
        return m;
    }

    /** 看板の URL の「#coord=XYZ」(ほかの指定と & で並べてもよい。coordinates= でも)。なければ、または正しくなければ null */
    static String fromUrl(String url) {
        if (url == null) return null;
        final int hash = url.indexOf('#');
        if (hash < 0) return null;
        for (String part : url.substring(hash + 1).split("&")) {
            final int eq = part.indexOf('=');
            if (eq < 0) continue;
            final String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (!key.equals("coord") && !key.equals("coordinates")) continue;
            String value = part.substring(eq + 1).trim();
            try {
                value = URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                return null;
            }
            return matrix(value) != null ? value.toUpperCase(Locale.ROOT) : null;
        }
        return null;
    }

    /**
     * 中身から上の向きを推定する (宣言のない .spz 用)。+Y が上なら "RUB"、+Y が下なら "RDF"、分からなければ null。
     * 撮影したものの多くは、床・机・皿のような水平な面の上にある。平たい分布 (いちばん短い軸が残りより十分に短い) のうち、
     * 面の向きが Y 軸に近いものの面積が、Y の範囲の下の端と上の端のどちらに多いかで決める。差が小さいときは、中央より上か下かでも見る。
     * 実物の .spz (Spark の作例 10 個。どれも RDF) では 8 個が正しく、1 個 (床のない蝶) は分からず、
     * 1 個 (上に明かりが多い夜の通り) は外れた。中身に手がかりのないもの (棒だけの試験の場面など) は分からない。
     * cloud はファイルの座標のまま (変換する前) のもの
     */
    static String guessUp(SplatCloud cloud) {
        final int n = cloud.count;
        if (n < 16) return null;
        final float[] positions = cloud.positions, scales = cloud.scales, quats = cloud.rotations, opacity = cloud.opacities;
        final float[] sorted = new float[n];
        for (int i = 0; i < n; i++) sorted[i] = positions[i * 3 + 1];
        Arrays.sort(sorted);
        final float lo = sorted[(int) Math.floor(n * 0.02)], hi = sorted[(int) Math.floor(n * 0.98)];
        final float med = sorted[n >> 1];
        final double spread = Math.max(1e-9, (sorted[(int) Math.floor(n * 0.75)] - sorted[(int) Math.floor(n * 0.25)]) / 2.0);
        final double band = (hi - lo) * 0.15;
        if (!(band > 0)) return null;
        double total = 0, horizontal = 0, low = 0, high = 0, side = 0;
        for (int i = 0; i < n; i++) {
            final float sx = Math.abs(scales[i * 3]), sy = Math.abs(scales[i * 3 + 1]), sz = Math.abs(scales[i * 3 + 2]);
            // いちばん短い軸と、残りの 2 つ
            int a = 0;
            float s0 = sx, s1 = sy, s2 = sz;
            if (sy < s0) {
                a = 1;
                s0 = sy;
                s1 = sx;
                s2 = sz;
            }
            if (sz < s0) {
                a = 2;
                s0 = sz;
                s1 = sx;
                s2 = sy;
            }
            final double mid = Math.min(s1, s2), big = Math.max(s1, s2);
            final double w = opacity[i] * mid * big;
            if (!(w > 0)) continue;
            total += w;
            if (s0 > 0.35 * mid || opacity[i] < 0.3) continue;
            // 短い軸 (面の向き) の Y 成分: 回転行列の a 列目の y
            final double qx = quats[i * 4], qy = quats[i * 4 + 1], qz = quats[i * 4 + 2], qw = quats[i * 4 + 3];
            final double len = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (!(len > 0)) continue;
            final double x = qx / len, y = qy / len, z = qz / len, ww = qw / len;
            final double ny = a == 0 ? 2 * (x * y + ww * z) : a == 1 ? 1 - 2 * (x * x + z * z) : 2 * (y * z - ww * x);
            if (Math.abs(ny) < 0.85) continue;
            horizontal += w;
            final float yy = positions[i * 3 + 1];
            if (yy <= lo + band) low += w;
            if (yy >= hi - band) high += w;
            side += w * Math.max(-2, Math.min(2, (yy - med) / spread));
        }
        if (!(total > 0) || horizontal < 0.02 * total) return null;
        // 端の比べ方 (+ なら上の端に水平な面が多い = ファイルの +Y は下)
        if (low + high >= 0.01 * total) {
            final double extreme = (high - low) / (high + low);
            if (Math.abs(extreme) >= 0.25) return extreme > 0 ? "RDF" : "RUB";
        }
        final double center = side / horizontal;
        if (Math.abs(center) >= 0.5) return center > 0 ? "RDF" : "RUB";
        return null;
    }

    /**
     * 座標の名前が from のデータを、to の座標に直す (位置・回転・球面調和関数の係数をその場で書き換える)。
     * 同じ向きなら何もしない
     */
    static void convert(SplatCloud cloud, String from, String to) {
        final double[] a = matrix(from), b = matrix(to);
        if (a == null || b == null) throw new IllegalArgumentException("Invalid coordinate system: " + from + " -> " + to);
        // from → RUB → to。b は符号付きの置換なので、逆行列は転置
        final double[] m = new double[9];
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += b[r * 3 + k] * a[c * 3 + k];
                m[c * 3 + r] = v;
            }
        }
        transform(cloud, m);
    }

    /**
     * 直交行列 m (列優先。鏡像を含んでもよい) で、位置・回転・球面調和関数の係数をその場で書き換える。
     * 鏡像 (行列式が -1) のときも、分布の形 (共分散) は -m (回転) で移したものと同じなので、回転は -m で移す。
     * 視線の向きで変わる色は、新しい向き d から見た色が元の向き m^T d から見た色と同じになるように係数を組み替える
     */
    static void transform(SplatCloud cloud, double[] m) {
        boolean identity = true;
        for (int i = 0; i < 9; i++) {
            if (Math.abs(m[i] - (i % 4 == 0 ? 1 : 0)) > 1e-9) identity = false;
        }
        if (identity) return;

        final int count = cloud.count;
        final float[] p = cloud.positions;
        for (int i = 0; i < count; i++) {
            final double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            p[i * 3] = (float) (m[0] * x + m[3] * y + m[6] * z);
            p[i * 3 + 1] = (float) (m[1] * x + m[4] * y + m[7] * z);
            p[i * 3 + 2] = (float) (m[2] * x + m[5] * y + m[8] * z);
        }

        final double det = m[0] * (m[4] * m[8] - m[7] * m[5])
                - m[3] * (m[1] * m[8] - m[7] * m[2])
                + m[6] * (m[1] * m[5] - m[4] * m[2]);
        final double s = det < 0 ? -1 : 1;
        final double[] q = rotationQuaternion(s * m[0], s * m[1], s * m[2], s * m[3], s * m[4], s * m[5], s * m[6], s * m[7], s * m[8]);
        final float[] r = cloud.rotations;
        for (int i = 0; i < count; i++) {
            final int o = i * 4;
            final double x2 = r[o], y2 = r[o + 1], z2 = r[o + 2], w2 = r[o + 3];
            // q を左から掛ける
            r[o] = (float) (q[3] * x2 + q[0] * w2 + q[1] * z2 - q[2] * y2);
            r[o + 1] = (float) (q[3] * y2 - q[0] * z2 + q[1] * w2 + q[2] * x2);
            r[o + 2] = (float) (q[3] * z2 + q[0] * y2 - q[1] * x2 + q[2] * w2);
            r[o + 3] = (float) (q[3] * w2 - q[0] * x2 - q[1] * y2 - q[2] * z2);
        }

        final int degree = cloud.shDegree;
        if (degree <= 0) return;
        final int rest = SplatCloud.restCoefficients(degree);
        final float[] sh = cloud.shCoefficients;
        final double[] in = new double[7];
        for (int l = 1; l <= degree; l++) {
            final double[][] t = shTransform(l, m);
            final int first = l * l - 1, size = 2 * l + 1;
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < 3; c++) {
                    for (int k = 0; k < size; k++) in[k] = sh[(i * rest + first + k) * 3 + c];
                    for (int j = 0; j < size; j++) {
                        double v = 0;
                        for (int k = 0; k < size; k++) v += t[j][k] * in[k];
                        sh[(i * rest + first + j) * 3 + c] = (float) v;
                    }
                }
            }
        }
    }

    // 回転行列 (列優先の成分) の四元数 (x, y, z, w)
    private static double[] rotationQuaternion(double m00, double m10, double m20, double m01, double m11, double m21,
                                               double m02, double m12, double m22) {
        final double trace = m00 + m11 + m22;
        final double x, y, z, w;
        if (trace > 0) {
            final double s = Math.sqrt(trace + 1) * 2;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            final double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            final double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            final double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        return new double[]{x, y, z, w};
    }

    /**
     * 次数 l の係数 (2l+1 個) の組み替え。新しい係数 c'_j = Σ_k t[j][k] c_k。
     * どの向き d でも Σ c'_j Y_j(d) = Σ c_k Y_k(m^T d) となる t を、いくつもの向きで最小二乗に解いて求める
     * (直交変換で同じ次数の基底どうしに閉じているので、誤差は丸めの分だけ)
     */
    static double[][] shTransform(int l, double[] m) {
        final int size = 2 * l + 1;
        // 正規方程式 (B^T B) X = B^T T。B[k][j] = Y_j(d_k)、T[k][i] = Y_i(m^T d_k)
        final double[][] ata = new double[size][size];
        final double[][] atb = new double[size][size];
        final double[] base = new double[size], moved = new double[size];
        for (int k = 0; k < SAMPLE_DIRECTIONS; k++) {
            // 球の上にほぼ均等に並べた向き
            final double y = 1 - 2 * (k + 0.5) / SAMPLE_DIRECTIONS, r = Math.sqrt(1 - y * y), a = k * 2.399963229728653;
            final double dx = r * Math.cos(a), dy = y, dz = r * Math.sin(a);
            basis(l, dx, dy, dz, base);
            // m^T d (m は列優先なので、列 c と d の内積)
            basis(l, m[0] * dx + m[1] * dy + m[2] * dz, m[3] * dx + m[4] * dy + m[5] * dz, m[6] * dx + m[7] * dy + m[8] * dz, moved);
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    ata[i][j] += base[i] * base[j];
                    atb[i][j] += base[i] * moved[j];
                }
            }
        }
        // X[j][i] = 基底 i を動かしたものに含まれる基底 j の量 (ガウスの消去法)
        final double[][] x = solve(ata, atb);
        final double[][] t = new double[size][size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double v = x[j][i];
                if (Math.abs(v) < 1e-9) v = 0;
                t[j][i] = v;
            }
        }
        return t;
    }

    // SplatScene が色を求めるときと同じ基底 (定数も含めて同じ。1 次は y, z, x の順)
    private static void basis(int l, double x, double y, double z, double[] out) {
        final double xx = x * x, yy = y * y, zz = z * z;
        if (l == 1) {
            out[0] = -0.4886025119029199 * y;
            out[1] = 0.4886025119029199 * z;
            out[2] = -0.4886025119029199 * x;
        } else if (l == 2) {
            out[0] = 1.0925484305920792 * x * y;
            out[1] = -1.0925484305920792 * y * z;
            out[2] = 0.31539156525252005 * (2 * zz - xx - yy);
            out[3] = -1.0925484305920792 * x * z;
            out[4] = 0.5462742152960396 * (xx - yy);
        } else {
            out[0] = -0.5900435899266435 * y * (3 * xx - yy);
            out[1] = 2.890611442640554 * x * y * z;
            out[2] = -0.4570457994644658 * y * (4 * zz - xx - yy);
            out[3] = 0.3731763325901154 * z * (2 * zz - 3 * xx - 3 * yy);
            out[4] = -0.4570457994644658 * x * (4 * zz - xx - yy);
            out[5] = 1.445305721320277 * z * (xx - yy);
            out[6] = -0.5900435899266435 * x * (xx - 3 * yy);
        }
    }

    // a X = b を解く (a は正定値の小さな行列)
    private static double[][] solve(double[][] a, double[][] b) {
        final int n = a.length;
        final double[][] m = new double[n][];
        final double[][] r = new double[n][];
        for (int i = 0; i < n; i++) {
            m[i] = a[i].clone();
            r[i] = b[i].clone();
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int i = col + 1; i < n; i++) if (Math.abs(m[i][col]) > Math.abs(m[pivot][col])) pivot = i;
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            tmp = r[col];
            r[col] = r[pivot];
            r[pivot] = tmp;
            final double d = m[col][col];
            for (int i = 0; i < n; i++) {
                if (i == col) continue;
                final double f = m[i][col] / d;
                if (f == 0) continue;
                for (int j = col; j < n; j++) m[i][j] -= f * m[col][j];
                for (int j = 0; j < r[i].length; j++) r[i][j] -= f * r[col][j];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < r[i].length; j++) r[i][j] /= m[i][i];
        }
        return r;
    }
}
