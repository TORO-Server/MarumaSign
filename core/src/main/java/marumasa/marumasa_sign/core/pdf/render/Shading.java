package marumasa.marumasa_sign.core.pdf.render;

import marumasa.marumasa_sign.core.pdf.color.ColorSpaces;
import marumasa.marumasa_sign.core.pdf.color.PdfColorSpace;
import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;
import marumasa.marumasa_sign.core.pdf.cos.PdfDocument;
import marumasa.marumasa_sign.core.pdf.cos.PdfStream;
import marumasa.marumasa_sign.core.pdf.function.PdfFunction;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * シェーディング (8.7.4.5)。Type 1 から 3 は点ごとの色を計算し、Type 4 から 7 (メッシュ) は三角形の並びにする。
 * メッシュの三角形の数と、関数を表にする大きさには上限を設ける。
 */
final class Shading {

    // メッシュの三角形の数の上限
    static final int MAX_TRIANGLES = 1 << 21;
    // 関数の値を表にするときの段階の数
    private static final int TABLE_SIZE = 1024;

    final int type;
    final PdfColorSpace space;
    private final PdfFunction[] functions;
    // Type 1
    private double[] domain2;
    private AffineTransform inverseMatrix;
    // Type 2 と 3
    private double[] coords;
    private double t0 = 0, t1 = 1;
    private boolean extend0, extend1;
    private int[] table;
    // Type 4 から 7: 三角形 {x0 y0 x1 y1 x2 y2} と、頂点の色 (関数があれば t、なければ色の成分)
    final List<double[]> triangles = new ArrayList<>();
    final List<float[][]> triangleColors = new ArrayList<>();
    final double[] bbox;
    // シェーディングの外を塗る色 (Background、ARGB)。なければ 0。パターンとして使うときだけ塗る (sh では使わない)
    int background;

    private Shading(int type, PdfColorSpace space, PdfFunction[] functions, double[] bbox) {
        this.type = type;
        this.space = space;
        this.functions = functions;
        this.bbox = bbox;
    }

    static Shading create(Object value, PdfDict resources) throws IOException {
        final PdfDict dict = value instanceof PdfStream ? ((PdfStream) value).dict : value instanceof PdfDict ? (PdfDict) value : null;
        if (dict == null) throw new IOException("Bad shading");
        final int type = dict.integer("ShadingType", 0);
        final PdfColorSpace space = ColorSpaces.create(dict.get("ColorSpace"), resources);
        PdfFunction[] functions = null;
        final Object f = dict.get("Function");
        if (f instanceof PdfArray) {
            final PdfArray list = (PdfArray) f;
            if (list.size() == 0 || list.size() > 32) throw new IOException("Bad shading functions");
            functions = new PdfFunction[list.size()];
            for (int i = 0; i < functions.length; i++) functions[i] = PdfFunction.create(list.get(i));
        } else if (f != null) {
            functions = new PdfFunction[]{PdfFunction.create(f)};
        }
        final Shading shading = new Shading(type, space, functions, PdfDocument.rectangle(dict.array("BBox")));
        final PdfArray background = dict.array("Background");
        final double[] values = background == null ? null : background.numbers();
        if (values != null && values.length >= space.components()) {
            final float[] components = new float[space.components()];
            for (int i = 0; i < components.length; i++) components[i] = (float) values[i];
            shading.background = 0xFF000000 | space.toRgb(components);
        }
        switch (type) {
            case 1:
                shading.readFunctionBased(dict);
                break;
            case 2:
            case 3:
                shading.readAxialRadial(dict);
                break;
            case 4:
            case 5:
            case 6:
            case 7:
                if (!(value instanceof PdfStream)) throw new IOException("Mesh shading needs a stream");
                shading.readMesh((PdfStream) value);
                break;
            default:
                throw new IOException("Unknown shading type " + type);
        }
        return shading;
    }

    // ---------- 色 ----------

