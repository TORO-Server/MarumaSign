package marumasa.marumasa_sign.core.gltf.draco;

import java.util.Arrays;

/**
 * 伸びる int の並び。展開の途中の作業用と、面の頂点番号を外へ渡すのに使う。
 */
public final class IntList {

    private int[] data;
    private int count;

    public IntList() {
        this(16);
    }

    IntList(int capacity) {
        data = new int[Math.max(4, capacity)];
    }

    IntList(int[] values) {
        data = values;
        count = values.length;
    }

    public int getCount() {
        return count;
    }

    public int get(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("index " + index + " / " + count);
        return data[index];
    }

    public int[] toArray() {
        return Arrays.copyOf(data, count);
    }

    void set(int index, int value) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException("index " + index + " / " + count);
        data[index] = value;
    }

    void add(int value) {
        if (count == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 1);
        }
        data[count++] = value;
    }

    int last() {
        return get(count - 1);
    }

    int removeLast() {
        final int value = get(count - 1);
        count--;
        return value;
    }

    void setLast(int value) {
        set(count - 1, value);
    }

    boolean isEmpty() {
        return count == 0;
    }

    void clear() {
        count = 0;
    }
}
