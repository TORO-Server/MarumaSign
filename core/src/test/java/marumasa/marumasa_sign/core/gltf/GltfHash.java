package marumasa.marumasa_sign.core.gltf;

import marumasa.marumasa_sign.core.image.ImageLoader;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 3D モデルを読み込んだ結果 (テクスチャのピクセルと、描画に使う頂点の値) のハッシュを、ファイルごとに表示する。
 * 読み込みの処理を速くしたときに、結果が 1 ビットも変わっていないことを、変える前の出力と比べて確かめるのに使う。
 * 引数: モデルのファイル...
 */
public final class GltfHash {

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            final File file = new File(name);
            final long start = System.nanoTime();
            final GltfModel model;
            try {
                model = GltfLoader.load(Files.readAllBytes(file.toPath()), ImageLoader.fileResources(file));
            } catch (Exception e) {
                System.out.println(file.getName() + " FAILED " + e);
                continue;
            }
            final long millis = (System.nanoTime() - start) / 1000000;
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < model.textureCount(); i++) {
                final GltfModel.Texture texture = model.texture(i);
                update(digest, texture.width);
                update(digest, texture.height);
                for (int pixel : texture.pixels) update(digest, pixel);
            }
            // 頂点の位置・テクスチャ座標・色は、決まった姿勢で並べたものを使う
            final GltfScene scene = new GltfScene(model);
            final GltfScene.Group[] groups = scene.groups();
            final float[][] vertices = new float[groups.length][];
            final int[][] colors = new int[groups.length][];
            for (int i = 0; i < groups.length; i++) {
                vertices[i] = new float[groups[i].vertexCount() * 5];
                colors[i] = new int[groups[i].vertexCount()];
            }
            scene.pose(0f);
            scene.emit(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, vertices, colors);
            for (int i = 0; i < groups.length; i++) {
                for (float value : vertices[i]) update(digest, Float.floatToIntBits(value));
                for (int value : colors[i]) update(digest, value);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            System.out.println(String.format(Locale.ROOT, "%-48s %s %6d ms", file.getName(), hex.substring(0, 32), millis));
        }
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
