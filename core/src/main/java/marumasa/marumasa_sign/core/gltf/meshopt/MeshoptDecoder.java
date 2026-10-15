package marumasa.marumasa_sign.core.gltf.meshopt;

import java.io.IOException;
import java.util.Arrays;

/**
 * EXT_meshopt_compression / KHR_meshopt_compression の展開を行う。
 * 頂点属性、三角形インデックス、汎用インデックス列の復号および各種フィルタの逆変換に対応する。
 */
public final class MeshoptDecoder {

    private static final int HEADER_VERTEX = 0xA0;
    private static final int HEADER_INDEX = 0xE0;
    private static final int HEADER_SEQUENCE = 0xD0;

    private static final int VERTEX_BLOCK_SIZE_BYTES = 8192;
    private static final int VERTEX_BLOCK_MAX_SIZE = 256;
    private static final int BYTE_GROUP_SIZE = 16;
    private static final int BYTE_GROUP_DECODE_LIMIT = 24;
    private static final int TAIL_MIN_SIZE_V0 = 32;
    private static final int TAIL_MIN_SIZE_V1 = 24;

    private static final int[] BITS_V0 = {0, 2, 4, 8};
    private static final int[] BITS_V1_0 = {0, 1, 2, 4};
    private static final int[] BITS_V1_1 = {1, 2, 4, 8};

    private MeshoptDecoder() {
    }

    private static int unzigzag(int v) {
        return (-(v & 1)) ^ (v >>> 1);
    }

    private static int rotate(int v, int r) {
        return (v << r) | (v >>> ((32 - r) & 31));
    }

    private static int getVertexBlockSize(int vertexSize) {
        final int result = (VERTEX_BLOCK_SIZE_BYTES / vertexSize) & ~(BYTE_GROUP_SIZE - 1);
        return result < VERTEX_BLOCK_MAX_SIZE ? result : VERTEX_BLOCK_MAX_SIZE;
    }

    // ---- 頂点属性の復号 (ATTRIBUTES モード) ----

    public static void decodeVertexBuffer(byte[] destination, int destOffset, int vertexCount, int vertexSize,
                                         byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if (vertexSize <= 0 || vertexSize > 256 || (vertexSize % 4) != 0) {
            throw new IOException("Invalid meshopt vertex size: " + vertexSize);
        }
        if (vertexCount < 0 || (long) vertexCount * vertexSize > destination.length - destOffset) {
            throw new IOException("Destination buffer is too small for meshopt vertex buffer");
        }
        if (sourceOffset < 0 || sourceLength < 1 || (long) sourceOffset + sourceLength > source.length) {
            throw new IOException("Truncated meshopt vertex buffer");
        }

        final int header = source[sourceOffset] & 0xFF;
        if ((header & 0xF0) != HEADER_VERTEX) {
            throw new IOException("Invalid meshopt vertex buffer header: 0x" + Integer.toHexString(header));
        }
        final int version = header & 0x0F;
        if (version > 1) {
            throw new IOException("Unsupported meshopt vertex version: " + version);
        }

        final int tailSize = vertexSize + (version == 0 ? 0 : vertexSize / 4);
        final int tailSizeMin = version == 0 ? TAIL_MIN_SIZE_V0 : TAIL_MIN_SIZE_V1;
        final int tailSizePad = tailSize < tailSizeMin ? tailSizeMin : tailSize;
        if (sourceLength < 1 + tailSizePad) {
            throw new IOException("Truncated meshopt vertex buffer tail");
        }

        final int dataEnd = sourceOffset + sourceLength;
        final int tailPos = dataEnd - tailSize;
        final byte[] lastVertex = new byte[256];
        System.arraycopy(source, tailPos, lastVertex, 0, vertexSize);

        final byte[] channels = version == 0 ? null : new byte[vertexSize / 4];
        if (version != 0) {
            System.arraycopy(source, tailPos + vertexSize, channels, 0, vertexSize / 4);
        }

        final int vertexBlockSize = getVertexBlockSize(vertexSize);
        final int[] dataPos = new int[]{sourceOffset + 1};
        final byte[] buffer = new byte[VERTEX_BLOCK_MAX_SIZE * 4];
        final byte[] transposed = new byte[VERTEX_BLOCK_SIZE_BYTES];

        int vertexOffset = 0;
        while (vertexOffset < vertexCount) {
            final int blockSize = Math.min(vertexBlockSize, vertexCount - vertexOffset);
            decodeVertexBlock(source, dataPos, dataEnd, destination, destOffset + vertexOffset * vertexSize,
                    blockSize, vertexSize, lastVertex, channels, version, buffer, transposed);
            vertexOffset += blockSize;
        }

        if (dataEnd - dataPos[0] != tailSizePad) {
            throw new IOException("Meshopt vertex buffer has unread or overflowing data: remaining=" + (dataEnd - dataPos[0]) + ", expected=" + tailSizePad);
        }
    }

