package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.gltf.meshopt.MeshoptDecoder;
import marumasa.marumasa_sign.core.util.Json;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 (.gltf / .glb) を読み込む。
 * 描画はどのバージョンでも同じ「テクスチャ 1 枚と頂点の色」で行うため、
 * 材質の情報は読み込みの時点でテクスチャと色にまとめておく。
 */
public final class GltfLoader {

    // 外部ファイル (バッファや画像) の読み込み
    public interface Resources {
        byte[] load(String uri) throws IOException;
    }

    // 三角形の数の上限
    public static final int MAX_TRIANGLES = 150000;
    // テクスチャの大きさの上限
    private static final int MAX_TEXTURE_SIZE = 2048;
    // 繰り返しを並べたテクスチャの大きさと枚数の上限
    private static final int MAX_TILED_SIZE = 4096;
    private static final int MAX_TILES = 16;
    // 陰影を焼き込むテクスチャの、長いほうの辺の最小の長さ
    private static final int MIN_BAKE_SIZE = 256;
    private static final int MAX_NODES = 65536;
    // バッファを持たないアクセサの要素数の上限
    private static final int MAX_EMPTY_ACCESSOR = 1 << 22;

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int WRAP_CLAMP = 33071;
    private static final int WRAP_MIRROR = 33648;
    private static final int WRAP_REPEAT = 10497;

    private static final float INVERSE_GAMMA = 1f / 2.2f;

    // 対応していない圧縮形式。これが必須のファイルは形を読み取れない
    private static final String[] UNSUPPORTED = {"KHR_draco_mesh_compression"};

    // 先頭のバイト列が GLB かどうか
    public static boolean isGlb(byte[] head, int length) {
        return length >= 4 && head[0] == 'g' && head[1] == 'l' && head[2] == 'T' && head[3] == 'F';
    }

    // 先頭のバイト列が JSON らしいかどうか (.gltf の可能性がある)
    public static boolean looksLikeJson(byte[] head, int length) {
        int i = 0;
        if (length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) i = 3;
        while (i < length && (head[i] == ' ' || head[i] == '\t' || head[i] == '\n' || head[i] == '\r')) i++;
        return i < length && head[i] == '{';
    }

    public static GltfModel load(byte[] content, Resources resources) throws IOException {
        try {
            return new GltfLoader(resources).read(content);
        } catch (RuntimeException e) {
            // 壊れたファイルによる範囲外の参照など
            throw new IOException("Invalid glTF: " + e, e);
        }
    }

    // テクスチャの参照と、その貼り方
    private static final class TexRef {
        int texture;
        int texCoord;
        float offsetX, offsetY, rotation;
        float scaleX = 1, scaleY = 1;
        int wrapS = WRAP_REPEAT, wrapT = WRAP_REPEAT;

        boolean sameMapping(TexRef other) {
            return texCoord == other.texCoord && offsetX == other.offsetX && offsetY == other.offsetY
                    && rotation == other.rotation && scaleX == other.scaleX && scaleY == other.scaleY
                    && wrapS == other.wrapS && wrapT == other.wrapT;
        }
    }

    // 読み込み途中の材質
    private static final class Draft {
        final GltfModel.Material material = new GltfModel.Material();
        // テクスチャ座標の元になる参照。テクスチャを使わなければ null
        TexRef mapping;
        int width, height;
        int[] pixels;
        final List<GltfModel.Primitive> primitives = new ArrayList<GltfModel.Primitive>();
        // 細かい表現をテクスチャに焼き込むための情報。焼き込む必要がなければ null
        GltfBaker.Input bake;
        int bakeWidth, bakeHeight;
        // 焼き込みで使う、材質のテクスチャ座標とは別の貼り方と、面ごとのその座標
        final List<TexRef> extraRefs = new ArrayList<TexRef>();
        final Map<GltfModel.Primitive, float[][]> extraUv = new IdentityHashMap<GltfModel.Primitive, float[][]>();
    }

    private static final class Pixels {
        final int width, height;
        final int[] argb;

        Pixels(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    private final Resources resources;
    private Map<String, Object> root;
    private byte[] glbBinary;
    private byte[][] buffers;
    private List<Object> bufferViews;
    private List<Object> accessors;
    private List<Object> textureList;
    private List<Object> samplerList;
    private List<Object> imageList;
    private Pixels[] images;
    private boolean[] imageTried;
    private byte[][] decompressedViews;

    private GltfLoader(Resources resources) {
        this.resources = resources;
    }

    private GltfModel read(byte[] content) throws IOException {
        final String json;
        if (isGlb(content, content.length)) {
            json = readGlb(content);
        } else {
            json = new String(content, StandardCharsets.UTF_8);
        }
        root = Json.object(Json.parse(json));
        if (!root.containsKey("asset")) throw new IOException("Not a glTF file");

        for (Object required : Json.array(root, "extensionsRequired")) {
            for (String name : UNSUPPORTED) {
                if (name.equals(required)) throw new IOException("Unsupported glTF extension: " + name);
            }
        }

        bufferViews = Json.array(root, "bufferViews");
        decompressedViews = new byte[bufferViews.size()][];
        accessors = Json.array(root, "accessors");
        textureList = Json.array(root, "textures");
        samplerList = Json.array(root, "samplers");
        imageList = Json.array(root, "images");
        images = new Pixels[imageList.size()];
        imageTried = new boolean[imageList.size()];
        readBuffers();

        final GltfModel model = new GltfModel();

        // 材質。最後に、材質の指定がない面のための既定の材質を足す
        final List<Object> materialList = Json.array(root, "materials");
        final Draft[] drafts = new Draft[materialList.size() + 1];
        for (int i = 0; i < drafts.length; i++) {
            drafts[i] = draft(i < materialList.size() ? Json.object(materialList.get(i)) : Json.object(null));
        }

        final List<Object> meshList = Json.array(root, "meshes");
        model.meshes = new GltfModel.Mesh[meshList.size()];
        for (int i = 0; i < model.meshes.length; i++) {
            model.meshes[i] = mesh(Json.object(meshList.get(i)), drafts);
        }

        readNodes(model);
        readSkins(model);
        readAnimations(model);

        // テクスチャ。0 番は白 1 ピクセル
        final List<GltfModel.Texture> textures = new ArrayList<GltfModel.Texture>();
        textures.add(new GltfModel.Texture(1, 1, new int[]{0xFFFFFFFF}));
        model.materials = new GltfModel.Material[drafts.length];
        for (int i = 0; i < drafts.length; i++) {
            final Draft draft = drafts[i];
            model.materials[i] = draft.material;
            if (draft.primitives.isEmpty()) continue;
            if (draft.mapping == null) {
                draft.material.texture = 0;
                continue;
            }
            final int[] tilesU = fit(draft, 0, draft.mapping.wrapS, draft.width);
            final int[] tilesV = fit(draft, 1, draft.mapping.wrapT, draft.height);
            if (draft.bake != null && tilesU[0] == 1 && tilesV[0] == 1) {
                final List<GltfBaker.Surface> surfaces = surfaces(model, draft);
                int width = draft.bakeWidth, height = draft.bakeHeight;
                GltfBaker.Result baked = surfaces == null ? null : GltfBaker.bake(draft.bake, surfaces, width, height);
                // 層を並べると大きくなりすぎる場合は、細かさを落としてやり直す
                while (baked != null && width * baked.layers > MAX_TILED_SIZE && width > 64) {
                    width /= 2;
                    height = Math.max(1, height / 2);
                    baked = GltfBaker.bake(draft.bake, surfaces, width, height);
                }
                if (baked != null) {
                    // 陰影まで計算したテクスチャになったので、描画のときは陰影を付けない
                    GltfBaker.apply(surfaces, baked.layers);
                    draft.pixels = baked.pixels;
                    draft.width = width * baked.layers;
                    draft.height = height;
                    draft.material.unlit = true;
                    model.bakedCount++;
                }
            }
            draft.material.texture = textures.size();
            textures.add(tile(draft, tilesU, tilesV));
        }
        model.textures = textures.toArray(new GltfModel.Texture[0]);

        long triangles = 0;
        for (GltfModel.Node node : model.nodes) {
            if (node.mesh < 0) continue;
            for (GltfModel.Primitive primitive : model.meshes[node.mesh].primitives) {
                final long count = primitive.indices.length / 3;
                triangles += model.materials[primitive.material].doubleSided ? count * 2 : count;
            }
        }
        if (triangles > MAX_TRIANGLES) {
            throw new IOException("Model exceeds safety limits (max " + MAX_TRIANGLES + " triangles)");
        }
        if (triangles == 0) throw new IOException("Model has nothing to draw");
        model.triangleCount = (int) triangles;
        return model;
    }

    private String readGlb(byte[] content) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        if (content.length < 20 || buffer.getInt(0) != GLB_MAGIC) throw new IOException("Not a GLB file");
        if (buffer.getInt(4) != 2) throw new IOException("Unsupported GLB version: " + buffer.getInt(4));
        String json = null;
        int pos = 12;
        while (pos + 8 <= content.length) {
            final int length = buffer.getInt(pos);
            final int type = buffer.getInt(pos + 4);
            pos += 8;
            if (length < 0 || pos + (long) length > content.length) throw new IOException("Truncated GLB");
            if (type == CHUNK_JSON && json == null) {
                json = new String(content, pos, length, StandardCharsets.UTF_8);
            } else if (type == CHUNK_BIN && glbBinary == null) {
                glbBinary = new byte[length];
                System.arraycopy(content, pos, glbBinary, 0, length);
            }
            // 4 バイト境界に揃えられている
            pos += (length + 3) & ~3;
        }
        if (json == null) throw new IOException("GLB has no JSON chunk");
        return json;
    }

