package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.sign.SignImage;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 読み込んだガウシアンスプラッティングのデータを、カメラの向きに応じた四角形の列にする。
 * 各ガウス分布は 3 次元の共分散をカメラに正対する平面へ射影し、2 次元の楕円に外接する四角形として出力する。
 */
public final class SplatScene {

    // 共通で使うガウス分布のテクスチャの一辺の長さ
    public static final int TEXTURE_SIZE = 64;

    // 楕円の広がりを打ち切る倍率 (標準偏差の何倍まで四角形を広げるか)
    private static final float CUTOFF = 2.5f;
    // 極端に小さい・細長いガウスで数値が破綻しないための標準偏差の下限 (ブロック単位)
    private static final float MIN_SIGMA = 5e-4f;

    // 並べ替えをやり直すカメラの向きの変化量 (約 1.5 度) と距離の変化割合 (3%)
    private static final float COS_ANGLE_THRESHOLD = (float) Math.cos(Math.toRadians(1.5));
    private static final float DIST_RATIO_THRESHOLD = 0.03f;

    // 球面調和関数の 1 次〜3 次の定数
    private static final float SH_C1 = 0.4886025119029199f;
    private static final float SH_C2_0 = 1.0925484305920792f;
    private static final float SH_C2_1 = -1.0925484305920792f;
    private static final float SH_C2_2 = 0.31539156525252005f;
    private static final float SH_C2_3 = -1.0925484305920792f;
    private static final float SH_C2_4 = 0.5462742152960396f;
    private static final float SH_C3_0 = -0.5900435899266435f;
    private static final float SH_C3_1 = 2.890611442640554f;
    private static final float SH_C3_2 = -0.4570457994644658f;
    private static final float SH_C3_3 = 0.3731763325901154f;
    private static final float SH_C3_4 = -0.4570457994644658f;
    private static final float SH_C3_5 = 1.445305721320277f;
    private static final float SH_C3_6 = -0.5900435899266435f;

    private static final int[] GAUSSIAN_TEXTURE = buildGaussianTexture();

    // バックグラウンドで並べ替えと頂点生成を行うスレッド
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private int count;

        @Override
        public synchronized Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "MarumaSign-Splat-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    });

    // スレッドごとに再利用する作業用配列
    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private static final class Scratch {
        int[] keys = new int[0];
        int[] order = new int[0];
        final int[] counts = new int[65536];
        float[] vertices = new float[0];
        int[] colors = new int[0];

        void ensureCapacity(int splatCount) {
            if (keys.length < splatCount) {
                keys = new int[splatCount];
                order = new int[splatCount];
            }
            final int vertexCount = splatCount * 4;
            if (colors.length < vertexCount) {
                vertices = new float[vertexCount * SignImage.Batch.STRIDE];
                colors = new int[vertexCount];
                fillUvs(vertices, splatCount);
            }
        }
    }

    // ガウス 1 つあたり 9 要素: px, py, pz, c00, c01, c02, c11, c12, c22
    private static final int DATA_STRIDE = 9;

    private final SplatCloud cloud;
    private final float[] restBounds;
    private final float[] splatData;
    private final int[] baseArgb;

