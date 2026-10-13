package marumasa.marumasa_sign.core.sign;

/**
 * URL ごとの画像テクスチャ。読み込み中・失敗の場合は代わりの画像を指す。
 * 動画や音声の場合は、再生中のフレームを映すテクスチャを指す。
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
    // 状態が変わるたびに増える。SignImage が頂点を作り直すために使う
    private volatile int revision;
    // 動画や音声の再生。画像なら null
    private MediaSession media;
    // frames のテクスチャをこのエントリが所有しているかどうか (共用の画像を指しているだけなら false)
    private boolean ownsTextures;

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
        this.state = state;
        this.revision++;
    }

    void setMedia(MediaSession media) {
        this.media = media;
    }

    MediaSession media() {
        return media;
    }

    public int state() {
        return state;
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