    private static void decodeVertexBlock(byte[] source, int[] dataPos, int dataEnd, byte[] destination,
                                          int destOffset, int vertexCount, int vertexSize, byte[] lastVertex,
                                          byte[] channels, int version, byte[] buffer, byte[] transposed) throws IOException {
        final int vertexCountAligned = (vertexCount + BYTE_GROUP_SIZE - 1) & ~(BYTE_GROUP_SIZE - 1);
        final int controlSize = version == 0 ? 0 : vertexSize / 4;
        if (dataEnd - dataPos[0] < controlSize) {
            throw new IOException("Truncated meshopt vertex control data");
        }

        final int controlPos = dataPos[0];
        dataPos[0] += controlSize;

        for (int k = 0; k < vertexSize; k += 4) {
            final int ctrlByte = version == 0 ? 0 : (source[controlPos + k / 4] & 0xFF);
            for (int j = 0; j < 4; j++) {
                final int ctrl = (ctrlByte >>> (j * 2)) & 3;
                final int bufOffset = j * vertexCount;
                if (ctrl == 3) {
                    if (dataEnd - dataPos[0] < vertexCount) {
                        throw new IOException("Truncated meshopt vertex literal data");
                    }
                    System.arraycopy(source, dataPos[0], buffer, bufOffset, vertexCount);
                    dataPos[0] += vertexCount;
                } else if (ctrl == 2) {
                    Arrays.fill(buffer, bufOffset, bufOffset + vertexCount, (byte) 0);
                } else {
                    final int[] bits = version == 0 ? BITS_V0 : (ctrl == 1 ? BITS_V1_1 : BITS_V1_0);
                    decodeBytes(source, dataPos, dataEnd, buffer, bufOffset, vertexCountAligned, bits);
                }
            }

            final int channel = version == 0 ? 0 : (channels[k / 4] & 0xFF);
            decodeDeltas4(buffer, transposed, vertexCount, vertexSize, lastVertex, k, channel);
        }

        System.arraycopy(transposed, 0, destination, destOffset, vertexCount * vertexSize);
        System.arraycopy(transposed, (vertexCount - 1) * vertexSize, lastVertex, 0, vertexSize);
    }

    private static void decodeBytes(byte[] source, int[] dataPos, int dataEnd, byte[] buffer, int bufOffset,
                                    int bufferSize, int[] bits) throws IOException {
        final int headerSize = (bufferSize / BYTE_GROUP_SIZE + 3) / 4;
        if (dataEnd - dataPos[0] < headerSize) {
            throw new IOException("Truncated meshopt bytes header");
        }
        final int headerPos = dataPos[0];
        dataPos[0] += headerSize;

        for (int i = 0; i < bufferSize; i += BYTE_GROUP_SIZE) {
            if (dataEnd - dataPos[0] < BYTE_GROUP_DECODE_LIMIT) {
                throw new IOException("Truncated meshopt bytes group");
            }
            final int headerOffset = i / BYTE_GROUP_SIZE;
            final int bitsk = (source[headerPos + headerOffset / 4] >>> ((headerOffset % 4) * 2)) & 3;
            decodeBytesGroup(source, dataPos, buffer, bufOffset + i, bits[bitsk]);
        }
    }

