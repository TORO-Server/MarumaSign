package marumasa.marumasa_sign.core.structure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 壊した構造物のファイルを読ませて、IOException (読めない) で終わり、ほかの例外、長い時間、メモリの使い切りにならないかを確かめる。
 * 引数: 回数 [ファイルまたはフォルダ...] (回数はファイルごと)。ファイルを渡さなければ src/test/resources/structure を使う。
 * <p>
 * 壊し方は 2 種類ある。
 * <ul>
 *     <li>バイト: 展開した中身のビットを変える、切り詰める、範囲を写す、4 バイトを大きな値にする</li>
 *     <li>NBT の形: 一度 NBT として読み、値を極端な数 (NaN、最大値など) や別の種類の値に変える、深く入れ子にする、
 *     リストや配列を増やす・減らす、別の場所の値を写す、看板の文字を変えたうえで、
 *     書き戻すときにリストや配列の長さ、要素の種類を偽る</li>
 * </ul>
 * どちらも元と同じ形 (gzip、zlib、圧縮なし、ビッグエンディアン、リトルエンディアン) に戻して読む。
 * システムプロパティ fuzz.mesh を true にすると、3D モデルにするところまで確かめる (頂点が有限の値かも見る)。
 * そのときに marumasign.assetJar で Minecraft の jar を指定しなければ、ネットワークを使わないように小さな代わりの jar を使う。
 * 乱数の種はシステムプロパティ fuzz.seed で決められる。失敗した入力は build/structure-fuzz に保存する。
 */
public final class StructureFuzz {

    // 1 つの場合の時間の上限。超えたら、止まらない読み込みがロックを持ったままになるので、そこで終える
    private static final long CASE_TIMEOUT_SECONDS = 60;
    // 1 つの場合で、新しく作る値の数の目安の上限 (壊し方でメモリを使い切らないように)
    private static final int MAX_GROWTH = 200000;

    private StructureFuzz() {
    }

