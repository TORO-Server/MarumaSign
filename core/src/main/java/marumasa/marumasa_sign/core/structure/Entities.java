package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 構造物に含まれるエンティティのうち、建物の飾りに使われるものを描く。
 * 絵画、額縁 (中のアイテムも)、ディスプレイエンティティ (ブロック、アイテム、文字)、防具立て。
 * 生き物は描かない。
 */
final class Entities {

    private final SpecialModels special;
    private final BlockModels models;
    private final Items items;
    private final GameAssets assets;

    Entities(SpecialModels special, BlockModels models) {
        this.special = special;
        this.models = models;
        this.items = new Items(models, special);
        this.assets = models.assets();
    }

    void emit(StructureMesher mesher, Structure.Entity entity, Structure structure) throws IOException {
        String id = entity.id;
        if (id == null || id.isEmpty()) return;
        id = id.substring(id.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        // 間引いた構造物では、エンティティも同じ割合で小さくする
        final float step = structure.step;
        final Transform base = Transform.identity().translate((float) entity.x / step, (float) entity.y / step, (float) entity.z / step)
                .scale(1 / step, 1 / step, 1 / step);
        switch (id) {
            case "painting":
                painting(mesher, entity.nbt, base, structure.source);
                break;
            case "item_frame":
            case "glow_item_frame":
                itemFrame(mesher, entity.nbt, base, id.equals("glow_item_frame"), structure.source);
                break;
            case "block_display":
                blockDisplay(mesher, entity.nbt, base);
                break;
            case "item_display":
                itemDisplay(mesher, entity.nbt, base);
                break;
            case "text_display":
                textDisplay(mesher, entity.nbt, base);
                break;
            case "armor_stand":
                special.armorStand(mesher, entity.nbt, base, items);
                break;
            default:
                break;
        }
    }

    // 2D の向きの番号 (南 0, 西 1, 北 2, 東 3) を方向にする
    private static int direction2d(int value) {
        switch (value & 3) {
            case 1:
                return StructureFixer.WEST;
            case 2:
                return StructureFixer.NORTH;
            case 3:
                return StructureFixer.EAST;
            default:
                return StructureFixer.SOUTH;
        }
    }

    // 3D の向きの番号 (下 0, 上 1, 北 2, 南 3, 西 4, 東 5)
    private static int direction3d(int value) {
        return value >= 0 && value < 6 ? value : StructureFixer.NORTH;
    }

    // ---------- 絵画 ----------

    private void painting(StructureMesher mesher, Map<String, Object> nbt, Transform base, Structure.Source source) throws IOException {
        String variant = Nbt.string(nbt, "variant", Nbt.string(nbt, "Motive", "minecraft:kebab"));
        if (nbt.get("variant") instanceof Map) variant = Nbt.string(Nbt.compound(nbt, "variant"), "asset_id", "minecraft:kebab");
        variant = variant.substring(variant.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        // 古い名前 (1.12 までは名前の先頭が大文字、統合版は同じ名前)
        if (variant.equals("burningskull")) variant = "burning_skull";
        if (variant.equals("donkeykong")) variant = "donkey_kong";
        if (variant.equals("skullandroses")) variant = "skull_and_roses";
        int width = 1, height = 1;
        String asset = "minecraft:" + variant;
        final byte[] definition = assets.read("data/minecraft/painting_variant/" + variant + ".json");
        if (definition != null) {
            final Map<String, Object> json = Json.object(Json.parse(new String(definition, StandardCharsets.UTF_8)));
            width = Json.integer(json, "width", 1);
            height = Json.integer(json, "height", 1);
            asset = Json.string(json, "asset_id", asset);
        }
        final int facing;
        if (nbt.containsKey("facing")) facing = direction2d(Nbt.integer(nbt, "facing", 0));
        else if (nbt.containsKey("Facing")) facing = direction2d(Nbt.integer(nbt, "Facing", 0));
        else facing = direction2d(Nbt.integer(nbt, "Direction", 0));

        final Transform t = base.copy().rotateY(180 - SpecialModels.yRot(facing));
        final Sprite front = Sprite.get(assets, "minecraft:painting/" + asset.substring(asset.indexOf(':') + 1));
        final Sprite back = Sprite.get(assets, "minecraft:painting/back");
        final float x0 = -width / 2f, x1 = width / 2f, y0 = -height / 2f, y1 = height / 2f, z = 0.03125f;
        // 表 (-Z 向き)。左右は絵の向きに合わせて逆になる
        quad(mesher, t, front, new float[]{x1, y0, -z, x0, y0, -z, x0, y1, -z, x1, y1, -z}, new float[]{0, 1, 1, 1, 1, 0, 0, 0});
        // 裏と縁
        quad(mesher, t, back, new float[]{x0, y1, z, x0, y0, z, x1, y0, z, x1, y1, z}, new float[]{0, 0, 0, 1, 1, 1, 1, 0});
        quad(mesher, t, back, new float[]{x0, y1, -z, x0, y1, z, x1, y1, z, x1, y1, -z}, new float[]{0, 0, 0, 0.0625f, 1, 0.0625f, 1, 0});
        quad(mesher, t, back, new float[]{x0, y0, z, x0, y0, -z, x1, y0, -z, x1, y0, z}, new float[]{0, 0, 0, 0.0625f, 1, 0.0625f, 1, 0});
        quad(mesher, t, back, new float[]{x1, y1, z, x1, y0, z, x1, y0, -z, x1, y1, -z}, new float[]{0, 0, 0, 1, 0.0625f, 1, 0.0625f, 0});
        quad(mesher, t, back, new float[]{x0, y1, -z, x0, y0, -z, x0, y0, z, x0, y1, z}, new float[]{0, 0, 0, 1, 0.0625f, 1, 0.0625f, 0});
    }

    // 四角形を 1 枚書き出す。表は頂点が反時計回りに見える側
    private static void quad(StructureMesher mesher, Transform t, Sprite sprite, float[] corners, float[] uvs) {
        final float[] positions = new float[12];
        final float[] texture = uvs.clone();
        for (int v = 0; v < 4; v++) t.apply(corners[v * 3], corners[v * 3 + 1], corners[v * 3 + 2], positions, v * 3);
        if (t.flips()) {
            reverse(positions, 3);
            reverse(texture, 2);
        }
        mesher.addQuad(StructureMesher.layer(sprite.transparency(0, 0, 1, 1)), positions, texture, 0xFFFFFFFF, sprite, true);
    }

    private static void reverse(float[] values, int stride) {
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < stride; k++) {
                final float tmp = values[i * stride + k];
                values[i * stride + k] = values[(3 - i) * stride + k];
                values[(3 - i) * stride + k] = tmp;
            }
        }
    }

