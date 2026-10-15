package marumasa.marumasa_sign.core.texture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Zstandard (RFC 8878) デコーダ。
 * KTX2 の UASTC supercompression (scheme 2) 解凍に必要な機能を提供する。
 */
public final class ZstdDecoder {

    private static final int ZSTD_MAGIC = 0xFD2FB528;

    private ZstdDecoder() {
    }

    public static byte[] decompress(byte[] src, int srcOffset, int srcLength, int maxOutputSize) throws IOException {
        if (src == null || srcOffset < 0 || srcLength < 4 || srcOffset + srcLength > src.length) {
            throw new IOException("Invalid Zstd input data");
        }

        int pos = srcOffset;
        final int end = srcOffset + srcLength;

        final int magic = readIntLE(src, pos);
        pos += 4;
        if (magic != ZSTD_MAGIC) {
            throw new IOException("Not a valid Zstandard frame: magic=" + Integer.toHexString(magic));
        }

        if (pos >= end) throw new IOException("Zstd frame header truncated");
        final int fhd = src[pos++] & 0xFF;
        final int fcsFlag = fhd >>> 6;
        final int singleSeg = (fhd >>> 5) & 1;
        final int contentChecksum = (fhd >>> 2) & 1;
        final int dictIdFlag = fhd & 3;

        int windowSize = 0;
        if (singleSeg == 0) {
            if (pos >= end) throw new IOException("Zstd window descriptor truncated");
            final int winDesc = src[pos++] & 0xFF;
            final int windowExponent = winDesc >>> 3;
            final int windowMantissa = winDesc & 7;
            final int windowBase = 1 << (10 + windowExponent);
            windowSize = windowBase + (windowBase / 8) * windowMantissa;
        }

        if (dictIdFlag != 0) {
            final int dictBytes = (dictIdFlag == 3) ? 4 : dictIdFlag;
            pos += dictBytes;
            if (pos > end) throw new IOException("Zstd dictionary ID truncated");
        }

        int frameContentSize = -1;
        if (fcsFlag != 0 || singleSeg != 0) {
            if (fcsFlag == 0 && singleSeg != 0) {
                if (pos >= end) throw new IOException("Zstd FCS truncated");
                frameContentSize = src[pos++] & 0xFF;
            } else if (fcsFlag == 1) {
                if (pos + 2 > end) throw new IOException("Zstd FCS truncated");
                frameContentSize = (readUShortLE(src, pos) & 0xFFFF) + 256;
                pos += 2;
            } else if (fcsFlag == 2) {
                if (pos + 4 > end) throw new IOException("Zstd FCS truncated");
                frameContentSize = readIntLE(src, pos);
                pos += 4;
            } else if (fcsFlag == 3) {
                if (pos + 8 > end) throw new IOException("Zstd FCS truncated");
                final long fcs64 = readLongLE(src, pos);
                pos += 8;
                if (fcs64 > maxOutputSize || fcs64 > Integer.MAX_VALUE) {
                    throw new IOException("Frame content size too large: " + fcs64);
                }
                frameContentSize = (int) fcs64;
            }
        }

        if (singleSeg != 0 && frameContentSize >= 0) {
            windowSize = frameContentSize;
        }

        final int initialCap = (frameContentSize > 0) ? Math.min(frameContentSize, maxOutputSize) : 65536;
        final FastByteArrayBuffer out = new FastByteArrayBuffer(initialCap);

        final Context ctx = new Context();
        final int limit = end - (contentChecksum != 0 ? 4 : 0);

        while (pos < limit) {
            if (pos + 3 > limit) throw new IOException("Block header truncated");
            final int bh = (src[pos] & 0xFF) | ((src[pos + 1] & 0xFF) << 8) | ((src[pos + 2] & 0xFF) << 16);
            pos += 3;
            final boolean lastBlock = (bh & 1) != 0;
            final int blockType = (bh >>> 1) & 3;
            final int blockSize = bh >>> 3;

            if (pos + blockSize > limit) {
                throw new IOException("Block data exceeds stream boundary");
            }

            switch (blockType) {
                case 0: // Raw
                    out.write(src, pos, blockSize);
                    pos += blockSize;
                    break;
                case 1: // RLE
                    final byte val = src[pos++];
                    for (int i = 0; i < blockSize; i++) {
                        out.writeByte(val);
                    }
                    break;
                case 2: // Compressed
                    decodeCompressedBlock(src, pos, blockSize, ctx, out, maxOutputSize);
                    pos += blockSize;
                    break;
                default:
                    throw new IOException("Reserved block type: " + blockType);
            }

            if (out.size() > maxOutputSize) {
                throw new IOException("Decompressed size exceeds limit: " + out.size() + " > " + maxOutputSize);
            }

            if (lastBlock) break;
        }

        return out.toByteArray();
    }

