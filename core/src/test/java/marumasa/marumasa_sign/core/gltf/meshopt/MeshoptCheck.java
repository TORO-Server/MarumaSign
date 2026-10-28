package marumasa.marumasa_sign.core.gltf.meshopt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * meshopt の展開 (MeshoptDecoder) の動作と安全性を確かめる。
 * 仕様 (EXT_meshopt_compression / KHR_meshopt_compression の Appendix A, B) をそのまま逆にたどる、テスト用の符号化を持っていて、
 * 乱数で作ったデータを符号化して展開し、元に戻ることを確かめる。
 * そのあと、壊したデータを決めた時間だけ読ませて、IOException 以外の例外や長い時間にならないことを確かめる。
 * 引数: [壊したデータを試す秒数 (既定 20)]
 */
public final class MeshoptCheck {

    private static int failures;

    private MeshoptCheck() {
    }

    public static void main(String[] args) throws Exception {
        final long fuzzSeconds = args.length > 0 ? Long.parseLong(args[0]) : 20;
        testSpecExample();
        testVertexRoundTrip(new Random(1), 3000);
        testTriangleRoundTrip(new Random(2), 3000);
        testSequenceRoundTrip(new Random(3), 3000);
        testFilters(new Random(4), 20000);
        testInvalidParameters();
        testRegression();
        fuzz(new Random(5), fuzzSeconds);
        if (failures > 0) {
            System.out.println("MeshoptCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("MeshoptCheck: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            if (failures <= 20) System.out.println("  FAILED: " + message);
        }
    }

    // ---------- 仕様の例 ----------

    // 仕様の、4 ビットの差分の例 (16 個の差分) を、1 つのブロックとして展開する
    private static void testSpecExample() throws IOException {
        final int[] packed = {0x17, 0x5f, 0xf0, 0xbc, 0x77, 0xa9, 0x21, 0x00, 0x34, 0xb5};
        final int[] expected = {-1, -4, -3, 26, -91, 0, -6, 6, -4, -4, 5, -5, 1, -1, 0, 0};
        // 版 0、要素 16 個、大きさ 4。1 バイト目だけが 4 ビットの差分 (見出し 2)、残りの 3 バイトは差分なし (見出し 0)
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xA0);
        out.write(2);
        for (int b : packed) out.write(b);
        for (int j = 1; j < 4; j++) out.write(0);
        for (int i = 0; i < 28; i++) out.write(0);
        for (int i = 0; i < 4; i++) out.write(0);
        final byte[] source = out.toByteArray();
        final byte[] decoded = new byte[16 * 4];
        MeshoptDecoder.decodeVertexBuffer(decoded, 0, 16, 4, source, 0, source.length);
        int value = 0;
        boolean ok = true;
        for (int i = 0; i < 16; i++) {
            value = (value + expected[i]) & 0xFF;
            ok &= (decoded[i * 4] & 0xFF) == value && decoded[i * 4 + 1] == 0;
        }
        check(ok, "specification example");
        System.out.println("spec example: " + (ok ? "OK" : "NG"));
    }

    // ---------- 頂点属性 ----------

    private static void testVertexRoundTrip(Random rnd, int iterations) throws IOException {
        int done = 0;
        for (int it = 0; it < iterations; it++) {
            final int stride = 4 * (1 + rnd.nextInt(rnd.nextInt(4) == 0 ? 64 : 6));
            final int count = rnd.nextInt(4) == 0 ? rnd.nextInt(5) : rnd.nextInt(rnd.nextBoolean() ? 40 : 1200);
            final byte[] data = randomVertices(rnd, count, stride);
            final int version = rnd.nextInt(2);
            final byte[] encoded = encodeVertices(data, count, stride, version, rnd);
            // 前後に余分なデータを置いて、範囲の指定が正しく使われることも確かめる
            final int pad = rnd.nextInt(3);
            final byte[] source = new byte[encoded.length + pad * 2];
            Arrays.fill(source, (byte) 0x5A);
            System.arraycopy(encoded, 0, source, pad, encoded.length);
            final byte[] decoded = new byte[count * stride + pad];
            MeshoptDecoder.decodeVertexBuffer(decoded, pad, count, stride, source, pad, encoded.length);
            check(Arrays.equals(Arrays.copyOfRange(decoded, pad, decoded.length), data),
                    "vertex round trip (count=" + count + ", stride=" + stride + ", version=" + version + ")");
            done++;
        }
        System.out.println("vertex round trip: " + done + " buffers");
    }

    // 属性らしいデータ (ゆっくり変わる値、乱数、定数、浮動小数点数) を混ぜて作る
    static byte[] randomVertices(Random rnd, int count, int stride) {
        final byte[] data = new byte[count * stride];
        for (int k = 0; k < stride; k += 4) {
            final int kind = rnd.nextInt(5);
            float f = rnd.nextFloat() * 100f;
            int v = rnd.nextInt();
            for (int i = 0; i < count; i++) {
                final int p = i * stride + k;
                final int value;
                switch (kind) {
                    case 0: value = rnd.nextInt(); break;
                    case 1: value = v; break;
                    case 2: v += rnd.nextInt(7) - 3; value = v; break;
                    case 3: f += rnd.nextFloat() - 0.5f; value = Float.floatToIntBits(f); break;
                    default: value = (v + i * 3) & 0xFFFF | ((rnd.nextInt(3)) << 16); break;
                }
                for (int j = 0; j < 4; j++) data[p + j] = (byte) (value >> (j * 8));
            }
        }
        return data;
    }

