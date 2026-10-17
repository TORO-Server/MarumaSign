package marumasa.marumasa_sign.core.splat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ガウシアンスプラッティングの .ply (標準の 3DGS 形式と PlayCanvas の圧縮形式) を読み込む。
 */
final class PlyReader {

    // ヘッダの長さの上限 (64 KB)
    private static final int MAX_HEADER_SIZE = 65536;
    // 要素の数の上限
    private static final int MAX_ELEMENTS = 50_000_000;
    private static final float SQRT2 = 1.4142135623730951f;

    private PlyReader() {
    }

    /**
     * 先頭のバイト列が 3DGS の PLY かどうかを判定する。
     * "ply" で始まり、ヘッダの中に f_dc_0 か packed_position のプロパティがあることを確かめる。
     */
    static boolean isPly(byte[] head, int length) {
        if (head == null || length < 16) return false;
        final int limit = Math.min(length, head.length);
        int pos = 0;
        if (limit >= 4 && head[0] == 'p' && head[1] == 'l' && head[2] == 'y' && head[3] == '\n') {
            pos = 4;
        } else if (limit >= 5 && head[0] == 'p' && head[1] == 'l' && head[2] == 'y' && head[3] == '\r' && head[4] == '\n') {
            pos = 5;
        } else {
            return false;
        }
        final int scanLimit = Math.min(limit, MAX_HEADER_SIZE);
        while (pos < scanLimit) {
            int lineEnd = pos;
            while (lineEnd < scanLimit && head[lineEnd] != '\n') lineEnd++;
            int trimEnd = lineEnd;
            if (trimEnd > pos && head[trimEnd - 1] == '\r') trimEnd--;
            final String line = new String(head, pos, trimEnd - pos, StandardCharsets.US_ASCII).trim();
            if ("end_header".equals(line)) break;
            if (line.startsWith("property ") && (line.endsWith(" f_dc_0") || line.endsWith(" packed_position"))) {
                return true;
            }
            pos = lineEnd + 1;
        }
        return false;
    }

    static SplatCloud load(byte[] content) throws IOException {
        if (content == null || content.length < 16) throw new IOException("Invalid PLY file");
        final Header header = parseHeader(content);
        final Element vertex = header.element("vertex");
        if (vertex == null || vertex.count <= 0) throw new IOException("PLY has no vertex element");

        // PlayCanvas / SuperSplat の圧縮 PLY かどうか
        if (vertex.property("packed_position") != null) {
            return loadCompressed(content, header, vertex);
        }
        if (header.format == Format.ASCII) {
            return loadAsciiStandard(content, header, vertex);
        }
        return loadBinaryStandard(content, header, vertex);
    }

    private enum Format {
        ASCII,
        BINARY_LE,
        BINARY_BE
    }

    private enum PropType {
        INT8(1),
        UINT8(1),
        INT16(2),
        UINT16(2),
        INT32(4),
        UINT32(4),
        FLOAT32(4),
        FLOAT64(8);

        final int size;

        PropType(int size) {
            this.size = size;
        }

        static PropType parse(String name) throws IOException {
            switch (name) {
                case "char":
                case "int8":
                    return INT8;
                case "uchar":
                case "uint8":
                    return UINT8;
                case "short":
                case "int16":
                    return INT16;
                case "ushort":
                case "uint16":
                    return UINT16;
                case "int":
                case "int32":
                    return INT32;
                case "uint":
                case "uint32":
                    return UINT32;
                case "float":
                case "float32":
                    return FLOAT32;
                case "double":
                case "float64":
                    return FLOAT64;
                default:
                    throw new IOException("Unsupported PLY property type: " + name);
            }
        }
    }

    private static final class Property {
        final String name;
        final PropType type;
        final int offset;
        final int index;

        Property(String name, PropType type, int offset, int index) {
            this.name = name;
            this.type = type;
            this.offset = offset;
            this.index = index;
        }
    }

