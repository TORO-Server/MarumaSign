package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板の文字列から表示する画像を引くための管理クラス。
 */
public final class SignManager {

    private final Platform platform;
    private final ImageLoader loader;

    // 読み込み済み看板マップ (看板の文字列 -> 画像)
    private final Map<String, SignImage> signs = new ConcurrentHashMap<String, SignImage>();
    // 読み込み済みテクスチャマップ (URL -> テクスチャ)
    private final Map<String, TextureEntry> textures = new ConcurrentHashMap<String, TextureEntry>();

    // 読み込み中・読み込み失敗のときに表示するテクスチャ
    private Object loadingTexture;
    private Object errorTexture;
    private int placeholderWidth = 160;
    private int placeholderHeight = 96;

    public SignManager(Platform platform, ImageLoader loader) {
        this.platform = platform;
        this.loader = loader;
    }

    /**
     * 看板の文字列に対応する画像を返す。MarumaSign の看板でなければ null。
     * メインスレッドから呼ぶこと。
     */
    public SignImage get(String text) {
        if (!SignSpec.looksLikeSign(text)) return null;

        SignImage image = signs.get(text);
        if (image != null) return image;

        final SignSpec spec = SignSpec.parse(text);
        if (spec == null) return null;

        image = new SignImage(spec, texture(spec.url));
        signs.put(text, image);
        return image;
    }

    private TextureEntry texture(final String url) {
        TextureEntry entry = textures.get(url);
        if (entry != null) return entry;

        ensurePlaceholders();
        final TextureEntry created = new TextureEntry(TextureEntry.LOADING, placeholderWidth, placeholderHeight, loadingTexture);
        textures.put(url, created);

        loader.request(url, new ImageLoader.Callback() {
            @Override
            public void onLoaded(final DecodedImage decoded) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        register(url, created, decoded);
                    }
                });
            }

            @Override
            public void onFailed() {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        fail(url, created);
                    }
                });
            }
        });
        return created;
    }

    private void register(String url, TextureEntry entry, DecodedImage decoded) {
        // キャッシュ削除後に届いた結果は捨てる
        if (textures.get(url) != entry) return;
        try {
            final int count = decoded.frames.length;
            final Object[] frames = new Object[count];
            final int[] frameEnd = new int[count];
            int time = 0;
            for (int i = 0; i < count; i++) {
                frames[i] = platform.createTexture(decoded.width, decoded.height, decoded.frames[i]);
                time += Math.max(1, decoded.delays[i]);
                frameEnd[i] = time;
            }
            entry.set(TextureEntry.LOADED, decoded.width, decoded.height, frames, frameEnd, decoded.repetitions);
            MarumaSign.LOGGER.info("Load: " + url);
        } catch (RuntimeException e) {
            MarumaSign.LOGGER.error("Failed to register texture: " + url, e);
            fail(url, entry);
        }
    }

    private void fail(String url, TextureEntry entry) {
        if (textures.get(url) != entry) return;
        entry.set(TextureEntry.ERROR, placeholderWidth, placeholderHeight, new Object[]{errorTexture}, new int[]{1}, 0);
    }

    private void ensurePlaceholders() {
        if (loadingTexture != null) return;
        final DecodedImage loading = ImageLoader.readResource("/marumasa_sign/loading.png");
        final DecodedImage error = ImageLoader.readResource("/marumasa_sign/error.png");
        placeholderWidth = loading.width;
        placeholderHeight = loading.height;
        loadingTexture = platform.createTexture(loading.width, loading.height, loading.frames[0]);
        errorTexture = platform.createTexture(error.width, error.height, error.frames[0]);
    }

    // 読み込み待ち・読み込み中の画像があるかどうか
    public boolean isBusy() {
        return loader.isBusy();
    }

    // 登録されているテクスチャをすべて削除する
    public void clearCache() {
        for (TextureEntry entry : textures.values()) {
            if (entry.state() != TextureEntry.LOADED) continue;
            for (Object frame : entry.frames()) {
                platform.releaseTexture(frame);
            }
        }
        signs.clear();
        textures.clear();
    }
}
