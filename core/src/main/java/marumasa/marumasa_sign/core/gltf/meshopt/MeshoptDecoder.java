package marumasa.marumasa_sign.core.gltf.meshopt;

import java.io.IOException;
import java.util.Arrays;

/**
 * EXT_meshopt_compression / KHR_meshopt_compression の展開を行う。
 * 頂点属性 (ATTRIBUTES)、三角形のインデックス (TRIANGLES)、インデックスの列 (INDICES) の復号と、各フィルタの逆変換に対応する。
 * 両方の拡張の仕様 (Appendix A: Bitstream, Appendix B: Filters) に沿って書いたもの。
 * 壊れたデータでは、範囲の外を読み書きせずに IOException を投げる。
 */
public final class MeshoptDecoder {

    private static final int HEADER_VERTEX = 0xA0;
    private static final int HEADER_TRIANGLES = 0xE0;
    private static final int HEADER_INDICES = 0xD0;

    // 頂点のブロックの大きさ (仕様で決まっている)
    private static final int BLOCK_BYTES = 8192;
    private static final int BLOCK_MAX_ELEMENTS = 256;
    private static final int GROUP_SIZE = 16;

    // 差分を何ビットで持つか。版 0 と、版 1 の制御モード 0, 1
    private static final int[] BITS_V0 = {0, 2, 4, 8};
    private static final int[][] BITS_V1 = {{0, 1, 2, 4}, {1, 2, 4, 8}};

    private MeshoptDecoder() {
    }

    // ---------- 頂点属性 (ATTRIBUTES) ----------

    /**
     * 頂点属性を展開する。destination の destOffset から、vertexCount 個 x vertexSize バイトを書く。
     */
    public static void decodeVertexBuffer(byte[] destination, int destOffset, int vertexCount, int vertexSize,
                                          byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if (vertexSize < 4 || vertexSize > 256 || (vertexSize & 3) != 0) {
            throw new IOException("Invalid meshopt vertex size: " + vertexSize);
        }
        checkDestination(destination, destOffset, vertexCount, vertexSize);
        checkSource(source, sourceOffset, sourceLength);
        final int header = source[sourceOffset] & 0xFF;
        if ((header & 0xF0) != HEADER_VERTEX || (header & 0x0F) > 1) {
            throw new IOException("Unsupported meshopt vertex buffer header: 0x" + Integer.toHexString(header));
        }
        final int version = header & 0x0F;

        // 末尾: 最初の要素の基準 (vertexSize バイト) と、版 1 ではチャンネルごとのモード。その前に、合わせて 32 (版 0) / 24 (版 1) バイトになるまで 0 を詰めてある
        final int channelCount = vertexSize / 4;
        final int tailSize = vertexSize + (version == 0 ? 0 : channelCount);
        final int paddedTail = Math.max(tailSize, version == 0 ? 32 : 24);
        final int end = sourceOffset + sourceLength;
        if (sourceLength - 1 < paddedTail) throw new IOException("Truncated meshopt vertex buffer");
        final int tail = end - tailSize;
        // ブロックのデータは、詰め物の手前までに収まっていなければならない
        final int dataEnd = end - paddedTail;

        final byte[] previous = Arrays.copyOfRange(source, tail, tail + vertexSize);
        final int[] channelModes = new int[channelCount];
        for (int c = 0; c < channelCount; c++) {
            final int mode = version == 0 ? 0 : source[tail + vertexSize + c] & 0xFF;
            // モード 0, 1 は上位 4 ビットが 0、モード 2 は上位 4 ビットが回転の量。3 以上は不正
            if ((mode & 0x0F) > 2 || ((mode & 0x0F) < 2 && (mode >> 4) != 0)) {
                throw new IOException("Invalid meshopt channel mode: 0x" + Integer.toHexString(mode));
            }
            channelModes[c] = mode;
        }

        final int blockElements = Math.min((BLOCK_BYTES / vertexSize) & ~(GROUP_SIZE - 1), BLOCK_MAX_ELEMENTS);
        final byte[] deltas = new byte[BLOCK_MAX_ELEMENTS * 4];
        final int[] pos = {sourceOffset + 1};
        for (int start = 0; start < vertexCount; start += blockElements) {
            final int count = Math.min(blockElements, vertexCount - start);
            decodeVertexBlock(source, pos, dataEnd, version, channelModes, count, vertexSize, previous, deltas,
                    destination, destOffset + start * vertexSize);
        }
        if (pos[0] != dataEnd) throw new IOException("Meshopt vertex buffer has unused data");
    }

