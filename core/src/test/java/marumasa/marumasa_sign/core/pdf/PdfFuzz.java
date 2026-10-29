package marumasa.marumasa_sign.core.pdf;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 壊した PDF を読ませて、想定外の例外、長い時間、メモリの使い切りにならないかを確かめる。
 * 試験の厳しい動作 (marumasign.pdfStrict) で動かし、内部で受け止めている想定外の例外も失敗として扱う。
 * <p>
 * 引数: 回数 [ファイルまたはフォルダ...]。ファイルを渡さなければ、試験用の PDF (src/test/resources/pdf) を種にする。
 * ここで作る圧縮していない PDF は、いつも種に加える。失敗したものは build/pdf-fuzz に保存する (同じ失敗を再現できるように)。
 * 乱数の種はシステムプロパティ fuzz.seed で決められる。
 */
public final class PdfFuzz {

    // 1 つの場合の時間の上限 (描く時間の上限 20 秒に、読み込みの分を足したもの)
    private static final long CASE_TIMEOUT_SECONDS = 45;
    // 開けた数と、描けたページの数 (壊し方が強すぎて、何も試せていないことがないかを見るため)
    private static final java.util.concurrent.atomic.AtomicInteger OPENED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger RENDERED = new java.util.concurrent.atomic.AtomicInteger();