    private static final class Element {
        final String name;
        final int count;
        final List<Property> properties = new ArrayList<Property>();
        final Map<String, Property> byName = new HashMap<String, Property>();
        int stride;
        long byteOffset;

        Element(String name, int count) {
            this.name = name;
            this.count = count;
        }

        void addProperty(String propName, PropType type) throws IOException {
            if (byName.containsKey(propName)) throw new IOException("Duplicate PLY property: " + propName);
            final Property prop = new Property(propName, type, stride, properties.size());
            properties.add(prop);
            byName.put(propName, prop);
            stride += type.size;
        }

        Property property(String propName) {
            return byName.get(propName);
        }

        Property require(String propName) throws IOException {
            final Property p = byName.get(propName);
            if (p == null) throw new IOException("Missing PLY property '" + propName + "' in element '" + name + "'");
            return p;
        }
    }

    private static final class Header {
        final Format format;
        final int dataStart;
        final List<Element> elements;

        Header(Format format, int dataStart, List<Element> elements) {
            this.format = format;
            this.dataStart = dataStart;
            this.elements = elements;
        }

        Element element(String name) {
            for (Element e : elements) {
                if (e.name.equals(name)) return e;
            }
            return null;
        }
    }

    private static Header parseHeader(byte[] content) throws IOException {
        final int limit = Math.min(content.length, MAX_HEADER_SIZE);
        int pos = 0;
        if (limit >= 4 && content[0] == 'p' && content[1] == 'l' && content[2] == 'y' && content[3] == '\n') {
            pos = 4;
        } else if (limit >= 5 && content[0] == 'p' && content[1] == 'l' && content[2] == 'y' && content[3] == '\r' && content[4] == '\n') {
            pos = 5;
        } else {
            throw new IOException("Not a PLY file");
        }

        Format format = null;
        final List<Element> elements = new ArrayList<Element>();
        Element current = null;
        int dataStart = -1;

        while (pos < limit) {
            int lineEnd = pos;
            while (lineEnd < limit && content[lineEnd] != '\n') lineEnd++;
            if (lineEnd >= limit && content[lineEnd - 1] != '\n') {
                throw new IOException("Unterminated PLY header");
            }
            int trimEnd = lineEnd;
            if (trimEnd > pos && content[trimEnd - 1] == '\r') trimEnd--;
            final String line = new String(content, pos, trimEnd - pos, StandardCharsets.US_ASCII).trim();
            pos = lineEnd + 1;

            if (line.isEmpty() || line.startsWith("comment ") || "comment".equals(line)
                    || line.startsWith("obj_info ") || "obj_info".equals(line)) {
                continue;
            }
            if ("end_header".equals(line)) {
                dataStart = pos;
                break;
            }
            final String[] tokens = line.split("\\s+");
            if ("format".equals(tokens[0])) {
                if (tokens.length < 3 || !"1.0".equals(tokens[2])) {
                    throw new IOException("Unsupported PLY format: " + line);
                }
                if ("binary_little_endian".equals(tokens[1])) format = Format.BINARY_LE;
                else if ("binary_big_endian".equals(tokens[1])) format = Format.BINARY_BE;
                else if ("ascii".equals(tokens[1])) format = Format.ASCII;
                else throw new IOException("Unknown PLY format: " + tokens[1]);
            } else if ("element".equals(tokens[0])) {
                if (tokens.length != 3) throw new IOException("Invalid PLY element line: " + line);
                final int count;
                try {
                    count = Integer.parseInt(tokens[2]);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid PLY element count: " + tokens[2]);
                }
                if (count < 0 || count > MAX_ELEMENTS) {
                    throw new IOException("PLY element count out of bounds: " + count);
                }
                current = new Element(tokens[1], count);
                elements.add(current);
            } else if ("property".equals(tokens[0])) {
                if (current == null || tokens.length < 3) throw new IOException("Invalid PLY property line: " + line);
                if ("list".equals(tokens[1])) {
                    throw new IOException("PLY list properties are not supported");
                }
                if (tokens.length != 3) throw new IOException("Invalid PLY property line: " + line);
                current.addProperty(tokens[2], PropType.parse(tokens[1]));
            } else {
                throw new IOException("Unknown PLY header directive: " + line);
            }
        }

        if (dataStart < 0 || format == null) throw new IOException("Incomplete PLY header");

        if (format != Format.ASCII) {
            long offset = dataStart;
            for (Element e : elements) {
                e.byteOffset = offset;
                final long bytes = (long) e.count * e.stride;
                if (bytes < 0 || offset + bytes > content.length) {
                    throw new IOException("Truncated binary PLY data for element " + e.name);
                }
                offset += bytes;
            }
        }
        return new Header(format, dataStart, elements);
    }

