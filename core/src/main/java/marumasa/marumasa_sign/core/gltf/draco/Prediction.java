package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * 属性の値の予測。符号化側は「予測との差」だけを書くので、
 * 先に復号した値から同じ予測をして差を足し、元の整数の値に戻す。
 * 値はすべて量子化された整数 (int) で、データの配列の上でその場で戻していく。
 */
final class Prediction {

    // 予測の方式
    static final int NONE = -2;
    static final int DIFFERENCE = 0;
    static final int PARALLELOGRAM = 1;
    static final int MULTI_PARALLELOGRAM = 2;
    static final int TEX_COORDS_DEPRECATED = 3;
    static final int CONSTRAINED_MULTI_PARALLELOGRAM = 4;
    static final int TEX_COORDS_PORTABLE = 5;
    static final int GEOMETRIC_NORMAL = 6;
    static final int METHOD_COUNT = 7;

    // 予測と差を合わせる変換
    static final int TRANSFORM_NONE = -1;
    static final int TRANSFORM_WRAP = 1;
    static final int TRANSFORM_OCTAHEDRON = 2;
    static final int TRANSFORM_OCTAHEDRON_CANONICALIZED = 3;
    static final int TRANSFORM_COUNT = 4;

    private static final int MAX_PARALLELOGRAMS = 4;

    final int method;
    final Transform transform;
    private final int version;
    private final Budget budget;

    // メッシュのつながりと、値の順番 (方式が DIFFERENCE のときは使わない)
    private final Connectivity table;
    private final IntList valueToCorner;
    private final int[] vertexToValue;

    // 予測に使う位置の属性
    private Parent parent;
    private int maxPredictedValues = -1;

    // 方式ごとに読んだデータ
    private final boolean[][] crease = new boolean[MAX_PARALLELOGRAMS][];
    private boolean[] orientations;
    private int orientationCount;
    private Rans.BitDecoder flips;
    private boolean oneTriangle;
    private final Octahedron normalBox = new Octahedron();

    Prediction(int method, Transform transform, Connectivity table, IntList valueToCorner, int[] vertexToValue,
               int version, Budget budget) {
        this.method = method;
        this.transform = transform;
        this.table = table;
        this.valueToCorner = valueToCorner;
        this.vertexToValue = vertexToValue;
        this.version = version;
        this.budget = budget;
    }

    // 位置を親にする方式か
    boolean needsPositions() {
        return method == TEX_COORDS_DEPRECATED || method == TEX_COORDS_PORTABLE || method == GEOMETRIC_NORMAL;
    }

    void setParent(Parent parent) {
        this.parent = parent;
    }

    void setMaxPredictedValues(int n) {
        maxPredictedValues = n;
    }

    /**
     * 予測に必要な追加のデータを読む。
     */
    void decodeData(ByteReader in) throws IOException {
        switch (method) {
            case CONSTRAINED_MULTI_PARALLELOGRAM:
                if (in.version < ByteReader.V22 && in.u8() != 0) {
                    throw new IOException("Unsupported Draco multi-parallelogram mode");
                }
                for (int i = 0; i < MAX_PARALLELOGRAMS; i++) {
                    final long flags = in.varU32();
                    if (flags > table.numCorners()) throw new IOException("Too many Draco crease flags");
                    if (flags > 0) {
                        budget.alloc(flags);
                        final int count = (int) flags;
                        crease[i] = new boolean[count];
                        final Rans.BitDecoder bits = Rans.BitDecoder.start(in);
                        for (int j = 0; j < count; j++) crease[i][j] = bits.next();
                    }
                }
                transform.decodeData(in);
                break;
            case TEX_COORDS_DEPRECATED: {
                final long n = in.count32Before22();
                if (n == 0 || n > table.numCorners()) throw new IOException("Invalid Draco UV orientation count");
                readOrientations(in, (int) n);
                transform.decodeData(in);
                break;
            }
            case TEX_COORDS_PORTABLE: {
                final int n = in.i32();
                if (n < 0 || (maxPredictedValues != -1 && n > maxPredictedValues)) {
                    throw new IOException("Invalid Draco UV orientation count");
                }
                readOrientations(in, n);
                transform.decodeData(in);
                break;
            }
            case GEOMETRIC_NORMAL:
                transform.decodeData(in);
                if (in.version < ByteReader.V22) {
                    final int mode = in.u8();
                    if (mode > 1) throw new IOException("Invalid Draco normal prediction mode");
                    oneTriangle = mode == 0;
                }
                flips = Rans.BitDecoder.start(in);
                break;
            default:
                transform.decodeData(in);
                break;
        }
    }

