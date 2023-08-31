package marumasa.marumasa_sign.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

public class Utils {

    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();

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
            return null;
        }
    }

    public static ByteArrayOutputStream createByteArrayOutputStream() {
        return new ByteArrayOutputStream() {
            @Override
            public synchronized byte[] toByteArray() {
                return this.buf;
            }
        };
    }

    public static void registerTexture(Identifier id, ByteArrayOutputStream stream) throws IOException {
        // テクスチャ 登録
        registerTexture(id, NativeImage.read(new ByteArrayInputStream(stream.toByteArray(), 0, stream.size())));
    }

    public static void registerTexture(Identifier id, NativeImage image) {
        // テクスチャ 登録
        textureManager.registerTexture(id, new NativeImageBackedTexture(image));
    }
}
