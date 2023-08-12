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
import java.util.ArrayList;
import java.util.List;

public class GetImage extends Thread {

    // テクスチャマネージャー
    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
    private final String StringURL;
    private final Identifier identifier;

    public GetImage(Identifier identifier, String StringURL) {
        this.identifier = identifier;
        this.StringURL = StringURL;
    }

    public void run() {

        try {
            AbstractTexture texture = new NativeImageBackedTexture(
                    NativeImage.read(new URL(this.StringURL).openStream())
            );

            // テクスチャ 登録
            textureManager.registerTexture(identifier, texture);

            // ログ出力
            MarumaSign.LOGGER.info("Load: " + this.StringURL + " : " + identifier);
        } catch (IOException e) {
            // URL から 画像を読み込めなかったら
            MarumaSign.LOGGER.warn("Failure: " + this.StringURL + " : " + identifier);
        }
    }
}
