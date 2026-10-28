package marumasa.marumasa_sign.core.gltf;

/**
 * 読み込んだ glTF のモデル。読み込んだあとは変更しない。
 */
public final class GltfModel {

    public static final int ALPHA_OPAQUE = 0;
    public static final int ALPHA_MASK = 1;
    public static final int ALPHA_BLEND = 2;

    static final int PATH_TRANSLATION = 0;
    static final int PATH_ROTATION = 1;
    static final int PATH_SCALE = 2;
    static final int PATH_WEIGHTS = 3;

    static final int TARGET_NODE = 0;
    static final int TARGET_MATERIAL_COLOR = 1;
    static final int TARGET_MATERIAL_EMISSIVE = 2;
    static final int TARGET_MATERIAL_METALLIC = 3;
    static final int TARGET_MATERIAL_ROUGHNESS = 4;
    static final int TARGET_MATERIAL_TEX_OFFSET = 5;
    static final int TARGET_MATERIAL_TEX_SCALE = 6;
    static final int TARGET_MATERIAL_TEX_ROTATION = 7;
    static final int TARGET_LIGHT_COLOR = 8;
    static final int TARGET_LIGHT_INTENSITY = 9;

    public static final int LIGHT_DIRECTIONAL = 0;
    public static final int LIGHT_POINT = 1;
    public static final int LIGHT_SPOT = 2;

    public static final class Light {
        public int type;
        public final float[] color = {1, 1, 1};
        public float intensity = 1;
        public float range = 0;
        public float innerConeAngle = 0;
        public float outerConeAngle = 0.7853981633974483f;
    }

    static final int INTERPOLATION_LINEAR = 0;
    static final int INTERPOLATION_STEP = 1;
    static final int INTERPOLATION_CUBIC = 2;

    static final class Node {
        int parent = -1;
        int[] children = new int[0];
        float[] translation = {0, 0, 0};
        float[] rotation = {0, 0, 0, 1};
        float[] scale = {1, 1, 1};
        // 行列で指定されている場合の値。そうでなければ null
        float[] matrix;
        int mesh = -1;
        int skin = -1;
        int light = -1;
        // モーフィングの重みの初期値。指定がなければ null
        float[] weights;
    }

    static final class Primitive {
        int vertexCount;
        // 頂点ごとの値
        float[] positions;
        // 法線。指定がなければ null で、面ごとに計算する
        float[] normals;
        // 接線 (x, y, z と、従法線の向きを表す符号)。指定がなければ null
        float[] tangents;
        // 頂点の色 (表示用の色空間に直した r, g, b, a)。指定がなければ null
        float[] colors;
        // 頂点に影響するボーンの数 (0, 4, 8)
        int influences;
        int[] joints;
        float[] weights;
        // 三角形の頂点番号 (3 つで 1 枚)。quads が true なら四角形の頂点番号 (4 つで 1 枚、反時計回り)
        int[] indices;
        boolean quads;
        // 三角形の角ごとのテクスチャ座標。indices と同じ並びで、1 つにつき u, v
        float[] cornerUv;
        // 変換前の元のテクスチャ座標。動的テクスチャトランスフォームで使用
        float[] rawUv;
        int material;
        // モーフィングの変化量。使わなければ長さ 0
        float[][] morphPositions = new float[0][];
        float[][] morphNormals = new float[0][];
    }

    static final class Mesh {
        Primitive[] primitives = new Primitive[0];
        float[] weights;
    }

    static final class Skin {
        int[] joints = new int[0];
        // ボーンごとの 4x4 行列を並べたもの
        float[] inverseBind;
    }

    public static final class Material {
        // 描画に使うテクスチャの番号 (GltfModel.textures の添字)
        public int texture;
        // 頂点の色に掛ける色 (表示用の色空間)。テクスチャに焼き込んだ場合は白
        final float[] color = {1, 1, 1, 1};
        // 頂点の色に足す発光色。テクスチャに焼き込んだ場合は 0
        final float[] emissive = {0, 0, 0};
        float metallic = 1;
        float roughness = 1;
        public int alphaMode = ALPHA_OPAQUE;
        public boolean doubleSided;
        // 陰影を付けない
        boolean unlit;
        final float[] f0 = {0.04f, 0.04f, 0.04f};
        float clearcoat = 0;
        float clearcoatRoughness = 0;
        final float[] sheen = {0, 0, 0};
        float sheenRoughness = 0;
        float iridescence = 0;
        float iridescenceIor = 1.3f;
        float iridescenceThickness = 100f;
        float anisotropy = 0;
        final float[] diffuseTransmission = {0, 0, 0};
        float transmission = 0;
        float thickness = 0;
        final float[] attenuationColor = {1, 1, 1};
        float attenuationDistance = Float.POSITIVE_INFINITY;
        float texOffsetX = 0, texOffsetY = 0;
        float texScaleX = 1, texScaleY = 1;
        float texRotation = 0;
        // KHR_animation_pointer で動かす値の、ファイルに書かれた元の値 (光の強さに比例する値)。
        // 読み込みでは表示用の色に直してテクスチャや color に入れているので、GltfScene は動かした値とこれとの比を掛けて反映する
        final float[] baseFactor = {1, 1, 1, 1};
        final float[] emissiveFactor = {0, 0, 0};
        float emissiveStrength = 1;
        float metallicFactor = 1, roughnessFactor = 1;
        // 発光色を emissive (頂点の色に足す値) で表しているかどうか。テクスチャに焼き込んだ場合は false で、発光色は動かせない
        boolean emissiveInColor;
    }

    public static final class Texture {
        public final int width;
        public final int height;
        // ARGB
        public final int[] pixels;

        Texture(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    static final class Channel {
        int targetType = TARGET_NODE;
        int targetIndex;
        int node;
        int path;
        int interpolation;
        float[] times;
        float[] values;
        // キーフレーム 1 つあたりの要素数 (3 次スプラインの接線は含まない)
        int components;
    }

    static final class Animation {
        Channel[] channels = new Channel[0];
        float duration;
    }

    Node[] nodes = new Node[0];
    // 表示するシーンに含まれるノードを、親が先になる順に並べたもの
    int[] order = new int[0];
    Mesh[] meshes = new Mesh[0];
    Skin[] skins = new Skin[0];
    Material[] materials = new Material[0];
    Texture[] textures = new Texture[0];
    Animation[] animations = new Animation[0];
    Light[] lights = new Light[0];
    float[] sphericalHarmonics;
    int triangleCount;
    // 陰影をテクスチャに焼き込んだ材質の数
    int bakedCount;
    // 時間で絵が変わるテクスチャ
    TextureAnimation[] textureAnimations = new TextureAnimation[0];

    public TextureAnimation[] textureAnimations() {
        return textureAnimations;
    }

    GltfModel() {
    }

    public int textureCount() {
        return textures.length;
    }

    public Texture texture(int index) {
        return textures[index];
    }

    public int triangleCount() {
        return triangleCount;
    }

    public int bakedCount() {
        return bakedCount;
    }

    public boolean isAnimated() {
        return animations.length > 0;
    }
}
