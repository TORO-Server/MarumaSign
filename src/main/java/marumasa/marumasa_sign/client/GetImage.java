package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class GetImage extends Thread {

    // 画像 読み込み済みリスト
    public static final Map<String, TextureURL> loadedURL = new HashMap<>();

    // テクスチャマネージャー
    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
    private final String StringURL;
    private final Identifier identifier;


    public GetImage(String StringURL, Identifier identifier) {
        this.StringURL = StringURL;
        this.identifier = identifier;
    }

    public void run() {
        try {
            NativeImage image = NativeImage.read(new URL(this.StringURL).openStream());

            int width = image.getWidth();
            int height = image.getHeight();

            AbstractTexture texture = new NativeImageBackedTexture(image);

            // テクスチャ 登録
            textureManager.registerTexture(identifier, texture);

            // 読み込み済みリストに 追加
            loadedURL.put(this.StringURL, new TextureURL(identifier, width, height));

            // ログ出力
            MarumaSign.LOGGER.info("Load: " + this.StringURL + " : " + identifier);
        } catch (IOException e) {
            // URL から 画像を読み込めなかったら
            MarumaSign.LOGGER.warn("Failure: " + this.StringURL + " : " + identifier);
        }
    }
}
