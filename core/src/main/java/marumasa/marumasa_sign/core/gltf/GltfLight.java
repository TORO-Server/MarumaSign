package marumasa.marumasa_sign.core.gltf;

/**
 * 3D モデルの陰影の計算。金属らしさと粗さを使う glTF の材質の式を、決まった照明で計算する。
 * 照明はモデルに固定で、上からの空の明るさ、斜め上からの太陽、空と地面の映り込みからなる。
 * 白い面の明るさが Minecraft のブロックに近くなる (上面が最も明るく、側面、底面の順に暗い) ように調整してある。
 * 色の計算はすべて、光の強さに比例する値 (リニア) で行う。
 */
final class GltfLight {

    // 空と地面の明るさ
    private static final float SKY = 0.39f;
    private static final float GROUND = 0.21f;
    // 太陽の向きと明るさ
    private static final float SUN_X = 0.088f, SUN_Y = 0.894f, SUN_Z = 0.440f;
    private static final float SUN = 0.68f;
    // ハイライトに使う太陽の明るさ。実際の太陽は空よりずっと明るいので、強めにする
    private static final float SUN_GLARE = 2.4f;
    // 映り込む景色の明るさ
    private static final float MIRROR_SKY = 1.0f;
    private static final float MIRROR_GROUND = 0.16f;
    // 見る人の向き (モデルの正面の少し上から見る)
    private static final float VIEW_X = 0f, VIEW_Y = 0.196f, VIEW_Z = 0.981f;

    private static final float[] TO_LINEAR = new float[256];
    private static final byte[] TO_DISPLAY = new byte[4097];

    static {
        for (int i = 0; i < 256; i++) {
            TO_LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
        }
        for (int i = 0; i <= 4096; i++) {
            TO_DISPLAY[i] = (byte) Math.round(Math.pow(i / 4096.0, 1 / 2.2) * 255.0);
        }
    }

    private GltfLight() {
    }

    // 画像の色の値 (0 - 255) を、光の強さに比例する値にする
    static float toLinear(int value) {
        return TO_LINEAR[value & 0xFF];
    }

    static float toLinear(float display) {
        if (display <= 0) return 0;
        return (float) Math.pow(Math.min(display, 1f), 2.2);
    }

    // 光の強さに比例する値を、画像の色の値 (0 - 255) にする
    static int toDisplay(float linear) {
        if (!(linear > 0)) return 0;
        if (linear >= 1) return 255;
        return TO_DISPLAY[(int) (linear * 4096f + 0.5f)] & 0xFF;
    }

    /**
     * 面の色を計算する。発光は含まない。
     *
     * @param r         面の色 (リニア)。結果は out[0..2] に入る
     * @param metallic  金属らしさ (0 - 1)
     * @param roughness 粗さ (0 - 1)
     * @param nx        面の外側を向いた、長さ 1 の法線 (モデルの座標)
     * @param occlusion 周りの形で光が遮られる度合い (1 で遮られない)
     */
    static void shade(float r, float g, float b, float metallic, float roughness,
                      float nx, float ny, float nz, float occlusion, float[] out) {
        metallic = metallic < 0 ? 0 : metallic > 1 ? 1 : metallic;
        roughness = roughness < 0.04f ? 0.04f : roughness > 1 ? 1 : roughness;

        // 見る人のほうを向いていない面は、裏から見ているものとして扱う
        float nv = nx * VIEW_X + ny * VIEW_Y + nz * VIEW_Z;
        final float facing = nv < 0 ? -nv : nv;
        final float nl = Math.max(0f, nx * SUN_X + ny * SUN_Y + nz * SUN_Z);

        // 空と地面からの光と、太陽の光
        final float ambient = (GROUND + (SKY - GROUND) * (0.5f + 0.5f * ny)) * occlusion;
        final float diffuse = (ambient + SUN * nl) * (1 - metallic);

        // 正面から見たときの反射率。金属は面の色、それ以外は 4%
        final float f0r = 0.04f + (r - 0.04f) * metallic;
        final float f0g = 0.04f + (g - 0.04f) * metallic;
        final float f0b = 0.04f + (b - 0.04f) * metallic;

        // 太陽のハイライト (GGX)
        float glare = 0;
        float fresnelSun = 0;
        if (nl > 0 && nv > 0) {
            float hx = SUN_X + VIEW_X, hy = SUN_Y + VIEW_Y, hz = SUN_Z + VIEW_Z;
            final float length = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
            hx /= length;
            hy /= length;
            hz /= length;
            final float nh = Math.max(0f, nx * hx + ny * hy + nz * hz);
            final float vh = Math.max(0f, VIEW_X * hx + VIEW_Y * hy + VIEW_Z * hz);
            final float alpha = roughness * roughness;
            final float alpha2 = alpha * alpha;
            final float denominator = nh * nh * (alpha2 - 1) + 1;
            final float distribution = alpha2 / (3.14159265f * denominator * denominator);
            final float k = alpha / 2;
            final float visibility = 1 / (4 * (nl * (1 - k) + k) * (nv * (1 - k) + k));
            glare = Math.min(4f, distribution * visibility) * nl * SUN_GLARE;
            fresnelSun = pow5(1 - vh);
        }

        // 景色の映り込み。粗い面ほどぼやけて、面が受ける光の明るさに近づく
        final float reflectedY = 2 * nv * ny - VIEW_Y;
        float height = 0.5f + 0.5f * reflectedY;
        height = height < 0 ? 0 : height > 1 ? 1 : height;
        height = height * height * (3 - 2 * height);
        final float sharp = MIRROR_GROUND + (MIRROR_SKY - MIRROR_GROUND) * height;
        final float blurred = (GROUND + (SKY - GROUND) * (0.5f + 0.5f * ny)) * 1.6f;
        final float mirror = (sharp + (blurred - sharp) * roughness) * occlusion;
        // 浅い角度で見るほど、よく映り込む
        final float grazing = pow5(1 - facing) * (1 - roughness);

        out[0] = r * diffuse + glare * (f0r + (1 - f0r) * fresnelSun) + mirror * (f0r + (1 - f0r) * grazing);
        out[1] = g * diffuse + glare * (f0g + (1 - f0g) * fresnelSun) + mirror * (f0g + (1 - f0g) * grazing);
        out[2] = b * diffuse + glare * (f0b + (1 - f0b) * fresnelSun) + mirror * (f0b + (1 - f0b) * grazing);
    }

    private static float pow5(float value) {
        final float squared = value * value;
        return squared * squared * value;
    }
}
