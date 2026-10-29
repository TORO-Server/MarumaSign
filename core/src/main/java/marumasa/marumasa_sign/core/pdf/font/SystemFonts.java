package marumasa.marumasa_sign.core.pdf.font;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.ref.SoftReference;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * パソコンに入っているフォントの一覧と、PDF に埋め込まれていないフォントの代わりを選ぶ規則。
 * <p>
 * 一覧を作るときは、代わりを選ぶのに使う表 (name、OS/2、head と、CFF の先頭) だけを読む。
 * 読んだ結果はファイルの大きさと更新日時と一緒に保存しておき、次からは変わったファイルだけを読む。
 * フォントの中身は、実際に使うときに読む (大きなファイルはメモリに読み込まず、ファイルを割り当てて読む)。
 * <p>
 * 代わりの選び方は、PDF の読み手 (Apache PDFBox 2.0 の FontMapperImpl) の規則に合わせている:
 * PostScript 名で探し、なければ別名 (Arial と Helvetica など) で探し、なければフォントの性質 (固定幅、セリフ、太字、斜体) から
 * 標準 14 フォントの代わりを選ぶ。CJK のフォントは、文字の集まり (Adobe-Japan1 など) と太さで選ぶ。
 */
public final class SystemFonts {

    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    // name 表の、読む大きさの上限 (壊れたファイルで大量に読まないように)
    private static final int MAX_NAME_RECORDS = 4096;
    // メモリに読み込む Type 1 フォント (.pfb) の大きさの上限
    private static final int MAX_TYPE1_SIZE = 16 * 1024 * 1024;

    private static SystemFonts instance;

    private final List<Info> fonts = new ArrayList<>();
    // 小文字の PostScript 名 (と、ハイフンを除いたもの) からフォント
    private final Map<String, Info> byName = new LinkedHashMap<>();

    /**
     * パソコンのフォントの一覧 (初めて使うときに作る)。
     */
    public static synchronized SystemFonts get() {
        if (instance == null) {
            final long start = System.nanoTime();
            File cache = null;
            try {
                cache = MarumaSign.platform() == null ? null : new File(MarumaSign.platform().gameDirectory(), "marumasign/pdf-fonts.cache");
            } catch (RuntimeException e) {
                cache = null;
            }
            instance = create(cache);
            MarumaSign.LOGGER.info("Found " + instance.fonts.size() + " system fonts for PDF in "
                    + (System.nanoTime() - start) / 1000000 + " ms");
        }
        return instance;
    }

    /**
     * パソコンのフォントの一覧を作る。
     *
     * @param cache 前に読んだ結果を保存するファイル (なければ null)
     */
    static SystemFonts create(File cache) {
        final SystemFonts provider = new SystemFonts();
        try {
            provider.scan(findFiles(), cache);
        } catch (RuntimeException e) {
            MarumaSign.LOGGER.warn("Could not list the system fonts for PDF: " + e);
        }
        for (Info info : provider.fonts) {
            provider.byName.put(info.postScriptName.toLowerCase(Locale.ENGLISH), info);
            provider.byName.put(info.postScriptName.replace("-", "").toLowerCase(Locale.ENGLISH), info);
        }
        return provider;
    }

    List<Info> fonts() {
        return fonts;
    }

    // ---------- 代わりの選び方 ----------

    // 標準 14 フォントの代わり (よくあるパソコンのフォントの名前)
    private static final Map<String, String[]> SUBSTITUTES = new HashMap<>();

