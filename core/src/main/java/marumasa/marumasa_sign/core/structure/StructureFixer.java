package marumasa.marumasa_sign.core.structure;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 読み込んだ構造物のブロックを、表示に使う Minecraft のバージョン (BlockData の版) の状態にそろえる。
 * <ul>
 *     <li>名前の変わったブロックの付け替え、性質の値の変わったものの直し</li>
 *     <li>1.12 までの形式で、ブロックエンティティに書かれていた色や種類 (ベッド、旗、頭、植木鉢) をブロックに移す</li>
 *     <li>1.12 までの形式と統合版で、まわりのブロックで決まる形 (フェンスのつながり、階段の角など) を計算する</li>
 *     <li>足りない性質を既定の値で埋める</li>
 * </ul>
 */
final class StructureFixer {

    // 方向 (下、上、北、南、西、東)
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final int[] DX = {0, 0, 0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0, 0, 0};
    static final int[] DZ = {0, 0, -1, 1, 0, 0};
    static final int[] OPPOSITE = {UP, DOWN, SOUTH, NORTH, EAST, WEST};
    static final String[] NAMES = {"down", "up", "north", "south", "west", "east"};
    // 水平の方向を時計回りに回したもの (上から見て)
    static final int[] CLOCKWISE = {DOWN, UP, EAST, WEST, NORTH, SOUTH};
    static final int[] COUNTER_CLOCKWISE = {DOWN, UP, WEST, EAST, SOUTH, NORTH};
    static final int[] HORIZONTAL = {NORTH, SOUTH, WEST, EAST};

    static final String[] DYE_COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    // 赤石の線がつながる、信号を出すブロック
    private static final Set<String> SIGNAL_SOURCES = new HashSet<String>();

    static {
        final String[] names = {"redstone_torch", "redstone_wall_torch", "lever", "redstone_block", "daylight_detector",
                "comparator", "trapped_chest", "tripwire_hook", "detector_rail", "sculk_sensor", "calibrated_sculk_sensor",
                "lectern", "target", "lightning_rod", "redstone_wire", "observer", "repeater"};
        for (String name : names) SIGNAL_SOURCES.add("minecraft:" + name);
    }

    private final Structure structure;
    private final BlockData data;
    // パレットの番号ごとの、表での状態の番号 (表にないブロックは -1)
    private int[] stateIndex;

    private StructureFixer(Structure structure, BlockData data) {
        this.structure = structure;
        this.data = data;
    }

    static void fix(Structure structure) throws IOException {
        final StructureFixer fixer = new StructureFixer(structure, BlockData.get());
        fixer.renamePalette();
        if (structure.source == Structure.Source.LEGACY) fixer.applyLegacyBlockEntities();
        if (structure.source == Structure.Source.BEDROCK) fixer.applyBedrockBlockEntities();
        fixer.completePalette();
        if (structure.source != Structure.Source.JAVA) fixer.updateShapes();
    }

    // ---------- 名前と値 ----------

    private void renamePalette() {
        for (int id = 1; id < structure.paletteSize(); id++) {
            final BlockState original = structure.state(id);
            final BlockState renamed = rename(original);
            if (renamed != original) structure.replacePalette(id, renamed);
        }
    }

    private BlockState rename(BlockState state) {
        final String name = state.name;
        if (name.equals("minecraft:grass")) return state.withName("minecraft:short_grass");
        if (name.equals("minecraft:grass_path")) return state.withName("minecraft:dirt_path");
        if (name.equals("minecraft:sign")) return state.withName("minecraft:oak_sign");
        if (name.equals("minecraft:wall_sign")) return state.withName("minecraft:oak_wall_sign");
        if (name.equals("minecraft:chain")) return state.withName("minecraft:iron_chain");
        // 1.14 より前の stone_slab は、今の smooth_stone_slab
        if (name.equals("minecraft:stone_slab") && (structure.source == Structure.Source.LEGACY
                || (structure.source == Structure.Source.JAVA && structure.dataVersion < 1901))) {
            return state.withName("minecraft:smooth_stone_slab");
        }
        // 1.17 より前の大釜は、水の量を持っていた
        if (name.equals("minecraft:cauldron")) {
            final String level = state.get("level");
            if (level != null && !level.equals("0")) return BlockState.parse("minecraft:water_cauldron[level=" + level + "]");
        }
        // 1.16 より前の壁は、つながりを true / false で持っていた
        if (state.get("up") != null && name.endsWith("_wall")) {
            BlockState result = state;
            for (int d : HORIZONTAL) {
                final String value = state.get(NAMES[d]);
                if ("true".equals(value)) result = result.with(NAMES[d], "low");
                else if ("false".equals(value)) result = result.with(NAMES[d], "none");
            }
            return result;
        }
        return state;
    }

