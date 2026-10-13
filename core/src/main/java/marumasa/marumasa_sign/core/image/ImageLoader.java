package marumasa.marumasa_sign.core.image;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.anim.AnimationFrame;
import marumasa.marumasa_sign.core.anim.ApngDecoder;
import marumasa.marumasa_sign.core.anim.DecodedAnimation;
import marumasa.marumasa_sign.core.anim.GifDecoder;
import marumasa.marumasa_sign.core.net.Http;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

        void onFailed();
    }

    // 静止画の大きさの上限
    private static final int MAX_STATIC_SIZE = 4096;
    // アニメーションの大きさとフレーム数の上限
    private static final int MAX_ANIMATION_SIZE = 2048;
    private static final int MAX_ANIMATION_FRAMES = 512;

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

    public void request(String url, Callback callback) {
        queue.add(new Request(url, callback));
    }

    public boolean isBusy() {
        return !queue.isEmpty() || activeDownloads.get() > 0;
    }

    // 同時ロード数の上限まで、待っている画像の読み込みを始める
    public void pump(int maxThreads) {
        while (activeDownloads.get() < maxThreads) {
            final Request request = queue.poll();
            if (request == null) break;
            activeDownloads.incrementAndGet();
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        load(request);
                    } finally {
                        activeDownloads.decrementAndGet();
                    }
                }
            });
        }
    }

    private static void load(Request request) {
        final String url = request.url;
        MarumaSign.LOGGER.info("Start: " + url);
        try {
            final DecodedImage image = decode(Http.download(url));
            request.callback.onLoaded(image);
        } catch (Exception e) {
            MarumaSign.LOGGER.info("Failure: " + url + " (" + e + ")");
            request.callback.onFailed();
        } catch (Throwable e) {
            // 画像の内容が原因ではない、想定外の失敗。握りつぶさずに記録する
            MarumaSign.LOGGER.error("Unexpected error while loading " + url, e);
            request.callback.onFailed();
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
            image = ImageIO.read(new ByteArrayInputStream(content));
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
        final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new DecodedImage(width, height, new int[][]{pixels}, new int[]{1}, 0);
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
