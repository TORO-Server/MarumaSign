package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.type.GifFrame;
import net.minecraft.client.render.RenderLayer;

import java.util.*;


public class GifPlayer {
    public static final List<GifFrame> gifList = new ArrayList<>();
    public static final Map<String, List<String>> signTextMap = new HashMap<>();

    public static void load() {
        for (GifFrame gifFrame : gifList) {

            gifFrame.frame++;

            final NavigableMap<Integer, RenderLayer> frameMap = gifFrame.frameMap;
            if (!frameMap.containsKey(gifFrame.frame)) continue;

            Integer key = frameMap.higherKey(gifFrame.frame);

            RenderLayer renderLayer;
            if (key == null) {
                gifFrame.frame = 0;
                if (gifFrame.repetitions != 0) {
                    gifFrame.repeat_count++;
                    if (gifFrame.repeat_count >= gifFrame.repetitions) {
                        MarumaSign.LOGGER.info("test");
                        gifList.remove(gifFrame);
                        continue;
                    }
                }
                renderLayer = frameMap.firstEntry().getValue();
            } else {
                renderLayer = frameMap.get(key);
            }

            for (String signText : signTextMap.get(gifFrame.stringURL)) {
                CustomSignProvider.updateSignTexture(signText, renderLayer);
            }
        }
    }
}
