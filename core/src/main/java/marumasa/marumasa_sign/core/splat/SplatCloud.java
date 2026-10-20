package marumasa.marumasa_sign.core.splat;

/**
 * 読み込んだガウシアンスプラッティングのデータ。読み込んだあとは変更しない。
 *
 * <p>座標は glTF と同じ向き (+Y が上、モデルの正面が +Z) に直してある。
 * ファイルの形式ごとの座標系の違い (+Y が下の形式など) は、読み込むときに吸収する。
 * 向きを変えたときは、位置・回転・球面調和関数の係数をすべて同じように変換する。</p>
 */
public final class SplatCloud {

    // 球面調和関数の 0 次の係数を色にするときの定数
    public static final float SH_C0 = 0.28209479177387814f;

    // 次数ごとの、0 次を除いた係数の数 (1 色あたり)
    public static int restCoefficients(int degree) {
        return (degree + 1) * (degree + 1) - 1;
    }

    // ガウス分布の数
    public final int count;
    // 中心の位置 (x, y, z)
    public final float[] positions;
    // 回転を表す正規化した四元数 (x, y, z, w)
    public final float[] rotations;
    // 各軸の標準偏差 (対数ではなく、そのままの長さ)
    public final float[] scales;
    // 不透明度 (0 - 1。シグモイドを通したあとの値)
    public final float[] opacities;
    // 基本の色 (r, g, b)。0 次の係数を 0.5 + SH_C0 * f_dc で色にしたもの。0 - 1 の外に出ることもある
    public final float[] colors;
    // 視線の向きで変わる色の、球面調和関数の次数 (0 - 3)。0 なら shCoefficients は長さ 0
    public final int shDegree;
    // 1 次以上の係数。ガウス分布 i の k 番目 (0 始まり、1 次の 3 つ、2 次の 5 つ、3 次の 7 つの順) の色 c は
    // shCoefficients[(i * restCoefficients(shDegree) + k) * 3 + c]
    public final float[] shCoefficients;

    public SplatCloud(int count, float[] positions, float[] rotations, float[] scales, float[] opacities,
                      float[] colors, int shDegree, float[] shCoefficients) {
        this.count = count;
        this.positions = positions;
        this.rotations = rotations;
        this.scales = scales;
        this.opacities = opacities;
        this.colors = colors;
        this.shDegree = shDegree;
        this.shCoefficients = shCoefficients;
    }
}