    private void readBuffers() throws IOException {
        final List<Object> list = Json.array(root, "buffers");
        buffers = new byte[list.size()][];
        for (int i = 0; i < buffers.length; i++) {
            final String uri = Json.string(Json.object(list.get(i)), "uri", null);
            if (uri == null) {
                buffers[i] = glbBinary != null && i == 0 ? glbBinary : new byte[0];
            } else {
                buffers[i] = resource(uri);
            }
        }
    }

    private byte[] resource(String uri) throws IOException {
        return resource(uri, resources);
    }

    // URI の指すデータを読み込む。data URI はその場で復号する
    static byte[] resource(String uri, Resources resources) throws IOException {
        if (uri.startsWith("data:")) {
            final int comma = uri.indexOf(',');
            if (comma < 0) throw new IOException("Invalid data URI");
            final String data = uri.substring(comma + 1);
            if (uri.substring(0, comma).endsWith(";base64")) {
                try {
                    return Base64.getMimeDecoder().decode(data);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid data URI", e);
                }
            }
            return data.getBytes(StandardCharsets.UTF_8);
        }
        if (resources == null) throw new IOException("External resource is not available: " + uri);
        return resources.load(uri);
    }

    // ---- アクセサ ----

    private static int componentCount(String type) throws IOException {
        if ("SCALAR".equals(type)) return 1;
        if ("VEC2".equals(type)) return 2;
        if ("VEC3".equals(type)) return 3;
        if ("VEC4".equals(type) || "MAT2".equals(type)) return 4;
        if ("MAT3".equals(type)) return 9;
        if ("MAT4".equals(type)) return 16;
        throw new IOException("Unknown accessor type: " + type);
    }

    private static int componentSize(int componentType) throws IOException {
        switch (componentType) {
            case 5120:
            case 5121:
                return 1;
            case 5122:
            case 5123:
                return 2;
            case 5125:
            case 5126:
                return 4;
            default:
                throw new IOException("Unknown component type: " + componentType);
        }
    }

    private static double component(ByteBuffer buffer, int pos, int componentType, boolean normalized) {
        switch (componentType) {
            case 5120: {
                final int value = buffer.get(pos);
                return normalized ? Math.max(value / 127.0, -1.0) : value;
            }
            case 5121: {
                final int value = buffer.get(pos) & 0xFF;
                return normalized ? value / 255.0 : value;
            }
            case 5122: {
                final int value = buffer.getShort(pos);
                return normalized ? Math.max(value / 32767.0, -1.0) : value;
            }
            case 5123: {
                final int value = buffer.getShort(pos) & 0xFFFF;
                return normalized ? value / 65535.0 : value;
            }
            case 5125:
                return buffer.getInt(pos) & 0xFFFFFFFFL;
            default:
                return buffer.getFloat(pos);
        }
    }

    // EXT_meshopt_compression / KHR_meshopt_compression の展開バッファを取得する
    private byte[] getDecompressedView(int viewIndex) throws IOException {
        if (viewIndex < 0 || viewIndex >= bufferViews.size()) return null;
        if (decompressedViews[viewIndex] != null) return decompressedViews[viewIndex];
        final Map<String, Object> view = Json.object(bufferViews.get(viewIndex));
        final Map<String, Object> extensions = Json.object(view, "extensions");
        Map<String, Object> meshopt = Json.object(extensions, "EXT_meshopt_compression");
        if (meshopt.isEmpty()) meshopt = Json.object(extensions, "KHR_meshopt_compression");
        if (meshopt.isEmpty()) return null;

        final int bufferIndex = Json.integer(meshopt, "buffer", 0);
        if (bufferIndex < 0 || bufferIndex >= buffers.length) {
            throw new IOException("Invalid meshopt buffer index: " + bufferIndex);
        }
        final byte[] sourceBuffer = buffers[bufferIndex];
        final int byteOffset = Json.integer(meshopt, "byteOffset", 0);
        final int byteLength = Json.integer(meshopt, "byteLength", 0);
        final int byteStride = Json.integer(meshopt, "byteStride", 0);
        final int count = Json.integer(meshopt, "count", 0);
        final String mode = Json.string(meshopt, "mode", "ATTRIBUTES");
        final String filter = Json.string(meshopt, "filter", "NONE");

        if (byteStride <= 0 || count < 0) {
            throw new IOException("Invalid meshopt parameters: stride=" + byteStride + ", count=" + count);
        }
        final long totalBytes = (long) count * byteStride;
        if (totalBytes > 1 << 28) {
            throw new IOException("Meshopt decompressed size exceeds safety limit: " + totalBytes);
        }
        final byte[] decompressed = new byte[(int) totalBytes];

        if ("ATTRIBUTES".equals(mode)) {
            MeshoptDecoder.decodeVertexBuffer(decompressed, 0, count, byteStride, sourceBuffer, byteOffset, byteLength);
        } else if ("TRIANGLES".equals(mode)) {
            MeshoptDecoder.decodeIndexBuffer(decompressed, 0, count, byteStride, sourceBuffer, byteOffset, byteLength);
        } else if ("INDICES".equals(mode)) {
            MeshoptDecoder.decodeIndexSequence(decompressed, 0, count, byteStride, sourceBuffer, byteOffset, byteLength);
        } else {
            throw new IOException("Unsupported meshopt compression mode: " + mode);
        }

        if ("OCTAHEDRAL".equals(filter)) {
            MeshoptDecoder.decodeFilterOct(decompressed, 0, count, byteStride);
        } else if ("QUATERNION".equals(filter)) {
            MeshoptDecoder.decodeFilterQuat(decompressed, 0, count, byteStride);
        } else if ("EXPONENTIAL".equals(filter)) {
            MeshoptDecoder.decodeFilterExp(decompressed, 0, count, byteStride);
        } else if ("COLOR".equals(filter)) {
            MeshoptDecoder.decodeFilterColor(decompressed, 0, count, byteStride);
        } else if (!"NONE".equals(filter) && filter != null) {
            throw new IOException("Unsupported meshopt filter: " + filter);
        }

        decompressedViews[viewIndex] = decompressed;
        return decompressed;
    }

