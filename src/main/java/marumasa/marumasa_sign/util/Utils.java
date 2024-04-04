package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

public class Utils {

    private static final MinecraftClient client = MinecraftClient.getInstance();

    public static boolean isGif(byte[] bytes) {
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

    public static void registerTexture(Identifier id, BufferedImage image) throws IOException {

        // BufferedImageをbyte配列に変換する
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", stream);
        byte[] bytes = stream.toByteArray();

        // テクスチャ 登録
        registerTexture(id, NativeImage.read(new ByteArrayInputStream(bytes)));
    }

    public static void registerTexture(Identifier id, NativeImage image) {
        // テクスチャ 登録
        client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
    }

    public static void destroyTexture(Identifier id) {
        // テクスチャ 削除
        client.getTextureManager().destroyTexture(id);
    }

    // キーバインド 作成
    public static KeyBinding createKeyBinding(String name, int code) {
        return new KeyBinding(
                // ID作成
                "key." + MarumaSign.MOD_ID + "." + name,

                // どのキーか設定
                InputUtil.Type.KEYSYM, code,

                // カテゴリ設定
                "key.categories." + MarumaSign.MOD_ID
        );
    }

    public static InputStream toPNG(InputStream inputStream) {
        try {
            // InputStream を BufferedImage に読み込む
            final BufferedImage bufferedImage = ImageIO.read(inputStream);

            // BufferedImage を PNG 形式の OutputStream に書き込む
            final ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", pngOutputStream);
            // PNG 形式の OutputStream から ByteArrayInputStream を作成して return
            return new ByteArrayInputStream(pngOutputStream.toByteArray());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
