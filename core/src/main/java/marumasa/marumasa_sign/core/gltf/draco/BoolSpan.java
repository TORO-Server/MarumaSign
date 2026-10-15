package marumasa.marumasa_sign.core.gltf.draco;


abstract class BoolSpan extends Span {

    private static final class ArraySpan extends BoolSpan {
        private final boolean[] array;
        public ArraySpan(boolean[] array, int offset, int length) {
            super(offset, length);
            this.array = array;
        }

        @Override
        public boolean get(int idx)
        {
            return array[idx + offset];
        }
        @Override
        public void put(int idx, boolean value)
        {
            rangeCheck(idx);
            array[idx + offset] = value;
        }

        @Override
        public BoolSpan slice(int offset, int size) {
            return new ArraySpan(array, offset + this.offset, size);
        }

    }
    private static final class BytesSpan extends BoolSpan {
        private final byte[] array;
        public BytesSpan(byte[] array, int offset, int length) {
            super(offset, length);
            this.array = array;
        }

        @Override
        public boolean get(int idx)
        {
            rangeCheck(idx);

            int ptr = (idx + offset);
            return array[ptr] != 0;
        }
        @Override
        public void put(int idx, boolean value)
        {
            rangeCheck(idx);
            int ptr = (idx + offset);
            array[ptr] = (byte)(value? 1 : 0);
        }

        @Override
        public BoolSpan slice(int offset, int size) {
            return new BytesSpan(array, offset + this.offset, size);
        }

    }

    public static BoolSpan wrap(boolean[] array) {
        return new ArraySpan(array, 0, array.length);
    }
    public static BoolSpan wrap(boolean[] array, int offset, int length) {
        return new ArraySpan(array, offset, length);
    }
    public static BoolSpan wrap(boolean[] array, int offset) {
        return new ArraySpan(array, offset, array.length - offset);
    }
    public static BoolSpan wrap(byte[] array) {
        return new BytesSpan(array, 0, array.length);
    }
    public static BoolSpan wrap(byte[] array, int offset, int length) {
        return new BytesSpan(array, offset, length);
    }

    protected BoolSpan(int offset, int length) {
        super(offset, length);
    }

    public int compareTo(BoolSpan span) {
        int num = Math.min(size(), span.size());
        for(int i = 0; i < num; i++) {
            int n = Boolean.compare(get(i), span.get(i));
            if(n != 0)
                return n;
        }
        return Integer.compare(size(), span.size());
    }
    public boolean equals(BoolSpan span) {
        if(size() != span.size())
            return false;
        for(int i = 0; i < size(); i++) {
            if(get(i) != span.get(i))
                return false;
        }
        return true;
    }
    public void copyTo(BoolSpan span) {
        for(int i = 0; i < size(); i++) {
            span.put(i, get(i));
        }
    }
    public void fill(boolean v)
    {
        for(int i = 0; i < size(); i++) {
            put(i, v);
        }
    }
    public boolean[] toArray() {
        boolean[] ret = new boolean[length];

        for(int i = 0; i < length; i++) {
            ret[i] = get(i);
        }
        return ret;
    }
    public abstract boolean get(int idx);
    public abstract void put(int idx, boolean value);

    public abstract BoolSpan slice(int offset, int size);
    public BoolSpan slice(int offset)
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
