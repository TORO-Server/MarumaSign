package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.gltf.GltfModel;
import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.gltf.TextureAnimation;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.splat.SplatScene;
import marumasa.marumasa_sign.core.pdf.PdfFile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板の文字列から表示する画像を引くための管理クラス。
 */
public final class SignManager {

    // 看板の中身が使ってよいメモリのおおよその合計 (バイト)。看板は誰でも書けるので、違う URL の看板を
    // たくさん並べられても、メモリを使い切らないようにする。超えたら、いま描かれていないものから消す
    public static final long MAX_BYTES = 1024L * 1024 * 1024;
    // この時間 (ミリ秒) 描かれていなければ、メモリが足りなくなったときに消してよい
    private static final long IDLE_MILLIS = 5000;
    // この時間 (ミリ秒) 描かれなかったものは消す (また見えたら読み込み直す)
    private static final long UNUSED_MILLIS = 5 * 60 * 1000;
    // 同時に再生する動画や音声の数の上限。超えたら、近いものから再生する
    public static final int MAX_PLAYING = 8;

    private final Platform platform;
    private final ImageLoader loader;

    // 読み込み済み看板マップ (看板の文字列 -> 画像)
    private final Map<String, SignImage> signs = new ConcurrentHashMap<String, SignImage>();
    // 読み込み済みテクスチャマップ (URL -> テクスチャ)
    private final Map<String, TextureEntry> textures = new ConcurrentHashMap<String, TextureEntry>();
    // 再生中の動画や音声
    private final List<MediaSession> sessions = new ArrayList<MediaSession>();
    // 表示中の PDF と、そのページ送りの操作
    private final List<PdfSession> pdfs = new ArrayList<PdfSession>();
    private final PdfControls controls;
    // 時間で絵が変わるテクスチャを持つ 3D モデル (構造物の水など)
    private final List<AnimatedModel> animated = new ArrayList<AnimatedModel>();

    private static final class AnimatedModel {
        final String url;
        final TextureEntry entry;
        final GltfModel model;
        final Object[] textures;
        final long start = System.currentTimeMillis();

        AnimatedModel(String url, TextureEntry entry, GltfModel model, Object[] textures) {
            this.url = url;
            this.entry = entry;
            this.model = model;
            this.textures = textures;
        }
    }
    private boolean ffmpegNoticeShown;

    // 読み込み中・読み込み失敗のときに表示するテクスチャ
    private Object loadingTexture;
    private Object errorTexture;
    // 音声だけを再生しているときに表示するテクスチャ
    private Object audioTexture;
    private int placeholderWidth = 160;
    private int placeholderHeight = 96;

    public SignManager(Platform platform, ImageLoader loader) {
        this.platform = platform;
        this.loader = loader;
        this.controls = new PdfControls(platform);
    }

    // PDF のページ送りの操作
    public PdfControls pdfControls() {
        return controls;
    }

    /**
     * 看板の文字列に対応する画像を返す。MarumaSign の看板でなければ null。
     * メインスレッドから呼ぶこと。
     */
    public SignImage get(String text) {
        if (!SignSpec.looksLikeSign(text)) return null;

        SignImage image = signs.get(text);
        if (image != null) {
            // 描くための計算で例外が出たものは描かない (看板そのものを表示する)。
            // 使われていないものとして、しばらくすると消えるので、そのあとでもう一度試す
            if (image.isBroken()) return null;
            touch(image);
            return image;
        }

        final SignSpec spec = SignSpec.parse(text);
        if (spec == null) return null;

        image = new SignImage(spec, texture(spec.url), controls);
        signs.put(text, image);
        touch(image);
        return image;
    }

    private static void touch(SignImage image) {
        image.lastUsed = System.currentTimeMillis();
        image.textureEntry().touch();
    }

