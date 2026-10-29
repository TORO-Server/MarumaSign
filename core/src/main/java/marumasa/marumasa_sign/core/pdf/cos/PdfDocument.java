package marumasa.marumasa_sign.core.pdf.cos;

import marumasa.marumasa_sign.core.pdf.Strict;
import marumasa.marumasa_sign.core.pdf.crypt.SecurityHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF ファイル全体。相互参照表 (xref) を読み、番号からオブジェクトを取り出す。
 * <p>
 * 壊れたファイルにも強くするため、相互参照表が読めないときや指す位置が間違っているときは、
 * ファイル全体から「番号 世代 obj」を探して表を作り直す。
 * 悪意のあるファイルへの対策として、次のものに上限や検出を設けている:
 * 相互参照表の /Prev の輪、参照の輪 (/Length が自分を指すなど)、オブジェクトストリームの入れ子、
 * ページの木の輪と深さ、展開したデータの大きさ。
 */
public final class PdfDocument implements Resolver {

    // ストリームを戻したデータの大きさの上限 (1 つあたり)
    public static final int MAX_DECODED = 128 * 1024 * 1024;
    // ページの数の上限
    private static final int MAX_PAGES = 100000;
    // ページの木の深さの上限
    private static final int MAX_TREE_DEPTH = 64;
    // オブジェクトストリームの中のオブジェクトの数の上限
    private static final int MAX_OBJECT_STREAM_ITEMS = 1 << 20;

    // 相互参照表の 1 項目
    private static final class Entry {
        // 0: 使われていない、1: ファイルの中の位置、2: オブジェクトストリームの中
        final int type;
        // type 1 なら位置、type 2 ならオブジェクトストリームの番号
        final long offset;
        // type 2 なら、オブジェクトストリームの中の順番
        final int index;

        Entry(int type, long offset, int index) {
            this.type = type;
            this.offset = offset;
            this.index = index;
        }
    }

    // 読んだオブジェクトストリーム
    private static final class ObjectStream {
        final int[] numbers;
        final int[] offsets;
        final byte[] data;
        final int first;

        ObjectStream(int[] numbers, int[] offsets, byte[] data, int first) {
            this.numbers = numbers;
            this.offsets = offsets;
            this.data = data;
            this.first = first;
        }
    }

    // ページ 1 つ。木から受け継ぐ値 (Resources など) を合わせたもの
    public static final class Page {
        public final PdfDict dict;
        public final PdfDict resources;
        public final double[] mediaBox;
        public final double[] cropBox;
        public final int rotate;

        Page(PdfDict dict, PdfDict resources, double[] mediaBox, double[] cropBox, int rotate) {
            this.dict = dict;
            this.resources = resources;
            this.mediaBox = mediaBox;
            this.cropBox = cropBox;
            this.rotate = rotate;
        }
    }

    private final byte[] data;
    // ファイルの先頭の "%PDF-" の位置 (前に余計なバイトが付いたファイルでは 0 でない)
    private final int headerOffset;
    private Map<Integer, Entry> xref = new HashMap<>();
    private PdfDict trailer;
    private boolean repaired;
    private SecurityHandler security;
    // 暗号化の辞書 (これ自身は復号しない)
    private Object encryptReference;

    private final Map<Integer, Object> cache = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private final Map<Integer, ObjectStream> objectStreams = new HashMap<>();
    private final Set<Integer> loadingObjectStreams = new HashSet<>();
    private List<Page> pages;

    public PdfDocument(byte[] data) throws IOException {
        this.data = data;
        this.headerOffset = findHeader(data);
        try {
            readXref();
        } catch (IOException | RuntimeException e) {
            repair();
        }
        if (!(trailer.get("Root") instanceof PdfDict) && !repaired) repairNow();
        if (trailer == null) throw new IOException("No trailer");
        setUpEncryption();
        pages = readPages();
        if (pages.isEmpty() && !repaired) {
            // ページの木が壊れているなら、表を作り直してもう一度試す
            final SecurityHandler previous = security;
            repairNow();
            try {
                setUpEncryption();
            } catch (IOException e) {
                // 作り直したトレーラーの /ID が違うと鍵が合わない。最初に合った鍵を使う
                if (previous == null) throw e;
                security = previous;
                cache.clear();
                objectStreams.clear();
            }
            pages = readPages();
        }
        if (pages.isEmpty()) pages = scanPages();
    }

