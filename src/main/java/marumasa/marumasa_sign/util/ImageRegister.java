package marumasa.marumasa_sign.util;

import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.sign.TextureURLProvider;
import marumasa.marumasa_sign.type.GifFrame;
import marumasa.marumasa_sign.type.TextureURL;
import marumasa.marumasa_sign.type.DecodedAnimation;
import marumasa.marumasa_sign.type.AnimationFrame;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ImageRegister {

    public static void registerAnimation(byte[] content, String stringURL, String path) throws IOException {
        DecodedAnimation animation;
        if (Utils.isGif(content)) {
            animation = GifDecoder.read(content);
        } else if (Utils.isApng(content)) {
            animation = ApngDecoder.read(content);
        } else if (Utils.isWebp(content)) {
            animation = WebpDecoder.read(content);
        } else {
            throw new IOException("Unsupported animation format");
        }

        final int width = animation.getWidth();
        final int height = animation.getHeight();
        final List<AnimationFrame> frames = animation.getFrames();
        final int frameCount = frames.size();

        if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || frameCount > 512) {
            throw new IOException("Animation validation failed: dimensions or frame count exceed safety limits (max 2048x2048, 512 frames).");
        }

        final int repetitions = animation.getRepetitions();

        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            try {
                final NavigableMap<Integer, RenderType> frameMap = new TreeMap<>();
                TextureURL firstTextureURL = TextureURL.error;
                int currentDelay = 0;
                for (int i = 0; i < frameCount; i++) {
                    Identifier identifier = Identifier.fromNamespaceAndPath(MarumaSign.MOD_ID, path + "/" + i);
                    if (i == 0) {
                        firstTextureURL = new TextureURL(identifier, width, height);
                    }
                    AnimationFrame frame = frames.get(i);
                    Utils.registerTexture(identifier, width, height, frame.getPixels());
                    currentDelay += frame.getDelay();
                    frameMap.put(currentDelay, Utils.getRenderLayer(identifier));
                }
                GifPlayer.gifMap.put(stringURL, new GifFrame(stringURL, frameMap, repetitions));
                TextureURLProvider.loadedTextureURL(stringURL, firstTextureURL);
            } catch (IOException e) {
                MarumaSign.LOGGER.error("Failed to register animation texture", e);
            }
        });
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

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            image.close();
            pngStream.close();
            return false;
        }

        final TextureURL textureURL = new TextureURL(identifier, width, height);

        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            Utils.registerTexture(identifier, image);
            TextureURLProvider.loadedTextureURL(stringURL, textureURL);
        });

        return true;
    }

    public static void registerError(String url) {
        // URL から 画像を読み込めなかったら

        TextureURLProvider.failureTextureURL(url);

    }
}
