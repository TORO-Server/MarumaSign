package marumasa.marumasa_sign.core.structure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 読み込んだ構造物。ブロックを並べた直方体と、ブロックエンティティとエンティティのデータを持つ。
 * 大きすぎる構造物は、何ブロックかおきに間引いて持つ (step)。
 */
public final class Structure {

    // 持つブロックの数の上限。これより大きい構造物は間引く
    static final int MAX_CELLS = 32 * 1024 * 1024;
    // 1 辺の長さの上限
    static final int MAX_EDGE = 1 << 16;

    public enum Source {
        // Java 版の、名前で書かれたブロック (1.13 以降)
        JAVA,
        // Java 版の、番号で書かれたブロック (1.12 まで)
        LEGACY,
        // 統合版
        BEDROCK
    }

    public static final class Entity {
        public final String id;
        // 構造物の角を原点とした位置 (ブロック単位。間引く前の座標)
        public final double x, y, z;
        public final Map<String, Object> nbt;

        Entity(String id, double x, double y, double z, Map<String, Object> nbt) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.nbt = nbt;
        }
    }

    public final Source source;
    // 書いたゲームのデータのバージョン (分からなければ 0)
    public final int dataVersion;
    // 間引く前の大きさ
    public final int fullX, fullY, fullZ;
    // 何ブロックおきに持つか
    public final int step;
    public final int sizeX, sizeY, sizeZ;

    private final List<BlockState> palette = new ArrayList<BlockState>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<BlockState, Integer>();
    private final char[] blocks;
    // ます目の番号 -> ブロックエンティティのデータ
    private final Map<Integer, Map<String, Object>> blockEntities = new HashMap<Integer, Map<String, Object>>();
    private final List<Entity> entities = new ArrayList<Entity>();
    // バイオーム (なければ null)
    private List<String> biomePalette;
    private char[] biomes;

    Structure(Source source, int dataVersion, int x, int y, int z) throws IOException {
        if (x <= 0 || y <= 0 || z <= 0) throw new IOException("Empty structure (" + x + " x " + y + " x " + z + ")");
        if (x > MAX_EDGE || y > MAX_EDGE || z > MAX_EDGE) throw new IOException("Structure is too large (" + x + " x " + y + " x " + z + ")");
        this.source = source;
        this.dataVersion = dataVersion;
        this.fullX = x;
        this.fullY = y;
        this.fullZ = z;
        int s = 1;
        while ((long) ceil(x, s) * ceil(y, s) * ceil(z, s) > MAX_CELLS) s++;
        this.step = s;
        this.sizeX = ceil(x, s);
        this.sizeY = ceil(y, s);
        this.sizeZ = ceil(z, s);
        this.blocks = new char[sizeX * sizeY * sizeZ];
        palette.add(BlockState.AIR);
        paletteIndex.put(BlockState.AIR, 0);
    }

    // 2 x 2 x 2 のます目を 1 つにまとめたもの
    private Structure(Structure base) {
        this.source = base.source;
        this.dataVersion = base.dataVersion;
        this.fullX = base.fullX;
        this.fullY = base.fullY;
        this.fullZ = base.fullZ;
        this.step = base.step * 2;
        this.sizeX = ceil(base.sizeX, 2);
        this.sizeY = ceil(base.sizeY, 2);
        this.sizeZ = ceil(base.sizeZ, 2);
        this.blocks = new char[sizeX * sizeY * sizeZ];
        palette.addAll(base.palette);
        paletteIndex.putAll(base.paletteIndex);
        biomePalette = base.biomePalette;
        if (base.biomes != null) biomes = new char[blocks.length];
        entities.addAll(base.entities);
    }

    /**
     * 2 x 2 x 2 のます目を 1 つにまとめた構造物を返す。まとめたます目には、中の空気でないブロックのうち最も多いものを置く。
     */
    Structure halve() {
        final Structure result = new Structure(this);
        final int[] ids = new int[8];
        final int[] indices = new int[8];
        for (int y = 0; y < result.sizeY; y++) {
            for (int z = 0; z < result.sizeZ; z++) {
                for (int x = 0; x < result.sizeX; x++) {
                    int count = 0;
                    for (int d = 0; d < 8; d++) {
                        final int sx = x * 2 + (d & 1), sy = y * 2 + ((d >> 1) & 1), sz = z * 2 + ((d >> 2) & 1);
                        if (sx >= sizeX || sy >= sizeY || sz >= sizeZ) continue;
                        final int index = index(sx, sy, sz);
                        if (blocks[index] == 0) continue;
                        ids[count] = blocks[index];
                        indices[count] = index;
                        count++;
                    }
                    if (count == 0) continue;
                    int best = 0, bestCount = 0;
                    for (int i = 0; i < count; i++) {
                        int same = 0;
                        for (int j = 0; j < count; j++) {
                            if (ids[j] == ids[i]) same++;
                        }
                        if (same > bestCount) {
                            bestCount = same;
                            best = i;
                        }
                    }
                    final int target = result.index(x, y, z);
                    result.blocks[target] = (char) ids[best];
                    final Map<String, Object> entity = blockEntities.get(indices[best]);
                    if (entity != null) result.blockEntities.put(target, entity);
                    if (biomes != null) result.biomes[target] = biomes[indices[best]];
                }
            }
        }
        return result;
    }

    private static int ceil(int value, int step) {
        return (value + step - 1) / step;
    }

    public int volume() {
        return blocks.length;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    // パレットに加えて番号を返す
    int paletteId(BlockState state) {
        final Integer existing = paletteIndex.get(state);
        if (existing != null) return existing;
        if (palette.size() >= Character.MAX_VALUE) throw new IllegalStateException("Too many block states");
        final int id = palette.size();
        palette.add(state);
        paletteIndex.put(state, id);
        return id;
    }

    // 間引く前の座標に置く。間引いて持たない位置なら何もしない
    void set(int x, int y, int z, int paletteId) {
        if (x < 0 || y < 0 || z < 0 || x >= fullX || y >= fullY || z >= fullZ) return;
        if (step > 1 && (x % step != 0 || y % step != 0 || z % step != 0)) return;
        blocks[index(x / step, y / step, z / step)] = (char) paletteId;
    }

    void set(int x, int y, int z, BlockState state) {
        set(x, y, z, paletteId(state));
    }

    void setBlockEntity(int x, int y, int z, Map<String, Object> nbt) {
        if (x < 0 || y < 0 || z < 0 || x >= fullX || y >= fullY || z >= fullZ) return;
        if (step > 1 && (x % step != 0 || y % step != 0 || z % step != 0)) return;
        blockEntities.put(index(x / step, y / step, z / step), nbt);
    }

    void addEntity(String id, double x, double y, double z, Map<String, Object> nbt) {
        entities.add(new Entity(id, x, y, z, nbt));
    }

    void setBiomes(List<String> names, char[] values) {
        this.biomePalette = names;
        this.biomes = values;
    }

    // 持っている位置 (間引いたあとの座標) のブロックのパレット番号
    public int id(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    public int id(int index) {
        return blocks[index];
    }

    void setId(int index, int id) {
        blocks[index] = (char) id;
    }

    public BlockState state(int id) {
        return palette.get(id);
    }

    public int paletteSize() {
        return palette.size();
    }

    // パレットの中身を差し替える (名前の付け替えなどに使う)
    void replacePalette(int id, BlockState state) {
        paletteIndex.remove(palette.get(id));
        palette.set(id, state);
        if (!paletteIndex.containsKey(state)) paletteIndex.put(state, id);
    }

    public Map<String, Object> blockEntity(int index) {
        return blockEntities.get(index);
    }

    public Map<Integer, Map<String, Object>> blockEntities() {
        return blockEntities;
    }

    public List<Entity> entities() {
        return entities;
    }

    // ます目のバイオームの名前。分からなければ null
    public String biome(int index) {
        if (biomes == null) return null;
        final int id = biomes[index];
        return id < biomePalette.size() ? biomePalette.get(id) : null;
    }

    public boolean hasBiomes() {
        return biomes != null;
    }
}