    public SplatScene(SplatCloud cloud) {
        this.cloud = cloud;
        final int count = cloud.count;
        this.restBounds = computePercentileBounds(cloud.positions, count);
        this.splatData = new float[count * DATA_STRIDE];
        this.baseArgb = new int[count];

        final float[] positions = cloud.positions;
        final float[] rotations = cloud.rotations;
        final float[] scales = cloud.scales;
        final float[] colors = cloud.colors;
        final float[] opacities = cloud.opacities;

        for (int i = 0; i < count; i++) {
            final int po = i * 3;
            final int ro = i * 4;
            final int doff = i * DATA_STRIDE;

            splatData[doff] = positions[po];
            splatData[doff + 1] = positions[po + 1];
            splatData[doff + 2] = positions[po + 2];

            float qx = rotations[ro], qy = rotations[ro + 1], qz = rotations[ro + 2], qw = rotations[ro + 3];
            final float qLen = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (qLen > 0f) {
                qx /= qLen;
                qy /= qLen;
                qz /= qLen;
                qw /= qLen;
            } else {
                qw = 1f;
            }

            final float xx = qx * qx, yy = qy * qy, zz = qz * qz;
            final float xy = qx * qy, xz = qx * qz, yz = qy * qz;
            final float wx = qw * qx, wy = qw * qy, wz = qw * qz;

            final float sx = scales[po], sy = scales[po + 1], sz = scales[po + 2];
            // M = R * S の各列
            final float m00 = (1f - 2f * (yy + zz)) * sx;
            final float m10 = (2f * (xy + wz)) * sx;
            final float m20 = (2f * (xz - wy)) * sx;

            final float m01 = (2f * (xy - wz)) * sy;
            final float m11 = (1f - 2f * (xx + zz)) * sy;
            final float m21 = (2f * (yz + wx)) * sy;

            final float m02 = (2f * (xz + wy)) * sz;
            final float m12 = (2f * (yz - wx)) * sz;
            final float m22 = (1f - 2f * (xx + yy)) * sz;

            // 3 次元の共分散行列 Sigma = M * M^T (対称行列の 6 成分)
            splatData[doff + 3] = m00 * m00 + m01 * m01 + m02 * m02;
            splatData[doff + 4] = m00 * m10 + m01 * m11 + m02 * m12;
            splatData[doff + 5] = m00 * m20 + m01 * m21 + m02 * m22;
            splatData[doff + 6] = m10 * m10 + m11 * m11 + m12 * m12;
            splatData[doff + 7] = m10 * m20 + m11 * m21 + m12 * m22;
            splatData[doff + 8] = m20 * m20 + m21 * m21 + m22 * m22;

            final int a = channel(opacities[i]);
            final int r = channel(colors[po]);
            final int g = channel(colors[po + 1]);
            final int b = channel(colors[po + 2]);
            baseArgb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static int[] buildGaussianTexture() {
        final int[] pixels = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        final float half = TEXTURE_SIZE * 0.5f;
        final float factor = -0.5f * CUTOFF * CUTOFF;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            final float v = (y + 0.5f - half) / half;
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                if (x == 0 || x == TEXTURE_SIZE - 1 || y == 0 || y == TEXTURE_SIZE - 1) {
                    pixels[y * TEXTURE_SIZE + x] = 0x00FFFFFF;
                    continue;
                }
                final float u = (x + 0.5f - half) / half;
                final float r2 = u * u + v * v;
                if (r2 >= 1f) {
                    pixels[y * TEXTURE_SIZE + x] = 0x00FFFFFF;
                    continue;
                }
                final float g = (float) Math.exp(factor * r2);
                final int a = Math.max(0, Math.min(255, Math.round(g * 255f)));
                pixels[y * TEXTURE_SIZE + x] = (a << 24) | 0x00FFFFFF;
            }
        }
        return pixels;
    }

    public static int[] gaussianTexture() {
        return GAUSSIAN_TEXTURE.clone();
    }

