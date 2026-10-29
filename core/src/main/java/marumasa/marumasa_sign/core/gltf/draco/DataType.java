package marumasa.marumasa_sign.core.gltf.draco;

/**
 * Draco の属性の値の型。番号は Draco のストリームに書かれる値と同じ。
 */
public final class DataType {

    public static final int INVALID = 0;
    public static final int INT8 = 1;
    public static final int UINT8 = 2;
    public static final int INT16 = 3;
    public static final int UINT16 = 4;
    public static final int INT32 = 5;
    public static final int UINT32 = 6;
    public static final int INT64 = 7;
    public static final int UINT64 = 8;
    public static final int FLOAT32 = 9;
    public static final int FLOAT64 = 10;
    public static final int BOOL = 11;
    public static final int TYPES_COUNT = 12;

    private DataType() {
    }

    /**
     * 1 要素のバイト数。知らない型は -1。
     */
    public static int length(int type) {
        switch (type) {
            case INT8:
            case UINT8:
            case BOOL:
                return 1;
            case INT16:
            case UINT16:
                return 2;
            case INT32:
            case UINT32:
            case FLOAT32:
                return 4;
            case INT64:
            case UINT64:
            case FLOAT64:
                return 8;
            default:
                return -1;
        }
    }
}
