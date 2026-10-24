package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.gltf.GltfLoader;
import marumasa.marumasa_sign.core.gltf.GltfModel;
import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.image.DecodedImage;
import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.net.Http;
import marumasa.marumasa_sign.core.net.UrlPolicy;
import marumasa.marumasa_sign.core.pdf.PdfFile;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 構造物の中にある、MarumaSign の形式で書かれた看板 (と、その文字を持つ看板のアイテム)。
 * ゲームでは看板の板が消えて、書かれた URL の画像や 3D モデルが表示されるので、構造物の中でも同じように描く。
 * <ul>
 *     <li>画像 (アニメーションも) と PDF の 1 ページ目は絵として描く</li>
 *     <li>3D モデルと構造物は、その形を描く。構造物の中の構造物は 2 段までで、同じ URL をたどり直すことはしない</li>
 *     <li>動画、音声、ガウシアンスプラッティング、読めなかったものは何も描かない (看板の板は消える)</li>
 * </ul>
 * 構造物のファイルは人から受け取ることが多いので、悪用されないように、読み込む URL の数と大きさに上限を設け、
 * 自分のパソコンや家の中のネットワークの URL は、ほかの看板と同じく読まない (UrlPolicy)。
 */
final class NestedSigns {

    // 入れ子にする深さの上限 (看板の構造物の中の看板の構造物、まで)
    private static final int MAX_DEPTH = 2;
    // 絵として描くものの長い辺の上限 (まとめた画像に入りきるように縮める)
    private static final int MAX_TEXTURE = 512;
    // 1 つの構造物 (中の構造物も含めて) で読み込む URL の数と、合計の大きさの上限
    private static final int MAX_URLS = 16;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    // 看板の 3D モデルの面の数の上限
    private static final int MAX_MODEL_QUADS = 30000;
    // 1 つの構造物 (中の構造物も含めて) で、看板の中身を読み込むのに使う時間の上限。
    // 構造物は 1 つずつ読み込むので、遅いサーバーを書いた看板で、ほかの構造物の読み込みを待たせ続けないように
    private static final long MAX_NANOS = 20L * 1000000000L;

