package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部ファイルを参照している .gltf を、1 つの .glb にまとめる。
 * アップロードできるのはファイル 1 つだけなので、バッファと画像を中に取り込む。
 */
public final class GltfPacker {

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private GltfPacker() {
    }

    /**
     * @param json      .gltf の内容
     * @param resources 参照されているファイルの読み込み
     * @return .glb の内容
     */
    public static byte[] pack(byte[] json, GltfLoader.Resources resources) throws IOException {
        final Map<String, Object> root;
        try {
            root = new LinkedHashMap<String, Object>(Json.object(Json.parse(new String(json, StandardCharsets.UTF_8))));
        } catch (RuntimeException e) {
            throw new IOException("Invalid glTF: " + e, e);
        }
        if (!root.containsKey("asset")) throw new IOException("Not a glTF file");

        final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        // バッファを 1 つにつなげる
        final List<Object> buffers = Json.array(root, "buffers");
        final int[] offsets = new int[buffers.size()];
        for (int i = 0; i < offsets.length; i++) {
            final String uri = Json.string(Json.object(buffers.get(i)), "uri", null);
            offsets[i] = append(binary, uri == null ? new byte[0] : GltfLoader.resource(uri, resources));
        }

        final List<Object> views = new ArrayList<Object>();
        for (Object item : Json.array(root, "bufferViews")) {
            final Map<String, Object> view = new LinkedHashMap<String, Object>(Json.object(item));
            final int buffer = Json.integer(view, "buffer", 0);
            if (buffer < 0 || buffer >= offsets.length) throw new IOException("Invalid buffer view");
            view.put("buffer", 0.0);
            view.put("byteOffset", (double) (Json.integer(view, "byteOffset", 0) + offsets[buffer]));
            views.add(view);
        }

        // 画像を取り込む
        final List<Object> images = new ArrayList<Object>();
        for (Object item : Json.array(root, "images")) {
            final Map<String, Object> image = new LinkedHashMap<String, Object>(Json.object(item));
            final String uri = Json.string(image, "uri", null);
            if (uri != null) {
                final byte[] data = GltfLoader.resource(uri, resources);
                final Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("buffer", 0.0);
                view.put("byteOffset", (double) append(binary, data));
                view.put("byteLength", (double) data.length);
                image.remove("uri");
                image.put("bufferView", (double) views.size());
                if (!image.containsKey("mimeType")) image.put("mimeType", mimeType(data));
                views.add(view);
            }
            images.add(image);
        }

        final Map<String, Object> buffer = new LinkedHashMap<String, Object>();
        buffer.put("byteLength", (double) binary.size());
        final List<Object> packedBuffers = new ArrayList<Object>();
        packedBuffers.add(buffer);
        if (binary.size() > 0) root.put("buffers", packedBuffers);
        else root.remove("buffers");
        if (!views.isEmpty()) root.put("bufferViews", views);
        if (!images.isEmpty()) root.put("images", images);

        final byte[] text = pad(Json.write(root).getBytes(StandardCharsets.UTF_8), (byte) ' ');
        final byte[] data = pad(binary.toByteArray(), (byte) 0);
        final int length = 12 + 8 + text.length + (data.length > 0 ? 8 + data.length : 0);
        final ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC).putInt(2).putInt(length);
        out.putInt(text.length).putInt(CHUNK_JSON).put(text);
        if (data.length > 0) out.putInt(data.length).putInt(CHUNK_BIN).put(data);
        return out.array();
    }

    // 4 バイト境界に揃えてから追加し、追加した位置を返す
    private static int append(ByteArrayOutputStream binary, byte[] data) {
        while ((binary.size() & 3) != 0) binary.write(0);
        final int offset = binary.size();
        binary.write(data, 0, data.length);
        return offset;
    }

    private static byte[] pad(byte[] data, byte filler) {
        final int length = (data.length + 3) & ~3;
        if (length == data.length) return data;
        final byte[] result = new byte[length];
        System.arraycopy(data, 0, result, 0, data.length);
        for (int i = data.length; i < length; i++) result[i] = filler;
        return result;
    }

    private static String mimeType(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[8] == 'W' && data[9] == 'E') return "image/webp";
        if (data.length >= 12 && (data[0] & 0xFF) == 0xAB && data[1] == 'K' && data[2] == 'T' && data[3] == 'X') return "image/ktx2";
        return "image/png";
    }
}
