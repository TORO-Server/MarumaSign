package marumasa.marumasa_sign.core.structure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * ブロックの性質の表 (marumasa_sign/structure/blocks.txt.gz)。tools/blockdata/BlockDataDump.java で作る。
 * 性質の名前と取りうる値、既定の状態、面の隠れ方、液体などを、ブロックの状態ごとに持つ。
 */
final class BlockData {

    // 状態の印 (flags)
    static final int FULL_COLLISION = 1;
    static final int LIGHT_PERMEABLE = 2;
    static final int SOLID_RENDER = 4;
    static final int INVISIBLE = 8;
    // 面が丈夫か (下、上、北、南、西、東の順に 1 ビットずつ)
    static final int STURDY_SHIFT = 4;
    // 固体 (BlockState.isSolid。液体の高さの計算に使う)
    static final int SOLID = 1024;

    static final int SKIP_NONE = 0;
    // 同じブロックどうしの間の面を描かない (ガラスなど)
    static final int SKIP_SAME = 1;
    // 鉄格子や板ガラス
    static final int SKIP_BARS = 2;
    static final int SKIP_LIQUID = 3;
    // マングローブの根 (上下に並んだときだけ)
    static final int SKIP_ROOTS = 4;

    static final int OFFSET_NONE = 0;
    static final int OFFSET_XZ = 1;
    static final int OFFSET_XYZ = 2;

    static final class Block {
        final String name;
        final int skip;
        final int offsetType;
        final float maxHorizontalOffset, maxVerticalOffset;
        final String[] propertyNames;
        final String[][] propertyValues;
        // 状態の番号の重み (最後の性質が 1)
        final int[] strides;
        int defaultIndex;
        // 全体の状態の表での、このブロックの最初の状態の位置
        final int base;
        final int stateCount;

        Block(String name, int skip, int offsetType, float maxHorizontal, float maxVertical,
              String[] propertyNames, String[][] propertyValues, int base) {
            this.name = name;
            this.skip = skip;
            this.offsetType = offsetType;
            this.maxHorizontalOffset = maxHorizontal;
            this.maxVerticalOffset = maxVertical;
            this.propertyNames = propertyNames;
            this.propertyValues = propertyValues;
            this.strides = new int[propertyNames.length];
            int count = 1;
            for (int i = propertyNames.length - 1; i >= 0; i--) {
                strides[i] = count;
                count *= propertyValues[i].length;
            }
            this.stateCount = count;
            this.base = base;
        }

        int propertyIndex(String key) {
            for (int i = 0; i < propertyNames.length; i++) {
                if (propertyNames[i].equals(key)) return i;
            }
            return -1;
        }

        boolean hasProperty(String key) {
            return propertyIndex(key) >= 0;
        }

        boolean isValid(String key, String value) {
            final int p = propertyIndex(key);
            if (p < 0) return false;
            for (String candidate : propertyValues[p]) {
                if (candidate.equals(value)) return true;
            }
            return false;
        }

        // 既定の状態での、性質の値
        String defaultValue(int property) {
            return propertyValues[property][(defaultIndex / strides[property]) % propertyValues[property].length];
        }
    }

    private static volatile BlockData instance;

    private final Map<String, Block> blocks = new HashMap<String, Block>();
    private long[][] masks;
    private int[] faces;
    private int[] flags;
    private byte[] emission;
    // 液体: 0 なし, それ以外は (種類 1 水 / 2 溶岩) | 量 << 2 | 源 << 6 | 落下 << 7
    private int[] fluids;

    static BlockData get() throws IOException {
        BlockData data = instance;
        if (data == null) {
            synchronized (BlockData.class) {
                data = instance;
                if (data == null) {
                    data = load();
                    instance = data;
                }
            }
        }
        return data;
    }

