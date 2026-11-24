package marumasa.marumasa_sign.core.gltf;

import java.util.Arrays;

/**
 * 辺を共有する三角形 2 枚を、四角形 1 枚にまとめる。
 * 三角形は「3 つ目の頂点を重ねた四角形」(4 頂点) として描くので、2 枚をまとめると頂点が 8 つから 4 つになる。
 *
 * <p>ゲームは四角形 (v0, v1, v2, v3) を、三角形 (v0, v1, v2) と (v2, v3, v0) に分けて描く。
 * 辺 x - y を共有する三角形 (y, z1, x) と (x, z2, y) を四角形 (y, z1, x, z2) にすると、分けたあとの 2 枚は
 * 元の 2 枚と同じ三角形 (向きも同じ) になるので、描かれる絵は変わらない。共有する 2 頂点では、位置・色・テクスチャ座標が
 * 2 枚で一致している必要がある (一致していない辺ではまとめない)。</p>
 *
 * <p>どの 2 枚を組にするかは、三角形を点、まとめられる辺を線としたグラフの「マッチング」(1 つの点を 2 回使わない線の集まり) で、
 * 線が多いほど頂点が減る (四角形の数の最小は、三角形の数から最大マッチングの大きさを引いたもの)。閉じた曲面のメッシュでは、
 * このグラフはどの点からも線が 3 本出ていて橋 (取ると 2 つに分かれる線) がないので、すべての三角形を組にできる (ピーターセンの定理)。
 * ここでは、線が 1 本しかない点を先に組にする方法 (Karp–Sipser。この手順は最大のマッチングを損なわない) で、これ以上足せない
 * マッチングを作り (最大のものの半分以上は必ずある)、短い増加道 (長さ 7 まで) で直す。証明は
 * docs/reference.md の「描画の計算と、その正しさ」にある。</p>
 *
 * <p>影 Mod は四角形ごとに面の向きを求めるので、まとめるのは、面の向きがほぼ同じ 2 枚だけにする (FOLD_COSINE)。</p>
 *
 * <p>1.16 までの Minecraft は四角形のまま OpenGL に渡すので、どちらの対角線で分けるかはグラフィックスドライバーによる。
 * そのため MODE_PLANAR では、どちらで分けても同じ絵になるもの (同じ平面にある凸な四角形で、4 頂点の法線と色が同じで、
 * テクスチャ座標が 1 つの一次式で表せるもの) だけをまとめる。</p>
 */
final class QuadPairing {

    // まとめない
    static final int MODE_NONE = 0;
    // どちらの対角線で分けても同じ絵になるものだけをまとめる
    static final int MODE_PLANAR = 1;
    // 辺を共有していればまとめる (四角形が必ず対角線 0 - 2 で分けられるとき)
    static final int MODE_ANY = 2;

    // 同じ平面にあるとみなす、平面からのずれ (四角形の大きさに対する割合)
    private static final double PLANAR_TOLERANCE = 1e-4;
    // テクスチャ座標が一次式からずれてよい量
    private static final double UV_TOLERANCE = 1e-5;
    // まとめる 2 枚の面の向きの差の上限 (10 度) の余弦。影 Mod (Iris、OptiFine) は、四角形ごとに 1 つの面の向きを
    // 頂点の位置から求めて陰影に使う。折れ目の大きい 2 枚をまとめると、影 Mod を使ったときだけ陰影が変わってしまうので、
    // ほぼ同じ向きの 2 枚だけをまとめる (影 Mod を使わないときの絵は、向きの差によらず変わらない)
    private static final double FOLD_COSINE = Math.cos(Math.toRadians(10));
    // 増加道を探す深さ (組になっている線を何本までたどるか)。長さ 2 * AUGMENT_DEPTH + 1 までの増加道を見つける
    private static final int AUGMENT_DEPTH = 3;

    static final class Plan {
        // 四角形 1 枚につき、indices の中の位置が 4 つ (反時計回り)。まとめられなかった三角形は、3 つ目をもう一度入れる。
        // 1 組もまとめられなければ null
        final int[] corners;
        // まとめた組の数
        final int pairs;

