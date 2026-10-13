package marumasa.marumasa_sign.core.gltf;

/**
 * 4x4 行列とクォータニオンの計算。行列は glTF と同じ列優先の float[16]。
 */
final class Mat4 {

    private Mat4() {
    }

    static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    // out = a * b。out は a, b と別の配列であること
    static void multiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            final float b0 = b[column * 4], b1 = b[column * 4 + 1], b2 = b[column * 4 + 2], b3 = b[column * 4 + 3];
            out[column * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            out[column * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            out[column * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            out[column * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
    }

    // 平行移動 t、回転 r (クォータニオン x, y, z, w)、拡大 s から行列を作る
    static void compose(float[] t, float[] r, float[] s, float[] out) {
        final float x = r[0], y = r[1], z = r[2], w = r[3];
        final float xx = x * x, yy = y * y, zz = z * z;
        final float xy = x * y, xz = x * z, yz = y * z;
        final float wx = w * x, wy = w * y, wz = w * z;
        out[0] = (1 - 2 * (yy + zz)) * s[0];
        out[1] = (2 * (xy + wz)) * s[0];
        out[2] = (2 * (xz - wy)) * s[0];
        out[3] = 0;
        out[4] = (2 * (xy - wz)) * s[1];
        out[5] = (1 - 2 * (xx + zz)) * s[1];
        out[6] = (2 * (yz + wx)) * s[1];
        out[7] = 0;
        out[8] = (2 * (xz + wy)) * s[2];
        out[9] = (2 * (yz - wx)) * s[2];
        out[10] = (1 - 2 * (xx + yy)) * s[2];
        out[11] = 0;
        out[12] = t[0];
        out[13] = t[1];
        out[14] = t[2];
        out[15] = 1;
    }

    // 2 つのクォータニオンを球面線形補間する
    static void slerp(float[] a, int ai, float[] b, int bi, float t, float[] out) {
        float bx = b[bi], by = b[bi + 1], bz = b[bi + 2], bw = b[bi + 3];
        float cos = a[ai] * bx + a[ai + 1] * by + a[ai + 2] * bz + a[ai + 3] * bw;
        // 近いほうの向きを通るようにする
        if (cos < 0) {
            cos = -cos;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float wa, wb;
        if (cos > 0.9995f) {
            wa = 1 - t;
            wb = t;
        } else {
            final double angle = Math.acos(cos);
            final double sin = Math.sin(angle);
            wa = (float) (Math.sin((1 - t) * angle) / sin);
            wb = (float) (Math.sin(t * angle) / sin);
        }
        out[0] = a[ai] * wa + bx * wb;
        out[1] = a[ai + 1] * wa + by * wb;
        out[2] = a[ai + 2] * wa + bz * wb;
        out[3] = a[ai + 3] * wa + bw * wb;
        normalize4(out);
    }

    // 左上 3x3 の行列式。負なら面の向きが裏返る
    static float determinant3(float[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    // 法線を変換するための 3x3 行列 (左上 3x3 の余因子行列) を out[0..8] に列優先で入れる
    static void normalMatrix(float[] m, float[] out) {
        out[0] = m[5] * m[10] - m[6] * m[9];
        out[1] = m[6] * m[8] - m[4] * m[10];
        out[2] = m[4] * m[9] - m[5] * m[8];
        out[3] = m[2] * m[9] - m[1] * m[10];
        out[4] = m[0] * m[10] - m[2] * m[8];
        out[5] = m[1] * m[8] - m[0] * m[9];
        out[6] = m[1] * m[6] - m[2] * m[5];
        out[7] = m[2] * m[4] - m[0] * m[6];
        out[8] = m[0] * m[5] - m[1] * m[4];
    }

    static void normalize4(float[] q) {
        final float length = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (length > 0) {
            q[0] /= length;
            q[1] /= length;
            q[2] /= length;
            q[3] /= length;
        }
    }
}
