package marumasa.marumasa_sign.core.gltf;

/**
 * 3D モデルの陰影の計算。金属らしさと粗さを使う glTF の材質の式を、決まった照明で計算する。
 * 照明はモデルに固定で、上からの空の明るさ、斜め上からの太陽、空と地面の映り込みからなる。
 * さらに、KHR_lights_punctual (点光源、スポットライト、方向光) や EXT_lights_image_based (球面調和関数の環境光)、
 * クリアコート、シーン、スペキュラ、イリデッセンス、異方性、拡散透過などの拡張を反映する。
 * 白い面の明るさが Minecraft のブロックに近くなるように調整してある。
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

    // シーン内に配置されたライトの情報
    static final class LightInstance {
        int type;
        float px, py, pz;
        float dx, dy, dz;
        float r, g, b;
        float intensity;
        float range;
        float innerCos, outerCos;
    }

    // 陰影の計算に使う追加パラメータ
    static final class Context {
        // 反射色 (非金属の F0、デフォルトは 0.04)
        float f0r = 0.04f, f0g = 0.04f, f0b = 0.04f;
        // クリアコート
        float clearcoat;
        float clearcoatRoughness;
        float cnx, cny, cnz;
        // シーン (微細繊維の散乱)
        float sheenR, sheenG, sheenB;
        float sheenRoughness;
        // 薄膜干渉 (イリデッセンス)
        float iridescence;
        float iridescenceIor = 1.3f;
        float iridescenceThickness = 100f;
        // 異方性
        float anisotropy;
        float tx, ty, tz;
        float bx, by, bz;
        // 拡散透過
        float diffuseTransR, diffuseTransG, diffuseTransB;
        // モデルの座標での頂点またはピクセルの位置
        float px, py, pz;
        // 配置されたライト
        LightInstance[] lights;
        int lightCount;
        // 球面調和関数の係数 (27 要素)
        float[] sphericalHarmonics;
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
     */
    static void shade(float r, float g, float b, float metallic, float roughness,
                      float nx, float ny, float nz, float occlusion, float[] out) {
        shade(r, g, b, metallic, roughness, nx, ny, nz, occlusion, null, out);
    }

    /**
     * 追加パラメータを考慮して面の色を計算する。
     */
    static void shade(float r, float g, float b, float metallic, float roughness,
                      float nx, float ny, float nz, float occlusion, Context ctx, float[] out) {
        metallic = metallic < 0 ? 0 : metallic > 1 ? 1 : metallic;
        roughness = roughness < 0.04f ? 0.04f : roughness > 1 ? 1 : roughness;

        // 見る人のほうを向いていない面は、裏から見ているものとして扱う
        final float nv = nx * VIEW_X + ny * VIEW_Y + nz * VIEW_Z;
        final float facing = nv < 0 ? -nv : nv;
        final float nl = Math.max(0f, nx * SUN_X + ny * SUN_Y + nz * SUN_Z);

        // 環境光 (球面調和関数がある場合はそれを用い、なければ空と地面の明るさ)
        float ambientR, ambientG, ambientB;
        if (ctx != null && ctx.sphericalHarmonics != null && ctx.sphericalHarmonics.length >= 27) {
            final float[] sh = new float[3];
            evalSh(ctx.sphericalHarmonics, nx, ny, nz, sh);
            ambientR = sh[0] * occlusion;
            ambientG = sh[1] * occlusion;
            ambientB = sh[2] * occlusion;
        } else {
            final float ambient = (GROUND + (SKY - GROUND) * (0.5f + 0.5f * ny)) * occlusion;
            ambientR = ambient;
            ambientG = ambient;
            ambientB = ambient;
        }

        // 拡散光
        float diffuseR = (ambientR + SUN * nl) * (1 - metallic);
        float diffuseG = (ambientG + SUN * nl) * (1 - metallic);
        float diffuseB = (ambientB + SUN * nl) * (1 - metallic);

        // 半透明拡散透過 (裏面からの太陽光)
        if (ctx != null && (ctx.diffuseTransR > 0 || ctx.diffuseTransG > 0 || ctx.diffuseTransB > 0)) {
            final float nlBack = Math.max(0f, -(nx * SUN_X + ny * SUN_Y + nz * SUN_Z));
            diffuseR += nlBack * SUN * ctx.diffuseTransR;
            diffuseG += nlBack * SUN * ctx.diffuseTransG;
            diffuseB += nlBack * SUN * ctx.diffuseTransB;
        }

        // 正面から見たときの反射率 (F0)
        float df0r = 0.04f, df0g = 0.04f, df0b = 0.04f;
        if (ctx != null) {
            df0r = ctx.f0r;
            df0g = ctx.f0g;
            df0b = ctx.f0b;
        }
        float f0r = df0r + (r - df0r) * metallic;
        float f0g = df0g + (g - df0g) * metallic;
        float f0b = df0b + (b - df0b) * metallic;

        // 薄膜干渉 (イリデッセンス)
        if (ctx != null && ctx.iridescence > 0) {
            final float eta = Math.max(1.0f, ctx.iridescenceIor);
            final float sinT2 = Math.max(0f, 1f - facing * facing) / (eta * eta);
            final float cosT = (float) Math.sqrt(Math.max(0f, 1f - sinT2));
            final float opd = 2f * eta * ctx.iridescenceThickness * cosT;
            // 赤 (650nm)、緑 (530nm)、青 (440nm) の干渉強度
            final float iridR = 0.5f + 0.5f * (float) Math.cos(6.2831853f * opd / 650.0f);
            final float iridG = 0.5f + 0.5f * (float) Math.cos(6.2831853f * opd / 530.0f);
            final float iridB = 0.5f + 0.5f * (float) Math.cos(6.2831853f * opd / 440.0f);
            f0r += (iridR - f0r) * ctx.iridescence;
            f0g += (iridG - f0g) * ctx.iridescence;
            f0b += (iridB - f0b) * ctx.iridescence;
        }

        // 太陽のハイライト
        float glareSun = 0;
        float fresnelSun = 0;
        if (nl > 0 && nv > 0) {
            float hx = SUN_X + VIEW_X, hy = SUN_Y + VIEW_Y, hz = SUN_Z + VIEW_Z;
            final float length = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
            hx /= length;
            hy /= length;
            hz /= length;
            final float nh = Math.max(0f, nx * hx + ny * hy + nz * hz);
            final float vh = Math.max(0f, VIEW_X * hx + VIEW_Y * hy + VIEW_Z * hz);

            if (ctx != null && Math.abs(ctx.anisotropy) > 0.01f && (ctx.tx != 0 || ctx.ty != 0 || ctx.tz != 0)) {
                glareSun = anisotropicGgx(ctx, nx, ny, nz, hx, hy, hz, nl, nv, roughness) * nl * SUN_GLARE;
            } else {
                glareSun = ggx(nh, nl, nv, roughness) * nl * SUN_GLARE;
            }
            fresnelSun = pow5(1 - vh);
        }

        float specR = glareSun * (f0r + (1 - f0r) * fresnelSun);
        float specG = glareSun * (f0g + (1 - f0g) * fresnelSun);
        float specB = glareSun * (f0b + (1 - f0b) * fresnelSun);

        // 追加のライト (KHR_lights_punctual)
        if (ctx != null && ctx.lights != null && ctx.lightCount > 0) {
            for (int i = 0; i < ctx.lightCount; i++) {
                final LightInstance light = ctx.lights[i];
                float lx, ly, lz, dist = 0, att = 1f;
                if (light.type == GltfModel.LIGHT_DIRECTIONAL) {
                    lx = -light.dx;
                    ly = -light.dy;
                    lz = -light.dz;
                } else {
                    lx = light.px - ctx.px;
                    ly = light.py - ctx.py;
                    lz = light.pz - ctx.pz;
                    dist = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
                    if (dist > 1e-4f) {
                        lx /= dist;
                        ly /= dist;
                        lz /= dist;
                    }
                    final float dEff = Math.max(dist, 0.08f);
                    att = 1f / (dEff * dEff);
                    if (light.range > 0) {
                        final float factor = dist / light.range;
                        final float factor4 = factor * factor * factor * factor;
                        att *= Math.max(0f, 1f - factor4);
                    }
                    if (light.type == GltfModel.LIGHT_SPOT) {
                        final float spotCos = -(lx * light.dx + ly * light.dy + lz * light.dz);
                        final float cone = (spotCos - light.outerCos) / Math.max(1e-4f, light.innerCos - light.outerCos);
                        final float spotAtt = Math.max(0f, Math.min(1f, cone));
                        att *= spotAtt * spotAtt;
                    }
                }
                if (att <= 0) continue;

                // 明るすぎないよう、緩やかに飽和させる
                final float e = light.intensity * att * 0.04f;
                final float factor = e / (1f + e * 0.25f);
                final float lr = light.r * factor;
                final float lg = light.g * factor;
                final float lb = light.b * factor;

                final float nli = Math.max(0f, nx * lx + ny * ly + nz * lz);
                diffuseR += lr * nli * (1 - metallic);
                diffuseG += lg * nli * (1 - metallic);
                diffuseB += lb * nli * (1 - metallic);

                if (ctx.diffuseTransR > 0 || ctx.diffuseTransG > 0 || ctx.diffuseTransB > 0) {
                    final float nlBack = Math.max(0f, -(nx * lx + ny * ly + nz * lz));
                    diffuseR += lr * nlBack * ctx.diffuseTransR;
                    diffuseG += lg * nlBack * ctx.diffuseTransG;
                    diffuseB += lb * nlBack * ctx.diffuseTransB;
                }

                if (nli > 0 && nv > 0) {
                    float hx = lx + VIEW_X, hy = ly + VIEW_Y, hz = lz + VIEW_Z;
                    final float hlen = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                    if (hlen > 0) {
                        hx /= hlen;
                        hy /= hlen;
                        hz /= hlen;
                        final float nh = Math.max(0f, nx * hx + ny * hy + nz * hz);
                        final float vh = Math.max(0f, VIEW_X * hx + VIEW_Y * hy + VIEW_Z * hz);
                        final float gLight = ggx(nh, nli, nv, roughness) * nli * 2.4f;
                        final float fLight = pow5(1 - vh);
                        specR += lr * gLight * (f0r + (1 - f0r) * fLight);
                        specG += lg * gLight * (f0g + (1 - f0g) * fLight);
                        specB += lb * gLight * (f0b + (1 - f0b) * fLight);
                    }
                }
            }
        }

        // 景色の映り込み
        final float reflectedY = 2 * nv * ny - VIEW_Y;
        float height = 0.5f + 0.5f * reflectedY;
        height = height < 0 ? 0 : height > 1 ? 1 : height;
        height = height * height * (3 - 2 * height);
        final float sharp = MIRROR_GROUND + (MIRROR_SKY - MIRROR_GROUND) * height;
        final float blurred = (GROUND + (SKY - GROUND) * (0.5f + 0.5f * ny)) * 1.6f;
        final float mirror = (sharp + (blurred - sharp) * roughness) * occlusion;
        final float grazing = pow5(1 - facing) * (1 - roughness);

        // シーン (微細繊維による散乱)
        if (ctx != null && (ctx.sheenR > 0 || ctx.sheenG > 0 || ctx.sheenB > 0)) {
            final float sheenRough = ctx.sheenRoughness < 0.04f ? 0.04f : ctx.sheenRoughness > 1f ? 1f : ctx.sheenRoughness;
            final float grazingSheen = (float) Math.pow(1f - facing, sheenRough * 3.5f + 0.5f);
            final float sheenLight = (ambientR + ambientG + ambientB) * 0.3333f + SUN * nl;
            diffuseR += ctx.sheenR * sheenLight * grazingSheen;
            diffuseG += ctx.sheenG * sheenLight * grazingSheen;
            diffuseB += ctx.sheenB * sheenLight * grazingSheen;
        }

        float outR = r * diffuseR + specR + mirror * (f0r + (1 - f0r) * grazing);
        float outG = g * diffuseG + specG + mirror * (f0g + (1 - f0g) * grazing);
        float outB = b * diffuseB + specB + mirror * (f0b + (1 - f0b) * grazing);

        // クリアコート
        if (ctx != null && ctx.clearcoat > 0) {
            float ccNx = ctx.cnx, ccNy = ctx.cny, ccNz = ctx.cnz;
            if (ccNx == 0 && ccNy == 0 && ccNz == 0) {
                ccNx = nx;
                ccNy = ny;
                ccNz = nz;
            }
            final float ccRoughness = ctx.clearcoatRoughness < 0.04f ? 0.04f : ctx.clearcoatRoughness > 1f ? 1f : ctx.clearcoatRoughness;
            final float ccNv = ccNx * VIEW_X + ccNy * VIEW_Y + ccNz * VIEW_Z;
            final float ccFacing = ccNv < 0 ? -ccNv : ccNv;
            final float ccNl = Math.max(0f, ccNx * SUN_X + ccNy * SUN_Y + ccNz * SUN_Z);

            float ccGlare = 0;
            float ccFresnelSun = 0;
            if (ccNl > 0 && ccNv > 0) {
                float hx = SUN_X + VIEW_X, hy = SUN_Y + VIEW_Y, hz = SUN_Z + VIEW_Z;
                final float length = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                hx /= length;
                hy /= length;
                hz /= length;
                final float nh = Math.max(0f, ccNx * hx + ccNy * hy + ccNz * hz);
                final float vh = Math.max(0f, VIEW_X * hx + VIEW_Y * hy + VIEW_Z * hz);
                ccGlare = ggx(nh, ccNl, ccNv, ccRoughness) * ccNl * SUN_GLARE;
                ccFresnelSun = pow5(1 - vh);
            }

            final float ccReflectedY = 2 * ccNv * ccNy - VIEW_Y;
            float ccHeight = 0.5f + 0.5f * ccReflectedY;
            ccHeight = ccHeight < 0 ? 0 : ccHeight > 1 ? 1 : ccHeight;
            ccHeight = ccHeight * ccHeight * (3 - 2 * ccHeight);
            final float ccSharp = MIRROR_GROUND + (MIRROR_SKY - MIRROR_GROUND) * ccHeight;
            final float ccBlurred = (GROUND + (SKY - GROUND) * (0.5f + 0.5f * ccNy)) * 1.6f;
            final float ccMirror = (ccSharp + (ccBlurred - ccSharp) * ccRoughness) * occlusion;
            final float ccGrazing = pow5(1 - ccFacing) * (1 - ccRoughness);

            final float ccF0 = 0.04f;
            final float ccSpecular = ccGlare * (ccF0 + (1 - ccF0) * ccFresnelSun) * ctx.clearcoat;
            final float ccFresnelEnv = (ccF0 + (1 - ccF0) * ccGrazing) * ctx.clearcoat;
            final float ccReflection = ccMirror * ccFresnelEnv;

            // クリアコートによる下層の遮蔽
            final float attenuation = 1f - ccFresnelEnv;
            outR = outR * attenuation + ccSpecular + ccReflection;
            outG = outG * attenuation + ccSpecular + ccReflection;
            outB = outB * attenuation + ccSpecular + ccReflection;
        }

        out[0] = outR;
        out[1] = outG;
        out[2] = outB;
    }

    private static float ggx(float nh, float nl, float nv, float roughness) {
        final float alpha = roughness * roughness;
        final float alpha2 = alpha * alpha;
        final float denominator = nh * nh * (alpha2 - 1) + 1;
        final float distribution = alpha2 / (3.14159265f * denominator * denominator);
        final float k = alpha / 2;
        final float visibility = 1 / (4 * (nl * (1 - k) + k) * (nv * (1 - k) + k));
        return Math.min(4f, distribution * visibility);
    }

    private static float anisotropicGgx(Context ctx, float nx, float ny, float nz,
                                        float hx, float hy, float hz, float nl, float nv, float roughness) {
        final float at = Math.max(0.001f, roughness * roughness * (1 + ctx.anisotropy));
        final float ab = Math.max(0.001f, roughness * roughness * (1 - ctx.anisotropy));
        final float th = ctx.tx * hx + ctx.ty * hy + ctx.tz * hz;
        final float bh = ctx.bx * hx + ctx.by * hy + ctx.bz * hz;
        final float nh = nx * hx + ny * hy + nz * hz;
        final float d1 = (th * th) / (at * at) + (bh * bh) / (ab * ab) + nh * nh;
        final float distribution = 1f / (3.14159265f * at * ab * d1 * d1);
        final float k = (at + ab) * 0.25f;
        final float visibility = 1 / (4 * (nl * (1 - k) + k) * (nv * (1 - k) + k));
        return Math.min(4f, distribution * visibility);
    }

    private static void evalSh(float[] sh, float nx, float ny, float nz, float[] outRgb) {
        // Ramamoorthi & Hanrahan (2001) の球面調和関数
        final float c1 = 0.429043f, c2 = 0.511664f, c3 = 0.743125f, c4 = 0.886227f, c5 = 0.247708f;
        for (int c = 0; c < 3; c++) {
            final float l00 = sh[c];
            final float l1_1 = sh[3 + c];
            final float l10 = sh[6 + c];
            final float l11 = sh[9 + c];
            final float l2_2 = sh[12 + c];
            final float l2_1 = sh[15 + c];
            final float l20 = sh[18 + c];
            final float l21 = sh[21 + c];
            final float l22 = sh[24 + c];

            final float val = c1 * l22 * (nx * nx - ny * ny)
                    + c3 * l20 * (nz * nz)
                    + c4 * l00 - c5 * l20
                    + 2f * c1 * (l2_2 * nx * ny + l21 * nx * nz + l2_1 * ny * nz)
                    + 2f * c2 * (l11 * nx + l1_1 * ny + l10 * nz);
            outRgb[c] = Math.max(0f, val * 0.318309886f); // 1 / pi
        }
    }

    private static float pow5(float value) {
        final float squared = value * value;
        return squared * squared * value;
    }
}
