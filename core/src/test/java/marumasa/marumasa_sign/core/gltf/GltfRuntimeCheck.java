package marumasa.marumasa_sign.core.gltf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 読み込んだモデルを動かす部分 (GltfScene) が、glTF 2.0 の仕様どおりの値を出すことを確かめる。
 * 期待する値は、仕様書の式から手で計算したもの。RuntimeLogicCheck から呼ぶ。
 * (アニメーションの補間、ノードの変換の順序、スキニング、モーフィング、KHR_animation_pointer、KHR_texture_transform)
 */
public final class GltfRuntimeCheck {

    private static final float EPSILON = 1e-4f;
    private static final float S45 = (float) Math.sqrt(0.5);

    private static int failures;

    private GltfRuntimeCheck() {
    }

    // 失敗の数を返す
    public static int run() throws IOException {
        failures = 0;
        nodeTransforms();
        matrixNode();
        linearAndWrap();
        stepAndSingleKey();
        cubicSpline();
        rotationSlerp();
        morphTargets();
        cubicSplineWeights();
        skinning();
        staticTextureTransform();
        animatedTextureTransform();
        materialColorPointer();
        emissivePointer();
        emptyModelBounds();
        return failures;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + "gltf: " + message);
        if (!condition) failures++;
    }

    private static boolean near(float[] actual, float... expected) {
        if (actual.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!(Math.abs(actual[i] - expected[i]) <= EPSILON)) return false;
        }
        return true;
    }

    // ---------- ファイルを作る ----------

    // バッファの中身と、bufferViews / accessors の JSON を集める
    private static final class Builder {
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        private final StringBuilder views = new StringBuilder();
        private final StringBuilder accessors = new StringBuilder();
        private int viewCount;
        private int accessorCount;

        private static int components(String type) {
            switch (type) {
                case "SCALAR": return 1;
                case "VEC2": return 2;
                case "VEC3": return 3;
                case "VEC4": return 4;
                case "MAT4": return 16;
                default: throw new IllegalArgumentException(type);
            }
        }

        private int view(byte[] data) {
            final int offset = bin.size();
            bin.write(data, 0, data.length);
            while (bin.size() % 4 != 0) bin.write(0);
            if (viewCount > 0) views.append(',');
            views.append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(data.length).append('}');
            return viewCount++;
        }

        private int accessor(int view, int componentType, int count, String type) {
            if (accessorCount > 0) accessors.append(',');
            accessors.append("{\"bufferView\":").append(view).append(",\"componentType\":").append(componentType)
                    .append(",\"count\":").append(count).append(",\"type\":\"").append(type).append("\"}");
            return accessorCount++;
        }

        int floats(String type, float... values) {
            final ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : values) buffer.putFloat(value);
            return accessor(view(buffer.array()), 5126, values.length / components(type), type);
        }

        int shorts(String type, int... values) {
            final ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) buffer.putShort((short) value);
            return accessor(view(buffer.array()), 5123, values.length / components(type), type);
        }

        // 画像を bufferView として入れ、その番号を返す
        int image(byte[] png) {
            return view(png);
        }

        GltfModel load(String rest) throws IOException {
            final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + bin.size() + "}],"
                    + "\"bufferViews\":[" + views + "],\"accessors\":[" + accessors + "]," + rest + "}";
            final byte[] text = json.getBytes(StandardCharsets.UTF_8);
            final int jsonLength = (text.length + 3) & ~3;
            final byte[] data = bin.toByteArray();
            final ByteBuffer out = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
            out.putInt(jsonLength).putInt(0x4E4F534A).put(text);
            for (int i = text.length; i < jsonLength; i++) out.put((byte) ' ');
            out.putInt(data.length).putInt(0x004E4942).put(data);
            return GltfLoader.load(out.array(), null);
        }
    }

    // 三角形 1 枚。頂点 0 は (1, 0, 0)
    private static final float[] TRIANGLE = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private static byte[] png(int width, int height) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < width * height; i++) image.setRGB(i % width, i / width, 0xFF000000 | i * 0x10307);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // 指定した時刻の姿勢で、変換なしで出力した頂点 (x, y, z, u, v)。最初のまとまりの index 番目
    private static float[] vertex(GltfScene scene, float time, int index) {
        scene.pose(time);
        final GltfScene.Group[] groups = scene.groups();
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
            colors[i] = new int[groups[i].vertexCount()];
        }
        scene.emit(Mat4.identity(), vertices, colors);
        return Arrays.copyOfRange(vertices[0], index * GltfScene.STRIDE, index * GltfScene.STRIDE + GltfScene.STRIDE);
    }

    private static float[] position(GltfScene scene, float time) {
        return Arrays.copyOf(vertex(scene, time, 0), 3);
    }

    // 最初の頂点の色 (ARGB)
    private static int color(GltfScene scene, float time) {
        scene.pose(time);
        final GltfScene.Group[] groups = scene.groups();
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
            colors[i] = new int[groups[i].vertexCount()];
        }
        scene.emit(Mat4.identity(), vertices, colors);
        return colors[0][0];
    }

    // ノード 0 の 1 つの値を動かすモデル
    private static GltfScene animated(String path, String interpolation, float[] times, String type, float[] values) throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int input = b.floats("SCALAR", times);
        final int output = b.floats(type, values);
        return new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "}}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + ",\"interpolation\":\"" + interpolation + "\"}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"" + path + "\"}}]}]"));
    }

    // ---------- 確かめること ----------

    // ノードの変換は T * R * S の順で、親の変換を左から掛ける
    private static void nodeTransforms() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final GltfScene scene = new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "}}]}],"
                + "\"nodes\":[{\"translation\":[10,0,0],\"children\":[1]},"
                + "{\"mesh\":0,\"translation\":[1,2,3],\"rotation\":[0,0," + S45 + "," + S45 + "],\"scale\":[2,1,1]}],"
                + "\"scenes\":[{\"nodes\":[0]}]"));
        // (1,0,0) を 2 倍 → (2,0,0)、Z 軸で 90 度 → (0,2,0)、移動 → (1,4,3)、親の移動 → (11,4,3)
        final float[] p = position(scene, 0);
        check(near(p, 11, 4, 3), "TRS order and parent transform: " + Arrays.toString(p));
    }

    // matrix で指定したノード (列優先)
    private static void matrixNode() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        // X 軸を Y 軸に移す回転と、(5, 6, 7) への移動
        final GltfScene scene = new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "}}]}],"
                + "\"nodes\":[{\"mesh\":0,\"matrix\":[0,1,0,0, -1,0,0,0, 0,0,1,0, 5,6,7,1]}],\"scenes\":[{\"nodes\":[0]}]"));
        final float[] p = position(scene, 0);
        check(near(p, 5, 7, 7), "matrix is column-major: " + Arrays.toString(p));
    }

    // 線形補間と、最後のキーを過ぎたときの繰り返し、最初のキーより前の値
    private static void linearAndWrap() throws IOException {
        final GltfScene scene = animated("translation", "LINEAR", new float[]{0, 1, 2}, "VEC3",
                new float[]{0, 0, 0, 1, 0, 0, 1, 2, 0});
        check(near(position(scene, 0.5f), 1.5f, 0, 0), "linear at 0.5: " + Arrays.toString(position(scene, 0.5f)));
        check(near(position(scene, 1.5f), 2, 1, 0), "linear at 1.5: " + Arrays.toString(position(scene, 1.5f)));
        check(near(position(scene, 2.5f), 1.5f, 0, 0), "animation loops after the last key: " + Arrays.toString(position(scene, 2.5f)));

        final GltfScene late = animated("translation", "LINEAR", new float[]{1, 2}, "VEC3", new float[]{3, 0, 0, 5, 0, 0});
        check(near(position(late, 0.5f), 4, 0, 0), "before the first key, the first value is held: " + Arrays.toString(position(late, 0.5f)));

        // 同じ時刻のキーが並ぶ (不連続な変化)。その時刻からは後のキーの値になる
        final GltfScene jump = animated("translation", "LINEAR", new float[]{0, 1, 1, 2}, "VEC3",
                new float[]{0, 0, 0, 1, 0, 0, 5, 0, 0, 6, 0, 0});
        check(near(position(jump, 1f), 6, 0, 0), "repeated key time jumps to the later key: " + Arrays.toString(position(jump, 1f)));
        check(near(position(jump, 1.5f), 6.5f, 0, 0), "after the repeated key: " + Arrays.toString(position(jump, 1.5f)));
    }

    private static void stepAndSingleKey() throws IOException {
        final GltfScene scene = animated("translation", "STEP", new float[]{0, 1, 2}, "VEC3",
                new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0});
        check(near(position(scene, 0.99f), 1, 0, 0), "step holds the previous key: " + Arrays.toString(position(scene, 0.99f)));
        check(near(position(scene, 1f), 2, 0, 0), "step changes at the key time: " + Arrays.toString(position(scene, 1f)));

        final GltfScene single = animated("scale", "LINEAR", new float[]{0.5f}, "VEC3", new float[]{3, 3, 3});
        check(near(position(single, 0), 3, 0, 0) && near(position(single, 7), 3, 0, 0), "single keyframe: " + Arrays.toString(position(single, 7)));
    }

    // 3 次スプライン: p(s) = h00 v0 + td h10 b0 + h01 v1 + td h11 a1 (td はキーの間の時間)
    private static void cubicSpline() throws IOException {
        // キー 0 (t = 0): 入りの接線 9, 値 0, 出の接線 1。キー 1 (t = 2): 入りの接線 3, 値 1, 出の接線 9
        final GltfScene scene = animated("translation", "CUBICSPLINE", new float[]{0, 2, 4}, "VEC3", new float[]{
                9, 0, 0, 0, 0, 0, 1, 0, 0,
                3, 0, 0, 1, 0, 0, 9, 0, 0,
                0, 0, 0, 1, 0, 0, 0, 0, 0});
        // s = 0.5: h00 = 0.5, h10 = 0.125, h01 = 0.5, h11 = -0.125。0 + 2 * 0.125 * 1 + 0.5 * 1 + 2 * -0.125 * 3 = 0 → x = 1 + 0
        check(near(position(scene, 1), 1, 0, 0), "cubic spline at the middle: " + Arrays.toString(position(scene, 1)));
        // s = 0.25: h00 = 0.84375, h10 = 0.140625, h01 = 0.15625, h11 = -0.046875
        // 2 * 0.140625 + 0.15625 - 2 * 0.046875 * 3 = 0.15625
        check(near(position(scene, 0.5f), 1.15625f, 0, 0), "cubic spline at a quarter: " + Arrays.toString(position(scene, 0.5f)));
        check(near(position(scene, 2), 2, 0, 0), "cubic spline at a key: " + Arrays.toString(position(scene, 2)));
    }

    // 回転は球面線形補間で、近いほうの向きを通る
    private static void rotationSlerp() throws IOException {
        final GltfScene scene = animated("rotation", "LINEAR", new float[]{0, 1}, "VEC4",
                new float[]{0, 0, 0, 1, 0, S45, 0, S45});
        // Y 軸で 45 度: (1,0,0) → (cos45, 0, -sin45)
        check(near(position(scene, 0.5f), S45, 0, -S45), "slerp halfway: " + Arrays.toString(position(scene, 0.5f)));
        // 22.5 度 (線形補間を正規化した値とは違う)
        final float c = (float) Math.cos(Math.PI / 8), s = (float) Math.sin(Math.PI / 8);
        check(near(position(scene, 0.25f), c, 0, -s), "slerp at a quarter: " + Arrays.toString(position(scene, 0.25f)));

        // 270 度の回転 (= -90 度) へは、短いほうの -90 度の向きに回る
        final float s135 = (float) Math.sin(Math.toRadians(135)), c135 = (float) Math.cos(Math.toRadians(135));
        final GltfScene shortest = animated("rotation", "LINEAR", new float[]{0, 1}, "VEC4",
                new float[]{0, 0, 0, 1, 0, s135, 0, c135});
        check(near(position(shortest, 0.5f), S45, 0, S45), "slerp takes the shortest path: " + Arrays.toString(position(shortest, 0.5f)));

        // STEP でも、長さが 1 でない値は正規化する
        final GltfScene step = animated("rotation", "STEP", new float[]{0}, "VEC4", new float[]{0, 0, 0, 2});
        check(near(position(step, 0), 1, 0, 0), "rotation keys are normalized: " + Arrays.toString(position(step, 0)));
    }

    private static void morphTargets() throws IOException {
        for (int variant = 0; variant < 3; variant++) {
            final Builder b = new Builder();
            final int position = b.floats("VEC3", TRIANGLE);
            final int delta = b.floats("VEC3", 0, 2, 0, 0, 2, 0, 0, 2, 0);
            final int input = b.floats("SCALAR", 0, 1);
            final int output = b.floats("SCALAR", 0, 1);
            final String node = variant == 1 ? "{\"mesh\":0,\"weights\":[0.25]}" : "{\"mesh\":0}";
            final String animation = variant == 2
                    ? ",\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + "}],"
                    + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"weights\"}}]}]" : "";
            final GltfScene scene = new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "},"
                    + "\"targets\":[{\"POSITION\":" + delta + "}]}],\"weights\":[0.5]}],"
                    + "\"nodes\":[" + node + "],\"scenes\":[{\"nodes\":[0]}]" + animation));
            final float time = variant == 2 ? 0.25f : 0;
            // メッシュの重み 0.5、ノードの重み 0.25、アニメーションの重み 0.25
            final float expected = variant == 0 ? 1 : 0.5f;
            final float[] p = position(scene, time);
            check(near(p, 1, expected, 0), "morph weights (" + (variant == 0 ? "mesh" : variant == 1 ? "node" : "animated") + "): " + Arrays.toString(p));
        }
    }

    // 3 次スプラインの重みは、キーごとに「入りの接線 (目標の数)、値 (目標の数)、出の接線 (目標の数)」
    private static void cubicSplineWeights() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int deltaA = b.floats("VEC3", 0, 1, 0, 0, 1, 0, 0, 1, 0);
        final int deltaB = b.floats("VEC3", 0, 0, 1, 0, 0, 1, 0, 0, 1);
        final int input = b.floats("SCALAR", 0, 1);
        final int output = b.floats("SCALAR", 0, 0, 0, 0, 1, 0, 0, 2, 1, 1, 0, 0);
        final GltfScene scene = new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "},"
                + "\"targets\":[{\"POSITION\":" + deltaA + "},{\"POSITION\":" + deltaB + "}]}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + ",\"interpolation\":\"CUBICSPLINE\"}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"weights\"}}]}]"));
        // A: 0.125 * 1 + 0.5 * 1 = 0.625。B: 0.5 * 1 - 0.125 * 2 = 0.25
        final float[] p = position(scene, 0.5f);
        check(near(p, 1, 0.625f, 0.25f), "cubic spline morph weights: " + Arrays.toString(p));
    }

    // 頂点 = Σ 重み * (ボーンの全体の変換 * 逆バインド行列) * 頂点。メッシュのノード自身の変換は使わない
    private static void skinning() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", 1, 1, 0, 1, 1, 0, 1, 1, 0);
        final int joints = b.shorts("VEC4", 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0);
        // 合計が 1 でない重みは、合計 1 に揃えて使う
        final int weights = b.floats("VEC4", 0.25f, 0.25f, 0, 0, 0.25f, 0.25f, 0, 0, 0.25f, 0.25f, 0, 0);
        final int inverseBind = b.floats("MAT4",
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1);
        final GltfScene scene = new GltfScene(b.load("\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position
                + ",\"JOINTS_0\":" + joints + ",\"WEIGHTS_0\":" + weights + "}}]}],"
                + "\"skins\":[{\"joints\":[1,2],\"inverseBindMatrices\":" + inverseBind + "}],"
                + "\"nodes\":[{\"mesh\":0,\"skin\":0,\"translation\":[100,0,0]},"
                + "{\"children\":[2]},{\"translation\":[0,1,0],\"rotation\":[0,0," + S45 + "," + S45 + "]}],"
                + "\"scenes\":[{\"nodes\":[0,1]}]"));
        // ボーン 0: (1,1,0)。ボーン 1: 逆バインドで (1,0,0)、Z 軸で 90 度 → (0,1,0)、移動 → (0,2,0)。平均は (0.5, 1.5, 0)
        final float[] p = position(scene, 0);
        check(near(p, 0.5f, 1.5f, 0), "skinning: " + Arrays.toString(p));
    }

    // テクスチャ座標が 0 - 1 の外に出る KHR_texture_transform は、読み込みで画像を並べて 0 - 1 に収める。
    // 描くときも、その収めた座標 (cornerUv) を使うこと
    private static void staticTextureTransform() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int uv = b.floats("VEC2", 0, 0, 1, 0, 0, 1);
        final int image = b.image(png(4, 4));
        final GltfModel model = b.load("\"images\":[{\"bufferView\":" + image + ",\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0,"
                + "\"extensions\":{\"KHR_texture_transform\":{\"scale\":[2,2]}}}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + ",\"TEXCOORD_0\":" + uv + "},\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}]");
        final GltfModel.Texture texture = model.textures[model.materials[0].texture];
        final GltfScene scene = new GltfScene(model);
        final float[] corner = vertex(scene, 0, 1);
        check(texture.width == 8 && near(new float[]{corner[3], corner[4]}, 1, 0),
                "static texture transform uses the tiled coordinates: texture " + texture.width + ", uv " + corner[3] + ", " + corner[4]);
    }

    // アニメーションで、元の (ファイルに書かれた) 貼り方と同じ値に戻ったときも、その値で貼る
    private static void animatedTextureTransform() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int uv = b.floats("VEC2", 0, 0, 0.5f, 0, 0, 0.5f);
        final int image = b.image(png(4, 4));
        final int input = b.floats("SCALAR", 0, 1, 2);
        final int output = b.floats("VEC2", 0.5f, 0, 0, 0, 0.25f, 0);
        final GltfScene scene = new GltfScene(b.load("\"images\":[{\"bufferView\":" + image + ",\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0,"
                + "\"extensions\":{\"KHR_texture_transform\":{\"offset\":[0.5,0]}}}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + ",\"TEXCOORD_0\":" + uv + "},\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + "}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"path\":\"pointer\",\"extensions\":{\"KHR_animation_pointer\":"
                + "{\"pointer\":\"/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset\"}}}}]}]"));
        float[] corner = vertex(scene, 0, 1);
        check(near(new float[]{corner[3], corner[4]}, 1, 0), "texture offset at its original value: " + corner[3] + ", " + corner[4]);
        corner = vertex(scene, 0.5f, 1);
        check(near(new float[]{corner[3], corner[4]}, 0.75f, 0), "animated texture offset: " + corner[3] + ", " + corner[4]);
        corner = vertex(scene, 1f, 1);
        check(near(new float[]{corner[3], corner[4]}, 0.5f, 0), "texture offset animated to zero: " + corner[3] + ", " + corner[4]);
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int display(float linear) {
        return Math.round((float) Math.pow(linear, 1 / 2.2) * 255f);
    }

    // KHR_animation_pointer の baseColorFactor は、ファイルの baseColorFactor と同じ線形の値。表示用の色に直して使う
    private static void materialColorPointer() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int input = b.floats("SCALAR", 0, 1, 2);
        final int output = b.floats("VEC4", 0.5f, 0.5f, 0.5f, 1, 0.25f, 0.25f, 0.25f, 1, 0.25f, 0.25f, 0.25f, 1);
        final GltfScene scene = new GltfScene(b.load("\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.5,0.5,0.5,1]},"
                + "\"extensions\":{\"KHR_materials_unlit\":{}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "},\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + "}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"path\":\"pointer\",\"extensions\":{\"KHR_animation_pointer\":"
                + "{\"pointer\":\"/materials/0/pbrMetallicRoughness/baseColorFactor\"}}}}]}]"));
        final int atStart = red(color(scene, 0));
        final int later = red(color(scene, 1));
        check(Math.abs(atStart - display(0.5f)) <= 1, "animated base color equal to the file's value: " + atStart + " (expected " + display(0.5f) + ")");
        check(Math.abs(later - display(0.25f)) <= 1, "animated base color: " + later + " (expected " + display(0.25f) + ")");
    }

    private static void emissivePointer() throws IOException {
        final Builder b = new Builder();
        final int position = b.floats("VEC3", TRIANGLE);
        final int input = b.floats("SCALAR", 0, 1);
        final int output = b.floats("VEC3", 0.2f, 0.2f, 0.2f, 0.5f, 0.5f, 0.5f);
        final GltfScene scene = new GltfScene(b.load("\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0,0,0,1]},"
                + "\"emissiveFactor\":[0.2,0.2,0.2],\"extensions\":{\"KHR_materials_unlit\":{}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" + position + "},\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[{\"input\":" + input + ",\"output\":" + output + "}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"path\":\"pointer\",\"extensions\":{\"KHR_animation_pointer\":"
                + "{\"pointer\":\"/materials/0/emissiveFactor\"}}}}]}]"));
        final int value = red(color(scene, 0));
        check(Math.abs(value - display(0.2f)) <= 1, "animated emissive color: " + value + " (expected " + display(0.2f) + ")");
    }

    // 三角形のないモデルでも、囲む直方体は有限の値 (看板に置くときの中心と大きさが NaN にならない)
    private static void emptyModelBounds() throws IOException {
        final Builder b = new Builder();
        b.floats("SCALAR", 0);
        GltfModel model;
        try {
            model = b.load("\"nodes\":[{}],\"scenes\":[{\"nodes\":[0]}]");
        } catch (IOException e) {
            check(true, "a model without triangles is rejected: " + e.getMessage());
            return;
        }
        final float[] box = new GltfScene(model).restBounds();
        boolean finite = true;
        for (float value : box) finite &= !Float.isNaN(value) && !Float.isInfinite(value);
        check(finite && box[0] <= box[3], "bounds of a model without triangles: " + Arrays.toString(box));
    }
}