    // 足りない性質を既定の値で埋める (表にないブロックはそのまま)
    private void completePalette() {
        stateIndex = new int[structure.paletteSize()];
        for (int id = 0; id < structure.paletteSize(); id++) {
            final BlockState state = structure.state(id);
            final BlockData.Block block = data.block(state.name);
            if (block == null) {
                stateIndex[id] = -1;
                continue;
            }
            final BlockState complete = data.complete(block, state);
            if (!complete.equals(state)) structure.replacePalette(id, complete);
            stateIndex[id] = data.stateIndex(block, complete);
        }
    }

    private int paletteId(BlockState state) throws IOException {
        final int before = structure.paletteSize();
        final int id = structure.paletteId(state);
        if (id >= before && stateIndex != null) {
            // 増えるたびに写し直すと、状態の多い構造物で時間がかかるので、倍ずつ広げる
            if (id >= stateIndex.length) stateIndex = java.util.Arrays.copyOf(stateIndex, Math.max(id + 1, stateIndex.length * 2));
            final BlockData.Block block = data.block(state.name);
            stateIndex[id] = block == null ? -1 : data.stateIndex(block, state);
        }
        return id;
    }

    // ---------- 1.12 までのブロックエンティティ ----------

    private void applyLegacyBlockEntities() throws IOException {
        for (Map.Entry<Integer, Map<String, Object>> entry : structure.blockEntities().entrySet()) {
            final int index = entry.getKey();
            final Map<String, Object> nbt = entry.getValue();
            final BlockState state = structure.state(structure.id(index));
            final String id = Nbt.string(nbt, "id", "").toLowerCase(java.util.Locale.ROOT);
            BlockState changed = null;
            if (state.path().endsWith("_bed") && (id.endsWith("bed"))) {
                final int color = Nbt.integer(nbt, "color", 14);
                changed = state.withName("minecraft:" + DYE_COLORS[color & 15] + "_bed");
            } else if (state.path().endsWith("_banner") && id.endsWith("banner")) {
                // 1.12 の旗の色は、染料の番号 (0 が黒) で書かれている
                final int base = Nbt.integer(nbt, "Base", 0);
                final String color = DYE_COLORS[15 - (base & 15)];
                changed = state.withName("minecraft:" + color + (state.path().endsWith("_wall_banner") ? "_wall_banner" : "_banner"));
            } else if (state.path().endsWith("_skull") || state.path().endsWith("_head")) {
                if (id.endsWith("skull")) changed = skull(state, nbt);
            } else if (state.path().equals("flower_pot") || state.path().startsWith("potted_")) {
                if (id.endsWith("flower_pot") || id.endsWith("flowerpot")) changed = flowerPot(nbt);
            }
            if (changed != null && !changed.equals(state)) structure.setId(index, structure.paletteId(changed));
        }
    }

