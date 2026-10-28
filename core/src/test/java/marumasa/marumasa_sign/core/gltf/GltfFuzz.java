package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.texture.TextureDecoder;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 壊した glTF / GLB と、モデルに入れるテクスチャ (KTX2、DDS) を読ませて、IOException 以外の例外、長い時間、
 * メモリの使い切り、深すぎる再帰にならないかを確かめる。読めたモデルは、姿勢を変えて三角形を出し、小さく描くところまで試す。
 * <p>
 * 引数: 回数 [ファイルまたはフォルダ...]。ファイルを渡さなければ src/test/resources/gltf と ../tools/verify/test-model.glb を種にする。
 * JSON は構造を知ったうえで壊し (数を極端な値にする、番号を範囲の外にする、ノードを循環させる、配列を増やす、拡張を足す)、
 * GLB のバイナリのチャンクとファイル全体のバイトも壊す。失敗したものは build/fuzz-failures に保存する。
 * 乱数の種はシステムプロパティ fuzz.seed で決められる。fuzz.strict を true にすると、
 * 読み込みが受け止めて IOException に包んだ、想定外の実行時例外 (範囲外の参照など) も失敗として扱う。
 */
public final class GltfFuzz {

    // 1 つの場合の時間の上限
    private static final long CASE_TIMEOUT_SECONDS = 20;
    // 外部ファイルとして読む大きさの上限
    private static final int MAX_RESOURCE = 16 * 1024 * 1024;
    // 壊したファイルの大きさの上限
    private static final int MAX_INPUT = 16 * 1024 * 1024;

    private static final AtomicInteger LOADED = new AtomicInteger();
    private static final AtomicInteger DRAWN = new AtomicInteger();
    // 読み込みが IOException に包んだ実行時例外の、種類と場所ごとの数
    private static final Map<String, Integer> WRAPPED = new TreeMap<String, Integer>();

    private GltfFuzz() {
    }

    private static final class Seed {
        final String name;
        final File dir;
        final byte[] data;
        // テクスチャのファイルなら、その MIME タイプ。モデルなら null
        final String texture;
        // モデルの JSON と、GLB のバイナリのチャンク (なければ null)
        final String json;
        final byte[] bin;

