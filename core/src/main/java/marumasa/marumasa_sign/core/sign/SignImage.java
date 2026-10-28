package marumasa.marumasa_sign.core.sign;

import marumasa.marumasa_sign.core.gltf.GltfScene;
import marumasa.marumasa_sign.core.splat.SplatScene;
import marumasa.marumasa_sign.core.util.Clock;

import java.util.Arrays;

/**
 * 看板 1 種類 (文字列 1 つ) に対応する、表示する画像や 3D モデルと、その形。
 * 描画に必要な座標計算はすべてここで行い、各バージョンの実装は頂点を並べるだけにする。
 */
public final class SignImage {

    /**
     * 同じテクスチャで描く四角形のまとまり。
     * 頂点は 4 つで 1 枚。三角形は 3 つ目の頂点を重ねた四角形になっている。
     */
    public static final class Batch {
        // vertices の 1 頂点あたりの要素数: x, y, z, u, v
        public static final int STRIDE = 5;

        public Object texture;
        // ブロックの角を原点とした座標
        public float[] vertices;
        // 頂点ごとの色 (ARGB)。null ならすべて白
        public int[] colors;
        public int vertexCount;
        // vertices と colors の内容が変わるたびに増える。描画用のバッファを作り直すかどうかの判断に使う
        public volatile int revision;
        // 半透明の面かどうか。半透明の面は、奥行きを記録せずに描くと重なりが自然になる
        public boolean blend;
        // 裏を向いた面も描くかどうか。ガウシアンスプラッティングの四角形はカメラの方を向けて作るので、裏面を省く必要がない
        public boolean doubleSided;
    }

    // mesh() の 1 頂点あたりの要素数: x, y, z, u, v, nx, ny, nz
    public static final int STRIDE = 8;
    // 表と裏で 4 頂点ずつ
    public static final int VERTEX_COUNT = 8;

    private static final float WALL_OFFSET = 0.4375f * 2;
    private static final float FACE_OFFSET = 0.005f;
    // 3D モデルのアニメーションを計算する細かさ (1 秒あたりの回数)
    private static final float MODEL_FRAME_RATE = 60f;
    // 向きの数。立て看板: 0-15, 壁掛け看板: 16-19
    private static final int ORIENTATIONS = 20;
    // アイテムとして描くときの計算結果を入れる場所
    private static final int ITEM = ORIENTATIONS;

    // 3D モデルの、看板の向きごとの計算結果
    private static final class ModelCache {
        final float[] placement = new float[16];
        float[][] vertices;
        int[][] colors;
        Batch[] batches;
        int frame = -1;
    }

    public final SignSpec spec;
    private final TextureEntry texture;

    private int builtRevision = -1;
    // 看板の向きに回転する前の 4 頂点 (x, y, z)
    private final float[] base = new float[12];
    private double viewDistance;
    // 看板の向きごとのメッシュ
    private final float[][] meshes = new float[ORIENTATIONS][];
    private final float[][] bounds = new float[ORIENTATIONS][];
    private final Batch[][] imageBatches = new Batch[ORIENTATIONS + 1][];
    private final ModelCache[] modelCaches = new ModelCache[ORIENTATIONS + 1];
    private final SplatScene.View[] splatViews = new SplatScene.View[ORIENTATIONS + 1];

    // 3D モデルやガウシアンスプラッティングを看板の大きさに合わせるための値
    private GltfScene model;
    private SplatScene splat;
    private float modelCenterX, modelCenterY, modelCenterZ;
    private float modelFit;
    private float modelDepth;
    private final float[] point = new float[3];

    // PDF のページ送りの操作。なければ null
    private final PdfControls controls;
    // 最後に看板として引かれた時刻 (Clock.millis。経った時間を測るためのもの)。SignManager が、使われていないものを消すのに使う
    volatile long lastUsed = Clock.millis();

    SignImage(SignSpec spec, TextureEntry texture, PdfControls controls) {
        this.spec = spec;
        this.texture = texture;
        this.controls = controls;
    }