    // 1 つのブロック (count 個の要素) を展開する。previous は直前の要素で、終わると最後の要素になる
    private static void decodeVertexBlock(byte[] source, int[] pos, int dataEnd, int version, int[] channelModes,
                                          int count, int vertexSize, byte[] previous, byte[] deltas,
                                          byte[] destination, int destOffset) throws IOException {
        final int groups = (count + GROUP_SIZE - 1) / GROUP_SIZE;
        // 版 1 では、先頭にバイトの位置ごとの制御モード (2 ビットずつ)
        final int controlStart = pos[0];
        if (version != 0) {
            need(pos[0], vertexSize / 4, dataEnd);
            pos[0] += vertexSize / 4;
        }
        for (int k = 0; k < vertexSize; k += 4) {
            // 4 バイト分の差分を、バイトの位置ごとに deltas[j * count + i] へ集める
            for (int j = 0; j < 4; j++) {
                final int control = version == 0 ? 0 : (source[controlStart + k / 4] >> (j * 2)) & 3;
                final int offset = j * count;
                if (control == 2) {
                    Arrays.fill(deltas, offset, offset + count, (byte) 0);
                } else if (control == 3) {
                    need(pos[0], count, dataEnd);
                    System.arraycopy(source, pos[0], deltas, offset, count);
                    pos[0] += count;
                } else {
                    decodeDeltaBytes(source, pos, dataEnd, version == 0 ? BITS_V0 : BITS_V1[control], groups, count,
                            deltas, offset);
                }
            }
            applyDeltas(deltas, count, channelModes[k / 4], previous, k, vertexSize, destination, destOffset);
        }
    }

    // 16 個ずつの組に分けた差分のバイトを読む。各組の符号化のしかたを 2 ビットずつ並べたものが先にある
    private static void decodeDeltaBytes(byte[] source, int[] pos, int dataEnd, int[] bitsTable, int groups, int count,
                                         byte[] deltas, int offset) throws IOException {
        final int headerStart = pos[0];
        final int headerSize = (groups + 3) / 4;
        need(headerStart, headerSize, dataEnd);
        int p = headerStart + headerSize;
        final byte[] group = new byte[GROUP_SIZE];
        for (int g = 0; g < groups; g++) {
            final int bits = bitsTable[(source[headerStart + g / 4] >> ((g % 4) * 2)) & 3];
            if (bits == 0) {
                Arrays.fill(group, (byte) 0);
            } else if (bits == 8) {
                need(p, GROUP_SIZE, dataEnd);
                System.arraycopy(source, p, group, 0, GROUP_SIZE);
                p += GROUP_SIZE;
            } else {
                // bits ビットずつ詰めた差分のあとに、全部のビットが 1 (はみ出しの印) だった差分の実際の値が 1 バイトずつ続く
                final int packedBytes = GROUP_SIZE * bits / 8;
                need(p, packedBytes, dataEnd);
                int extra = p + packedBytes;
                final int sentinel = (1 << bits) - 1;
                for (int i = 0; i < GROUP_SIZE; i++) {
                    final int b = source[p + i * bits / 8] & 0xFF;
                    // 1 ビットのときは下位から、2, 4 ビットのときは上位から並んでいる
                    final int shift = bits == 1 ? i & 7 : 8 - bits - (i * bits) % 8;
                    final int value = (b >> shift) & sentinel;
                    if (value == sentinel) {
                        need(extra, 1, dataEnd);
                        group[i] = source[extra++];
                    } else {
                        group[i] = (byte) value;
                    }
                }
                p = extra;
            }
            // 要素の数が 16 の倍数でないときは、最後の組の余りを捨てる
            System.arraycopy(group, 0, deltas, offset + g * GROUP_SIZE, Math.min(GROUP_SIZE, count - g * GROUP_SIZE));
        }
        pos[0] = p;
    }

