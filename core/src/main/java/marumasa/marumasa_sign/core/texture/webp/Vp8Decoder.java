package marumasa.marumasa_sign.core.texture.webp;

import java.io.IOException;
import java.util.Arrays;

/**
 * WebP の非可逆圧縮 (VP8 のキーフレーム、RFC 6386) を展開する。
 * 予測と係数から Y'CbCr の 3 つの面を組み立て、ループフィルタをかけ、RGB にして返す。
 * WebP の画像はキーフレーム 1 枚なので、フレーム間の予測 (インターフレーム) は扱わない。
 */
public final class Vp8Decoder {

    // フレームの先頭 (種類と第 1 区画の長さ 3 バイト、開始コード 3 バイト、幅と高さ 4 バイト) の長さ
    private static final int HEADER_SIZE = 10;
    // 展開する画像の画素数の上限 (8192 x 8192)。呼び出し側でも大きさを確かめるが、念のためここでも止める
    private static final long MAX_PIXELS = 8192L * 8192;

    // 係数の種類 (確率の表の 1 つ目の添字)
    private static final int TYPE_Y_AFTER_Y2 = 0;
    private static final int TYPE_Y2 = 1;
    private static final int TYPE_CHROMA = 2;
    private static final int TYPE_Y_WITH_DC = 3;

    // 16x16 の輝度の方法の番号 (Vp8Tables の KF_YMODE_TREE と同じ)
    private static final int MODE_DC = 0;
    private static final int MODE_V = 1;
    private static final int MODE_H = 2;
    private static final int MODE_TM = 3;
    private static final int MODE_B = 4;

    // 1 つのマクロブロックの係数の並び: 輝度 16 個、U 4 個、V 4 個、Y2 1 個の 4x4 のブロック
    private static final int BLOCK_U = 16;
    private static final int BLOCK_V = 20;
    private static final int BLOCK_Y2 = 24;

    // 予測に使う作業場所。左に 1 列、上に 1 行、右に (右上の画素の) 4 列の余白を持つ
    private static final int LUMA_STRIDE = 21;
    private static final int CHROMA_STRIDE = 9;

    private final byte[] data;
    private final int end;
    private final int width;
    private final int height;
    private final int mbWidth;
    private final int mbHeight;

    private BoolReader header;
    private BoolReader[] partitions;

    // 区分 (segment) ごとの調整 (RFC 6386 9.3)。値は絶対値か、フレーム全体の値からの差
    private boolean segmentationEnabled;
    private boolean segmentMapUpdated;
    private boolean segmentAbsolute;
    private final int[] segmentQuant = new int[4];
    private final int[] segmentFilter = new int[4];
    private final int[] segmentProbs = {255, 255, 255};
    // 区分ごとの量子化の値: [区分][0 輝度 DC, 1 輝度 AC, 2 Y2 DC, 3 Y2 AC, 4 色差 DC, 5 色差 AC]
    private final int[][] quant = new int[4][6];
    private final int[] coefficientProbs = new int[4 * 8 * 3 * 11];
    private boolean useSkipProb;
    private int skipProb;

    // ループフィルタ。filterType は 0 = なし、1 = 簡易、2 = 通常
    private int filterType;
    // [区分 * 2 + 4x4 予測か] ごとの、端の限度 (0 ならかけない)、内側の限度、変化の大きさのしきい値
    private final int[] filterLimit = new int[8];
    private final int[] filterInterior = new int[8];
    private final int[] filterHev = new int[8];
    // マクロブロックごとの、フィルタの種類の番号 (区分 * 2 + 4x4 予測か) と、内側の端もかけるか (+8)
    private byte[] filterInfo;

    // 復号した面
    private byte[] planeY;
    private byte[] planeU;
    private byte[] planeV;
    private int strideY;
    private int strideUV;

    // 上と左のマクロブロックの文脈: 4x4 の予測の方法と、係数があったかどうか
    private int[] topModes;
    private final int[] leftModes = new int[4];
    // 係数の有無は、輝度 4 つ、U 2 つ、V 2 つ、Y2 1 つの 9 つを並べる
    private byte[] topNonZero;
    private final byte[] leftNonZero = new byte[9];

    // マクロブロック 1 つ分の作業場所
    private final int[] subModes = new int[16];
    private final short[] coefficients = new short[25 * 16];
    private final boolean[] blockNonZero = new boolean[24];
    private final int[] workY = new int[LUMA_STRIDE * 17];
    private final int[] workU = new int[CHROMA_STRIDE * 9];
    private final int[] workV = new int[CHROMA_STRIDE * 9];
    // 逆変換の途中の値と、4x4 の予測の値
    private final int[] transformTemp = new int[16];
    private final int[] subPrediction = new int[16];

    private Vp8Decoder(byte[] data, int offset, int length) throws IOException {
        this.data = data;
        this.end = offset + length;
        final int[] size = readSize(data, offset, length);
        this.width = size[0];
        this.height = size[1];
        if ((long) width * height > MAX_PIXELS) throw new IOException("VP8 image exceeds safety limits: " + width + "x" + height);
        this.mbWidth = (width + 15) >> 4;
        this.mbHeight = (height + 15) >> 4;
    }

    /**
     * VP8 のデータの先頭から、画像の幅と高さを読む。
     */
    public static int[] readSize(byte[] data, int offset, int length) throws IOException {
        if (length < HEADER_SIZE) throw new IOException("Truncated VP8 header");
        final int tag = u8(data, offset) | u8(data, offset + 1) << 8 | u8(data, offset + 2) << 16;
        if ((tag & 1) != 0) throw new IOException("VP8 frame is not a key frame");
        if (((tag >> 1) & 7) > 3) throw new IOException("Unknown VP8 profile");
        if (((tag >> 4) & 1) == 0) throw new IOException("VP8 frame is not displayable");
        if (u8(data, offset + 3) != 0x9d || u8(data, offset + 4) != 0x01 || u8(data, offset + 5) != 0x2a) {
            throw new IOException("Invalid VP8 start code");
        }
        // 上位 2 ビットは拡大の指定。WebP では使わない
        final int w = (u8(data, offset + 6) | u8(data, offset + 7) << 8) & 0x3fff;
        final int h = (u8(data, offset + 8) | u8(data, offset + 9) << 8) & 0x3fff;
        if (w == 0 || h == 0) throw new IOException("Invalid VP8 image size");
        return new int[]{w, h};
    }