    // ---------- 構造物の中の MarumaSign の看板 (描画しない、形だけの計算) ----------

    /**
     * 画像の看板の頂点 (mesh() と同じ形)。
     *
     * @param signYaw 看板の向き (度)。立て看板は rotation * 22.5、壁掛け看板は向いている方角の角度
     */
    public static float[] detachedImageMesh(SignSpec spec, int width, int height, float signYaw, boolean wall) {
        final TextureEntry entry = new TextureEntry(TextureEntry.LOADED, width, height, null);
        return new SignImage(spec, entry, null).mesh(signYaw, wall);
    }

    // アイテムとしての画像の四角形 (itemBatches() と同じ形。x, y, z, u, v が 8 頂点)
    public static float[] detachedItemImage(int width, int height) {
        final TextureEntry entry = new TextureEntry(TextureEntry.LOADED, width, height, null);
        return new SignImage(new SignSpec("", 0, 0, 0, 1, 1, 0, 0, 0), entry, null).itemBatches(0)[0].vertices.clone();
    }

    /**
     * 3D モデルの看板で、モデルの座標からブロックの角を原点とした座標への変換 (列優先の 4x4)。
     *
     * @param item アイテムとして描くとき true (signYaw と wall は使わない)
     */
    public static float[] detachedModelPlacement(SignSpec spec, GltfScene scene, float signYaw, boolean wall, boolean item) {
        final TextureEntry entry = new TextureEntry(TextureEntry.LOADED, 1, 1, null);
        entry.setModel(scene, new Object[scene.model().textureCount()]);
        final SignImage image = new SignImage(spec, entry, null);
        image.ensureBuilt();
        final float[] placement = new float[16];
        image.computePlacement(item ? ITEM : meshIndex(signYaw, wall), signYaw, wall, placement);
        return placement;
    }

    public TextureEntry textureEntry() {
        return texture;
    }

    // 現在表示するべきテクスチャ。描画のたびに呼ぶ
    public Object texture() {
        return texture.current(0);
    }

    /**
     * 現在表示するべきテクスチャ。描画のたびに呼ぶ。
     *
     * @param distance カメラから看板までの距離 (ブロック)。動画や音声の音量に使う
     */
    public Object texture(double distance) {
        return texture.current(distance);
    }

    // 読み込みが完了した画像を表示しているかどうか
    public boolean isLoaded() {
        return texture.state() == TextureEntry.LOADED;
    }

    // 描画する距離の上限
    public double viewDistance() {
        ensureBuilt();
        return viewDistance;
    }

    private void ensureBuilt() {
        final int revision = texture.revision();
        if (builtRevision == revision) return;

        model = texture.model();
        splat = texture.splat();
        double maxDistSqr = 0;
        if (model == null && splat == null) {
            final int width = texture.width();
            final int height = texture.height();
            float max = Math.max(width, height);
            if (max <= 0) max = 1;
            final float vx = width / max * spec.scaleX;
            final float vy = height / max * spec.scaleY;

            rotate(-vx, -vy, 0, base, 0);
            rotate(-vx, +vy, 0, base, 3);
            rotate(+vx, +vy, 0, base, 6);
            rotate(+vx, -vy, 0, base, 9);
            for (int i = 0; i < 4; i++) {
                final float x = base[i * 3], y = base[i * 3 + 1], z = base[i * 3 + 2];
                maxDistSqr = Math.max(maxDistSqr, x * x + y * y + z * z);
            }
        } else {
            // モデルを囲む直方体の最も長い辺が、画像の長い辺と同じ大きさになるようにする
            final float[] box = model != null ? model.restBounds() : splat.restBounds();
            modelCenterX = (box[0] + box[3]) / 2;
            modelCenterY = (box[1] + box[4]) / 2;
            modelCenterZ = (box[2] + box[5]) / 2;
            final float extent = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
            modelFit = extent > 0 ? 2f / extent : 1f;
            modelDepth = (box[5] - box[2]) / 2 * modelFit * Math.abs(spec.scaleX);
            for (int corner = 0; corner < 8; corner++) {
                local(box[(corner & 1) * 3], box[1 + ((corner >> 1) & 1) * 3], box[2 + ((corner >> 2) & 1) * 3], point);
                maxDistSqr = Math.max(maxDistSqr, point[0] * point[0] + point[1] * point[1] + point[2] * point[2]);
            }
        }
        viewDistance = Math.min(512.0, 64.0 + Math.sqrt(maxDistSqr) * 2.0);

        for (int i = 0; i < ORIENTATIONS; i++) {
            meshes[i] = null;
            bounds[i] = null;
        }
        for (int i = 0; i <= ORIENTATIONS; i++) {
            imageBatches[i] = null;
            modelCaches[i] = null;
            splatViews[i] = null;
        }
        builtRevision = revision;
    }

