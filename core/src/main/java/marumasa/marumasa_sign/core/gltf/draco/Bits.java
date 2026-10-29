package marumasa.marumasa_sign.core.gltf.draco;

/**
 * リトルエンディアンのバイト列と整数の読み書き。
 */
final class Bits {

    private Bits() {
    }

    static int le32(byte[] d, int pos) {
        return (d[pos] & 0xff) | (d[pos + 1] & 0xff) << 8 | (d[pos + 2] & 0xff) << 16 | (d[pos + 3] & 0xff) << 24;
    }

    static long le64(byte[] d, int pos) {
        return (le32(d, pos) & 0xffffffffL) | (long) le32(d, pos + 4) << 32;
    }

    static void put32(byte[] d, int pos, int v) {
        d[pos] = (byte) v;
        d[pos + 1] = (byte) (v >> 8);
        d[pos + 2] = (byte) (v >> 16);
        d[pos + 3] = (byte) (v >> 24);
    }

    /**
     * 整数の型 (64 ビット以外) の値を読む。
     */
    static long readInteger(byte[] d, int pos, int type) {
        switch (type) {
            case DataType.INT8:
                return d[pos];
            case DataType.UINT8:
            case DataType.BOOL:
                return d[pos] & 0xff;
            case DataType.INT16:
                return (short) ((d[pos] & 0xff) | (d[pos + 1] & 0xff) << 8);
            case DataType.UINT16:
                return (d[pos] & 0xff) | (d[pos + 1] & 0xff) << 8;
            case DataType.INT32:
                return le32(d, pos);
            case DataType.UINT32:
                return le32(d, pos) & 0xffffffffL;
            case DataType.INT64:
                return le64(d, pos);
            default:
                throw new IllegalArgumentException("not an integer type: " + type);
        }
    }

    /**
     * 32 ビットの値を、型の幅に切り詰めて書く。
     */
    static void writeInteger(byte[] d, int pos, int type, int v) {
        switch (type) {
            case DataType.INT8:
            case DataType.UINT8:
                d[pos] = (byte) v;
                break;
            case DataType.INT16:
            case DataType.UINT16:
                d[pos] = (byte) v;
                d[pos + 1] = (byte) (v >> 8);
                break;
            default:
                put32(d, pos, v);
                break;
        }
    }

    /**
     * 整数の型の最大値 (正規化された値の換算に使う)。
     */
    static long maxOf(int type) {
        switch (type) {
            case DataType.INT8:
                return Byte.MAX_VALUE;
            case DataType.UINT8:
                return 0xff;
            case DataType.INT16:
                return Short.MAX_VALUE;
            case DataType.UINT16:
                return 0xffff;
            case DataType.INT32:
                return Integer.MAX_VALUE;
            case DataType.UINT32:
                return 0xffffffffL;
            case DataType.INT64:
                return Long.MAX_VALUE;
            case DataType.BOOL:
                return 1;
            default:
                return 1;
        }
    }

    /**
     * 最上位の 1 のビットの位置 (n は 0 でないこと)。
     */
    static int mostSignificantBit(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    /**
     * 符号なし 64 ビットの整数の平方根 (切り捨て)。Draco の IntSqrt と同じ手順で求める。
     */
    static long intSqrt(long number) {
        if (number == 0) return 0;
        long act = number;
        long root = 1;
        while (Long.compareUnsigned(act, 2) >= 0) {
            root *= 2;
            act >>>= 2;
        }
        // ニュートン法。上から近づくので、2 乗が number 以下になったら終わり
        for (int i = 0; i < 128; i++) {
            root = (root + Long.divideUnsigned(number, root)) >>> 1;
            if (Long.compareUnsigned(root * root, number) <= 0) break;
        }
        return root;
    }
}
