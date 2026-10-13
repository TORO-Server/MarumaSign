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
        // 三角形の頂点番号 (3 つで 1 枚)
        int[] indices;
        // 三角形の角ごとのテクスチャ座標。indices と同じ並びで、1 つにつき u, v
        float[] cornerUv;
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
    int triangleCount;
    // 陰影をテクスチャに焼き込んだ材質の数
    int bakedCount;

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
