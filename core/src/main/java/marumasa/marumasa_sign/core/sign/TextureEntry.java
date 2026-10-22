package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.splat.SplatScene;

/**
 * URL ごとの画像テクスチャ。読み込み中・失敗の場合は代わりの画像を指す。
 * 動画や音声の場合は、再生中のフレームを映すテクスチャを指す。
 * 3D モデルやガウシアンスプラッティングの場合は、データとテクスチャを持つ。
 */
public final class TextureEntry {

    public static final int LOADING = 0;
    public static final int LOADED = 1;
    public static final int ERROR = 2;

    private volatile int state;
    private int width;
    private int height;
    // フレームごとのテクスチャ (アニメーションでなければ 1 つ)
    private Object[] frames;
    // 各フレームが終わる時刻 (ミリ秒, 累積)
    private int[] frameEnd;
    private int repetitions;
    private long loadedAt;
    // 看板が初めて描かれようとした時刻と、そこから表示できるようになるまでの時間 (ミリ秒、まだなら -1)
    private final long requestedAt = System.currentTimeMillis();
    private volatile long loadMillis = -1;
    // 状態が変わるたびに増える。SignImage が頂点を作り直すために使う
    private volatile int revision;
    // 動画や音声の再生。画像なら null
    private MediaSession media;
    // PDF の表示。PDF でなければ null
    private PdfSession pdf;
    // frames のテクスチャをこのエントリが所有しているかどうか (共用の画像を指しているだけなら false)
    private boolean ownsTextures;
    // 3D モデル。画像なら null
    private GltfScene model;
    // ガウシアンスプラッティング。画像や 3D モデルなら null
    private SplatScene splat;
    // 最後に看板として引かれた時刻 (System.currentTimeMillis)。使われていないものを消すのに使う
    private volatile long lastUsed = System.currentTimeMillis();
    // 使っているメモリのおおよその量 (バイト)
    private long bytes;

    TextureEntry(int state, int width, int height, Object texture) {
        set(state, width, height, new Object[]{texture}, new int[]{1}, 0, false);
    }

    void set(int state, int width, int height, Object[] frames, int[] frameEnd, int repetitions, boolean ownsTextures) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.frameEnd = frameEnd;
        this.repetitions = repetitions;
        this.ownsTextures = ownsTextures;
        this.loadedAt = System.currentTimeMillis();
        if (state == LOADED && loadMillis < 0) loadMillis = loadedAt - requestedAt;
        this.state = state;
        this.revision++;
    }

    // 3D モデルを表示する状態にする。textures はモデルのテクスチャと同じ並び
    void setModel(GltfScene model, Object[] textures) {
        this.model = model;
        this.splat = null;
        set(LOADED, 1, 1, textures, new int[]{1}, 0, true);
    }

    // 3D モデル。画像なら null
    GltfScene model() {
        return model;
    }

    // ガウシアンスプラッティングを表示する状態にする。texture はガウス分布のテクスチャ
    void setSplat(SplatScene splat, Object texture) {
        this.splat = splat;
        this.model = null;
        set(LOADED, 1, 1, new Object[]{texture}, new int[]{1}, 0, true);
    }

    // ガウシアンスプラッティング。画像や 3D モデルなら null
    SplatScene splat() {
        return splat;
    }

    Object modelTexture(int index) {
        return frames[index];
    }

    // 読み込んでからの時間 (秒)。アニメーションの時刻に使う
    float modelTime() {
        return (System.currentTimeMillis() - loadedAt) / 1000f;
    }

    void setMedia(MediaSession media) {
        this.media = media;
    }

    MediaSession media() {
        return media;
    }

    // 動画や音声の今の音の大きさ (0 - 1)。動画や音声でないか、再生していなければ -1
    public float mediaGain() {
        return media == null ? -1f : media.currentGain();
    }

    // 動画や音声の、設定の音量を 100 としたときの今の音の大きさ (0 - 1)。動画や音声でないか、再生していなければ -1
    public float mediaFalloff() {
        return media == null ? -1f : media.currentFalloff();
    }

    void setPdf(PdfSession pdf) {
        this.pdf = pdf;
    }

    // PDF の表示。PDF でなければ null
    PdfSession pdf() {
        return pdf;
    }

    // PDF なら true
    public boolean isPdf() {
        return pdf != null;
    }

    public int state() {
        return state;
    }

    // 看板が初めて描かれようとしてから、表示できるようになるまでにかかった時間 (ミリ秒)。まだ表示できていなければ -1
    public long loadMillis() {
        return loadMillis;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    int revision() {
        return revision;
    }

    void touch() {
        lastUsed = System.currentTimeMillis();
    }

    long lastUsed() {
        return lastUsed;
    }

    long bytes() {
        return bytes;
    }

    void setBytes(long bytes) {
        this.bytes = bytes;
    }

    // このエントリが所有していて、破棄するときに解放するべきテクスチャ
    Object[] ownedTextures() {
        return ownsTextures ? frames : new Object[0];
    }

    /**
     * 現在表示するべきテクスチャ。描画のたびに呼ぶ。
     *
     * @param distance カメラから看板までの距離 (ブロック)。音量の計算に使う
     */
    public Object current(double distance) {
        if (media != null) media.use(distance);

        final Object[] frames = this.frames;
        if (frames.length == 1) return frames[0];

        final int[] frameEnd = this.frameEnd;
        final int total = frameEnd[frameEnd.length - 1];
        if (total <= 0) return frames[0];

        final long elapsed = System.currentTimeMillis() - loadedAt;
        if (repetitions > 0 && elapsed >= (long) total * repetitions) {
            return frames[frames.length - 1];
        }

        final int time = (int) (elapsed % total);
        // 二分探索で time より後に終わる最初のフレームを探す
        int low = 0, high = frameEnd.length - 1;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (frameEnd[mid] > time) high = mid;
            else low = mid + 1;
        }
        return frames[low];
    }
}