    /**
     * VP8 のデータを展開し、ARGB (透明度は 255) のピクセルを左上から行ごとに並べて返す。
     */
    public static int[] decode(byte[] data, int offset, int length) throws IOException {
        final Vp8Decoder decoder = new Vp8Decoder(data, offset, length);
        decoder.readHeaders(offset);
        decoder.decodeFrame();
        if (decoder.filterType != 0) decoder.applyLoopFilter();
        final int[] argb = new int[decoder.width * decoder.height];
        YuvConverter.toArgb(decoder.planeY, decoder.strideY, decoder.planeU, decoder.planeV, decoder.strideUV,
                decoder.width, decoder.height, argb);
        return argb;
    }

    private static int u8(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    // ---------- フレームの頭 (RFC 6386 9 節) ----------

    private void readHeaders(int offset) throws IOException {
        final int tag = u8(data, offset) | u8(data, offset + 1) << 8 | u8(data, offset + 2) << 16;
        final int firstPartitionSize = tag >>> 5;
        final int firstStart = offset + HEADER_SIZE;
        if (firstPartitionSize > end - firstStart) throw new IOException("Truncated VP8 first partition");
        header = new BoolReader(data, firstStart, firstStart + firstPartitionSize);

        // 色空間と、値を丸める必要があるか。どちらも結果に影響しない
        header.readBit(128);
        header.readBit(128);
        readSegmentation();
        readFilterHeader();
        readPartitions(firstStart + firstPartitionSize);
        readQuantizers();
        // 確率を次のフレームに持ち越すか。1 枚だけなので関係ない
        header.readBit(128);
        readCoefficientProbs();
        useSkipProb = header.readBit(128) != 0;
        if (useSkipProb) skipProb = header.readLiteral(8);
        if (header.eof()) throw new IOException("Truncated VP8 frame header");
    }

    private void readSegmentation() {
        segmentationEnabled = header.readBit(128) != 0;
        if (!segmentationEnabled) return;
        segmentMapUpdated = header.readBit(128) != 0;
        final boolean updateData = header.readBit(128) != 0;
        if (updateData) {
            segmentAbsolute = header.readBit(128) != 0;
            for (int s = 0; s < 4; s++) segmentQuant[s] = header.readOptionalSigned(7);
            for (int s = 0; s < 4; s++) segmentFilter[s] = header.readOptionalSigned(6);
        } else {
            // 前のフレームの値を引き継ぐ指定だが、キーフレーム 1 枚なので初期値 (差分 0) のまま
            segmentAbsolute = true;
        }
        if (segmentMapUpdated) {
            for (int i = 0; i < 3; i++) segmentProbs[i] = header.readBit(128) != 0 ? header.readLiteral(8) : 255;
        }
    }

    // ループフィルタの種類と強さ (RFC 6386 9.4, 15.4)
    private void readFilterHeader() {
        final boolean simple = header.readBit(128) != 0;
        final int level = header.readLiteral(6);
        final int sharpness = header.readLiteral(3);
        final int[] refDelta = new int[4];
        final int[] modeDelta = new int[4];
        final boolean useDelta = header.readBit(128) != 0;
        if (useDelta && header.readBit(128) != 0) {
            for (int i = 0; i < 4; i++) if (header.readBit(128) != 0) refDelta[i] = header.readSigned(6);
            for (int i = 0; i < 4; i++) if (header.readBit(128) != 0) modeDelta[i] = header.readSigned(6);
        }
        // フレーム全体の強さが 0 なら、区分ごとの強さにかかわらずフィルタはかけない
        filterType = level == 0 ? 0 : simple ? 1 : 2;
        if (filterType == 0) return;
        for (int s = 0; s < 4; s++) {
            int base = level;
            if (segmentationEnabled) {
                base = segmentFilter[s];
                if (!segmentAbsolute) base += level;
            }
            for (int i4 = 0; i4 <= 1; i4++) {
                int strength = base;
                if (useDelta) {
                    // キーフレームの参照フレームは常に「イントラ」(0)、4x4 予測は方法の差分の 0 番
                    strength += refDelta[0];
                    if (i4 == 1) strength += modeDelta[0];
                }
                strength = Math.max(0, Math.min(63, strength));
                final int index = s * 2 + i4;
                if (strength == 0) {
                    filterLimit[index] = 0;
                    continue;
                }
                int interior = strength;
                if (sharpness > 0) {
                    interior >>= sharpness > 4 ? 2 : 1;
                    if (interior > 9 - sharpness) interior = 9 - sharpness;
                }
                if (interior < 1) interior = 1;
                filterInterior[index] = interior;
                filterLimit[index] = 2 * strength + interior;
                filterHev[index] = strength >= 40 ? 2 : strength >= 15 ? 1 : 0;
            }
        }
    }

    // 係数の区画 (RFC 6386 9.5)。最後の区画以外は 3 バイトずつの長さが並ぶ
    private void readPartitions(int start) throws IOException {
        final int count = 1 << header.readLiteral(2);
        final int sizesBytes = 3 * (count - 1);
        if (sizesBytes > end - start) throw new IOException("Truncated VP8 partition table");
        partitions = new BoolReader[count];
        int position = start + sizesBytes;
        for (int i = 0; i < count - 1; i++) {
            final int index = start + 3 * i;
            int size = u8(data, index) | u8(data, index + 1) << 8 | u8(data, index + 2) << 16;
            // 残りより長い区画は、残りの分だけにする (足りない分は読むときに eof になる)
            size = Math.min(size, end - position);
            partitions[i] = new BoolReader(data, position, position + size);
            position += size;
        }
        if (position >= end) throw new IOException("Truncated VP8 partitions");
        partitions[count - 1] = new BoolReader(data, position, end);
    }

    // 量子化の値 (RFC 6386 9.6, 14.1)
    private void readQuantizers() {
        final int baseIndex = header.readLiteral(7);
        final int yDcDelta = header.readOptionalSigned(4);
        final int y2DcDelta = header.readOptionalSigned(4);
        final int y2AcDelta = header.readOptionalSigned(4);
        final int uvDcDelta = header.readOptionalSigned(4);
        final int uvAcDelta = header.readOptionalSigned(4);
        for (int s = 0; s < 4; s++) {
            int q = baseIndex;
            if (segmentationEnabled) {
                q = segmentQuant[s];
                if (!segmentAbsolute) q += baseIndex;
            }
            final int[] m = quant[s];
            m[0] = Vp8Tables.DC_QUANT[clampIndex(q + yDcDelta, 127)];
            m[1] = Vp8Tables.AC_QUANT[clampIndex(q, 127)];
            m[2] = Vp8Tables.DC_QUANT[clampIndex(q + y2DcDelta, 127)] * 2;
            // Y2 の AC は 155 / 100 倍で、8 より小さくしない
            m[3] = Math.max(8, Vp8Tables.AC_QUANT[clampIndex(q + y2AcDelta, 127)] * 155 / 100);
            // 色差の DC は表の 117 番 (132) までに抑える
            m[4] = Vp8Tables.DC_QUANT[clampIndex(q + uvDcDelta, 117)];
            m[5] = Vp8Tables.AC_QUANT[clampIndex(q + uvAcDelta, 127)];
        }
    }

    private static int clampIndex(int value, int max) {
        return value < 0 ? 0 : value > max ? max : value;
    }

    // 係数の確率の更新 (RFC 6386 13.4)
    private void readCoefficientProbs() {
        for (int i = 0; i < coefficientProbs.length; i++) {
            coefficientProbs[i] = header.readBit(Vp8Tables.COEFF_UPDATE_PROBS[i]) != 0
                    ? header.readLiteral(8) : Vp8Tables.DEFAULT_COEFF_PROBS[i];
        }
    }

    // ---------- マクロブロックの復号 ----------

    private void decodeFrame() throws IOException {
        strideY = mbWidth * 16;
        strideUV = mbWidth * 8;
        planeY = new byte[strideY * mbHeight * 16];
        planeU = new byte[strideUV * mbHeight * 8];
        planeV = new byte[strideUV * mbHeight * 8];
        topModes = new int[mbWidth * 4];
        topNonZero = new byte[mbWidth * 9];
        filterInfo = new byte[mbWidth * mbHeight];

        for (int mbY = 0; mbY < mbHeight; mbY++) {
            final BoolReader tokens = partitions[mbY & (partitions.length - 1)];
            Arrays.fill(leftModes, 0);
            Arrays.fill(leftNonZero, (byte) 0);
            for (int mbX = 0; mbX < mbWidth; mbX++) {
                decodeMacroblock(mbX, mbY, tokens);
            }
            // データが足りなければ、そこで失敗にする (足りない部分を 0 として読んだ値は使わない)
            if (header.eof()) throw new IOException("Truncated VP8 first partition");
            if (tokens.eof()) throw new IOException("Truncated VP8 coefficient partition");
        }
    }

    private void decodeMacroblock(int mbX, int mbY, BoolReader tokens) {
        // 予測の方法 (RFC 6386 11 節)。区分の番号、係数を省略するか、輝度、色差の順
        final int segment = segmentMapUpdated ? header.readTree(Vp8Tables.SEGMENT_TREE, segmentProbs, 0) : 0;
        final boolean skip = useSkipProb && header.readBit(skipProb) != 0;
        final int yMode = header.readTree(Vp8Tables.KF_YMODE_TREE, Vp8Tables.KF_YMODE_PROBS, 0);
        final int top = mbX * 4;
        if (yMode == MODE_B) {
            for (int y = 0; y < 4; y++) {
                int left = leftModes[y];
                for (int x = 0; x < 4; x++) {
                    final int above = topModes[top + x];
                    final int mode = header.readTree(Vp8Tables.BMODE_TREE, Vp8Tables.KF_BMODE_PROBS, (above * 10 + left) * 9);
                    subModes[y * 4 + x] = mode;
                    topModes[top + x] = mode;
                    left = mode;
                }
                leftModes[y] = left;
            }
        } else {
            final int implied = Vp8Tables.YMODE_TO_BMODE[yMode];
            for (int i = 0; i < 4; i++) {
                topModes[top + i] = implied;
                leftModes[i] = implied;
            }
        }
        final int uvMode = header.readTree(Vp8Tables.UV_MODE_TREE, Vp8Tables.KF_UV_MODE_PROBS, 0);

        // 係数
        final boolean i4 = yMode == MODE_B;
        boolean hasCoefficients = false;
        Arrays.fill(coefficients, (short) 0);
        Arrays.fill(blockNonZero, false);
        if (skip) {
            final int t = mbX * 9;
            for (int i = 0; i < 8; i++) {
                topNonZero[t + i] = 0;
                leftNonZero[i] = 0;
            }
            if (!i4) {
                topNonZero[t + 8] = 0;
                leftNonZero[8] = 0;
            }
        } else {
            hasCoefficients = readResiduals(mbX, segment, i4, tokens);
        }
        filterInfo[mbY * mbWidth + mbX] = (byte) (segment * 2 + (i4 ? 1 : 0) + (i4 || hasCoefficients ? 8 : 0));

        reconstruct(mbX, mbY, yMode, uvMode);
    }

    // 係数を読む (RFC 6386 13 節)。どれかのブロックに係数があれば true
    private boolean readResiduals(int mbX, int segment, boolean i4, BoolReader tokens) {
        final int[] q = quant[segment];
        final int t = mbX * 9;
        final int firstY;
        final int yType;
        if (!i4) {
            // 16 個の輝度ブロックの DC をまとめた Y2 を先に読み、逆アダマール変換で各ブロックの DC にする
            final int context = topNonZero[t + 8] + leftNonZero[8];
            final int last = readBlock(tokens, TYPE_Y2, context, 0, q[2], q[3], BLOCK_Y2 * 16);
            final byte flag = (byte) (last > 0 ? 1 : 0);
            topNonZero[t + 8] = flag;
            leftNonZero[8] = flag;
            inverseWalshHadamard();
            firstY = 1;
            yType = TYPE_Y_AFTER_Y2;
        } else {
            firstY = 0;
            yType = TYPE_Y_WITH_DC;
        }
        boolean any = false;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                final int block = y * 4 + x;
                final int context = topNonZero[t + x] + leftNonZero[y];
                final int last = readBlock(tokens, yType, context, firstY, q[0], q[1], block * 16);
                final byte flag = (byte) (last > firstY ? 1 : 0);
                topNonZero[t + x] = flag;
                leftNonZero[y] = flag;
                // 係数を読んだか、Y2 から DC が入ったブロックは逆変換が要る
                blockNonZero[block] = last > 1 || coefficients[block * 16] != 0;
                any |= blockNonZero[block];
            }
        }
        for (int plane = 0; plane < 2; plane++) {
            final int base = plane == 0 ? BLOCK_U : BLOCK_V;
            final int nz = 4 + plane * 2;
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    final int block = base + y * 2 + x;
                    final int context = topNonZero[t + nz + x] + leftNonZero[nz + y];
                    final int last = readBlock(tokens, TYPE_CHROMA, context, 0, q[4], q[5], block * 16);
                    final byte flag = (byte) (last > 0 ? 1 : 0);
                    topNonZero[t + nz + x] = flag;
                    leftNonZero[nz + y] = flag;
                    blockNonZero[block] = last > 1 || coefficients[block * 16] != 0;
                    any |= blockNonZero[block];
                }
            }
        }
        return any;
    }

    /**
     * 4x4 のブロック 1 つの係数を読み、逆量子化して coefficients[offset..] に入れる。
     * 戻り値は、最後に読んだ係数の次の位置 (ブロックの終わりの印を読んだ位置、または 16)。
     */
    private int readBlock(BoolReader r, int type, int context, int first, int dcQuant, int acQuant, int offset) {
        final int[] probs = coefficientProbs;
        final int typeBase = type * (8 * 3 * 11);
        int n = first;
        boolean previousZero = false;
        while (n < 16) {
            final int p = typeBase + (Vp8Tables.BANDS[n] * 3 + context) * 11;
            // 直前が 0 の係数なら、ブロックの終わりの印は来ない
            if (!previousZero && r.readBit(probs[p]) == 0) return n;
            if (r.readBit(probs[p + 1]) == 0) {
                previousZero = true;
                context = 0;
                n++;
                continue;
            }
            final int value;
            if (r.readBit(probs[p + 2]) == 0) {
                value = 1;
                context = 1;
            } else {
                value = readLargeValue(r, probs, p);
                context = 2;
            }
            final int signed = r.readBit(128) != 0 ? -value : value;
            // 逆量子化した値は 16 ビットに収める (参照デコーダと同じ)
            coefficients[offset + Vp8Tables.ZIGZAG[n]] = (short) (signed * (n > 0 ? acQuant : dcQuant));
            previousZero = false;
            n++;
        }
        return 16;
    }

    // 2 以上の係数の値。木の 4 番目以降の節 (RFC 6386 13.2)
    private static int readLargeValue(BoolReader r, int[] probs, int p) {
        if (r.readBit(probs[p + 3]) == 0) {
            if (r.readBit(probs[p + 4]) == 0) return 2;
            return 3 + r.readBit(probs[p + 5]);
        }
        if (r.readBit(probs[p + 6]) == 0) {
            if (r.readBit(probs[p + 7]) == 0) return 5 + r.readBit(159);
            final int high = r.readBit(165);
            return 7 + 2 * high + r.readBit(145);
        }
        final int high = r.readBit(probs[p + 8]);
        final int category = 2 * high + r.readBit(probs[p + 9 + high]);
        int extra = 0;
        for (int prob : Vp8Tables.CATEGORY_PROBS[category]) extra = 2 * extra + r.readBit(prob);
        return Vp8Tables.CATEGORY_BASE[category] + extra;
    }

    // Y2 の逆アダマール変換 (RFC 6386 14.3)。結果を 16 個の輝度ブロックの DC に入れる
    private void inverseWalshHadamard() {
        final short[] c = coefficients;
        final int in = BLOCK_Y2 * 16;
        final int[] tmp = transformTemp;
        for (int i = 0; i < 4; i++) {
            final int a1 = c[in + i] + c[in + 12 + i];
            final int b1 = c[in + 4 + i] + c[in + 8 + i];
            final int c1 = c[in + 4 + i] - c[in + 8 + i];
            final int d1 = c[in + i] - c[in + 12 + i];
            tmp[i] = a1 + b1;
            tmp[4 + i] = c1 + d1;
            tmp[8 + i] = a1 - b1;
            tmp[12 + i] = d1 - c1;
        }
        for (int i = 0; i < 4; i++) {
            final int a1 = tmp[4 * i] + tmp[4 * i + 3];
            final int b1 = tmp[4 * i + 1] + tmp[4 * i + 2];
            final int c1 = tmp[4 * i + 1] - tmp[4 * i + 2];
            final int d1 = tmp[4 * i] - tmp[4 * i + 3];
            c[(4 * i) * 16] = (short) ((a1 + b1 + 3) >> 3);
            c[(4 * i + 1) * 16] = (short) ((c1 + d1 + 3) >> 3);
            c[(4 * i + 2) * 16] = (short) ((a1 - b1 + 3) >> 3);
            c[(4 * i + 3) * 16] = (short) ((d1 - c1 + 3) >> 3);
        }
    }

    // ---------- 予測と組み立て (RFC 6386 12 節、14 節) ----------

    private void reconstruct(int mbX, int mbY, int yMode, int uvMode) {
        loadEdges(workY, LUMA_STRIDE, 16, planeY, strideY, mbX, mbY, true);
        if (yMode == MODE_B) {
            // 右端の列のブロック (3, 7, 11, 15) の右上は、どれもマクロブロックの右上の 4 画素を使う
            for (int row = 4; row <= 12; row += 4) {
                System.arraycopy(workY, 17, workY, row * LUMA_STRIDE + 17, 4);
            }
            for (int block = 0; block < 16; block++) {
                final int position = ((block >> 2) * 4 + 1) * LUMA_STRIDE + (block & 3) * 4 + 1;
                predictSubblock(workY, position, subModes[block], subPrediction);
                if (blockNonZero[block]) inverseDct(block * 16, workY, position, LUMA_STRIDE);
            }
        } else {
            predictBlock(workY, LUMA_STRIDE + 1, LUMA_STRIDE, 16, yMode, mbX > 0, mbY > 0);
            for (int block = 0; block < 16; block++) {
                if (!blockNonZero[block]) continue;
                final int position = ((block >> 2) * 4 + 1) * LUMA_STRIDE + (block & 3) * 4 + 1;
                inverseDct(block * 16, workY, position, LUMA_STRIDE);
            }
        }
        storeBlock(workY, LUMA_STRIDE, 16, planeY, strideY, mbX, mbY);

        loadEdges(workU, CHROMA_STRIDE, 8, planeU, strideUV, mbX, mbY, false);
        loadEdges(workV, CHROMA_STRIDE, 8, planeV, strideUV, mbX, mbY, false);
        predictBlock(workU, CHROMA_STRIDE + 1, CHROMA_STRIDE, 8, uvMode, mbX > 0, mbY > 0);
        predictBlock(workV, CHROMA_STRIDE + 1, CHROMA_STRIDE, 8, uvMode, mbX > 0, mbY > 0);
        for (int i = 0; i < 4; i++) {
            final int position = ((i >> 1) * 4 + 1) * CHROMA_STRIDE + (i & 1) * 4 + 1;
            if (blockNonZero[BLOCK_U + i]) inverseDct((BLOCK_U + i) * 16, workU, position, CHROMA_STRIDE);
            if (blockNonZero[BLOCK_V + i]) inverseDct((BLOCK_V + i) * 16, workV, position, CHROMA_STRIDE);
        }
        storeBlock(workU, CHROMA_STRIDE, 8, planeU, strideUV, mbX, mbY);
        storeBlock(workV, CHROMA_STRIDE, 8, planeV, strideUV, mbX, mbY);
    }

    /**
     * 作業場所の上の行と左の列に、まわりの (ループフィルタをかける前の) 画素を入れる。
     * 画像の外は、上が 127、左が 129 (左上の角は、いちばん上の行なら 127、それ以外は 129)。
     * 輝度は右上の 4 画素も入れる。右端のマクロブロックでは上の行の最後の画素を繰り返す。
     */
    private static void loadEdges(int[] work, int stride, int size, byte[] plane, int planeStride, int mbX, int mbY, boolean luma) {
        final int x0 = mbX * size;
        final int y0 = mbY * size;
        final int extra = luma ? 4 : 0;
        if (mbY == 0) {
            for (int i = 0; i <= size + extra; i++) work[i] = 127;
        } else {
            final int row = (y0 - 1) * planeStride;
            work[0] = mbX == 0 ? 129 : plane[row + x0 - 1] & 0xFF;
            for (int i = 0; i < size; i++) work[1 + i] = plane[row + x0 + i] & 0xFF;
            if (luma) {
                final boolean rightmost = x0 + size >= planeStride;
                for (int i = 0; i < 4; i++) {
                    work[1 + size + i] = rightmost ? work[size] : plane[row + x0 + size + i] & 0xFF;
                }
            }
        }
        for (int i = 0; i < size; i++) {
            work[(i + 1) * stride] = mbX == 0 ? 129 : plane[(y0 + i) * planeStride + x0 - 1] & 0xFF;
        }
    }

    private static void storeBlock(int[] work, int stride, int size, byte[] plane, int planeStride, int mbX, int mbY) {
        for (int y = 0; y < size; y++) {
            final int from = (y + 1) * stride + 1;
            final int to = (mbY * size + y) * planeStride + mbX * size;
            for (int x = 0; x < size; x++) plane[to + x] = (byte) work[from + x];
        }
    }

    // 16x16 (輝度) または 8x8 (色差) の予測。DC は、画像の端では使える側だけの平均にする
    private static void predictBlock(int[] w, int p, int s, int size, int mode, boolean hasLeft, boolean hasTop) {
        switch (mode) {
            case MODE_DC: {
                final int shift = size == 16 ? 4 : 3;
                int value;
                if (hasTop && hasLeft) {
                    int sum = 0;
                    for (int i = 0; i < size; i++) sum += w[p - s + i] + w[p + i * s - 1];
                    value = (sum + size) >> (shift + 1);
                } else if (hasTop) {
                    int sum = 0;
                    for (int i = 0; i < size; i++) sum += w[p - s + i];
                    value = (sum + (size >> 1)) >> shift;
                } else if (hasLeft) {
                    int sum = 0;
                    for (int i = 0; i < size; i++) sum += w[p + i * s - 1];
                    value = (sum + (size >> 1)) >> shift;
                } else {
                    value = 128;
                }
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) w[p + y * s + x] = value;
                }
                break;
            }
            case MODE_V:
                for (int y = 0; y < size; y++) System.arraycopy(w, p - s, w, p + y * s, size);
                break;
            case MODE_H:
                for (int y = 0; y < size; y++) {
                    final int left = w[p + y * s - 1];
                    for (int x = 0; x < size; x++) w[p + y * s + x] = left;
                }
                break;
            default: {
                // TM: 左 + 上 - 左上
                final int corner = w[p - s - 1];
                for (int y = 0; y < size; y++) {
                    final int left = w[p + y * s - 1];
                    for (int x = 0; x < size; x++) w[p + y * s + x] = clamp255(left + w[p - s + x] - corner);
                }
                break;
            }
        }
    }

    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    private static int avg3(int a, int b, int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    // 4x4 の予測 (RFC 6386 12.3)。A は上の 8 画素、L は左の 4 画素、P は左上
    private static void predictSubblock(int[] w, int p, int mode, int[] b) {
        final int s = LUMA_STRIDE;
        final int top = p - s;
        final int P = w[top - 1];
        final int A0 = w[top], A1 = w[top + 1], A2 = w[top + 2], A3 = w[top + 3];
        final int A4 = w[top + 4], A5 = w[top + 5], A6 = w[top + 6], A7 = w[top + 7];
        final int L0 = w[p - 1], L1 = w[p + s - 1], L2 = w[p + 2 * s - 1], L3 = w[p + 3 * s - 1];
        // b は B[行][列] を、行ごとに 4 つずつ並べたもの
        switch (mode) {
            case 0: { // B_DC
                final int v = (A0 + A1 + A2 + A3 + L0 + L1 + L2 + L3 + 4) >> 3;
                Arrays.fill(b, v);
                break;
            }
            case 1: // B_TM
                for (int r = 0; r < 4; r++) {
                    final int left = w[p + r * s - 1];
                    for (int c = 0; c < 4; c++) b[r * 4 + c] = clamp255(left + w[top + c] - P);
                }
                break;
            case 2: // B_VE: なめらかにした上の行
                for (int r = 0; r < 4; r++) {
                    b[r * 4] = avg3(P, A0, A1);
                    b[r * 4 + 1] = avg3(A0, A1, A2);
                    b[r * 4 + 2] = avg3(A1, A2, A3);
                    b[r * 4 + 3] = avg3(A2, A3, A4);
                }
                break;
            case 3: // B_HE: なめらかにした左の列
                Arrays.fill(b, 0, 4, avg3(P, L0, L1));
                Arrays.fill(b, 4, 8, avg3(L0, L1, L2));
                Arrays.fill(b, 8, 12, avg3(L1, L2, L3));
                Arrays.fill(b, 12, 16, avg3(L2, L3, L3));
                break;
            case 4: // B_LD: 左下向きの斜め
                set(b, avg3(A0, A1, A2), 0, 0);
                set(b, avg3(A1, A2, A3), 0, 1, 1, 0);
                set(b, avg3(A2, A3, A4), 0, 2, 1, 1, 2, 0);
                set(b, avg3(A3, A4, A5), 0, 3, 1, 2, 2, 1, 3, 0);
                set(b, avg3(A4, A5, A6), 1, 3, 2, 2, 3, 1);
                set(b, avg3(A5, A6, A7), 2, 3, 3, 2);
                set(b, avg3(A6, A7, A7), 3, 3);
                break;
            case 5: // B_RD: 右下向きの斜め。辺の画素を左下から右上へ L3 L2 L1 L0 P A0 A1 A2 A3 と並べて使う
                set(b, avg3(L3, L2, L1), 3, 0);
                set(b, avg3(L2, L1, L0), 3, 1, 2, 0);
                set(b, avg3(L1, L0, P), 3, 2, 2, 1, 1, 0);
                set(b, avg3(L0, P, A0), 3, 3, 2, 2, 1, 1, 0, 0);
                set(b, avg3(P, A0, A1), 2, 3, 1, 2, 0, 1);
                set(b, avg3(A0, A1, A2), 1, 3, 0, 2);
                set(b, avg3(A1, A2, A3), 0, 3);
                break;
            case 6: // B_VR
                set(b, avg3(L2, L1, L0), 3, 0);
                set(b, avg3(L1, L0, P), 2, 0);
                set(b, avg3(L0, P, A0), 3, 1, 1, 0);
                set(b, avg2(P, A0), 2, 1, 0, 0);
                set(b, avg3(P, A0, A1), 3, 2, 1, 1);
                set(b, avg2(A0, A1), 2, 2, 0, 1);
                set(b, avg3(A0, A1, A2), 3, 3, 1, 2);
                set(b, avg2(A1, A2), 2, 3, 0, 2);
                set(b, avg3(A1, A2, A3), 1, 3);
                set(b, avg2(A2, A3), 0, 3);
                break;
            case 7: // B_VL
                set(b, avg2(A0, A1), 0, 0);
                set(b, avg3(A0, A1, A2), 1, 0);
                set(b, avg2(A1, A2), 2, 0, 0, 1);
                set(b, avg3(A1, A2, A3), 1, 1, 3, 0);
                set(b, avg2(A2, A3), 2, 1, 0, 2);
                set(b, avg3(A2, A3, A4), 3, 1, 1, 2);
                set(b, avg2(A3, A4), 2, 2, 0, 3);
                set(b, avg3(A3, A4, A5), 3, 2, 1, 3);
                set(b, avg3(A4, A5, A6), 2, 3);
                set(b, avg3(A5, A6, A7), 3, 3);
                break;
            case 8: // B_HD
                set(b, avg2(L3, L2), 3, 0);
                set(b, avg3(L3, L2, L1), 3, 1);
                set(b, avg2(L2, L1), 2, 0, 3, 2);
                set(b, avg3(L2, L1, L0), 2, 1, 3, 3);
                set(b, avg2(L1, L0), 2, 2, 1, 0);
                set(b, avg3(L1, L0, P), 2, 3, 1, 1);
                set(b, avg2(L0, P), 1, 2, 0, 0);
                set(b, avg3(L0, P, A0), 1, 3, 0, 1);
                set(b, avg3(P, A0, A1), 0, 2);
                set(b, avg3(A0, A1, A2), 0, 3);
                break;
            default: // B_HU
                set(b, avg2(L0, L1), 0, 0);
                set(b, avg3(L0, L1, L2), 0, 1);
                set(b, avg2(L1, L2), 0, 2, 1, 0);
                set(b, avg3(L1, L2, L3), 0, 3, 1, 1);
                set(b, avg2(L2, L3), 1, 2, 2, 0);
                set(b, avg3(L2, L3, L3), 1, 3, 2, 1);
                set(b, L3, 2, 2, 2, 3, 3, 0, 3, 1, 3, 2, 3, 3);
                break;
        }
        for (int r = 0; r < 4; r++) System.arraycopy(b, r * 4, w, p + r * s, 4);
    }

    // b[行][列] に値を入れる。位置は (行, 列) の組を並べて渡す
    private static void set(int[] b, int value, int... positions) {
        for (int i = 0; i < positions.length; i += 2) b[positions[i] * 4 + positions[i + 1]] = value;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    // 逆 DCT (RFC 6386 14.4) をして予測に足す
    private void inverseDct(int offset, int[] w, int p, int s) {
        final short[] in = coefficients;
        final int[] tmp = transformTemp;
        // 縦方向
        for (int i = 0; i < 4; i++) {
            final int i0 = in[offset + i], i4 = in[offset + 4 + i], i8 = in[offset + 8 + i], i12 = in[offset + 12 + i];
            final int a1 = i0 + i8;
            final int b1 = i0 - i8;
            final int c1 = mulSin(i4) - mulCos(i12);
            final int d1 = mulCos(i4) + mulSin(i12);
            tmp[i] = a1 + d1;
            tmp[12 + i] = a1 - d1;
            tmp[4 + i] = b1 + c1;
            tmp[8 + i] = b1 - c1;
        }
        // 横方向。8 で割って丸め、予測に足す
        for (int r = 0; r < 4; r++) {
            final int i0 = tmp[r * 4], i1 = tmp[r * 4 + 1], i2 = tmp[r * 4 + 2], i3 = tmp[r * 4 + 3];
            final int a1 = i0 + i2;
            final int b1 = i0 - i2;
            final int c1 = mulSin(i1) - mulCos(i3);
            final int d1 = mulCos(i1) + mulSin(i3);
            final int row = p + r * s;
            w[row] = clamp255(w[row] + ((a1 + d1 + 4) >> 3));
            w[row + 1] = clamp255(w[row + 1] + ((b1 + c1 + 4) >> 3));
            w[row + 2] = clamp255(w[row + 2] + ((b1 - c1 + 4) >> 3));
            w[row + 3] = clamp255(w[row + 3] + ((a1 - d1 + 4) >> 3));
        }
    }

    // x * sqrt(2) * sin(pi / 8) の 16 ビット固定小数点の近似
    private static int mulSin(int x) {
        return (x * 35468) >> 16;
    }

    // x * sqrt(2) * cos(pi / 8)。1 を超える分 (x * (sqrt(2) cos(pi/8) - 1)) を足す
    private static int mulCos(int x) {
        return x + ((x * 20091) >> 16);
    }

    // ---------- ループフィルタ (RFC 6386 15 節) ----------

    private void applyLoopFilter() {
        for (int mbY = 0; mbY < mbHeight; mbY++) {
            for (int mbX = 0; mbX < mbWidth; mbX++) {
                final int info = filterInfo[mbY * mbWidth + mbX];
                final int kind = info & 7;
                final int limit = filterLimit[kind];
                if (limit == 0) continue;
                final boolean inner = (info & 8) != 0;
                final int interior = filterInterior[kind];
                final int hev = filterHev[kind];
                final int y = mbY * 16 * strideY + mbX * 16;
                final int uv = mbY * 8 * strideUV + mbX * 8;
                if (filterType == 1) {
                    // 簡易フィルタは輝度だけ
                    if (mbX > 0) simpleEdge(planeY, y, 1, strideY, 16, limit + 4);
                    if (inner) for (int i = 4; i < 16; i += 4) simpleEdge(planeY, y + i, 1, strideY, 16, limit);
                    if (mbY > 0) simpleEdge(planeY, y, strideY, 1, 16, limit + 4);
                    if (inner) for (int i = 4; i < 16; i += 4) simpleEdge(planeY, y + i * strideY, strideY, 1, 16, limit);
                } else {
                    if (mbX > 0) {
                        macroblockEdge(planeY, y, 1, strideY, 16, limit + 4, interior, hev);
                        macroblockEdge(planeU, uv, 1, strideUV, 8, limit + 4, interior, hev);
                        macroblockEdge(planeV, uv, 1, strideUV, 8, limit + 4, interior, hev);
                    }
                    if (inner) {
                        for (int i = 4; i < 16; i += 4) subblockEdge(planeY, y + i, 1, strideY, 16, limit, interior, hev);
                        subblockEdge(planeU, uv + 4, 1, strideUV, 8, limit, interior, hev);
                        subblockEdge(planeV, uv + 4, 1, strideUV, 8, limit, interior, hev);
                    }
                    if (mbY > 0) {
                        macroblockEdge(planeY, y, strideY, 1, 16, limit + 4, interior, hev);
                        macroblockEdge(planeU, uv, strideUV, 1, 8, limit + 4, interior, hev);
                        macroblockEdge(planeV, uv, strideUV, 1, 8, limit + 4, interior, hev);
                    }
                    if (inner) {
                        for (int i = 4; i < 16; i += 4) subblockEdge(planeY, y + i * strideY, strideY, 1, 16, limit, interior, hev);
                        subblockEdge(planeU, uv + 4 * strideUV, strideUV, 1, 8, limit, interior, hev);
                        subblockEdge(planeV, uv + 4 * strideUV, strideUV, 1, 8, limit, interior, hev);
                    }
                }
            }
        }
    }

    // 符号付き 8 ビットに収める
    private static int c(int v) {
        return v < -128 ? -128 : v > 127 ? 127 : v;
    }

    // 画素の値を符号付きにする
    private static int u2s(byte v) {
        return (v & 0xFF) - 128;
    }

    // 符号付きの値を収めて、画素の値に戻す
    private static byte s2u(int v) {
        return (byte) (c(v) + 128);
    }

    /**
     * 端をはさむ画素の並びを順に処理する。edge は端のすぐ後ろ (q0) の画素の位置、step は端をまたぐ方向の間隔、
     * along は端に沿った方向の間隔、count は並びの数。
     */
    private static void simpleEdge(byte[] b, int edge, int step, int along, int count, int edgeLimit) {
        for (int i = 0; i < count; i++) {
            final int q = edge + i * along;
            if (edgeDifference(b, q, step) <= edgeLimit) commonAdjust(true, b, q, step);
        }
    }

    private static void subblockEdge(byte[] b, int edge, int step, int along, int count, int edgeLimit, int interior, int hevThreshold) {
        for (int i = 0; i < count; i++) {
            final int q = edge + i * along;
            if (!filterYes(b, q, step, edgeLimit, interior)) continue;
            final boolean hev = highEdgeVariance(b, q, step, hevThreshold);
            final int p1 = u2s(b[q - 2 * step]);
            final int q1 = u2s(b[q + step]);
            final int a = (commonAdjust(hev, b, q, step) + 1) >> 1;
            if (!hev) {
                b[q + step] = s2u(q1 - a);
                b[q - 2 * step] = s2u(p1 + a);
            }
        }
    }

    private static void macroblockEdge(byte[] b, int edge, int step, int along, int count, int edgeLimit, int interior, int hevThreshold) {
        for (int i = 0; i < count; i++) {
            final int q = edge + i * along;
            if (!filterYes(b, q, step, edgeLimit, interior)) continue;
            if (highEdgeVariance(b, q, step, hevThreshold)) {
                commonAdjust(true, b, q, step);
                continue;
            }
            final int p2 = u2s(b[q - 3 * step]), p1 = u2s(b[q - 2 * step]), p0 = u2s(b[q - step]);
            final int q0 = u2s(b[q]), q1 = u2s(b[q + step]), q2 = u2s(b[q + 2 * step]);
            final int w = c(c(p1 - q1) + 3 * (q0 - p0));
            int a = c((27 * w + 63) >> 7);
            b[q] = s2u(q0 - a);
            b[q - step] = s2u(p0 + a);
            a = c((18 * w + 63) >> 7);
            b[q + step] = s2u(q1 - a);
            b[q - 2 * step] = s2u(p1 + a);
            a = c((9 * w + 63) >> 7);
            b[q + 2 * step] = s2u(q2 - a);
            b[q - 3 * step] = s2u(p2 + a);
        }
    }

    // |p0 - q0| * 2 + |p1 - q1| / 2
    private static int edgeDifference(byte[] b, int q, int step) {
        final int p1 = b[q - 2 * step] & 0xFF, p0 = b[q - step] & 0xFF, q0 = b[q] & 0xFF, q1 = b[q + step] & 0xFF;
        return Math.abs(p0 - q0) * 2 + (Math.abs(p1 - q1) >> 1);
    }

    private static boolean filterYes(byte[] b, int q, int step, int edgeLimit, int interior) {
        if (edgeDifference(b, q, step) > edgeLimit) return false;
        final int p3 = b[q - 4 * step] & 0xFF, p2 = b[q - 3 * step] & 0xFF, p1 = b[q - 2 * step] & 0xFF, p0 = b[q - step] & 0xFF;
        final int q0 = b[q] & 0xFF, q1 = b[q + step] & 0xFF, q2 = b[q + 2 * step] & 0xFF, q3 = b[q + 3 * step] & 0xFF;
        return Math.abs(p3 - p2) <= interior && Math.abs(p2 - p1) <= interior && Math.abs(p1 - p0) <= interior
                && Math.abs(q3 - q2) <= interior && Math.abs(q2 - q1) <= interior && Math.abs(q1 - q0) <= interior;
    }

    private static boolean highEdgeVariance(byte[] b, int q, int step, int threshold) {
        final int p1 = b[q - 2 * step] & 0xFF, p0 = b[q - step] & 0xFF, q0 = b[q] & 0xFF, q1 = b[q + step] & 0xFF;
        return Math.abs(p1 - p0) > threshold || Math.abs(q1 - q0) > threshold;
    }

    // 端の 2 画素を近づける。outerTaps なら外側の 2 画素の差も使う。q0 から引いた量を返す
    private static int commonAdjust(boolean outerTaps, byte[] b, int q, int step) {
        final int p1 = u2s(b[q - 2 * step]), p0 = u2s(b[q - step]), q0 = u2s(b[q]), q1 = u2s(b[q + step]);
        int a = c((outerTaps ? c(p1 - q1) : 0) + 3 * (q0 - p0));
        final int round = c(a + 3) >> 3;
        a = c(a + 4) >> 3;
        b[q] = s2u(q0 - a);
        b[q - step] = s2u(p0 + round);
        return a;
    }
}