    // 統合版のブロックエンティティに書かれた色や種類を、ブロックに移す
    private void applyBedrockBlockEntities() throws IOException {
        for (Map.Entry<Integer, Map<String, Object>> entry : structure.blockEntities().entrySet()) {
            final int index = entry.getKey();
            final Map<String, Object> nbt = entry.getValue();
            final BlockState state = structure.state(structure.id(index));
            final String id = Nbt.string(nbt, "id", "");
            final String path = state.path();
            BlockState changed = null;
            if (path.endsWith("_bed") && id.equals("Bed")) {
                changed = state.withName("minecraft:" + DYE_COLORS[Nbt.integer(nbt, "color", 14) & 15] + "_bed");
            } else if (path.endsWith("_banner") && id.equals("Banner")) {
                final String color = DYE_COLORS[15 - (Nbt.integer(nbt, "Base", 0) & 15)];
                changed = state.withName("minecraft:" + color + (path.endsWith("_wall_banner") ? "_wall_banner" : "_banner"));
            } else if ((path.endsWith("_skull") || path.endsWith("_head")) && id.equals("Skull")) {
                BlockState skull = state;
                if (nbt.containsKey("SkullType") && path.startsWith("skeleton_")) {
                    final String[] types = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head", "piglin_head"};
                    final int type = Nbt.integer(nbt, "SkullType", 0);
                    String name = types[type >= 0 && type < types.length ? type : 0];
                    if (path.contains("_wall_")) name = name.endsWith("_skull") ? name.replace("_skull", "_wall_skull") : name.replace("_head", "_wall_head");
                    skull = skull.withName("minecraft:" + name);
                }
                if (!path.contains("_wall_")) {
                    final float rotation = (float) Nbt.number(nbt.get("Rotation"), 0);
                    skull = skull.with("rotation", String.valueOf(Math.round(rotation / 22.5f) & 15));
                }
                changed = skull;
            } else if (path.equals("flower_pot") && nbt.get("PlantBlock") instanceof Map) {
                final Map<String, Object> plant = Nbt.compound(nbt, "PlantBlock");
                final Map<String, String> values = new java.util.TreeMap<String, String>();
                for (Map.Entry<String, Object> value : Nbt.compound(plant, "states").entrySet()) values.put(value.getKey(), String.valueOf(value.getValue()));
                final BlockState java = BlockTables.bedrock(Nbt.string(plant, "name", ""), values);
                if (java != null && !java.isAir() && data.block("minecraft:potted_" + java.path()) != null) {
                    changed = BlockState.parse("minecraft:potted_" + java.path());
                }
            }
            if (changed != null && !changed.equals(state)) structure.setId(index, structure.paletteId(changed));
        }
    }

    private static BlockState skull(BlockState state, Map<String, Object> nbt) {
        final String[] types = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head"};
        final int type = Nbt.integer(nbt, "SkullType", 0);
        final String base = types[type >= 0 && type < types.length ? type : 0];
        final boolean wall = state.path().contains("_wall_");
        if (wall) {
            final String name = base.endsWith("_skull") ? base.replace("_skull", "_wall_skull") : base.replace("_head", "_wall_head");
            return BlockState.parse("minecraft:" + name + "[facing=" + (state.get("facing") == null ? "north" : state.get("facing")) + "]");
        }
        final int rotation = Nbt.integer(nbt, "Rot", 0) & 15;
        return BlockState.parse("minecraft:" + base + "[rotation=" + rotation + "]");
    }

    private static BlockState flowerPot(Map<String, Object> nbt) {
        final Object itemValue = nbt.get("Item");
        String item;
        if (itemValue instanceof Number) {
            item = legacyItemName(((Number) itemValue).intValue());
        } else {
            item = Nbt.string(nbt, "Item", "");
            if (item.indexOf(':') < 0 && !item.isEmpty()) item = "minecraft:" + item;
        }
        final int data = Nbt.integer(nbt, "Data", 0);
        final String plant;
        if (item.equals("minecraft:red_flower")) {
            final String[] flowers = {"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"};
            plant = data >= 0 && data < flowers.length ? flowers[data] : "poppy";
        } else if (item.equals("minecraft:yellow_flower")) {
            plant = "dandelion";
        } else if (item.equals("minecraft:sapling")) {
            final String[] saplings = {"oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling"};
            plant = data >= 0 && data < saplings.length ? saplings[data] : "oak_sapling";
        } else if (item.equals("minecraft:red_mushroom")) {
            plant = "red_mushroom";
        } else if (item.equals("minecraft:brown_mushroom")) {
            plant = "brown_mushroom";
        } else if (item.equals("minecraft:cactus")) {
            plant = "cactus";
        } else if (item.equals("minecraft:deadbush")) {
            plant = "dead_bush";
        } else if (item.equals("minecraft:tallgrass") && data == 2) {
            plant = "fern";
        } else {
            return BlockState.parse("minecraft:flower_pot");
        }
        return BlockState.parse("minecraft:potted_" + plant);
    }