    // 看板で指定された回転と移動を行い、out[offset..] に x, y, z を入れる
    private void rotate(float x, float y, float z, float[] out, int offset) {
        // X 軸回転
        double a = Math.toRadians(spec.pitch);
        double sin = Math.sin(a), cos = Math.cos(a);
        float ty = (float) (y * cos - z * sin);
        float tz = (float) (y * sin + z * cos);
        y = ty;
        z = tz;

        // Y 軸回転
        a = Math.toRadians(-spec.yaw);
        sin = Math.sin(a);
        cos = Math.cos(a);
        float tx = (float) (x * cos + z * sin);
        tz = (float) (-x * sin + z * cos);
        x = tx;
        z = tz;

        // Z 軸回転
        a = Math.toRadians(-spec.roll);
        sin = Math.sin(a);
        cos = Math.cos(a);
        tx = (float) (x * cos - y * sin);
        ty = (float) (x * sin + y * cos);
        x = tx;
        y = ty;

        out[offset] = x + spec.x * 2;
        out[offset + 1] = y + spec.y * 2;
        out[offset + 2] = z + spec.z * 2;
    }

    // モデルの座標を、看板の向きに回転する前の座標にする
    private void local(float x, float y, float z, float[] out) {
        // モデルの正面 (+Z) が看板の正面を向くように、縦の軸で半回転させる
        rotate(-(x - modelCenterX) * modelFit * spec.scaleX,
                (y - modelCenterY) * modelFit * spec.scaleY,
                -(z - modelCenterZ) * modelFit * spec.scaleX, out, 0);
    }

    // モデルの座標を、ブロックの角を原点とした座標にする
    private void place(float x, float y, float z, float signYaw, boolean wall, float[] out) {
        local(x, y, z, out);
        // 壁掛け看板では、モデルの背面が壁に付くようにする
        final float moveZ = wall ? WALL_OFFSET - modelDepth : 0f;
        final double angle = Math.toRadians(-signYaw - 180);
        final float sin = (float) Math.sin(angle);
        final float cos = (float) Math.cos(angle);
        final float px = out[0], pz = out[2] + moveZ;
        out[0] = (px * cos + pz * sin) * 0.5f + 0.5f;
        out[1] = out[1] * 0.5f + 0.5f;
        out[2] = (-px * sin + pz * cos) * 0.5f + 0.5f;
    }

    private static int meshIndex(float signYaw, boolean wall) {
        if (wall) return 16 + (Math.round(signYaw / 90f) & 3);
        return Math.round(signYaw / 22.5f) & 15;
    }

    public Batch[] batches(float signYaw, boolean wall, double distance) {
        return batches(signYaw, wall, distance, 0.5f, 0.5f, 4.5f);
    }

    /**
     * 描画する四角形を返す。描画のたびに呼ぶ。
     *
     * @param signYaw  看板の向き (度)。立て看板は rotation * 22.5、壁掛け看板は向いている方角の角度
     * @param wall     壁掛け看板かどうか
     * @param distance カメラから看板までの距離 (ブロック)。動画や音声の音量に使う
     * @param cameraX  ブロックの角を原点としたカメラの X 座標
     * @param cameraY  ブロックの角を原点としたカメラの Y 座標
     * @param cameraZ  ブロックの角を原点としたカメラの Z 座標
     */
    public Batch[] batches(float signYaw, boolean wall, double distance, float cameraX, float cameraY, float cameraZ) {
        return batches(signYaw, wall, distance, cameraX, cameraY, cameraZ, 0, 0, 0, false);
    }

