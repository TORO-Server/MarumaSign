package marumasa.marumasa_sign.core.structure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 構造物のファイルを読む。次の形式に対応する。
 * <ul>
 *     <li>.schem (Sponge Schematic バージョン 1 - 3。WorldEdit など)</li>
 *     <li>.litematic (Litematica)</li>
 *     <li>.nbt (ストラクチャーブロック)</li>
 *     <li>.mcstructure (統合版のストラクチャーブロック)</li>
 *     <li>.schematic (MCEdit / 古い WorldEdit / Schematica。1.12 までのブロックの番号)</li>
 * </ul>
 * 形式は拡張子ではなく中身で判別する。
 */
public final class StructureReader {

    // 展開したあとの大きさの上限
    // (小さく圧縮した巨大なファイルで、ゲームのメモリを使い切らせないように)
    static final int MAX_DECOMPRESSED = 96 * 1024 * 1024;
    // 読むます目の数の上限 (Litematica は領域の合計。壊れたファイルで、長い時間かからないように)
    static final long MAX_SCANNED = 128L * 1024 * 1024;

    private StructureReader() {
    }

    /**
     * 先頭のバイト列が構造物のファイルらしいかどうか。
     * gzip で圧縮されたもの (統合版以外の形式) と、圧縮されていない NBT。
     */
    public static boolean looksLikeStructure(byte[] head, int length) {
        if (length >= 3 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B && head[2] == 8) {
            return gzipLooksLikeNbt(head, length);
        }
        return Nbt.looksLikeNbt(head, length, false) || Nbt.looksLikeNbt(head, length, true);
    }

