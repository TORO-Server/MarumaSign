package marumasa.marumasa_sign.core.splat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SplatFuzz などで見つけて直した、ガウシアンスプラッティングの読み込みの不具合が、もう一度起きないことを確かめる。引数は要らない。
 * 失敗があれば 1 で終わる。src/test/resources/splat の種のファイルが読めることも確かめる。
 */
public final class SplatRegressionCheck {

    private static int failures;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "SplatRegressionCheck");
        thread.setDaemon(true);
        return thread;
    });

    private SplatRegressionCheck() {
    }

    public static void main(String[] args) throws Exception {
        seeds();
        asciiCount();
        emptyElements();
        spzRatio();
        compressedPlyBounds();
        ksplatBlockSize();
        sogImageSize();
        sogZipSize();
        check(SplatLoader.importance(Float.NaN, 1, 1, 1) == 0f && SplatLoader.importance(1, Float.NaN, 1, 1) == 0f,
                "importance of NaN values is 0");
        System.out.println(failures == 0 ? "SplatRegressionCheck: all tests passed" : "SplatRegressionCheck: " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "OK   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    // 時間の上限をつけて読む。読めれば "loaded"、読めなければ IOException のメッセージ、時間を過ぎたら null
    private static String load(final byte[] data, final String name, long seconds) throws Exception {
        final Future<String> future = EXECUTOR.submit(new Callable<String>() {
            @Override
            public String call() {
                try {
                    final SplatCloud cloud = SplatLoader.load(data, name);
                    new SplatScene(cloud).restBounds();
                    return "loaded";
                } catch (IOException e) {
                    return "IOException: " + e.getMessage();
                }
            }
        });
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private static byte[] seed(String name) throws IOException {
        return Files.readAllBytes(new File("src/test/resources/splat", name).toPath());
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static void seeds() throws Exception {
        final File[] files = new File("src/test/resources/splat").listFiles();
        check(files != null && files.length > 0, "seed folder exists");
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            if (file.getName().endsWith(".txt")) continue;
            final String result = load(Files.readAllBytes(file.toPath()), file.getName(), 30);
            check("loaded".equals(result), "seed " + file.getName() + ": " + result);
        }
    }

    // ASCII の PLY は、書かれた数だけで (データを確かめる前に) 大きな配列を確保していた
    private static void asciiCount() throws Exception {
        final String text = new String(seed("ascii.ply"), StandardCharsets.ISO_8859_1).replace("element vertex 20", "element vertex 50000000");
        final String result = load(text.getBytes(StandardCharsets.ISO_8859_1), "a.ply", 20);
        check(result != null && result.contains("Truncated ASCII PLY"), "ASCII PLY count is checked against the data length: " + result);
    }

    // プロパティのない要素の数だけ空回りしていた (数千万 x 要素の数)
    private static void emptyElements() throws Exception {
        final StringBuilder header = new StringBuilder("ply\nformat ascii 1.0\n");
        for (int i = 0; i < 2500; i++) header.append("element e").append(i).append(" 50000000\n");
        final String original = new String(seed("ascii.ply"), StandardCharsets.ISO_8859_1);
        final String text = original.replace("ply\r\nformat ascii 1.0\r\n", header.toString());
        final String result = load(text.getBytes(StandardCharsets.ISO_8859_1), "e.ply", 20);
        check("loaded".equals(result), "elements without properties are skipped quickly: " + result);
    }

    // Zstandard の .spz (版 4) は、展開後の大きさと圧縮率を確かめていなかった (数 KB から数百 MB に増やせた)
    private static void spzRatio() throws Exception {
        final int points = 1000000;
        final long[] sizes = {9L * points, points, 3L * points, 3L * points, 4L * points};
        final byte[][] streams = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) streams[i] = rleFrame((int) sizes[i]);
        final ByteBuffer header = ByteBuffer.allocate(32 + sizes.length * 16).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x5053474E).putInt(4).putInt(points);
        header.put((byte) 0).put((byte) 12).put((byte) 0).put((byte) sizes.length).putInt(32);
        header.position(32);
        for (int i = 0; i < sizes.length; i++) header.putLong(streams[i].length).putLong(sizes[i]);
        final byte[] data = concat(header.array(), concat(streams));
        final String result = load(data, "r.spz", 30);
        check(result != null && result.contains("safety limits"), "SPZ v4 compression ratio is limited: " + result
                + " (" + data.length + " bytes)");
    }

    // 同じバイトだけの Zstandard のフレーム (128 KB ずつの RLE のブロック)
    private static byte[] rleFrame(int size) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x28);
        out.write(0xB5);
        out.write(0x2F);
        out.write(0xFD);
        out.write(0xA0);
        for (int i = 0; i < 4; i++) out.write(size >>> (8 * i));
        for (int done = 0; done < size; ) {
            final int length = Math.min(131072, size - done);
            done += length;
            final int header = length << 3 | 2 | (done == size ? 1 : 0);
            out.write(header);
            out.write(header >>> 8);
            out.write(header >>> 16);
            out.write(0x40);
        }
        return out.toByteArray();
    }

    // 圧縮した PLY の範囲の最小と最大が大きいと、補間で位置が無限大や NaN になった
    private static void compressedPlyBounds() throws Exception {
        final byte[] data = seed("compressed.ply");
        final int body = new String(data, StandardCharsets.ISO_8859_1).indexOf("end_header\n") + 11;
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(body, -3e38f).putFloat(body + 12, 3e38f);
        final String result = load(data, "c.ply", 20);
        check(result != null && result.contains("Non-finite"), "non-finite positions are rejected: " + result);
    }

    // .ksplat の区画の大きさが大きいと、位置が無限大になった
    private static void ksplatBlockSize() throws Exception {
        final byte[] data = seed("mode1.ksplat");
        // 区画の大きさと、最初のバケットの中心 (区画の見出しのあと、端数のバケットの数 4 バイトのあと) を大きくする
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(4096 + 16, 3.4e38f).putFloat(4096 + 1024 + 4, 3.4e38f);
        final String result = load(data, "k.ksplat", 20);
        check(result != null && result.contains("Non-finite"), "non-finite ksplat positions are rejected: " + result);
    }

    // .sog の画像は、要る数よりずっと大きくても展開していた (小さく圧縮した大きな画像でメモリを使い切った)
    private static void sogImageSize() throws Exception {
        final ByteBuffer webp = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(32).put("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)).putInt(20);
        webp.put((byte) 0x2F).putInt(8191 | 8191 << 14);
        final String meta = "{\"version\":2,\"count\":1,\"means\":{\"mins\":[0,0,0],\"maxs\":[1,1,1],\"files\":[\"a.webp\",\"a.webp\"]},"
                + "\"scales\":{\"codebook\":" + codebook() + ",\"files\":[\"a.webp\"]},\"quats\":{\"files\":[\"a.webp\"]},"
                + "\"sh0\":{\"codebook\":" + codebook() + ",\"files\":[\"a.webp\"]}}";
        final String result = load(zip("meta.json", meta.getBytes(StandardCharsets.UTF_8), "a.webp", webp.array()), "s.sog", 20);
        check(result != null && result.contains("safety limits"), "SOG image size is checked before decoding: " + result);
    }

    // ZIP の展開後の大きさを大きく書くと、展開する前にその大きさを確保していた
    private static void sogZipSize() throws Exception {
        final byte[] data = zip("meta.json", "{}".getBytes(StandardCharsets.UTF_8), "b.webp", new byte[100]);
        // 中央ディレクトリの 2 つ目 (b.webp) の展開後の大きさを 60 MB にする
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int found = 0;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (buffer.getInt(i) == 0x02014B50 && ++found == 2) buffer.putInt(i + 24, 60 * 1024 * 1024);
        }
        final String result = load(data, "z.sog", 20);
        check(result != null && result.contains("Size mismatch"), "ZIP entry size is checked against the compressed size: " + result);
    }

    private static String codebook() {
        final StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < 256; i++) text.append(i == 0 ? "" : ",").append(i / 256.0);
        return text.append(']').toString();
    }

    private static byte[] zip(String name1, byte[] data1, String name2, byte[] data2) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(name1));
        zip.write(data1);
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry(name2));
        zip.write(data2);
        zip.closeEntry();
        zip.close();
        return out.toByteArray();
    }
}