    private static final class Context {
        final int[] repeatOffsets = {1, 4, 8};
        final FseTable llTable = new FseTable();
        final FseTable ofTable = new FseTable();
        final FseTable mlTable = new FseTable();
        final HuffmanTable huffTable = new HuffmanTable();
    }

    private static void decodeCompressedBlock(byte[] src, int blockOffset, int blockSize, Context ctx,
                                              FastByteArrayBuffer out, int maxOutputSize) throws IOException {
        int pos = blockOffset;
        final int blockEnd = blockOffset + blockSize;
        if (pos >= blockEnd) throw new IOException("Empty compressed block");

        // 1. Literals Section
        final int litHdr = src[pos++] & 0xFF;
        final int litType = litHdr & 3;
        final int sizeFmt = (litHdr >>> 2) & 3;

        int regenSize;
        int compSize = 0;
        int numStreams = 1;

        if (litType == 0 || litType == 1) { // Raw or RLE
            if (sizeFmt == 0 || sizeFmt == 2) {
                regenSize = litHdr >>> 3;
            } else if (sizeFmt == 1) {
                if (pos >= blockEnd) throw new IOException("Literals header truncated");
                regenSize = (litHdr >>> 4) | ((src[pos++] & 0xFF) << 4);
            } else {
                if (pos + 1 >= blockEnd) throw new IOException("Literals header truncated");
                regenSize = (litHdr >>> 4) | ((src[pos++] & 0xFF) << 4) | ((src[pos++] & 0xFF) << 12);
            }
        } else { // Compressed (2) or Treeless (3)
            numStreams = (sizeFmt == 0) ? 1 : 4;
            if (sizeFmt == 0 || sizeFmt == 1) {
                if (pos + 1 >= blockEnd) throw new IOException("Literals header truncated");
                final int b1 = src[pos++] & 0xFF;
                final int b2 = src[pos++] & 0xFF;
                regenSize = (litHdr >>> 4) | ((b1 & 0x3F) << 4);
                compSize = (b1 >>> 6) | (b2 << 2);
            } else if (sizeFmt == 2) {
                if (pos + 2 >= blockEnd) throw new IOException("Literals header truncated");
                final int b1 = src[pos++] & 0xFF;
                final int b2 = src[pos++] & 0xFF;
                final int b3 = src[pos++] & 0xFF;
                regenSize = (litHdr >>> 4) | (b1 << 4) | ((b2 & 0x03) << 12);
                compSize = (b2 >>> 2) | (b3 << 6);
            } else {
                if (pos + 3 >= blockEnd) throw new IOException("Literals header truncated");
                final int b1 = src[pos++] & 0xFF;
                final int b2 = src[pos++] & 0xFF;
                final int b3 = src[pos++] & 0xFF;
                final int b4 = src[pos++] & 0xFF;
                regenSize = (litHdr >>> 4) | (b1 << 4) | ((b2 & 0x3F) << 12);
                compSize = (b2 >>> 6) | (b3 << 2) | (b4 << 10);
            }
        }

        if (regenSize > maxOutputSize) {
            throw new IOException("Regenerated size too large: " + regenSize);
        }

        final byte[] literals = new byte[regenSize];

        if (litType == 0) { // Raw
            if (pos + regenSize > blockEnd) throw new IOException("Raw literals truncated");
            System.arraycopy(src, pos, literals, 0, regenSize);
            pos += regenSize;
        } else if (litType == 1) { // RLE
            if (pos >= blockEnd) throw new IOException("RLE literal truncated");
            final byte b = src[pos++];
            Arrays.fill(literals, b);
        } else { // Compressed or Treeless
            final int litEnd = pos + compSize;
            if (litEnd > blockEnd) throw new IOException("Compressed literals exceed block");

            if (litType == 2) { // Read Huffman tree description
                final int huffBytesRead = ctx.huffTable.read(src, pos, compSize);
                pos += huffBytesRead;
            }

            if (numStreams == 1) {
                ctx.huffTable.decode1Stream(src, pos, litEnd - pos, literals);
            } else {
                ctx.huffTable.decode4Streams(src, pos, litEnd - pos, literals);
            }
            pos = litEnd;
        }

        // 2. Sequences Section
        if (pos >= blockEnd) {
            out.write(literals, 0, literals.length);
            return;
        }

        final int seqHeader = src[pos++] & 0xFF;
        int numSequences;
        if (seqHeader == 0) {
            numSequences = 0;
        } else if (seqHeader < 128) {
            numSequences = seqHeader;
        } else if (seqHeader < 255) {
            if (pos >= blockEnd) throw new IOException("Sequences header truncated");
            numSequences = ((seqHeader - 128) << 8) | (src[pos++] & 0xFF);
        } else {
            if (pos + 1 >= blockEnd) throw new IOException("Sequences header truncated");
            numSequences = (src[pos++] & 0xFF) | ((src[pos++] & 0xFF) << 8) + 0x7F00;
        }

        if (numSequences == 0) {
            out.write(literals, 0, literals.length);
            return;
        }

        if (pos >= blockEnd) throw new IOException("Sequences mode byte missing");
        final int modes = src[pos++] & 0xFF;
        final int llMode = modes >>> 6;
        final int ofMode = (modes >>> 4) & 3;
        final int mlMode = (modes >>> 2) & 3;

        // FSE テーブル初期化 / 読み出し
        pos = initFseTable(src, pos, blockEnd, ctx.llTable, llMode, LL_DEFAULT_DISTRIBUTION, 6, 35);
        pos = initFseTable(src, pos, blockEnd, ctx.ofTable, ofMode, OF_DEFAULT_DISTRIBUTION, 5, 28);
        pos = initFseTable(src, pos, blockEnd, ctx.mlTable, mlMode, ML_DEFAULT_DISTRIBUTION, 6, 52);

        // 逆方向ビットストリームからシーケンスを復号
        final BitReaderRev bitReader = new BitReaderRev(src, pos, blockEnd - pos);

        int stateLL = bitReader.readBits(ctx.llTable.accuracyLog);
        int stateOF = bitReader.readBits(ctx.ofTable.accuracyLog);
        int stateML = bitReader.readBits(ctx.mlTable.accuracyLog);

        int litPos = 0;

        for (int i = 0; i < numSequences; i++) {
            final int ofCode = ctx.ofTable.symbols[stateOF];
            final int mlCode = ctx.mlTable.symbols[stateML];
            final int llCode = ctx.llTable.symbols[stateLL];

            final int mlBits = ML_EXTRA_BITS[mlCode];
            final int llBits = LL_EXTRA_BITS[llCode];

            final int offset;
            if (ofCode > 1) {
                final int ofExtra = bitReader.readBits(ofCode);
                offset = OF_BASE[ofCode] + ofExtra;
                ctx.repeatOffsets[2] = ctx.repeatOffsets[1];
                ctx.repeatOffsets[1] = ctx.repeatOffsets[0];
                ctx.repeatOffsets[0] = offset;
            } else {
                final int ll0 = (LL_BASE[llCode] == 0) ? 1 : 0;
                if (ofCode == 0) {
                    offset = ctx.repeatOffsets[ll0];
                    ctx.repeatOffsets[1] = ctx.repeatOffsets[1 - ll0];
                    ctx.repeatOffsets[0] = offset;
                } else { // ofCode == 1
                    final int extra = bitReader.readBits(1);
                    final int offsetVal = 1 + ll0 + extra;
                    final int temp = (offsetVal == 3) ? ctx.repeatOffsets[0] - 1 : ctx.repeatOffsets[offsetVal];
                    if (offsetVal != 1) {
                        ctx.repeatOffsets[2] = ctx.repeatOffsets[1];
                    }
                    ctx.repeatOffsets[1] = ctx.repeatOffsets[0];
                    ctx.repeatOffsets[0] = offset = temp;
                }
            }

            final int matchLength = ML_BASE[mlCode] + ((mlBits > 0) ? bitReader.readBits(mlBits) : 0);
            final int litLength = LL_BASE[llCode] + ((llBits > 0) ? bitReader.readBits(llBits) : 0);

            // 1. リテラル出力
            if (litLength > 0) {
                if (litPos + litLength > literals.length) {
                    throw new IOException("Literal length exceeds available literals");
                }
                out.write(literals, litPos, litLength);
                litPos += litLength;
            }

            // 2. 過去のデータから matchLength バイトをコピー
            out.copyMatch(offset, matchLength);

            // 3. 次の状態へ遷移 (RFC 8878: LL -> ML -> OF)
            if (i < numSequences - 1) {
                final int llNumBits = ctx.llTable.numBits[stateLL];
                stateLL = ctx.llTable.newStateBase[stateLL] + ((llNumBits > 0) ? bitReader.readBits(llNumBits) : 0);

                final int mlNumBits = ctx.mlTable.numBits[stateML];
                stateML = ctx.mlTable.newStateBase[stateML] + ((mlNumBits > 0) ? bitReader.readBits(mlNumBits) : 0);

                final int ofNumBits = ctx.ofTable.numBits[stateOF];
                stateOF = ctx.ofTable.newStateBase[stateOF] + ((ofNumBits > 0) ? bitReader.readBits(ofNumBits) : 0);
            }
        }

        // 残りのリテラルを出力
        if (litPos < literals.length) {
            out.write(literals, litPos, literals.length - litPos);
        }
    }