    private static void decodeBytesGroup(byte[] source, int[] dataPos, byte[] buffer, int outOffset, int bits) {
        if (bits == 0) {
            Arrays.fill(buffer, outOffset, outOffset + BYTE_GROUP_SIZE, (byte) 0);
            return;
        }
        if (bits == 8) {
            System.arraycopy(source, dataPos[0], buffer, outOffset, BYTE_GROUP_SIZE);
            dataPos[0] += BYTE_GROUP_SIZE;
            return;
        }

        final int byteSize = 8 >> (bits >> 1);
        final int sentinel = (1 << bits) - 1;
        int dataVar = dataPos[0] + (BYTE_GROUP_SIZE / byteSize);

        int out = outOffset;
        for (int i = 0; i < BYTE_GROUP_SIZE; i += byteSize) {
            int b = source[dataPos[0]++] & 0xFF;
            if (bits == 1) {
                // ビット順を反転する
                b = (int) (((b * 0x80200802L) & 0x0884422110L) * 0x0101010101L >>> 32) & 0xFF;
            }
            for (int k = 0; k < byteSize; k++) {
                final int shift = 8 - (k + 1) * bits;
                final int enc = (b >>> shift) & sentinel;
                if (enc == sentinel) {
                    buffer[out++] = source[dataVar++];
                } else {
                    buffer[out++] = (byte) enc;
                }
            }
        }
        dataPos[0] = dataVar;
    }

    private static void decodeDeltas4(byte[] buffer, byte[] transposed, int vertexCount, int vertexSize,
                                      byte[] lastVertex, int k, int channel) {
        final int type = channel & 3;
        if (type == 0) {
            for (int j = 0; j < 4; j++) {
                int p = lastVertex[k + j] & 0xFF;
                int vertexOffset = k + j;
                final int bufBase = j * vertexCount;
                for (int i = 0; i < vertexCount; i++) {
                    final int v = (unzigzag(buffer[bufBase + i] & 0xFF) + p) & 0xFF;
                    transposed[vertexOffset] = (byte) v;
                    p = v;
                    vertexOffset += vertexSize;
                }
            }
        } else if (type == 1) {
            for (int j = 0; j < 4; j += 2) {
                int p = (lastVertex[k + j] & 0xFF) | ((lastVertex[k + j + 1] & 0xFF) << 8);
                int vertexOffset = k + j;
                final int buf0 = j * vertexCount;
                final int buf1 = (j + 1) * vertexCount;
                for (int i = 0; i < vertexCount; i++) {
                    final int enc = (buffer[buf0 + i] & 0xFF) | ((buffer[buf1 + i] & 0xFF) << 8);
                    final int v = (unzigzag(enc) + p) & 0xFFFF;
                    transposed[vertexOffset] = (byte) v;
                    transposed[vertexOffset + 1] = (byte) (v >>> 8);
                    p = v;
                    vertexOffset += vertexSize;
                }
            }
        } else {
            final int rot = (32 - (channel >>> 4)) & 31;
            int p = (lastVertex[k] & 0xFF) | ((lastVertex[k + 1] & 0xFF) << 8)
                    | ((lastVertex[k + 2] & 0xFF) << 16) | ((lastVertex[k + 3] & 0xFF) << 24);
            int vertexOffset = k;
            final int b0 = 0;
            final int b1 = vertexCount;
            final int b2 = vertexCount * 2;
            final int b3 = vertexCount * 3;
            for (int i = 0; i < vertexCount; i++) {
                final int enc = (buffer[b0 + i] & 0xFF) | ((buffer[b1 + i] & 0xFF) << 8)
                        | ((buffer[b2 + i] & 0xFF) << 16) | ((buffer[b3 + i] & 0xFF) << 24);
                final int v = rotate(enc, rot) ^ p;
                transposed[vertexOffset] = (byte) v;
                transposed[vertexOffset + 1] = (byte) (v >>> 8);
                transposed[vertexOffset + 2] = (byte) (v >>> 16);
                transposed[vertexOffset + 3] = (byte) (v >>> 24);
                p = v;
                vertexOffset += vertexSize;
            }
        }
    }

