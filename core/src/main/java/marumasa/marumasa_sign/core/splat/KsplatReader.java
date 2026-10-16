package marumasa.marumasa_sign.core.splat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GaussianSplats3D (mkkellogg) の .ksplat (圧縮モード 0 / 1 / 2) を読み込む。
 */
final class KsplatReader {

    private static final int MAIN_HEADER_SIZE = 4096;
    private static final int SECTION_HEADER_SIZE = 1024;
    private static final int MAX_SECTIONS = 65536;
    private static final int MAX_SPLATS_LIMIT = 50_000_000;
    private static final float DEFAULT_SH_RANGE = 1.5f;

    private KsplatReader() {
    }

    /**
     * 拡張子が .ksplat であり、先頭のヘッダ値が妥当かどうかを判定する。
     */
    static boolean isKsplat(byte[] head, int length, String ext) {
        if (!".ksplat".equals(ext) || head == null || length < 44) return false;
        final int limit = Math.min(length, head.length);
        if (limit < 44) return false;
        final ByteBuffer buf = ByteBuffer.wrap(head, 0, limit).order(ByteOrder.LITTLE_ENDIAN);
        final int versionMajor = head[0] & 0xFF;
        final int versionMinor = head[1] & 0xFF;
        if (versionMajor != 0 || versionMinor < 1) return false;

        final int maxSections = buf.getInt(4);
        if (maxSections <= 0 || maxSections > MAX_SECTIONS) return false;

        final int maxSplats = buf.getInt(12);
        final int numSplats = buf.getInt(16);
        final int total = numSplats > 0 ? numSplats : maxSplats;
        if (numSplats < 0 || maxSplats < 0 || total <= 0 || total > MAX_SPLATS_LIMIT) return false;

        final int compressionMode = buf.getShort(20) & 0xFFFF;
        if (compressionMode > 2) return false;

        final float cx = buf.getFloat(24);
        final float cy = buf.getFloat(28);
        final float cz = buf.getFloat(32);
        final float minSh = buf.getFloat(36);
        final float maxSh = buf.getFloat(40);
        return Float.isFinite(cx) && Float.isFinite(cy) && Float.isFinite(cz)
                && Float.isFinite(minSh) && Float.isFinite(maxSh) && minSh <= maxSh;
    }