    private static int initFseTable(byte[] src, int pos, int end, FseTable table, int mode,
                                    short[] defaultDist, int defaultAccLog, int maxSymbol) throws IOException {
        switch (mode) {
            case 0: // Predefined
                table.buildFromDistribution(defaultDist, defaultAccLog, maxSymbol);
                return pos;
            case 1: // RLE
                if (pos >= end) throw new IOException("RLE FSE symbol truncated");
                final int sym = src[pos++] & 0xFF;
                table.buildRle(sym);
                return pos;
            case 2: // FSE compressed
                return table.readFse(src, pos, end, maxSymbol);
            case 3: // Repeat
                if (!table.isValid()) throw new IOException("Repeat mode with uninitialized FSE table");
                return pos;
            default:
                throw new IOException("Invalid FSE mode: " + mode);
        }
    }

    // FSE テーブル
    private static final class FseTable {
        int accuracyLog;
        int[] symbols;
        byte[] numBits;
        short[] newStateBase;
        private boolean valid = false;

        boolean isValid() {
            return valid;
        }

        void buildRle(int symbol) {
            accuracyLog = 0;
            symbols = new int[]{symbol};
            numBits = new byte[]{0};
            newStateBase = new short[]{0};
            valid = true;
        }

        void buildFromDistribution(short[] dist, int accLog, int maxSymbol) {
            this.accuracyLog = accLog;
            final int tableSize = 1 << accLog;
            symbols = new int[tableSize];
            numBits = new byte[tableSize];
            newStateBase = new short[tableSize];

            final int[] nextState = new int[maxSymbol + 1];
            for (int s = 0; s <= maxSymbol; s++) {
                final int prob = dist[s];
                if (prob < 0) {
                    nextState[s] = 1;
                } else {
                    nextState[s] = prob;
                }
            }

            int highThreshold = tableSize - 1;
            for (int s = 0; s <= maxSymbol; s++) {
                if (dist[s] == -1) {
                    symbols[highThreshold] = s;
                    highThreshold--;
                }
            }

            int position = 0;
            final int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            final int mask = tableSize - 1;

            for (int s = 0; s <= maxSymbol; s++) {
                final int prob = dist[s];
                if (prob <= 0) continue;
                for (int i = 0; i < prob; i++) {
                    symbols[position] = s;
                    do {
                        position = (position + step) & mask;
                    } while (position > highThreshold);
                }
            }

            for (int i = 0; i < tableSize; i++) {
                final int s = symbols[i];
                final int state = nextState[s]++;
                final int bits = accLog - highBit(state);
                numBits[i] = (byte) bits;
                newStateBase[i] = (short) ((state << bits) - tableSize);
            }

            valid = true;
        }