    static {
        final String[][] table = {
                {"Courier", "CourierNew", "CourierNewPSMT", "LiberationMono", "NimbusMonL-Regu", "NimbusMonoPS-Regular", "DejaVuSansMono"},
                {"Courier-Bold", "CourierNewPS-BoldMT", "CourierNew-Bold", "LiberationMono-Bold", "NimbusMonL-Bold", "NimbusMonoPS-Bold", "DejaVuSansMono-Bold"},
                {"Courier-Oblique", "CourierNewPS-ItalicMT", "CourierNew-Italic", "LiberationMono-Italic", "NimbusMonL-ReguObli", "NimbusMonoPS-Italic", "DejaVuSansMono-Oblique"},
                {"Courier-BoldOblique", "CourierNewPS-BoldItalicMT", "CourierNew-BoldItalic", "LiberationMono-BoldItalic", "NimbusMonL-BoldObli", "NimbusMonoPS-BoldItalic", "DejaVuSansMono-BoldOblique"},
                {"Helvetica", "ArialMT", "Arial", "LiberationSans", "NimbusSanL-Regu", "NimbusSans-Regular", "DejaVuSans"},
                {"Helvetica-Bold", "Arial-BoldMT", "Arial-Bold", "LiberationSans-Bold", "NimbusSanL-Bold", "NimbusSans-Bold", "DejaVuSans-Bold"},
                {"Helvetica-Oblique", "Arial-ItalicMT", "Arial-Italic", "Helvetica-Italic", "LiberationSans-Italic", "NimbusSanL-ReguItal", "NimbusSans-Italic", "DejaVuSans-Oblique"},
                {"Helvetica-BoldOblique", "Arial-BoldItalicMT", "Helvetica-BoldItalic", "LiberationSans-BoldItalic", "NimbusSanL-BoldItal", "NimbusSans-BoldItalic", "DejaVuSans-BoldOblique"},
                {"Times-Roman", "TimesNewRomanPSMT", "TimesNewRoman", "TimesNewRomanPS", "LiberationSerif", "NimbusRomNo9L-Regu", "NimbusRoman-Regular", "DejaVuSerif"},
                {"Times-Bold", "TimesNewRomanPS-BoldMT", "TimesNewRomanPS-Bold", "TimesNewRoman-Bold", "LiberationSerif-Bold", "NimbusRomNo9L-Medi", "NimbusRoman-Bold", "DejaVuSerif-Bold"},
                {"Times-Italic", "TimesNewRomanPS-ItalicMT", "TimesNewRomanPS-Italic", "TimesNewRoman-Italic", "LiberationSerif-Italic", "NimbusRomNo9L-ReguItal", "NimbusRoman-Italic", "DejaVuSerif-Italic"},
                {"Times-BoldItalic", "TimesNewRomanPS-BoldItalicMT", "TimesNewRomanPS-BoldItalic", "TimesNewRoman-BoldItalic", "LiberationSerif-BoldItalic", "NimbusRomNo9L-MediItal", "NimbusRoman-BoldItalic", "DejaVuSerif-BoldItalic"},
                {"Symbol", "Symbol", "SymbolMT", "StandardSymL", "StandardSymbolsPS"},
                {"ZapfDingbats", "ZapfDingbatsITCbyBT-Regular", "ZapfDingbatsITC", "Dingbats", "D050000L", "MS-Gothic", "DejaVuSans"}};
        for (String[] row : table) SUBSTITUTES.put(row[0].toLowerCase(Locale.ENGLISH), java.util.Arrays.copyOfRange(row, 1, row.length));
    }

    private Info info(Format format, String postScriptName) {
        final int plus = postScriptName.indexOf('+');
        if (plus >= 0) postScriptName = postScriptName.substring(plus + 1);
        final Info info = byName.get(postScriptName.toLowerCase(Locale.ENGLISH));
        return info != null && (format == null || info.format == format) ? info : null;
    }

    private static String[] substitutes(String postScriptName) {
        String key = postScriptName.replace(" ", "");
        // 標準 14 フォントの別名 (Arial,Bold など) は、正式な名前の代わりを使う
        final String standard = FontData.standard14Name(key);
        if (standard != null) key = standard;
        final String[] result = SUBSTITUTES.get(key.toLowerCase(Locale.ENGLISH));
        return result == null ? new String[0] : result;
    }

    // 名前で探す (名前のままと、ハイフンを除いたもの、決まった代わり、Windows の書き方 (Arial,Bold) を直したもの)
    private FontProgram find(Format format, String postScriptName) {
        if (postScriptName == null || postScriptName.isEmpty()) return null;
        FontProgram font = load(info(format, postScriptName));
        if (font != null) return font;
        font = load(info(format, postScriptName.replace("-", "")));
        if (font != null) return font;
        for (String substitute : substitutes(postScriptName)) {
            font = load(info(format, substitute));
            if (font != null) return font;
        }
        font = load(info(format, postScriptName.replace(",", "-")));
        if (font != null) return font;
        String base = postScriptName;
        if (base.contains(",")) {
            base = base.substring(0, base.indexOf(','));
            font = load(info(format, base));
            if (font != null) return font;
        }
        return load(info(format, base + "-Regular"));
    }

    private static FontProgram load(Info info) {
        return info == null ? null : info.font();
    }

    /**
     * 名前で探す。形式は Type 1、TrueType、OpenType の順に試す。見つからなければ null。
     *
     * @param trueTypeFirst TrueType を先に試す (PDF のフォントが TrueType のとき)
     */
    public FontProgram findByName(String postScriptName, boolean trueTypeFirst) {
        final Format[] order = trueTypeFirst ? new Format[]{Format.TTF, Format.OTF, Format.PFB} : new Format[]{Format.PFB, Format.TTF, Format.OTF};
        for (Format format : order) {
            final FontProgram font = find(format, postScriptName);
            if (font != null) return font;
        }
        return null;
    }