    // 関数の出力 (または色の成分) を ARGB にする
    private int color(double[] input) {
        final float[] components = new float[space.components()];
        if (functions == null) {
            for (int i = 0; i < components.length && i < input.length; i++) components[i] = (float) input[i];
        } else if (functions.length == 1) {
            final double[] out = new double[Math.max(functions[0].outputs(), components.length)];
            functions[0].evaluate(input, out);
            for (int i = 0; i < components.length; i++) components[i] = (float) out[i];
        } else {
            // 成分ごとの関数
            final double[] out = new double[Math.max(1, functions[0].outputs())];
            for (int i = 0; i < components.length && i < functions.length; i++) {
                functions[i].evaluate(input, out);
                components[i] = (float) out[0];
            }
        }
        return 0xFF000000 | space.toRgb(components);
    }

    // t (t0 から t1) の色の表
    private int[] table() {
        if (table == null) {
            table = new int[TABLE_SIZE];
            for (int i = 0; i < TABLE_SIZE; i++) table[i] = color(new double[]{t0 + (t1 - t0) * i / (TABLE_SIZE - 1)});
        }
        return table;
    }

    int colorForT(double t) {
        final int[] colors = table();
        final double s = t1 == t0 ? 0 : (t - t0) / (t1 - t0);
        final int index = (int) Math.round(Math.max(0, Math.min(1, s)) * (TABLE_SIZE - 1));
        return colors[index];
    }

    int colorForComponents(float[] components) {
        if (functions != null) return colorForT(components[0]);
        final double[] input = new double[components.length];
        for (int i = 0; i < input.length; i++) input[i] = components[i];
        return color(input);
    }

    boolean hasFunction() {
        return functions != null;
    }

    // ---------- Type 1 ----------

