package marumasa.marumasa_sign.core.image;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.anim.AnimationFrame;
import marumasa.marumasa_sign.core.anim.ApngDecoder;
import marumasa.marumasa_sign.core.anim.DecodedAnimation;
import marumasa.marumasa_sign.core.anim.GifDecoder;
import marumasa.marumasa_sign.core.gltf.GltfLoader;
import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.splat.SplatLoader;
import marumasa.marumasa_sign.core.splat.SplatScene;
import marumasa.marumasa_sign.core.structure.StructureLoader;
import marumasa.marumasa_sign.core.pdf.PdfFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * URL から画像をダウンロードしてデコードする。
 */
public final class ImageLoader {

    public interface Callback {
        void onLoaded(DecodedImage image);

        // 3D モデルだったとき
        void onModel(GltfScene scene);

        // ガウシアンスプラッティングだったとき
        void onSplat(SplatScene scene);

        // PDF だったとき。page は最初に表示するページ (0 から始まる)、firstPage はそのページを画像にしたもの
        void onPdf(PdfFile file, int page, DecodedImage firstPage);

        // 画像でも 3D モデルでもガウシアンスプラッティングでも PDF でもなかったとき。動画や音声として再生できるかもしれない
        void onMedia();

        void onFailed();

        /**
         * まだ結果が要るかどうか。読み込みの順番を待っている間に看板が消された (メモリを空けるために消した、
         * 長く見えなかった、キャッシュを消したなど) ものは false を返す。読み込み始める前に確かめ、false なら読まずに捨てる。
         * 読み込みのスレッドから呼ぶ。
         */
        default boolean isWanted() {
            return true;
        }
    }

    // 静止画の大きさの上限
    public static final int MAX_STATIC_SIZE = 4096;
    // アニメーションの大きさとフレーム数の上限
    public static final int MAX_ANIMATION_SIZE = 2048;
    public static final int MAX_ANIMATION_FRAMES = 512;
    // アニメーションの全フレームのピクセル数の合計の上限 (WebP と同じ。1 ピクセル 4 バイトで 256 MB)
    public static final long MAX_ANIMATION_PIXELS = 64L * 1000 * 1000;
    // 3D モデルの大きさの上限 (外部ファイルを含む)
    public static final int MAX_MODEL_SIZE = 64 * 1024 * 1024;
    // ガウシアンスプラッティングの大きさの上限
    public static final int MAX_SPLAT_SIZE = 200 * 1024 * 1024;

    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_MODEL = 2;
    private static final int KIND_SPLAT = 3;
    private static final int KIND_PDF = 4;

    private static final class Request {
        final String url;
        final Callback callback;

        Request(String url, Callback callback) {
            this.url = url;
            this.callback = callback;
        }
    }

