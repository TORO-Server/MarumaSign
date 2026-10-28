package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * 属性 1 つの値を読む。
 * 値はまず「持ち運べる形」(量子化された整数) で読み、予測を戻してから、
 * 量子化や法線の変換を戻して元の型の値にする。
 */
final class AttributeDecoder {

    // 値の書き方
    static final int GENERIC = 0;
    static final int INTEGER = 1;
    static final int QUANTIZATION = 2;
    static final int NORMALS = 3;

    final MeshDecoding session;
    final AttributeGroup group;
    final int kind;
    final PointAttribute attribute;

    // 持ち運べる形の値 (整数)。GENERIC では作らない
    int[] portable;
    int portableEntries;

    // 量子化の情報
    private float[] minValues;
    private float range;
    private int quantizationBits = -1;
    // 法線の量子化のビット数
    private int normalBits = -1;

    AttributeDecoder(MeshDecoding session, AttributeGroup group, int kind, PointAttribute attribute) throws IOException {
        this.session = session;
        this.group = group;
        this.kind = kind;
        this.attribute = attribute;
        if (kind == QUANTIZATION && attribute.getDataType() != DataType.FLOAT32) {
            throw new IOException("Draco quantized attribute must be float");
        }
        if (kind == NORMALS && (attribute.getComponentsCount() != 3 || attribute.getDataType() != DataType.FLOAT32)) {
            throw new IOException("Draco normal attribute must be 3 floats");
        }
    }

    private boolean legacy() {
        return session.version < ByteReader.V20;
    }

    // 持ち運べる形の 1 値あたりの成分の数 (法線は 2 つの整数)
    private int valueComponents() {
        return kind == NORMALS ? 2 : attribute.getComponentsCount();
    }

    /**
     * 値を読む (予測を戻すところまで)。
     */
    void decodePortable(ByteReader in, int[] pointIds) throws IOException {
        session.budget.alloc((long) pointIds.length * attribute.getByteStride() / 4 + 1);
        attribute.allocate(pointIds.length);
        if (kind == GENERIC) {
            // 元の型のまま並んでいる
            final int bytes = pointIds.length * attribute.getByteStride();
            in.need(bytes);
            System.arraycopy(in.data, in.pos, attribute.data(), 0, bytes);
            in.pos += bytes;
            return;
        }
        // 予測の方式と変換
        final int method = in.i8();
        if (method < Prediction.NONE || method >= Prediction.METHOD_COUNT) {
            throw new IOException("Invalid Draco prediction method: " + method);
        }
        Prediction prediction = null;
        if (method != Prediction.NONE) {
            final int transform = in.i8();
            if (transform < Prediction.TRANSFORM_NONE || transform >= Prediction.TRANSFORM_COUNT) {
                throw new IOException("Invalid Draco prediction transform: " + transform);
            }
            prediction = createPrediction(method, transform);
        }
        if (prediction != null && prediction.needsPositions()) {
            prediction.setParent(session.positionParent(this));
        }
        // 古い形式では変換の情報が値より前にある
        if (legacy()) {
            if (kind == QUANTIZATION) readQuantization(in);
            if (kind == NORMALS) normalBits = in.u8();
        }
        decodeIntegers(in, pointIds, prediction);
        if (legacy()) storeValues();
    }

    /**
     * 予測の方式を作る。対応していない組み合わせのときは null (予測なしで読む。元の実装と同じ)。
     */
    private Prediction createPrediction(int method, int transformType) {
        if (kind == NORMALS) {
            if (transformType != Prediction.TRANSFORM_OCTAHEDRON && transformType != Prediction.TRANSFORM_OCTAHEDRON_CANONICALIZED) {
                return null;
            }
        } else if (transformType != Prediction.TRANSFORM_WRAP) {
            return null;
        }
        final Prediction.Transform transform = new Prediction.Transform(transformType);
        final AttributeGroup g = group;
        if (g.table != null && method > Prediction.DIFFERENCE
                && (transformType == Prediction.TRANSFORM_WRAP || method == Prediction.GEOMETRIC_NORMAL)) {
            return new Prediction(method, transform, g.table, g.encoding.valueToCorner, g.encoding.vertexToValue,
                    session.version, session.budget);
        }
        // メッシュのつながりを使えないときは差分にする
        return new Prediction(Prediction.DIFFERENCE, transform, null, null, null, session.version, session.budget);
    }

