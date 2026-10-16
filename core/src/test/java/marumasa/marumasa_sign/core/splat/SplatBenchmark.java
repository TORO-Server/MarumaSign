package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.sign.SignImage;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 150,000 個のガウシアンスプラッティングに対する、並べ替えと頂点生成の処理時間を測定する。
 */
public final class SplatBenchmark {

    public static void main(String[] args) throws Exception {
        final File file = new File(args.length > 0 ? args[0] : "D:\\Dev\\MarumaSign-wt\\splat-data\\nike.splat");
        System.out.println("Loading " + file.getAbsolutePath() + "...");
        final byte[] content = Files.readAllBytes(file.toPath());
        final SplatCloud cloud = SplatLoader.load(content, file.getName());
        final SplatScene scene = new SplatScene(cloud);
        final int count = scene.cloud().count;
        System.out.println("Splats: " + count);

        final float[] placement = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
        final int vertexCount = count * 4;
        final float[] vertices = new float[vertexCount * SignImage.Batch.STRIDE];
        final int[] colors = new int[vertexCount];

        // ウォームアップ (JIT コンパイルの促進)
        System.out.println("Warming up (50 iterations)...");
        for (int i = 0; i < 50; i++) {
            final float angle = (float) Math.toRadians(i * 5.0);
            final float camX = (float) Math.sin(angle) * 10f;
            final float camZ = (float) Math.cos(angle) * 10f;
            scene.emit(placement, camX, 0f, camZ, vertices, colors);
        }

        // 本番計測 (100 回)
        System.out.println("Benchmarking (100 iterations)...");
        final int iterations = 100;
        final long[] times = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            final float angle = (float) Math.toRadians(i * 3.6);
            final float camX = (float) Math.sin(angle) * 10f;
            final float camZ = (float) Math.cos(angle) * 10f;
            final long start = System.nanoTime();
            scene.emit(placement, camX, 0f, camZ, vertices, colors);
            final long end = System.nanoTime();
            times[i] = end - start;
        }

        Arrays.sort(times);
        long sum = 0;
        for (long t : times) sum += t;
        final double avgMs = (sum / (double) iterations) / 1_000_000.0;
        final double minMs = times[0] / 1_000_000.0;
        final double medMs = times[iterations / 2] / 1_000_000.0;
        final double p95Ms = times[(int) (iterations * 0.95)] / 1_000_000.0;
        final double maxMs = times[iterations - 1] / 1_000_000.0;

        System.out.println("=== Benchmark Results ===");
        System.out.printf("Count:      %d splats%n", count);
        System.out.printf("Average:    %.3f ms%n", avgMs);
        System.out.printf("Median:     %.3f ms%n", medMs);
        System.out.printf("Min:        %.3f ms%n", minMs);
        System.out.printf("95th pct:   %.3f ms%n", p95Ms);
        System.out.printf("Max:        %.3f ms%n", maxMs);
    }
}
