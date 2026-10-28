package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 壊したガウシアンスプラッティングのファイル (.splat .ply .spz .sog .ksplat) を読ませて、IOException 以外の例外、長い時間、
 * メモリの使い切り、深すぎる再帰にならないかを確かめる。読めたものは、SplatScene にして並べ替えと頂点の生成、小さな絵まで試す。
 * <p>
 * 引数: 回数 [ファイルまたはフォルダ...]。ファイルを渡さなければ src/test/resources/splat と ../tools/verify/test-splat.splat を種にする。
 * 形式を知ったうえで壊す (gzip や ZIP は展開してから壊して詰め直す、PLY の見出しの数や型を変える、meta.json の数を極端にする) ほか、
 * ファイル全体のバイトも壊す。失敗したものは build/fuzz-failures に保存する。乱数の種はシステムプロパティ fuzz.seed で決められる。
 * fuzz.strict を true にすると、読み込みが受け止めて IOException に包んだ想定外の実行時例外も失敗として扱う。
 */
public final class SplatFuzz {

    private static final long CASE_TIMEOUT_SECONDS = 20;
    private static final int MAX_INPUT = 16 * 1024 * 1024;

    private static final AtomicInteger LOADED = new AtomicInteger();
    private static final Map<String, Integer> WRAPPED = new TreeMap<String, Integer>();

    private SplatFuzz() {
    }

    private static final class Seed {
        final String name;
        final byte[] data;