    // gzip の中身の先頭を展開して、NBT らしいかを見る (ほかの gzip のファイルを読まないように)
    private static boolean gzipLooksLikeNbt(byte[] head, int length) {
        try {
            final java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(head, 0, length));
            final byte[] inner = new byte[64];
            int filled = 0;
            try {
                while (filled < inner.length) {
                    final int read = input.read(inner, filled, inner.length - filled);
                    if (read < 0) break;
                    filled += read;
                }
            } catch (java.io.EOFException e) {
                // 先頭しか渡していないので、途中で終わるのは普通
            }
            return filled >= 6 && Nbt.looksLikeNbt(inner, filled, false);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 構造物のファイルを読む。
     *
     * @param content ファイルの内容 (圧縮されたままでよい)
     */
    public static Structure read(byte[] content) throws IOException {
        final byte[] data = Nbt.decompress(content, MAX_DECOMPRESSED);
        final boolean compressed = data != content;
        // 統合版は圧縮されておらず、リトルエンディアン
        if (!compressed && Nbt.looksLikeNbt(data, Math.min(data.length, 64), true) && !Nbt.looksLikeNbt(data, Math.min(data.length, 64), false)) {
            return bedrock(Nbt.read(data, true).value);
        }
        Nbt.Root root;
        try {
            root = Nbt.read(data, false);
        } catch (IOException e) {
            if (compressed) throw e;
            root = null;
        }
        if (root == null || (!compressed && looksBedrock(root.value))) {
            final Nbt.Root little = Nbt.read(data, true);
            if (looksBedrock(little.value)) return bedrock(little.value);
            if (root == null) throw new IOException("Not a structure file");
        }
        final Map<String, Object> value = root.value;
        if (value.get("Schematic") instanceof Map) {
            // Sponge Schematic バージョン 3 は、名前のない複合タグの中に Schematic がある
            return sponge(Nbt.compound(value, "Schematic"));
        }
        if (value.containsKey("Regions") && value.containsKey("Version")) return litematic(value);
        if (value.containsKey("Blocks") && value.containsKey("Width") && value.get("Blocks") instanceof byte[]) return mcedit(value);
        if (value.containsKey("Palette") || value.containsKey("BlockData")) return sponge(value);
        // バージョン 3 なのに Schematic の中に入れずに書いたもの
        if (value.get("Blocks") instanceof Map && Nbt.integer(value, "Version", 0) >= 3) return sponge(value);
        if (value.containsKey("blocks") && value.containsKey("size")) return template(value);
        if (looksBedrock(value)) return bedrock(value);
        throw new IOException("Unknown structure format");
    }

    // パレットの数を確かめる。状態の種類は MAX_PALETTE までしか持てないので、それより多いものは壊れたファイルとして読まない
    // (1 つずつ名前を読んで状態に直すのに時間がかからないように、直す前に確かめる)
    private static void checkPaletteSize(int size) throws IOException {
        if (size > Structure.MAX_PALETTE) throw new IOException("Too many block states in the palette: " + size);
    }

    private static boolean looksBedrock(Map<String, Object> root) {
        return root.containsKey("structure") && root.containsKey("size") && root.containsKey("format_version");
    }

    // ---------- Sponge Schematic (.schem) ----------

    private static Structure sponge(Map<String, Object> schematic) throws IOException {
        final int version = Nbt.integer(schematic, "Version", 1);
        final int width = Nbt.integer(schematic, "Width", 0) & 0xFFFF;
        final int height = Nbt.integer(schematic, "Height", 0) & 0xFFFF;
        final int length = Nbt.integer(schematic, "Length", 0) & 0xFFFF;
        // バージョン 1 にはデータのバージョンがない (1.13 のころの形式)
        final int dataVersion = Nbt.integer(schematic, "DataVersion", 1519);
        final Structure structure = new Structure(Structure.Source.JAVA, dataVersion, width, height, length);

        final Map<String, Object> blocks = version >= 3 ? Nbt.compound(schematic, "Blocks") : schematic;
        final Map<String, Object> palette = Nbt.compound(blocks, "Palette");
        checkPaletteSize(palette.size());
        final byte[] data = Nbt.bytes(blocks, version >= 3 ? "Data" : "BlockData");
        if (data == null) throw new IOException("Schematic has no block data");
        int maxIndex = 0;
        for (Map.Entry<String, Object> entry : palette.entrySet()) maxIndex = Math.max(maxIndex, Nbt.integer(entry.getValue(), 0));
        // 番号はパレットの数より小さいはず。壊れたファイルで大きな配列を作らないようにする
        if (maxIndex >= Math.max(1024, palette.size() * 4)) throw new IOException("Invalid schematic palette index: " + maxIndex);
        final int[] mapping = new int[maxIndex + 1];
        for (Map.Entry<String, Object> entry : palette.entrySet()) {
            final int index = Nbt.integer(entry.getValue(), 0);
            if (index >= 0) mapping[index] = structure.paletteId(BlockState.parse(entry.getKey()));
        }

        // 番号は可変長の整数 (7 ビットずつ) で並んでいる。並びは x が最も速く、次に z、最後に y
        int position = 0;
        long index = 0;
        final long total = (long) width * height * length;
        while (index < total && position < data.length) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                if (position >= data.length) throw new IOException("Truncated block data");
                b = data[position++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("Invalid block data");
            } while ((b & 0x80) != 0);
            final int x = (int) (index % width);
            final int z = (int) ((index / width) % length);
            final int y = (int) (index / ((long) width * length));
            if (value >= 0 && value < mapping.length) structure.set(x, y, z, mapping[value]);
            index++;
        }

        // ブロックエンティティ (バージョン 1 は TileEntities、2 は BlockEntities、3 は Blocks の中の BlockEntities)
        List<Object> entities = Nbt.list(blocks, "BlockEntities");
        if (entities.isEmpty()) entities = Nbt.list(schematic, "TileEntities");
        for (Object item : entities) {
            final Map<String, Object> entity = Nbt.compound(item);
            final int[] pos = Nbt.ints(entity.get("Pos"));
            if (pos == null || pos.length < 3) continue;
            Map<String, Object> nbt = entity;
            if (version >= 3 && entity.get("Data") instanceof Map) {
                nbt = new HashMap<String, Object>(Nbt.compound(entity, "Data"));
                nbt.put("id", Nbt.string(entity, "Id", ""));
            } else if (!entity.containsKey("id")) {
                nbt = new HashMap<String, Object>(entity);
                nbt.put("id", Nbt.string(entity, "Id", ""));
            }
            structure.setBlockEntity(pos[0], pos[1], pos[2], nbt);
        }

        // バージョン 2 までのエンティティの位置は、構造物の角からではなく、ワールドの座標で書かれている (WorldEdit、FastAsyncWorldEdit)。
        // 構造物の角のワールドの座標は Offset にある。バージョン 3 からは構造物の角からの位置
        final int[] offset = version < 3 ? Nbt.ints(schematic.get("Offset")) : null;
        for (Object item : Nbt.list(schematic, "Entities")) {
            final Map<String, Object> entity = Nbt.compound(item);
            final double[] pos = relative(Nbt.doubles(entity.get("Pos")), offset, structure);
            if (pos == null) continue;
            Map<String, Object> nbt = entity;
            if (version >= 3 && entity.get("Data") instanceof Map) nbt = Nbt.compound(entity, "Data");
            structure.addEntity(Nbt.string(entity, "Id", Nbt.string(nbt, "id", "")), pos[0], pos[1], pos[2], nbt);
        }

        readSpongeBiomes(structure, version, version >= 3 ? Nbt.compound(schematic, "Biomes") : schematic, width, height, length);
        return structure;
    }

