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

        // PDF だったとき。firstPage は最初のページを画像にしたもの
        void onPdf(PdfFile file, DecodedImage firstPage);

        // 画像でも 3D モデルでもガウシアンスプラッティングでも PDF でもなかったとき。動画や音声として再生できるかもしれない
        void onMedia();

        void onFailed();
    }

    // 静止画の大きさの上限
    private static final int MAX_STATIC_SIZE = 4096;
    // アニメーションの大きさとフレーム数の上限
    private static final int MAX_ANIMATION_SIZE = 2048;
    private static final int MAX_ANIMATION_FRAMES = 512;
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
                            load(current);
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
            final byte[] content = Http.download(url, new Http.ContentCheck() {
                @Override
                public int maxBytes(byte[] head, int length, String name) {
                    resolvedName[0] = name;
                    if (isSupportedImage(head, length)) {
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
            });
            if (content == null) {
                // FFmpeg を探すのには時間がかかることがあるので、メインスレッドに渡す前にここで済ませておく
                Ffmpeg.isAvailable();
                request.callback.onMedia();
                return;
            }
            if (kind[0] == KIND_IMAGE) {
                request.callback.onLoaded(decode(content));
                return;
            }
            if (kind[0] == KIND_SPLAT) {
                request.callback.onSplat(splat(resolvedName[0], content));
                return;
            }
            if (kind[0] == KIND_PDF) {
                final PdfFile file = PdfFile.open(content);
                try {
                    request.callback.onPdf(file, file.render(0, PdfFile.PAGE_SIZE));
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

    // ファイルをガウシアンスプラッティングとして読み込む
    public static SplatScene splat(File file) throws IOException {
        if (file.length() > MAX_SPLAT_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_SPLAT_SIZE + " bytes.");
        return splat(file.getName(), Files.readAllBytes(file.toPath()));
    }

    // URL からダウンロードした内容を 3D モデルとして読み込む
    public static GltfScene model(String url, byte[] content) throws IOException {
        if (StructureLoader.isStructure(content, Math.min(content.length, 512))) return StructureLoader.load(content);
        return scene(content, new ModelResources(url, content.length));
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

    // 3D モデルが参照している外部ファイルを、モデルのファイルと同じフォルダから読み込む
    public static GltfLoader.Resources fileResources(final File file) {
        return new GltfLoader.Resources() {
            private long total = file.length();

            @Override
            public byte[] load(String uri) throws IOException {
                final File target = new File(file.getAbsoluteFile().getParentFile(), URLDecoder.decode(uri, "UTF-8"));
                total += target.length();
                if (total > MAX_MODEL_SIZE) throw new IOException("Content size exceeds the limit of " + MAX_MODEL_SIZE + " bytes.");
                return Files.readAllBytes(target.toPath());
            }
        };
    }

    // 3D モデルが参照している外部ファイル (バッファや画像) を、モデルの URL からの相対位置で読み込む
    private static final class ModelResources implements GltfLoader.Resources {
        private final URL base;
        private long total;

        ModelResources(String url, long loaded) throws IOException {
            this.base = new URL(url);
            this.total = loaded;
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
            final byte[] content = Http.download(resolved.toString(), null, (int) remaining);
            total += content.length;
            return content;
        }
    }

    public static DecodedImage decode(byte[] content) throws IOException {
        if (content == null) throw new IOException("No content");

        final DecodedAnimation animation;
        if (isGif(content)) {
            animation = GifDecoder.read(content);
        } else if (isApng(content)) {
            animation = ApngDecoder.read(content);
        } else {
            return decodeStatic(content);
        }

        final int width = animation.getWidth();
        final int height = animation.getHeight();
        final List<AnimationFrame> frames = animation.getFrames();
        final int count = frames.size();
        if (count == 0 || width <= 0 || height <= 0
                || width > MAX_ANIMATION_SIZE || height > MAX_ANIMATION_SIZE || count > MAX_ANIMATION_FRAMES) {
            throw new IOException("Animation exceeds safety limits (max " + MAX_ANIMATION_SIZE + "x" + MAX_ANIMATION_SIZE
                    + ", " + MAX_ANIMATION_FRAMES + " frames)");
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

    private static DecodedImage decodeStatic(byte[] content) throws IOException {
        final BufferedImage image;
        try {
            image = ImageBytes.read(content);
        } catch (RuntimeException e) {
            throw new IOException("Not an image", e);
        }
        if (image == null) throw new IOException("Not an image");
        return fromBufferedImage(image);
    }

    public static DecodedImage fromBufferedImage(BufferedImage image) throws IOException {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_STATIC_SIZE || height > MAX_STATIC_SIZE) {
            throw new IOException("Image exceeds safety limits (max " + MAX_STATIC_SIZE + "x" + MAX_STATIC_SIZE + ")");
        }
        return new DecodedImage(width, height, new int[][]{ArgbPixels.read(image)}, new int[]{1}, 0);
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

    // このクラスで読める形式 (PNG, GIF, JPEG, BMP) かどうかを、先頭のバイト列から判定する
    public static boolean isSupportedImage(byte[] head, int length) {
        if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true;
        if (length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return true;
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return true;
        return length >= 2 && head[0] == 'B' && head[1] == 'M';
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
        int pos = 8;
        while (pos + 8 <= bytes.length) {
            final int length = ((bytes[pos] & 0xFF) << 24) | ((bytes[pos + 1] & 0xFF) << 16)
                    | ((bytes[pos + 2] & 0xFF) << 8) | (bytes[pos + 3] & 0xFF);
            if (length < 0) break;
            final String type = new String(bytes, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL")) return true;
            // 画像データより後ろに acTL が来ることはない
            if (type.equals("IDAT")) return false;
            pos += 8 + length + 4;
        }
        return false;
    }
}