    // ---------- 額縁 ----------

    private void itemFrame(StructureMesher mesher, Map<String, Object> nbt, Transform base, boolean glow, Structure.Source source) {
        // 1.12 までは 2D の向きの番号だった
        final int facing = source == Structure.Source.LEGACY ? direction2d(Nbt.integer(nbt, "Facing", Nbt.integer(nbt, "Direction", 0)))
                : direction3d(Nbt.integer(nbt, "Facing", Nbt.integer(nbt, "facing", StructureFixer.NORTH)));
        final boolean invisible = Nbt.integer(nbt, "Invisible", 0) != 0;
        final Map<String, Object> item = Nbt.compound(nbt, "Item");
        final String itemId = Items.itemId(item);
        final boolean map = itemId != null && itemId.equals("minecraft:filled_map");
        // ItemFrameRenderer: 壁から少し離して、向きに合わせて回す
        final Transform t = base.copy().translate(StructureFixer.DX[facing] * 0.46875f, StructureFixer.DY[facing] * 0.46875f, StructureFixer.DZ[facing] * 0.46875f);
        if (facing >= StructureFixer.NORTH) {
            t.rotateY(180 - SpecialModels.yRot(facing));
        } else {
            t.rotateX(facing == StructureFixer.UP ? -90 : 90).rotateY(180);
        }
        if (!invisible) {
            final Map<String, String> properties = new TreeMap<String, String>();
            properties.put("map", map ? "true" : "false");
            final BlockModels.StateModel model = models.model(BlockState.of(glow ? "minecraft:glow_item_frame" : "minecraft:item_frame", properties));
            emitBlock(mesher, model, t.copy().translate(-0.5f, -0.5f, -0.5f));
        }
        if (itemId == null || map) return;
        final Transform itemTransform = t.copy().translate(0, 0, invisible ? 0.5f : 0.4375f)
                .rotateZ(Nbt.integer(nbt, "ItemRotation", 0) * 45f).scale(0.5f, 0.5f, 0.5f);
        items.emit(mesher, item, "fixed", itemTransform);
    }

