package marumasa.marumasa_sign.core.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * BC6H / BC7 (Bptc)、UASTC (Uastc)、ETC1S (BasisEtc1s) の展開の動作と安全性を確かめる。
 * <ul>
 *   <li>決めた乱数で作ったたくさんのブロックを展開し、結果のハッシュが書き換える前の実装と同じか</li>
 *   <li>テスト用の小さな KTX2 (src/test/resources/marumasa_sign/test/texture) を展開した結果のハッシュ</li>
 *   <li>壊した KTX2 を決めた時間だけ読ませて、想定外の例外や長い時間にならないか</li>
 * </ul>
 * 引数: [壊したデータを試す秒数 (既定 20)]
 */
public final class CompressedTextureCheck {

    private static final String RESOURCES = "/marumasa_sign/test/texture/";
    // {ファイル名, 書き換える前の実装で展開した画素のハッシュ}
    private static final String[][] SAMPLES = {
            {"2d_etc1s.ktx2", "953d42aeedf2ba23"},
            {"2d_etc1s_alpha.ktx2", "c2f29e544211d283"},
            {"2d_uastc.ktx2", "657530a9a97cc7b2"},
            {"2d_uastc_zstd.ktx2", "657530a9a97cc7b2"},
            {"2d_bc7.ktx2", "05ebd83f05a0c7f1"}
    };
    // 乱数のブロックのハッシュ (BC7, BC6H 符号なし, BC6H 符号付き, UASTC, UASTC sRGB)。書き換える前の実装で求めた値
    private static final long[] EXPECTED_BLOCK_HASHES = {
            0x06e6b7c86fddf8d5L, 0x4d74f465aeb03a4fL, 0x704ee30304b409c5L, 0x81b3804c4b77a9bbL, 0x1500d6b6c1b76f7fL};
    private static final int RANDOM_BLOCKS = 300000;

    private static int failures;

    private CompressedTextureCheck() {
    }

    public static void main(String[] args) throws Exception {
        final long fuzzSeconds = args.length > 0 ? Long.parseLong(args[0]) : 20;
        testReservedModes();
        testRandomBlocks();
        testSamples();
        fuzz(new Random(11), fuzzSeconds);
        if (failures > 0) {
            System.out.println("CompressedTextureCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CompressedTextureCheck: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            if (failures <= 20) System.out.println("  FAILED: " + message);
        }
    }

    // 使わないモードは仕様どおり 0 (黒) になる
    private static void testReservedModes() {
        final byte[] block = new byte[16];
        Arrays.fill(block, (byte) 0xFF);
        block[0] = 0;
        final int[] argb = new int[16];
        Arrays.fill(argb, 123);
        Bptc.bc7(block, 0, argb);
        check(Arrays.equals(argb, new int[16]), "BC7 reserved mode should be zero");
        final float[] rgb = new float[48];
        for (int mode : new int[]{19, 23, 27, 31}) {
            Arrays.fill(rgb, 1f);
            block[0] = (byte) (0xE0 | mode);
            Bptc.bc6h(block, 0, false, rgb);
            check(Arrays.equals(rgb, new float[48]), "BC6H reserved mode " + mode + " should be zero");
        }
        // UASTC のモード 19 (将来のための符号) は不正
        block[0] = 0x45;
        check(!Uastc.decode(block, 0, false, argb), "UASTC mode 19 should be rejected");
        System.out.println("reserved modes: done");
    }

    static long[] randomBlockHashes(int count) {
        final long[] hashes = new long[5];
        Arrays.fill(hashes, 1469598103934665603L);
        final byte[] block = new byte[16];
        final int[] argb = new int[16];
        final float[] rgb = new float[48];
        long state = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < count; i++) {
            for (int b = 0; b < 16; b += 8) {
                state ^= state << 13;
                state ^= state >>> 7;
                state ^= state << 17;
                for (int k = 0; k < 8; k++) block[b + k] = (byte) (state >>> (k * 8));
            }
            // ときどき 0 や 0xFF を増やして、端の値も試す
            if (i % 7 == 3) for (int k = 1; k < 16; k += 2) block[k] = 0;
            if (i % 7 == 5) for (int k = 2; k < 16; k += 3) block[k] = (byte) 0xFF;
            Bptc.bc7(block, 0, argb);
            hashes[0] = mix(hashes[0], argb);
            for (int s = 0; s < 2; s++) {
                Bptc.bc6h(block, 0, s == 1, rgb);
                for (float f : rgb) hashes[1 + s] = mix(hashes[1 + s], Float.floatToIntBits(f));
            }
            for (int s = 0; s < 2; s++) {
                final boolean ok = Uastc.decode(block, 0, s == 1, argb);
                hashes[3 + s] = ok ? mix(hashes[3 + s], argb) : mix(hashes[3 + s], -1);
            }
        }
        return hashes;
    }