        Seed(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        String extension() {
            return SplatLoader.extension(name);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "replay".equals(args[0])) {
            // 保存した失敗をもう一度読む: replay ファイル...
            for (int i = 1; i < args.length; i++) {
                final File file = new File(args[i]);
                final Seed seed = new Seed(file.getName(), Files.readAllBytes(file.toPath()));
                final long start = System.nanoTime();
                String problem;
                try {
                    problem = run(seed.data, seed.name, true);
                } catch (Throwable e) {
                    problem = "crash: " + describe(e);
                }
                System.out.println(file.getName() + ": " + (problem == null ? "OK" : problem) + " ("
                        + (System.nanoTime() - start) / 1000000 + " ms)");
            }
            return;
        }
        final int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        final List<Seed> seeds = new ArrayList<Seed>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), seeds);
        if (seeds.isEmpty()) {
            collect(new File("src/test/resources/splat"), seeds);
            collect(new File("../tools/verify/test-splat.splat"), seeds);
        }
        if (seeds.isEmpty()) throw new IllegalArgumentException("No seed files");
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        final boolean strict = Boolean.getBoolean("fuzz.strict");
        System.out.println("SplatFuzz: " + iterations + " iterations over " + seeds.size() + " seeds, seed=" + seed
                + (strict ? ", strict" : ""));
        final Random random = new Random(seed);
        final File failures = new File("build/fuzz-failures");
        int failed = 0;
        ExecutorService executor = newExecutor();
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final Seed source = seeds.get(random.nextInt(seeds.size()));
            byte[] mutated = mutate(source, random);
            if (mutated.length > MAX_INPUT) mutated = mutateBytes(source.data, random);
            final byte[] data = mutated;
            final Future<String> future = executor.submit(new Callable<String>() {
                @Override
                public String call() {
                    return run(data, source.name, strict);
                }
            });
            String problem;
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
                final File file = new File(failures, "splat-" + seed + "-" + i + source.extension());
                Files.write(file.toPath(), data);
                System.out.println("FAIL " + source.name + " #" + i + ": " + problem + " -> " + file);
                if (failed >= 50) {
                    System.out.println("Too many failures; stopping");
                    break;
                }
            }
        }
        executor.shutdownNow();
        System.out.println("SplatFuzz finished in " + (System.nanoTime() - start) / 1000000000L + " s, loaded " + LOADED.get()
                + ", failures: " + failed);
        synchronized (WRAPPED) {
            if (!WRAPPED.isEmpty()) {
                System.out.println("Runtime exceptions wrapped in IOException by the loaders:");
                for (Map.Entry<String, Integer> entry : WRAPPED.entrySet()) {
                    System.out.println("  " + entry.getValue() + " x " + entry.getKey());
                }
            }
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread thread = new Thread(r, "SplatFuzz");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static void collect(File file, List<Seed> seeds) throws IOException {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) collect(child, seeds);
            return;
        }
        final String extension = SplatLoader.extension(file.getName());
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) return;
        if (Arrays.asList(".splat", ".ply", ".spz", ".sog", ".ksplat").contains(extension)) {
            seeds.add(new Seed(file.getName(), Files.readAllBytes(file.toPath())));
        }
    }

    // ---------- 動かす ----------

    // 読み込んで、SplatScene で頂点を作り、小さく描く。問題がなければ null
    private static String run(byte[] data, String name, boolean strict) {
        final SplatCloud cloud;
        try {
            cloud = SplatLoader.load(data, name);
        } catch (IOException e) {
            return wrapped(e, strict);
        }
        LOADED.incrementAndGet();
        final int count = cloud.count;
        if (count < 0 || count > SplatLoader.MAX_SPLATS) return "too many splats: " + count;
        final int rest = SplatCloud.restCoefficients(cloud.shDegree);
        if (cloud.positions.length != count * 3 || cloud.rotations.length != count * 4 || cloud.scales.length != count * 3
                || cloud.opacities.length != count || cloud.colors.length != count * 3
                || cloud.shDegree < 0 || cloud.shDegree > 3 || cloud.shCoefficients.length != (cloud.shDegree > 0 ? count * rest * 3 : 0)) {
            return "inconsistent arrays";
        }
        for (int i = 0; i < count * 3; i++) {
            if (!Float.isFinite(cloud.positions[i]) || !Float.isFinite(cloud.scales[i])) return "non-finite position or scale at " + i / 3;
        }
        final SplatScene scene = new SplatScene(cloud);
        final float[] bounds = scene.restBounds();
        for (float value : bounds) {
            if (!Float.isFinite(value)) return "non-finite bounds";
        }
        final float[] vertices = new float[count * 4 * SignImage.Batch.STRIDE];
        final int[] colors = new int[count * 4];
        final float[] placement = {0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0.5f, 0, 1, 2, 3, 1};
        scene.emit(placement, 0, 0, 10, vertices, colors);
        scene.emit(placement, 1, 2, 3, vertices, colors);
        if (count <= 20000) scene.renderPreview(24);
        return null;
    }

    private static String wrapped(IOException e, boolean strict) {
        Throwable cause = e.getCause();
        while (cause instanceof IOException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof StackOverflowError || cause instanceof OutOfMemoryError) return "error wrapped in IOException: " + describe(cause);
        if (!(cause instanceof RuntimeException) || e.getMessage() == null || !e.getMessage().contains(cause.getClass().getName())) {
            return null;
        }
        final StringBuilder key = new StringBuilder(cause.getClass().getSimpleName());
        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().startsWith("marumasa.")) {
                key.append(" at ").append(element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1))
                        .append('.').append(element.getMethodName()).append(':').append(element.getLineNumber());
                break;
            }
        }
        synchronized (WRAPPED) {
            final Integer count = WRAPPED.get(key.toString());
            WRAPPED.put(key.toString(), count == null ? 1 : count + 1);
        }
        return strict ? "runtime exception wrapped in IOException: " + describe(cause) : null;
    }

    private static String describe(Throwable e) {
        final StringBuilder text = new StringBuilder(String.valueOf(e));
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root != e) text.append(" / root ").append(root);
        final StackTraceElement[] trace = root.getStackTrace();
        for (int i = 0; i < Math.min(8, trace.length); i++) text.append("\n    at ").append(trace[i]);
        return text.toString();
    }

    // ---------- 壊し方 ----------

    private static byte[] mutate(Seed seed, Random random) {
        final String extension = seed.extension();
        try {
            if (random.nextInt(4) != 0) {
                if (".ply".equals(extension)) return mutatePly(seed.data, random);
                if (".spz".equals(extension) && (seed.data[0] & 0xFF) == 0x1F) {
                    // gzip を展開して中身を壊し、詰め直す
                    return gzip(mutateHeaderAndBytes(gunzip(seed.data), random, 16));
                }
                if (".spz".equals(extension)) return mutateHeaderAndBytes(seed.data, random, 40 + 6 * 16);
                if (".sog".equals(extension)) return mutateSog(seed.data, random);
                if (".ksplat".equals(extension)) return mutateHeaderAndBytes(seed.data, random, 4096 + 1024);
            }
        } catch (IOException | RuntimeException e) {
            // 壊し方がうまく当てはまらなかった。下で全体を壊す
        }
        return mutateBytes(seed.data, random);
    }

    // 見出し (先頭の headerSize バイト) の 4 バイトの値を極端にすることを多めにして壊す
    private static byte[] mutateHeaderAndBytes(byte[] data, Random random, int headerSize) {
        if (random.nextBoolean() || data.length < 8) return mutateBytes(data, random);
        final byte[] result = data.clone();
        final int[] values = {0, 1, 2, 3, 4, 5, -1, 0x7FFFFFFF, 0x80000000, 0xFFFF, 0x10000, 1 << 24, 10000000, 150001,
                Float.floatToIntBits(3e38f), Float.floatToIntBits(-3e38f), Float.floatToIntBits(1e20f), 0x7F800000, 0x7FC00000,
                0x7BFF7BFF, random.nextInt()};
        for (int n = 0, count = 1 + random.nextInt(3); n < count; n++) {
            final int at = random.nextInt(Math.max(1, Math.min(headerSize, result.length) - 3)) & (random.nextBoolean() ? ~3 : ~0);
            if (at + 4 > result.length) continue;
            if (random.nextInt(3) == 0) {
                result[at] = (byte) random.nextInt(256);
            } else {
                final int value = values[random.nextInt(values.length)];
                result[at] = (byte) value;
                result[at + 1] = (byte) (value >>> 8);
                result[at + 2] = (byte) (value >>> 16);
                result[at + 3] = (byte) (value >>> 24);
            }
        }
        return random.nextInt(4) == 0 ? mutateBytes(result, random) : result;
    }

    private static final String[] PLY_TYPES = {"char", "uchar", "short", "ushort", "int", "uint", "float", "double", "int8", "float64", "list"};
    private static final String[] PLY_COUNTS = {"0", "1", "2", "-1", "255", "256", "257", "150000", "150001", "50000000", "50000001",
            "2147483647", "99999999999"};

    // PLY の見出しの行 (数、型、名前、形式) を変える。データのバイトも壊すことがある
    private static byte[] mutatePly(byte[] data, Random random) {
        final String text = new String(data, StandardCharsets.ISO_8859_1);
        final int end = text.indexOf("end_header");
        if (end < 0) return mutateBytes(data, random);
        final int bodyStart = text.indexOf('\n', end) + 1;
        final List<String> lines = new ArrayList<String>(Arrays.asList(text.substring(0, bodyStart).split("\n", -1)));
        lines.remove(lines.size() - 1);
        for (int n = 0, count = 1 + random.nextInt(3); n < count; n++) {
            final int index = 1 + random.nextInt(Math.max(1, lines.size() - 2));
            final String line = lines.get(index);
            final String[] tokens = line.trim().split("\\s+");
            switch (random.nextInt(6)) {
                case 0: // 要素の数
                    if ("element".equals(tokens[0]) && tokens.length == 3) {
                        lines.set(index, "element " + tokens[1] + " " + PLY_COUNTS[random.nextInt(PLY_COUNTS.length)]);
                    }
                    break;
                case 1: // プロパティの型
                    if ("property".equals(tokens[0]) && tokens.length == 3) {
                        lines.set(index, "property " + PLY_TYPES[random.nextInt(PLY_TYPES.length)] + " " + tokens[2]);
                    }
                    break;
                case 2: // 行を消すか、繰り返す
                    if (random.nextBoolean()) lines.remove(index);
                    else lines.add(index, line);
                    break;
                case 3: // 形式
                    lines.set(index, "format " + new String[]{"ascii", "binary_little_endian", "binary_big_endian"}[random.nextInt(3)] + " 1.0");
                    break;
                case 4: // 要素を足す
                    lines.add(index, "element extra " + PLY_COUNTS[random.nextInt(PLY_COUNTS.length)]);
                    if (random.nextBoolean()) lines.add(index + 1, "property float extra_value");
                    break;
                default: // プロパティの名前を入れ替える
                    final int other = 1 + random.nextInt(Math.max(1, lines.size() - 2));
                    lines.set(index, lines.get(other));
                    lines.set(other, line);
            }
        }
        final StringBuilder header = new StringBuilder();
        for (String line : lines) header.append(line).append('\n');
        final byte[] head = header.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = Arrays.copyOfRange(data, bodyStart, data.length);
        if (random.nextInt(4) == 0 && body.length > 0) body = mutateBytes(body, random);
        final byte[] result = new byte[head.length + body.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(body, 0, result, head.length, body.length);
        return result;
    }

    private static final double[] EXTREMES = {0, -1, 1, 2, 3, 4, 255, 256, 65535, 65536, 16777216, 16777217, 2147483647,
            4294967297.0, 1e10, -1e10, 1e30, 3.4e38, 1e39, -1e39, 0.5, 1e-45, 88.8, -88.8, 1000};

    // .sog の ZIP を展開して、meta.json の数か、画像のバイトを壊して詰め直す
    @SuppressWarnings("unchecked")
    private static byte[] mutateSog(byte[] data, Random random) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        final ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), readAll(zip));
        } finally {
            zip.close();
        }
        if (entries.isEmpty()) return mutateBytes(data, random);
        final List<String> names = new ArrayList<String>(entries.keySet());
        if (random.nextBoolean() && entries.containsKey("meta.json")) {
            final Object root = Json.parse(new String(entries.get("meta.json"), StandardCharsets.UTF_8));
            final List<Object[]> numbers = new ArrayList<Object[]>();
            final List<List<Object>> lists = new ArrayList<List<Object>>();
            walk(root, numbers, lists, 0);
            for (int n = 0, count = 1 + random.nextInt(3); n < count; n++) {
                switch (random.nextInt(4)) {
                    case 0:
                    case 1:
                        if (numbers.isEmpty()) break;
                        final Object[] slot = numbers.get(random.nextInt(numbers.size()));
                        final double value = EXTREMES[random.nextInt(EXTREMES.length)];
                        if (slot[0] instanceof Map) ((Map<String, Object>) slot[0]).put((String) slot[1], value);
                        else ((List<Object>) slot[0]).set((Integer) slot[1], value);
                        break;
                    case 2:
                        if (lists.isEmpty()) break;
                        final List<Object> list = lists.get(random.nextInt(lists.size()));
                        if (!list.isEmpty()) {
                            if (random.nextBoolean()) list.remove(list.size() - 1);
                            else list.add(list.get(0));
                        }
                        break;
                    default:
                        // 画像の名前を入れ替える
                        for (List<Object> files : lists) {
                            if (!files.isEmpty() && files.get(0) instanceof String && random.nextBoolean()) {
                                files.set(0, names.get(random.nextInt(names.size())));
                            }
                        }
                }
            }
            entries.put("meta.json", Json.write(root).getBytes(StandardCharsets.UTF_8));
        } else {
            final String name = names.get(random.nextInt(names.size()));
            entries.put(name, mutateBytes(entries.get(name), random));
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ZipOutputStream zipOut = new ZipOutputStream(out);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            final ZipEntry zipEntry = new ZipEntry(entry.getKey());
            zipOut.putNextEntry(zipEntry);
            zipOut.write(entry.getValue());
            zipOut.closeEntry();
        }
        zipOut.close();
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object value, List<Object[]> numbers, List<List<Object>> lists, int depth) {
        if (depth > 32) return;
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (entry.getValue() instanceof Double) numbers.add(new Object[]{value, entry.getKey()});
                else walk(entry.getValue(), numbers, lists, depth + 1);
            }
        } else if (value instanceof List) {
            final List<Object> list = (List<Object>) value;
            lists.add(list);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Double) {
                    if (i < 8) numbers.add(new Object[]{list, i});
                } else {
                    walk(list.get(i), numbers, lists, depth + 1);
                }
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_INPUT) throw new IOException("Too large");
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        return readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(data);
        gzip.close();
        return output.toByteArray();
    }

    /**
     * バイト列の壊し方: バイトを変える、途中で切る、消す、写して挟む、4 バイトを極端な値にする。
     */
    static byte[] mutateBytes(byte[] seed, Random random) {
        byte[] data = seed.clone();
        final int count = 1 + random.nextInt(4);
        for (int n = 0; n < count && data.length > 0; n++) {
            switch (random.nextInt(6)) {
                case 0:
                    for (int k = 0, flips = 1 + random.nextInt(16); k < flips; k++) {
                        data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    }
                    break;
                case 1:
                    data = Arrays.copyOf(data, Math.max(1, random.nextInt(data.length)));
                    break;
                case 2: {
                    final int from = random.nextInt(data.length);
                    final int length = Math.min(data.length - from, 1 + random.nextInt(64));
                    final byte[] out = new byte[data.length - length];
                    System.arraycopy(data, 0, out, 0, from);
                    System.arraycopy(data, from + length, out, from, data.length - from - length);
                    if (out.length > 0) data = out;
                    break;
                }
                case 3: {
                    final int from = random.nextInt(data.length);
                    final int length = Math.min(data.length - from, 1 + random.nextInt(256));
                    final int to = random.nextInt(data.length + 1);
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(data, 0, to);
                    out.write(data, from, length);
                    out.write(data, to, data.length - to);
                    data = out.toByteArray();
                    break;
                }
                default: {
                    if (data.length < 4) break;
                    final int at = random.nextBoolean() ? random.nextInt(Math.min(data.length - 3, 160)) : random.nextInt(data.length - 3);
                    final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    final int[] values = {0, 1, -1, 0x7FFFFFFF, 0x80000000, 0xFFFF, 0x10000, 1 << 24, 0x7F800000, 0x7FC00000,
                            Float.floatToIntBits(3e38f), Float.floatToIntBits(-3e38f), random.nextInt()};
                    buffer.putInt(at, values[random.nextInt(values.length)]);
                }
            }
        }
        return data;
    }
}
