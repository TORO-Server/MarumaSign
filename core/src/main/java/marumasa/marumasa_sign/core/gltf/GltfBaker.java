package marumasa.marumasa_sign.core.gltf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 材質の細かい表現 (法線マップ、金属らしさと粗さのテクスチャ、発光のテクスチャなど) を、
 * 陰影を計算した 1 枚のテクスチャに焼き込む。
 * 描画はどのバージョンでも「テクスチャ 1 枚と頂点の色」だけで行うため、
 * ピクセル単位の計算が必要な表現は、読み込みの時点でテクスチャの各点について済ませておく。
 *
 * 左右対称のモデルなどでは、向きの違う面がテクスチャの同じ場所を使っている。
 * 陰影は面の向きで変わるので、そのような面は別の層に分け、層を横に並べたテクスチャにする。
 */
final class GltfBaker {

    // 画像と、その中の色の取り出し
    static final class Map {
        final int width, height;
        final int[] argb;
        // 使うテクスチャ座標。0 は焼き込み先と同じ座標、1 以上は Surface.extraUv の (番号 - 1) 番目
        final int slot;
        // 範囲の外の扱い。0: 端で止める、1: 繰り返す、2: 折り返しながら繰り返す
        final int wrapU, wrapV;

        Map(int width, int height, int[] argb, int slot, int wrapU, int wrapV) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.slot = slot;
            this.wrapU = wrapU;
            this.wrapV = wrapV;
        }