    /**
     * 描画する四角形を返す。描画のたびに呼ぶ。
     * 看板の位置も渡すと、PDF の看板に照準が合っているときに、ページ送りの帯も返す。
     *
     * @param signYaw  看板の向き (度)。立て看板は rotation * 22.5、壁掛け看板は向いている方角の角度
     * @param wall     壁掛け看板かどうか
     * @param distance カメラから看板までの距離 (ブロック)。動画や音声の音量に使う
     * @param cameraX  ブロックの角を原点としたカメラの X 座標。cameraY, cameraZ も同じ
     * @param blockX   看板のブロックの位置 (ワールド座標)。blockY, blockZ も同じ
     */
    public Batch[] batches(float signYaw, boolean wall, double distance, float cameraX, float cameraY, float cameraZ,
                           int blockX, int blockY, int blockZ) {
        return batches(signYaw, wall, distance, cameraX, cameraY, cameraZ, blockX, blockY, blockZ, true);
    }

    private Batch[] batches(float signYaw, boolean wall, double distance, float cameraX, float cameraY, float cameraZ,
                            int blockX, int blockY, int blockZ, boolean placed) {
        final Batch[] batches = imageBatches(signYaw, wall, distance, cameraX, cameraY, cameraZ);
        final PdfSession pdf = texture.pdf();
        if (!placed || pdf == null || controls == null || model != null || splat != null) return batches;
        final Batch bar = controls.overlay(pdf, mesh(signYaw, wall), blockX, blockY, blockZ);
        if (bar == null) return batches;
        final Batch[] withBar = Arrays.copyOf(batches, batches.length + 1);
        withBar[batches.length] = bar;
        return withBar;
    }

    private Batch[] imageBatches(float signYaw, boolean wall, double distance, float cameraX, float cameraY, float cameraZ) {
        ensureBuilt();
        final int index = meshIndex(signYaw, wall);
        if (model != null) return modelBatches(index, signYaw, wall);
        if (splat != null) return splatBatches(index, signYaw, wall, distance, cameraX, cameraY, cameraZ);

        Batch[] batches = imageBatches[index];
        if (batches == null) {
            final float[] mesh = mesh(signYaw, wall);
            final Batch batch = new Batch();
            batch.vertexCount = VERTEX_COUNT;
            batch.vertices = new float[VERTEX_COUNT * Batch.STRIDE];
            for (int i = 0; i < VERTEX_COUNT; i++) {
                System.arraycopy(mesh, i * STRIDE, batch.vertices, i * Batch.STRIDE, Batch.STRIDE);
            }
            batches = new Batch[]{batch};
            imageBatches[index] = batches;
        }
        batches[0].texture = texture.current(distance);
        return batches;
    }

    /**
     * アイテムとして描くときの四角形を返す。描画のたびに呼ぶ。
     * 一辺が 1 の立方体 (0 - 1) の中央に収まる大きさで、正面は +Z を向く。
     * 看板に書かれた位置・大きさ・回転は使わない。
     *
     * @param distance カメラからアイテムまでの距離 (ブロック)。動画や音声の音量に使う
     */
    public Batch[] itemBatches(double distance) {
        ensureBuilt();
        if (model != null) return modelBatches(ITEM, 0, false);
        if (splat != null) return splatBatches(ITEM, 0, false, distance, 0.5f, 0.5f, 100f);

        Batch[] batches = imageBatches[ITEM];
        if (batches == null) {
            float max = Math.max(texture.width(), texture.height());
            if (max <= 0) max = 1;
            final float x = texture.width() / max * 0.5f, y = texture.height() / max * 0.5f;
            final Batch batch = new Batch();
            batch.vertexCount = VERTEX_COUNT;
            batch.vertices = new float[]{
                    // 表 (+Z 側から見える)
                    0.5f - x, 0.5f - y, 0.5f, 0, 1,
                    0.5f + x, 0.5f - y, 0.5f, 1, 1,
                    0.5f + x, 0.5f + y, 0.5f, 1, 0,
                    0.5f - x, 0.5f + y, 0.5f, 0, 0,
                    // 裏
                    0.5f - x, 0.5f - y, 0.5f, 0, 1,
                    0.5f - x, 0.5f + y, 0.5f, 0, 0,
                    0.5f + x, 0.5f + y, 0.5f, 1, 0,
                    0.5f + x, 0.5f - y, 0.5f, 1, 1,
            };
            batches = new Batch[]{batch};
            imageBatches[ITEM] = batches;
        }
        batches[0].texture = texture.current(distance);
        return batches;
    }

