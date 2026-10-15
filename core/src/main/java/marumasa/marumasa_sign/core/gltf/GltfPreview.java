package marumasa.marumasa_sign.core.gltf;

import java.awt.image.BufferedImage;

/**
 * 3D モデルを画像に描く。設定画面のプレビューに使う。
 */
public final class GltfPreview {

    private GltfPreview() {
    }

    /**
     * モデルを斜め上から見た絵を返す。背景は透明。
     *
     * @param time アニメーションの時刻 (秒)
     * @param size 画像の一辺の長さ
     */
    public static BufferedImage render(GltfScene scene, float time, int size) {
        final int[] frame = new int[size * size];
        final float[] depth = new float[size * size];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        render(scene, time, size, frame, depth);
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, frame, 0, size);
        return image;
    }

    // frame (ARGB) にモデルを描き足す
    public static void render(GltfScene scene, float time, int size, int[] frame, float[] depth) {
        final float[] box = scene.restBounds();
        final float cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
        final float extent = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        final float fit = extent > 0 ? 1.1f / extent : 1f;

        // 斜め上から見る
        final double yaw = Math.toRadians(30), pitch = Math.toRadians(20);
        final float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        final float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
        final float[] center = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -cx, -cy, -cz, 1};
        final float[] rotateY = {cosY, 0, -sinY, 0, 0, 1, 0, 0, sinY, 0, cosY, 0, 0, 0, 0, 1};
        final float[] rotateX = {1, 0, 0, 0, 0, cosP, sinP, 0, 0, -sinP, cosP, 0, 0, 0, 0, 1};
        final float[] scale = {fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, fit, 0, 0, 0, 0, 1};
        final float[] a = new float[16], b = new float[16], placement = new float[16];
        Mat4.multiply(rotateY, center, a);
        Mat4.multiply(rotateX, a, b);
        Mat4.multiply(scale, b, placement);

        final GltfScene.Group[] groups = scene.groups();
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
            colors[i] = new int[groups[i].vertexCount()];
        }
        scene.pose(time);
        scene.emit(placement, vertices, colors);

        for (int g = 0; g < groups.length; g++) {
            final GltfModel.Texture texture = scene.model().texture(groups[g].texture);
            draw(frame, depth, size, vertices[g], colors[g], groups[g].vertexCount(),
                    texture.width, texture.height, texture.pixels, groups[g].blend);
        }
    }

    /**
     * 四角形の列を描く。頂点は x, y, z, u, v の順で、x, y は -1 から 1 が画面の範囲、z は大きいほど手前。
     * 反時計回りに見える面だけを描く。colors が null なら白として扱う。
     */
    public static void draw(int[] frame, float[] depth, int size, float[] vertices, int[] colors, int vertexCount,
                            int textureWidth, int textureHeight, int[] texture, boolean blend) {
        final int[] corner = new int[3];
        final float[] sx = new float[3], sy = new float[3];
        for (int v = 0; v + 3 < vertexCount; v += 4) {
            // 四角形は三角形 2 枚に分ける。3D モデルの三角形は 2 枚目の面積が 0 になる
            for (int half = 0; half < 2; half++) {
                corner[0] = v;
                corner[1] = v + 1 + half;
                corner[2] = v + 2 + half;
                triangle(frame, depth, size, vertices, colors, corner, sx, sy, textureWidth, textureHeight, texture, blend);
            }
        }
    }

    private static void triangle(int[] frame, float[] depth, int size, float[] vertices, int[] colors, int[] corner,
                                 float[] sx, float[] sy, int textureWidth, int textureHeight, int[] texture, boolean blend) {
        for (int k = 0; k < 3; k++) {
            sx[k] = (vertices[corner[k] * GltfScene.STRIDE] * 0.5f + 0.5f) * size;
            sy[k] = (0.5f - vertices[corner[k] * GltfScene.STRIDE + 1] * 0.5f) * size;
        }
        // 画面の y は下向きなので、表 (反時計回り) の面積は負になる
        final float area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
        if (area >= 0) return;

        final int minX = Math.max(0, (int) Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        final int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        final int minY = Math.max(0, (int) Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        final int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        final int colorA = colors == null ? 0xFFFFFFFF : colors[corner[0]];
        final int colorB = colors == null ? 0xFFFFFFFF : colors[corner[1]];
        final int colorC = colors == null ? 0xFFFFFFFF : colors[corner[2]];

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                final float px = x + 0.5f, py = y + 0.5f;
                final float w0 = ((sx[1] - px) * (sy[2] - py) - (sx[2] - px) * (sy[1] - py)) / area;
                final float w1 = ((sx[2] - px) * (sy[0] - py) - (sx[0] - px) * (sy[2] - py)) / area;
                final float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;

                final float z = value(vertices, corner, 2, w0, w1, w2);
                final int pixel = y * size + x;
                if (z <= depth[pixel]) continue;

                final float u = value(vertices, corner, 3, w0, w1, w2);
                final float v = value(vertices, corner, 4, w0, w1, w2);
                final int tx = Math.max(0, Math.min(textureWidth - 1, (int) (u * textureWidth)));
                final int ty = Math.max(0, Math.min(textureHeight - 1, (int) (v * textureHeight)));
                final int texel = texture[ty * textureWidth + tx];

                final int behind = frame[pixel];
                final float behindAlpha = (behind >>> 24) / 255f;
                float alpha = 1;
                int result = 0;
                for (int shift = 24; shift >= 0; shift -= 8) {
                    final float color = (((colorA >>> shift) & 0xFF) * w0 + ((colorB >>> shift) & 0xFF) * w1
                            + ((colorC >>> shift) & 0xFF) * w2) / 255f;
                    final float value = color * ((texel >>> shift) & 0xFF) / 255f;
                    if (shift == 24) {
                        alpha = value;
                    } else {
                        // 半透明の面は、すでに描かれている色と混ぜる
                        final float under = ((behind >>> shift) & 0xFF) / 255f * behindAlpha;
                        final float mixed = blend ? under + (value - under) * alpha : value;
                        result |= Math.max(0, Math.min(255, Math.round(mixed * 255))) << shift;
                    }
                }
                if (alpha < 0.1f) continue;
                final float coverage = blend ? Math.max(behindAlpha, alpha) : 1f;
                frame[pixel] = result | (Math.round(coverage * 255) << 24);
                if (!blend) depth[pixel] = z;
            }
        }
    }

    private static float value(float[] vertices, int[] corner, int element, float w0, float w1, float w2) {
        return vertices[corner[0] * GltfScene.STRIDE + element] * w0 + vertices[corner[1] * GltfScene.STRIDE + element] * w1
                + vertices[corner[2] * GltfScene.STRIDE + element] * w2;
    }
}