    static SplatCloud load(byte[] content) throws IOException {
        if (content == null || content.length < MAIN_HEADER_SIZE) {
            throw new IOException("Invalid .ksplat file size");
        }
        final ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

        final int versionMajor = content[0] & 0xFF;
        final int versionMinor = content[1] & 0xFF;
        if (versionMajor != 0 || versionMinor < 1) {
            throw new IOException("Unsupported .ksplat version: " + versionMajor + "." + versionMinor);
        }

        final int maxSections = buf.getInt(4);
        if (maxSections <= 0 || maxSections > MAX_SECTIONS) {
            throw new IOException("Invalid .ksplat maxSections: " + maxSections);
        }

        final int headerMaxSplats = buf.getInt(12);
        final int headerSplats = buf.getInt(16);
        final int targetSplats = headerSplats > 0 ? headerSplats : headerMaxSplats;
        if (headerSplats < 0 || headerMaxSplats < 0 || targetSplats <= 0 || targetSplats > MAX_SPLATS_LIMIT) {
            throw new IOException("Invalid .ksplat splat count: " + targetSplats);
        }

        final int compressionMode = buf.getShort(20) & 0xFFFF;
        if (compressionMode > 2) {
            throw new IOException("Unsupported .ksplat compression mode: " + compressionMode);
        }

        float minSh = buf.getFloat(36);
        float maxSh = buf.getFloat(40);
        if (!Float.isFinite(minSh) || !Float.isFinite(maxSh) || minSh > maxSh) {
            throw new IOException("Invalid .ksplat SH range");
        }
        if (minSh == 0.0f && maxSh == 0.0f) {
            minSh = -DEFAULT_SH_RANGE;
            maxSh = DEFAULT_SH_RANGE;
        }

        final long sectionHeadersEnd = (long) MAIN_HEADER_SIZE + (long) maxSections * SECTION_HEADER_SIZE;
        if (sectionHeadersEnd > content.length) {
            throw new IOException("Truncated .ksplat section headers");
        }

        final Section[] sections = new Section[maxSections];
        long dataOffset = sectionHeadersEnd;
        long availableSplats = 0;
        int maxShDegree = 0;

        for (int s = 0; s < maxSections; s++) {
            final int hdrOff = MAIN_HEADER_SIZE + s * SECTION_HEADER_SIZE;
            final int rawSecSplats = buf.getInt(hdrOff);
            final int maxSecSplats = buf.getInt(hdrOff + 4);
            final int secSplats = rawSecSplats > 0 ? rawSecSplats : maxSecSplats;
            final int bucketCapacity = buf.getInt(hdrOff + 8);
            final int bucketCount = buf.getInt(hdrOff + 12);
            final float spatialBlockSize = buf.getFloat(hdrOff + 16);
            final int bucketStorageSize = buf.getShort(hdrOff + 20) & 0xFFFF;
            final int rawQuantRange = buf.getInt(hdrOff + 24);
            final int fullBuckets = buf.getInt(hdrOff + 32);
            final int partialBuckets = buf.getInt(hdrOff + 36);
            final int shDegree = buf.getShort(hdrOff + 40) & 0xFFFF;

            if (rawSecSplats < 0 || maxSecSplats < 0 || secSplats > maxSecSplats
                    || bucketCapacity < 0 || bucketCount < 0
                    || fullBuckets < 0 || partialBuckets < 0
                    || (long) fullBuckets + partialBuckets > bucketCount
                    || shDegree > 3 || !Float.isFinite(spatialBlockSize) || spatialBlockSize < 0.0f) {
                throw new IOException("Invalid .ksplat section header " + s);
            }

            final int quantRange = rawQuantRange != 0 ? rawQuantRange : (compressionMode == 0 ? 1 : 32767);
            if (quantRange <= 0) throw new IOException("Invalid .ksplat quantization range");

            final int shComps = SplatCloud.restCoefficients(shDegree) * 3;
            final int bytesPerSplat;
            if (compressionMode == 0) {
                bytesPerSplat = 44 + shComps * 4;
            } else if (compressionMode == 1) {
                bytesPerSplat = 24 + shComps * 2;
            } else {
                bytesPerSplat = 24 + shComps;
            }

            final long partialMetaBytes = (long) partialBuckets * 4L;
            final long bucketsBase = dataOffset + partialMetaBytes;
            final long bucketsBytes = (long) bucketCount * bucketStorageSize;
            final long splatDataBase = bucketsBase + bucketsBytes;
            final long splatDataBytes = (long) maxSecSplats * bytesPerSplat;
            final long nextOffset = splatDataBase + splatDataBytes;

            if (nextOffset > content.length) {
                throw new IOException("Truncated .ksplat section data in section " + s);
            }

            if (compressionMode >= 1 && secSplats > 0) {
                if (bucketStorageSize < 12 || (long) bucketCount * 12L > bucketsBytes) {
                    throw new IOException("Invalid .ksplat bucket storage in section " + s);
                }
                long covered = (long) fullBuckets * bucketCapacity;
                for (int pb = 0; pb < partialBuckets; pb++) {
                    final int pbSize = buf.getInt((int) (dataOffset + pb * 4L));
                    if (pbSize <= 0 || pbSize > bucketCapacity) {
                        throw new IOException("Invalid .ksplat partial bucket size in section " + s);
                    }
                    covered += pbSize;
                }
                if (covered < secSplats) {
                    throw new IOException("Buckets do not cover all splats in .ksplat section " + s);
                }
                for (int b = 0; b < bucketCount; b++) {
                    final int bOff = (int) (bucketsBase + b * 12L);
                    if (!Float.isFinite(buf.getFloat(bOff))
                            || !Float.isFinite(buf.getFloat(bOff + 4))
                            || !Float.isFinite(buf.getFloat(bOff + 8))) {
                        throw new IOException("Non-finite bucket center in .ksplat section " + s);
                    }
                }
            }

            sections[s] = new Section(secSplats, bucketCapacity, fullBuckets, partialBuckets,
                    (spatialBlockSize / 2.0f) / quantRange, quantRange, shDegree, bytesPerSplat,
                    (int) dataOffset, (int) bucketsBase, (int) splatDataBase);
            dataOffset = nextOffset;
            availableSplats += secSplats;
            if (shDegree > maxShDegree) maxShDegree = shDegree;
        }

        if (availableSplats < targetSplats) {
            throw new IOException("Truncated .ksplat splat count: " + availableSplats + " < " + targetSplats);
        }
        final int total = targetSplats;

        // 1 パス目: 全ガウス分布の値の妥当性を確かめつつ importance を計算する
        float[] importance = new float[total];
        int globalIdx = 0;
        for (int s = 0; s < maxSections && globalIdx < total; s++) {
            final Section sec = sections[s];
            final int limit = Math.min(sec.splatCount, total - globalIdx);
            final int shComps = SplatCloud.restCoefficients(sec.shDegree) * 3;
            for (int local = 0; local < limit; local++, globalIdx++) {
                final int base = sec.splatDataBase + local * sec.bytesPerSplat;
                final float sx;
                final float sy;
                final float sz;
                final float opacity;
                if (compressionMode == 0) {
                    final float px = buf.getFloat(base);
                    final float py = buf.getFloat(base + 4);
                    final float pz = buf.getFloat(base + 8);
                    sx = buf.getFloat(base + 12);
                    sy = buf.getFloat(base + 16);
                    sz = buf.getFloat(base + 20);
                    final float rw = buf.getFloat(base + 24);
                    final float rx = buf.getFloat(base + 28);
                    final float ry = buf.getFloat(base + 32);
                    final float rz = buf.getFloat(base + 36);
                    if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)
                            || !Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)
                            || !Float.isFinite(rw) || !Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) {
                        throw new IOException("Non-finite value in .ksplat mode 0");
                    }
                    for (int k = 0; k < shComps; k++) {
                        if (!Float.isFinite(buf.getFloat(base + 44 + k * 4))) {
                            throw new IOException("Non-finite SH value in .ksplat mode 0");
                        }
                    }
                    opacity = (content[base + 43] & 0xFF) / 255.0f;
                } else {
                    sx = halfToFloat(buf.getShort(base + 6) & 0xFFFF);
                    sy = halfToFloat(buf.getShort(base + 8) & 0xFFFF);
                    sz = halfToFloat(buf.getShort(base + 10) & 0xFFFF);
                    final float rw = halfToFloat(buf.getShort(base + 12) & 0xFFFF);
                    final float rx = halfToFloat(buf.getShort(base + 14) & 0xFFFF);
                    final float ry = halfToFloat(buf.getShort(base + 16) & 0xFFFF);
                    final float rz = halfToFloat(buf.getShort(base + 18) & 0xFFFF);
                    if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)
                            || !Float.isFinite(rw) || !Float.isFinite(rx) || !Float.isFinite(ry) || !Float.isFinite(rz)) {
                        throw new IOException("Non-finite half-float in .ksplat");
                    }
                    if (compressionMode == 1) {
                        for (int k = 0; k < shComps; k++) {
                            if (!Float.isFinite(halfToFloat(buf.getShort(base + 24 + k * 2) & 0xFFFF))) {
                                throw new IOException("Non-finite SH half-float in .ksplat mode 1");
                            }
                        }
                    }
                    opacity = (content[base + 23] & 0xFF) / 255.0f;
                }
                importance[globalIdx] = SplatLoader.importance(
                        opacity, Math.abs(sx), Math.abs(sy), Math.abs(sz));
            }
        }

        final int[] selected = SplatLoader.selectTop(importance, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;
        final int rest = SplatCloud.restCoefficients(maxShDegree);

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = maxShDegree > 0 ? new float[count * rest * 3] : new float[0];
        final float shRange = maxSh - minSh;

        // 2 パス目: 選ばれたガウス分布だけを展開する
        int selPtr = 0;
        int sectionStartGlobal = 0;
        for (int s = 0; s < maxSections && selPtr < count; s++) {
            final Section sec = sections[s];
            final int secLimit = Math.min(sec.splatCount, total - sectionStartGlobal);
            final int sectionEndGlobal = sectionStartGlobal + secLimit;
            if (selected[selPtr] >= sectionEndGlobal) {
                sectionStartGlobal = sectionEndGlobal;
                continue;
            }

            final int fullSplats = sec.fullBuckets * sec.bucketCapacity;
            int partialBucketIdx = 0;
            int partialStartLocal = fullSplats;
            int partialEndLocal = sec.partialBuckets > 0
                    ? partialStartLocal + buf.getInt(sec.partialBase)
                    : fullSplats;

            while (selPtr < count && selected[selPtr] < sectionEndGlobal) {
                final int local = selected[selPtr] - sectionStartGlobal;
                final int base = sec.splatDataBase + local * sec.bytesPerSplat;
                final int n = selPtr++;

                if (compressionMode == 0) {
                    positions[n * 3] = buf.getFloat(base);
                    positions[n * 3 + 1] = buf.getFloat(base + 4);
                    positions[n * 3 + 2] = buf.getFloat(base + 8);

                    scales[n * 3] = Math.abs(buf.getFloat(base + 12));
                    scales[n * 3 + 1] = Math.abs(buf.getFloat(base + 16));
                    scales[n * 3 + 2] = Math.abs(buf.getFloat(base + 20));

                    final float rw = buf.getFloat(base + 24);
                    final float rx = buf.getFloat(base + 28);
                    final float ry = buf.getFloat(base + 32);
                    final float rz = buf.getFloat(base + 36);
                    PlyReader.storeNormalizedQuat(rotations, n * 4, rx, ry, rz, rw);

                    colors[n * 3] = (content[base + 40] & 0xFF) / 255.0f;
                    colors[n * 3 + 1] = (content[base + 41] & 0xFF) / 255.0f;
                    colors[n * 3 + 2] = (content[base + 42] & 0xFF) / 255.0f;
                    opacities[n] = (content[base + 43] & 0xFF) / 255.0f;
                } else {
                    final int bucketIndex;
                    if (local < fullSplats) {
                        bucketIndex = local / sec.bucketCapacity;
                    } else {
                        while (local >= partialEndLocal && partialBucketIdx + 1 < sec.partialBuckets) {
                            partialBucketIdx++;
                            partialStartLocal = partialEndLocal;
                            partialEndLocal += buf.getInt(sec.partialBase + partialBucketIdx * 4);
                        }
                        bucketIndex = sec.fullBuckets + partialBucketIdx;
                    }
                    final int bOff = sec.bucketsBase + bucketIndex * 12;
                    final int qx = buf.getShort(base) & 0xFFFF;
                    final int qy = buf.getShort(base + 2) & 0xFFFF;
                    final int qz = buf.getShort(base + 4) & 0xFFFF;
                    positions[n * 3] = (qx - sec.quantRange) * sec.posScaleFactor + buf.getFloat(bOff);
                    positions[n * 3 + 1] = (qy - sec.quantRange) * sec.posScaleFactor + buf.getFloat(bOff + 4);
                    positions[n * 3 + 2] = (qz - sec.quantRange) * sec.posScaleFactor + buf.getFloat(bOff + 8);

                    scales[n * 3] = Math.abs(halfToFloat(buf.getShort(base + 6) & 0xFFFF));
                    scales[n * 3 + 1] = Math.abs(halfToFloat(buf.getShort(base + 8) & 0xFFFF));
                    scales[n * 3 + 2] = Math.abs(halfToFloat(buf.getShort(base + 10) & 0xFFFF));

                    final float rw = halfToFloat(buf.getShort(base + 12) & 0xFFFF);
                    final float rx = halfToFloat(buf.getShort(base + 14) & 0xFFFF);
                    final float ry = halfToFloat(buf.getShort(base + 16) & 0xFFFF);
                    final float rz = halfToFloat(buf.getShort(base + 18) & 0xFFFF);
                    PlyReader.storeNormalizedQuat(rotations, n * 4, rx, ry, rz, rw);

                    colors[n * 3] = (content[base + 20] & 0xFF) / 255.0f;
                    colors[n * 3 + 1] = (content[base + 21] & 0xFF) / 255.0f;
                    colors[n * 3 + 2] = (content[base + 22] & 0xFF) / 255.0f;
                    opacities[n] = (content[base + 23] & 0xFF) / 255.0f;
                }

                if (sec.shDegree > 0) {
                    final int secRest = SplatCloud.restCoefficients(sec.shDegree);
                    final int shBase = base + (compressionMode == 0 ? 44 : 24);
                    for (int k = 0; k < secRest; k++) {
                        for (int c = 0; c < 3; c++) {
                            // .ksplat の SH は次数 (バンド) ごとに R, G, B の塊で並んでいる
                            final int srcIdx;
                            if (k < 3) {
                                srcIdx = c * 3 + k;
                            } else if (k < 8) {
                                srcIdx = 9 + c * 5 + (k - 3);
                            } else {
                                srcIdx = 24 + c * 7 + (k - 8);
                            }
                            final float val;
                            if (compressionMode == 0) {
                                val = buf.getFloat(shBase + srcIdx * 4);
                            } else if (compressionMode == 1) {
                                val = halfToFloat(buf.getShort(shBase + srcIdx * 2) & 0xFFFF);
                            } else {
                                val = ((content[shBase + srcIdx] & 0xFF) / 255.0f) * shRange + minSh;
                            }
                            sh[(n * rest + k) * 3 + c] = val;
                        }
                    }
                }
            }
            sectionStartGlobal = sectionEndGlobal;
        }

        SplatLoader.flipYZ(count, positions, rotations, maxShDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, maxShDegree, sh);
    }

    private static float halfToFloat(int h) {
        final int s = (h >>> 15) & 0x1;
        final int e = (h >>> 10) & 0x1F;
        final int m = h & 0x3FF;
        if (e == 0) {
            if (m == 0) return s != 0 ? -0.0f : 0.0f;
            final float v = Math.scalb((float) m, -24);
            return s != 0 ? -v : v;
        }
        if (e == 31) {
            return m == 0 ? (s != 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY) : Float.NaN;
        }
        final int bits = (s << 31) | ((e + 112) << 23) | (m << 13);
        return Float.intBitsToFloat(bits);
    }

    private static final class Section {
        final int splatCount;
        final int bucketCapacity;
        final int fullBuckets;
        final int partialBuckets;
        final float posScaleFactor;
        final int quantRange;
        final int shDegree;
        final int bytesPerSplat;
        final int partialBase;
        final int bucketsBase;
        final int splatDataBase;

        Section(int splatCount, int bucketCapacity, int fullBuckets, int partialBuckets,
                float posScaleFactor, int quantRange, int shDegree, int bytesPerSplat,
                int partialBase, int bucketsBase, int splatDataBase) {
            this.splatCount = splatCount;
            this.bucketCapacity = bucketCapacity;
            this.fullBuckets = fullBuckets;
            this.partialBuckets = partialBuckets;
            this.posScaleFactor = posScaleFactor;
            this.quantRange = quantRange;
            this.shDegree = shDegree;
            this.bytesPerSplat = bytesPerSplat;
            this.partialBase = partialBase;
            this.bucketsBase = bucketsBase;
            this.splatDataBase = splatDataBase;
        }
    }
}