    // バッファビューから count 個の要素を読んで out に入れる
    private void readView(int viewIndex, int byteOffset, int componentType, boolean normalized,
                          int components, int count, double[] out) throws IOException {
        final Map<String, Object> view = Json.object(bufferViews.get(viewIndex));
        final byte[] decomp = getDecompressedView(viewIndex);
        final byte[] data;
        final int viewOffset;
        final int viewLength;
        if (decomp != null) {
            data = decomp;
            viewOffset = 0;
            viewLength = decomp.length;
        } else {
            data = buffers[Json.integer(view, "buffer", 0)];
            viewOffset = Json.integer(view, "byteOffset", 0);
            viewLength = Json.integer(view, "byteLength", 0);
        }
        final int size = componentSize(componentType);
        final int elementSize = size * components;
        int stride = Json.integer(view, "byteStride", 0);
        if (stride <= 0) stride = elementSize;

        final long start = (long) viewOffset + byteOffset;
        final long end = count == 0 ? start : start + (long) (count - 1) * stride + elementSize;
        if (viewOffset < 0 || byteOffset < 0 || end > (long) viewOffset + viewLength || end > data.length) {
            throw new IOException("Accessor is out of range");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            final int base = (int) start + i * stride;
            for (int c = 0; c < components; c++) {
                out[i * components + c] = component(buffer, base + c * size, componentType, normalized);
            }
        }
    }

    private int accessorCount(int index) {
        return Json.integer(Json.object(accessors.get(index)), "count", 0);
    }

    private int accessorComponents(int index) throws IOException {
        return componentCount(Json.string(Json.object(accessors.get(index)), "type", "SCALAR"));
    }

    private double[] accessor(int index) throws IOException {
        final Map<String, Object> accessor = Json.object(accessors.get(index));
        final int count = Json.integer(accessor, "count", 0);
        final int components = componentCount(Json.string(accessor, "type", "SCALAR"));
        final int componentType = Json.integer(accessor, "componentType", 5126);
        final boolean normalized = Json.bool(accessor, "normalized", false);
        if (count < 0) throw new IOException("Invalid accessor");

        final double[] out;
        if (accessor.containsKey("bufferView")) {
            final int viewIndex = Json.integer(accessor, "bufferView", 0);
            final byte[] decomp = getDecompressedView(viewIndex);
            final Map<String, Object> view = Json.object(bufferViews.get(viewIndex));
            final int viewLength = decomp != null ? decomp.length : Json.integer(view, "byteLength", 0);
            if ((long) count * components * componentSize(componentType) > viewLength) {
                throw new IOException("Accessor is out of range");
            }
            out = new double[count * components];
            readView(viewIndex, Json.integer(accessor, "byteOffset", 0),
                    componentType, normalized, components, count, out);
        } else {
            if ((long) count * components > MAX_EMPTY_ACCESSOR) throw new IOException("Accessor is too large");
            out = new double[count * components];
        }

        // 一部の要素だけを上書きする形式
        final Map<String, Object> sparse = Json.object(accessor, "sparse");
        if (!sparse.isEmpty()) {
            final int sparseCount = Json.integer(sparse, "count", 0);
            if (sparseCount < 0 || sparseCount > count) throw new IOException("Invalid sparse accessor");
            final Map<String, Object> indices = Json.object(sparse, "indices");
            final Map<String, Object> values = Json.object(sparse, "values");
            final double[] targets = new double[sparseCount];
            readView(Json.integer(indices, "bufferView", 0), Json.integer(indices, "byteOffset", 0),
                    Json.integer(indices, "componentType", 5125), false, 1, sparseCount, targets);
            final double[] value = new double[sparseCount * components];
            readView(Json.integer(values, "bufferView", 0), Json.integer(values, "byteOffset", 0),
                    componentType, normalized, components, sparseCount, value);
            for (int i = 0; i < sparseCount; i++) {
                final int target = (int) targets[i];
                if (target < 0 || target >= count) throw new IOException("Invalid sparse accessor");
                System.arraycopy(value, i * components, out, target * components, components);
            }
        }
        return out;
    }

    private float[] floats(int index) throws IOException {
        final double[] values = accessor(index);
        final float[] result = new float[values.length];
        for (int i = 0; i < result.length; i++) {
            final float value = (float) values[i];
            // 壊れた値で以降の計算が止まらないようにする
            result[i] = Float.isNaN(value) || Float.isInfinite(value) ? 0f : value;
        }
        return result;
    }

    private int[] ints(int index) throws IOException {
        final double[] values = accessor(index);
        final int[] result = new int[values.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (int) values[i];
        }
        return result;
    }

    // ---- 画像と材質 ----

    private static float gamma(float linear) {
        if (linear <= 0) return 0;
        return (float) Math.pow(Math.min(linear, 1f), INVERSE_GAMMA);
    }

    // 画像を読み込む。読めない形式なら null
    private Pixels image(int index) {
        if (index < 0 || index >= images.length) return null;
        if (imageTried[index]) return images[index];
        imageTried[index] = true;
        try {
            final Map<String, Object> json = Json.object(imageList.get(index));
            final byte[] data;
            final String uri = Json.string(json, "uri", null);
            if (uri != null) {
                data = resource(uri);
            } else {
                final int viewIndex = Json.integer(json, "bufferView", -1);
                final byte[] decomp = getDecompressedView(viewIndex);
                if (decomp != null) {
                    data = decomp;
                } else {
                    final Map<String, Object> view = Json.object(bufferViews.get(viewIndex));
                    final byte[] buffer = buffers[Json.integer(view, "buffer", 0)];
                    final int offset = Json.integer(view, "byteOffset", 0);
                    final int length = Json.integer(view, "byteLength", 0);
                    if (offset < 0 || length < 0 || (long) offset + length > buffer.length) return null;
                    data = new byte[length];
                    System.arraycopy(buffer, offset, data, 0, length);
                }
            }
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
            if (decoded == null) return null;

            int width = decoded.getWidth();
            int height = decoded.getHeight();
            if (width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
                final float ratio = Math.min(MAX_TEXTURE_SIZE / (float) width, MAX_TEXTURE_SIZE / (float) height);
                width = Math.max(1, Math.round(width * ratio));
                height = Math.max(1, Math.round(height * ratio));
                final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = scaled.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(decoded, 0, 0, width, height, null);
                } finally {
                    g.dispose();
                }
                decoded = scaled;
            }
            images[index] = new Pixels(width, height, decoded.getRGB(0, 0, width, height, null, 0, width));
        } catch (Exception e) {
            // 画像が読めなくても、形だけは表示する
            images[index] = null;
        }
        return images[index];
    }

