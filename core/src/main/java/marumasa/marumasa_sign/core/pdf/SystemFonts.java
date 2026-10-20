package marumasa.marumasa_sign.core.pdf;

import marumasa.marumasa_sign.core.MarumaSign;
import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.type1.Type1Font;
import org.apache.fontbox.util.autodetect.FontDirFinder;
import org.apache.fontbox.util.autodetect.MacFontDirFinder;
import org.apache.fontbox.util.autodetect.OS400FontDirFinder;
import org.apache.fontbox.util.autodetect.UnixFontDirFinder;
import org.apache.fontbox.util.autodetect.WindowsFontDirFinder;
import org.apache.pdfbox.pdmodel.font.CIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.FontFormat;
import org.apache.pdfbox.pdmodel.font.FontInfo;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontProvider;
import org.apache.pdfbox.pdmodel.font.PDPanoseClassification;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.ref.SoftReference;
import java.lang.reflect.Method;
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
 * パソコンに入っているフォントの一覧。PDF に埋め込まれていないフォントの代わりを探すのに使う。
 * <p>
 * PDFBox の標準の一覧は、初めて使うときにすべてのフォントファイルを最後まで読んで (ハッシュを求めるため)、
 * フォントの多いパソコンでは 1 分近くかかる。ここでは、代わりを選ぶのに使う表 (name, OS/2, head と、CFF の先頭) だけを
 * 読んで、同じ内容の一覧を作る。フォントの中身は、実際に使うときに PDFBox で読む。
 */
final class SystemFonts extends FontProvider {

    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    // name 表の、読む大きさの上限 (壊れたファイルで大量に読まないように)
    private static final int MAX_NAME_RECORDS = 4096;

    private static boolean installed;

    private final List<Info> fonts = new ArrayList<>();