    // 4 バイト分 (1 つのチャンネル) の差分を、チャンネルのモードに従って直前の要素に足し、出力に書く
    private static void applyDeltas(byte[] deltas, int count, int channelMode, byte[] previous, int k, int vertexSize,
                                    byte[] destination, int destOffset) {
        int out = destOffset + k;
        final int mode = channelMode & 0x0F;
        if (mode == 0) {
            // 1 バイトごとの差 (ジグザグ符号)
            for (int j = 0; j < 4; j++) {
                int value = previous[k + j] & 0xFF;
                int o = out + j;
                for (int i = 0; i < count; i++) {
                    value = (value + unzigzag(deltas[j * count + i] & 0xFF)) & 0xFF;
                    destination[o] = (byte) value;
                    o += vertexSize;
                }
                previous[k + j] = (byte) value;
            }
        } else if (mode == 1) {
            // 2 バイトごとの差 (ジグザグ符号、リトルエンディアン)
            for (int j = 0; j < 4; j += 2) {
                int value = (previous[k + j] & 0xFF) | ((previous[k + j + 1] & 0xFF) << 8);
                int o = out + j;
                for (int i = 0; i < count; i++) {
                    final int delta = (deltas[j * count + i] & 0xFF) | ((deltas[(j + 1) * count + i] & 0xFF) << 8);
                    value = (value + unzigzag(delta)) & 0xFFFF;
                    destination[o] = (byte) value;
                    destination[o + 1] = (byte) (value >> 8);
                    o += vertexSize;
                }
                previous[k + j] = (byte) value;
                previous[k + j + 1] = (byte) (value >> 8);
            }
        } else {
            // 4 バイトの排他的論理和。差は上位 4 ビットの量だけ左に回してあるので、右に回して戻す
            final int rotation = channelMode >> 4;
            int value = (previous[k] & 0xFF) | ((previous[k + 1] & 0xFF) << 8)
                    | ((previous[k + 2] & 0xFF) << 16) | ((previous[k + 3] & 0xFF) << 24);
            for (int i = 0; i < count; i++) {
                final int delta = (deltas[i] & 0xFF) | ((deltas[count + i] & 0xFF) << 8)
                        | ((deltas[2 * count + i] & 0xFF) << 16) | ((deltas[3 * count + i] & 0xFF) << 24);
                value ^= Integer.rotateRight(delta, rotation);
                destination[out] = (byte) value;
                destination[out + 1] = (byte) (value >> 8);
                destination[out + 2] = (byte) (value >> 16);
                destination[out + 3] = (byte) (value >> 24);
                out += vertexSize;
            }
            for (int j = 0; j < 4; j++) previous[k + j] = (byte) (value >> (j * 8));
        }
    }

    // ジグザグ符号を戻す (最下位のビットが符号)
    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    // ---------- 三角形のインデックス (TRIANGLES) ----------