    public byte[] data() {
        return data;
    }

    public PdfDict trailer() {
        return trailer;
    }

    public PdfDict catalog() {
        final Object root = trailer.get("Root");
        return root instanceof PdfDict ? (PdfDict) root : null;
    }

    public List<Page> pages() {
        return pages;
    }

    // ---------- 先頭と相互参照表 ----------

    private static int findHeader(byte[] data) {
        final int limit = Math.min(data.length, 1024) - 5;
        for (int i = 0; i <= limit; i++) {
            if (data[i] == '%' && data[i + 1] == 'P' && data[i + 2] == 'D' && data[i + 3] == 'F' && data[i + 4] == '-') return i;
        }
        return 0;
    }

    private void readXref() throws IOException {
        long start = findStartXref();
        final Set<Long> visited = new HashSet<>();
        boolean first = true;
        while (start >= 0) {
            if (!visited.add(start)) break; // /Prev の輪
            if (visited.size() > 1024) throw new IOException("Too many xref sections");
            final PdfDict sectionTrailer = readXrefSection(start);
            if (first) {
                trailer = sectionTrailer;
                first = false;
            } else {
                // 古い版のトレーラーの値は、新しい版にないときだけ使う
                for (String key : sectionTrailer.keys()) {
                    if (trailer.raw(key) == null && !key.equals("Prev") && !key.equals("XRefStm")) trailer.put(key, sectionTrailer.raw(key));
                }
            }
            final Object prev = sectionTrailer.get("Prev");
            start = prev instanceof Number ? ((Number) prev).longValue() : -1;
        }
        if (trailer == null) throw new IOException("No trailer");
    }

    private long findStartXref() throws IOException {
        final byte[] keyword = "startxref".getBytes(StandardCharsets.ISO_8859_1);
        final int from = Math.max(0, data.length - 4096);
        for (int i = data.length - keyword.length; i >= from; i--) {
            if (matches(i, keyword)) {
                final Lexer lexer = new Lexer(data, i + keyword.length, data.length);
                final Object offset = lexer.next();
                if (offset instanceof Integer) return (Integer) offset;
                break;
            }
        }
        throw new IOException("No startxref");
    }

    private boolean matches(int position, byte[] text) {
        if (position < 0 || position + text.length > data.length) return false;
        for (int i = 0; i < text.length; i++) {
            if (data[position + i] != text[i]) return false;
        }
        return true;
    }

    // 相互参照表の 1 つの版を読み、そのトレーラーを返す
    private PdfDict readXrefSection(long start) throws IOException {
        if (start < 0 || start >= data.length) throw new IOException("Bad xref offset");
        int position = (int) start;
        // 位置が "%PDF-" より前の余計なバイトの分だけずれているファイルがある
        if (!looksLikeXref(position) && looksLikeXref(position + headerOffset)) position += headerOffset;
        final Lexer lexer = new Lexer(data, position, data.length);
        final Object token = lexer.next();
        if (token instanceof Keyword && ((Keyword) token).is("xref")) {
            final PdfDict sectionTrailer = readXrefTable(lexer);
            // 両方の形を持つファイル (hybrid): 表にないオブジェクトは /XRefStm のストリームにある
            final Object stm = sectionTrailer.get("XRefStm");
            if (stm instanceof Number) {
                try {
                    readXrefStream(((Number) stm).longValue());
                } catch (IOException | RuntimeException e) {
                    // 表のほうは読めたので続ける
                }
            }
            return sectionTrailer;
        }
        return readXrefStream(position);
    }

    private boolean looksLikeXref(int position) {
        if (position < 0 || position >= data.length) return false;
        final Lexer lexer = new Lexer(data, position, data.length);
        final Object token = lexer.next();
        if (token instanceof Keyword) return ((Keyword) token).is("xref");
        if (!(token instanceof Integer)) return false;
        return lexer.next() instanceof Integer && lexer.peekKeyword("obj");
    }

