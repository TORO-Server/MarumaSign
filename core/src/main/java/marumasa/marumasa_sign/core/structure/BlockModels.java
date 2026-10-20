package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ブロックの状態から、描く四角形 (ブロックの角を原点とし、1 ブロックが 1 の座標) を作る。
 * Minecraft の blockstates と models の JSON を、ゲームと同じ規則で読む
 * (variants / multipart、モデルの継承、要素の回転と拡大、回転のときのテクスチャの固定 (uvlock)、面の省き方)。
 */
final class BlockModels {

    // 方向の番号は StructureFixer と同じ (下、上、北、南、西、東)
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final float[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    // FaceInfo: 面ごとの 4 頂点が、要素の最小 (0) と最大 (1) のどちらを使うか (x, y, z)
    private static final int[][][] FACE_INFO = {
            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
            {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
            {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
            {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},
    };

    // 描く四角形 1 枚
    static final class Quad {
        // 4 頂点の x, y, z
        final float[] positions = new float[12];
        // 4 頂点の u, v (テクスチャの中の 0 - 1)
        final float[] uvs = new float[8];
        Sprite sprite;
        // 隣のブロックで隠れるときに省く方向。省かない面は -1
        int cull = -1;
        // 面の向き (最も近い方向)
        int direction;
        // 面の向きによる明るさに使う方向
        int shadeDirection;
        int tint = -1;
        int transparency;
    }

    // 1 つのモデルを回転したもの
    static final class Variant {
        final List<Quad> quads;
        final boolean ambientOcclusion;

        Variant(List<Quad> quads, boolean ambientOcclusion) {
            this.quads = quads;
            this.ambientOcclusion = ambientOcclusion;
        }
    }

    // 重み付きで選ぶモデルの組
    static final class Choice {
        final Variant[] variants;
        final int[] weights;
        final int total;

        Choice(Variant[] variants, int[] weights) {
            this.variants = variants;
            this.weights = weights;
            int sum = 0;
            for (int weight : weights) sum += weight;
            this.total = sum;
        }

        Variant pick(java.util.Random random) {
            if (variants.length == 1) return variants[0];
            int selection = random.nextInt(total);
            for (int i = 0; i < variants.length; i++) {
                selection -= weights[i];
                if (selection < 0) return variants[i];
            }
            return variants[variants.length - 1];
        }
    }

    // ブロックの状態 1 つのモデル
    static final class StateModel {
        static final StateModel EMPTY = new StateModel(Collections.<Choice>emptyList(), false);

        final List<Choice> choices;
        // multipart (複数のモデルを重ねる) かどうか
        final boolean multipart;

        StateModel(List<Choice> choices, boolean multipart) {
            this.choices = choices;
            this.multipart = multipart;
        }

        /**
         * 位置で決まる乱数で、描くモデルを選ぶ (Minecraft の ModelBlockRenderer と同じ選び方)。
         */
        void collect(long seed, java.util.Random random, List<Variant> output) {
            if (choices.isEmpty()) return;
            random.setSeed(seed);
            if (!multipart) {
                output.add(choices.get(0).pick(random));
                return;
            }
            final long partSeed = random.nextLong();
            for (Choice choice : choices) {
                random.setSeed(partSeed);
                output.add(choice.pick(random));
            }
        }
    }

    private final GameAssets assets;
    private final Map<BlockState, StateModel> states = new ConcurrentHashMap<BlockState, StateModel>();
    private final Map<String, Map<String, Object>> blockstateFiles = new ConcurrentHashMap<String, Map<String, Object>>();
    private final Map<String, Map<String, Object>> modelFiles = new ConcurrentHashMap<String, Map<String, Object>>();
    private final Map<String, Variant> baked = new ConcurrentHashMap<String, Variant>();

    BlockModels(GameAssets assets) {
        this.assets = assets;
    }

    GameAssets assets() {
        return assets;
    }

    private StateModel sharedMissing;

    // ブロックではないが、ゲームがブロックの状態のモデルで描くもの (額縁)。状態はこちらで決めるので、そのまま覚えてよい
    private static final java.util.Set<String> ENTITY_STATES = new java.util.HashSet<String>(
            java.util.Arrays.asList("minecraft:item_frame", "minecraft:glow_item_frame"));

    StateModel model(BlockState input) {
        // 表にあるブロックは、性質をそろえた状態で覚える。表にないブロック (別の Mod のものや、壊れた名前) は覚えずに、紫と黒の立方体にする
        // (状態は構造物のファイルの中身で決まり、そのまま覚えるといくらでも増えうるため)
        BlockState state = input;
        try {
            final BlockData data = BlockData.get();
            final BlockData.Block block = data.block(input.name);
            if (block == null && !ENTITY_STATES.contains(input.name)) {
                if (MISSING.size() < 256) MISSING.add(input.name);
                if (sharedMissing == null) sharedMissing = missingModel();
                return sharedMissing;
            }
            if (block != null) state = data.complete(block, input);
        } catch (IOException e) {
            // 表が読めなければ、そのままの状態で探す
        }
        StateModel model = states.get(state);
        if (model == null) {
            try {
                model = build(state);
            } catch (IOException | RuntimeException e) {
                model = missingModel();
            }
            states.put(state, model);
        }
        return model;
    }

    // モデルが見つからないブロックは、紫と黒の立方体にする (Minecraft と同じ)
    private StateModel missingModel() {
        final List<Quad> quads = new ArrayList<Quad>();
        final Sprite sprite = Sprite.missing();
        for (int face = 0; face < 6; face++) {
            quads.add(bakeFace(new float[]{0, 0, 0}, new float[]{16, 16, 16}, face, null, 0, sprite, false, -1, face, null, null, IDENTITY, false));
        }
        return new StateModel(Collections.singletonList(new Choice(new Variant[]{new Variant(quads, true)}, new int[]{1})), false);
    }

    private Map<String, Object> blockstateFile(String name) throws IOException {
        Map<String, Object> file = blockstateFiles.get(name);
        if (file == null) {
            final byte[] bytes = assets.read(GameAssets.path(name, "blockstates", ".json"));
            file = bytes == null ? Collections.<String, Object>emptyMap() : Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            blockstateFiles.put(name, file);
        }
        return file;
    }

    // モデルが見つからなかったブロックの名前 (確認用)
    static final java.util.Set<String> MISSING = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private StateModel build(BlockState state) throws IOException {
        final Map<String, Object> file = blockstateFile(state.name);
        if (file.isEmpty()) {
            if (MISSING.size() < 256) MISSING.add(state.name);
            return missingModel();
        }
        final List<Choice> choices = new ArrayList<Choice>();
        boolean multipart = false;
        final Map<String, Object> variants = Json.object(file, "variants");
        if (!variants.isEmpty()) {
            Object selected = null;
            for (Map.Entry<String, Object> entry : variants.entrySet()) {
                if (matchesVariant(state, entry.getKey())) selected = entry.getValue();
            }
            if (selected != null) choices.add(choice(selected));
        }
        final List<Object> parts = Json.array(file, "multipart");
        if (!parts.isEmpty()) {
            multipart = true;
            for (Object item : parts) {
                final Map<String, Object> part = Json.object(item);
                if (part.containsKey("when") && !matchesCondition(state, Json.object(part, "when"))) continue;
                choices.add(choice(part.get("apply")));
            }
        }
        if (choices.isEmpty()) return variants.isEmpty() && parts.isEmpty() ? missingModel() : StateModel.EMPTY;
        return new StateModel(choices, multipart || choices.size() > 1);
    }

    // "facing=east,half=bottom" のような条件に、状態が当てはまるか
    private static boolean matchesVariant(BlockState state, String key) {
        if (key.isEmpty()) return true;
        for (String pair : key.split(",")) {
            final int equals = pair.indexOf('=');
            if (equals < 0) continue;
            final String value = state.get(pair.substring(0, equals));
            if (value == null || !value.equals(pair.substring(equals + 1))) return false;
        }
        return true;
    }

    private static boolean matchesCondition(BlockState state, Map<String, Object> condition) {
        if (condition.containsKey("OR")) {
            for (Object item : Json.array(condition, "OR")) {
                if (matchesCondition(state, Json.object(item))) return true;
            }
            return false;
        }
        if (condition.containsKey("AND")) {
            for (Object item : Json.array(condition, "AND")) {
                if (!matchesCondition(state, Json.object(item))) return false;
            }
            return true;
        }
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            final String actual = state.get(entry.getKey());
            if (actual == null) return false;
            final Object expected = entry.getValue();
            final String text = expected instanceof Double ? String.valueOf(Math.round((Double) expected)) : String.valueOf(expected);
            boolean any = false;
            for (String term : text.split("\\|")) {
                final boolean negated = term.startsWith("!");
                final String value = negated ? term.substring(1) : term;
                if (actual.equals(value) ^ negated) any = true;
            }
            if (!any) return false;
        }
        return true;
    }

    private Choice choice(Object value) throws IOException {
        final List<Object> list = value instanceof List ? Json.array(value) : Collections.singletonList(value);
        final Variant[] variants = new Variant[list.size()];
        final int[] weights = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Map<String, Object> variant = Json.object(list.get(i));
            variants[i] = bakeVariant(variant);
            weights[i] = Math.max(1, Json.integer(variant, "weight", 1));
        }
        return new Choice(variants, weights);
    }

    // ---------- アイテムのモデル ----------

    // アイテムのモデル: 四角形と、表示する場所ごとの変換 ({回転 x, y, z, 移動 x, y, z, 拡大 x, y, z})
    static final class ItemModel {
        final List<Quad> quads;
        final Map<String, float[]> display;

        ItemModel(List<Quad> quads, Map<String, float[]> display) {
            this.quads = quads;
            this.display = display;
        }
    }

    private final Map<String, ItemModel> itemModels = new ConcurrentHashMap<String, ItemModel>();

    // アイテムのモデル (models/item/... など)。平らなアイテム (builtin/generated) は、絵の形に 1 ピクセルの厚みを付ける
    ItemModel itemModel(String name) {
        ItemModel model = itemModels.get(name);
        if (model != null) return model;
        try {
            model = buildItemModel(name);
        } catch (IOException | RuntimeException e) {
            model = new ItemModel(Collections.<Quad>emptyList(), Collections.<String, float[]>emptyMap());
        }
        itemModels.put(name, model);
        return model;
    }

    private ItemModel buildItemModel(String name) throws IOException {
        final ResolvedModel resolved = resolve(name);
        // 表示する場所ごとの変換は、継承をたどって最初に見つかったもの
        final Map<String, float[]> display = new HashMap<String, float[]>();
        boolean generated = false;
        String current = name;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            final String key = current.indexOf(':') >= 0 ? current : "minecraft:" + current;
            if (key.equals("minecraft:builtin/generated")) {
                generated = true;
                break;
            }
            final Map<String, Object> file = modelFile(current);
            if (file.isEmpty()) break;
            for (Map.Entry<String, Object> entry : Json.object(file, "display").entrySet()) {
                if (display.containsKey(entry.getKey())) continue;
                final Map<String, Object> transform = Json.object(entry.getValue());
                final float[] rotation = Json.numbers(transform, "rotation", new float[]{0, 0, 0});
                final float[] translation = Json.numbers(transform, "translation", new float[]{0, 0, 0});
                final float[] scale = Json.numbers(transform, "scale", new float[]{1, 1, 1});
                final float[] values = new float[9];
                for (int i = 0; i < 3; i++) {
                    values[i] = rotation[i];
                    values[3 + i] = Math.max(-5, Math.min(5, translation[i] * 0.0625f));
                    values[6 + i] = Math.max(-4, Math.min(4, scale[i]));
                }
                display.put(entry.getKey(), values);
            }
            if (file.get("elements") instanceof List) break;
            final Object parent = file.get("parent");
            current = parent instanceof String ? (String) parent : null;
        }
        final List<Quad> quads = new ArrayList<Quad>();
        if (generated && resolved.elements == null) {
            for (int layer = 0; layer < 5; layer++) {
                final Object texture = resolved.textures.get("layer" + layer);
                if (texture == null) break;
                final Object[] reference = resolveTexture(resolved, "#layer" + layer);
                if (reference == null) break;
                generateLayer(quads, Sprite.get(assets, (String) reference[0]), layer);
            }
        } else {
            quads.addAll(bakeVariant(variantFor(name)).quads);
        }
        return new ItemModel(quads, display);
    }

