package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, RenderType> frameMap;
    public final int repetitions;

    public int frame = 0;
    public int repeat_count = 0;

    public GifFrame(String stringURL, NavigableMap<Integer, RenderType> frameMap, int repetitions) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
        this.repetitions = repetitions;
        MarumaSign.LOGGER.info(String.valueOf(repetitions));
    }
}