    private PdfDict readXrefTable(Lexer lexer) throws IOException {
        while (true) {
            final int before = lexer.position();
            final Object token = lexer.next();
            if (token instanceof Keyword && ((Keyword) token).is("trailer")) break;
            if (!(token instanceof Integer)) {
                // 表の途中で壊れている: トレーラーを探す
                lexer.seek(before);
                if (!skipTo(lexer, "trailer")) throw new IOException("No trailer after xref");
                break;
            }
            final Object countToken = lexer.next();
            if (!(countToken instanceof Integer)) throw new IOException("Bad xref subsection");
            final int first = (Integer) token;
            int count = (Integer) countToken;
            // 1 項目は少なくとも 5 バイトあるので、残りのデータより多い数は読まない (巨大な数で止まらないように)
            count = Math.max(0, Math.min(count, (data.length - lexer.position()) / 5 + 1));
            for (int i = 0; i < count; i++) {
                final int entryStart = lexer.position();
                final Object offset = lexer.next();
                final Object generation = lexer.next();
                final Object kind = lexer.next();
                if (!(offset instanceof Number) || !(generation instanceof Integer) || !(kind instanceof Keyword)) {
                    lexer.seek(entryStart);
                    break;
                }
                final long number = (long) first + i;
                if (number < 0 || number > Integer.MAX_VALUE) continue;
                final boolean inUse = ((Keyword) kind).is("n");
                // 0 番は必ず使われていない。新しい版の項目を優先する (先に読んだものを上書きしない)
                if (!xref.containsKey((int) number)) {
                    final long position = ((Number) offset).longValue();
                    xref.put((int) number, inUse && position > 0 ? new Entry(1, position, 0) : new Entry(0, 0, 0));
                }
            }
        }
        final ObjectParser parser = new ObjectParser(lexer, this, true);
        final Object dict = parser.next();
        if (!(dict instanceof PdfDict)) throw new IOException("Bad trailer");
        return (PdfDict) dict;
    }

    private static boolean skipTo(Lexer lexer, String keyword) {
        while (true) {
            final Object token = lexer.next();
            if (token == null) return false;
            if (token instanceof Keyword && ((Keyword) token).is(keyword)) return true;
        }
    }

    // 相互参照ストリーム (/Type /XRef)。その辞書をトレーラーとして返す
    private PdfDict readXrefStream(long start) throws IOException {
        if (start < 0 || start >= data.length) throw new IOException("Bad xref stream offset");
        final Object object = parseObjectAt((int) start, -1, false);
        if (!(object instanceof PdfStream)) throw new IOException("Not an xref stream");
        final PdfStream stream = (PdfStream) object;
        final PdfDict dict = stream.dict;
        final PdfArray widths = dict.array("W");
        if (widths == null || widths.size() < 3) throw new IOException("Bad /W");
        final int w0 = widths.integer(0, -1), w1 = widths.integer(1, -1), w2 = widths.integer(2, -1);
        if (w0 < 0 || w1 < 0 || w2 < 0 || w0 > 8 || w1 > 8 || w2 > 8) throw new IOException("Bad /W");
        final int rowSize = w0 + w1 + w2;
        if (rowSize == 0) throw new IOException("Bad /W");
        final byte[] rows = stream.decode(MAX_DECODED);
        final int size = dict.integer("Size", 0);
        PdfArray index = dict.array("Index");
        final List<int[]> ranges = new ArrayList<>();
        if (index == null) {
            ranges.add(new int[]{0, Math.max(0, size)});
        } else {
            for (int i = 0; i + 1 < index.size(); i += 2) ranges.add(new int[]{index.integer(i, 0), index.integer(i + 1, 0)});
        }
        int position = 0;
        for (int[] range : ranges) {
            // データにある行の数までしか読まない (巨大な /Index で長い時間止まらないように)
            final int available = (rows.length - position) / rowSize;
            final int count = Math.max(0, Math.min(range[1], available));
            for (int i = 0; i < count; i++, position += rowSize) {
                final int type = w0 == 0 ? 1 : (int) field(rows, position, w0);
                final long second = field(rows, position + w0, w1);
                final long third = field(rows, position + w0 + w1, w2);
                final long number = (long) range[0] + i;
                if (number < 0 || number > Integer.MAX_VALUE || xref.containsKey((int) number)) continue;
                if (type == 1) {
                    xref.put((int) number, new Entry(1, second, 0));
                } else if (type == 2) {
                    if (second < 0 || second > Integer.MAX_VALUE || third < 0 || third > Integer.MAX_VALUE) continue;
                    xref.put((int) number, new Entry(2, second, (int) third));
                } else if (type == 0) {
                    xref.put((int) number, new Entry(0, 0, 0));
                }
                // 知らない種類は、参照がないものとして扱う (仕様どおり)
            }
        }
        return dict;
    }