        int readFse(byte[] src, int pos, int end, int maxSymbol) throws IOException {
            final short[] dist = new short[maxSymbol + 1];

            long bitBuffer = 0;
            int bitsInBuffer = 0;
            int curPos = pos;

            // fill initial bits
            while (bitsInBuffer <= 56 && curPos < end) {
                bitBuffer |= ((long) (src[curPos++] & 0xFF)) << bitsInBuffer;
                bitsInBuffer += 8;
            }

            final int accLog = (int) ((bitBuffer & 0xF) + 5);
            bitBuffer >>>= 4;
            bitsInBuffer -= 4;
            int totalBitsRead = 4;

            int remaining = (1 << accLog) + 1;
            int threshold = 1 << accLog;
            int nbBits = accLog + 1;

            int charnum = 0;
            boolean previous0 = false;

            while (remaining > 1 && charnum <= maxSymbol) {
                if (previous0) {
                    while (bitsInBuffer <= 56 && curPos < end) {
                        bitBuffer |= ((long) (src[curPos++] & 0xFF)) << bitsInBuffer;
                        bitsInBuffer += 8;
                    }
                    int repeats = 0;
                    while ((bitBuffer & 3) == 3) {
                        repeats++;
                        bitBuffer >>>= 2;
                        bitsInBuffer -= 2;
                        totalBitsRead += 2;
                        while (bitsInBuffer <= 56 && curPos < end) {
                            bitBuffer |= ((long) (src[curPos++] & 0xFF)) << bitsInBuffer;
                            bitsInBuffer += 8;
                        }
                    }
                    final int extra = (int) (bitBuffer & 3);
                    bitBuffer >>>= 2;
                    bitsInBuffer -= 2;
                    totalBitsRead += 2;
                    charnum += 3 * repeats + extra;
                    if (charnum > maxSymbol) break;
                }

                while (bitsInBuffer <= 56 && curPos < end) {
                    bitBuffer |= ((long) (src[curPos++] & 0xFF)) << bitsInBuffer;
                    bitsInBuffer += 8;
                }

                final int maxVal = (2 * threshold - 1) - remaining;
                final int valLow = (int) (bitBuffer & (threshold - 1));
                int count;
                if (valLow < maxVal) {
                    count = valLow;
                    bitBuffer >>>= (nbBits - 1);
                    bitsInBuffer -= (nbBits - 1);
                    totalBitsRead += nbBits - 1;
                } else {
                    int valFull = (int) (bitBuffer & (2 * threshold - 1));
                    if (valFull >= threshold) {
                        valFull -= maxVal;
                    }
                    count = valFull;
                    bitBuffer >>>= nbBits;
                    bitsInBuffer -= nbBits;
                    totalBitsRead += nbBits;
                }

                count -= 1;
                if (count >= 0) {
                    remaining -= count;
                } else {
                    remaining += count; // count == -1 -> remaining -= 1
                }
                dist[charnum++] = (short) count;
                previous0 = (count == 0);

                if (remaining < threshold) {
                    if (remaining <= 1) break;
                    nbBits = highBit(remaining) + 1;
                    threshold = 1 << (nbBits - 1);
                }
            }

            buildFromDistribution(dist, accLog, maxSymbol);
            final int bytesRead = (totalBitsRead + 7) >>> 3;
            return pos + bytesRead;
        }
    }