    // ---- 三角形インデックスの復号 (TRIANGLES モード) ----

    public static void decodeIndexBuffer(byte[] destination, int destOffset, int indexCount, int indexSize,
                                        byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if ((indexCount % 3) != 0 || (indexSize != 2 && indexSize != 4)) {
            throw new IOException("Invalid meshopt index buffer parameters: count=" + indexCount + ", size=" + indexSize);
        }
        if (indexCount < 0 || (long) indexCount * indexSize > destination.length - destOffset) {
            throw new IOException("Destination buffer is too small for meshopt index buffer");
        }
        if (sourceOffset < 0 || sourceLength < 1 + indexCount / 3 + 16 || (long) sourceOffset + sourceLength > source.length) {
            throw new IOException("Truncated meshopt index buffer");
        }

        final int header = source[sourceOffset] & 0xFF;
        if ((header & 0xF0) != HEADER_INDEX) {
            throw new IOException("Invalid meshopt index header: 0x" + Integer.toHexString(header));
        }
        final int version = header & 0x0F;
        if (version > 1) {
            throw new IOException("Unsupported meshopt index version: " + version);
        }

        final int fecmax = version >= 1 ? 13 : 15;
        final int codeSafeEnd = sourceOffset + 1 + indexCount / 3;
        final int dataSafeEnd = sourceOffset + sourceLength - 16;
        final int codeauxTablePos = dataSafeEnd;

        final int[][] edgeFifo = new int[16][2];
        final int[] vertexFifo = new int[16];
        Arrays.fill(vertexFifo, -1);
        for (int i = 0; i < 16; i++) {
            edgeFifo[i][0] = -1;
            edgeFifo[i][1] = -1;
        }

        int edgeFifoOffset = 0;
        int vertexFifoOffset = 0;
        int next = 0;
        int last = 0;

        int codePos = sourceOffset + 1;
        final int[] dataPos = new int[]{codeSafeEnd};
        int destPos = destOffset;

        while (codePos < codeSafeEnd) {
            final int codetri = source[codePos++] & 0xFF;
            if (codetri < 0xF0) {
                final int fe = codetri >>> 4;
                final int edgeIndex = (edgeFifoOffset - 1 - fe) & 15;
                final int a = edgeFifo[edgeIndex][0];
                final int b = edgeFifo[edgeIndex][1];
                int c;

                final int fec = codetri & 15;
                if (fec < fecmax) {
                    final int cf = vertexFifo[(vertexFifoOffset - 1 - fec) & 15];
                    c = (fec == 0) ? next : cf;
                    final boolean fec0 = fec == 0;
                    if (fec0) next++;
                    vertexFifo[vertexFifoOffset] = c;
                    if (fec0) vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                } else {
                    if (dataPos[0] > dataSafeEnd) throw new IOException("Truncated meshopt index data");
                    last = c = (fec != 15) ? last + (fec * 2 - 27) : decodeIndex(source, dataPos, dataSafeEnd, last);
                    vertexFifo[vertexFifoOffset] = c;
                    vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                }

                edgeFifo[edgeFifoOffset][0] = c;
                edgeFifo[edgeFifoOffset][1] = b;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
                edgeFifo[edgeFifoOffset][0] = a;
                edgeFifo[edgeFifoOffset][1] = c;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;

                destPos = writeIndex(destination, destPos, indexSize, a);
                destPos = writeIndex(destination, destPos, indexSize, b);
                destPos = writeIndex(destination, destPos, indexSize, c);
            } else if (codetri < 0xFE) {
                final int codeaux = source[codeauxTablePos + (codetri & 15)] & 0xFF;
                final int feb = codeaux >>> 4;
                final int fec = codeaux & 15;

                final int a = next++;
                final int bf = vertexFifo[(vertexFifoOffset - feb) & 15];
                final int b = (feb == 0) ? next : bf;
                final boolean feb0 = feb == 0;
                if (feb0) next++;

                final int cf = vertexFifo[(vertexFifoOffset - fec) & 15];
                final int c = (fec == 0) ? next : cf;
                final boolean fec0 = fec == 0;
                if (fec0) next++;

                destPos = writeIndex(destination, destPos, indexSize, a);
                destPos = writeIndex(destination, destPos, indexSize, b);
                destPos = writeIndex(destination, destPos, indexSize, c);

                vertexFifo[vertexFifoOffset] = a;
                vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                vertexFifo[vertexFifoOffset] = b;
                if (feb0) vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                vertexFifo[vertexFifoOffset] = c;
                if (fec0) vertexFifoOffset = (vertexFifoOffset + 1) & 15;

                edgeFifo[edgeFifoOffset][0] = b;
                edgeFifo[edgeFifoOffset][1] = a;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
                edgeFifo[edgeFifoOffset][0] = c;
                edgeFifo[edgeFifoOffset][1] = b;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
                edgeFifo[edgeFifoOffset][0] = a;
                edgeFifo[edgeFifoOffset][1] = c;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
            } else {
                if (dataPos[0] >= dataSafeEnd) throw new IOException("Truncated meshopt index data");
                final int codeaux = source[dataPos[0]++] & 0xFF;
                final int fea = codetri == 0xFE ? 0 : 15;
                final int feb = codeaux >>> 4;
                final int fec = codeaux & 15;

                if (codeaux == 0) next = 0;

                int a = (fea == 0) ? next++ : 0;
                int b = (feb == 0) ? next++ : vertexFifo[(vertexFifoOffset - feb) & 15];
                int c = (fec == 0) ? next++ : vertexFifo[(vertexFifoOffset - fec) & 15];

                if (fea == 15) last = a = decodeIndex(source, dataPos, dataSafeEnd, last);
                if (feb == 15) last = b = decodeIndex(source, dataPos, dataSafeEnd, last);
                if (fec == 15) last = c = decodeIndex(source, dataPos, dataSafeEnd, last);

                destPos = writeIndex(destination, destPos, indexSize, a);
                destPos = writeIndex(destination, destPos, indexSize, b);
                destPos = writeIndex(destination, destPos, indexSize, c);

                vertexFifo[vertexFifoOffset] = a;
                vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                vertexFifo[vertexFifoOffset] = b;
                if (feb == 0 || feb == 15) vertexFifoOffset = (vertexFifoOffset + 1) & 15;
                vertexFifo[vertexFifoOffset] = c;
                if (fec == 0 || fec == 15) vertexFifoOffset = (vertexFifoOffset + 1) & 15;

                edgeFifo[edgeFifoOffset][0] = b;
                edgeFifo[edgeFifoOffset][1] = a;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
                edgeFifo[edgeFifoOffset][0] = c;
                edgeFifo[edgeFifoOffset][1] = b;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
                edgeFifo[edgeFifoOffset][0] = a;
                edgeFifo[edgeFifoOffset][1] = c;
                edgeFifoOffset = (edgeFifoOffset + 1) & 15;
            }
        }

        if (dataPos[0] != dataSafeEnd) {
            throw new IOException("Meshopt index buffer has unread or overflowing data");
        }
    }

