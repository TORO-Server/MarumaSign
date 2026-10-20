package marumasa.marumasa_sign.core.structure;

/**
 * 4x4 の変換 (行の順の float[16])。右から順に掛けたものが先に効く (Minecraft の PoseStack と同じ)。
 */
final class Transform {

    final float[] m;

    private Transform(float[] m) {
        this.m = m;
    }

    static Transform identity() {
        return new Transform(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    Transform copy() {
        return new Transform(m.clone());
    }

    // this = this * other
    Transform multiply(float[] other) {
        final float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += m[row * 4 + k] * other[k * 4 + column];
                result[row * 4 + column] = sum;
            }
        }
        System.arraycopy(result, 0, m, 0, 16);
        return this;
    }

    Transform translate(float x, float y, float z) {
        return multiply(new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1});
    }

    Transform scale(float x, float y, float z) {
        return multiply(new float[]{x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1});
    }

    // 軸のまわりに回す (度。右手系で、軸の正の向きから見て反時計回り)
    Transform rotateX(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return multiply(new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1});
    }

    Transform rotateY(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return multiply(new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1});
    }

    Transform rotateZ(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return multiply(new float[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    // 四元数 (x, y, z, w) の回転
    Transform rotateQuaternion(float x, float y, float z, float w) {
        final float length = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (length == 0) return this;
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        return multiply(new float[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0,
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0,
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0,
                0, 0, 0, 1});
    }

    // X, Y, Z の順の角度 (度) の回転 (Quaternionf.rotationXYZ と同じ。行列は X * Y * Z)
    Transform rotateXYZ(float x, float y, float z) {
        return rotateX(x).rotateY(y).rotateZ(z);
    }

    Transform multiply(Transform other) {
        return multiply(other.m);
    }

    // 点 (cx, cy, cz) を中心に、縦の軸で回す
    Transform rotateYAround(float degrees, float cx, float cy, float cz) {
        return translate(cx, cy, cz).rotateY(degrees).translate(-cx, -cy, -cz);
    }

    // 点を変換して out[offset..] に書く
    void apply(float x, float y, float z, float[] out, int offset) {
        out[offset] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[offset + 1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[offset + 2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    // 裏返す変換 (行列式が負) かどうか
    boolean flips() {
        final float det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
        return det < 0;
    }
}