    private PdfFuzz() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("marumasign.pdfStrict", "true");
        if (!Strict.ENABLED) throw new AssertionError("Strict mode must be enabled before the PDF classes load");
        final int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        final List<byte[]> seeds = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), seeds, names);
        if (seeds.isEmpty()) collect(new File("src/test/resources/pdf"), seeds, names);
        // ここで作る PDF は圧縮していないので、壊すと内容の流れやフォントの辞書にそのまま届く。ファイルを渡したときも使う
        for (String[] synthetic : PdfSamples.synthetic()) {
            names.add(synthetic[0]);
            seeds.add(synthetic[1].getBytes(StandardCharsets.ISO_8859_1));
        }
        if (seeds.isEmpty()) throw new IllegalArgumentException("No seed PDFs");
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        System.out.println("PdfFuzz: " + iterations + " iterations over " + seeds.size() + " seeds, seed=" + seed);
        final Random random = new Random(seed);
        final File failures = new File("build/pdf-fuzz");
        int failed = 0;
        ExecutorService executor = newExecutor();
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final int which = random.nextInt(seeds.size());
            final byte[] data = mutate(seeds.get(which), random);
            final Future<String> future = executor.submit(() -> run(data));
            String problem = null;
            try {
                problem = future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                executor = newExecutor();
                problem = "timeout (" + CASE_TIMEOUT_SECONDS + " s)";
            } catch (ExecutionException e) {
                problem = "crash: " + describe(e.getCause());
            }
            if (problem != null) {
                failed++;
                failures.mkdirs();
                final File file = new File(failures, "case-" + seed + "-" + i + ".pdf");
                Files.write(file.toPath(), data);
                System.out.println("FAIL " + names.get(which) + " #" + i + ": " + problem + " -> " + file);
            }
        }
        executor.shutdownNow();
        System.out.println("PdfFuzz finished in " + (System.nanoTime() - start) / 1_000_000_000 + " s, opened " + OPENED.get()
                + ", rendered pages " + RENDERED.get() + ", failures: " + failed);
        if (failed > 0) System.exit(1);
        System.exit(0);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "PdfFuzz");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void collect(File file, List<byte[]> seeds, List<String> names) throws IOException {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            java.util.Arrays.sort(children);
            for (File child : children) collect(child, seeds, names);
        } else if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") && file.length() < PdfFile.MAX_SIZE) {
            seeds.add(Files.readAllBytes(file.toPath()));
            names.add(file.getName());
        }
    }

    // 開いて、最初と最後のページを小さく描く。問題がなければ null
    private static String run(byte[] data) {
        final PdfFile pdf;
        try {
            pdf = PdfFile.open(data);
            OPENED.incrementAndGet();
        } catch (IOException e) {
            return unexpectedCause(e);
        }
        try {
            for (int page : new int[]{0, pdf.pageCount() - 1}) {
                try {
                    pdf.render(page, 160);
                    RENDERED.incrementAndGet();
                } catch (IOException e) {
                    final String problem = unexpectedCause(e);
                    if (problem != null) return problem;
                }
            }
        } finally {
            pdf.close();
        }
        return null;
    }

    // IOException の原因が、想定外のもの (スタックの使い切りなど) なら、その説明
    private static String unexpectedCause(IOException e) {
        final Throwable cause = e.getCause();
        if (cause instanceof StackOverflowError) return "stack overflow: " + describe(cause);
        return null;
    }

    private static String describe(Throwable e) {
        final StringBuilder text = new StringBuilder(String.valueOf(e));
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root != e) text.append(" / root ").append(root);
        final StackTraceElement[] trace = root.getStackTrace();
        for (int i = 0; i < Math.min(6, trace.length); i++) text.append("\n    at ").append(trace[i]);
        return text.toString();
    }

    /**
     * 壊し方: バイトを変える、挟む、消す、途中で切る、数を極端な値にする、一部を写す。
     */
    static byte[] mutate(byte[] seed, Random random) {
        byte[] data = seed.clone();
        final int count = 1 + random.nextInt(4);
        for (int n = 0; n < count && data.length > 0; n++) {
            switch (random.nextInt(7)) {
                case 0: // バイトを変える
                    for (int k = 0, flips = 1 + random.nextInt(16); k < flips; k++) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    break;
                case 1: { // 途中で切る
                    data = java.util.Arrays.copyOf(data, Math.max(1, random.nextInt(data.length)));
                    break;
                }
                case 2: { // 消す
                    final int from = random.nextInt(data.length);
                    final int length = Math.min(data.length - from, 1 + random.nextInt(64));
                    final byte[] out = new byte[data.length - length];
                    System.arraycopy(data, 0, out, 0, from);
                    System.arraycopy(data, from + length, out, from, data.length - from - length);
                    data = out.length == 0 ? data : out;
                    break;
                }
                case 3: { // 一部を別の場所に写して挟む
                    final int from = random.nextInt(data.length);
                    final int length = Math.min(data.length - from, 1 + random.nextInt(256));
                    final int to = random.nextInt(data.length + 1);
                    final byte[] out = new byte[data.length + length];
                    System.arraycopy(data, 0, out, 0, to);
                    System.arraycopy(data, from, out, to, length);
                    System.arraycopy(data, to, out, to + length, data.length - to);
                    data = out;
                    break;
                }
                default: // 数を極端な値にする
                    data = replaceNumber(data, random);
                    break;
            }
        }
        return data;
    }

    private static final String[] EXTREMES = {"0", "-1", "1", "2147483647", "-2147483648", "4294967296", "99999999999999999999",
            "1e30", "-0.0", "65535", "65536", "1000000", "3.4e38", "0.000001", "255", "256", "-99999"};

    private static byte[] replaceNumber(byte[] data, Random random) {
        // 数字の並びを探して、極端な値に置き換える
        for (int attempt = 0; attempt < 32; attempt++) {
            int p = random.nextInt(data.length);
            while (p < data.length && !(data[p] >= '0' && data[p] <= '9')) p++;
            if (p >= data.length) continue;
            int start = p;
            while (start > 0 && ((data[start - 1] >= '0' && data[start - 1] <= '9') || data[start - 1] == '.' || data[start - 1] == '-')) start--;
            int end = p;
            while (end < data.length && ((data[end] >= '0' && data[end] <= '9') || data[end] == '.')) end++;
            final byte[] replacement = EXTREMES[random.nextInt(EXTREMES.length)].getBytes(StandardCharsets.ISO_8859_1);
            final byte[] out = new byte[data.length - (end - start) + replacement.length];
            System.arraycopy(data, 0, out, 0, start);
            System.arraycopy(replacement, 0, out, start, replacement.length);
            System.arraycopy(data, end, out, start + replacement.length, data.length - end);
            return out;
        }
        return data;
    }
}