    public static void main(String[] args) throws Exception {
        final int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        final List<File> files = new ArrayList<File>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), files);
        if (args.length < 2) collect(new File("src/test/resources/structure"), files);
        if (files.isEmpty()) throw new IllegalArgumentException("No seed files");
        final boolean mesh = Boolean.getBoolean("fuzz.mesh");
        // Minecraft の jar を指定しなければ、ダウンロードせずに小さな代わりの jar を使う (ブロックは紫と黒の模様になるが、形の計算は同じ)
        if (mesh && System.getProperty("marumasign.assetJar") == null) StructureRegressionCheck.useOfflineAssets();
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        System.out.println("StructureFuzz: " + rounds + " rounds x " + files.size() + " files, mesh=" + mesh + ", seed=" + seed);
        final Random random = new Random(seed);
        final File failures = new File("build/structure-fuzz");
        int unexpected = 0, ok = 0, rejected = 0, structural = 0;
        long slowest = 0;
        String slowestName = "";
        ExecutorService executor = newExecutor();
        final long begin = System.nanoTime();
        outer:
        for (File file : files) {
            final byte[] original = Files.readAllBytes(file.toPath());
            final Seed parsed = Seed.of(original);
            for (int round = 0; round < rounds; round++) {
                byte[] raw = null;
                if (parsed.root != null && random.nextInt(3) != 0) {
                    try {
                        raw = mutateTree(parsed, random);
                        structural++;
                    } catch (IOException e) {
                        raw = null;
                    }
                }
                if (raw == null) raw = mutate(parsed.raw, random);
                final byte[] input = parsed.wrap(raw);
                final long start = System.nanoTime();
                String problem = null;
                final Future<String> future = executor.submit(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return run(input, mesh);
                    }
                });
                boolean timedOut = false;
                try {
                    final String result = future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (result == null) {
                        ok++;
                    } else if (result.equals("rejected")) {
                        rejected++;
                    } else {
                        problem = result;
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                    problem = "timeout (" + CASE_TIMEOUT_SECONDS + " s)";
                    timedOut = true;
                } catch (ExecutionException e) {
                    final Throwable cause = e.getCause();
                    problem = cause.toString();
                    if (!(cause instanceof OutOfMemoryError)) {
                        final java.io.StringWriter trace = new java.io.StringWriter();
                        cause.printStackTrace(new java.io.PrintWriter(trace));
                        final String[] lines = trace.toString().split("\n");
                        problem += "\n" + String.join("\n", Arrays.asList(lines).subList(0, Math.min(14, lines.length)));
                    }
                }
                final long millis = (System.nanoTime() - start) / 1000000;
                if (millis > slowest) {
                    slowest = millis;
                    slowestName = file.getName() + " round " + round;
                }
                if (problem != null) {
                    unexpected++;
                    failures.mkdirs();
                    final File saved = new File(failures, "case-" + seed + "-" + round + "-" + file.getName());
                    Files.write(saved.toPath(), input);
                    System.out.println("FAIL " + file.getName() + " round " + round + ": " + problem + " -> " + saved);
                    // 止まらない読み込みは構造物のロックを持ったままなので、ここで終える
                    if (timedOut) break outer;
                }
            }
        }
        executor.shutdownNow();
        System.out.println(files.size() + " files: " + ok + " read, " + rejected + " rejected, " + unexpected + " unexpected errors ("
                + structural + " structure-aware mutations); slowest " + slowest + " ms (" + slowestName + "); "
                + (System.nanoTime() - begin) / 1000000000L + " s");
        System.exit(unexpected > 0 ? 1 : 0);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread thread = new Thread(r, "StructureFuzz");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    // 読む。問題がなければ null、読めなかった (IOException) なら "rejected"、それ以外は問題の説明
    private static String run(byte[] input, boolean mesh) throws Exception {
        try {
            if (mesh) {
                final StructureLoader.Result result = StructureLoader.loadDetailed(input);
                for (float value : result.scene.restBounds()) {
                    if (Float.isNaN(value) || Float.isInfinite(value)) return "non-finite vertex in the model: " + Arrays.toString(result.scene.restBounds());
                }
            } else {
                final Structure structure = StructureReader.read(input);
                StructureFixer.fix(structure);
            }
            return null;
        } catch (IOException e) {
            if (e.getCause() instanceof StackOverflowError) return "stack overflow inside an IOException: " + e;
            return "rejected";
        }
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) collect(child, files);
        } else if (file.isFile() && !file.getName().endsWith(".txt") && !file.getName().endsWith(".py")) {
            files.add(file);
        }
    }

    // ---------- 種 ----------

    // 種のファイル: 展開した中身と、包み方 (0: なし、1: gzip、2: zlib)、NBT として読んだもの (読めなければ null)
    private static final class Seed {
        byte[] raw;
        int wrapping;
        boolean little;
        String rootName;
        Map<String, Object> root;

        static Seed of(byte[] original) throws IOException {
            final Seed seed = new Seed();
            if (original.length > 2 && (original[0] & 0xFF) == 0x1F && (original[1] & 0xFF) == 0x8B) {
                seed.wrapping = 1;
                seed.raw = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(original)));
            } else if (original.length > 2 && (original[0] & 0xFF) == 0x78 && (((original[0] & 0xFF) << 8) | (original[1] & 0xFF)) % 31 == 0) {
                seed.wrapping = 2;
                seed.raw = readAll(new InflaterInputStream(new java.io.ByteArrayInputStream(original)));
            } else {
                seed.raw = original;
            }
            final int head = Math.min(seed.raw.length, 64);
            seed.little = Nbt.looksLikeNbt(seed.raw, head, true) && !Nbt.looksLikeNbt(seed.raw, head, false);
            try {
                final Nbt.Root root = Nbt.read(seed.raw, seed.little);
                seed.rootName = root.name;
                seed.root = root.value;
            } catch (IOException e) {
                seed.root = null;
            }
            return seed;
        }

        byte[] wrap(byte[] data) throws IOException {
            if (wrapping == 0) return data;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final java.io.OutputStream stream = wrapping == 1 ? new GZIPOutputStream(output) : new DeflaterOutputStream(output);
            stream.write(data);
            stream.close();
            return output.toByteArray();
        }
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        input.close();
        return output.toByteArray();
    }

    // ---------- バイトを壊す ----------

    private static byte[] mutate(byte[] data, Random random) {
        final byte[] result = data.clone();
        switch (random.nextInt(4)) {
            case 0:
                // いくつかのバイトを変える
                for (int i = 0; i < 1 + random.nextInt(8) && result.length > 0; i++) result[random.nextInt(result.length)] = (byte) random.nextInt(256);
                return result;
            case 1:
                // 途中で切る
                return Arrays.copyOf(result, result.length == 0 ? 0 : random.nextInt(result.length));
            case 2: {
                // 長さや大きさに使われる 4 バイトを大きな値にする
                if (result.length < 8) return result;
                final int at = random.nextInt(result.length - 4);
                final int value = random.nextBoolean() ? 0x7FFFFFFF : random.nextInt();
                result[at] = (byte) (value >>> 24);
                result[at + 1] = (byte) (value >>> 16);
                result[at + 2] = (byte) (value >>> 8);
                result[at + 3] = (byte) value;
                return result;
            }
            default: {
                // 一部を別の場所に写す
                if (result.length < 16) return result;
                final int length = 1 + random.nextInt(Math.min(64, result.length / 2));
                System.arraycopy(result, random.nextInt(result.length - length), result, random.nextInt(result.length - length), length);
                return result;
            }
        }
    }

    // ---------- NBT の形を壊す ----------

    // 値の置き場所 (複合タグの名前か、リストの番号)
    private static final class Slot {
        final Object container;
        final Object key;

        Slot(Object container, Object key) {
            this.container = container;
            this.key = key;
        }

        @SuppressWarnings("unchecked")
        Object get() {
            return container instanceof Map ? ((Map<String, Object>) container).get(key) : ((List<Object>) container).get((Integer) key);
        }

        @SuppressWarnings("unchecked")
        void set(Object value) {
            if (container instanceof Map) ((Map<String, Object>) container).put((String) key, value);
            else ((List<Object>) container).set((Integer) key, value);
        }

        @SuppressWarnings("unchecked")
        void remove() {
            if (container instanceof Map) ((Map<String, Object>) container).remove(key);
            else ((List<Object>) container).remove((int) (Integer) key);
        }
    }

    private static byte[] mutateTree(Seed seed, Random random) throws IOException {
        @SuppressWarnings("unchecked") final Map<String, Object> root = (Map<String, Object>) copy(seed.root);
        final int count = 1 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            final List<Slot> slots = new ArrayList<Slot>();
            slots(root, slots, 0);
            if (slots.isEmpty()) break;
            mutateSlot(slots.get(random.nextInt(slots.size())), slots, random);
        }
        final Writer writer = new Writer(seed.little, random, random.nextInt(4) == 0 ? 1 + random.nextInt(3) : 0);
        return writer.root(seed.rootName, root);
    }

    // 値をすべて写す (元の種を壊さないように)
    private static Object copy(Object value) {
        if (value instanceof Map) {
            final Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : Nbt.compound(value).entrySet()) result.put(entry.getKey(), copy(entry.getValue()));
            return result;
        }
        if (value instanceof List) {
            final List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(copy(item));
            return result;
        }
        if (value instanceof byte[]) return ((byte[]) value).clone();
        if (value instanceof int[]) return ((int[]) value).clone();
        if (value instanceof long[]) return ((long[]) value).clone();
        return value;
    }

    // 置き場所を集める (多すぎるときは途中まで)
    @SuppressWarnings("unchecked")
    private static void slots(Object value, List<Slot> slots, int depth) {
        if (slots.size() > 20000 || depth > 64) return;
        if (value instanceof Map) {
            for (String key : ((Map<String, Object>) value).keySet()) {
                final Object child = ((Map<String, Object>) value).get(key);
                slots.add(new Slot(value, key));
                slots(child, slots, depth + 1);
            }
        } else if (value instanceof List) {
            final List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                slots.add(new Slot(value, i));
                slots(list.get(i), slots, depth + 1);
            }
        }
    }

    private static final int[] EXTREME_INTS = {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, 2, 3, 15, 16, 255, 256, 4095, 4096, 32767, 32768,
            65535, 65536, 65537, 1 << 20, 1 << 24, (1 << 24) + 1, 1 << 30, -65536, -2, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1};
    private static final double[] EXTREME_DOUBLES = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e30, -1e30, 1e300,
            -0.0, 0, 1e-30, Double.MIN_VALUE, Double.MAX_VALUE, 2147483648.0, -2147483649.0, 65536.5};

    private static void mutateSlot(Slot slot, List<Slot> all, Random random) {
        final Object value = slot.get();
        switch (random.nextInt(10)) {
            case 0:
            case 1:
                slot.set(extreme(value, random));
                break;
            case 2:
                slot.set(otherType(value, random));
                break;
            case 3:
                slot.set(nested(random.nextInt(4) == 0 ? 400 + random.nextInt(300) : 1 + random.nextInt(100), random, value));
                break;
            case 4:
                slot.set(grow(value, random));
                break;
            case 5:
                if (value instanceof List && !((List<?>) value).isEmpty() && random.nextBoolean()) {
                    final List<?> list = (List<?>) value;
                    slot.set(new ArrayList<Object>(list.subList(0, random.nextInt(list.size()))));
                } else if (value instanceof long[] || value instanceof int[] || value instanceof byte[]) {
                    slot.set(shrinkArray(value, random));
                } else {
                    slot.remove();
                }
                break;
            case 6:
                if (value instanceof String || random.nextInt(3) == 0) slot.set(text(random));
                else slot.set(extreme(value, random));
                break;
            case 7: {
                // 別の場所の値を写す (同じ値を何か所からも参照させる)
                final Slot other = all.get(random.nextInt(all.size()));
                final Object source = other.get();
                if (source != null && size(source) < MAX_GROWTH) slot.set(copy(source));
                break;
            }
            case 8:
                slot.set(scramble(value, random));
                break;
            default:
                // 数のリストを有限でない値や大きな値にする (位置や角度)
                if (value instanceof List) {
                    final List<Object> list = new ArrayList<Object>();
                    for (Object item : (List<?>) value) list.add(item instanceof Number ? extreme(item, random) : item);
                    slot.set(list);
                } else {
                    slot.set(extreme(value, random));
                }
                break;
        }
    }

    private static int size(Object value) {
        if (value instanceof Map) {
            int total = 1;
            for (Object child : ((Map<?, ?>) value).values()) total += size(child);
            return total;
        }
        if (value instanceof List) {
            int total = 1;
            for (Object child : (List<?>) value) total += size(child);
            return total;
        }
        if (value instanceof byte[]) return 1 + ((byte[]) value).length / 64;
        if (value instanceof int[]) return 1 + ((int[]) value).length / 16;
        if (value instanceof long[]) return 1 + ((long[]) value).length / 8;
        return 1;
    }

    private static Object extreme(Object value, Random random) {
        final int i = EXTREME_INTS[random.nextInt(EXTREME_INTS.length)];
        final double d = EXTREME_DOUBLES[random.nextInt(EXTREME_DOUBLES.length)];
        if (value instanceof Byte) return (byte) (random.nextBoolean() ? i : random.nextInt(256));
        if (value instanceof Short) return (short) (random.nextBoolean() ? i : random.nextInt(65536));
        if (value instanceof Integer) return random.nextInt(4) == 0 ? (Integer) value + random.nextInt(7) - 3 : i;
        if (value instanceof Long) return random.nextBoolean() ? (long) i : random.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE;
        if (value instanceof Float) return (float) d;
        if (value instanceof Double) return d;
        if (value instanceof int[]) {
            final int[] array = ((int[]) value).clone();
            for (int k = 0; k < 1 + random.nextInt(4) && array.length > 0; k++) array[random.nextInt(array.length)] = i;
            return array;
        }
        if (value instanceof long[]) {
            final long[] array = ((long[]) value).clone();
            for (int k = 0; k < 1 + random.nextInt(4) && array.length > 0; k++) array[random.nextInt(array.length)] = random.nextBoolean() ? -1L : random.nextLong();
            return array;
        }
        if (value instanceof byte[]) {
            final byte[] array = ((byte[]) value).clone();
            for (int k = 0; k < 1 + random.nextInt(8) && array.length > 0; k++) array[random.nextInt(array.length)] = (byte) (random.nextBoolean() ? 0xFF : random.nextInt(256));
            return array;
        }
        if (value instanceof String) return String.valueOf(i);
        return i;
    }

    private static Object otherType(Object value, Random random) {
        switch (random.nextInt(11)) {
            case 0:
                return (byte) random.nextInt(256);
            case 1:
                return (short) random.nextInt(65536);
            case 2:
                return EXTREME_INTS[random.nextInt(EXTREME_INTS.length)];
            case 3:
                return random.nextLong();
            case 4:
                return (float) EXTREME_DOUBLES[random.nextInt(EXTREME_DOUBLES.length)];
            case 5:
                return EXTREME_DOUBLES[random.nextInt(EXTREME_DOUBLES.length)];
            case 6:
                return text(random);
            case 7: {
                final List<Object> list = new ArrayList<Object>();
                for (int i = 0; i < random.nextInt(5); i++) list.add(value instanceof List || value instanceof Map ? copy(value) : EXTREME_INTS[random.nextInt(EXTREME_INTS.length)]);
                return list;
            }
            case 8: {
                final Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("x", value instanceof Map || value instanceof List ? copy(value) : value);
                return map;
            }
            case 9:
                return new int[]{random.nextInt(), random.nextInt(), random.nextInt()};
            default:
                return new long[]{random.nextLong(), -1L};
        }
    }

    // 深く入れ子にした値 (一番内側は元の値)
    private static Object nested(int depth, Random random, Object inner) {
        Object value = inner instanceof Map || inner instanceof List ? "x" : inner;
        for (int i = 0; i < depth; i++) {
            if (random.nextBoolean()) {
                final List<Object> list = new ArrayList<Object>();
                list.add(value);
                value = list;
            } else {
                final Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put(random.nextBoolean() ? "extra" : "text", value);
                value = map;
            }
        }
        return value;
    }

    // リストの要素や配列を増やす
    private static Object grow(Object value, Random random) {
        if (value instanceof List && !((List<?>) value).isEmpty()) {
            final List<?> list = (List<?>) value;
            final int each = Math.max(1, size(list) / list.size());
            final int target = Math.min(MAX_GROWTH / each, list.size() * (2 + random.nextInt(random.nextBoolean() ? 4 : 2000)));
            final List<Object> result = new ArrayList<Object>(list);
            while (result.size() < target) result.add(copy(list.get(random.nextInt(list.size()))));
            return result;
        }
        if (value instanceof long[]) {
            final long[] array = Arrays.copyOf((long[]) value, ((long[]) value).length * 2 + random.nextInt(4096));
            for (int i = 0; i < array.length; i++) if (array[i] == 0) array[i] = random.nextLong();
            return array;
        }
        if (value instanceof int[]) {
            final int[] array = Arrays.copyOf((int[]) value, ((int[]) value).length * 2 + random.nextInt(4096));
            for (int i = 0; i < array.length; i++) if (array[i] == 0) array[i] = random.nextInt(64) - 8;
            return array;
        }
        if (value instanceof byte[]) {
            final byte[] array = Arrays.copyOf((byte[]) value, ((byte[]) value).length * 2 + random.nextInt(65536));
            for (int i = 0; i < array.length; i++) if (array[i] == 0) array[i] = (byte) random.nextInt(256);
            return array;
        }
        if (value instanceof String) {
            final StringBuilder text = new StringBuilder((String) value);
            while (text.length() < 20000) text.append((String) value).append('x');
            return text.toString();
        }
        return value;
    }

    private static Object shrinkArray(Object value, Random random) {
        if (value instanceof long[]) return Arrays.copyOf((long[]) value, random.nextInt(((long[]) value).length + 1));
        if (value instanceof int[]) return Arrays.copyOf((int[]) value, random.nextInt(((int[]) value).length + 1));
        return Arrays.copyOf((byte[]) value, random.nextInt(((byte[]) value).length + 1));
    }

    // 配列や数のリストの中身を入れ替える (並びの番号を範囲の外にするなど)
    private static Object scramble(Object value, Random random) {
        if (value instanceof long[]) {
            final long[] array = ((long[]) value).clone();
            for (int i = 0; i < array.length; i++) if (random.nextInt(4) == 0) array[i] = random.nextLong();
            return array;
        }
        if (value instanceof int[]) {
            final int[] array = ((int[]) value).clone();
            for (int i = 0; i < array.length; i++) if (random.nextInt(4) == 0) array[i] = random.nextInt(4096) - 64;
            return array;
        }
        if (value instanceof byte[]) {
            final byte[] array = ((byte[]) value).clone();
            for (int i = 0; i < array.length; i++) if (random.nextInt(4) == 0) array[i] = (byte) random.nextInt(256);
            return array;
        }
        if (value instanceof List) {
            final List<Object> list = new ArrayList<Object>((List<?>) value);
            java.util.Collections.shuffle(list, random);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Integer && random.nextInt(3) == 0) list.set(i, random.nextInt(4096) - 64);
            }
            return list;
        }
        return extreme(value, random);
    }

    // 看板の文字や名前に使われる文字
    private static String text(Random random) {
        switch (random.nextInt(10)) {
            case 0: {
                // 深く入れ子にした JSON
                final int depth = 1 + random.nextInt(2000);
                final StringBuilder text = new StringBuilder();
                for (int i = 0; i < depth; i++) text.append(random.nextBoolean() ? "[" : "{\"extra\":[");
                return text.toString();
            }
            case 1:
                return "http://127.0.0.1:1/x.png|0|0|0|1|1|0|0|0";
            case 2:
                return "http://127.0.0.1:1/x.nbt|NaN|Infinity|-1e30|1e30|0|0|0|0";
            case 3: {
                final StringBuilder text = new StringBuilder();
                for (int i = 0; i < 1 + random.nextInt(4000); i++) text.append('§').append("0123456789abcdeflr".charAt(random.nextInt(18)));
                return text.toString();
            }
            case 4:
                return "{\"text\":\"a\",\"color\":\"#zzzzzz\",\"bold\":1,\"extra\":[\"b\",{\"translate\":\"c\"},3,true]}";
            case 5:
                return "minecraft:oak_sign[rotation=99,waterlogged=maybe]";
            case 6:
                return "minecraft:" + "a[b=c,d=e]=,[]".substring(random.nextInt(8));
            case 7: {
                final StringBuilder text = new StringBuilder();
                for (int i = 0; i < 1 + random.nextInt(65535); i++) text.append((char) (0x20 + random.nextInt(0x3000)));
                return text.toString();
            }
            case 8:
                return "";
            default:
                return "minecraft:stone";
        }
    }

    // ---------- 書き戻す ----------

    // NBT を書く。lies 回だけ、リストや配列の長さ、要素の種類を偽る
    private static final class Writer {
        private final boolean little;
        private final Random random;
        private int lies;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        Writer(boolean little, Random random, int lies) {
            this.little = little;
            this.random = random;
            this.lies = lies;
        }

        byte[] root(String name, Map<String, Object> root) throws IOException {
            out.writeByte(Nbt.COMPOUND);
            string(name);
            compound(root);
            out.close();
            return bytes.toByteArray();
        }

        private boolean lie() {
            if (lies > 0 && random.nextInt(8) == 0) {
                lies--;
                return true;
            }
            return false;
        }

        private void s16(int value) throws IOException {
            if (little) {
                out.writeByte(value);
                out.writeByte(value >> 8);
            } else {
                out.writeShort(value);
            }
        }

        private void s32(int value) throws IOException {
            out.writeInt(little ? Integer.reverseBytes(value) : value);
        }

        private void s64(long value) throws IOException {
            out.writeLong(little ? Long.reverseBytes(value) : value);
        }

        // 長さ (偽るときは、大きな値、負の値、少しずらした値)
        private void count(int real) throws IOException {
            if (!lie()) {
                s32(real);
                return;
            }
            final int[] fakes = {Integer.MAX_VALUE, Integer.MIN_VALUE, -1, real + 1, real - 1, real * 2 + 1, 1 << 28, 65536};
            s32(fakes[random.nextInt(fakes.length)]);
        }

        private void string(String text) throws IOException {
            final byte[] utf = modifiedUtf8(text);
            s16(Math.min(65535, utf.length));
            out.write(utf, 0, Math.min(65535, utf.length));
        }

        private void compound(Map<String, Object> map) throws IOException {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                final int type = type(entry.getValue());
                out.writeByte(lie() ? random.nextInt(16) : type);
                string(entry.getKey());
                value(type, entry.getValue());
            }
            out.writeByte(Nbt.END);
        }

        @SuppressWarnings("unchecked")
        private void value(int type, Object value) throws IOException {
            switch (type) {
                case Nbt.BYTE:
                    out.writeByte((Byte) value);
                    break;
                case Nbt.SHORT:
                    s16((Short) value);
                    break;
                case Nbt.INT:
                    s32((Integer) value);
                    break;
                case Nbt.LONG:
                    s64((Long) value);
                    break;
                case Nbt.FLOAT:
                    s32(Float.floatToRawIntBits((Float) value));
                    break;
                case Nbt.DOUBLE:
                    s64(Double.doubleToRawLongBits((Double) value));
                    break;
                case Nbt.BYTE_ARRAY: {
                    final byte[] array = (byte[]) value;
                    count(array.length);
                    out.write(array);
                    break;
                }
                case Nbt.STRING:
                    string((String) value);
                    break;
                case Nbt.LIST: {
                    final List<Object> list = (List<Object>) value;
                    final int elementType = list.isEmpty() ? Nbt.END : type(list.get(0));
                    // 種類の違う要素は書けないので除く
                    final List<Object> same = new ArrayList<Object>();
                    for (Object item : list) {
                        if (type(item) == elementType) same.add(item);
                    }
                    out.writeByte(lie() ? random.nextInt(14) : elementType);
                    count(same.size());
                    for (Object item : same) value(elementType, item);
                    break;
                }
                case Nbt.COMPOUND:
                    compound((Map<String, Object>) value);
                    break;
                case Nbt.INT_ARRAY: {
                    final int[] array = (int[]) value;
                    count(array.length);
                    for (int item : array) s32(item);
                    break;
                }
                case Nbt.LONG_ARRAY: {
                    final long[] array = (long[]) value;
                    count(array.length);
                    for (long item : array) s64(item);
                    break;
                }
                default:
                    break;
            }
        }

        private static int type(Object value) {
            if (value instanceof Byte) return Nbt.BYTE;
            if (value instanceof Short) return Nbt.SHORT;
            if (value instanceof Integer) return Nbt.INT;
            if (value instanceof Long) return Nbt.LONG;
            if (value instanceof Float) return Nbt.FLOAT;
            if (value instanceof Double) return Nbt.DOUBLE;
            if (value instanceof byte[]) return Nbt.BYTE_ARRAY;
            if (value instanceof String) return Nbt.STRING;
            if (value instanceof List) return Nbt.LIST;
            if (value instanceof Map) return Nbt.COMPOUND;
            if (value instanceof int[]) return Nbt.INT_ARRAY;
            if (value instanceof long[]) return Nbt.LONG_ARRAY;
            return Nbt.END;
        }

        // DataOutput.writeUTF と同じ「修正 UTF-8」 (長さの制限なし)
        private static byte[] modifiedUtf8(String text) {
            final ByteArrayOutputStream result = new ByteArrayOutputStream(text.length());
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c >= 1 && c < 0x80) {
                    result.write(c);
                } else if (c < 0x800) {
                    result.write(0xC0 | c >> 6);
                    result.write(0x80 | c & 0x3F);
                } else {
                    result.write(0xE0 | c >> 12);
                    result.write(0x80 | c >> 6 & 0x3F);
                    result.write(0x80 | c & 0x3F);
                }
            }
            return result.toByteArray();
        }
    }
}
