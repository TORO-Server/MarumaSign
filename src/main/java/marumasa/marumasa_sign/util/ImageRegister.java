package marumasa.marumasa_sign.util;

import at.dhyan.open_imaging.GifDecoder;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.TextureURLProvider;
import marumasa.marumasa_sign.type.GifFrame;
import marumasa.marumasa_sign.type.TextureURL;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ImageRegister {

    public static void registerGif(InputStream stream, String stringURL, String path) throws IOException {

        // ログ出力
        MarumaSign.LOGGER.info("Start: " + stringURL);

        final NavigableMap<Integer, RenderLayer> frameMap = new TreeMap<>();
        TextureURL firstTextureURL = TextureURL.error;

        final GifDecoder.GifImage gifImage = GifDecoder.read(stream);
        final int width = gifImage.getWidth();
        final int height = gifImage.getHeight();
        final int frameCount = gifImage.getFrameCount();

        int delay = 0;

        for (int i = 0; i < frameCount; i++) {

            final BufferedImage image = gifImage.getFrame(i);

            delay += gifImage.getDelay(i);


            Identifier identifier = new Identifier(MarumaSign.MOD_ID, path + "/" + i);
            if (i == 0) {
                firstTextureURL = new TextureURL(identifier, width, height);
            }

            // テクスチャ 登録
            Utils.registerTexture(identifier, image);

            // ログ出力
            MarumaSign.LOGGER.info("Load: " + stringURL + " : " + delay + " : " + identifier);
            frameMap.put(delay, Utils.getRenderLayer(identifier));

        }

        GifPlayer.gifList.add(new GifFrame(stringURL, frameMap, gifImage.repetitions));
        List<String> signTextList = TextureURLProvider.loadedTextureURL(stringURL, firstTextureURL);
        GifPlayer.signTextMap.put(stringURL, signTextList);
    }


    static boolean registerDefault(InputStream stream, String stringURL, Identifier identifier) throws IOException {


        // どんな画像形式でも png に変換する
        final InputStream pngStream = Utils.toPNG(stream);

        // もし null (pngに変換できないファイルの場合) return
        if (pngStream == null) return false;

        // 変換した png を読み込む
        NativeImage image = NativeImage.read(pngStream);

        int width = image.getWidth();
        int height = image.getHeight();

        final TextureURL textureURL = new TextureURL(identifier, width, height);

        // テクスチャ 登録
        Utils.registerTexture(identifier, image);

        TextureURLProvider.loadedTextureURL(stringURL, textureURL);

        return true;
    }

    public static void registerError(String url) {
        // URL から 画像を読み込めなかったら

        TextureURLProvider.failureTextureURL(url);

    }
}
