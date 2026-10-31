package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.gltf.GltfPreview;
import marumasa.marumasa_sign.core.sign.SignImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Minecraft を起動せずに、ガウシアンスプラッティングのデータを PNG に描いて確かめる。
 * 引数: 出力先のフォルダ, スプラットのファイル...
 * 正面 (front)、斜め (diagonal)、横 (side) の 3 視点を書き出す。
 */
public final class SplatPreview {

    public static final int FRAME_SIZE = 512;

    private SplatPreview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SplatPreview <outDir> <splatFiles...>");
            return;
        }
        final File outDir = new File(args[0]);
        outDir.mkdirs();

        final double[][] views = {
                {0, 0},     // front: 正面
                {35, 20},   // diagonal: 斜め
                {90, 0}     // side: 横
        };
        final String[] viewNames = {"front", "diagonal", "side"};

        int failures = 0;
        for (int i = 1; i < args.length; i++) {
            // 「ファイル#coord=RDF」のように、看板の URL と同じ座標の向きの指定を付けてもよい
            final int hash = args[i].indexOf('#');
            final String fragment = hash < 0 ? "" : args[i].substring(hash);
            final File file = new File(hash < 0 ? args[i] : args[i].substring(0, hash));
            try {
                final long start = System.nanoTime();
                final SplatCloud cloud = load(file, fragment);
                final SplatScene scene = new SplatScene(cloud);
                final long loaded = System.nanoTime();

                final String baseName = (file.isDirectory() ? file.getName() + "_folder" : file.getName()).replace('.', '_')
                        + (fragment.isEmpty() ? "" : "_" + fragment.replaceAll("[^A-Za-z0-9]+", ""));
                for (int v = 0; v < views.length; v++) {
                    final int[] frame = render(scene, views[v][0], views[v][1], FRAME_SIZE);
                    final BufferedImage image = new BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, FRAME_SIZE, FRAME_SIZE, frame, 0, FRAME_SIZE);
                    final File outFile = new File(outDir, baseName + "_" + viewNames[v] + ".png");
                    ImageIO.write(image, "png", outFile);
                }
                final long drawn = System.nanoTime();
                System.out.println("OK   " + file.getName() + ": " + cloud.count + " splats, load "
                        + (loaded - start) / 1000000 + " ms, draw 3 views " + (drawn - loaded) / 1000000 + " ms");
            } catch (IOException e) {
                failures++;
                System.out.println("FAIL " + file.getName() + ": " + e.getMessage());
            }
        }
        if (failures > 0) System.exit(1);
    }

    // ファイルを読む。フォルダか meta.json なら、zip にしていない .sog として、同じフォルダの WebP を読む
    static SplatCloud load(File file, String fragment) throws IOException {
        final File meta = file.isDirectory() ? new File(file, "meta.json") : file;
        final byte[] content = Files.readAllBytes(meta.toPath());
        if (!meta.getName().equals("meta.json") || !SplatLoader.isSogMeta(content)) {
            return SplatLoader.load(content, file.getName() + fragment);
        }
        final File folder = meta.getAbsoluteFile().getParentFile();
        return SplatLoader.loadSogFiles(content, new SplatLoader.SogFiles() {
            @Override
            public byte[] load(String name, int maxBytes) throws IOException {
                final File target = new File(folder, name);
                if (target.length() > maxBytes) throw new IOException("Too large: " + name);
                return Files.readAllBytes(target.toPath());
            }
        }, meta.getName() + fragment);
    }

    public static int[] render(SplatScene scene, double yawDeg, double pitchDeg, int size) {
        final int[] frame = new int[size * size];
        final float[] depth = new float[size * size];
        for (int i = 0; i < frame.length; i++) {
            // 透明な部分が分かるように市松模様の背景にする
            final boolean dark = (((i % size) >> 5) + ((i / size) >> 5)) % 2 == 0;
            frame[i] = dark ? 0xFF6E7F8D : 0xFF8494A1;
            depth[i] = Float.NEGATIVE_INFINITY;
        }
        renderView(scene, yawDeg, pitchDeg, size, frame, depth);
        return frame;
    }

    public static void renderView(SplatScene scene, double yawDeg, double pitchDeg, int size, int[] frame, float[] depth) {
        final float[] box = scene.restBounds();
        final float cx = (box[0] + box[3]) * 0.5f;
        final float cy = (box[1] + box[4]) * 0.5f;
        final float cz = (box[2] + box[5]) * 0.5f;
        final float extent = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        final float fit = extent > 0f ? 1.6f / extent : 1f;

        final double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        final float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        final float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
        final float[] center = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -cx, -cy, -cz, 1};
        final float[] rotateY = {cosY, 0, -sinY, 0, 0, 1, 0, 0, sinY, 0, cosY, 0, 0, 0, 0, 1};
        final float[] rotateX = {1, 0, 0, 0, 0, cosP, sinP, 0, 0, -sinP, cosP, 0, 0, 0, 0, 1};
        final float[] scale = {fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, 1};
        final float[] a = new float[16], b = new float[16], placement = new float[16];
        matMultiply(rotateY, center, a);
        matMultiply(rotateX, a, b);
        matMultiply(scale, b, placement);

        final int[] tex = SplatScene.gaussianTexture();
        final SplatScene.View view = scene.createView(placement, tex);
        final SignImage.Batch[] batches = view.batches(0f, 0f, 10f, tex);
        for (SignImage.Batch batch : batches) {
            GltfPreview.draw(frame, depth, size, batch.vertices, batch.colors, batch.vertexCount,
                    SplatScene.TEXTURE_SIZE, SplatScene.TEXTURE_SIZE, tex, batch.blend);
        }
    }

    private static void matMultiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            final float b0 = b[column * 4], b1 = b[column * 4 + 1], b2 = b[column * 4 + 2], b3 = b[column * 4 + 3];
            out[column * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3;
            out[column * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3;
            out[column * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3;
            out[column * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3;
        }
    }
}
