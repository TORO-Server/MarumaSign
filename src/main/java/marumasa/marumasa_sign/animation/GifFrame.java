package marumasa.marumasa_sign.animation;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, RenderType> frameMap;
    public final int repetitions;
    public final int totalDuration;
    public final List<Identifier> identifiers;

    public GifFrame(String stringURL, NavigableMap<Integer, RenderType> frameMap, int repetitions, List<Identifier> identifiers) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
        this.repetitions = repetitions;
        this.totalDuration = frameMap.isEmpty() ? 1 : frameMap.lastKey();
        this.identifiers = identifiers;
        MarumaSign.LOGGER.info(String.valueOf(repetitions));
    }
}