    /**
     * 三角形のインデックスを展開する。indexSize は 2 か 4。
     */
    public static void decodeIndexBuffer(byte[] destination, int destOffset, int indexCount, int indexSize,
                                         byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if (indexCount < 0 || indexCount % 3 != 0 || (indexSize != 2 && indexSize != 4)) {
            throw new IOException("Invalid meshopt index buffer parameters: count=" + indexCount + ", size=" + indexSize);
        }
        checkDestination(destination, destOffset, indexCount, indexSize);
        checkSource(source, sourceOffset, sourceLength);
        final int triangles = indexCount / 3;
        // 見出しの 1 バイト、三角形ごとに 1 バイトの符号、追加のデータ、末尾の 16 バイトの表
        if (sourceLength < 1 + triangles + 16) throw new IOException("Truncated meshopt index buffer");
        final int header = source[sourceOffset] & 0xFF;
        // 仕様の版は 1 (0xE1)。meshoptimizer の古い版 0 (0xE0) も読めるようにしておく
        if ((header & 0xF0) != HEADER_TRIANGLES || (header & 0x0F) > 1) {
            throw new IOException("Unsupported meshopt index buffer header: 0x" + Integer.toHexString(header));
        }
        final boolean version1 = (header & 0x0F) == 1;

        final int codeStart = sourceOffset + 1;
        final int dataEnd = sourceOffset + sourceLength - 16;
        final int tableStart = dataEnd;
        final int[] pos = {codeStart + triangles};
        final int[] last = {0};

        // 最近の辺 (2 頂点) と頂点の FIFO。書いた数を数えて、まだ書いていない場所を読ませない
        final int[] edgeA = new int[16];
        final int[] edgeB = new int[16];
        final int[] vertices = new int[16];
        int edgeHead = 0;
        int edgeCount = 0;
        int vertexHead = 0;
        int vertexCount = 0;
        int next = 0;
        int out = destOffset;

        for (int t = 0; t < triangles; t++) {
            final int code = source[codeStart + t] & 0xFF;
            final int a;
            final int b;
            final int c;
            if (code < 0xF0) {
                // 上位 4 ビット: 最近の辺の位置。下位 4 ビット: 3 つ目の頂点の求め方
                final int fe = code >> 4;
                if (fe >= edgeCount) throw new IOException("Invalid meshopt edge reference");
                final int edge = (edgeHead - 1 - fe) & 15;
                a = edgeA[edge];
                b = edgeB[edge];
                final int fv = code & 15;
                boolean push = true;
                if (fv == 0) {
                    c = next++;
                } else if (fv < 13 || (!version1 && fv < 15)) {
                    // 最近の頂点 (FIFO の fv 番目)。FIFO には足さない
                    if (fv >= vertexCount) throw new IOException("Invalid meshopt vertex reference");
                    c = vertices[(vertexHead - 1 - fv) & 15];
                    push = false;
                } else if (fv == 15) {
                    c = readIndexDelta(source, pos, dataEnd, last);
                } else {
                    // 13: last - 1、14: last + 1 (版 1 だけ)
                    c = last[0] + (fv == 13 ? -1 : 1);
                    last[0] = c;
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
                // 3 頂点とも辺の FIFO を使わない三角形。0xF0 - 0xFD は末尾の表、0xFE, 0xFF は追加のデータの 1 バイトで求め方を決める
                final int aux;
                if (code < 0xFE) {
                    aux = source[tableStart + (code & 15)] & 0xFF;
                } else {
                    need(pos[0], 1, dataEnd);
                    aux = source[pos[0]++] & 0xFF;
                    // 0 は next を 0 に戻す印 (別々の三角形の列をつなげたとき)
                    if (aux == 0) next = 0;
                }
                final int fb = aux >> 4;
                final int fc = aux & 15;
                // 表から引いたときは 15 (明示した値) を使えない
                if (code < 0xFE && (fb == 15 || fc == 15)) throw new IOException("Invalid meshopt code table");
                if (code == 0xFF) {
                    a = readIndexDelta(source, pos, dataEnd, last);
                } else {
                    a = next++;
                }
                // 0 は新しい頂点、1 - 14 は FIFO の (値 - 1) 番目、15 は明示した値。FIFO は読み終えてから更新する
                if (fb == 0) b = next++;
                else if (fb < 15) b = fifo(vertices, vertexHead, vertexCount, fb - 1);
                else b = -1;
                if (fc == 0) c = next++;
                else if (fc < 15) c = fifo(vertices, vertexHead, vertexCount, fc - 1);
                else c = -1;
                final int bValue = fb == 15 ? readIndexDelta(source, pos, dataEnd, last) : b;
                final int cValue = fc == 15 ? readIndexDelta(source, pos, dataEnd, last) : c;

                vertices[vertexHead] = a;
                vertexHead = (vertexHead + 1) & 15;
                int pushed = 1;
                if (fb == 0 || fb == 15) {
                    vertices[vertexHead] = bValue;
                    vertexHead = (vertexHead + 1) & 15;
                    pushed++;
                }
                if (fc == 0 || fc == 15) {
                    vertices[vertexHead] = cValue;
                    vertexHead = (vertexHead + 1) & 15;
                    pushed++;
                }
                vertexCount = Math.min(vertexCount + pushed, 16);

                edgeA[edgeHead] = bValue;
                edgeB[edgeHead] = a;
                edgeHead = (edgeHead + 1) & 15;
                edgeA[edgeHead] = cValue;
                edgeB[edgeHead] = bValue;
                edgeHead = (edgeHead + 1) & 15;
                edgeA[edgeHead] = a;
                edgeB[edgeHead] = cValue;
                edgeHead = (edgeHead + 1) & 15;
                edgeCount = Math.min(edgeCount + 3, 16);

                out = writeIndex(destination, out, indexSize, a);
                out = writeIndex(destination, out, indexSize, bValue);
                out = writeIndex(destination, out, indexSize, cValue);
                continue;
            }
            out = writeIndex(destination, out, indexSize, a);
            out = writeIndex(destination, out, indexSize, b);
            out = writeIndex(destination, out, indexSize, c);
        }
        if (pos[0] != dataEnd) throw new IOException("Meshopt index buffer has unused data");
    }

    // 頂点の FIFO の index 番目 (0 が最も新しい)
    private static int fifo(int[] vertices, int head, int count, int index) throws IOException {
        if (index >= count) throw new IOException("Invalid meshopt vertex reference");
        return vertices[(head - 1 - index) & 15];
    }

    // 直前に明示した値 (last) からの差 (ジグザグ符号の可変長整数) を読んで、新しい値を返す
    private static int readIndexDelta(byte[] source, int[] pos, int dataEnd, int[] last) throws IOException {
        final int v = readVarint(source, pos, dataEnd);
        last[0] += unzigzag(v);
        return last[0];
    }

    // 7 ビットずつの可変長整数 (1 - 5 バイト。最後のバイトだけ最上位のビットが 0)。32 ビットを超える分は捨てる
    private static int readVarint(byte[] source, int[] pos, int dataEnd) throws IOException {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            need(pos[0], 1, dataEnd);
            final int b = source[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << (7 * i);
            if (b < 0x80) return result;
        }
        throw new IOException("Invalid meshopt varint");
    }

    private static int writeIndex(byte[] destination, int pos, int indexSize, int index) {
        destination[pos] = (byte) index;
        destination[pos + 1] = (byte) (index >> 8);
        if (indexSize == 2) return pos + 2;
        destination[pos + 2] = (byte) (index >> 16);
        destination[pos + 3] = (byte) (index >> 24);
        return pos + 4;
    }

    // ---------- インデックスの列 (INDICES) ----------

    /**
     * インデックスの列を展開する。indexSize は 2 か 4。
     */
    public static void decodeIndexSequence(byte[] destination, int destOffset, int indexCount, int indexSize,
                                           byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if (indexCount < 0 || (indexSize != 2 && indexSize != 4)) {
            throw new IOException("Invalid meshopt index sequence parameters: count=" + indexCount + ", size=" + indexSize);
        }
        checkDestination(destination, destOffset, indexCount, indexSize);
        checkSource(source, sourceOffset, sourceLength);
        // 見出しの 1 バイト、値ごとに 1 バイト以上、末尾の 4 バイト
        if (sourceLength < 1 + 4 + (long) indexCount) throw new IOException("Truncated meshopt index sequence");
        final int header = source[sourceOffset] & 0xFF;
        // 仕様の版は 1 (0xD1)。meshoptimizer は 0xD0 も同じ形式として読む
        if ((header & 0xF0) != HEADER_INDICES || (header & 0x0F) > 1) {
            throw new IOException("Unsupported meshopt index sequence header: 0x" + Integer.toHexString(header));
        }
        final int dataEnd = sourceOffset + sourceLength - 4;
        final int[] pos = {sourceOffset + 1};
        // 2 つの基準の値。最下位のビットでどちらを使うかを選び、残りが基準からの差 (ジグザグ符号)
        final int[] baseline = new int[2];
        int out = destOffset;
        for (int i = 0; i < indexCount; i++) {
            final int v = readVarint(source, pos, dataEnd);
            final int which = v & 1;
            baseline[which] += unzigzag(v >>> 1);
            out = writeIndex(destination, out, indexSize, baseline[which]);
        }
        if (pos[0] != dataEnd) throw new IOException("Meshopt index sequence has unused data");
    }

    // ---------- 共通の範囲の確認 ----------

    private static void checkDestination(byte[] destination, int offset, int count, int size) throws IOException {
        if (destination == null || offset < 0 || count < 0 || offset > destination.length
                || (long) count * size > destination.length - offset) {
            throw new IOException("Destination buffer is too small for meshopt data");
        }
    }

    private static void checkSource(byte[] source, int offset, int length) throws IOException {
        if (source == null || offset < 0 || length < 1 || offset > source.length || length > source.length - offset) {
            throw new IOException("Truncated meshopt data");
        }
    }

    // pos から count バイトを読めるか (limit より手前に収まるか)
    private static void need(int pos, int count, int limit) throws IOException {
        if (count > limit - pos) throw new IOException("Truncated meshopt data");
    }

    // ---------- フィルタ ----------
    // 結果は仕様で 1 単位の誤差まで許されている。ここでは割り算をまとめて 1 回にした形で計算する

    private static void checkFilter(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (buffer == null || offset < 0 || count < 0 || offset > buffer.length || (long) count * stride > buffer.length - offset) {
            throw new IOException("Buffer is too small for meshopt filter");
        }
    }

    private static int readShort(byte[] b, int p) {
        return (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
    }

    private static void writeShort(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }

    // 0 から遠い方へ丸めて整数にする
    private static int round(float v) {
        return (int) (v + (v >= 0f ? 0.5f : -0.5f));
    }

    /**
     * 八面体の符号化 (OCTAHEDRAL) を、単位ベクトルに戻す。stride は 4 (8 ビット) か 8 (16 ビット)。
     */
    public static void decodeFilterOct(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 4 && stride != 8) throw new IOException("Invalid octahedral filter stride: " + stride);
        checkFilter(buffer, offset, count, stride);
        final boolean wide = stride == 8;
        final float max = wide ? 32767f : 127f;
        for (int i = 0; i < count; i++) {
            final int p = offset + i * stride;
            // 3 つ目の成分は、同じビット数で表した 1.0
            float x = wide ? readShort(buffer, p) : buffer[p];
            float y = wide ? readShort(buffer, p + 2) : buffer[p + 1];
            final float one = wide ? readShort(buffer, p + 4) : buffer[p + 2];
            final float z = one - Math.abs(x) - Math.abs(y);
            // z が負 (下の半球) なら、x と y を折り返す
            final float t = Math.min(z, 0f);
            x -= Math.copySign(t, x);
            y -= Math.copySign(t, y);
            final float length = (float) Math.sqrt(x * x + y * y + z * z);
            final float scale = max / (length > 0f ? length : 1f);
            final int xi = round(x * scale);
            final int yi = round(y * scale);
            final int zi = round(z * scale);
            if (wide) {
                writeShort(buffer, p, xi);
                writeShort(buffer, p + 2, yi);
                writeShort(buffer, p + 4, zi);
            } else {
                buffer[p] = (byte) xi;
                buffer[p + 1] = (byte) yi;
                buffer[p + 2] = (byte) zi;
            }
        }
    }

    /**
     * 最大の成分を省いた四元数 (QUATERNION) を戻す。stride は 8。
     */
    public static void decodeFilterQuat(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 8) throw new IOException("Invalid quaternion filter stride: " + stride);
        checkFilter(buffer, offset, count, stride);
        // 残りの 3 成分は sqrt(2) 倍して持っている
        final float scale = 32767f / (float) Math.sqrt(2.0);
        final int[] out = new int[4];
        for (int i = 0; i < count; i++) {
            final int p = offset + i * 8;
            final int w = readShort(buffer, p + 6);
            // 4 つ目の成分は、下位 2 ビットが省いた成分の位置で、残りが 1.0
            final float one = w | 3;
            final float x = readShort(buffer, p);
            final float y = readShort(buffer, p + 2);
            final float z = readShort(buffer, p + 4);
            final float rest = one * one * 2f - x * x - y * y - z * z;
            final float largest = (float) Math.sqrt(Math.max(rest, 0f));
            final float s = scale / one;
            final int maxComponent = w & 3;
            out[(maxComponent + 1) & 3] = round(x * s);
            out[(maxComponent + 2) & 3] = round(y * s);
            out[(maxComponent + 3) & 3] = round(z * s);
            out[maxComponent] = (int) (largest * s + 0.5f);
            for (int c = 0; c < 4; c++) writeShort(buffer, p + c * 2, out[c]);
        }
    }