    /**
     * bytes を新しく使えるように、描かれていないものを古い順に消す。それでも足りなければ false。
     */
    private boolean makeRoom(TextureEntry keep, long bytes) {
        long total = bytes;
        for (TextureEntry entry : textures.values()) {
            if (entry != keep) total += entry.bytes();
        }
        if (total <= MAX_BYTES) return true;
        final List<Map.Entry<String, TextureEntry>> idle = new ArrayList<Map.Entry<String, TextureEntry>>();
        final long now = System.currentTimeMillis();
        for (Map.Entry<String, TextureEntry> item : textures.entrySet()) {
            final TextureEntry entry = item.getValue();
            if (entry != keep && entry.bytes() > 0 && now - entry.lastUsed() > IDLE_MILLIS) idle.add(item);
        }
        Collections.sort(idle, new Comparator<Map.Entry<String, TextureEntry>>() {
            @Override
            public int compare(Map.Entry<String, TextureEntry> a, Map.Entry<String, TextureEntry> b) {
                return Long.compare(a.getValue().lastUsed(), b.getValue().lastUsed());
            }
        });
        for (Map.Entry<String, TextureEntry> item : idle) {
            if (total <= MAX_BYTES) break;
            total -= item.getValue().bytes();
            evict(item.getKey(), item.getValue());
        }
        if (total <= MAX_BYTES) return true;
        MarumaSign.LOGGER.info("Not enough memory for more signs (" + (total >> 20) + " MB would be used)");
        return false;
    }

    // URL の読み込み結果を捨て、テクスチャや再生を片付ける。また看板が描かれれば読み込み直す
    private void evict(String url, TextureEntry entry) {
        if (!textures.remove(url, entry)) return;
        final MediaSession media = entry.media();
        if (media != null) {
            media.close();
            sessions.remove(media);
        }
        final PdfSession pdf = entry.pdf();
        if (pdf != null) {
            pdf.close();
            pdfs.remove(pdf);
        }
        for (Object texture : entry.ownedTextures()) {
            MarumaSign.releaseTexture(platform, texture);
        }
        for (Iterator<SignImage> it = signs.values().iterator(); it.hasNext(); ) {
            if (it.next().textureEntry() == entry) it.remove();
        }
    }