    // ハフマンテーブル
    private static final class HuffmanTable {
        private int[] symbols;
        private byte[] numBits;
        private int tableLog = 0;

        int read(byte[] src, int pos, int maxLen) throws IOException {
            if (maxLen < 1) throw new IOException("Huffman description empty");
            final int header = src[pos] & 0xFF;
            final byte[] weights = new byte[256];
            int numWeights = 0;
            final int bytesRead;

            if (header < 128) { // FSE 圧縮
                final int fseLen = header;
                bytesRead = 1 + fseLen;
                if (bytesRead > maxLen) throw new IOException("FSE Huffman tree exceeds literals block");
                final FseTable fse = new FseTable();
                final int fseHeaderEnd = fse.readFse(src, pos + 1, pos + 1 + fseLen, 255);

                final int bitstreamBytes = (pos + 1 + fseLen) - fseHeaderEnd;
                final BitReaderRev bitReader = new BitReaderRev(src, fseHeaderEnd, bitstreamBytes);

                int state1 = bitReader.readBits(fse.accuracyLog);
                int state2 = bitReader.readBits(fse.accuracyLog);

                while (numWeights < 255) {
                    final int w1 = fse.symbols[state1];
                    weights[numWeights++] = (byte) w1;
                    if (numWeights >= 255) break;

                    final int nBits1 = fse.numBits[state1];
                    state1 = fse.newStateBase[state1] + ((nBits1 > 0) ? bitReader.readBits(nBits1) : 0);

                    final int w2 = fse.symbols[state2];
                    weights[numWeights++] = (byte) w2;
                    if (numWeights >= 255) break;

                    final int nBits2 = fse.numBits[state2];
                    state2 = fse.newStateBase[state2] + ((nBits2 > 0) ? bitReader.readBits(nBits2) : 0);
                }
            } else { // 直接 4 ビット重み
                numWeights = header - 127;
                bytesRead = 1 + (numWeights + 1) / 2;
                if (bytesRead > maxLen) throw new IOException("Direct Huffman tree exceeds literals block");
                for (int i = 0; i < numWeights; i++) {
                    final int b = src[pos + 1 + i / 2] & 0xFF;
                    weights[i] = (byte) ((i % 2 == 0) ? (b >>> 4) : (b & 0xF));
                }
            }

            // 最後の重みを導出
            int weightTotal = 0;
            for (int i = 0; i < numWeights; i++) {
                final int w = weights[i] & 0xFF;
                if (w > 0) {
                    weightTotal += 1 << (w - 1);
                }
            }
            final int tLog = highBit(weightTotal) + 1;
            final int target = 1 << tLog;
            final int rest = target - weightTotal;
            if (rest > 0) {
                final int lastWeight = highBit(rest) + 1;
                weights[numWeights++] = (byte) lastWeight;
            }

            buildFromWeights(weights, numWeights);
            return bytesRead;
        }