    private static void testRandomBlocks() {
        final long start = System.nanoTime();
        final long[] hashes = randomBlockHashes(RANDOM_BLOCKS);
        final String[] names = {"BC7", "BC6H unsigned", "BC6H signed", "UASTC", "UASTC sRGB"};
        for (int i = 0; i < hashes.length; i++) {
            System.out.printf("random blocks %s: %016x%n", names[i], hashes[i]);
            check(hashes[i] == EXPECTED_BLOCK_HASHES[i], "random block hash " + names[i]);
        }
        System.out.printf("random blocks: %d blocks in %.1fs%n", RANDOM_BLOCKS, (System.nanoTime() - start) / 1e9);
    }

    private static void testSamples() throws IOException {
        for (String[] sample : SAMPLES) {
            final byte[] data = readResource(sample[0]);
            final BufferedImage image = Ktx2Decoder.decode(data);
            final long hash = imageHash(image);
            System.out.printf("sample %s: %dx%d %016x%n", sample[0], image.getWidth(), image.getHeight(), hash);
            check(hash == Long.parseUnsignedLong(sample[1], 16), "sample hash " + sample[0]);
        }
    }

    static long imageHash(BufferedImage image) {
        final int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        return mix(mix(1469598103934665603L, image.getWidth() * 65536 + image.getHeight()), pixels);
    }

    static byte[] readResource(String name) throws IOException {
        final InputStream in = CompressedTextureCheck.class.getResourceAsStream(RESOURCES + name);
        if (in == null) throw new IOException("Missing test resource: " + name);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static long mix(long hash, int[] values) {
        for (int v : values) hash = mix(hash, v);
        return hash;
    }

    private static long mix(long hash, int value) {
        for (int i = 0; i < 4; i++) {
            hash ^= (value >>> (i * 8)) & 0xFF;
            hash *= 1099511628211L;
        }
        return hash;
    }

    // 壊した KTX2 を読ませる。IOException の原因が、展開のクラスの中で起きた実行時例外なら失敗にする
    private static void fuzz(Random rnd, long seconds) throws IOException {
        final byte[][] samples = new byte[SAMPLES.length][];
        for (int i = 0; i < SAMPLES.length; i++) samples[i] = readResource(SAMPLES[i][0]);
        final long deadline = System.nanoTime() + seconds * 1000000000L;
        long cases = 0, rejected = 0, slowest = 0, outsideRuntime = 0;
        while (System.nanoTime() < deadline) {
            final byte[] data = mutate(rnd, samples[rnd.nextInt(samples.length)]);
            final long start = System.nanoTime();
            try {
                Ktx2Decoder.decode(data);
            } catch (IOException e) {
                rejected++;
                final Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    // このプロジェクトのクラスのうち、最初に現れるものが起きた場所
                    StackTraceElement origin = null;
                    for (StackTraceElement frame : cause.getStackTrace()) {
                        if (frame.getClassName().startsWith("marumasa.")) {
                            origin = frame;
                            break;
                        }
                    }
                    final String name = origin == null ? "" : origin.getClassName();
                    if (name.contains("BasisEtc1s") || name.contains("Uastc") || name.contains("Bptc")) {
                        check(false, "unexpected " + cause + " at " + origin);
                    } else {
                        outsideRuntime++;
                    }
                }
            } catch (Throwable t) {
                check(false, "unexpected " + t);
            }
            slowest = Math.max(slowest, System.nanoTime() - start);
            cases++;
        }
        check(slowest < 2000000000L, "slow decode " + slowest / 1000000 + "ms");
        System.out.printf("fuzz: %d cases in %ds, rejected=%d (runtime exceptions outside the block decoders: %d), slowest=%.1fms%n",
                cases, seconds, rejected, outsideRuntime, slowest / 1e6);
    }

    private static byte[] mutate(Random rnd, byte[] data) {
        byte[] d = data.clone();
        final int kind = rnd.nextInt(8);
        if (kind == 0) {
            d = Arrays.copyOf(d, 12 + rnd.nextInt(d.length - 12));
        } else if (kind == 1) {
            // 見出しより後ろを乱数にする
            for (int i = 12 + rnd.nextInt(d.length - 12); i < d.length; i++) d[i] = (byte) rnd.nextInt(256);
        } else {
            final int edits = 1 + rnd.nextInt(kind == 2 ? 40 : 6);
            for (int i = 0; i < edits; i++) {
                final int pos = rnd.nextInt(d.length);
                switch (rnd.nextInt(4)) {
                    case 0: d[pos] = (byte) rnd.nextInt(256); break;
                    case 1: d[pos] ^= (byte) (1 << rnd.nextInt(8)); break;
                    case 2: d[pos] = 0; break;
                    default: d[pos] = (byte) 0xFF; break;
                }
            }
        }
        return d;
    }
}