    /**
     * フォントの性質から、標準 14 フォントのどれかの代わりを選ぶ名前。
     *
     * @param heavy FontDescriptor の太さ (FontWeight や ForceBold) が太字を示すか。名前に Bold などがあっても太字にする
     */
    public static String fallbackName(String fontName, boolean hasDescriptor, boolean fixedPitch, boolean serif, boolean italic, boolean heavy) {
        if (!hasDescriptor) return "Times-Roman";
        final String lower = fontName == null ? "" : fontName.toLowerCase(Locale.ENGLISH);
        final boolean bold = heavy || lower.contains("bold") || lower.contains("black") || lower.contains("heavy") || lower.contains("demi");
        if (fixedPitch) {
            return "Courier" + (bold && italic ? "-BoldOblique" : bold ? "-Bold" : italic ? "-Oblique" : "");
        }
        if (serif) {
            return "Times" + (bold && italic ? "-BoldItalic" : bold ? "-Bold" : italic ? "-Italic" : "-Roman");
        }
        return "Helvetica" + (bold && italic ? "-BoldOblique" : bold ? "-Bold" : italic ? "-Oblique" : "");
    }

    /**
     * 最後の手段: よくある欧文のフォント。なければ、Unicode の文字の対応を持つ最初のフォント。それもなければ null。
     */
    public synchronized FontProgram lastResort() {
        for (String name : new String[]{"LiberationSans", "ArialMT", "Arial", "DejaVuSans", "Helvetica", "NimbusSans-Regular",
                "FreeSans", "NotoSans-Regular", "Roboto-Regular", "SegoeUI", "Verdana"}) {
            final FontProgram font = findByName(name, true);
            if (font != null) return font;
        }
        for (Info info : fonts) {
            if (info.format == Format.PFB) continue;
            final FontProgram font = info.font();
            if (font instanceof TrueTypeFont && ((TrueTypeFont) font).unicodeLookup('A') > 0) return font;
        }
        return null;
    }

