package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.gltf.GltfModel;
import marumasa.marumasa_sign.core.gltf.GltfPreview;
import marumasa.marumasa_sign.core.gltf.GltfScene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 構造物のファイルを読み込み、斜め上の 2 方向から見た絵を PNG に書き出す (Minecraft を起動せずに確かめる)。
 * 引数: 出力先 ファイルまたはフォルダ...
 * ブロックのテクスチャは、システムプロパティ marumasign.assetJar の jar か、ダウンロードしたものを使う。
 */
public final class StructureCheck {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: StructureCheck <output directory> <file or directory>...");
            System.exit(2);
        }
        final File output = new File(args[0]);
        output.mkdirs();
        final List<File> files = new ArrayList<File>();
        for (int i = 1; i < args.length; i++) collect(new File(args[i]), files);
        int failures = 0;
        for (File file : files) {
            final long start = System.nanoTime();
            try {
                final byte[] content = Files.readAllBytes(file.toPath());
                final boolean detected = StructureLoader.isStructure(content, Math.min(content.length, 512));
                final StructureLoader.Result result = StructureLoader.loadDetailed(content);
                final long millis = (System.nanoTime() - start) / 1000000;
                final Structure s = result.structure;
                System.out.println(file.getName() + ": " + s.source + " dv=" + s.dataVersion + " " + s.fullX + "x" + s.fullY + "x" + s.fullZ
                        + " step=" + s.step + " palette=" + s.paletteSize() + " blockEntities=" + s.blockEntities().size()
                        + " entities=" + s.entities().size() + " quads=" + result.quads + " textures=" + textureSummary(result.scene.model())
                        + " detected=" + detected + " " + millis + " ms");
                final BufferedImage a = render(result.scene, 30, 25, 768);
                final BufferedImage b = render(result.scene, 210, 25, 768);
                final BufferedImage both = new BufferedImage(a.getWidth() * 2, a.getHeight(), BufferedImage.TYPE_INT_ARGB);
                both.getGraphics().drawImage(a, 0, 0, null);
                both.getGraphics().drawImage(b, a.getWidth(), 0, null);
                ImageIO.write(both, "png", new File(output, file.getName() + ".png"));
            } catch (Exception e) {
                failures++;
                System.out.println(file.getName() + ": FAILED " + e);
            }
        }
        if (!BlockModels.MISSING.isEmpty()) System.out.println("blocks without a model: " + BlockModels.MISSING);
        if (!Sprite.MISSING.isEmpty()) System.out.println("missing textures: " + Sprite.MISSING);
        System.out.println(files.size() + " files, " + failures + " failed");
    }

    private static void collect(File file, List<File> files) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children == null) return;
            java.util.Arrays.sort(children);
            for (File child : children) collect(child, files);
        } else if (file.isFile()) {
            files.add(file);
        }
    }

    private static String textureSummary(GltfModel model) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < model.textureCount(); i++) {
            if (i > 0) builder.append(',');
            builder.append(model.texture(i).width).append('x').append(model.texture(i).height);
        }
        return builder.toString();
    }

    // 列優先の 4x4 行列の積 (out = a * b)
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[column * 4 + k];
                out[column * 4 + row] = sum;
            }
        }
    }

    // 指定した向きから見た絵 (背景は灰色)
    static BufferedImage render(GltfScene scene, float yawDegrees, float pitchDegrees, int size) {
        final float[] box = scene.restBounds();
        float cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
        final float dx = box[3] - box[0], dy = box[4] - box[1], dz = box[5] - box[2];
        float extent = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        // -Dstructure.focus=x,y,z,半径 で、一部を大きく描く (構造物の座標)
        final String focus = System.getProperty("structure.focus");
        if (focus != null) {
            final String[] parts = focus.split(",");
            cx = Float.parseFloat(parts[0]);
            cy = Float.parseFloat(parts[1]);
            cz = Float.parseFloat(parts[2]);
            extent = Float.parseFloat(parts[3]) * 2;
        }
        final float fit = extent > 0 ? 1.9f / extent : 1f;
        final double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        final float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        final float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
        final float[] center = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -cx, -cy, -cz, 1};
        final float[] rotateY = {cosY, 0, -sinY, 0, 0, 1, 0, 0, sinY, 0, cosY, 0, 0, 0, 0, 1};
        final float[] rotateX = {1, 0, 0, 0, 0, cosP, sinP, 0, 0, -sinP, cosP, 0, 0, 0, 0, 1};
        final float[] scale = {fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, 1};
        final float[] t1 = new float[16], t2 = new float[16], placement = new float[16];
        multiply(rotateY, center, t1);
        multiply(rotateX, t1, t2);
        multiply(scale, t2, placement);

        final int[] frame = new int[size * size];
        java.util.Arrays.fill(frame, 0xFF8090A0);
        final float[] depth = new float[size * size];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        final GltfScene.Group[] groups = scene.groups();
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
            colors[i] = new int[groups[i].vertexCount()];
        }
        scene.pose(0);
        scene.emit(placement, vertices, colors);
        for (int g = 0; g < groups.length; g++) {
            final GltfModel.Texture texture = scene.model().texture(groups[g].texture);
            GltfPreview.draw(frame, depth, size, vertices[g], colors[g], groups[g].vertexCount(),
                    texture.width, texture.height, texture.pixels, groups[g].blend);
        }
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, frame, 0, size);
        return image;
    }
}
