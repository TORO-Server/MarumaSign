package marumasa.marumasa_sign.core.image;

/**
 * デコード済みの画像。アニメーションでなければフレームは 1 つ。
 */
public final class DecodedImage {

    public final int width;
    public final int height;
    // フレームごとの ARGB ピクセル
    public final int[][] frames;
    // フレームごとの表示時間 (ミリ秒)
    public final int[] delays;
    // 繰り返し回数 (0 は無限)
    public final int repetitions;

    public DecodedImage(int width, int height, int[][] frames, int[] delays, int repetitions) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.delays = delays;
        this.repetitions = repetitions;
    }

    public boolean isAnimated() {
        return frames.length > 1;
    }
}