    // いま読み込んでいる構造物 (いちばん外側から) で、読み込んだ URL の数と合計の大きさ、読み込みの期限 (System.nanoTime)
    private static final ThreadLocal<long[]> USED = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            return new long[3];
        }
    };

    // いちばん外側の構造物を読み込み始めるときに呼ぶ
    static void startTopLevel() {
        final long[] used = USED.get();
        used[0] = 0;
        used[1] = 0;
        used[2] = System.nanoTime() + MAX_NANOS;
    }

    private static final ThreadLocal<Set<String>> VISITING = new ThreadLocal<Set<String>>() {
        @Override
        protected Set<String> initialValue() {
            return new HashSet<String>();
        }
    };

    // 読み込んだもの (URL ごと。読めなかったものは NOTHING)
    private static final Object NOTHING = new Object();
    private final Map<String, Object> loaded = new ConcurrentHashMap<String, Object>();

    // 看板の文字 (表、裏の順に行をつなげたもの) が MarumaSign の形式なら、その内容。そうでなければ null
    static SignSpec spec(List<List<SignTexts.Run>> lines) {
        final StringBuilder text = new StringBuilder();
        for (List<SignTexts.Run> line : lines) {
            for (SignTexts.Run run : line) text.append(run.text);
        }
        return SignSpec.parse(text.toString());
    }

    // URL の中身 (絵なら Sprite、モデルなら GltfScene)。描くものがなければ null
    private Object load(String url) {
        Object result = loaded.get(url);
        if (result != null) return result == NOTHING ? null : result;
        result = NOTHING;
        final Set<String> visiting = VISITING.get();
        final long[] used = USED.get();
        if (used[0] < MAX_URLS && used[1] < MAX_BYTES && visiting.size() < MAX_DEPTH && !visiting.contains(url)
                && System.nanoTime() - used[2] < 0 && UrlPolicy.isAllowed(url)) {
            used[0]++;
            visiting.add(url);
            try {
                final Object value = fetch(url, used[2]);
                if (value != null) result = value;
            } catch (Exception e) {
                MarumaSign.LOGGER.info("Could not load a sign in the structure: " + url + " (" + e + ")");
            } finally {
                visiting.remove(url);
            }
        }
        loaded.put(url, result);
        return result == NOTHING ? null : result;
    }

    private static Object fetch(String url, long deadline) throws Exception {
        final int[] kind = {0};
        final String[] name = {url};
        final byte[] content = Http.downloadBefore(url, new Http.ContentCheck() {
            @Override
            public int maxBytes(byte[] head, int length, String resolved) {
                name[0] = resolved;
                if (ImageLoader.isSupportedImage(head, length, resolved)) {
                    kind[0] = 1;
                    return Http.MAX_SIZE;
                }
                if (PdfFile.isPdf(head, length)) {
                    kind[0] = 2;
                    return PdfFile.MAX_SIZE;
                }
                if (GltfLoader.isGlb(head, length) || StructureLoader.isStructure(head, length)) {
                    kind[0] = 3;
                    return Math.max(ImageLoader.MAX_MODEL_SIZE, StructureLoader.MAX_SIZE);
                }
                if (ImageLoader.isSplat(head, length, resolved)) return 0;
                if (GltfLoader.looksLikeJson(head, length)) {
                    kind[0] = 3;
                    return ImageLoader.MAX_MODEL_SIZE;
                }
                return 0;
            }
        }, deadline);
        // 動画や音声は描かない (構造物は止まった形として描くので)
        if (content == null) return null;
        USED.get()[1] += content.length;
        switch (kind[0]) {
            case 1:
                return sprite(url, ImageLoader.decode(content, name[0]));
            case 2: {
                final PdfFile file = PdfFile.open(content);
                try {
                    // 置いた看板と同じく、URL の「#page=」で指定したページを描く
                    return sprite(url, file.render(file.clampPage(PdfFile.requestedPage(url)), PdfFile.PAGE_SIZE));
                } finally {
                    file.close();
                }
            }
            case 3:
                return ImageLoader.model(name[0], content, deadline);
            default:
                return null;
        }
    }

    // 絵を、まとめた画像に入る大きさに縮めてテクスチャにする。アニメーションは tick 単位の表示時間にする
    private static Sprite sprite(String url, DecodedImage image) {
        final int longest = Math.max(image.width, image.height);
        final int divisor = (longest + MAX_TEXTURE - 1) / MAX_TEXTURE;
        final int width = Math.max(1, image.width / Math.max(1, divisor)), height = Math.max(1, image.height / Math.max(1, divisor));
        final int count = image.frames.length;
        final int[][] frames = new int[count][];
        for (int f = 0; f < count; f++) frames[f] = shrink(image.frames[f], image.width, image.height, width, height);
        if (count == 1) return Sprite.of("sign:" + url, width, height, frames[0]);
        final int[] sequence = new int[count];
        final int[] times = new int[count];
        for (int f = 0; f < count; f++) {
            sequence[f] = f;
            times[f] = Math.max(1, Math.round(Math.max(1, image.delays[f]) / 50f));
        }
        return Sprite.animated("sign:" + url, width, height, frames, sequence, times);
    }

    private static int[] shrink(int[] pixels, int width, int height, int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight) return pixels;
        final int[] result = new int[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            for (int x = 0; x < newWidth; x++) result[y * newWidth + x] = pixels[(y * height / newHeight) * width + x * width / newWidth];
        }
        return result;
    }

    // ---------- 描く ----------

    /**
     * 看板の位置に、書かれた URL の中身を描く。
     *
     * @param signYaw 看板の向き (度)
     * @param origin  看板のブロックの角の位置 (構造物の座標)
     * @param scale   1 ブロックの大きさ (間引いた構造物では 1 / step)
     */
    void emitSign(StructureMesher mesher, SignSpec spec, float signYaw, boolean wall, float[] origin, float scale) {
        final Object content = load(spec.url);
        if (content instanceof Sprite) {
            final Sprite sprite = (Sprite) content;
            final float[] mesh = SignImage.detachedImageMesh(spec, sprite.width, sprite.height, signYaw, wall);
            for (int side = 0; side < 2; side++) {
                final float[] positions = new float[12];
                final float[] uvs = new float[8];
                for (int v = 0; v < 4; v++) {
                    final int o = (side * 4 + v) * SignImage.STRIDE;
                    positions[v * 3] = origin[0] + mesh[o] * scale;
                    positions[v * 3 + 1] = origin[1] + mesh[o + 1] * scale;
                    positions[v * 3 + 2] = origin[2] + mesh[o + 2] * scale;
                    uvs[v * 2] = mesh[o + 3];
                    uvs[v * 2 + 1] = mesh[o + 4];
                }
                // 看板の画像は、面の向きによる明るさを付けない
                mesher.addQuad(StructureMesher.layer(sprite.transparency(0, 0, 1, 1)), positions, uvs, 0xFFFFFFFF, sprite, false);
            }
        } else if (content instanceof GltfScene) {
            final GltfScene scene = (GltfScene) content;
            emitModel(mesher, scene, SignImage.detachedModelPlacement(spec, scene, signYaw, wall, false), origin, scale);
        }
    }

    /**
     * MarumaSign の看板の文字を持つ看板のアイテムを、アイテムの位置 (一辺 1 の立方体) に描く。
     */
    void emitItem(StructureMesher mesher, SignSpec spec, Transform transform) {
        final Object content = load(spec.url);
        if (content instanceof Sprite) {
            final Sprite sprite = (Sprite) content;
            final float[] vertices = SignImage.detachedItemImage(sprite.width, sprite.height);
            for (int side = 0; side < 2; side++) {
                final float[] positions = new float[12];
                final float[] uvs = new float[8];
                for (int v = 0; v < 4; v++) {
                    final int o = (side * 4 + v) * 5;
                    transform.apply(vertices[o], vertices[o + 1], vertices[o + 2], positions, v * 3);
                    uvs[v * 2] = vertices[o + 3];
                    uvs[v * 2 + 1] = vertices[o + 4];
                }
                if (transform.flips()) {
                    reverse(positions, 3);
                    reverse(uvs, 2);
                }
                mesher.addQuad(StructureMesher.layer(sprite.transparency(0, 0, 1, 1)), positions, uvs, 0xFFFFFFFF, sprite, false);
            }
        } else if (content instanceof GltfScene) {
            final GltfScene scene = (GltfScene) content;
            final float[] placement = SignImage.detachedModelPlacement(spec, scene, 0, false, true);
            final float[] combined = new float[16];
            // transform (行の順) と placement (列優先) をつなげて、列優先の行列にする
            final float[] t = transform.m;
            for (int column = 0; column < 4; column++) {
                for (int row = 0; row < 4; row++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++) sum += t[row * 4 + k] * placement[column * 4 + k];
                    combined[column * 4 + row] = sum;
                }
            }
            emitModel(mesher, scene, combined, new float[3], 1);
        }
    }

    private static void reverse(float[] values, int stride) {
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < stride; k++) {
                final float tmp = values[i * stride + k];
                values[i * stride + k] = values[(3 - i) * stride + k];
                values[(3 - i) * stride + k] = tmp;
            }
        }
    }

    // モデルの四角形を、絵の色ごと書き出す (アニメーションするモデルは最初の姿勢)
    private void emitModel(StructureMesher mesher, GltfScene scene, float[] placement, float[] origin, float scale) {
        final GltfScene.Group[] groups = scene.groups();
        int quads = 0;
        for (GltfScene.Group group : groups) quads += group.vertexCount() / 4;
        if (quads > MAX_MODEL_QUADS || scene.model().textureCount() > 32) {
            MarumaSign.LOGGER.info("A model on a sign in the structure has too many faces (" + quads + "); not drawing it");
            return;
        }
        final float[][] vertices = new float[groups.length][];
        final int[][] colors = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            vertices[i] = new float[groups[i].vertexCount() * GltfScene.STRIDE];
            colors[i] = new int[groups[i].vertexCount()];
        }
        synchronized (scene) {
            scene.pose(0);
            scene.emit(placement, vertices, colors);
        }
        final GltfModel model = scene.model();
        final List<Sprite> sprites = new ArrayList<Sprite>();
        for (int i = 0; i < model.textureCount(); i++) {
            final GltfModel.Texture texture = model.texture(i);
            final int longest = Math.max(texture.width, texture.height);
            final int divisor = (longest + MAX_TEXTURE - 1) / MAX_TEXTURE;
            final int width = Math.max(1, texture.width / Math.max(1, divisor)), height = Math.max(1, texture.height / Math.max(1, divisor));
            sprites.add(Sprite.of("model:" + System.identityHashCode(scene) + ":" + i, width, height,
                    shrink(texture.pixels, texture.width, texture.height, width, height)));
        }
        final float[] positions = new float[12];
        final float[] uvs = new float[8];
        final int[] quadColors = new int[4];
        for (int g = 0; g < groups.length; g++) {
            final Sprite sprite = sprites.get(groups[g].texture);
            final int layer = groups[g].blend ? StructureMesher.LAYER_TRANSLUCENT : StructureMesher.LAYER_CUTOUT;
            final float[] v = vertices[g];
            for (int q = 0; q + 3 < groups[g].vertexCount(); q += 4) {
                for (int k = 0; k < 4; k++) {
                    final int o = (q + k) * GltfScene.STRIDE;
                    positions[k * 3] = origin[0] + v[o] * scale;
                    positions[k * 3 + 1] = origin[1] + v[o + 1] * scale;
                    positions[k * 3 + 2] = origin[2] + v[o + 2] * scale;
                    // テクスチャの繰り返しはできないので、範囲の中に収める
                    uvs[k * 2] = v[o + 3] - (float) Math.floor(v[o + 3]);
                    uvs[k * 2 + 1] = v[o + 4] - (float) Math.floor(v[o + 4]);
                    quadColors[k] = colors[g][q + k];
                }
                mesher.addColoredQuad(layer, positions, uvs, quadColors, sprite);
            }
        }
    }
}
