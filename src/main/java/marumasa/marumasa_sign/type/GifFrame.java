package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, RenderType> frameMap;
    public final int repetitions;
    public final int totalDuration;

    public GifFrame(String stringURL, NavigableMap<Integer, RenderType> frameMap, int repetitions) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
        this.repetitions = repetitions;
        this.totalDuration = frameMap.isEmpty() ? 1 : frameMap.lastKey();
        MarumaSign.LOGGER.info(String.valueOf(repetitions));
    }
}
