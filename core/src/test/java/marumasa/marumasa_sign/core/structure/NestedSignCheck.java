package marumasa.marumasa_sign.core.structure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.core.sign.SignSpec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static marumasa.marumasa_sign.core.structure.StructureShowcase.list;
import static marumasa.marumasa_sign.core.structure.StructureShowcase.map;

/**
 * 構造物の中にある、MarumaSign の形式で書かれた看板 (と看板のアイテム) を確かめる。
 * 自分自身や互いを指す構造物、読めない URL、手元のネットワークの URL、NaN や大きすぎる数値、
 * ゆっくりしか返さないサーバー、たくさんの URL などで、止まったり、落ちたり、上限を超えて読み込んだりしないこと。
 * 1 つでも違えば終了コード 1 で終わる。
 */
public final class NestedSignCheck {

    private static int failures;
    private static final Map<String, byte[]> files = new ConcurrentHashMap<String, byte[]>();
    private static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<String, AtomicInteger>();
    private static String base;

    public static void main(String[] args) throws Exception {
        System.setProperty("marumasign.allowLocalUrls", "true");
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new FileHandler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            files.put("/img.png", png(64, 32));
            final byte[] broken = png(64, 64);
            files.put("/truncated.png", Arrays.copyOf(broken, broken.length / 2));
            final byte[] garbage = new byte[4096];
            new Random(1).nextBytes(garbage);
            files.put("/garbage.png", garbage);

            checkImage();
            checkKinds();
            checkSelfAndCycles();
            checkBrokenUrls();
            checkValues();
            checkManyUrls();
            checkLocalNetwork();
            checkSlowServer();
        } finally {
            server.stop(0);
        }
        System.out.println(failures == 0 ? "All nested sign checks passed" : failures + " failures");
        System.exit(failures > 0 ? 1 : 0);
    }

    // 看板 1 つの構造物。text は看板に書く文字
    private static StructureShowcase signStructure(String state, String text, boolean back) {
        final StructureShowcase s = new StructureShowcase();
        s.put(0, 0, 0, "minecraft:stone");
        s.put(0, 0, 1, "minecraft:stone");
        s.put(0, 1, 1, state, signData(text, back));
        return s;
    }

    static Map<String, Object> signData(String text, boolean back) {
        final Map<String, Object> written = sideData(text);
        final Map<String, Object> empty = sideData("");
        return map("id", "minecraft:sign", "front_text", back ? empty : written, "back_text", back ? written : empty);
    }

    private static Map<String, Object> sideData(String text) {
        final String[] lines = SignSpec.splitLines(text, 1000);
        final Object[] messages = new Object[4];
        for (int i = 0; i < 4; i++) messages[i] = "\"" + lines[i].replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return map("messages", list(messages), "color", "black");
    }

    private static String sign(String path) {
        return base + path + "|0|0|0|1|1|0|0|0";
    }

    private static StructureLoader.Result load(StructureShowcase s) throws IOException {
        return StructureLoader.loadDetailed(s.bytes());
    }

    private static int hits(String path) {
        final AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    private static boolean finite(StructureLoader.Result result) {
        final float[] bounds = result.scene.restBounds();
        for (float value : bounds) {
            if (Float.isNaN(value) || Float.isInfinite(value) || Math.abs(value) > 100000f) return false;
        }
        return true;
    }

    // 画像の看板: 看板の板の代わりに画像が描かれる
    private static void checkImage() throws IOException {
        final int plain = load(signStructure("minecraft:oak_sign[rotation=0]", "Hello", false)).quads;
        final StructureLoader.Result image = load(signStructure("minecraft:oak_sign[rotation=0]", sign("img.png"), false));
        check("an image sign is read once", hits("/img.png") == 1);
        check("an image sign draws the image instead of the board (" + plain + " -> " + image.quads + " faces)",
                image.quads != plain && finite(image));
    }

    // 壁、裏の面、吊り看板、額縁の中の看板のアイテム
    private static void checkKinds() throws IOException {
        files.put("/wall.png", png(16, 16));
        files.put("/back.png", png(16, 16));
        files.put("/hanging.png", png(16, 16));
        files.put("/item.png", png(16, 16));
        check("a wall sign loads", finite(load(signStructure("minecraft:oak_wall_sign[facing=south]", sign("wall.png"), false)))
                && hits("/wall.png") == 1);
        check("text on the back side loads", finite(load(signStructure("minecraft:oak_sign[rotation=4]", sign("back.png"), true)))
                && hits("/back.png") == 1);
        check("a hanging sign does not break", finite(load(signStructure("minecraft:oak_hanging_sign[rotation=0,attached=false]",
                sign("hanging.png"), false))));
        final StructureShowcase s = new StructureShowcase();
        for (int y = 0; y < 2; y++) s.put(0, y, 0, "minecraft:oak_planks");
        s.entity(0.5, 1.5, 1.03125, map("id", "minecraft:item_frame", "Facing", (byte) 3, "Item", map("id", "minecraft:oak_sign",
                "count", 1, "components", map("minecraft:block_entity_data", signData(sign("item.png"), false)))));
        check("a sign item in an item frame loads", finite(load(s)) && hits("/item.png") == 1);
    }

    // 自分自身を指す構造物と、互いを指す構造物: 2 段までで止まり、同じ URL はたどり直さない
    private static void checkSelfAndCycles() throws IOException {
        files.put("/self.nbt", signStructure("minecraft:oak_sign[rotation=0]", sign("self.nbt"), false).bytes());
        files.put("/a.nbt", signStructure("minecraft:oak_sign[rotation=0]", sign("b.nbt"), false).bytes());
        files.put("/b.nbt", signStructure("minecraft:oak_sign[rotation=0]", sign("a.nbt"), false).bytes());
        final long start = System.nanoTime();
        final StructureLoader.Result self = load(signStructure("minecraft:oak_sign[rotation=0]", sign("self.nbt"), false));
        check("a structure that shows itself stops (" + hits("/self.nbt") + " reads)", finite(self) && hits("/self.nbt") <= 2);
        final StructureLoader.Result cycle = load(signStructure("minecraft:oak_sign[rotation=0]", sign("a.nbt"), false));
        check("structures that show each other stop (" + hits("/a.nbt") + " + " + hits("/b.nbt") + " reads)",
                finite(cycle) && hits("/a.nbt") + hits("/b.nbt") <= 2);
        check("nested structures load quickly", System.nanoTime() - start < 10000000000L);
    }

    // 読めない URL: 看板の板は消え、ほかは描かれる
    private static void checkBrokenUrls() throws IOException {
        for (String path : new String[]{"missing.png", "truncated.png", "garbage.png"}) {
            boolean ok;
            try {
                ok = finite(load(signStructure("minecraft:oak_sign[rotation=0]", sign(path), false)));
            } catch (IOException e) {
                ok = false;
                System.out.println("  " + e);
            }
            check("a sign with an unreadable URL (" + path + ") does not break the structure", ok);
        }
        boolean ok;
        try {
            ok = finite(load(signStructure("minecraft:oak_sign[rotation=0]", "notaurl|0|0|0|1|1|0|0|0", false)));
        } catch (IOException e) {
            ok = false;
        }
        check("a sign whose URL is not a URL does not break the structure", ok);
    }

    // NaN や大きすぎる数値: NaN は MarumaSign の看板として扱わず、大きすぎる値は上限に収める
    private static void checkValues() throws IOException {
        files.put("/values.png", png(16, 16));
        final String[] texts = {
                base + "values.png|NaN|0|0|1|1|0|0|0", base + "values.png|0|0|0|Infinity|1|0|0|0",
                base + "values.png|1e30|-1e30|1e30|1e30|1e30|1e30|1e30|1e30", base + "values.png|0|0|0|0|0|0|0|0",
                base + "values.png|0|0|0|-1|-1|0|0|0",
        };
        for (String text : texts) {
            boolean ok;
            try {
                final StructureLoader.Result result = load(signStructure("minecraft:oak_sign[rotation=0]", text, false));
                ok = finite(result);
            } catch (IOException e) {
                // 描くものが何も残らなかったときだけ失敗してよい (大きさ 0 の看板だけの構造物ではない)
                ok = false;
                System.out.println("  " + e);
            }
            check("sign values '" + text.substring(base.length()) + "' are handled", ok);
        }
    }

    // たくさんの URL: 1 つの構造物で読むのは上限の数まで
    private static void checkManyUrls() throws IOException {
        final StructureShowcase s = new StructureShowcase();
        for (int i = 0; i < 40; i++) {
            files.put("/many" + i + ".png", png(16, 16));
            s.put(i, 0, 0, "minecraft:stone");
            s.put(i, 1, 0, "minecraft:oak_sign[rotation=0]", signData(sign("many" + i + ".png"), false));
        }
        check("the structure loads", finite(load(s)));
        int read = 0;
        for (int i = 0; i < 40; i++) read += hits("/many" + i + ".png");
        check("no more than 16 URLs are read for one structure (" + read + ")", read == 16);
    }

    // 手元や家の中のネットワークの URL は読まない
    private static void checkLocalNetwork() throws IOException {
        System.clearProperty("marumasign.allowLocalUrls");
        try {
            files.put("/private.png", png(16, 16));
            final StructureLoader.Result result = load(signStructure("minecraft:oak_sign[rotation=0]", sign("private.png"), false));
            check("a sign pointing at this computer is not read", finite(result) && hits("/private.png") == 0);
            final StructureShowcase s = new StructureShowcase();
            s.put(0, 0, 0, "minecraft:stone");
            s.put(0, 1, 0, "minecraft:oak_sign[rotation=0]", signData("http://192.168.0.1/router.png|0|0|0|1|1|0|0|0", false));
            s.put(1, 0, 0, "minecraft:stone");
            s.put(1, 1, 0, "minecraft:oak_sign[rotation=0]", signData("http://169.254.169.254/latest|0|0|0|1|1|0|0|0", false));
            final long start = System.nanoTime();
            check("signs pointing at the home network are skipped at once", finite(load(s)) && System.nanoTime() - start < 5000000000L);
        } finally {
            System.setProperty("marumasign.allowLocalUrls", "true");
        }
    }

    // ゆっくりしか返さないサーバー: 看板の中身を読むのに使う時間には上限があり、ほかの構造物を待たせ続けない
    private static void checkSlowServer() throws Exception {
        final StructureShowcase s = new StructureShowcase();
        for (int i = 0; i < 16; i++) {
            s.put(i, 0, 0, "minecraft:stone");
            s.put(i, 1, 0, "minecraft:oak_sign[rotation=0]", signData(sign("slow" + i + ".png"), false));
        }
        final long start = System.nanoTime();
        final boolean loaded = finite(load(s));
        final double seconds = (System.nanoTime() - start) / 1e9;
        System.out.println(String.format("  16 slow signs took %.1f s", seconds));
        check("slow servers do not hold the structure for long", loaded && seconds < 30);
        final long next = System.nanoTime();
        check("the next structure loads right after", finite(load(signStructure("minecraft:oak_sign[rotation=0]", "Hi", false)))
                && System.nanoTime() - next < 10000000000L);
    }

    private static byte[] png(int width, int height) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFFFF0000 : 0xFF0000FF);
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    // 置いたファイルを返し、要求を数える。slow で始まるものは、1 秒に 1 バイトずつ返す
    private static final class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            AtomicInteger count = hits.get(path);
            if (count == null) {
                hits.putIfAbsent(path, new AtomicInteger());
                count = hits.get(path);
            }
            count.incrementAndGet();
            if (path.startsWith("/slow")) {
                exchange.sendResponseHeaders(200, 100000);
                final OutputStream output = exchange.getResponseBody();
                try {
                    output.write(png(16, 16), 0, 8);
                    for (int i = 0; i < 120; i++) {
                        Thread.sleep(1000);
                        output.write(0);
                        output.flush();
                    }
                } catch (Exception e) {
                    // 読む側が切った
                } finally {
                    exchange.close();
                }
                return;
            }
            final byte[] content = files.get(path);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            final OutputStream output = exchange.getResponseBody();
            try {
                output.write(content);
            } finally {
                output.close();
            }
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok: " : "FAIL: ") + name);
    }
}
