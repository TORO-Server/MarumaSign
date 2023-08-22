package marumasa.marumasa_sign.client.gif;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static marumasa.marumasa_sign.client.GetImage.URLtoID;

public class gif {
    private static final TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();

    public static final List<gifFrame> gifList = new ArrayList<>();

    public static void GifToPng(InputStream stream, String StringURL) {

        final Map<Integer, Identifier> FrameMap = new HashMap<>();
        try {


            // gifファイルを読み込むImageReaderを取得

            ImageReader reader = getGifReader();
            // gifファイルをオープン

            assert reader != null;
            reader.setInput(ImageIO.createImageInputStream(stream));
            // フレーム数を取得
            int frameCount = reader.getNumImages(true);
            // フレームごとに処理

            int delayTime = 0;

            for (int i = 0; i < frameCount; i++) {
                // フレームを取得
                BufferedImage frame = reader.read(i);
                // フレームのメタデータを取得
                IIOMetadata metadata = reader.getImageMetadata(i);
                // フレームの遅延時間を取得（単位は10分の1秒）
                delayTime += getDelayTime(metadata);
                // pngファイルの名前を生成（遅延時間も含める）
                // pngファイルに書き込むImageWriterを取得
                // pngファイルをオープン

                final ByteArrayOutputStream output = new ByteArrayOutputStream() {
                    @Override
                    public synchronized byte[] toByteArray() {
                        return this.buf;
                    }
                };
                ImageIO.write(frame, "png", output);

                NativeImage image = NativeImage.read(new ByteArrayInputStream(output.toByteArray(), 0, output.size()));


                AbstractTexture texture = new NativeImageBackedTexture(image);

                Identifier identifier = new Identifier(MarumaSign.MOD_ID, URLtoID(StringURL) + i);


                // テクスチャ 登録
                textureManager.registerTexture(identifier, texture);

                FrameMap.put(delayTime, identifier);

            }
            // gifファイルをクローズ
            reader.dispose();

            gifList.add(new gifFrame(FrameMap, 0));
        } catch (IOException e) {
            // 例外が発生した場合はエラーメッセージを表示
            MarumaSign.LOGGER.warn(String.valueOf(e));
        }

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

    public record gifFrame(Map<Integer, Identifier> FrameMap, int Frame) {
    }
}