    // ブロックのモデルを、変換して書き出す (面の向きによる明るさだけ付ける)
    private static void emitBlock(StructureMesher mesher, BlockModels.StateModel model, Transform t) {
        final List<BlockModels.Variant> variants = new ArrayList<BlockModels.Variant>();
        model.collect(0L, new java.util.Random(), variants);
        final boolean flip = t.flips();
        for (BlockModels.Variant variant : variants) {
            for (BlockModels.Quad quad : variant.quads) {
                final float[] positions = new float[12];
                final float[] uvs = new float[8];
                for (int v = 0; v < 4; v++) {
                    final int source = flip ? 3 - v : v;
                    t.apply(quad.positions[source * 3], quad.positions[source * 3 + 1], quad.positions[source * 3 + 2], positions, v * 3);
                    uvs[v * 2] = quad.uvs[source * 2];
                    uvs[v * 2 + 1] = quad.uvs[source * 2 + 1];
                }
                final int color = quad.tint >= 0 ? StructureMesher.scale(0xFFFFFFFF, 1) : 0xFFFFFFFF;
                mesher.addQuad(StructureMesher.layer(quad.transparency), positions, uvs, color, quad.sprite, true);
            }
        }
    }

    // ---------- ディスプレイエンティティ ----------

    // Display の向きと transformation
    private static Transform display(Map<String, Object> nbt, Transform base) {
        final Transform t = base.copy();
        final double[] rotation = Nbt.doubles(nbt.get("Rotation"));
        final float yaw = rotation != null && rotation.length >= 2 ? (float) rotation[0] : 0;
        final float pitch = rotation != null && rotation.length >= 2 ? (float) rotation[1] : 0;
        // billboard が fixed 以外 (カメラの方を向く) のものも、置いたときの向きで描く
        t.rotateY(-yaw).rotateX(pitch);
        final Object transformation = nbt.get("transformation");
        if (transformation instanceof Map || transformation instanceof List) {
            final Map<String, Object> node = new TreeMap<String, Object>();
            node.put("transformation", toJson(transformation));
            return Items.withTransformation(t, node);
        }
        return t;
    }

    // NBT の値を、JSON を読んだときと同じ形 (数は Double) にする
    private static Object toJson(Object value) {
        if (value instanceof Map) {
            final Map<String, Object> result = new TreeMap<String, Object>();
            for (Map.Entry<String, Object> entry : Nbt.compound(value).entrySet()) result.put(entry.getKey(), toJson(entry.getValue()));
            return result;
        }
        if (value instanceof List || value instanceof int[] || value instanceof long[]) {
            final double[] numbers = Nbt.doubles(value);
            final List<Object> result = new ArrayList<Object>();
            if (numbers != null) {
                for (double number : numbers) result.add(number);
            } else {
                for (Object item : Nbt.list(value)) result.add(toJson(item));
            }
            return result;
        }
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value;
    }

    private void blockDisplay(StructureMesher mesher, Map<String, Object> nbt, Transform base) {
        final Map<String, Object> state = Nbt.compound(nbt, "block_state");
        final Map<String, String> properties = new TreeMap<String, String>();
        for (Map.Entry<String, Object> entry : Nbt.compound(state, "Properties").entrySet()) properties.put(entry.getKey(), String.valueOf(entry.getValue()));
        final BlockState block = BlockState.of(Nbt.string(state, "Name", "minecraft:air"), properties);
        if (block.isAir()) return;
        emitBlock(mesher, models.model(block), display(nbt, base));
    }

    private void itemDisplay(StructureMesher mesher, Map<String, Object> nbt, Transform base) {
        final Map<String, Object> item = Nbt.compound(nbt, "item");
        if (Items.itemId(item) == null) return;
        final String context = Nbt.string(nbt, "item_display", "none");
        items.emit(mesher, item, context, display(nbt, base).rotateY(180));
    }

    private void textDisplay(StructureMesher mesher, Map<String, Object> nbt, Transform base) {
        final Object text = nbt.get("text");
        if (text == null) return;
        final int lineWidth = Nbt.integer(nbt, "line_width", 200);
        final int background = nbt.containsKey("background") ? Nbt.integer(nbt, "background", 0x40000000) : 0x40000000;
        final int opacity = Nbt.integer(nbt, "text_opacity", -1) & 0xFF;
        final String alignment = Nbt.string(nbt, "alignment", "center");
        final boolean defaultBackground = Nbt.integer(nbt, "default_background", 0) != 0;
        special.textDisplay(mesher, SignTexts.parse(text), lineWidth, defaultBackground ? 0x40000000 : background, opacity == 0 ? 255 : opacity,
                alignment, display(nbt, base));
    }
}
