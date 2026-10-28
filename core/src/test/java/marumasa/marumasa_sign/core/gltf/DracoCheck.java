package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.gltf.draco.DataType;
import marumasa.marumasa_sign.core.gltf.draco.DracoDecoder;
import marumasa.marumasa_sign.core.gltf.draco.DracoMesh;
import marumasa.marumasa_sign.core.gltf.draco.IntList;
import marumasa.marumasa_sign.core.gltf.draco.PointAttribute;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Draco デコーダの単体テストおよび安全性検査。
 * 切り詰められたデータや破損したデータに対し、ハングせず確実に IOException を投げることを検証する。
 * 同梱のサンプル (src/test/resources/draco、出所とライセンスは SOURCES.txt) は、
 * Google の Draco デコーダ (draco3d 1.5.7) で展開した結果のハッシュと一致することを確かめる。
 */
public final class DracoCheck {

    private DracoCheck() {}

    // サンプルと、公式のデコーダで展開した結果のハッシュ (canonical() の SHA-256 の先頭 8 バイト)、点と面の数
    private static final String[][] SAMPLES = {
            {"avocado.drc", "83a9a5f2e7439f33", "406", "682"},
            {"box.metadata.drc", "679309a02250a071", "24", "12"},
            {"box.predictive_edgebreaker.drc", "679309a02250a071", "24", "12"},
            {"cube_att.obj.edgebreaker.cl10.2.2.drc", "e84cd80f9500a972", "24", "12"},
            {"cube_att.obj.edgebreaker.cl4.2.2.drc", "20956e2d3fdc56e5", "24", "12"},
            {"cube_att.obj.sequential.cl3.2.2.drc", "c0339158728a0b59", "24", "12"},
            {"cube_att.octahedron_transform.drc", "e84cd80f9500a972", "24", "12"},
            {"cube_att.sequential_compressed.drc", "c0339158728a0b59", "24", "12"},
            {"cube_att.tex_coords_deprecated.drc", "2c4cca6088af376b", "24", "12"},
            {"rigged_simple.eb_valence.drc", "f7f651388b0270e5", "160", "188"},
            {"rigged_simple.multi_parallelogram.drc", "6574251a9b3db097", "160", "188"},
            {"rigged_simple.sequential.drc", "d52e707cbd1df36d", "160", "188"},
            {"test_nm.1.1.0.multi_parallelogram.drc", "0058622bc7f012df", "99", "170"},
            {"test_nm.obj.edgebreaker.0.9.1.drc", "3389c05a509803c2", "99", "170"},
            {"test_nm.obj.edgebreaker.1.0.0.drc", "f452f110e62e6829", "99", "170"},
            {"test_nm.obj.edgebreaker.cl10.2.2.drc", "691c743dcf7f066a", "99", "170"},
            {"test_nm.obj.sequential.1.1.0.drc", "10e9fef4551015b4", "97", "170"},
            {"test_nm_quant.0.9.0.drc", "e40af2799f54e184", "99", "170"},
            {"test_nm_quant.octahedron_transform.drc", "0c4536fc395fb4ca", "99", "170"},
    };

    // ファジングの回数と、1 回の展開にかけてよい時間
    private static final int FUZZ_ITERATIONS = 3000;
    private static final long FUZZ_TIME_LIMIT_MS = 5000;

