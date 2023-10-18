package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.render.RenderLayer;

import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, RenderLayer> frameMap;
    public final int repetitions;

    public int frame = 0;
    public int repeat_count = 0;

    public GifFrame(String stringURL, NavigableMap<Integer, RenderLayer> frameMap, int repetitions) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
        this.repetitions = repetitions;
        MarumaSign.LOGGER.info(String.valueOf(repetitions));
    }
}
