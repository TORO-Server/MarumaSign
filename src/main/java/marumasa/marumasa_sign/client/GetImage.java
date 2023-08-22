package marumasa.marumasa_sign.client;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.net.URL;

public class GetImage extends Thread {

    // テクスチャマネージャー
    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
    private final String StringURL;
    private final String[] parameters;
    private final String signText;


    public GetImage(
            // 画像のURL
            String StringURL,
            String[] parameters,
            String signText
    ) {
        this.StringURL = StringURL;
        this.parameters = parameters;
        this.signText = signText;
    }

    public void run() {

        final Identifier identifier = new Identifier(MarumaSign.MOD_ID, URLtoID(StringURL));

        try {
            NativeImage image = NativeImage.read(new URL(this.StringURL).openStream());

            int width = image.getWidth();
            int height = image.getHeight();

            AbstractTexture texture = new NativeImageBackedTexture(image);

            // テクスチャ 登録
            textureManager.registerTexture(identifier, texture);

            final CustomSign.TextureURL textureURL = new CustomSign.TextureURL(identifier, width, height);

            // 画像 読み込み済みリスト 更新
            CustomSign.loadedTextureURL.put(StringURL, textureURL);

            // 看板 読み込み済みリスト 更新
            CustomSign.loadedCustomSign.put(signText, CustomSign.load(textureURL, parameters));

            // ログ出力
            MarumaSign.LOGGER.info("Load: " + this.StringURL + " : " + identifier);
        } catch (IOException e) {
            // URL から 画像を読み込めなかったら

            // 看板 読み込み済みリスト 更新
            CustomSign.loadedCustomSign.put(signText, CustomSign.load(MarumaSignClient.Error, parameters));

            MarumaSign.LOGGER.warn("Failure: " + this.StringURL + " : " + identifier);
        }
    }

    // URLを Identifier で使える ID に変換
    public static String URLtoID(String StringURL) {
        // base32 に変換
        String base32 = BaseEncoding.base32().encode(StringURL.getBytes());
        // Identifier は 大文字使えないので すべて小文字にする
        base32 = base32.toLowerCase();
        // Identifier は イコール という文字が使えないので アンダーバー に置き換える
        base32 = base32.replace('=', '_');
        return base32;
    }
}