        private void buildFromWeights(byte[] weights, int numWeights) {
            int weightSum = 0;
            for (int i = 0; i < numWeights; i++) {
                final int w = weights[i] & 0xFF;
                if (w > 0) {
                    weightSum += 1 << (w - 1);
                }
            }
            this.tableLog = highBit(weightSum) + 1;
            final int tableSize = 1 << tableLog;
            symbols = new int[tableSize];
            numBits = new byte[tableSize];

            final int[] rankVal = new int[tableLog + 1];
            for (int i = 0; i < numWeights; i++) {
                final int w = weights[i] & 0xFF;
                rankVal[w]++;
            }

            final int[] rankStart = new int[tableLog + 1];
            int curr = 0;
            for (int n = 0; n <= tableLog; n++) {
                rankStart[n] = curr;
                curr += rankVal[n];
            }

            final int[] sortedSymbols = new int[numWeights];
            for (int s = 0; s < numWeights; s++) {
                final int w = weights[s] & 0xFF;
                sortedSymbols[rankStart[w]++] = s;
            }

            int symbolIdx = rankVal[0];
            int rStart = 0;
            for (int w = 1; w <= tableLog; w++) {
                final int symCount = rankVal[w];
                final int length = 1 << (w - 1);
                final byte nb = (byte) (tableLog + 1 - w);
                for (int s = 0; s < symCount; s++) {
                    final int sym = sortedSymbols[symbolIdx + s];
                    for (int u = 0; u < length; u++) {
                        symbols[rStart + u] = sym;
                        numBits[rStart + u] = nb;
                    }
                    rStart += length;
                }
                symbolIdx += symCount;
            }
        }

