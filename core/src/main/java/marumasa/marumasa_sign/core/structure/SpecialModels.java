package marumasa.marumasa_sign.core.structure;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ブロックのモデルのファイルでは形が決まらず、ゲームのコードで描かれるブロック (チェスト、旗、頭など) と、エンティティを描く。
 * 形は EntityModels (ゲームのモデルの定義から書き出したもの)、置き方は各ブロックの描き方 (BlockEntityRenderer) と同じにする。
 */
final class SpecialModels {

    // 染料の色 (DyeColor.getTextureDiffuseColor)。番号は StructureFixer.DYE_COLORS と同じ順
    static final int[] DYE_DIFFUSE = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    // 1.20.4 までの旗の模様の短い名前
    private static final Map<String, String> PATTERN_CODES = new HashMap<String, String>();

    static {
        final String[] pairs = {"b", "base", "bs", "stripe_bottom", "ts", "stripe_top", "ls", "stripe_left", "rs", "stripe_right",
                "cs", "stripe_center", "ms", "stripe_middle", "drs", "stripe_downright", "dls", "stripe_downleft", "ss", "small_stripes",
                "cr", "cross", "sc", "straight_cross", "ld", "diagonal_left", "rud", "diagonal_up_right", "lud", "diagonal_up_left",
                "rd", "diagonal_right", "vh", "half_vertical", "vhr", "half_vertical_right", "hh", "half_horizontal",
                "hhb", "half_horizontal_bottom", "bl", "square_bottom_left", "br", "square_bottom_right", "tl", "square_top_left",
                "tr", "square_top_right", "bt", "triangle_bottom", "tt", "triangle_top", "bts", "triangles_bottom",
                "tts", "triangles_top", "mc", "circle", "mr", "rhombus", "bo", "border", "cbo", "curly_border", "bri", "bricks",
                "gra", "gradient", "gru", "gradient_up", "cre", "creeper", "sku", "skull", "flo", "flower", "moj", "mojang",
                "glb", "globe", "pig", "piglin", "flw", "flow", "gus", "guster"};
        for (int i = 0; i < pairs.length; i += 2) PATTERN_CODES.put(pairs[i], pairs[i + 1]);
    }

    // 作った絵 (旗の模様を重ねたものなど) を、同じ内容なら使い回す
    private static final BoundedCache<String, Sprite> COMPOSED = new BoundedCache<String, Sprite>(256);

    private final BlockModels models;
    private final GameAssets assets;
    private final PlayerSkins skins;
    private final SignTexts signs;
    final NestedSigns nested;
    // 1 つの構造物で使う、看板の文字の絵と旗の絵の数の上限
    private static final int MAX_TEXT_SPRITES = 64, MAX_BANNER_SPRITES = 64;
    private final java.util.Set<Sprite> usedTexts = new java.util.HashSet<Sprite>();
    private final java.util.Set<String> usedBanners = new java.util.HashSet<String>();

    SpecialModels(BlockModels models) {
        this.models = models;
        this.assets = models.assets();
        this.skins = new PlayerSkins(assets);
        this.signs = new SignTexts(assets);
        this.nested = new NestedSigns();
    }

    private static boolean isChest(String path) {
        return path.equals("chest") || path.equals("trapped_chest") || path.equals("ender_chest") || path.endsWith("copper_chest");
    }

    private static boolean isSkull(String path) {
        return path.endsWith("_skull") || (path.endsWith("_head") && !path.equals("piston_head"));
    }

    // そのブロックを、ここで描くかどうか
    boolean handles(BlockState state) {
        final String path = state.path();
        if (!state.name.startsWith("minecraft:")) return false;
        return isChest(path) || path.endsWith("shulker_box") || path.endsWith("_banner") || isSkull(path)
                || path.equals("bell") || path.equals("decorated_pot") || path.equals("enchanting_table")
                || (path.equals("lectern") && "true".equals(state.get("has_book"))) || path.equals("conduit")
                || path.endsWith("copper_golem_statue") || path.equals("end_portal") || path.equals("end_gateway")
                || path.equals("moving_piston") || path.endsWith("_sign");
    }

    /**
     * 描く。ブロックのモデルを描かないほうがよいとき (MarumaSign の看板の板は消える) は true を返す。
     */
    boolean emit(StructureMesher mesher, BlockState state, Map<String, Object> entity, int x, int y, int z) {
        try {
            return emitChecked(mesher, state, entity == null ? new HashMap<String, Object>() : entity, x, y, z);
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.info("Could not draw " + state + " (" + e + ")");
            return false;
        }
    }

    private boolean emitChecked(StructureMesher mesher, BlockState state, Map<String, Object> entity, int x, int y, int z) throws IOException {
        final String path = state.path();
        if (path.endsWith("_sign")) return signText(mesher, state, entity, mesher.structure().source, x, y, z);
        if (isChest(path)) chest(mesher, state, x, y, z);
        else if (path.endsWith("shulker_box")) shulkerBox(mesher, state, x, y, z);
        else if (path.endsWith("_banner")) banner(mesher, state, entity, mesher.structure().source, x, y, z);
        else if (isSkull(path)) skull(mesher, state, entity, x, y, z);
        else if (path.equals("bell")) model(mesher, "bell", sprite("minecraft:entity/bell/bell_body"), Transform.identity(), x, y, z);
        else if (path.equals("decorated_pot")) decoratedPot(mesher, state, entity, x, y, z);
        else if (path.equals("enchanting_table")) {
            // 近くにプレイヤーがいないときの、閉じた本 (EnchantTableRenderer)
            final Transform t = Transform.identity().translate(0.5f, 0.75f, 0.5f).translate(0, 0.1f, 0).rotateZ(80);
            model(mesher, "book_closed", sprite("minecraft:entity/enchantment/enchanting_table_book"), t, x, y, z);
        } else if (path.equals("lectern")) {
            final float yRot = yRot(StructureFixer.CLOCKWISE[facing(state)]);
            final Transform t = Transform.identity().translate(0.5f, 1.0625f, 0.5f).rotateY(-yRot).rotateZ(67.5f).translate(0, -0.125f, 0);
            model(mesher, "book_lectern", sprite("minecraft:entity/enchantment/enchanting_table_book"), t, x, y, z);
        } else if (path.equals("conduit")) {
            model(mesher, "conduit_shell", sprite("minecraft:entity/conduit/base"), Transform.identity().translate(0.5f, 0.5f, 0.5f), x, y, z);
        } else if (path.endsWith("copper_golem_statue")) copperGolem(mesher, state, x, y, z);
        else if (path.equals("end_portal") || path.equals("end_gateway")) endCube(mesher, path.equals("end_portal"), x, y, z);
        else if (path.equals("moving_piston")) movingPiston(mesher, entity, x, y, z);
        return false;
    }