    private static int writeIndex(byte[] destination, int pos, int indexSize, int index) {
        if (indexSize == 2) {
            destination[pos] = (byte) index;
            destination[pos + 1] = (byte) (index >>> 8);
            return pos + 2;
        }
        destination[pos] = (byte) index;
        destination[pos + 1] = (byte) (index >>> 8);
        destination[pos + 2] = (byte) (index >>> 16);
        destination[pos + 3] = (byte) (index >>> 24);
        return pos + 4;
    }

    private static int decodeVByte(byte[] source, int[] dataPos, int dataSafeEnd) throws IOException {
        if (dataPos[0] > dataSafeEnd) throw new IOException("Truncated vbyte data");
        final int lead = source[dataPos[0]++] & 0xFF;
        if (lead < 128) return lead;
        int result = lead & 127;
        int shift = 7;
        for (int i = 0; i < 4; i++) {
            if (dataPos[0] > dataSafeEnd) throw new IOException("Truncated vbyte data");
            final int group = source[dataPos[0]++] & 0xFF;
            result |= (group & 127) << shift;
            shift += 7;
            if (group < 128) break;
        }
        return result;
    }

    private static int decodeIndex(byte[] source, int[] dataPos, int dataSafeEnd, int last) throws IOException {
        final int v = decodeVByte(source, dataPos, dataSafeEnd);
        final int d = (v >>> 1) ^ (-(v & 1));
        return last + d;
    }