        void decode1Stream(byte[] src, int pos, int len, byte[] out) throws IOException {
            final BitReaderRev bitReader = new BitReaderRev(src, pos, len);
            for (int i = 0; i < out.length; i++) {
                final int peek = bitReader.peekBits(tableLog);
                out[i] = (byte) symbols[peek];
                bitReader.consumeBits(numBits[peek]);
            }
        }

        void decode4Streams(byte[] src, int pos, int len, byte[] out) throws IOException {
            if (len < 6) throw new IOException("4 streams literals truncated");
            final int s1Len = readUShortLE(src, pos);
            final int s2Len = readUShortLE(src, pos + 2);
            final int s3Len = readUShortLE(src, pos + 4);
            final int s4Len = len - 6 - s1Len - s2Len - s3Len;
            if (s4Len < 0) throw new IOException("Invalid stream lengths in 4-stream literals");

            final int p1 = pos + 6;
            final int p2 = p1 + s1Len;
            final int p3 = p2 + s2Len;
            final int p4 = p3 + s3Len;

            final int segSize = (out.length + 3) / 4;
            decodeStreamSegment(src, p1, s1Len, out, 0, Math.min(segSize, out.length));
            decodeStreamSegment(src, p2, s2Len, out, segSize, Math.min(segSize * 2, out.length));
            decodeStreamSegment(src, p3, s3Len, out, segSize * 2, Math.min(segSize * 3, out.length));
            decodeStreamSegment(src, p4, s4Len, out, segSize * 3, out.length);
        }

        private void decodeStreamSegment(byte[] src, int pos, int len, byte[] out, int start, int end) throws IOException {
            final BitReaderRev bitReader = new BitReaderRev(src, pos, len);
            for (int i = start; i < end; i++) {
                final int peek = bitReader.peekBits(tableLog);
                out[i] = (byte) symbols[peek];
                bitReader.consumeBits(numBits[peek]);
            }
        }
    }

    // 逆方向ビットリーダー (BIT_DStream 準拠)
    private static final class BitReaderRev {
        private final byte[] data;
        private final int start;
        private int ptr;
        private long bitContainer;
        private int bitsConsumed;

        BitReaderRev(byte[] data, int start, int size) throws IOException {
            if (size < 1) throw new IOException("BitReaderRev empty stream");
            this.data = data;
            this.start = start;
            if (size >= 8) {
                this.ptr = start + size - 8;
                this.bitContainer = readLongLE(data, ptr);
                final int lastByte = data[start + size - 1] & 0xFF;
                if (lastByte == 0) throw new IOException("Bitstream termination bit missing");
                final int highBit = highBit(lastByte);
                this.bitsConsumed = 8 - highBit;
            } else {
                this.ptr = start;
                long bc = 0;
                for (int i = 0; i < size; i++) {
                    bc |= ((long) (data[start + i] & 0xFF)) << (i * 8);
                }
                this.bitContainer = bc;
                final int lastByte = data[start + size - 1] & 0xFF;
                if (lastByte == 0) throw new IOException("Bitstream termination bit missing");
                final int highBit = highBit(lastByte);
                this.bitsConsumed = (8 - highBit) + (8 - size) * 8;
            }
        }