    private static Map<String, Object> variantFor(String model) {
        final Map<String, Object> variant = new HashMap<String, Object>();
        variant.put("model", model);
        return variant;
    }

    // ItemModelGenerator: 絵の表と裏、色のあるピクセルの縁ごとの側面
    private static void generateLayer(List<Quad> quads, Sprite sprite, int layer) {
        final float[] from = {0, 0, 7.5f}, to = {16, 16, 8.5f};
        quads.add(bakeFace(from, to, SOUTH, new float[]{0, 0, 16, 16}, 0, sprite, false, layer, -1, null, null, IDENTITY, false));
        quads.add(bakeFace(from, to, NORTH, new float[]{16, 0, 0, 16}, 0, sprite, false, layer, -1, null, null, IDENTITY, false));
        final int width = sprite.width, height = sprite.height;
        final float xScale = 16.0f / width, yScale = 16.0f / height;
        final java.util.Set<Long> faces = new java.util.HashSet<Long>();
        final int frames = sprite.sequence == null ? 1 : sprite.frames.length;
        // 上、下、左、右 (左は東向きの面、右は西向きの面)
        final int[][] steps = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int f = 0; f < frames; f++) {
            final int[] pixels = sprite.frames[f];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((pixels[y * width + x] >>> 24) == 0) continue;
                    for (int side = 0; side < 4; side++) {
                        // checkTransition: 向きの step を引いた位置のピクセルが透明なら、そこに側面を作る
                        final int sx = x - steps[side][0];
                        final int sy = y - steps[side][1];
                        final boolean transparent = sx < 0 || sy < 0 || sx >= width || sy >= height || (pixels[sy * width + sx] >>> 24) == 0;
                        if (transparent) faces.add(((long) side << 40) | ((long) x << 20) | y);
                    }
                }
            }
        }
        final int[] directions = {UP, DOWN, EAST, WEST};
        for (long face : faces) {
            final int side = (int) (face >>> 40), x = (int) ((face >>> 20) & 0xFFFFF), y = (int) (face & 0xFFFFF);
            final float u0 = x + 0.1f, u1 = x + 1.0f - 0.1f;
            final float v0, v1;
            if (side < 2) {
                v0 = y + 0.1f;
                v1 = y + 1.0f - 0.1f;
            } else {
                v0 = y + 1.0f - 0.1f;
                v1 = y + 0.1f;
            }
            float startX = x, startY = y, endX = x, endY = y;
            switch (side) {
                case 0:
                    endX = x + 1;
                    break;
                case 1:
                    endX = x + 1;
                    startY = y + 1;
                    endY = y + 1;
                    break;
                case 2:
                    endY = y + 1;
                    break;
                default:
                    startX = x + 1;
                    endX = x + 1;
                    endY = y + 1;
                    break;
            }
            startX *= xScale;
            endX *= xScale;
            startY = 16 - startY * yScale;
            endY = 16 - endY * yScale;
            final float[] a, b;
            switch (side) {
                case 0:
                    a = new float[]{startX, startY, 7.5f};
                    b = new float[]{endX, startY, 8.5f};
                    break;
                case 1:
                    a = new float[]{startX, endY, 7.5f};
                    b = new float[]{endX, endY, 8.5f};
                    break;
                case 2:
                    a = new float[]{startX, startY, 7.5f};
                    b = new float[]{startX, endY, 8.5f};
                    break;
                default:
                    a = new float[]{endX, startY, 7.5f};
                    b = new float[]{endX, endY, 8.5f};
                    break;
            }
            // ゲームと同じく、from と to は大小をそろえずにそのまま使う (面の向きは頂点から決まる)
            quads.add(bakeFaceUnchecked(a, b, directions[side], new float[]{u0 * xScale, v0 * yScale, u1 * xScale, v1 * yScale}, sprite, layer));
        }
    }

    // 厚さのない面でも描く (bakeFace と同じ計算で、透明度の判定は絵全体で行う)
    private static Quad bakeFaceUnchecked(float[] from, float[] to, int face, float[] uv, Sprite sprite, int tint) {
        return bakeFace(from, to, face, uv, 0, sprite, false, tint, -1, null, null, IDENTITY, false);
    }

    // ---------- モデル ----------

    private Map<String, Object> modelFile(String name) throws IOException {
        final String key = name.indexOf(':') >= 0 ? name : "minecraft:" + name;
        Map<String, Object> file = modelFiles.get(key);
        if (file == null) {
            final byte[] bytes = assets.read(GameAssets.path(key, "models", ".json"));
            file = bytes == null ? Collections.<String, Object>emptyMap() : Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            modelFiles.put(key, file);
        }
        return file;
    }

    // モデルの継承をたどった結果
    private static final class ResolvedModel {
        List<Object> elements;
        final Map<String, Object> textures = new HashMap<String, Object>();
        Boolean ambientOcclusion;
    }

    private ResolvedModel resolve(String name) throws IOException {
        final ResolvedModel resolved = new ResolvedModel();
        String current = name;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            final Map<String, Object> file = modelFile(current);
            if (file.isEmpty()) break;
            if (resolved.elements == null && file.get("elements") instanceof List) resolved.elements = Json.array(file, "elements");
            if (resolved.ambientOcclusion == null && file.get("ambientocclusion") instanceof Boolean) {
                resolved.ambientOcclusion = (Boolean) file.get("ambientocclusion");
            }
            for (Map.Entry<String, Object> texture : Json.object(file, "textures").entrySet()) {
                // 子のモデルの指定が優先される
                if (!resolved.textures.containsKey(texture.getKey())) resolved.textures.put(texture.getKey(), texture.getValue());
            }
            final Object parent = file.get("parent");
            current = parent instanceof String ? (String) parent : null;
        }
        return resolved;
    }

    // テクスチャの名前 (#side など) をたどって、テクスチャの名前と「常に半透明」の指定を返す。見つからなければ null
    private static Object[] resolveTexture(ResolvedModel model, String reference) {
        // 面の texture は、# がなくてもテクスチャの名前 (TextureSlots.getMaterial)
        String current = reference.startsWith("#") ? reference : "#" + reference;
        for (int depth = 0; depth < 32; depth++) {
            if (!current.startsWith("#")) return new Object[]{current, Boolean.FALSE};
            final Object value = model.textures.get(current.substring(1));
            if (value instanceof String) {
                current = (String) value;
            } else if (value instanceof Map) {
                final Map<String, Object> material = Json.object(value);
                final String sprite = Json.string(material, "sprite", null);
                if (sprite == null) return null;
                if (sprite.startsWith("#")) {
                    current = sprite;
                    continue;
                }
                return new Object[]{sprite, Json.bool(material, "force_translucent", false)};
            } else {
                return null;
            }
        }
        return null;
    }

    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private Variant bakeVariant(Map<String, Object> variant) throws IOException {
        final String model = Json.string(variant, "model", "minecraft:block/air");
        final int x = Json.integer(variant, "x", 0), y = Json.integer(variant, "y", 0), z = Json.integer(variant, "z", 0);
        final boolean uvLock = Json.bool(variant, "uvlock", false);
        final String key = model + "|" + x + "|" + y + "|" + z + "|" + uvLock;
        Variant result = baked.get(key);
        if (result != null) return result;

        final ResolvedModel resolved = resolve(model);
        final float[] rotation = multiply(rotationZ(-z), multiply(rotationY(-y), rotationX(-x)));
        final boolean rotated = x % 360 != 0 || y % 360 != 0 || z % 360 != 0;
        final List<Quad> quads = new ArrayList<Quad>();
        if (resolved.elements != null) {
            for (Object item : resolved.elements) {
                final Map<String, Object> element = Json.object(item);
                final float[] from = Json.numbers(element, "from", new float[]{0, 0, 0});
                final float[] to = Json.numbers(element, "to", new float[]{16, 16, 16});
                final float[] elementRotation = elementRotation(Json.object(element, "rotation"));
                final float[] origin = elementRotation == null ? null : Json.numbers(Json.object(element, "rotation"), "origin", new float[]{8, 8, 8});
                final String shadeName = Json.string(element, "shade_direction_override", null);
                final int shadeOverride = shadeName == null ? -1 : direction(shadeName);
                // 1.21.4 までの "shade": false は、上向きの明るさと同じ
                final boolean noShade = element.get("shade") instanceof Boolean && !((Boolean) element.get("shade"));

                boolean drawX = true, drawY = true, drawZ = true;
                if (from[0] == to[0]) drawY = drawZ = false;
                if (from[1] == to[1]) drawX = drawZ = false;
                if (from[2] == to[2]) drawX = drawY = false;
                if (!drawX && !drawY && !drawZ) continue;

                for (Map.Entry<String, Object> faceEntry : Json.object(element, "faces").entrySet()) {
                    final int face = direction(faceEntry.getKey());
                    if (face < 0) continue;
                    final int axis = face / 2;
                    if ((axis == 0 && !drawY) || (axis == 1 && !drawZ) || (axis == 2 && !drawX)) continue;
                    final Map<String, Object> faceData = Json.object(faceEntry.getValue());
                    final Object[] texture = resolveTexture(resolved, Json.string(faceData, "texture", "#missing"));
                    if (texture == null) Sprite.MISSING.add(model + " " + Json.string(faceData, "texture", "?"));
                    final Sprite sprite = texture == null ? Sprite.missing() : Sprite.get(assets, (String) texture[0]);
                    final boolean forceTranslucent = texture != null && (Boolean) texture[1];
                    final float[] uv = faceData.get("uv") instanceof List ? Json.numbers(faceData, "uv", new float[]{0, 0, 16, 16}) : null;
                    final int uvRotation = ((Json.integer(faceData, "rotation", 0) % 360) + 360) % 360 / 90;
                    final String cullName = Json.string(faceData, "cullface", null);
                    final int cull = cullName == null ? -1 : direction(cullName);
                    final int shade = noShade ? UP : shadeOverride;
                    final Quad quad = bakeFace(from, to, face, uv, uvRotation, sprite, forceTranslucent, Json.integer(faceData, "tintindex", -1),
                            shade, elementRotation, origin, rotated ? rotation : IDENTITY, uvLock && rotated);
                    if (cull >= 0) quad.cull = rotated ? rotateDirection(rotation, cull) : cull;
                    quads.add(quad);
                }
            }
        }
        result = new Variant(quads, resolved.ambientOcclusion == null || resolved.ambientOcclusion);
        baked.put(key, result);
        return result;
    }

    // 要素の回転 (原点を中心にする前の 3x3 の行列)。回転しなければ null
    private static float[] elementRotation(Map<String, Object> rotation) {
        if (rotation.isEmpty()) return null;
        float[] matrix;
        if (rotation.containsKey("axis") || rotation.containsKey("angle")) {
            final float angle = Json.number(rotation, "angle", 0);
            if (angle == 0) return null;
            final String axis = Json.string(rotation, "axis", "y");
            matrix = axis.equals("x") ? rotationX(angle) : axis.equals("z") ? rotationZ(angle) : rotationY(angle);
        } else {
            final float x = Json.number(rotation, "x", 0), y = Json.number(rotation, "y", 0), z = Json.number(rotation, "z", 0);
            matrix = multiply(rotationZ(z), multiply(rotationY(y), rotationX(x)));
        }
        if (Json.bool(rotation, "rescale", false)) {
            // 回転で縮んで見える分を、軸ごとに引き伸ばす
            final float[] scale = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                final float a = Math.abs(matrix[axis]), b = Math.abs(matrix[3 + axis]), c = Math.abs(matrix[6 + axis]);
                scale[axis] = 1f / Math.max(a, Math.max(b, c));
            }
            final float[] scaled = matrix.clone();
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) scaled[row * 3 + column] *= scale[column];
            }
            matrix = scaled;
        }
        return matrix;
    }

    /**
     * 要素の面 1 枚を四角形にする (Minecraft の FaceBakery と同じ計算)。
     *
     * @param uv          テクスチャの範囲 (0 - 16)。null なら要素の大きさから決める
     * @param uvRotation  テクスチャの回転 (90 度単位の回数)
     * @param shade       明るさに使う方向。-1 なら面の向き
     * @param elementRot  要素の回転。なければ null
     * @param rotation    モデル全体の回転
     * @param uvLock      回転してもテクスチャの向きを変えないか
     */
    static Quad bakeFace(float[] from, float[] to, int face, float[] uv, int uvRotation, Sprite sprite, boolean forceTranslucent,
                         int tint, int shade, float[] elementRot, float[] origin, float[] rotation, boolean uvLock) {
        if (uv == null) uv = defaultUv(from, to, face);
        final Quad quad = new Quad();
        quad.sprite = sprite;
        quad.tint = tint;
        quad.transparency = forceTranslucent ? Sprite.TRANSLUCENT
                : sprite.transparency(Math.min(uv[0], uv[2]) / 16f, Math.min(uv[1], uv[3]) / 16f,
                Math.max(uv[0], uv[2]) / 16f, Math.max(uv[1], uv[3]) / 16f);

        final float[] uvTransform = uvLock ? inverseFaceTransform(rotation, face) : null;
        final boolean rotatedModel = rotation != IDENTITY;
        for (int i = 0; i < 4; i++) {
            final int[] info = FACE_INFO[face][i];
            float x = (info[0] == 0 ? from[0] : to[0]) / 16f;
            float y = (info[1] == 0 ? from[1] : to[1]) / 16f;
            float z = (info[2] == 0 ? from[2] : to[2]) / 16f;
            if (elementRot != null) {
                final float ox = origin[0] / 16f, oy = origin[1] / 16f, oz = origin[2] / 16f;
                final float px = x - ox, py = y - oy, pz = z - oz;
                x = elementRot[0] * px + elementRot[1] * py + elementRot[2] * pz + ox;
                y = elementRot[3] * px + elementRot[4] * py + elementRot[5] * pz + oy;
                z = elementRot[6] * px + elementRot[7] * py + elementRot[8] * pz + oz;
            }
            if (rotatedModel) {
                final float px = x - 0.5f, py = y - 0.5f, pz = z - 0.5f;
                x = rotation[0] * px + rotation[1] * py + rotation[2] * pz + 0.5f;
                y = rotation[3] * px + rotation[4] * py + rotation[5] * pz + 0.5f;
                z = rotation[6] * px + rotation[7] * py + rotation[8] * pz + 0.5f;
            }
            quad.positions[i * 3] = x;
            quad.positions[i * 3 + 1] = y;
            quad.positions[i * 3 + 2] = z;

            final int corner = (i + uvRotation) % 4;
            float u = (corner == 0 || corner == 1 ? uv[0] : uv[2]) / 16f;
            float v = (corner == 0 || corner == 3 ? uv[1] : uv[3]) / 16f;
            if (uvTransform != null) {
                final float cu = u - 0.5f, cv = v - 0.5f;
                u = uvTransform[0] * cu + uvTransform[1] * cv + 0.5f;
                v = uvTransform[3] * cu + uvTransform[4] * cv + 0.5f;
            }
            quad.uvs[i * 2] = u;
            quad.uvs[i * 2 + 1] = v;
        }

        // 面の向きを、回転したあとの頂点から求める
        final float ax = quad.positions[3] - quad.positions[0], ay = quad.positions[4] - quad.positions[1], az = quad.positions[5] - quad.positions[2];
        final float bx = quad.positions[6] - quad.positions[0], by = quad.positions[7] - quad.positions[1], bz = quad.positions[8] - quad.positions[2];
        final float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        int best = -1;
        float bestDot = 0;
        final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0 && !Float.isNaN(length)) {
            for (int d = 0; d < 6; d++) {
                final float dot = (nx * NORMALS[d][0] + ny * NORMALS[d][1] + nz * NORMALS[d][2]) / length;
                if (dot >= 0 && dot > bestDot) {
                    bestDot = dot;
                    best = d;
                }
            }
        }
        if (elementRot == null && best >= 0) recalculateWinding(quad, best);
        quad.direction = best < 0 ? UP : best;
        quad.shadeDirection = shade >= 0 ? shade : quad.direction;
        return quad;
    }

    // 頂点の順番を、向きごとの決まった順にそろえる (明るさの計算が頂点の順番に依るため)
    private static void recalculateWinding(Quad quad, int direction) {
        final float[] p = quad.positions;
        float minX = 999, minY = 999, minZ = 999, maxX = -999, maxY = -999, maxZ = -999;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, p[i * 3]);
            minY = Math.min(minY, p[i * 3 + 1]);
            minZ = Math.min(minZ, p[i * 3 + 2]);
            maxX = Math.max(maxX, p[i * 3]);
            maxY = Math.max(maxY, p[i * 3 + 1]);
            maxZ = Math.max(maxZ, p[i * 3 + 2]);
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            final int[] info = FACE_INFO[direction][vertex];
            final float x = info[0] == 0 ? minX : maxX, y = info[1] == 0 ? minY : maxY, z = info[2] == 0 ? minZ : maxZ;
            int found = -1;
            for (int i = vertex; i < 4; i++) {
                if (p[i * 3] == x && p[i * 3 + 1] == y && p[i * 3 + 2] == z) {
                    found = i;
                    break;
                }
            }
            // 斜めの面など、箱の角に頂点がない場合はそのままにする
            if (found < 0) return;
            if (found != vertex) {
                for (int k = 0; k < 3; k++) {
                    final float t = p[vertex * 3 + k];
                    p[vertex * 3 + k] = p[found * 3 + k];
                    p[found * 3 + k] = t;
                }
                for (int k = 0; k < 2; k++) {
                    final float t = quad.uvs[vertex * 2 + k];
                    quad.uvs[vertex * 2 + k] = quad.uvs[found * 2 + k];
                    quad.uvs[found * 2 + k] = t;
                }
            }
        }
    }

    private static float[] defaultUv(float[] from, float[] to, int face) {
        switch (face) {
            case DOWN:
                return new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
            case UP:
                return new float[]{from[0], from[2], to[0], to[2]};
            case NORTH:
                return new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case SOUTH:
                return new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case WEST:
                return new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            default:
                return new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
        }
    }

    static int direction(String name) {
        if (name == null) return -1;
        switch (name) {
            case "down":
            case "bottom":
                return DOWN;
            case "up":
            case "top":
                return UP;
            case "north":
                return NORTH;
            case "south":
                return SOUTH;
            case "west":
                return WEST;
            case "east":
                return EAST;
            default:
                return -1;
        }
    }

    // 行列で方向を回したときに、最も近い方向
    static int rotateDirection(float[] rotation, int direction) {
        final float[] n = NORMALS[direction];
        final float x = rotation[0] * n[0] + rotation[1] * n[1] + rotation[2] * n[2];
        final float y = rotation[3] * n[0] + rotation[4] * n[1] + rotation[5] * n[2];
        final float z = rotation[6] * n[0] + rotation[7] * n[1] + rotation[8] * n[2];
        return nearest(x, y, z);
    }

    private static int nearest(float x, float y, float z) {
        int best = UP;
        float bestDot = -Float.MAX_VALUE;
        for (int d = 0; d < 6; d++) {
            final float dot = x * NORMALS[d][0] + y * NORMALS[d][1] + z * NORMALS[d][2];
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    // ---------- 回転の行列 (3x3、行の順) ----------

    static float[] rotationX(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) cos(a), s = (float) sin(a);
        return new float[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    static float[] rotationY(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) cos(a), s = (float) sin(a);
        return new float[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    static float[] rotationZ(float degrees) {
        final double a = Math.toRadians(degrees);
        final float c = (float) cos(a), s = (float) sin(a);
        return new float[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    // 90 度の倍数では、誤差のないちょうどの値にする
    private static double cos(double radians) {
        final double value = Math.cos(radians);
        return Math.abs(value) < 1e-9 ? 0 : Math.abs(Math.abs(value) - 1) < 1e-9 ? Math.signum(value) : value;
    }

    private static double sin(double radians) {
        final double value = Math.sin(radians);
        return Math.abs(value) < 1e-9 ? 0 : Math.abs(Math.abs(value) - 1) < 1e-9 ? Math.signum(value) : value;
    }

    static float[] multiply(float[] a, float[] b) {
        final float[] result = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                float sum = 0;
                for (int k = 0; k < 3; k++) sum += a[row * 3 + k] * b[k * 3 + column];
                result[row * 3 + column] = sum;
            }
        }
        return result;
    }

    static float[] transpose(float[] m) {
        return new float[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    // 面を「南向きの面」に写す回転 (BlockMath の VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL)
    private static float[] localToGlobal(int face) {
        switch (face) {
            case EAST:
                return rotationY(90);
            case WEST:
                return rotationY(-90);
            case NORTH:
                return rotationY(180);
            case UP:
                return rotationX(-90);
            case DOWN:
                return rotationX(90);
            default:
                return IDENTITY.clone();
        }
    }

    /**
     * テクスチャを固定するときの、テクスチャ座標の変換 (BlockMath.getFaceTransformation の逆)。
     */
    private static float[] inverseFaceTransform(float[] rotation, int face) {
        final float[] action = multiply(rotation, localToGlobal(face));
        final int newSide = nearest(action[2], action[5], action[8]);
        final float[] transform = multiply(transpose(localToGlobal(newSide)), action);
        return transpose(transform);
    }
}
