package marumasa.marumasa_sign.core.splat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ガウシアンスプラッティングの 5 形式 (.ply .spz .sog .splat .ksplat) のデコーダの単体テスト・
 * 安全性検査・および実データと基準実装 (splat-transform 等) との数値比較を実行する。
 */
public final class SplatCheck {

    private SplatCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running SplatCheck...");

        testIsSplat();
        testFlipYZSignTable();
        testMaxSplatsSelection();
        testMalformedAndMaliciousInputs();

        final File dataDir = findDataDir(args);
        if (dataDir != null && dataDir.isDirectory()) {
            System.out.println("Using splat-data directory: " + dataDir.getAbsolutePath());
            testSogV1WithRealWebp(dataDir);
            testRealSamplesAndTruncation(dataDir);
            testNumericalComparisons(dataDir);
            testLargePlyMemory(dataDir);
        } else {
            throw new AssertionError("splat-data directory not found");
        }

        System.out.println("All SplatCheck tests PASSED.");
    }

    private static File findDataDir(String[] args) {
        if (args.length > 0 && !args[0].isEmpty()) {
            final File f = new File(args[0]);
            if (f.exists()) return f;
        }
        final File[] candidates = new File[]{
                new File("D:/Dev/MarumaSign-wt/splat-data"),
                new File("../splat-data")
        };
        for (File c : candidates) {
            if (c.isDirectory()) return c;
        }
        return null;
    }

    // 1. isSplat の判定テスト (内容判定・拡張子判定・URL クエリ・非 3DGS ファイルの除外)
    private static void testIsSplat() throws IOException {
        System.out.println("[1/6] Testing SplatLoader.isSplat...");

        if (SplatLoader.isSplat(null, 100, "test.ply")) throw new AssertionError("null head should be false");
        if (SplatLoader.isSplat(new byte[64], 0, "test.ply")) throw new AssertionError("length 0 should be false");
        if (SplatLoader.isSplat(new byte[64], -1, "test.ply")) throw new AssertionError("negative length should be false");

        // 標準 3DGS PLY (LF および CRLF)
        final byte[] validPly = ("ply\nformat binary_little_endian 1.0\nelement vertex 1\n"
                + "property float x\nproperty float f_dc_0\nend_header\n").getBytes(StandardCharsets.US_ASCII);
        if (!SplatLoader.isSplat(validPly, validPly.length, null)) {
            throw new AssertionError("Valid 3DGS PLY with null name should be true");
        }
        final byte[] validPlyCrlf = ("ply\r\nformat ascii 1.0\r\nelement vertex 1\r\n"
                + "property float packed_position\r\nend_header\r\n").getBytes(StandardCharsets.US_ASCII);
        if (!SplatLoader.isSplat(validPlyCrlf, validPlyCrlf.length, "https://example.com/scene.ply?ver=2#top")) {
            throw new AssertionError("Valid compressed PLY with CRLF should be true");
        }

        // 通常の 3D スキャン PLY (頂点色・法線のみ) は false でなければならない
        final byte[] meshPly = ("ply\nformat ascii 1.0\nelement vertex 3\n"
                + "property float x\nproperty float y\nproperty float z\n"
                + "property uchar red\nproperty uchar green\nproperty uchar blue\n"
                + "end_header\n0 0 0 255 0 0\n").getBytes(StandardCharsets.US_ASCII);
        if (SplatLoader.isSplat(meshPly, meshPly.length, "scan.ply")) {
            throw new AssertionError("Non-3DGS mesh PLY must be false");
        }

        // SPZ: v2/v3 (gzip) および v4 (平文 NGSP ヘッダ)
        final byte[] validSpz = createMinimalSpz(2, 1, 0);
        if (!SplatLoader.isSplat(validSpz, validSpz.length, null)) {
            throw new AssertionError("Valid SPZ v2 with null name should be true");
        }
        final byte[] validSpzV4 = createMinimalSpzV4(1, 0, 0, 0, false);
        if (!SplatLoader.isSplat(validSpzV4, validSpzV4.length, null)) {
            throw new AssertionError("Valid SPZ v4 with null name should be true");
        }
        if (!SplatLoader.isSplat(validSpzV4, 32, "scene.spz?v=4")) {
            throw new AssertionError("Valid SPZ v4 head (32 bytes) should be true");
        }
        if (SplatLoader.isSplat(validSpzV4, 31, "scene.spz")) {
            throw new AssertionError("SPZ v4 with <32 bytes must be false");
        }
        final byte[] badVerSpzV4 = validSpzV4.clone();
        ByteBuffer.wrap(badVerSpzV4).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 5);
        if (SplatLoader.isSplat(badVerSpzV4, badVerSpzV4.length, "bad_ver.spz")) {
            throw new AssertionError("SPZ v5 must be false");
        }

        final ByteArrayOutputStream plainGzipOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(plainGzipOut)) {
            gz.write("not an spz file at all".getBytes(StandardCharsets.US_ASCII));
        }
        final byte[] plainGzip = plainGzipOut.toByteArray();
        if (SplatLoader.isSplat(plainGzip, plainGzip.length, "fake.spz")) {
            throw new AssertionError("Non-NGSP gzip must be false even with .spz extension");
        }

        // SOG: ZIP ローカルヘッダとエントリ名 / 拡張子
        final byte[] sogZip = createZipWithEntry("meta.json", "{}".getBytes(StandardCharsets.UTF_8));
        if (!SplatLoader.isSplat(sogZip, sogZip.length, null)) {
            throw new AssertionError("ZIP starting with meta.json should be true even with null name");
        }
        final byte[] webpZip = createZipWithEntry("means_l.webp", new byte[]{1, 2, 3});
        if (!SplatLoader.isSplat(webpZip, webpZip.length, "model.zip")) {
            throw new AssertionError("ZIP starting with *.webp should be true");
        }
        final byte[] otherZip = createZipWithEntry("readme.txt", new byte[]{1, 2, 3});
        if (SplatLoader.isSplat(otherZip, otherZip.length, "archive.zip")) {
            throw new AssertionError("Ordinary ZIP must be false");
        }
        if (!SplatLoader.isSplat(otherZip, otherZip.length, "https://cdn.example.org/scene.sog?dl=1")) {
            throw new AssertionError("ZIP with .sog URL should be true");
        }

        // .splat: 拡張子と 32 バイト以上の有限 float
        final byte[] validSplat = createMinimalSplat(1);
        if (!SplatLoader.isSplat(validSplat, validSplat.length, "https://example.com/nike.splat?a=1#b")) {
            throw new AssertionError("Valid .splat URL should be true");
        }
        if (SplatLoader.isSplat(validSplat, 31, "nike.splat")) {
            throw new AssertionError("Truncated .splat (<32 bytes) must be false");
        }
        if (SplatLoader.isSplat(validSplat, validSplat.length, "nike.bin")) {
            throw new AssertionError(".splat without .splat extension must be false");
        }
        final byte[] nanSplat = validSplat.clone();
        ByteBuffer.wrap(nanSplat).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, Float.NaN);
        if (SplatLoader.isSplat(nanSplat, nanSplat.length, "nan.splat")) {
            throw new AssertionError(".splat with NaN in header must be false");
        }

        // .ksplat: 拡張子とヘッダ値の妥当性
        final byte[] validKsplat = createMinimalKsplatMode0(1);
        if (!SplatLoader.isSplat(validKsplat, 64, "https://example.com/scene.ksplat?x=1")) {
            throw new AssertionError("Valid .ksplat head (64 bytes) should be true");
        }
        if (SplatLoader.isSplat(validKsplat, 64, "scene.bin")) {
            throw new AssertionError(".ksplat with wrong extension must be false");
        }
        final byte[] badModeKsplat = validKsplat.clone();
        ByteBuffer.wrap(badModeKsplat).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 5);
        if (SplatLoader.isSplat(badModeKsplat, 64, "bad.ksplat")) {
            throw new AssertionError(".ksplat with compressionMode=5 must be false");
        }
    }

    // 2. flipYZ の球面調和関数 (1〜3 次) の符号表の数学的検算
    private static void testFlipYZSignTable() {
        System.out.println("[2/6] Verifying flipYZ spherical harmonics sign table...");
        // (x, y, z) -> (x, -y, -z) の変換で各実球面調和基底 Y_l^m(x, y, z) がどう変化するかをランダムな単位ベクトルで検算する
        final float[][] dirs = new float[][]{
                {0.3f, -0.4f, 0.8660254f},
                {-0.6f, 0.7f, -0.38729835f},
                {0.123f, 0.456f, 0.8814233f}
        };
        for (float[] d : dirs) {
            final float x = d[0], y = d[1], z = d[2];
            final float[] basisOrig = evalShBasis15(x, y, z);
            final float[] basisFlipped = evalShBasis15(x, -y, -z);

            final float[] sh = new float[15 * 3];
            for (int k = 0; k < 15; k++) {
                sh[k * 3] = 1.0f;
                sh[k * 3 + 1] = 2.0f;
                sh[k * 3 + 2] = -0.5f;
            }
            final float[] pos = new float[]{x, y, z};
            final float[] rot = new float[]{0f, 0f, 0f, 1f};
            SplatLoader.flipYZ(1, pos, rot, 3, sh);

            for (int k = 0; k < 15; k++) {
                final float expected = basisOrig[k];
                final float actual = sh[k * 3] * basisFlipped[k];
                if (Math.abs(expected - actual) > 1e-6f) {
                    throw new AssertionError("SH sign mismatch at k=" + k + ": expected " + expected + ", got " + actual);
                }
            }
        }
    }

    // 標準的な 3DGS の実球面調和基底 (1〜3 次の 15 項、定数係数を除く多項式部分)
    private static float[] evalShBasis15(float x, float y, float z) {
        final float xx = x * x, yy = y * y, zz = z * z;
        return new float[]{
                // l = 1 (m = -1, 0, 1)
                -y,
                z,
                -x,
                // l = 2 (m = -2, -1, 0, 1, 2)
                x * y,
                -y * z,
                2.0f * zz - xx - yy,
                -x * z,
                xx - yy,
                // l = 3 (m = -3, -2, -1, 0, 1, 2, 3)
                -y * (3.0f * xx - yy),
                x * y * z,
                -y * (4.0f * zz - xx - yy),
                z * (2.0f * zz - 3.0f * xx - 3.0f * yy),
                -x * (4.0f * zz - xx - yy),
                z * (xx - yy),
                -x * (xx - 3.0f * yy)
        };
    }

    // 3. MAX_SPLATS を超える場合の selectTop / importance 抽出テスト
    private static void testMaxSplatsSelection() throws IOException {
        System.out.println("[3/6] Testing MAX_SPLATS selection...");
        final int total = SplatLoader.MAX_SPLATS + 2500;
        final byte[] splatBytes = new byte[total * 32];
        final ByteBuffer buf = ByteBuffer.wrap(splatBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < total; i++) {
            final int o = i * 32;
            buf.putFloat(o, i * 0.01f);
            buf.putFloat(o + 4, 1.0f);
            buf.putFloat(o + 8, 2.0f);
            // 特定の 10 個だけスケールを大きくして必ず選ばれるようにする
            final float scale = (i % 15000 == 7) ? 10.0f : 0.1f;
            buf.putFloat(o + 12, scale);
            buf.putFloat(o + 16, scale);
            buf.putFloat(o + 20, scale);
            splatBytes[o + 24] = (byte) 128;
            splatBytes[o + 25] = (byte) 128;
            splatBytes[o + 26] = (byte) 128;
            splatBytes[o + 27] = (byte) 255;
            splatBytes[o + 28] = (byte) 255;
            splatBytes[o + 29] = (byte) 128;
            splatBytes[o + 30] = (byte) 128;
            splatBytes[o + 31] = (byte) 128;
        }
        final SplatCloud cloud = SplatLoader.load(splatBytes, "large.splat");
        if (cloud.count != SplatLoader.MAX_SPLATS) {
            throw new AssertionError("Expected MAX_SPLATS=" + SplatLoader.MAX_SPLATS + ", got " + cloud.count);
        }
        int highScaleCount = 0;
        for (int i = 0; i < cloud.count; i++) {
            if (cloud.scales[i * 3] > 5.0f) highScaleCount++;
        }
        if (highScaleCount != 11) {
            throw new AssertionError("Expected all 11 high-importance splats to be kept, got " + highScaleCount);
        }
    }

    // 4. 破損・悪意のある入力に対する安全性検査 (すべての形式で IOException となり RuntimeException や OOM が起きないこと)
    private static void testMalformedAndMaliciousInputs() throws IOException {
        System.out.println("[4/6] Testing malformed and malicious inputs across all 5 formats...");

        expectIOException(null, "test.ply", "null content");
        expectIOException(new byte[0], "test.ply", "empty content");
        expectIOException(new byte[]{1, 2, 3, 4}, "unknown.xyz", "unknown extension");

        // PLY の異常系
        expectIOException("ply\nformat binary_little_endian 1.0\nelement vertex -5\nproperty float x\nproperty float f_dc_0\nend_header\n"
                .getBytes(StandardCharsets.US_ASCII), "neg.ply", "negative PLY vertex count");
        expectIOException("ply\nformat binary_little_endian 1.0\nelement vertex 999999999\nproperty float x\nproperty float f_dc_0\nend_header\n"
                .getBytes(StandardCharsets.US_ASCII), "huge.ply", "huge PLY vertex count");
        expectIOException("ply\nformat binary_little_endian 1.0\nelement vertex 10\nproperty float x\nproperty float y\nproperty float z\nproperty float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\nproperty float opacity\nproperty float scale_0\nproperty float scale_1\nproperty float scale_2\nproperty float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\nend_header\n1234"
                .getBytes(StandardCharsets.US_ASCII), "trunc.ply", "truncated binary PLY");
        expectIOException("ply\nformat ascii 1.0\nelement vertex 2\nproperty float x\nproperty float y\nproperty float z\nproperty float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\nproperty float opacity\nproperty float scale_0\nproperty float scale_1\nproperty float scale_2\nproperty float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\nend_header\n0 0 0 0 0 0 0 100 0 0 1 0 0 0\n0 0 0 0 0 0 0 0 0 0 1 0 0 0\n"
                .getBytes(StandardCharsets.US_ASCII), "overflow.ply", "scale exp overflow in ASCII PLY");
        expectIOException("ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nproperty float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\nproperty float opacity\nproperty float scale_0\nproperty float scale_1\nproperty float scale_2\nproperty float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\nend_header\nNaN 0 0 0 0 0 0 0 0 0 1 0 0 0\n"
                .getBytes(StandardCharsets.US_ASCII), "nan.ply", "NaN in ASCII PLY");

        // SPZ の異常系 (切り詰め・不正バージョン・負の個数・gzip 爆弾)
        final byte[] validSpz = createMinimalSpz(3, 4, 1);
        for (int len = 1; len < validSpz.length; len += 3) {
            expectIOException(Arrays.copyOf(validSpz, len), "trunc.spz", "truncated SPZ len=" + len);
        }
        // SPZ gzip 爆弾 (小さい gzip なのに 200 万点を主張するヘッダ)
        final ByteArrayOutputStream bombSpzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bombSpzOut)) {
            final byte[] hdr = new byte[16];
            final ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(0, 0x5053474E);
            bb.putInt(4, 3);
            bb.putInt(8, 2_000_000);
            hdr[12] = 3;
            hdr[13] = 12;
            gz.write(hdr);
            gz.write(new byte[1024]);
        }
        expectIOException(bombSpzOut.toByteArray(), "bomb.spz", "SPZ gzip bomb");

        // SPZ v4 の異常系 (切り詰め・不正ヘッダ・TOC 範囲外・サイズ不一致・ZSTD 破損・ZSTD 爆弾)
        final byte[] validSpzV4 = createMinimalSpzV4(4, 1, 0, 0, false);
        for (int len = 1; len < validSpzV4.length; len += 5) {
            expectIOException(Arrays.copyOf(validSpzV4, len), "trunc_v4.spz", "truncated SPZ v4 len=" + len);
        }

        // 不正バージョン (v5)
        final byte[] badVerSpzV4 = validSpzV4.clone();
        ByteBuffer.wrap(badVerSpzV4).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 5);
        expectIOException(badVerSpzV4, "bad_ver_v4.spz", "SPZ v4 unsupported version 5");

        // 不正な点数 (0 点)
        final byte[] zeroPointsSpzV4 = validSpzV4.clone();
        ByteBuffer.wrap(zeroPointsSpzV4).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0);
        expectIOException(zeroPointsSpzV4, "zero_pts_v4.spz", "SPZ v4 zero points");

        // 不正なストリーム数
        final byte[] badStreamsSpzV4 = validSpzV4.clone();
        badStreamsSpzV4[15] = (byte) 2;
        expectIOException(badStreamsSpzV4, "bad_streams_v4.spz", "SPZ v4 unexpected stream count");

        // TOC が範囲外 (巨大な tocByteOffset)
        final byte[] badTocOffsetSpzV4 = validSpzV4.clone();
        ByteBuffer.wrap(badTocOffsetSpzV4).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 999999);
        expectIOException(badTocOffsetSpzV4, "bad_toc_v4.spz", "SPZ v4 TOC out of range");

        // TOC の uncompressedSize 不一致
        final byte[] badUSizeSpzV4 = validSpzV4.clone();
        final int tocOff = ByteBuffer.wrap(validSpzV4).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
        ByteBuffer.wrap(badUSizeSpzV4).order(ByteOrder.LITTLE_ENDIAN).putLong(tocOff + 8, 99999);
        expectIOException(badUSizeSpzV4, "bad_usize_v4.spz", "SPZ v4 uncompressed size mismatch");

        // ZSTD ストリーム破損
        final byte[] corruptZstdSpzV4 = validSpzV4.clone();
        final int dataStart = tocOff + (validSpzV4[15] & 0xFF) * 16;
        corruptZstdSpzV4[dataStart + 2] = (byte) 0xFF; // ZSTD magic 破壊
        expectIOException(corruptZstdSpzV4, "corrupt_zstd_v4.spz", "SPZ v4 corrupt ZSTD magic");

        // ZSTD 爆弾 (小さいファイルなのに巨大な点数を詐称)
        final byte[] bombSpzV4 = validSpzV4.clone();
        ByteBuffer.wrap(bombSpzV4).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 20_000_000);
        expectIOException(bombSpzV4, "bomb_v4.spz", "SPZ v4 ZSTD bomb exceeding point limit");

        // SPZ v4 の拡張付きファイルが正常に読めることの検査 (未知の拡張をスキップ)
        final byte[] extSpzV4 = createMinimalSpzV4(4, 1, 0, 64, false);
        final SplatCloud extCloud = SplatLoader.load(extSpzV4, "ext.spz");
        if (extCloud.count != 4 || extCloud.shDegree != 1) {
            throw new AssertionError("SPZ v4 with extensions failed to load correctly: count=" + extCloud.count);
        }

        // SPZ v4 の SH 次数 4 が来たら 3 次までを採用し 4 次を捨てることの検査
        final byte[] deg4SpzV4 = createMinimalSpzV4(4, 4, 0, 0, false);
        final SplatCloud deg4Cloud = SplatLoader.load(deg4SpzV4, "deg4.spz");
        if (deg4Cloud.count != 4 || deg4Cloud.shDegree != 3) {
            throw new AssertionError("SPZ v4 SH degree 4 failed: expected count=4, shDegree=3, got " + deg4Cloud.shDegree);
        }

        // SOG の異常系 (meta.json 欠如・パストラバーサル・不正 JSON・WebP 破損)
        expectIOException(createZipWithEntry("other.txt", new byte[]{1, 2}), "no_meta.sog", "SOG without meta.json");
        expectIOException(createZipWithEntry("../meta.json", "{}".getBytes(StandardCharsets.UTF_8)), "traversal.sog", "SOG path traversal");
        expectIOException(createZipWithEntry("meta.json", "{invalid json".getBytes(StandardCharsets.UTF_8)), "bad_json.sog", "SOG invalid JSON");
        expectIOException(createZipWithEntry("meta.json", "{\"version\":2,\"count\":-10}".getBytes(StandardCharsets.UTF_8)), "neg_count.sog", "SOG negative count");

        // .splat の異常系 (32 の倍数でないサイズ・NaN・Infinity)
        expectIOException(new byte[33], "bad_size.splat", "non-multiple of 32 .splat");
        final byte[] nanSplat = createMinimalSplat(2);
        ByteBuffer.wrap(nanSplat).order(ByteOrder.LITTLE_ENDIAN).putFloat(32 + 12, Float.POSITIVE_INFINITY);
        expectIOException(nanSplat, "inf.splat", ".splat with Infinity scale");

        // .ksplat の異常系 (切り詰め・NaN 半精度浮動小数点・不正バケット)
        final byte[] validKsplat = createMinimalKsplatMode0(2);
        expectIOException(Arrays.copyOf(validKsplat, 2048), "short.ksplat", "truncated .ksplat main header");
        expectIOException(Arrays.copyOf(validKsplat, 4500), "short_sec.ksplat", "truncated .ksplat section header");
        expectIOException(Arrays.copyOf(validKsplat, 5120 + 44), "short_data.ksplat", "truncated .ksplat splat data");
        final byte[] nanKsplat = validKsplat.clone();
        ByteBuffer.wrap(nanKsplat).order(ByteOrder.LITTLE_ENDIAN).putFloat(5120 + 12, Float.NaN);
        expectIOException(nanKsplat, "nan.ksplat", ".ksplat with NaN scale");
    }

    // SOG v1 形式のデコード検証 (lizard_10k.sog の WebP 画像を利用して v1 形式の meta.json で構成)
    private static void testSogV1WithRealWebp(File dataDir) throws IOException {
        final File sogV2File = new File(dataDir, "lizard_10k.sog");
        if (!sogV2File.exists()) return;
        final byte[] sogV2Bytes = Files.readAllBytes(sogV2File.toPath());
        final java.util.Map<String, byte[]> entries = SogReader.readZip(sogV2Bytes);
        final String v1Meta = "{"
                + "\"means\":{\"shape\":[10000,3],\"mins\":[-2.0,-2.0,-2.0],\"maxs\":[2.0,2.0,2.0],\"files\":[\"means_l.webp\",\"means_u.webp\"]},"
                + "\"scales\":{\"mins\":[-8.0,-8.0,-8.0],\"maxs\":[-1.0,-1.0,-1.0],\"files\":[\"scales.webp\"]},"
                + "\"quats\":{\"files\":[\"quats.webp\"]},"
                + "\"sh0\":{\"mins\":[-1.0,-1.0,-1.0,-4.0],\"maxs\":[1.0,1.0,1.0,4.0],\"files\":[\"sh0.webp\"]},"
                + "\"shN\":{\"mins\":-0.5,\"maxs\":0.5,\"files\":[\"shN_centroids.webp\",\"shN_labels.webp\"]}"
                + "}";
        final ByteArrayOutputStream v1ZipOut = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(v1ZipOut)) {
            zos.putNextEntry(new ZipEntry("meta.json"));
            zos.write(v1Meta.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (java.util.Map.Entry<String, byte[]> e : entries.entrySet()) {
                if ("meta.json".equals(e.getKey())) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        final byte[] v1Bytes = v1ZipOut.toByteArray();
        final SplatCloud v1Cloud = SplatLoader.load(v1Bytes, "synthetic_v1.sog");
        if (v1Cloud.count != 10000 || v1Cloud.shDegree != 3) {
            throw new AssertionError("SOG v1 decode failed: count=" + v1Cloud.count + ", shDegree=" + v1Cloud.shDegree);
        }
    }

    // 5. 実データの読み込み・切り詰め耐性検査
    private static void testRealSamplesAndTruncation(File dataDir) throws IOException {
        System.out.println("[5/6] Testing real datasets and deep truncation resilience...");
        final String[] samplePaths = new String[]{
                "repos/spz/samples/hornedlizard.spz",
                "repos/spz/samples/racoonfamily.spz",
                "hornedlizard_v4.spz",
                "nike.splat",
                "plush.splat",
                "train.splat",
                "lizard_10k.ply",
                "lizard_10k_be.ply",
                "lizard_1k_ascii.ply",
                "lizard_10k.compressed.ply",
                "lizard_10k.sog",
                "lizard_10k_v2.spz",
                "lizard_10k_v3.spz",
                "lizard_10k_v4.spz",
                "lizard_10k.splat",
                "lizard_10k_m0.ksplat",
                "lizard_10k_m1.ksplat",
                "lizard_10k_m2.ksplat"
        };

        for (String rel : samplePaths) {
            final File f = new File(dataDir, rel);
            if (!f.exists()) {
                throw new AssertionError("Missing expected test file: " + f.getAbsolutePath());
            }
            final byte[] bytes = Files.readAllBytes(f.toPath());
            if (!SplatLoader.isSplat(bytes, Math.min(bytes.length, 4096), f.getName())) {
                throw new AssertionError("isSplat returned false for " + f.getName());
            }
            final long t0 = System.nanoTime();
            final SplatCloud cloud = SplatLoader.load(bytes, f.getName());
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            validateCloudIntegrity(cloud, f.getName());
            System.out.println(String.format(Locale.ROOT,
                    "  OK %-34s -> count=%6d, shDegree=%d (%4d ms)",
                    rel, cloud.count, cloud.shDegree, ms));
        }

        // 各形式の実データを途中で切り詰めたときに必ず IOException になることを確かめる
        final String[] truncTargets = new String[]{
                "lizard_10k.ply",
                "lizard_10k.compressed.ply",
                "lizard_10k.sog",
                "lizard_10k_v3.spz",
                "lizard_10k_v4.spz",
                "lizard_10k_m1.ksplat"
        };
        for (String rel : truncTargets) {
            final File f = new File(dataDir, rel);
            final byte[] bytes = Files.readAllBytes(f.toPath());
            final int step = Math.max(1, bytes.length / 25);
            for (int len = 10; len < bytes.length - 16; len += step) {
                expectIOException(Arrays.copyOf(bytes, len), f.getName(), "truncated " + rel + " at " + len);
            }
        }
    }

    // 6. 同一シーン (lizard_10k) の全形式間の数値比較、および splat-transform の逆変換 PLY との数値比較
    private static void testNumericalComparisons(File dataDir) throws IOException {
        System.out.println("[6/6] Running numerical accuracy comparisons...");

        final SplatCloud refPly = loadFile(dataDir, "lizard_10k.ply");
        final SplatCloud refPly1k = loadFile(dataDir, "lizard_1k_ascii.ply");

        System.out.println("\n=== Table A: Comparison against original standard PLY (lizard_10k.ply, N=10,000) ===");
        System.out.println("| Format | File | N | SH | Pos Max | Pos Mean | Scale Max | Scale Mean | Opacity Max | Opacity Mean | Color Max | Color Mean | Rot Max | Rot Mean | Cov3D Max | SH Max | SH Mean |");
        System.out.println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");

        compareAndVerify("PLY (Big-Endian)", loadFile(dataDir, "lizard_10k_be.ply"), refPly, "lizard_10k_be.ply",
                false, 1e-6f, 1e-6f, 1e-6f, 1e-6f, 1e-6f, 1e-5f);
        compareAndVerify("PLY (ASCII 1k)", refPly1k, loadSubPly(refPly, 1000), "lizard_1k_ascii.ply",
                false, 5e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);
        compareAndVerify("Compressed PLY", loadFile(dataDir, "lizard_10k.compressed.ply"), refPly, "lizard_10k.compressed.ply",
                true, 0.03f, 0.01f, 0.005f, 0.01f, 0.01f, 0.05f);
        compareAndVerify("SOG v2", loadFile(dataDir, "lizard_10k.sog"), refPly, "lizard_10k.sog",
                true, 0.01f, 0.02f, 0.005f, 0.05f, 0.02f, 0.25f);
        compareAndVerify("SPZ v2", loadFile(dataDir, "lizard_10k_v2.spz"), refPly, "lizard_10k_v2.spz",
                false, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 0.02f, 1e-5f);
        compareAndVerify("SPZ v3", loadFile(dataDir, "lizard_10k_v3.spz"), refPly, "lizard_10k_v3.spz",
                false, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 0.005f, 1e-5f);
        compareAndVerify("SPLAT", loadFile(dataDir, "lizard_10k.splat"), refPly, "lizard_10k.splat",
                false, 1e-6f, 1e-6f, 0.005f, 0.35f, 0.02f, 0.0f);
        compareAndVerify("KSPLAT (mode 0)", loadFile(dataDir, "lizard_10k_m0.ksplat"), refPly, "lizard_10k_m0.ksplat",
                true, 1e-5f, 1e-5f, 0.005f, 0.35f, 1e-5f, 1e-5f);
        compareAndVerify("KSPLAT (mode 1)", loadFile(dataDir, "lizard_10k_m1.ksplat"), refPly, "lizard_10k_m1.ksplat",
                true, 0.002f, 0.002f, 0.005f, 0.35f, 0.005f, 0.005f);
        compareAndVerify("KSPLAT (mode 2)", loadFile(dataDir, "lizard_10k_m2.ksplat"), refPly, "lizard_10k_m2.ksplat",
                true, 0.002f, 0.002f, 0.005f, 0.35f, 0.005f, 0.02f);

        System.out.println("\n=== Table B: Cross-Implementation Comparison against @playcanvas/splat-transform Roundtrip PLY ===");
        System.out.println("| Format | Direct File vs splat-transform PLY | N | SH | Pos Max | Pos Mean | Scale Max | Scale Mean | Opacity Max | Opacity Mean | Color Max | Color Mean | Rot Max | Rot Mean | SH Max | SH Mean |");
        System.out.println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");

        compareRoundtrip("Compressed PLY", loadFile(dataDir, "lizard_10k.compressed.ply"),
                loadFile(dataDir, "rt_compressed.ply"), "lizard_10k.compressed.ply vs rt_compressed.ply",
                5e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);
        compareRoundtrip("SOG v2", loadFile(dataDir, "lizard_10k.sog"),
                loadFile(dataDir, "rt_sog.ply"), "lizard_10k.sog vs rt_sog.ply",
                5e-4f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);
        compareRoundtrip("SPLAT", loadFile(dataDir, "lizard_10k.splat"),
                loadFile(dataDir, "rt_splat.ply"), "lizard_10k.splat vs rt_splat.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 0.01f, 1e-5f);
        compareRoundtrip("KSPLAT (mode 0)", loadFile(dataDir, "lizard_10k_m0.ksplat"),
                loadFile(dataDir, "rt_ksplat_m0.ply"), "lizard_10k_m0.ksplat vs rt_ksplat_m0.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);
        compareRoundtrip("KSPLAT (mode 1)", loadFile(dataDir, "lizard_10k_m1.ksplat"),
                loadFile(dataDir, "rt_ksplat_m1.ply"), "lizard_10k_m1.ksplat vs rt_ksplat_m1.ply",
                1e-4f, 1e-4f, 1e-5f, 1e-5f, 1e-4f, 1e-5f);
        compareRoundtrip("KSPLAT (mode 2)", loadFile(dataDir, "lizard_10k_m2.ksplat"),
                loadFile(dataDir, "rt_ksplat_m2.ply"), "lizard_10k_m2.ksplat vs rt_ksplat_m2.ply",
                1e-4f, 1e-4f, 1e-5f, 1e-5f, 1e-4f, 1e-5f);

        // splat-transform の read-spz.ts は CoordinateSystem.UNSPECIFIED で展開するため、rt_spz_*.ply はすでに RUB の値が書かれている。
        // したがって rt_spz_*.ply に対してもう一度 flipYZ を掛けて RUB に戻したものと比較すると完全一致する。
        final SplatCloud rtSpzV2 = loadFile(dataDir, "rt_spz_v2.ply");
        SplatLoader.flipYZ(rtSpzV2.count, rtSpzV2.positions, rtSpzV2.rotations, rtSpzV2.shDegree, rtSpzV2.shCoefficients);
        compareRoundtrip("SPZ v2", loadFile(dataDir, "lizard_10k_v2.spz"),
                rtSpzV2, "lizard_10k_v2.spz vs rt_spz_v2.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        final SplatCloud rtSpzV3 = loadFile(dataDir, "rt_spz_v3.ply");
        SplatLoader.flipYZ(rtSpzV3.count, rtSpzV3.positions, rtSpzV3.rotations, rtSpzV3.shDegree, rtSpzV3.shCoefficients);
        compareRoundtrip("SPZ v3", loadFile(dataDir, "lizard_10k_v3.spz"),
                rtSpzV3, "lizard_10k_v3.spz vs rt_spz_v3.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        // SPZ v4: SH 次数 0 / 1 / 2 / 3
        final SplatCloud rtSpzV4Deg0 = loadFile(dataDir, "rt_spz_v4_deg0.ply");
        SplatLoader.flipYZ(rtSpzV4Deg0.count, rtSpzV4Deg0.positions, rtSpzV4Deg0.rotations, rtSpzV4Deg0.shDegree, rtSpzV4Deg0.shCoefficients);
        compareRoundtrip("SPZ v4 (deg 0)", loadFile(dataDir, "lizard_10k_deg0_v4.spz"),
                rtSpzV4Deg0, "lizard_10k_deg0_v4.spz vs rt_spz_v4_deg0.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        final SplatCloud rtSpzV4Deg1 = loadFile(dataDir, "rt_spz_v4_deg1.ply");
        SplatLoader.flipYZ(rtSpzV4Deg1.count, rtSpzV4Deg1.positions, rtSpzV4Deg1.rotations, rtSpzV4Deg1.shDegree, rtSpzV4Deg1.shCoefficients);
        compareRoundtrip("SPZ v4 (deg 1)", loadFile(dataDir, "lizard_10k_deg1_v4.spz"),
                rtSpzV4Deg1, "lizard_10k_deg1_v4.spz vs rt_spz_v4_deg1.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        final SplatCloud rtSpzV4Deg2 = loadFile(dataDir, "rt_spz_v4_deg2.ply");
        SplatLoader.flipYZ(rtSpzV4Deg2.count, rtSpzV4Deg2.positions, rtSpzV4Deg2.rotations, rtSpzV4Deg2.shDegree, rtSpzV4Deg2.shCoefficients);
        compareRoundtrip("SPZ v4 (deg 2)", loadFile(dataDir, "lizard_10k_deg2_v4.spz"),
                rtSpzV4Deg2, "lizard_10k_deg2_v4.spz vs rt_spz_v4_deg2.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        final SplatCloud rtSpzV4Deg3 = loadFile(dataDir, "rt_spz_v4.ply");
        SplatLoader.flipYZ(rtSpzV4Deg3.count, rtSpzV4Deg3.positions, rtSpzV4Deg3.rotations, rtSpzV4Deg3.shDegree, rtSpzV4Deg3.shCoefficients);
        compareRoundtrip("SPZ v4 (deg 3)", loadFile(dataDir, "lizard_10k_v4.spz"),
                rtSpzV4Deg3, "lizard_10k_v4.spz vs rt_spz_v4.ply",
                1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f, 1e-5f);

        // 10 万個超の実データ (hornedlizard_v4.spz: 786,233 点 -> MAX_SPLATS=150,000 点選択)
        final SplatCloud rtHorned = loadFile(dataDir, "rt_hornedlizard_v4.ply");
        SplatLoader.flipYZ(rtHorned.count, rtHorned.positions, rtHorned.rotations, rtHorned.shDegree, rtHorned.shCoefficients);
        compareRoundtrip("SPZ v4 (786k)", loadFile(dataDir, "hornedlizard_v4.spz"),
                rtHorned, "hornedlizard_v4.spz vs rt_hornedlizard_v4.ply",
                1e-4f, 1e-4f, 1e-4f, 1e-4f, 1e-4f, 1e-4f);
        System.out.println();
    }

    // 300 万個・SH 3 次 (約 710 MB) のバイナリ PLY を 2 パスで省メモリに読み込めることを検証する
    private static void testLargePlyMemory(File dataDir) throws IOException {
        final File largePlyFile = new File(dataDir, "large_3m.ply");
        if (!largePlyFile.exists()) {
            System.out.println("Generating 3,000,000-splat SH-degree-3 PLY (" + largePlyFile.getAbsolutePath() + ") for memory test...");
            generateLarge3MPly(largePlyFile, 3_000_000);
        }
        System.gc();
        final long t0 = System.nanoTime();
        final byte[] content = Files.readAllBytes(largePlyFile.toPath());
        final SplatCloud cloud = SplatLoader.load(content, largePlyFile.getName());
        final long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        final long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        if (cloud.count != SplatLoader.MAX_SPLATS || cloud.shDegree != 3) {
            throw new AssertionError("Large 3M PLY test failed: count=" + cloud.count + ", shDegree=" + cloud.shDegree);
        }
        validateCloudIntegrity(cloud, "large_3m.ply");
        System.out.println("Large 3M splats (SH deg 3, " + (content.length / (1024 * 1024))
                + " MB) loaded into MAX_SPLATS=" + cloud.count + " in " + elapsedMs + " ms (heap used: " + usedMb + " MB)");
    }

    private static void generateLarge3MPly(File target, int count) throws IOException {
        final StringBuilder hdr = new StringBuilder();
        hdr.append("ply\nformat binary_little_endian 1.0\nelement vertex ").append(count).append("\n");
        hdr.append("property float x\nproperty float y\nproperty float z\n");
        hdr.append("property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n");
        for (int i = 0; i < 45; i++) {
            hdr.append("property float f_rest_").append(i).append("\n");
        }
        hdr.append("property float opacity\nproperty float scale_0\nproperty float scale_1\nproperty float scale_2\n");
        hdr.append("property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n");
        hdr.append("end_header\n");

        final byte[] headerBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);
        final int vertexBytes = 59 * 4;
        final int batchVerts = 4096;
        final ByteBuffer batchBuf = ByteBuffer.allocate(batchVerts * vertexBytes).order(ByteOrder.LITTLE_ENDIAN);

        try (java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target.toPath()), 1024 * 1024)) {
            out.write(headerBytes);
            int remaining = count;
            int idx = 0;
            while (remaining > 0) {
                final int n = Math.min(remaining, batchVerts);
                batchBuf.clear();
                for (int i = 0; i < n; i++, idx++) {
                    batchBuf.putFloat((idx % 1000) * 0.01f);
                    batchBuf.putFloat(((idx / 1000) % 1000) * 0.01f);
                    batchBuf.putFloat((idx / 1_000_000) * 0.1f);
                    batchBuf.putFloat(0.2f);
                    batchBuf.putFloat(-0.1f);
                    batchBuf.putFloat(0.3f);
                    for (int s = 0; s < 45; s++) {
                        batchBuf.putFloat((s - 22) * 0.01f);
                    }
                    batchBuf.putFloat((idx % 20 == 0) ? 2.0f : -1.0f);
                    final float sc = (idx % 20 == 0) ? -2.0f : -5.0f;
                    batchBuf.putFloat(sc);
                    batchBuf.putFloat(sc);
                    batchBuf.putFloat(sc);
                    batchBuf.putFloat(1.0f);
                    batchBuf.putFloat(0.0f);
                    batchBuf.putFloat(0.0f);
                    batchBuf.putFloat(0.0f);
                }
                out.write(batchBuf.array(), 0, n * vertexBytes);
                remaining -= n;
            }
        }
    }

    private static SplatCloud loadFile(File dir, String name) throws IOException {
        final File f = new File(dir, name);
        return SplatLoader.load(Files.readAllBytes(f.toPath()), f.getName());
    }

    private static SplatCloud loadSubPly(SplatCloud src, int limit) {
        final int rest = SplatCloud.restCoefficients(src.shDegree);
        return new SplatCloud(
                limit,
                Arrays.copyOf(src.positions, limit * 3),
                Arrays.copyOf(src.rotations, limit * 4),
                Arrays.copyOf(src.scales, limit * 3),
                Arrays.copyOf(src.opacities, limit),
                Arrays.copyOf(src.colors, limit * 3),
                src.shDegree,
                Arrays.copyOf(src.shCoefficients, limit * rest * 3)
        );
    }

    private static void compareAndVerify(String label, SplatCloud actual, SplatCloud ref, String fileName,
                                         boolean reordered, float tolPos, float tolScale, float tolOp,
                                         float tolColor, float tolRot, float tolSh) {
        if (actual.count != ref.count) {
            throw new AssertionError("Count mismatch for " + fileName + ": " + actual.count + " vs " + ref.count);
        }
        final int n = actual.count;
        final int[] matchToRef = reordered ? matchByPosition(actual, ref) : identityMap(n);

        double sumPos = 0, maxPos = 0;
        double sumScale = 0, maxScale = 0;
        double sumOp = 0, maxOp = 0;
        double sumCol = 0, maxCol = 0;
        double sumRot = 0, maxRot = 0;
        double maxCov = 0;
        double sumSh = 0, maxSh = 0;

        final int minDeg = Math.min(actual.shDegree, ref.shDegree);
        final int commonRest = SplatCloud.restCoefficients(minDeg);
        final int actualRest = SplatCloud.restCoefficients(actual.shDegree);
        final int refRest = SplatCloud.restCoefficients(ref.shDegree);

        for (int i = 0; i < n; i++) {
            final int j = matchToRef[i];
            for (int c = 0; c < 3; c++) {
                final double dp = Math.abs(actual.positions[i * 3 + c] - ref.positions[j * 3 + c]);
                sumPos += dp;
                if (dp > maxPos) maxPos = dp;

                final double ds = Math.abs(actual.scales[i * 3 + c] - ref.scales[j * 3 + c]);
                sumScale += ds;
                if (ds > maxScale) maxScale = ds;

                final double dc = Math.abs(actual.colors[i * 3 + c] - ref.colors[j * 3 + c]);
                sumCol += dc;
                if (dc > maxCol) maxCol = dc;
            }

            final double dop = Math.abs(actual.opacities[i] - ref.opacities[j]);
            sumOp += dop;
            if (dop > maxOp) maxOp = dop;

            final double drot = quatDistance(actual.rotations, i * 4, ref.rotations, j * 4);
            sumRot += drot;
            if (drot > maxRot) maxRot = drot;

            final double dcov = covDistance(actual.scales, i * 3, actual.rotations, i * 4,
                    ref.scales, j * 3, ref.rotations, j * 4);
            if (dcov > maxCov) maxCov = dcov;

            for (int k = 0; k < commonRest; k++) {
                for (int c = 0; c < 3; c++) {
                    final float va = actual.shCoefficients[(i * actualRest + k) * 3 + c];
                    final float vr = ref.shCoefficients[(j * refRest + k) * 3 + c];
                    final double dsh = Math.abs(va - vr);
                    sumSh += dsh;
                    if (dsh > maxSh) maxSh = dsh;
                }
            }
        }

        final double meanPos = sumPos / (n * 3.0);
        final double meanScale = sumScale / (n * 3.0);
        final double meanOp = sumOp / n;
        final double meanCol = sumCol / (n * 3.0);
        final double meanRot = sumRot / n;
        final double meanSh = commonRest > 0 ? sumSh / ((double) n * commonRest * 3.0) : 0.0;

        System.out.println(String.format(Locale.ROOT,
                "| %s | `%s` | %d | %d | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f | %.6f |",
                label, fileName, n, actual.shDegree,
                maxPos, meanPos, maxScale, meanScale, maxOp, meanOp,
                maxCol, meanCol, maxRot, meanRot, maxCov, maxSh, meanSh));

        if (meanPos > tolPos || meanScale > tolScale || meanOp > tolOp
                || meanCol > tolColor || meanRot > tolRot || (commonRest > 0 && meanSh > tolSh)) {
            throw new AssertionError("Error tolerance exceeded for " + fileName);
        }
    }

    private static void compareRoundtrip(String label, SplatCloud direct, SplatCloud rtPly, String desc,
                                         float tolPos, float tolScale, float tolOp, float tolCol, float tolRot, float tolSh) {
        if (direct.count != rtPly.count || direct.shDegree != rtPly.shDegree) {
            throw new AssertionError("Roundtrip metadata mismatch for " + desc);
        }
        final int n = direct.count;
        final int rest = SplatCloud.restCoefficients(direct.shDegree);

        double sumPos = 0, maxPos = 0;
        double sumScale = 0, maxScale = 0;
        double sumOp = 0, maxOp = 0;
        double sumCol = 0, maxCol = 0;
        double sumRot = 0, maxRot = 0;
        double sumSh = 0, maxSh = 0;

        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) {
                final double dp = Math.abs(direct.positions[i * 3 + c] - rtPly.positions[i * 3 + c]);
                sumPos += dp;
                if (dp > maxPos) maxPos = dp;

                final double ds = Math.abs(direct.scales[i * 3 + c] - rtPly.scales[i * 3 + c]);
                sumScale += ds;
                if (ds > maxScale) maxScale = ds;

                final double dc = Math.abs(direct.colors[i * 3 + c] - rtPly.colors[i * 3 + c]);
                sumCol += dc;
                if (dc > maxCol) maxCol = dc;
            }
            final double dop = Math.abs(direct.opacities[i] - rtPly.opacities[i]);
            sumOp += dop;
            if (dop > maxOp) maxOp = dop;

            final double drot = quatDistance(direct.rotations, i * 4, rtPly.rotations, i * 4);
            sumRot += drot;
            if (drot > maxRot) maxRot = drot;

            for (int k = 0; k < rest * 3; k++) {
                final double dsh = Math.abs(direct.shCoefficients[i * rest * 3 + k] - rtPly.shCoefficients[i * rest * 3 + k]);
                sumSh += dsh;
                if (dsh > maxSh) maxSh = dsh;
            }
        }

        final double meanPos = sumPos / (n * 3.0);
        final double meanScale = sumScale / (n * 3.0);
        final double meanOp = sumOp / n;
        final double meanCol = sumCol / (n * 3.0);
        final double meanRot = sumRot / n;
        final double meanSh = rest > 0 ? sumSh / ((double) n * rest * 3.0) : 0.0;

        System.out.println(String.format(Locale.ROOT,
                "| %s | `%s` | %d | %d | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f | %.7f |",
                label, desc, n, direct.shDegree,
                maxPos, meanPos, maxScale, meanScale, maxOp, meanOp,
                maxCol, meanCol, maxRot, meanRot, maxSh, meanSh));

        if (maxPos > tolPos || maxScale > tolScale || maxOp > tolOp
                || maxCol > tolCol || maxRot > tolRot || (rest > 0 && maxSh > tolSh)) {
            throw new AssertionError("Roundtrip tolerance exceeded for " + desc);
        }
    }

    // q と -q を同一視した四元数の成分最大誤差 min(||q1 - q2||_inf, ||q1 + q2||_inf)
    private static double quatDistance(float[] q1, int o1, float[] q2, int o2) {
        double diffMinus = 0;
        double diffPlus = 0;
        for (int c = 0; c < 4; c++) {
            diffMinus = Math.max(diffMinus, Math.abs(q1[o1 + c] - q2[o2 + c]));
            diffPlus = Math.max(diffPlus, Math.abs(q1[o1 + c] + q2[o2 + c]));
        }
        return Math.min(diffMinus, diffPlus);
    }

    // 3D ガウス分布の共分散行列 Sigma = R * diag(s^2) * R^T の要素最大誤差
    private static double covDistance(float[] s1, int so1, float[] q1, int qo1,
                                      float[] s2, int so2, float[] q2, int qo2) {
        final double[] cov1 = computeCov3D(s1[so1], s1[so1 + 1], s1[so1 + 2],
                q1[qo1], q1[qo1 + 1], q1[qo1 + 2], q1[qo1 + 3]);
        final double[] cov2 = computeCov3D(s2[so2], s2[so2 + 1], s2[so2 + 2],
                q2[qo2], q2[qo2 + 1], q2[qo2 + 2], q2[qo2 + 3]);
        double maxDiff = 0;
        for (int i = 0; i < 6; i++) {
            final double d = Math.abs(cov1[i] - cov2[i]);
            if (d > maxDiff) maxDiff = d;
        }
        return maxDiff;
    }

    private static double[] computeCov3D(double sx, double sy, double sz,
                                         double x, double y, double z, double w) {
        final double r00 = 1.0 - 2.0 * (y * y + z * z);
        final double r01 = 2.0 * (x * y - w * z);
        final double r02 = 2.0 * (x * z + w * y);
        final double r10 = 2.0 * (x * y + w * z);
        final double r11 = 1.0 - 2.0 * (x * x + z * z);
        final double r12 = 2.0 * (y * z - w * x);
        final double r20 = 2.0 * (x * z - w * y);
        final double r21 = 2.0 * (y * z + w * x);
        final double r22 = 1.0 - 2.0 * (x * x + y * y);

        final double vx = sx * sx, vy = sy * sy, vz = sz * sz;
        return new double[]{
                r00 * r00 * vx + r01 * r01 * vy + r02 * r02 * vz,
                r00 * r10 * vx + r01 * r11 * vy + r02 * r12 * vz,
                r00 * r20 * vx + r01 * r21 * vy + r02 * r22 * vz,
                r10 * r10 * vx + r11 * r11 * vy + r12 * r12 * vz,
                r10 * r20 * vx + r11 * r21 * vy + r12 * r22 * vz,
                r20 * r20 * vx + r21 * r21 * vy + r22 * r22 * vz
        };
    }

    private static int[] identityMap(int n) {
        final int[] map = new int[n];
        for (int i = 0; i < n; i++) map[i] = i;
        return map;
    }

    // SOG や KSPLAT の変換器はモートン順序やバケット順に並び替えるため、位置・スケール・色の最近傍で元のガウス分布と対応付ける
    private static int[] matchByPosition(SplatCloud target, SplatCloud ref) {
        final int n = target.count;
        final int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            final float tx = target.positions[i * 3];
            final float ty = target.positions[i * 3 + 1];
            final float tz = target.positions[i * 3 + 2];
            final float tsx = target.scales[i * 3];
            float bestDist = Float.POSITIVE_INFINITY;
            int bestJ = 0;
            for (int j = 0; j < ref.count; j++) {
                final float dx = tx - ref.positions[j * 3];
                final float dy = ty - ref.positions[j * 3 + 1];
                final float dz = tz - ref.positions[j * 3 + 2];
                final float ds = tsx - ref.scales[j * 3];
                final float dist = dx * dx + dy * dy + dz * dz + ds * ds * 0.01f;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestJ = j;
                }
            }
            result[i] = bestJ;
        }
        return result;
    }

    private static void validateCloudIntegrity(SplatCloud cloud, String name) {
        if (cloud == null || cloud.count <= 0 || cloud.count > SplatLoader.MAX_SPLATS) {
            throw new AssertionError("Invalid cloud count in " + name);
        }
        if (cloud.shDegree < 0 || cloud.shDegree > 3) {
            throw new AssertionError("Invalid shDegree in " + name + ": " + cloud.shDegree);
        }
        final int rest = SplatCloud.restCoefficients(cloud.shDegree);
        if (cloud.positions.length != cloud.count * 3
                || cloud.rotations.length != cloud.count * 4
                || cloud.scales.length != cloud.count * 3
                || cloud.opacities.length != cloud.count
                || cloud.colors.length != cloud.count * 3
                || cloud.shCoefficients.length != cloud.count * rest * 3) {
            throw new AssertionError("Array length mismatch in " + name);
        }
        for (int i = 0; i < cloud.positions.length; i++) {
            if (!Float.isFinite(cloud.positions[i])) throw new AssertionError("Non-finite position in " + name);
        }
        for (int i = 0; i < cloud.scales.length; i++) {
            if (!Float.isFinite(cloud.scales[i]) || cloud.scales[i] < 0f) {
                throw new AssertionError("Invalid scale in " + name + ": " + cloud.scales[i]);
            }
        }
        for (int i = 0; i < cloud.opacities.length; i++) {
            if (!Float.isFinite(cloud.opacities[i]) || cloud.opacities[i] < -1e-5f || cloud.opacities[i] > 1.00001f) {
                throw new AssertionError("Invalid opacity in " + name + ": " + cloud.opacities[i]);
            }
        }
        for (int i = 0; i < cloud.colors.length; i++) {
            if (!Float.isFinite(cloud.colors[i])) throw new AssertionError("Non-finite color in " + name);
        }
        for (int i = 0; i < cloud.count; i++) {
            final float x = cloud.rotations[i * 4];
            final float y = cloud.rotations[i * 4 + 1];
            final float z = cloud.rotations[i * 4 + 2];
            final float w = cloud.rotations[i * 4 + 3];
            final float norm = (float) Math.sqrt(x * x + y * y + z * z + w * w);
            if (!Float.isFinite(norm) || Math.abs(norm - 1.0f) > 1e-3f) {
                throw new AssertionError("Unnormalized quaternion in " + name + ": norm=" + norm);
            }
        }
        for (int i = 0; i < cloud.shCoefficients.length; i++) {
            if (!Float.isFinite(cloud.shCoefficients[i])) throw new AssertionError("Non-finite SH in " + name);
        }
    }

    private static void expectIOException(byte[] data, String name, String desc) {
        try {
            SplatLoader.load(data, name);
            throw new AssertionError("Expected IOException for: " + desc);
        } catch (IOException expected) {
            // 期待通り
        }
    }

    private static byte[] createMinimalSpz(int version, int numPoints, int shDegree) throws IOException {
        final int shDim = SplatCloud.restCoefficients(shDegree);
        final int rotStride = version == 3 ? 4 : 3;
        final int total = 16 + numPoints * (9 + 1 + 3 + 3 + rotStride + shDim * 3);
        final byte[] raw = new byte[total];
        final ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0x5053474E);
        bb.putInt(4, version);
        bb.putInt(8, numPoints);
        raw[12] = (byte) shDegree;
        raw[13] = 12;
        raw[14] = 0;
        raw[15] = 0;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] createMinimalSplat(int count) {
        final byte[] b = new byte[count * 32];
        final ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            final int o = i * 32;
            bb.putFloat(o, 1.0f);
            bb.putFloat(o + 4, 2.0f);
            bb.putFloat(o + 8, 3.0f);
            bb.putFloat(o + 12, 0.1f);
            bb.putFloat(o + 16, 0.2f);
            bb.putFloat(o + 20, 0.3f);
            b[o + 27] = (byte) 255;
            b[o + 28] = (byte) 255;
            b[o + 29] = (byte) 128;
            b[o + 30] = (byte) 128;
            b[o + 31] = (byte) 128;
        }
        return b;
    }

    private static byte[] createMinimalKsplatMode0(int count) {
        final byte[] b = new byte[4096 + 1024 + count * 44];
        final ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        b[0] = 0;
        b[1] = 1;
        bb.putInt(4, 1);
        bb.putInt(8, 1);
        bb.putInt(12, count);
        bb.putInt(16, count);
        bb.putShort(20, (short) 0);
        bb.putFloat(36, -1.5f);
        bb.putFloat(40, 1.5f);

        bb.putInt(4096, count);
        bb.putInt(4096 + 4, count);
        for (int i = 0; i < count; i++) {
            final int o = 5120 + i * 44;
            bb.putFloat(o, 1.0f);
            bb.putFloat(o + 4, 2.0f);
            bb.putFloat(o + 8, 3.0f);
            bb.putFloat(o + 12, 0.1f);
            bb.putFloat(o + 16, 0.1f);
            bb.putFloat(o + 20, 0.1f);
            bb.putFloat(o + 24, 1.0f);
            b[o + 43] = (byte) 255;
        }
        return b;
    }

    private static byte[] createZipWithEntry(String entryName, byte[] entryData) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(entryData);
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] wrapInZstdRawBlock(byte[] raw) {
        final int size = raw.length;
        final byte[] frame = new byte[12 + size];
        // Zstandard Magic: 0xFD2FB528 LE
        frame[0] = (byte) 0x28;
        frame[1] = (byte) 0xB5;
        frame[2] = (byte) 0x2F;
        frame[3] = (byte) 0xFD;
        // Frame_Header_Descriptor: Single_Segment=1, fcsFlag=2 (4 bytes FCS) -> 0xA0
        frame[4] = (byte) 0xA0;
        // Frame_Content_Size: 4 bytes LE
        frame[5] = (byte) (size & 0xFF);
        frame[6] = (byte) ((size >> 8) & 0xFF);
        frame[7] = (byte) ((size >> 16) & 0xFF);
        frame[8] = (byte) ((size >> 24) & 0xFF);
        // Block Header: 3 bytes LE (last_block=1, type=0 (Raw_Block), size=size)
        final int bh = (size << 3) | 1;
        frame[9] = (byte) (bh & 0xFF);
        frame[10] = (byte) ((bh >> 8) & 0xFF);
        frame[11] = (byte) ((bh >> 16) & 0xFF);
        System.arraycopy(raw, 0, frame, 12, size);
        return frame;
    }

    private static byte[] createMinimalSpzV4(int numPoints, int shDegree, int fractionalBits, int extLength, boolean antialiased) throws IOException {
        final int numStreams = shDegree == 0 ? 5 : 6;
        final int fileShDim = SpzReader.shDimForDegree(shDegree);

        final byte[] posRaw = new byte[numPoints * 9];
        final byte[] alphaRaw = new byte[numPoints];
        Arrays.fill(alphaRaw, (byte) 200);
        final byte[] colorRaw = new byte[numPoints * 3];
        Arrays.fill(colorRaw, (byte) 128);
        final byte[] scaleRaw = new byte[numPoints * 3];
        Arrays.fill(scaleRaw, (byte) 160);
        final byte[] rotRaw = new byte[numPoints * 4];
        final ByteBuffer rotBb = ByteBuffer.wrap(rotRaw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numPoints; i++) {
            // smallest-three: largest=3, magnitude=0 -> packed = 3 << 30
            rotBb.putInt(i * 4, 3 << 30);
        }
        final byte[] shRaw = shDegree > 0 ? new byte[numPoints * fileShDim * 3] : null;
        if (shRaw != null) {
            Arrays.fill(shRaw, (byte) 128);
        }

        final byte[][] rawStreams = new byte[numStreams][];
        rawStreams[0] = posRaw;
        rawStreams[1] = alphaRaw;
        rawStreams[2] = colorRaw;
        rawStreams[3] = scaleRaw;
        rawStreams[4] = rotRaw;
        if (shDegree > 0) rawStreams[5] = shRaw;

        final byte[][] compStreams = new byte[numStreams][];
        int totalCompSize = 0;
        for (int i = 0; i < numStreams; i++) {
            compStreams[i] = wrapInZstdRawBlock(rawStreams[i]);
            totalCompSize += compStreams[i].length;
        }

        final int tocByteOffset = 32 + extLength;
        final int totalSize = tocByteOffset + numStreams * 16 + totalCompSize;
        final byte[] spz = new byte[totalSize];
        final ByteBuffer bb = ByteBuffer.wrap(spz).order(ByteOrder.LITTLE_ENDIAN);

        // 32-byte header
        bb.putInt(0, 0x5053474E); // NGSP
        bb.putInt(4, 4);          // version 4
        bb.putInt(8, numPoints);
        spz[12] = (byte) shDegree;
        spz[13] = (byte) fractionalBits;
        spz[14] = (byte) ((antialiased ? 1 : 0) | (extLength > 0 ? 2 : 0));
        spz[15] = (byte) numStreams;
        bb.putInt(16, tocByteOffset);

        // Extensions (if any)
        if (extLength > 0) {
            Arrays.fill(spz, 32, 32 + extLength, (byte) 0x55);
        }

        // TOC
        for (int i = 0; i < numStreams; i++) {
            final int entryOff = tocByteOffset + i * 16;
            bb.putLong(entryOff, compStreams[i].length);
            bb.putLong(entryOff + 8, rawStreams[i].length);
        }

        // Streams
        int streamOff = tocByteOffset + numStreams * 16;
        for (int i = 0; i < numStreams; i++) {
            System.arraycopy(compStreams[i], 0, spz, streamOff, compStreams[i].length);
            streamOff += compStreams[i].length;
        }

        return spz;
    }
}