    private static long field(byte[] rows, int position, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) value = (value << 8) | (rows[position + i] & 0xFF);
        return value;
    }

    // ---------- 作り直し ----------

    /**
     * ファイル全体から「番号 世代 obj」を探して、相互参照表を作り直す。
     * 同じ番号が何度も出てきたら、後ろ (新しい版) のものを使う。
     */
    private void repair() throws IOException {
        repaired = true;
        cache.clear();
        objectStreams.clear();
        final Map<Integer, Entry> found = new HashMap<>();
        final List<Integer> trailers = new ArrayList<>();
        final List<Integer> objectStreamNumbers = new ArrayList<>();
        for (int i = 0; i + 3 <= data.length; i++) {
            final byte b = data[i];
            if (b == 'o' && data[i + 1] == 'b' && data[i + 2] == 'j' && (i + 3 == data.length || !Lexer.isRegular(data[i + 3]))) {
                final int start = objectHeaderStart(i);
                if (start >= 0) {
                    final Lexer lexer = new Lexer(data, start, i);
                    final Object number = lexer.next();
                    if (number instanceof Integer && (Integer) number > 0) {
                        found.put((Integer) number, new Entry(1, start, 0));
                        // オブジェクトストリームと相互参照ストリームは、中を見る必要がある
                        if (startsWithin(i + 3, "/ObjStm", 256)) objectStreamNumbers.add((Integer) number);
                        if (startsWithin(i + 3, "/XRef", 256)) trailers.add(-(Integer) number - 1);
                    }
                }
            } else if (b == 't' && i + 7 <= data.length && matches(i, TRAILER)) {
                trailers.add(i + 7);
            }
        }
        xref = found;
        // オブジェクトストリームの中のオブジェクト。直接書かれたものを優先する
        for (int number : objectStreamNumbers) {
            try {
                final ObjectStream stream = objectStream(number);
                for (int k = 0; k < stream.numbers.length; k++) {
                    if (!xref.containsKey(stream.numbers[k]) && stream.numbers[k] > 0) xref.put(stream.numbers[k], new Entry(2, number, k));
                }
            } catch (IOException | RuntimeException e) {
                // このオブジェクトストリームは使えない
            }
        }
        cache.clear();
        // トレーラー: 後ろのものほど新しい。/Root のあるものを合わせる
        PdfDict merged = null;
        for (int t : trailers) {
            PdfDict dict = null;
            try {
                if (t >= 0) {
                    final Object value = new ObjectParser(new Lexer(data, t, data.length), this, true).next();
                    if (value instanceof PdfDict) dict = (PdfDict) value;
                } else {
                    final Object value = getObject(-t - 1);
                    if (value instanceof PdfStream) dict = ((PdfStream) value).dict;
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (dict == null) continue;
            if (merged == null) merged = new PdfDict(this);
            for (String key : dict.keys()) {
                if (key.equals("Root") && !(dict.get("Root") instanceof PdfDict)) continue;
                merged.put(key, dict.raw(key));
            }
        }
        if (merged == null || !(merged.get("Root") instanceof PdfDict)) {
            // トレーラーがない: /Type /Catalog のオブジェクトを探す
            if (merged == null) merged = new PdfDict(this);
            for (Map.Entry<Integer, Entry> entry : xref.entrySet()) {
                final Object value = getObject(entry.getKey());
                if (value instanceof PdfDict && ((PdfDict) value).isName("Type", "Catalog") && ((PdfDict) value).get("Pages") != null) {
                    merged.put("Root", new PdfRef(entry.getKey(), 0));
                    break;
                }
            }
        }
        // カタログが見つからなくても、ページだけは scanPages で探せる
        trailer = merged;
    }

    private static final byte[] TRAILER = "trailer".getBytes(StandardCharsets.ISO_8859_1);

    private boolean startsWithin(int from, String text, int distance) {
        final byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        final int limit = Math.min(data.length - bytes.length, from + distance);
        for (int i = from; i <= limit; i++) {
            if (matches(i, bytes)) return true;
            if (data[i] == 's' && matches(i, STREAM)) return false;
        }
        return false;
    }

    private static final byte[] STREAM = "stream".getBytes(StandardCharsets.ISO_8859_1);

    // "obj" の前にある「番号 世代」の始まりの位置。形が合わなければ -1
    private int objectHeaderStart(int objPosition) {
        int p = objPosition - 1;
        if (p < 0 || !Lexer.isWhitespace(data[p])) return -1;
        while (p >= 0 && Lexer.isWhitespace(data[p])) p--;
        final int generationEnd = p;
        while (p >= 0 && data[p] >= '0' && data[p] <= '9') p--;
        if (p == generationEnd || p < 0 || !Lexer.isWhitespace(data[p])) return -1;
        while (p >= 0 && Lexer.isWhitespace(data[p])) p--;
        final int numberEnd = p;
        while (p >= 0 && data[p] >= '0' && data[p] <= '9') p--;
        if (p == numberEnd || numberEnd - p > 10) return -1;
        return p + 1;
    }

    // ---------- オブジェクトの取り出し ----------

    @Override
    public Object resolve(Object value) {
        // 参照が参照を指すこともあるので、たどる回数に上限を設ける
        for (int i = 0; i < 32 && value instanceof PdfRef; i++) {
            value = getObject(((PdfRef) value).number);
        }
        return value instanceof PdfRef ? null : value;
    }

    /**
     * 番号のオブジェクト。ないもの、読めないものは null。
     */
    public Object getObject(int number) {
        if (cache.containsKey(number)) return cache.get(number);
        // 読んでいる途中のものをまた読もうとした (参照の輪)
        if (!loading.add(number)) return null;
        Object value = null;
        try {
            value = load(number);
        } catch (IOException | RuntimeException e) {
            Strict.check(e);
            value = null;
        } finally {
            loading.remove(number);
        }
        cache.put(number, value);
        return value;
    }

    private Object load(int number) throws IOException {
        Entry entry = xref.get(number);
        if (entry == null || (entry.type == 1 && locate(entry, number) < 0)) {
            // 表にない番号か、表の指す位置が違う: 表を作り直して探す (1 回だけ)
            if (repaired) return null;
            repairMissing();
            entry = xref.get(number);
            if (entry == null) return null;
        }
        if (entry.type == 2) {
            final ObjectStream stream = objectStream((int) entry.offset);
            int index = entry.index;
            // 順番が合わなければ、番号で探す
            if (index >= stream.numbers.length || stream.numbers[index] != number) {
                index = -1;
                for (int i = 0; i < stream.numbers.length; i++) {
                    if (stream.numbers[i] == number) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) return null;
            }
            final long start = (long) stream.first + stream.offsets[index];
            if (start < 0 || start >= stream.data.length) return null;
            final ObjectParser parser = new ObjectParser(new Lexer(stream.data, (int) start, stream.data.length), this, true);
            final Object value = parser.next();
            return value instanceof Keyword ? null : value;
        }
        if (entry.type != 1) return null;
        final int position = locate(entry, number);
        return position < 0 ? null : parseObjectAt(position, number, true);
    }

    // 表の項目が指す、オブジェクトの始まりの位置。「番号 世代 obj」が見つからなければ -1
    private int locate(Entry entry, int number) {
        if (entry.offset < 0 || entry.offset >= data.length) return -1;
        final int position = (int) entry.offset;
        if (headerAt(position, number)) return position;
        // 位置が "%PDF-" より前の余計なバイトの分だけずれているファイルがある
        if (headerOffset > 0 && headerAt(position + headerOffset, number)) return position + headerOffset;
        return -1;
    }

    /**
     * 読めた表にない番号 (どこからか参照だけが残ったものなど) や、表の指す位置が違うものを探すための作り直し。
     * 作り直した表は、元の表で見つからないものにだけ使う。元の表の正しい項目まで置き換えると、
     * オブジェクトストリームに入れて更新した新しい版より、前に直接書かれた古い版を選んでしまう
     * (参照の切れた番号が 1 つあるだけの正しいファイルで、古い版のページを描いていた)。
     * 読んだオブジェクトとトレーラーもそのまま使う (同じ辞書を読み直して別のものにすると、
     * 表示しない層 (OCG) のように、辞書そのもので比べるものが合わなくなる)。
     */
    private void repairMissing() throws IOException {
        final Map<Integer, Entry> original = xref;
        final Map<Integer, Object> loaded = new HashMap<>(cache);
        final Map<Integer, ObjectStream> streams = new HashMap<>(objectStreams);
        final PdfDict saved = trailer;
        repair();
        for (Map.Entry<Integer, Entry> item : original.entrySet()) {
            final Entry entry = item.getValue();
            if (entry.type != 1 || locate(entry, item.getKey()) >= 0) xref.put(item.getKey(), entry);
        }
        cache.clear();
        for (Map.Entry<Integer, Object> item : loaded.entrySet()) {
            if (item.getValue() != null) cache.put(item.getKey(), item.getValue());
        }
        objectStreams.clear();
        objectStreams.putAll(streams);
        // 元のトレーラーのカタログが見つかるなら、元のトレーラーを使う
        final Object root = saved == null ? null : saved.raw("Root");
        if (root instanceof PdfDict || (root instanceof PdfRef && xref.containsKey(((PdfRef) root).number))) trailer = saved;
    }

    private void repairNow() throws IOException {
        final PdfDict saved = trailer;
        repair();
        // 作り直した表にカタログが見つからなければ、元のトレーラーを使う
        if (saved != null && !(trailer.get("Root") instanceof PdfDict)) trailer = saved;
    }

    private boolean headerAt(int position, int number) {
        if (position < 0 || position >= data.length) return false;
        final Lexer lexer = new Lexer(data, position, Math.min(data.length, position + 64));
        final Object n = lexer.next();
        if (!(n instanceof Integer) || (Integer) n != number) return false;
        return lexer.next() instanceof Integer && lexer.peekKeyword("obj");
    }

    /**
     * 「番号 世代 obj ... endobj」を読む。
     *
     * @param expected 番号 (分からなければ -1)
     * @param decrypt  暗号化されたファイルなら復号するか
     */
    private Object parseObjectAt(int position, int expected, boolean decrypt) throws IOException {
        final Lexer lexer = new Lexer(data, position, data.length);
        final Object number = lexer.next();
        final Object generation = lexer.next();
        final Object obj = lexer.next();
        if (!(number instanceof Integer) || !(generation instanceof Integer) || !(obj instanceof Keyword) || !((Keyword) obj).is("obj")) {
            throw new IOException("Bad object header");
        }
        final int objectNumber = (Integer) number;
        final int objectGeneration = (Integer) generation;
        final ObjectParser parser = new ObjectParser(lexer, this, true);
        final boolean useSecurity = decrypt && security != null && !isEncryptDictionary(objectNumber);
        if (useSecurity) {
            parser.setDecryptor(new ObjectParser.StringDecryptor() {
                @Override
                public byte[] decrypt(byte[] bytes) {
                    return security.decryptString(objectNumber, objectGeneration, bytes);
                }
            });
        }
        final Object value = parser.next();
        if (value instanceof Keyword) return null;
        if (!(value instanceof PdfDict)) return value;
        if (!lexer.peekKeyword("stream")) return value;
        lexer.next();
        // "stream" の後ろの改行 (CRLF か LF、まれに CR だけ) の次からがデータ
        int start = lexer.position();
        if (start < data.length && data[start] == '\r') start++;
        if (start < data.length && data[start] == '\n') start++;
        final PdfDict dict = (PdfDict) value;
        final int end = streamEnd(dict, start);
        byte[] bytes = new byte[Math.max(0, end - start)];
        System.arraycopy(data, start, bytes, 0, bytes.length);
        if (useSecurity && !dict.isName("Type", "XRef") && !hasIdentityCrypt(dict)) {
            bytes = security.decryptStream(objectNumber, objectGeneration, bytes);
        }
        return new PdfStream(dict, bytes);
    }

    // ストリームのデータの終わり。/Length が正しくなければ "endstream" を探す
    private int streamEnd(PdfDict dict, int start) {
        final Object length = dict.get("Length");
        if (length instanceof Number) {
            final long n = ((Number) length).longValue();
            if (n >= 0 && start + n <= data.length) {
                final int end = (int) (start + n);
                final Lexer lexer = new Lexer(data, end, Math.min(data.length, end + 64));
                final Object token = lexer.next();
                if (token instanceof Keyword && (((Keyword) token).is("endstream") || ((Keyword) token).is("endobj"))) return end;
            }
        }
        final byte[] endstream = "endstream".getBytes(StandardCharsets.ISO_8859_1);
        int end = data.length;
        for (int i = start; i + endstream.length <= data.length; i++) {
            if (data[i] == 'e' && matches(i, endstream)) {
                end = i;
                break;
            }
        }
        // "endstream" の前の改行は、データに含めない
        if (end > start && data[end - 1] == '\n') end--;
        if (end > start && data[end - 1] == '\r') end--;
        return end;
    }

    // ストリーム自身に、復号しない指定 (/Crypt フィルタの /Identity) があるか
    private static boolean hasIdentityCrypt(PdfDict dict) {
        final Object filter = dict.get("Filter");
        final boolean crypt = filter instanceof PdfName ? ((PdfName) filter).name.equals("Crypt")
                : filter instanceof PdfArray && ((PdfArray) filter).size() > 0 && "Crypt".equals(((PdfArray) filter).name(0));
        if (!crypt) return false;
        Object parms = dict.get("DecodeParms");
        if (parms instanceof PdfArray) parms = ((PdfArray) parms).get(0);
        final String name = parms instanceof PdfDict ? ((PdfDict) parms).name("Name") : null;
        return name == null || name.equals("Identity");
    }

    private boolean isEncryptDictionary(int number) {
        return encryptReference instanceof PdfRef && ((PdfRef) encryptReference).number == number;
    }

    private ObjectStream objectStream(int number) throws IOException {
        final ObjectStream cached = objectStreams.get(number);
        if (cached != null) return cached;
        if (!loadingObjectStreams.add(number)) throw new IOException("Object stream refers to itself");
        try {
            final Entry entry = xref.get(number);
            // オブジェクトストリームはオブジェクトストリームの中には置けない (入れ子による輪を防ぐ)
            if (entry == null || entry.type != 1) throw new IOException("Object stream not found");
            final Object value = getObject(number);
            if (!(value instanceof PdfStream)) throw new IOException("Not an object stream");
            final PdfStream stream = (PdfStream) value;
            final int count = stream.dict.integer("N", 0);
            final int first = stream.dict.integer("First", 0);
            if (count < 0 || count > MAX_OBJECT_STREAM_ITEMS || first < 0) throw new IOException("Bad object stream");
            final byte[] decoded = stream.decode(MAX_DECODED);
            final Lexer lexer = new Lexer(decoded, 0, Math.min(first, decoded.length));
            final List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final Object n = lexer.next();
                final Object offset = lexer.next();
                if (!(n instanceof Integer) || !(offset instanceof Integer)) break;
                pairs.add(new int[]{(Integer) n, (Integer) offset});
            }
            final int[] numbers = new int[pairs.size()];
            final int[] offsets = new int[pairs.size()];
            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = pairs.get(i)[0];
                offsets[i] = pairs.get(i)[1];
            }
            final ObjectStream result = new ObjectStream(numbers, offsets, decoded, first);
            objectStreams.put(number, result);
            return result;
        } finally {
            loadingObjectStreams.remove(number);
        }
    }

    // ---------- 暗号化 ----------

    private void setUpEncryption() throws IOException {
        security = null;
        encryptReference = trailer.raw("Encrypt");
        if (encryptReference == null) return;
        final Object encrypt = resolve(encryptReference);
        if (!(encrypt instanceof PdfDict)) return;
        final Object ids = trailer.get("ID");
        security = SecurityHandler.create((PdfDict) encrypt, SecurityHandler.firstId(ids instanceof PdfArray ? (PdfArray) ids : null));
        // 暗号化の辞書を読む前に読んだオブジェクトは、復号されていないので読み直す
        cache.clear();
        objectStreams.clear();
    }

    // ---------- ページ ----------

    private List<Page> readPages() {
        final List<Page> result = new ArrayList<>();
        final PdfDict catalog = catalog();
        if (catalog == null) return result;
        final PdfDict root = catalog.dict("Pages");
        if (root == null) return result;
        final Map<PdfDict, Boolean> visited = new IdentityHashMap<>();
        collect(root, null, null, null, 0, 0, visited, result);
        return result;
    }

    private void collect(PdfDict node, PdfDict resources, double[] mediaBox, double[] cropBox, int rotate, int depth,
                         Map<PdfDict, Boolean> visited, List<Page> result) {
        if (depth > MAX_TREE_DEPTH || result.size() >= MAX_PAGES) return;
        // 同じ節を 2 度たどらない (木の輪で止まらないように)
        if (visited.put(node, Boolean.TRUE) != null) return;
        final PdfDict ownResources = node.dict("Resources");
        if (ownResources != null) resources = ownResources;
        final double[] ownMedia = pageBox(node.array("MediaBox"));
        if (ownMedia != null) mediaBox = ownMedia;
        final double[] ownCrop = pageBox(node.array("CropBox"));
        if (ownCrop != null) cropBox = ownCrop;
        final Object ownRotate = node.get("Rotate");
        if (ownRotate instanceof Number) rotate = Objects.toInt((Number) ownRotate);
        final PdfArray kids = node.array("Kids");
        final boolean isPage = node.isName("Type", "Page") || (kids == null && !node.isName("Type", "Pages"));
        if (isPage) {
            if (kids == null || node.isName("Type", "Page")) {
                result.add(new Page(node, resources, mediaBox, cropBox, rotate));
                return;
            }
        }
        if (kids == null) return;
        for (int i = 0; i < kids.size() && result.size() < MAX_PAGES; i++) {
            final PdfDict kid = kids.dict(i);
            if (kid != null) collect(kid, resources, mediaBox, cropBox, rotate, depth + 1, visited, result);
        }
    }

    // ページの木が使えないときに、/Type /Page のオブジェクトを番号の順に集める
    private List<Page> scanPages() {
        final List<Page> result = new ArrayList<>();
        final List<Integer> numbers = new ArrayList<>(xref.keySet());
        java.util.Collections.sort(numbers);
        for (int number : numbers) {
            if (result.size() >= MAX_PAGES) break;
            final Object value = getObject(number);
            if (!(value instanceof PdfDict) || !((PdfDict) value).isName("Type", "Page")) continue;
            final PdfDict page = (PdfDict) value;
            // 親から受け継ぐ値も、たどれる範囲で集める
            PdfDict resources = null;
            double[] mediaBox = null, cropBox = null;
            int rotate = 0;
            boolean rotateFound = false;
            PdfDict node = page;
            final Map<PdfDict, Boolean> seen = new IdentityHashMap<>();
            for (int depth = 0; node != null && depth < MAX_TREE_DEPTH && seen.put(node, Boolean.TRUE) == null; depth++) {
                if (resources == null) resources = node.dict("Resources");
                if (mediaBox == null) mediaBox = pageBox(node.array("MediaBox"));
                if (cropBox == null) cropBox = pageBox(node.array("CropBox"));
                if (!rotateFound && node.get("Rotate") instanceof Number) {
                    rotate = Objects.toInt((Number) node.get("Rotate"));
                    rotateFound = true;
                }
                node = node.dict("Parent");
            }
            result.add(new Page(page, resources, mediaBox, cropBox, rotate));
        }
        return result;
    }

    // ページの箱 (MediaBox、CropBox)。[0 0 0 0] と書いたものは、ないものとして親から受け継ぐ (poppler と同じ。
    // そのまま使うと、1 ポイントの大きさのページをページの画像いっぱいに拡大して描いていた)
    private static double[] pageBox(PdfArray array) {
        final double[] box = rectangle(array);
        return box != null && box[0] == 0 && box[1] == 0 && box[2] == 0 && box[3] == 0 ? null : box;
    }

    // [x1 y1 x2 y2] を、左下と右上の順にそろえたもの。形が合わなければ null
    public static double[] rectangle(PdfArray array) {
        if (array == null || array.size() < 4) return null;
        final double x1 = array.number(0, Double.NaN), y1 = array.number(1, Double.NaN);
        final double x2 = array.number(2, Double.NaN), y2 = array.number(3, Double.NaN);
        if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return null;
        return new double[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
    }
}