    /**
     * CJK の CID フォントの代わり: 名前で探し、なければ文字の集まり (Adobe-Japan1 など) を持つフォントから、太さが近いものを選ぶ。
     *
     * @param weight     FontDescriptor の FontWeight (なければ 0)
     * @param panose     FontDescriptor の Style の Panose (なければ null)
     * @param fontFamily FontDescriptor の FontFamily (なければ null)
     */
    public FontProgram findCid(String baseFont, int weight, byte[] panose, String fontFamily, String registry, String ordering) {
        FontProgram font = find(Format.OTF, baseFont);
        if (font == null) font = find(Format.TTF, baseFont);
        if (font != null) return font;
        if (registry == null || ordering == null) return null;
        final String collection = registry + "-" + ordering;
        if (!collection.equals("Adobe-GB1") && !collection.equals("Adobe-CNS1") && !collection.equals("Adobe-Japan1")
                && !collection.equals("Adobe-Korea1")) {
            return null;
        }
        final boolean barcode = probablyBarcode(baseFont, fontFamily);
        Info best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Info info : byName.values()) {
            if (!isCharSetMatch(info, registry, ordering)) continue;
            double score = 0;
            if (panose != null && panose.length >= 10 && info.panose != null) {
                final int family = panose[0], infoFamily = info.panose[0];
                if (family == infoFamily) {
                    if (family == 0 && (info.postScriptName.toLowerCase(Locale.ENGLISH).contains("barcode")
                            || info.postScriptName.startsWith("Code")) && !barcode) continue;
                    final int serif = panose[1], infoSerif = info.panose[1];
                    if (serif == infoSerif) score += 2;
                    else if (serif >= 2 && serif <= 5 && infoSerif >= 2 && infoSerif <= 5) score += 1;
                    else if (serif >= 11 && serif <= 13 && infoSerif >= 11 && infoSerif <= 13) score += 1;
                    else if (serif != 0 && infoSerif != 0) score -= 1;
                    int infoWeight = info.panose[2];
                    final int weightClass = info.weightClassAsPanose();
                    if (Math.abs(infoWeight - weightClass) > 2) infoWeight = weightClass;
                    if (panose[2] == infoWeight) score += 2;
                    else if (panose[2] > 1 && infoWeight > 1) score += 1 - Math.abs(panose[2] - infoWeight) * 0.5;
                }
            } else if (weight > 0 && info.weightClass > 0) {
                score += 1 - (Math.abs(weight - info.weightClass) / 100.0) * 0.5;
            }
            // 同じ点数なら、一覧の先のもの (毎回同じ結果になるように)
            if (score > bestScore) {
                final FontProgram candidate = info.font();
                if (candidate == null) continue;
                best = info;
                bestScore = score;
            }
        }
        return best == null ? null : best.font();
    }

    private static boolean probablyBarcode(String fontName, String fontFamily) {
        final String family = fontFamily == null ? "" : fontFamily;
        final String name = fontName == null ? "" : fontName;
        return family.startsWith("Code") || family.toLowerCase(Locale.ENGLISH).contains("barcode")
                || name.startsWith("Code") || name.toLowerCase(Locale.ENGLISH).contains("barcode");
    }

    private static boolean isCharSetMatch(Info info, String registry, String ordering) {
        if (info.ros != null) return info.ros[0].equals(registry) && info.ros[1].equals(ordering);
        long range = info.codePageRange();
        final long japanese = 1 << 17, simplified = 1 << 18, wansung = 1 << 19, traditional = 1 << 20, johab = 1 << 21;
        // このフォントは韓国語だけを持つが、ほかの言語の印も付いている
        if ("MalgunGothic-Semilight".equals(info.postScriptName)) range &= ~(japanese | simplified | traditional);
        switch (ordering) {
            case "GB1":
                return (range & simplified) != 0;
            case "CNS1":
                return (range & traditional) != 0;
            case "Japan1":
                return (range & japanese) != 0;
            case "Korea1":
                return (range & wansung) != 0 || (range & johab) != 0;
            default:
                return false;
        }
    }

    // フォントのあるフォルダ (OS ごと)
    private static List<File> fontDirectories() {
        final String os = System.getProperty("os.name", "");
        final String home = System.getProperty("user.home", "");
        final List<File> directories = new ArrayList<>();
        if (os.startsWith("Windows")) {
            String windows = System.getenv("WINDIR");
            if (windows == null) windows = System.getenv("SystemRoot");
            if (windows == null) windows = "C:\\Windows";
            directories.add(new File(windows, "Fonts"));
            final String local = System.getenv("LOCALAPPDATA");
            if (local != null) directories.add(new File(local, "Microsoft\\Windows\\Fonts"));
        } else if (os.startsWith("Mac")) {
            directories.add(new File(home, "Library/Fonts"));
            directories.add(new File("/Library/Fonts"));
            directories.add(new File("/System/Library/Fonts"));
            directories.add(new File("/Network/Library/Fonts"));
        } else {
            directories.add(new File(home, ".fonts"));
            directories.add(new File(home, ".local/share/fonts"));
            directories.add(new File("/usr/local/fonts"));
            directories.add(new File("/usr/local/share/fonts"));
            directories.add(new File("/usr/share/fonts"));
            directories.add(new File("/usr/X11R6/lib/X11/fonts"));
        }
        final List<File> existing = new ArrayList<>();
        for (File directory : directories) {
            if (directory.isDirectory()) existing.add(directory);
        }
        return existing;
    }

    /**
     * パソコンのフォントファイルを、大きさと更新日時と一緒に探す。
     * ファイルごとにフォルダかどうかを問い合わせると時間がかかる (6000 個ほどで 1 秒) ので、
     * フォルダの一覧を読むときに一緒に得られる情報を使う (同じ数で 40 ms ほど)。
     */
    static List<CachedFile> findFiles() {
        final List<CachedFile> files = new ArrayList<>();
        for (File directory : fontDirectories()) {
            final Path root = directory.toPath();
            try {
                // リンクの先もたどる (フォントの管理ソフトがリンクを置くことがある)
                Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                        // 名前が "." で始まるフォルダは見ない
                        return !path.equals(root) && path.getFileName().toString().startsWith(".")
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                        final String name = path.getFileName().toString().toLowerCase(Locale.US);
                        if (!attributes.isDirectory() && !name.startsWith("fonts.") && (name.endsWith(".ttf")
                                || name.endsWith(".otf") || name.endsWith(".pfb") || name.endsWith(".ttc"))) {
                            files.add(new CachedFile(path.toFile().getPath(), attributes.size(),
                                    attributes.lastModifiedTime().toMillis()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path path, IOException e) {
                        // 読めないファイルやフォルダは飛ばす (FontFileFinder も飛ばす)
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException e) {
                // このフォルダは読めない。ほかのフォルダは使える
            }
        }
        return files;
    }

    /**
     * フォントファイルを読んで一覧を作る。
     *
     * @param files findFiles で探したファイル
     * @param cache 前に読んだ結果を保存するファイル (なければ null)。大きさと更新日時が同じファイルは読み直さない
     */
    private void scan(List<CachedFile> files, File cache) {
        final Map<String, CachedFile> cached = cache == null ? new HashMap<String, CachedFile>() : readCache(cache);
        final List<CachedFile> scanned = new ArrayList<>();
        boolean changed = cached.size() != files.size();
        // 前に読んだ結果がないか、変わったファイルは読み直す。ファイルを開くたびに待ち時間があるので (ウイルス対策の検査など)、
        // いくつかのスレッドで同時に読む。代わりを選ぶ結果が毎回同じになるよう、結果はファイルの順に並べる
        final List<Future<List<Info>>> reads = new ArrayList<>();
        ExecutorService readers = null;
        try {
            for (CachedFile entry : files) {
                final CachedFile previous = cached.get(entry.path);
                if (previous != null && previous.size == entry.size && previous.modified == entry.modified) {
                    reads.add(null);
                    continue;
                }
                if (readers == null) {
                    readers = Executors.newFixedThreadPool(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())),
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    final Thread thread = new Thread(r, "MarumaSign-Fonts");
                                    thread.setDaemon(true);
                                    return thread;
                                }
                            });
                }
                final File file = new File(entry.path);
                reads.add(readers.submit(new Callable<List<Info>>() {
                    @Override
                    public List<Info> call() {
                        final SystemFonts part = new SystemFonts();
                        part.read(file);
                        return part.fonts;
                    }
                }));
            }
            for (int index = 0; index < files.size(); index++) {
                final CachedFile entry = files.get(index);
                final File file = new File(entry.path);
                final Future<List<Info>> read = reads.get(index);
                if (read == null) {
                    final CachedFile previous = cached.get(entry.path);
                    for (String[] line : previous.lines) {
                        final Info info = Info.parse(file, line);
                        if (info != null) fonts.add(info);
                    }
                    entry.lines.addAll(previous.lines);
                } else {
                    changed = true;
                    List<Info> found;
                    try {
                        found = read.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExecutionException e) {
                        // 読めないフォントは一覧に入れない
                        found = new ArrayList<>();
                    }
                    for (Info info : found) {
                        fonts.add(info);
                        entry.lines.add(info.fields());
                    }
                }
                scanned.add(entry);
            }
        } finally {
            if (readers != null) readers.shutdownNow();
        }
        if (cache != null && changed) writeCache(cache, scanned);
    }

    private void read(File file) {
        final String path = file.getPath().toLowerCase(Locale.ROOT);
        try {
            if (path.endsWith(".ttf") || path.endsWith(".otf")) {
                try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                    addFont(in, file, 0, path.endsWith(".otf"), false);
                }
            } else if (path.endsWith(".ttc") || path.endsWith(".otc")) {
                addCollection(file);
            } else if (path.endsWith(".pfb")) {
                addType1(file);
            }
        } catch (IOException | RuntimeException e) {
            // 読めないフォントは一覧に入れない
        }
    }

    // ---------- 前に読んだ結果 ----------

    // 1 つのフォントファイルと、その中のフォントの項目 (Info.fields の形)
    static final class CachedFile {
        final String path;
        final long size, modified;
        final List<String[]> lines = new ArrayList<>();

        CachedFile(String path, long size, long modified) {
            this.path = path;
            this.size = size;
            this.modified = modified;
        }
    }

    // 形式: ファイルごとに "F <tab> パス <tab> 大きさ <tab> 更新日時" の行と、その中のフォントごとに "<tab> 項目..." の行
    private static final String CACHE_HEADER = "MarumaSign PDF fonts 1";

    private static Map<String, CachedFile> readCache(File cache) {
        final Map<String, CachedFile> result = new HashMap<>();
        if (!cache.isFile()) return result;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(cache), java.nio.charset.StandardCharsets.UTF_8))) {
            if (!CACHE_HEADER.equals(reader.readLine())) return result;
            CachedFile current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                final String[] parts = line.split("\t", -1);
                if (parts.length == 4 && parts[0].equals("F")) {
                    current = new CachedFile(parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
                    result.put(current.path, current);
                } else if (current != null && parts.length > 1 && parts[0].isEmpty()) {
                    current.lines.add(java.util.Arrays.copyOfRange(parts, 1, parts.length));
                }
            }
        } catch (IOException | RuntimeException e) {
            // 読めなければ、すべて読み直す
            result.clear();
        }
        return result;
    }

    private static void writeCache(File cache, List<CachedFile> files) {
        final File parent = cache.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        final File temporary = new File(cache.getPath() + ".tmp");
        try (java.io.Writer writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(temporary), java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write(CACHE_HEADER + "\n");
            for (CachedFile file : files) {
                if (file.path.contains("\t") || file.path.contains("\n")) continue;
                writer.write("F\t" + file.path + "\t" + file.size + "\t" + file.modified + "\n");
                for (String[] line : file.lines) {
                    writer.write("\t" + String.join("\t", line) + "\n");
                }
            }
        } catch (IOException e) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(cache)) {
            cache.delete();
            if (!temporary.renameTo(cache)) temporary.delete();
        }
    }

    private void addCollection(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (readInt(in, 0) != 0x74746366) return; // "ttcf"
            final int count = readInt(in, 8);
            if (count <= 0 || count > 1024) return;
            final long[] offsets = new long[count];
            for (int i = 0; i < count; i++) offsets[i] = readInt(in, 12 + 4L * i) & 0xFFFFFFFFL;
            for (long offset : offsets) {
                try {
                    // 集合の中のフォントは、"OTTO" で始まるものだけ OpenType として読まれる
                    addFont(in, file, offset, readInt(in, offset) == 0x4F54544F, true);
                } catch (IOException | RuntimeException e) {
                    // 1 つ読めなくても、ほかのフォントは使える
                }
            }
        }
    }

    /**
     * 1 つのフォントを一覧に加える。輪郭や文字の対応の表が足りないもの、名前のないものは除く。
     *
     * @param openType CFF の輪郭を持つ OpenType として扱うか (拡張子が .otf か、集合の中で "OTTO" で始まるもの)
     */
    private void addFont(RandomAccessFile in, File file, long start, boolean openType, boolean collection) throws IOException {
        final int version = readInt(in, start);
        final int tableCount = readShort(in, start + 4);
        final Map<String, long[]> tables = new HashMap<>();
        final byte[] directory = read(in, start + 12, 16 * tableCount);
        for (int i = 0; i < tableCount; i++) {
            final int o = 16 * i;
            final String tag = new String(directory, o, 4, ISO_8859_1);
            tables.put(tag, new long[]{u32(directory, o + 8), u32(directory, o + 12)});
        }
        // 文字を描くのに必ず要る表
        for (String tag : new String[]{"head", "hhea", "maxp", "post", "name", "hmtx", "cmap"}) {
            if (!tables.containsKey(tag)) return;
        }
        final boolean hasCff = tables.containsKey("CFF ");
        final boolean postScript = openType ? version == 0x4F54544F || hasCff || tables.containsKey("CFF2") : hasCff;
        if (!postScript) {
            if (!tables.containsKey("loca") || !tables.containsKey("glyf")) return;
        } else if (!openType) {
            return;
        }

        final String name = postScriptName(in, tables.get("name"));
        if (!usableName(name)) return;

        final long[] head = tables.get("head");
        final int macStyle = head[1] >= 46 ? readShort(in, head[0] + 44) : 0;
        int familyClass = -1, weightClass = -1, codePage1 = 0, codePage2 = 0;
        byte[] panose = null;
        final long[] os2 = tables.get("OS/2");
        if (os2 != null && os2[1] >= 42) {
            final byte[] data = read(in, os2[0], (int) Math.min(os2[1], 86));
            final int os2Version = u16(data, 0);
            weightClass = u16(data, 4);
            familyClass = (short) u16(data, 30);
            panose = new byte[10];
            System.arraycopy(data, 32, panose, 0, 10);
            if (os2Version >= 1 && data.length >= 86) {
                codePage1 = (int) u32(data, 78);
                codePage2 = (int) u32(data, 82);
            }
        }

        String[] ros = null;
        if (postScript && hasCff) {
            try {
                ros = cffRos(in, tables.get("CFF "));
            } catch (IOException | RuntimeException e) {
                // ROS が分からなくても、名前で探すことはできる
            }
        }
        fonts.add(new Info(file, collection, postScript ? Format.OTF : Format.TTF, name, ros,
                weightClass, familyClass, codePage1, codePage2, macStyle, panose));
    }

    // 保存の形に合わない、"|"、改行、タブを含む名前のフォントは除く
    private static boolean usableName(String name) {
        return name != null && name.indexOf('|') < 0 && name.indexOf('\t') < 0 && name.indexOf('\n') < 0 && name.indexOf('\r') < 0;
    }

    // name 表の PostScript 名。Mac の英語、Windows の英語 (米国) の順に探す
    private static String postScriptName(RandomAccessFile in, long[] table) throws IOException {
        final long base = table[0];
        final int count = readShort(in, base + 2);
        final int stringOffset = readShort(in, base + 4);
        if (count > MAX_NAME_RECORDS) return null;
        final byte[] records = read(in, base + 6, 12 * count);
        String windows = null;
        for (int i = 0; i < count; i++) {
            final int o = 12 * i;
            final int platform = u16(records, o), encoding = u16(records, o + 2), language = u16(records, o + 4);
            final int nameId = u16(records, o + 6), length = u16(records, o + 8), offset = u16(records, o + 10);
            if (nameId != 6) continue;
            if (platform == 1 && encoding == 0 && language == 0) {
                final String mac = new String(read(in, base + stringOffset + offset, length), ISO_8859_1).trim();
                return mac;
            }
            if (platform == 3 && encoding == 1 && language == 0x409 && windows == null) {
                windows = new String(read(in, base + stringOffset + offset, length), UTF_16BE).trim();
            }
        }
        return windows;
    }

    // CFF の先頭の辞書にある ROS (CID フォントの文字の集まり)。CID フォントでなければ null
    private static String[] cffRos(RandomAccessFile in, long[] table) throws IOException {
        final long base = table[0];
        final int headerSize = in.length() > base + 2 ? read(in, base + 2, 1)[0] & 0xFF : 0;
        final long[] names = index(in, base + headerSize);
        final long[] topDicts = index(in, names[0]);
        if (topDicts.length < 3) return null;
        final long[] strings = index(in, topDicts[0]);
        final byte[] dict = read(in, topDicts[1], (int) Math.min(topDicts[2] - topDicts[1], 65536));
        final List<Number> operands = new ArrayList<>();
        int i = 0;
        while (i < dict.length) {
            final int b0 = dict[i] & 0xFF;
            if (b0 == 12) {
                if (i + 1 >= dict.length) return null;
                if ((dict[i + 1] & 0xFF) == 30 && operands.size() >= 3) {
                    final String registry = sid(in, strings, operands.get(0).intValue());
                    final String ordering = sid(in, strings, operands.get(1).intValue());
                    if (registry == null || ordering == null) return null;
                    return new String[]{registry, ordering, Integer.toString(operands.get(2).intValue())};
                }
                operands.clear();
                i += 2;
            } else if (b0 <= 21) {
                operands.clear();
                i++;
            } else if (b0 == 28) {
                operands.add((short) (((dict[i + 1] & 0xFF) << 8) | (dict[i + 2] & 0xFF)));
                i += 3;
            } else if (b0 == 29) {
                operands.add((int) u32(dict, i + 1));
                i += 5;
            } else if (b0 == 30) {
                // 実数は ROS には使われないので、読み飛ばす
                i++;
                while (i < dict.length && (dict[i] & 0x0F) != 0x0F && (dict[i] & 0xF0) != 0xF0) i++;
                i++;
                operands.add(0);
            } else if (b0 >= 32 && b0 <= 246) {
                operands.add(b0 - 139);
                i++;
            } else if (b0 >= 247 && b0 <= 250) {
                operands.add((b0 - 247) * 256 + (dict[i + 1] & 0xFF) + 108);
                i += 2;
            } else if (b0 >= 251 && b0 <= 254) {
                operands.add(-(b0 - 251) * 256 - (dict[i + 1] & 0xFF) - 108);
                i += 2;
            } else {
                return null;
            }
        }
        return null;
    }

    // CFF の文字列。391 番より前は決まった文字列だが、ROS には使われない
    private static String sid(RandomAccessFile in, long[] strings, int sid) throws IOException {
        final int index = sid - 391;
        if (index < 0 || index + 2 >= strings.length) return null;
        final long from = strings[index + 1], to = strings[index + 2];
        if (to < from || to - from > 1024) return null;
        return new String(read(in, from, (int) (to - from)), ISO_8859_1);
    }

    /**
     * CFF の INDEX。{次の位置, 1 つ目の始まり, 2 つ目の始まり, ..., 最後の終わり} を返す。
     */
    private static long[] index(RandomAccessFile in, long position) throws IOException {
        final int count = readShort(in, position);
        if (count == 0) return new long[]{position + 2};
        final int offSize = read(in, position + 2, 1)[0] & 0xFF;
        if (offSize < 1 || offSize > 4) throw new IOException("Bad CFF INDEX");
        // 必要なのは先頭のいくつかだけだが、終わりの位置を知るために最後の offset も読む
        final int wanted = Math.min(count, 16);
        final long dataStart = position + 3 + (long) (count + 1) * offSize - 1;
        final long[] result = new long[wanted + 2];
        for (int i = 0; i <= wanted; i++) result[i + 1] = dataStart + offset(in, position + 3 + (long) i * offSize, offSize);
        result[0] = dataStart + offset(in, position + 3 + (long) count * offSize, offSize);
        return result;
    }

    private static long offset(RandomAccessFile in, long position, int size) throws IOException {
        final byte[] bytes = read(in, position, size);
        long value = 0;
        for (byte b : bytes) value = (value << 8) | (b & 0xFF);
        return value;
    }

    // Type 1 フォント (.pfb)。最初の、暗号化されていない部分にある /FontName を名前にする
    private void addType1(File file) throws IOException {
        final byte[] head = new byte[65536];
        int length = 0;
        try (InputStream input = new FileInputStream(file)) {
            int n;
            while (length < head.length && (n = input.read(head, length, head.length - length)) > 0) length += n;
        }
        final String text = new String(head, 0, length, ISO_8859_1);
        final int key = text.indexOf("/FontName");
        if (key < 0) return;
        int from = text.indexOf('/', key + 9);
        if (from < 0) return;
        from++;
        int to = from;
        while (to < text.length() && " \t\r\n/[]{}()<>%".indexOf(text.charAt(to)) < 0) to++;
        final String name = text.substring(from, to);
        if (name.isEmpty() || !usableName(name)) return;
        fonts.add(new Info(file, false, Format.PFB, name, null, -1, -1, 0, 0, -1, null));
    }

    // ---------- ファイルの読み取り ----------

    private static byte[] read(RandomAccessFile in, long position, int length) throws IOException {
        if (length < 0 || position < 0 || position + length > in.length()) throw new IOException("Out of range");
        final byte[] bytes = new byte[length];
        in.seek(position);
        in.readFully(bytes);
        return bytes;
    }

    private static int readInt(RandomAccessFile in, long position) throws IOException {
        return (int) u32(read(in, position, 4), 0);
    }

    private static int readShort(RandomAccessFile in, long position) throws IOException {
        return u16(read(in, position, 2), 0);
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) u16(bytes, offset) << 16) | u16(bytes, offset + 2);
    }

    // ---------- 一覧の 1 項目 ----------

    enum Format {TTF, OTF, PFB}

    static final class Info {
        final File file;
        private final boolean collection;
        final Format format;
        final String postScriptName;
        // CFF の ROS {Registry, Ordering, Supplement} (CID フォントでなければ null)
        final String[] ros;
        final int weightClass, familyClass, codePage1, codePage2, macStyle;
        final byte[] panose;
        // 読んだフォント。メモリが足りなくなれば捨て、また読む
        private SoftReference<FontProgram> loaded;
        private boolean broken;

        Info(File file, boolean collection, Format format, String postScriptName, String[] ros,
             int weightClass, int familyClass, int codePage1, int codePage2, int macStyle, byte[] panose) {
            this.file = file;
            this.collection = collection;
            this.format = format;
            this.postScriptName = postScriptName;
            this.ros = ros;
            this.weightClass = weightClass;
            this.familyClass = familyClass;
            this.codePage1 = codePage1;
            this.codePage2 = codePage2;
            this.macStyle = macStyle;
            this.panose = panose;
        }

        // 前に読んだ結果として保存する項目
        String[] fields() {
            final StringBuilder panoseText = new StringBuilder();
            if (panose != null) {
                for (byte b : panose) panoseText.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return new String[]{collection ? "1" : "0", format.name(), postScriptName,
                    ros == null ? "" : ros[0], ros == null ? "" : ros[1], ros == null ? "" : ros[2],
                    Integer.toString(weightClass), Integer.toString(familyClass),
                    Integer.toString(codePage1), Integer.toString(codePage2), Integer.toString(macStyle),
                    panoseText.toString()};
        }

        // fields の逆。形が合わなければ null
        static Info parse(File file, String[] fields) {
            if (fields.length != 12) return null;
            try {
                final String[] ros = fields[3].isEmpty() ? null : new String[]{fields[3], fields[4], fields[5]};
                byte[] panose = null;
                if (fields[11].length() == 20) {
                    panose = new byte[10];
                    for (int i = 0; i < 10; i++) panose[i] = (byte) Integer.parseInt(fields[11].substring(2 * i, 2 * i + 2), 16);
                }
                return new Info(file, fields[0].equals("1"), Format.valueOf(fields[1]), fields[2], ros,
                        Integer.parseInt(fields[6]), Integer.parseInt(fields[7]), Integer.parseInt(fields[8]),
                        Integer.parseInt(fields[9]), Integer.parseInt(fields[10]), panose);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        long codePageRange() {
            return ((long) codePage2 << 32) | (codePage1 & 0xFFFFFFFFL);
        }

        // usWeightClass を Panose の太さ (2 から 10) にしたもの。分からなければ 0
        int weightClassAsPanose() {
            switch (weightClass) {
                case 100: return 2;
                case 200: return 3;
                case 300: return 4;
                case 400: return 5;
                case 500: return 6;
                case 600: return 7;
                case 700: return 8;
                case 800: return 9;
                case 900: return 10;
                default: return 0;
            }
        }

        synchronized FontProgram font() {
            if (broken) return null;
            final FontProgram cached = loaded == null ? null : loaded.get();
            if (cached != null) return cached;
            try {
                final FontProgram font = load();
                if (font != null) loaded = new SoftReference<>(font);
                else broken = true;
                return font;
            } catch (IOException | RuntimeException e) {
                broken = true;
                MarumaSign.LOGGER.warn("Could not load font " + postScriptName + " from " + file + ": " + e);
                return null;
            }
        }

        private FontProgram load() throws IOException {
            if (format == Format.PFB) {
                if (file.length() > MAX_TYPE1_SIZE) return null;
                return Type1Font.parse(Files.readAllBytes(file.toPath()));
            }
            // 大きなフォントもあるので、メモリには読み込まずファイルを割り当てる
            final MappedByteBuffer buffer;
            try (RandomAccessFile in = new RandomAccessFile(file, "r"); FileChannel channel = in.getChannel()) {
                if (channel.size() > Integer.MAX_VALUE) return null;
                buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            }
            final FontBytes bytes = new FontBytes(buffer, 0, buffer.capacity());
            if (!collection) return new TrueTypeFont(bytes, 0);
            final int count = (int) Math.min(bytes.u32(8), 1024);
            for (int i = 0; i < count; i++) {
                try {
                    final TrueTypeFont font = new TrueTypeFont(bytes, bytes.u32(12 + 4L * i));
                    if (postScriptName.equals(font.postScriptName())) return font;
                } catch (FontBytes.FontFormatError e) {
                    // 集合の中の、読めないフォント
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return postScriptName + " (" + file + ")";
        }
    }
}