    /**
     * PDFBox が代わりのフォントを探すときに、この一覧を使うようにする。何度呼んでもよい。
     * うまくいかなければ PDFBox の標準の一覧のまま (遅いが、表示はできる)。
     */
    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            final long start = System.nanoTime();
            final File cache = MarumaSign.platform() == null ? null
                    : new File(MarumaSign.platform().gameDirectory(), "marumasign/pdf-fonts.cache");
            final SystemFonts provider = create(cache);
            // 標準の FontMapper は公開されていないクラスなので、公開されているメソッドを reflection で呼ぶ
            final FontMapper mapper = FontMappers.instance();
            final Method setProvider = mapper.getClass().getMethod("setProvider", FontProvider.class);
            setProvider.setAccessible(true);
            setProvider.invoke(mapper, provider);
            MarumaSign.LOGGER.info("Found " + provider.fonts.size() + " system fonts for PDF in "
                    + (System.nanoTime() - start) / 1000000 + " ms");
        } catch (Exception | LinkageError e) {
            MarumaSign.LOGGER.warn("Could not set up the system fonts for PDF: " + e);
        }
    }

    /**
     * パソコンのフォントの一覧を作る。
     *
     * @param cache 前に読んだ結果を保存するファイル (なければ null)
     */
    static SystemFonts create(File cache) {
        final SystemFonts provider = new SystemFonts();
        provider.scan(findFiles(), cache);
        return provider;
    }

    @Override
    public String toDebugString() {
        return fonts.size() + " fonts";
    }

    @Override
    public List<? extends FontInfo> getFontInfo() {
        return fonts;
    }

    /**
     * パソコンのフォントファイルを、大きさと更新日時と一緒に探す。
     * PDFBox の FontFileFinder と同じフォルダを同じ順にたどり、同じ条件のファイルを同じ順に返す。
     * FontFileFinder はファイルごとにフォルダかどうかを問い合わせ (6000 個ほどで 1 秒)、大きさなどもあとで別に調べる必要があるが、
     * ここではフォルダの一覧を読むときに一緒に得られる情報を使う (同じ数で 40 ms ほど)。
     */
    static List<CachedFile> findFiles() {
        final String os = System.getProperty("os.name", "");
        final FontDirFinder finder = os.startsWith("Windows") ? new WindowsFontDirFinder()
                : os.startsWith("Mac") ? new MacFontDirFinder()
                : os.startsWith("OS/400") ? new OS400FontDirFinder() : new UnixFontDirFinder();
        final List<CachedFile> files = new ArrayList<>();
        for (File directory : finder.find()) {
            final Path root = directory.toPath();
            try {
                // FontFileFinder と同じく、リンクの先もたどる
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
        // いくつかのスレッドで同時に読む。一覧の順番は PDFBox と同じにしたいので、結果はファイルの順に並べる
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
                        // 読めないフォントは、PDFBox の一覧でも除かれる
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
            // 読めないフォントは、PDFBox の一覧でも除かれる
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
     * 1 つのフォントを一覧に加える。PDFBox の TTFParser / OTFParser が読めないもの、名前のないものは除く。
     *
     * @param openType OTFParser で読まれるか (拡張子が .otf か、集合の中で "OTTO" で始まるもの)
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
        // PDFBox が必ず要る表
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

        CIDSystemInfo ros = null;
        if (postScript && hasCff) {
            try {
                ros = cffRos(in, tables.get("CFF "));
            } catch (IOException | RuntimeException e) {
                // ROS が分からなくても、名前で探すことはできる
            }
        }
        fonts.add(new Info(file, collection, postScript ? FontFormat.OTF : FontFormat.TTF, name, ros,
                weightClass, familyClass, codePage1, codePage2, macStyle, panose));
    }

    // PDFBox は "|" を含む名前のフォントを除く。保存の形に合わない、改行やタブを含むものも除く
    private static boolean usableName(String name) {
        return name != null && name.indexOf('|') < 0 && name.indexOf('\t') < 0 && name.indexOf('\n') < 0 && name.indexOf('\r') < 0;
    }

    // name 表の PostScript 名。PDFBox と同じく、Mac の英語、Windows の英語 (米国) の順に探す
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
    private static CIDSystemInfo cffRos(RandomAccessFile in, long[] table) throws IOException {
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
                    return new CIDSystemInfo(registry, ordering, operands.get(2).intValue());
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
        fonts.add(new Info(file, false, FontFormat.PFB, name, null, -1, -1, 0, 0, -1, null));
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

    static final class Info extends FontInfo {
        final File file;
        private final boolean collection;
        private final FontFormat format;
        private final String postScriptName;
        private final CIDSystemInfo ros;
        private final int weightClass, familyClass, codePage1, codePage2, macStyle;
        private final PDPanoseClassification panose;
        // 読んだフォント。メモリが足りなくなれば捨て、また読む
        private SoftReference<FontBoxFont> loaded;

        Info(File file, boolean collection, FontFormat format, String postScriptName, CIDSystemInfo ros,
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
            this.panose = panose != null ? new PDPanoseClassification(panose) : null;
        }

        // 前に読んだ結果として保存する項目
        String[] fields() {
            final StringBuilder panoseText = new StringBuilder();
            if (panose != null) {
                for (byte b : panose.getBytes()) panoseText.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return new String[]{collection ? "1" : "0", format.name(), postScriptName,
                    ros == null ? "" : ros.getRegistry(), ros == null ? "" : ros.getOrdering(),
                    ros == null ? "" : Integer.toString(ros.getSupplement()),
                    Integer.toString(weightClass), Integer.toString(familyClass),
                    Integer.toString(codePage1), Integer.toString(codePage2), Integer.toString(macStyle),
                    panoseText.toString()};
        }

        // fields の逆。形が合わなければ null
        static Info parse(File file, String[] fields) {
            if (fields.length != 12) return null;
            try {
                final CIDSystemInfo ros = fields[3].isEmpty() ? null
                        : new CIDSystemInfo(fields[3], fields[4], Integer.parseInt(fields[5]));
                byte[] panose = null;
                if (fields[11].length() == 20) {
                    panose = new byte[10];
                    for (int i = 0; i < 10; i++) panose[i] = (byte) Integer.parseInt(fields[11].substring(2 * i, 2 * i + 2), 16);
                }
                return new Info(file, fields[0].equals("1"), FontFormat.valueOf(fields[1]), fields[2], ros,
                        Integer.parseInt(fields[6]), Integer.parseInt(fields[7]), Integer.parseInt(fields[8]),
                        Integer.parseInt(fields[9]), Integer.parseInt(fields[10]), panose);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        public String getPostScriptName() {
            return postScriptName;
        }

        @Override
        public FontFormat getFormat() {
            return format;
        }

        @Override
        public CIDSystemInfo getCIDSystemInfo() {
            return ros;
        }

        @Override
        public synchronized FontBoxFont getFont() {
            final FontBoxFont cached = loaded == null ? null : loaded.get();
            if (cached != null) return cached;
            try {
                final FontBoxFont font = load();
                if (font != null) loaded = new SoftReference<>(font);
                return font;
            } catch (IOException | RuntimeException e) {
                MarumaSign.LOGGER.warn("Could not load font " + postScriptName + " from " + file + ": " + e);
                return null;
            }
        }

        private FontBoxFont load() throws IOException {
            if (format == FontFormat.PFB) {
                try (InputStream input = new FileInputStream(file)) {
                    return Type1Font.createWithPFB(input);
                }
            }
            if (collection) {
                // 集合は、使っている間ファイルを開いたままにする必要がある (PDFBox の標準の一覧と同じ)
                final TrueTypeCollection ttc = new TrueTypeCollection(file);
                final TrueTypeFont font;
                try {
                    font = ttc.getFontByName(postScriptName);
                } catch (IOException | RuntimeException e) {
                    ttc.close();
                    throw e;
                }
                if (font == null) {
                    ttc.close();
                    throw new IOException("Font not found in the collection");
                }
                return font;
            }
            return format == FontFormat.OTF ? new OTFParser(false, true).parse(file) : new TTFParser(false, true).parse(file);
        }

        @Override
        public int getFamilyClass() {
            return familyClass;
        }

        @Override
        public int getWeightClass() {
            return weightClass;
        }

        @Override
        public int getCodePageRange1() {
            return codePage1;
        }

        @Override
        public int getCodePageRange2() {
            return codePage2;
        }

        @Override
        public int getMacStyle() {
            return macStyle;
        }

        @Override
        public PDPanoseClassification getPanose() {
            return panose;
        }
    }
}