        int peekBits(int count) {
            if (count == 0) return 0;
            final int shift = 64 - bitsConsumed - count;
            if (shift < 0) {
                return (int) ((bitContainer << (-shift)) & ((1L << count) - 1));
            } else {
                return (int) ((bitContainer >>> shift) & ((1L << count) - 1));
            }
        }

        void consumeBits(int count) {
            bitsConsumed += count;
            if (bitsConsumed >= 32 && ptr > start) {
                int bytesToDrop = bitsConsumed >>> 3;
                if (ptr - bytesToDrop < start) {
                    bytesToDrop = ptr - start;
                }
                ptr -= bytesToDrop;
                bitsConsumed -= bytesToDrop * 8;
                bitContainer = readLongLE(data, ptr);
            }
        }

        int readBits(int count) {
            final int val = peekBits(count);
            consumeBits(count);
            return val;
        }
    }

    // 高速バイトバッファ
    private static final class FastByteArrayBuffer {
        private byte[] buf;
        private int count;

        FastByteArrayBuffer(int capacity) {
            buf = new byte[Math.max(capacity, 1024)];
            count = 0;
        }

        int size() {
            return count;
        }

        void write(byte[] b, int off, int len) {
            ensureCapacity(count + len);
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        void writeByte(byte b) {
            ensureCapacity(count + 1);
            buf[count++] = b;
        }

        void copyMatch(int offset, int matchLength) throws IOException {
            if (offset <= 0 || offset > count) {
                throw new IOException("Invalid match offset: " + offset + ", buffer size: " + count);
            }
            ensureCapacity(count + matchLength);
            int srcPos = count - offset;
            for (int i = 0; i < matchLength; i++) {
                buf[count++] = buf[srcPos++];
            }
        }

        byte[] toByteArray() {
            final byte[] result = new byte[count];
            System.arraycopy(buf, 0, result, 0, count);
            return result;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buf.length) {
                int newCap = buf.length * 2;
                if (newCap < minCapacity) newCap = minCapacity;
                final byte[] newBuf = new byte[newCap];
                System.arraycopy(buf, 0, newBuf, 0, count);
                buf = newBuf;
            }
        }
    }

    private static int highBit(int val) {
        return 31 - Integer.numberOfLeadingZeros(val);
    }

    private static int readUShortLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        return (readIntLE(data, offset) & 0xFFFFFFFFL) | (((long) readIntLE(data, offset + 4)) << 32);
    }

    // RFC 8878 Section 3.1.1.3.2.2 定数テーブル
    private static final short[] LL_DEFAULT_DISTRIBUTION = {
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1
    };

    private static final short[] ML_DEFAULT_DISTRIBUTION = {
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1
    };

    private static final short[] OF_DEFAULT_DISTRIBUTION = {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };

    private static final int[] LL_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
            8192, 16384, 32768, 65536
    };

    private static final int[] LL_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16
    };

    private static final int[] ML_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
            4099, 8195, 16387, 32771, 65539
    };

    private static final int[] ML_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16
    };

    private static final int[] OF_BASE = {
            0, 1, 1, 5, 0xD, 0x1D, 0x3D, 0x7D,
            0xFD, 0x1FD, 0x3FD, 0x7FD, 0xFFD, 0x1FFD, 0x3FFD, 0x7FFD,
            0xFFFD, 0x1FFFD, 0x3FFFD, 0x7FFFD, 0xFFFFD, 0x1FFFFD, 0x3FFFFD, 0x7FFFFD,
            0xFFFFFD, 0x1FFFFFD, 0x3FFFFFD, 0x7FFFFFD, 0xFFFFFFD, 0x1FFFFFFD, 0x3FFFFFFD, 0x7FFFFFFD
    };
}
