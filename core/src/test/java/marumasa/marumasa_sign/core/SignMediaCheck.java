package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.sign.SignImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Minecraft を起動せずに、看板の文字列から画像・動画・音声が読み込まれて表示されるまでを確かめる道具。
 * 引数: URL, 動かす秒数, 最後のテクスチャを保存するファイル
 */
public final class SignMediaCheck {

    public static void main(String[] args) throws Exception {
        final String url = args[0];
        final int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        final File output = args.length > 2 ? new File(args[2]) : null;

        final FakePlatform platform = new FakePlatform("en_us");
        MarumaSign.init(platform);
        MarumaSign.config().setVolume(0);
        final String text = url + "|0|0|0|1|1|0|0|0";

        // ゲームと同じように、1 秒に 20 回の tick と 60 回の描画を行う
        boolean loaded = false;
        double loadedAt = 0;
        // 表示が始まったときに貼られていた絵のうち、透けている (アルファが 0 の) ピクセルの数
        int clearAtLoad = -1;
        final long begin = System.nanoTime();
        long nextTick = 0;
        while (System.nanoTime() - begin < seconds * 1_000_000_000L) {
            final long elapsed = (System.nanoTime() - begin) / 1_000_000;
            if (elapsed >= nextTick) {
                MarumaSign.tick();
                nextTick += 50;
            }
            platform.runTasks();
            final SignImage image = MarumaSign.sign(text);
            if (image == null) throw new IllegalStateException("not a sign text");
            final Object texture = image.texture(4.0);
            if (image.isLoaded() && !loaded) {
                loaded = true;
                loadedAt = elapsed / 1000.0;
                clearAtLoad = 0;
                for (int argb : ((FakePlatform.Texture) texture).pixels) {
                    if (argb >>> 24 == 0) clearAtLoad++;
                }
            }
            Thread.sleep(16);
        }
        final SignImage image = MarumaSign.sign(text);
        System.out.println(String.format("loaded=%s after %.2fs, size=%dx%d, textures=%d, updates=%d (%.1f per second),"
                        + " transparent pixels when shown=%d",
                image.isLoaded(), loadedAt, image.textureEntry().width(), image.textureEntry().height(),
                platform.textureCount, platform.updateCount, platform.updateCount / Math.max(0.1, seconds - loadedAt), clearAtLoad));
        if (output != null && platform.pixels != null) {
            final BufferedImage picture = new BufferedImage(platform.width, platform.height, BufferedImage.TYPE_INT_ARGB);
            picture.setRGB(0, 0, platform.width, platform.height, platform.pixels, 0, platform.width);
            ImageIO.write(picture, "png", output);
        }
        MarumaSign.signs().clearCache();
        System.exit(0);
    }
}
