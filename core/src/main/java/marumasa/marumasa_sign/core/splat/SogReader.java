package marumasa.marumasa_sign.core.splat;

import marumasa.marumasa_sign.core.texture.WebpDecoder;
import marumasa.marumasa_sign.core.util.Json;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PlayCanvas の .sog (SOGS v1 / v2) を読み込む。
 * ZIP アーカイブ内の meta.json と可逆 WebP 画像を展開してガウス分布を復元する。
 */
final class SogReader {

    private static final int MAX_ENTRIES = 64;
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_SPLATS_LIMIT = 16_777_216;
    private static final float SQRT2 = 1.4142135623730951f;

    private SogReader() {
    }

    /**
     * 先頭のバイト列と拡張子から .sog かどうかを判定する。
     * ZIP のローカルファイルヘッダを持ち、拡張子が .sog であるか、最初のエントリ名が meta.json または *.webp であることを確かめる。
     */
    static boolean isSog(byte[] head, int length, String ext) {
        if (head == null || length < 4) return false;
        final int limit = Math.min(length, head.length);
        if (head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) return false;
        if (".sog".equals(ext)) return true;
        if (limit < 30) return false;
        final int nameLen = (head[26] & 0xFF) | ((head[27] & 0xFF) << 8);
        if (nameLen <= 0 || 30 + nameLen > limit) return false;
        final String entryName = baseName(new String(head, 30, nameLen, StandardCharsets.UTF_8));
        return "meta.json".equals(entryName) || entryName.endsWith(".webp");
    }