    // ---- 汎用インデックス列の復号 (INDICES モード) ----

    public static void decodeIndexSequence(byte[] destination, int destOffset, int indexCount, int indexSize,
                                          byte[] source, int sourceOffset, int sourceLength) throws IOException {
        if (indexSize != 2 && indexSize != 4) {
            throw new IOException("Invalid meshopt index size: " + indexSize);
        }
        if (indexCount < 0 || (long) indexCount * indexSize > destination.length - destOffset) {
            throw new IOException("Destination buffer is too small for meshopt index sequence");
        }
        if (sourceOffset < 0 || sourceLength < 1 + indexCount + 4 || (long) sourceOffset + sourceLength > source.length) {
            throw new IOException("Truncated meshopt index sequence");
        }

        final int header = source[sourceOffset] & 0xFF;
        if ((header & 0xF0) != HEADER_SEQUENCE) {
            throw new IOException("Invalid meshopt sequence header: 0x" + Integer.toHexString(header));
        }
        final int version = header & 0x0F;
        if (version > 1) {
            throw new IOException("Unsupported meshopt sequence version: " + version);
        }

        final int dataSafeEnd = sourceOffset + sourceLength - 4;
        final int[] dataPos = new int[]{sourceOffset + 1};
        final int[] last = new int[2];
        int destPos = destOffset;

        for (int i = 0; i < indexCount; i++) {
            if (dataPos[0] >= dataSafeEnd) throw new IOException("Truncated meshopt sequence data");
            int v = decodeVByte(source, dataPos, dataSafeEnd);
            final int current = v & 1;
            v >>>= 1;
            final int d = (v >>> 1) ^ (-(v & 1));
            final int index = last[current] + d;
            last[current] = index;
            destPos = writeIndex(destination, destPos, indexSize, index);
        }

        if (dataPos[0] != dataSafeEnd) {
            throw new IOException("Meshopt sequence buffer has unread or overflowing data");
        }
    }

    // ---- フィルタ逆変換 ----

    public static void decodeFilterOct(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 4 && stride != 8) {
            throw new IOException("Invalid octahedral filter stride: " + stride);
        }
        if (offset < 0 || (long) count * stride > buffer.length - offset) {
            throw new IOException("Buffer is too small for octahedral filter");
        }