    // UV の向き (反転しているか) を 1 ビットずつ読む。前と違うときに 0 が書かれている
    private void readOrientations(ByteReader in, int n) throws IOException {
        budget.alloc(n);
        orientations = new boolean[n];
        orientationCount = n;
        final Rans.BitDecoder bits = Rans.BitDecoder.start(in);
        boolean last = true;
        for (int i = 0; i < n; i++) {
            if (!bits.next()) last = !last;
            orientations[i] = last;
        }
    }

    private boolean popOrientation() throws IOException {
        if (orientationCount == 0) throw new IOException("Draco UV orientations ran out");
        return orientations[--orientationCount];
    }

    /**
     * data の size 個の値 (差) を、その場で元の値に戻す。pointIds は値の番号から点の番号への対応。
     */
    void restore(int[] data, int size, int components, int[] pointIds) throws IOException {
        switch (method) {
            case PARALLELOGRAM:
                restoreParallelogram(data, size, components);
                break;
            case MULTI_PARALLELOGRAM:
                restoreMultiParallelogram(data, size, components);
                break;
            case CONSTRAINED_MULTI_PARALLELOGRAM:
                restoreConstrained(data, size, components);
                break;
            case TEX_COORDS_DEPRECATED:
                restoreTexCoordsDeprecated(data, size, components, pointIds);
                break;
            case TEX_COORDS_PORTABLE:
                restoreTexCoordsPortable(data, size, components, pointIds);
                break;
            case GEOMETRIC_NORMAL:
                restoreGeometricNormal(data, size, components, pointIds);
                break;
            default:
                restoreDifference(data, size, components);
                break;
        }
    }

    // ---------- 差分 ----------

    // 1 つ前の値からの差
    private void restoreDifference(int[] data, int size, int components) {
        transform.init(components);
        transform.apply(new int[components], 0, data, 0);
        for (int i = components; i < size; i += components) {
            transform.apply(data, i - components, data, i);
        }
    }

    // ---------- 平行四辺形 ----------

    private int valueOfVertex(int vertex) throws IOException {
        if (vertex < 0 || vertex >= vertexToValue.length) throw new IOException("Invalid Draco vertex in prediction");
        return vertexToValue[vertex];
    }

    private int cornerOfValue(int value) {
        return valueToCorner.get(value);
    }

    private void checkSize(int size, int components) throws IOException {
        if ((long) valueToCorner.getCount() * components > size) throw new IOException("Draco prediction size mismatch");
    }

    /**
     * 角 c の向かいの三角形で平行四辺形の予測をする。3 つの頂点がまだ復号されていなければ false。
     */
    private boolean parallelogram(int entry, int c, int[] data, int components, int[] out) throws IOException {
        final int oc = table.opposite(c);
        if (oc == Connectivity.INVALID) return false;
        final int opp = valueOfVertex(table.vertex(oc));
        final int next = valueOfVertex(table.vertex(Connectivity.next(oc)));
        final int prev = valueOfVertex(table.vertex(Connectivity.previous(oc)));
        if (opp >= entry || next >= entry || prev >= entry) return false;
        for (int i = 0; i < components; i++) {
            final long v = (long) data[next * components + i] + data[prev * components + i] - data[opp * components + i];
            out[i] = (int) v;
        }
        return true;
    }

    private void restoreParallelogram(int[] data, int size, int components) throws IOException {
        transform.init(components);
        final int[] pred = new int[components];
        transform.apply(pred, 0, data, 0);
        checkSize(size, components);
        final int count = valueToCorner.getCount();
        for (int p = 1; p < count; p++) {
            final int dst = p * components;
            if (parallelogram(p, cornerOfValue(p), data, components, pred)) {
                transform.apply(pred, 0, data, dst);
            } else {
                transform.apply(data, dst - components, data, dst);
            }
        }
    }

