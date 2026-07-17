package marumasa.marumasa_sign.animation;

import java.util.List;

public class DecodedAnimation {
    private final int width;
    private final int height;
    private final int repetitions;
    private final List<AnimationFrame> frames;

    public DecodedAnimation(int width, int height, int repetitions, List<AnimationFrame> frames) {
        this.width = width;
        this.height = height;
        this.repetitions = repetitions;
        this.frames = frames;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public List<AnimationFrame> getFrames() {
        return frames;
    }
}
