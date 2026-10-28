package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.texture.TextureDecoder;
import marumasa.marumasa_sign.core.texture.ZstdDecoder;
import marumasa.marumasa_sign.core.util.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GltfFuzz などで見つけて直した、3D モデルとテクスチャの読み込みの不具合が、もう一度起きないことを確かめる。引数は要らない。
 * 失敗があれば 1 で終わる。src/test/resources/gltf の種のファイルが読めることも確かめる。
 */
public final class GltfRegressionCheck {

    private static int failures;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "GltfRegressionCheck");
        thread.setDaemon(true);
        return thread;
    });

    private GltfRegressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        seeds();
        occlusionOnlyBake();
        slowBake();
        sharedAccessor();
        sharedIndices();
        accessorBudget();
        meshoptDeclaredCount();
        instancingType();
        percentDataUri();
        emptyPrimitives();
        pointerToDefaultMaterial();
        infiniteTransform();
        manyLights();
        json();
        textureHeaders();
        zstd();
        System.out.println(failures == 0 ? "GltfRegressionCheck: all tests passed" : "GltfRegressionCheck: " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    // 時間の上限をつけて動かす。時間を過ぎたら null
    private static <T> T within(long seconds, Callable<T> task) throws Exception {
        final Future<T> future = EXECUTOR.submit(task);
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    // 読み込んで、三角形を出すところまで試す。読めなければ IOException のメッセージ
    private static String load(byte[] data) {
        try {
            final GltfScene scene = new GltfScene(GltfLoader.load(data, null));
            scene.restBounds();
            return "loaded";
        } catch (IOException e) {
            return "IOException: " + e.getMessage();
        }
    }

    // ---------- ファイルを作る ----------

    private static byte[] glb(String json, byte[] bin) {
        final byte[] text = json.getBytes(StandardCharsets.UTF_8);
        final int jsonLength = (text.length + 3) & ~3;
        final int binLength = (bin.length + 3) & ~3;
        final ByteBuffer out = ByteBuffer.allocate(12 + 8 + jsonLength + 8 + binLength).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity());
        out.putInt(jsonLength).putInt(0x4E4F534A).put(text);
        for (int i = text.length; i < jsonLength; i++) out.put((byte) ' ');
        out.putInt(binLength).putInt(0x004E4942).put(bin);
        return out.array();
    }

    private static byte[] floats(float... values) {
        final ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] png(int width, int height) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < width * height; i++) image.setRGB(i % width, i / width, 0xFF000000 | i * 0x10307);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // 三角形 1 枚 (位置と UV、36 + 24 バイト) と、その後ろに画像を入れたモデル
    private static byte[] texturedTriangle(String material, String primitiveExtra) throws IOException {
        final byte[] geometry = floats(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1);
        final byte[] image = png(4, 4);
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + (60 + image.length) + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":60,\"byteLength\":" + image.length + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"}],"
                + "\"images\":[{\"bufferView\":2,\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[" + material + "],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"material\":0" + primitiveExtra + "}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}]}";
        return glb(json, concat(geometry, image));
    }

    // ---------- 確かめること ----------

    // 種のファイルが読めること
    private static void seeds() throws IOException {
        final File[] files = new File("src/test/resources/gltf").listFiles();
        check(files != null && files.length > 0, "seed folder exists");
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            if (!file.getName().endsWith(".glb") && !file.getName().endsWith(".gltf")) continue;
            String result;
            try {
                final GltfScene scene = new GltfScene(GltfLoader.load(Files.readAllBytes(file.toPath()), ImageLoader.fileResources(file)));
                scene.pose(0.5f);
                scene.restBounds();
                result = "loaded";
            } catch (IOException e) {
                result = e.toString();
            }
            check("loaded".equals(result), "seed " + file.getName() + ": " + result);
        }
    }

    // 遮蔽のテクスチャだけを持つ材質に焼き込みの要る拡張があると、焼き込む大きさが 0 のまま倍にし続けて止まらなかった
    private static void occlusionOnlyBake() throws Exception {
        final byte[] data = texturedTriangle("{\"occlusionTexture\":{\"index\":0},"
                + "\"extensions\":{\"KHR_materials_clearcoat\":{\"clearcoatFactor\":0.5}}}", "");
        final String result = within(20, () -> load(data));
        check("loaded".equals(result), "occlusion-only material with clearcoat finishes: " + result);
    }

    // テクスチャの上で細長い三角形 (扇の形) をとても多く並べると、焼き込みで三角形を囲む四角形をたどり続けて終わらなかった
    private static void slowBake() throws Exception {
        final int count = 30000;
        final float[] positions = new float[count * 3];
        final float[] uvs = new float[count * 2];
        for (int i = 1; i < count; i++) {
            final double angle = i * 1e-4;
            positions[i * 3] = (float) Math.cos(angle);
            positions[i * 3 + 1] = (float) Math.sin(angle);
            uvs[i * 2] = 1;
            uvs[i * 2 + 1] = (float) i / count;
        }
        final byte[] geometry = concat(floats(positions), floats(uvs));
        final byte[] image = png(4, 4);
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + (geometry.length + image.length) + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + count * 12 + "},"
                + "{\"buffer\":0,\"byteOffset\":" + count * 12 + ",\"byteLength\":" + count * 8 + "},"
                + "{\"buffer\":0,\"byteOffset\":" + geometry.length + ",\"byteLength\":" + image.length + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":" + count + ",\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":" + count + ",\"type\":\"VEC2\"}],"
                + "\"images\":[{\"bufferView\":2,\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0}},\"normalTexture\":{\"index\":0}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"material\":0,\"mode\":6}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}]}";
        final byte[] data = glb(json, concat(geometry, image));
        final long start = System.nanoTime();
        final String result = within(30, () -> load(data));
        check("loaded".equals(result), "fan of thin texture triangles bakes in bounded time: " + result + " ("
                + (System.nanoTime() - start) / 1000000 + " ms)");
    }

    // 同じアクセサを参照する面がとても多いと、面ごとに同じ値を読み直して、メモリを使い切った
    private static void sharedAccessor() throws Exception {
        final StringBuilder primitives = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            if (i > 0) primitives.append(',');
            primitives.append("{\"attributes\":{\"POSITION\":0},\"indices\":1}");
        }
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":48}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":12}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5125,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[" + primitives + "]}],\"nodes\":[{\"mesh\":0}]}";
        final byte[] data = glb(json, concat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), new byte[]{0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0}));
        final GltfModel model = GltfLoader.load(data, null);
        final GltfModel.Primitive[] list = model.meshes[0].primitives;
        check(list.length == 3000 && list[0].positions == list[2999].positions, "primitives share the values of the same accessor");
    }

    // 同じ索引のアクセサを何千もの面で使うと、面ごとに作る角の配列 (三角形の並びとテクスチャ座標) でメモリを使い切った
    // (描く三角形の数は、すべてを読んだあとでしか確かめていなかった)
    private static void sharedIndices() throws Exception {
        final StringBuilder primitives = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            if (i > 0) primitives.append(',');
            primitives.append("{\"attributes\":{\"POSITION\":0},\"indices\":1}");
        }
        final byte[] indices = new byte[1500 * 4];
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + (36 + indices.length) + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":" + indices.length + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5125,\"count\":1500,\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[" + primitives + "]}],\"nodes\":[{\"mesh\":0}]}";
        final String result = within(20, () -> load(glb(json, concat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), indices))));
        check(result != null && result.contains("triangles"), "triangle corners of all primitives are limited while reading: " + result);
    }

    // 値を持たないアクセサをいくつも参照させて、合計でとても大きな配列を作らせることができた
    private static void accessorBudget() throws Exception {
        final StringBuilder accessors = new StringBuilder();
        final StringBuilder primitives = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                accessors.append(',');
                primitives.append(',');
            }
            accessors.append("{\"componentType\":5126,\"count\":1398101,\"type\":\"VEC3\"}");
            // 索引は空にして、三角形の数の上限より先に、アクセサの大きさの上限に当たるようにする
            primitives.append("{\"attributes\":{\"POSITION\":").append(i).append("},\"indices\":16}");
        }
        accessors.append(",{\"componentType\":5125,\"count\":0,\"type\":\"SCALAR\"}");
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"accessors\":[" + accessors + "],"
                + "\"meshes\":[{\"primitives\":[" + primitives + "]}],\"nodes\":[{\"mesh\":0}]}";
        final String result = within(20, () -> load(json.getBytes(StandardCharsets.UTF_8)));
        check(result != null && result.contains("accessor elements"), "total accessor size is limited: " + result);
    }

    // meshopt の要素の数だけを大きく書くと、短いデータでも展開する前に大きな領域を確保した
    private static void meshoptDeclaredCount() throws Exception {
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":64}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":64,\"extensions\":{\"EXT_meshopt_compression\":"
                + "{\"buffer\":0,\"byteLength\":64,\"byteStride\":16,\"count\":16000000,\"mode\":\"ATTRIBUTES\"}}}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"nodes\":[{\"mesh\":0}]}";
        final String result = load(glb(json, new byte[64]));
        check(result.contains("too short"), "meshopt count is checked against the compressed length: " + result);
    }

    // 型の違うアクセサ (VEC2) を平行移動に使うと、範囲外の参照になった
    private static void instancingType() throws Exception {
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":60}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":24}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"nodes\":[{\"mesh\":0,\"extensions\":{\"EXT_mesh_gpu_instancing\":{\"attributes\":{\"TRANSLATION\":1}}}}]}";
        final String result = load(glb(json, concat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), floats(0, 0, 1, 1, 2, 2))));
        check(result.contains("Invalid instancing accessor"), "instancing accessor type is checked: " + result);
    }

    // base64 でない data URI の %XX を戻していなかった
    private static void percentDataUri() throws Exception {
        final StringBuilder uri = new StringBuilder("data:application/octet-stream,");
        for (byte b : floats(0, 0, 0, 2, 0, 0, 0, 1, 0)) uri.append(String.format("%%%02X", b & 0xFF));
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":36,\"uri\":\"" + uri + "\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"nodes\":[{\"mesh\":0}]}";
        final float[] bounds = new GltfScene(GltfLoader.load(json.getBytes(StandardCharsets.UTF_8), null)).restBounds();
        check(bounds[3] == 2f && bounds[4] == 1f, "percent-encoded data URI is decoded: max " + bounds[3] + ", " + bounds[4]);
    }

    // 三角形のない面を何千も並べると、描くたびにすべてをたどっていた
    private static void emptyPrimitives() throws Exception {
        final StringBuilder primitives = new StringBuilder("{\"attributes\":{\"POSITION\":0}}");
        for (int i = 0; i < 2000; i++) primitives.append(",{\"attributes\":{\"POSITION\":0},\"mode\":4,\"indices\":1}");
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":44}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":8}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5125,\"count\":2,\"type\":\"SCALAR\"}],"
                + "\"meshes\":[{\"primitives\":[" + primitives + "]}],\"nodes\":[{\"mesh\":0}]}";
        final GltfModel model = GltfLoader.load(glb(json, concat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), new byte[8])), null);
        check(model.meshes[0].primitives.length == 1, "primitives without triangles are dropped: " + model.meshes[0].primitives.length);
    }

    // KHR_animation_pointer で、ファイルにない材質の番号 (読み込みで足した既定の材質) を指せた
    private static void pointerToDefaultMaterial() throws Exception {
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":60}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":8},"
                + "{\"buffer\":0,\"byteOffset\":44,\"byteLength\":16}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":2,\"type\":\"VEC2\"}],"
                + "\"materials\":[{}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"material\":0}]}],\"nodes\":[{\"mesh\":0}],"
                + "\"animations\":[{\"samplers\":[{\"input\":1,\"output\":2}],\"channels\":[{\"sampler\":0,\"target\":{\"path\":\"pointer\","
                + "\"extensions\":{\"KHR_animation_pointer\":{\"pointer\":\"/materials/1/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset\"}}}}]}]}";
        final GltfModel model = GltfLoader.load(glb(json, concat(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), floats(0, 1), floats(0, 0, 1, 1))), null);
        check(model.animations.length == 0, "animation pointer to the implicit default material is ignored");
    }

    // JSON の 1e39 (float では無限大) を、ノードの平行移動にそのまま使っていた
    private static void infiniteTransform() throws Exception {
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":36}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"nodes\":[{\"mesh\":0,\"translation\":[1e39,0,0],\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1e39]}]}";
        final float[] bounds = new GltfScene(GltfLoader.load(glb(json, floats(0, 0, 0, 1, 0, 0, 0, 1, 0)), null)).restBounds();
        boolean finite = true;
        for (float value : bounds) finite &= !Float.isNaN(value) && !Float.isInfinite(value);
        check(finite, "infinite numbers in the JSON do not reach the transforms: " + Arrays.toString(bounds));
    }

    // 光源をとても多く置くと、頂点ごと・ピクセルごとに全部を計算して止まったようになった
    private static void manyLights() throws Exception {
        final StringBuilder nodes = new StringBuilder("{\"mesh\":0}");
        for (int i = 0; i < 1000; i++) nodes.append(",{\"extensions\":{\"KHR_lights_punctual\":{\"light\":0}}}");
        final String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":36}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"extensions\":{\"KHR_lights_punctual\":{\"lights\":[{\"type\":\"point\"}]}},\"nodes\":[" + nodes + "]}";
        final GltfModel model = GltfLoader.load(glb(json, floats(0, 0, 0, 1, 0, 0, 0, 1, 0)), null);
        final GltfLight.LightInstance[] lights = GltfLoader.collectLights(model, GltfLoader.computeInitialGlobals(model));
        check(lights.length == GltfLoader.MAX_LIGHTS, "placed lights are limited: " + lights.length);
    }

    private static void json() throws Exception {
        final Map<String, Object> object = Json.object(Json.parse("{\"a\":4294967297,\"b\":-1e20,\"c\":1e39,\"d\":[1,1e39]}"));
        check(Json.integer(object, "a", 0) == Integer.MAX_VALUE && Json.integer(object, "b", 0) == Integer.MIN_VALUE,
                "integers outside the int range saturate");
        check(Json.number(object, "c", 7f) == 7f && Json.numbers(object, "d", new float[]{5, 6})[1] == 6f
                && Json.numbers(object.get("d"))[1] == 0f, "numbers that overflow float are treated as missing");
        final StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 2200000; i++) many.append(i == 0 ? "0" : ",0");
        many.append(']');
        String result;
        try {
            Json.parse(many.toString());
            result = "parsed";
        } catch (IOException e) {
            result = e.getMessage();
        }
        check(result.contains("Too many values"), "number of JSON values is limited: " + result);
    }

    // 幅と高さだけを大きく書いた小さな画像を、展開する前に断ること
    private static void textureHeaders() throws Exception {
        final byte[] png = png(4, 4);
        // IHDR の幅と高さを変え、CRC も直す (CRC が違うと、大きさを読む前に断られて確かめにならない)
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        ByteBuffer.wrap(png).putInt(16, 20000).putInt(20, 20000);
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        // 以前は ImageIO が展開する領域を確保しようとしてメモリを使い切り、それを ImageIO が IOException に包んでいた
        final String pngResult = rejected(png, "image/png");
        check(pngResult.contains("safety limits"), "PNG with a huge header size is rejected before decoding: " + pngResult);

        final byte[] ktx2 = new byte[80 + 24];
        System.arraycopy(new byte[]{(byte) 0xAB, 'K', 'T', 'X', ' ', '2', '0', (byte) 0xBB, '\r', '\n', 0x1A, '\n'}, 0, ktx2, 0, 12);
        final ByteBuffer k = ByteBuffer.wrap(ktx2).order(ByteOrder.LITTLE_ENDIAN);
        k.putInt(12, 37).putInt(20, 8192).putInt(24, 8192).putInt(40, 1).putLong(80, 104).putLong(88, 0);
        check(rejected(ktx2, "image/ktx2").contains("Truncated"), "KTX2 without level data is rejected");

        final byte[] dds = new byte[128];
        final ByteBuffer d = ByteBuffer.wrap(dds).order(ByteOrder.LITTLE_ENDIAN);
        d.putInt(0, 0x20534444).putInt(4, 124).putInt(12, 8192).putInt(16, 8192).putInt(80, 4).putInt(84, 0x31545844);
        check(rejected(dds, "image/vnd-ms.dds").contains("truncated"), "DDS without block data is rejected");
    }

    // 断られたときの IOException のメッセージ。読めてしまったら "decoded"
    private static String rejected(byte[] data, String mimeType) {
        try {
            TextureDecoder.decode(data, mimeType);
            return "decoded";
        } catch (IOException e) {
            return String.valueOf(e.getMessage());
        }
    }

    // Zstandard (RFC 8878) の検査
    private static void zstd() throws Exception {
        final byte[] ok = frame(new byte[]{(byte) 0xA0, 5, 0, 0, 0}, block(0, true, 5), new byte[]{1, 2, 3, 4, 5});
        check(Arrays.equals(ZstdDecoder.decompress(ok, 0, ok.length, 100), new byte[]{1, 2, 3, 4, 5}), "raw zstd block");
        // RLE のブロックは 128 KB を超えてはいけない
        final byte[] rle = frame(new byte[]{0x00, 0x70}, block(1, true, 200000), new byte[]{7});
        check(zstdRejected(rle), "zstd block larger than 128 KB is rejected");
        // 書かれた展開後の大きさと実際の大きさが違う
        final byte[] mismatch = frame(new byte[]{(byte) 0xA0, 9, 0, 0, 0}, block(0, true, 5), new byte[]{1, 2, 3, 4, 5});
        check(zstdRejected(mismatch), "zstd frame content size mismatch is rejected");
        // 確かめる値 (checksum) が途中で切れている
        final byte[] checksum = frame(new byte[]{(byte) 0xA4, 1, 0, 0, 0}, block(0, true, 1), new byte[]{1, 0});
        check(zstdRejected(checksum), "truncated zstd checksum is rejected");
    }

    private static boolean zstdRejected(byte[] data) {
        try {
            ZstdDecoder.decompress(data, 0, data.length, 1 << 24);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static byte[] block(int type, boolean last, int size) {
        final int header = size << 3 | type << 1 | (last ? 1 : 0);
        return new byte[]{(byte) header, (byte) (header >>> 8), (byte) (header >>> 16)};
    }

    private static byte[] frame(byte[] header, byte[] block, byte[] content) {
        return concat(new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD}, header, block, content);
    }
}