    /**
     * 指数と仮数に分けた数 (EXPONENTIAL) を 32 ビットの浮動小数点数に戻す。stride は 4 の倍数。
     */
    public static void decodeFilterExp(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride <= 0 || (stride & 3) != 0) throw new IOException("Invalid exponential filter stride: " + stride);
        checkFilter(buffer, offset, count, stride);
        final int values = count * (stride / 4);
        for (int i = 0; i < values; i++) {
            final int p = offset + i * 4;
            final int v = (buffer[p] & 0xFF) | ((buffer[p + 1] & 0xFF) << 8) | ((buffer[p + 2] & 0xFF) << 16) | (buffer[p + 3] << 24);
            // 上位 8 ビットが符号付きの指数、下位 24 ビットが符号付きの仮数。値は 2^指数 x 仮数 (丸めは 1 回だけ)
            final int exponent = v >> 24;
            final int mantissa = (v << 8) >> 8;
            final int bits = Float.floatToRawIntBits(Math.scalb((float) mantissa, exponent));
            buffer[p] = (byte) bits;
            buffer[p + 1] = (byte) (bits >> 8);
            buffer[p + 2] = (byte) (bits >> 16);
            buffer[p + 3] = (byte) (bits >> 24);
        }
    }

    /**
     * YCoCg で持った色 (COLOR) を RGBA に戻す。stride は 4 (8 ビット) か 8 (16 ビット)。
     */
    public static void decodeFilterColor(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 4 && stride != 8) throw new IOException("Invalid color filter stride: " + stride);
        checkFilter(buffer, offset, count, stride);
        final boolean wide = stride == 8;
        final float max = wide ? 65535f : 255f;
        for (int i = 0; i < count; i++) {
            final int p = offset + i * stride;
            final int y = wide ? readShort(buffer, p) & 0xFFFF : buffer[p] & 0xFF;
            final int co = wide ? readShort(buffer, p + 2) : buffer[p + 1];
            final int cg = wide ? readShort(buffer, p + 4) : buffer[p + 2];
            final int alpha = wide ? readShort(buffer, p + 6) & 0xFFFF : buffer[p + 3] & 0xFF;
            // 透明度の最上位の立っているビットが、値のビット数 K を表す。全部 1 の K ビットが最大値
            final int all = alpha == 0 ? 0 : (Integer.highestOneBit(alpha) << 1) - 1;
            final int r = y + co - cg;
            final int g = y + cg;
            final int b = y - co - cg;
            // 透明度は K - 1 ビットなので、1 ビット広げて最下位を繰り返す
            final int a = ((alpha << 1) & all) | (alpha & 1);
            final float s = max / (all > 0 ? all : 1);
            final int ri = (int) (r * s + 0.5f);
            final int gi = (int) (g * s + 0.5f);
            final int bi = (int) (b * s + 0.5f);
            final int ai = (int) (a * s + 0.5f);
            if (wide) {
                writeShort(buffer, p, ri);
                writeShort(buffer, p + 2, gi);
                writeShort(buffer, p + 4, bi);
                writeShort(buffer, p + 6, ai);
            } else {
                buffer[p] = (byte) ri;
                buffer[p + 1] = (byte) gi;
                buffer[p + 2] = (byte) bi;
                buffer[p + 3] = (byte) ai;
            }
        }
    }
}
