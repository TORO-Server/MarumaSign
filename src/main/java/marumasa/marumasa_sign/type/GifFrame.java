package marumasa.marumasa_sign.type;

import net.minecraft.client.render.RenderLayer;

import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, RenderLayer> frameMap;

    public int frame = 0;

    public GifFrame(String stringURL, NavigableMap<Integer, RenderLayer> frameMap) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
    }
}