    private void computePlacement(int index, float signYaw, boolean wall, float[] placement) {
        if (index == ITEM) {
            // 立方体の中央に、収まる大きさで置く
            final float size = modelFit / 2;
            placement[0] = placement[5] = placement[10] = size;
            placement[12] = 0.5f - modelCenterX * size;
            placement[13] = 0.5f - modelCenterY * size;
            placement[14] = 0.5f - modelCenterZ * size;
            placement[15] = 1;
        } else {
            // 変換は平行移動と線形変換の組み合わせなので、原点と各軸の行き先から行列を作る
            final float[] origin = new float[3];
            place(0, 0, 0, signYaw, wall, origin);
            for (int axis = 0; axis < 3; axis++) {
                place(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0, signYaw, wall, point);
                placement[axis * 4] = point[0] - origin[0];
                placement[axis * 4 + 1] = point[1] - origin[1];
                placement[axis * 4 + 2] = point[2] - origin[2];
            }
            placement[12] = origin[0];
            placement[13] = origin[1];
            placement[14] = origin[2];
            placement[15] = 1;
        }
    }

    private Batch[] modelBatches(int index, float signYaw, boolean wall) {
        ModelCache cache = modelCaches[index];
        if (cache == null) {
            cache = new ModelCache();
            computePlacement(index, signYaw, wall, cache.placement);

            final GltfScene.Group[] groups = model.groups();
            cache.vertices = new float[groups.length][];
            cache.colors = new int[groups.length][];
            cache.batches = new Batch[groups.length];
            for (int i = 0; i < groups.length; i++) {
                final Batch batch = new Batch();
                batch.vertexCount = groups[i].vertexCount();
                batch.vertices = cache.vertices[i] = new float[batch.vertexCount * Batch.STRIDE];
                batch.colors = cache.colors[i] = new int[batch.vertexCount];
                batch.texture = texture.modelTexture(groups[i].texture);
                batch.blend = groups[i].blend;
                cache.batches[i] = batch;
            }
            modelCaches[index] = cache;
        }

        // 動かないモデルは 1 回だけ計算する
        final int frame = model.isAnimated() ? (int) (texture.modelTime() * MODEL_FRAME_RATE) : 0;
        if (cache.frame != frame) {
            model.pose(frame / MODEL_FRAME_RATE);
            model.emit(cache.placement, cache.vertices, cache.colors);
            cache.frame = frame;
            for (Batch batch : cache.batches) batch.revision++;
        }
        return cache.batches;
    }

    private Batch[] splatBatches(int index, float signYaw, boolean wall, double distance,
                                 float cameraX, float cameraY, float cameraZ) {
        SplatScene.View view = splatViews[index];
        if (view == null) {
            final float[] placement = new float[16];
            computePlacement(index, signYaw, wall, placement);
            view = splat.createView(placement, texture.current(distance));
            splatViews[index] = view;
        }
        return view.batches(cameraX, cameraY, cameraZ, texture.current(distance));
    }