    private Pixels textureImage(TexRef ref) {
        if (ref == null || ref.texture < 0 || ref.texture >= textureList.size()) return null;
        final Map<String, Object> texture = Json.object(textureList.get(ref.texture));
        int source = Json.integer(texture, "source", -1);
        if (source < 0) {
            // 別の形式の画像だけが指定されている場合
            final Map<String, Object> extensions = Json.object(texture, "extensions");
            for (Object extension : extensions.values()) {
                source = Json.integer(Json.object(extension), "source", -1);
                if (source >= 0) break;
            }
        }
        return image(source);
    }

    private TexRef texRef(Map<String, Object> parent, String name) {
        if (!parent.containsKey(name)) return null;
        final Map<String, Object> info = Json.object(parent, name);
        final TexRef ref = new TexRef();
        ref.texture = Json.integer(info, "index", -1);
        ref.texCoord = Json.integer(info, "texCoord", 0);

        final Map<String, Object> transform = Json.object(Json.object(info, "extensions"), "KHR_texture_transform");
        if (!transform.isEmpty()) {
            final float[] offset = Json.numbers(transform, "offset", new float[]{0, 0});
            final float[] scale = Json.numbers(transform, "scale", new float[]{1, 1});
            ref.offsetX = offset[0];
            ref.offsetY = offset[1];
            ref.scaleX = scale[0];
            ref.scaleY = scale[1];
            ref.rotation = Json.number(transform, "rotation", 0);
            ref.texCoord = Json.integer(transform, "texCoord", ref.texCoord);
        }

        if (ref.texture >= 0 && ref.texture < textureList.size()) {
            final int sampler = Json.integer(Json.object(textureList.get(ref.texture)), "sampler", -1);
            if (sampler >= 0 && sampler < samplerList.size()) {
                final Map<String, Object> json = Json.object(samplerList.get(sampler));
                ref.wrapS = Json.integer(json, "wrapS", WRAP_REPEAT);
                ref.wrapT = Json.integer(json, "wrapT", WRAP_REPEAT);
            }
        }
        return ref;
    }

    private Draft draft(Map<String, Object> json) {
        final Draft draft = new Draft();
        final GltfModel.Material material = draft.material;
        final Map<String, Object> extensions = Json.object(json, "extensions");

        final Map<String, Object> pbr = Json.object(json, "pbrMetallicRoughness");
        float[] baseFactor = Json.numbers(pbr, "baseColorFactor", new float[]{1, 1, 1, 1});
        TexRef baseRef = texRef(pbr, "baseColorTexture");
        material.metallic = Json.number(pbr, "metallicFactor", 1);
        material.roughness = Json.number(pbr, "roughnessFactor", 1);
        final float metallicFactor = material.metallic, roughnessFactor = material.roughness;

        // テクスチャで指定されている金属らしさと粗さは、焼き込まない場合、平均を全体の値として使う
        final TexRef metallicRoughnessRef = texRef(pbr, "metallicRoughnessTexture");
        final Pixels metallicRoughness = textureImage(metallicRoughnessRef);
        if (metallicRoughness != null) {
            long green = 0, blue = 0;
            int samples = 0;
            final int step = Math.max(1, metallicRoughness.argb.length / 4096);
            for (int i = 0; i < metallicRoughness.argb.length; i += step) {
                green += (metallicRoughness.argb[i] >> 8) & 0xFF;
                blue += metallicRoughness.argb[i] & 0xFF;
                samples++;
            }
            material.roughness *= green / (255f * samples);
            material.metallic *= blue / (255f * samples);
        }

        // 古い形式の材質
        final Map<String, Object> glossiness = Json.object(extensions, "KHR_materials_pbrSpecularGlossiness");
        if (!glossiness.isEmpty()) {
            baseFactor = Json.numbers(glossiness, "diffuseFactor", new float[]{1, 1, 1, 1});
            baseRef = texRef(glossiness, "diffuseTexture");
            material.metallic = 0;
            material.roughness = 1 - Json.number(glossiness, "glossinessFactor", 1);
        }

        final String alphaMode = Json.string(json, "alphaMode", "OPAQUE");
        material.alphaMode = "BLEND".equals(alphaMode) ? GltfModel.ALPHA_BLEND
                : "MASK".equals(alphaMode) ? GltfModel.ALPHA_MASK : GltfModel.ALPHA_OPAQUE;
        final float alphaCutoff = Json.number(json, "alphaCutoff", 0.5f);
        material.doubleSided = Json.bool(json, "doubleSided", false);
        material.unlit = extensions.containsKey("KHR_materials_unlit");

        final float[] emissiveFactor = Json.numbers(json, "emissiveFactor", new float[]{0, 0, 0});
        final float strength = Json.number(Json.object(extensions, "KHR_materials_emissive_strength"), "emissiveStrength", 1);
        final float[] emissive = {gamma(emissiveFactor[0] * strength), gamma(emissiveFactor[1] * strength),
                gamma(emissiveFactor[2] * strength)};
        final boolean glows = emissive[0] > 0 || emissive[1] > 0 || emissive[2] > 0;
        final TexRef emissiveRef = glows ? texRef(json, "emissiveTexture") : null;
        final TexRef occlusionRef = texRef(json, "occlusionTexture");
        final float occlusionStrength = Json.number(Json.object(json, "occlusionTexture"), "strength", 1);

        final float[] base = {gamma(baseFactor[0]), gamma(baseFactor[1]), gamma(baseFactor[2]),
                Math.max(0, Math.min(1, baseFactor[3]))};

        final Pixels baseImage = textureImage(baseRef);
        final Pixels emissiveImage = textureImage(emissiveRef);
        final Pixels occlusionImage = textureImage(occlusionRef);
        final TexRef normalRef = material.unlit ? null : texRef(json, "normalTexture");
        final Pixels normalImage = textureImage(normalRef);
        final TexRef roughnessRef = material.unlit || !glossiness.isEmpty() ? null : metallicRoughnessRef;
        final Pixels roughnessImage = roughnessRef == null ? null : metallicRoughness;

        // テクスチャ座標は、色のテクスチャのものを使う。なければ、ほかに使っているテクスチャのもの
        final TexRef mapping = baseImage != null ? baseRef : emissiveImage != null ? emissiveRef
                : occlusionImage != null ? occlusionRef : normalImage != null ? normalRef
                : roughnessImage != null ? roughnessRef : null;
        if (mapping == null) {
            // テクスチャがない材質は、頂点の色だけで表す
            float alpha = base[3];
            if (material.alphaMode == GltfModel.ALPHA_OPAQUE) alpha = 1;
            else if (material.alphaMode == GltfModel.ALPHA_MASK) alpha = alpha >= alphaCutoff ? 1 : 0;
            material.color[0] = base[0];
            material.color[1] = base[1];
            material.color[2] = base[2];
            material.color[3] = alpha;
            // 発光のテクスチャが読めなかった場合は、全体が光ってしまわないように無視する
            if (glows && emissiveRef == null) System.arraycopy(emissive, 0, material.emissive, 0, 3);
            return draft;
        }

        final Pixels size = baseImage != null ? baseImage : emissiveImage != null ? emissiveImage
                : occlusionImage != null ? occlusionImage : normalImage != null ? normalImage : roughnessImage;
        final Pixels emissiveMap = emissiveImage != null && emissiveRef.sameMapping(mapping) ? emissiveImage : null;
        final Pixels occlusionMap = occlusionImage != null && occlusionRef.sameMapping(mapping) ? occlusionImage : null;
        final boolean constantGlow = glows && emissiveRef == null;

        final int width = size.width, height = size.height;
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int source = baseImage != null ? baseImage.argb[y * width + x] : 0xFFFFFFFF;
                float r = ((source >> 16) & 0xFF) / 255f * base[0];
                float g = ((source >> 8) & 0xFF) / 255f * base[1];
                float b = (source & 0xFF) / 255f * base[2];
                float a = ((source >>> 24) & 0xFF) / 255f * base[3];

                if (occlusionMap != null) {
                    final int sample = sample(occlusionMap, x, y, width, height);
                    final float occlusion = 1 + occlusionStrength * (((sample >> 16) & 0xFF) / 255f - 1);
                    final float shade = gamma(occlusion);
                    r *= shade;
                    g *= shade;
                    b *= shade;
                }
                if (emissiveMap != null) {
                    final int sample = sample(emissiveMap, x, y, width, height);
                    r += ((sample >> 16) & 0xFF) / 255f * emissive[0];
                    g += ((sample >> 8) & 0xFF) / 255f * emissive[1];
                    b += (sample & 0xFF) / 255f * emissive[2];
                } else if (constantGlow) {
                    r += emissive[0];
                    g += emissive[1];
                    b += emissive[2];
                }

                if (material.alphaMode == GltfModel.ALPHA_OPAQUE) a = 1;
                else if (material.alphaMode == GltfModel.ALPHA_MASK) a = a >= alphaCutoff ? 1 : 0;

                pixels[y * width + x] = (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
            }
        }
        draft.mapping = mapping;
        draft.width = width;
        draft.height = height;
        draft.pixels = pixels;

