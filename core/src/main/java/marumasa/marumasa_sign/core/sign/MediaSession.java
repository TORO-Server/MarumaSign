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

    // 映像を貼るテクスチャと、その内容を受け取るための配列
    private Object texture;
    private int width;
    private int height;
    private int[] pixels;

    // この tick の間に描画された看板のうち、最も近いものまでの距離
    private double nearestDistance = Double.MAX_VALUE;

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
            start();
            return;
        }
        if (texture != null && player.pollFrame(pixels)) {
            platform.updateTexture(texture, width, height, pixels);
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
        // 近いほど大きく聞こえるようにする
        final double distance = nearestDistance == Double.MAX_VALUE ? 0 : nearestDistance;
        final double falloff = Math.max(0.0, 1.0 - distance / HEARING_DISTANCE);
        player.setGain((float) (falloff * falloff * MarumaSign.config().getVolume() / 100.0));
        nearestDistance = Double.MAX_VALUE;
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