        // u, v の位置の色。周りの 4 点から、なめらかにつながるように求める
        int at(float u, float v) {
            final float x = position(u, width, wrapU), y = position(v, height, wrapV);
            final int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            final float tx = x - x0, ty = y - y0;
            final int left = index(x0, width, wrapU), right = index(x0 + 1, width, wrapU);
            final int top = index(y0, height, wrapV) * width, bottom = index(y0 + 1, height, wrapV) * width;
            final int a = argb[top + left], b = argb[top + right], c = argb[bottom + left], d = argb[bottom + right];
            if (a == b && a == c && a == d) return a;
            int result = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                final float upper = ((a >>> shift) & 0xFF) + (((b >>> shift) & 0xFF) - ((a >>> shift) & 0xFF)) * tx;
                final float lower = ((c >>> shift) & 0xFF) + (((d >>> shift) & 0xFF) - ((c >>> shift) & 0xFF)) * tx;
                result |= ((int) (upper + (lower - upper) * ty + 0.5f) & 0xFF) << shift;
            }
            return result;
        }

        // 座標を、点の中心を整数とする画像上の位置にする
        private static float position(float coordinate, int size, int wrap) {
            if (wrap != 0) {
                final float period = wrap == 2 ? 2f : 1f;
                coordinate -= (float) Math.floor(coordinate / period) * period;
                if (coordinate > 1f) coordinate = 2f - coordinate;
            }
            return coordinate * size - 0.5f;
        }

        private static int index(int position, int size, int wrap) {
            if (wrap == 1) return position < 0 ? position + size : position >= size ? position - size : position;
            return position < 0 ? 0 : position >= size ? size - 1 : position;
        }
    }

    // 焼き込む材質
    static final class Input {
        Map base, metallicRoughness, normal, occlusion, emissive;
        Map specular, specularColor;
        Map clearcoat, clearcoatRoughness, clearcoatNormal;
        Map sheenColor, sheenRoughness;
        Map iridescence, iridescenceThickness;
        Map anisotropy;
        Map transmission, thickness;
        Map diffuseTransmission, diffuseTransmissionColor;
        Map specularGlossiness;

        // リニアの値
        float[] baseFactor = {1, 1, 1, 1};
        float[] emissiveFactor = {0, 0, 0};
        float metallic = 1, roughness = 1;
        float normalScale = 1, occlusionStrength = 1;
        int alphaMode;
        float alphaCutoff = 0.5f;

        float[] f0 = {0.04f, 0.04f, 0.04f};
        float specularFactor = 1f;
        float[] specularColorFactor = {1, 1, 1};
        float clearcoatFactor = 0, clearcoatRoughnessFactor = 0;
        float clearcoatNormalScale = 1;
        float[] sheenColorFactor = {0, 0, 0};
        float sheenRoughnessFactor = 0;
        float iridescenceFactor = 0, iridescenceIor = 1.3f;
        float iridescenceThicknessMin = 100f, iridescenceThicknessMax = 400f;
        float anisotropyStrength = 0, anisotropyRotation = 0;
        float transmissionFactor = 0;
        float thicknessFactor = 0;
        float[] attenuationColor = {1, 1, 1};
        float attenuationDistance = Float.POSITIVE_INFINITY;
        float diffuseTransmissionFactor = 0;
        float[] diffuseTransmissionColorFactor = {1, 1, 1};

        boolean isSpecGloss;
        float[] specGlossSpecularFactor = {1, 1, 1};
        float specGlossGlossinessFactor = 1;

        GltfLight.LightInstance[] lights;
        int lightCount;
        float[] sphericalHarmonics;
    }

    // 焼き込む面。行列はモデルの座標への変換 (列優先の 4x4)
    static final class Surface {
        final GltfModel.Primitive primitive;
        final float[] matrix;
        // 焼き込み先とは別のテクスチャ座標 (三角形の角ごと)。使わなければ null
        final float[][] extraUv;
        // モデルの座標に変換した頂点
        float[] positions, normals, tangents;
        float sign;
        // 三角形ごとの、割り当てた層
        int[] layers;

        Surface(GltfModel.Primitive primitive, float[] matrix, float[][] extraUv) {
            this.primitive = primitive;
            this.matrix = matrix;
            this.extraUv = extraUv;
        }
    }

    static final class Result {
        // 層を横に並べたテクスチャ (ARGB)。幅は width * layers
        int[] pixels;
        int layers;
    }

    // 層の数の上限
    private static final int MAX_LAYERS = 4;
    // 三角形の中で、すでに別の向きの面が使っている点がこの割合を超えたら、次の層に回す
    private static final float MAX_OVERLAP = 0.1f;
    // テクスチャ上で面積のない三角形がこの割合を超えたら、焼き込みをやめる
    private static final float MAX_FLAT = 0.02f;
    // 面の継ぎ目に色がにじまないように、面の外側へ色を広げる回数
    private static final int DILATION = 6;

    // 1 つの層
    private static final class Layer {
        final int[] pixels;
        // 各点を最初に塗った面の法線。重なりの検出に使う
        final int[] owner;
        final boolean[] covered;
        // 各点の色を決める三角形 (すべての面を通した三角形の番号 + 1)。0 ならその点は塗らない
        final int[] writer;

        Layer(int size) {
            pixels = new int[size];
            owner = new int[size];
            covered = new boolean[size];
            writer = new int[size];
        }
    }

    // 各点の色を計算するスレッドの数 (呼び出したスレッドを含む)。ゲームの描画のために 1 つは空けておく
    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    private static final ExecutorService POOL = THREADS <= 1 ? null : Executors.newFixedThreadPool(THREADS - 1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "MarumaSign-Bake");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    });
    // 1 回にまとめて計算する点の数の目安
    private static final int CHUNK_PIXELS = 16384;

    private final Input input;
    private final int width, height;
    private final List<Layer> layers = new ArrayList<Layer>();
    // 塗る三角形を決めるときに使う、三角形の情報
    private final Triangle scratch = new Triangle();
    // scan の結果
    private int inside, overlap;

    // 三角形 1 枚の、テクスチャの上での位置と、面の向き
    private final class Triangle {
        Surface surface;
        // 三角形の最初の角の番号 (三角形の番号 * 3)
        int t;
        int a, b, c;
        float xa, ya, xb, yb, xc, yc, area;
        float fx, fy, fz;
        float faceTx, faceTy, faceTz, faceHand;
        // 準備した三角形の、すべての面を通した番号 + 1。まだなら 0
        int id;

        // 三角形を準備する。テクスチャの上で面積がないか、面の向きが決まらなければ false
        boolean setup(Surface surface, int triangle) {
            id = 0;
            this.surface = surface;
            final GltfModel.Primitive primitive = surface.primitive;
            final float[] positions = surface.positions;
            final float sign = surface.sign;
            t = triangle * 3;
            a = primitive.indices[t];
            b = primitive.indices[t + 1];
            c = primitive.indices[t + 2];
            final float[] uv = primitive.cornerUv;
            final float ua = uv[t * 2], va = uv[t * 2 + 1];
            final float ub = uv[t * 2 + 2], vb = uv[t * 2 + 3];
            final float uc = uv[t * 2 + 4], vc = uv[t * 2 + 5];

            // テクスチャ上での位置 (ピクセル)
            xa = ua * width;
            ya = va * height;
            xb = ub * width;
            yb = vb * height;
            xc = uc * width;
            yc = vc * height;
            area = (xb - xa) * (yc - ya) - (xc - xa) * (yb - ya);
            if (area == 0 || Float.isNaN(area)) return false;

            // 面の向き
            final float e1x = positions[b * 3] - positions[a * 3], e1y = positions[b * 3 + 1] - positions[a * 3 + 1],
                    e1z = positions[b * 3 + 2] - positions[a * 3 + 2];
            final float e2x = positions[c * 3] - positions[a * 3], e2y = positions[c * 3 + 1] - positions[a * 3 + 1],
                    e2z = positions[c * 3 + 2] - positions[a * 3 + 2];
            fx = (e1y * e2z - e1z * e2y) * sign;
            fy = (e1z * e2x - e1x * e2z) * sign;
            fz = (e1x * e2y - e1y * e2x) * sign;
            final float faceLength = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (faceLength == 0) return false;
            fx /= faceLength;
            fy /= faceLength;
            fz /= faceLength;

            // テクスチャの横方向 (u が増える向き) を表す、面に沿ったベクトル。法線マップの貼り方で決まる
            final float[] bumpUv = input.normal != null && input.normal.slot > 0 && surface.extraUv != null
                    ? surface.extraUv[input.normal.slot - 1] : uv;
            final float du1 = bumpUv[t * 2 + 2] - bumpUv[t * 2], dv1 = bumpUv[t * 2 + 3] - bumpUv[t * 2 + 1];
            final float du2 = bumpUv[t * 2 + 4] - bumpUv[t * 2], dv2 = bumpUv[t * 2 + 5] - bumpUv[t * 2 + 1];
            final float uvArea = du1 * dv2 - du2 * dv1;
            faceTx = 0;
            faceTy = 0;
            faceTz = 0;
            if (uvArea != 0) {
                faceTx = (e1x * dv2 - e2x * dv1) / uvArea;
                faceTy = (e1y * dv2 - e2y * dv1) / uvArea;
                faceTz = (e1z * dv2 - e2z * dv1) / uvArea;
            }
            // テクスチャの縦は下向きに増えるので、普通の貼り方では面積が負になる。そのとき画像の上が面の「上」になる
            faceHand = uvArea < 0 ? 1 : -1;
            return true;
        }
    }

    // 各点の色を計算するスレッドが、それぞれ持つ作業用の場所
    private final class Painter {
        final Triangle triangle = new Triangle();
        final GltfLight.Context ctx = new GltfLight.Context();
        final float[] shaded = new float[3];
    }

    private GltfBaker(Input input, int width, int height) {
        this.input = input;
        this.width = width;
        this.height = height;
    }

    /**
     * 焼き込んだテクスチャを返す。面のテクスチャ座標は変更しない。
     * どの層の画像を使うかは Surface.layers に入るので、成功したら apply で座標に反映する。
     *
     * @return 重なりが多すぎて焼き込めない場合は null
     */
    static Result bake(Input input, List<Surface> surfaces, int width, int height) {
        final GltfBaker baker = new GltfBaker(input, width, height);
        // 各面の最初の三角形の、すべての面を通した番号
        final int[] bases = new int[surfaces.size()];
        int base = 0;
        for (int i = 0; i < surfaces.size(); i++) {
            final Surface surface = surfaces.get(i);
            baker.prepare(surface);
            bases[i] = base;
            // ここでは、各点をどの三角形が塗るかだけを決める (重なりの判定は塗る順番で変わるので、順に行う)
            if (!baker.paint(surface, base)) return null;
            base += surface.layers.length;
        }
        if (baker.layers.isEmpty()) return null;

        final int count = baker.layers.size();
        final Result result = new Result();
        result.layers = count;
        result.pixels = new int[width * count * height];
        for (int i = 0; i < count; i++) {
            final Layer layer = baker.layers.get(i);
            // 各点の色は、塗る三角形が決まれば点ごとに独立に求まるので、まとめて並列に計算する
            baker.fill(layer, surfaces, bases);
            dilate(layer.pixels, layer.covered, width, height);
            for (int y = 0; y < height; y++) {
                System.arraycopy(layer.pixels, y * width, result.pixels, y * width * count + i * width, width);
            }
        }
        return result;
    }

    // 面のテクスチャ座標を、層を横に並べたテクスチャのものに直す
    static void apply(List<Surface> surfaces, int layerCount) {
        for (Surface surface : surfaces) {
            final float[] uv = surface.primitive.cornerUv;
            for (int triangle = 0; triangle < surface.layers.length; triangle++) {
                for (int corner = 0; corner < 3; corner++) {
                    final int index = (triangle * 3 + corner) * 2;
                    uv[index] = (uv[index] + surface.layers[triangle]) / layerCount;
                }
            }
        }
    }

    // 頂点をモデルの座標に変換する
    private void prepare(Surface surface) {
        final GltfModel.Primitive primitive = surface.primitive;
        final float[] m = surface.matrix;
        final float[] normalMatrix = new float[9];
        Mat4.normalMatrix(m, normalMatrix);
        final float sign = Mat4.determinant3(m) < 0 ? -1 : 1;
        final int count = primitive.vertexCount;

        surface.sign = sign;
        surface.positions = new float[count * 3];
        surface.normals = primitive.normals == null ? null : new float[count * 3];
        surface.tangents = primitive.tangents == null || surface.normals == null ? null : new float[count * 3];
        surface.layers = new int[primitive.indices.length / 3];
        for (int i = 0; i < count; i++) {
            final float px = primitive.positions[i * 3], py = primitive.positions[i * 3 + 1], pz = primitive.positions[i * 3 + 2];
            surface.positions[i * 3] = m[0] * px + m[4] * py + m[8] * pz + m[12];
            surface.positions[i * 3 + 1] = m[1] * px + m[5] * py + m[9] * pz + m[13];
            surface.positions[i * 3 + 2] = m[2] * px + m[6] * py + m[10] * pz + m[14];
            if (surface.normals != null) {
                final float nx = primitive.normals[i * 3], ny = primitive.normals[i * 3 + 1], nz = primitive.normals[i * 3 + 2];
                surface.normals[i * 3] = (normalMatrix[0] * nx + normalMatrix[3] * ny + normalMatrix[6] * nz) * sign;
                surface.normals[i * 3 + 1] = (normalMatrix[1] * nx + normalMatrix[4] * ny + normalMatrix[7] * nz) * sign;
                surface.normals[i * 3 + 2] = (normalMatrix[2] * nx + normalMatrix[5] * ny + normalMatrix[8] * nz) * sign;
                normalize(surface.normals, i * 3);
            }
            if (surface.tangents != null) {
                final float tx = primitive.tangents[i * 4], ty = primitive.tangents[i * 4 + 1], tz = primitive.tangents[i * 4 + 2];
                surface.tangents[i * 3] = m[0] * tx + m[4] * ty + m[8] * tz;
                surface.tangents[i * 3 + 1] = m[1] * tx + m[5] * ty + m[9] * tz;
                surface.tangents[i * 3 + 2] = m[2] * tx + m[6] * ty + m[10] * tz;
            }
        }
    }

    // 面の三角形を、重ならない層を選んで塗る。層が足りないか、テクスチャに焼き込めない面が多ければ false
    // base は、この面の最初の三角形の、すべての面を通した番号
    private boolean paint(Surface surface, int base) {
        final int triangles = surface.layers.length;
        final float[] uv = surface.primitive.cornerUv;
        int flat = 0;
        for (int triangle = 0; triangle < triangles; triangle++) {
            // テクスチャ上で面積のない三角形 (1 点や線に潰れている) は、塗る場所がない
            final int o = triangle * 6;
            final float area = (uv[o + 2] - uv[o]) * width * (uv[o + 5] - uv[o + 1]) * height
                    - (uv[o + 4] - uv[o]) * width * (uv[o + 3] - uv[o + 1]) * height;
            if (!(Math.abs(area) > 1e-6f)) {
                if (++flat > triangles * MAX_FLAT) return false;
                continue;
            }

            int chosen = -1;
            for (int index = 0; index < MAX_LAYERS; index++) {
                if (index == layers.size()) {
                    // まだ使っていない層なら、必ず空いている
                    chosen = index;
                    break;
                }
                raster(surface, triangle, layers.get(index), 0);
                if (overlap <= inside * MAX_OVERLAP) {
                    chosen = index;
                    break;
                }
            }
            if (chosen < 0) return false;
            if (chosen == layers.size()) layers.add(new Layer(width * height));
            surface.layers[triangle] = chosen;
            raster(surface, triangle, layers.get(chosen), base + triangle + 1);
        }
        return true;
    }

    // 層の各点の色を、その点を塗る三角形 (Layer.writer) から計算する。点ごとに独立なので、いくつかのスレッドで分けて行う
    private void fill(final Layer layer, final List<Surface> surfaces, final int[] bases) {
        final int rowsPerChunk = Math.max(1, CHUNK_PIXELS / Math.max(1, width));
        final int chunks = (height + rowsPerChunk - 1) / rowsPerChunk;
        final AtomicInteger next = new AtomicInteger();
        final Runnable work = new Runnable() {
            @Override
            public void run() {
                final Painter painter = new Painter();
                for (int chunk; (chunk = next.getAndIncrement()) < chunks; ) {
                    final int end = Math.min(height, (chunk + 1) * rowsPerChunk);
                    for (int y = chunk * rowsPerChunk; y < end; y++) {
                        for (int x = 0; x < width; x++) {
                            final int pixel = y * width + x;
                            final int id = layer.writer[pixel];
                            if (id == 0) continue;
                            final Triangle triangle = painter.triangle;
                            if (triangle.id != id) {
                                // 番号から面と三角形を探す (bases は昇順)
                                int s = Arrays.binarySearch(bases, id - 1);
                                if (s < 0) s = -s - 2;
                                // 空の面 (三角形がない) が続くと同じ番号が並ぶので、三角形のある最後の面にする
                                while (s + 1 < bases.length && bases[s + 1] == bases[s] && bases[s + 1] <= id - 1) s++;
                                triangle.setup(surfaces.get(s), id - 1 - bases[s]);
                                triangle.id = id;
                            }
                            layer.pixels[pixel] = color(painter, x, y);
                        }
                    }
                }
            }
        };
        final int helpers = POOL == null ? 0 : Math.min(THREADS - 1, chunks - 1);
        final List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < helpers; i++) futures.add(POOL.submit(work));
        // 呼び出したスレッドも計算に加わる
        work.run();
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while baking", e);
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException(cause);
            }
        }
    }

    /**
     * 三角形 1 枚を、テクスチャの上でたどる。
     * id が 0 なら、三角形の中の点の数 (inside) と、別の向きの面がすでに使っている点の数 (overlap) を数えるだけにする。
     * id が 0 でなければ、塗る点を覚え (Layer.writer に id を入れる)、色は fill であとから計算する。
     */
    private void raster(Surface surface, int triangle, Layer layer, int id) {
        inside = 0;
        overlap = 0;
        final boolean paint = id != 0;
        final Triangle tri = scratch;
        if (!tri.setup(surface, triangle)) return;
        final float[] normals = surface.normals;
        final int a = tri.a, b = tri.b, c = tri.c;
        final float xa = tri.xa, ya = tri.ya, xb = tri.xb, yb = tri.yb, xc = tri.xc, yc = tri.yc, area = tri.area;

        final int minX = Math.max(0, (int) Math.floor(Math.min(xa, Math.min(xb, xc)) - 0.5f));
        final int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(xa, Math.max(xb, xc)) - 0.5f));
        final int minY = Math.max(0, (int) Math.floor(Math.min(ya, Math.min(yb, yc)) - 0.5f));
        final int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(ya, Math.max(yb, yc)) - 0.5f));
        // 辺の上の点を取りこぼさないための余裕
        final float tolerance = 0.5f / Math.max(1f, Math.abs(area)) * (Math.abs(xb - xa) + Math.abs(xc - xa)
                + Math.abs(yb - ya) + Math.abs(yc - ya));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                final float px = x + 0.5f, py = y + 0.5f;
                final float w0 = ((xb - px) * (yc - py) - (xc - px) * (yb - py)) / area;
                final float w1 = ((xc - px) * (ya - py) - (xa - px) * (yc - py)) / area;
                final float w2 = 1 - w0 - w1;
                if (w0 < -tolerance || w1 < -tolerance || w2 < -tolerance) continue;
                final boolean within = w0 >= 0 && w1 >= 0 && w2 >= 0;
                final int pixel = y * width + x;
                // 面の外側にはみ出した点は、まだ塗られていないときだけ塗る
                if (!within && (!paint || layer.covered[pixel])) continue;

                if (within) {
                    // 法線
                    float nx = tri.fx, ny = tri.fy, nz = tri.fz;
                    if (normals != null) {
                        final float ix = normals[a * 3] * w0 + normals[b * 3] * w1 + normals[c * 3] * w2;
                        final float iy = normals[a * 3 + 1] * w0 + normals[b * 3 + 1] * w1 + normals[c * 3 + 1] * w2;
                        final float iz = normals[a * 3 + 2] * w0 + normals[b * 3 + 2] * w1 + normals[c * 3 + 2] * w2;
                        final float length = (float) Math.sqrt(ix * ix + iy * iy + iz * iz);
                        if (length > 0) {
                            nx = ix / length;
                            ny = iy / length;
                            nz = iz / length;
                        }
                    }
                    inside++;
                    if (layer.covered[pixel]) {
                        final int packed = layer.owner[pixel];
                        final float ox = ((packed >> 20) & 0x3FF) / 511f - 1, oy = ((packed >> 10) & 0x3FF) / 511f - 1,
                                oz = (packed & 0x3FF) / 511f - 1;
                        // 別の向きの面がすでに同じ点を使っている
                        if (ox * nx + oy * ny + oz * nz < 0.7f) overlap++;
                    } else if (paint) {
                        layer.covered[pixel] = true;
                        layer.owner[pixel] = (Math.round((nx + 1) * 511f) << 20) | (Math.round((ny + 1) * 511f) << 10)
                                | Math.round((nz + 1) * 511f);
                    }
                }
                // 後から塗った三角形の色が残る
                if (paint) layer.writer[pixel] = id;
            }
        }
    }

    // 三角形 (painter.triangle) が塗る点 (x, y) の色を計算する
    private int color(Painter painter, int x, int y) {
        final Triangle tri = painter.triangle;
        final Surface surface = tri.surface;
        final GltfModel.Primitive primitive = surface.primitive;
        final float[] normals = surface.normals;
        final float[] tangents = surface.tangents;
        final float sign = surface.sign;
        final int t = tri.t, a = tri.a, b = tri.b, c = tri.c;
        final float xa = tri.xa, ya = tri.ya, xb = tri.xb, yb = tri.yb, xc = tri.xc, yc = tri.yc, area = tri.area;

        // raster と同じ式で求める (同じ値になるように)
        final float px = x + 0.5f, py = y + 0.5f;
        final float w0 = ((xb - px) * (yc - py) - (xc - px) * (yb - py)) / area;
        final float w1 = ((xc - px) * (ya - py) - (xa - px) * (yc - py)) / area;
        final float w2 = 1 - w0 - w1;

        // 法線
        float nx = tri.fx, ny = tri.fy, nz = tri.fz;
        if (normals != null) {
            final float ix = normals[a * 3] * w0 + normals[b * 3] * w1 + normals[c * 3] * w2;
            final float iy = normals[a * 3 + 1] * w0 + normals[b * 3 + 1] * w1 + normals[c * 3 + 1] * w2;
            final float iz = normals[a * 3 + 2] * w0 + normals[b * 3 + 2] * w1 + normals[c * 3 + 2] * w2;
            final float length = (float) Math.sqrt(ix * ix + iy * iy + iz * iz);
            if (length > 0) {
                nx = ix / length;
                ny = iy / length;
                nz = iz / length;
            }
        }

        final float u = px / width, v = py / height;
        // 接線。頂点に指定があればそれを、なければ面から求めたものを使う
        float tx = 0, ty = 0, tz = 0, bx = 0, by = 0, bz = 0;
        if (tangents != null) {
            tx = tangents[a * 3] * w0 + tangents[b * 3] * w1 + tangents[c * 3] * w2;
            ty = tangents[a * 3 + 1] * w0 + tangents[b * 3 + 1] * w1 + tangents[c * 3 + 1] * w2;
            tz = tangents[a * 3 + 2] * w0 + tangents[b * 3 + 2] * w1 + tangents[c * 3 + 2] * w2;
            final float hand = primitive.tangents[a * 4 + 3] < 0 ? -sign : sign;
            final float along = tx * nx + ty * ny + tz * nz;
            tx -= nx * along;
            ty -= ny * along;
            tz -= nz * along;
            final float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (length > 1e-12f) {
                tx /= length; ty /= length; tz /= length;
                bx = (ny * tz - nz * ty) * hand;
                by = (nz * tx - nx * tz) * hand;
                bz = (nx * ty - ny * tx) * hand;
            }
        } else if (tri.faceTx != 0 || tri.faceTy != 0 || tri.faceTz != 0) {
            tx = tri.faceTx; ty = tri.faceTy; tz = tri.faceTz;
            final float along = tx * nx + ty * ny + tz * nz;
            tx -= nx * along;
            ty -= ny * along;
            tz -= nz * along;
            final float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (length > 1e-12f) {
                tx /= length; ty /= length; tz /= length;
                bx = (ny * tz - nz * ty) * tri.faceHand;
                by = (nz * tx - nx * tz) * tri.faceHand;
                bz = (nx * ty - ny * tx) * tri.faceHand;
            }
        }

        if (input.normal != null && (tx != 0 || ty != 0 || tz != 0)) {
            final int sample = sample(input.normal, surface, t, u, v, w0, w1, w2);
            final float mx = (((sample >> 16) & 0xFF) / 127.5f - 1) * input.normalScale;
            final float my = (((sample >> 8) & 0xFF) / 127.5f - 1) * input.normalScale;
            final float mz = (sample & 0xFF) / 127.5f - 1;
            final float rx = tx * mx + bx * my + nx * mz;
            final float ry = ty * mx + by * my + ny * mz;
            final float rz = tz * mx + bz * my + nz * mz;
            final float bent = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (bent > 0) {
                nx = rx / bent;
                ny = ry / bent;
                nz = rz / bent;
            }
        }
        return texel(painter, surface, t, u, v, w0, w1, w2, nx, ny, nz, tx, ty, tz, bx, by, bz);
    }

    // 画像の色を取り出す。焼き込み先と違う座標を使う画像は、三角形の中での位置から座標を求める
    private static int sample(Map map, Surface surface, int corner, float u, float v, float w0, float w1, float w2) {
        if (map.slot == 0 || surface.extraUv == null) return map.at(u, v);
        final float[] uv = surface.extraUv[map.slot - 1];
        final int o = corner * 2;
        return map.at(uv[o] * w0 + uv[o + 2] * w1 + uv[o + 4] * w2, uv[o + 1] * w0 + uv[o + 3] * w1 + uv[o + 5] * w2);
    }

    // テクスチャの 1 点の色を計算する
    private int texel(Painter painter, Surface surface, int corner, float u, float v, float w0, float w1, float w2,
                      float nx, float ny, float nz, float tx, float ty, float tz, float bx, float by, float bz) {
        final GltfLight.Context ctx = painter.ctx;
        final float[] shaded = painter.shaded;
        float r = input.baseFactor[0], g = input.baseFactor[1], b = input.baseFactor[2], a = input.baseFactor[3];
        if (input.base != null) {
            final int sample = sample(input.base, surface, corner, u, v, w0, w1, w2);
            r *= GltfLight.toLinear(sample >> 16);
            g *= GltfLight.toLinear(sample >> 8);
            b *= GltfLight.toLinear(sample);
            a *= (sample >>> 24) / 255f;
        }

        float metallic = input.metallic, roughness = input.roughness;
        float f0r = input.f0[0], f0g = input.f0[1], f0b = input.f0[2];

        if (input.isSpecGloss) {
            float specR = input.specGlossSpecularFactor[0];
            float specG = input.specGlossSpecularFactor[1];
            float specB = input.specGlossSpecularFactor[2];
            float gloss = input.specGlossGlossinessFactor;
            if (input.specularGlossiness != null) {
                final int sample = sample(input.specularGlossiness, surface, corner, u, v, w0, w1, w2);
                specR *= GltfLight.toLinear(sample >> 16);
                specG *= GltfLight.toLinear(sample >> 8);
                specB *= GltfLight.toLinear(sample);
                gloss *= ((sample >>> 24) & 0xFF) / 255f;
            }
            roughness = Math.max(0.04f, 1f - gloss);
            metallic = 0f;
            f0r = specR;
            f0g = specG;
            f0b = specB;
            final float maxSpec = Math.max(specR, Math.max(specG, specB));
            final float diffuseFactor = Math.max(0f, 1f - maxSpec);
            r *= diffuseFactor;
            g *= diffuseFactor;
            b *= diffuseFactor;
        } else {
            if (input.metallicRoughness != null) {
                final int sample = sample(input.metallicRoughness, surface, corner, u, v, w0, w1, w2);
                roughness *= ((sample >> 8) & 0xFF) / 255f;
                metallic *= (sample & 0xFF) / 255f;
            }
            float specFactor = input.specularFactor;
            if (input.specular != null) {
                final int sample = sample(input.specular, surface, corner, u, v, w0, w1, w2);
                specFactor *= ((sample >>> 24) & 0xFF) / 255f;
            }
            float specColorR = input.specularColorFactor[0];
            float specColorG = input.specularColorFactor[1];
            float specColorB = input.specularColorFactor[2];
            if (input.specularColor != null) {
                final int sample = sample(input.specularColor, surface, corner, u, v, w0, w1, w2);
                specColorR *= GltfLight.toLinear(sample >> 16);
                specColorG *= GltfLight.toLinear(sample >> 8);
                specColorB *= GltfLight.toLinear(sample);
            }
            f0r = Math.min(1f, f0r * specFactor * specColorR);
            f0g = Math.min(1f, f0g * specFactor * specColorG);
            f0b = Math.min(1f, f0b * specFactor * specColorB);
        }

        ctx.f0r = f0r;
        ctx.f0g = f0g;
        ctx.f0b = f0b;

        final GltfModel.Primitive prim = surface.primitive;
        final int ac = prim.indices[corner], bc = prim.indices[corner + 1], cc = prim.indices[corner + 2];
        ctx.px = surface.positions[ac * 3] * w0 + surface.positions[bc * 3] * w1 + surface.positions[cc * 3] * w2;
        ctx.py = surface.positions[ac * 3 + 1] * w0 + surface.positions[bc * 3 + 1] * w1 + surface.positions[cc * 3 + 1] * w2;
        ctx.pz = surface.positions[ac * 3 + 2] * w0 + surface.positions[bc * 3 + 2] * w1 + surface.positions[cc * 3 + 2] * w2;

        ctx.tx = tx; ctx.ty = ty; ctx.tz = tz;
        ctx.bx = bx; ctx.by = by; ctx.bz = bz;
        ctx.lights = input.lights;
        ctx.lightCount = input.lightCount;
        ctx.sphericalHarmonics = input.sphericalHarmonics;

        float clearcoat = input.clearcoatFactor;
        if (input.clearcoat != null) {
            final int sample = sample(input.clearcoat, surface, corner, u, v, w0, w1, w2);
            clearcoat *= ((sample >> 16) & 0xFF) / 255f;
        }
        ctx.clearcoat = clearcoat;
        float ccRough = input.clearcoatRoughnessFactor;
        if (input.clearcoatRoughness != null) {
            final int sample = sample(input.clearcoatRoughness, surface, corner, u, v, w0, w1, w2);
            ccRough *= ((sample >> 8) & 0xFF) / 255f;
        }
        ctx.clearcoatRoughness = ccRough;
        if (input.clearcoatNormal != null && (tx != 0 || ty != 0 || tz != 0)) {
            final int sample = sample(input.clearcoatNormal, surface, corner, u, v, w0, w1, w2);
            final float mx = (((sample >> 16) & 0xFF) / 127.5f - 1) * input.clearcoatNormalScale;
            final float my = (((sample >> 8) & 0xFF) / 127.5f - 1) * input.clearcoatNormalScale;
            final float mz = (sample & 0xFF) / 127.5f - 1;
            final float rx = tx * mx + bx * my + nx * mz;
            final float ry = ty * mx + by * my + ny * mz;
            final float rz = tz * mx + bz * my + nz * mz;
            final float bent = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (bent > 0) {
                ctx.cnx = rx / bent; ctx.cny = ry / bent; ctx.cnz = rz / bent;
            } else {
                ctx.cnx = nx; ctx.cny = ny; ctx.cnz = nz;
            }
        } else {
            ctx.cnx = nx; ctx.cny = ny; ctx.cnz = nz;
        }

        float sheenR = input.sheenColorFactor[0], sheenG = input.sheenColorFactor[1], sheenB = input.sheenColorFactor[2];
        if (input.sheenColor != null) {
            final int sample = sample(input.sheenColor, surface, corner, u, v, w0, w1, w2);
            sheenR *= GltfLight.toLinear(sample >> 16);
            sheenG *= GltfLight.toLinear(sample >> 8);
            sheenB *= GltfLight.toLinear(sample);
        }
        ctx.sheenR = sheenR; ctx.sheenG = sheenG; ctx.sheenB = sheenB;
        float sheenRough = input.sheenRoughnessFactor;
        if (input.sheenRoughness != null) {
            final int sample = sample(input.sheenRoughness, surface, corner, u, v, w0, w1, w2);
            sheenRough *= ((sample >>> 24) & 0xFF) / 255f;
        }
        ctx.sheenRoughness = sheenRough;

        float irid = input.iridescenceFactor;
        if (input.iridescence != null) {
            final int sample = sample(input.iridescence, surface, corner, u, v, w0, w1, w2);
            irid *= ((sample >> 16) & 0xFF) / 255f;
        }
        ctx.iridescence = irid;
        ctx.iridescenceIor = input.iridescenceIor;
        float iridThick = input.iridescenceThicknessMax;
        if (input.iridescenceThickness != null) {
            final int sample = sample(input.iridescenceThickness, surface, corner, u, v, w0, w1, w2);
            final float frac = ((sample >> 8) & 0xFF) / 255f;
            iridThick = input.iridescenceThicknessMin + (input.iridescenceThicknessMax - input.iridescenceThicknessMin) * frac;
        }
        ctx.iridescenceThickness = iridThick;

        float aniso = input.anisotropyStrength;
        if (input.anisotropy != null) {
            final int sample = sample(input.anisotropy, surface, corner, u, v, w0, w1, w2);
            final float ax = ((sample >> 16) & 0xFF) / 127.5f - 1f;
            final float ay = ((sample >> 8) & 0xFF) / 127.5f - 1f;
            aniso *= (float) Math.sqrt(ax * ax + ay * ay);
        }
        ctx.anisotropy = aniso;

        float dtFactor = input.diffuseTransmissionFactor;
        if (input.diffuseTransmission != null) {
            final int sample = sample(input.diffuseTransmission, surface, corner, u, v, w0, w1, w2);
            dtFactor *= ((sample >>> 24) & 0xFF) / 255f;
        }
        float dtR = input.diffuseTransmissionColorFactor[0] * dtFactor;
        float dtG = input.diffuseTransmissionColorFactor[1] * dtFactor;
        float dtB = input.diffuseTransmissionColorFactor[2] * dtFactor;
        if (input.diffuseTransmissionColor != null) {
            final int sample = sample(input.diffuseTransmissionColor, surface, corner, u, v, w0, w1, w2);
            dtR *= GltfLight.toLinear(sample >> 16);
            dtG *= GltfLight.toLinear(sample >> 8);
            dtB *= GltfLight.toLinear(sample);
        }
        ctx.diffuseTransR = dtR; ctx.diffuseTransG = dtG; ctx.diffuseTransB = dtB;

        float occlusion = 1;
        if (input.occlusion != null) {
            final int sample = sample(input.occlusion, surface, corner, u, v, w0, w1, w2);
            occlusion = 1 + input.occlusionStrength * (((sample >> 16) & 0xFF) / 255f - 1);
        }
        GltfLight.shade(r, g, b, metallic, roughness, nx, ny, nz, occlusion, ctx, shaded);
        r = shaded[0];
        g = shaded[1];
        b = shaded[2];

        if (input.emissive != null) {
            final int sample = sample(input.emissive, surface, corner, u, v, w0, w1, w2);
            r += GltfLight.toLinear(sample >> 16) * input.emissiveFactor[0];
            g += GltfLight.toLinear(sample >> 8) * input.emissiveFactor[1];
            b += GltfLight.toLinear(sample) * input.emissiveFactor[2];
        } else {
            r += input.emissiveFactor[0];
            g += input.emissiveFactor[1];
            b += input.emissiveFactor[2];
        }

        float trans = input.transmissionFactor;
        if (input.transmission != null) {
            final int sample = sample(input.transmission, surface, corner, u, v, w0, w1, w2);
            trans *= ((sample >> 16) & 0xFF) / 255f;
        }
        if (trans > 0) {
            float thick = input.thicknessFactor;
            if (input.thickness != null) {
                final int sample = sample(input.thickness, surface, corner, u, v, w0, w1, w2);
                thick *= ((sample >> 8) & 0xFF) / 255f;
            }
            r *= GltfLight.volumeTint(input.attenuationColor[0], thick, input.attenuationDistance);
            g *= GltfLight.volumeTint(input.attenuationColor[1], thick, input.attenuationDistance);
            b *= GltfLight.volumeTint(input.attenuationColor[2], thick, input.attenuationDistance);
            a = GltfLight.transmittedAlpha(a, trans);
        }

        if (input.alphaMode == GltfModel.ALPHA_OPAQUE) a = 1;
        else if (input.alphaMode == GltfModel.ALPHA_MASK) a = a >= input.alphaCutoff ? 1 : 0;
        final int alpha = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (alpha << 24) | (GltfLight.toDisplay(r) << 16) | (GltfLight.toDisplay(g) << 8) | GltfLight.toDisplay(b);
    }

    private static void normalize(float[] array, int offset) {
        final float length = (float) Math.sqrt(array[offset] * array[offset] + array[offset + 1] * array[offset + 1]
                + array[offset + 2] * array[offset + 2]);
        if (length > 0) {
            array[offset] /= length;
            array[offset + 1] /= length;
            array[offset + 2] /= length;
        }
    }

    // 塗られていない点に、隣の塗られた点の色を広げる
    private static void dilate(int[] pixels, boolean[] covered, int width, int height) {
        boolean[] current = covered;
        for (int pass = 0; pass < DILATION; pass++) {
            final boolean[] next = current.clone();
            boolean changed = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int pixel = y * width + x;
                    if (current[pixel]) continue;
                    int source = -1;
                    if (x > 0 && current[pixel - 1]) source = pixel - 1;
                    else if (x < width - 1 && current[pixel + 1]) source = pixel + 1;
                    else if (y > 0 && current[pixel - width]) source = pixel - width;
                    else if (y < height - 1 && current[pixel + width]) source = pixel + width;
                    if (source < 0) continue;
                    pixels[pixel] = pixels[source];
                    next[pixel] = true;
                    changed = true;
                }
            }
            current = next;
            if (!changed) break;
        }
    }
}
