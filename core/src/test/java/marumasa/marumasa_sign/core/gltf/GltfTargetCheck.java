package marumasa.marumasa_sign.core.gltf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 看板の向きごとの出力先 (GltfScene.Target) が、変わった部分だけを計算し直しても、毎回すべてを計算したもの
 * (GltfScene.emit) と同じ値になることと、三角形をまとめた四角形 (QuadPairing) が、元と同じ三角形の集まりを
 * 描くことを確かめる。GltfRuntimeCheck から呼ぶ。
 */
final class GltfTargetCheck {

    private static final int STRIDE = GltfScene.STRIDE;
    private static int failures;

    private GltfTargetCheck() {
    }

    // 失敗の数を返す
    static int run() throws IOException {
        failures = 0;
        final List<String> names = new ArrayList<String>();
        final List<GltfModel> models = new ArrayList<GltfModel>();
        names.add("rigid nodes");
        models.add(rigidModel());
        names.add("skin, morph, vertex colours");
        models.add(deformingModel());
        names.add("flat shading, lights, unlit, blend");
        models.add(shadingModel());
        names.add("animated materials");
        models.add(materialModel());
        final File resources = new File("src/test/resources/gltf");
        final File[] files = resources.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (!file.getName().endsWith(".glb")) continue;
                names.add(file.getName());
                models.add(GltfLoader.load(Files.readAllBytes(file.toPath()), null));
            }
        }
        for (int i = 0; i < models.size(); i++) {
            incremental(names.get(i), models.get(i));
            paired(names.get(i), models.get(i), QuadPairing.MODE_ANY);
            paired(names.get(i), models.get(i), QuadPairing.MODE_PLANAR);
        }
        pairingCounts();
        matching();
        versions();
        return failures;
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + "gltf: " + message);
        if (!condition) failures++;
    }

    // ---------- ファイルを作る ----------

    private static final class Glb {
        private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        private final StringBuilder views = new StringBuilder();
        private final StringBuilder accessors = new StringBuilder();
        private int viewCount;
        private int accessorCount;

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

        int floats(String type, int components, float... values) {
            final ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : values) buffer.putFloat(value);
            return accessor(view(buffer.array()), 5126, values.length / components, type);
        }

        int shorts(String type, int components, int... values) {
            final ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) buffer.putShort((short) value);
            return accessor(view(buffer.array()), 5123, values.length / components, type);
        }

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

    // 格子のメッシュ。positions, normals, uvs, indices (頂点を共有する)
    private static final class Mesh {
        float[] positions, normals, uvs;
        int[] indices;
    }

    // ドーナツ形 (閉じた曲面。どの辺も 2 枚の三角形が共有する)
    private static Mesh torus(int rings, int segments) {
        final Mesh mesh = new Mesh();
        final int count = rings * segments;
        mesh.positions = new float[count * 3];
        mesh.normals = new float[count * 3];
        mesh.uvs = new float[count * 2];
        for (int r = 0; r < rings; r++) {
            final double a = r * 2 * Math.PI / rings;
            for (int s = 0; s < segments; s++) {
                final double b = s * 2 * Math.PI / segments;
                final int v = r * segments + s;
                final double nx = Math.cos(a) * Math.cos(b), ny = Math.sin(b), nz = Math.sin(a) * Math.cos(b);
                mesh.positions[v * 3] = (float) (Math.cos(a) * 1.0 + nx * 0.35);
                mesh.positions[v * 3 + 1] = (float) (ny * 0.35);
                mesh.positions[v * 3 + 2] = (float) (Math.sin(a) * 1.0 + nz * 0.35);
                mesh.normals[v * 3] = (float) nx;
                mesh.normals[v * 3 + 1] = (float) ny;
                mesh.normals[v * 3 + 2] = (float) nz;
                mesh.uvs[v * 2] = (float) r / rings;
                mesh.uvs[v * 2 + 1] = (float) s / segments;
            }
        }
        mesh.indices = new int[count * 6];
        int n = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                final int a = r * segments + s, b = r * segments + (s + 1) % segments;
                final int c = ((r + 1) % rings) * segments + s, d = ((r + 1) % rings) * segments + (s + 1) % segments;
                mesh.indices[n++] = a;
                mesh.indices[n++] = b;
                mesh.indices[n++] = c;
                mesh.indices[n++] = b;
                mesh.indices[n++] = d;
                mesh.indices[n++] = c;
            }
        }
        return mesh;
    }

    // 平らな格子。shared が false なら、三角形ごとに別の頂点を使う (同じ中身の頂点が重なる)
    private static Mesh plane(int cells, boolean shared) {
        final Mesh grid = new Mesh();
        final int side = cells + 1;
        grid.positions = new float[side * side * 3];
        grid.normals = new float[side * side * 3];
        grid.uvs = new float[side * side * 2];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                final int v = y * side + x;
                grid.positions[v * 3] = x * 0.25f - 1;
                grid.positions[v * 3 + 1] = y * 0.25f - 1;
                grid.positions[v * 3 + 2] = 0.5f;
                grid.normals[v * 3 + 2] = 1;
                grid.uvs[v * 2] = x / (float) cells;
                grid.uvs[v * 2 + 1] = y / (float) cells;
            }
        }
        grid.indices = new int[cells * cells * 6];
        int n = 0;
        for (int y = 0; y < cells; y++) {
            for (int x = 0; x < cells; x++) {
                final int a = y * side + x, b = a + 1, c = a + side, d = c + 1;
                grid.indices[n++] = a;
                grid.indices[n++] = b;
                grid.indices[n++] = d;
                grid.indices[n++] = a;
                grid.indices[n++] = d;
                grid.indices[n++] = c;
            }
        }
        if (shared) return grid;
        final Mesh mesh = new Mesh();
        final int corners = grid.indices.length;
        mesh.positions = new float[corners * 3];
        mesh.normals = new float[corners * 3];
        mesh.uvs = new float[corners * 2];
        mesh.indices = new int[corners];
        for (int c = 0; c < corners; c++) {
            final int v = grid.indices[c];
            System.arraycopy(grid.positions, v * 3, mesh.positions, c * 3, 3);
            System.arraycopy(grid.normals, v * 3, mesh.normals, c * 3, 3);
            System.arraycopy(grid.uvs, v * 2, mesh.uvs, c * 2, 2);
            mesh.indices[c] = c;
        }
        return mesh;
    }

    // メッシュを入れて、primitive の JSON の中身 (attributes と indices) を返す
    private static String primitive(Glb b, Mesh mesh, boolean normals, String extra) {
        final StringBuilder attributes = new StringBuilder("\"POSITION\":" + b.floats("VEC3", 3, mesh.positions)
                + ",\"TEXCOORD_0\":" + b.floats("VEC2", 2, mesh.uvs));
        if (normals) attributes.append(",\"NORMAL\":").append(b.floats("VEC3", 3, mesh.normals));
        if (extra != null) attributes.append(extra);
        return "\"attributes\":{" + attributes + "},\"indices\":" + b.shorts("SCALAR", 1, mesh.indices);
    }

    private static byte[] png(int width, int height) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < width * height; i++) image.setRGB(i % width, i / width, 0xFF000000 | i * 0x10307);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String sampler(int input, int output, String interpolation) {
        return "{\"input\":" + input + ",\"output\":" + output + ",\"interpolation\":\"" + interpolation + "\"}";
    }

    private static String channel(int sampler, int node, String path) {
        return "{\"sampler\":" + sampler + ",\"target\":{\"node\":" + node + ",\"path\":\"" + path + "\"}}";
    }

    // 回転・移動・拡大で動くノードと、動かないノード (裏返しの拡大を含む)。平らな面と曲面
    private static GltfModel rigidModel() throws IOException {
        final Glb b = new Glb();
        final String curved = primitive(b, torus(12, 8), true, null);
        final String flatShared = primitive(b, plane(6, true), true, null);
        final String flatSplit = primitive(b, plane(5, false), true, null);
        final int times = b.floats("SCALAR", 1, 0, 0.5f, 1, 1.5f, 2);
        final float s = (float) Math.sqrt(0.5);
        final int rotations = b.floats("VEC4", 4, 0, 0, 0, 1, 0, s, 0, s, 0, 1, 0, 0, 0, s, 0, -s, 0, 0, 0, 1);
        final int moves = b.floats("VEC3", 3, 0, 0, 0, 0, 1, 0, 0, 1, 0, 2, 0, 0, 0, 0, 0);
        // 3 次スプライン: 入りの接線、値、出の接線
        final int scales = b.floats("VEC3", 3,
                0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 2, 1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0,
                0, 0, 0, 1, 3, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0);
        return b.load("\"materials\":[{\"doubleSided\":true},{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.8,0.4,0.2,1],"
                + "\"metallicFactor\":0.7,\"roughnessFactor\":0.3}}],"
                + "\"meshes\":[{\"primitives\":[{" + curved + ",\"material\":0}]},"
                + "{\"primitives\":[{" + flatShared + ",\"material\":1},{" + flatSplit + ",\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0},{\"mesh\":1},{\"mesh\":0,\"scale\":[-1,1,1],\"translation\":[3,0,0]},"
                + "{\"children\":[1],\"translation\":[0,2,0]},{\"mesh\":1,\"translation\":[0,-2,0],\"scale\":[1,2,3]}],"
                + "\"scenes\":[{\"nodes\":[0,2,3,4]}],"
                + "\"animations\":[{\"samplers\":[" + sampler(times, rotations, "LINEAR") + "," + sampler(times, moves, "STEP") + ","
                + sampler(times, scales, "CUBICSPLINE") + "],\"channels\":[" + channel(0, 0, "rotation") + "," + channel(1, 1, "translation")
                + "," + channel(2, 3, "scale") + "]}]");
    }

    // スキニング、モーフィング (位置と法線)、頂点の色
    private static GltfModel deformingModel() throws IOException {
        final Glb b = new Glb();
        final Mesh tube = torus(10, 6);
        final int vertices = tube.positions.length / 3;
        final int[] joints = new int[vertices * 4];
        final float[] weights = new float[vertices * 4];
        final float[] colors = new float[vertices * 4];
        final float[] deltaPositions = new float[vertices * 3];
        final float[] deltaNormals = new float[vertices * 3];
        for (int v = 0; v < vertices; v++) {
            final float t = (tube.positions[v * 3] + 1.35f) / 2.7f * 2;
            final int first = Math.min(1, (int) t);
            joints[v * 4] = first;
            joints[v * 4 + 1] = first + 1;
            weights[v * 4] = 1 - (t - first);
            weights[v * 4 + 1] = t - first;
            colors[v * 4] = 0.5f + 0.5f * tube.normals[v * 3];
            colors[v * 4 + 1] = 0.5f + 0.5f * tube.normals[v * 3 + 1];
            colors[v * 4 + 2] = 0.75f;
            colors[v * 4 + 3] = v % 3 == 0 ? 0.25f : 1f;
            deltaPositions[v * 3 + 1] = tube.positions[v * 3] * 0.5f;
            deltaNormals[v * 3] = 0.3f * tube.normals[v * 3 + 1];
        }
        final String skinned = primitive(b, tube, true, ",\"JOINTS_0\":" + b.shorts("VEC4", 4, joints) + ",\"WEIGHTS_0\":" + b.floats("VEC4", 4, weights));
        final String morphed = primitive(b, tube, true, ",\"COLOR_0\":" + b.floats("VEC4", 4, colors))
                + ",\"targets\":[{\"POSITION\":" + b.floats("VEC3", 3, deltaPositions) + ",\"NORMAL\":" + b.floats("VEC3", 3, deltaNormals) + "}]";
        final int inverseBind = b.floats("MAT4", 16,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -1, 0, 0, 1);
        final int times = b.floats("SCALAR", 1, 0, 1, 2);
        final float s = (float) Math.sin(0.4), c = (float) Math.cos(0.4);
        final int bend = b.floats("VEC4", 4, 0, 0, 0, 1, 0, 0, s, c, 0, 0, 0, 1);
        final int morph = b.floats("SCALAR", 1, 0, 1, 0);
        return b.load("\"materials\":[{},{\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5,\"doubleSided\":true}],"
                + "\"meshes\":[{\"primitives\":[{" + skinned + ",\"material\":0}]},{\"primitives\":[{" + morphed + ",\"material\":1}]}],"
                + "\"skins\":[{\"joints\":[1,2,3],\"inverseBindMatrices\":" + inverseBind + "}],"
                + "\"nodes\":[{\"mesh\":0,\"skin\":0},{\"translation\":[-1,0,0],\"children\":[2]},{\"translation\":[1,0,0],\"children\":[3]},"
                + "{\"translation\":[1,0,0]},{\"mesh\":1,\"translation\":[0,3,0]}],"
                + "\"scenes\":[{\"nodes\":[0,1,4]}],"
                + "\"animations\":[{\"samplers\":[" + sampler(times, bend, "LINEAR") + "," + sampler(times, morph, "LINEAR") + "],"
                + "\"channels\":[" + channel(0, 2, "rotation") + "," + channel(0, 3, "rotation") + "," + channel(1, 4, "weights") + "]}]");
    }

    // 法線のないメッシュ (面ごとの陰影)、動く光源、陰影を付けない材質、半透明の材質、異方性
    private static GltfModel shadingModel() throws IOException {
        final Glb b = new Glb();
        final Mesh curved = torus(9, 7);
        final float[] colors = new float[curved.positions.length / 3 * 4];
        for (int v = 0; v < colors.length / 4; v++) {
            colors[v * 4] = (v % 5) / 4f;
            colors[v * 4 + 1] = 1;
            colors[v * 4 + 2] = (v % 3) / 2f;
            colors[v * 4 + 3] = 1;
        }
        final String faceted = primitive(b, curved, false, null);
        final String facetedColored = primitive(b, curved, false, ",\"COLOR_0\":" + b.floats("VEC4", 4, colors));
        final String smooth = primitive(b, curved, true, null);
        final String flat = primitive(b, plane(4, true), true, null);
        final int times = b.floats("SCALAR", 1, 0, 1, 2);
        final int moves = b.floats("VEC3", 3, 0, 2, 0, 2, 2, 0, 0, 2, 2);
        final float s = (float) Math.sqrt(0.5);
        final int turns = b.floats("VEC4", 4, 0, 0, 0, 1, s, 0, 0, s, 0, 0, 0, 1);
        return b.load("\"extensionsUsed\":[\"KHR_lights_punctual\",\"KHR_materials_unlit\",\"KHR_materials_anisotropy\"],"
                + "\"extensions\":{\"KHR_lights_punctual\":{\"lights\":[{\"type\":\"point\",\"color\":[1,0.8,0.6],\"intensity\":40},"
                + "{\"type\":\"spot\",\"intensity\":60,\"spot\":{\"outerConeAngle\":0.8}}]}},"
                + "\"materials\":[{\"doubleSided\":true},{\"extensions\":{\"KHR_materials_unlit\":{}},"
                + "\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.2,0.9,0.4,1]}},"
                + "{\"alphaMode\":\"BLEND\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,0.5]}},"
                + "{\"extensions\":{\"KHR_materials_anisotropy\":{\"anisotropyStrength\":0.8}}}],"
                + "\"meshes\":[{\"primitives\":[{" + faceted + ",\"material\":0},{" + facetedColored + ",\"material\":1}]},"
                + "{\"primitives\":[{" + smooth + ",\"material\":2},{" + flat + ",\"material\":3},{" + smooth + ",\"material\":3}]},"
                + "{\"primitives\":[{" + flat + ",\"material\":1},{" + facetedColored + ",\"material\":3}]}],"
                + "\"nodes\":[{\"mesh\":0},{\"mesh\":1,\"translation\":[3,0,0]},{\"mesh\":2,\"translation\":[-3,0,0]},"
                + "{\"extensions\":{\"KHR_lights_punctual\":{\"light\":0}}},{\"extensions\":{\"KHR_lights_punctual\":{\"light\":1}},\"translation\":[0,4,0]}],"
                + "\"scenes\":[{\"nodes\":[0,1,2,3,4]}],"
                + "\"animations\":[{\"samplers\":[" + sampler(times, moves, "LINEAR") + "," + sampler(times, turns, "LINEAR") + "],"
                + "\"channels\":[" + channel(0, 3, "translation") + "," + channel(1, 2, "rotation") + "]}]");
    }

    // KHR_animation_pointer で動く材質の色とテクスチャの貼り方
    private static GltfModel materialModel() throws IOException {
        final Glb b = new Glb();
        final String flat = primitive(b, plane(3, true), true, null);
        final String curved = primitive(b, torus(8, 6), true, null);
        final int image = b.image(png(4, 4));
        final int times = b.floats("SCALAR", 1, 0, 1, 2);
        final int offsets = b.floats("VEC2", 2, 0.5f, 0, 0, 0, 0.25f, 0.5f);
        final int colors = b.floats("VEC4", 4, 0.5f, 0.5f, 0.5f, 1, 0.25f, 0.5f, 1, 1, 0.5f, 0.5f, 0.5f, 1);
        final int turns = b.floats("SCALAR", 1, 0, 1, 0);
        final String pointer = "\"target\":{\"path\":\"pointer\",\"extensions\":{\"KHR_animation_pointer\":{\"pointer\":\"/materials/";
        return b.load("\"extensionsUsed\":[\"KHR_texture_transform\",\"KHR_animation_pointer\"],"
                + "\"images\":[{\"bufferView\":" + image + ",\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0,"
                + "\"extensions\":{\"KHR_texture_transform\":{\"offset\":[0.5,0]}}}}},"
                + "{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.5,0.5,0.5,1]}},{}],"
                + "\"meshes\":[{\"primitives\":[{" + flat + ",\"material\":0},{" + curved + ",\"material\":1},{" + curved + ",\"material\":2}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],"
                + "\"animations\":[{\"samplers\":[" + sampler(times, offsets, "LINEAR") + "," + sampler(times, colors, "LINEAR") + ","
                + sampler(times, turns, "STEP") + "],"
                + "\"channels\":[{\"sampler\":0," + pointer + "0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset\"}}}},"
                + "{\"sampler\":1," + pointer + "1/pbrMetallicRoughness/baseColorFactor\"}}}},"
                + "{\"sampler\":2," + pointer + "0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/rotation\"}}}}]}]");
    }

    // ---------- 比べる ----------

    private static final float[][] PLACEMENTS = {
            {0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0.5f, 0, 0.5f, 0.5f, 0.5f, 1},
            // 軸ごとに違う拡大と回転と、裏返し
            {0.3f, 0.1f, -0.4f, 0, -0.2f, 0.7f, 0.05f, 0, -0.5f, -0.1f, -0.25f, 0, 1, -2, 3, 1},
    };

    // 毎回すべてを計算した出力 (別のシーンで、その時刻の姿勢だけを計算する)
    private static Object[] reference(GltfModel model, double time, float[] placement) {
        final GltfScene scene = new GltfScene(model, QuadPairing.MODE_NONE);
        final GltfScene.Group[] groups = scene.groups();
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int g = 0; g < groups.length; g++) {
            vertices[g] = new float[groups[g].vertexCount() * STRIDE];
            colors[g] = new int[groups[g].vertexCount()];
        }
        scene.pose(time);
        scene.emit(placement, vertices, colors);
        return new Object[]{vertices, colors};
    }

    private static boolean sameBits(float[] a, float[] b, int count) {
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) return false;
        }
        return true;
    }

    // いろいろな時刻を行き来しながら、2 つの出力先を別々のタイミングで書き直しても、毎回すべてを計算したものと同じになる
    private static void incremental(String name, GltfModel model) {
        final GltfScene scene = new GltfScene(model, QuadPairing.MODE_NONE);
        final GltfScene.Target[] targets = {scene.createTarget(PLACEMENTS[0]), scene.createTarget(PLACEMENTS[1])};
        final Random random = new Random(42);
        final double[] moments = {0, 0, 0.25, 0.25, 0.5, 1, 1, 1.5, 0.75, 2, 2.5, 4.25, 4.25, 0, 1e6 + 0.125, 0.3, 0.3};
        boolean same = true, skipped = false;
        String example = "";
        for (int step = 0; step < 60 && same; step++) {
            final double time = step < moments.length ? moments[step] : random.nextInt(5) == 0 ? random.nextDouble() * 9 : (step % 7) * 0.31;
            scene.pose(time);
            for (int k = 0; k < targets.length; k++) {
                // 片方は、ときどき書き直さない (その間に変わった分も、次で直ること)
                if (k == 1 && step % 3 == 1) continue;
                final boolean changed = targets[k].update();
                skipped |= !changed;
                final Object[] expected = reference(model, time, PLACEMENTS[k]);
                final float[][] vertices = (float[][]) expected[0];
                final int[][] colors = (int[][]) expected[1];
                for (int g = 0; g < vertices.length; g++) {
                    if (targets[k].vertexCount(g) != colors[g].length || !sameBits(targets[k].vertices(g), vertices[g], vertices[g].length)
                            || !Arrays.equals(targets[k].colors(g), colors[g])) {
                        same = false;
                        example = "step " + step + ", time " + time + ", target " + k + ", group " + g;
                    }
                }
            }
        }
        check(same, "incremental output equals a full recomputation (" + name + ")" + (same ? "" : ": " + example));
        // 同じ時刻が続くところでは、何も書き直さないはず
        check(skipped, "nothing is rewritten when the pose does not change (" + name + ")");
    }

    // 頂点 1 つの中身 (位置、テクスチャ座標、色) を 1 つの値にする
    private static long cornerKey(float[] vertices, int[] colors, int vertex) {
        long h = colors[vertex];
        for (int k = 0; k < STRIDE; k++) {
            h = (h ^ Float.floatToRawIntBits(vertices[vertex * STRIDE + k])) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
        }
        return h;
    }

    // 四角形の列が描く三角形 (面積のないものを除く) を、並びによらない形にして集める。四角形は (0, 1, 2) と (2, 3, 0) に分ける
    private static long[] triangles(float[] vertices, int[] colors, int vertexCount) {
        final long[] result = new long[vertexCount / 2];
        int n = 0;
        final long[] key = new long[4];
        for (int q = 0; q + 3 < vertexCount; q += 4) {
            for (int k = 0; k < 4; k++) key[k] = cornerKey(vertices, colors, q + k);
            for (int half = 0; half < 2; half++) {
                final long a = key[half * 2], b = key[half * 2 + 1], c = key[(half * 2 + 2) & 3];
                if (a == b || b == c || a == c) continue;
                // 回しても同じ三角形なので、いちばん小さい頂点から始める
                final long first, second, third;
                if (a <= b && a <= c) {
                    first = a;
                    second = b;
                    third = c;
                } else if (b <= a && b <= c) {
                    first = b;
                    second = c;
                    third = a;
                } else {
                    first = c;
                    second = a;
                    third = b;
                }
                result[n++] = (first * 31 + second) * 0xBF58476D1CE4E5B9L + third;
            }
        }
        final long[] used = Arrays.copyOf(result, n);
        Arrays.sort(used);
        return used;
    }

    // まとめた四角形が、どちらの対角線で分けても同じ絵になるか (同じ平面にある凸な四角形で、色が同じで、テクスチャ座標がずれない)
    private static boolean interchangeable(float[] vertices, int[] colors, int q) {
        if (colors[q] != colors[q + 1] || colors[q] != colors[q + 2] || colors[q] != colors[q + 3]) return false;
        final double[][] p = new double[4][5];
        for (int k = 0; k < 4; k++) {
            for (int e = 0; e < STRIDE; e++) p[k][e] = vertices[(q + k) * STRIDE + e];
        }
        final double[] a = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        final double[] b = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
        final double[] c = {p[3][0] - p[0][0], p[3][1] - p[0][1], p[3][2] - p[0][2]};
        final double[] n = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        final double size = Math.sqrt(Math.max(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], Math.max(b[0] * b[0] + b[1] * b[1] + b[2] * b[2],
                c[0] * c[0] + c[1] * c[1] + c[2] * c[2])));
        final double off = (c[0] * n[0] + c[1] * n[1] + c[2] * n[2]) / Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (!(Math.abs(off) <= 5e-4 * size)) return false;
        // 頂点 3 = 頂点 0 + alpha (頂点 1 - 頂点 0) + beta (頂点 2 - 頂点 0)
        final double aa = a[0] * a[0] + a[1] * a[1] + a[2] * a[2], ab = a[0] * b[0] + a[1] * b[1] + a[2] * b[2], bb = b[0] * b[0] + b[1] * b[1] + b[2] * b[2];
        final double ac = a[0] * c[0] + a[1] * c[1] + a[2] * c[2], bc = b[0] * c[0] + b[1] * c[1] + b[2] * c[2];
        final double det = aa * bb - ab * ab;
        final double alpha = (ac * bb - bc * ab) / det, beta = (bc * aa - ac * ab) / det;
        if (!(alpha < 0 && beta > 0 && alpha + beta < 1)) return false;
        for (int e = 3; e < 5; e++) {
            final double predicted = p[0][e] + alpha * (p[1][e] - p[0][e]) + beta * (p[2][e] - p[0][e]);
            if (!(Math.abs(predicted - p[3][e]) <= 1e-4)) return false;
        }
        return true;
    }

    // 三角形をまとめた出力が、まとめない出力と同じ三角形の集まりを描く (姿勢を変えても)
    private static void paired(String name, GltfModel model, int mode) {
        final GltfScene scene = new GltfScene(model, mode);
        final GltfScene.Group[] groups = scene.groups();
        boolean same = true, safe = true;
        String example = "";
        int before = 0, after = 0;
        for (int k = 0; k < PLACEMENTS.length; k++) {
            final GltfScene.Target target = scene.createTarget(PLACEMENTS[k]);
            for (double time : new double[]{0, 0.4, 1.3, 0.4, 1.9}) {
                scene.pose(time);
                target.update();
                final Object[] expected = reference(model, time, PLACEMENTS[k]);
                for (int g = 0; g < groups.length; g++) {
                    final float[] vertices = ((float[][]) expected[0])[g];
                    final int[] colors = ((int[][]) expected[1])[g];
                    if (!Arrays.equals(triangles(vertices, colors, colors.length),
                            triangles(target.vertices(g), target.colors(g), target.vertexCount(g)))) {
                        same = false;
                        example = "time " + time + ", placement " + k + ", group " + g;
                    }
                    if (target.vertexCount(g) > colors.length) same = false;
                    if (groups[g].blend && target.vertexCount(g) != colors.length) {
                        same = false;
                        example = "translucent group " + g + " was merged";
                    }
                    if (mode == QuadPairing.MODE_PLANAR) {
                        final float[] v = target.vertices(g);
                        for (int q = 0; q + 3 < target.vertexCount(g); q += 4) {
                            final boolean triangle = cornerKey(v, target.colors(g), q + 2) == cornerKey(v, target.colors(g), q + 3);
                            // 元から四角形のまとまり (構造物) は対象外。ここのモデルにはない
                            if (!triangle && !interchangeable(v, target.colors(g), q)) {
                                safe = false;
                                example = "time " + time + ", placement " + k + ", group " + g + ", quad " + q / 4;
                            }
                        }
                    }
                    if (k == 0 && time == 0) {
                        before += colors.length;
                        after += target.vertexCount(g);
                    }
                }
            }
        }
        final String label = mode == QuadPairing.MODE_ANY ? "any shared edge" : "planar only";
        check(same, "merged quads draw the same triangles (" + label + ", " + name + "): " + before + " -> " + after + " vertices"
                + (same ? "" : ": " + example));
        if (mode == QuadPairing.MODE_PLANAR) {
            check(safe, "merged quads do not depend on the diagonal (" + name + ")" + (safe ? "" : ": " + example));
        }
    }

    // 閉じた曲面と格子では、すべての三角形が組になる
    private static void pairingCounts() throws IOException {
        final Glb b = new Glb();
        final String curved = primitive(b, torus(24, 16), true, null);
        final String flatShared = primitive(b, plane(12, true), true, null);
        final String flatSplit = primitive(b, plane(12, false), true, null);
        final GltfModel model = b.load("\"meshes\":[{\"primitives\":[{" + curved + "}]},{\"primitives\":[{" + flatShared + "}]},"
                + "{\"primitives\":[{" + flatSplit + "}]}],\"nodes\":[{\"mesh\":0},{\"mesh\":1},{\"mesh\":2}],\"scenes\":[{\"nodes\":[0,1,2]}]");
        final GltfModel.Material material = model.materials[model.meshes[0].primitives[0].material];
        final int[][] expected = {
                // {辺を共有していればまとめる, 平らなものだけまとめる}
                {24 * 16, 0}, {12 * 12, 12 * 12}, {12 * 12, 12 * 12}};
        boolean all = true;
        final StringBuilder counts = new StringBuilder();
        for (int m = 0; m < 3; m++) {
            final GltfModel.Primitive primitive = model.meshes[m].primitives[0];
            final int any = QuadPairing.plan(primitive, material, false, QuadPairing.MODE_ANY).pairs;
            final int planar = QuadPairing.plan(primitive, material, false, QuadPairing.MODE_PLANAR).pairs;
            counts.append(' ').append(any).append('/').append(planar).append(" of ").append(primitive.indices.length / 6);
            all &= any == expected[m][0] && planar == expected[m][1];
        }
        check(all, "every triangle of a closed surface and of a grid is paired (any/planar):" + counts);
        // 光源があると、同じ法線でも色が位置で変わるので、平らなものだけの決まりではまとめない
        check(QuadPairing.plan(model.meshes[1].primitives[0], material, true, QuadPairing.MODE_PLANAR).pairs == 0
                        && QuadPairing.plan(model.meshes[1].primitives[0], material, true, QuadPairing.MODE_ANY).pairs == 12 * 12
                        && QuadPairing.plan(model.meshes[1].primitives[0], material, false, QuadPairing.MODE_NONE).pairs == 0,
                "pairing rules with lights and when turned off");
    }

    // 小さなグラフでの最大のマッチング (総当たり)
    private static int maximum(int[] partner, int triangles, boolean[] used, int from) {
        int t = from;
        while (t < triangles && used[t]) t++;
        if (t >= triangles) return 0;
        used[t] = true;
        int best = maximum(partner, triangles, used, t + 1);
        for (int s = 0; s < 3; s++) {
            final int e = t * 3 + s;
            if (partner[e] < 0) continue;
            final int u = partner[e] / 3;
            if (used[u]) continue;
            used[u] = true;
            best = Math.max(best, 1 + maximum(partner, triangles, used, t + 1));
            used[u] = false;
        }
        used[t] = false;
        return best;
    }

    // でたらめなグラフで、組の作り方が正しいこと (同じ三角形を 2 回使わない、これ以上足せない、最大に近い)
    private static void matching() {
        final Random random = new Random(7);
        boolean valid = true, maximal = true;
        int found = 0, best = 0, perfect = 0, graphs = 0;
        for (int n = 0; n < 3000; n++) {
            final int triangles = 2 + random.nextInt(13);
            final int[] partner = new int[triangles * 3];
            Arrays.fill(partner, -1);
            final int edges = random.nextInt(triangles * 2 + 1);
            for (int k = 0; k < edges; k++) {
                final int a = random.nextInt(triangles * 3), c = random.nextInt(triangles * 3);
                if (a / 3 == c / 3 || partner[a] >= 0 || partner[c] >= 0) continue;
                partner[a] = c;
                partner[c] = a;
            }
            final int[] match = QuadPairing.match(partner, triangles);
            int size = 0;
            for (int t = 0; t < triangles; t++) {
                final int e = match[t];
                if (e < 0) {
                    // 組になっていない三角形どうしがつながっていてはいけない
                    for (int s = 0; s < 3; s++) {
                        final int other = partner[t * 3 + s];
                        if (other >= 0 && match[other / 3] < 0) maximal = false;
                    }
                    continue;
                }
                size++;
                if (e / 3 != t || partner[e] < 0 || match[partner[e] / 3] != partner[e]) valid = false;
            }
            size /= 2;
            final int most = maximum(partner, triangles, new boolean[triangles], 0);
            if (size > most) valid = false;
            found += size;
            best += most;
            if (size == most) perfect++;
            graphs++;
        }
        check(valid && maximal, "triangle pairs form a maximal matching");
        // これ以上足せないマッチングは、最大のものの半分以上ある。短い増加道で直しているので、実際にはほぼ最大になる
        check(found * 100L >= best * 98L, "pairing is close to the maximum matching: " + found + " of " + best + " pairs, optimal in "
                + perfect + " of " + graphs + " graphs");
    }

    private static void versions() {
        final String[] fixed = {"1.17", "1.17.1", "1.18.2", "1.20.1", "1.21.11", "26.1", "26.3", "1.21.1-pre1"};
        final String[] unknown = {"1.7.10", "1.8", "1.8.9", "1.12.2", "1.14.4", "1.15.2", "1.16.5", "24w44a", "", "1", "1.x"};
        boolean right = !QuadPairing.fixedDiagonal(null);
        for (String version : fixed) right &= QuadPairing.fixedDiagonal(version);
        for (String version : unknown) right &= !QuadPairing.fixedDiagonal(version);
        check(right, "Minecraft versions that always split quads along the first diagonal");
    }
}