        // ピクセル単位の計算が必要な表現があれば、あとで陰影ごとテクスチャに焼き込む。
        // 焼き込みでは、別のテクスチャ座標を使っているテクスチャも扱える
        final boolean detailed = normalImage != null || roughnessImage != null || emissiveImage != null
                || (occlusionImage != null && occlusionMap == null);
        if (!material.unlit && detailed) {
            final GltfBaker.Input input = new GltfBaker.Input();
            input.base = map(draft, baseImage, baseRef);
            input.normal = map(draft, normalImage, normalRef);
            input.metallicRoughness = map(draft, roughnessImage, roughnessRef);
            input.occlusion = map(draft, occlusionImage, occlusionRef);
            input.emissive = map(draft, emissiveImage, emissiveRef);
            input.baseFactor = new float[]{Math.max(0, baseFactor[0]), Math.max(0, baseFactor[1]), Math.max(0, baseFactor[2]), base[3]};
            if (emissiveImage != null || constantGlow) {
                input.emissiveFactor = new float[]{Math.max(0, emissiveFactor[0] * strength),
                        Math.max(0, emissiveFactor[1] * strength), Math.max(0, emissiveFactor[2] * strength)};
            }
            // テクスチャがあればその値を掛け、なければ係数だけを使う
            input.metallic = glossiness.isEmpty() ? metallicFactor : material.metallic;
            input.roughness = glossiness.isEmpty() ? roughnessFactor : material.roughness;
            input.normalScale = Json.number(Json.object(json, "normalTexture"), "scale", 1);
            input.occlusionStrength = occlusionStrength;
            input.alphaMode = material.alphaMode;
            input.alphaCutoff = alphaCutoff;
            draft.bake = input;
            for (Pixels image : new Pixels[]{baseImage, normalImage, roughnessImage, emissiveImage}) {
                if (image == null) continue;
                draft.bakeWidth = Math.max(draft.bakeWidth, image.width);
                draft.bakeHeight = Math.max(draft.bakeHeight, image.height);
            }
            // 陰影はテクスチャの細かさでしか表せないので、小さな画像しか使っていない材質でも、ある程度の細かさにする
            while (Math.max(draft.bakeWidth, draft.bakeHeight) < MIN_BAKE_SIZE) {
                draft.bakeWidth *= 2;
                draft.bakeHeight *= 2;
            }
        }
        return draft;
    }

    // 焼き込みに使う画像。材質のテクスチャ座標と違う貼り方のものには、別の座標の番号を割り当てる
    private static GltfBaker.Map map(Draft draft, Pixels image, TexRef ref) {
        if (image == null) return null;
        int slot = 0;
        if (!ref.sameMapping(draft.mapping)) {
            for (int i = 0; i < draft.extraRefs.size() && slot == 0; i++) {
                if (draft.extraRefs.get(i).sameMapping(ref)) slot = i + 1;
            }
            if (slot == 0) {
                draft.extraRefs.add(ref);
                slot = draft.extraRefs.size();
            }
        }
        return new GltfBaker.Map(image.width, image.height, image.argb, slot,
                ref.wrapS == WRAP_CLAMP ? 0 : ref.wrapS == WRAP_MIRROR ? 2 : 1,
                ref.wrapT == WRAP_CLAMP ? 0 : ref.wrapT == WRAP_MIRROR ? 2 : 1);
    }

    // 大きさの違う画像の、同じ位置の色を取り出す
    private static int sample(Pixels image, int x, int y, int width, int height) {
        final int sx = Math.min(image.width - 1, (int) ((long) x * image.width / width));
        final int sy = Math.min(image.height - 1, (int) ((long) y * image.height / height));
        return image.argb[sy * image.width + sx];
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    /**
     * 材質を焼き込む対象の面を集める。
     * 陰影はモデルに固定で計算するため、動くモデルや、同じメッシュを複数の場所で使っているモデルは対象にしない (null を返す)。
     */
    private static List<GltfBaker.Surface> surfaces(GltfModel model, Draft draft) {
        if (model.animations.length > 0) return null;

        // 最初の姿勢での、各ノードの変換
        final float[][] global = new float[model.nodes.length][];
        for (int index : model.order) {
            final GltfModel.Node node = model.nodes[index];
            final float[] local = new float[16];
            if (node.matrix != null) System.arraycopy(node.matrix, 0, local, 0, 16);
            else Mat4.compose(node.translation, node.rotation, node.scale, local);
            if (node.parent < 0) {
                global[index] = local;
            } else {
                global[index] = new float[16];
                Mat4.multiply(global[node.parent], local, global[index]);
            }
        }

        final List<GltfBaker.Surface> surfaces = new ArrayList<GltfBaker.Surface>();
        for (GltfModel.Primitive primitive : draft.primitives) {
            if (primitive.morphPositions.length > 0) return null;
            float[] matrix = null;
            for (int index : model.order) {
                final GltfModel.Node node = model.nodes[index];
                if (node.mesh < 0) continue;
                boolean uses = false;
                for (GltfModel.Primitive candidate : model.meshes[node.mesh].primitives) {
                    if (candidate == primitive) uses = true;
                }
                if (!uses) continue;
                if (matrix != null || node.skin >= 0) return null;
                matrix = global[index];
            }
            if (matrix != null) surfaces.add(new GltfBaker.Surface(primitive, matrix, draft.extraUv.get(primitive)));
        }
        return surfaces.isEmpty() ? null : surfaces;
    }

    // 繰り返しの指定に合わせて画像を並べたテクスチャを作る。tilesU, tilesV は fit で求めたもの
    private static GltfModel.Texture tile(Draft draft, int[] tilesU, int[] tilesV) {
        if (tilesU[0] == 1 && tilesV[0] == 1) return new GltfModel.Texture(draft.width, draft.height, draft.pixels);

        final int width = draft.width * tilesU[0], height = draft.height * tilesV[0];
        final int[] columns = new int[width];
        for (int x = 0; x < width; x++) columns[x] = source(x, draft.width, draft.mapping.wrapS, tilesU[1]);
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int sy = source(y, draft.height, draft.mapping.wrapT, tilesV[1]);
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = draft.pixels[sy * draft.width + columns[x]];
            }
        }
        return new GltfModel.Texture(width, height, pixels);
    }

    // 並べたテクスチャの位置に対応する、元の画像の位置
    private static int source(int position, int size, int wrap, int origin) {
        if (wrap == WRAP_CLAMP) {
            // 元の画像の外側は、端の色を引き伸ばす
            return Math.max(0, Math.min(size - 1, position - origin * size));
        }
        final int inside = position % size;
        return wrap == WRAP_MIRROR && ((position / size) & 1) == 1 ? size - 1 - inside : inside;
    }

    /**
     * 1 つの軸についてテクスチャ座標を 0 - 1 に収める。
     * 範囲の外を使っている場合は、画像を並べた (端で止める指定なら端の色を引き伸ばした) テクスチャを使うことにして、
     * {並べる枚数, 元の画像を置く位置} を返す。
     */
    private static int[] fit(Draft draft, int axis, int wrap, int size) {
        final int limit = Math.max(1, Math.min(MAX_TILES, MAX_TILED_SIZE / Math.max(1, size)));
        if (wrap == WRAP_CLAMP) {
            float min = 0, max = 1;
            for (GltfModel.Primitive primitive : draft.primitives) {
                final float[] uv = primitive.cornerUv;
                for (int i = axis; i < uv.length; i += 2) {
                    if (uv[i] < min) min = uv[i];
                    if (uv[i] > max) max = uv[i];
                }
            }
            // 元の画像の前後に必要な枚数。上限を超える分は、座標を端に寄せる
            int before = Math.min(limit - 1, (int) Math.ceil(-min - 1e-4f));
            int after = Math.min(limit - 1 - before, (int) Math.ceil(max - 1 - 1e-4f));
            before = Math.max(0, before);
            after = Math.max(0, after);
            final int count = before + 1 + after;
            for (GltfModel.Primitive primitive : draft.primitives) {
                final float[] uv = primitive.cornerUv;
                for (int i = axis; i < uv.length; i += 2) {
                    uv[i] = (Math.max(-before, Math.min(1f + after, uv[i])) + before) / count;
                }
            }
            return new int[]{count, before};
        }

        final int period = wrap == WRAP_MIRROR ? 2 : 1;
        float max = 1;
        for (GltfModel.Primitive primitive : draft.primitives) {
            final float[] uv = primitive.cornerUv;
            // 三角形ごとに、繰り返しの単位だけずらして 0 の近くに寄せる
            for (int i = axis; i + 4 < uv.length; i += 6) {
                final float min = Math.min(uv[i], Math.min(uv[i + 2], uv[i + 4]));
                final float shift = (float) Math.floor((min + 1e-4f) / period) * period;
                for (int k = 0; k < 3; k++) {
                    uv[i + k * 2] = Math.max(0f, uv[i + k * 2] - shift);
                    if (uv[i + k * 2] > max) max = uv[i + k * 2];
                }
            }
        }
        final int tiles = Math.max(1, Math.min(limit, (int) Math.ceil(max - 1e-4f)));
        for (GltfModel.Primitive primitive : draft.primitives) {
            final float[] uv = primitive.cornerUv;
            for (int i = axis; i < uv.length; i += 2) {
                uv[i] = Math.min(1f, uv[i] / tiles);
            }
        }
        return new int[]{tiles, 0};
    }

    // ---- メッシュ ----

    private GltfModel.Mesh mesh(Map<String, Object> json, Draft[] drafts) throws IOException {
        final GltfModel.Mesh mesh = new GltfModel.Mesh();
        final List<GltfModel.Primitive> primitives = new ArrayList<GltfModel.Primitive>();
        for (Object item : Json.array(json, "primitives")) {
            final GltfModel.Primitive primitive = primitive(Json.object(item), drafts);
            if (primitive != null) primitives.add(primitive);
        }
        mesh.primitives = primitives.toArray(new GltfModel.Primitive[0]);
        if (json.containsKey("weights")) mesh.weights = Json.numbers(json.get("weights"));
        return mesh;
    }

    private GltfModel.Primitive primitive(Map<String, Object> json, Draft[] drafts) throws IOException {
        if (Json.object(json, "extensions").containsKey("KHR_draco_mesh_compression")) {
            throw new IOException("Unsupported glTF extension: KHR_draco_mesh_compression");
        }
        final Map<String, Object> attributes = Json.object(json, "attributes");
        if (!attributes.containsKey("POSITION")) return null;
        final int mode = Json.integer(json, "mode", 4);
        // 点と線は描かない
        if (mode < 4 || mode > 6) return null;

        final GltfModel.Primitive primitive = new GltfModel.Primitive();
        final int position = Json.integer(attributes, "POSITION", -1);
        primitive.positions = floats(position);
        if (accessorComponents(position) != 3) throw new IOException("Invalid POSITION accessor");
        final int count = primitive.positions.length / 3;
        primitive.vertexCount = count;

        if (attributes.containsKey("NORMAL")) {
            final float[] normals = floats(Json.integer(attributes, "NORMAL", -1));
            if (normals.length == count * 3) primitive.normals = normals;
        }

        if (attributes.containsKey("TANGENT")) {
            final float[] tangents = floats(Json.integer(attributes, "TANGENT", -1));
            if (tangents.length == count * 4) primitive.tangents = tangents;
        }

        if (attributes.containsKey("COLOR_0")) {
            final int accessor = Json.integer(attributes, "COLOR_0", -1);
            final float[] source = floats(accessor);
            final int components = accessorComponents(accessor);
            if ((components == 3 || components == 4) && source.length == count * components) {
                final float[] colors = new float[count * 4];
                for (int i = 0; i < count; i++) {
                    colors[i * 4] = gamma(source[i * components]);
                    colors[i * 4 + 1] = gamma(source[i * components + 1]);
                    colors[i * 4 + 2] = gamma(source[i * components + 2]);
                    colors[i * 4 + 3] = components == 4 ? Math.max(0, Math.min(1, source[i * components + 3])) : 1;
                }
                primitive.colors = colors;
            }
        }

        readInfluences(primitive, attributes, count);

        // 頂点番号。帯や扇の形式は、三角形の並びに直す
        final int[] source;
        if (json.containsKey("indices")) {
            source = ints(Json.integer(json, "indices", -1));
            for (int index : source) {
                if (index < 0 || index >= count) throw new IOException("Vertex index is out of range");
            }
        } else {
            source = new int[count];
            for (int i = 0; i < count; i++) source[i] = i;
        }
        primitive.indices = triangles(source, mode);

        // モーフィング
        final List<Object> targets = Json.array(json, "targets");
        final List<float[]> morphPositions = new ArrayList<float[]>();
        final List<float[]> morphNormals = new ArrayList<float[]>();
        for (Object item : targets) {
            final Map<String, Object> target = Json.object(item);
            float[] positions = target.containsKey("POSITION") ? floats(Json.integer(target, "POSITION", -1)) : null;
            float[] normals = target.containsKey("NORMAL") ? floats(Json.integer(target, "NORMAL", -1)) : null;
            if (positions != null && positions.length != count * 3) positions = null;
            if (normals != null && normals.length != count * 3) normals = null;
            morphPositions.add(positions);
            morphNormals.add(normals);
        }
        primitive.morphPositions = morphPositions.toArray(new float[0][]);
        primitive.morphNormals = morphNormals.toArray(new float[0][]);

        // 材質とテクスチャ座標
        int material = Json.integer(json, "material", -1);
        if (material < 0 || material >= drafts.length - 1) material = drafts.length - 1;
        primitive.material = material;
        final Draft draft = drafts[material];
        draft.primitives.add(primitive);

        primitive.cornerUv = cornerUv(attributes, draft.mapping, primitive.indices, count);
        if (!draft.extraRefs.isEmpty()) {
            final float[][] extra = new float[draft.extraRefs.size()][];
            for (int i = 0; i < extra.length; i++) {
                extra[i] = cornerUv(attributes, draft.extraRefs.get(i), primitive.indices, count);
            }
            draft.extraUv.put(primitive, extra);
        }
        return primitive;
    }

    // 三角形の角ごとのテクスチャ座標を、テクスチャの貼り方の指定 (ずらし、回転、拡大) を反映して求める
    private float[] cornerUv(Map<String, Object> attributes, TexRef mapping, int[] indices, int count) throws IOException {
        final float[] result = new float[indices.length * 2];
        final String name = mapping == null ? null : "TEXCOORD_" + mapping.texCoord;
        if (name == null || !attributes.containsKey(name)) return result;
        final float[] uv = floats(Json.integer(attributes, name, -1));
        if (uv.length != count * 2) return result;
        final float cos = (float) Math.cos(mapping.rotation);
        final float sin = (float) Math.sin(mapping.rotation);
        for (int i = 0; i < indices.length; i++) {
            final float u = uv[indices[i] * 2] * mapping.scaleX;
            final float v = uv[indices[i] * 2 + 1] * mapping.scaleY;
            result[i * 2] = cos * u + sin * v + mapping.offsetX;
            result[i * 2 + 1] = -sin * u + cos * v + mapping.offsetY;
        }
        return result;
    }

    private void readInfluences(GltfModel.Primitive primitive, Map<String, Object> attributes, int count) throws IOException {
        if (!attributes.containsKey("JOINTS_0") || !attributes.containsKey("WEIGHTS_0")) return;
        final int sets = attributes.containsKey("JOINTS_1") && attributes.containsKey("WEIGHTS_1") ? 2 : 1;
        final int influences = sets * 4;
        final int[] joints = new int[count * influences];
        final float[] weights = new float[count * influences];
        for (int set = 0; set < sets; set++) {
            final int[] setJoints = ints(Json.integer(attributes, "JOINTS_" + set, -1));
            final float[] setWeights = floats(Json.integer(attributes, "WEIGHTS_" + set, -1));
            if (setJoints.length != count * 4 || setWeights.length != count * 4) return;
            for (int i = 0; i < count; i++) {
                for (int k = 0; k < 4; k++) {
                    joints[i * influences + set * 4 + k] = setJoints[i * 4 + k];
                    weights[i * influences + set * 4 + k] = Math.max(0f, setWeights[i * 4 + k]);
                }
            }
        }
        // 重みの合計を 1 に揃える
        for (int i = 0; i < count; i++) {
            float sum = 0;
            for (int k = 0; k < influences; k++) sum += weights[i * influences + k];
            if (sum <= 0) {
                weights[i * influences] = 1;
            } else {
                for (int k = 0; k < influences; k++) weights[i * influences + k] /= sum;
            }
        }
        primitive.influences = influences;
        primitive.joints = joints;
        primitive.weights = weights;
    }

    private static int[] triangles(int[] source, int mode) {
        if (mode == 4) {
            final int[] result = new int[source.length / 3 * 3];
            System.arraycopy(source, 0, result, 0, result.length);
            return result;
        }
        final int count = Math.max(0, source.length - 2);
        final int[] result = new int[count * 3];
        for (int i = 0; i < count; i++) {
            if (mode == 5) {
                // 帯: 1 枚おきに向きが逆になる
                result[i * 3] = source[i];
                result[i * 3 + 1] = source[i + 1 + (i & 1)];
                result[i * 3 + 2] = source[i + 2 - (i & 1)];
            } else {
                // 扇
                result[i * 3] = source[0];
                result[i * 3 + 1] = source[i + 1];
                result[i * 3 + 2] = source[i + 2];
            }
        }
        return result;
    }

    // ---- ノード、スキン、アニメーション ----

    private void readNodes(GltfModel model) throws IOException {
        final List<Object> list = Json.array(root, "nodes");
        if (list.size() > MAX_NODES) throw new IOException("Model exceeds safety limits (max " + MAX_NODES + " nodes)");
        final List<GltfModel.Node> nodeList = new ArrayList<GltfModel.Node>();
        final List<List<Integer>> childrenList = new ArrayList<List<Integer>>();
        for (int i = 0; i < list.size(); i++) {
            final Map<String, Object> json = Json.object(list.get(i));
            final GltfModel.Node node = new GltfModel.Node();
            if (json.containsKey("matrix")) {
                final float[] matrix = Json.numbers(json.get("matrix"));
                if (matrix.length == 16) node.matrix = matrix;
            }
            node.translation = Json.numbers(json, "translation", node.translation);
            node.rotation = Json.numbers(json, "rotation", node.rotation);
            node.scale = Json.numbers(json, "scale", node.scale);
            Mat4.normalize4(node.rotation);

            final int mesh = Json.integer(json, "mesh", -1);
            if (mesh >= 0 && mesh < model.meshes.length) node.mesh = mesh;
            node.skin = Json.integer(json, "skin", -1);
            if (json.containsKey("weights")) node.weights = Json.numbers(json.get("weights"));
            nodeList.add(node);
            childrenList.add(new ArrayList<Integer>());
        }

        // 親子関係。親は 1 つだけなので、2 つ目以降の指定は無視する
        for (int i = 0; i < list.size(); i++) {
            final List<Object> children = Json.array(Json.object(list.get(i)), "children");
            for (Object item : children) {
                if (!(item instanceof Double)) continue;
                final int child = (int) Math.round((Double) item);
                if (child < 0 || child >= list.size() || child == i || nodeList.get(child).parent >= 0) continue;
                nodeList.get(child).parent = i;
                childrenList.get(i).add(child);
            }
        }

        // EXT_mesh_gpu_instancing: 各インスタンスを子ノードとして展開する
        for (int i = 0; i < list.size(); i++) {
            final Map<String, Object> json = Json.object(list.get(i));
            final Map<String, Object> extensions = Json.object(json, "extensions");
            final Map<String, Object> instancing = Json.object(extensions, "EXT_mesh_gpu_instancing");
            if (instancing.isEmpty()) continue;
            final GltfModel.Node parentNode = nodeList.get(i);
            if (parentNode.mesh < 0) continue;

            final Map<String, Object> attributes = Json.object(instancing, "attributes");
            final int transAcc = Json.integer(attributes, "TRANSLATION", -1);
            final int rotAcc = Json.integer(attributes, "ROTATION", -1);
            final int scaleAcc = Json.integer(attributes, "SCALE", -1);

            int instanceCount = -1;
            if (transAcc >= 0) instanceCount = accessorCount(transAcc);
            if (rotAcc >= 0) {
                final int count = accessorCount(rotAcc);
                if (instanceCount >= 0 && instanceCount != count) throw new IOException("Instancing accessor count mismatch");
                instanceCount = count;
            }
            if (scaleAcc >= 0) {
                final int count = accessorCount(scaleAcc);
                if (instanceCount >= 0 && instanceCount != count) throw new IOException("Instancing accessor count mismatch");
                instanceCount = count;
            }
            if (instanceCount <= 0) continue;

            if ((long) nodeList.size() + instanceCount > MAX_NODES) {
                throw new IOException("Model exceeds safety limits (max " + MAX_NODES + " nodes)");
            }

            final float[] translations = transAcc >= 0 ? floats(transAcc) : null;
            final float[] rotations = rotAcc >= 0 ? floats(rotAcc) : null;
            final float[] scales = scaleAcc >= 0 ? floats(scaleAcc) : null;

            final int origMesh = parentNode.mesh;
            final int origSkin = parentNode.skin;
            final float[] origWeights = parentNode.weights;
            // 元のノード自体は描画しない (各インスタンスが描画する)
            parentNode.mesh = -1;

            for (int k = 0; k < instanceCount; k++) {
                final GltfModel.Node instNode = new GltfModel.Node();
                instNode.parent = i;
                instNode.mesh = origMesh;
                instNode.skin = origSkin;
                instNode.weights = origWeights;
                if (translations != null) {
                    instNode.translation = new float[]{translations[k * 3], translations[k * 3 + 1], translations[k * 3 + 2]};
                }
                if (rotations != null) {
                    instNode.rotation = new float[]{rotations[k * 4], rotations[k * 4 + 1], rotations[k * 4 + 2], rotations[k * 4 + 3]};
                    Mat4.normalize4(instNode.rotation);
                }
                if (scales != null) {
                    instNode.scale = new float[]{scales[k * 3], scales[k * 3 + 1], scales[k * 3 + 2]};
                }
                final int instIndex = nodeList.size();
                nodeList.add(instNode);
                childrenList.add(new ArrayList<Integer>());
                childrenList.get(i).add(instIndex);
            }
        }

        final GltfModel.Node[] nodes = nodeList.toArray(new GltfModel.Node[0]);
        for (int i = 0; i < nodes.length; i++) {
            final List<Integer> ch = childrenList.get(i);
            nodes[i].children = new int[ch.size()];
            for (int k = 0; k < ch.size(); k++) nodes[i].children[k] = ch.get(k);
        }

        // 表示するシーン
        final List<Object> scenes = Json.array(root, "scenes");
        final List<Integer> roots = new ArrayList<Integer>();
        if (!scenes.isEmpty()) {
            int scene = Json.integer(root, "scene", 0);
            if (scene < 0 || scene >= scenes.size()) scene = 0;
            for (Object item : Json.array(Json.object(scenes.get(scene)), "nodes")) {
                if (!(item instanceof Double)) continue;
                final int node = (int) Math.round((Double) item);
                if (node >= 0 && node < nodes.length && nodes[node].parent < 0 && !roots.contains(node)) roots.add(node);
            }
        } else {
            for (int i = 0; i < nodes.length; i++) {
                if (nodes[i].parent < 0) roots.add(i);
            }
        }

        // 親が先になる順に並べる。シーンに含まれないノードと、循環している部分は除かれる
        final int[] order = new int[nodes.length];
        int length = 0;
        for (int node : roots) order[length++] = node;
        for (int i = 0; i < length; i++) {
            for (int child : nodes[order[i]].children) order[length++] = child;
        }
        final boolean[] reachable = new boolean[nodes.length];
        for (int i = 0; i < length; i++) reachable[order[i]] = true;
        for (int i = 0; i < nodes.length; i++) {
            // シーンに含まれないノードのメッシュは描かない
            if (!reachable[i]) nodes[i].mesh = -1;
        }
        model.nodes = nodes;
        model.order = new int[length];
        System.arraycopy(order, 0, model.order, 0, length);
    }

    private void readSkins(GltfModel model) throws IOException {
        final List<Object> list = Json.array(root, "skins");
        model.skins = new GltfModel.Skin[list.size()];
        for (int i = 0; i < model.skins.length; i++) {
            final Map<String, Object> json = Json.object(list.get(i));
            final GltfModel.Skin skin = new GltfModel.Skin();
            final float[] joints = Json.numbers(json.get("joints"));
            skin.joints = new int[joints.length];
            for (int k = 0; k < joints.length; k++) {
                final int joint = (int) joints[k];
                skin.joints[k] = joint >= 0 && joint < model.nodes.length ? joint : -1;
            }
            if (json.containsKey("inverseBindMatrices")) {
                final float[] matrices = floats(Json.integer(json, "inverseBindMatrices", -1));
                if (matrices.length >= skin.joints.length * 16) skin.inverseBind = matrices;
            }
            if (skin.inverseBind == null) {
                skin.inverseBind = new float[skin.joints.length * 16];
                for (int k = 0; k < skin.joints.length; k++) {
                    System.arraycopy(Mat4.identity(), 0, skin.inverseBind, k * 16, 16);
                }
            }
            model.skins[i] = skin;
        }
        for (GltfModel.Node node : model.nodes) {
            if (node.skin >= model.skins.length) node.skin = -1;
        }
    }

    private void readAnimations(GltfModel model) throws IOException {
        final List<Object> list = Json.array(root, "animations");
        final List<GltfModel.Animation> animations = new ArrayList<GltfModel.Animation>();
        for (Object item : list) {
            final Map<String, Object> json = Json.object(item);
            final List<Object> samplers = Json.array(json, "samplers");
            final List<GltfModel.Channel> channels = new ArrayList<GltfModel.Channel>();
            float duration = 0;
            for (Object channelItem : Json.array(json, "channels")) {
                final Map<String, Object> channelJson = Json.object(channelItem);
                final Map<String, Object> target = Json.object(channelJson, "target");
                final int samplerIndex = Json.integer(channelJson, "sampler", -1);
                final int node = Json.integer(target, "node", -1);
                if (samplerIndex < 0 || samplerIndex >= samplers.size() || node < 0 || node >= model.nodes.length) continue;
                final Map<String, Object> sampler = Json.object(samplers.get(samplerIndex));

                final GltfModel.Channel channel = new GltfModel.Channel();
                channel.node = node;
                final String path = Json.string(target, "path", "");
                if ("translation".equals(path)) channel.path = GltfModel.PATH_TRANSLATION;
                else if ("rotation".equals(path)) channel.path = GltfModel.PATH_ROTATION;
                else if ("scale".equals(path)) channel.path = GltfModel.PATH_SCALE;
                else if ("weights".equals(path)) channel.path = GltfModel.PATH_WEIGHTS;
                else continue;

                final String interpolation = Json.string(sampler, "interpolation", "LINEAR");
                channel.interpolation = "STEP".equals(interpolation) ? GltfModel.INTERPOLATION_STEP
                        : "CUBICSPLINE".equals(interpolation) ? GltfModel.INTERPOLATION_CUBIC : GltfModel.INTERPOLATION_LINEAR;
                channel.times = floats(Json.integer(sampler, "input", -1));
                channel.values = floats(Json.integer(sampler, "output", -1));
                final int keys = channel.times.length;
                if (keys == 0) continue;
                final int perKey = channel.interpolation == GltfModel.INTERPOLATION_CUBIC ? 3 : 1;
                if (channel.values.length % (keys * perKey) != 0) continue;
                channel.components = channel.values.length / (keys * perKey);
                final int expected = channel.path == GltfModel.PATH_ROTATION ? 4 : 3;
                if (channel.path != GltfModel.PATH_WEIGHTS && channel.components != expected) continue;
                if (channel.components == 0) continue;

                duration = Math.max(duration, channel.times[keys - 1]);
                channels.add(channel);
            }
            if (channels.isEmpty()) continue;
            final GltfModel.Animation animation = new GltfModel.Animation();
            animation.channels = channels.toArray(new GltfModel.Channel[0]);
            animation.duration = duration;
            animations.add(animation);
        }
        model.animations = animations.toArray(new GltfModel.Animation[0]);
    }
}