    // 標準のバイナリ PLY を 2 パスで読み込む
    private static SplatCloud loadBinaryStandard(byte[] content, Header header, Element vertex) throws IOException {
        final Property px = vertex.require("x");
        final Property py = vertex.require("y");
        final Property pz = vertex.require("z");
        final Property fdc0 = vertex.require("f_dc_0");
        final Property fdc1 = vertex.require("f_dc_1");
        final Property fdc2 = vertex.require("f_dc_2");
        final Property popacity = vertex.require("opacity");
        final Property ps0 = vertex.require("scale_0");
        final Property ps1 = vertex.require("scale_1");
        final Property ps2 = vertex.require("scale_2");
        final Property prot0 = vertex.require("rot_0");
        final Property prot1 = vertex.require("rot_1");
        final Property prot2 = vertex.require("rot_2");
        final Property prot3 = vertex.require("rot_3");

        final List<Property> fRestProps = findRestProperties(vertex);
        final int shCount = fRestProps.size();
        final int cPerChannel = shCount / 3;
        final int shDegree = cPerChannel >= 15 ? 3 : cPerChannel >= 8 ? 2 : cPerChannel >= 3 ? 1 : 0;
        final int rest = SplatCloud.restCoefficients(shDegree);

        final ByteBuffer buf = ByteBuffer.wrap(content).order(
                header.format == Format.BINARY_LE ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        final int total = vertex.count;
        final int stride = vertex.stride;
        final long baseOffset = vertex.byteOffset;

        // 1 パス目: 値の妥当性を確かめながら位置と importance を計算する
        final float[] rawPositions = new float[total * 3];
        float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int row = (int) (baseOffset + (long) i * stride);
            final float x = readFloat(buf, row, px);
            final float y = readFloat(buf, row, py);
            final float z = readFloat(buf, row, pz);
            final float dc0 = readFloat(buf, row, fdc0);
            final float dc1 = readFloat(buf, row, fdc1);
            final float dc2 = readFloat(buf, row, fdc2);
            final float rawOp = readFloat(buf, row, popacity);
            final float rawS0 = readFloat(buf, row, ps0);
            final float rawS1 = readFloat(buf, row, ps1);
            final float rawS2 = readFloat(buf, row, ps2);
            final float r0 = readFloat(buf, row, prot0);
            final float r1 = readFloat(buf, row, prot1);
            final float r2 = readFloat(buf, row, prot2);
            final float r3 = readFloat(buf, row, prot3);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(dc0) || !Float.isFinite(dc1) || !Float.isFinite(dc2)
                    || !Float.isFinite(rawOp) || !Float.isFinite(rawS0) || !Float.isFinite(rawS1) || !Float.isFinite(rawS2)
                    || !Float.isFinite(r0) || !Float.isFinite(r1) || !Float.isFinite(r2) || !Float.isFinite(r3)) {
                throw new IOException("Non-finite float in PLY vertex " + i);
            }
            rawPositions[i * 3] = x;
            rawPositions[i * 3 + 1] = y;
            rawPositions[i * 3 + 2] = z;
            final float opacity = sigmoid(rawOp);
            final float sx = (float) Math.exp(rawS0);
            final float sy = (float) Math.exp(rawS1);
            final float sz = (float) Math.exp(rawS2);
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Scale overflow in PLY vertex " + i);
            }
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.select(importance, rawPositions, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = shDegree > 0 ? new float[count * rest * 3] : new float[0];

        // 2 パス目: 選ばれた頂点だけを展開する
        for (int n = 0; n < count; n++) {
            final int i = selected[n];
            final int row = (int) (baseOffset + (long) i * stride);

            positions[n * 3] = rawPositions[i * 3];
            positions[n * 3 + 1] = rawPositions[i * 3 + 1];
            positions[n * 3 + 2] = rawPositions[i * 3 + 2];

            colors[n * 3] = 0.5f + SplatCloud.SH_C0 * readFloat(buf, row, fdc0);
            colors[n * 3 + 1] = 0.5f + SplatCloud.SH_C0 * readFloat(buf, row, fdc1);
            colors[n * 3 + 2] = 0.5f + SplatCloud.SH_C0 * readFloat(buf, row, fdc2);

            opacities[n] = sigmoid(readFloat(buf, row, popacity));

            scales[n * 3] = (float) Math.exp(readFloat(buf, row, ps0));
            scales[n * 3 + 1] = (float) Math.exp(readFloat(buf, row, ps1));
            scales[n * 3 + 2] = (float) Math.exp(readFloat(buf, row, ps2));

            final float w = readFloat(buf, row, prot0);
            final float x = readFloat(buf, row, prot1);
            final float y = readFloat(buf, row, prot2);
            final float z = readFloat(buf, row, prot3);
            storeNormalizedQuat(rotations, n * 4, x, y, z, w);

            if (shDegree > 0) {
                for (int c = 0; c < 3; c++) {
                    for (int k = 0; k < rest; k++) {
                        final float val = readFloat(buf, row, fRestProps.get(c * cPerChannel + k));
                        if (!Float.isFinite(val)) throw new IOException("Non-finite SH value in PLY");
                        sh[(n * rest + k) * 3 + c] = val;
                    }
                }
            }
        }

        SplatLoader.flipYZ(count, positions, rotations, shDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
    }

    // 標準の ASCII PLY を 2 パスで読み込む
    private static SplatCloud loadAsciiStandard(byte[] content, Header header, Element vertex) throws IOException {
        // vertex より前にある要素の行を読み飛ばす
        int cursor = header.dataStart;
        for (Element e : header.elements) {
            if (e == vertex) break;
            final int propsPerRow = e.properties.size();
            for (int r = 0; r < e.count; r++) {
                for (int p = 0; p < propsPerRow; p++) {
                    cursor = skipAsciiToken(content, cursor);
                }
            }
        }

        final int px = vertex.require("x").index;
        final int py = vertex.require("y").index;
        final int pz = vertex.require("z").index;
        final int fdc0 = vertex.require("f_dc_0").index;
        final int fdc1 = vertex.require("f_dc_1").index;
        final int fdc2 = vertex.require("f_dc_2").index;
        final int popacity = vertex.require("opacity").index;
        final int ps0 = vertex.require("scale_0").index;
        final int ps1 = vertex.require("scale_1").index;
        final int ps2 = vertex.require("scale_2").index;
        final int prot0 = vertex.require("rot_0").index;
        final int prot1 = vertex.require("rot_1").index;
        final int prot2 = vertex.require("rot_2").index;
        final int prot3 = vertex.require("rot_3").index;

        final List<Property> fRestProps = findRestProperties(vertex);
        final int shCount = fRestProps.size();
        final int cPerChannel = shCount / 3;
        final int shDegree = cPerChannel >= 15 ? 3 : cPerChannel >= 8 ? 2 : cPerChannel >= 3 ? 1 : 0;
        final int rest = SplatCloud.restCoefficients(shDegree);

        final int total = vertex.count;
        final int numProps = vertex.properties.size();
        final float[] rowValues = new float[numProps];
        final int[] rowOffsets = new int[total];
        final float[] rawPositions = new float[total * 3];
        float[] importance = new float[total];
        final int[] nextCursor = new int[]{cursor};

        for (int i = 0; i < total; i++) {
            rowOffsets[i] = nextCursor[0];
            for (int p = 0; p < numProps; p++) {
                final float v = readAsciiFloat(content, nextCursor);
                if (!Float.isFinite(v)) throw new IOException("Non-finite value in ASCII PLY");
                rowValues[p] = v;
            }
            rawPositions[i * 3] = rowValues[px];
            rawPositions[i * 3 + 1] = rowValues[py];
            rawPositions[i * 3 + 2] = rowValues[pz];
            final float opacity = sigmoid(rowValues[popacity]);
            final float sx = (float) Math.exp(rowValues[ps0]);
            final float sy = (float) Math.exp(rowValues[ps1]);
            final float sz = (float) Math.exp(rowValues[ps2]);
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Scale overflow in ASCII PLY");
            }
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.select(importance, rawPositions, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = shDegree > 0 ? new float[count * rest * 3] : new float[0];

        for (int n = 0; n < count; n++) {
            final int idx = selected[n];
            nextCursor[0] = rowOffsets[idx];
            for (int p = 0; p < numProps; p++) {
                rowValues[p] = readAsciiFloat(content, nextCursor);
            }
            positions[n * 3] = rawPositions[idx * 3];
            positions[n * 3 + 1] = rawPositions[idx * 3 + 1];
            positions[n * 3 + 2] = rawPositions[idx * 3 + 2];

            colors[n * 3] = 0.5f + SplatCloud.SH_C0 * rowValues[fdc0];
            colors[n * 3 + 1] = 0.5f + SplatCloud.SH_C0 * rowValues[fdc1];
            colors[n * 3 + 2] = 0.5f + SplatCloud.SH_C0 * rowValues[fdc2];

            opacities[n] = sigmoid(rowValues[popacity]);

            scales[n * 3] = (float) Math.exp(rowValues[ps0]);
            scales[n * 3 + 1] = (float) Math.exp(rowValues[ps1]);
            scales[n * 3 + 2] = (float) Math.exp(rowValues[ps2]);

            storeNormalizedQuat(rotations, n * 4, rowValues[prot1], rowValues[prot2], rowValues[prot3], rowValues[prot0]);

            if (shDegree > 0) {
                for (int c = 0; c < 3; c++) {
                    for (int k = 0; k < rest; k++) {
                        final int propIdx = fRestProps.get(c * cPerChannel + k).index;
                        sh[(n * rest + k) * 3 + c] = rowValues[propIdx];
                    }
                }
            }
        }

        SplatLoader.flipYZ(count, positions, rotations, shDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
    }

    // PlayCanvas / SuperSplat の圧縮 PLY を読み込む
    private static SplatCloud loadCompressed(byte[] content, Header header, Element vertex) throws IOException {
        if (header.format != Format.BINARY_LE) {
            throw new IOException("Compressed PLY must be binary_little_endian");
        }
        final Element chunk = header.element("chunk");
        final int total = vertex.count;
        final int expectedChunks = (total + 255) >>> 8;
        if (chunk == null || chunk.count < expectedChunks) {
            throw new IOException("Invalid chunk element in compressed PLY");
        }

        final Property cMinX = chunk.require("min_x");
        final Property cMinY = chunk.require("min_y");
        final Property cMinZ = chunk.require("min_z");
        final Property cMaxX = chunk.require("max_x");
        final Property cMaxY = chunk.require("max_y");
        final Property cMaxZ = chunk.require("max_z");
        final Property cMinSx = chunk.require("min_scale_x");
        final Property cMinSy = chunk.require("min_scale_y");
        final Property cMinSz = chunk.require("min_scale_z");
        final Property cMaxSx = chunk.require("max_scale_x");
        final Property cMaxSy = chunk.require("max_scale_y");
        final Property cMaxSz = chunk.require("max_scale_z");

        final Property cMinR = chunk.property("min_r");
        final Property cMinG = chunk.property("min_g");
        final Property cMinB = chunk.property("min_b");
        final Property cMaxR = chunk.property("max_r");
        final Property cMaxG = chunk.property("max_g");
        final Property cMaxB = chunk.property("max_b");
        final boolean hasChunkColor = cMinR != null && cMinG != null && cMinB != null
                && cMaxR != null && cMaxG != null && cMaxB != null;

        final Property pPos = vertex.require("packed_position");
        final Property pRot = vertex.require("packed_rotation");
        final Property pScale = vertex.require("packed_scale");
        final Property pColor = vertex.require("packed_color");

        final Element shElem = header.element("sh");
        final List<Property> shProps;
        final int shDegree;
        final int rest;
        final int shPerChannel;
        if (shElem != null && shElem.count > 0) {
            if (shElem.count < total) throw new IOException("Compressed PLY sh count mismatch");
            shProps = findRestProperties(shElem);
            shPerChannel = shProps.size() / 3;
            shDegree = shPerChannel >= 15 ? 3 : shPerChannel >= 8 ? 2 : shPerChannel >= 3 ? 1 : 0;
            rest = SplatCloud.restCoefficients(shDegree);
        } else {
            shProps = null;
            shPerChannel = 0;
            shDegree = 0;
            rest = 0;
        }

        final ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

        // チャンクの境界値の妥当性を先に確かめる
        for (int ci = 0; ci < expectedChunks; ci++) {
            final int cRow = (int) (chunk.byteOffset + (long) ci * chunk.stride);
            if (!Float.isFinite(readFloat(buf, cRow, cMinX)) || !Float.isFinite(readFloat(buf, cRow, cMaxX))
                    || !Float.isFinite(readFloat(buf, cRow, cMinY)) || !Float.isFinite(readFloat(buf, cRow, cMaxY))
                    || !Float.isFinite(readFloat(buf, cRow, cMinZ)) || !Float.isFinite(readFloat(buf, cRow, cMaxZ))
                    || !Float.isFinite(readFloat(buf, cRow, cMinSx)) || !Float.isFinite(readFloat(buf, cRow, cMaxSx))
                    || !Float.isFinite(readFloat(buf, cRow, cMinSy)) || !Float.isFinite(readFloat(buf, cRow, cMaxSy))
                    || !Float.isFinite(readFloat(buf, cRow, cMinSz)) || !Float.isFinite(readFloat(buf, cRow, cMaxSz))) {
                throw new IOException("Non-finite chunk bounds in compressed PLY");
            }
            if (hasChunkColor) {
                if (!Float.isFinite(readFloat(buf, cRow, cMinR)) || !Float.isFinite(readFloat(buf, cRow, cMaxR))
                        || !Float.isFinite(readFloat(buf, cRow, cMinG)) || !Float.isFinite(readFloat(buf, cRow, cMaxG))
                        || !Float.isFinite(readFloat(buf, cRow, cMinB)) || !Float.isFinite(readFloat(buf, cRow, cMaxB))) {
                    throw new IOException("Non-finite chunk color bounds in compressed PLY");
                }
            }
        }

        // 1 パス目: 位置と importance の計算
        final float[] rawPositions = new float[total * 3];
        float[] importance = new float[total];
        for (int i = 0; i < total; i++) {
            final int ci = i >>> 8;
            final int cRow = (int) (chunk.byteOffset + (long) ci * chunk.stride);
            final int vRow = (int) (vertex.byteOffset + (long) i * vertex.stride);

            final int pp = readIntBits(buf, vRow, pPos);
            rawPositions[i * 3] = lerp(readFloat(buf, cRow, cMinX), readFloat(buf, cRow, cMaxX), unpackUnorm(pp >>> 21, 11));
            rawPositions[i * 3 + 1] = lerp(readFloat(buf, cRow, cMinY), readFloat(buf, cRow, cMaxY), unpackUnorm(pp >>> 11, 10));
            rawPositions[i * 3 + 2] = lerp(readFloat(buf, cRow, cMinZ), readFloat(buf, cRow, cMaxZ), unpackUnorm(pp, 11));

            final int ps = readIntBits(buf, vRow, pScale);
            final int pc = readIntBits(buf, vRow, pColor);

            final float sx = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSx), readFloat(buf, cRow, cMaxSx), unpackUnorm(ps >>> 21, 11)));
            final float sy = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSy), readFloat(buf, cRow, cMaxSy), unpackUnorm(ps >>> 11, 10)));
            final float sz = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSz), readFloat(buf, cRow, cMaxSz), unpackUnorm(ps, 11)));
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                throw new IOException("Scale overflow in compressed PLY");
            }
            final float opacity = (pc & 0xFF) / 255.0f;
            importance[i] = SplatLoader.importance(opacity, sx, sy, sz);
        }

        final int[] selected = SplatLoader.select(importance, rawPositions, total, SplatLoader.MAX_SPLATS);
        importance = null;
        final int count = selected.length;

        final float[] positions = new float[count * 3];
        final float[] rotations = new float[count * 4];
        final float[] scales = new float[count * 3];
        final float[] opacities = new float[count];
        final float[] colors = new float[count * 3];
        final float[] sh = shDegree > 0 ? new float[count * rest * 3] : new float[0];

        // 2 パス目: 選ばれた頂点だけを展開する
        for (int n = 0; n < count; n++) {
            final int i = selected[n];
            final int ci = i >>> 8;
            final int cRow = (int) (chunk.byteOffset + (long) ci * chunk.stride);
            final int vRow = (int) (vertex.byteOffset + (long) i * vertex.stride);

            final int pr = readIntBits(buf, vRow, pRot);
            final int ps = readIntBits(buf, vRow, pScale);
            final int pc = readIntBits(buf, vRow, pColor);

            positions[n * 3] = rawPositions[i * 3];
            positions[n * 3 + 1] = rawPositions[i * 3 + 1];
            positions[n * 3 + 2] = rawPositions[i * 3 + 2];

            scales[n * 3] = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSx), readFloat(buf, cRow, cMaxSx), unpackUnorm(ps >>> 21, 11)));
            scales[n * 3 + 1] = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSy), readFloat(buf, cRow, cMaxSy), unpackUnorm(ps >>> 11, 10)));
            scales[n * 3 + 2] = (float) Math.exp(lerp(readFloat(buf, cRow, cMinSz), readFloat(buf, cRow, cMaxSz), unpackUnorm(ps, 11)));

            final float cr = unpackUnorm(pc >>> 24, 8);
            final float cg = unpackUnorm(pc >>> 16, 8);
            final float cb = unpackUnorm(pc >>> 8, 8);
            if (hasChunkColor) {
                colors[n * 3] = lerp(readFloat(buf, cRow, cMinR), readFloat(buf, cRow, cMaxR), cr);
                colors[n * 3 + 1] = lerp(readFloat(buf, cRow, cMinG), readFloat(buf, cRow, cMaxG), cg);
                colors[n * 3 + 2] = lerp(readFloat(buf, cRow, cMinB), readFloat(buf, cRow, cMaxB), cb);
            } else {
                colors[n * 3] = cr;
                colors[n * 3 + 1] = cg;
                colors[n * 3 + 2] = cb;
            }
            opacities[n] = (pc & 0xFF) / 255.0f;

            // smallest-three (上位 2 ビットが最大成分の番号: 0=w, 1=x, 2=y, 3=z)
            final int which = pr >>> 30;
            final float a = (unpackUnorm(pr >>> 20, 10) - 0.5f) * SQRT2;
            final float b = (unpackUnorm(pr >>> 10, 10) - 0.5f) * SQRT2;
            final float c = (unpackUnorm(pr, 10) - 0.5f) * SQRT2;
            final float m = (float) Math.sqrt(Math.max(0.0f, 1.0f - (a * a + b * b + c * c)));
            switch (which) {
                case 0:
                    storeNormalizedQuat(rotations, n * 4, a, b, c, m);
                    break;
                case 1:
                    storeNormalizedQuat(rotations, n * 4, m, b, c, a);
                    break;
                case 2:
                    storeNormalizedQuat(rotations, n * 4, b, m, c, a);
                    break;
                default:
                    storeNormalizedQuat(rotations, n * 4, b, c, m, a);
                    break;
            }

            if (shDegree > 0 && shElem != null) {
                final int sRow = (int) (shElem.byteOffset + (long) i * shElem.stride);
                for (int ch = 0; ch < 3; ch++) {
                    for (int k = 0; k < rest; k++) {
                        final Property sp = shProps.get(ch * shPerChannel + k);
                        final int byteVal = content[sRow + sp.offset] & 0xFF;
                        final float nrm = byteVal == 0 ? 0.0f : byteVal == 255 ? 1.0f : (byteVal + 0.5f) / 256.0f;
                        sh[(n * rest + k) * 3 + ch] = (nrm - 0.5f) * 8.0f;
                    }
                }
            }
        }

        SplatLoader.flipYZ(count, positions, rotations, shDegree, sh);
        return new SplatCloud(count, positions, rotations, scales, opacities, colors, shDegree, sh);
    }

    private static List<Property> findRestProperties(Element elem) {
        final List<Property> list = new ArrayList<Property>();
        for (int idx = 0; idx < 45; idx++) {
            final Property p = elem.property("f_rest_" + idx);
            if (p == null) break;
            list.add(p);
        }
        return list;
    }

    private static float readFloat(ByteBuffer buf, int rowOffset, Property prop) {
        final int pos = rowOffset + prop.offset;
        switch (prop.type) {
            case FLOAT32:
                return buf.getFloat(pos);
            case FLOAT64:
                return (float) buf.getDouble(pos);
            case INT8:
                return buf.get(pos);
            case UINT8:
                return buf.get(pos) & 0xFF;
            case INT16:
                return buf.getShort(pos);
            case UINT16:
                return buf.getShort(pos) & 0xFFFF;
            case INT32:
                return buf.getInt(pos);
            case UINT32:
                return (float) (buf.getInt(pos) & 0xFFFFFFFFL);
            default:
                return 0f;
        }
    }

    private static int readIntBits(ByteBuffer buf, int rowOffset, Property prop) throws IOException {
        if (prop.type != PropType.UINT32 && prop.type != PropType.INT32) {
            throw new IOException("Expected 32-bit integer property for " + prop.name);
        }
        return buf.getInt(rowOffset + prop.offset);
    }

    private static int skipAsciiToken(byte[] content, int cursor) throws IOException {
        final int len = content.length;
        while (cursor < len && content[cursor] <= ' ') cursor++;
        if (cursor >= len) throw new IOException("Unexpected EOF in ASCII PLY");
        while (cursor < len && content[cursor] > ' ') cursor++;
        return cursor;
    }

    private static float readAsciiFloat(byte[] content, int[] cursorRef) throws IOException {
        final int len = content.length;
        int i = cursorRef[0];
        while (i < len && content[i] <= ' ') i++;
        if (i >= len) throw new IOException("Unexpected EOF in ASCII PLY");
        final int start = i;
        while (i < len && content[i] > ' ') i++;
        cursorRef[0] = i;
        final String token = new String(content, start, i - start, StandardCharsets.US_ASCII);
        try {
            return Float.parseFloat(token);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid number in ASCII PLY: " + token);
        }
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private static float unpackUnorm(int value, int bits) {
        final int mask = (1 << bits) - 1;
        return (value & mask) / (float) mask;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static void storeNormalizedQuat(float[] rotations, int offset, float x, float y, float z, float w) {
        final float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len > 0f && Float.isFinite(len)) {
            rotations[offset] = x / len;
            rotations[offset + 1] = y / len;
            rotations[offset + 2] = z / len;
            rotations[offset + 3] = w / len;
        } else {
            rotations[offset] = 0f;
            rotations[offset + 1] = 0f;
            rotations[offset + 2] = 0f;
            rotations[offset + 3] = 1f;
        }
    }
}