        Seed(String name, File dir, byte[] data, String texture, String json, byte[] bin) {
            this.name = name;
            this.dir = dir;
            this.data = data;
            this.texture = texture;
            this.json = json;
            this.bin = bin;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "replay".equals(args[0])) {
            // 保存した失敗をもう一度読む: replay ファイル...
            for (int i = 1; i < args.length; i++) {
                final File file = new File(args[i]);
                final List<Seed> one = new ArrayList<Seed>();
                collect(file, one);
                final Seed seed = one.isEmpty() ? new Seed(file.getName(), file.getParentFile(), Files.readAllBytes(file.toPath()),
                        null, null, null) : one.get(0);
                final long start = System.nanoTime();
                String problem;
                try {
                    problem = run(seed.data, seed, true);
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
            collect(new File("src/test/resources/gltf"), seeds);
            collect(new File("../tools/verify/test-model.glb"), seeds);
        }
        if (seeds.isEmpty()) throw new IllegalArgumentException("No seed models");
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        final boolean strict = Boolean.getBoolean("fuzz.strict");
        System.out.println("GltfFuzz: " + iterations + " iterations over " + seeds.size() + " seeds, seed=" + seed
                + (strict ? ", strict" : ""));
        final Random random = new Random(seed);
        final File failures = new File("build/fuzz-failures");
        int failed = 0;
        ExecutorService executor = newExecutor();
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final Seed source = seeds.get(random.nextInt(seeds.size()));
            byte[] mutated = source.texture != null ? mutateBytes(source.data, random) : mutateModel(source, random);
            // 配列を何度も増やすと大きくなりすぎるので、大きくなったものは使わない (Mod が読む大きさの上限は 64 MB)
            if (mutated.length > MAX_INPUT) mutated = mutateBytes(source.data, random);
            final byte[] data = mutated;
            final Future<String> future = executor.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return run(data, source, strict);
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
                final String extension = source.texture != null ? source.name.substring(source.name.lastIndexOf('.'))
                        : GltfLoader.isGlb(data, data.length) ? ".glb" : ".gltf";
                final File file = new File(failures, "gltf-" + seed + "-" + i + extension);
                Files.write(file.toPath(), data);
                System.out.println("FAIL " + source.name + " #" + i + ": " + problem + " -> " + file
                        + (source.dir != null ? " (external files: " + source.dir + ")" : ""));
                if (failed >= 50) {
                    System.out.println("Too many failures; stopping");
                    break;
                }
            }
        }
        executor.shutdownNow();
        System.out.println("GltfFuzz finished in " + (System.nanoTime() - start) / 1000000000L + " s, loaded " + LOADED.get()
                + ", drawn " + DRAWN.get() + ", failures: " + failed);
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
                final Thread thread = new Thread(r, "GltfFuzz");
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
        final String name = file.getName().toLowerCase(Locale.ROOT);
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) return;
        final byte[] data = Files.readAllBytes(file.toPath());
        if (name.endsWith(".ktx2")) {
            seeds.add(new Seed(file.getName(), null, data, "image/ktx2", null, null));
        } else if (name.endsWith(".dds")) {
            seeds.add(new Seed(file.getName(), null, data, "image/vnd-ms.dds", null, null));
        } else if (name.endsWith(".glb") && GltfLoader.isGlb(data, data.length)) {
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            String json = null;
            byte[] bin = null;
            int pos = 12;
            while (pos + 8 <= data.length) {
                final int length = buffer.getInt(pos);
                final int type = buffer.getInt(pos + 4);
                pos += 8;
                if (length < 0 || pos + (long) length > data.length) break;
                if (type == 0x4E4F534A && json == null) json = new String(data, pos, length, StandardCharsets.UTF_8);
                else if (type == 0x004E4942 && bin == null) bin = Arrays.copyOfRange(data, pos, pos + length);
                pos += (length + 3) & ~3;
            }
            if (json != null) seeds.add(new Seed(file.getName(), file.getParentFile(), data, null, json, bin));
        } else if (name.endsWith(".gltf")) {
            seeds.add(new Seed(file.getName(), file.getParentFile(), data, null, new String(data, StandardCharsets.UTF_8), null));
        }
    }

    // ---------- 動かす ----------

    // 読み込んで、姿勢を変えて三角形を出し、小さく描く。問題がなければ null
    private static String run(byte[] data, Seed seed, boolean strict) {
        try {
            if (seed.texture != null) {
                final BufferedImage image = TextureDecoder.decode(data, seed.texture);
                if (image != null && (image.getWidth() > 8192 || image.getHeight() > 8192)) {
                    return "texture larger than the limit: " + image.getWidth() + "x" + image.getHeight();
                }
                return null;
            }
            final GltfModel model = GltfLoader.load(data, resources(seed.dir));
            LOADED.incrementAndGet();
            if (model.triangleCount() > GltfLoader.MAX_TRIANGLES) return "too many triangles: " + model.triangleCount();
            final GltfScene scene = new GltfScene(model);
            scene.restBounds();
            final GltfScene.Group[] groups = scene.groups();
            final float[][] vertices = new float[groups.length][];
            final int[][] colors = new int[groups.length][];
            for (int i = 0; i < groups.length; i++) {
                vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
                colors[i] = new int[groups[i].vertexCount()];
            }
            final float[] placement = {0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, -0.5f, 0, 1, 2, 3, 1};
            for (float time : new float[]{0f, 0.37f, 1.5f, 1e7f, -3f}) {
                scene.pose(time);
                scene.emit(placement, vertices, colors);
            }
            GltfPreview.render(scene, 0.25f, 24);
            DRAWN.incrementAndGet();
            return null;
        } catch (IOException e) {
            return wrapped(e, strict);
        }
    }

    // IOException の原因が想定外の実行時例外なら数えて、strict ならその説明を返す。スタックの使い切りは常に失敗
    private static String wrapped(IOException e, boolean strict) {
        Throwable cause = e.getCause();
        while (cause instanceof IOException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof StackOverflowError || cause instanceof OutOfMemoryError) return "error wrapped in IOException: " + describe(cause);
        // 読み込みが「想定外」として包んだもの (メッセージに元の例外が入っている) だけを数える。
        // base64 の誤りのように、読み込みが自分で確かめて IOException にしたものは数えない
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

    private static GltfLoader.Resources resources(final File dir) {
        return new GltfLoader.Resources() {
            @Override
            public byte[] load(String uri) throws IOException {
                if (dir == null || uri.contains("..") || uri.startsWith("/") || uri.indexOf(':') >= 0 || uri.indexOf('\\') >= 0) {
                    throw new IOException("Unsupported resource location: " + uri);
                }
                final File file = new File(dir, java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8"));
                if (!file.isFile() || file.length() > MAX_RESOURCE) throw new IOException("Missing resource: " + uri);
                return Files.readAllBytes(file.toPath());
            }
        };
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

    private static byte[] mutateModel(Seed seed, Random random) {
        final int how = random.nextInt(100);
        if (how < 65) {
            // JSON の構造を壊す。バイナリのチャンクも少し壊すことがある
            Object root;
            try {
                root = Json.parse(seed.json);
            } catch (IOException e) {
                return mutateBytes(seed.data, random);
            }
            final int count = 1 + random.nextInt(random.nextInt(4) == 0 ? 12 : 4);
            // 同じものを何度も参照する配列を重ねて増やすと、文字にしたときにとても大きくなる。そうなったら使わない
            for (int i = 0; i < count; i++) {
                mutateJson(root, random);
                if (values(root, new int[]{2000000}) < 0) return mutateBytes(seed.data, random);
            }
            byte[] bin = seed.bin;
            if (bin != null && random.nextInt(5) == 0) bin = mutateBytes(bin, random);
            final String json = Json.write(root);
            return seed.bin != null || GltfLoader.isGlb(seed.data, seed.data.length) ? glb(json, bin) : json.getBytes(StandardCharsets.UTF_8);
        }
        if (how < 85 && seed.bin != null) {
            // バイナリのチャンク (頂点、画像、圧縮したデータ) だけを壊す
            return glb(seed.json, mutateBytes(seed.bin, random));
        }
        if (how < 85) {
            // .gltf の data URI の中身を壊す
            try {
                final Object root = Json.parse(seed.json);
                final List<Map<String, Object>> uris = new ArrayList<Map<String, Object>>();
                for (String collection : new String[]{"buffers", "images"}) {
                    for (Object item : Json.array(Json.object(root), collection)) {
                        final Object uri = Json.object(item).get("uri");
                        if (uri instanceof String && ((String) uri).startsWith("data:") && ((String) uri).contains(";base64,")) {
                            uris.add(Json.object(item));
                        }
                    }
                }
                if (!uris.isEmpty()) {
                    final Map<String, Object> item = uris.get(random.nextInt(uris.size()));
                    final String uri = (String) item.get("uri");
                    final int comma = uri.indexOf(',');
                    final byte[] content = Base64.getMimeDecoder().decode(uri.substring(comma + 1));
                    item.put("uri", uri.substring(0, comma + 1) + Base64.getEncoder().encodeToString(mutateBytes(content, random)));
                    return Json.write(root).getBytes(StandardCharsets.UTF_8);
                }
            } catch (IOException | IllegalArgumentException e) {
                // 下で全体を壊す
            }
        }
        return mutateBytes(seed.data, random);
    }

    private static byte[] glb(String json, byte[] bin) {
        final byte[] text = json.getBytes(StandardCharsets.UTF_8);
        final int jsonLength = (text.length + 3) & ~3;
        final int binLength = bin == null ? 0 : (bin.length + 3) & ~3;
        final int total = 12 + 8 + jsonLength + (bin == null ? 0 : 8 + binLength);
        final ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(jsonLength).putInt(0x4E4F534A).put(text);
        for (int i = text.length; i < jsonLength; i++) out.put((byte) ' ');
        if (bin != null) {
            out.putInt(binLength).putInt(0x004E4942).put(bin);
            for (int i = bin.length; i < binLength; i++) out.put((byte) 0);
        }
        return out.array();
    }

    private static final Object[] EXTREMES = {0.0, -1.0, 1.0, 2.0, 3.0, 4.0, 5.0, 16.0, 255.0, 256.0, 65535.0, 65536.0, 1048576.0,
            16777216.0, 268435456.0, 2147483647.0, -2147483648.0, 2147483648.0, 4294967295.0, 4294967297.0, 1e10, -1e10, 1e30,
            -1e30, 3.4e38, 1e39, -1e39, 1e300, 1e-45, 0.5, -0.5, 0.0001, 33071.0, 33648.0, 10497.0, 5120.0, 5121.0, 5122.0,
            5123.0, 5125.0, 5126.0};

    private static final String[] INDEX_KEYS = {"bufferView", "buffer", "indices", "POSITION", "NORMAL", "TANGENT", "TEXCOORD_0",
            "TEXCOORD_1", "COLOR_0", "JOINTS_0", "WEIGHTS_0", "JOINTS_1", "WEIGHTS_1", "input", "output", "inverseBindMatrices",
            "TRANSLATION", "ROTATION", "SCALE", "material", "mesh", "node", "skin", "sampler", "source", "index", "camera",
            "light", "scene", "texCoord", "skeleton"};

    private static final String[][] ENUMS = {
            {"type", "SCALAR", "VEC2", "VEC3", "VEC4", "MAT2", "MAT3", "MAT4", "VEC5"},
            {"interpolation", "LINEAR", "STEP", "CUBICSPLINE", "CUBIC"},
            {"alphaMode", "OPAQUE", "MASK", "BLEND"},
            {"path", "translation", "rotation", "scale", "weights", "pointer"},
            {"mimeType", "image/png", "image/jpeg", "image/ktx2", "image/webp", "image/vnd-ms.dds", "image/avif", "image/gif"},
            {"mode", "ATTRIBUTES", "TRIANGLES", "INDICES", "OTHER"},
            {"filter", "NONE", "OCTAHEDRAL", "QUATERNION", "EXPONENTIAL", "COLOR", "OTHER"},
            {"pointer", "/nodes/0/translation", "/nodes/0/rotation", "/nodes/1/scale", "/nodes/0/weights",
                    "/materials/0/pbrMetallicRoughness/baseColorFactor", "/materials/0/emissiveFactor",
                    "/materials/0/pbrMetallicRoughness/metallicFactor", "/materials/0/pbrMetallicRoughness/roughnessFactor",
                    "/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset",
                    "/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/scale",
                    "/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/rotation",
                    "/extensions/KHR_lights_punctual/lights/0/color", "/extensions/KHR_lights_punctual/lights/0/intensity",
                    "/materials/99999999999/x", "/nodes/-1/rotation", "nodes"},
            {"uri", "missing.bin", "data:application/octet-stream;base64,AAAA", "data:,abc", "data:;base64,!!!", "../x.bin",
                    "data:image/png;base64,iVBORw0KGgo="},
    };

    @SuppressWarnings("unchecked")
    private static void mutateJson(Object rootObject, Random random) {
        if (!(rootObject instanceof Map)) return;
        final Map<String, Object> root = (Map<String, Object>) rootObject;
        final List<Object[]> numbers = new ArrayList<Object[]>();
        final List<Map<String, Object>> maps = new ArrayList<Map<String, Object>>();
        final List<List<Object>> lists = new ArrayList<List<Object>>();
        walk(root, numbers, maps, lists, 0, new int[]{200000});
        switch (random.nextInt(12)) {
            case 0:
            case 1: { // 数を極端な値にする
                if (numbers.isEmpty()) return;
                final Object[] slot = numbers.get(random.nextInt(numbers.size()));
                final double old = ((Double) get(slot)).doubleValue();
                final double value;
                switch (random.nextInt(4)) {
                    case 0:
                        value = old + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(4));
                        break;
                    case 1:
                        value = old * (random.nextBoolean() ? 1000 : -1);
                        break;
                    default:
                        value = (Double) EXTREMES[random.nextInt(EXTREMES.length)];
                }
                set(slot, value);
                return;
            }
            case 2: { // 番号を範囲の外や別のものにする
                final List<Object[]> indices = new ArrayList<Object[]>();
                for (Object[] slot : numbers) {
                    if (slot[0] instanceof Map && Arrays.asList(INDEX_KEYS).contains(slot[1])) indices.add(slot);
                }
                if (indices.isEmpty()) return;
                final Object[] slot = indices.get(random.nextInt(indices.size()));
                final int limit = 2 + Math.max(Json.array(root, "accessors").size(), Json.array(root, "nodes").size());
                set(slot, (double) (random.nextInt(limit + 4) - 2));
                return;
            }
            case 3: { // ノードを循環させる、親を 2 つにする、シーンに子を入れる
                final List<Object> nodes = Json.array(root, "nodes");
                if (nodes.isEmpty()) return;
                final Map<String, Object> a = Json.object(nodes.get(random.nextInt(nodes.size())));
                if (a.isEmpty()) return;
                final double b = random.nextInt(nodes.size());
                final Object children = a.get("children");
                final List<Object> list = children instanceof List ? (List<Object>) children : new ArrayList<Object>();
                list.add(b);
                if (random.nextBoolean()) list.add(b);
                a.put("children", list);
                if (random.nextBoolean()) {
                    final Map<String, Object> other = Json.object(nodes.get((int) b));
                    final List<Object> back = other.get("children") instanceof List ? (List<Object>) other.get("children") : new ArrayList<Object>();
                    back.add((double) nodes.indexOf(a));
                    if (!other.isEmpty()) other.put("children", back);
                }
                if (random.nextBoolean()) {
                    for (Object scene : Json.array(root, "scenes")) {
                        final Object sceneNodes = Json.object(scene).get("nodes");
                        if (sceneNodes instanceof List) ((List<Object>) sceneNodes).add(b);
                    }
                }
                return;
            }
            case 4: { // 配列を増やす (同じものを何度も参照する)
                if (lists.isEmpty()) return;
                final List<Object> list = lists.get(random.nextInt(lists.size()));
                if (list.isEmpty()) return;
                final int copies = random.nextInt(3) == 0 ? 1 + random.nextInt(3000) : 1 + random.nextInt(20);
                final int size = list.size();
                for (int i = 0; i < copies && list.size() < 70000; i++) list.add(list.get(random.nextInt(size)));
                return;
            }
            case 5: { // 鍵を消す
                if (maps.isEmpty()) return;
                final Map<String, Object> map = maps.get(random.nextInt(maps.size()));
                if (map.isEmpty()) return;
                final List<String> keys = new ArrayList<String>(map.keySet());
                map.remove(keys.get(random.nextInt(keys.size())));
                return;
            }
            case 6: { // 決まった文字列を別のものにする
                final String[] choices = ENUMS[random.nextInt(ENUMS.length)];
                final List<Map<String, Object>> holders = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> map : maps) if (map.containsKey(choices[0])) holders.add(map);
                if (holders.isEmpty()) return;
                holders.get(random.nextInt(holders.size())).put(choices[0], choices[1 + random.nextInt(choices.length - 1)]);
                return;
            }
            case 7: { // 値の型を変える
                if (maps.isEmpty()) return;
                final Map<String, Object> map = maps.get(random.nextInt(maps.size()));
                if (map.isEmpty()) return;
                final List<String> keys = new ArrayList<String>(map.keySet());
                final Object[] values = {null, "x", Boolean.TRUE, new ArrayList<Object>(), new LinkedHashMap<String, Object>(), -1.0,
                        new ArrayList<Object>(Arrays.asList((Object) 1e39, -1e39, 0.0))};
                map.put(keys.get(random.nextInt(keys.size())), values[random.nextInt(values.length)]);
                return;
            }
            default:
                addFeature(root, random);
        }
    }

    // 拡張や機能を足す (参照する番号はでたらめ)
    @SuppressWarnings("unchecked")
    private static void addFeature(Map<String, Object> root, Random random) {
        final int accessors = Math.max(1, Json.array(root, "accessors").size());
        final int views = Math.max(1, Json.array(root, "bufferViews").size());
        final List<Object> nodes = Json.array(root, "nodes");
        final List<Map<String, Object>> primitives = new ArrayList<Map<String, Object>>();
        for (Object mesh : Json.array(root, "meshes")) {
            for (Object primitive : Json.array(Json.object(mesh), "primitives")) {
                if (primitive instanceof Map) primitives.add((Map<String, Object>) primitive);
            }
        }
        switch (random.nextInt(13)) {
            case 0: { // KHR_texture_transform
                final List<Map<String, Object>> infos = new ArrayList<Map<String, Object>>();
                collectTextureInfos(root, infos, 0);
                if (infos.isEmpty()) return;
                final Map<String, Object> transform = new LinkedHashMap<String, Object>();
                transform.put("offset", list(pick(random), pick(random)));
                transform.put("scale", list(pick(random), pick(random)));
                transform.put("rotation", pick(random));
                if (random.nextBoolean()) transform.put("texCoord", (double) random.nextInt(3));
                final Map<String, Object> extensions = new LinkedHashMap<String, Object>();
                extensions.put("KHR_texture_transform", transform);
                infos.get(random.nextInt(infos.size())).put("extensions", extensions);
                return;
            }
            case 1: { // EXT_mesh_gpu_instancing
                if (nodes.isEmpty()) return;
                final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
                for (String name : new String[]{"TRANSLATION", "ROTATION", "SCALE"}) {
                    if (random.nextBoolean()) attributes.put(name, (double) random.nextInt(accessors));
                }
                final Map<String, Object> instancing = new LinkedHashMap<String, Object>();
                instancing.put("attributes", attributes);
                final Map<String, Object> extensions = new LinkedHashMap<String, Object>();
                extensions.put("EXT_mesh_gpu_instancing", instancing);
                final Map<String, Object> node = Json.object(nodes.get(random.nextInt(nodes.size())));
                if (!node.isEmpty()) {
                    node.put("extensions", extensions);
                    if (!node.containsKey("mesh")) node.put("mesh", 0.0);
                }
                return;
            }
            case 2: { // 疎なアクセサ
                final List<Object> list = Json.array(root, "accessors");
                if (list.isEmpty()) return;
                final Map<String, Object> accessor = Json.object(list.get(random.nextInt(list.size())));
                final Map<String, Object> indices = new LinkedHashMap<String, Object>();
                indices.put("bufferView", (double) random.nextInt(views));
                indices.put("componentType", (double) new int[]{5121, 5123, 5125, 5126}[random.nextInt(4)]);
                if (random.nextBoolean()) indices.put("byteOffset", (double) random.nextInt(64));
                final Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("bufferView", (double) random.nextInt(views));
                final Map<String, Object> sparse = new LinkedHashMap<String, Object>();
                sparse.put("count", (double) (random.nextBoolean() ? 1 + random.nextInt(8) : pick(random)));
                sparse.put("indices", indices);
                sparse.put("values", values);
                if (!accessor.isEmpty()) {
                    accessor.put("sparse", sparse);
                    if (random.nextBoolean()) accessor.remove("bufferView");
                }
                return;
            }
            case 3: { // モーフィングの目標と重み
                if (primitives.isEmpty()) return;
                final Map<String, Object> primitive = primitives.get(random.nextInt(primitives.size()));
                final List<Object> targets = new ArrayList<Object>();
                for (int i = 0, n = 1 + random.nextInt(random.nextInt(4) == 0 ? 200 : 3); i < n; i++) {
                    final Map<String, Object> target = new LinkedHashMap<String, Object>();
                    target.put("POSITION", (double) random.nextInt(accessors));
                    if (random.nextBoolean()) target.put("NORMAL", (double) random.nextInt(accessors));
                    targets.add(target);
                }
                primitive.put("targets", targets);
                if (!nodes.isEmpty()) {
                    final Map<String, Object> node = Json.object(nodes.get(random.nextInt(nodes.size())));
                    if (!node.isEmpty()) node.put("weights", list(pick(random), 0.5, 1e39));
                }
                return;
            }
            case 4: { // アニメーション
                final List<Object> animations = root.get("animations") instanceof List ? (List<Object>) root.get("animations") : new ArrayList<Object>();
                final Map<String, Object> sampler = new LinkedHashMap<String, Object>();
                sampler.put("input", (double) random.nextInt(accessors));
                sampler.put("output", (double) random.nextInt(accessors));
                sampler.put("interpolation", new String[]{"LINEAR", "STEP", "CUBICSPLINE"}[random.nextInt(3)]);
                final Map<String, Object> target = new LinkedHashMap<String, Object>();
                if (random.nextBoolean()) {
                    target.put("node", (double) random.nextInt(Math.max(1, nodes.size())));
                    target.put("path", new String[]{"translation", "rotation", "scale", "weights"}[random.nextInt(4)]);
                } else {
                    final Map<String, Object> pointer = new LinkedHashMap<String, Object>();
                    pointer.put("pointer", ENUMS[7][1 + random.nextInt(ENUMS[7].length - 1)]);
                    final Map<String, Object> extensions = new LinkedHashMap<String, Object>();
                    extensions.put("KHR_animation_pointer", pointer);
                    target.put("path", "pointer");
                    target.put("extensions", extensions);
                }
                final Map<String, Object> channel = new LinkedHashMap<String, Object>();
                channel.put("sampler", 0.0);
                channel.put("target", target);
                final Map<String, Object> animation = new LinkedHashMap<String, Object>();
                animation.put("samplers", new ArrayList<Object>(Arrays.asList((Object) sampler)));
                animation.put("channels", new ArrayList<Object>(Arrays.asList((Object) channel)));
                animations.add(0, animation);
                root.put("animations", animations);
                return;
            }
            case 5: { // スキン
                if (nodes.isEmpty()) return;
                final List<Object> skins = root.get("skins") instanceof List ? (List<Object>) root.get("skins") : new ArrayList<Object>();
                final Map<String, Object> skin = new LinkedHashMap<String, Object>();
                final List<Object> joints = new ArrayList<Object>();
                for (int i = 0, n = 1 + random.nextInt(random.nextInt(4) == 0 ? 2000 : 4); i < n; i++) {
                    joints.add((double) (random.nextInt(nodes.size() + 2) - 1));
                }
                skin.put("joints", joints);
                if (random.nextBoolean()) skin.put("inverseBindMatrices", (double) random.nextInt(accessors));
                skins.add(skin);
                root.put("skins", skins);
                final Map<String, Object> node = Json.object(nodes.get(random.nextInt(nodes.size())));
                if (!node.isEmpty()) node.put("skin", (double) (skins.size() - 1));
                if (!primitives.isEmpty()) {
                    final Map<String, Object> attributes = Json.object(primitives.get(random.nextInt(primitives.size())), "attributes");
                    if (!attributes.isEmpty()) {
                        attributes.put("JOINTS_0", (double) random.nextInt(accessors));
                        attributes.put("WEIGHTS_0", (double) random.nextInt(accessors));
                    }
                }
                return;
            }
            case 6: { // 光源をたくさん置く
                final Map<String, Object> extensions = root.get("extensions") instanceof Map ? (Map<String, Object>) root.get("extensions")
                        : new LinkedHashMap<String, Object>();
                final List<Object> lights = new ArrayList<Object>();
                final String[] types = {"point", "spot", "directional"};
                for (int i = 0, n = 1 + random.nextInt(8); i < n; i++) {
                    final Map<String, Object> light = new LinkedHashMap<String, Object>();
                    light.put("type", types[random.nextInt(3)]);
                    light.put("intensity", pick(random));
                    light.put("range", pick(random));
                    lights.add(light);
                }
                final Map<String, Object> punctual = new LinkedHashMap<String, Object>();
                punctual.put("lights", lights);
                extensions.put("KHR_lights_punctual", punctual);
                root.put("extensions", extensions);
                final List<Object> nodeList = root.get("nodes") instanceof List ? (List<Object>) root.get("nodes") : new ArrayList<Object>();
                for (int i = 0, n = random.nextInt(4) == 0 ? 1 + random.nextInt(3000) : 1 + random.nextInt(4); i < n; i++) {
                    final Map<String, Object> light = new LinkedHashMap<String, Object>();
                    light.put("light", (double) random.nextInt(lights.size()));
                    final Map<String, Object> nodeExtensions = new LinkedHashMap<String, Object>();
                    nodeExtensions.put("KHR_lights_punctual", light);
                    final Map<String, Object> node = new LinkedHashMap<String, Object>();
                    node.put("extensions", nodeExtensions);
                    node.put("translation", list(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()));
                    nodeList.add(node);
                }
                root.put("nodes", nodeList);
                root.remove("scenes");
                return;
            }
            case 7: { // meshopt で圧縮したバッファビュー
                final List<Object> list = Json.array(root, "bufferViews");
                if (list.isEmpty()) return;
                final Map<String, Object> view = Json.object(list.get(random.nextInt(list.size())));
                final Map<String, Object> meshopt = new LinkedHashMap<String, Object>();
                meshopt.put("buffer", 0.0);
                meshopt.put("byteOffset", (double) random.nextInt(256));
                meshopt.put("byteLength", (double) random.nextInt(4096));
                meshopt.put("byteStride", (double) (4 * (1 + random.nextInt(8))));
                meshopt.put("count", random.nextBoolean() ? (double) random.nextInt(5000) : pick(random));
                meshopt.put("mode", ENUMS[5][1 + random.nextInt(3)]);
                meshopt.put("filter", ENUMS[6][1 + random.nextInt(5)]);
                final Map<String, Object> extensions = new LinkedHashMap<String, Object>();
                extensions.put(random.nextBoolean() ? "EXT_meshopt_compression" : "KHR_meshopt_compression", meshopt);
                if (!view.isEmpty()) view.put("extensions", extensions);
                return;
            }
            case 8: { // Draco
                if (primitives.isEmpty()) return;
                final Map<String, Object> draco = new LinkedHashMap<String, Object>();
                draco.put("bufferView", (double) random.nextInt(views));
                final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
                attributes.put("POSITION", (double) random.nextInt(4));
                if (random.nextBoolean()) attributes.put("TEXCOORD_0", (double) random.nextInt(4));
                draco.put("attributes", attributes);
                final Map<String, Object> extensions = new LinkedHashMap<String, Object>();
                extensions.put("KHR_draco_mesh_compression", draco);
                primitives.get(random.nextInt(primitives.size())).put("extensions", extensions);
                return;
            }
            case 9: { // 材質の拡張 (焼き込みを起こす)
                final List<Object> materials = Json.array(root, "materials");
                if (materials.isEmpty()) return;
                final Map<String, Object> material = Json.object(materials.get(random.nextInt(materials.size())));
                if (material.isEmpty()) return;
                final int textures = Math.max(1, Json.array(root, "textures").size());
                final Map<String, Object> extensions = material.get("extensions") instanceof Map ? (Map<String, Object>) material.get("extensions")
                        : new LinkedHashMap<String, Object>();
                final String[][] choices = {
                        {"KHR_materials_clearcoat", "clearcoatFactor", "clearcoatTexture"},
                        {"KHR_materials_sheen", "sheenRoughnessFactor", "sheenColorTexture"},
                        {"KHR_materials_transmission", "transmissionFactor", "transmissionTexture"},
                        {"KHR_materials_volume", "thicknessFactor", "thicknessTexture"},
                        {"KHR_materials_iridescence", "iridescenceFactor", "iridescenceThicknessTexture"},
                        {"KHR_materials_anisotropy", "anisotropyStrength", "anisotropyTexture"},
                        {"KHR_materials_specular", "specularFactor", "specularColorTexture"},
                        {"KHR_materials_diffuse_transmission", "diffuseTransmissionFactor", "diffuseTransmissionTexture"},
                        {"KHR_materials_pbrSpecularGlossiness", "glossinessFactor", "specularGlossinessTexture"},
                };
                final String[] choice = choices[random.nextInt(choices.length)];
                final Map<String, Object> extension = new LinkedHashMap<String, Object>();
                extension.put(choice[1], random.nextBoolean() ? random.nextDouble() : pick(random));
                final Map<String, Object> info = new LinkedHashMap<String, Object>();
                info.put("index", (double) random.nextInt(textures));
                extension.put(choice[2], info);
                extensions.put(choice[0], extension);
                material.put("extensions", extensions);
                if (random.nextBoolean()) {
                    // 遮蔽のテクスチャだけを持つ材質
                    final Map<String, Object> occlusion = new LinkedHashMap<String, Object>();
                    occlusion.put("index", (double) random.nextInt(textures));
                    material.clear();
                    material.put("occlusionTexture", occlusion);
                    material.put("extensions", extensions);
                }
                return;
            }
            case 10: { // ノードの変換を極端にする
                if (nodes.isEmpty()) return;
                final Map<String, Object> node = Json.object(nodes.get(random.nextInt(nodes.size())));
                if (node.isEmpty()) return;
                switch (random.nextInt(4)) {
                    case 0:
                        final List<Object> matrix = new ArrayList<Object>();
                        for (int i = 0; i < 16; i++) matrix.add(random.nextBoolean() ? pick(random) : (double) (i % 5 == 0 ? 1 : 0));
                        node.put("matrix", matrix);
                        break;
                    case 1:
                        node.put("translation", list(pick(random), pick(random), pick(random)));
                        break;
                    case 2:
                        node.put("rotation", list(pick(random), pick(random), pick(random), pick(random)));
                        break;
                    default:
                        node.put("scale", list(pick(random), pick(random), pick(random)));
                }
                return;
            }
            case 11: { // 画像の参照を変える
                final List<Object> images = Json.array(root, "images");
                if (images.isEmpty()) return;
                final Map<String, Object> image = Json.object(images.get(random.nextInt(images.size())));
                if (image.isEmpty()) return;
                if (random.nextBoolean()) {
                    image.remove("bufferView");
                    image.put("uri", ENUMS[8][1 + random.nextInt(ENUMS[8].length - 1)]);
                } else {
                    image.put("bufferView", (double) random.nextInt(views));
                    image.remove("uri");
                }
                return;
            }
            default: { // 描き方 (帯、扇、点) を変える
                if (primitives.isEmpty()) return;
                primitives.get(random.nextInt(primitives.size())).put("mode", (double) random.nextInt(8));
            }
        }
    }

    // 文字にしたときの値の数を数える (同じものを何度も参照していれば、その回数だけ数える)。budget を超えたら負の数
    @SuppressWarnings("unchecked")
    private static int values(Object value, int[] budget) {
        if (--budget[0] < 0) return -1;
        if (value instanceof Map) {
            for (Object child : ((Map<String, Object>) value).values()) {
                if (values(child, budget) < 0) return -1;
            }
        } else if (value instanceof List) {
            for (Object child : (List<Object>) value) {
                if (values(child, budget) < 0) return -1;
            }
        }
        return budget[0];
    }

    private static double pick(Random random) {
        return (Double) EXTREMES[random.nextInt(EXTREMES.length)];
    }

    private static List<Object> list(double... values) {
        final List<Object> list = new ArrayList<Object>();
        for (double value : values) list.add(value);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static void collectTextureInfos(Object value, List<Map<String, Object>> out, int depth) {
        if (depth > 32) return;
        if (value instanceof Map) {
            final Map<String, Object> map = (Map<String, Object>) value;
            if (map.get("index") instanceof Double) out.add(map);
            for (Object child : map.values()) collectTextureInfos(child, out, depth + 1);
        } else if (value instanceof List) {
            for (Object child : (List<Object>) value) collectTextureInfos(child, out, depth + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object value, List<Object[]> numbers, List<Map<String, Object>> maps, List<List<Object>> lists, int depth,
                             int[] budget) {
        // 同じものを何度も参照していると、たどる数が増えすぎるので、budget 個までにする
        if (depth > 64 || --budget[0] < 0) return;
        if (value instanceof Map) {
            final Map<String, Object> map = (Map<String, Object>) value;
            maps.add(map);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Double) numbers.add(new Object[]{map, entry.getKey()});
                else walk(entry.getValue(), numbers, maps, lists, depth + 1, budget);
            }
        } else if (value instanceof List) {
            final List<Object> list = (List<Object>) value;
            // 大きな配列 (行列など) の数は、いくつかだけを候補にする
            lists.add(list);
            for (int i = 0; i < list.size(); i++) {
                final Object item = list.get(i);
                if (item instanceof Double) {
                    if (i < 64) numbers.add(new Object[]{list, i});
                } else if (i < 4096) {
                    walk(item, numbers, maps, lists, depth + 1, budget);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object get(Object[] slot) {
        return slot[0] instanceof Map ? ((Map<String, Object>) slot[0]).get(slot[1]) : ((List<Object>) slot[0]).get((Integer) slot[1]);
    }

    @SuppressWarnings("unchecked")
    private static void set(Object[] slot, double value) {
        if (slot[0] instanceof Map) ((Map<String, Object>) slot[0]).put((String) slot[1], value);
        else ((List<Object>) slot[0]).set((Integer) slot[1], value);
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
                    // 先頭のほう (見出し) を選びやすくする
                    final int at = random.nextBoolean() ? random.nextInt(Math.min(data.length - 3, 160)) : random.nextInt(data.length - 3);
                    final int[] values = {0, 1, -1, 0x7FFFFFFF, 0x80000000, 0xFFFF, 0x10000, 1 << 24, random.nextInt()};
                    final int value = values[random.nextInt(values.length)];
                    data[at] = (byte) value;
                    data[at + 1] = (byte) (value >>> 8);
                    data[at + 2] = (byte) (value >>> 16);
                    data[at + 3] = (byte) (value >>> 24);
                }
            }
        }
        return data;
    }
}
