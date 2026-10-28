package marumasa.marumasa_sign.core.gltf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Minecraft を起動せずに、glTF のモデルを PNG に描いて確かめる。
 * 引数: 出力先のフォルダ, 時刻 (秒), モデルのファイル...
 * 外部ファイルを参照している .gltf は、1 つの .glb にまとめたものでも同じ絵になるかを確かめる。
 */
public final class GltfCheck {

    public static final int FRAME_SIZE = 512;

    private GltfCheck() {
    }

    public static void main(String[] args) throws Exception {
        final File outDir = new File(args[0]);
        outDir.mkdirs();
        final float time = Float.parseFloat(args[1]);
        int failures = 0;
        for (int i = 2; i < args.length; i++) {
            final File file = new File(args[i]);
            try {
                final long start = System.nanoTime();
                final byte[] content = Files.readAllBytes(file.toPath());
                final GltfModel model = GltfLoader.load(content, resources(file));
                final GltfScene scene = new GltfScene(model);
                final long loaded = System.nanoTime();
                final int[] frame = render(scene, time);
                final long drawn = System.nanoTime();
                final String name = file.getName().replace('.', '_') + ".png";
                final BufferedImage image = new BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, FRAME_SIZE, FRAME_SIZE, frame, 0, FRAME_SIZE);
                ImageIO.write(image, "png", new File(outDir, name));

                String packed = "";
                if (!GltfLoader.isGlb(content, content.length)) {
                    final byte[] glb = GltfPacker.pack(content, resources(file));
                    final int[] again = render(new GltfScene(GltfLoader.load(glb, null)), time);
                    packed = java.util.Arrays.equals(frame, again) ? ", packed GLB matches" : ", PACKED GLB DIFFERS";
                    if (!java.util.Arrays.equals(frame, again)) failures++;
                }
                System.out.println("OK   " + file.getName() + ": " + model.triangleCount() + " triangles, "
                        + model.textureCount() + " textures (" + model.bakedCount() + " baked), " + scene.groups().length
                        + " groups, animated="
                        + scene.isAnimated() + ", load " + (loaded - start) / 1000000 + " ms, draw "
                        + (drawn - loaded) / 1000000 + " ms" + packed);
            } catch (IOException e) {
                failures++;
                System.out.println("FAIL " + file.getName() + ": " + e.getMessage());
            }
        }
        if (failures > 0) System.exit(1);
    }

    // URI の %XX (空白の %20 など) を戻して読む (Mod と同じ読み方)
    private static GltfLoader.Resources resources(final File file) {
        return marumasa.marumasa_sign.core.image.ImageLoader.fileResources(file);
    }

    private static int[] render(GltfScene scene, float time) {
        final int[] frame = new int[FRAME_SIZE * FRAME_SIZE];
        final float[] depth = new float[FRAME_SIZE * FRAME_SIZE];
        for (int i = 0; i < frame.length; i++) {
            // 透明な部分が分かるように、市松模様の背景にする
            final boolean dark = (((i % FRAME_SIZE) >> 5) + ((i / FRAME_SIZE) >> 5)) % 2 == 0;
            frame[i] = dark ? 0xFF6E7F8D : 0xFF8494A1;
            depth[i] = Float.NEGATIVE_INFINITY;
        }
        GltfPreview.render(scene, time, FRAME_SIZE, frame, depth);
        return frame;
    }
}
