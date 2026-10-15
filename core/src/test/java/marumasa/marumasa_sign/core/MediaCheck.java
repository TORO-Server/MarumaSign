package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.media.Ffmpeg;
import marumasa.marumasa_sign.core.media.MediaPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Minecraft を起動せずに、動画や音声の再生を確かめるための道具。
 * 引数: URL, 再生する秒数, フレームを保存するフォルダ, 音量 (0 - 1)
 */
public final class MediaCheck {

    public static void main(String[] args) throws Exception {
        final String url = args[0];
        final int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        final File output = new File(args.length > 2 ? args[2] : ".");
        final float gain = args.length > 3 ? Float.parseFloat(args[3]) : 0f;
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("ffmpeg available=" + Ffmpeg.isAvailable());

        final int[] size = new int[2];
        final String[] failure = new String[1];
        final Object ready = new Object();
        final MediaPlayer player = new MediaPlayer(url, new MediaPlayer.Listener() {
            @Override
            public void onReady(int width, int height) {
                synchronized (ready) {
                    size[0] = width;
                    size[1] = height;
                    ready.notifyAll();
                }
                System.out.println("ready: " + width + "x" + height);
            }

            @Override
            public void onFailed(String reason) {
                synchronized (ready) {
                    failure[0] = reason;
                    ready.notifyAll();
                }
                System.out.println("failed: " + reason);
            }
        });
        player.setGain(gain);
        final long start = System.nanoTime();
        player.start();
        synchronized (ready) {
            while (size[0] == 0 && failure[0] == null && System.nanoTime() - start < 30_000_000_000L) ready.wait(100);
        }
        if (failure[0] != null) System.exit(1);

        // 描画のスレッドの代わりに、60 分の 1 秒ごとにフレームを取り出す
        final boolean video = size[0] > 0;
        final int[] pixels = new int[Math.max(1, size[0] * size[1])];
        int frames = 0;
        int saved = 0;
        final long begin = System.nanoTime();
        while (System.nanoTime() - begin < seconds * 1_000_000_000L) {
            if (video && player.pollFrame(pixels)) {
                frames++;
                final double elapsed = (System.nanoTime() - begin) / 1e9;
                if (saved < 3 && elapsed >= saved * 1.5) {
                    final BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, size[0], size[1], pixels, 0, size[0]);
                    final File file = new File(output, "frame-" + saved + ".png");
                    ImageIO.write(image, "png", file);
                    System.out.println(String.format("saved %s at %.2fs", file.getName(), elapsed));
                    saved++;
                }
            }
            Thread.sleep(16);
        }
        final double elapsed = (System.nanoTime() - begin) / 1e9;
        System.out.println(String.format("frames=%d in %.2fs (%.1f fps)", frames, elapsed, frames / elapsed));
        player.stop();
        System.out.println("stopped");
        System.exit(0);
    }
}