    // 頂点の周りのすべての平行四辺形の平均 (古い形式)
    private void restoreMultiParallelogram(int[] data, int size, int components) throws IOException {
        transform.init(components);
        final int[] pred = new int[components];
        final int[] one = new int[components];
        transform.apply(pred, 0, data, 0);
        checkSize(size, components);
        final int count = valueToCorner.getCount();
        for (int p = 1; p < count; p++) {
            final int start = cornerOfValue(p);
            int corner = start;
            int found = 0;
            java.util.Arrays.fill(pred, 0);
            while (corner != Connectivity.INVALID) {
                budget.step();
                if (parallelogram(p, corner, data, components, one)) {
                    for (int i = 0; i < components; i++) pred[i] += one[i];
                    found++;
                }
                corner = table.swingRight(corner);
                if (corner == start) corner = Connectivity.INVALID;
            }
            final int dst = p * components;
            if (found == 0) {
                transform.apply(data, dst - components, data, dst);
            } else {
                for (int i = 0; i < components; i++) pred[i] /= found;
                transform.apply(pred, 0, data, dst);
            }
        }
    }

    // 平行四辺形のうち「折り目」でないものだけの平均
    private void restoreConstrained(int[] data, int size, int components) throws IOException {
        transform.init(components);
        final int[][] preds = new int[MAX_PARALLELOGRAMS][components];
        transform.apply(preds[0], 0, data, 0);
        final int[] position = new int[MAX_PARALLELOGRAMS];
        final int[] multi = new int[components];
        checkSize(size, components);
        final int count = valueToCorner.getCount();
        for (int p = 1; p < count; p++) {
            final int start = cornerOfValue(p);
            int corner = start;
            int found = 0;
            boolean firstPass = true;
            while (corner != Connectivity.INVALID) {
                budget.step();
                if (parallelogram(p, corner, data, components, preds[found])) {
                    if (++found == MAX_PARALLELOGRAMS) break;
                }
                // まず左へ回り、境界に当たったら最初の角から右へ回る
                corner = firstPass ? table.swingLeft(corner) : table.swingRight(corner);
                if (corner == start) break;
                if (corner == Connectivity.INVALID && firstPass) {
                    firstPass = false;
                    corner = table.swingRight(start);
                }
            }
            int used = 0;
            if (found > 0) {
                java.util.Arrays.fill(multi, 0);
                final int context = found - 1;
                for (int i = 0; i < found; i++) {
                    final int pos = position[context]++;
                    if (crease[context] == null || pos >= crease[context].length) {
                        throw new IOException("Draco crease flags ran out");
                    }
                    if (!crease[context][pos]) {
                        used++;
                        for (int j = 0; j < components; j++) multi[j] += preds[i][j];
                    }
                }
            }
            final int dst = p * components;
            if (used == 0) {
                transform.apply(data, dst - components, data, dst);
            } else {
                for (int i = 0; i < components; i++) multi[i] /= used;
                transform.apply(multi, 0, data, dst);
            }
        }
    }

    // ---------- UV ----------

