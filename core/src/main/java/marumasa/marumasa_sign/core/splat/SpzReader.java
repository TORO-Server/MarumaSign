package marumasa.marumasa_sign.core.splat;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Niantic の .spz (バージョン 2、3、および 4) を読み込む。
 * バージョン 2 および 3 は全体が gzip 圧縮され、バージョン 4 は平文の 32 バイトヘッダと
 * TOC (目次)、および属性ストリームごとの Zstandard (RFC 8878) 圧縮で構成される。
 * .spz の座標系は仕様上 RUB (+X が右、+Y が上、+Z が手前) で SplatCloud と一致するため、flipYZ は行わない。
 */
final class SpzReader {

    private static final int NGSP_MAGIC = 0x5053474E;
    private static final int MAX_POINTS = 10_000_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final float COLOR_SCALE = 0.15f;
    private static final float SQRT1_2 = 0.7071067811865475f;

    private SpzReader() {
    }

    /**
     * 先頭のバイト列から SPZ (v2 / v3 の gzip、または v4 の平文 NGSP ヘッダ) かどうかを確かめる。
     */
    static boolean isSpz(byte[] head, int length) {
        if (head == null || length < 4) return false;
        final int limit = Math.min(length, head.length);

        // バージョン 4: 先頭が平文の "NGSP" マジックで始まる
        if (limit >= 4 && readIntLE(head, 0) == NGSP_MAGIC) {
            if (limit < 32) return false;
            final int version = readIntLE(head, 4);
            final int numPoints = readIntLE(head, 8);
            final int shDegree = head[12] & 0xFF;
            final int fractionalBits = head[13] & 0xFF;
            final int numStreams = head[15] & 0xFF;
            final int tocByteOffset = readIntLE(head, 16);
            final int expectedStreams = shDegree == 0 ? 5 : 6;
            return version == 4
                    && numPoints > 0 && numPoints <= MAX_POINTS
                    && shDegree <= 4
                    && fractionalBits <= 24
                    && numStreams == expectedStreams
                    && tocByteOffset >= 32;
        }

        // バージョン 2 / 3: 全体が gzip 圧縮されたストリーム
        if (limit < 18) return false;
        if ((head[0] & 0xFF) != 0x1F || (head[1] & 0xFF) != 0x8B) return false;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(head, 0, limit))) {
            final byte[] hdr = new byte[16];
            readFully(gzip, hdr, 0, 16);
            final int magic = readIntLE(hdr, 0);
            final int version = readIntLE(hdr, 4);
            final int numPoints = readIntLE(hdr, 8);
            final int shDegree = hdr[12] & 0xFF;
            final int fractionalBits = hdr[13] & 0xFF;
            final int reserved = hdr[15] & 0xFF;
            return magic == NGSP_MAGIC
                    && (version == 2 || version == 3)
                    && numPoints > 0 && numPoints <= MAX_POINTS
                    && shDegree <= 3
                    && fractionalBits <= 24
                    && reserved == 0;
        } catch (IOException e) {
            return false;
        }
    }

    static SplatCloud load(byte[] content) throws IOException {
        if (content == null || content.length < 18) throw new IOException("Invalid SPZ file");

        // バージョン 4: 平文の "NGSP" で始まる
        if (content.length >= 32 && readIntLE(content, 0) == NGSP_MAGIC) {
            return loadV4(content);
        }

        // バージョン 2 / 3: gzip 圧縮ストリーム
        if ((content[0] & 0xFF) == 0x1F && (content[1] & 0xFF) == 0x8B) {
            return loadLegacyGzip(content);
        }

        throw new IOException("Not a valid SPZ file");
    }

    private static SplatCloud loadLegacyGzip(byte[] content) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            final byte[] hdr = new byte[16];
            readFully(gzip, hdr, 0, 16);

            final int magic = readIntLE(hdr, 0);
            if (magic != NGSP_MAGIC) throw new IOException("Invalid SPZ magic");

            final int version = readIntLE(hdr, 4);
            if (version != 2 && version != 3) throw new IOException("Unsupported SPZ version: " + version);

            final int numPoints = readIntLE(hdr, 8);
            if (numPoints <= 0 || numPoints > MAX_POINTS) {
                throw new IOException("Invalid SPZ point count: " + numPoints);
            }

            final int shDegree = hdr[12] & 0xFF;
            final int fractionalBits = hdr[13] & 0xFF;
            final int reserved = hdr[15] & 0xFF;
            if (shDegree > 3 || fractionalBits > 24 || reserved != 0) {
                throw new IOException("Invalid SPZ header parameters");
            }

            final int rotStride = version == 3 ? 4 : 3;
            final int shDim = SplatCloud.restCoefficients(shDegree);
            final int shPerPoint = shDim * 3;

            final long posBytesLen = 9L * numPoints;
            final long alphaBytesLen = numPoints;
            final long colorBytesLen = 3L * numPoints;
            final long scaleBytesLen = 3L * numPoints;
            final long rotBytesLen = (long) rotStride * numPoints;
            final long shBytesLen = (long) shPerPoint * numPoints;
            final long totalUncompressed = 16L + posBytesLen + alphaBytesLen + colorBytesLen
                    + scaleBytesLen + rotBytesLen + shBytesLen;

            // gzip 爆弾を防ぐため、展開後の大きさと圧縮率の上限を確かめる
            if (totalUncompressed > MAX_UNCOMPRESSED_BYTES
                    || (totalUncompressed > 65536L && totalUncompressed > (long) content.length * 100L)) {
                throw new IOException("SPZ uncompressed size exceeds safety limits");
            }

            final byte[] posBytes = new byte[(int) posBytesLen];
            final byte[] alphaBytes = new byte[(int) alphaBytesLen];
            final byte[] colorBytes = new byte[(int) colorBytesLen];
            final byte[] scaleBytes = new byte[(int) scaleBytesLen];
            final byte[] rotBytes = new byte[(int) rotBytesLen];

            readFully(gzip, posBytes, 0, posBytes.length);
            readFully(gzip, alphaBytes, 0, alphaBytes.length);
            readFully(gzip, colorBytes, 0, colorBytes.length);
            readFully(gzip, scaleBytes, 0, scaleBytes.length);
            readFully(gzip, rotBytes, 0, rotBytes.length);

            // 1 パス目: importance の計算
            float[] importance = new float[numPoints];
            for (int i = 0; i < numPoints; i++) {
                final float opacity = (alphaBytes[i] & 0xFF) / 255.0f;
                final int so = i * 3;
                final float sx = (float) Math.exp((scaleBytes[so] & 0xFF) / 16.0f - 10.0f);
                final float sy = (float) Math.exp((scaleBytes[so + 1] & 0xFF) / 16.0f - 10.0f);
                final float sz = (float) Math.exp((scaleBytes[so + 2] & 0xFF) / 16.0f - 10.0f);
                importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
            }

            final int[] selected = SplatLoader.selectTop(importance, numPoints, SplatLoader.MAX_SPLATS);
            importance = null;
            final int count = selected.length;

            final float[] positions = new float[count * 3];
            final float[] rotations = new float[count * 4];
            final float[] scales = new float[count * 3];
            final float[] opacities = new float[count];
            final float[] colors = new float[count * 3];
            final float[] sh = shDegree > 0 ? new float[count * shPerPoint] : new float[0];

            final float posScale = 1.0f / (1 << fractionalBits);

            // 2 パス目: 選ばれたガウス分布だけを展開する
            for (int n = 0; n < count; n++) {
                final int i = selected[n];

                final int po = i * 9;
                for (int c = 0; c < 3; c++) {
                    final int co = po + c * 3;
                    int fixed = (posBytes[co] & 0xFF)
                            | ((posBytes[co + 1] & 0xFF) << 8)
                            | ((posBytes[co + 2] & 0xFF) << 16);
                    if ((fixed & 0x800000) != 0) fixed |= 0xFF000000;
                    positions[n * 3 + c] = fixed * posScale;
                }

                opacities[n] = (alphaBytes[i] & 0xFF) / 255.0f;

                final int co = i * 3;
                for (int c = 0; c < 3; c++) {
                    final float fDc = ((colorBytes[co + c] & 0xFF) / 255.0f - 0.5f) / COLOR_SCALE;
                    colors[n * 3 + c] = 0.5f + SplatCloud.SH_C0 * fDc;
                    scales[n * 3 + c] = (float) Math.exp((scaleBytes[co + c] & 0xFF) / 16.0f - 10.0f);
                }

                final int ro = i * rotStride;
                if (version == 2) {
                    final float x = (rotBytes[ro] & 0xFF) / 127.5f - 1.0f;
                    final float y = (rotBytes[ro + 1] & 0xFF) / 127.5f - 1.0f;
                    final float z = (rotBytes[ro + 2] & 0xFF) / 127.5f - 1.0f;
                    final float w = (float) Math.sqrt(Math.max(0.0f, 1.0f - (x * x + y * y + z * z)));
                    PlyReader.storeNormalizedQuat(rotations, n * 4, x, y, z, w);
                } else {
                    int comp = readIntLE(rotBytes, ro);
                    final int iLargest = comp >>> 30;
                    float sumSquares = 0.0f;
                    final float[] q = new float[4];
                    for (int k = 3; k >= 0; --k) {
                        if (k != iLargest) {
                            final int mag = comp & 511;
                            final int negbit = (comp >>> 9) & 1;
                            comp >>>= 10;
                            float val = SQRT1_2 * (mag / 511.0f);
                            if (negbit == 1) val = -val;
                            q[k] = val;
                            sumSquares += val * val;
                        }
                    }
                    q[iLargest] = (float) Math.sqrt(Math.max(0.0f, 1.0f - sumSquares));
                    PlyReader.storeNormalizedQuat(rotations, n * 4, q[0], q[1], q[2], q[3]);
                }
            }

            // SH 係数はストリームから順番に読み、選ばれたものだけを残す
            if (shDegree > 0) {
                final byte[] shBuf = new byte[shPerPoint];
                int selIdx = 0;
                for (int i = 0; i < numPoints; i++) {
                    readFully(gzip, shBuf, 0, shPerPoint);
                    if (selIdx < count && selected[selIdx] == i) {
                        final int dstBase = selIdx * shPerPoint;
                        for (int j = 0; j < shPerPoint; j++) {
                            sh[dstBase + j] = ((shBuf[j] & 0xFF) - 128.0f) / 128.0f;
                        }
                        selIdx++;
                    }
                }
            }

            // gzip の末尾のトレーラ (CRC32・サイズ) を検証するためストリームの終端まで読む
            final byte[] tail = new byte[1024];
            int extra = 0;
            int r;
            while ((r = gzip.read(tail)) >= 0) {
                extra += r;
                if (extra > 65536) throw new IOException("Excessive trailing data in SPZ stream");
            }

            return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
        }
    }

    private static int readIntLE(byte[] b, int o) {
        return (b[o] & 0xFF)
                | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            final int r = in.read(buf, off + total, len - total);
            if (r < 0) throw new EOFException("Truncated SPZ stream");
            total += r;
        }
    }

    /**
     * SPZ バージョン 4 を読み込む。
     * 32 バイトの平文ヘッダ、任意の拡張、TOC (各属性ストリームの圧縮・非圧縮サイズ)、
     * およびストリームごとの ZSTD 圧縮データを順次展開する。
     */
    private static SplatCloud loadV4(byte[] content) throws IOException {
        if (content.length < 32) throw new IOException("SPZ v4 file too short for header");

        final int magic = readIntLE(content, 0);
        if (magic != NGSP_MAGIC) throw new IOException("Invalid SPZ magic");

        final int version = readIntLE(content, 4);
        if (version != 4) throw new IOException("Unsupported SPZ version: " + version);

        final int numPoints = readIntLE(content, 8);
        if (numPoints <= 0 || numPoints > MAX_POINTS) {
            throw new IOException("Invalid SPZ point count: " + numPoints);
        }

        final int shDegree = content[12] & 0xFF;
        if (shDegree > 4) {
            throw new IOException("Unsupported SPZ SH degree: " + shDegree);
        }
        final int fractionalBits = content[13] & 0xFF;
        if (fractionalBits > 24) {
            throw new IOException("Invalid SPZ fractional bits: " + fractionalBits);
        }

        final int numStreams = content[15] & 0xFF;
        final int expectedStreams = shDegree == 0 ? 5 : 6;
        if (numStreams != expectedStreams) {
            throw new IOException("Unexpected stream count: " + numStreams + " (expected " + expectedStreams + ")");
        }

        final int tocByteOffset = readIntLE(content, 16);
        if (tocByteOffset < 32) {
            throw new IOException("Invalid TOC byte offset: " + tocByteOffset);
        }
        final long tocEnd = (long) tocByteOffset + (long) numStreams * 16L;
        if (tocEnd > content.length) {
            throw new IOException("TOC extends beyond end of file");
        }

        final long posBytesLen = 9L * numPoints;
        final long alphaBytesLen = numPoints;
        final long colorBytesLen = 3L * numPoints;
        final long scaleBytesLen = 3L * numPoints;
        final long rotBytesLen = 4L * numPoints; // v4 は常に smallest-three (4 バイト)

        final int fileShDim = shDimForDegree(shDegree);
        final long shBytesLen = (long) numPoints * fileShDim * 3L;

        final long[] expectedSizes = new long[numStreams];
        expectedSizes[0] = posBytesLen;
        expectedSizes[1] = alphaBytesLen;
        expectedSizes[2] = colorBytesLen;
        expectedSizes[3] = scaleBytesLen;
        expectedSizes[4] = rotBytesLen;
        if (shDegree > 0) {
            expectedSizes[5] = shBytesLen;
        }

        final long totalExpected = posBytesLen + alphaBytesLen + colorBytesLen + scaleBytesLen + rotBytesLen + (shDegree > 0 ? shBytesLen : 0);
        if (totalExpected > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("SPZ uncompressed size exceeds safety limits: " + totalExpected);
        }

        final long[] compressedSizes = new long[numStreams];
        final int[] streamOffsets = new int[numStreams];
        long currentOffset = tocEnd;

        for (int i = 0; i < numStreams; i++) {
            final int entryOffset = tocByteOffset + i * 16;
            final long cSize = readLongLE(content, entryOffset);
            final long uSize = readLongLE(content, entryOffset + 8);
            if (cSize <= 0 || cSize > Integer.MAX_VALUE) {
                throw new IOException("Invalid compressed stream size: " + cSize);
            }
            if (uSize != expectedSizes[i]) {
                throw new IOException("Stream " + i + " uncompressed size mismatch: " + uSize + " != expected " + expectedSizes[i]);
            }
            if (currentOffset + cSize > content.length) {
                throw new IOException("Stream " + i + " extends beyond end of file");
            }
            compressedSizes[i] = cSize;
            streamOffsets[i] = (int) currentOffset;
            currentOffset += cSize;
        }

        if (currentOffset != content.length) {
            throw new IOException("File size mismatch: total compressed streams end at " + currentOffset + ", file size " + content.length);
        }

        // 1 パス目: alphas (ストリーム 1) と scales (ストリーム 3) を展開して importance を計算
        final byte[] alphaBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                content, streamOffsets[1], (int) compressedSizes[1], (int) alphaBytesLen);
        if (alphaBytes.length != (int) alphaBytesLen) {
            throw new IOException("Decompressed alpha size mismatch");
        }

        final byte[] scaleBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                content, streamOffsets[3], (int) compressedSizes[3], (int) scaleBytesLen);
        if (scaleBytes.length != (int) scaleBytesLen) {
            throw new IOException("Decompressed scale size mismatch");
        }

        float[] importance = new float[numPoints];
        for (int i = 0; i < numPoints; i++) {
            final float opacity = (alphaBytes[i] & 0xFF) / 255.0f;
            final int so = i * 3;
            final float sx = (float) Math.exp((scaleBytes[so] & 0xFF) / 16.0f - 10.0f);
            final float sy = (float) Math.exp((scaleBytes[so + 1] & 0xFF) / 16.0f - 10.0f);
            final float sz = (float) Math.exp((scaleBytes[so + 2] & 0xFF) / 16.0f - 10.0f);
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.selectTop(importance, numPoints, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        // 残りのストリームを展開
        final byte[] posBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                content, streamOffsets[0], (int) compressedSizes[0], (int) posBytesLen);
        if (posBytes.length != (int) posBytesLen) {
            throw new IOException("Decompressed position size mismatch");
        }

        final byte[] colorBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                content, streamOffsets[2], (int) compressedSizes[2], (int) colorBytesLen);
        if (colorBytes.length != (int) colorBytesLen) {
            throw new IOException("Decompressed color size mismatch");
        }

        final byte[] rotBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                content, streamOffsets[4], (int) compressedSizes[4], (int) rotBytesLen);
        if (rotBytes.length != (int) rotBytesLen) {
            throw new IOException("Decompressed rotation size mismatch");
        }

        final byte[] shBytes;
        if (shDegree > 0) {
            shBytes = marumasa.marumasa_sign.core.texture.ZstdDecoder.decompress(
                    content, streamOffsets[5], (int) compressedSizes[5], (int) shBytesLen);
            if (shBytes.length != (int) shBytesLen) {
                throw new IOException("Decompressed SH size mismatch");
            }
        } else {
            shBytes = null;
        }

        // SH 次数 4 が来たら 3 次までを使い、4 次は捨てる
        final int outShDegree = Math.min(shDegree, 3);
        final int outShDim = SplatCloud.restCoefficients(outShDegree);
        final int outShPerPoint = outShDim * 3;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = outShDegree > 0 ? new float[count * outShPerPoint] : new float[0];

        final float posScale = 1.0f / (1 << fractionalBits);

        // 2 パス目: 選ばれたガウス分布だけを展開する
        for (int n = 0; n < count; n++) {
            final int i = selected[n];

            final int po = i * 9;
            for (int c = 0; c < 3; c++) {
                final int co = po + c * 3;
                int fixed = (posBytes[co] & 0xFF)
                        | ((posBytes[co + 1] & 0xFF) << 8)
                        | ((posBytes[co + 2] & 0xFF) << 16);
                if ((fixed & 0x800000) != 0) fixed |= 0xFF000000;
                positions[n * 3 + c] = fixed * posScale;
            }

            opacities[n] = (alphaBytes[i] & 0xFF) / 255.0f;

            final int co = i * 3;
            for (int c = 0; c < 3; c++) {
                final float fDc = ((colorBytes[co + c] & 0xFF) / 255.0f - 0.5f) / COLOR_SCALE;
                colors[n * 3 + c] = 0.5f + SplatCloud.SH_C0 * fDc;
                scales[n * 3 + c] = (float) Math.exp((scaleBytes[co + c] & 0xFF) / 16.0f - 10.0f);
            }

            final int ro = i * 4;
            int comp = readIntLE(rotBytes, ro);
            final int iLargest = comp >>> 30;
            float sumSquares = 0.0f;
            final float[] q = new float[4];
            for (int k = 3; k >= 0; --k) {
                if (k != iLargest) {
                    final int mag = comp & 511;
                    final int negbit = (comp >>> 9) & 1;
                    comp >>>= 10;
                    float val = SQRT1_2 * (mag / 511.0f);
                    if (negbit == 1) val = -val;
                    q[k] = val;
                    sumSquares += val * val;
                }
            }
            q[iLargest] = (float) Math.sqrt(Math.max(0.0f, 1.0f - sumSquares));
            PlyReader.storeNormalizedQuat(rotations, n * 4, q[0], q[1], q[2], q[3]);

            if (outShDegree > 0 && shBytes != null) {
                final int fileBase = i * fileShDim * 3;
                final int dstBase = n * outShPerPoint;
                for (int j = 0; j < outShPerPoint; j++) {
                    sh[dstBase + j] = ((shBytes[fileBase + j] & 0xFF) - 128.0f) / 128.0f;
                }
            }
        }

        return new SplatCloud(count, positions, rotations, scales, opacities, colors, outShDegree, sh);
    }

    static int shDimForDegree(int degree) {
        switch (degree) {
            case 0: return 0;
            case 1: return 3;
            case 2: return 8;
            case 3: return 15;
            case 4: return 24;
            default: return 0;
        }
    }

    private static long readLongLE(byte[] b, int o) {
        long low = (b[o] & 0xFFL)
                | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16)
                | ((b[o + 3] & 0xFFL) << 24);
        long high = (b[o + 4] & 0xFFL)
                | ((b[o + 5] & 0xFFL) << 8)
                | ((b[o + 6] & 0xFFL) << 16)
                | ((b[o + 7] & 0xFFL) << 24);
        return low | (high << 32);
    }
}