    static SplatCloud load(byte[] content) throws IOException {
        if (content == null || content.length < 30) throw new IOException("Invalid SOG file");
        final Map<String, byte[]> entries = readZip(content);
        final byte[] metaBytes = entries.get("meta.json");
        if (metaBytes == null) throw new IOException("SOG archive is missing meta.json");

        final Object parsed = Json.parse(new String(metaBytes, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException("Invalid SOG meta.json");
        final Map<String, Object> meta = Json.object(parsed);

        final int version = Json.integer(meta, "version", 1);
        if (version == 2) {
            return loadV2(meta, entries);
        } else if (version == 1 && !meta.containsKey("version")) {
            return loadV1(meta, entries);
        } else {
            throw new IOException("Unsupported SOG version: " + version);
        }
    }

    private static SplatCloud loadV2(Map<String, Object> meta, Map<String, byte[]> entries) throws IOException {
        final int total = Json.integer(meta, "count", -1);
        if (total <= 0 || total > MAX_SPLATS_LIMIT) {
            throw new IOException("Invalid SOG count: " + total);
        }

        final Map<String, Object> meansObj = Json.object(meta, "means");
        final float[] meanMins = requireFiniteArray(meansObj.get("mins"), 3, "means.mins");
        final float[] meanMaxs = requireFiniteArray(meansObj.get("maxs"), 3, "means.maxs");
        final List<Object> meanFiles = Json.array(meansObj, "files");
        if (meanFiles.size() < 2) throw new IOException("Missing SOG means files");

        final Map<String, Object> scalesObj = Json.object(meta, "scales");
        final float[] scaleCodebook = requireFiniteArray(scalesObj.get("codebook"), 256, "scales.codebook");
        final List<Object> scaleFiles = Json.array(scalesObj, "files");
        if (scaleFiles.isEmpty()) throw new IOException("Missing SOG scales file");

        final Map<String, Object> quatsObj = Json.object(meta, "quats");
        final List<Object> quatFiles = Json.array(quatsObj, "files");
        if (quatFiles.isEmpty()) throw new IOException("Missing SOG quats file");

        final Map<String, Object> sh0Obj = Json.object(meta, "sh0");
        final float[] sh0Codebook = requireFiniteArray(sh0Obj.get("codebook"), 256, "sh0.codebook");
        final List<Object> sh0Files = Json.array(sh0Obj, "files");
        if (sh0Files.isEmpty()) throw new IOException("Missing SOG sh0 file");

        final ImageData scalesImg = decodeWebp(entries, String.valueOf(scaleFiles.get(0)), total);
        final ImageData sh0Img = decodeWebp(entries, String.valueOf(sh0Files.get(0)), total);

        // 1 パス目: scales と sh0 から importance を計算する
        final byte[] sRgba = scalesImg.rgba;
        final byte[] c0Rgba = sh0Img.rgba;
        float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int o4 = i * 4;
            final float sx = (float) Math.exp(scaleCodebook[sRgba[o4] & 0xFF]);
            final float sy = (float) Math.exp(scaleCodebook[sRgba[o4 + 1] & 0xFF]);
            final float sz = (float) Math.exp(scaleCodebook[sRgba[o4 + 2] & 0xFF]);
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Scale overflow in SOG");
            }
            final float opacity = (c0Rgba[o4 + 3] & 0xFF) / 255.0f;
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.selectTop(importance, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        final ImageData meansLImg = decodeWebp(entries, String.valueOf(meanFiles.get(0)), total);
        final ImageData meansUImg = decodeWebp(entries, String.valueOf(meanFiles.get(1)), total);
        final ImageData quatsImg = decodeWebp(entries, String.valueOf(quatFiles.get(0)), total);

        final byte[] mL = meansLImg.rgba;
        final byte[] mU = meansUImg.rgba;
        final byte[] qRgba = quatsImg.rgba;

        int shDegree = 0;
        int rest = 0;
        int paletteCount = 0;
        float[] shCodebook = null;
        ImageData centroidsImg = null;
        ImageData labelsImg = null;

        if (meta.get("shN") instanceof Map) {
            final Map<String, Object> shNObj = Json.object(meta, "shN");
            final int bands = Json.integer(shNObj, "bands", 0);
            if (bands < 1 || bands > 3) throw new IOException("Invalid SOG shN.bands: " + bands);
            shDegree = bands;
            rest = SplatCloud.restCoefficients(shDegree);
            paletteCount = Json.integer(shNObj, "count", 65536);
            shCodebook = requireFiniteArray(shNObj.get("codebook"), 256, "shN.codebook");
            final List<Object> shNFiles = Json.array(shNObj, "files");
            if (shNFiles.size() < 2) throw new IOException("Missing SOG shN files");
            centroidsImg = decodeWebp(entries, String.valueOf(shNFiles.get(0)), 1);
            labelsImg = decodeWebp(entries, String.valueOf(shNFiles.get(1)), total);
            if (centroidsImg.width < 64 * rest) {
                throw new IOException("Invalid SOG shN centroids width: " + centroidsImg.width);
            }
        }

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = shDegree > 0 ? new float[count * rest * 3] : new float[0];

        // 2 パス目: 選ばれたガウス分布だけを展開する
        for (int n = 0; n < count; n++) {
            final int i = selected[n];
            final int o4 = i * 4;

            for (int c = 0; c < 3; c++) {
                final int u16 = (mL[o4 + c] & 0xFF) | ((mU[o4 + c] & 0xFF) << 8);
                final float v = meanMins[c] + (meanMaxs[c] - meanMins[c]) * (u16 / 65535.0f);
                final float pos = (float) Math.copySign(Math.exp(Math.abs(v)) - 1.0, v);
                if (!Float.isFinite(pos)) throw new IOException("Non-finite position in SOG");
                positions[n * 3 + c] = pos;

                scales[n * 3 + c] = (float) Math.exp(scaleCodebook[sRgba[o4 + c] & 0xFF]);
                colors[n * 3 + c] = 0.5f + SplatCloud.SH_C0 * sh0Codebook[c0Rgba[o4 + c] & 0xFF];
            }

            opacities[n] = (c0Rgba[o4 + 3] & 0xFF) / 255.0f;
            unpackQuat(qRgba, o4, rotations, n * 4);

            if (shDegree > 0 && centroidsImg != null && labelsImg != null && shCodebook != null) {
                final int label = (labelsImg.rgba[o4] & 0xFF) | ((labelsImg.rgba[o4 + 1] & 0xFF) << 8);
                if (label < paletteCount) {
                    final int cy = label >>> 6;
                    final int cxBase = (label & 63) * rest;
                    if (cy < centroidsImg.height && cxBase + rest <= centroidsImg.width) {
                        for (int k = 0; k < rest; k++) {
                            final int cOff = (cy * centroidsImg.width + cxBase + k) * 4;
                            final int dst = (n * rest + k) * 3;
                            sh[dst] = shCodebook[centroidsImg.rgba[cOff] & 0xFF];
                            sh[dst + 1] = shCodebook[centroidsImg.rgba[cOff + 1] & 0xFF];
                            sh[dst + 2] = shCodebook[centroidsImg.rgba[cOff + 2] & 0xFF];
                        }
                    }
                }
            }
        }

        SplatLoader.flipYZ(count, positions, rotations, shDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
    }

    private static SplatCloud loadV1(Map<String, Object> meta, Map<String, byte[]> entries) throws IOException {
        final Map<String, Object> meansObj = Json.object(meta, "means");
        final List<Object> shape = Json.array(meansObj, "shape");
        if (shape.isEmpty() || !(shape.get(0) instanceof Number)) {
            throw new IOException("Invalid SOG v1 means.shape");
        }
        final int total = ((Number) shape.get(0)).intValue();
        if (total <= 0 || total > MAX_SPLATS_LIMIT) {
            throw new IOException("Invalid SOG v1 count: " + total);
        }

        final float[] meanMins = requireFiniteArray(meansObj.get("mins"), 3, "means.mins");
        final float[] meanMaxs = requireFiniteArray(meansObj.get("maxs"), 3, "means.maxs");
        final List<Object> meanFiles = Json.array(meansObj, "files");
        if (meanFiles.size() < 2) throw new IOException("Missing SOG v1 means files");

        final Map<String, Object> scalesObj = Json.object(meta, "scales");
        final float[] scaleMins = requireFiniteArray(scalesObj.get("mins"), 3, "scales.mins");
        final float[] scaleMaxs = requireFiniteArray(scalesObj.get("maxs"), 3, "scales.maxs");
        final List<Object> scaleFiles = Json.array(scalesObj, "files");
        if (scaleFiles.isEmpty()) throw new IOException("Missing SOG v1 scales file");

        final Map<String, Object> quatsObj = Json.object(meta, "quats");
        final List<Object> quatFiles = Json.array(quatsObj, "files");
        if (quatFiles.isEmpty()) throw new IOException("Missing SOG v1 quats file");

        final Map<String, Object> sh0Obj = Json.object(meta, "sh0");
        final float[] sh0Mins = requireFiniteArray(sh0Obj.get("mins"), 4, "sh0.mins");
        final float[] sh0Maxs = requireFiniteArray(sh0Obj.get("maxs"), 4, "sh0.maxs");
        final List<Object> sh0Files = Json.array(sh0Obj, "files");
        if (sh0Files.isEmpty()) throw new IOException("Missing SOG v1 sh0 file");

        final ImageData scalesImg = decodeWebp(entries, String.valueOf(scaleFiles.get(0)), total);
        final ImageData sh0Img = decodeWebp(entries, String.valueOf(sh0Files.get(0)), total);

        final byte[] sRgba = scalesImg.rgba;
        final byte[] c0Rgba = sh0Img.rgba;
        float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int o4 = i * 4;
            final float sx = (float) Math.exp(lerp(scaleMins[0], scaleMaxs[0], (sRgba[o4] & 0xFF) / 255.0f));
            final float sy = (float) Math.exp(lerp(scaleMins[1], scaleMaxs[1], (sRgba[o4 + 1] & 0xFF) / 255.0f));
            final float sz = (float) Math.exp(lerp(scaleMins[2], scaleMaxs[2], (sRgba[o4 + 2] & 0xFF) / 255.0f));
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Scale overflow in SOG v1");
            }
            final float rawOp = lerp(sh0Mins[3], sh0Maxs[3], (c0Rgba[o4 + 3] & 0xFF) / 255.0f);
            final float opacity = (float) (1.0 / (1.0 + Math.exp(-rawOp)));
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.selectTop(importance, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        final ImageData meansLImg = decodeWebp(entries, String.valueOf(meanFiles.get(0)), total);
        final ImageData meansUImg = decodeWebp(entries, String.valueOf(meanFiles.get(1)), total);
        final ImageData quatsImg = decodeWebp(entries, String.valueOf(quatFiles.get(0)), total);

        final byte[] mL = meansLImg.rgba;
        final byte[] mU = meansUImg.rgba;
        final byte[] qRgba = quatsImg.rgba;

        int shDegree = 0;
        int rest = 0;
        float shMin = 0f;
        float shMax = 0f;
        ImageData centroidsImg = null;
        ImageData labelsImg = null;

        if (meta.get("shN") instanceof Map) {
            final Map<String, Object> shNObj = Json.object(meta, "shN");
            shMin = Json.number(shNObj, "mins", Float.NaN);
            shMax = Json.number(shNObj, "maxs", Float.NaN);
            if (!Float.isFinite(shMin) || !Float.isFinite(shMax)) {
                throw new IOException("Invalid SOG v1 shN mins/maxs");
            }
            final List<Object> shNFiles = Json.array(shNObj, "files");
            if (shNFiles.size() < 2) throw new IOException("Missing SOG v1 shN files");
            centroidsImg = decodeWebp(entries, String.valueOf(shNFiles.get(0)), 1);
            labelsImg = decodeWebp(entries, String.valueOf(shNFiles.get(1)), total);
            if (centroidsImg.width == 192) shDegree = 1;
            else if (centroidsImg.width == 512) shDegree = 2;
            else if (centroidsImg.width == 960) shDegree = 3;
            else throw new IOException("Invalid SOG v1 centroids width: " + centroidsImg.width);
            rest = SplatCloud.restCoefficients(shDegree);
        }

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = shDegree > 0 ? new float[count * rest * 3] : new float[0];

        for (int n = 0; n < count; n++) {
            final int i = selected[n];
            final int o4 = i * 4;

            for (int c = 0; c < 3; c++) {
                final int u16 = (mL[o4 + c] & 0xFF) | ((mU[o4 + c] & 0xFF) << 8);
                final float v = meanMins[c] + (meanMaxs[c] - meanMins[c]) * (u16 / 65535.0f);
                final float pos = (float) Math.copySign(Math.exp(Math.abs(v)) - 1.0, v);
                if (!Float.isFinite(pos)) throw new IOException("Non-finite position in SOG v1");
                positions[n * 3 + c] = pos;

                scales[n * 3 + c] = (float) Math.exp(lerp(scaleMins[c], scaleMaxs[c], (sRgba[o4 + c] & 0xFF) / 255.0f));
                final float fDc = lerp(sh0Mins[c], sh0Maxs[c], (c0Rgba[o4 + c] & 0xFF) / 255.0f);
                colors[n * 3 + c] = 0.5f + SplatCloud.SH_C0 * fDc;
            }

            final float rawOp = lerp(sh0Mins[3], sh0Maxs[3], (c0Rgba[o4 + 3] & 0xFF) / 255.0f);
            opacities[n] = (float) (1.0 / (1.0 + Math.exp(-rawOp)));
            unpackQuat(qRgba, o4, rotations, n * 4);

            if (shDegree > 0 && centroidsImg != null && labelsImg != null) {
                final int label = (labelsImg.rgba[o4] & 0xFF) | ((labelsImg.rgba[o4 + 1] & 0xFF) << 8);
                final int cy = label >>> 6;
                final int cxBase = (label & 63) * rest;
                if (cy < centroidsImg.height && cxBase + rest <= centroidsImg.width) {
                    for (int k = 0; k < rest; k++) {
                        final int cOff = (cy * centroidsImg.width + cxBase + k) * 4;
                        final int dst = (n * rest + k) * 3;
                        sh[dst] = lerp(shMin, shMax, (centroidsImg.rgba[cOff] & 0xFF) / 255.0f);
                        sh[dst + 1] = lerp(shMin, shMax, (centroidsImg.rgba[cOff + 1] & 0xFF) / 255.0f);
                        sh[dst + 2] = lerp(shMin, shMax, (centroidsImg.rgba[cOff + 2] & 0xFF) / 255.0f);
                    }
                }
            }
        }

        SplatLoader.flipYZ(count, positions, rotations, shDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
    }

    private static void unpackQuat(byte[] qRgba, int o4, float[] rotations, int dstOff) throws IOException {
        final int tag = qRgba[o4 + 3] & 0xFF;
        if (tag < 252 || tag > 255) {
            throw new IOException("Invalid SOG quaternion tag: " + tag);
        }
        final float a = (((qRgba[o4] & 0xFF) / 255.0f) * 2.0f - 1.0f) / SQRT2;
        final float b = (((qRgba[o4 + 1] & 0xFF) / 255.0f) * 2.0f - 1.0f) / SQRT2;
        final float c = (((qRgba[o4 + 2] & 0xFF) / 255.0f) * 2.0f - 1.0f) / SQRT2;
        final float d = (float) Math.sqrt(Math.max(0.0f, 1.0f - (a * a + b * b + c * c)));
        switch (tag - 252) {
            case 0:
                PlyReader.storeNormalizedQuat(rotations, dstOff, a, b, c, d);
                break;
            case 1:
                PlyReader.storeNormalizedQuat(rotations, dstOff, d, b, c, a);
                break;
            case 2:
                PlyReader.storeNormalizedQuat(rotations, dstOff, b, d, c, a);
                break;
            default:
                PlyReader.storeNormalizedQuat(rotations, dstOff, b, c, d, a);
                break;
        }
    }

    private static float[] requireFiniteArray(Object obj, int minLen, String label) throws IOException {
        final float[] arr = Json.numbers(obj);
        if (arr.length < minLen) {
            throw new IOException("SOG array too short for " + label + ": " + arr.length);
        }
        for (int i = 0; i < arr.length; i++) {
            if (!Float.isFinite(arr[i])) throw new IOException("Non-finite value in SOG " + label);
        }
        return arr;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class ImageData {
        final int width;
        final int height;
        final byte[] rgba;

        ImageData(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    private static ImageData decodeWebp(Map<String, byte[]> entries, String name, int minPixels) throws IOException {
        byte[] data = entries.get(name);
        if (data == null) data = entries.get(baseName(name));
        if (data == null) throw new IOException("Missing SOG image entry: " + name);
        final BufferedImage img = WebpDecoder.decode(data);
        final int w = img.getWidth();
        final int h = img.getHeight();
        if ((long) w * h < minPixels) {
            throw new IOException("SOG image " + name + " has too few pixels: " + (w * h) + " < " + minPixels);
        }
        final byte[] rgba = new byte[w * h * 4];
        img.getRaster().getDataElements(0, 0, w, h, rgba);
        return new ImageData(w, h, rgba);
    }

    static Map<String, byte[]> readZip(byte[] content) throws IOException {
        final int eocd = findEocd(content);
        if (eocd < 0) {
            throw new IOException("Truncated or invalid SOG ZIP archive");
        }
        final ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        final int totalEntries = buf.getShort(eocd + 10) & 0xFFFF;
        final long cdSize = buf.getInt(eocd + 12) & 0xFFFFFFFFL;
        final long cdOffset = buf.getInt(eocd + 16) & 0xFFFFFFFFL;

        if (totalEntries > MAX_ENTRIES) {
            throw new IOException("Too many entries in SOG archive: " + totalEntries);
        }
        if (cdOffset < 0 || cdOffset + cdSize > eocd) {
            throw new IOException("Corrupted ZIP Central Directory offsets");
        }

        final Map<String, byte[]> map = new HashMap<String, byte[]>();
        long totalBytes = 0;
        int pos = (int) cdOffset;

        for (int i = 0; i < totalEntries; i++) {
            if (pos + 46 > content.length) {
                throw new IOException("Truncated ZIP Central Directory header");
            }
            final int sig = buf.getInt(pos);
            if (sig != 0x02014B50) {
                throw new IOException("Invalid ZIP Central Directory signature at " + pos);
            }
            final int method = buf.getShort(pos + 10) & 0xFFFF;
            final long compSize = buf.getInt(pos + 20) & 0xFFFFFFFFL;
            final long uncompSize = buf.getInt(pos + 24) & 0xFFFFFFFFL;
            final int nameLen = buf.getShort(pos + 28) & 0xFFFF;
            final int extraLen = buf.getShort(pos + 30) & 0xFFFF;
            final int commentLen = buf.getShort(pos + 32) & 0xFFFF;
            final long localOffset = buf.getInt(pos + 42) & 0xFFFFFFFFL;

            if (uncompSize > MAX_ENTRY_BYTES) {
                throw new IOException("ZIP entry exceeds max entry size: " + uncompSize);
            }
            totalBytes += uncompSize;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("SOG archive exceeds uncompressed size limits");
            }

            final int nameStart = pos + 46;
            if (nameStart + nameLen > content.length) {
                throw new IOException("Truncated entry name in ZIP Central Directory");
            }
            final String rawName = new String(content, nameStart, nameLen, StandardCharsets.UTF_8);
            if (rawName.contains("..")) {
                throw new IOException("Invalid path in SOG archive: " + rawName);
            }

            if (localOffset + 30 > content.length || localOffset < 0) {
                throw new IOException("Local header offset out of bounds: " + localOffset);
            }
            final int localSig = buf.getInt((int) localOffset);
            if (localSig != 0x04034B50) {
                throw new IOException("Invalid local header signature at " + localOffset);
            }
            final int localNameLen = buf.getShort((int) localOffset + 26) & 0xFFFF;
            final int localExtraLen = buf.getShort((int) localOffset + 28) & 0xFFFF;
            final long dataStart = localOffset + 30 + localNameLen + localExtraLen;
            if (dataStart < 0 || dataStart + compSize > content.length) {
                throw new IOException("Compressed data exceeds ZIP length");
            }

            final byte[] uncompressed = new byte[(int) uncompSize];
            if (method == 0) {
                if (compSize != uncompSize) {
                    throw new IOException("Stored size mismatch in ZIP entry: " + rawName);
                }
                System.arraycopy(content, (int) dataStart, uncompressed, 0, (int) uncompSize);
            } else if (method == 8) {
                final Inflater inflater = new Inflater(true);
                inflater.setInput(content, (int) dataStart, (int) compSize);
                try {
                    int decompressed = 0;
                    while (decompressed < uncompressed.length && !inflater.finished()) {
                        final int count = inflater.inflate(uncompressed, decompressed, uncompressed.length - decompressed);
                        if (count == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) {
                                throw new IOException("Truncated or invalid deflate stream in ZIP entry: " + rawName);
                            }
                            break;
                        }
                        decompressed += count;
                    }
                    if (decompressed != uncompSize || !inflater.finished()) {
                        throw new IOException("Decompressed size mismatch in ZIP entry: " + rawName);
                    }
                } catch (DataFormatException e) {
                    throw new IOException("Deflate decompression error in ZIP entry " + rawName + ": " + e, e);
                } finally {
                    inflater.end();
                }
            } else {
                throw new IOException("Unsupported ZIP compression method " + method + " in entry: " + rawName);
            }

            map.put(rawName, uncompressed);
            map.put(baseName(rawName), uncompressed);

            pos = nameStart + nameLen + extraLen + commentLen;
        }

        if (map.isEmpty()) throw new IOException("Empty or invalid SOG ZIP archive");
        return map;
    }

    private static int findEocd(byte[] content) {
        if (content.length < 22) return -1;
        final int minPos = Math.max(0, content.length - 22 - 65535);
        for (int pos = content.length - 22; pos >= minPos; pos--) {
            if (content[pos] == 'P' && content[pos + 1] == 'K' && content[pos + 2] == 5 && content[pos + 3] == 6) {
                final int commentLen = (content[pos + 20] & 0xFF) | ((content[pos + 21] & 0xFF) << 8);
                if (pos + 22 + commentLen == content.length) return pos;
            }
        }
        return -1;
    }

    private static String baseName(String path) {
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        final String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.toLowerCase(Locale.ROOT);
    }
}
