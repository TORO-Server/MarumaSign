package marumasa.marumasa_sign.animation;

import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.*;

public class GifPlayer {
    public static final Map<String, GifFrame> gifMap = new java.util.concurrent.ConcurrentHashMap<>();

    public static RenderType getRenderType(String stringURL, RenderType defaultRenderType, long startTime) {
        GifFrame gifFrame = gifMap.get(stringURL);
        if (gifFrame == null) return defaultRenderType;

        NavigableMap<Integer, RenderType> frameMap = gifFrame.frameMap;
        if (frameMap.isEmpty()) return defaultRenderType;

        long elapsed = System.currentTimeMillis() - startTime;
        int totalDuration = gifFrame.totalDuration;
        int repetitions = gifFrame.repetitions;

        if (repetitions > 0) {
            long maxDuration = (long) totalDuration * repetitions;
            if (elapsed >= maxDuration) {
                return frameMap.lastEntry().getValue();
            }
        }

        int loopElapsed = (int) (elapsed % totalDuration);
        Map.Entry<Integer, RenderType> entry = frameMap.higherEntry(loopElapsed);
        if (entry == null) {
            return frameMap.firstEntry().getValue();
        }
        return entry.getValue();
    }
}