    /**
     * 頂点属性を符号化する (テスト用)。モードや差分のビット数は、乱数で選んだ正しいもののどれかにする。
     */
    static byte[] encodeVertices(byte[] data, int count, int stride, int version, Random rnd) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xA0 | version);
        final int channels = stride / 4;
        final int[] modes = new int[channels];
        if (version == 1) {
            for (int c = 0; c < channels; c++) {
                final int m = rnd.nextInt(3);
                modes[c] = m == 2 ? 2 | (rnd.nextInt(16) << 4) : m;
            }
        }
        // 最初の要素の基準は、0、最初の要素、乱数のどれか
        final byte[] baseline = new byte[stride];
        final int baseKind = rnd.nextInt(3);
        if (baseKind == 1 && count > 0) System.arraycopy(data, 0, baseline, 0, stride);
        if (baseKind == 2) rnd.nextBytes(baseline);
        final byte[] previous = baseline.clone();

        final int blockElements = Math.min((8192 / stride) & ~15, 256);
        for (int start = 0; start < count; start += blockElements) {
            final int n = Math.min(blockElements, count - start);
            final int groups = (n + 15) / 16;
            final byte[][] deltas = new byte[stride][groups * 16];
            for (int k = 0; k < stride; k += 4) {
                for (int i = 0; i < n; i++) {
                    final int p = (start + i) * stride + k;
                    final int mode = modes[k / 4] & 15;
                    if (mode == 0) {
                        for (int j = 0; j < 4; j++) {
                            final int d = (data[p + j] - previous[k + j]) & 0xFF;
                            deltas[k + j][i] = (byte) ((d & 0x80) != 0 ? ~(d << 1) : d << 1);
                            previous[k + j] = data[p + j];
                        }
                    } else if (mode == 1) {
                        for (int j = 0; j < 4; j += 2) {
                            final int cur = (data[p + j] & 0xFF) | ((data[p + j + 1] & 0xFF) << 8);
                            final int prev = (previous[k + j] & 0xFF) | ((previous[k + j + 1] & 0xFF) << 8);
                            final int d = (cur - prev) & 0xFFFF;
                            final int z = ((d & 0x8000) != 0 ? ~(d << 1) : d << 1) & 0xFFFF;
                            deltas[k + j][i] = (byte) z;
                            deltas[k + j + 1][i] = (byte) (z >> 8);
                            previous[k + j] = data[p + j];
                            previous[k + j + 1] = data[p + j + 1];
                        }
                    } else {
                        final int cur = readInt(data, p);
                        final int prev = readInt(previous, k);
                        final int x = Integer.rotateLeft(cur ^ prev, modes[k / 4] >> 4);
                        for (int j = 0; j < 4; j++) {
                            deltas[k + j][i] = (byte) (x >> (j * 8));
                            previous[k + j] = data[p + j];
                        }
                    }
                }
            }
            // 版 1 は、バイトの位置ごとに制御モードを選ぶ
            final int[] controls = new int[stride];
            if (version == 1) {
                for (int b = 0; b < stride; b++) {
                    final boolean zero = allZero(deltas[b], 0, n);
                    controls[b] = zero && rnd.nextBoolean() ? 2 : rnd.nextInt(zero ? 4 : 3) == 2 ? 3 : rnd.nextInt(2);
                    if (controls[b] == 2 && !zero) controls[b] = 3;
                }
                for (int c = 0; c < channels; c++) {
                    out.write(controls[c * 4] | (controls[c * 4 + 1] << 2) | (controls[c * 4 + 2] << 4) | (controls[c * 4 + 3] << 6));
                }
            }
            for (int b = 0; b < stride; b++) {
                if (controls[b] == 2) continue;
                if (controls[b] == 3) {
                    out.write(deltas[b], 0, n);
                    continue;
                }
                final int[] bitsTable = version == 0 ? new int[]{0, 2, 4, 8} : controls[b] == 0 ? new int[]{0, 1, 2, 4} : new int[]{1, 2, 4, 8};
                final int[] header = new int[(groups + 3) / 4];
                final ByteArrayOutputStream body = new ByteArrayOutputStream();
                for (int g = 0; g < groups; g++) {
                    final boolean zero = allZero(deltas[b], g * 16, g * 16 + 16);
                    int h;
                    do {
                        h = rnd.nextInt(4);
                    } while (bitsTable[h] == 0 && !zero);
                    header[g / 4] |= h << ((g % 4) * 2);
                    encodeGroup(deltas[b], g * 16, bitsTable[h], body);
                }
                for (int h : header) out.write(h);
                final byte[] bodyBytes = body.toByteArray();
                out.write(bodyBytes, 0, bodyBytes.length);
            }
        }
        // 末尾: 詰め物、基準の要素、チャンネルのモード
        final int tail = stride + (version == 1 ? channels : 0);
        for (int i = tail; i < (version == 0 ? 32 : 24); i++) out.write(0);
        out.write(baseline, 0, stride);
        if (version == 1) for (int m : modes) out.write(m);
        return out.toByteArray();
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static void encodeGroup(byte[] deltas, int offset, int bits, ByteArrayOutputStream out) {
        if (bits == 0) return;
        if (bits == 8) {
            out.write(deltas, offset, 16);
            return;
        }
        final int sentinel = (1 << bits) - 1;
        final byte[] packed = new byte[16 * bits / 8];
        final ByteArrayOutputStream extra = new ByteArrayOutputStream();
        for (int i = 0; i < 16; i++) {
            final int v = deltas[offset + i] & 0xFF;
            final int code = v < sentinel ? v : sentinel;
            if (code == sentinel) extra.write(v);
            final int shift = bits == 1 ? i & 7 : 8 - bits - (i * bits) % 8;
            packed[i * bits / 8] |= (byte) (code << shift);
        }
        out.write(packed, 0, packed.length);
        final byte[] e = extra.toByteArray();
        out.write(e, 0, e.length);
    }

    private static int readInt(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
    }

    // ---------- 三角形のインデックス ----------

    private static void testTriangleRoundTrip(Random rnd, int iterations) throws IOException {
        for (int it = 0; it < iterations; it++) {
            final int[] triangles = randomTriangles(rnd);
            final int version = rnd.nextInt(4) == 0 ? 0 : 1;
            final int indexSize = rnd.nextBoolean() ? 2 : 4;
            final int[] emitted = new int[triangles.length];
            final byte[] encoded = encodeTriangles(triangles, version, rnd, emitted);
            final byte[] decoded = new byte[triangles.length * indexSize];
            MeshoptDecoder.decodeIndexBuffer(decoded, 0, triangles.length, indexSize, encoded, 0, encoded.length);
            boolean ok = true;
            for (int i = 0; i < triangles.length; i++) {
                final int expected = indexSize == 2 ? emitted[i] & 0xFFFF : emitted[i];
                final int actual = indexSize == 2 ? (decoded[i * 2] & 0xFF) | ((decoded[i * 2 + 1] & 0xFF) << 8) : readInt(decoded, i * 4);
                ok &= expected == actual;
            }
            // 符号化は三角形を回すだけで、向きと中身は変えない
            for (int t = 0; t < triangles.length; t += 3) ok &= isRotation(triangles, emitted, t);
            check(ok, "triangle round trip (" + triangles.length / 3 + " triangles, version " + version + ")");
        }
        System.out.println("triangle round trip: " + iterations + " buffers");
    }

    private static boolean isRotation(int[] a, int[] b, int t) {
        for (int r = 0; r < 3; r++) {
            if (a[t] == b[t + r] && a[t + 1] == b[t + (r + 1) % 3] && a[t + 2] == b[t + (r + 2) % 3]) return true;
        }
        return false;
    }

    // 格子状の面、帯、ばらばらの三角形、大きな番号などを混ぜる
    static int[] randomTriangles(Random rnd) {
        final List<Integer> list = new ArrayList<Integer>();
        final int parts = 1 + rnd.nextInt(4);
        for (int part = 0; part < parts; part++) {
            final int kind = rnd.nextInt(4);
            final int base = rnd.nextInt(5) == 0 ? rnd.nextInt() : rnd.nextInt(100);
            if (kind == 0) {
                final int w = 2 + rnd.nextInt(12);
                final int h = 2 + rnd.nextInt(12);
                for (int y = 0; y + 1 < h; y++) {
                    for (int x = 0; x + 1 < w; x++) {
                        final int v = base + y * w + x;
                        addTriangle(list, v, v + w, v + 1);
                        addTriangle(list, v + 1, v + w, v + w + 1);
                    }
                }
            } else if (kind == 1) {
                final int n = 3 + rnd.nextInt(60);
                for (int i = 0; i + 2 < n; i++) {
                    if ((i & 1) == 0) addTriangle(list, base + i, base + i + 1, base + i + 2);
                    else addTriangle(list, base + i + 1, base + i, base + i + 2);
                }
            } else if (kind == 2) {
                final int n = 1 + rnd.nextInt(40);
                final int range = 1 + rnd.nextInt(rnd.nextBoolean() ? 30 : 100000);
                for (int i = 0; i < n; i++) {
                    addTriangle(list, base + rnd.nextInt(range), base + rnd.nextInt(range), base + rnd.nextInt(range));
                }
            } else {
                // 0, 1, 2 から始めなおす (next を 0 に戻す印を使う場合)
                addTriangle(list, 0, 1, 2);
                addTriangle(list, 2, 1, 3);
            }
        }
        final int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) result[i] = list.get(i);
        return result;
    }

    private static void addTriangle(List<Integer> list, int a, int b, int c) {
        list.add(a);
        list.add(b);
        list.add(c);
    }

    /**
     * 三角形のインデックスを符号化する (テスト用)。emitted には、展開すると出てくる (回した) 三角形を書く。
     * 復号側と同じ FIFO を持ち、使える符号のうち乱数で選んだものを使う。
     */
    static byte[] encodeTriangles(int[] triangles, int version, Random rnd, int[] emitted) {
        final int count = triangles.length / 3;
        final ByteArrayOutputStream codes = new ByteArrayOutputStream();
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        // 末尾の表。下位と上位の 4 ビットはどちらも 0 - 14、最後の 2 つは 0
        final int[] table = new int[16];
        for (int i = 0; i < 14; i++) table[i] = rnd.nextInt(4) == 0 ? 0 : (rnd.nextInt(4) << 4) | rnd.nextInt(4);
        final int[] edgeA = new int[16], edgeB = new int[16], vertices = new int[16];
        int edgeHead = 0, edgeCount = 0, vertexHead = 0, vertexCount = 0, next = 0, last = 0;
        final int fifoLimit = version == 1 ? 13 : 15;

        for (int t = 0; t < count; t++) {
            final int x = triangles[t * 3], y = triangles[t * 3 + 1], z = triangles[t * 3 + 2];
            final int[][] rotations = {{x, y, z}, {y, z, x}, {z, x, y}};
            final int startRotation = rnd.nextInt(3);
            int a = 0, b = 0, c = 0, edge = -1;
            if (rnd.nextInt(5) != 0) {
                for (int r = 0; r < 3 && edge < 0; r++) {
                    final int[] tri = rotations[(startRotation + r) % 3];
                    for (int e = 0; e < Math.min(edgeCount, 15); e++) {
                        final int slot = (edgeHead - 1 - e) & 15;
                        if (edgeA[slot] == tri[0] && edgeB[slot] == tri[1]) {
                            edge = e;
                            a = tri[0];
                            b = tri[1];
                            c = tri[2];
                            break;
                        }
                    }
                }
            }
            if (edge >= 0) {
                // 辺 (a, b) を FIFO から使い、c の表し方を選ぶ
                final List<Integer> options = new ArrayList<Integer>();
                if (c == next) options.add(0);
                for (int f = 1; f < Math.min(fifoLimit, vertexCount); f++) {
                    if (vertices[(vertexHead - 1 - f) & 15] == c) options.add(f);
                }
                if (version == 1 && c == last - 1) options.add(13);
                if (version == 1 && c == last + 1) options.add(14);
                options.add(15);
                final int fv = options.get(rnd.nextInt(options.size()));
                codes.write((edge << 4) | fv);
                boolean push = true;
                if (fv == 0) {
                    next++;
                } else if (fv < fifoLimit) {
                    push = false;
                } else if (fv == 15) {
                    writeVarint(data, zigzag(c - last));
                    last = c;
                } else {
                    last = c;
                }
                if (push) {
                    vertices[vertexHead] = c;
                    vertexHead = (vertexHead + 1) & 15;
                    vertexCount = Math.min(vertexCount + 1, 16);
                }
                edgeA[edgeHead] = c;
                edgeB[edgeHead] = b;
                edgeHead = (edgeHead + 1) & 15;
                edgeA[edgeHead] = a;
                edgeB[edgeHead] = c;
                edgeHead = (edgeHead + 1) & 15;
                edgeCount = Math.min(edgeCount + 2, 16);
            } else {
                final int[] tri = rotations[startRotation];
                a = tri[0];
                b = tri[1];
                c = tri[2];
                // (0, 1, 2) で next が 0 でなければ、next を 0 に戻す印を使える
                final boolean reset = a == 0 && b == 1 && c == 2 && next > 0 && rnd.nextBoolean();
                int n = reset ? 0 : next;
                final boolean explicitA = !reset && (a != n || rnd.nextInt(6) == 0);
                if (!explicitA) n++;
                int fb;
                if (b == n && (reset || rnd.nextInt(4) != 0)) {
                    fb = 0;
                    n++;
                } else {
                    fb = 15;
                    for (int f = 0; f < Math.min(14, vertexCount); f++) {
                        if (vertices[(vertexHead - 1 - f) & 15] == b && rnd.nextInt(3) != 0) {
                            fb = f + 1;
                            break;
                        }
                    }
                }
                int fc;
                if (c == n && (reset || rnd.nextInt(4) != 0)) {
                    fc = 0;
                    n++;
                } else {
                    fc = 15;
                    for (int f = 0; f < Math.min(14, vertexCount); f++) {
                        if (vertices[(vertexHead - 1 - f) & 15] == c && rnd.nextInt(3) != 0) {
                            fc = f + 1;
                            break;
                        }
                    }
                }
                int aux = (fb << 4) | fc;
                if (reset) {
                    // 印のときは aux が 0 (b, c とも新しい頂点) でなければならない
                    if (explicitA || fb != 0 || fc != 0) {
                        throw new IllegalStateException("reset encoding");
                    }
                    codes.write(0xFE);
                    data.write(0);
                } else {
                    if (aux == 0 && !explicitA) {
                        // aux 0 を 0xFE で送ると next が 0 に戻ってしまうので、表を使うか、c を明示する
                        int tableIndex = -1;
                        for (int i = 0; i < 14; i++) if (table[i] == 0) tableIndex = i;
                        if (tableIndex < 0) {
                            fc = 15;
                            n--;
                            aux = (fb << 4) | fc;
                        }
                    }
                    if (aux == 0 && explicitA) {
                        fc = 15;
                        n--;
                        aux = (fb << 4) | fc;
                    }
                    int tableIndex = -1;
                    if (!explicitA && fb != 15 && fc != 15) {
                        for (int i = 0; i < 14; i++) if (table[i] == aux) tableIndex = i;
                    }
                    if (tableIndex >= 0 && (aux == 0 || rnd.nextBoolean())) {
                        codes.write(0xF0 | tableIndex);
                    } else {
                        codes.write(explicitA ? 0xFF : 0xFE);
                        data.write(aux);
                    }
                }
                next = n;
                if (explicitA) {
                    writeVarint(data, zigzag(a - last));
                    last = a;
                }
                if (fb == 15) {
                    writeVarint(data, zigzag(b - last));
                    last = b;
                }
                if (fc == 15) {
                    writeVarint(data, zigzag(c - last));
                    last = c;
                }
                vertices[vertexHead] = a;
                vertexHead = (vertexHead + 1) & 15;
                int pushed = 1;
                if (fb == 0 || fb == 15) {
                    vertices[vertexHead] = b;
                    vertexHead = (vertexHead + 1) & 15;
                    pushed++;
                }
                if (fc == 0 || fc == 15) {
                    vertices[vertexHead] = c;
                    vertexHead = (vertexHead + 1) & 15;
                    pushed++;
                }
                vertexCount = Math.min(vertexCount + pushed, 16);
                edgeA[edgeHead] = b;
                edgeB[edgeHead] = a;
                edgeHead = (edgeHead + 1) & 15;
                edgeA[edgeHead] = c;
                edgeB[edgeHead] = b;
                edgeHead = (edgeHead + 1) & 15;
                edgeA[edgeHead] = a;
                edgeB[edgeHead] = c;
                edgeHead = (edgeHead + 1) & 15;
                edgeCount = Math.min(edgeCount + 3, 16);
            }
            emitted[t * 3] = a;
            emitted[t * 3 + 1] = b;
            emitted[t * 3 + 2] = c;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xE0 | version);
        final byte[] codeBytes = codes.toByteArray();
        out.write(codeBytes, 0, codeBytes.length);
        final byte[] dataBytes = data.toByteArray();
        out.write(dataBytes, 0, dataBytes.length);
        for (int v : table) out.write(v);
        return out.toByteArray();
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static void writeVarint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    // ---------- インデックスの列 ----------

    private static void testSequenceRoundTrip(Random rnd, int iterations) throws IOException {
        for (int it = 0; it < iterations; it++) {
            final int count = rnd.nextInt(4) == 0 ? rnd.nextInt(3) : rnd.nextInt(2000);
            final int[] values = randomSequence(rnd, count);
            final int indexSize = rnd.nextBoolean() ? 2 : 4;
            final byte[] encoded = encodeSequence(values, rnd);
            final byte[] decoded = new byte[count * indexSize];
            MeshoptDecoder.decodeIndexSequence(decoded, 0, count, indexSize, encoded, 0, encoded.length);
            boolean ok = true;
            for (int i = 0; i < count; i++) {
                final int actual = indexSize == 2 ? (decoded[i * 2] & 0xFF) | ((decoded[i * 2 + 1] & 0xFF) << 8) : readInt(decoded, i * 4);
                ok &= actual == (indexSize == 2 ? values[i] & 0xFFFF : values[i]);
            }
            check(ok, "index sequence round trip (" + count + ")");
        }
        System.out.println("index sequence round trip: " + iterations + " buffers");
    }

    static int[] randomSequence(Random rnd, int count) {
        final int[] values = new int[count];
        int a = rnd.nextInt(1000), b = rnd.nextInt(1 << 29);
        for (int i = 0; i < count; i++) {
            switch (rnd.nextInt(4)) {
                case 0: a += rnd.nextInt(5) - 1; values[i] = a; break;
                case 1: b += rnd.nextInt(5) - 1; values[i] = b; break;
                case 2: values[i] = rnd.nextInt(1 << 30) - (1 << 29); break;
                default: values[i] = a + rnd.nextInt(100); break;
            }
        }
        return values;
    }

    // 2 つの基準のうち差の小さい方 (ときどき乱数で選んだ方) からの差を書く。差は -2^30 - 2^30-1 に収まる必要がある
    static byte[] encodeSequence(int[] values, Random rnd) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xD1);
        final int[] baseline = new int[2];
        for (int value : values) {
            // 差は 32 ビットで回り込む
            int which = Math.abs((long) (value - baseline[0])) <= Math.abs((long) (value - baseline[1])) ? 0 : 1;
            if (rnd.nextInt(8) == 0) which ^= 1;
            int delta = value - baseline[which];
            if (delta < -(1 << 30) || delta >= (1 << 30)) {
                which ^= 1;
                delta = value - baseline[which];
            }
            if (delta < -(1 << 30) || delta >= (1 << 30)) throw new IllegalStateException("delta out of range");
            writeVarint(out, (zigzag(delta) << 1) | which);
            baseline[which] = value;
        }
        for (int i = 0; i < 4; i++) out.write(0);
        return out.toByteArray();
    }

    // ---------- フィルタ ----------

    private static void testFilters(Random rnd, int iterations) throws IOException {
        double worstOct8 = 0, worstOct16 = 0, worstQuat = 0, worstColor8 = 0, worstColor16 = 0;
        int expExact = 0;
        for (int it = 0; it < iterations; it++) {
            // 八面体: 単位ベクトルを K ビットで符号化し、戻した値が仕様の浮動小数点の式と 1 単位以内で合うか
            final boolean wide = rnd.nextBoolean();
            final int bits = wide ? 2 + rnd.nextInt(15) : 2 + rnd.nextInt(7);
            final float[] n = randomUnit(rnd, 3);
            final int one = (1 << (bits - 1)) - 1;
            final float l1 = Math.abs(n[0]) + Math.abs(n[1]) + Math.abs(n[2]);
            float u = n[0] / l1, v = n[1] / l1;
            if (n[2] < 0) {
                final float tu = (1 - Math.abs(v)) * Math.signum(u == 0 ? 1 : u);
                final float tv = (1 - Math.abs(u)) * Math.signum(v == 0 ? 1 : v);
                u = tu;
                v = tv;
            }
            final int[] in = {Math.round(u * one), Math.round(v * one), one, rnd.nextInt(wide ? 65536 : 256) - (wide ? 32768 : 128)};
            final byte[] buffer = new byte[wide ? 8 : 4];
            for (int c = 0; c < 4; c++) put(buffer, c, in[c], wide);
            MeshoptDecoder.decodeFilterOct(buffer, 0, 1, wide ? 8 : 4);
            final double[] expected = specOct(in);
            final int max = wide ? 32767 : 127;
            for (int c = 0; c < 3; c++) {
                final double err = Math.abs(get(buffer, c, wide, true) - expected[c] * max);
                if (wide) worstOct16 = Math.max(worstOct16, err);
                else worstOct8 = Math.max(worstOct8, err);
            }
            check(get(buffer, 3, wide, true) == (wide ? (short) in[3] : (byte) in[3]), "octahedral filter keeps the 4th component");

            // 四元数
            final float[] q = randomUnit(rnd, 4);
            final int qbits = 4 + rnd.nextInt(13);
            int qc = 0;
            for (int c = 1; c < 4; c++) if (Math.abs(q[c]) > Math.abs(q[qc])) qc = c;
            final float sign = q[qc] < 0 ? -1 : 1;
            final int qone = (1 << (qbits - 1)) - 1;
            final int[] qin = new int[4];
            for (int c = 0; c < 3; c++) qin[c] = Math.round(q[(qc + 1 + c) & 3] * (float) Math.sqrt(2) * sign * qone);
            qin[3] = (qone & ~3) | qc;
            final byte[] qbuf = new byte[8];
            for (int c = 0; c < 4; c++) put(qbuf, c, qin[c], true);
            MeshoptDecoder.decodeFilterQuat(qbuf, 0, 1, 8);
            final double[] qexp = specQuat(qin);
            for (int c = 0; c < 4; c++) worstQuat = Math.max(worstQuat, Math.abs(get(qbuf, c, true, true) - qexp[c] * 32767));

            // 指数: 仕様では誤差なく一致しなければならない
            final int e = rnd.nextInt(201) - 100;
            final int m = rnd.nextInt(1 << 24) - (1 << 23);
            final byte[] ebuf = new byte[4];
            final int packed = (e << 24) | (m & 0xFFFFFF);
            for (int c = 0; c < 4; c++) ebuf[c] = (byte) (packed >> (c * 8));
            MeshoptDecoder.decodeFilterExp(ebuf, 0, 1, 4);
            final float expected32 = (float) (m * Math.pow(2.0, e));
            if (Float.floatToIntBits(Float.intBitsToFloat(readInt(ebuf, 0))) == Float.floatToIntBits(expected32)) expExact++;
            else check(false, "exponential filter e=" + e + " m=" + m);

            // 色 (YCoCg)
            final boolean cwide = rnd.nextBoolean();
            final int cbits = cwide ? 2 + rnd.nextInt(15) : 2 + rnd.nextInt(7);
            final int cmax = (1 << cbits) - 1;
            final int r = rnd.nextInt(cmax + 1), g = rnd.nextInt(cmax + 1), b = rnd.nextInt(cmax + 1), a = rnd.nextInt(cmax + 1);
            final int co = (r - b) / 2;
            final int tmp = b + co;
            final int cg = (g - tmp) / 2;
            final int yy = tmp + cg;
            final int fa = (a >> 1) | (1 << (cbits - 1));
            final byte[] cbuf = new byte[cwide ? 8 : 4];
            put(cbuf, 0, yy, cwide);
            put(cbuf, 1, co, cwide);
            put(cbuf, 2, cg, cwide);
            put(cbuf, 3, fa, cwide);
            final int[] cin = {yy, co, cg, fa};
            MeshoptDecoder.decodeFilterColor(cbuf, 0, 1, cwide ? 8 : 4);
            final double[] cexp = specColor(cin, cwide);
            for (int c = 0; c < 4; c++) {
                final double err = Math.abs(get(cbuf, c, cwide, false) - cexp[c]);
                if (cwide) worstColor16 = Math.max(worstColor16, err);
                else worstColor8 = Math.max(worstColor8, err);
            }
        }
        // 仕様は結果の 1 単位の誤差を許している
        check(worstOct8 <= 1.0 && worstOct16 <= 1.0, "octahedral filter error " + worstOct8 + " / " + worstOct16);
        check(worstQuat <= 1.0, "quaternion filter error " + worstQuat);
        check(worstColor8 <= 1.0 && worstColor16 <= 1.0, "color filter error " + worstColor8 + " / " + worstColor16);
        System.out.printf("filters: %d cases, max error oct8=%.3f oct16=%.3f quat=%.3f color8=%.3f color16=%.3f, exp exact=%d%n",
                iterations, worstOct8, worstOct16, worstQuat, worstColor8, worstColor16, expExact);
    }

    private static float[] randomUnit(Random rnd, int n) {
        final float[] v = new float[n];
        double len;
        do {
            len = 0;
            for (int i = 0; i < n; i++) {
                v[i] = (float) rnd.nextGaussian();
                len += v[i] * v[i];
            }
        } while (len < 1e-6);
        for (int i = 0; i < n; i++) v[i] /= (float) Math.sqrt(len);
        return v;
    }

    // 仕様の擬似コードを、そのまま倍精度で計算したもの (丸める前の値)
    private static double[] specOct(int[] in) {
        final double one = in[2];
        double x = in[0] / one, y = in[1] / one;
        final double z = 1.0 - Math.abs(x) - Math.abs(y);
        final double t = Math.min(z, 0.0);
        x -= Math.copySign(t, x);
        y -= Math.copySign(t, y);
        final double len = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / len, y / len, z / len};
    }

    private static double[] specQuat(int[] in) {
        final double range = 1.0 / Math.sqrt(2.0);
        final double one = (short) in[3] | 3;
        final double x = (short) in[0] / one * range, y = (short) in[1] / one * range, z = (short) in[2] / one * range;
        final double w = Math.sqrt(Math.max(0.0, 1.0 - x * x - y * y - z * z));
        final int maxComponent = in[3] & 3;
        final double[] out = new double[4];
        out[(maxComponent + 1) % 4] = x;
        out[(maxComponent + 2) % 4] = y;
        out[(maxComponent + 3) % 4] = z;
        out[maxComponent] = w;
        return out;
    }

    private static double[] specColor(int[] in, boolean wide) {
        final int msb = 31 - Integer.numberOfLeadingZeros(in[3]);
        final int as = (1 << (msb + 1)) - 1;
        final int y = in[0], co = in[1], cg = in[2];
        int a = in[3] & (as >> 1);
        a = (a << 1) | (a & 1);
        final double ss = (wide ? 65535.0 : 255.0) / as;
        return new double[]{(y + co - cg) * ss, (y + cg) * ss, (y - co - cg) * ss, a * ss};
    }

    private static void put(byte[] buffer, int component, int value, boolean wide) {
        if (wide) {
            buffer[component * 2] = (byte) value;
            buffer[component * 2 + 1] = (byte) (value >> 8);
        } else {
            buffer[component] = (byte) value;
        }
    }

    private static int get(byte[] buffer, int component, boolean wide, boolean signed) {
        if (wide) {
            final int v = (buffer[component * 2] & 0xFF) | ((buffer[component * 2 + 1] & 0xFF) << 8);
            return signed ? (short) v : v;
        }
        return signed ? buffer[component] : buffer[component] & 0xFF;
    }

    // ---------- 不正な引数 ----------

    private static void testInvalidParameters() {
        final byte[] small = new byte[64];
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, 4, 6, small, 0, 64), "vertex size not multiple of 4");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, 4, 260, small, 0, 64), "vertex size over 256");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, 100, 4, small, 0, 64), "destination too small");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 70, 1, 4, small, 0, 64), "destination offset out of range");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, 1, 4, small, 60, 10), "source out of range");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, 1, 4, small, -1, 10), "negative source offset");
        expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(small, 0, -1, 4, small, 0, 10), "negative count");
        expectFailure(() -> MeshoptDecoder.decodeIndexBuffer(small, 0, 4, 2, small, 0, 64), "triangle count not multiple of 3");
        expectFailure(() -> MeshoptDecoder.decodeIndexBuffer(small, 0, 3, 3, small, 0, 64), "index size 3");
        expectFailure(() -> MeshoptDecoder.decodeIndexBuffer(small, 0, Integer.MAX_VALUE - 1, 4, small, 0, 64), "huge triangle count");
        expectFailure(() -> MeshoptDecoder.decodeIndexSequence(small, 0, Integer.MAX_VALUE, 2, small, 0, 64), "huge sequence count");
        expectFailure(() -> MeshoptDecoder.decodeFilterOct(small, 0, 3, 6), "octahedral stride");
        expectFailure(() -> MeshoptDecoder.decodeFilterQuat(small, 0, 9, 8), "quaternion buffer too small");
        expectFailure(() -> MeshoptDecoder.decodeFilterExp(small, 0, Integer.MAX_VALUE, 4), "exponential count overflow");
        expectFailure(() -> MeshoptDecoder.decodeFilterColor(small, 1, 16, 4), "color buffer too small");
        // 版 1 で、チャンネルのモード 3 と、モード 0 で上位のビットがあるものは不正
        final byte[] data = randomVertices(new Random(9), 20, 8);
        final byte[] encoded = encodeVertices(data, 20, 8, 1, new Random(10));
        for (int mode : new int[]{3, 0x10, 0x31, 0x13, 0x0F}) {
            final byte[] bad = encoded.clone();
            bad[bad.length - 1] = (byte) mode;
            expectFailure(() -> MeshoptDecoder.decodeVertexBuffer(new byte[160], 0, 20, 8, bad, 0, bad.length), "channel mode 0x" + Integer.toHexString(mode));
        }
        System.out.println("invalid parameters: done");
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void expectFailure(Action action, String what) {
        try {
            action.run();
            check(false, "should fail: " + what);
        } catch (IOException expected) {
            // 期待どおり
        } catch (RuntimeException e) {
            check(false, "unexpected " + e + ": " + what);
        }
    }

    // ---------- 回帰 (固定の乱数で作ったデータの展開結果のハッシュ) ----------

    // 書き換える前の実装 (meshoptimizer から移したもの) で求めた値。符号化と展開の結果が変わっていないことを確かめる
    private static final long EXPECTED_STREAM_HASH = 0x48d0c3d594450d0aL;
    private static final long EXPECTED_FILTER_HASH = 0x48fd78c511998b64L;

    private static void testRegression() throws IOException {
        final long streamHash = regressionStreamHash();
        final long filterHash = regressionFilterHash();
        System.out.printf("regression: streams=%016x filters=%016x%n", streamHash, filterHash);
        check(streamHash == EXPECTED_STREAM_HASH, "stream regression hash");
        check(filterHash == EXPECTED_FILTER_HASH, "filter regression hash");
    }

    static long regressionStreamHash() throws IOException {
        final Random rnd = new Random(1234);
        long hash = 1469598103934665603L;
        for (int it = 0; it < 200; it++) {
            final int stride = 4 * (1 + rnd.nextInt(16));
            final int count = rnd.nextInt(700);
            final byte[] data = randomVertices(rnd, count, stride);
            final byte[] encoded = encodeVertices(data, count, stride, it & 1, rnd);
            final byte[] decoded = new byte[count * stride];
            MeshoptDecoder.decodeVertexBuffer(decoded, 0, count, stride, encoded, 0, encoded.length);
            hash = fnv(hash, encoded);
            hash = fnv(hash, decoded);
            final int[] triangles = randomTriangles(rnd);
            final byte[] tri = encodeTriangles(triangles, it % 3 == 0 ? 0 : 1, rnd, new int[triangles.length]);
            final byte[] triDecoded = new byte[triangles.length * 4];
            MeshoptDecoder.decodeIndexBuffer(triDecoded, 0, triangles.length, 4, tri, 0, tri.length);
            hash = fnv(hash, tri);
            hash = fnv(hash, triDecoded);
            final int[] sequence = randomSequence(rnd, rnd.nextInt(300));
            final byte[] seq = encodeSequence(sequence, rnd);
            final byte[] seqDecoded = new byte[sequence.length * 2];
            MeshoptDecoder.decodeIndexSequence(seqDecoded, 0, sequence.length, 2, seq, 0, seq.length);
            hash = fnv(hash, seq);
            hash = fnv(hash, seqDecoded);
        }
        return hash;
    }

    // 正しい範囲の入力に対する、フィルタの結果のハッシュ (八面体の 3 つ目や四元数の 4 つ目の成分が負になるものなど、仕様で結果の決まらない入力は除く)
    static long regressionFilterHash() throws IOException {
        final Random rnd = new Random(4321);
        long hash = 1469598103934665603L;
        for (int it = 0; it < 4000; it++) {
            final byte[] b4 = new byte[4 * 16];
            final byte[] b8 = new byte[8 * 16];
            rnd.nextBytes(b4);
            rnd.nextBytes(b8);
            for (int i = 0; i < 16; i++) {
                b4[i * 4 + 2] = (byte) rnd.nextInt(128);
                b8[i * 8 + 5] = (byte) rnd.nextInt(128);
                b8[i * 8 + 7] = (byte) rnd.nextInt(128);
            }
            final byte[] oct4 = b4.clone();
            final byte[] oct8 = b8.clone();
            MeshoptDecoder.decodeFilterOct(oct4, 0, 16, 4);
            MeshoptDecoder.decodeFilterOct(oct8, 0, 16, 8);
            final byte[] quat = b8.clone();
            MeshoptDecoder.decodeFilterQuat(quat, 0, 16, 8);
            final byte[] exp = b8.clone();
            for (int i = 0; i < 32; i++) exp[i * 4 + 3] = (byte) (rnd.nextInt(253) - 126);
            MeshoptDecoder.decodeFilterExp(exp, 0, 16, 8);
            final byte[] color4 = b4.clone();
            final byte[] color8 = b8.clone();
            for (int i = 0; i < 16; i++) {
                if (color4[i * 4 + 3] == 0) color4[i * 4 + 3] = 1;
                if (color8[i * 8 + 6] == 0 && color8[i * 8 + 7] == 0) color8[i * 8 + 6] = 1;
            }
            MeshoptDecoder.decodeFilterColor(color4, 0, 16, 4);
            MeshoptDecoder.decodeFilterColor(color8, 0, 16, 8);
            for (byte[] b : new byte[][]{oct4, oct8, quat, exp, color4, color8}) hash = fnv(hash, b);
        }
        return hash;
    }

    private static long fnv(long hash, byte[] data) {
        for (byte b : data) {
            hash ^= b & 0xFF;
            hash *= 1099511628211L;
        }
        hash ^= data.length;
        return hash * 1099511628211L;
    }

    // ---------- 壊したデータ ----------

    private static void fuzz(Random rnd, long seconds) {
        final long deadline = System.nanoTime() + seconds * 1000000000L;
        long cases = 0, rejected = 0, slowest = 0;
        while (System.nanoTime() < deadline) {
            final int kind = rnd.nextInt(3);
            byte[] encoded;
            int count;
            int size;
            if (kind == 0) {
                size = 4 * (1 + rnd.nextInt(8));
                count = rnd.nextInt(600);
                encoded = encodeVertices(randomVertices(rnd, count, size), count, size, rnd.nextInt(2), rnd);
            } else if (kind == 1) {
                final int[] triangles = randomTriangles(rnd);
                count = triangles.length;
                size = rnd.nextBoolean() ? 2 : 4;
                encoded = encodeTriangles(triangles, rnd.nextInt(2), rnd, new int[count]);
            } else {
                count = rnd.nextInt(500);
                size = rnd.nextBoolean() ? 2 : 4;
                encoded = encodeSequence(randomSequence(rnd, count), rnd);
            }
            final byte[] bad = mutate(rnd, encoded);
            // 個数を変えたり、乱数だけのデータにしたりもする
            if (rnd.nextInt(10) == 0) count = rnd.nextInt(5000) * (kind == 1 ? 3 : 1);
            final int finalCount = count;
            final int finalSize = size;
            final byte[] destination = new byte[Math.max(0, count * size)];
            final long start = System.nanoTime();
            try {
                if (kind == 0) MeshoptDecoder.decodeVertexBuffer(destination, 0, finalCount, finalSize, bad, 0, bad.length);
                else if (kind == 1) MeshoptDecoder.decodeIndexBuffer(destination, 0, finalCount, finalSize, bad, 0, bad.length);
                else MeshoptDecoder.decodeIndexSequence(destination, 0, finalCount, finalSize, bad, 0, bad.length);
                // 展開できたものにフィルタもかける
                if (kind == 0) {
                    MeshoptDecoder.decodeFilterExp(destination, 0, finalCount, finalSize);
                    if (finalSize == 4 || finalSize == 8) {
                        MeshoptDecoder.decodeFilterOct(destination, 0, finalCount, finalSize);
                        MeshoptDecoder.decodeFilterColor(destination, 0, finalCount, finalSize);
                    }
                    if (finalSize == 8) MeshoptDecoder.decodeFilterQuat(destination, 0, finalCount, finalSize);
                }
            } catch (IOException expected) {
                rejected++;
            } catch (Throwable t) {
                check(false, "unexpected exception " + t + " (kind " + kind + ")");
            }
            slowest = Math.max(slowest, System.nanoTime() - start);
            cases++;
        }
        check(slowest < 2000000000L, "slow decode " + slowest / 1000000 + "ms");
        System.out.printf("fuzz: %d cases in %ds, rejected=%d, slowest=%.1fms%n", cases, seconds, rejected, slowest / 1e6);
    }

    static byte[] mutate(Random rnd, byte[] data) {
        byte[] d = data.clone();
        final int kind = rnd.nextInt(6);
        if (kind == 0 && d.length > 1) {
            d = Arrays.copyOf(d, rnd.nextInt(d.length));
        } else if (kind == 1) {
            d = Arrays.copyOf(d, d.length + 1 + rnd.nextInt(40));
        } else if (kind == 2) {
            rnd.nextBytes(d);
        } else {
            final int edits = 1 + rnd.nextInt(6);
            for (int i = 0; i < edits && d.length > 0; i++) {
                final int pos = rnd.nextInt(d.length);
                d[pos] = rnd.nextBoolean() ? (byte) rnd.nextInt(256) : (byte) (d[pos] ^ (1 << rnd.nextInt(8)));
            }
        }
        if (d.length == 0) d = new byte[]{(byte) rnd.nextInt(256)};
        return d;
    }
}