    private static String legacyItemName(int id) {
        switch (id) {
            case 6: return "minecraft:sapling";
            case 31: return "minecraft:tallgrass";
            case 32: return "minecraft:deadbush";
            case 37: return "minecraft:yellow_flower";
            case 38: return "minecraft:red_flower";
            case 39: return "minecraft:brown_mushroom";
            case 40: return "minecraft:red_mushroom";
            case 81: return "minecraft:cactus";
            default: return "";
        }
    }

    // ---------- まわりで決まる形 ----------

    private BlockState stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= structure.sizeX || y >= structure.sizeY || z >= structure.sizeZ) return BlockState.AIR;
        return structure.state(structure.id(x, y, z));
    }

    private int stateIndexAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= structure.sizeX || y >= structure.sizeY || z >= structure.sizeZ) return -1;
        return stateIndex[structure.id(x, y, z)];
    }

    private boolean sturdy(int x, int y, int z, int face) {
        final int index = stateIndexAt(x, y, z);
        return index >= 0 && (data.flags(index) & (1 << (BlockData.STURDY_SHIFT + face))) != 0;
    }

    private boolean solid(int x, int y, int z) {
        final int index = stateIndexAt(x, y, z);
        return index >= 0 && (data.flags(index) & BlockData.SOLID_RENDER) != 0;
    }

    private static boolean isFence(BlockState state) {
        return state.path().endsWith("_fence") && state.get("north") != null;
    }

    private static boolean isWoodenFence(BlockState state) {
        return isFence(state) && !state.path().equals("nether_brick_fence");
    }

    private static boolean isWall(BlockState state) {
        return state.path().endsWith("_wall") && state.get("up") != null;
    }

    private static boolean isFenceGate(BlockState state) {
        return state.path().endsWith("_fence_gate");
    }

    private boolean isBars(BlockState state) {
        final BlockData.Block block = data.block(state.name);
        return block != null && block.skip == BlockData.SKIP_BARS;
    }

    private static boolean isStairs(BlockState state) {
        return state.get("shape") != null && state.get("half") != null && state.get("facing") != null && state.path().endsWith("_stairs");
    }

    // Block.isExceptionForConnection: これらのブロックの面にはつながらない
    private static boolean isExceptionForConnection(BlockState state) {
        final String path = state.path();
        return path.endsWith("_leaves") || path.equals("barrier") || path.equals("carved_pumpkin") || path.equals("jack_o_lantern")
                || path.equals("melon") || path.equals("pumpkin") || path.endsWith("shulker_box");
    }

    private static int direction(String name) {
        for (int d = 0; d < 6; d++) {
            if (NAMES[d].equals(name)) return d;
        }
        return -1;
    }

    // フェンスゲートが、その向きにつながるか (ゲートの向きと直角の向き)
    private static boolean gateConnects(BlockState gate, int direction) {
        final int facing = direction(gate.get("facing"));
        if (facing < 0) return false;
        final boolean facingAlongX = facing == WEST || facing == EAST;
        final int turned = CLOCKWISE[direction];
        final boolean turnedAlongX = turned == WEST || turned == EAST;
        return facingAlongX == turnedAlongX;
    }

    private void updateShapes() throws IOException {
        final Map<Integer, Map<String, Object>> entities = structure.blockEntities();
        // 1 回目: 2 段のブロックの上下をそろえる。2 回目: まわりで決まる形
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < structure.sizeY; y++) {
                for (int z = 0; z < structure.sizeZ; z++) {
                    for (int x = 0; x < structure.sizeX; x++) {
                        final int index = structure.index(x, y, z);
                        final BlockState state = structure.state(structure.id(index));
                        if (state.isAir()) continue;
                        final BlockState updated = pass == 0 ? updateHalves(state, x, y, z) : updateShape(state, x, y, z, entities.get(index));
                        if (updated != null && !updated.equals(state)) structure.setId(index, paletteId(updated));
                    }
                }
            }
        }
        pairChests();
    }

    // 扉と背の高い植物の上下の半分は、片方にしか書かれていない値がある
    private BlockState updateHalves(BlockState state, int x, int y, int z) {
        final String half = state.get("half");
        if (half == null) return null;
        if (state.path().endsWith("_door")) {
            if (half.equals("upper")) {
                final BlockState below = stateAt(x, y - 1, z);
                if (!below.path().endsWith("_door")) return null;
                return state.withName(below.name).with("facing", below.get("facing")).with("open", below.get("open"));
            }
            final BlockState above = stateAt(x, y + 1, z);
            if (!above.path().endsWith("_door") || !"upper".equals(above.get("half"))) return null;
            return state.with("hinge", above.get("hinge")).with("powered", above.get("powered"));
        }
        if (half.equals("upper") && state.get("facing") == null) {
            // 背の高い植物 (上の半分は種類を持っていなかった)
            final BlockState below = stateAt(x, y - 1, z);
            if ("lower".equals(below.get("half")) && below.get("facing") == null) return state.withName(below.name);
        }
        return null;
    }

    private BlockState updateShape(BlockState state, int x, int y, int z, Map<String, Object> entity) {
        final String path = state.path();
        if (isFence(state)) {
            BlockState result = state;
            final boolean wooden = isWoodenFence(state);
            for (int d : HORIZONTAL) {
                final int nx = x + DX[d], nz = z + DZ[d];
                final BlockState neighbor = stateAt(nx, y, nz);
                final boolean connect = (!isExceptionForConnection(neighbor) && sturdy(nx, y, nz, OPPOSITE[d]))
                        || (isFence(neighbor) && isWoodenFence(neighbor) == wooden)
                        || (isFenceGate(neighbor) && gateConnects(neighbor, d));
                result = result.with(NAMES[d], connect ? "true" : "false");
            }
            return result;
        }
        if (isBars(state)) {
            BlockState result = state;
            for (int d : HORIZONTAL) {
                final int nx = x + DX[d], nz = z + DZ[d];
                final BlockState neighbor = stateAt(nx, y, nz);
                final boolean connect = (!isExceptionForConnection(neighbor) && sturdy(nx, y, nz, OPPOSITE[d]))
                        || isBars(neighbor) || isWall(neighbor);
                result = result.with(NAMES[d], connect ? "true" : "false");
            }
            return result;
        }
        if (isWall(state)) return updateWall(state, x, y, z);
        if (isStairs(state)) return state.with("shape", stairsShape(state, x, y, z));
        if (path.equals("redstone_wire")) return updateWire(state, x, y, z);
        if (path.equals("grass_block") || path.equals("podzol") || path.equals("mycelium")) {
            final String above = stateAt(x, y + 1, z).path();
            final boolean snowy = above.equals("snow") || above.equals("snow_block") || above.equals("powder_snow");
            return state.with("snowy", snowy ? "true" : "false");
        }
        if (path.equals("chest") || path.equals("trapped_chest") || path.endsWith("copper_chest")) {
            // 統合版は、つながる相手の位置をブロックエンティティに書いている
            if (entity != null && entity.containsKey("pairx") && entity.containsKey("pairz")) {
                final int facing = direction(state.get("facing"));
                final int px = Nbt.integer(entity, "pairx", 0), pz = Nbt.integer(entity, "pairz", 0);
                final int[] own = absolute(entity, x, z);
                final int dx = Integer.signum(px - own[0]), dz = Integer.signum(pz - own[1]);
                if (facing >= 0) {
                    final int clockwise = CLOCKWISE[facing];
                    if (DX[clockwise] == dx && DZ[clockwise] == dz) return state.with("type", "left");
                    final int counter = COUNTER_CLOCKWISE[facing];
                    if (DX[counter] == dx && DZ[counter] == dz) return state.with("type", "right");
                }
            }
            return state.with("type", "single");
        }
        return null;
    }

    // ブロックエンティティに書かれた、ワールドでの位置 (x, z)。書かれていなければ構造物の中の位置
    private static int[] absolute(Map<String, Object> entity, int x, int z) {
        return new int[]{Nbt.integer(entity, "x", x), Nbt.integer(entity, "z", z)};
    }

    private String stairsShape(BlockState state, int x, int y, int z) {
        final int facing = direction(state.get("facing"));
        final String half = state.get("half");
        if (facing < 2) return "straight";
        final BlockState behind = stateAt(x + DX[facing], y, z + DZ[facing]);
        if (isStairs(behind) && half.equals(behind.get("half"))) {
            final int behindFacing = direction(behind.get("facing"));
            if (behindFacing >= 2 && axis(behindFacing) != axis(facing) && canTakeShape(state, x, y, z, OPPOSITE[behindFacing])) {
                return behindFacing == COUNTER_CLOCKWISE[facing] ? "outer_left" : "outer_right";
            }
        }
        final int front = OPPOSITE[facing];
        final BlockState frontState = stateAt(x + DX[front], y, z + DZ[front]);
        if (isStairs(frontState) && half.equals(frontState.get("half"))) {
            final int frontFacing = direction(frontState.get("facing"));
            if (frontFacing >= 2 && axis(frontFacing) != axis(facing) && canTakeShape(state, x, y, z, frontFacing)) {
                return frontFacing == COUNTER_CLOCKWISE[facing] ? "inner_left" : "inner_right";
            }
        }
        return "straight";
    }

    private boolean canTakeShape(BlockState state, int x, int y, int z, int direction) {
        final BlockState neighbor = stateAt(x + DX[direction], y, z + DZ[direction]);
        return !isStairs(neighbor) || !state.get("facing").equals(neighbor.get("facing")) || !state.get("half").equals(neighbor.get("half"));
    }

    private static int axis(int direction) {
        return direction / 2;
    }

    private BlockState updateWall(BlockState state, int x, int y, int z) {
        BlockState result = state;
        final boolean[] connected = new boolean[6];
        final BlockState above = stateAt(x, y + 1, z);
        for (int d : HORIZONTAL) {
            final int nx = x + DX[d], nz = z + DZ[d];
            final BlockState neighbor = stateAt(nx, y, nz);
            connected[d] = isWall(neighbor) || (!isExceptionForConnection(neighbor) && sturdy(nx, y, nz, OPPOSITE[d]))
                    || isBars(neighbor) || (isFenceGate(neighbor) && gateConnects(neighbor, d));
            String height = "none";
            if (connected[d]) {
                // 上にブロックが載っていれば高い壁になる
                final boolean covered = sturdy(x, y + 1, z, DOWN) || (isWall(above) && !"none".equals(above.get(NAMES[d])));
                height = covered ? "tall" : "low";
            }
            result = result.with(NAMES[d], height);
        }
        final boolean northSouth = connected[NORTH] && connected[SOUTH] && !connected[EAST] && !connected[WEST];
        final boolean eastWest = connected[EAST] && connected[WEST] && !connected[NORTH] && !connected[SOUTH];
        final boolean aboveForcesPost = above.path().endsWith("torch") || above.path().endsWith("lantern")
                || above.path().endsWith("_sign") || above.path().endsWith("_banner") || (isWall(above) && "true".equals(above.get("up")));
        result = result.with("up", (northSouth || eastWest) && !aboveForcesPost ? "false" : "true");
        return result;
    }

    private BlockState updateWire(BlockState state, int x, int y, int z) {
        final boolean canConnectUp = !solid(x, y + 1, z);
        BlockState result = state;
        final boolean[] connected = new boolean[6];
        for (int d : HORIZONTAL) {
            final int nx = x + DX[d], nz = z + DZ[d];
            final BlockState neighbor = stateAt(nx, y, nz);
            String side = "none";
            if (canConnectUp && (sturdy(nx, y, nz, UP) || neighbor.path().endsWith("_trapdoor") || neighbor.path().equals("hopper"))
                    && wireConnects(stateAt(nx, y + 1, nz), -1)) {
                side = sturdy(nx, y, nz, OPPOSITE[d]) ? "up" : "side";
            } else if (wireConnects(neighbor, d) || (!solid(nx, y, nz) && wireConnects(stateAt(nx, y - 1, nz), -1))) {
                side = "side";
            }
            connected[d] = !side.equals("none");
            result = result.with(NAMES[d], side);
        }
        // どこにもつながらない線は十字、1 方向だけなら反対側にも伸ばす
        final boolean northSouthEmpty = !connected[NORTH] && !connected[SOUTH];
        final boolean eastWestEmpty = !connected[EAST] && !connected[WEST];
        if (!connected[WEST] && northSouthEmpty) result = result.with("west", "side");
        if (!connected[EAST] && northSouthEmpty) result = result.with("east", "side");
        if (!connected[NORTH] && eastWestEmpty) result = result.with("north", "side");
        if (!connected[SOUTH] && eastWestEmpty) result = result.with("south", "side");
        return result;
    }

    // BlockState.shouldRedstoneWireConnectTo。direction が -1 なら向きを問わない (上下の段の線)
    private static boolean wireConnects(BlockState state, int direction) {
        final String path = state.path();
        if (path.equals("redstone_wire")) return true;
        if (path.equals("repeater")) {
            final int facing = direction(state.get("facing"));
            return direction >= 0 && (facing == direction || facing == OPPOSITE[direction]);
        }
        if (path.equals("observer")) return direction >= 0 && direction(state.get("facing")) == direction;
        if (direction < 0) return false;
        return SIGNAL_SOURCES.contains(state.name) || path.endsWith("_button") || path.endsWith("_pressure_plate");
    }

    // 1.12 までの形式のチェストは、隣り合う同じ向きのチェストとつながる
    private void pairChests() throws IOException {
        if (structure.source != Structure.Source.LEGACY) return;
        // つなげ終えたます目 (チェストだらけの構造物でもメモリを使いすぎないように、ビットで持つ)
        final java.util.BitSet done = new java.util.BitSet(structure.volume());
        for (int y = 0; y < structure.sizeY; y++) {
            for (int z = 0; z < structure.sizeZ; z++) {
                for (int x = 0; x < structure.sizeX; x++) {
                    final int index = structure.index(x, y, z);
                    if (done.get(index)) continue;
                    final BlockState state = structure.state(structure.id(index));
                    if (!state.path().equals("chest") && !state.path().equals("trapped_chest")) continue;
                    final int facing = direction(state.get("facing"));
                    if (facing < 2) continue;
                    for (int side = 0; side < 2; side++) {
                        final int d = side == 0 ? CLOCKWISE[facing] : COUNTER_CLOCKWISE[facing];
                        final int nx = x + DX[d], nz = z + DZ[d];
                        if (nx < 0 || nz < 0 || nx >= structure.sizeX || nz >= structure.sizeZ) continue;
                        final int other = structure.index(nx, y, nz);
                        if (done.get(other)) continue;
                        final BlockState neighbor = structure.state(structure.id(other));
                        if (!neighbor.name.equals(state.name) || !state.get("facing").equals(neighbor.get("facing"))) continue;
                        structure.setId(index, paletteId(state.with("type", side == 0 ? "left" : "right")));
                        structure.setId(other, paletteId(neighbor.with("type", side == 0 ? "right" : "left")));
                        done.set(index);
                        done.set(other);
                        break;
                    }
                }
            }
        }
    }
}