    // 古い形式の UV の予測。位置を float で計算する
    private void restoreTexCoordsDeprecated(int[] data, int size, int components, int[] pointIds) throws IOException {
        if (components != 2) throw new IOException("Draco UV prediction needs 2 components");
        transform.init(components);
        checkSize(size, components);
        final int[] pred = new int[2];
        final float[] tip = new float[3];
        final float[] nextPos = new float[3];
        final float[] prevPos = new float[3];
        final int count = valueToCorner.getCount();
        for (int p = 0; p < count; p++) {
            final int c = cornerOfValue(p);
            final int nextId = valueOfVertex(table.vertex(Connectivity.next(c)));
            final int prevId = valueOfVertex(table.vertex(Connectivity.previous(c)));
            if (prevId < p && nextId < p) {
                final float nu = data[nextId * 2];
                final float nv = data[nextId * 2 + 1];
                final float pu = data[prevId * 2];
                final float pv = data[prevId * 2 + 1];
                if (pu == nu && pv == nv) {
                    pred[0] = floatToIntOrMin(pu);
                    pred[1] = floatToIntOrMin(pv);
                } else {
                    parent.floatPosition(pointIds[p], tip);
                    parent.floatPosition(pointIds[nextId], nextPos);
                    parent.floatPosition(pointIds[prevId], prevPos);
                    final float pnx = prevPos[0] - nextPos[0];
                    final float pny = prevPos[1] - nextPos[1];
                    final float pnz = prevPos[2] - nextPos[2];
                    final float cnx = tip[0] - nextPos[0];
                    final float cny = tip[1] - nextPos[1];
                    final float cnz = tip[2] - nextPos[2];
                    final float pnNorm = 0f + pnx * pnx + pny * pny + pnz * pnz;
                    float s;
                    float t;
                    if (version < ByteReader.V12 || pnNorm > 0) {
                        s = (0f + pnx * cnx + pny * cny + pnz * cnz) / pnNorm;
                        final float dx = cnx - pnx * s;
                        final float dy = cny - pny * s;
                        final float dz = cnz - pnz * s;
                        t = (float) Math.sqrt((0f + dx * dx + dy * dy + dz * dz) / pnNorm);
                    } else {
                        s = 0;
                        t = 0;
                    }
                    final float pnu = pu - nu;
                    final float pnv = pv - nv;
                    final float pnus = pnu * s + nu;
                    final float pnut = pnu * t;
                    final float pnvs = pnv * s + nv;
                    final float pnvt = pnv * t;
                    final float u;
                    final float v;
                    if (popOrientation()) {
                        u = pnus - pnvt;
                        v = pnvs + pnut;
                    } else {
                        u = pnus + pnvt;
                        v = pnvs - pnut;
                    }
                    pred[0] = roundToIntOrMin(u);
                    pred[1] = roundToIntOrMin(v);
                }
            } else {
                deltaFallback(data, p, prevId, nextId, pred);
            }
            transform.apply(pred, 0, data, p * 2);
        }
    }

    private static int floatToIntOrMin(float f) {
        if (Float.isNaN(f) || (double) f > Integer.MAX_VALUE || (double) f < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) f;
    }

