package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.TextureURLProvider;
import marumasa.marumasa_sign.type.GifFrame;
import marumasa.marumasa_sign.type.TextureURL;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ImageRegister {

    public static void registerGif(InputStream stream, String stringURL, String path) throws IOException {
        // gifファイルを読み込むImageReaderを取得
        ImageReader reader = getGifReader();
        // gifファイルをオープン
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(stream);
        assert reader != null;
        reader.setInput(imageInputStream);
        // フレーム数を取得
        int frameCount = reader.getNumImages(true);
        // フレームごとに処理
        int delayTime = 0;
        final NavigableMap<Integer, RenderLayer> frameMap = new TreeMap<>();
        TextureURL firstTextureURL = TextureURL.error;
        for (int i = 0; i < frameCount; i++) {
            // フレームを取得
            BufferedImage frame = reader.read(i);
            // フレームのメタデータを取得
            IIOMetadata metadata = reader.getImageMetadata(i);
            // フレームの遅延時間を取得 (単位は100分の1)
            delayTime += getDelayTime(metadata);
            // pngファイルの名前を生成（遅延時間も含める）
            // pngファイルに書き込むImageWriterを取得
            // pngファイルをオープン

            final ByteArrayOutputStream output = Utils.createByteArrayOutputStream();

            ImageIO.write(frame, "png", output);
            Identifier identifier = new Identifier(MarumaSign.MOD_ID, path + "/" + i);
            if (i == 0) {
                int width = frame.getWidth();
                int height = frame.getHeight();
                firstTextureURL = new TextureURL(identifier, width, height);
            }

            // テクスチャ 登録
            Utils.registerTexture(identifier, output);

            // ログ出力
            MarumaSign.LOGGER.info("Load: " + stringURL + " : " + identifier);
            frameMap.put(delayTime, Utils.getRenderLayer(identifier));
        }
        // gifファイルをクローズ
        reader.dispose();
        GifPlayer.gifList.add(new GifFrame(stringURL, frameMap));
        List<String> signTextList = TextureURLProvider.loadedTextureURL(stringURL, firstTextureURL);
        GifPlayer.signTextMap.put(stringURL, signTextList);
    }

    // gifファイルからフレームの遅延時間を取得するメソッド（単位は10分の1秒）
    private static int getDelayTime(IIOMetadata metadata) {
        // メタデータからGraphicsControlExtensionノードを取得
        Node gceNode = ((Element) metadata.getAsTree("javax_imageio_gif_image_1.0")).getElementsByTagName("GraphicControlExtension").item(0);
        // ノードからdelayTime属性の値を取得（単位は10分の1秒）
        // 遅延時間を返す
        return Integer.parseInt(gceNode.getAttributes().getNamedItem("delayTime").getNodeValue());
    }

    // gifファイルを読み込むImageReaderを取得するメソッド
    private static ImageReader getGifReader() {
        // gif形式のImageReaderのイテレーターを取得
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        // イテレーターが空でなければ最初の要素を返す
        if (readers.hasNext()) {
            return readers.next();
        } else {
            // イテレーターが空ならnullを返す
            return null;
        }
    }


    static void registerDefault(InputStream stream, String stringURL, String path) throws IOException {

        final Identifier identifier = new Identifier(MarumaSign.MOD_ID, path);

        NativeImage image = NativeImage.read(stream);

        int width = image.getWidth();
        int height = image.getHeight();

        final TextureURL textureURL = new TextureURL(identifier, width, height);

        // テクスチャ 登録
        Utils.registerTexture(identifier, image);

        TextureURLProvider.loadedTextureURL(stringURL, textureURL);

        // ログ出力
        MarumaSign.LOGGER.info("Load: " + stringURL + " : " + identifier);
    }

    public static void registerError(String url) {
        // URL から 画像を読み込めなかったら

        TextureURLProvider.failureTextureURL(url);

        MarumaSign.LOGGER.warn("Failure: " + url);
    }
}
