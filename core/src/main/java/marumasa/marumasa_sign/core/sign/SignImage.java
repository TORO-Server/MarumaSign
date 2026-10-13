package marumasa.marumasa_sign.core.sign;

/**
 * 看板 1 種類 (文字列 1 つ) に対応する、表示する画像と形。
 * 描画に必要な座標計算はすべてここで行い、各バージョンの実装は頂点を並べるだけにする。
 */
public final class SignImage {

    // mesh() の 1 頂点あたりの要素数: x, y, z, u, v, nx, ny, nz
    public static final int STRIDE = 8;
    // 表と裏で 4 頂点ずつ
    public static final int VERTEX_COUNT = 8;

    private static final float WALL_OFFSET = 0.4375f * 2;
    private static final float FACE_OFFSET = 0.005f;

    public final SignSpec spec;
    private final TextureEntry texture;

    private int builtRevision = -1;
    // 看板の向きに回転する前の 4 頂点 (x, y, z)
    private final float[] base = new float[12];
    private double viewDistance;
    // 看板の向きごとのメッシュ。添字は 立て看板: 0-15, 壁掛け看板: 16-19
    private final float[][] meshes = new float[20][];
    private final float[][] bounds = new float[20][];

    SignImage(SignSpec spec, TextureEntry texture) {
        this.spec = spec;
        this.texture = texture;
    }

    public TextureEntry textureEntry() {
        return texture;
    }

    // 現在表示するべきテクスチャ
    public Object texture() {
        return texture.current();
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

        final int width = texture.width();
        final int height = texture.height();
        float max = Math.max(width, height);
        if (max <= 0) max = 1;
        final float vx = width / max * spec.scaleX;
        final float vy = height / max * spec.scaleY;

        putVertex(0, -vx, -vy);
        putVertex(1, -vx, +vy);
        putVertex(2, +vx, +vy);
        putVertex(3, +vx, -vy);

        double maxDistSqr = 0;
        for (int i = 0; i < 4; i++) {
            final float x = base[i * 3], y = base[i * 3 + 1], z = base[i * 3 + 2];
            maxDistSqr = Math.max(maxDistSqr, x * x + y * y + z * z);
        }
        viewDistance = Math.min(512.0, 64.0 + Math.sqrt(maxDistSqr) * 2.0);

        for (int i = 0; i < meshes.length; i++) {
            meshes[i] = null;
            bounds[i] = null;
        }
        builtRevision = revision;
    }

    private void putVertex(int index, float x, float y) {
        float z = 0;

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

        base[index * 3] = x + spec.x * 2;
        base[index * 3 + 1] = y + spec.y * 2;
        base[index * 3 + 2] = z + spec.z * 2;
    }

    private static int meshIndex(float signYaw, boolean wall) {
        if (wall) return 16 + (Math.round(signYaw / 90f) & 3);
        return Math.round(signYaw / 22.5f) & 15;
    }

    /**
     * ブロックの角を原点とした座標での頂点列を返す。
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
     * ブロックの角を原点とした、画像を囲む直方体。
     * minX, minY, minZ, maxX, maxY, maxZ の順。
     */
    public float[] bounds(float signYaw, boolean wall) {
        final float[] mesh = mesh(signYaw, wall);
        final int index = meshIndex(signYaw, wall);
        float[] box = bounds[index];
        if (box == null) {
            box = new float[]{
                    Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
            };
            for (int i = 0; i < VERTEX_COUNT; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    final float value = mesh[i * STRIDE + axis];
                    if (value < box[axis]) box[axis] = value;
                    if (value > box[axis + 3]) box[axis + 3] = value;
                }
            }
            bounds[index] = box;
        }
        return box;
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