    /**
     * ワールドの座標で書かれたエンティティの位置を、構造物の角からの位置にする。位置がなければ null。
     * 角の座標を引くと構造物から外れるのに、引かなければ中にあるもの (構造物の角からの位置で書く道具で作ったもの) は、そのまま使う。
     *
     * @param origin 構造物の角のワールドの座標 (分からなければ null)
     */
    private static double[] relative(double[] pos, int[] origin, Structure structure) {
        if (pos == null || pos.length < 3) return null;
        if (origin == null || origin.length < 3) return pos;
        final double[] moved = {pos[0] - origin[0], pos[1] - origin[1], pos[2] - origin[2]};
        return !inside(moved, structure) && inside(pos, structure) ? pos : moved;
    }

    // 構造物の中 (まわり 1 ブロックを含む) かどうか
    private static boolean inside(double[] pos, Structure structure) {
        return pos[0] >= -1 && pos[0] <= structure.fullX + 1 && pos[1] >= -1 && pos[1] <= structure.fullY + 1
                && pos[2] >= -1 && pos[2] <= structure.fullZ + 1;
    }

    private static void readSpongeBiomes(Structure structure, int version, Map<String, Object> source, int width, int height, int length) {
        final Map<String, Object> palette = Nbt.compound(source, version >= 3 ? "Palette" : "BiomePalette");
        final byte[] data = Nbt.bytes(source, version >= 3 ? "Data" : "BiomeData");
        if (palette.isEmpty() || data == null) return;
        final List<String> names = new ArrayList<String>();
        final Map<Integer, Integer> remap = new HashMap<Integer, Integer>();
        for (Map.Entry<String, Object> entry : palette.entrySet()) {
            remap.put(Nbt.integer(entry.getValue(), 0), names.size());
            names.add(BlockState.namespaced(entry.getKey()));
        }
        final char[] biomes = new char[structure.volume()];
        // バージョン 2 は x, z の 2 次元、バージョン 3 は 3 次元
        final long total = version >= 3 ? (long) width * height * length : (long) width * length;
        int position = 0;
        for (long index = 0; index < total && position < data.length; index++) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                if (position >= data.length) return;
                b = data[position++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) return;
            } while ((b & 0x80) != 0);
            final Integer mapped = remap.get(value);
            if (mapped == null) continue;
            final int x = (int) (index % width);
            final int z = (int) ((index / width) % length);
            final int step = structure.step;
            if (x % step != 0 || z % step != 0) continue;
            if (version >= 3) {
                final int y = (int) (index / ((long) width * length));
                if (y % step != 0) continue;
                biomes[structure.index(x / step, y / step, z / step)] = (char) (int) mapped;
            } else {
                for (int y = 0; y < structure.sizeY; y++) biomes[structure.index(x / step, y, z / step)] = (char) (int) mapped;
            }
        }
        structure.setBiomes(names, biomes);
    }

    // ---------- Litematica (.litematic) ----------

    private static Structure litematic(Map<String, Object> root) throws IOException {
        final int dataVersion = Nbt.integer(root, "MinecraftDataVersion", 1631);
        final Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions.isEmpty()) throw new IOException("Litematic has no regions");

        // すべての領域を囲む範囲。位置と大きさは int の範囲のどこにでも書けるので、足し算があふれないように long で計算する
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (Object item : regions.values()) {
            final Map<String, Object> region = Nbt.compound(item);
            final long[] min = regionMin(region);
            final int[] size = regionSize(region);
            minX = Math.min(minX, min[0]);
            minY = Math.min(minY, min[1]);
            minZ = Math.min(minZ, min[2]);
            maxX = Math.max(maxX, min[0] + size[0]);
            maxY = Math.max(maxY, min[1] + size[1]);
            maxZ = Math.max(maxZ, min[2] + size[2]);
        }
        if (maxX - minX > Structure.MAX_EDGE || maxY - minY > Structure.MAX_EDGE || maxZ - minZ > Structure.MAX_EDGE) {
            throw new IOException("Litematic is too large (" + (maxX - minX) + " x " + (maxY - minY) + " x " + (maxZ - minZ) + ")");
        }
        final Structure structure = new Structure(Structure.Source.JAVA, dataVersion, (int) (maxX - minX), (int) (maxY - minY), (int) (maxZ - minZ));

        long scanned = 0;
        for (Object item : regions.values()) {
            final Map<String, Object> region = Nbt.compound(item);
            final long[] min = regionMin(region);
            final int[] size = regionSize(region);
            final int offsetX = (int) (min[0] - minX), offsetY = (int) (min[1] - minY), offsetZ = (int) (min[2] - minZ);

            final List<Object> paletteList = Nbt.list(region, "BlockStatePalette");
            checkPaletteSize(paletteList.size());
            final int[] mapping = new int[Math.max(1, paletteList.size())];
            for (int i = 0; i < paletteList.size(); i++) {
                final Map<String, Object> entry = Nbt.compound(paletteList.get(i));
                mapping[i] = structure.paletteId(stateFromNbt(entry));
            }
            final Object statesValue = region.get("BlockStates");
            final long[] states = statesValue instanceof long[] ? (long[]) statesValue : new long[0];
            // 1 つの番号のビット数。番号は long の境目をまたいで詰められている
            final int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteList.size()) - 1));
            final long mask = (1L << bits) - 1L;
            // 1 辺は囲む範囲 (MAX_EDGE) に収まっているので、かけ算はあふれない
            final long total = (long) size[0] * size[1] * size[2];
            scanned += total;
            if (scanned > MAX_SCANNED) throw new IOException("Litematic regions are too large");
            if ((total * bits + 63) / 64 > states.length) throw new IOException("Litematic block data is too short");
            for (int y = 0; y < size[1]; y++) {
                for (int z = 0; z < size[2]; z++) {
                    for (int x = 0; x < size[0]; x++) {
                        final long index = ((long) y * size[2] + z) * size[0] + x;
                        final long start = index * bits;
                        final int startLong = (int) (start >>> 6);
                        final int endLong = (int) ((start + bits - 1) >>> 6);
                        final int startBit = (int) (start & 63);
                        final long value;
                        if (startLong == endLong) {
                            value = (states[startLong] >>> startBit) & mask;
                        } else {
                            value = ((states[startLong] >>> startBit) | (states[endLong] << (64 - startBit))) & mask;
                        }
                        if (value < mapping.length) structure.set(offsetX + x, offsetY + y, offsetZ + z, mapping[(int) value]);
                    }
                }
            }

            for (Object entityItem : Nbt.list(region, "TileEntities")) {
                final Map<String, Object> entity = Nbt.compound(entityItem);
                structure.setBlockEntity(offsetX + Nbt.integer(entity, "x", 0), offsetY + Nbt.integer(entity, "y", 0),
                        offsetZ + Nbt.integer(entity, "z", 0), entity);
            }
            // エンティティの位置は、最も小さい角ではなく、領域の位置 (Position) からの位置
            // (大きさが負の領域では、両者がずれる)
            final Map<String, Object> position = Nbt.compound(region, "Position");
            final long entityX = Nbt.integer(position, "x", 0) - minX;
            final long entityY = Nbt.integer(position, "y", 0) - minY;
            final long entityZ = Nbt.integer(position, "z", 0) - minZ;
            for (Object entityItem : Nbt.list(region, "Entities")) {
                final Map<String, Object> entity = Nbt.compound(entityItem);
                final double[] pos = Nbt.doubles(entity.get("Pos"));
                if (pos == null || pos.length < 3) continue;
                structure.addEntity(Nbt.string(entity, "id", ""), entityX + pos[0], entityY + pos[1], entityZ + pos[2], entity);
            }
        }
        return structure;
    }

    // 領域の大きさ (負の向きに広がるものも正の値にする)。1 辺が MAX_EDGE を超える領域は読まない
    private static int[] regionSize(Map<String, Object> region) throws IOException {
        final Map<String, Object> size = Nbt.compound(region, "Size");
        final int[] result = new int[3];
        final String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            final long value = Math.abs((long) Nbt.integer(size, axes[i], 0));
            if (value > Structure.MAX_EDGE) throw new IOException("Litematic region is too large");
            result[i] = (int) value;
        }
        return result;
    }

    // 領域の最も小さい角。大きさが負なら、位置から負の向きに広がっている
    private static long[] regionMin(Map<String, Object> region) {
        final Map<String, Object> position = Nbt.compound(region, "Position");
        final Map<String, Object> size = Nbt.compound(region, "Size");
        final long[] result = new long[3];
        final String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            final long p = Nbt.integer(position, axes[i], 0);
            final long s = Nbt.integer(size, axes[i], 0);
            result[i] = s < 0 ? p + s + 1 : p;
        }
        return result;
    }

    // {Name, Properties} (26.1 からは {id, properties}) の形のブロックの状態
    private static BlockState stateFromNbt(Map<String, Object> entry) {
        final Map<String, String> properties = new TreeMap<String, String>();
        final Map<String, Object> source = entry.containsKey("Properties") ? Nbt.compound(entry, "Properties") : Nbt.compound(entry, "properties");
        for (Map.Entry<String, Object> property : source.entrySet()) {
            properties.put(property.getKey(), String.valueOf(property.getValue()));
        }
        final String name = entry.containsKey("Name") ? Nbt.string(entry, "Name", "minecraft:air") : Nbt.string(entry, "id", "minecraft:air");
        return BlockState.of(name, properties);
    }

    // ---------- ストラクチャーブロック (.nbt) ----------

    private static Structure template(Map<String, Object> root) throws IOException {
        final int[] size = Nbt.ints(root.get("size"));
        if (size == null || size.length < 3) throw new IOException("Structure has no size");
        final int dataVersion = Nbt.integer(root, "DataVersion", 1343);
        final Structure structure = new Structure(Structure.Source.JAVA, dataVersion, size[0], size[1], size[2]);

        List<Object> paletteList = Nbt.list(root, "palette");
        if (paletteList.isEmpty()) {
            // 置くたびに選ばれる複数のパレットがある場合は、最初のものを使う
            final List<Object> palettes = Nbt.list(root, "palettes");
            if (!palettes.isEmpty()) paletteList = Nbt.list(palettes.get(0));
        }
        checkPaletteSize(paletteList.size());
        final int[] mapping = new int[paletteList.size()];
        for (int i = 0; i < mapping.length; i++) mapping[i] = structure.paletteId(stateFromNbt(Nbt.compound(paletteList.get(i))));

        for (Object item : Nbt.list(root, "blocks")) {
            final Map<String, Object> block = Nbt.compound(item);
            final int[] pos = Nbt.ints(block.get("pos"));
            final int state = Nbt.integer(block, "state", -1);
            if (pos == null || pos.length < 3 || state < 0 || state >= mapping.length) continue;
            structure.set(pos[0], pos[1], pos[2], mapping[state]);
            if (block.get("nbt") instanceof Map) structure.setBlockEntity(pos[0], pos[1], pos[2], Nbt.compound(block, "nbt"));
        }
        for (Object item : Nbt.list(root, "entities")) {
            final Map<String, Object> entity = Nbt.compound(item);
            final double[] pos = Nbt.doubles(entity.get("pos"));
            final Map<String, Object> nbt = Nbt.compound(entity, "nbt");
            if (pos == null || pos.length < 3) continue;
            structure.addEntity(Nbt.string(nbt, "id", ""), pos[0], pos[1], pos[2], nbt);
        }
        return structure;
    }

    // ---------- 統合版 (.mcstructure) ----------

    private static Structure bedrock(Map<String, Object> root) throws IOException {
        final int[] size = Nbt.ints(root.get("size"));
        if (size == null || size.length < 3) throw new IOException("Structure has no size");
        final Structure structure = new Structure(Structure.Source.BEDROCK, 0, size[0], size[1], size[2]);
        final Map<String, Object> body = Nbt.compound(root, "structure");
        final List<Object> layers = Nbt.list(body, "block_indices");
        final Map<String, Object> palette = Nbt.compound(Nbt.compound(body, "palette"), "default");
        final List<Object> paletteList = Nbt.list(palette, "block_palette");
        checkPaletteSize(paletteList.size());

        final int[] mapping = new int[paletteList.size()];
        final boolean[] water = new boolean[paletteList.size()];
        final BlockState[] states = new BlockState[paletteList.size()];
        for (int i = 0; i < mapping.length; i++) {
            final Map<String, Object> entry = Nbt.compound(paletteList.get(i));
            final String name = BlockState.namespaced(Nbt.string(entry, "name", "minecraft:air"));
            final Map<String, String> values = new TreeMap<String, String>();
            for (Map.Entry<String, Object> state : Nbt.compound(entry, "states").entrySet()) {
                values.put(state.getKey(), String.valueOf(state.getValue()));
            }
            BlockState java = BlockTables.bedrock(name, values);
            if (java == null) {
                // 表にないブロックは、同じ名前の Java 版のブロックとして扱う
                java = BlockState.of(name, new TreeMap<String, String>());
            }
            states[i] = java;
            water[i] = name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
            mapping[i] = structure.paletteId(java);
        }

        final int[] primary = layers.size() > 0 ? Nbt.ints(layers.get(0)) : null;
        final int[] secondary = layers.size() > 1 ? Nbt.ints(layers.get(1)) : null;
        if (primary == null) throw new IOException("Structure has no blocks");
        final int sx = size[0], sy = size[1], sz = size[2];
        final Map<Integer, Integer> waterlogged = new HashMap<Integer, Integer>();
        final long cells = Math.min((long) sx * sy * sz, primary.length);
        for (int index = 0; index < cells; index++) {
            // 並びは z が最も速く、次に y、最後に x
            final int z = index % sz;
            final int y = (index / sz) % sy;
            final int x = (int) (index / ((long) sz * sy));
            final int id = primary[index];
            if (id < 0 || id >= mapping.length) continue;
            int paletteId = mapping[id];
            // 2 層目の水は、1 層目のブロックが水に浸かっていることを表す
            if (secondary != null && index < secondary.length) {
                final int second = secondary[index];
                if (second >= 0 && second < water.length && water[second]) {
                    final BlockState state = states[id];
                    if (state.isAir()) {
                        paletteId = mapping[second];
                    } else {
                        Integer logged = waterlogged.get(id);
                        if (logged == null) {
                            logged = structure.paletteId(state.with("waterlogged", "true"));
                            waterlogged.put(id, logged);
                        }
                        paletteId = logged;
                    }
                }
            }
            structure.set(x, y, z, paletteId);
        }

        // ブロックエンティティは、ます目の番号 (上と同じ並び) ごとに書かれている
        for (Map.Entry<String, Object> entry : Nbt.compound(palette, "block_position_data").entrySet()) {
            final int index;
            try {
                index = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            final Map<String, Object> data = Nbt.compound(Nbt.compound(entry.getValue()), "block_entity_data");
            if (data.isEmpty()) continue;
            final int z = index % sz;
            final int y = (index / sz) % sy;
            final int x = (int) (index / ((long) sz * sy));
            structure.setBlockEntity(x, y, z, data);
        }

        final int[] origin = Nbt.ints(root.get("structure_world_origin"));
        for (Object item : Nbt.list(body, "entities")) {
            final Map<String, Object> entity = Nbt.compound(item);
            final double[] pos = Nbt.doubles(entity.get("Pos"));
            if (pos == null || pos.length < 3) continue;
            final double ox = origin != null && origin.length >= 3 ? origin[0] : 0;
            final double oy = origin != null && origin.length >= 3 ? origin[1] : 0;
            final double oz = origin != null && origin.length >= 3 ? origin[2] : 0;
            structure.addEntity(Nbt.string(entity, "identifier", ""), pos[0] - ox, pos[1] - oy, pos[2] - oz, entity);
        }
        return structure;
    }

    // ---------- MCEdit / 古い WorldEdit / Schematica (.schematic) ----------

    private static Structure mcedit(Map<String, Object> root) throws IOException {
        final int width = Nbt.integer(root, "Width", 0) & 0xFFFF;
        final int height = Nbt.integer(root, "Height", 0) & 0xFFFF;
        final int length = Nbt.integer(root, "Length", 0) & 0xFFFF;
        final String materials = Nbt.string(root, "Materials", "Alpha");
        if (!"Alpha".equals(materials)) throw new IOException("Unsupported schematic materials: " + materials);
        final byte[] blocks = Nbt.bytes(root, "Blocks");
        final byte[] data = Nbt.bytes(root, "Data");
        final byte[] add = Nbt.bytes(root, "AddBlocks") != null ? Nbt.bytes(root, "AddBlocks") : Nbt.bytes(root, "Add");
        final long total = (long) width * height * length;
        if (blocks == null || blocks.length < total) throw new IOException("Schematic block data is too short");
        final Structure structure = new Structure(Structure.Source.LEGACY, 0, width, height, length);

        // 番号を名前で書いたもの (Schematica と MCEdit 2)。標準の番号に付け替える
        final Map<Integer, Integer> remap = new HashMap<Integer, Integer>();
        readIdMapping(Nbt.compound(root, "SchematicaMapping"), true, remap);
        readIdMapping(Nbt.compound(root, "BlockIDs"), false, remap);

        final Map<Integer, Integer> cache = new HashMap<Integer, Integer>();
        for (int index = 0; index < total; index++) {
            int id = blocks[index] & 0xFF;
            if (add != null && (index >> 1) < add.length) {
                id |= (index & 1) == 0 ? (add[index >> 1] & 0x0F) << 8 : (add[index >> 1] & 0xF0) << 4;
            }
            if (id == 0) continue;
            final Integer mapped = remap.get(id);
            if (mapped != null) id = mapped;
            final int meta = data != null && index < data.length ? data[index] & 0x0F : 0;
            final int key = id << 4 | meta;
            Integer paletteId = cache.get(key);
            if (paletteId == null) {
                final BlockState state = BlockTables.legacy(id, meta);
                paletteId = state == null ? 0 : structure.paletteId(state);
                cache.put(key, paletteId);
            }
            if (paletteId == 0) continue;
            final int x = index % width;
            final int z = (index / width) % length;
            final int y = index / (width * length);
            structure.set(x, y, z, paletteId);
        }

        for (Object item : Nbt.list(root, "TileEntities")) {
            final Map<String, Object> entity = Nbt.compound(item);
            structure.setBlockEntity(Nbt.integer(entity, "x", 0), Nbt.integer(entity, "y", 0), Nbt.integer(entity, "z", 0), entity);
        }
        // WorldEdit で書いたものは、エンティティの位置がワールドの座標で、構造物の角のワールドの座標が WEOrigin にある
        // (MCEdit と Schematica は構造物の角からの位置)
        final int[] origin = Nbt.has(root, "WEOriginX") && Nbt.has(root, "WEOriginY") && Nbt.has(root, "WEOriginZ")
                ? new int[]{Nbt.integer(root, "WEOriginX", 0), Nbt.integer(root, "WEOriginY", 0), Nbt.integer(root, "WEOriginZ", 0)} : null;
        for (Object item : Nbt.list(root, "Entities")) {
            final Map<String, Object> entity = Nbt.compound(item);
            final double[] pos = relative(Nbt.doubles(entity.get("Pos")), origin, structure);
            if (pos == null) continue;
            structure.addEntity(Nbt.string(entity, "id", ""), pos[0], pos[1], pos[2], entity);
        }
        return structure;
    }

    /**
     * 番号と名前の対応を読む。
     *
     * @param nameToId 名前 -> 番号 の形 (Schematica) なら true、番号 -> 名前 の形 (MCEdit 2) なら false
     */
    private static void readIdMapping(Map<String, Object> mapping, boolean nameToId, Map<Integer, Integer> remap) throws IOException {
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            final String name;
            final int id;
            try {
                if (nameToId) {
                    name = entry.getKey();
                    id = Nbt.integer(entry.getValue(), -1);
                } else {
                    name = String.valueOf(entry.getValue());
                    id = Integer.parseInt(entry.getKey());
                }
            } catch (NumberFormatException e) {
                continue;
            }
            final int standard = BlockTables.legacyId(name);
            if (id >= 0 && standard >= 0 && standard != id) remap.put(id, standard);
        }
    }
}
