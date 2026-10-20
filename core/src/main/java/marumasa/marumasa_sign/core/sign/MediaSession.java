package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.media.MediaPlayer;

/**
 * 看板に表示する動画や音声 1 つ分の再生。
 * 誰も見ていない間は再生を止め、また見えるようになったら最初から再生し直す。
 * メインスレッドからのみ使う。
 */
final class MediaSession {

    // この時間 (ミリ秒) 描画されなければ再生を止める
    private static final long IDLE_MILLIS = 3000;
    // 音が聞こえる距離の上限 (ブロック)
    private static final double HEARING_DISTANCE = 32.0;

    interface Listener {
        // 映像の大きさが分かったとき。映像がなければ width, height は 0
        void onReady(int width, int height);

        void onFailed();
    }

    private final Platform platform;
    private final String url;
    private final Listener listener;

    private MediaPlayer player;
    private boolean ready;
    private boolean failed;
    private long lastUsed = System.currentTimeMillis();
    // 再生してよいか。同時に再生する数の上限の中に入っていれば true (SignManager が決める)
    private boolean allowed;

    // 映像を貼るテクスチャと、その内容を受け取るための配列
    private Object texture;
    private int width;
    private int height;
    private int[] pixels;

    // この tick の間に描画された看板のうち、最も近いものまでの距離
    private double nearestDistance = Double.MAX_VALUE;
    // 最後に分かった、最も近い看板までの距離。
    // 看板が視界の外にあると描画されず距離が分からないので、その間はこの値で音量を決める
    private double lastDistance = Double.MAX_VALUE;
    private float currentGain;
    private float currentFalloff;

    MediaSession(Platform platform, String url, Listener listener) {
        this.platform = platform;
        this.url = url;
        this.listener = listener;
    }

    void start() {
        if (player != null || failed) return;
        final MediaPlayer started = new MediaPlayer(url, new MediaPlayer.Listener() {
            @Override
            public void onReady(final int videoWidth, final int videoHeight) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        ready(videoWidth, videoHeight);
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                platform.execute(new Runnable() {
                    @Override
                    public void run() {
                        failed = true;
                        if (!ready) listener.onFailed();
                    }
                });
            }
        });
        player = started;
        started.setGain(0f);
        started.start();
    }

    private void ready(int videoWidth, int videoHeight) {
        // 再開したときは、前と同じ大きさならテクスチャをそのまま使う
        if (ready && videoWidth == width && videoHeight == height) return;
        width = videoWidth;
        height = videoHeight;
        if (videoWidth > 0) {
            MarumaSign.releaseTexture(platform, texture);
            pixels = new int[videoWidth * videoHeight];
            texture = platform.createTexture(videoWidth, videoHeight, pixels);
        }
        ready = true;
        listener.onReady(videoWidth, videoHeight);
    }

    // 映像を貼るテクスチャ。映像がなければ null
    Object texture() {
        return texture;
    }

    /**
     * 描画のたびに呼ぶ。止まっていれば再生を再開し、新しいフレームがあればテクスチャに反映する。
     */
    void use(double distance) {
        lastUsed = System.currentTimeMillis();
        if (distance < nearestDistance) nearestDistance = distance;
        if (player == null) {
            if (allowed) start();
            return;
        }
        if (texture != null && player.pollFrame(pixels)) {
            platform.updateTexture(texture, width, height, pixels);
        }
    }

    // 描かれていて、再生したいかどうか
    boolean isWanted() {
        return !failed && System.currentTimeMillis() - lastUsed <= IDLE_MILLIS;
    }

    // 最も近い看板までの、最近の距離 (ブロック)
    double recentDistance() {
        return nearestDistance != Double.MAX_VALUE ? nearestDistance : lastDistance;
    }

    // 再生してよいかを決める。止められたら、再生を止めて最後のフレームを残す (また許されたら最初から再生する)
    void setAllowed(boolean value) {
        allowed = value;
        if (!value && player != null) {
            player.stop();
            player = null;
        }
    }

    // tick ごとに呼ぶ
    void tick() {
        if (player == null) return;
        if (System.currentTimeMillis() - lastUsed > IDLE_MILLIS) {
            player.stop();
            player = null;
            return;
        }
        // 近いほど大きく聞こえるようにする。
        // 看板に背を向けると描画されなくなるが、そのときに距離 0 (最大音量) と見なさないよう、前の距離を使う
        if (nearestDistance != Double.MAX_VALUE) lastDistance = nearestDistance;
        currentGain = gain(lastDistance, MarumaSign.config().getVolume());
        currentFalloff = gain(lastDistance, 100);
        player.setGain(currentGain);
        nearestDistance = Double.MAX_VALUE;
    }

    // 今の音の大きさ (0 - 1)。再生していなければ -1
    float currentGain() {
        return player == null ? -1f : currentGain;
    }

    // 設定の音量を 100 としたときの、今の音の大きさ (0 - 1)。距離による変化だけを表す。再生していなければ -1
    // (動作確認では音量を 0 にして音を出さないので、距離による変化はこちらで確かめる)
    float currentFalloff() {
        return player == null ? -1f : currentFalloff;
    }

    /**
     * 看板までの距離と設定の音量から、再生する音の大きさ (0 - 1) を求める。
     *
     * @param distance 看板までの距離 (ブロック)。分からなければ Double.MAX_VALUE で、音は出さない
     * @param volume   設定の音量 (0 - 100)
     */
    static float gain(double distance, int volume) {
        final double falloff = Math.max(0.0, 1.0 - distance / HEARING_DISTANCE);
        return (float) (falloff * falloff * volume / 100.0);
    }

    void close() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (texture != null) {
            MarumaSign.releaseTexture(platform, texture);
            texture = null;
        }
    }
}
