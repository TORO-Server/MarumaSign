package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.util.NavigableMap;

public class GifRead extends Thread {
    private static final MinecraftClient client = MinecraftClient.getInstance();

    public void run() {
        while (true) {
            try {
                Thread.sleep(10);
                if (client.world == null) continue;
                load();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void load() {
        for (GifFrame gifFrame : GifProvider.gifList) {

            gifFrame.frame++;

            final NavigableMap<Integer, Identifier> frameMap = gifFrame.frameMap;
            if (!frameMap.containsKey(gifFrame.frame)) continue;

            Integer key = frameMap.higherKey(gifFrame.frame);

            Identifier identifier;
            if (key == null) {
                gifFrame.frame = 0;
                identifier = frameMap.firstEntry().getValue();
            } else {
                identifier = frameMap.get(key);
            }

            for (String signText : GifProvider.signTextMap.get(gifFrame.stringURL)) {
                CustomSignProvider.updateSignTexture(signText, identifier);
            }
        }
    }
}
