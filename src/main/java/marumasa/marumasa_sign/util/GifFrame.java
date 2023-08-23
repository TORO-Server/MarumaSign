package marumasa.marumasa_sign.util;

import net.minecraft.util.Identifier;

import java.util.NavigableMap;

public class GifFrame {
    public final String stringURL;
    public final NavigableMap<Integer, Identifier> frameMap;

    public int frame = 0;

    public GifFrame(String stringURL, NavigableMap<Integer, Identifier> frameMap) {
        this.stringURL = stringURL;
        this.frameMap = frameMap;
    }
}
