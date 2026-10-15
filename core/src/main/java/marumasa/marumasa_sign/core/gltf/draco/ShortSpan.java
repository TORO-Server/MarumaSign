package marumasa.marumasa_sign.core.gltf.draco;



import java.lang.reflect.Array;
import java.lang.reflect.Type;

abstract class ShortSpan extends Span {

    private static final class ArraySpan extends ShortSpan {
        private final short[] array;
        public ArraySpan(short[] array, int offset, int length) {
            super(offset, length);
            this.array = array;
        }

        @Override
        public short get(int idx)
        {
            return array[idx + offset];
        }
        @Override
        public void put(int idx, short value)
        {
            rangeCheck(idx);
            array[idx + offset] = value;
        }

        @Override
        public ShortSpan slice(int offset, int size) {
            return new ArraySpan(array, offset + this.offset, size);
        }

    }
    private static final class BytesSpan extends ShortSpan {
        private final byte[] array;
        public BytesSpan(byte[] array, int offset, int length) {
            super(offset, length);
            this.array = array;
        }

        @Override
        public short get(int idx)
        {
            rangeCheck(idx);

            int ptr = (idx + offset) * 2;
            return getShortL(array, ptr);
        }
        @Override
        public void put(int idx, short value)
        {
            rangeCheck(idx);
            int ptr = (idx + offset) * 2;
            putShortL(array, ptr, value);
        }

        @Override
        public ShortSpan slice(int offset, int size) {
            return new BytesSpan(array, offset + this.offset, size);
        }

    }

    public static ShortSpan wrap(short[] array) {
        return new ArraySpan(array, 0, array.length);
    }
    public static ShortSpan wrap(short[] array, int offset, int length) {
        return new ArraySpan(array, offset, length);
    }
    public static ShortSpan wrap(short[] array, int offset) {
        return new ArraySpan(array, offset, array.length - offset);
    }
    public static ShortSpan wrap(byte[] array) {
        return new BytesSpan(array, 0, array.length / 4);
    }
    public static ShortSpan wrap(byte[] array, int offset, int length) {
        return new BytesSpan(array, offset / 4, length / 4);
    }

    protected ShortSpan(int offset, int length) {
        super(offset, length);
    }

    public int compareTo(ShortSpan span) {
        int num = Math.min(size(), span.size());
        for(int i = 0; i < num; i++) {
            int n = Integer.compare(get(i), span.get(i));
            if(n != 0)
                return n;
        }
        return Integer.compare(size(), span.size());
    }
    public boolean equals(ShortSpan span) {
        if(size() != span.size())
            return false;
        for(int i = 0; i < size(); i++) {
            if(get(i) != span.get(i))
                return false;
        }
        return true;
    }
    public void copyTo(ShortSpan span) {
        for(int i = 0; i < size(); i++) {
            span.put(i, get(i));
        }
    }
    public void fill(short v)
    {
        for(int i = 0; i < size(); i++) {
            put(i, v);
        }
    }
    public short[] toArray() {
        short[] ret = new short[length];

        for(int i = 0; i < length; i++) {
            ret[i] = get(i);
        }
        return ret;
    }
    public abstract short get(int idx);
    public abstract void put(int idx, short value);

    public abstract ShortSpan slice(int offset, int size);
    public ShortSpan slice(int offset)
    {
        return slice(offset, this.length - offset);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for(int i = 0; i < Math.min(10, length); i++) {
            if(i > 0)
                sb.append(",");
            sb.append(get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