    private static int roundToIntOrMin(float f) {
        final double r = Math.floor(f + 0.5);
        if (Double.isNaN(r) || r > Integer.MAX_VALUE || r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    // 両隣の UV がそろっていないときは、使える近くの値をそのまま予測にする
    private static void deltaFallback(int[] data, int p, int prevId, int nextId, int[] pred) {
        int offset = 0;
        if (prevId < p) offset = prevId * 2;
        if (nextId < p) {
            offset = nextId * 2;
        } else if (p > 0) {
            offset = (p - 1) * 2;
        } else {
            pred[0] = 0;
            pred[1] = 0;
            return;
        }
        pred[0] = data[offset];
        pred[1] = data[offset + 1];
    }

    // 整数だけで計算する UV の予測。位置の三角形を UV の空間へ写す
    private void restoreTexCoordsPortable(int[] data, int size, int components, int[] pointIds) throws IOException {
        if (components != 2) throw new IOException("Draco UV prediction needs 2 components");
        transform.init(components);
        checkSize(size, components);
        final int[] pred = new int[2];
        final long[] tip = new long[3];
        final long[] nextPos = new long[3];
        final long[] prevPos = new long[3];
        final int count = valueToCorner.getCount();
        for (int p = 0; p < count; p++) {
            final int c = cornerOfValue(p);
            final int nextId = valueOfVertex(table.vertex(Connectivity.next(c)));
            final int prevId = valueOfVertex(table.vertex(Connectivity.previous(c)));
            boolean done = false;
            if (prevId < p && nextId < p) {
                final long nu = data[nextId * 2];
                final long nv = data[nextId * 2 + 1];
                final long pu = data[prevId * 2];
                final long pv = data[prevId * 2 + 1];
                if (pu == nu && pv == nv) {
                    pred[0] = (int) pu;
                    pred[1] = (int) pv;
                    done = true;
                } else {
                    parent.longPosition(pointIds[p], tip);
                    parent.longPosition(pointIds[nextId], nextPos);
                    parent.longPosition(pointIds[prevId], prevPos);
                    done = portablePrediction(tip, nextPos, prevPos, nu, nv, pu, pv, pred);
                }
            }
            if (!done) deltaFallback(data, p, prevId, nextId, pred);
            transform.apply(pred, 0, data, p * 2);
        }
    }

    /**
     * 位置の三角形 N, P, C から、UV の C を求める。計算は 64 ビットで、桁あふれは元の実装と同じく回す。
     * N と P が同じ位置で予測できないときは false。途中の掛け算が 64 ビットを超えるときはデータの誤り。
     */
    private boolean portablePrediction(long[] tip, long[] next, long[] prev, long nu, long nv, long pu, long pv,
                                       int[] pred) throws IOException {
        final long pnx = prev[0] - next[0];
        final long pny = prev[1] - next[1];
        final long pnz = prev[2] - next[2];
        final long pnNorm = pnx * pnx + pny * pny + pnz * pnz;
        if (pnNorm == 0) return false;
        final long cnx = tip[0] - next[0];
        final long cny = tip[1] - next[1];
        final long cnz = tip[2] - next[2];
        final long cnDotPn = pnx * cnx + pny * cny + pnz * cnz;
        final long pnu = pu - nu;
        final long pnv = pv - nv;
        // 掛け算が 64 ビットを超えるときは誤りにする (元の実装も復号に失敗する)
        final long nAbsMax = Math.max(Math.abs(nu), Math.abs(nv));
        if (Long.compareUnsigned(nAbsMax, Long.divideUnsigned(Long.MAX_VALUE, pnNorm)) > 0) throw new IOException("Draco UV prediction overflows");
        final long pnuvAbsMax = Math.max(Math.abs(pnu), Math.abs(pnv));
        if (pnuvAbsMax == 0) throw new IOException("Draco UV prediction overflows");
        if (Math.abs(cnDotPn) > Long.MAX_VALUE / pnuvAbsMax) throw new IOException("Draco UV prediction overflows");
        final long xu = nu * pnNorm + pnu * cnDotPn;
        final long xv = nv * pnNorm + pnv * cnDotPn;
        final long pnAbsMax = Math.max(Math.max(Math.abs(pnx), Math.abs(pny)), Math.abs(pnz));
        if (pnAbsMax == 0) throw new IOException("Draco UV prediction overflows");
        if (Math.abs(cnDotPn) > Long.MAX_VALUE / pnAbsMax) throw new IOException("Draco UV prediction overflows");
        // C を PN に落とした点 X と C の距離の 2 乗
        final long dx = tip[0] - (next[0] + cnDotPn * pnx / pnNorm);
        final long dy = tip[1] - (next[1] + cnDotPn * pny / pnNorm);
        final long dz = tip[2] - (next[2] + cnDotPn * pnz / pnNorm);
        final long cxNorm = dx * dx + dy * dy + dz * dz;
        final long norm = Bits.intSqrt(cxNorm * pnNorm);
        final long cxu = pnv * norm;
        final long cxv = -pnu * norm;
        final long u;
        final long v;
        if (popOrientation()) {
            u = (xu + cxu) / pnNorm;
            v = (xv + cxv) / pnNorm;
        } else {
            u = (xu - cxu) / pnNorm;
            v = (xv - cxv) / pnNorm;
        }
        pred[0] = (int) u;
        pred[1] = (int) v;
        return true;
    }

    // ---------- 法線 ----------

    // 周りの三角形の面積で重み付けした法線から予測する
    private void restoreGeometricNormal(int[] data, int size, int components, int[] pointIds) throws IOException {
        normalBox.setQuantizationBits(transform.quantizationBits());
        checkSize(size, components);
        final long[] normal = new long[3];
        final int[] vec = new int[3];
        final int[] coords = new int[2];
        final long[] center = new long[3];
        final long[] a = new long[3];
        final long[] b = new long[3];
        final int count = valueToCorner.getCount();
        for (int id = 0; id < count; id++) {
            final int corner = cornerOfValue(id);
            positionOfCorner(corner, pointIds, center);
            java.util.Arrays.fill(normal, 0);
            int c = corner;
            boolean left = true;
            while (c != Connectivity.INVALID) {
                budget.step();
                final int base = oneTriangle ? corner : c;
                positionOfCorner(Connectivity.next(base), pointIds, a);
                positionOfCorner(Connectivity.previous(base), pointIds, b);
                final long ax = a[0] - center[0];
                final long ay = a[1] - center[1];
                final long az = a[2] - center[2];
                final long bx = b[0] - center[0];
                final long by = b[1] - center[1];
                final long bz = b[2] - center[2];
                normal[0] += ay * bz - az * by;
                normal[1] += az * bx - ax * bz;
                normal[2] += ax * by - ay * bx;
                // 頂点の周りを左へ回り、境界に当たったら最初の角から右へ回る
                if (left) {
                    c = table.swingLeft(c);
                    if (c == Connectivity.INVALID) {
                        c = table.swingRight(corner);
                        left = false;
                    } else if (c == corner) {
                        c = Connectivity.INVALID;
                    }
                } else {
                    c = table.swingRight(c);
                }
            }
            final long upper = 1L << 29;
            final long absSum = oneTriangle ? (int) absSum(normal) : absSum(normal);
            if (absSum > upper) {
                final long quotient = absSum / upper;
                for (int i = 0; i < 3; i++) normal[i] /= quotient;
            }
            for (int i = 0; i < 3; i++) vec[i] = (int) normal[i];
            normalBox.canonicalizeIntegerVector(vec);
            if (flips.next()) {
                for (int i = 0; i < 3; i++) vec[i] = -vec[i];
            }
            normalBox.integerVectorToCoords(vec, coords);
            transform.apply(coords, 0, data, id * 2);
        }
    }

    // 絶対値の和。64 ビットに収まらなければ最大値にする
    private static long absSum(long[] v) {
        long result = 0;
        for (long x : v) {
            final long a = Math.abs(x);
            if (result > Long.MAX_VALUE - a) return Long.MAX_VALUE;
            result += a;
        }
        return result;
    }

    private void positionOfCorner(int corner, int[] pointIds, long[] out) throws IOException {
        final int value = valueOfVertex(table.vertex(corner));
        if (value >= pointIds.length) throw new IOException("Invalid Draco value in prediction");
        parent.longPosition(pointIds[value], out);
    }

    // ---------- 親の属性 ----------

    /**
     * 予測に使う位置の値。2.0 以降は量子化された整数 (portable)、それより前は展開後の値を読む。
     */
    static final class Parent {
        private final PointAttribute attribute;
        private final int[] portable;
        private final int portableValues;

        Parent(PointAttribute attribute, int[] portable, int portableValues) {
            this.attribute = attribute;
            this.portable = portable;
            this.portableValues = portableValues;
        }

        void longPosition(int point, long[] out) throws IOException {
            final int value = attribute.mappedIndex(point);
            java.util.Arrays.fill(out, 0);
            if (portable != null) {
                if (value < 0 || value >= portableValues) throw new IOException("Invalid Draco position index");
                for (int i = 0; i < 3; i++) out[i] = portable[value * 3 + i];
            } else {
                attribute.convertToLong(value, out);
            }
        }

        void floatPosition(int point, float[] out) throws IOException {
            final int value = attribute.mappedIndex(point);
            java.util.Arrays.fill(out, 0);
            if (portable != null) {
                if (value < 0 || value >= portableValues) throw new IOException("Invalid Draco position index");
                for (int i = 0; i < 3; i++) out[i] = portable[value * 3 + i];
            } else {
                attribute.convertToFloat(value, out);
            }
        }
    }

    // ---------- 変換 ----------

    /**
     * 予測と差から元の値を作る変換。WRAP は値の範囲で折り返し、OCTAHEDRON 系は法線の (s, t) 用。
     */
    static final class Transform {
        final int type;
        private int components;
        private int min;
        private int max;
        private int maxDif;
        private int[] clamped = new int[0];
        final Octahedron box = new Octahedron();

        Transform(int type) {
            this.type = type;
        }

        void init(int components) {
            if (type == TRANSFORM_WRAP) {
                this.components = components;
                clamped = new int[components];
            }
        }

        boolean correctionsPositive() {
            return type != TRANSFORM_WRAP;
        }

        int quantizationBits() {
            return type == TRANSFORM_WRAP ? -1 : box.quantizationBits;
        }

        void decodeData(ByteReader in) throws IOException {
            if (type == TRANSFORM_WRAP) {
                min = in.i32();
                max = in.i32();
                final long dif = (long) max - min;
                if (dif < 0 || dif >= Integer.MAX_VALUE) throw new IOException("Invalid Draco wrap range");
                maxDif = 1 + (int) dif;
            } else {
                final int maxQuantized = in.i32();
                if (type == TRANSFORM_OCTAHEDRON_CANONICALIZED || in.version < ByteReader.V22) {
                    in.i32(); // 中心の値。使わない
                }
                if (maxQuantized % 2 == 0 || !box.setQuantizationBits(Bits.mostSignificantBit(maxQuantized) + 1)) {
                    throw new IOException("Invalid Draco normal quantization");
                }
            }
        }

        /**
         * 予測 pred[po..] と差 data[off..] から元の値を data[off..] に書く。
         */
        void apply(int[] pred, int po, int[] data, int off) {
            switch (type) {
                case TRANSFORM_WRAP:
                    for (int i = 0; i < components; i++) {
                        final int p = pred[po + i];
                        clamped[i] = p > max ? max : (p < min ? min : p);
                    }
                    for (int i = 0; i < components; i++) {
                        long v = (long) clamped[i] + data[off + i];
                        if (v > max) {
                            v -= maxDif;
                        } else if (v < min) {
                            v += maxDif;
                        }
                        data[off + i] = (int) v;
                    }
                    break;
                case TRANSFORM_OCTAHEDRON:
                    applyOctahedron(pred[po], pred[po + 1], data, off);
                    break;
                default:
                    applyCanonicalized(pred[po], pred[po + 1], data, off);
                    break;
            }
        }

        private void applyOctahedron(int ps, int pt, int[] data, int off) {
            final int[] p = {ps - box.center, pt - box.center};
            final boolean inDiamond = box.isInDiamond(p[0], p[1]);
            if (!inDiamond) box.invertDiamond(p);
            final int[] o = {box.modMax(p[0] + data[off]), box.modMax(p[1] + data[off + 1])};
            if (!inDiamond) box.invertDiamond(o);
            data[off] = o[0] + box.center;
            data[off + 1] = o[1] + box.center;
        }

        private void applyCanonicalized(int ps, int pt, int[] data, int off) {
            final int[] p = {ps - box.center, pt - box.center};
            final boolean inDiamond = box.isInDiamond(p[0], p[1]);
            if (!inDiamond) box.invertDiamond(p);
            final boolean bottomLeft = (p[0] == 0 && p[1] == 0) || (p[0] < 0 && p[1] <= 0);
            final int rotation = rotationCount(p[0], p[1]);
            if (!bottomLeft) rotate(p, rotation);
            final int[] o = {box.modMax(p[0] + data[off]), box.modMax(p[1] + data[off + 1])};
            if (!bottomLeft) rotate(o, (4 - rotation) % 4);
            if (!inDiamond) box.invertDiamond(o);
            data[off] = o[0] + box.center;
            data[off + 1] = o[1] + box.center;
        }

        // 予測を左下の区画へ回すための回転の回数
        private static int rotationCount(int x, int y) {
            if (x == 0) {
                if (y == 0) return 0;
                return y > 0 ? 3 : 1;
            }
            if (x > 0) return y >= 0 ? 2 : 1;
            return y <= 0 ? 0 : 3;
        }

        private static void rotate(int[] p, int count) {
            final int x = p[0];
            final int y = p[1];
            switch (count) {
                case 1:
                    p[0] = y;
                    p[1] = -x;
                    break;
                case 2:
                    p[0] = -x;
                    p[1] = -y;
                    break;
                case 3:
                    p[0] = -y;
                    p[1] = x;
                    break;
                default:
                    break;
            }
        }
    }
}