    // URL の読み込みの状態。まだ看板に使われていなければ null
    public TextureEntry entry(String url) {
        return url == null ? null : textures.get(url);
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
            public void onModel(final GltfScene scene) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        registerModel(url, created, scene);
                    }
                });
            }

            @Override
            public void onSplat(final SplatScene scene) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        registerSplat(url, created, scene);
                    }
                });
            }

            @Override
            public void onPdf(final PdfFile file, final int page, final DecodedImage firstPage) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        registerPdf(url, created, file, page, firstPage);
                    }
                });
            }

            @Override
            public void onMedia() {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        startMedia(url, created);
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
        final int count = decoded.frames.length;
        final long bytes = 4L * decoded.width * decoded.height * count;
        if (!makeRoom(entry, bytes)) {
            fail(url, entry);
            return;
        }
        try {
            final Object[] frames = new Object[count];
            final int[] frameEnd = new int[count];
            int time = 0;
            for (int i = 0; i < count; i++) {
                frames[i] = platform.createTexture(decoded.width, decoded.height, decoded.frames[i]);
                time += Math.max(1, decoded.delays[i]);
                frameEnd[i] = time;
            }
            entry.set(TextureEntry.LOADED, decoded.width, decoded.height, frames, frameEnd, decoded.repetitions, true);
            entry.setBytes(bytes);
            MarumaSign.LOGGER.info("Load: " + url + " (" + entry.loadMillis() + " ms)");
        } catch (RuntimeException | Error e) {
            // スケジュールされたタスクの中の例外は呼び出し元で捨てられるため、ここで記録する
            MarumaSign.LOGGER.error("Failed to register texture: " + url, e);
            fail(url, entry);
        }
    }

    private void registerModel(String url, TextureEntry entry, GltfScene scene) {
        if (textures.get(url) != entry) return;
        final GltfModel model = scene.model();
        // テクスチャ (この中と GPU の 2 つ分) と頂点
        long bytes = 96L * model.triangleCount();
        for (int i = 0; i < model.textureCount(); i++) bytes += 8L * model.texture(i).width * model.texture(i).height;
        if (!makeRoom(entry, bytes)) {
            fail(url, entry);
            return;
        }
        final Object[] created = new Object[model.textureCount()];
        try {
            for (int i = 0; i < created.length; i++) {
                final GltfModel.Texture texture = model.texture(i);
                created[i] = platform.createTexture(texture.width, texture.height, texture.pixels);
            }
            entry.setModel(scene, created);
            entry.setBytes(bytes);
            if (model.textureAnimations().length > 0) animated.add(new AnimatedModel(url, entry, model, created));
            MarumaSign.LOGGER.info("Load: " + url + " (" + model.triangleCount() + " triangles, " + entry.loadMillis() + " ms)");
        } catch (RuntimeException | Error e) {
            MarumaSign.LOGGER.error("Failed to register model: " + url, e);
            for (Object texture : created) {
                MarumaSign.releaseTexture(platform, texture);
            }
            fail(url, entry);
        }
    }

    private void registerSplat(String url, TextureEntry entry, SplatScene scene) {
        if (textures.get(url) != entry) return;
        final long bytes = 64L * scene.cloud().count;
        if (!makeRoom(entry, bytes)) {
            fail(url, entry);
            return;
        }
        Object created = null;
        try {
            created = platform.createTexture(SplatScene.TEXTURE_SIZE, SplatScene.TEXTURE_SIZE, SplatScene.gaussianTexture());
            entry.setSplat(scene, created);
            entry.setBytes(bytes);
            MarumaSign.LOGGER.info("Load: " + url + " (" + scene.cloud().count + " splats, " + entry.loadMillis() + " ms)");
        } catch (RuntimeException | Error e) {
            MarumaSign.LOGGER.error("Failed to register splat: " + url, e);
            MarumaSign.releaseTexture(platform, created);
            fail(url, entry);
        }
    }

    private void registerPdf(String url, TextureEntry entry, PdfFile file, int page, DecodedImage firstPage) {
        // ページの絵 (この中と GPU の 2 つ分) と、読み込んだ PDF
        final long bytes = 8L * firstPage.width * firstPage.height + file.size();
        if (textures.get(url) != entry || !makeRoom(entry, bytes)) {
            file.close();
            if (textures.get(url) == entry) fail(url, entry);
            return;
        }
        final PdfSession session = new PdfSession(platform, url, file, entry);
        entry.setPdf(session);
        entry.setBytes(bytes);
        pdfs.add(session);
        session.start(page, firstPage);
        MarumaSign.LOGGER.info("Load: " + url + " (PDF, " + file.pageCount() + " pages, " + entry.loadMillis() + " ms)");
    }

    // 画像ではなかった URL を、動画や音声として再生する
    private void startMedia(final String url, final TextureEntry entry) {
        if (textures.get(url) != entry) return;
        if (!Ffmpeg.isAvailable()) {
            // 再生できない理由を、最初の 1 回だけチャットで知らせる
            if (!ffmpegNoticeShown) {
                ffmpegNoticeShown = true;
                MarumaSign.chat("text.ffmpeg_missing");
            }
            fail(url, entry);
            return;
        }
        final MediaSession[] holder = new MediaSession[1];
        final MediaSession session = new MediaSession(platform, url, new MediaSession.Listener() {
            @Override
            public void onReady(int width, int height) {
                if (textures.get(url) != entry) return;
                // 映像のフレーム (この中と GPU の 2 つ分)
                entry.setBytes(8L * width * height);
                if (width > 0) {
                    // テクスチャは再生の側が持っているので、ここでは所有しない
                    entry.set(TextureEntry.LOADED, width, height, new Object[]{holder[0].texture()}, new int[]{1}, 0, false);
                } else {
                    // 音声だけの場合は、スピーカーの絵を表示する
                    entry.set(TextureEntry.LOADED, placeholderWidth, placeholderHeight, new Object[]{audioTexture}, new int[]{1}, 0, false);
                }
                MarumaSign.LOGGER.info("Play: " + url + " (" + entry.loadMillis() + " ms)");
            }

            @Override
            public void onFailed() {
                fail(url, entry);
            }
        });
        holder[0] = session;
        entry.setMedia(session);
        // 再生は、描かれたときに、同時に再生する数の上限の中で始める (allowNearestMedia)
        sessions.add(session);
    }

    private void fail(String url, TextureEntry entry) {
        if (textures.get(url) != entry) return;
        entry.set(TextureEntry.ERROR, placeholderWidth, placeholderHeight, new Object[]{errorTexture}, new int[]{1}, 0, false);
    }

    private void ensurePlaceholders() {
        if (loadingTexture != null) return;
        final DecodedImage loading = ImageLoader.readResource("/marumasa_sign/loading.png");
        final DecodedImage error = ImageLoader.readResource("/marumasa_sign/error.png");
        placeholderWidth = loading.width;
        placeholderHeight = loading.height;
        loadingTexture = platform.createTexture(loading.width, loading.height, loading.frames[0]);
        errorTexture = platform.createTexture(error.width, error.height, error.frames[0]);
        audioTexture = platform.createTexture(placeholderWidth, placeholderHeight, speakerIcon(placeholderWidth, placeholderHeight));
    }

    // スピーカーの絵を描く
    private static int[] speakerIcon(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x3C3C3C));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0x6ABE30));
            final int cx = width / 2 - height / 6;
            final int cy = height / 2;
            final int unit = height / 8;
            // 本体
            g.fillRect(cx - unit * 2, cy - unit, unit, unit * 2);
            g.fillPolygon(new int[]{cx - unit, cx + unit, cx + unit, cx - unit},
                    new int[]{cy - unit, cy - unit * 2 - unit / 2, cy + unit * 2 + unit / 2, cy + unit}, 4);
            // 音の波
            g.setStroke(new BasicStroke(Math.max(2f, unit / 2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i <= 3; i++) {
                final int radius = unit * (i + 1);
                g.drawArc(cx + unit - radius / 2, cy - radius, radius * 2, radius * 2, -40, 80);
            }
        } finally {
            g.dispose();
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    // tick ごとに呼ぶ
    public void tick() {
        allowNearestMedia();
        if (++ticks % 100 == 0) evictUnused();
        for (int i = 0; i < sessions.size(); i++) {
            sessions.get(i).tick();
        }
        for (int i = animated.size() - 1; i >= 0; i--) {
            final AnimatedModel item = animated.get(i);
            if (textures.get(item.url) != item.entry) {
                animated.remove(i);
                continue;
            }
            final long tick = (System.currentTimeMillis() - item.start) / 50;
            for (TextureAnimation animation : item.model.textureAnimations()) {
                final int[] pixels = animation.update(tick);
                if (pixels == null) continue;
                final GltfModel.Texture texture = item.model.texture(animation.textureIndex());
                platform.updateTexture(item.textures[animation.textureIndex()], texture.width, texture.height, pixels);
            }
        }
    }

    private int ticks;

    // 描かれている動画や音声のうち、近いものから MAX_PLAYING 個だけを再生させる
    private void allowNearestMedia() {
        final List<MediaSession> wanted = new ArrayList<MediaSession>();
        for (MediaSession session : sessions) {
            if (session.isWanted()) {
                wanted.add(session);
            } else {
                session.setAllowed(false);
            }
        }
        Collections.sort(wanted, new Comparator<MediaSession>() {
            @Override
            public int compare(MediaSession a, MediaSession b) {
                return Double.compare(a.recentDistance(), b.recentDistance());
            }
        });
        for (int i = 0; i < wanted.size(); i++) {
            wanted.get(i).setAllowed(i < MAX_PLAYING);
        }
    }

    // 長く描かれていないものを消す
    private void evictUnused() {
        final long now = System.currentTimeMillis();
        for (Map.Entry<String, TextureEntry> item : textures.entrySet()) {
            if (now - item.getValue().lastUsed() > UNUSED_MILLIS) evict(item.getKey(), item.getValue());
        }
        for (Iterator<SignImage> it = signs.values().iterator(); it.hasNext(); ) {
            if (now - it.next().lastUsed > UNUSED_MILLIS) it.remove();
        }
    }

    // 読み込み待ち・読み込み中の画像があるかどうか
    public boolean isBusy() {
        return loader.isBusy();
    }

    // 登録されているテクスチャをすべて削除する
    public void clearCache() {
        for (MediaSession session : sessions) {
            session.close();
        }
        sessions.clear();
        for (PdfSession pdf : pdfs) {
            pdf.close();
        }
        pdfs.clear();
        animated.clear();
        controls.close();
        for (TextureEntry entry : textures.values()) {
            for (Object frame : entry.ownedTextures()) {
                MarumaSign.releaseTexture(platform, frame);
            }
        }
        signs.clear();
        textures.clear();
    }
}
