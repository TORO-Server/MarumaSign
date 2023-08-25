package marumasa.marumasa_sign.util;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

public class Utils {
    public static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isGif(byte[] bytes) throws IOException {
        byte[] header = Arrays.copyOf(bytes, 6);
        String s = new String(header);
        return s.equals("GIF89a");
    }

    public static RenderLayer getRenderLayer(Identifier identifier) {
        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        return RenderLayer.getEntityTranslucent(identifier);
    }

    public static String encodeURL(String url) {
        try {
            URI uri = new URI(url);
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