    private final Queue<Request> queue = new ConcurrentLinkedQueue<Request>();
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("MarumaSign-ImageLoader");
            return thread;
        }
    });

    // 同時ロード数の上限。最初に pump が呼ばれるまでは 0 (読み込みを始めない)
    private volatile int maxThreads;

    public void request(String url, Callback callback) {
        queue.add(new Request(url, callback));
        // 次の tick を待たずに始める
        final int limit = maxThreads;
        if (limit > 0) pump(limit);
    }

    public boolean isBusy() {
        return !queue.isEmpty() || activeDownloads.get() > 0;
    }

    // 読み込みの待ちと実行中の数 (動作確認で、読み込みが始まらないときの手がかりにする)
    public String status() {
        return "queued " + queue.size() + ", active " + activeDownloads.get() + " of " + maxThreads;
    }

    // 同時ロード数の上限まで、待っている画像の読み込みを始める。どのスレッドから呼んでもよい
    public void pump(int maxThreads) {
        this.maxThreads = maxThreads;
        while (!queue.isEmpty() && reserve(maxThreads)) {
            final Request request = queue.poll();
            if (request == null) {
                activeDownloads.decrementAndGet();
                break;
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 読み終えたら、待っている次の読み込みを続けて始める (次の tick を待たない)
                        Request current = request;
                        while (current != null) {
                            // 待っている間に要らなくなったものは読まない (見えている看板の読み込みを後回しにしないように)
                            if (current.callback.isWanted()) load(current);
                            current = activeDownloads.get() <= ImageLoader.this.maxThreads ? queue.poll() : null;
                        }
                    } finally {
                        activeDownloads.decrementAndGet();
                    }
                }
            });
        }
    }

    // 同時ロード数の枠を 1 つ取る。上限に達していれば false
    private boolean reserve(int limit) {
        while (true) {
            final int active = activeDownloads.get();
            if (active >= limit) return false;
            if (activeDownloads.compareAndSet(active, active + 1)) return true;
        }
    }

    private static void load(Request request) {
        final String url = request.url;
        MarumaSign.LOGGER.info("Start: " + url);
        try {
            // 先頭だけを読んで、画像・3D モデル・ガウシアンスプラッティング・PDF のどれでもなければ残りは読まない
            final int[] kind = new int[]{KIND_NONE};
            final String[] resolvedName = new String[]{url};
            final boolean[] webPage = new boolean[]{false};
            final Http.ContentCheck check = new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    resolvedName[0] = name;
                    webPage[0] = looksLikePage(head, length);
                    if (isSupportedImage(head, length, name)) {
                        kind[0] = KIND_IMAGE;
                        return Http.MAX_SIZE;
                    }
                    if (PdfFile.isPdf(head, length)) {
                        kind[0] = KIND_PDF;
                        return PdfFile.MAX_SIZE;
                    }
                    if (GltfLoader.isGlb(head, length)) {
                        kind[0] = KIND_MODEL;
                        return MAX_MODEL_SIZE;
                    }
                    if (StructureLoader.isStructure(head, length)) {
                        kind[0] = KIND_MODEL;
                        return StructureLoader.MAX_SIZE;
                    }
                    if (SplatLoader.isSplat(head, length, name)) {
                        kind[0] = KIND_SPLAT;
                        return MAX_SPLAT_SIZE;
                    }
                    if (GltfLoader.looksLikeJson(head, length)) {
                        kind[0] = KIND_MODEL;
                        return MAX_MODEL_SIZE;
                    }
                    return 0;
                }
            };
            final byte[] content;
            try {
                content = Http.download(url, check);
            } catch (IOException e) {
                // フォルダの URL (zip にしていない .sog) なら、その中の meta.json を読む
                final SplatScene folder = sogFolder(url);
                if (folder == null) throw e;
                if (request.callback.isWanted()) request.callback.onSplat(folder);
                return;
            }
            // ダウンロードしている間に要らなくなったものは、時間のかかる読み込み (デコードや 3D モデルの準備) をしない
            if (!request.callback.isWanted()) return;
            if (content == null && webPage[0]) {
                // フォルダの一覧などの Web ページが返ってきた。zip にしていない .sog のフォルダかもしれない
                final SplatScene folder = sogFolder(url);
                if (folder != null) {
                    if (request.callback.isWanted()) request.callback.onSplat(folder);
                    return;
                }
            }
            if (content == null) {
                // FFmpeg を探すのには時間がかかることがあるので、メインスレッドに渡す前にここで済ませておく
                Ffmpeg.isAvailable();
                request.callback.onMedia();
                return;
            }
            if (kind[0] == KIND_IMAGE) {
                request.callback.onLoaded(decode(content, resolvedName[0]));
                return;
            }
            if (kind[0] == KIND_SPLAT) {
                // 「#coord=RDF」のような座標の向きの指定は、ダウンロードした後の名前には残らないので付け直す
                request.callback.onSplat(splat(resolvedName[0] + Http.fragment(url), content));
                return;
            }
            if (kind[0] == KIND_MODEL && SplatLoader.isSogMeta(content)) {
                // zip にしていない .sog の meta.json
                request.callback.onSplat(sogFiles(url, content));
                return;
            }
            if (kind[0] == KIND_PDF) {
                final PdfFile file = PdfFile.open(content);
                try {
                    // URL の末尾に「#page=ページ番号」があれば、そのページから表示する
                    final int page = file.clampPage(PdfFile.requestedPage(url));
                    request.callback.onPdf(file, page, file.render(page, PdfFile.PAGE_SIZE));
                } catch (IOException | RuntimeException e) {
                    file.close();
                    throw e;
                }
                return;
            }
            request.callback.onModel(model(resolvedName[0], content));
        } catch (Exception e) {
            MarumaSign.LOGGER.info("Failure: " + url + " (" + e + ")");
            request.callback.onFailed();
        } catch (Throwable e) {
            // 画像の内容が原因ではない、想定外の失敗。握りつぶさずに記録する
            MarumaSign.LOGGER.error("Unexpected error while loading " + url, e);
            request.callback.onFailed();
        }
    }

    // 先頭のバイト列が 3D モデル (.glb / .gltf) か構造物のファイル (.schem / .litematic / .nbt / .mcstructure / .schematic) らしいかどうか
    public static boolean isModel(byte[] head, int length) {
        return GltfLoader.isGlb(head, length) || StructureLoader.isStructure(head, length) || GltfLoader.looksLikeJson(head, length);
    }

    // 先頭のバイト列が構造物のファイルらしいかどうか
    public static boolean isStructure(byte[] head, int length) {
        return StructureLoader.isStructure(head, length);
    }

    // 先頭のバイト列と名前がガウシアンスプラッティングのファイルらしいかどうか
    public static boolean isSplat(byte[] head, int length, String name) {
        return SplatLoader.isSplat(head, length, name);
    }

    // バイト列をガウシアンスプラッティングとして読み込む
    public static SplatScene splat(String name, byte[] content) throws IOException {
        return new SplatScene(SplatLoader.load(content, name));
    }

    // 先頭が「<」で始まる (HTML などの Web ページらしい)。フォルダの一覧やエラーのページ
    private static boolean looksLikePage(byte[] head, int length) {
        int i = 0;
        if (length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) i = 3;
        while (i < length && (head[i] == ' ' || head[i] == '\t' || head[i] == '\r' || head[i] == '\n')) i++;
        return i < length && head[i] == '<';
    }

    /**
     * フォルダの URL (末尾が「/」か、拡張子のない URL) の中の meta.json の URL。フォルダの URL でなければ null。
     * クエリ (?…) のある URL は、どこに meta.json を付けるか決まらないので扱わない
     */
    static String sogFolderMeta(String url) {
        final String base = Http.withoutFragment(url);
        if (base.indexOf('?') >= 0) return null;
        final int scheme = base.indexOf("://");
        if (scheme < 0) return null;
        final int slash = base.indexOf('/', scheme + 3);
        // 「https://example.com」のような、サーバーだけの URL
        if (slash < 0) return null;
        if (base.endsWith("/")) return base + "meta.json";
        if (base.substring(base.lastIndexOf('/') + 1).indexOf('.') >= 0) return null;
        return base + "/meta.json";
    }

    // フォルダの URL なら、その中の meta.json を zip にしていない .sog として読む。meta.json がないか、.sog の meta.json でなければ null
    private static SplatScene sogFolder(String url) throws IOException {
        final String metaUrl = sogFolderMeta(url);
        if (metaUrl == null) return null;
        final byte[] meta;
        try {
            meta = Http.download(metaUrl, new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    return GltfLoader.looksLikeJson(head, length) ? SplatLoader.MAX_SOG_META_SIZE : 0;
                }
            });
        } catch (IOException e) {
            return null;
        }
        if (meta == null || !SplatLoader.isSogMeta(meta)) return null;
        return sogFiles(metaUrl + Http.fragment(url), meta);
    }

    /**
     * zip にしていない .sog を読む。meta.json が参照する WebP は、meta.json の URL からの相対の位置で、
     * 看板の URL と同じ決まり (手元や家の中のネットワークは読まない) でダウンロードする
     *
     * @param metaUrl meta.json の URL (#coord= の指定を含んでよい)
     */
    static SplatScene sogFiles(String metaUrl, byte[] meta) throws IOException {
        final URL base = new URL(Http.withoutFragment(metaUrl));
        return new SplatScene(SplatLoader.loadSogFiles(meta, new SplatLoader.SogFiles() {
            @Override
            public byte[] load(String name, final int maxBytes) throws IOException {
                final URL resolved = new URL(base, name);
                final String protocol = resolved.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) {
                    throw new IOException("Unsupported SOG file location: " + name);
                }
                final byte[] content = Http.download(resolved.toString(), new Http.ContentCheck() {
                    @Override
                    public int maxBytes(byte[] head, int length, String resolvedName) {
                        return maxBytes;
                    }
                });
                if (content == null) throw new IOException("Not a file: " + name);
                return content;
            }
        }, metaUrl));
    }

    // ファイルをガウシアンスプラッティングとして読み込む
    public static SplatScene splat(File file) throws IOException {
        if (file.length() > MAX_SPLAT_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SPLAT_SIZE + " bytes.");
        return splat(file.getName(), Files.readAllBytes(file.toPath()));
    }

    // URL からダウンロードした内容を 3D モデルとして読み込む
    public static GltfScene model(String url, byte[] content) throws IOException {
        return model(url, content, 0L);
    }

    // deadline (System.nanoTime の値。0 なら期限なし) までに、外部ファイルも読み終える
    public static GltfScene model(String url, byte[] content, long deadline) throws IOException {
        if (StructureLoader.isStructure(content, Math.min(content.length, 512))) return StructureLoader.load(content);
        return scene(content, new ModelResources(url, content.length, deadline));
    }

    // ファイルを 3D モデルとして読み込む
    public static GltfScene model(File file) throws IOException {
        final long limit = Math.max(MAX_MODEL_SIZE, StructureLoader.MAX_SIZE);
        if (file.length() > limit) throw new IOException("Content size exceeds the limit of " + limit + " bytes.");
        final byte[] content = Files.readAllBytes(file.toPath());
        if (StructureLoader.isStructure(content, Math.min(content.length, 512))) return StructureLoader.load(content);
        return scene(content, fileResources(file));
    }

    private static GltfScene scene(byte[] content, GltfLoader.Resources resources) throws IOException {
        final GltfScene scene = new GltfScene(GltfLoader.load(content, resources));
        // 大きさの計算には時間がかかることがあるので、描画するスレッドに渡す前にここで済ませておく
        scene.restBounds();
        return scene;
    }

    // 3D モデルの外部ファイルとして読んでよい拡張子 (バッファと画像)
    private static final String[] RESOURCE_EXTENSIONS = {
            ".bin", ".glbin", ".glbuf", ".png", ".jpg", ".jpeg", ".webp", ".ktx2", ".ktx", ".dds", ".basis", ".avif", ".gif",
            ".bmp", ".tga", ".tif", ".tiff", ".hdr", ".exr",
    };

    /**
     * 3D モデルが参照している外部ファイルを、モデルのファイルと同じフォルダ (とその中のフォルダ) から読み込む。
     * 選んだモデルは、外部ファイルを取り込んだうえで誰でも見られる場所にアップロードする (GltfPacker)。
     * 人から受け取ったモデルが「../../」や絶対パスで別の場所のファイル (ランチャーのログイン情報など) を指していても
     * 一緒にアップロードしてしまわないように、フォルダの外のファイルと、バッファや画像ではない種類のファイルは読まない。
     */
    public static GltfLoader.Resources fileResources(final File file) {
        return new GltfLoader.Resources() {
            private long total = file.length();

            @Override
            public byte[] load(String uri) throws IOException {
                final File target = localResource(file, uri);
                total += target.length();
                if (total > MAX_MODEL_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_MODEL_SIZE + " bytes.");
                return Files.readAllBytes(target.toPath());
            }
        };
    }

    // モデルのファイル model から見た uri の場所。読んではいけない場所なら IOException
    static File localResource(File model, String uri) throws IOException {
        // URL の書き方 (file: や http: など) やドライブ名、区切りから始まる場所は、フォルダの外を指しうる
        if (uri.isEmpty() || uri.indexOf(':') >= 0 || uri.startsWith("/") || uri.startsWith("\\")) {
            throw new IOException("Unsupported resource location: " + uri);
        }
        final String decoded;
        try {
            // 「+」は空白ではなく、そのままの文字 (URL の % の書き方だけを戻す)
            decoded = URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid resource location: " + uri, e);
        }
        if (decoded.indexOf(':') >= 0 || decoded.indexOf('\0') >= 0 || decoded.startsWith("/") || decoded.startsWith("\\")) {
            throw new IOException("Unsupported resource location: " + uri);
        }
        final String lower = decoded.toLowerCase(java.util.Locale.ROOT);
        boolean known = false;
        for (String extension : RESOURCE_EXTENSIONS) {
            if (lower.endsWith(extension)) known = true;
        }
        if (!known) throw new IOException("Unsupported resource type: " + uri);
        final File folder = model.getAbsoluteFile().getParentFile().getCanonicalFile();
        final File target = new File(folder, decoded).getCanonicalFile();
        // ドライブの一番上 (C:\) のフォルダは、名前が区切りで終わっている
        final String prefix = folder.getPath().endsWith(File.separator) ? folder.getPath() : folder.getPath() + File.separator;
        // シンボリックリンクなどもたどった後の、本当の場所で確かめる
        if (!target.getPath().startsWith(prefix) || !target.isFile()) {
            throw new IOException("Resource is outside the model's folder: " + uri);
        }
        return target;
    }

    // 3D モデルが参照している外部ファイル (バッファや画像) を、モデルの URL からの相対位置で読み込む
    private static final class ModelResources implements GltfLoader.Resources {
        private final URL base;
        private final long deadline;
        private long total;

        ModelResources(String url, long loaded, long deadline) throws IOException {
            this.base = new URL(url);
            this.total = loaded;
            this.deadline = deadline;
        }

        @Override
        public byte[] load(String uri) throws IOException {
            final URL resolved = new URL(base, uri);
            final String protocol = resolved.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IOException("Unsupported resource location: " + uri);
            }
            // 全体の大きさを、モデル 1 つ分の上限に収める
            final long remaining = MAX_MODEL_SIZE - total;
            if (remaining <= 0) throw new IOException("Content size exceeds the limit of " + MAX_MODEL_SIZE + " bytes.");
            final byte[] content = Http.downloadBefore(resolved.toString(), new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    return (int) remaining;
                }
            }, deadline);
            // 画像を送り続ける配信などは、ファイルとして読めない
            if (content == null) throw new IOException("Not a file: " + uri);
            total += content.length;
            return content;
        }
    }

    public static DecodedImage decode(byte[] content) throws IOException {
        return decode(content, null);
    }

    /**
     * @param name ファイル名や URL。署名のない形式 (TGA など) の判定に使う。なければ null
     */
    public static DecodedImage decode(byte[] content, String name) throws IOException {
        if (content == null) throw new IOException("No content");

        final DecodedAnimation animation;
        if (isGif(content) || isApng(content)) {
            try {
                animation = isGif(content) ? GifDecoder.read(content) : ApngDecoder.read(content);
            } catch (RuntimeException e) {
                // 壊れたアニメーションによる範囲外の参照など
                throw new IOException("Invalid animation: " + e, e);
            }
        } else {
            // PNG と GIF 以外 (JPEG、WebP、TIFF、TGA、…) は ImageFormats に任せる
            final DecodedImage other = ImageFormats.decode(content, name);
            if (other != null) return withinLimits(other);
            return decodeStatic(content);
        }

        final int width = animation.getWidth();
        final int height = animation.getHeight();
        final List<AnimationFrame> frames = animation.getFrames();
        final int count = frames.size();
        // 1 コマだけの GIF や APNG は、静止画と同じ大きさまで読む
        final int max = count > 1 ? MAX_ANIMATION_SIZE : MAX_STATIC_SIZE;
        if (count == 0 || width <= 0 || height <= 0 || width > max || height > max || count > MAX_ANIMATION_FRAMES) {
            throw new IOException("Animation exceeds safety limits (max " + max + "x" + max + ", " + MAX_ANIMATION_FRAMES + " frames)");
        }

        final int[][] pixels = new int[count][];
        final int[] delays = new int[count];
        for (int i = 0; i < count; i++) {
            final AnimationFrame frame = frames.get(i);
            pixels[i] = frame.getPixels();
            delays[i] = frame.getDelay();
        }
        return new DecodedImage(width, height, pixels, delays, animation.getRepetitions());
    }

    // 大きさやフレーム数が上限を超えていたら断る
    private static DecodedImage withinLimits(DecodedImage image) throws IOException {
        final boolean animated = image.frames.length > 1;
        final int max = animated ? MAX_ANIMATION_SIZE : MAX_STATIC_SIZE;
        if (image.frames.length == 0 || image.width <= 0 || image.height <= 0 || image.width > max || image.height > max
                || image.frames.length > MAX_ANIMATION_FRAMES) {
            throw new IOException("Image exceeds safety limits (max " + max + "x" + max + ", " + MAX_ANIMATION_FRAMES + " frames)");
        }
        return image;
    }

    private static DecodedImage decodeStatic(byte[] content) throws IOException {
        // ここに来るのは、アニメーションでない PNG だけ。それ以外の形式は (ImageIO が読めても) 読まない。
        // ImageIO は、大きさを偽った画像や、中に JPEG の入った画像 (libjpeg で読まれる) も読もうとするため
        if (!isPng(content)) throw new IOException("Not an image");
        // ImageIO に渡す前に見出しの大きさを確かめる (大きさを偽ったファイルでメモリ不足にならないように)
        final long width = ((content[16] & 0xFFL) << 24) | ((content[17] & 0xFF) << 16) | ((content[18] & 0xFF) << 8) | (content[19] & 0xFF);
        final long height = ((content[20] & 0xFFL) << 24) | ((content[21] & 0xFF) << 16) | ((content[22] & 0xFF) << 8) | (content[23] & 0xFF);
        if (width <= 0 || height <= 0 || width > MAX_STATIC_SIZE || height > MAX_STATIC_SIZE) {
            throw new IOException("Image exceeds safety limits (max " + MAX_STATIC_SIZE + "x" + MAX_STATIC_SIZE + ")");
        }
        final BufferedImage image;
        try {
            image = ImageBytes.read(content);
        } catch (RuntimeException e) {
            throw new IOException("Not an image", e);
        }
        if (image == null) throw new IOException("Not an image");
        return fromBufferedImage(image);
    }

    // 先頭が PNG の署名で、最初のチャンクが IHDR か (大きさは IHDR の中にある)
    private static boolean isPng(byte[] content) {
        if (content.length < 24) return false;
        for (int i = 0; i < 8; i++) {
            if (content[i] != PNG_SIGNATURE[i]) return false;
        }
        return content[12] == 'I' && content[13] == 'H' && content[14] == 'D' && content[15] == 'R';
    }

    public static DecodedImage fromBufferedImage(BufferedImage image) throws IOException {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_STATIC_SIZE || height > MAX_STATIC_SIZE) {
            throw new IOException("Image exceeds safety limits (max " + MAX_STATIC_SIZE + "x" + MAX_STATIC_SIZE + ")");
        }
        return new DecodedImage(width, height, new int[][]{ArgbPixels.readDisplay(image)}, new int[]{1}, 0);
    }

    // jar に同梱されている画像を読み込む
    public static DecodedImage readResource(String path) {
        try {
            final InputStream stream = ImageLoader.class.getResourceAsStream(path);
            if (stream == null) throw new IOException("Resource not found: " + path);
            try {
                return fromBufferedImage(ImageIO.read(stream));
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            MarumaSign.LOGGER.error("Failed to load resource: " + path, e);
            // 読めなかった場合は 1 ピクセルのマゼンタで代用する
            return new DecodedImage(1, 1, new int[][]{{0xFFFF00FF}}, new int[]{1}, 0);
        }
    }

    // 読める静止画の形式かどうかを、先頭のバイト列から判定する
    public static boolean isSupportedImage(byte[] head, int length) {
        return isSupportedImage(head, length, null);
    }

    /**
     * @param name ファイル名や URL。署名のない形式 (TGA、WBMP、XBM) の判定に使う。なければ null
     */
    public static boolean isSupportedImage(byte[] head, int length, String name) {
        return ImageFormats.isSupported(head, length, name);
    }

    public static boolean isGif(byte[] bytes) {
        if (bytes == null || bytes.length < 6) return false;
        final String header = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
        return header.equals("GIF89a") || header.equals("GIF87a");
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public static boolean isApng(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false;
        }
        // 大きな長さを書かれても桁あふれしないよう、位置は long で数える
        long pos = 8;
        while (pos + 8 <= bytes.length) {
            final int at = (int) pos;
            final int length = ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                    | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
            if (length < 0) break;
            final String type = new String(bytes, at + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL")) return true;
            // 画像データより後ろに acTL が来ることはない
            if (type.equals("IDAT")) return false;
            pos += 8L + length + 4;
        }
        return false;
    }
}
