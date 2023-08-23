package marumasa.marumasa_sign.util;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.TextureURL;
import marumasa.marumasa_sign.client.sign.TextureURLProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Queue;

public class GetImage extends Thread {

    // テクスチャマネージャー
    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();

    private static final Queue<String> queue = new ArrayDeque<>();

    public static void open(
            // 画像のURL
            String stringURL
    ) {
        queue.add(stringURL);
        if (queue.size() == 1) new GetImage().start();
    }

    public GetImage() {
        this.setName("GetImage thread");
    }

    public void run() {
        while (queue.size() != 0) {
            String stringURL = queue.remove();
            getURL(stringURL);
        }
    }

    public static byte[] getURLContent(String stringURL) throws IOException {

        InputStream input = new URL(stringURL).openStream();

        // ByteArrayOutputStream 書き込み
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) != -1) {
            output.write(buffer, 0, len);
        }
        // InputStreamとByteArrayOutputStreamを閉じる
        input.close();
        output.close();

        // byte配列に変換
        return output.toByteArray();
    }

    public static void getURL(String stringURL) {
        String path = URLtoPath(stringURL);


        try {
            final byte[] content = getURLContent(stringURL);

            InputStream stream = new ByteArrayInputStream(content);

            if (GifProvider.isGif(content)) {
                GifProvider.registerGif(stream, stringURL, path);
            } else {
                registerDefault(stream, stringURL, path);
            }

        } catch (IOException e) {
            // URL から 画像を読み込めなかったら

            TextureURLProvider.failureTextureURL(stringURL);

            MarumaSign.LOGGER.warn("Failure: " + stringURL);
        }
    }

    // URLを Identifier で使える ID に変換
    public static String URLtoPath(String stringURL) {
        // base32 に変換
        String base32 = BaseEncoding.base32().encode(stringURL.getBytes());
        // Identifier は 大文字使えないので すべて小文字にする
        base32 = base32.toLowerCase();
        // Identifier は イコール という文字が使えないので アンダーバー に置き換える
        base32 = base32.replace('=', '_');
        return base32;
    }


    private static void registerDefault(InputStream stream, String stringURL, String path) throws IOException {

        final Identifier identifier = new Identifier(MarumaSign.MOD_ID, path);

        NativeImage image = NativeImage.read(stream);

        AbstractTexture texture = new NativeImageBackedTexture(image);

        int width = image.getWidth();
        int height = image.getHeight();

        final TextureURL textureURL = new TextureURL(identifier, width, height);

        // テクスチャ 登録
        textureManager.registerTexture(identifier, texture);

        TextureURLProvider.loadedTextureURL(stringURL, textureURL);

        // ログ出力
        MarumaSign.LOGGER.info("Load: " + stringURL + " : " + identifier);
    }
}
