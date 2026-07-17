package marumasa.marumasa_sign.animation;

public class AnimationFrame {
    private final int[] pixels; // ARGB format pixels
    private final int delay;    // Delay in milliseconds

    public AnimationFrame(int[] pixels, int delay) {
        this.pixels = pixels;
        this.delay = delay;
    }

    public int[] getPixels() {
        return pixels;
    }

    public int getDelay() {
        return delay;
    }
}