    private void readFunctionBased(PdfDict dict) throws IOException {
        if (functions == null) throw new IOException("Function-based shading without a function");
        final PdfArray d = dict.array("Domain");
        final double[] values = d == null ? null : d.numbers();
        domain2 = values != null && values.length >= 4 ? values : new double[]{0, 1, 0, 1};
        final PdfArray m = dict.array("Matrix");
        final double[] matrix = m == null ? null : m.numbers();
        AffineTransform transform = matrix != null && matrix.length >= 6
                ? new AffineTransform(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]) : new AffineTransform();
        try {
            inverseMatrix = transform.createInverse();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            throw new IOException("Bad shading matrix");
        }
    }

    // ---------- Type 2 と 3 ----------

    private void readAxialRadial(PdfDict dict) throws IOException {
        if (functions == null) throw new IOException("Shading without a function");
        final PdfArray c = dict.array("Coords");
        coords = c == null ? null : c.numbers();
        if (coords == null || coords.length < (type == 2 ? 4 : 6)) throw new IOException("Bad Coords");
        final PdfArray d = dict.array("Domain");
        final double[] domain = d == null ? null : d.numbers();
        if (domain != null && domain.length >= 2) {
            t0 = domain[0];
            t1 = domain[1];
        }
        final PdfArray e = dict.array("Extend");
        if (e != null && e.size() >= 2) {
            extend0 = Boolean.TRUE.equals(e.get(0));
            extend1 = Boolean.TRUE.equals(e.get(1));
        }
    }

    /**
     * シェーディングの空間の点の色 (ARGB)。塗らない点は 0。Type 1 から 3 だけ。
     */
    int colorAt(double x, double y) {
        switch (type) {
            case 1: {
                final double[] point = {x, y};
                inverseMatrix.transform(point, 0, point, 0, 1);
                if (point[0] < domain2[0] || point[0] > domain2[1] || point[1] < domain2[2] || point[1] > domain2[3]) return 0;
                return color(point);
            }
            case 2: {
                final double dx = coords[2] - coords[0], dy = coords[3] - coords[1];
                final double length = dx * dx + dy * dy;
                double s = length == 0 ? 0 : ((x - coords[0]) * dx + (y - coords[1]) * dy) / length;
                if (s < 0) {
                    if (!extend0) return 0;
                    s = 0;
                } else if (s > 1) {
                    if (!extend1) return 0;
                    s = 1;
                }
                return colorForT(t0 + s * (t1 - t0));
            }
            case 3: {
                final double s = radial(x, y);
                if (Double.isNaN(s)) return 0;
                return colorForT(t0 + s * (t1 - t0));
            }
            default:
                return 0;
        }
    }

    // 点を通る円のうち、いちばん大きい s (0 から 1、延長があればその外も)。なければ NaN
    private double radial(double x, double y) {
        final double x0 = coords[0], y0 = coords[1], r0 = coords[2], x1 = coords[3], y1 = coords[4], r1 = coords[5];
        final double cdx = x1 - x0, cdy = y1 - y0, dr = r1 - r0;
        final double pdx = x - x0, pdy = y - y0;
        final double a = cdx * cdx + cdy * cdy - dr * dr;
        final double b = pdx * cdx + pdy * cdy + r0 * dr;
        final double c = pdx * pdx + pdy * pdy - r0 * r0;
        double first, second;
        if (Math.abs(a) < 1e-12) {
            if (b == 0) return Double.NaN;
            first = second = c / (2 * b);
        } else {
            final double discriminant = b * b - a * c;
            if (discriminant < 0) return Double.NaN;
            final double root = Math.sqrt(discriminant);
            first = Math.max((b + root) / a, (b - root) / a);
            second = Math.min((b + root) / a, (b - root) / a);
        }
        for (double s : new double[]{first, second}) {
            if (r0 + s * dr < 0) continue;
            if (s >= 0 && s <= 1) return s;
            if (s > 1 && extend1) return 1;
            if (s < 0 && extend0) return 0;
        }
        return Double.NaN;
    }

    // ---------- Type 4 から 7 (メッシュ) ----------

    // ビットを読む
    private static final class BitReader {
        final byte[] data;
        long bit;

        BitReader(byte[] data) {
            this.data = data;
        }

        boolean hasMore(long bits) {
            return bit + bits <= (long) data.length * 8;
        }

        long read(int bits) {
            long value = 0;
            for (int i = 0; i < bits; i++) {
                final long p = bit + i;
                value = (value << 1) | ((data[(int) (p >> 3)] >> (7 - (p & 7))) & 1);
            }
            bit += bits;
            return value;
        }

        void align() {
            bit = (bit + 7) & ~7L;
        }
    }

    private int bitsPerCoordinate, bitsPerComponent, bitsPerFlag;
    private double[] decode;
    private int colorCount;

    private void readMesh(PdfStream stream) throws IOException {
        final PdfDict dict = stream.dict;
        bitsPerCoordinate = dict.integer("BitsPerCoordinate", 0);
        bitsPerComponent = dict.integer("BitsPerComponent", 0);
        bitsPerFlag = dict.integer("BitsPerFlag", 8);
        if (bitsPerCoordinate < 1 || bitsPerCoordinate > 32 || bitsPerComponent < 1 || bitsPerComponent > 16
                || (type != 5 && (bitsPerFlag < 2 || bitsPerFlag > 8))) {
            throw new IOException("Bad mesh shading parameters");
        }
        colorCount = functions != null ? 1 : space.components();
        final PdfArray d = dict.array("Decode");
        decode = d == null ? null : d.numbers();
        if (decode == null || decode.length < 4 + 2 * colorCount) throw new IOException("Bad mesh Decode");
        final BitReader reader = new BitReader(stream.decode(PdfDocument.MAX_DECODED));
        if (type == 4) readFreeForm(reader);
        else if (type == 5) readLattice(reader, dict.integer("VerticesPerRow", 0));
        else readPatches(reader, type == 7);
    }

    private double[] readPoint(BitReader reader) {
        final double max = bitsPerCoordinate == 32 ? 4294967295.0 : (1L << bitsPerCoordinate) - 1;
        final double x = decode[0] + reader.read(bitsPerCoordinate) * (decode[1] - decode[0]) / max;
        final double y = decode[2] + reader.read(bitsPerCoordinate) * (decode[3] - decode[2]) / max;
        return new double[]{x, y};
    }

    private float[] readColor(BitReader reader) {
        final double max = (1L << bitsPerComponent) - 1;
        final float[] color = new float[colorCount];
        for (int i = 0; i < colorCount; i++) {
            color[i] = (float) (decode[4 + 2 * i] + reader.read(bitsPerComponent) * (decode[5 + 2 * i] - decode[4 + 2 * i]) / max);
        }
        return color;
    }

    private int vertexBits() {
        return 2 * bitsPerCoordinate + colorCount * bitsPerComponent;
    }

    // 上限を超えた三角形は捨てる (そこまでの分は描く)
    private void addTriangle(double[] a, double[] b, double[] c, float[] ca, float[] cb, float[] cc) {
        if (triangles.size() >= MAX_TRIANGLES) return;
        triangles.add(new double[]{a[0], a[1], b[0], b[1], c[0], c[1]});
        triangleColors.add(new float[][]{ca, cb, cc});
    }

    private void readFreeForm(BitReader reader) throws IOException {
        double[] pa = null, pb = null, pc = null;
        float[] ca = null, cb = null, cc = null;
        while (reader.hasMore(bitsPerFlag + vertexBits())) {
            final int flag = (int) reader.read(bitsPerFlag);
            final double[] point = readPoint(reader);
            final float[] color = readColor(reader);
            reader.align();
            if (flag == 0 || pa == null) {
                // 新しい三角形: あと 2 つの頂点を読む
                pa = point;
                ca = color;
                if (!reader.hasMore(2 * (bitsPerFlag + vertexBits()))) break;
                reader.read(bitsPerFlag);
                pb = readPoint(reader);
                cb = readColor(reader);
                reader.align();
                reader.read(bitsPerFlag);
                pc = readPoint(reader);
                cc = readColor(reader);
                reader.align();
            } else if (flag == 1) {
                pa = pb;
                ca = cb;
                pb = pc;
                cb = cc;
                pc = point;
                cc = color;
            } else {
                pb = pc;
                cb = cc;
                pc = point;
                cc = color;
            }
            addTriangle(pa, pb, pc, ca, cb, cc);
        }
    }

    private void readLattice(BitReader reader, int perRow) throws IOException {
        if (perRow < 2) throw new IOException("Bad VerticesPerRow");
        final List<double[]> points = new ArrayList<>();
        final List<float[]> colors = new ArrayList<>();
        while (reader.hasMore(vertexBits())) {
            points.add(readPoint(reader));
            colors.add(readColor(reader));
            reader.align();
            if (points.size() > MAX_TRIANGLES) break;
        }
        final int rows = points.size() / perRow;
        for (int r = 0; r + 1 < rows; r++) {
            for (int c = 0; c + 1 < perRow; c++) {
                final int i = r * perRow + c;
                addTriangle(points.get(i), points.get(i + 1), points.get(i + perRow), colors.get(i), colors.get(i + 1), colors.get(i + perRow));
                addTriangle(points.get(i + 1), points.get(i + perRow + 1), points.get(i + perRow), colors.get(i + 1),
                        colors.get(i + perRow + 1), colors.get(i + perRow));
            }
        }
    }

    // パッチの 4 x 4 の制御点 (行 x 4 + 列) と、角の色 (0: 行 0 列 0、1: 行 0 列 3、2: 行 3 列 0、3: 行 3 列 3)
    private void readPatches(BitReader reader, boolean tensor) throws IOException {
        double[][] ps = new double[16][];
        float[][] cs = new float[4][];
        boolean first = true;
        while (reader.hasMore(bitsPerFlag)) {
            final int flag = (int) reader.read(bitsPerFlag);
            final int newPoints = (flag == 0 ? 12 : 8) + (tensor ? 4 : 0);
            final int newColors = flag == 0 ? 4 : 2;
            if (!reader.hasMore((long) newPoints * 2 * bitsPerCoordinate + (long) newColors * colorCount * bitsPerComponent)) break;
            final double[][] d = new double[newPoints][];
            for (int i = 0; i < newPoints; i++) d[i] = readPoint(reader);
            final float[][] c = new float[newColors][];
            for (int i = 0; i < newColors; i++) c[i] = readColor(reader);
            reader.align();
            final double[][] old = ps.clone();
            final float[][] oldColors = cs.clone();
            if (flag == 0 || first) {
                if (flag != 0) break; // 最初のパッチは前のパッチを使えない
                ps[0] = d[0];
                ps[4] = d[1];
                ps[8] = d[2];
                ps[12] = d[3];
                ps[13] = d[4];
                ps[14] = d[5];
                ps[15] = d[6];
                ps[11] = d[7];
                ps[7] = d[8];
                ps[3] = d[9];
                ps[2] = d[10];
                ps[1] = d[11];
                if (tensor) {
                    ps[5] = d[12];
                    ps[9] = d[13];
                    ps[10] = d[14];
                    ps[6] = d[15];
                }
                cs[0] = c[0];
                cs[2] = c[1];
                cs[3] = c[2];
                cs[1] = c[3];
            } else {
                // 前のパッチの辺を受け継ぐ
                final int[] shared;
                final int[] sharedColors;
                if (flag == 1) {
                    shared = new int[]{12, 13, 14, 15};
                    sharedColors = new int[]{2, 3};
                } else if (flag == 2) {
                    shared = new int[]{15, 11, 7, 3};
                    sharedColors = new int[]{3, 1};
                } else {
                    shared = new int[]{3, 2, 1, 0};
                    sharedColors = new int[]{1, 0};
                }
                ps = new double[16][];
                ps[0] = old[shared[0]];
                ps[4] = old[shared[1]];
                ps[8] = old[shared[2]];
                ps[12] = old[shared[3]];
                ps[13] = d[0];
                ps[14] = d[1];
                ps[15] = d[2];
                ps[11] = d[3];
                ps[7] = d[4];
                ps[3] = d[5];
                ps[2] = d[6];
                ps[1] = d[7];
                if (tensor) {
                    ps[5] = d[8];
                    ps[9] = d[9];
                    ps[10] = d[10];
                    ps[6] = d[11];
                }
                cs = new float[4][];
                cs[0] = oldColors[sharedColors[0]];
                cs[2] = oldColors[sharedColors[1]];
                cs[3] = c[0];
                cs[1] = c[1];
            }
            first = false;
            if (!tensor) coonsInterior(ps);
            boolean complete = true;
            for (double[] p : ps) complete &= p != null;
            for (float[] color : cs) complete &= color != null;
            if (!complete) break;
            triangulate(ps, cs);
        }
    }

    // Coons パッチの内側の 4 点 (テンソル積のパッチとして描くため)
    private static void coonsInterior(double[][] p) {
        p[5] = combine(p[0], p[15], p[4], p[1], p[12], p[3], p[13], p[7]);
        p[6] = combine(p[3], p[12], p[2], p[7], p[0], p[15], p[4], p[14]);
        p[9] = combine(p[12], p[3], p[8], p[13], p[0], p[15], p[11], p[1]);
        p[10] = combine(p[15], p[0], p[11], p[14], p[12], p[3], p[2], p[8]);
    }

    // (-4 a - b + 6 (c + d) - 2 (e + f) + 3 (g + h)) / 9
    private static double[] combine(double[] a, double[] b, double[] c, double[] d, double[] e, double[] f, double[] g, double[] h) {
        final double[] r = new double[2];
        for (int i = 0; i < 2; i++) {
            r[i] = (-4 * a[i] - b[i] + 6 * (c[i] + d[i]) - 2 * (e[i] + f[i]) + 3 * (g[i] + h[i])) / 9;
        }
        return r;
    }

    // パッチの一辺を分ける数
    private static final int PATCH_SPLIT = 12;

    private void triangulate(double[][] ps, float[][] cs) {
        final int n = PATCH_SPLIT;
        final double[][] grid = new double[(n + 1) * (n + 1)][];
        final float[][] colors = new float[(n + 1) * (n + 1)][];
        for (int row = 0; row <= n; row++) {
            final double v = row / (double) n;
            final double[] bv = bernstein(v);
            for (int col = 0; col <= n; col++) {
                final double u = col / (double) n;
                final double[] bu = bernstein(u);
                double x = 0, y = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        final double m = bv[i] * bu[j];
                        x += ps[i * 4 + j][0] * m;
                        y += ps[i * 4 + j][1] * m;
                    }
                }
                grid[row * (n + 1) + col] = new double[]{x, y};
                final float[] color = new float[colorCount];
                for (int k = 0; k < colorCount; k++) {
                    color[k] = (float) ((cs[0][k] * (1 - u) + cs[1][k] * u) * (1 - v) + (cs[2][k] * (1 - u) + cs[3][k] * u) * v);
                }
                colors[row * (n + 1) + col] = color;
            }
        }
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                final int a = row * (n + 1) + col, b = a + 1, c = a + n + 1, d = c + 1;
                addTriangle(grid[a], grid[b], grid[c], colors[a], colors[b], colors[c]);
                addTriangle(grid[b], grid[d], grid[c], colors[b], colors[d], colors[c]);
            }
        }
    }

    private static double[] bernstein(double t) {
        final double s = 1 - t;
        return new double[]{s * s * s, 3 * t * s * s, 3 * t * t * s, t * t * t};
    }
}
