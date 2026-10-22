package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.pdf.PdfFile;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 看板に表示している PDF 1 つ分。表示中のページと、そのページを映すテクスチャを持つ。
 * ページは見る人ごと (このクライアントだけ) に切り替わり、同じ URL の看板はすべて同じページを表示する
 * (「#page=」の指定が違えば別の URL として扱うので、看板ごとに違うページから始められる)。
 * メインスレッドからのみ使う (ページを画像にする処理だけは別のスレッドで行う)。
 */
final class PdfSession {

    // 描き終えたページを覚えておく数。前後のページへすぐに切り替えられるようにする
    private static final int CACHED_PAGES = 4;

    // ページを画像にするスレッド。重い処理なので、すべての PDF で 1 つを共有する
    private static final ExecutorService RENDERER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "MarumaSign-PDF");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    });

    private final Platform platform;
    private final String url;
    private final PdfFile file;
    private final TextureEntry entry;
    private final int pageCount;

    // 表示したいページと、テクスチャに入っているページ (0 から始まる)
    private int page;
    private int shownPage = -1;
    // 画像にし終えたページ (ページ番号 -> 画像)。古いものから捨てる
    private final Map<Integer, DecodedImage> cache = new LinkedHashMap<Integer, DecodedImage>();
    // 画像にしている途中のページ。なければ -1
    private int rendering = -1;
    private boolean closed;

    private Object texture;
    private int textureWidth, textureHeight;

    PdfSession(Platform platform, String url, PdfFile file, TextureEntry entry) {
        this.platform = platform;
        this.url = url;
        this.file = file;
        this.entry = entry;
        this.pageCount = file.pageCount();
    }

    // 最初に表示するページ (0 から始まる。URL の「#page=」で指定できる) を表示する。first はそのページを画像にしたもの
    void start(int first, DecodedImage image) {
        page = first;
        cache.put(first, image);
        show(first, image);
        renderNext();
    }

    // 表示したいページ (0 から始まる)
    int page() {
        return page;
    }

    int pageCount() {
        return pageCount;
    }

    // 表示したいページを画像にしている途中かどうか
    boolean isLoading() {
        return shownPage != page;
    }

    /**
     * ページを進める (delta が正) か戻す (負)。最初と最後のページより先へは進まない。
     *
     * @return ページが変わったかどうか
     */
    boolean turn(int delta) {
        final int next = Math.max(0, Math.min(pageCount - 1, page + delta));
        if (next == page) return false;
        page = next;
        // 画像にしてあればすぐに表示し、なければ描き終わったときに表示する
        final DecodedImage cached = cache.get(next);
        if (cached != null) show(next, cached);
        renderNext();
        return true;
    }

    // 表示したいページ、その次のページ、前のページの順に、まだ画像にしていないものを 1 つ描く
    private void renderNext() {
        if (closed || rendering >= 0) return;
        int target = -1;
        if (!cache.containsKey(page)) {
            target = page;
        } else if (page + 1 < pageCount && !cache.containsKey(page + 1)) {
            target = page + 1;
        } else if (page > 0 && !cache.containsKey(page - 1)) {
            target = page - 1;
        }
        if (target < 0) return;
        final int index = target;
        rendering = index;
        RENDERER.submit(new Runnable() {
            @Override
            public void run() {
                DecodedImage image = null;
                try {
                    image = file.render(index, PdfFile.PAGE_SIZE);
                } catch (IOException | RuntimeException e) {
                    MarumaSign.LOGGER.info("Could not draw page " + (index + 1) + " of " + url + " (" + e + ")");
                } catch (OutOfMemoryError e) {
                    MarumaSign.LOGGER.info("Not enough memory to draw page " + (index + 1) + " of " + url);
                }
                final DecodedImage result = image;
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        rendered(index, result);
                    }
                });
            }
        });
    }

    private void rendered(int index, DecodedImage image) {
        rendering = -1;
        if (closed) return;
        if (image == null) {
            // 描けなかったページは白紙にして、同じページを何度も描こうとしないようにする
            image = blank();
        }
        cache.put(index, image);
        while (cache.size() > CACHED_PAGES) {
            // 表示中のページと、そのすぐ隣は残す
            boolean removed = false;
            for (Iterator<Integer> it = cache.keySet().iterator(); it.hasNext(); ) {
                final int key = it.next();
                if (Math.abs(key - page) > 1) {
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) break;
        }
        if (index == page) show(index, image);
        renderNext();
    }

    private DecodedImage blank() {
        final int width = Math.max(1, textureWidth), height = Math.max(1, textureHeight);
        final int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        return new DecodedImage(width, height, new int[][]{pixels}, new int[]{1}, 0);
    }

    private void show(int index, DecodedImage image) {
        if (closed) return;
        try {
            if (texture != null && textureWidth == image.width && textureHeight == image.height) {
                platform.updateTexture(texture, image.width, image.height, image.frames[0]);
            } else {
                final Object created = platform.createTexture(image.width, image.height, image.frames[0]);
                MarumaSign.releaseTexture(platform, texture);
                texture = created;
                textureWidth = image.width;
                textureHeight = image.height;
            }
        } catch (RuntimeException | Error e) {
            MarumaSign.LOGGER.error("Failed to show page " + (index + 1) + " of " + url, e);
            return;
        }
        shownPage = index;
        // テクスチャは、このクラスが持っているので、ここでは所有しない
        entry.set(TextureEntry.LOADED, image.width, image.height, new Object[]{texture}, new int[]{1}, 0, false);
    }

    void close() {
        closed = true;
        cache.clear();
        // 描いている途中のページがあれば、それが終わってから閉じる (メインスレッドを待たせない)
        RENDERER.submit(new Runnable() {
            @Override
            public void run() {
                file.close();
            }
        });
        if (texture != null) {
            MarumaSign.releaseTexture(platform, texture);
            texture = null;
        }
    }
}
