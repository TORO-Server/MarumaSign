package marumasa.marumasa_sign.core.gltf.draco;

/**
 * 法線を正八面体に投影して 2 つの整数 (s, t) で表す方式の計算。
 * 正八面体を平面に開くと、中央のひし形が x が正の側、四隅の三角形が x が負の側になる。
 * 整数の計算は Java の int の桁あふれ (2 の補数で回る) をそのまま使う。元の実装も符号なしで回している。
 */
final class Octahedron {

    int quantizationBits = -1;
    int maxQuantizedValue = -1;
    int maxValue = -1;
    int center = -1;
    float dequantizationScale = 1f;

    boolean setQuantizationBits(int q) {
        if (q < 2 || q > 30) return false;
        quantizationBits = q;
        maxQuantizedValue = (1 << q) - 1;
        maxValue = maxQuantizedValue - 1;
        dequantizationScale = 2f / maxValue;
        center = maxValue / 2;
        return true;
    }

    // 縁の上の点を、同じ向きを表す決まった側へ寄せる
    void canonicalize(int s, int t, int[] out) {
        if ((s == 0 && t == 0) || (s == 0 && t == maxValue) || (s == maxValue && t == 0)) {
            s = maxValue;
            t = maxValue;
        } else if (s == 0 && t > center) {
            t = center - (t - center);
        } else if (s == maxValue && t < center) {
            t = center + (center - t);
        } else if (t == maxValue && s < center) {
            s = center + (center - s);
        } else if (t == 0 && s > center) {
            s = center - (s - center);
        }
        out[0] = s;
        out[1] = t;
    }

    /**
     * 成分の絶対値の和が center の整数ベクトルを (s, t) にする。
     */
    void integerVectorToCoords(int[] v, int[] out) {
        int s;
        int t;
        if (v[0] >= 0) {
            s = v[1] + center;
            t = v[2] + center;
        } else {
            s = v[1] < 0 ? Math.abs(v[2]) : maxValue - Math.abs(v[2]);
            t = v[2] < 0 ? Math.abs(v[1]) : maxValue - Math.abs(v[1]);
        }
        canonicalize(s, t, out);
    }

    /**
     * 成分の絶対値の和が center になるように縮める。
     */
    void canonicalizeIntegerVector(int[] v) {
        final long absSum = (long) Math.abs(v[0]) + (long) Math.abs(v[1]) + (long) Math.abs(v[2]);
        if (absSum == 0) {
            v[0] = center;
        } else {
            v[0] = (int) ((long) v[0] * center / absSum);
            v[1] = (int) ((long) v[1] * center / absSum);
            final int rest = center - Math.abs(v[0]) - Math.abs(v[1]);
            v[2] = v[2] >= 0 ? rest : -rest;
        }
    }

    /**
     * (s, t) から長さ 1 のベクトルに戻す。float の計算の順番は元の実装と同じにしてある。
     */
    void toUnitVector(int s, int t, float[] out) {
        float y = s * dequantizationScale - 1f;
        float z = t * dequantizationScale - 1f;
        final float x = 1f - Math.abs(y) - Math.abs(z);
        float offset = -x;
        offset = offset < 0 ? 0 : offset;
        y += y < 0 ? offset : -offset;
        z += z < 0 ? offset : -offset;
        final float norm = x * x + y * y + z * z;
        if (norm < 1e-6) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
        } else {
            final float d = 1f / (float) Math.sqrt(norm);
            out[0] = x * d;
            out[1] = y * d;
            out[2] = z * d;
        }
    }

    // 原点を中心にした (s, t) がひし形の内側か
    boolean isInDiamond(int s, int t) {
        final long st = ((Math.abs(s) & 0xffffffffL) + (Math.abs(t) & 0xffffffffL)) & 0xffffffffL;
        return st <= (center & 0xffffffffL);
    }

    // ひし形の内と外を、最も近い斜めの辺で折り返して入れ替える
    void invertDiamond(int[] p) {
        final int s = p[0];
        final int t = p[1];
        final int signS;
        final int signT;
        if (s >= 0 && t >= 0) {
            signS = 1;
            signT = 1;
        } else if (s <= 0 && t <= 0) {
            signS = -1;
            signT = -1;
        } else {
            signS = s > 0 ? 1 : -1;
            signT = t > 0 ? 1 : -1;
        }
        final int cornerS = signS * center;
        final int cornerT = signT * center;
        int us = s + s - cornerS;
        int ut = t + t - cornerT;
        if (signS * signT >= 0) {
            final int temp = us;
            us = -ut;
            ut = -temp;
        } else {
            final int temp = us;
            us = ut;
            ut = temp;
        }
        p[0] = (us + cornerS) / 2;
        p[1] = (ut + cornerT) / 2;
    }

    int modMax(int x) {
        if (x > center) return x - maxQuantizedValue;
        if (x < -center) return x + maxQuantizedValue;
        return x;
    }
}