    /**
     * ブロックの角を原点とした座標での、画像の頂点列を返す。
     * 表 4 頂点、裏 4 頂点の順で、1 頂点は x, y, z, u, v, nx, ny, nz。
     *
     * @param signYaw 看板の向き (度)。立て看板は rotation * 22.5、壁掛け看板は向いている方角の角度
     * @param wall    壁掛け看板かどうか
     */
    public float[] mesh(float signYaw, boolean wall) {
        ensureBuilt();
        final int index = meshIndex(signYaw, wall);
        float[] mesh = meshes[index];
        if (mesh == null) {
            mesh = buildMesh(signYaw, wall);
            meshes[index] = mesh;
        }
        return mesh;
    }

    /**
     * ブロックの角を原点とした、画像や 3D モデルを囲む直方体。
     * minX, minY, minZ, maxX, maxY, maxZ の順。
     */
    public float[] bounds(float signYaw, boolean wall) {
        ensureBuilt();
        final int index = meshIndex(signYaw, wall);
        float[] box = bounds[index];
        if (box != null) return box;

        box = new float[]{
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
        };
        if (model == null && splat == null) {
            final float[] mesh = mesh(signYaw, wall);
            for (int i = 0; i < VERTEX_COUNT; i++) {
                extend(box, mesh[i * STRIDE], mesh[i * STRIDE + 1], mesh[i * STRIDE + 2]);
            }
        } else {
            final float[] rest = model != null ? model.restBounds() : splat.restBounds();
            for (int corner = 0; corner < 8; corner++) {
                place(rest[(corner & 1) * 3], rest[1 + ((corner >> 1) & 1) * 3], rest[2 + ((corner >> 2) & 1) * 3],
                        signYaw, wall, point);
                extend(box, point[0], point[1], point[2]);
            }
            if ((model != null && model.isAnimated()) || splat != null) {
                // 動きや外れ値で直方体からはみ出す分の余裕を持たせる
                for (int axis = 0; axis < 3; axis++) {
                    final float margin = (box[axis + 3] - box[axis]) / 2;
                    box[axis] -= margin;
                    box[axis + 3] += margin;
                }
            }
        }
        bounds[index] = box;
        return box;
    }

    private static void extend(float[] box, float x, float y, float z) {
        if (x < box[0]) box[0] = x;
        if (y < box[1]) box[1] = y;
        if (z < box[2]) box[2] = z;
        if (x > box[3]) box[3] = x;
        if (y > box[4]) box[4] = y;
        if (z > box[5]) box[5] = z;
    }

    // 頂点の並び順: 表は 0,1,2,3、裏は 3,2,1,0
    private static final int[] ORDER = {0, 1, 2, 3, 3, 2, 1, 0};
    private static final float[] U = {1, 1, 0, 0};
    private static final float[] V = {1, 0, 0, 1};

    private float[] buildMesh(float signYaw, boolean wall) {
        final float moveZ = wall ? WALL_OFFSET : 0f;
        final double angle = Math.toRadians(-signYaw - 180);
        final float sin = (float) Math.sin(angle);
        final float cos = (float) Math.cos(angle);

        final float[] mesh = new float[VERTEX_COUNT * STRIDE];
        for (int i = 0; i < VERTEX_COUNT; i++) {
            final int v = ORDER[i];
            final boolean front = i < 4;
            final float x = base[v * 3];
            final float y = base[v * 3 + 1];
            final float z = base[v * 3 + 2] + moveZ + (front ? FACE_OFFSET : -FACE_OFFSET);
            final float nz = front ? 1 : -1;

            final int o = i * STRIDE;
            // 看板の向きに回転し、ブロックの中心に合わせる
            mesh[o] = (x * cos + z * sin) * 0.5f + 0.5f;
            mesh[o + 1] = y * 0.5f + 0.5f;
            mesh[o + 2] = (-x * sin + z * cos) * 0.5f + 0.5f;
            mesh[o + 3] = U[v];
            mesh[o + 4] = V[v];
            mesh[o + 5] = nz * sin;
            mesh[o + 6] = 0;
            mesh[o + 7] = nz * cos;
        }
        return mesh;
    }
}