    // ---------- 看板の文字 ----------

    private boolean signText(StructureMesher mesher, BlockState state, Map<String, Object> entity, Structure.Source source, int x, int y, int z) {
        final String path = state.path();
        final boolean hanging = path.endsWith("_hanging_sign");
        final boolean wall = path.contains("_wall_");
        final float angle;
        if (wall) {
            angle = yRot(facing(state));
        } else {
            int rotation = 0;
            try {
                rotation = Integer.parseInt(state.get("rotation"));
            } catch (NumberFormatException e) {
                // 値がなければ 0
            }
            angle = rotation * 22.5f;
        }
        // MarumaSign の看板 (吊り看板は対象外)。表と裏の行をつなげた文字が形式に合えば、板を消して中身を描く
        if (!hanging) {
            final marumasa.marumasa_sign.core.sign.SignSpec spec = signSpec(entity, source);
            if (spec != null) {
                final float scale = 1.0f / mesher.structure().step;
                nested.emitSign(mesher, spec, angle, wall, new float[]{x, y, z}, scale);
                return true;
            }
        }
        for (int side = 0; side < 2; side++) {
            final Object[] text = signSide(entity, side == 0, source);
            if (text == null) continue;
            @SuppressWarnings("unchecked") final List<List<SignTexts.Run>> lines = (List<List<SignTexts.Run>>) text[0];
            final int dyeColor = (Integer) text[1];
            final boolean glowing = (Boolean) text[2];
            // AbstractSignRenderer.getDarkColor
            final int dark = dyeColor == 0 && glowing ? 0xF0EBCC : StructureMesher.scale(0xFF000000 | dyeColor, 0.4f) & 0xFFFFFF;
            final int color = glowing ? dyeColor : dark;
            final int outline = glowing ? dark : -1;
            final Sprite sprite = signs.render(lines, hanging ? 9 : 10, hanging ? 60 : 90, color, outline);
            if (sprite == null) continue;
            // 違う文字の看板は 1 つずつ絵になるので、数に上限を設ける (まとめた画像が大きくなりすぎないように)
            if (!usedTexts.contains(sprite)) {
                if (usedTexts.size() >= MAX_TEXT_SPRITES) continue;
                usedTexts.add(sprite);
            }
            final Transform t = Transform.identity();
            if (hanging) {
                t.translate(0.5f, 0.9375f, 0.5f).rotateY(-angle).translate(0, -0.3125f, 0);
                if (side == 1) t.rotateY(180);
                t.translate(0, -0.32f, 0.073f).scale(0.0140625f, -0.0140625f, 0.0140625f);
            } else {
                t.translate(0.5f, 0.5f, 0.5f).rotateY(-angle);
                if (wall) t.translate(0, -0.3125f, -0.4375f);
                if (side == 1) t.rotateY(180);
                t.translate(0, 0.33333334f, 0.046666667f).scale(0.010416667f, -0.010416667f, 0.010416667f);
            }
            final float w = SignTexts.HALF_WIDTH, h = SignTexts.HALF_HEIGHT;
            final float[] corners = {-w, -h, -w, h, w, h, w, -h};
            final float[] uvs = {0, 0, 0, 1, 1, 1, 1, 0};
            final float[] positions = new float[12];
            for (int v = 0; v < 4; v++) {
                t.apply(corners[v * 2], corners[v * 2 + 1], 0, positions, v * 3);
                positions[v * 3] += x;
                positions[v * 3 + 1] += y;
                positions[v * 3 + 2] += z;
            }
            // 表が文字の前 (文字の座標の +Z) を向くように、頂点の順を合わせる
            final float[] front = new float[3], origin = new float[3];
            t.apply(0, 0, 1, front, 0);
            t.apply(0, 0, 0, origin, 0);
            final float fx = front[0] - origin[0], fy = front[1] - origin[1], fz = front[2] - origin[2];
            final float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
            final float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
            final float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            if (nx * fx + ny * fy + nz * fz < 0) {
                reverse(positions, 3);
                reverse(uvs, 2);
            }
            // 光る文字は明るさを付けない
            mesher.addQuad(StructureMesher.LAYER_CUTOUT, positions, uvs, 0xFFFFFFFF, sprite, !glowing);
        }
        return false;
    }

    // 看板のデータの文字 (表、裏の順に行をつなげたもの) が MarumaSign の形式なら、その内容。そうでなければ null
    static marumasa.marumasa_sign.core.sign.SignSpec signSpec(Map<String, Object> entity, Structure.Source source) {
        final List<List<SignTexts.Run>> all = new ArrayList<List<SignTexts.Run>>();
        for (int side = 0; side < 2; side++) {
            final Object[] text = signSide(entity, side == 0, source);
            if (text != null) {
                @SuppressWarnings("unchecked") final List<List<SignTexts.Run>> lines = (List<List<SignTexts.Run>>) text[0];
                all.addAll(lines);
            }
        }
        return NestedSigns.spec(all);
    }