        Plan(int[] corners, int pairs) {
            this.corners = corners;
            this.pairs = pairs;
        }
    }

    private static final Plan NOTHING = new Plan(null, 0);

    private QuadPairing() {
    }

    /**
     * 四角形が必ず対角線 0 - 2 で分けられるバージョンかどうか。
     * 1.17 以降は、四角形を頂点番号 (0, 1, 2, 2, 3, 0) の三角形として描く (RenderSystem の sharedSequentialQuad)。
     * 分からないバージョンは false。
     */
    static boolean fixedDiagonal(String version) {
        if (version == null) return false;
        final String[] parts = version.split("\\.");
        try {
            final int major = Integer.parseInt(parts[0]);
            if (major >= 2) return true;
            if (major != 1 || parts.length < 2) return false;
            // "1.21.1-pre1" のような続きがあっても、数字の部分だけを見る
            int end = 0;
            while (end < parts[1].length() && Character.isDigit(parts[1].charAt(end))) end++;
            return end > 0 && Integer.parseInt(parts[1].substring(0, end)) >= 17;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 面のまとまりの三角形を、四角形にまとめる。
     *
     * @param lights モデルに光源があるかどうか (あると、同じ法線の頂点でも位置によって色が変わる)
     */
    static Plan plan(GltfModel.Primitive primitive, GltfModel.Material material, boolean lights, int mode) {
        final int[] indices = primitive.indices;
        final int triangles = indices.length / 3;
        // 頂点ごとの色を使うまとまりだけ (法線がなければ色は面ごとに違う)。四角形のままのものは、まとめる必要がない。
        // 半透明の面は、ゲームが四角形ごとに奥から並べ替えることがあり、まとめると重なりの順が変わりうるので、まとめない
        if (mode == MODE_NONE || primitive.quads || primitive.normals == null || triangles < 2
                || material.alphaMode == GltfModel.ALPHA_BLEND || primitive.cornerUv == null) {
            return NOTHING;
        }
        // 切り抜きの材質は、材質とテクスチャの不透明度を読み込みで 0 か 1 にしてあるが、頂点の色の不透明度は掛かる。
        // それが 0 でも 1 でもない頂点があると半透明の画素ができ、描く順で絵が変わりうるので、まとめない
        if (material.alphaMode != GltfModel.ALPHA_OPAQUE && primitive.colors != null) {
            for (int a = 3; a < primitive.colors.length; a += 4) {
                final float alpha = primitive.colors[a];
                if (alpha != 0f && alpha != 1f) return NOTHING;
            }
        }
        final boolean planar = mode != MODE_ANY;
        // 形が変わるまとまり (スキニング、モーフィング) は、姿勢によって平面でなくなる
        if (planar && (primitive.influences > 0 || primitive.morphPositions.length > 0 || lights)) return NOTHING;

        final int cornerCount = triangles * 3;
        if (cornerCount >= INDEX_LIMIT || primitive.vertexCount >= INDEX_LIMIT || primitive.vertexCount <= 0) return NOTHING;
        for (int c = 0; c < cornerCount; c++) {
            if (indices[c] < 0 || indices[c] >= primitive.vertexCount) return NOTHING;
        }
        final int[] vertexClass = vertexClasses(primitive);
        final int[] cornerClass = cornerClasses(primitive, vertexClass, cornerCount);

        // 辺 (三角形 t の s 番目の角から次の角へ) を、(始点の組, 終点の組) で並べる
        final long[] edges = new long[cornerCount];
        for (int e = 0; e < cornerCount; e++) {
            final int t = e / 3, s = e - t * 3;
            final long from = cornerClass[e], to = cornerClass[t * 3 + (s + 1) % 3];
            edges[e] = (from << (2 * INDEX_BITS)) | (to << INDEX_BITS) | e;
        }
        Arrays.sort(edges);

        // 三角形ごとの、まとめられる相手 (1 辺につき 1 つまで)。partner[e] は、辺 e と逆向きに重なる辺。なければ -1
        final int[] partner = new int[cornerCount];
        Arrays.fill(partner, -1);
        final Geometry geometry = new Geometry(primitive);
        for (int k = 0; k < cornerCount; k++) {
            final int e = (int) (edges[k] & INDEX_MASK);
            if (partner[e] >= 0) continue;
            final long from = edges[k] >>> (2 * INDEX_BITS), to = (edges[k] >>> INDEX_BITS) & INDEX_MASK;
            // 縮退した辺 (両端が同じ) は使わない
            if (from == to) continue;
            // 逆向きの辺 (to, from) のうち、まだ相手のない最初のもの
            final long reversed = (to << (2 * INDEX_BITS)) | (from << INDEX_BITS);
            int at = Arrays.binarySearch(edges, reversed);
            if (at < 0) at = -at - 1;
            // 相手の候補を調べる数にも上限を置く (同じ辺が何千回も重なっているモデルで、時間がかからないように)
            for (int tried = 0; at < cornerCount && (edges[at] >>> INDEX_BITS) == (reversed >>> INDEX_BITS) && tried < MAX_CANDIDATES; at++) {
                final int other = (int) (edges[at] & INDEX_MASK);
                if (partner[other] >= 0 || other / 3 == e / 3) continue;
                tried++;
                if (planar ? !geometry.interchangeable(e, other) : !geometry.flatEnough(e, other)) continue;
                partner[e] = other;
                partner[other] = e;
                break;
            }
        }

        final int[] match = match(partner, triangles);
        int pairs = 0;
        for (int t = 0; t < triangles; t++) {
            if (match[t] >= 0) pairs++;
        }
        pairs /= 2;
        if (pairs == 0) return NOTHING;

        // 元の並びの順に、四角形と、残った三角形を書き出す
        final int[] corners = new int[(triangles - pairs) * 4];
        int n = 0;
        for (int t = 0; t < triangles; t++) {
            final int e = match[t];
            if (e < 0) {
                corners[n++] = t * 3;
                corners[n++] = t * 3 + 1;
                corners[n++] = t * 3 + 2;
                corners[n++] = t * 3 + 2;
                continue;
            }
            final int other = partner[e];
            if (other / 3 < t) continue;
            // 辺 e は角 s (x) から次の角 (y) へ。四角形は (y, z1, x, z2)
            final int s = e - t * 3;
            final int u = other / 3, us = other - u * 3;
            corners[n++] = t * 3 + (s + 1) % 3;
            corners[n++] = t * 3 + (s + 2) % 3;
            corners[n++] = e;
            corners[n++] = u * 3 + (us + 2) % 3;
        }
        return new Plan(corners, pairs);
    }

    // ---------- 同じ頂点・同じ角をまとめる ----------

    // 番号 (頂点、角、辺) に使うビット数。これより多いまとまりは、まとめない
    private static final int INDEX_BITS = 21;
    private static final int INDEX_LIMIT = 1 << INDEX_BITS;
    private static final long INDEX_MASK = INDEX_LIMIT - 1;
    // 同じハッシュの中で、中身を比べる相手の数の上限。わざとハッシュをぶつけたモデルでも、時間がかからないようにする
    // (上限を超えた分は別の組として扱うだけで、違うものを同じ組にすることはない)
    private static final int MAX_CANDIDATES = 8;

    private static long mix(long h, float[] values, int offset, int count) {
        for (int k = 0; k < count; k++) {
            h = (h ^ (offset + k < values.length ? Float.floatToRawIntBits(values[offset + k]) : 0)) * 0x9E3779B97F4A7C15L;
            h ^= h >>> 29;
        }
        return h;
    }

    // ハッシュ (上位 43 ビット) と番号を 1 つの long にする。並べ替えると、同じハッシュのものが隣り合う
    private static long keyed(long hash, int index) {
        hash ^= hash >>> 31;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        return (hash & ~INDEX_MASK) | index;
    }

    private static boolean same(float[] values, int a, int b, int count) {
        for (int k = 0; k < count; k++) {
            final int ia = a + k, ib = b + k;
            final int x = ia < values.length ? Float.floatToRawIntBits(values[ia]) : 0;
            final int y = ib < values.length ? Float.floatToRawIntBits(values[ib]) : 0;
            if (x != y) return false;
        }
        return true;
    }

    /**
     * 頂点ごとの組の番号。位置・法線・接線・色・ボーン・モーフィングの変化量がすべて同じ (ビット列が同じ) 頂点は、
     * 番号が違っても同じ組にする (どの姿勢でも、同じ位置と色になる)。
     */
    private static int[] vertexClasses(GltfModel.Primitive primitive) {
        final int count = primitive.vertexCount;
        final long[] order = new long[count];
        for (int v = 0; v < count; v++) {
            long h = 17;
            h = mix(h, primitive.positions, v * 3, 3);
            h = mix(h, primitive.normals, v * 3, 3);
            if (primitive.tangents != null) h = mix(h, primitive.tangents, v * 4, 4);
            if (primitive.colors != null) h = mix(h, primitive.colors, v * 4, 4);
            order[v] = keyed(h, v);
        }
        Arrays.sort(order);
        final int[] result = new int[count];
        final int[] candidates = new int[MAX_CANDIDATES];
        int classes = 0;
        for (int start = 0; start < count; ) {
            int end = start + 1;
            while (end < count && (order[end] & ~INDEX_MASK) == (order[start] & ~INDEX_MASK)) end++;
            int candidateCount = 0;
            for (int a = start; a < end; a++) {
                final int va = (int) (order[a] & INDEX_MASK);
                int found = -1;
                for (int k = 0; k < candidateCount; k++) {
                    if (sameVertex(primitive, va, candidates[k])) {
                        found = result[candidates[k]];
                        break;
                    }
                }
                if (found < 0) {
                    found = classes++;
                    if (candidateCount < MAX_CANDIDATES) candidates[candidateCount++] = va;
                }
                result[va] = found;
            }
            start = end;
        }
        return result;
    }

    private static boolean sameVertex(GltfModel.Primitive primitive, int a, int b) {
        if (a == b) return true;
        if (!same(primitive.positions, a * 3, b * 3, 3) || !same(primitive.normals, a * 3, b * 3, 3)) return false;
        if (primitive.tangents != null && !same(primitive.tangents, a * 4, b * 4, 4)) return false;
        if (primitive.colors != null && !same(primitive.colors, a * 4, b * 4, 4)) return false;
        final int influences = primitive.influences;
        if (influences > 0) {
            for (int k = 0; k < influences; k++) {
                if (primitive.joints[a * influences + k] != primitive.joints[b * influences + k]) return false;
            }
            if (!same(primitive.weights, a * influences, b * influences, influences)) return false;
        }
        for (int t = 0; t < primitive.morphPositions.length; t++) {
            final float[] positions = primitive.morphPositions[t];
            if (positions != null && !same(positions, a * 3, b * 3, 3)) return false;
            final float[] normals = t < primitive.morphNormals.length ? primitive.morphNormals[t] : null;
            if (normals != null && !same(normals, a * 3, b * 3, 3)) return false;
        }
        return true;
    }

    /**
     * 角 (indices の中の位置) ごとの組の番号。頂点の組とテクスチャ座標 (アニメーションで貼り方が変わるときの元の座標も)
     * が同じ角を、同じ組にする。
     */
    private static int[] cornerClasses(GltfModel.Primitive primitive, int[] vertexClass, int cornerCount) {
        final int[] indices = primitive.indices;
        final float[] uv = primitive.cornerUv;
        final float[] raw = primitive.rawUv;
        final long[] order = new long[cornerCount];
        for (int c = 0; c < cornerCount; c++) {
            long h = vertexClass[indices[c]];
            h = mix(h, uv, c * 2, 2);
            if (raw != null) h = mix(h, raw, c * 2, 2);
            order[c] = keyed(h, c);
        }
        Arrays.sort(order);
        final int[] result = new int[cornerCount];
        final int[] candidates = new int[MAX_CANDIDATES];
        int classes = 0;
        for (int start = 0; start < cornerCount; ) {
            int end = start + 1;
            while (end < cornerCount && (order[end] & ~INDEX_MASK) == (order[start] & ~INDEX_MASK)) end++;
            int candidateCount = 0;
            for (int a = start; a < end; a++) {
                final int ca = (int) (order[a] & INDEX_MASK);
                int found = -1;
                for (int k = 0; k < candidateCount; k++) {
                    final int cb = candidates[k];
                    if (vertexClass[indices[ca]] == vertexClass[indices[cb]] && same(uv, ca * 2, cb * 2, 2)
                            && (raw == null || same(raw, ca * 2, cb * 2, 2))) {
                        found = result[cb];
                        break;
                    }
                }
                if (found < 0) {
                    found = classes++;
                    if (candidateCount < MAX_CANDIDATES) candidates[candidateCount++] = ca;
                }
                result[ca] = found;
            }
            start = end;
        }
        return result;
    }

    // ---------- どちらの対角線で分けても同じ絵になるか ----------

    private static final class Geometry {
        private final GltfModel.Primitive primitive;

        Geometry(GltfModel.Primitive primitive) {
            this.primitive = primitive;
        }

        // 三角形 t の面の向き (長さは面積の 2 倍) を out に入れる
        private void faceNormal(int t, double[] out) {
            final int[] indices = primitive.indices;
            final float[] p = primitive.positions;
            final int a = indices[t * 3] * 3, b = indices[t * 3 + 1] * 3, c = indices[t * 3 + 2] * 3;
            final double ux = (double) p[b] - p[a], uy = (double) p[b + 1] - p[a + 1], uz = (double) p[b + 2] - p[a + 2];
            final double vx = (double) p[c] - p[a], vy = (double) p[c + 1] - p[a + 1], vz = (double) p[c + 2] - p[a + 2];
            out[0] = uy * vz - uz * vy;
            out[1] = uz * vx - ux * vz;
            out[2] = ux * vy - uy * vx;
        }

        private final double[] first = new double[3], second = new double[3];

        // 辺 e と other を持つ 2 枚の三角形の面の向きが、ほぼ同じかどうか (最初の姿勢で比べる)。面積のない三角形はまとめない
        boolean flatEnough(int e, int other) {
            faceNormal(e / 3, first);
            faceNormal(other / 3, second);
            final double aa = first[0] * first[0] + first[1] * first[1] + first[2] * first[2];
            final double bb = second[0] * second[0] + second[1] * second[1] + second[2] * second[2];
            final double ab = first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
            return aa > 0 && bb > 0 && ab > 0 && ab * ab >= FOLD_COSINE * FOLD_COSINE * aa * bb;
        }

        // 辺 e (三角形 t の角 s から次の角へ) と、逆向きに重なる辺 other でまとめた四角形が、条件を満たすかどうか
        boolean interchangeable(int e, int other) {
            final int t = e / 3, s = e - t * 3;
            final int u = other / 3, us = other - u * 3;
            // 四角形 (y, z1, x, z2) の角
            final int cy = t * 3 + (s + 1) % 3, cz1 = t * 3 + (s + 2) % 3, cx = e, cz2 = u * 3 + (us + 2) % 3;
            final int[] indices = primitive.indices;
            final int vy = indices[cy], vz1 = indices[cz1], vx = indices[cx], vz2 = indices[cz2];

            // 4 頂点の色が同じになること: 法線・頂点の色・接線が同じ (光源がなければ、陰影は法線だけで決まる)
            if (!same(primitive.normals, vy * 3, vz1 * 3, 3) || !same(primitive.normals, vy * 3, vx * 3, 3)
                    || !same(primitive.normals, vy * 3, vz2 * 3, 3)) {
                return false;
            }
            if (primitive.colors != null && (!same(primitive.colors, vy * 4, vz1 * 4, 4) || !same(primitive.colors, vy * 4, vx * 4, 4)
                    || !same(primitive.colors, vy * 4, vz2 * 4, 4))) {
                return false;
            }
            if (primitive.tangents != null && (!same(primitive.tangents, vy * 4, vz1 * 4, 4) || !same(primitive.tangents, vy * 4, vx * 4, 4)
                    || !same(primitive.tangents, vy * 4, vz2 * 4, 4))) {
                return false;
            }

            // 位置: y を原点として、a = z1 - y, b = x - y, c = z2 - y
            final float[] p = primitive.positions;
            final double ax = (double) p[vz1 * 3] - p[vy * 3], ay = (double) p[vz1 * 3 + 1] - p[vy * 3 + 1], az = (double) p[vz1 * 3 + 2] - p[vy * 3 + 2];
            final double bx = (double) p[vx * 3] - p[vy * 3], by = (double) p[vx * 3 + 1] - p[vy * 3 + 1], bz = (double) p[vx * 3 + 2] - p[vy * 3 + 2];
            final double cxx = (double) p[vz2 * 3] - p[vy * 3], cyy = (double) p[vz2 * 3 + 1] - p[vy * 3 + 1], czz = (double) p[vz2 * 3 + 2] - p[vy * 3 + 2];
            // 1 枚目の三角形 (y, z1, x) の法線 n = a x b
            final double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            final double nn = nx * nx + ny * ny + nz * nz;
            final double size2 = Math.max(ax * ax + ay * ay + az * az, Math.max(bx * bx + by * by + bz * bz, cxx * cxx + cyy * cyy + czz * czz));
            if (!(nn > 1e-24 * size2 * size2) || !(size2 > 0)) return false;
            // z2 の、平面からのずれ
            final double off = (cxx * nx + cyy * ny + czz * nz) / Math.sqrt(nn);
            if (!(Math.abs(off) <= PLANAR_TOLERANCE * Math.sqrt(size2))) return false;

            // z2 = y + alpha a + beta b と表す (平面の中の座標)。a, b のグラム行列を解く
            final double aa = ax * ax + ay * ay + az * az, ab = ax * bx + ay * by + az * bz, bb = bx * bx + by * by + bz * bz;
            final double ac = ax * cxx + ay * cyy + az * czz, bc = bx * cxx + by * cyy + bz * czz;
            final double det = aa * bb - ab * ab;
            final double alpha = (ac * bb - bc * ab) / det, beta = (bc * aa - ac * ab) / det;
            // 凸であること: 四角形 (y, z1, x, z2) で、z2 は辺 y - x をはさんで z1 の反対側にあり (alpha < 0)、
            // もう 1 本の対角線 z1 - z2 が y と x の間を通る。交点は y + beta / (1 - alpha) * b なので、
            // 0 < beta かつ alpha + beta < 1 であればよい (余裕を持って確かめる)
            final double margin = 1e-6;
            if (!(alpha < -margin && beta > margin && alpha + beta < 1 - margin)) return false;

            // テクスチャ座標が、1 つの一次式で表せること
            if (!affine(primitive.cornerUv, cy, cz1, cx, cz2, alpha, beta)) return false;
            return primitive.rawUv == null || affine(primitive.rawUv, cy, cz1, cx, cz2, alpha, beta);
        }

        private static boolean affine(float[] uv, int cy, int cz1, int cx, int cz2, double alpha, double beta) {
            for (int k = 0; k < 2; k++) {
                final double y = uv[cy * 2 + k], z1 = uv[cz1 * 2 + k], x = uv[cx * 2 + k], z2 = uv[cz2 * 2 + k];
                final double predicted = y + alpha * (z1 - y) + beta * (x - y);
                final double scale = Math.max(1, Math.max(Math.abs(y), Math.max(Math.abs(z1), Math.max(Math.abs(x), Math.abs(z2)))));
                if (!(Math.abs(predicted - z2) <= UV_TOLERANCE * scale)) return false;
            }
            return true;
        }
    }

    // ---------- マッチング ----------

    /**
     * 三角形を点、partner の線 (三角形 1 つにつき 3 本まで) を線としたグラフのマッチングを作る。
     * 戻り値は、三角形ごとの、組にした辺 (partner の添字)。組にしなかった三角形は -1。
     */
    static int[] match(int[] partner, int triangles) {
        final int[] match = new int[triangles];
        Arrays.fill(match, -1);
        // まだ組になっていない相手の数
        final int[] degree = new int[triangles];
        for (int e = 0; e < partner.length; e++) {
            if (partner[e] >= 0) degree[e / 3]++;
        }
        // 線が 1 本だけの点を先に組にする (その線を使う最大のマッチングが必ずある)
        final int[] stack = new int[triangles];
        int top = 0;
        for (int t = 0; t < triangles; t++) {
            if (degree[t] == 1) stack[top++] = t;
        }
        int next = 0;
        while (true) {
            int t;
            if (top > 0) {
                t = stack[--top];
                if (match[t] >= 0 || degree[t] != 1) continue;
            } else {
                // 1 本だけの点がなくなったら、番号の小さい順に、相手の線が少ないものと組にする
                while (next < triangles && (match[next] >= 0 || degree[next] == 0)) next++;
                if (next >= triangles) break;
                t = next;
            }
            int best = -1, bestDegree = Integer.MAX_VALUE;
            for (int s = 0; s < 3; s++) {
                final int e = t * 3 + s;
                if (partner[e] < 0) continue;
                final int u = partner[e] / 3;
                if (match[u] >= 0) continue;
                if (degree[u] < bestDegree) {
                    bestDegree = degree[u];
                    best = e;
                }
            }
            if (best < 0) {
                degree[t] = 0;
                continue;
            }
            final int u = partner[best] / 3;
            match[t] = best;
            match[u] = partner[best];
            // 組になった 2 点のまわりの点は、相手が 1 つ減る
            top = removed(t, partner, match, degree, stack, top);
            top = removed(u, partner, match, degree, stack, top);
        }

        // 短い増加道で直す: 組になっていない点から、組でない線・組の線を交互にたどって、組になっていない別の点に着けば、
        // 道の上の線の「組・組でない」を入れ替えて、組を 1 つ増やせる
        final int[] visited = new int[triangles];
        final int[] path = new int[AUGMENT_DEPTH * 2 + 2];
        int stampValue = 0;
        for (int t = 0; t < triangles; t++) {
            if (match[t] >= 0) continue;
            stampValue++;
            visited[t] = stampValue;
            augment(t, AUGMENT_DEPTH, partner, match, visited, stampValue, path, 0);
        }
        return match;
    }

    // 点 t が組になったので、まだ組になっていない隣の点の相手の数を減らす
    private static int removed(int t, int[] partner, int[] match, int[] degree, int[] stack, int top) {
        for (int s = 0; s < 3; s++) {
            final int e = t * 3 + s;
            if (partner[e] < 0) continue;
            final int u = partner[e] / 3;
            if (match[u] >= 0) continue;
            if (--degree[u] == 1) stack[top++] = u;
        }
        return top;
    }

    /**
     * 組になっていない点 t から、増加道を探して入れ替える。見つけたら true。
     * path には、たどった線 (partner の添字) を入れる。
     */
    private static boolean augment(int t, int depth, int[] partner, int[] match, int[] visited, int stamp, int[] path, int length) {
        // まず、組になっていない隣の点 (長さ 1 の増加道の終わり)
        for (int s = 0; s < 3; s++) {
            final int e = t * 3 + s;
            if (partner[e] < 0) continue;
            final int u = partner[e] / 3;
            if (match[u] >= 0 || visited[u] == stamp) continue;
            // 道を入れ替える: path の線は「組でない線」で、その間の組の線を外して、組でない線を組にする
            path[length] = e;
            for (int k = 0; k <= length; k++) {
                final int edge = path[k];
                match[edge / 3] = edge;
                match[partner[edge] / 3] = partner[edge];
            }
            return true;
        }
        if (depth == 0) return false;
        for (int s = 0; s < 3; s++) {
            final int e = t * 3 + s;
            if (partner[e] < 0) continue;
            final int u = partner[e] / 3;
            if (visited[u] == stamp) continue;
            // u は組になっている。その相手 w へ進む
            final int w = partner[match[u]] / 3;
            if (visited[w] == stamp) continue;
            visited[u] = stamp;
            visited[w] = stamp;
            path[length] = e;
            if (augment(w, depth - 1, partner, match, visited, stamp, path, length + 1)) return true;
        }
        return false;
    }
}
