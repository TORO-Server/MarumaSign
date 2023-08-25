package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;

import java.util.NavigableMap;

public class GifRead extends Thread {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    public void run() {
        while (true) {
            Utils.sleep(10);
            if (client.world == null) continue;
            load();
        }
    }

    private static void load() {
        for (GifFrame gifFrame : GifProvider.gifList) {

            gifFrame.frame++;

            final NavigableMap<Integer, RenderLayer> frameMap = gifFrame.frameMap;
            if (!frameMap.containsKey(gifFrame.frame)) continue;

            Integer key = frameMap.higherKey(gifFrame.frame);

            RenderLayer renderLayer;
            if (key == null) {
                gifFrame.frame = 0;
                renderLayer = frameMap.firstEntry().getValue();
            } else {
                renderLayer = frameMap.get(key);
            }

            for (String signText : GifProvider.signTextMap.get(gifFrame.stringURL)) {
                CustomSignProvider.updateSignTexture(signText, renderLayer);
            }
        }
    }
}