    private static BlockData load() throws IOException {
        final InputStream raw = BlockData.class.getResourceAsStream("/marumasa_sign/structure/blocks.txt.gz");
        if (raw == null) throw new IOException("Missing block data");
        final BlockData data = new BlockData();
        final List<long[]> maskList = new ArrayList<long[]>();
        maskList.add(new long[4]);
        maskList.add(new long[]{-1L, -1L, -1L, -1L});
        final IntList faceList = new IntList();
        final IntList flagList = new IntList();
        final IntList emissionList = new IntList();
        final IntList fluidList = new IntList();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
        try {
            Block current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                final String[] parts = line.split(" ");
                switch (parts[0]) {
                    case "M": {
                        final int id = Integer.parseInt(parts[1]);
                        final String hex = parts[2];
                        final long[] bits = new long[4];
                        for (int i = 0; i < 4; i++) {
                            bits[3 - i] = Long.parseUnsignedLong(hex.substring(i * 16, i * 16 + 16), 16);
                        }
                        while (maskList.size() <= id) maskList.add(null);
                        maskList.set(id, bits);
                        break;
                    }
                    case "B": {
                        final int count = parts.length - 6;
                        final String[] names = new String[count];
                        final String[][] values = new String[count][];
                        for (int i = 0; i < count; i++) {
                            final String part = parts[6 + i];
                            final int equals = part.indexOf('=');
                            names[i] = part.substring(0, equals);
                            values[i] = part.substring(equals + 1).split(",");
                        }
                        final int skip = "same".equals(parts[2]) ? SKIP_SAME : "bars".equals(parts[2]) ? SKIP_BARS
                                : "liquid".equals(parts[2]) ? SKIP_LIQUID : "roots".equals(parts[2]) ? SKIP_ROOTS : SKIP_NONE;
                        final int offset = "xz".equals(parts[3]) ? OFFSET_XZ : "xyz".equals(parts[3]) ? OFFSET_XYZ : OFFSET_NONE;
                        current = new Block(parts[1], skip, offset, Float.parseFloat(parts[4]), Float.parseFloat(parts[5]),
                                names, values, flagList.size());
                        data.blocks.put(current.name, current);
                        break;
                    }
                    case "D":
                        if (current != null) current.defaultIndex = Integer.parseInt(parts[1]);
                        break;
                    case "S": {
                        for (int i = 0; i < 6; i++) faceList.add(Integer.parseInt(parts[1 + i]));
                        flagList.add(Integer.parseInt(parts[7]));
                        emissionList.add(Integer.parseInt(parts[8]));
                        fluidList.add(parseFluid(parts[9]));
                        break;
                    }
                    default:
                        break;
                }
            }
        } finally {
            reader.close();
        }
        data.masks = maskList.toArray(new long[0][]);
        data.faces = faceList.toArray();
        data.flags = flagList.toArray();
        final int[] light = emissionList.toArray();
        data.emission = new byte[light.length];
        for (int i = 0; i < light.length; i++) data.emission[i] = (byte) light[i];
        data.fluids = fluidList.toArray();
        return data;
    }

    private static int parseFluid(String text) {
        if (text.equals("-")) return 0;
        final int type = text.charAt(0) == 'w' ? 1 : text.charAt(0) == 'l' ? 2 : 0;
        int end = 1;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        final int amount = Integer.parseInt(text.substring(1, end));
        final boolean source = text.indexOf('s', end) >= 0;
        final boolean falling = text.indexOf('f', end) >= 0;
        return type | amount << 2 | (source ? 1 << 6 : 0) | (falling ? 1 << 7 : 0);
    }

    Block block(String name) {
        return blocks.get(name);
    }

    /**
     * 状態の、表での番号。足りない性質は既定の値、知らない性質や値は無視する。
     */
    int stateIndex(Block block, BlockState state) {
        int index = 0;
        for (int p = 0; p < block.propertyNames.length; p++) {
            final String value = state.get(block.propertyNames[p]);
            int valueIndex = -1;
            if (value != null) {
                final String[] values = block.propertyValues[p];
                for (int i = 0; i < values.length; i++) {
                    if (values[i].equals(value)) valueIndex = i;
                }
            }
            if (valueIndex < 0) valueIndex = (block.defaultIndex / block.strides[p]) % block.propertyValues[p].length;
            index += valueIndex * block.strides[p];
        }
        return block.base + index;
    }

    // 足りない性質を既定の値で埋め、知らない性質を除いた状態
    BlockState complete(Block block, BlockState state) {
        final int local = stateIndex(block, state) - block.base;
        final String[] keys = block.propertyNames.clone();
        final String[] values = new String[keys.length];
        for (int p = 0; p < keys.length; p++) {
            values[p] = block.propertyValues[p][(local / block.strides[p]) % block.propertyValues[p].length];
        }
        final Map<String, String> map = new java.util.TreeMap<String, String>();
        for (int p = 0; p < keys.length; p++) map.put(keys[p], values[p]);
        return BlockState.of(block.name, map);
    }

    long[] faceMask(int stateIndex, int direction) {
        return masks[faces[stateIndex * 6 + direction]];
    }

    int faceMaskId(int stateIndex, int direction) {
        return faces[stateIndex * 6 + direction];
    }

    int flags(int stateIndex) {
        return flags[stateIndex];
    }

    int lightEmission(int stateIndex) {
        return emission[stateIndex];
    }

    int fluid(int stateIndex) {
        return fluids[stateIndex];
    }

    // 増える int の配列
    static final class IntList {
        private int[] values = new int[1024];
        private int size;

        void add(int value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
