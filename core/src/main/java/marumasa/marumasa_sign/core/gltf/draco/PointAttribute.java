package marumasa.marumasa_sign.core.gltf.draco;

/**
 * 展開した属性 1 つ (位置、法線、UV など)。
 * 値は型どおりのバイト列で持ち、点の番号から値の番号への対応を別に持つ。
 */
public final class PointAttribute {

    // 属性の種類 (Draco の番号)
    public static final int POSITION = 0;
    public static final int NORMAL = 1;
    public static final int COLOR = 2;
    public static final int TEX_COORD = 3;
    public static final int GENERIC = 4;

    private final int attributeType;
    private final int dataType;
    private final int components;
    private final boolean normalized;
    private final int uniqueId;
    private final int byteStride;

    private DataBuffer buffer = new DataBuffer(new byte[0]);
    private int numValues;
    // 点 → 値の対応。null のときは点の番号がそのまま値の番号
    private int[] pointToValue;

    PointAttribute(int attributeType, int dataType, int components, boolean normalized, int uniqueId) {
        this.attributeType = attributeType;
        this.dataType = dataType;
        this.components = components;
        this.normalized = normalized;
        this.uniqueId = uniqueId;
        this.byteStride = DataType.length(dataType) * components;
    }

    public int getAttributeType() {
        return attributeType;
    }

    public int getDataType() {
        return dataType;
    }

    public int getComponentsCount() {
        return components;
    }

    public boolean getNormalized() {
        return normalized;
    }

    public int getUniqueId() {
        return uniqueId;
    }

    public int getByteStride() {
        return byteStride;
    }

    public int getByteOffset() {
        return 0;
    }

    public DataBuffer getBuffer() {
        return buffer;
    }

    public int getNumUniqueEntries() {
        return numValues;
    }

    /**
     * 点の番号に対応する値の番号。どの値にも対応しない点は -1。
     */
    public int mappedIndex(int point) {
        if (pointToValue == null) return point;
        if (point < 0 || point >= pointToValue.length) return -1;
        return pointToValue[point];
    }

    // 値の入れ物を用意する (中身は 0)
    void allocate(int values) {
        numValues = values;
        buffer = new DataBuffer(new byte[values * byteStride]);
    }

    byte[] data() {
        return buffer.getBuffer();
    }

    void setMapping(int[] map) {
        pointToValue = map;
    }

    /**
     * 値 1 つの成分を整数で取り出す。予測の親 (古い形式の位置) を読むのに使う。
     * 変換できないときは false を返し、それより前の成分だけ書かれる。
     */
    boolean convertToLong(int value, long[] out) {
        final byte[] d = buffer.getBuffer();
        final int size = DataType.length(dataType);
        int pos = value * byteStride;
        if (value < 0 || value >= numValues) return false;
        final int n = Math.min(components, out.length);
        for (int c = 0; c < n; c++, pos += size) {
            switch (dataType) {
                case DataType.FLOAT32: {
                    final float f = Float.intBitsToFloat(Bits.le32(d, pos));
                    if (Float.isNaN(f) || Float.isInfinite(f)) return false;
                    if (normalized) return false;
                    if (f < -9.223372036854775808E18f || f >= 9.223372036854775807E18f) return false;
                    out[c] = (long) f;
                    break;
                }
                case DataType.FLOAT64: {
                    final double f = Double.longBitsToDouble(Bits.le64(d, pos));
                    if (Double.isNaN(f) || Double.isInfinite(f)) return false;
                    if (normalized) return false;
                    if (f < -9.223372036854775808E18 || f >= 9.223372036854775807E18) return false;
                    out[c] = (long) f;
                    break;
                }
                case DataType.UINT64: {
                    final long v = Bits.le64(d, pos);
                    if (v < 0) return false;
                    out[c] = v;
                    break;
                }
                default:
                    out[c] = Bits.readInteger(d, pos, dataType);
                    break;
            }
        }
        for (int c = components; c < out.length; c++) out[c] = 0;
        return true;
    }

    /**
     * 値 1 つの成分を float で取り出す。古い UV 予測で使う。
     */
    boolean convertToFloat(int value, float[] out) {
        final byte[] d = buffer.getBuffer();
        final int size = DataType.length(dataType);
        int pos = value * byteStride;
        if (value < 0 || value >= numValues) return false;
        final int n = Math.min(components, out.length);
        for (int c = 0; c < n; c++, pos += size) {
            switch (dataType) {
                case DataType.FLOAT32:
                    out[c] = Float.intBitsToFloat(Bits.le32(d, pos));
                    break;
                case DataType.FLOAT64:
                    out[c] = (float) Double.longBitsToDouble(Bits.le64(d, pos));
                    break;
                case DataType.UINT64: {
                    final long v = Bits.le64(d, pos);
                    out[c] = v >= 0 ? (float) v : (float) ((v >>> 1) | (v & 1)) * 2f;
                    if (normalized) out[c] /= 1.8446744E19f;
                    break;
                }
                default: {
                    final long v = Bits.readInteger(d, pos, dataType);
                    out[c] = (float) v;
                    if (normalized) out[c] /= (float) Bits.maxOf(dataType);
                    break;
                }
            }
        }
        for (int c = components; c < out.length; c++) out[c] = 0;
        return true;
    }
}