        if (stride == 4) {
            final float max = 127f;
            for (int i = 0; i < count; i++) {
                final int base = offset + i * 4;
                float x = buffer[base];
                float y = buffer[base + 1];
                float z = (buffer[base + 2] & 0xFF) - Math.abs(x) - Math.abs(y);
                final float t = (z >= 0f) ? 0f : z;
                x += (x >= 0f) ? t : -t;
                y += (y >= 0f) ? t : -t;
                final float l = (float) Math.sqrt(x * x + y * y + z * z);
                final float s = max / (l > 0f ? l : 1f);
                final int xf = (int) (x * s + (x >= 0f ? 0.5f : -0.5f));
                final int yf = (int) (y * s + (y >= 0f ? 0.5f : -0.5f));
                final int zf = (int) (z * s + (z >= 0f ? 0.5f : -0.5f));
                buffer[base] = (byte) xf;
                buffer[base + 1] = (byte) yf;
                buffer[base + 2] = (byte) zf;
            }
        } else {
            final float max = 32767f;
            for (int i = 0; i < count; i++) {
                final int base = offset + i * 8;
                final short sx = (short) ((buffer[base] & 0xFF) | (buffer[base + 1] << 8));
                final short sy = (short) ((buffer[base + 2] & 0xFF) | (buffer[base + 3] << 8));
                final short sz = (short) ((buffer[base + 4] & 0xFF) | (buffer[base + 5] << 8));
                float x = sx;
                float y = sy;
                float z = (sz & 0xFFFF) - Math.abs(x) - Math.abs(y);
                final float t = (z >= 0f) ? 0f : z;
                x += (x >= 0f) ? t : -t;
                y += (y >= 0f) ? t : -t;
                final float l = (float) Math.sqrt(x * x + y * y + z * z);
                final float s = max / (l > 0f ? l : 1f);
                final int xf = (int) (x * s + (x >= 0f ? 0.5f : -0.5f));
                final int yf = (int) (y * s + (y >= 0f ? 0.5f : -0.5f));
                final int zf = (int) (z * s + (z >= 0f ? 0.5f : -0.5f));
                buffer[base] = (byte) xf;
                buffer[base + 1] = (byte) (xf >>> 8);
                buffer[base + 2] = (byte) yf;
                buffer[base + 3] = (byte) (yf >>> 8);
                buffer[base + 4] = (byte) zf;
                buffer[base + 5] = (byte) (zf >>> 8);
            }
        }
    }

    public static void decodeFilterQuat(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 8) {
            throw new IOException("Invalid quaternion filter stride: " + stride);
        }
        if (offset < 0 || (long) count * stride > buffer.length - offset) {
            throw new IOException("Buffer is too small for quaternion filter");
        }

        final float scale = 32767f / (float) Math.sqrt(2.0);
        for (int i = 0; i < count; i++) {
            final int base = offset + i * 8;
            final short sx = (short) ((buffer[base] & 0xFF) | (buffer[base + 1] << 8));
            final short sy = (short) ((buffer[base + 2] & 0xFF) | (buffer[base + 3] << 8));
            final short sz = (short) ((buffer[base + 4] & 0xFF) | (buffer[base + 5] << 8));
            final short sw = (short) ((buffer[base + 6] & 0xFF) | (buffer[base + 7] << 8));

            final int sf = sw | 3;
            final float s = sf;
            final float x = sx;
            final float y = sy;
            final float z = sz;

            final float ws = s * s;
            final float ww = ws * 2f - x * x - y * y - z * z;
            final float w = (float) Math.sqrt(ww >= 0f ? ww : 0f);
            final float ss = scale / (s != 0f ? s : 1f);

            final int xf = (int) (x * ss + (x >= 0f ? 0.5f : -0.5f));
            final int yf = (int) (y * ss + (y >= 0f ? 0.5f : -0.5f));
            final int zf = (int) (z * ss + (z >= 0f ? 0.5f : -0.5f));
            final int wf = (int) (w * ss + 0.5f);

            final int qc = sw & 3;
            final int[] out = new int[4];
            out[(qc + 1) & 3] = xf;
            out[(qc + 2) & 3] = yf;
            out[(qc + 3) & 3] = zf;
            out[(qc + 0) & 3] = wf;

            for (int j = 0; j < 4; j++) {
                buffer[base + j * 2] = (byte) out[j];
                buffer[base + j * 2 + 1] = (byte) (out[j] >>> 8);
            }
        }
    }

    public static void decodeFilterExp(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride <= 0 || (stride % 4) != 0) {
            throw new IOException("Invalid exponential filter stride: " + stride);
        }
        if (offset < 0 || (long) count * stride > buffer.length - offset) {
            throw new IOException("Buffer is too small for exponential filter");
        }

        final int totalValues = count * (stride / 4);
        for (int i = 0; i < totalValues; i++) {
            final int base = offset + i * 4;
            final int v = (buffer[base] & 0xFF) | ((buffer[base + 1] & 0xFF) << 8)
                    | ((buffer[base + 2] & 0xFF) << 16) | ((buffer[base + 3] & 0xFF) << 24);
            final int m = (v << 8) >> 8;
            final int e = v >> 24;

            final int expBits = (e + 127) << 23;
            final float factor = Float.intBitsToFloat(expBits);
            final float val = factor * (float) m;
            final int outBits = Float.floatToRawIntBits(val);

            buffer[base] = (byte) outBits;
            buffer[base + 1] = (byte) (outBits >>> 8);
            buffer[base + 2] = (byte) (outBits >>> 16);
            buffer[base + 3] = (byte) (outBits >>> 24);
        }
    }

    public static void decodeFilterColor(byte[] buffer, int offset, int count, int stride) throws IOException {
        if (stride != 4 && stride != 8) {
            throw new IOException("Invalid color filter stride: " + stride);
        }
        if (offset < 0 || (long) count * stride > buffer.length - offset) {
            throw new IOException("Buffer is too small for color filter");
        }

        if (stride == 4) {
            final float max = 255f;
            for (int i = 0; i < count; i++) {
                final int base = offset + i * 4;
                int as = buffer[base + 3] & 0xFF;
                as |= as >> 1;
                as |= as >> 2;
                as |= as >> 4;

                final int y = buffer[base] & 0xFF;
                final byte co = buffer[base + 1];
                final byte cg = buffer[base + 2];

                final int r = y + co - cg;
                final int g = y + cg;
                final int b = y - co - cg;

                int a = buffer[base + 3] & 0xFF;
                a = ((a << 1) & as) | (a & 1);

                final float ss = max / (as > 0 ? (float) as : 1f);
                final int rf = (int) (r * ss + 0.5f);
                final int gf = (int) (g * ss + 0.5f);
                final int bf = (int) (b * ss + 0.5f);
                final int af = (int) (a * ss + 0.5f);

                buffer[base] = (byte) rf;
                buffer[base + 1] = (byte) gf;
                buffer[base + 2] = (byte) bf;
                buffer[base + 3] = (byte) af;
            }
        } else {
            final float max = 65535f;
            for (int i = 0; i < count; i++) {
                final int base = offset + i * 8;
                int as = (buffer[base + 6] & 0xFF) | ((buffer[base + 7] & 0xFF) << 8);
                as |= as >> 1;
                as |= as >> 2;
                as |= as >> 4;
                as |= as >> 8;

                final int y = (buffer[base] & 0xFF) | ((buffer[base + 1] & 0xFF) << 8);
                final short co = (short) ((buffer[base + 2] & 0xFF) | (buffer[base + 3] << 8));
                final short cg = (short) ((buffer[base + 4] & 0xFF) | (buffer[base + 5] << 8));

                final int r = y + co - cg;
                final int g = y + cg;
                final int b = y - co - cg;

                int a = (buffer[base + 6] & 0xFF) | ((buffer[base + 7] & 0xFF) << 8);
                a = ((a << 1) & as) | (a & 1);

                final float ss = max / (as > 0 ? (float) as : 1f);
                final int rf = (int) (r * ss + 0.5f);
                final int gf = (int) (g * ss + 0.5f);
                final int bf = (int) (b * ss + 0.5f);
                final int af = (int) (a * ss + 0.5f);

                buffer[base] = (byte) rf;
                buffer[base + 1] = (byte) (rf >>> 8);
                buffer[base + 2] = (byte) gf;
                buffer[base + 3] = (byte) (gf >>> 8);
                buffer[base + 4] = (byte) bf;
                buffer[base + 5] = (byte) (bf >>> 8);
                buffer[base + 6] = (byte) af;
                buffer[base + 7] = (byte) (af >>> 8);
            }
        }
    }
}