    private static void reverse(float[] values, int stride) {
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < stride; k++) {
                final float t = values[i * stride + k];
                values[i * stride + k] = values[(3 - i) * stride + k];
                values[(3 - i) * stride + k] = t;
            }
        }
    }

    // 看板の片面の {行の並び, 色 (RGB), 光るか}。書かれていなければ null
    private static Object[] signSide(Map<String, Object> entity, boolean front, Structure.Source source) {
        final List<List<SignTexts.Run>> lines = new ArrayList<List<SignTexts.Run>>();
        int color = 0;
        boolean glowing = false;
        final Object java = entity.get(front ? "front_text" : "back_text");
        final Object bedrock = entity.get(front ? "FrontText" : "BackText");
        if (java instanceof Map) {
            final Map<String, Object> text = Nbt.compound(java);
            // 描くのは 4 行まで (それより多い行は読まない)
            final List<Object> messages = Nbt.list(text, "messages");
            for (int i = 0; i < messages.size() && i < 4; i++) lines.add(SignTexts.parse(messages.get(i)));
            color = dyeTextColor(Nbt.string(text, "color", "black"));
            glowing = Nbt.integer(text, "has_glowing_text", 0) != 0;
        } else if (bedrock instanceof Map || (front && entity.get("Text") instanceof String && source == Structure.Source.BEDROCK)) {
            final Map<String, Object> text = bedrock instanceof Map ? Nbt.compound(bedrock) : entity;
            final String[] split = Nbt.string(text, "Text", "").split("\n", 5);
            for (int i = 0; i < split.length && i < 4; i++) lines.add(SignTexts.parse(split[i]));
            color = Nbt.integer(text, "SignTextColor", 0xFF000000) & 0xFFFFFF;
            glowing = Nbt.integer(text, "IgnoreLighting", 0) != 0;
        } else if (front && (entity.containsKey("Text1") || entity.containsKey("Text2"))) {
            for (int i = 1; i <= 4; i++) lines.add(SignTexts.parse(entity.get("Text" + i) == null ? "" : entity.get("Text" + i)));
            color = dyeTextColor(Nbt.string(entity, "Color", "black"));
            glowing = Nbt.integer(entity, "GlowingText", 0) != 0;
        } else {
            return null;
        }
        while (lines.size() > 4) lines.remove(lines.size() - 1);
        return new Object[]{lines, color, glowing};
    }

    private static int dyeTextColor(String name) {
        for (int i = 0; i < 16; i++) {
            if (StructureFixer.DYE_COLORS[i].equals(name)) return SignTexts.DYE_TEXT[i];
        }
        return 0;
    }

    private Sprite sprite(String id) {
        return Sprite.get(assets, id);
    }

    // ---------- 共通 ----------

    // Direction.toYRot (南 0, 西 90, 北 180, 東 270)
    static float yRot(int direction) {
        switch (direction) {
            case StructureFixer.WEST:
                return 90;
            case StructureFixer.NORTH:
                return 180;
            case StructureFixer.EAST:
                return 270;
            default:
                return 0;
        }
    }

    private static int facing(BlockState state) {
        final int d = BlockModels.direction(state.get("facing"));
        return d < 0 ? StructureFixer.NORTH : d;
    }

    // モデルを変換して、ブロックの位置に置く
    void model(StructureMesher mesher, String name, Sprite sprite, Transform transform, int x, int y, int z) throws IOException {
        emitQuads(mesher, EntityModels.get(name), sprite, transform, x, y, z, 0xFFFFFFFF);
    }

    static void emitQuads(StructureMesher mesher, List<float[]> quads, Sprite sprite, Transform transform, float x, float y, float z, int color) {
        final boolean flip = transform.flips();
        final float[] positions = new float[12];
        final float[] uvs = new float[8];
        final float[] point = new float[3];
        for (float[] quad : quads) {
            float minU = 1, minV = 1, maxU = 0, maxV = 0;
            for (int v = 0; v < 4; v++) {
                final int source = flip ? 3 - v : v;
                transform.apply(quad[source * 5], quad[source * 5 + 1], quad[source * 5 + 2], point, 0);
                positions[v * 3] = point[0] + x;
                positions[v * 3 + 1] = point[1] + y;
                positions[v * 3 + 2] = point[2] + z;
                uvs[v * 2] = quad[source * 5 + 3];
                uvs[v * 2 + 1] = quad[source * 5 + 4];
                minU = Math.min(minU, uvs[v * 2]);
                maxU = Math.max(maxU, uvs[v * 2]);
                minV = Math.min(minV, uvs[v * 2 + 1]);
                maxV = Math.max(maxV, uvs[v * 2 + 1]);
            }
            final int layer = StructureMesher.layer(sprite.transparency(minU, minV, maxU, maxV));
            mesher.addQuad(layer, positions, uvs, color, sprite, true);
        }
    }

    // ---------- チェスト ----------

    private static boolean isChristmas() {
        final Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.MONTH) + 1 == 12 && calendar.get(Calendar.DATE) >= 24 && calendar.get(Calendar.DATE) <= 26;
    }

    private void chest(StructureMesher mesher, BlockState state, int x, int y, int z) throws IOException {
        final String path = state.path();
        final String type = state.get("type") == null ? "single" : state.get("type");
        String texture;
        if (path.equals("ender_chest")) {
            texture = "ender";
        } else {
            if (path.endsWith("copper_chest")) {
                final String base = path.replace("waxed_", "").replace("_copper_chest", "").replace("copper_chest", "");
                texture = base.isEmpty() ? "copper" : "copper_" + base;
            } else {
                texture = isChristmas() ? "christmas" : path.equals("trapped_chest") ? "trapped" : "normal";
            }
            if (type.equals("left")) texture += "_left";
            else if (type.equals("right")) texture += "_right";
        }
        final String model = path.equals("ender_chest") || type.equals("single") ? "chest" : type.equals("left") ? "chest_left" : "chest_right";
        final Transform t = Transform.identity().rotateYAround(-yRot(facing(state)), 0.5f, 0, 0.5f);
        model(mesher, model, sprite("minecraft:entity/chest/" + texture), t, x, y, z);
    }

    // ---------- シュルカーボックス ----------

    private void shulkerBox(StructureMesher mesher, BlockState state, int x, int y, int z) throws IOException {
        final String path = state.path();
        final String color = path.equals("shulker_box") ? "" : "_" + path.substring(0, path.length() - "_shulker_box".length());
        final int facing = BlockModels.direction(state.get("facing") == null ? "up" : state.get("facing"));
        final Transform t = Transform.identity().translate(0.5f, 0.5f, 0.5f).scale(0.9995f, 0.9995f, 0.9995f);
        // Direction.getRotation
        switch (facing) {
            case StructureFixer.DOWN:
                t.rotateX(180);
                break;
            case StructureFixer.NORTH:
                t.rotateX(90).rotateY(0).rotateZ(180);
                break;
            case StructureFixer.SOUTH:
                t.rotateX(90);
                break;
            case StructureFixer.WEST:
                t.rotateX(90).rotateZ(90);
                break;
            case StructureFixer.EAST:
                t.rotateX(90).rotateZ(-90);
                break;
            default:
                break;
        }
        t.scale(1, -1, -1).translate(0, -1, 0);
        model(mesher, "shulker_box", sprite("minecraft:entity/shulker/shulker" + color), t, x, y, z);
    }

    // ---------- 旗 ----------

    private void banner(StructureMesher mesher, BlockState state, Map<String, Object> entity, Structure.Source source, int x, int y, int z) throws IOException {
        final String path = state.path();
        final boolean wall = path.endsWith("_wall_banner");
        final String colorName = path.substring(0, path.length() - (wall ? "_wall_banner" : "_banner").length());
        int base = 0;
        for (int i = 0; i < 16; i++) {
            if (StructureFixer.DYE_COLORS[i].equals(colorName)) base = i;
        }
        final float angle;
        if (wall) {
            angle = yRot(facing(state));
        } else {
            int rotation = 0;
            try {
                rotation = Integer.parseInt(state.get("rotation"));
            } catch (NumberFormatException e) {
                // 値がなければ 0
            }
            angle = rotation * 22.5f;
        }
        final Transform t = Transform.identity().translate(0.5f, 0, 0.5f).rotateY(-angle).scale(0.6666667f, -0.6666667f, -0.6666667f);
        final Sprite texture = bannerTexture(base, patterns(entity, source));
        model(mesher, wall ? "banner_wall" : "banner_standing", texture, t, x, y, z);
        model(mesher, wall ? "banner_flag_wall" : "banner_flag_standing", texture, t, x, y, z);
    }

    // 模様の一覧 ({模様の名前, 色の番号})
    private static List<Object[]> patterns(Map<String, Object> entity, Structure.Source source) {
        final List<Object[]> result = new ArrayList<Object[]>();
        // 1.20.5 から: patterns: [{color: "red", pattern: "minecraft:creeper"}]
        for (Object item : Nbt.list(entity, "patterns")) {
            final Map<String, Object> layer = Nbt.compound(item);
            final Object pattern = layer.get("pattern");
            String name = pattern instanceof String ? (String) pattern : Nbt.string(Nbt.compound(pattern), "asset_id", "");
            name = name.substring(name.indexOf(':') + 1);
            final String color = Nbt.string(layer, "color", "white");
            int index = 0;
            for (int i = 0; i < 16; i++) {
                if (StructureFixer.DYE_COLORS[i].equals(color)) index = i;
            }
            result.add(new Object[]{name, index});
        }
        // 1.20.4 まで: Patterns: [{Color: 番号, Pattern: "cre"}]。1.12 までと統合版は色の番号が逆の順
        for (Object item : Nbt.list(entity, "Patterns")) {
            final Map<String, Object> layer = Nbt.compound(item);
            final String code = Nbt.string(layer, "Pattern", "");
            final String name = PATTERN_CODES.containsKey(code) ? PATTERN_CODES.get(code) : code;
            int color = Nbt.integer(layer, "Color", 0) & 15;
            if (source != Structure.Source.JAVA) color = 15 - color;
            result.add(new Object[]{name, color});
        }
        return result;
    }

    // 旗の絵 (下地に模様を重ねたもの)。BannerRenderer は模様を 1 枚ずつ重ねて描く
    private Sprite bannerTexture(int base, List<Object[]> patterns) {
        final StringBuilder key = new StringBuilder("banner:").append(base);
        for (int i = 0; i < patterns.size() && i < 16; i++) key.append('|').append(patterns.get(i)[0]).append(':').append(patterns.get(i)[1]);
        final String id = key.toString();
        // 違う模様の旗は 1 つずつ絵になるので、数に上限を設ける。超えたら模様を付けない
        if (!usedBanners.contains(id)) {
            if (usedBanners.size() >= MAX_BANNER_SPRITES) return sprite("minecraft:entity/banner/banner_base");
            usedBanners.add(id);
        }
        Sprite sprite = COMPOSED.get(id);
        if (sprite != null) return sprite;
        final Sprite background = sprite("minecraft:entity/banner/banner_base");
        final int width = background.width, height = background.height;
        final int[] pixels = background.firstFrame().clone();
        final List<Object[]> layers = new ArrayList<Object[]>();
        layers.add(new Object[]{"base", base});
        for (int i = 0; i < patterns.size() && i < 16; i++) layers.add(patterns.get(i));
        for (Object[] layer : layers) {
            final Sprite mask = sprite("minecraft:entity/banner/" + layer[0]);
            if (mask.missing || mask.width != width || mask.height != height) continue;
            final int color = DYE_DIFFUSE[(Integer) layer[1]];
            final int[] source = mask.firstFrame();
            for (int i = 0; i < pixels.length; i++) {
                final int s = source[i];
                final int alpha = s >>> 24;
                if (alpha == 0) continue;
                final int d = pixels[i];
                final int sr = ((s >> 16) & 0xFF) * ((color >> 16) & 0xFF) / 255;
                final int sg = ((s >> 8) & 0xFF) * ((color >> 8) & 0xFF) / 255;
                final int sb = (s & 0xFF) * (color & 0xFF) / 255;
                final int r = (sr * alpha + ((d >> 16) & 0xFF) * (255 - alpha)) / 255;
                final int g = (sg * alpha + ((d >> 8) & 0xFF) * (255 - alpha)) / 255;
                final int b = (sb * alpha + (d & 0xFF) * (255 - alpha)) / 255;
                final int a = Math.max(d >>> 24, alpha);
                pixels[i] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        sprite = Sprite.of(id, width, height, pixels);
        COMPOSED.put(id, sprite);
        return sprite;
    }

    // ---------- 頭 ----------

    private void skull(StructureMesher mesher, BlockState state, Map<String, Object> entity, int x, int y, int z) throws IOException {
        final String path = state.path();
        final boolean wall = path.contains("_wall_");
        final String kind = path.replace("_wall_skull", "").replace("_wall_head", "").replace("_skull", "").replace("_head", "");
        final Transform t = Transform.identity();
        if (wall) {
            final int facing = facing(state);
            final float stepX = StructureFixer.DX[facing], stepZ = StructureFixer.DZ[facing];
            t.translate(0.5f - stepX * 0.25f, 0.25f, 0.5f - stepZ * 0.25f).rotateY(-yRot(StructureFixer.OPPOSITE[facing])).scale(-1, -1, 1);
        } else {
            int rotation = 0;
            try {
                rotation = Integer.parseInt(state.get("rotation"));
            } catch (NumberFormatException e) {
                // 値がなければ 0
            }
            t.translate(0.5f, 0, 0.5f).rotateY(-rotation * 22.5f).scale(-1, -1, 1);
        }
        final String model;
        final Sprite texture;
        switch (kind) {
            case "skeleton":
                model = "skull_mob";
                texture = sprite("minecraft:entity/skeleton/skeleton");
                break;
            case "wither_skeleton":
                model = "skull_mob";
                texture = sprite("minecraft:entity/skeleton/wither_skeleton");
                break;
            case "creeper":
                model = "skull_mob";
                texture = sprite("minecraft:entity/creeper/creeper");
                break;
            case "zombie":
                model = "skull_humanoid";
                texture = sprite("minecraft:entity/zombie/zombie");
                break;
            case "piglin":
                model = "skull_piglin";
                texture = sprite("minecraft:entity/piglin/piglin");
                break;
            case "dragon":
                model = "skull_dragon";
                texture = sprite("minecraft:entity/enderdragon/dragon");
                break;
            default:
                model = "skull_humanoid";
                texture = skins.skin(entity);
                break;
        }
        model(mesher, model, texture, t, x, y, z);
    }

    // ---------- 飾り壺 ----------

    private void decoratedPot(StructureMesher mesher, BlockState state, Map<String, Object> entity, int x, int y, int z) throws IOException {
        pot(mesher, entity, Transform.identity().rotateYAround(180 - yRot(facing(state)), 0.5f, 0.5f, 0.5f), x, y, z);
    }

    // ---------- 銅のゴーレムの像 ----------

    private void copperGolem(StructureMesher mesher, BlockState state, int x, int y, int z) throws IOException {
        final String path = state.path().replace("waxed_", "");
        final String oxidation = path.startsWith("exposed_") ? "_exposed" : path.startsWith("weathered_") ? "_weathered"
                : path.startsWith("oxidized_") ? "_oxidized" : "";
        final String pose = state.get("copper_golem_pose") != null ? state.get("copper_golem_pose") : state.get("pose") != null ? state.get("pose") : "standing";
        final Transform t = Transform.identity().translate(0.5f, 0, 0.5f).rotateY(-yRot(StructureFixer.OPPOSITE[facing(state)]));
        model(mesher, "copper_golem_" + pose, sprite("minecraft:entity/copper_golem/copper_golem" + oxidation), t, x, y, z);
    }

    // ---------- エンドポータル ----------

    // 星空は特別な描き方をするので、空の色と星の模様を重ねた絵で近づける
    private void endCube(StructureMesher mesher, boolean portal, int x, int y, int z) {
        Sprite sprite = COMPOSED.get("end_portal");
        if (sprite == null) {
            final Sprite stars = sprite("minecraft:entity/end_portal/end_portal");
            final int[] pixels = new int[stars.width * stars.height];
            final int[] source = stars.firstFrame();
            for (int i = 0; i < pixels.length; i++) {
                final int s = source[i];
                final int alpha = s >>> 24;
                final int r = (((s >> 16) & 0xFF) * alpha + 0x0B * (255 - alpha)) / 255;
                final int g = (((s >> 8) & 0xFF) * alpha + 0x16 * (255 - alpha)) / 255;
                final int b = ((s & 0xFF) * alpha + 0x1E * (255 - alpha)) / 255;
                pixels[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            sprite = Sprite.of("end_portal", stars.width, stars.height, pixels);
            COMPOSED.put("end_portal", sprite);
        }
        final float y0 = portal ? 0.375f : 0, y1 = portal ? 0.75f : 1;
        for (int face = 0; face < 6; face++) {
            if (portal && face > StructureFixer.UP) continue;
            final BlockModels.Quad quad = BlockModels.bakeFace(new float[]{0, y0 * 16, 0}, new float[]{16, y1 * 16, 16}, face,
                    new float[]{0, 0, 16, 16}, 0, sprite, false, -1, -1, null, null, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, false);
            final float[] positions = quad.positions.clone();
            for (int v = 0; v < 4; v++) {
                positions[v * 3] += x;
                positions[v * 3 + 1] += y;
                positions[v * 3 + 2] += z;
            }
            mesher.addQuad(StructureMesher.LAYER_SOLID, positions, quad.uvs, 0xFFFFFFFF, sprite, false);
        }
    }

    // ---------- 動いているピストンのブロック ----------

    private void movingPiston(StructureMesher mesher, Map<String, Object> entity, int x, int y, int z) {
        final Object stateValue = entity.get("blockState");
        if (!(stateValue instanceof Map)) return;
        final Map<String, Object> stateNbt = Nbt.compound(stateValue);
        final Map<String, String> properties = new java.util.TreeMap<String, String>();
        final Map<String, Object> source = stateNbt.containsKey("Properties") ? Nbt.compound(stateNbt, "Properties") : Nbt.compound(stateNbt, "properties");
        for (Map.Entry<String, Object> entry : source.entrySet()) properties.put(entry.getKey(), String.valueOf(entry.getValue()));
        final String name = stateNbt.containsKey("Name") ? Nbt.string(stateNbt, "Name", "minecraft:air") : Nbt.string(stateNbt, "id", "minecraft:air");
        final BlockState moved = BlockState.of(name, properties);
        if (moved.isAir()) return;
        // PistonMovingBlockEntity.getXOff: 伸びているときは progress - 1、縮んでいるときは 1 - progress だけ、向きの方へずらす
        final float progress = (float) Nbt.number(entity.get("progress"), 1.0);
        final boolean extending = Nbt.integer(entity, "extending", 0) != 0;
        final int facing = Nbt.integer(entity, "facing", 0);
        final int[] order = {StructureFixer.DOWN, StructureFixer.UP, StructureFixer.NORTH, StructureFixer.SOUTH, StructureFixer.WEST, StructureFixer.EAST};
        final int direction = order[Math.max(0, Math.min(5, facing))];
        final float amount = extending ? progress - 1 : 1 - progress;
        final BlockModels.StateModel model = models.model(moved);
        final List<BlockModels.Variant> variants = new ArrayList<BlockModels.Variant>();
        model.collect(StructureMesher.seed(x, y, z), new java.util.Random(), variants);
        final float ox = x + StructureFixer.DX[direction] * amount, oy = y + StructureFixer.DY[direction] * amount, oz = z + StructureFixer.DZ[direction] * amount;
        for (BlockModels.Variant variant : variants) {
            for (BlockModels.Quad quad : variant.quads) {
                final float[] positions = quad.positions.clone();
                for (int v = 0; v < 4; v++) {
                    positions[v * 3] += ox;
                    positions[v * 3 + 1] += oy;
                    positions[v * 3 + 2] += oz;
                }
                final int c = StructureMesher.scale(0xFFFFFFFF, StructureMesher.SHADE[quad.shadeDirection]);
                mesher.addQuad(StructureMesher.layer(quad.transparency), positions, quad.uvs, c, quad.sprite, false);
            }
        }
    }

    // ---------- アイテムの特別なモデル (items の minecraft:special) ----------

    void item(StructureMesher mesher, Map<String, Object> model, Map<String, Object> components, Transform t) {
        try {
            final String type = Nbt.string(model, "type", "").replace("minecraft:", "");
            switch (type) {
                case "chest": {
                    final String texture = Nbt.string(model, "texture", "minecraft:normal");
                    emitQuads(mesher, EntityModels.get("chest"), sprite("minecraft:entity/chest/" + texture.substring(texture.indexOf(':') + 1)), t, 0, 0, 0, -1);
                    break;
                }
                case "shulker_box": {
                    final String texture = Nbt.string(model, "texture", "minecraft:shulker");
                    emitQuads(mesher, EntityModels.get("shulker_box"), sprite("minecraft:entity/shulker/" + texture.substring(texture.indexOf(':') + 1)), t, 0, 0, 0, -1);
                    break;
                }
                case "head": {
                    final String kind = Nbt.string(model, "kind", "skeleton");
                    final String[] chosen = headModel(kind);
                    final Object override = model.get("texture");
                    final Sprite texture = override instanceof String
                            ? sprite("minecraft:entity/" + ((String) override).substring(((String) override).indexOf(':') + 1)) : sprite(chosen[1]);
                    emitQuads(mesher, EntityModels.get(chosen[0]), texture, t, 0, 0, 0, -1);
                    break;
                }
                case "player_head": {
                    final Map<String, Object> entity = new HashMap<String, Object>();
                    if (components.containsKey("minecraft:profile")) entity.put("profile", components.get("minecraft:profile"));
                    emitQuads(mesher, EntityModels.get("skull_humanoid"), skins.skin(entity), t, 0, 0, 0, -1);
                    break;
                }
                case "banner": {
                    int base = 0;
                    final String color = Nbt.string(model, "color", "white");
                    for (int i = 0; i < 16; i++) {
                        if (StructureFixer.DYE_COLORS[i].equals(color)) base = i;
                    }
                    final Map<String, Object> entity = new HashMap<String, Object>();
                    entity.put("patterns", components.get("minecraft:banner_patterns"));
                    final Sprite texture = bannerTexture(base, patterns(entity, Structure.Source.JAVA));
                    emitQuads(mesher, EntityModels.get("banner_standing"), texture, t, 0, 0, 0, -1);
                    emitQuads(mesher, EntityModels.get("banner_flag_standing"), texture, t, 0, 0, 0, -1);
                    break;
                }
                case "decorated_pot": {
                    final Map<String, Object> entity = new HashMap<String, Object>();
                    entity.put("sherds", components.get("minecraft:pot_decorations"));
                    pot(mesher, entity, t, 0, 0, 0);
                    break;
                }
                case "conduit":
                    emitQuads(mesher, EntityModels.get("conduit_shell"), sprite("minecraft:entity/conduit/base"), t, 0, 0, 0, -1);
                    break;
                case "copper_golem_statue": {
                    final String texture = Nbt.string(model, "texture", "minecraft:textures/entity/copper_golem/copper_golem.png");
                    final String path = texture.substring(texture.indexOf(':') + 1).replace("textures/", "").replace(".png", "");
                    emitQuads(mesher, EntityModels.get("copper_golem_" + Nbt.string(model, "pose", "standing")), sprite("minecraft:" + path), t, 0, 0, 0, -1);
                    break;
                }
                default:
                    // 盾とトライデントは描かない
                    break;
            }
        } catch (IOException | RuntimeException e) {
            MarumaSign.LOGGER.info("Could not draw a special item model (" + e + ")");
        }
    }

    // 頭の種類ごとの {モデル, テクスチャ}
    private static String[] headModel(String kind) {
        switch (kind) {
            case "wither_skeleton":
                return new String[]{"skull_mob", "minecraft:entity/skeleton/wither_skeleton"};
            case "creeper":
                return new String[]{"skull_mob", "minecraft:entity/creeper/creeper"};
            case "zombie":
                return new String[]{"skull_humanoid", "minecraft:entity/zombie/zombie"};
            case "piglin":
                return new String[]{"skull_piglin", "minecraft:entity/piglin/piglin"};
            case "dragon":
                return new String[]{"skull_dragon", "minecraft:entity/enderdragon/dragon"};
            case "player":
                return new String[]{"skull_humanoid", "minecraft:entity/player/wide/steve"};
            default:
                return new String[]{"skull_mob", "minecraft:entity/skeleton/skeleton"};
        }
    }

    // 飾り壺 (変換は呼ぶ側で決める)
    private void pot(StructureMesher mesher, Map<String, Object> entity, Transform t, float x, float y, float z) throws IOException {
        final Sprite base = sprite("minecraft:entity/decorated_pot/decorated_pot_base");
        emitQuads(mesher, EntityModels.get("pot_neck"), base, t, x, y, z, -1);
        emitQuads(mesher, EntityModels.get("pot_top"), base, t, x, y, z, -1);
        emitQuads(mesher, EntityModels.get("pot_bottom"), base, t, x, y, z, -1);
        // sherds: 後ろ、左、右、前 の順の陶片のアイテム
        final List<Object> sherds = Nbt.list(entity, "sherds");
        final String[] sides = {"back", "left", "right", "front"};
        for (int i = 0; i < 4; i++) {
            String item = i < sherds.size() ? String.valueOf(sherds.get(i)) : "minecraft:brick";
            item = item.substring(item.indexOf(':') + 1);
            Sprite side = sprite("minecraft:entity/decorated_pot/decorated_pot_side");
            if (item.endsWith("_pottery_sherd")) {
                final Sprite pattern = sprite("minecraft:entity/decorated_pot/" + item.replace("_pottery_sherd", "_pottery_pattern"));
                if (!pattern.missing) side = pattern;
            }
            emitQuads(mesher, EntityModels.get("pot_" + sides[i]), side, t, x, y, z, -1);
        }
    }

    // ---------- 文字ディスプレイ ----------

    private int textDisplays;

    // 構造物を描き始めるときに呼ぶ (粗くしてやり直すときも)
    void startPass() {
        textDisplays = 0;
    }

    void textDisplay(StructureMesher mesher, List<SignTexts.Run> runs, int lineWidth, int background, int opacity, String alignment, Transform transform) {
        // 文字ディスプレイは 1 つずつ絵になるので、数に上限を設ける
        if (textDisplays >= MAX_TEXT_SPRITES) return;
        textDisplays++;
        final Object[] block = signs.renderBlock(runs, lineWidth, alignment, background, opacity);
        final Sprite sprite = (Sprite) block[0];
        final int width = (Integer) block[1], height = (Integer) block[2];
        // TextDisplayRenderer: 半回転し、(-0.025) 倍にして、文字の塊の下の中央を原点にする
        final Transform t = transform.copy().rotateY(180).scale(-0.025f, -0.025f, -0.025f)
                .translate(1.0f - (width - 1) / 2.0f, -(height - 1), 0).translate(-1, -1, 0);
        final float[] corners = {0, 0, 0, height, width, height, width, 0};
        final float[] uvs = {0, 0, 0, 1, 1, 1, 1, 0};
        final float[] positions = new float[12];
        for (int v = 0; v < 4; v++) t.apply(corners[v * 2], corners[v * 2 + 1], 0, positions, v * 3);
        // 文字ディスプレイは裏からも見える
        mesher.addQuad(StructureMesher.layer(sprite.transparency(0, 0, 1, 1)), positions, uvs, 0xFFFFFFFF, sprite, false);
        final float[] back = new float[12];
        final float[] backUvs = new float[8];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(positions, (3 - v) * 3, back, v * 3, 3);
            backUvs[v * 2] = uvs[(3 - v) * 2];
            backUvs[v * 2 + 1] = uvs[(3 - v) * 2 + 1];
        }
        mesher.addQuad(StructureMesher.layer(sprite.transparency(0, 0, 1, 1)), back, backUvs, 0xFFFFFFFF, sprite, false);
    }

    // ---------- 防具立て ----------

    void armorStand(StructureMesher mesher, Map<String, Object> nbt, Transform base, Items items) throws IOException {
        if (Nbt.integer(nbt, "Marker", 0) != 0 && Nbt.integer(nbt, "Invisible", 0) != 0 && nbt.isEmpty()) return;
        final double[] rotation = Nbt.doubles(nbt.get("Rotation"));
        final float yaw = rotation != null && rotation.length > 0 ? (float) rotation[0] : 0;
        final boolean small = Nbt.integer(nbt, "Small", 0) != 0;
        final boolean invisible = Nbt.integer(nbt, "Invisible", 0) != 0;
        final boolean showArms = Nbt.integer(nbt, "ShowArms", 0) != 0;
        final boolean basePlate = Nbt.integer(nbt, "NoBasePlate", 0) == 0;
        final Map<String, Object> pose = Nbt.compound(nbt, "Pose");
        final float[] head = angles(pose, "Head", 0, 0, 0), body = angles(pose, "Body", 0, 0, 0);
        final float[] leftArm = angles(pose, "LeftArm", -10, 0, -10), rightArm = angles(pose, "RightArm", -15, 0, 10);
        final float[] leftLeg = angles(pose, "LeftLeg", -1, 0, -1), rightLeg = angles(pose, "RightLeg", 1, 0, 1);

        // LivingEntityRenderer: 向きに回し、上下と左右を返して、足元を原点にする
        final Transform root = base.copy();
        if (small) root.scale(0.5f, 0.5f, 0.5f);
        root.rotateY(180 - yaw).scale(-1, -1, 1).translate(0, -1.501f, 0);

        if (!invisible) {
            final Sprite texture = sprite("minecraft:entity/armorstand/armorstand");
            part(mesher, "armor_stand.head", head, root, texture, -1);
            part(mesher, "armor_stand.body", body, root, texture, -1);
            if (showArms) {
                part(mesher, "armor_stand.right_arm", rightArm, root, texture, -1);
                part(mesher, "armor_stand.left_arm", leftArm, root, texture, -1);
            }
            part(mesher, "armor_stand.right_leg", rightLeg, root, texture, -1);
            part(mesher, "armor_stand.left_leg", leftLeg, root, texture, -1);
            part(mesher, "armor_stand.right_body_stick", body, root, texture, -1);
            part(mesher, "armor_stand.left_body_stick", body, root, texture, -1);
            part(mesher, "armor_stand.shoulder_stick", body, root, texture, -1);
            if (basePlate) part(mesher, "armor_stand.base_plate", new float[]{0, -yaw, 0}, root, texture, -1);
        }

        // 装備 (1.21.5 から equipment、それより前は ArmorItems [足, 脚, 胴, 頭] と HandItems [利き手, 反対の手])
        final Map<String, Object> equipment = new HashMap<String, Object>(Nbt.compound(nbt, "equipment"));
        final List<Object> armor = Nbt.list(nbt, "ArmorItems");
        final String[] armorSlots = {"feet", "legs", "chest", "head"};
        for (int i = 0; i < armor.size() && i < 4; i++) {
            if (!equipment.containsKey(armorSlots[i])) equipment.put(armorSlots[i], armor.get(i));
        }
        final List<Object> hands = Nbt.list(nbt, "HandItems");
        if (hands.size() > 0 && !equipment.containsKey("mainhand")) equipment.put("mainhand", hands.get(0));
        if (hands.size() > 1 && !equipment.containsKey("offhand")) equipment.put("offhand", hands.get(1));

        final float[][] poses = {head, body, rightArm, leftArm, rightLeg, leftLeg};
        for (String slot : new String[]{"head", "chest", "legs", "feet"}) {
            final Map<String, Object> item = Nbt.compound(equipment.get(slot));
            final String id = Items.itemId(item);
            if (id == null) continue;
            final String material = armorMaterial(id, slot, Items.components(item));
            if (material != null) {
                armorLayers(mesher, slot, material, Items.components(item), poses, root);
            } else if (slot.equals("head")) {
                // 頭に載せたアイテム (CustomHeadLayer)
                final Transform t = partTransform(root, "armor_stand.head", head).translate(0, -0.25f, 0).rotateY(180).scale(0.625f, -0.625f, -0.625f);
                items.emit(mesher, item, "head", t);
            }
        }
        if (showArms) {
            for (int side = 0; side < 2; side++) {
                final Map<String, Object> item = Nbt.compound(equipment.get(side == 0 ? "mainhand" : "offhand"));
                if (Items.itemId(item) == null) continue;
                // ItemInHandLayer: 腕の先に、腕に沿わせて持たせる
                final boolean left = side == 1;
                final Transform t = partTransform(root, left ? "armor_stand.left_arm" : "armor_stand.right_arm", left ? leftArm : rightArm)
                        .rotateX(-90).rotateY(180).translate((left ? -1 : 1) / 16.0f, 0.125f, -0.625f);
                items.emit(mesher, item, left ? "thirdperson_lefthand" : "thirdperson_righthand", t);
            }
        }
    }

    private static float[] angles(Map<String, Object> pose, String name, float x, float y, float z) {
        final double[] values = Nbt.doubles(pose.get(name));
        if (values == null || values.length < 3) return new float[]{x, y, z};
        return new float[]{(float) values[0], (float) values[1], (float) values[2]};
    }

    // 部分の変換 (ModelPart.translateAndRotate: 位置に動かし、Z, Y, X の順の回転)
    private static Transform partTransform(Transform root, String part, float[] angles) throws IOException {
        final float[] pivot = EntityModels.pivot(part);
        return root.copy().translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f).rotateZ(angles[2]).rotateY(angles[1]).rotateX(angles[0]);
    }

    private void part(StructureMesher mesher, String part, float[] angles, Transform root, Sprite texture, int color) throws IOException {
        emitQuads(mesher, EntityModels.get(part), texture, partTransform(root, part, angles), 0, 0, 0, color);
    }

    // 防具の素材 (equipment の名前)。防具でなければ null
    private static String armorMaterial(String id, String slot, Map<String, Object> components) {
        final Map<String, Object> equippable = Nbt.compound(components, "minecraft:equippable");
        final String asset = Nbt.string(equippable, "asset_id", null);
        if (asset != null) return asset.substring(asset.indexOf(':') + 1);
        final String path = id.substring(id.indexOf(':') + 1);
        if (path.equals("turtle_helmet")) return "turtle_scute";
        final String[] pieces = {"_helmet", "_chestplate", "_leggings", "_boots"};
        final String[] slots = {"head", "chest", "legs", "feet"};
        for (int i = 0; i < 4; i++) {
            if (path.endsWith(pieces[i]) && slots[i].equals(slot)) {
                final String material = path.substring(0, path.length() - pieces[i].length());
                return material.equals("golden") ? "gold" : material;
            }
        }
        return null;
    }

    // HumanoidArmorLayer: 防具の層を、部分ごとに防具立ての姿勢で描く
    private void armorLayers(StructureMesher mesher, String slot, String material, Map<String, Object> components, float[][] poses, Transform root) throws IOException {
        final byte[] bytes = assets.read("assets/minecraft/equipment/" + material + ".json");
        if (bytes == null) return;
        final Map<String, Object> json = marumasa.marumasa_sign.core.util.Json.object(
                marumasa.marumasa_sign.core.util.Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        final String layerType = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        final List<Object> layers = marumasa.marumasa_sign.core.util.Json.array(marumasa.marumasa_sign.core.util.Json.object(json, "layers"), layerType);
        final String[] parts = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
        for (Object layerItem : layers) {
            final Map<String, Object> layer = marumasa.marumasa_sign.core.util.Json.object(layerItem);
            final String texture = marumasa.marumasa_sign.core.util.Json.string(layer, "texture", "");
            int color = -1;
            if (layer.get("dyeable") instanceof Map) {
                final Object dyed = components.get("minecraft:dyed_color");
                if (dyed instanceof Number) color = 0xFF000000 | ((Number) dyed).intValue();
                else {
                    final Object undyed = marumasa.marumasa_sign.core.util.Json.object(layer, "dyeable").get("color_when_undyed");
                    color = undyed instanceof Double ? 0xFF000000 | (int) Math.round((Double) undyed) : -1;
                }
            }
            final Sprite sprite = sprite("minecraft:entity/equipment/" + layerType + "/" + texture.substring(texture.indexOf(':') + 1));
            for (int p = 0; p < parts.length; p++) {
                final String name = "armor_stand_armor_" + slot + "." + parts[p];
                emitQuads(mesher, EntityModels.get(name), sprite, partTransform(root, name, poses[p]), 0, 0, 0, color);
            }
        }
    }

    // ---------- エンティティ ----------

    // 描くエンティティの数の上限
    static final int MAX_ENTITIES = 4096;

    void emitEntities(StructureMesher mesher, Structure structure) {
        if (entities == null) entities = new Entities(this, models);
        int count = 0;
        for (Structure.Entity entity : structure.entities()) {
            if (++count > MAX_ENTITIES) break;
            try {
                entities.emit(mesher, entity, structure);
            } catch (IOException | RuntimeException e) {
                MarumaSign.LOGGER.info("Could not draw an entity " + entity.id + " (" + e + ")");
            }
        }
    }

    private Entities entities;
}