    // 外れ値で全体が極端に小さくならないよう、各軸の 1〜99 パーセンタイルを代表の直方体とする
    private static float[] computePercentileBounds(float[] positions, int count) {
        if (count <= 0) return new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
        final float[] xs = new float[count];
        final float[] ys = new float[count];
        final float[] zs = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = positions[i * 3];
            ys[i] = positions[i * 3 + 1];
            zs[i] = positions[i * 3 + 2];
        }
        Arrays.sort(xs);
        Arrays.sort(ys);
        Arrays.sort(zs);
        final int lo = (int) Math.floor((count - 1) * 0.01);
        final int hi = (int) Math.ceil((count - 1) * 0.99);
        return new float[]{xs[lo], ys[lo], zs[lo], xs[hi], ys[hi], zs[hi]};
    }

    static void fillUvs(float[] vertices, int splatCount) {
        for (int i = 0; i < splatCount; i++) {
            final int o = i * 4 * SignImage.Batch.STRIDE;
            vertices[o + 3] = 0f;
            vertices[o + 4] = 1f;
            vertices[o + 8] = 1f;
            vertices[o + 9] = 1f;
            vertices[o + 13] = 1f;
            vertices[o + 14] = 0f;
            vertices[o + 18] = 0f;
            vertices[o + 19] = 0f;
        }
    }

    public SplatCloud cloud() {
        return cloud;
    }

    /**
     * 外れ値を除いたモデルの範囲 (minX, minY, minZ, maxX, maxY, maxZ)。
     */
    public float[] restBounds() {
        return restBounds.clone();
    }

    /**
     * 看板の向きごとの描画状態を管理する。
     */
    public View createView(float[] placement, Object texture) {
        return new View(this, placement, texture);
    }

    public static final class View {
        private final SplatScene scene;
        private final float[] placement;
        private final float centerX, centerY, centerZ;
        private final SignImage.Batch batchA;
        private final SignImage.Batch batchB;
        private final SignImage.Batch[] batchesA;
        private final SignImage.Batch[] batchesB;
        private volatile SignImage.Batch[] frontBatches;

        private float lastNx, lastNy, lastNz;
        private float lastDist = -1f;
        private final AtomicBoolean updating = new AtomicBoolean(false);

        View(SplatScene scene, float[] placement, Object texture) {
            this.scene = scene;
            this.placement = placement.clone();
            final float[] box = scene.restBounds;
            final float mx = (box[0] + box[3]) * 0.5f;
            final float my = (box[1] + box[4]) * 0.5f;
            final float mz = (box[2] + box[5]) * 0.5f;
            this.centerX = placement[0] * mx + placement[4] * my + placement[8] * mz + placement[12];
            this.centerY = placement[1] * mx + placement[5] * my + placement[9] * mz + placement[13];
            this.centerZ = placement[2] * mx + placement[6] * my + placement[10] * mz + placement[14];

            final int splatCount = scene.cloud.count;
            final int vertexCount = splatCount * 4;

            this.batchA = new SignImage.Batch();
            this.batchA.texture = texture;
            this.batchA.vertexCount = vertexCount;
            this.batchA.vertices = new float[vertexCount * SignImage.Batch.STRIDE];
            this.batchA.colors = new int[vertexCount];
            this.batchA.blend = true;
            fillUvs(this.batchA.vertices, splatCount);
            this.batchesA = new SignImage.Batch[]{this.batchA};

            this.batchB = new SignImage.Batch();
            this.batchB.texture = texture;
            this.batchB.vertexCount = vertexCount;
            this.batchB.vertices = new float[vertexCount * SignImage.Batch.STRIDE];
            this.batchB.colors = new int[vertexCount];
            this.batchB.blend = true;
            fillUvs(this.batchB.vertices, splatCount);
            this.batchesB = new SignImage.Batch[]{this.batchB};

            this.frontBatches = this.batchesA;
        }

        public SignImage.Batch[] batches(final float cameraX, final float cameraY, final float cameraZ, Object texture) {
            final SignImage.Batch[] currentFront = this.frontBatches;
            currentFront[0].texture = texture;
            if (scene.cloud.count == 0) return currentFront;

            float dx = cameraX - centerX;
            float dy = cameraY - centerY;
            float dz = cameraZ - centerZ;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            final float nx, ny, nz;
            if (dist > 1e-5f) {
                nx = dx / dist;
                ny = dy / dist;
                nz = dz / dist;
            } else {
                nx = 0f;
                ny = 0f;
                nz = 1f;
                dist = 1f;
            }

            // 初回はその場で計算してすぐに表示できるようにする
            if (lastDist < 0f) {
                lastNx = nx;
                lastNy = ny;
                lastNz = nz;
                lastDist = dist;
                final Scratch scratch = SCRATCH.get();
                scratch.ensureCapacity(scene.cloud.count);
                scene.rebuild(placement, cameraX, cameraY, cameraZ, nx, ny, nz,
                        batchA.vertices, batchA.colors, scratch.keys, scratch.order, scratch.counts);
                batchA.revision++;
                return currentFront;
            }

            final float dot = nx * lastNx + ny * lastNy + nz * lastNz;
            final float distDiff = Math.abs(dist - lastDist);
            if (dot >= COS_ANGLE_THRESHOLD && distDiff <= lastDist * DIST_RATIO_THRESHOLD) {
                return currentFront;
            }

            if (updating.compareAndSet(false, true)) {
                lastNx = nx;
                lastNy = ny;
                lastNz = nz;
                lastDist = dist;

                // ダブルバッファリングにより描画スレッドとの競合を排除する。
                // 描画スレッドには現在公開中の currentFront を返し、バックグラウンドスレッドは
                // 非公開側の backBatch に対して頂点と色の書き込みを行う。
                // 新たな計算タスクが開始されるのは必ず描画スレッドが batches() を呼び出した時点であり、
                // その時点の frontBatches が描画スレッドに渡され、書き込み先はもう一方 (非公開側) となる。
                // したがって、公開された直後の Batch に対して裏で書き込みが行われることは構造上発生せず、
                // synchronized による排他制御を行わずに安全に頂点データを更新できる。
                // なお、古いバージョン (1.7〜1.13) の ModelArrays では WeakHashMap<Batch, Buffers> と revision で
                // Batch ごとに頂点バッファをキャッシュしているため、2 つの Batch が交互に公開されても
                // revision に応じて安全かつ独立にバッファが更新される。
                final SignImage.Batch backBatch = (currentFront == batchesA) ? batchB : batchA;
                final SignImage.Batch[] nextBatches = (currentFront == batchesA) ? batchesB : batchesA;
                final Object currentTexture = texture;

                EXECUTOR.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final int splatCount = scene.cloud.count;
                            final Scratch scratch = SCRATCH.get();
                            scratch.ensureCapacity(splatCount);
                            scene.rebuild(placement, cameraX, cameraY, cameraZ, nx, ny, nz,
                                    scratch.vertices, scratch.colors, scratch.keys, scratch.order, scratch.counts);
                            final int vLen = splatCount * 4 * SignImage.Batch.STRIDE;
                            final int cLen = splatCount * 4;
                            System.arraycopy(scratch.vertices, 0, backBatch.vertices, 0, vLen);
                            System.arraycopy(scratch.colors, 0, backBatch.colors, 0, cLen);
                            backBatch.texture = currentTexture;
                            backBatch.revision++;
                            frontBatches = nextBatches;
                        } finally {
                            updating.set(false);
                        }
                    }
                });
            }
            return currentFront;
        }

        // テスト用: バックグラウンドの更新が終わるのを待つ
        public void awaitIdle() {
            while (updating.get()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 指定された配置行列とカメラ位置に対して、奥から手前へ並べ替えた四角形頂点と頂点色を出力する。
     */
    public void emit(float[] placement, float cameraX, float cameraY, float cameraZ, float[] vertices, int[] colors) {
        final int count = cloud.count;
        if (count == 0) return;
        fillUvs(vertices, count);

        final float mx = (restBounds[0] + restBounds[3]) * 0.5f;
        final float my = (restBounds[1] + restBounds[4]) * 0.5f;
        final float mz = (restBounds[2] + restBounds[5]) * 0.5f;
        final float cx = placement[0] * mx + placement[4] * my + placement[8] * mz + placement[12];
        final float cy = placement[1] * mx + placement[5] * my + placement[9] * mz + placement[13];
        final float cz = placement[2] * mx + placement[6] * my + placement[10] * mz + placement[14];

        float dx = cameraX - cx, dy = cameraY - cy, dz = cameraZ - cz;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float nx, ny, nz;
        if (dist > 1e-5f) {
            nx = dx / dist;
            ny = dy / dist;
            nz = dz / dist;
        } else {
            nx = 0f;
            ny = 0f;
            nz = 1f;
        }

        final Scratch scratch = SCRATCH.get();
        scratch.ensureCapacity(count);
        rebuild(placement, cameraX, cameraY, cameraZ, nx, ny, nz,
                vertices, colors, scratch.keys, scratch.order, scratch.counts);
    }

    void rebuild(float[] p, float cameraX, float cameraY, float cameraZ,
                 float nx, float ny, float nz,
                 float[] outVertices, int[] outColors,
                 int[] keys, int[] order, int[] counts) {
        final int count = cloud.count;
        if (count == 0) return;

        // カメラに正対する平面の正規直交基底 (u: 右, v: 上, n: カメラ向き)
        float ux = nz, uy = 0f, uz = -nx;
        final float uLen = (float) Math.sqrt(ux * ux + uz * uz);
        if (uLen > 1e-4f) {
            ux /= uLen;
            uz /= uLen;
        } else {
            ux = 1f;
            uy = 0f;
            uz = 0f;
        }
        float vx = ny * uz - nz * uy;
        float vy = nz * ux - nx * uz;
        float vz = nx * uy - ny * ux;
        final float vLen = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vLen > 0f) {
            vx /= vLen;
            vy /= vLen;
            vz /= vLen;
        }

        final float p0 = p[0], p1 = p[1], p2 = p[2];
        final float p4 = p[4], p5 = p[5], p6 = p[6];
        final float p8 = p[8], p9 = p[9], p10 = p[10];
        final float p12 = p[12], p13 = p[13], p14 = p[14];

        // 基底ベクトルをモデル空間へ引き戻す (A^T * u, A^T * v, -A^T * n)
        final float tux = p0 * ux + p1 * uy + p2 * uz;
        final float tuy = p4 * ux + p5 * uy + p6 * uz;
        final float tuz = p8 * ux + p9 * uy + p10 * uz;

        final float tvx = p0 * vx + p1 * vy + p2 * vz;
        final float tvy = p4 * vx + p5 * vy + p6 * vz;
        final float tvz = p8 * vx + p9 * vy + p10 * vz;

        // 奥行き方向 w = -n (カメラから看板へ向かう向き)
        final float twx = -(p0 * nx + p1 * ny + p2 * nz);
        final float twy = -(p4 * nx + p5 * ny + p6 * nz);
        final float twz = -(p8 * nx + p9 * ny + p10 * nz);

        final float[] data = splatData;

        // 1. 奥行きを計算して最小・最大を求める (keys に float のビット列を一時保存する)
        float minZ = Float.POSITIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            final int o = i * DATA_STRIDE;
            final float z = twx * data[o] + twy * data[o + 1] + twz * data[o + 2];
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
            keys[i] = Float.floatToRawIntBits(z);
        }

        // 2. 16 ビットの度数ソート (1 パス) で奥 (maxZ) から手前 (minZ) の順に並べる
        Arrays.fill(counts, 0);
        final float range = maxZ - minZ;
        final float scale = range > 1e-6f ? 65535f / range : 0f;
        for (int i = 0; i < count; i++) {
            final float z = Float.intBitsToFloat(keys[i]);
            int bucket = (int) ((maxZ - z) * scale);
            if (bucket < 0) bucket = 0;
            else if (bucket > 65535) bucket = 65535;
            keys[i] = bucket;
            counts[bucket]++;
        }
        int sum = 0;
        for (int b = 0; b < 65536; b++) {
            final int c = counts[b];
            counts[b] = sum;
            sum += c;
        }
        for (int i = 0; i < count; i++) {
            order[counts[keys[i]]++] = i;
        }

        // 球面調和関数を使う場合は、カメラ位置をモデル座標系へ逆変換する
        final int shDegree = cloud.shDegree;
        final int shRest = shDegree > 0 ? SplatCloud.restCoefficients(shDegree) : 0;
        final float[] sh = cloud.shCoefficients;
        final boolean useSh = shDegree > 0 && sh != null && sh.length >= count * shRest * 3;
        float camMx = 0f, camMy = 0f, camMz = 1f;
        if (useSh) {
            final float c00 = p5 * p10 - p6 * p9;
            final float c01 = p2 * p9 - p1 * p10;
            final float c02 = p1 * p6 - p2 * p5;
            final float det = p0 * c00 + p4 * c01 + p8 * c02;
            if (Math.abs(det) > 1e-12f) {
                final float invDet = 1f / det;
                final float c10 = p6 * p8 - p4 * p10;
                final float c11 = p0 * p10 - p2 * p8;
                final float c12 = p2 * p4 - p0 * p6;
                final float c20 = p4 * p9 - p5 * p8;
                final float c21 = p1 * p8 - p0 * p9;
                final float c22 = p0 * p5 - p1 * p4;
                final float tx = cameraX - p12, ty = cameraY - p13, tz = cameraZ - p14;
                camMx = (c00 * tx + c01 * ty + c02 * tz) * invDet;
                camMy = (c10 * tx + c11 * ty + c12 * tz) * invDet;
                camMz = (c20 * tx + c21 * ty + c22 * tz) * invDet;
            }
        }

        final float[] baseColors = cloud.colors;
        final float[] opacities = cloud.opacities;
        final int[] precomputedArgb = baseArgb;

        // 3. 奥から手前の順に 2 次元共分散の固有値分解と頂点出力を行う
        for (int q = 0; q < count; q++) {
            final int i = order[q];
            final int o = i * DATA_STRIDE;

            final float px = data[o];
            final float py = data[o + 1];
            final float pz = data[o + 2];

            final float wx = p0 * px + p4 * py + p8 * pz + p12;
            final float wy = p1 * px + p5 * py + p9 * pz + p13;
            final float wz = p2 * px + p6 * py + p10 * pz + p14;

            final float s00 = data[o + 3];
            final float s01 = data[o + 4];
            final float s02 = data[o + 5];
            final float s11 = data[o + 6];
            final float s12 = data[o + 7];
            final float s22 = data[o + 8];

            // 2x2 共分散行列 [a, b; b, d] = [tux, tuy, tuz; tvx, tvy, tvz] * Sigma * [...]^T
            final float sux = s00 * tux + s01 * tuy + s02 * tuz;
            final float suy = s01 * tux + s11 * tuy + s12 * tuz;
            final float suz = s02 * tux + s12 * tuy + s22 * tuz;

            final float a = tux * sux + tuy * suy + tuz * suz;
            final float b = tvx * sux + tvy * suy + tvz * suz;
            final float d = tvx * (s00 * tvx + s01 * tvy + s02 * tvz)
                    + tvy * (s01 * tvx + s11 * tvy + s12 * tvz)
                    + tvz * (s02 * tvx + s12 * tvy + s22 * tvz);

            final float halfSum = 0.5f * (a + d);
            final float halfDiff = 0.5f * (a - d);
            final float disc = (float) Math.sqrt(Math.max(0f, halfDiff * halfDiff + b * b));
            final float lambda1 = halfSum + disc;
            final float lambda2 = halfSum - disc;

            final float sigma1 = Math.max(MIN_SIGMA, (float) Math.sqrt(Math.max(0f, lambda1)));
            final float sigma2 = Math.max(MIN_SIGMA, (float) Math.sqrt(Math.max(0f, lambda2)));
            final float r1 = CUTOFF * sigma1;
            final float r2 = CUTOFF * sigma2;

            float e1u, e1v;
            if (Math.abs(b) > 1e-12f) {
                final float ex, ey;
                if (halfDiff >= 0f) {
                    ex = halfDiff + disc;
                    ey = b;
                } else {
                    ex = b;
                    ey = -halfDiff + disc;
                }
                final float len = (float) Math.sqrt(ex * ex + ey * ey);
                if (len > 1e-20f) {
                    e1u = ex / len;
                    e1v = ey / len;
                } else {
                    e1u = 1f;
                    e1v = 0f;
                }
            } else if (a >= d) {
                e1u = 1f;
                e1v = 0f;
            } else {
                e1u = 0f;
                e1v = 1f;
            }
            final float e2u = -e1v;
            final float e2v = e1u;

            final float ax1 = r1 * (e1u * ux + e1v * vx);
            final float ay1 = r1 * (e1u * uy + e1v * vy);
            final float az1 = r1 * (e1u * uz + e1v * vz);

            final float ax2 = r2 * (e2u * ux + e2v * vx);
            final float ay2 = r2 * (e2u * uy + e2v * vy);
            final float az2 = r2 * (e2u * uz + e2v * vz);

            // カメラから見て反時計回りになる 4 頂点 (UV は fillUvs で書き込み済み)
            final int vo = q * 4 * SignImage.Batch.STRIDE;
            outVertices[vo] = wx - ax1 - ax2;
            outVertices[vo + 1] = wy - ay1 - ay2;
            outVertices[vo + 2] = wz - az1 - az2;

            outVertices[vo + 5] = wx + ax1 - ax2;
            outVertices[vo + 6] = wy + ay1 - ay2;
            outVertices[vo + 7] = wz + az1 - az2;

            outVertices[vo + 10] = wx + ax1 + ax2;
            outVertices[vo + 11] = wy + ay1 + ay2;
            outVertices[vo + 12] = wz + az1 + az2;

            outVertices[vo + 15] = wx - ax1 + ax2;
            outVertices[vo + 16] = wy - ay1 + ay2;
            outVertices[vo + 17] = wz - az1 + az2;

            final int argb;
            if (!useSh) {
                argb = precomputedArgb[i];
            } else {
                argb = evaluateShColor(i, px, py, pz, camMx, camMy, camMz,
                        baseColors, opacities, sh, shDegree, shRest);
            }

            final int co = q * 4;
            outColors[co] = argb;
            outColors[co + 1] = argb;
            outColors[co + 2] = argb;
            outColors[co + 3] = argb;
        }
    }

    private static int evaluateShColor(int i, float px, float py, float pz,
                                       float camMx, float camMy, float camMz,
                                       float[] baseColors, float[] opacities,
                                       float[] sh, int shDegree, int shRest) {
        final int co = i * 3;
        float r = baseColors[co];
        float g = baseColors[co + 1];
        float b = baseColors[co + 2];

        float dx = px - camMx;
        float dy = py - camMy;
        float dz = pz - camMz;
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float x, y, z;
        if (len > 0f) {
            x = dx / len;
            y = dy / len;
            z = dz / len;
        } else {
            x = 0f;
            y = 0f;
            z = -1f;
        }

        final int base = i * shRest * 3;
        final float b0 = -SH_C1 * y;
        final float b1 = SH_C1 * z;
        final float b2 = -SH_C1 * x;
        r += b0 * sh[base] + b1 * sh[base + 3] + b2 * sh[base + 6];
        g += b0 * sh[base + 1] + b1 * sh[base + 4] + b2 * sh[base + 7];
        b += b0 * sh[base + 2] + b1 * sh[base + 5] + b2 * sh[base + 8];

        if (shDegree >= 2) {
            final float xx = x * x, yy = y * y, zz = z * z;
            final float xy = x * y, yz = y * z, xz = x * z;
            final float b3 = SH_C2_0 * xy;
            final float b4 = SH_C2_1 * yz;
            final float b5 = SH_C2_2 * (2f * zz - xx - yy);
            final float b6 = SH_C2_3 * xz;
            final float b7 = SH_C2_4 * (xx - yy);
            r += b3 * sh[base + 9] + b4 * sh[base + 12] + b5 * sh[base + 15] + b6 * sh[base + 18] + b7 * sh[base + 21];
            g += b3 * sh[base + 10] + b4 * sh[base + 13] + b5 * sh[base + 16] + b6 * sh[base + 19] + b7 * sh[base + 22];
            b += b3 * sh[base + 11] + b4 * sh[base + 14] + b5 * sh[base + 17] + b6 * sh[base + 20] + b7 * sh[base + 23];

            if (shDegree >= 3) {
                final float b8 = SH_C3_0 * y * (3f * xx - yy);
                final float b9 = SH_C3_1 * xy * z;
                final float b10 = SH_C3_2 * y * (4f * zz - xx - yy);
                final float b11 = SH_C3_3 * z * (2f * zz - 3f * xx - 3f * yy);
                final float b12 = SH_C3_4 * x * (4f * zz - xx - yy);
                final float b13 = SH_C3_5 * z * (xx - yy);
                final float b14 = SH_C3_6 * x * (xx - 3f * yy);
                r += b8 * sh[base + 24] + b9 * sh[base + 27] + b10 * sh[base + 30] + b11 * sh[base + 33]
                        + b12 * sh[base + 36] + b13 * sh[base + 39] + b14 * sh[base + 42];
                g += b8 * sh[base + 25] + b9 * sh[base + 28] + b10 * sh[base + 31] + b11 * sh[base + 34]
                        + b12 * sh[base + 37] + b13 * sh[base + 40] + b14 * sh[base + 43];
                b += b8 * sh[base + 26] + b9 * sh[base + 29] + b10 * sh[base + 32] + b11 * sh[base + 35]
                        + b12 * sh[base + 38] + b13 * sh[base + 41] + b14 * sh[base + 44];
            }
        }

        final int aCh = channel(opacities[i]);
        final int rCh = channel(r);
        final int gCh = channel(g);
        final int bCh = channel(b);
        return (aCh << 24) | (rCh << 16) | (gCh << 8) | bCh;
    }

    /**
     * 設定画面のプレビュー用に、斜め上から見た絵を描く。
     */
    public BufferedImage renderPreview(int size) {
        final int[] frame = new int[size * size];
        final float[] box = restBounds;
        final float cx = (box[0] + box[3]) * 0.5f;
        final float cy = (box[1] + box[4]) * 0.5f;
        final float cz = (box[2] + box[5]) * 0.5f;
        final float extent = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        final float fit = extent > 0f ? 1.1f / extent : 1f;

        final double yaw = Math.toRadians(30), pitch = Math.toRadians(20);
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

        final int vertexCount = cloud.count * 4;
        final float[] vertices = new float[vertexCount * SignImage.Batch.STRIDE];
        final int[] colors = new int[vertexCount];
        emit(placement, 0f, 0f, 10f, vertices, colors);

        final float[] depth = new float[size * size];
        Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        marumasa.marumasa_sign.core.gltf.GltfPreview.draw(
                frame, depth, size, vertices, colors, vertexCount,
                TEXTURE_SIZE, TEXTURE_SIZE, GAUSSIAN_TEXTURE, true);

        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, frame, 0, size);
        return image;
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
