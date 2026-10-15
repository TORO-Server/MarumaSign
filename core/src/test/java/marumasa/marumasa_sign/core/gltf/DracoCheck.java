package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.gltf.draco.DracoDecoder;
import marumasa.marumasa_sign.core.gltf.draco.DracoMesh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

/**
 * Draco デコーダの単体テストおよび安全性検査。
 * 切り詰められたデータや破損したデータに対し、ハングせず確実に IOException を投げることを検証する。
 */
public final class DracoCheck {

    private DracoCheck() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Running DracoCheck...");

        testNullAndEmpty();
        testInvalidHeader();
        testTruncatedHeader();
        testCorruptedAndTruncatedBuffers();
        testOptionalFallback();

        System.out.println("All DracoCheck tests PASSED.");
    }

    private static void testNullAndEmpty() {
        try {
            DracoDecoder.decodeMesh(null);
            throw new AssertionError("null data should fail");
        } catch (IOException e) {
            // 期待通り
        }

        try {
            DracoDecoder.decodeMesh(new byte[0]);
            throw new AssertionError("empty data should fail");
        } catch (IOException e) {
            // 期待通り
        }
    }

    private static void testInvalidHeader() {
        byte[][] invalidHeaders = new byte[][] {
                new byte[] { 'B', 'A', 'D', '!', '!' },
                new byte[] { 'D', 'R', 'A', 'K', 'O' },
                new byte[] { 0, 0, 0, 0, 0, 1, 2, 3 }
        };

        for (byte[] data : invalidHeaders) {
            try {
                DracoDecoder.decodeMesh(data);
                throw new AssertionError("Invalid header should fail");
            } catch (IOException e) {
                // 期待通り
            }
        }
    }

    private static void testTruncatedHeader() {
        for (int len = 1; len < 5; len++) {
            byte[] data = new byte[len];
            System.arraycopy(new byte[] { 'D', 'R', 'A', 'C', 'O' }, 0, data, 0, len);
            try {
                DracoDecoder.decodeMesh(data);
                throw new AssertionError("Truncated header (len=" + len + ") should fail");
            } catch (IOException e) {
                // 期待通り
            }
        }
    }

    private static File findAvocadoBin() {
        File[] candidates = new File[] {
                new File("build/gltf-samples/Avocado/glTF-Draco/Avocado.bin"),
                new File("../build/gltf-samples/Avocado/glTF-Draco/Avocado.bin")
        };
        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    private static void testCorruptedAndTruncatedBuffers() throws IOException {
        File binFile = findAvocadoBin();
        if (binFile == null) {
            System.out.println("Avocado.bin not found for deep truncation check, skipping deep check.");
            return;
        }

        byte[] all = Files.readAllBytes(binFile.toPath());
        // Avocado.bin の先頭 8722 バイトが Draco ストリーム
        int dracoLen = Math.min(8722, all.length);
        byte[] validDraco = new byte[dracoLen];
        System.arraycopy(all, 0, validDraco, 0, dracoLen);

        // 正常データのデコード検証
        DracoMesh mesh = DracoDecoder.decodeMesh(validDraco);
        if (mesh == null || mesh.getNumFaces() == 0 || mesh.getNumPoints() == 0) {
            throw new AssertionError("Valid Draco decode produced empty mesh");
        }
        System.out.println("Valid Avocado Draco mesh decoded: " + mesh.getNumFaces() + " faces, " + mesh.getNumPoints() + " points");

        // 切り詰めテスト: 5バイトから全長まで83バイト刻みで切り詰めて検証
        for (int len = 5; len < validDraco.length; len += 83) {
            byte[] truncated = new byte[len];
            System.arraycopy(validDraco, 0, truncated, 0, len);
            try {
                DracoDecoder.decodeMesh(truncated);
                throw new AssertionError("Truncated buffer (len=" + len + ") must fail with IOException");
            } catch (IOException e) {
                // 期待通り: ハングせず IOException を投げる
            }
        }

        // 破損テスト: 正常なデータをランダムに改変して検証
        Random random = new Random(42);
        for (int trial = 0; trial < 100; trial++) {
            byte[] corrupted = validDraco.clone();
            // 1〜10箇所のバイトをランダムに破損
            int corruptions = 1 + random.nextInt(10);
            for (int c = 0; c < corruptions; c++) {
                int pos = 5 + random.nextInt(corrupted.length - 5);
                corrupted[pos] = (byte) random.nextInt(256);
            }
            try {
                DracoDecoder.decodeMesh(corrupted);
                // まれに破損後も整合性が保たれてデコードが通る可能性はあるが、クラッシュやハングはしない
            } catch (IOException e) {
                // 期待通り
            }
        }
        System.out.println("Truncation and corruption tests passed.");
    }

    private static void testOptionalFallback() throws IOException {
        // KHR_draco_mesh_compression が optional な場合に、壊れた Draco データがあれば
        // 未圧縮の頂点データに安全にフォールバックすることの検証
        // 小さな三角形（3頂点）の glTF を作成
        // POSITION: 3 vertices * 12 bytes = 36 bytes
        float[] positions = new float[] {
                0.0f, 0.0f, 0.0f,
                1.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f
        };
        byte[] posBytes = new byte[36];
        java.nio.ByteBuffer.wrap(posBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(positions);

        // 壊れた Draco バッファ
        byte[] badDraco = new byte[] { 'D', 'R', 'A', 'C', 'O', 2, 2, 0, 0, 0, 0, 0, 0 };

        // 結合バッファ: [posBytes (36)] + [badDraco (13)]
        byte[] buffer = new byte[posBytes.length + badDraco.length];
        System.arraycopy(posBytes, 0, buffer, 0, posBytes.length);
        System.arraycopy(badDraco, 0, buffer, posBytes.length, badDraco.length);

        String b64 = java.util.Base64.getEncoder().encodeToString(buffer);

        // JSON: extensionsRequired には入れず、primitive.extensions に KHR_draco_mesh_compression を含める
        String gltfJson = "{\n" +
                "  \"asset\": {\"version\": \"2.0\"},\n" +
                "  \"extensionsUsed\": [\"KHR_draco_mesh_compression\"],\n" +
                "  \"buffers\": [{\"byteLength\": " + buffer.length + ", \"uri\": \"data:application/octet-stream;base64," + b64 + "\"}],\n" +
                "  \"bufferViews\": [\n" +
                "    {\"buffer\": 0, \"byteOffset\": 0, \"byteLength\": 36},\n" +
                "    {\"buffer\": 0, \"byteOffset\": 36, \"byteLength\": " + badDraco.length + "}\n" +
                "  ],\n" +
                "  \"accessors\": [\n" +
                "    {\"bufferView\": 0, \"byteOffset\": 0, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\"}\n" +
                "  ],\n" +
                "  \"meshes\": [{\n" +
                "    \"primitives\": [{\n" +
                "      \"attributes\": {\"POSITION\": 0},\n" +
                "      \"extensions\": {\n" +
                "        \"KHR_draco_mesh_compression\": {\n" +
                "          \"bufferView\": 1,\n" +
                "          \"attributes\": {\"POSITION\": 0}\n" +
                "        }\n" +
                "      }\n" +
                "    }]\n" +
                "  }],\n" +
                "  \"nodes\": [{\"mesh\": 0}],\n" +
                "  \"scenes\": [{\"nodes\": [0]}],\n" +
                "  \"scene\": 0\n" +
                "}";

        GltfModel model = GltfLoader.load(gltfJson.getBytes("UTF-8"), null);
        if (model == null || model.meshes.length == 0 || model.meshes[0].primitives.length == 0) {
            throw new AssertionError("Fallback failed to load model");
        }
        GltfModel.Primitive prim = model.meshes[0].primitives[0];
        if (prim.positions.length != 9) {
            throw new AssertionError("Expected 9 position floats, got: " + prim.positions.length);
        }
        System.out.println("Optional fallback test passed.");
    }
}
