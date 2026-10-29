package marumasa.marumasa_sign.core.texture;

import marumasa.marumasa_sign.core.image.DecodedImage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * WebP デコーダの動作と安全性を確かめる。
 * 1. 試験用の画像 (src/test/resources/webp) を展開し、PIL (libwebp) で展開した結果のハッシュと比べる。
 * 2. 壊れたデータ (空、途中で切れたもの、でたらめな値) が IOException になることを確かめる。
 * 3. 試験用の画像を壊したもの (ビットの反転、切り詰め、値の書き換え) を読ませ、
 *    IOException 以外の例外や、時間がかかりすぎることがないかを確かめる。
 * 引数: 壊したデータを読ませる回数 (省略すると 3000)。問題があれば終了コード 1 で終わる。
 */
public final class WebpCheck {

    private static final String RESOURCES = "/webp/";
    // 壊したデータ 1 つの展開にかけてよい時間
    private static final long TIMEOUT_SECONDS = 10;

    private static int failures;

    private WebpCheck() {
    }

    // 試験用の画像 1 つと、期待する結果
    private static final class Sample {
        String name;
        byte[] data;
        int width;
        int height;
        int frames;
        String hash;
        int[] delays;
        int repetitions;
    }

    public static void main(String[] args) throws Exception {
        final int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        final List<Sample> samples = loadSamples();
        System.out.println("WebpCheck: " + samples.size() + " samples");
        checkSamples(samples);
        checkInvalidInputs(samples);
        checkCraftedInputs();
        fuzz(samples, iterations);
        if (failures > 0) {
            System.out.println("WebpCheck FAILED: " + failures + " problem(s)");
            System.exit(1);
        }
        System.out.println("WebpCheck passed");
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }

    private static byte[] resource(String name) throws IOException {
        final InputStream in = WebpCheck.class.getResourceAsStream(RESOURCES + name);
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

    // samples.txt: 名前 幅 高さ フレーム数 ハッシュ 表示時間 (カンマ区切り、静止画は -) 繰り返し回数
    private static List<Sample> loadSamples() throws IOException {
        final List<Sample> samples = new ArrayList<Sample>();
        for (String line : new String(resource("samples.txt"), StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final String[] f = line.split(" ");
            final Sample s = new Sample();
            s.name = f[0];
            s.width = Integer.parseInt(f[1]);
            s.height = Integer.parseInt(f[2]);
            s.frames = Integer.parseInt(f[3]);
            s.hash = f[4];
            if (!f[5].equals("-")) {
                final String[] d = f[5].split(",");
                s.delays = new int[d.length];
                for (int i = 0; i < d.length; i++) s.delays[i] = Integer.parseInt(d[i]);
            }
            s.repetitions = Integer.parseInt(f[6]);
            s.data = resource(s.name);
            samples.add(s);
        }
        return samples;
    }

    // ---------- 試験用の画像 ----------

    private static void checkSamples(List<Sample> samples) throws Exception {
        for (Sample s : samples) {
            try {
                final BufferedImage image = WebpDecoder.decode(s.data);
                final int[] still = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                if (image.getWidth() != s.width || image.getHeight() != s.height) {
                    fail(s.name + ": size " + image.getWidth() + "x" + image.getHeight());
                    continue;
                }
                if (image.getType() != BufferedImage.TYPE_4BYTE_ABGR) fail(s.name + ": image type " + image.getType());
                final DecodedImage animated = WebpDecoder.decodeAnimated(s.data);
                final String hash;
                if (s.frames > 1) {
                    if (animated == null) {
                        fail(s.name + ": not decoded as an animation");
                        continue;
                    }
                    if (animated.frames.length != s.frames) fail(s.name + ": frames " + animated.frames.length);
                    if (!Arrays.equals(animated.delays, s.delays)) fail(s.name + ": delays " + Arrays.toString(animated.delays));
                    if (animated.repetitions != s.repetitions) fail(s.name + ": repetitions " + animated.repetitions);
                    // 1 枚として読んだときは、最初のフレームを描いたキャンバスになる
                    if (!Arrays.equals(still, animated.frames[0])) fail(s.name + ": decode() differs from the first frame");
                    hash = hash(animated.frames, true);
                } else {
                    if (animated != null) fail(s.name + ": still image decoded as an animation");
                    hash = hash(new int[][]{still}, false);
                }
                if (!hash.equals(s.hash)) fail(s.name + ": hash " + hash + " (expected " + s.hash + ")");
                else System.out.println("ok " + s.name);
            } catch (IOException e) {
                fail(s.name + ": " + e);
            }
        }
    }

    /**
     * ARGB のフレームを、R, G, B, A の順のバイト列にした SHA-256 (先頭 32 文字)。
     * clearTransparent なら、完全に透明な画素を 0 にそろえる (アニメーションの透明な画素の色は、実装によって違い見えもしないため)。
     */
    private static String hash(int[][] frames, boolean clearTransparent) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int[] frame : frames) {
            final byte[] bytes = new byte[frame.length * 4];
            for (int i = 0; i < frame.length; i++) {
                final int c = clearTransparent && (frame[i] >>> 24) == 0 ? 0 : frame[i];
                bytes[4 * i] = (byte) (c >> 16);
                bytes[4 * i + 1] = (byte) (c >> 8);
                bytes[4 * i + 2] = (byte) c;
                bytes[4 * i + 3] = (byte) (c >>> 24);
            }
            digest.update(bytes);
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b & 0xFF));
        return sb.substring(0, 32);
    }

    // ---------- 壊れたデータ ----------

    private static void expectIOException(String label, byte[] data) {
        try {
            WebpDecoder.decode(data);
            fail(label + ": decode() accepted invalid data");
        } catch (IOException e) {
            // 期待どおり
        } catch (Throwable t) {
            fail(label + ": decode() threw " + t);
        }
        try {
            WebpDecoder.decodeAnimated(data);
        } catch (IOException e) {
            // null を返すか、IOException なら良い
        } catch (Throwable t) {
            fail(label + ": decodeAnimated() threw " + t);
        }
    }

    private static void checkInvalidInputs(List<Sample> samples) {
        expectIOException("null", null);
        expectIOException("empty", new byte[0]);
        expectIOException("not webp", "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1));
        expectIOException("header only", "RIFF\4\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1));
        expectIOException("unknown chunk", "RIFF\24\0\0\0WEBPABCD\4\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        for (Sample s : samples) {
            // 途中で切ったもの (RIFF の大きさはそのまま)。どこで切っても例外は IOException だけ
            for (int length = 12; length < s.data.length; length += Math.max(1, s.data.length / 40)) {
                final byte[] cut = Arrays.copyOf(s.data, length);
                try {
                    WebpDecoder.decode(cut);
                    WebpDecoder.decodeAnimated(cut);
                } catch (IOException e) {
                    // 読めないのは良い
                } catch (Throwable t) {
                    fail(s.name + " truncated to " + length + ": " + t);
                }
            }
        }
        System.out.println("invalid inputs checked");
    }

    // ---------- わざと作った危ないデータ ----------

    // 下位ビットから順に書く (VP8L のビット列用)
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        BitWriter bits(int value, int n) {
            for (int i = 0; i < n; i++) {
                buffer |= (long) ((value >> i) & 1) << count;
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        // ハフマン符号は上位ビットから書く
        BitWriter code(int code, int length) {
            for (int i = length - 1; i >= 0; i--) bits((code >> i) & 1, 1);
            return this;
        }

        // 記号 1 つだけの符号 (短い書き方)
        BitWriter singleSymbol(int symbol) {
            return symbol < 2 ? bits(1, 1).bits(0, 1).bits(0, 1).bits(symbol, 1) : bits(1, 1).bits(0, 1).bits(1, 1).bits(symbol, 8);
        }

        byte[] bytes() {
            final ByteArrayOutputStream copy = new ByteArrayOutputStream();
            final byte[] written = out.toByteArray();
            copy.write(written, 0, written.length);
            if (count > 0) copy.write((int) buffer);
            return copy.toByteArray();
        }
    }

    private static BitWriter vp8lHeader(int width, int height) {
        return new BitWriter().bits(0x2f, 8).bits(width - 1, 14).bits(height - 1, 14).bits(0, 1).bits(0, 3);
    }

    private static byte[] chunk(String type, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] tag = type.getBytes(StandardCharsets.ISO_8859_1);
        out.write(tag, 0, 4);
        for (int i = 0; i < 4; i++) out.write(payload.length >> (8 * i));
        out.write(payload, 0, payload.length);
        if ((payload.length & 1) != 0) out.write(0);
        return out.toByteArray();
    }

    private static byte[] riff(byte[]... chunks) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('W');
        body.write('E');
        body.write('B');
        body.write('P');
        for (byte[] c : chunks) body.write(c, 0, c.length);
        final byte[] b = body.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('R');
        out.write('I');
        out.write('F');
        out.write('F');
        for (int i = 0; i < 4; i++) out.write(b.length >> (8 * i));
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static byte[] u24(int width, int height) {
        return new byte[]{(byte) width, (byte) (width >> 8), (byte) (width >> 16), (byte) height, (byte) (height >> 8), (byte) (height >> 16)};
    }

    // 1x1 の正しい VP8L (どの符号も記号 1 つだけ)
    private static byte[] tinyLossless() {
        final BitWriter w = vp8lHeader(1, 1).bits(0, 1).bits(0, 1).bits(0, 1);
        for (int i = 0; i < 5; i++) w.singleSymbol(i == 3 ? 255 : 7);
        return w.bytes();
    }

    // 指定した時間のうちに IOException になることを確かめる
    private static void expectRejected(String label, byte[] data, boolean animated) {
        final long start = System.nanoTime();
        try {
            if (animated) WebpDecoder.decodeAnimated(data);
            else WebpDecoder.decode(data);
            fail(label + ": accepted");
        } catch (IOException e) {
            final long ms = (System.nanoTime() - start) / 1000000;
            System.out.println("rejected " + label + " in " + ms + " ms: " + e.getMessage());
            if (ms > 5000) fail(label + ": took " + ms + " ms");
        } catch (Throwable t) {
            fail(label + ": threw " + t);
        }
    }

    private static void checkCraftedInputs() throws IOException {
        // 正しい 1x1 の画像は読める (以下の組み立て方が正しいことの確認)
        final byte[] tiny = riff(chunk("VP8L", tinyLossless()));
        final int pixel = WebpDecoder.decode(tiny).getRGB(0, 0);
        if (pixel != 0xff070707) fail("tiny lossless: " + Integer.toHexString(pixel));

        // CVE-2023-4863 と同じ形: 符号長の合計が多すぎる (色のキャッシュ 11 ビットで最大の表) ハフマン符号
        BitWriter w = vp8lHeader(1, 1).bits(0, 1).bits(1, 1).bits(11, 4).bits(0, 1);
        w.bits(0, 1).bits(15, 4);
        for (int i = 0; i < 19; i++) w.bits(i == 2 || i == 11 ? 1 : 0, 3);  // 符号長の記号 0 と 8 に長さ 1
        w.bits(0, 1);
        for (int i = 0; i < 256 + 24 + 2048; i++) w.code(1, 1);  // すべての記号に長さ 8
        expectRejected("over-subscribed prefix code", riff(chunk("VP8L", w.bytes())), false);

        // 足りない (木が埋まらない) ハフマン符号
        w = vp8lHeader(1, 1).bits(0, 1).bits(0, 1).bits(0, 1);
        w.bits(0, 1).bits(15, 4);
        for (int i = 0; i < 19; i++) w.bits(i == 2 || i == 11 ? 1 : 0, 3);
        w.bits(1, 1).bits(0, 3).bits(1, 2);  // 3 つだけ読む
        for (int i = 0; i < 3; i++) w.code(1, 1);
        expectRejected("incomplete prefix code", riff(chunk("VP8L", w.bytes())), false);

        // 符号長の符号そのものが足りない
        w = vp8lHeader(1, 1).bits(0, 1).bits(0, 1).bits(0, 1);
        w.bits(0, 1).bits(0, 4);
        for (int i = 0; i < 4; i++) w.bits(i >= 2 ? 2 : 0, 3);  // 長さ 2 の符号が 2 つだけ
        expectRejected("incomplete code length code", riff(chunk("VP8L", w.bytes())), false);

        // 色のキャッシュが 11 ビットを超える
        expectRejected("color cache bits", riff(chunk("VP8L", vp8lHeader(1, 1).bits(0, 1).bits(1, 1).bits(12, 4).bytes())), false);

        // 同じ変換が 2 回
        w = vp8lHeader(1, 1).bits(1, 1).bits(2, 2).bits(1, 1).bits(2, 2);
        expectRejected("duplicate transform", riff(chunk("VP8L", w.bytes())), false);

        // 大きすぎる画像
        expectRejected("huge lossless", riff(chunk("VP8L", vp8lHeader(16384, 16384).bits(0, 3).bytes())), false);

        // ハフマン符号の組の番号が 65535 (65536 組を読ませる) なのに、データがほとんどない
        w = vp8lHeader(4, 4).bits(0, 1).bits(0, 1).bits(1, 1).bits(0, 3);
        w.bits(0, 1).singleSymbol(255).singleSymbol(255).singleSymbol(0).singleSymbol(0).singleSymbol(0);
        expectRejected("too many prefix code groups", riff(chunk("VP8L", w.bytes())), false);

        // LZ77 の参照が画像の先頭より前を指す
        w = vp8lHeader(2, 1).bits(0, 1).bits(0, 1).bits(0, 1);
        // 緑: 記号 256 (長さ 1 の参照) だけ。符号長の記号 0 (長さ 1)、1 と 18 (長さ 2)
        w.bits(0, 1).bits(0, 4);
        w.bits(0, 3).bits(2, 3).bits(1, 3).bits(2, 3);  // 17: 0, 18: 2, 0: 1, 1: 2
        w.bits(1, 1).bits(0, 3).bits(1, 2);  // 3 つ読む
        w.code(3, 2).bits(127, 7).code(3, 2).bits(107, 7).code(2, 2);  // 0 を 138 個、118 個、そして長さ 1
        w.singleSymbol(0).singleSymbol(0).singleSymbol(0).singleSymbol(0);
        expectRejected("backward reference before start", riff(chunk("VP8L", w.bytes())), false);

        // VP8 で 8192x8192 と書いてあるのに、データがほとんどない
        final byte[] vp8 = new byte[40];
        final int tag = (16 << 5) | 0x10;
        vp8[0] = (byte) tag;
        vp8[1] = (byte) (tag >> 8);
        vp8[2] = (byte) (tag >> 16);
        vp8[3] = (byte) 0x9d;
        vp8[4] = 0x01;
        vp8[5] = 0x2a;
        vp8[6] = 0;
        vp8[7] = 0x20;
        vp8[8] = 0;
        vp8[9] = 0x20;
        expectRejected("huge lossy with little data", riff(chunk("VP8 ", vp8)), false);

        // アニメーション: キャンバスからはみ出すフレーム
        final byte[] frame = tinyLossless();
        final ByteArrayOutputStream anmf = new ByteArrayOutputStream();
        final byte[] head = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0};
        anmf.write(head, 0, head.length);
        final byte[] sub = chunk("VP8L", frame);
        anmf.write(sub, 0, sub.length);
        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x02;
        System.arraycopy(u24(0, 0), 0, vp8x, 4, 6);
        expectRejected("frame outside canvas", riff(chunk("VP8X", vp8x), chunk("ANIM", new byte[6]), chunk("ANMF", anmf.toByteArray())), true);

        // アニメーション: フレームが多すぎる
        final byte[] inside = anmf.toByteArray();
        inside[0] = 0;
        final byte[][] chunks = new byte[602][];
        chunks[0] = chunk("VP8X", vp8x);
        chunks[1] = chunk("ANIM", new byte[6]);
        for (int i = 2; i < chunks.length; i++) chunks[i] = chunk("ANMF", inside);
        final byte[] many = riff(chunks);
        expectRejected("too many frames", many, true);
        // 1 枚として読むときは最初のフレームだけを使うので読める
        if (WebpDecoder.decode(many).getRGB(0, 0) != 0xff070707) fail("first frame of many");
        System.out.println("crafted inputs checked");
    }

    // ---------- 壊したデータを読ませる ----------

    private static void fuzz(List<Sample> samples, int iterations) throws Exception {
        final ExecutorService executor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "webp-fuzz");
            t.setDaemon(true);
            return t;
        });
        final Random random = new Random(20261028L);
        int accepted = 0, rejected = 0;
        long slowest = 0;
        for (int n = 0; n < iterations; n++) {
            final Sample sample = samples.get(random.nextInt(samples.size()));
            final byte[] mutated = mutate(sample.data, random);
            final long start = System.nanoTime();
            final Future<Boolean> future = executor.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    WebpDecoder.decodeAnimated(mutated);
                    WebpDecoder.decode(mutated);
                    return Boolean.TRUE;
                }
            });
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                accepted++;
            } catch (TimeoutException e) {
                future.cancel(true);
                fail("timeout: " + sample.name + " iteration " + n);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    rejected++;
                } else {
                    fail(sample.name + " iteration " + n + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
            slowest = Math.max(slowest, System.nanoTime() - start);
        }
        executor.shutdownNow();
        System.out.println("fuzz: " + iterations + " inputs, accepted " + accepted + ", rejected " + rejected
                + ", slowest " + slowest / 1000000 + " ms");
    }

    // ランダムに壊す: ビットの反転、切り詰め、32 ビットの値の書き換え、バイトの挿入と削除、範囲の塗りつぶし
    private static byte[] mutate(byte[] original, Random random) {
        byte[] d = original.clone();
        final int kinds = 1 + random.nextInt(3);
        for (int k = 0; k < kinds; k++) {
            switch (random.nextInt(6)) {
                case 0: {
                    final int flips = 1 + random.nextInt(8);
                    for (int i = 0; i < flips; i++) {
                        final int p = random.nextInt(d.length);
                        d[p] ^= (byte) (1 << random.nextInt(8));
                    }
                    break;
                }
                case 1:
                    d = Arrays.copyOf(d, 12 + random.nextInt(Math.max(1, d.length - 12)));
                    break;
                case 2: {
                    // チャンクの大きさや画像の大きさになりそうな位置に、極端な値を書く
                    if (d.length < 16) break;
                    final int p = 4 + random.nextInt(d.length - 8);
                    final int[] values = {0, 1, 0x7fffffff, 0xffffffff, 0x3fff3fff, random.nextInt()};
                    final int v = values[random.nextInt(values.length)];
                    for (int i = 0; i < 4 && p + i < d.length; i++) d[p + i] = (byte) (v >> (8 * i));
                    break;
                }
                case 3: {
                    final int p = 12 + random.nextInt(Math.max(1, d.length - 12));
                    final int count = 1 + random.nextInt(16);
                    final byte[] grown = new byte[d.length + count];
                    System.arraycopy(d, 0, grown, 0, p);
                    for (int i = 0; i < count; i++) grown[p + i] = (byte) random.nextInt(256);
                    System.arraycopy(d, p, grown, p + count, d.length - p);
                    d = grown;
                    break;
                }
                case 4: {
                    if (d.length <= 13) break;
                    final int p = 12 + random.nextInt(d.length - 12);
                    final int count = Math.min(d.length - p, 1 + random.nextInt(16));
                    final byte[] shrunk = new byte[d.length - count];
                    System.arraycopy(d, 0, shrunk, 0, p);
                    System.arraycopy(d, p + count, shrunk, p, d.length - p - count);
                    d = shrunk;
                    break;
                }
                default: {
                    final int p = random.nextInt(d.length);
                    final int count = Math.min(d.length - p, 1 + random.nextInt(64));
                    final byte value = random.nextBoolean() ? (byte) random.nextInt(256) : 0;
                    for (int i = 0; i < count; i++) d[p + i] = random.nextBoolean() ? value : (byte) random.nextInt(256);
                    break;
                }
            }
        }
        return d;
    }
}
