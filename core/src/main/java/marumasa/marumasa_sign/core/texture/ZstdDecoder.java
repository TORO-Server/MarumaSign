package marumasa.marumasa_sign.core.texture;

import java.io.IOException;
import java.util.Arrays;

/**
 * Zstandard (RFC 8878) の展開。KTX2 の超圧縮 (supercompressionScheme 2) に使う。
 * 辞書は使わない (KTX2 では使われない)。
 */
public final class ZstdDecoder {

    private static final int MAGIC = 0xFD2FB528;
    // ブロックの大きさ (展開する前と後) の上限 (RFC 8878 3.1.1.2.4 の Block_Maximum_Size)
    private static final int MAX_BLOCK_SIZE = 128 * 1024;

    // 長さの符号ごとの、基準の値と追加のビット数
    private static final int[] LL_BASE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
    private static final int[] LL_BITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final int[] ML_BASE = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539};
    private static final int[] ML_BITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    // 既定の分布
    private static final short[] LL_DEFAULT = {4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1};
    private static final short[] ML_DEFAULT = {1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1};
    private static final short[] OF_DEFAULT = {1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};

    private ZstdDecoder() {
    }

    /**
     * 展開する。maxSize を超えるときは失敗にする。
     */
    public static byte[] decompress(byte[] src, int offset, int length, int maxSize) throws IOException {
        try {
            return new Frame(src, offset, offset + length, maxSize).run();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Zstandard data: " + e, e);
        }
    }

    // ---------- FSE の表 ----------

    private static final class FseTable {
        int accuracyLog;
        int[] symbol;
        int[] bits;
        int[] base;

        // 確率の分布から表を作る
        static FseTable build(short[] normalized, int symbols, int accuracyLog) throws IOException {
            final int size = 1 << accuracyLog;
            final FseTable table = new FseTable();
            table.accuracyLog = accuracyLog;
            table.symbol = new int[size];
            table.bits = new int[size];
            table.base = new int[size];
            final int[] next = new int[symbols];
            int high = size - 1;
            // 「1 より小さい」確率の記号は、表の後ろから 1 つずつ置く
            for (int s = 0; s < symbols; s++) {
                if (normalized[s] == -1) {
                    if (high < 0) throw new IOException("Invalid FSE distribution");
                    table.symbol[high--] = s;
                    next[s] = 1;
                } else {
                    next[s] = normalized[s];
                }
            }
            final int step = (size >> 1) + (size >> 3) + 3;
            final int mask = size - 1;
            int position = 0;
            for (int s = 0; s < symbols; s++) {
                for (int i = 0; i < normalized[s]; i++) {
                    table.symbol[position] = s;
                    do {
                        position = (position + step) & mask;
                    } while (position > high);
                }
            }
            if (position != 0) throw new IOException("Invalid FSE distribution");
            for (int state = 0; state < size; state++) {
                final int s = table.symbol[state];
                final int nextState = next[s]++;
                final int nbBits = accuracyLog - (31 - Integer.numberOfLeadingZeros(nextState));
                table.bits[state] = nbBits;
                table.base[state] = (nextState << nbBits) - size;
            }
            return table;
        }

        // 1 つの記号だけの表 (RLE)
        static FseTable single(int value) {
            final FseTable table = new FseTable();
            table.accuracyLog = 0;
            table.symbol = new int[]{value};
            table.bits = new int[]{0};
            table.base = new int[]{0};
            return table;
        }
    }

    // ---------- ビットの読み出し ----------

    // 前から下位ビット順に読む (表の記述用)
    private static final class ForwardBits {
        private final byte[] data;
        private final int end;
        private long position;

        ForwardBits(byte[] data, int start, int end) {
            this.data = data;
            this.end = end;
            this.position = (long) start * 8;
        }

        int bits(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                final int index = (int) (position >> 3);
                final int bit = index < end ? (data[index] >> (position & 7)) & 1 : 0;
                result |= bit << i;
                position++;
            }
            return result;
        }

        int peek(int count) {
            final long saved = position;
            final int result = bits(count);
            position = saved;
            return result;
        }

        void skip(int count) {
            position += count;
        }

        int bytePosition() {
            return (int) ((position + 7) >> 3);
        }
    }

    // 後ろから読む (FSE とハフマンの符号の列)。末尾の 1 のビットの手前から始まる
    private static final class BackwardBits {
        private final byte[] data;
        private final int start;
        private long position;

        BackwardBits(byte[] data, int start, int end) throws IOException {
            this.data = data;
            this.start = start;
            if (end <= start) throw new IOException("Empty Zstandard bitstream");
            final int last = data[end - 1] & 0xFF;
            if (last == 0) throw new IOException("Invalid Zstandard bitstream padding");
            position = (long) (end - start - 1) * 8 + (31 - Integer.numberOfLeadingZeros(last));
        }

        // 次の count ビットを、上位から順に読んだ値として返す。先頭より前は 0 として読む
        int bits(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                position--;
                int bit = 0;
                if (position >= 0) bit = (data[start + (int) (position >> 3)] >> (position & 7)) & 1;
                result = (result << 1) | bit;
            }
            return result;
        }

        int peek(int count) {
            final long saved = position;
            final int result = bits(count);
            position = saved;
            return result;
        }

        void skip(int count) {
            position -= count;
        }

        boolean overflowed() {
            return position < 0;
        }

        boolean finished() {
            return position == 0;
        }
    }

    // ---------- フレーム ----------

    private static final class Frame {
        private final byte[] src;
        private int pos;
        private final int end;
        private final int maxSize;

        private byte[] out = new byte[1 << 16];
        private int outSize;
        // 今のフレームの出力が始まる位置。前のフレームの出力はさかのぼって写せない
        private int frameStart;

        private final int[] repeat = {1, 4, 8};
        private FseTable llTable, ofTable, mlTable;
        private int[] huffmanSymbol;
        private int[] huffmanBits;
        private int huffmanLog;

        Frame(byte[] src, int start, int end, int maxSize) {
            this.src = src;
            this.pos = start;
            this.end = end;
            this.maxSize = maxSize;
        }

        byte[] run() throws IOException {
            while (pos < end) {
                if (end - pos < 4) throw new IOException("Truncated Zstandard frame");
                final int magic = readInt(pos);
                if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
                    // 読み飛ばす枠
                    final long size = readInt(pos + 4) & 0xFFFFFFFFL;
                    pos += 8;
                    if (size > end - pos) throw new IOException("Truncated skippable frame");
                    pos += (int) size;
                    continue;
                }
                if (magic != MAGIC) throw new IOException("Not a Zstandard frame");
                pos += 4;
                readFrame();
            }
            return outSize == out.length ? out : Arrays.copyOf(out, outSize);
        }

        private void readFrame() throws IOException {
            final int descriptor = u8(pos++);
            final int fcsFlag = descriptor >> 6;
            final boolean singleSegment = (descriptor & 0x20) != 0;
            final boolean checksum = (descriptor & 0x04) != 0;
            final int dictionaryFlag = descriptor & 3;
            if ((descriptor & 0x08) != 0) throw new IOException("Reserved Zstandard frame bit");
            if (!singleSegment) pos++;
            final int dictionaryBytes = dictionaryFlag == 0 ? 0 : dictionaryFlag == 1 ? 1 : dictionaryFlag == 2 ? 2 : 4;
            if (dictionaryBytes > 0) {
                long id = 0;
                for (int i = 0; i < dictionaryBytes; i++) id |= (long) u8(pos + i) << (8 * i);
                if (id != 0) throw new IOException("Zstandard dictionaries are not supported");
                pos += dictionaryBytes;
            }
            final int fcsBytes = fcsFlag == 0 ? (singleSegment ? 1 : 0) : fcsFlag == 1 ? 2 : fcsFlag == 2 ? 4 : 8;
            long contentSize = -1;
            if (fcsBytes > 0) {
                long size = 0;
                for (int i = 0; i < fcsBytes; i++) size |= (long) u8(pos + i) << (8 * i);
                if (fcsBytes == 2) size += 256;
                if (size < 0 || size > maxSize - outSize) throw new IOException("Zstandard content is too large");
                contentSize = size;
                pos += fcsBytes;
            }
            frameStart = outSize;

            // フレームごとに、繰り返しの距離と表を初めに戻す
            repeat[0] = 1;
            repeat[1] = 4;
            repeat[2] = 8;
            llTable = ofTable = mlTable = null;
            huffmanSymbol = null;

            for (;;) {
                if (end - pos < 3) throw new IOException("Truncated Zstandard block");
                final int header = u8(pos) | u8(pos + 1) << 8 | u8(pos + 2) << 16;
                pos += 3;
                final boolean last = (header & 1) != 0;
                final int type = (header >> 1) & 3;
                final int size = header >>> 3;
                // ブロックは 128 KB を超えない (RLE のブロックは、3 バイトの見出しと 1 バイトで 2 MB 近くに増やせてしまう)
                if (size > MAX_BLOCK_SIZE) throw new IOException("Zstandard block is too large");
                final int blockStart = outSize;
                if (type == 0) {
                    if (size > end - pos) throw new IOException("Truncated Zstandard block");
                    ensure(size);
                    System.arraycopy(src, pos, out, outSize, size);
                    outSize += size;
                    pos += size;
                } else if (type == 1) {
                    if (pos >= end) throw new IOException("Truncated Zstandard block");
                    ensure(size);
                    Arrays.fill(out, outSize, outSize + size, src[pos]);
                    outSize += size;
                    pos++;
                } else if (type == 2) {
                    if (size > end - pos) throw new IOException("Truncated Zstandard block");
                    readCompressedBlock(pos, pos + size);
                    pos += size;
                } else {
                    throw new IOException("Reserved Zstandard block type");
                }
                if (outSize - blockStart > MAX_BLOCK_SIZE) throw new IOException("Zstandard block is too large");
                if (last) break;
            }
            // 書かれていた展開後の大きさと違えば壊れている
            if (contentSize >= 0 && outSize - frameStart != contentSize) throw new IOException("Zstandard content size mismatch");
            if (checksum) {
                if (end - pos < 4) throw new IOException("Truncated Zstandard checksum");
                pos += 4;
            }
        }

        private void readCompressedBlock(int start, int blockEnd) throws IOException {
            // リテラル (そのまま写す文字列)
            int p = start;
            final int b0 = u8(p);
            final int literalsType = b0 & 3;
            final int sizeFormat = (b0 >> 2) & 3;
            int regenerated, compressed = 0, streams = 1;
            if (literalsType < 2) {
                if ((sizeFormat & 1) == 0) {
                    regenerated = b0 >> 3;
                    p += 1;
                } else if (sizeFormat == 1) {
                    regenerated = (b0 >> 4) + (u8(p + 1) << 4);
                    p += 2;
                } else {
                    regenerated = (b0 >> 4) + (u8(p + 1) << 4) + (u8(p + 2) << 12);
                    p += 3;
                }
            } else {
                final long h;
                if (sizeFormat < 2) {
                    h = u8(p) | (long) u8(p + 1) << 8 | (long) u8(p + 2) << 16;
                    regenerated = (int) ((h >> 4) & 0x3FF);
                    compressed = (int) ((h >> 14) & 0x3FF);
                    streams = sizeFormat == 0 ? 1 : 4;
                    p += 3;
                } else if (sizeFormat == 2) {
                    h = u8(p) | (long) u8(p + 1) << 8 | (long) u8(p + 2) << 16 | (long) u8(p + 3) << 24;
                    regenerated = (int) ((h >> 4) & 0x3FFF);
                    compressed = (int) ((h >> 18) & 0x3FFF);
                    streams = 4;
                    p += 4;
                } else {
                    h = u8(p) | (long) u8(p + 1) << 8 | (long) u8(p + 2) << 16 | (long) u8(p + 3) << 24 | (long) u8(p + 4) << 32;
                    regenerated = (int) ((h >> 4) & 0x3FFFF);
                    compressed = (int) ((h >> 22) & 0x3FFFF);
                    streams = 4;
                    p += 5;
                }
            }

            final byte[] literals = new byte[regenerated];
            if (literalsType == 0) {
                if (regenerated > blockEnd - p) throw new IOException("Truncated Zstandard literals");
                System.arraycopy(src, p, literals, 0, regenerated);
                p += regenerated;
            } else if (literalsType == 1) {
                if (p >= blockEnd) throw new IOException("Truncated Zstandard literals");
                Arrays.fill(literals, src[p]);
                p += 1;
            } else {
                if (compressed > blockEnd - p) throw new IOException("Truncated Zstandard literals");
                final int literalsEnd = p + compressed;
                if (literalsType == 2) {
                    p = readHuffmanTable(p, literalsEnd);
                } else if (huffmanSymbol == null) {
                    throw new IOException("Zstandard literals refer to a missing Huffman table");
                }
                decodeLiterals(p, literalsEnd, streams, literals);
                p = literalsEnd;
            }

            // 並び (リテラルの長さ、さかのぼる距離、写す長さ)
            if (p >= blockEnd) throw new IOException("Truncated Zstandard sequences");
            int count = u8(p++);
            if (count >= 128) {
                if (count < 255) {
                    count = ((count - 128) << 8) + u8(p++);
                } else {
                    count = u8(p) + (u8(p + 1) << 8) + 0x7F00;
                    p += 2;
                }
            }
            if (count == 0) {
                append(literals, 0, literals.length);
                return;
            }
            final int modes = u8(p++);
            if ((modes & 3) != 0) throw new IOException("Reserved Zstandard sequence mode bits");
            p = readTable(p, blockEnd, modes >> 6, 0);
            p = readTable(p, blockEnd, (modes >> 4) & 3, 1);
            p = readTable(p, blockEnd, (modes >> 2) & 3, 2);

            final BackwardBits bits = new BackwardBits(src, p, blockEnd);
            int llState = bits.bits(llTable.accuracyLog);
            int ofState = bits.bits(ofTable.accuracyLog);
            int mlState = bits.bits(mlTable.accuracyLog);
            int literalPos = 0;
            for (int i = 0; i < count; i++) {
                final int ofCode = ofTable.symbol[ofState];
                final int mlCode = mlTable.symbol[mlState];
                final int llCode = llTable.symbol[llState];
                if (ofCode > 31 || mlCode >= ML_BASE.length || llCode >= LL_BASE.length) {
                    throw new IOException("Invalid Zstandard sequence code");
                }
                final long offsetValue = (1L << ofCode) + bits.bits(ofCode);
                final int matchLength = ML_BASE[mlCode] + bits.bits(ML_BITS[mlCode]);
                final int literalLength = LL_BASE[llCode] + bits.bits(LL_BITS[llCode]);

                final int offset;
                if (offsetValue > 3) {
                    if (offsetValue - 3 > Integer.MAX_VALUE) throw new IOException("Zstandard offset is too large");
                    offset = (int) (offsetValue - 3);
                    repeat[2] = repeat[1];
                    repeat[1] = repeat[0];
                    repeat[0] = offset;
                } else {
                    int index = (int) offsetValue - 1 + (literalLength == 0 ? 1 : 0);
                    if (index == 0) {
                        offset = repeat[0];
                    } else {
                        offset = index == 3 ? repeat[0] - 1 : repeat[index];
                        if (index != 1) repeat[2] = repeat[1];
                        repeat[1] = repeat[0];
                        repeat[0] = offset;
                    }
                }

                // リテラルを写し、そのあと過去の出力を写す
                if (literalLength > literals.length - literalPos) throw new IOException("Zstandard literals overflow");
                append(literals, literalPos, literalLength);
                literalPos += literalLength;
                if (offset <= 0 || offset > outSize - frameStart) throw new IOException("Invalid Zstandard match offset");
                ensure(matchLength);
                int from = outSize - offset;
                for (int k = 0; k < matchLength; k++) {
                    out[outSize++] = out[from++];
                }

                if (i < count - 1) {
                    llState = llTable.base[llState] + bits.bits(llTable.bits[llState]);
                    mlState = mlTable.base[mlState] + bits.bits(mlTable.bits[mlState]);
                    ofState = ofTable.base[ofState] + bits.bits(ofTable.bits[ofState]);
                }
            }
            if (!bits.finished()) throw new IOException("Zstandard sequence bitstream not fully consumed");
            append(literals, literalPos, literals.length - literalPos);
        }

        // 並びの 3 種類の値の表を読む。kind: 0 リテラルの長さ、1 距離、2 写す長さ
        private int readTable(int p, int blockEnd, int mode, int kind) throws IOException {
            final FseTable table;
            if (mode == 0) {
                table = kind == 0 ? FseTable.build(LL_DEFAULT, LL_DEFAULT.length, 6)
                        : kind == 1 ? FseTable.build(OF_DEFAULT, OF_DEFAULT.length, 5)
                        : FseTable.build(ML_DEFAULT, ML_DEFAULT.length, 6);
            } else if (mode == 1) {
                if (p >= blockEnd) throw new IOException("Truncated Zstandard table");
                table = FseTable.single(u8(p++));
            } else if (mode == 2) {
                final int maxLog = kind == 1 ? 8 : 9;
                final int maxSymbol = kind == 0 ? 35 : kind == 1 ? 31 : 52;
                final short[] normalized = new short[maxSymbol + 1];
                final ForwardBits bits = new ForwardBits(src, p, blockEnd);
                final int[] result = readDistribution(bits, normalized, maxSymbol, maxLog);
                table = FseTable.build(normalized, result[0], result[1]);
                p = bits.bytePosition();
            } else {
                table = kind == 0 ? llTable : kind == 1 ? ofTable : mlTable;
                if (table == null) throw new IOException("Zstandard table repeat without a previous table");
            }
            if (kind == 0) llTable = table;
            else if (kind == 1) ofTable = table;
            else mlTable = table;
            return p;
        }

        // ---------- ハフマン符号 (リテラル) ----------

        private int readHuffmanTable(int p, int limit) throws IOException {
            final int header = u8(p++);
            final int[] weights = new int[256];
            int count;
            if (header < 128) {
                // 重みを FSE で圧縮してある
                if (header > limit - p) throw new IOException("Truncated Huffman weights");
                final int weightsEnd = p + header;
                final ForwardBits forward = new ForwardBits(src, p, weightsEnd);
                final short[] normalized = new short[16];
                final int[] result = readDistribution(forward, normalized, 15, 6);
                final FseTable table = FseTable.build(normalized, result[0], result[1]);
                final BackwardBits bits = new BackwardBits(src, forward.bytePosition(), weightsEnd);
                int state1 = bits.bits(table.accuracyLog);
                int state2 = bits.bits(table.accuracyLog);
                count = 0;
                for (;;) {
                    if (count >= 255) throw new IOException("Too many Huffman weights");
                    weights[count++] = table.symbol[state1];
                    state1 = table.base[state1] + bits.bits(table.bits[state1]);
                    if (bits.overflowed()) {
                        weights[count++] = table.symbol[state2];
                        break;
                    }
                    if (count >= 255) throw new IOException("Too many Huffman weights");
                    weights[count++] = table.symbol[state2];
                    state2 = table.base[state2] + bits.bits(table.bits[state2]);
                    if (bits.overflowed()) {
                        weights[count++] = table.symbol[state1];
                        break;
                    }
                }
                p = weightsEnd;
            } else {
                count = header - 127;
                final int bytes = (count + 1) / 2;
                if (bytes > limit - p) throw new IOException("Truncated Huffman weights");
                for (int i = 0; i < count; i++) {
                    final int b = u8(p + i / 2);
                    weights[i] = (i & 1) == 0 ? b >> 4 : b & 15;
                }
                p += bytes;
            }

            // 最後の記号の重みは、合計が 2 の累乗になるように決まる
            long total = 0;
            for (int i = 0; i < count; i++) {
                if (weights[i] > 11) throw new IOException("Invalid Huffman weight");
                if (weights[i] > 0) total += 1L << (weights[i] - 1);
            }
            if (total == 0) throw new IOException("Invalid Huffman weights");
            final int maxBits = 64 - Long.numberOfLeadingZeros(total);
            final long rest = (1L << maxBits) - total;
            if (Long.bitCount(rest) != 1) throw new IOException("Invalid Huffman weights");
            if (count >= 256) throw new IOException("Too many Huffman symbols");
            weights[count++] = 64 - Long.numberOfLeadingZeros(rest);
            if (maxBits > 11) throw new IOException("Huffman code is too long");

            // 重みの小さい記号から順に符号を割り当てる
            final int size = 1 << maxBits;
            final int[] rankStart = new int[maxBits + 2];
            final int[] rankCount = new int[maxBits + 2];
            for (int i = 0; i < count; i++) rankCount[weights[i]]++;
            int next = 0;
            for (int w = 1; w <= maxBits; w++) {
                rankStart[w] = next;
                next += rankCount[w] << (w - 1);
            }
            huffmanSymbol = new int[size];
            huffmanBits = new int[size];
            huffmanLog = maxBits;
            for (int s = 0; s < count; s++) {
                final int w = weights[s];
                if (w == 0) continue;
                final int length = 1 << (w - 1);
                for (int k = 0; k < length; k++) {
                    huffmanSymbol[rankStart[w] + k] = s;
                    huffmanBits[rankStart[w] + k] = maxBits + 1 - w;
                }
                rankStart[w] += length;
            }
            return p;
        }

        private void decodeLiterals(int p, int limit, int streams, byte[] literals) throws IOException {
            if (streams == 1) {
                decodeHuffmanStream(p, limit, literals, 0, literals.length);
                return;
            }
            if (limit - p < 6) throw new IOException("Truncated Huffman jump table");
            final int size1 = u8(p) | u8(p + 1) << 8;
            final int size2 = u8(p + 2) | u8(p + 3) << 8;
            final int size3 = u8(p + 4) | u8(p + 5) << 8;
            int start = p + 6;
            final int total = limit - start;
            if (size1 + size2 + size3 > total) throw new IOException("Invalid Huffman jump table");
            final int perStream = (literals.length + 3) / 4;
            final int[] sizes = {size1, size2, size3, total - size1 - size2 - size3};
            int out = 0;
            for (int i = 0; i < 4; i++) {
                final int n = i < 3 ? Math.min(perStream, literals.length - out) : literals.length - out;
                decodeHuffmanStream(start, start + sizes[i], literals, out, n);
                start += sizes[i];
                out += n;
            }
        }

        private void decodeHuffmanStream(int start, int streamEnd, byte[] literals, int offset, int count) throws IOException {
            final BackwardBits bits = new BackwardBits(src, start, streamEnd);
            for (int i = 0; i < count; i++) {
                final int index = bits.peek(huffmanLog);
                literals[offset + i] = (byte) huffmanSymbol[index];
                bits.skip(huffmanBits[index]);
            }
            if (!bits.finished()) throw new IOException("Huffman stream not fully consumed");
        }

        // ---------- 共通 ----------

        // FSE の確率の分布を読む。返り値は {記号の数, 精度}
        private int[] readDistribution(ForwardBits bits, short[] normalized, int maxSymbol, int maxLog) throws IOException {
            final int accuracyLog = bits.bits(4) + 5;
            if (accuracyLog > maxLog) throw new IOException("FSE accuracy is too high");
            int remaining = (1 << accuracyLog) + 1;
            int threshold = 1 << accuracyLog;
            int nbBits = accuracyLog + 1;
            int symbol = 0;
            boolean previousZero = false;
            while (remaining > 1 && symbol <= maxSymbol) {
                if (previousZero) {
                    int repeatCount;
                    do {
                        repeatCount = bits.bits(2);
                        for (int i = 0; i < repeatCount; i++) {
                            if (symbol > maxSymbol) throw new IOException("Too many FSE symbols");
                            normalized[symbol++] = 0;
                        }
                    } while (repeatCount == 3);
                    if (symbol > maxSymbol) break;
                }
                final int max = (2 * threshold - 1) - remaining;
                int count;
                final int low = bits.peek(nbBits - 1);
                if (low < max) {
                    count = low;
                    bits.skip(nbBits - 1);
                } else {
                    count = bits.bits(nbBits);
                    if (count >= threshold) count -= max;
                }
                count--;
                remaining -= Math.abs(count);
                normalized[symbol++] = (short) count;
                previousZero = count == 0;
                while (remaining < threshold) {
                    nbBits--;
                    threshold >>= 1;
                }
            }
            if (remaining != 1) throw new IOException("Invalid FSE distribution");
            return new int[]{symbol, accuracyLog};
        }

        private void append(byte[] data, int offset, int length) throws IOException {
            ensure(length);
            System.arraycopy(data, offset, out, outSize, length);
            outSize += length;
        }

        private void ensure(int extra) throws IOException {
            if (extra < 0 || (long) outSize + extra > maxSize) throw new IOException("Zstandard content is too large");
            if (outSize + extra > out.length) {
                out = Arrays.copyOf(out, (int) Math.min(maxSize, Math.max((long) out.length * 2, (long) outSize + extra)));
            }
        }

        private int u8(int index) throws IOException {
            if (index >= end) throw new IOException("Truncated Zstandard data");
            return src[index] & 0xFF;
        }

        private int readInt(int index) throws IOException {
            return u8(index) | u8(index + 1) << 8 | u8(index + 2) << 16 | u8(index + 3) << 24;
        }
    }
}
