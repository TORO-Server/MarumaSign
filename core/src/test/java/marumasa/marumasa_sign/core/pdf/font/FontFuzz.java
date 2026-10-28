package marumasa.marumasa_sign.core.pdf.font;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * フォントの読み込み (TrueType、OpenType の CFF、Type 1、CMap) に、壊したファイルを直接読ませる。
 * PDF の中のフォントは圧縮されていることが多く、PDF ごと壊すファジングではフォントの解析まで届きにくいので、こちらで試す。
 * <p>
 * 読めないフォントは FontFormatError (と IOException) で断ればよい。それ以外の例外、スタックやメモリの使い切り、
 * 長い時間 (1 つあたり 10 秒) を失敗にする。失敗した入力は build/font-fuzz に保存する。
 * <p>
 * 引数: 回数 ファイルまたはフォルダ... (.ttf .otf .ttc .pfb .pfa .cff と、CMap の文字 (.cmap か、拡張子のないもの))。
 * 乱数の種はシステムプロパティ fuzz.seed で決められる。
 */
public final class FontFuzz {

    private static final long CASE_TIMEOUT_SECONDS = 10;
    private static final long MAX_SEED_SIZE = 4L * 1024 * 1024;

    private FontFuzz() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("marumasign.pdfStrict", "true");
        final int iterations = Integer.parseInt(args[0]);
        final List<File> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), files);
        if (files.isEmpty()) {
            // フォントの入っていない環境 (最小限の CI の機械など) では試せない
            System.out.println("skip: no seed fonts were found in " + Arrays.toString(Arrays.copyOfRange(args, 1, args.length)));
            System.exit(0);
        }
        final long seed = Long.getLong("fuzz.seed", System.nanoTime());
        System.out.println("FontFuzz: " + iterations + " iterations over " + files.size() + " seeds, seed=" + seed);
        final Random random = new Random(seed);
        final List<byte[]> seeds = new ArrayList<>();
        for (File file : files) seeds.add(Files.readAllBytes(file.toPath()));
        ExecutorService executor = newExecutor();
        int failed = 0;
        long parsed = 0;
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            final int which = random.nextInt(seeds.size());
            final String name = files.get(which).getName();
            final byte[] data = mutate(seeds.get(which), random);
            final Future<Boolean> future = executor.submit(() -> exercise(name, data));
            String problem = null;
            try {
                if (future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) parsed++;
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                executor = newExecutor();
                problem = "timeout (" + CASE_TIMEOUT_SECONDS + " s)";
            } catch (ExecutionException e) {
                problem = describe(e.getCause());
            }
            if (problem != null) {
                failed++;
                final File dir = new File("build/font-fuzz");
                dir.mkdirs();
                final File out = new File(dir, "case-" + seed + "-" + i + "-" + name);
                Files.write(out.toPath(), data);
                System.out.println("FAIL " + name + " #" + i + ": " + problem + " -> " + out);
            }
        }
        executor.shutdownNow();
        System.out.println("FontFuzz finished in " + (System.nanoTime() - start) / 1_000_000_000 + " s, parsed " + parsed
                + ", failures: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "FontFuzz");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) collect(child, files);
        } else if (file.isFile() && file.length() > 0 && file.length() <= MAX_SEED_SIZE && kind(file.getName()) != null) {
            files.add(file);
        }
    }

    private static String kind(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")) return "sfnt";
        if (lower.endsWith(".pfb") || lower.endsWith(".pfa") || lower.endsWith(".t1")) return "type1";
        if (lower.endsWith(".cff")) return "cff";
        if (lower.endsWith(".cmap") || !lower.contains(".")) return "cmap";
        return null;
    }

    // 読んで、グリフの輪郭、幅、名前、文字の対応をひととおり使う。読めたら true、断られたら false
    private static boolean exercise(String name, byte[] data) {
        try {
            final String kind = kind(name);
            if (kind.equals("cmap")) {
                final CMap cmap = CMaps.parse(data, (name.hashCode() & 1) == 0);
                final int[] code = new int[2];
                final byte[] text = {0, 1, 0x20, 0x41, (byte) 0x81, 0x40, (byte) 0xFF, (byte) 0xFE};
                for (int p = 0; p < text.length; p += Math.max(1, code[1])) {
                    cmap.readCode(text, p, code);
                    cmap.lookup(code[0], code[1]);
                }
                return true;
            }
            final List<FontProgram> fonts = new ArrayList<>();
            if (kind.equals("type1")) {
                fonts.add(Type1Font.parse(data));
            } else if (kind.equals("cff")) {
                fonts.add(new CffFont(new FontBytes(data), null));
            } else if (data.length >= 16 && data[0] == 't' && data[1] == 't' && data[2] == 'c' && data[3] == 'f') {
                final FontBytes bytes = new FontBytes(data);
                final long count = Math.min(4, bytes.u32(8));
                for (int i = 0; i < count; i++) fonts.add(new TrueTypeFont(bytes, bytes.u32(12 + 4L * i)));
            } else {
                fonts.add(TrueTypeFont.parse(data));
            }
            for (FontProgram font : fonts) use(font);
            return true;
        } catch (FontBytes.FontFormatError e) {
            return false;
        }
    }

    private static void use(FontProgram font) {
        font.postScriptName();
        final int count = font.glyphCount();
        // 多いフォントは、先頭と、間を飛ばしたものだけ (1 つの場合の時間を抑える)
        final int step = Math.max(1, count / 400);
        for (int gid = -1; gid <= count; gid += gid < 40 ? 1 : step) {
            font.path(gid).getBounds2D();
            font.advance(gid);
            final String glyph = font.nameForGid(gid);
            if (glyph != null) font.gidForName(glyph);
        }
        font.gidForName(".notdef");
        font.gidForName("A");
        if (font instanceof UnicodeFont) {
            for (int c : new int[]{0x20, 0x41, 0x3042, 0x4E00, 0xFFFF, 0x1F600, -1, Integer.MAX_VALUE}) {
                ((UnicodeFont) font).unicodeLookup(c);
            }
        }
        if (font instanceof TrueTypeFont) {
            final TrueTypeFont ttf = (TrueTypeFont) font;
            for (int code : new int[]{0x20, 0x41, 0xF041, 0x100, -1}) {
                ttf.cmapLookup(3, 0, code);
                ttf.cmapLookup(1, 0, code);
            }
        }
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        final StringBuilder text = new StringBuilder(String.valueOf(e));
        if (root != e) text.append(" / root ").append(root);
        final StackTraceElement[] trace = root.getStackTrace();
        for (int i = 0; i < Math.min(8, trace.length); i++) text.append("\n    at ").append(trace[i]);
        return text.toString();
    }

    /**
     * 壊し方: バイトを変える、途中で切る、2 バイトや 4 バイトの数を極端な値にする、一部を写して挟む。
     * フォントの表は 2 バイトと 4 バイトの数でできているので、極端な数を多めに入れる。
     */
    static byte[] mutate(byte[] seed, Random random) {
        byte[] data = seed.clone();
        final int count = 1 + random.nextInt(6);
        for (int n = 0; n < count && data.length > 4; n++) {
            switch (random.nextInt(6)) {
                case 0:
                    for (int k = 0, flips = 1 + random.nextInt(32); k < flips; k++) data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                    break;
                case 1:
                    data = Arrays.copyOf(data, 4 + random.nextInt(data.length - 4));
                    break;
                case 2: {
                    // 先頭の 4 KB (表の一覧や見出し) を重点的に壊す
                    final int limit = Math.min(4096, data.length);
                    for (int k = 0, flips = 1 + random.nextInt(8); k < flips; k++) data[random.nextInt(limit)] = (byte) random.nextInt(256);
                    break;
                }
                case 3: {
                    final int p = random.nextInt(data.length - 1);
                    final int[] extremes = {0, 0xFF, 0x7F, 0x80};
                    final int v = extremes[random.nextInt(extremes.length)];
                    data[p] = (byte) v;
                    data[p + 1] = (byte) (random.nextBoolean() ? v : random.nextInt(256));
                    break;
                }
                case 4: {
                    final int p = random.nextInt(Math.max(1, data.length - 3));
                    final long[] extremes = {0, 0xFFFFFFFFL, 0x7FFFFFFF, 0x80000000L, 0x10000, 0xFFFF, 1};
                    final long v = extremes[random.nextInt(extremes.length)];
                    for (int k = 0; k < 4 && p + k < data.length; k++) data[p + k] = (byte) (v >>> (24 - 8 * k));
                    break;
                }
                default: {
                    final int from = random.nextInt(data.length);
                    final int length = Math.min(data.length - from, 1 + random.nextInt(512));
                    final int to = random.nextInt(data.length + 1);
                    final byte[] out = new byte[data.length + length];
                    System.arraycopy(data, 0, out, 0, to);
                    System.arraycopy(data, from, out, to, length);
                    System.arraycopy(data, to, out, to + length, data.length - to);
                    data = out;
                    break;
                }
            }
        }
        return data;
    }
}
