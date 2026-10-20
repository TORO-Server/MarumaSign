package marumasa.marumasa_sign.core.structure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 確認用の構造物のファイル (.nbt) を作る。引数: 出力先のフォルダ
 * <ul>
 *     <li>all_blocks.nbt: すべてのブロックの既定の状態を 2 ブロックおきに並べたもの</li>
 *     <li>specials.nbt: ゲームのコードで描かれるブロック (チェスト、旗、頭など) と液体を並べたもの</li>
 * </ul>
 */
public final class StructureShowcase {

    private final List<Map<String, Object>> palette = new ArrayList<Map<String, Object>>();
    private final Map<String, Integer> paletteIndex = new LinkedHashMap<String, Integer>();
    private final List<Object> blocks = new ArrayList<Object>();
    private final List<Object> entities = new ArrayList<Object>();
    private int maxX, maxY, maxZ;

    public static void main(String[] args) throws Exception {
        final File directory = new File(args[0]);
        directory.mkdirs();
        allBlocks().write(new File(directory, "all_blocks.nbt"));
        specials().write(new File(directory, "specials.nbt"));
    }

    // 置く位置の z をずらす量 (壁を手前の看板の正面から見えるように、奥に置くため)
    private int zShift;

    private void put(int x, int y, int z, String state, Map<String, Object> nbt) {
        z += zShift;
        final BlockState parsed = BlockState.parse(state);
        final String key = parsed.toString();
        Integer index = paletteIndex.get(key);
        if (index == null) {
            index = palette.size();
            final Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("Name", parsed.name);
            if (parsed.size() > 0) {
                final Map<String, Object> properties = new LinkedHashMap<String, Object>();
                for (int i = 0; i < parsed.size(); i++) properties.put(parsed.key(i), parsed.value(i));
                entry.put("Properties", properties);
            }
            palette.add(entry);
            paletteIndex.put(key, index);
        }
        final Map<String, Object> block = new LinkedHashMap<String, Object>();
        final List<Object> pos = new ArrayList<Object>();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        block.put("pos", pos);
        block.put("state", index);
        if (nbt != null) block.put("nbt", nbt);
        blocks.add(block);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    private void put(int x, int y, int z, String state) {
        put(x, y, z, state, null);
    }

    private static StructureShowcase allBlocks() throws IOException {
        final StructureShowcase s = new StructureShowcase();
        final BlockData data = BlockData.get();
        final java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(
                StructureShowcase.class.getResourceAsStream("/marumasa_sign/structure/blocks.txt.gz"));
        final List<String> names = new ArrayList<String>();
        final java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("B ")) names.add(line.split(" ")[1]);
        }
        reader.close();
        final int columns = (int) Math.ceil(Math.sqrt(names.size()));
        int i = 0;
        for (String name : names) {
            final BlockData.Block block = data.block(name);
            final BlockState state = data.complete(block, BlockState.parse(name));
            final int x = (i % columns) * 2, z = (i / columns) * 2;
            s.put(x, 0, z, "minecraft:stone");
            s.put(x, 1, z, state.toString());
            i++;
        }
        return s;
    }

    private static Map<String, Object> map(Object... pairs) {
        final Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static List<Object> list(Object... items) {
        final List<Object> result = new ArrayList<Object>();
        java.util.Collections.addAll(result, items);
        return result;
    }

    private static StructureShowcase specials() {
        final StructureShowcase s = new StructureShowcase();
        s.zShift = 3;
        // 床
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) s.put(x, 0, z, "minecraft:smooth_stone");
        }
        int x = 1;
        // チェスト
        s.put(x, 1, 1, "minecraft:chest[facing=south,type=single]");
        s.put(x + 2, 1, 1, "minecraft:chest[facing=south,type=right]");
        s.put(x + 3, 1, 1, "minecraft:chest[facing=south,type=left]");
        s.put(x + 5, 1, 1, "minecraft:trapped_chest[facing=east,type=single]");
        s.put(x + 7, 1, 1, "minecraft:ender_chest[facing=west]");
        s.put(x + 9, 1, 1, "minecraft:oxidized_copper_chest[facing=north,type=single]");
        s.put(x + 11, 1, 1, "minecraft:copper_chest[facing=south,type=single]");
        // シュルカーボックス
        final String[] facings = {"up", "down", "north", "south", "west", "east"};
        for (int i = 0; i < facings.length; i++) {
            s.put(1 + i * 2, 1, 4, "minecraft:" + StructureFixer.DYE_COLORS[i * 2] + "_shulker_box[facing=" + facings[i] + "]");
        }
        s.put(13, 1, 4, "minecraft:shulker_box[facing=up]");
        // 旗
        s.put(1, 1, 7, "minecraft:white_banner[rotation=0]", map("id", "minecraft:banner", "patterns", list(
                map("color", "red", "pattern", "minecraft:creeper"), map("color", "blue", "pattern", "minecraft:border"))));
        s.put(3, 1, 7, "minecraft:red_banner[rotation=4]", map("id", "minecraft:banner", "patterns", list(
                map("color", "yellow", "pattern", "minecraft:cross"))));
        s.put(5, 2, 7, "minecraft:stone");
        s.put(5, 2, 8, "minecraft:black_wall_banner[facing=south]", map("id", "minecraft:banner", "patterns", list(
                map("color", "white", "pattern", "minecraft:skull"), map("color", "lime", "pattern", "minecraft:stripe_bottom"))));
        // 頭
        final String[] heads = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "creeper_head", "piglin_head", "dragon_head", "player_head"};
        for (int i = 0; i < heads.length; i++) s.put(1 + i * 2, 1, 11, "minecraft:" + heads[i] + "[rotation=" + (i * 2) + "]");
        s.put(15, 1, 11, "minecraft:stone");
        s.put(15, 1, 12, "minecraft:creeper_wall_head[facing=south]");
        // ベル、飾り壺、エンチャントテーブル、書見台、コンジット、銅のゴーレムの像
        s.put(1, 3, 14, "minecraft:oak_planks");
        s.put(1, 2, 14, "minecraft:bell[attachment=ceiling,facing=north]");
        s.put(3, 1, 14, "minecraft:bell[attachment=floor,facing=east]");
        s.put(5, 1, 14, "minecraft:decorated_pot[facing=south]", map("id", "minecraft:decorated_pot", "sherds", list(
                "minecraft:brick", "minecraft:archer_pottery_sherd", "minecraft:heart_pottery_sherd", "minecraft:skull_pottery_sherd")));
        s.put(7, 1, 14, "minecraft:enchanting_table");
        s.put(9, 1, 14, "minecraft:lectern[facing=south,has_book=true]");
        s.put(11, 1, 14, "minecraft:conduit");
        final String[] poses = {"standing", "running", "sitting", "star"};
        for (int i = 0; i < poses.length; i++) {
            s.put(13 + i * 2, 1, 14, "minecraft:" + (i == 0 ? "" : i == 1 ? "exposed_" : i == 2 ? "weathered_" : "oxidized_")
                    + "copper_golem_statue[copper_golem_pose=" + poses[i] + ",facing=south]");
        }
        // エンドポータル
        for (int i = 0; i < 3; i++) s.put(1 + i, 1, 17, "minecraft:end_portal");
        s.put(5, 1, 17, "minecraft:end_gateway");
        // 水と溶岩 (源と、流れの段)
        for (int i = 0; i < 8; i++) s.put(8 + i, 1, 17, "minecraft:water[level=" + i + "]");
        for (int i = 0; i < 8; i++) s.put(8 + i, 1, 19, "minecraft:lava[level=" + i + "]");
        s.put(1, 1, 20, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]");
        s.put(2, 1, 20, "minecraft:water[level=0]");
        s.put(3, 1, 20, "minecraft:oak_slab[type=bottom,waterlogged=true]");
        // 看板の文字
        s.put(5, 1, 20, "minecraft:oak_sign[rotation=0]", map("id", "minecraft:sign", "front_text", map(
                "messages", list("\"Hello\"", "\"MarumaSign\"", "\"こんにちは\"", "\"\""), "color", "black")));
        s.addEntities();
        return s;
    }

    private void entity(double x, double y, double z, Map<String, Object> nbt) {
        z += zShift;
        entities.add(map("pos", list(x, y, z), "blockPos", list((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), "nbt", nbt));
    }

    private static Map<String, Object> item(String id) {
        return map("id", id, "count", 1);
    }

    // 絵画、額縁、防具立て、ディスプレイエンティティ
    private void addEntities() {
        // 奥 (z = 0) の壁と、その南の面に掛けるもの (向きは南。2D の 0、3D の 3)
        final int shift = zShift;
        zShift = 0;
        for (int x = 2; x <= 22; x++) {
            for (int y = 0; y <= 3; y++) put(x, y, 0, "minecraft:oak_planks");
        }
        entity(3.5, 2.0, 1.03125, map("id", "minecraft:painting", "variant", "minecraft:kebab", "facing", (byte) 0));
        entity(6.0, 2.5, 1.03125, map("id", "minecraft:painting", "variant", "minecraft:pool", "facing", (byte) 0));
        final String[] frameItems = {"minecraft:diamond_sword", "minecraft:grass_block", "minecraft:chest", "minecraft:creeper_head", "minecraft:red_banner", "minecraft:apple"};
        for (int i = 0; i < frameItems.length; i++) {
            entity(9.5 + i, 2.5, 1.03125, map("id", i % 2 == 0 ? "minecraft:item_frame" : "minecraft:glow_item_frame", "Facing", (byte) 3,
                    "ItemRotation", (byte) (i == 0 ? 1 : 0), "Item", item(frameItems[i])));
        }
        zShift = shift;
        entity(17.5, 1.0, 20.5, map("id", "minecraft:armor_stand", "Rotation", list(30f, 0f), "ShowArms", (byte) 1,
                "Pose", map("RightArm", list(-60f, 0f, 10f)),
                "equipment", map("head", item("minecraft:iron_helmet"), "chest", item("minecraft:diamond_chestplate"),
                        "legs", item("minecraft:leather_leggings"), "feet", item("minecraft:golden_boots"),
                        "mainhand", item("minecraft:iron_sword"), "offhand", item("minecraft:torch"))));
        entity(19.5, 1.0, 20.5, map("id", "minecraft:armor_stand", "Rotation", list(-20f, 0f), "Small", (byte) 1,
                "equipment", map("head", item("minecraft:pumpkin"))));
        entity(21.0, 1.0, 18.0, map("id", "minecraft:block_display", "block_state", map("Name", "minecraft:diamond_block"),
                "transformation", map("translation", list(0f, 0f, 0f), "left_rotation", list(0f, 0.38268343f, 0f, 0.9238795f),
                        "scale", list(0.5f, 0.5f, 0.5f), "right_rotation", list(0f, 0f, 0f, 1f))));
        entity(21.5, 2.0, 16.5, map("id", "minecraft:item_display", "item", item("minecraft:golden_apple"), "item_display", "fixed"));
        entity(21.5, 2.5, 14.5, map("id", "minecraft:text_display", "text", "{\"text\":\"Text display\",\"color\":\"gold\"}"));
    }

    private void write(File file) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("DataVersion", 5023);
        root.put("size", list(maxX + 1, maxY + 1, maxZ + 1));
        root.put("palette", new ArrayList<Object>(palette));
        root.put("blocks", blocks);
        root.put("entities", entities);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        writeCompound(out, root);
        out.close();
        final GZIPOutputStream gzip = new GZIPOutputStream(new FileOutputStream(file));
        gzip.write(bytes.toByteArray());
        gzip.close();
        System.out.println(file + ": " + blocks.size() + " blocks, " + palette.size() + " states");
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            final Object value = entry.getValue();
            out.writeByte(type(value));
            out.writeUTF(entry.getKey());
            writeValue(out, value);
        }
        out.writeByte(0);
    }

    private static int type(Object value) {
        if (value instanceof Byte) return 1;
        if (value instanceof Integer) return 3;
        if (value instanceof Float) return 5;
        if (value instanceof Double) return 6;
        if (value instanceof String) return 8;
        if (value instanceof List) return 9;
        if (value instanceof Map) return 10;
        throw new IllegalArgumentException(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value instanceof Byte) {
            out.writeByte((Byte) value);
        } else if (value instanceof Float) {
            out.writeFloat((Float) value);
        } else if (value instanceof Double) {
            out.writeDouble((Double) value);
        } else if (value instanceof Integer) {
            out.writeInt((Integer) value);
        } else if (value instanceof String) {
            out.writeUTF((String) value);
        } else if (value instanceof List) {
            final List<Object> list = (List<Object>) value;
            out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
            out.writeInt(list.size());
            for (Object item : list) writeValue(out, item);
        } else {
            writeCompound(out, (Map<String, Object>) value);
        }
    }
}
