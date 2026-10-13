package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.gltf.GltfPreview;
import marumasa.marumasa_sign.core.sign.SignImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Minecraft を起動せずに、看板の文字列から画像や 3D モデルが読み込まれ、看板の位置に置かれるまでを確かめる道具。
 * 看板のあるブロックを、看板の正面の斜め上から見た絵を PNG に書き出す。
 * 引数: 看板の文字列 (URL だけでもよい), 看板の向き (度), 壁掛けなら wall, 出力ファイル, [アニメーションを進める秒数]
 */
public final class SignModelCheck {

    private static final int SIZE = 512;
    // 1 ブロックの大きさ (画面の半分を 1 としたとき)
    private static final float ZOOM = 0.45f;

    public static void main(String[] args) throws Exception {
        final String text = args[0].contains("|") ? args[0] : args[0] + "|0|0|0|1|1|0|0|0";
        final float yaw = Float.parseFloat(args[1]);
        final boolean wall = "wall".equals(args[2]);
        final File output = new File(args[3]);
        final double seconds = args.length > 4 ? Double.parseDouble(args[4]) : 0;

        final FakePlatform platform = new FakePlatform("en_us");
        MarumaSign.init(platform);
        MarumaSign.config().setVolume(0);

        SignImage image = null;
        final long begin = System.nanoTime();
        long loadedAt = -1;
        while (true) {
            final long elapsed = (System.nanoTime() - begin) / 1_000_000;
            MarumaSign.tick();
            platform.runTasks();
            image = MarumaSign.sign(text);
            if (image == null) throw new IllegalStateException("not a sign text");
            image.batches(yaw, wall, 4.0);
            if (image.isLoaded() && loadedAt < 0) loadedAt = elapsed;
            if (loadedAt >= 0 && elapsed - loadedAt >= seconds * 1000) break;
            if (image.textureEntry().state() == marumasa.marumasa_sign.core.sign.TextureEntry.ERROR) break;
            if (elapsed > 60000) break;
            Thread.sleep(16);
        }

        final long start = System.nanoTime();
        final SignImage.Batch[] batches = image.batches(yaw, wall, 4.0);
        final long built = System.nanoTime();
        int vertexCount = 0;
        for (SignImage.Batch batch : batches) vertexCount += batch.vertexCount;
        final float[] bounds = image.bounds(yaw, wall);
        System.out.println(String.format(
                "loaded=%s after %d ms, batches=%d, quads=%d, build %.2f ms, viewDistance=%.1f, bounds=[%.2f %.2f %.2f .. %.2f %.2f %.2f]",
                image.isLoaded(), loadedAt, batches.length, vertexCount / 4, (built - start) / 1e6, image.viewDistance(),
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));

        final int[] frame = new int[SIZE * SIZE];
        final float[] depth = new float[SIZE * SIZE];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = 0xFF9DB7D6;
            depth[i] = Float.NEGATIVE_INFINITY;
        }

        // 目印として、看板のあるブロックの床と、壁掛け看板なら背後の壁を描く
        final double front = Math.toRadians(yaw);
        final float fx = (float) -Math.sin(front), fz = (float) Math.cos(front);
        final int[] white = {0xFFFFFFFF};
        plane(frame, depth, yaw, new float[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, 0xFF5E8C3A, white);
        if (wall) {
            // 看板の背後 (正面の反対側) にあるブロックの面
            final float cx = 0.5f - fx * 0.5f, cz = 0.5f - fz * 0.5f;
            final float sx = fz * 0.5f, sz = -fx * 0.5f;
            plane(frame, depth, yaw, new float[]{cx - sx, 0, cz - sz, cx + sx, 0, cz + sz,
                    cx + sx, 1, cz + sz, cx - sx, 1, cz - sz}, 0xFF8A8A8A, white);
        }

        for (SignImage.Batch batch : batches) {
            final FakePlatform.Texture texture = (FakePlatform.Texture) batch.texture;
            final float[] view = new float[batch.vertexCount * SignImage.Batch.STRIDE];
            for (int i = 0; i < batch.vertexCount; i++) {
                final int o = i * SignImage.Batch.STRIDE;
                toView(batch.vertices[o], batch.vertices[o + 1], batch.vertices[o + 2], yaw, view, o);
                view[o + 3] = batch.vertices[o + 3];
                view[o + 4] = batch.vertices[o + 4];
            }
            GltfPreview.draw(frame, depth, SIZE, view, batch.colors, batch.vertexCount,
                    texture.width, texture.height, texture.pixels, false);
        }

        final BufferedImage picture = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(0, 0, SIZE, SIZE, frame, 0, SIZE);
        ImageIO.write(picture, "png", output);
        MarumaSign.signs().clearCache();
        System.exit(image.isLoaded() ? 0 : 1);
    }

    // 4 つの角 (x, y, z) で指定した面を、両側から見えるように描く
    private static void plane(int[] frame, float[] depth, float yaw, float[] corners, int color, int[] white) {
        final float[] view = new float[8 * SignImage.Batch.STRIDE];
        final int[] colors = new int[8];
        for (int i = 0; i < 8; i++) {
            final int corner = i < 4 ? i : 7 - i;
            toView(corners[corner * 3], corners[corner * 3 + 1], corners[corner * 3 + 2], yaw, view, i * SignImage.Batch.STRIDE);
            colors[i] = color;
        }
        GltfPreview.draw(frame, depth, SIZE, view, colors, 8, 1, 1, white, false);
    }

    // ブロックの角を原点とした座標を、看板の正面の斜め上から見た画面の座標にする
    private static void toView(float x, float y, float z, float yaw, float[] out, int offset) {
        x -= 0.5f;
        y -= 0.5f;
        z -= 0.5f;
        // 看板の正面を手前に向けてから、横に 30 度、上に 20 度回り込む
        final double a = Math.toRadians(yaw - 30);
        final float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        final float rx = x * cos + z * sin;
        final float rz = -x * sin + z * cos;
        final double p = Math.toRadians(20);
        final float cosP = (float) Math.cos(p), sinP = (float) Math.sin(p);
        final float ry = y * cosP - rz * sinP;
        final float depth = y * sinP + rz * cosP;
        out[offset] = rx * ZOOM;
        out[offset + 1] = ry * ZOOM;
        out[offset + 2] = depth;
    }
}