    private void decodeIntegers(ByteReader in, int[] pointIds, Prediction prediction) throws IOException {
        final int components = valueComponents();
        final int entries = pointIds.length;
        final long count = (long) entries * components;
        if (entries == 0) throw new IOException("Draco attribute has no values");
        session.budget.alloc(count);
        if (count > Integer.MAX_VALUE - 8) throw new IOException("Draco attribute is too large");
        final int n = (int) count;
        final int[] values = new int[n];
        final int compressed = in.u8();
        if (compressed > 0) {
            Rans.decodeSymbols(in, n, components, values);
        } else {
            final int bytes = in.u8();
            if (bytes > 4) throw new IOException("Invalid Draco integer size: " + bytes);
            in.need((long) bytes * n);
            for (int i = 0; i < n; i++) {
                int v = 0;
                for (int b = 0; b < bytes; b++) v |= (in.data[in.pos++] & 0xff) << (8 * b);
                values[i] = v;
            }
        }
        if (prediction == null || !prediction.transform.correctionsPositive()) {
            // 0, -1, 1, -2, ... と並べた符号なしの値を符号付きに戻す
            for (int i = 0; i < n; i++) {
                final int v = values[i];
                values[i] = (v >>> 1) ^ -(v & 1);
            }
        }
        if (prediction != null) {
            prediction.setMaxPredictedValues(n);
            prediction.decodeData(in);
            prediction.restore(values, n, components, pointIds);
        }
        portable = values;
        portableEntries = entries;
    }

    /**
     * 変換を戻すのに必要な情報を読む (2.0 以降)。
     */
    void decodeTransformData(ByteReader in) throws IOException {
        if (legacy()) return;
        if (kind == QUANTIZATION) readQuantization(in);
        if (kind == NORMALS) normalBits = in.u8();
    }

    private void readQuantization(ByteReader in) throws IOException {
        final int components = attribute.getComponentsCount();
        minValues = new float[components];
        for (int i = 0; i < components; i++) minValues[i] = in.f32();
        range = in.f32();
        quantizationBits = in.u8();
        if (quantizationBits < 1 || quantizationBits > 30) {
            throw new IOException("Invalid Draco quantization bits: " + quantizationBits);
        }
    }

    /**
     * 元の型の値に戻す (2.0 以降)。
     */
    void transformToOriginal() throws IOException {
        if (kind == GENERIC || legacy()) return;
        storeValues();
    }

    // 持ち運べる形の値を、元の型の値にして書く
    private void storeValues() throws IOException {
        final byte[] out = attribute.data();
        final int entries = attribute.getNumUniqueEntries();
        switch (kind) {
            case INTEGER: {
                final int type = attribute.getDataType();
                if (type != DataType.INT8 && type != DataType.UINT8 && type != DataType.INT16
                        && type != DataType.UINT16 && type != DataType.INT32 && type != DataType.UINT32) {
                    throw new IOException("Draco integer attribute has unsupported type " + type);
                }
                final int size = DataType.length(type);
                final int n = entries * attribute.getComponentsCount();
                for (int i = 0; i < n; i++) Bits.writeInteger(out, i * size, type, portable[i]);
                break;
            }
            case QUANTIZATION: {
                // 量子化の 1 段の大きさは float で求める (元の実装と同じ順の計算)
                final int maxQuantized = (1 << quantizationBits) - 1;
                final float delta = range / (float) maxQuantized;
                final int components = attribute.getComponentsCount();
                int k = 0;
                for (int i = 0; i < entries; i++) {
                    for (int c = 0; c < components; c++, k++) {
                        final float value = (float) portable[k] * delta + minValues[c];
                        Bits.put32(out, k * 4, Float.floatToRawIntBits(value));
                    }
                }
                break;
            }
            case NORMALS: {
                final Octahedron box = new Octahedron();
                if (!box.setQuantizationBits(normalBits)) throw new IOException("Invalid Draco normal bits: " + normalBits);
                final float[] v = new float[3];
                for (int i = 0; i < entries; i++) {
                    box.toUnitVector(portable[2 * i], portable[2 * i + 1], v);
                    for (int c = 0; c < 3; c++) Bits.put32(out, (3 * i + c) * 4, Float.floatToRawIntBits(v[c]));
                }
                break;
            }
            default:
                break;
        }
    }
}
