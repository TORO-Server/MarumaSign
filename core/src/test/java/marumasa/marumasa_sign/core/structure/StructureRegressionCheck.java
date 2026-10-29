package marumasa.marumasa_sign.core.structure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.sign.SignSpec;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipOutputStream;

import static marumasa.marumasa_sign.core.structure.StructureShowcase.list;
import static marumasa.marumasa_sign.core.structure.StructureShowcase.map;

/**
 * 構造物のファイルの読み込みで直した不具合が、戻っていないかを確かめる。引数はいらない。1 つでも違えば終了コード 1 で終わる。
 * <ul>
 *     <li>種のファイル (src/test/resources/structure。tools/structure/make_seeds.py で作る) が、どの形式でも正しいブロックに読めること</li>
 *     <li>壊れたファイル (あふれる大きさ、多すぎる状態、NaN の位置など) で、IOException 以外の例外や、長い時間、メモリの使い切りにならないこと</li>
 *     <li>構造物の中の看板が読み込む大きさの合計が、上限 (64 MB) を超えないこと</li>
 * </ul>
 * Minecraft の jar は使わない (システムプロパティ marumasign.assetJar がなければ、空の jar にする。ブロックは紫と黒の模様になる)。
 */
public final class StructureRegressionCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        useOfflineAssets();

        run("seeds", StructureRegressionCheck::checkSeeds);
        run("litematic overflow", StructureRegressionCheck::checkLitematicOverflow);
        run("palette limit", StructureRegressionCheck::checkPaletteLimit);
        run("sign text", StructureRegressionCheck::checkSignText);
        run("bedrock table", StructureRegressionCheck::checkBedrockNearest);
        run("entity positions", StructureRegressionCheck::checkEntityPositions);
        run("face cache", StructureRegressionCheck::checkFaceCache);
        run("legacy chests", StructureRegressionCheck::checkLegacyChests);
        run("transparency", StructureRegressionCheck::checkTransparencyCache);
        run("nested signs", StructureRegressionCheck::checkNestedSigns);

        System.out.println(failures == 0 ? "All structure regression checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    /**
     * ネットワークを使わずに 3D モデルにするところまで動かせるようにする (StructureFuzz からも使う)。
     * システムプロパティ marumasign.assetJar がなければ、Minecraft の jar の代わりに小さな jar を作って使う。
     * 看板の文字の、jar にない文字に使う Unifont はダウンロードしないように空にし、ダウンロードしたものは残さない。
     */
    static void useOfflineAssets() throws Exception {
        final File work = Files.createTempDirectory("structure-check").toFile();
        work.deleteOnExit();
        if (System.getProperty("marumasign.assetJar") == null) {
            final File jar = new File(work, "assets.jar");
            writeAssets(jar);
            jar.deleteOnExit();
            System.setProperty("marumasign.assetJar", jar.getPath());
        }
        System.setProperty("marumasign.gameDir", work.getPath());
        System.setProperty("marumasign.cache", "false");
        final java.lang.reflect.Field unifont = SignTexts.class.getDeclaredField("unifont");
        unifont.setAccessible(true);
        unifont.set(null, new java.util.HashMap<Integer, String>());
    }

    // Minecraft の jar の代わり。音符ブロックだけ、面が隣のブロックで隠れる立方体のモデルを入れる (ほかは紫と黒の模様の立方体)
    private static void writeAssets(File jar) throws IOException {
        final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar.toPath()));
        try {
            zip.putNextEntry(new java.util.zip.ZipEntry("assets/minecraft/blockstates/note_block.json"));
            zip.write("{\"variants\": {\"\": {\"model\": \"minecraft:block/check_cube\"}}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            final StringBuilder faces = new StringBuilder();
            for (String face : new String[]{"down", "up", "north", "south", "west", "east"}) {
                if (faces.length() > 0) faces.append(',');
                faces.append('"').append(face).append("\": {\"texture\": \"#all\", \"cullface\": \"").append(face).append("\"}");
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("assets/minecraft/models/block/check_cube.json"));
            zip.write(("{\"textures\": {\"all\": \"minecraft:block/check\"}, \"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 16, 16], "
                    + "\"faces\": {" + faces + "}}]}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            zip.close();
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    // 1 つの確認を動かす。想定外の例外で終わったら、失敗として数えて次へ進む
    private static void run(String name, Check check) {
        try {
            check.run();
        } catch (Throwable e) {
            failures++;
            System.out.println("FAIL: " + name + " threw " + e);
            e.printStackTrace(System.out);
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }

    private static boolean finite(StructureLoader.Result result, float limit) {
        for (float value : result.scene.restBounds()) {
            if (Float.isNaN(value) || Math.abs(value) > limit) return false;
        }
        return true;
    }

    // ---------- 種のファイル ----------

    private static File seedDirectory() throws Exception {
        final java.net.URL url = StructureRegressionCheck.class.getResource("/structure/expected.txt");
        if (url == null) throw new IOException("src/test/resources must be on the class path");
        return new File(url.toURI()).getParentFile();
    }

    // すべての形式の種のファイルを読み、書いておいた位置のブロックとエンティティと比べる。3D モデルにするところまで確かめる
    private static void checkSeeds() throws Exception {
        final File directory = seedDirectory();
        final Map<String, Map<String, String>> expected = new LinkedHashMap<String, Map<String, String>>();
        final Map<String, List<String>> entities = new LinkedHashMap<String, List<String>>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(directory, "expected.txt")), StandardCharsets.UTF_8));
        try {
            String line;
            Map<String, String> current = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    current = new TreeMap<String, String>();
                    expected.put(line.substring(1, line.length() - 1), current);
                    entities.put(line.substring(1, line.length() - 1), new ArrayList<String>());
                } else if (line.startsWith("E ") && current != null) {
                    final List<Map.Entry<String, Map<String, String>>> all = new ArrayList<Map.Entry<String, Map<String, String>>>(expected.entrySet());
                    entities.get(all.get(all.size() - 1).getKey()).add(line.substring(2));
                } else if (current != null) {
                    final int space = line.indexOf(' ');
                    current.put(line.substring(0, space), "minecraft:" + line.substring(space + 1));
                }
            }
        } finally {
            reader.close();
        }
        for (Map.Entry<String, Map<String, String>> entry : expected.entrySet()) {
            final byte[] content = Files.readAllBytes(new File(directory, entry.getKey()).toPath());
            String problem = null;
            try {
                final Structure structure = StructureReader.read(content);
                int wrong = 0;
                for (Map.Entry<String, String> cell : entry.getValue().entrySet()) {
                    final String[] xyz = cell.getKey().split(",");
                    final String actual = structure.state(structure.id(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]))).name;
                    if (!actual.equals(cell.getValue())) {
                        if (wrong++ == 0) problem = cell.getKey() + " is " + actual + ", expected " + cell.getValue();
                    }
                }
                // エンティティの名前と位置 (構造物の角から)
                final List<String> wanted = entities.get(entry.getKey());
                if (problem == null && !wanted.isEmpty()) {
                    if (structure.entities().size() != wanted.size()) problem = structure.entities().size() + " entities, expected " + wanted.size();
                    for (int i = 0; i < wanted.size() && problem == null; i++) {
                        final String[] parts = wanted.get(i).split(" ");
                        final String[] xyz = parts[1].split(",");
                        final Structure.Entity entity = structure.entities().get(i);
                        if (!entity.id.equals(parts[0]) || Math.abs(entity.x - Double.parseDouble(xyz[0])) > 1e-6
                                || Math.abs(entity.y - Double.parseDouble(xyz[1])) > 1e-6 || Math.abs(entity.z - Double.parseDouble(xyz[2])) > 1e-6) {
                            problem = "entity " + entity.id + " at " + entity.x + "," + entity.y + "," + entity.z + ", expected " + wanted.get(i);
                        }
                    }
                }
                if (problem == null) {
                    final StructureLoader.Result result = StructureLoader.loadDetailed(content);
                    if (result.quads == 0 || !finite(result, 1000)) problem = "bad model: " + Arrays.toString(result.scene.restBounds());
                }
            } catch (IOException e) {
                problem = e.toString();
            }
            check("seed " + entry.getKey() + " reads the right blocks" + (problem == null ? "" : " (" + problem + ")"), problem == null);
        }
    }

    // ---------- Litematica の大きさのあふれ ----------

    // 大きさのかけ算 (2^21 x 2^21 x 2^22) が long であふれて 0 になり、配列の外を読んで ArrayIndexOutOfBoundsException になっていた
    private static void checkLitematicOverflow() throws Exception {
        final byte[] content = Files.readAllBytes(new File(seedDirectory(), "regress_litematic_overflow.litematic").toPath());
        String outcome;
        try {
            StructureReader.read(content);
            outcome = "read";
        } catch (IOException e) {
            outcome = "IOException";
        } catch (RuntimeException e) {
            outcome = e.toString();
        }
        check("a litematic whose region sizes overflow is rejected with an IOException (" + outcome + ")", outcome.equals("IOException"));
    }

    // ---------- 多すぎるブロックの状態 ----------

    // 状態は char で持つので 65535 種類まで。超えると IllegalStateException になっていた
    private static void checkPaletteLimit() throws Exception {
        final Map<String, Object> palette = new LinkedHashMap<String, Object>();
        palette.put("minecraft:air", 0);
        for (int i = 1; i <= 70000; i++) palette.put("minecraft:stone[seed=" + i + "]", i);
        final Map<String, Object> sponge = map("Version", 2, "DataVersion", 3465, "Width", (short) 2, "Height", (short) 1, "Length", (short) 1,
                "Palette", palette, "BlockData", new byte[]{1, 2});
        check("a schematic with 70000 block states is rejected with an IOException", rejected(gzip(nbt(sponge))));
        final List<Object> states = new ArrayList<Object>();
        for (int i = 0; i <= 70000; i++) states.add(map("Name", "minecraft:stone", "Properties", map("seed", String.valueOf(i))));
        final Map<String, Object> template = map("DataVersion", 4671, "size", list(1, 1, 1), "palette", states,
                "blocks", list(map("pos", list(0, 0, 0), "state", 1)), "entities", new ArrayList<Object>());
        check("a structure block file with 70000 block states is rejected with an IOException", rejected(gzip(nbt(template))));
        // 読んだあとに状態を足す (形の計算など) ときも IOException になる
        final Structure structure = new Structure(Structure.Source.LEGACY, 0, 1, 1, 1);
        String outcome = "accepted";
        try {
            for (int i = 0; i < 70000; i++) structure.paletteId(BlockState.parse("minecraft:stone[seed=" + i + "]"));
        } catch (IOException e) {
            outcome = "IOException";
        } catch (RuntimeException e) {
            outcome = e.toString();
        }
        check("adding a 65536th block state throws an IOException (" + outcome + ")", outcome.equals("IOException"));
    }

    private static boolean rejected(byte[] content) {
        try {
            StructureReader.read(content);
            return false;
        } catch (IOException e) {
            return true;
        } catch (RuntimeException e) {
            System.out.println("  " + e);
            return false;
        }
    }

    // ---------- 看板の文字 ----------

    // 1 行が 2048 文字の後に空の文字を何百万も並べたリストで、文字を足すたびに全体を数え直し、何十秒もかかっていた
    private static void checkSignText() {
        final List<Object> line = new ArrayList<Object>();
        for (int i = 0; i < 2048; i++) line.add("a");
        for (int i = 0; i < 3000000; i++) line.add("");
        long start = System.nanoTime();
        final List<SignTexts.Run> runs = SignTexts.parse(line);
        int length = 0;
        for (SignTexts.Run run : runs) length += run.text.length();
        long millis = (System.nanoTime() - start) / 1000000;
        check("a sign line with millions of empty strings is read quickly (" + millis + " ms, " + length + " characters)", millis < 2000 && length == 2048);
        // 看板の行がとても多くても、描く 4 行しか読まない
        final List<Object> messages = new ArrayList<Object>();
        for (int i = 0; i < 3000000; i++) messages.add("[\"a\",\"b\"]");
        final Map<String, Object> entity = map("front_text", map("messages", messages));
        start = System.nanoTime();
        final SignSpec spec = SpecialModels.signSpec(entity, Structure.Source.JAVA);
        millis = (System.nanoTime() - start) / 1000000;
        check("a sign with millions of lines is read quickly (" + millis + " ms)", millis < 500 && spec == null);
    }

    // ---------- 統合版のブロックの対応表 ----------

    // 表にない値の組み合わせは、近いものを表の状態を 1 つずつ読み直して探していたので、状態の多いブロック (石の塀は 2430) を
    // 値だけ変えてたくさん並べると、1 つのファイルに何分もかかっていた
    private static void checkBedrockNearest() throws IOException {
        // 知っている結果 (表で最初に見つかる、最も多く一致するもの)
        final Map<String, String> states = new TreeMap<String, String>();
        states.put("wall_block_type", "andesite");
        states.put("wall_post_bit", "1");
        states.put("unknown", "x");
        BlockState result = BlockTables.bedrock("minecraft:cobblestone_wall", states);
        check("the nearest bedrock wall state is chosen (" + result + ")", result != null && result.toString()
                .equals("minecraft:andesite_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"));
        result = BlockTables.bedrock("minecraft:stone", new TreeMap<String, String>());
        check("a bedrock block without states is found (" + result + ")", result != null && result.name.equals("minecraft:stone"));
        check("an unknown bedrock block is not found", BlockTables.bedrock("minecraft:no_such_block", new TreeMap<String, String>()) == null);

        final String[] types = {"cobblestone", "mossy_cobblestone", "granite", "diorite", "andesite", "sandstone", "brick", "x", null};
        final String[] connections = {"none", "short", "tall", "x", null};
        final long start = System.nanoTime();
        int count = 0;
        for (String type : types) {
            for (String east : connections) {
                for (String north : connections) {
                    for (String south : connections) {
                        for (String west : connections) {
                            final Map<String, String> query = new TreeMap<String, String>();
                            if (type != null) query.put("wall_block_type", type);
                            if (east != null) query.put("wall_connection_type_east", east);
                            if (north != null) query.put("wall_connection_type_north", north);
                            if (south != null) query.put("wall_connection_type_south", south);
                            if (west != null) query.put("wall_connection_type_west", west);
                            query.put("wall_post_bit", String.valueOf(count & 1));
                            BlockTables.bedrock("minecraft:cobblestone_wall", query);
                            count++;
                        }
                    }
                }
            }
        }
        final long millis = (System.nanoTime() - start) / 1000000;
        check("5625 different bedrock wall states are matched quickly (" + millis + " ms)", millis < 5000 && count == 5625);
    }

    // ---------- エンティティの位置 ----------

    // NaN や無限大、とても遠い位置のエンティティや変換で、モデルの大きさが壊れ、構造物が見えなくなっていた
    private static void checkEntityPositions() throws Exception {
        final byte[] content = Files.readAllBytes(new File(seedDirectory(), "regress_entity_positions.nbt").toPath());
        final Structure structure = StructureReader.read(content);
        check("entities at NaN or infinite positions are dropped (" + structure.entities().size() + " kept)", structure.entities().size() == 3);
        final StructureLoader.Result result = StructureLoader.loadDetailed(content);
        check("broken entities do not change the size of the model " + Arrays.toString(result.scene.restBounds()), finite(result, 3));
        // 四角形を集めるところで、範囲の外のものを除く
        final StructureMesher.Output output = new StructureMesher.Output(new Structure(Structure.Source.JAVA, 0, 2, 2, 2));
        final float[] uvs = new float[8];
        final int[] colors = new int[4];
        final Sprite sprite = Sprite.missing();
        output.add(0, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, uvs, colors, sprite);
        output.add(0, new float[]{0, 0, 0, Float.NaN, 0, 0, 1, 1, 0, 0, 1, 0}, uvs, colors, sprite);
        output.add(0, new float[]{0, 0, 0, 1, 0, 0, 1, Float.POSITIVE_INFINITY, 0, 0, 1, 0}, uvs, colors, sprite);
        output.add(0, new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 1e9f}, uvs, colors, sprite);
        check("faces with NaN, infinite or far away corners are not drawn (" + output.quadCount + " of 4 kept)", output.quadCount == 1);
    }

    // ---------- 面を描くかどうかを覚える表 ----------

    // ブロックの組み合わせごとに覚えていたので、いろいろなブロックを混ぜて並べた構造物で、メモリを使い切り、何分もかかっていた
    private static void checkFaceCache() throws IOException {
        final List<String> states = new ArrayList<String>();
        final String[] instruments = {"harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone",
                "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};
        for (String instrument : instruments) {
            for (int note = 0; note < 25; note++) {
                for (String powered : new String[]{"true", "false"}) {
                    states.add("minecraft:note_block[instrument=" + instrument + ",note=" + note + ",powered=" + powered + "]");
                }
            }
        }
        final int size = 128;
        final Map<String, Object> palette = new LinkedHashMap<String, Object>();
        palette.put("minecraft:air", 0);
        for (int i = 0; i < states.size(); i++) palette.put(states.get(i), i + 1);
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final Random random = new Random(3);
        for (int i = 0; i < size * size * size; i++) {
            final int value = 1 + random.nextInt(states.size());
            data.write(value & 0x7F | 0x80);
            data.write(value >> 7);
        }
        final byte[] content = gzip(nbt(map("Version", 2, "DataVersion", 3465, "Width", (short) size, "Height", (short) size,
                "Length", (short) size, "Palette", palette, "BlockData", data.toByteArray())));
        final long start = System.nanoTime();
        final StructureLoader.Result result = StructureLoader.loadDetailed(content);
        final long millis = (System.nanoTime() - start) / 1000000;
        check("128 x 128 x 128 blocks of 800 kinds are drawn quickly (" + millis + " ms, " + result.quads + " faces)", millis < 15000 && result.quads > 0);
    }

    // ---------- 1.12 までのチェスト ----------

    // つなげ終えたチェストを HashMap で覚えていたので、チェストだらけの構造物でメモリを使い切っていた。つながり方が変わっていないこと
    private static void checkLegacyChests() throws IOException {
        final int width = 64, height = 4, length = 64;
        final byte[] blocks = new byte[width * height * length];
        final byte[] data = new byte[blocks.length];
        Arrays.fill(blocks, (byte) 54);
        // 北向き (データ値 2)
        Arrays.fill(data, (byte) 2);
        final byte[] content = gzip(nbt(map("Width", (short) width, "Height", (short) height, "Length", (short) length, "Materials", "Alpha",
                "Blocks", blocks, "Data", data)));
        final Structure structure = StructureReader.read(content);
        StructureFixer.fix(structure);
        boolean ok = true;
        for (int x = 0; x < width && ok; x++) {
            // 北向きのチェストは、西から順に 2 つずつ組になる (ゲームと同じく、時計回りの向き (東) につながるものが left)
            final String type = structure.state(structure.id(x, 1, 5)).get("type");
            ok = (x % 2 == 0 ? "left" : "right").equals(type);
            if (!ok) System.out.println("  chest at x=" + x + " is " + type);
        }
        check("1.12 chests side by side are paired", ok);
    }

    // ---------- 透明度の扱い ----------

    // 看板の絵 (アニメーション) の透明度を、同じ URL の看板の数だけ全部の絵で調べ直していた
    private static void checkTransparencyCache() {
        final int frames = 64, side = 256;
        final int[][] pixels = new int[frames][side * side];
        for (int[] frame : pixels) Arrays.fill(frame, 0xFF336699);
        final int[] sequence = new int[frames];
        final int[] times = new int[frames];
        for (int i = 0; i < frames; i++) {
            sequence[i] = i;
            times[i] = 1;
        }
        final Sprite sprite = Sprite.animated("check", side, side, pixels, sequence, times);
        final long start = System.nanoTime();
        int result = 0;
        for (int i = 0; i < 4000; i++) result |= sprite.transparency(0, 0, 1, 1);
        final long millis = (System.nanoTime() - start) / 1000000;
        check("the transparency of a large animation is computed once (" + millis + " ms)", millis < 2000 && result == Sprite.OPAQUE);
    }

    // ---------- 構造物の中の看板 ----------

    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<String, byte[]>();
    private static final Map<String, AtomicLong> SENT = new ConcurrentHashMap<String, AtomicLong>();

    private static void checkNestedSigns() throws Exception {
        System.setProperty("marumasign.allowLocalUrls", "true");
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new Handler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            checkNestedBudget(base);
            checkNestedModelTextures(base);
        } finally {
            server.stop(0);
            System.clearProperty("marumasign.allowLocalUrls");
        }
    }

    // 合計の大きさの上限 (64 MB) は、読み始める前の合計で見ていたので、最後の 1 つで上限を大きく超えて読んでいた。
    // 大きすぎて途中でやめたものも数えていなかった
    private static void checkNestedBudget(String base) throws IOException {
        // 構造物らしく見える (NBT の見出しの) 50 MB のファイル。大きさを知らせずに送る
        final byte[] big = new byte[50 * 1024 * 1024];
        final byte[] head = {10, 0, 0, 7, 0, 4, 'D', 'a', 't', 'a', 0x02, (byte) 0xFF, (byte) 0xFF, (byte) 0xF0};
        System.arraycopy(head, 0, big, 0, head.length);
        final StructureShowcase s = new StructureShowcase();
        for (int i = 0; i < 3; i++) {
            FILES.put("/big" + i + ".nbt", big);
            s.put(i, 0, 0, "minecraft:stone");
            s.put(i, 1, 0, "minecraft:oak_sign[rotation=0]", NestedSignCheck.signData(base + "big" + i + ".nbt|0|0|0|1|1|0|0|0", false));
        }
        StructureLoader.loadDetailed(s.bytes());
        long sent = 0;
        for (int i = 0; i < 3; i++) sent += sent("/big" + i + ".nbt");
        // 送った量には、読む側が切るまでに送り先の受け取りの枠に入った分も含まれるので、少し余裕を見る
        check("signs in a structure read at most 64 MB in total (" + sent / (1024 * 1024) + " MB sent)", sent < 80L * 1024 * 1024);
        FILES.clear();
    }

    // 同じモデルの看板がいくつあっても、モデルのテクスチャはまとめた画像に 1 回だけ入る (看板の数だけ入れて、メモリを使い切っていた)
    private static void checkNestedModelTextures(String base) throws IOException {
        final StructureShowcase inner = new StructureShowcase();
        inner.put(0, 0, 0, "minecraft:stone");
        FILES.put("/inner.nbt", inner.bytes());
        final StructureShowcase s = new StructureShowcase();
        for (int i = 0; i < 300; i++) {
            s.put(i % 30, 0, i / 30 * 2, "minecraft:stone");
            s.put(i % 30, 1, i / 30 * 2, "minecraft:oak_sign[rotation=0]", NestedSignCheck.signData(base + "inner.nbt|0|0|0|1|1|0|0|0", false));
        }
        final StructureLoader.Result result = StructureLoader.loadDetailed(s.bytes());
        long pixels = 0;
        for (int i = 0; i < result.scene.model().textureCount(); i++) {
            pixels += (long) result.scene.model().texture(i).width * result.scene.model().texture(i).height;
        }
        check("300 signs showing the same model add its textures once (" + pixels + " texture pixels, " + sent("/inner.nbt") + " bytes read)",
                pixels <= 128 * 128 && sent("/inner.nbt") == FILES.get("/inner.nbt").length);
        FILES.clear();
    }

    private static long sent(String path) {
        final AtomicLong count = SENT.get(path);
        return count == null ? 0 : count.get();
    }

    // 置いたファイルを、大きさを知らせずに返し、送れた量を数える
    private static final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            final byte[] content = FILES.get(path);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            AtomicLong count = SENT.get(path);
            if (count == null) {
                SENT.putIfAbsent(path, new AtomicLong());
                count = SENT.get(path);
            }
            exchange.sendResponseHeaders(200, 0);
            final OutputStream output = exchange.getResponseBody();
            try {
                for (int offset = 0; offset < content.length; offset += 65536) {
                    final int length = Math.min(65536, content.length - offset);
                    output.write(content, offset, length);
                    count.addAndGet(length);
                }
            } catch (IOException e) {
                // 読む側が切った
            } finally {
                exchange.close();
            }
        }
    }

    // ---------- NBT を書く ----------

    private static byte[] gzip(byte[] data) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final GZIPOutputStream output = new GZIPOutputStream(bytes);
        output.write(data);
        output.close();
        return bytes.toByteArray();
    }

    // ビッグエンディアンの NBT (Short、Integer、String、byte[]、List、Map だけ)
    private static byte[] nbt(Map<String, Object> root) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Nbt.COMPOUND);
        out.writeUTF("");
        writeCompound(out, root);
        out.close();
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            out.writeByte(type(entry.getValue()));
            out.writeUTF(entry.getKey());
            writeValue(out, entry.getValue());
        }
        out.writeByte(Nbt.END);
    }

    private static int type(Object value) {
        if (value instanceof Short) return Nbt.SHORT;
        if (value instanceof Integer) return Nbt.INT;
        if (value instanceof String) return Nbt.STRING;
        if (value instanceof byte[]) return Nbt.BYTE_ARRAY;
        if (value instanceof List) return Nbt.LIST;
        if (value instanceof Map) return Nbt.COMPOUND;
        throw new IllegalArgumentException(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value instanceof Short) {
            out.writeShort((Short) value);
        } else if (value instanceof Integer) {
            out.writeInt((Integer) value);
        } else if (value instanceof String) {
            out.writeUTF((String) value);
        } else if (value instanceof byte[]) {
            out.writeInt(((byte[]) value).length);
            out.write((byte[]) value);
        } else if (value instanceof List) {
            final List<Object> items = (List<Object>) value;
            out.writeByte(items.isEmpty() ? Nbt.END : type(items.get(0)));
            out.writeInt(items.size());
            for (Object item : items) writeValue(out, item);
        } else {
            writeCompound(out, (Map<String, Object>) value);
        }
    }
}
