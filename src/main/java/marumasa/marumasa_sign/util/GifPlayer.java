package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.type.GifFrame;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.*;


public class GifPlayer {
    public static final List<GifFrame> gifList = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final Map<String, List<String>> signTextMap = new java.util.concurrent.ConcurrentHashMap<>();
 
    public static void load() {
        for (GifFrame gifFrame : gifList) {
 
            gifFrame.frame++;
 
            final NavigableMap<Integer, RenderType> frameMap = gifFrame.frameMap;
            if (!frameMap.containsKey(gifFrame.frame)) continue;
 
            Integer key = frameMap.higherKey(gifFrame.frame);
 
            RenderType renderLayer;
            if (key == null) {
                gifFrame.frame = 0;
                if (gifFrame.repetitions != 0) {
                    gifFrame.repeat_count++;
                    if (gifFrame.repeat_count >= gifFrame.repetitions) {
                        gifList.remove(gifFrame);
                        continue;
                    }
                }
                renderLayer = frameMap.firstEntry().getValue();
            } else {
                renderLayer = frameMap.get(key);
            }
 
            List<String> signTexts = signTextMap.get(gifFrame.stringURL);
            if (signTexts != null) {
                for (String signText : signTexts) {
                    CustomSignProvider.updateSignTexture(signText, renderLayer);
                }
            }
        }
    }
}
