package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * アイテムを描く (額縁の中、ディスプレイエンティティ、防具立ての手など)。
 * assets/minecraft/items のアイテムの定義と、models/item のモデルを、ゲームと同じ規則で読む。
 * 時刻や持ち方で変わる部分は、置かれたまま何もしていないときのもの (選べないものは既定のもの) にする。
 */
final class Items {

    private final BlockModels models;
    private final SpecialModels special;
    private final GameAssets assets;
    private final Map<String, Map<String, Object>> definitions = new ConcurrentHashMap<String, Map<String, Object>>();

    Items(BlockModels models, SpecialModels special) {
        this.models = models;
        this.special = special;
        this.assets = models.assets();
    }

    // アイテムのデータ ({id, count, components} や {id, Count, tag}) の名前。なければ null
    static String itemId(Map<String, Object> item) {
        final Object id = item.get("id");
        if (id instanceof String) return BlockState.namespaced((String) id);
        final Object name = item.get("Name");
        if (name instanceof String) return BlockState.namespaced((String) name);
        return null;
    }

    static Map<String, Object> components(Map<String, Object> item) {
        if (item.get("components") instanceof Map) return Nbt.compound(item, "components");
        return Collections.emptyMap();
    }

    private Map<String, Object> definition(String id) {
        Map<String, Object> definition = definitions.get(id);
        if (definition == null) {
            try {
                final byte[] bytes = assets.read(GameAssets.path(id, "items", ".json"));
                definition = bytes == null ? Collections.<String, Object>emptyMap()
                        : Json.object(Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8))), "model");
            } catch (IOException | RuntimeException e) {
                definition = Collections.emptyMap();
            }
            // ないアイテムは覚えない (名前は構造物のファイルの中身で決まる)
            if (!definition.isEmpty()) definitions.put(id, definition);
        }
        return definition;
    }

    /**
     * アイテムを描く。
     *
     * @param item      アイテムのデータ
     * @param context   表示する場所 (fixed, ground, head, thirdperson_righthand など)
     * @param transform アイテムの位置 (この中心に、表示する場所ごとの変換をしたアイテムを置く)
     */
    void emit(StructureMesher mesher, Map<String, Object> item, String context, Transform transform) {
        final String id = itemId(item);
        if (id == null || id.equals("minecraft:air")) return;
        final Map<String, Object> definition = definition(id);
        if (definition.isEmpty()) {
            // 定義のないアイテム (別の Mod のものなど) は、紫と黒の模様の立方体にする
            emitModel(mesher, "minecraft:missing", Collections.<Object>emptyList(), components(item), context, transform, Collections.<String, Object>emptyMap());
            return;
        }
        // MarumaSign の看板の文字を持つ看板のアイテムは、ゲームでも看板と同じものが表示される
        if (id.endsWith("_sign") && !id.endsWith("_hanging_sign")) {
            Map<String, Object> data = Nbt.compound(components(item), "minecraft:block_entity_data");
            if (data.isEmpty()) data = Nbt.compound(Nbt.compound(item, "tag"), "BlockEntityTag");
            final marumasa.marumasa_sign.core.sign.SignSpec spec = data.isEmpty() ? null : SpecialModels.signSpec(data, Structure.Source.JAVA);
            if (spec != null) {
                final BlockModels.ItemModel model = models.itemModel("minecraft:item/" + id.substring(id.indexOf(':') + 1));
                special.nested.emitItem(mesher, spec, withDisplay(transform, model, context));
                return;
            }
        }
        emitNode(mesher, definition, item, components(item), context, transform, 0);
    }

    private void emitNode(StructureMesher mesher, Map<String, Object> node, Map<String, Object> item, Map<String, Object> components,
                          String context, Transform transform, int depth) {
        if (depth > 16) return;
        final String type = Json.string(node, "type", "minecraft:model").replace("minecraft:", "");
        switch (type) {
            case "model":
                emitModel(mesher, Json.string(node, "model", "minecraft:item/air"), Json.array(node, "tints"), components, context, transform, node);
                break;
            case "composite":
                for (Object child : Json.array(node, "models")) emitNode(mesher, Json.object(child), item, components, context, transform, depth + 1);
                break;
            case "condition":
                emitNode(mesher, Json.object(node, "on_false"), item, components, context, transform, depth + 1);
                break;
            case "select": {
                final String property = Json.string(node, "property", "").replace("minecraft:", "");
                Map<String, Object> chosen = null;
                for (Object caseItem : Json.array(node, "cases")) {
                    final Map<String, Object> branch = Json.object(caseItem);
                    final Object when = branch.get("when");
                    final List<Object> values = when instanceof List ? Json.array(when) : Collections.singletonList(when);
                    for (Object value : values) {
                        if (matchesSelect(property, String.valueOf(value), context, components)) chosen = Json.object(branch, "model");
                    }
                    if (chosen != null) break;
                }
                if (chosen == null) chosen = Json.object(node, "fallback");
                if (!chosen.isEmpty()) emitNode(mesher, chosen, item, components, context, transform, depth + 1);
                break;
            }
            case "range_dispatch": {
                // 値は 0 (使っていない、時刻や方角が分からない) として選ぶ
                Map<String, Object> chosen = Json.object(node, "fallback");
                float best = Float.NEGATIVE_INFINITY;
                for (Object entryItem : Json.array(node, "entries")) {
                    final Map<String, Object> entry = Json.object(entryItem);
                    final float threshold = Json.number(entry, "threshold", 0);
                    if (threshold <= 0 && threshold >= best) {
                        best = threshold;
                        chosen = Json.object(entry, "model");
                    }
                }
                if (!chosen.isEmpty()) emitNode(mesher, chosen, item, components, context, transform, depth + 1);
                break;
            }
            case "special": {
                final Transform t = withDisplay(transform, models.itemModel(Json.string(node, "base", "minecraft:item/air")), context);
                special.item(mesher, Json.object(node, "model"), components, withTransformation(t, node));
                break;
            }
            default:
                // empty, bundle/selected_item などは何も描かない
                break;
        }
    }

    private static boolean matchesSelect(String property, String value, String context, Map<String, Object> components) {
        if (property.equals("display_context")) return value.equals(context);
        if (property.equals("block_state")) return false;
        if (property.equals("trim_material")) {
            final Map<String, Object> trim = Nbt.compound(components, "minecraft:trim");
            return value.equals(Nbt.string(trim, "material", ""));
        }
        return false;
    }

    // ノードの transformation (平行移動、左の回転、拡大、右の回転) を掛ける
    static Transform withTransformation(Transform transform, Map<String, Object> node) {
        final Object value = node.get("transformation");
        if (value == null) return transform;
        final Transform result = transform.copy();
        if (value instanceof List) {
            final float[] m = Json.numbers(value);
            if (m.length == 16) {
                // Transformation の行列は列優先で書かれている
                result.multiply(new float[]{m[0], m[4], m[8], m[12], m[1], m[5], m[9], m[13], m[2], m[6], m[10], m[14], m[3], m[7], m[11], m[15]});
            }
            return result;
        }
        final Map<String, Object> parts = Json.object(value);
        final float[] translation = Json.numbers(parts, "translation", new float[]{0, 0, 0});
        final float[] scale = Json.numbers(parts, "scale", new float[]{1, 1, 1});
        result.translate(translation[0], translation[1], translation[2]);
        applyRotation(result, parts.get("left_rotation"));
        result.scale(scale[0], scale[1], scale[2]);
        applyRotation(result, parts.get("right_rotation"));
        return result;
    }

    // 回転は四元数 [x, y, z, w] か、{angle (ラジアン), axis} で書かれている
    private static void applyRotation(Transform transform, Object rotation) {
        if (rotation instanceof List) {
            final float[] q = Json.numbers(rotation);
            if (q.length == 4) transform.rotateQuaternion(q[0], q[1], q[2], q[3]);
        } else if (rotation instanceof Map) {
            final Map<String, Object> axisAngle = Json.object(rotation);
            final float angle = Json.number(axisAngle, "angle", 0);
            final float[] axis = Json.numbers(axisAngle, "axis", new float[]{0, 1, 0});
            final float length = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            if (length == 0) return;
            final float s = (float) Math.sin(angle / 2) / length;
            transform.rotateQuaternion(axis[0] * s, axis[1] * s, axis[2] * s, (float) Math.cos(angle / 2));
        }
    }

    // ItemTransform.apply: 移動、回転 (X, Y, Z)、拡大のあと、モデルの中心を原点に合わせる
    static Transform withDisplay(Transform transform, BlockModels.ItemModel model, String context) {
        final Transform result = transform.copy();
        final float[] d = model.display.get(context);
        if (d != null) {
            result.translate(d[3], d[4], d[5]).rotateXYZ(d[0], d[1], d[2]).scale(d[6], d[7], d[8]);
        }
        return result.translate(-0.5f, -0.5f, -0.5f);
    }

    private void emitModel(StructureMesher mesher, String modelName, List<Object> tints, Map<String, Object> components, String context,
                           Transform transform, Map<String, Object> node) {
        final BlockModels.ItemModel model = models.itemModel(modelName);
        // 表示する場所ごとの変換のあとに、ノードの transformation を掛ける (ItemStackRenderState と同じ順)
        final Transform t = withTransformation(withDisplay(transform, model, context), node);
        final boolean flip = t.flips();
        final float[] positions = new float[12];
        final float[] uvs = new float[8];
        for (BlockModels.Quad quad : model.quads) {
            final int color = quad.tint >= 0 && quad.tint < tints.size() ? tintColor(Json.object(tints.get(quad.tint)), components) : 0xFFFFFFFF;
            for (int v = 0; v < 4; v++) {
                final int source = flip ? 3 - v : v;
                t.apply(quad.positions[source * 3], quad.positions[source * 3 + 1], quad.positions[source * 3 + 2], positions, v * 3);
                uvs[v * 2] = quad.uvs[source * 2];
                uvs[v * 2 + 1] = quad.uvs[source * 2 + 1];
            }
            mesher.addQuad(StructureMesher.layer(quad.transparency), positions.clone(), uvs.clone(), color, quad.sprite, true);
        }
    }

    // アイテムの色 (ItemTintSource)。データで決まる色は、データがあればそれ、なければ既定の色
    private int tintColor(Map<String, Object> tint, Map<String, Object> components) {
        final String type = Json.string(tint, "type", "").replace("minecraft:", "");
        switch (type) {
            case "constant":
                return color(tint.get("value"), 0xFFFFFFFF);
            case "dye": {
                final Object dyed = components.get("minecraft:dyed_color");
                if (dyed instanceof Number) return 0xFF000000 | ((Number) dyed).intValue();
                if (dyed instanceof Map && Nbt.compound(dyed).get("rgb") instanceof Number) {
                    return 0xFF000000 | ((Number) Nbt.compound(dyed).get("rgb")).intValue();
                }
                return color(tint.get("default"), 0xFFFFFFFF);
            }
            case "potion": {
                final Map<String, Object> contents = Nbt.compound(components, "minecraft:potion_contents");
                if (contents.get("custom_color") instanceof Number) return 0xFF000000 | ((Number) contents.get("custom_color")).intValue();
                return color(tint.get("default"), 0xFFFFFFFF);
            }
            case "grass": {
                // GrassColor.get(温度, 雨の量)
                final BiomeColors colors = StructureLoader.biomeColors();
                return colors == null ? 0xFF91BD59 : colors.grassAt(Json.number(tint, "temperature", 0.5f), Json.number(tint, "downfall", 1.0f));
            }
            default:
                return color(tint.get("default"), 0xFFFFFFFF);
        }
    }

    // 色は 0xRRGGBB の数か、[r, g, b] (0 - 1) で書かれている
    private static int color(Object value, int fallback) {
        if (value instanceof Double) return 0xFF000000 | (int) Math.round((Double) value);
        if (value instanceof List) {
            final float[] rgb = Json.numbers(value);
            if (rgb.length >= 3) {
                return 0xFF000000 | Math.round(rgb[0] * 255) << 16 | Math.round(rgb[1] * 255) << 8 | Math.round(rgb[2] * 255);
            }
        }
        return fallback;
    }
}