    public static void main(String[] args) throws Exception {
        System.out.println("Running DracoCheck...");

        testNullAndEmpty();
        testInvalidHeader();
        testTruncatedHeader();
        testCorruptedAndTruncatedBuffers();
        testOptionalFallback();
        testReferenceSamples();
        testHugeDeclarations();
        testFuzz();

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
        byte[] validDraco;
        if (binFile != null) {
            byte[] all = Files.readAllBytes(binFile.toPath());
            // Avocado.bin の先頭 8722 バイトが Draco ストリーム
            int dracoLen = Math.min(8722, all.length);
            validDraco = new byte[dracoLen];
            System.arraycopy(all, 0, validDraco, 0, dracoLen);
        } else {
            // 同梱のサンプルは Avocado.bin から取り出した同じストリーム
            validDraco = readSample("avocado.drc");
        }

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

    // ---------- 公式のデコーダとの一致 ----------

    private static byte[] readSample(String name) throws IOException {
        try (InputStream in = DracoCheck.class.getClassLoader().getResourceAsStream("draco/" + name)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }
        // クラスパスにないとき (javac と java で直接動かしたとき) はソースの場所を探す
        for (String dir : new String[] {"src/test/resources/draco", "core/src/test/resources/draco"}) {
            File f = new File(dir, name);
            if (f.exists()) return Files.readAllBytes(f.toPath());
        }
        throw new IOException("Sample not found: " + name);
    }

    /**
     * 展開した結果を、点の順に並べ直したバイト列にする。
     * 面の頂点、属性の種類と型、各点の値 (型どおりのバイト) を並べる。公式のデコーダの出力も同じ形にして比べた。
     */
    static byte[] canonical(DracoMesh mesh) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(mesh.getNumPoints());
        out.writeInt(mesh.getNumFaces());
        IntList indices = mesh.getIndices();
        out.writeInt(indices.getCount());
        for (int i = 0; i < indices.getCount(); i++) out.writeInt(indices.get(i));
        out.writeInt(mesh.getNumAttributes());
        for (int a = 0; a < mesh.getNumAttributes(); a++) {
            PointAttribute pa = mesh.attribute(a);
            out.writeInt(pa.getUniqueId());
            out.writeInt(pa.getAttributeType());
            out.writeInt(pa.getDataType());
            out.writeInt(pa.getComponentsCount());
            out.writeInt(pa.getNormalized() ? 1 : 0);
            byte[] data = pa.getBuffer().getBuffer();
            int size = DataType.length(pa.getDataType()) * pa.getComponentsCount();
            for (int p = 0; p < mesh.getNumPoints(); p++) {
                int pos = pa.getByteOffset() + pa.mappedIndex(p) * pa.getByteStride();
                out.write(data, pos, size);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
        return sb.toString();
    }

    private static void testReferenceSamples() throws Exception {
        for (String[] sample : SAMPLES) {
            DracoMesh mesh = DracoDecoder.decodeMesh(readSample(sample[0]));
            String h = hash(canonical(mesh));
            if (!h.equals(sample[1])
                    || mesh.getNumPoints() != Integer.parseInt(sample[2])
                    || mesh.getNumFaces() != Integer.parseInt(sample[3])) {
                throw new AssertionError("Decoded " + sample[0] + " differs from the reference decoder: " + h
                        + " points=" + mesh.getNumPoints() + " faces=" + mesh.getNumFaces());
            }
        }
        System.out.println("Reference sample test passed (" + SAMPLES.length + " files).");
    }

    // ---------- 大きな数を書いた小さなデータ ----------

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        while (true) {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    private static ByteArrayOutputStream header(int method) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('D');
        out.write('R');
        out.write('A');
        out.write('C');
        out.write('O');
        out.write(2);
        out.write(2);
        out.write(1);
        out.write(method);
        out.write(0);
        out.write(0);
        return out;
    }

    // 1 種類の記号だけの rANS (値 1 つあたり 0 ビット) で、count 個の値を書く
    private static void zeroBitSymbols(ByteArrayOutputStream out, int numSymbols, int symbol) {
        out.write(1); // 生の記号
        out.write(3); // 最大のビット数
        writeVarint(out, numSymbols);
        if (symbol > 0) out.write(((symbol - 1) << 2) | 3); // 確率 0 の記号が symbol 個
        out.write(((4096 & 63) << 2) | 1);
        out.write(4096 >> 6);
        writeVarint(out, 1);
        out.write(0);
    }

    /**
     * 数十バイトで巨大な点や面の数を書いたデータで、メモリを使い切らず短い時間で終わることを確かめる。
     */
    private static void testHugeDeclarations() throws Exception {
        // 順番に並べる方式で、面のない 300 万個の点
        ByteArrayOutputStream seq = header(0);
        writeVarint(seq, 0);
        writeVarint(seq, 3L << 20);
        seq.write(1);
        seq.write(1);
        writeVarint(seq, 1);
        seq.write(new byte[] {0, 9, 3, 0, 0, 2}, 0, 6);
        seq.write(0xfe);
        seq.write(1);
        zeroBitSymbols(seq, 1, 0);
        expectQuickFailure("huge point count", seq.toByteArray());

        // EdgeBreaker の価数の方式で、すべての記号が E (新しい三角形) の 100 万面
        int faces = 1 << 20;
        ByteArrayOutputStream eb = header(1);
        eb.write(2);
        writeVarint(eb, 3L * faces);
        writeVarint(eb, faces);
        eb.write(0);
        writeVarint(eb, faces);
        writeVarint(eb, 0);
        writeVarint(eb, 0);
        eb.write(new byte[] {0, 1, 0}, 0, 3); // 最初の面はいつも境界から
        writeVarint(eb, faces - 1);
        zeroBitSymbols(eb, 5, 4);
        for (int i = 0; i < 5; i++) writeVarint(eb, 0);
        eb.write(1);
        eb.write(new byte[] {(byte) 0xff, 0, 0}, 0, 3);
        writeVarint(eb, 1);
        eb.write(new byte[] {0, 5, 3, 0, 0, 1}, 0, 6);
        eb.write(0xfe);
        eb.write(1);
        zeroBitSymbols(eb, 1, 0);
        expectQuickFailure("huge face count", eb.toByteArray());

        // 面の数が上限を超える
        ByteArrayOutputStream tooMany = header(1);
        tooMany.write(0);
        writeVarint(tooMany, 0xffffffffL);
        writeVarint(tooMany, 0x55555555L);
        expectQuickFailure("face count overflow", tooMany.toByteArray());
        System.out.println("Huge declaration test passed.");
    }

    private static void expectQuickFailure(String name, byte[] data) throws Exception {
        long start = System.nanoTime();
        try {
            DracoDecoder.decodeMesh(data);
            throw new AssertionError(name + ": decoding should fail");
        } catch (IOException e) {
            // 期待通り
        }
        long ms = (System.nanoTime() - start) / 1000000;
        if (ms > FUZZ_TIME_LIMIT_MS) throw new AssertionError(name + ": took " + ms + " ms");
    }

    // ---------- ファジング ----------

    // データを少し壊す (ビットの反転、値の書き換え、削除、挿入、切り詰め)
    private static byte[] mutate(byte[] src, Random random) {
        byte[] b = src.clone();
        double kind = random.nextDouble();
        if (kind < 0.55) {
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                // 見出しの近くを壊すほうが、変わった道を通りやすい
                int limit = random.nextBoolean() ? Math.min(b.length, 160) : b.length;
                int pos = 11 + random.nextInt(Math.max(1, limit - 11));
                if (pos >= b.length) continue;
                switch (random.nextInt(4)) {
                    case 0:
                        b[pos] ^= (byte) (1 << random.nextInt(8));
                        break;
                    case 1:
                        b[pos] = (byte) random.nextInt(256);
                        break;
                    case 2:
                        b[pos] = (byte) 0xff;
                        break;
                    default:
                        b[pos] = 0;
                        break;
                }
            }
            return b;
        }
        int pos = 11 + random.nextInt(b.length - 11);
        if (kind < 0.7) {
            int len = Math.min(1 + random.nextInt(8), b.length - pos);
            byte[] c = new byte[b.length - len];
            System.arraycopy(b, 0, c, 0, pos);
            System.arraycopy(b, pos + len, c, pos, b.length - pos - len);
            return c;
        }
        if (kind < 0.85) {
            int len = 1 + random.nextInt(8);
            byte[] c = new byte[b.length + len];
            System.arraycopy(b, 0, c, 0, pos);
            for (int i = 0; i < len; i++) c[pos + i] = (byte) (random.nextInt(3) == 0 ? 0xff : random.nextInt(256));
            System.arraycopy(b, pos, c, pos + len, b.length - pos);
            return c;
        }
        return Arrays.copyOf(b, pos);
    }

    /**
     * 同梱のサンプルを壊したデータを展開し、IOException 以外の例外が出ないこと、時間内に終わることを確かめる。
     * 展開できたときは、すべての点の値が入れ物の中を指していることも確かめる。
     */
    private static void testFuzz() throws Exception {
        final byte[][] seeds = new byte[SAMPLES.length][];
        for (int i = 0; i < SAMPLES.length; i++) seeds[i] = readSample(SAMPLES[i][0]);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "draco-fuzz");
            t.setDaemon(true);
            return t;
        });
        Random random = new Random(20261028L);
        int decoded = 0;
        long slowest = 0;
        try {
            for (int it = 0; it < FUZZ_ITERATIONS; it++) {
                final byte[] data = mutate(seeds[random.nextInt(seeds.length)], random);
                long start = System.nanoTime();
                Future<Boolean> result = executor.submit(() -> {
                    DracoMesh mesh;
                    try {
                        mesh = DracoDecoder.decodeMesh(data);
                    } catch (IOException e) {
                        return false;
                    }
                    checkMappedValues(mesh);
                    return true;
                });
                try {
                    if (result.get(FUZZ_TIME_LIMIT_MS, TimeUnit.MILLISECONDS)) decoded++;
                } catch (TimeoutException e) {
                    throw new AssertionError("Fuzz iteration " + it + " did not finish in time");
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("Fuzz iteration " + it + " threw " + e.getCause(), e.getCause());
                }
                slowest = Math.max(slowest, System.nanoTime() - start);
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.println("Fuzz test passed (" + FUZZ_ITERATIONS + " iterations, " + decoded
                + " still decodable, slowest " + slowest / 1000000 + " ms).");
    }

    private static void checkMappedValues(DracoMesh mesh) {
        IntList indices = mesh.getIndices();
        for (int i = 0; i < indices.getCount(); i++) {
            int v = indices.get(i);
            if (v < 0 || v >= mesh.getNumPoints()) throw new AssertionError("Face index out of range: " + v);
        }
        for (int a = 0; a < mesh.getNumAttributes(); a++) {
            PointAttribute pa = mesh.attribute(a);
            int length = pa.getBuffer().getBuffer().length;
            for (int p = 0; p < mesh.getNumPoints(); p++) {
                int value = pa.mappedIndex(p);
                if (value < -1 || (value >= 0 && (long) value * pa.getByteStride() + pa.getByteStride() > length)) {
                    throw new AssertionError("Attribute value index out of range: " + value);
                }
            }
        }
    }
}
