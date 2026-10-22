import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.banner.BannerModel;
import net.minecraft.client.model.object.bell.BellModel;
import net.minecraft.client.model.object.book.BookModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.model.object.skull.DragonHeadModel;
import net.minecraft.client.model.object.skull.PiglinHeadModel;
import net.minecraft.client.model.object.skull.SkullModel;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.model.object.statue.CopperGolemStatueModel;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * ゲームのコードで形が決まっているブロック (チェスト、旗、頭など) の形を書き出す (開発用。配布する jar には入らない)。
 * Minecraft のモデルの定義を、ゲームと同じ姿勢にして、四角形の列にする。
 * 使い方は tools/blockdata/README.md を参照。
 *
 * 出力 (gzip で圧縮したテキスト):
 *   E 名前                          モデルの始まり
 *   Q x y z u v (4 頂点分)           四角形。位置はブロック単位、テクスチャ座標は 0 - 1
 */
public final class EntityModelDump {

    private static Map<ModelLayerLocation, LayerDefinition> layers;

    private static ModelPart bake(ModelLayerLocation layer) {
        return layers.get(layer).bakeRoot();
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        layers = LayerDefinitions.createRoots();

        final List<String> lines = new ArrayList<>();

        final ChestModel chest = new ChestModel(bake(ModelLayers.CHEST));
        chest.setupAnim(0.0F);
        dump(lines, "chest", chest.root());
        final ChestModel left = new ChestModel(bake(ModelLayers.DOUBLE_CHEST_LEFT));
        left.setupAnim(0.0F);
        dump(lines, "chest_left", left.root());
        final ChestModel right = new ChestModel(bake(ModelLayers.DOUBLE_CHEST_RIGHT));
        right.setupAnim(0.0F);
        dump(lines, "chest_right", right.root());

        // ShulkerBoxRenderer.ShulkerBoxModel.setupAnim(0)
        final ModelPart shulker = bake(ModelLayers.SHULKER_BOX);
        shulker.resetPose();
        shulker.getChild("lid").setPos(0.0F, 24.0F, 0.0F);
        shulker.getChild("lid").yRot = 0.0F;
        dump(lines, "shulker_box", shulker);

        final BannerModel standing = new BannerModel(bake(ModelLayers.STANDING_BANNER));
        standing.setupAnim(Unit.INSTANCE);
        dump(lines, "banner_standing", standing.root());
        final BannerModel wall = new BannerModel(bake(ModelLayers.WALL_BANNER));
        wall.setupAnim(Unit.INSTANCE);
        dump(lines, "banner_wall", wall.root());
        final BannerFlagModel standingFlag = new BannerFlagModel(bake(ModelLayers.STANDING_BANNER_FLAG));
        standingFlag.setupAnim(0.0F);
        dump(lines, "banner_flag_standing", standingFlag.root());
        final BannerFlagModel wallFlag = new BannerFlagModel(bake(ModelLayers.WALL_BANNER_FLAG));
        wallFlag.setupAnim(0.0F);
        dump(lines, "banner_flag_wall", wallFlag.root());

        final BellModel bell = new BellModel(bake(ModelLayers.BELL));
        bell.setupAnim(new BellModel.State(0.0F, null));
        dump(lines, "bell", bell.root());

        final BookModel book = new BookModel(bake(ModelLayers.BOOK));
        book.setupAnim(new BookModel.State(0.0F, 0.0F, 0.0F));
        dump(lines, "book_closed", book.root());
        book.setupAnim(BookModel.State.forAnimation(0.0F, 0.1F, 0.9F, 1.2F));
        dump(lines, "book_lectern", book.root());

        final ModelPart shell = bake(ModelLayers.CONDUIT_SHELL);
        dump(lines, "conduit_shell", shell);

        final ModelPart potBase = bake(ModelLayers.DECORATED_POT_BASE);
        for (String part : new String[]{"neck", "top", "bottom"}) dumpChild(lines, "pot_" + part, potBase, part);
        final ModelPart potSides = bake(ModelLayers.DECORATED_POT_SIDES);
        for (String part : new String[]{"front", "back", "left", "right"}) dumpChild(lines, "pot_" + part, potSides, part);

        final SkullModelBase.State skullState = new SkullModelBase.State();
        skullState.animationPos = 0.0F;
        final SkullModel mobSkull = new SkullModel(bake(ModelLayers.SKELETON_SKULL));
        mobSkull.setupAnim(skullState);
        dump(lines, "skull_mob", mobSkull.root());
        final SkullModel humanoidSkull = new SkullModel(bake(ModelLayers.PLAYER_HEAD));
        humanoidSkull.setupAnim(skullState);
        dump(lines, "skull_humanoid", humanoidSkull.root());
        final PiglinHeadModel piglin = new PiglinHeadModel(bake(ModelLayers.PIGLIN_HEAD));
        piglin.setupAnim(skullState);
        dump(lines, "skull_piglin", piglin.root());
        final DragonHeadModel dragon = new DragonHeadModel(bake(ModelLayers.DRAGON_SKULL));
        dragon.setupAnim(skullState);
        dump(lines, "skull_dragon", dragon.root());

        final ModelLayerLocation[] golemLayers = {ModelLayers.COPPER_GOLEM, ModelLayers.COPPER_GOLEM_RUNNING, ModelLayers.COPPER_GOLEM_SITTING, ModelLayers.COPPER_GOLEM_STAR};
        final String[] golemNames = {"standing", "running", "sitting", "star"};
        for (int i = 0; i < golemLayers.length; i++) {
            final CopperGolemStatueModel golem = new CopperGolemStatueModel(bake(golemLayers[i]));
            golem.setupAnim(Unit.INSTANCE);
            dump(lines, "copper_golem_" + golemNames[i], golem.root());
        }

        // 防具立てと、防具立てに着せる防具は、姿勢を変えられる部分ごとに、部分の中の座標で書き出す
        final String[] standParts = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "right_body_stick", "left_body_stick", "shoulder_stick", "base_plate"};
        final ModelPart stand = bake(ModelLayers.ARMOR_STAND);
        // ArmorStandModel は頭の上の層 (hat) を描かない
        stand.getChild("head").getChild("hat").visible = false;
        dumpParts(lines, "armor_stand", stand, standParts);
        final String[] armorParts = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
        dumpParts(lines, "armor_stand_armor_head", bake(ModelLayers.ARMOR_STAND_ARMOR.head()), armorParts);
        dumpParts(lines, "armor_stand_armor_chest", bake(ModelLayers.ARMOR_STAND_ARMOR.chest()), armorParts);
        dumpParts(lines, "armor_stand_armor_legs", bake(ModelLayers.ARMOR_STAND_ARMOR.legs()), armorParts);
        dumpParts(lines, "armor_stand_armor_feet", bake(ModelLayers.ARMOR_STAND_ARMOR.feet()), armorParts);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(Paths.get(args[0]))), StandardCharsets.UTF_8))) {
            out.print("# MarumaSign entity model data (" + SharedConstants.getCurrentVersion().name() + ")\n");
            for (String line : lines) out.print(line + "\n");
        }
        System.out.println("entity models written: " + lines.size() + " lines");
    }

    // 部分ごとに "E 名前.部分" と、その部分の位置 "P x y z" (ピクセル単位) を書き、四角形は部分の中の座標で書く
    private static void dumpParts(List<String> lines, String name, ModelPart root, String[] parts) {
        for (String part : parts) {
            final ModelPart child = root.getChild(part);
            final float x = child.x, y = child.y, z = child.z, xRot = child.xRot, yRot = child.yRot, zRot = child.zRot;
            lines.add("E " + name + "." + part);
            lines.add(String.format(Locale.ROOT, "P %.6f %.6f %.6f", x, y, z));
            child.x = 0;
            child.y = 0;
            child.z = 0;
            child.xRot = 0;
            child.yRot = 0;
            child.zRot = 0;
            child.visible = true;
            child.render(new PoseStack(), recorder(lines), 0xF000F0, 0);
            child.x = x;
            child.y = y;
            child.z = z;
            child.xRot = xRot;
            child.yRot = yRot;
            child.zRot = zRot;
        }
    }

    private static void dumpChild(List<String> lines, String name, ModelPart root, String child) {
        final PoseStack pose = new PoseStack();
        root.translateAndRotate(pose);
        lines.add("E " + name);
        root.getChild(child).render(pose, recorder(lines), 0xF000F0, 0);
    }

    private static void dump(List<String> lines, String name, ModelPart root) {
        lines.add("E " + name);
        root.render(new PoseStack(), recorder(lines), 0xF000F0, 0);
    }

    // 頂点を 4 つずつまとめて記録する VertexConsumer
    private static VertexConsumer recorder(List<String> lines) {
        final StringBuilder quad = new StringBuilder();
        final int[] count = {0};
        final InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("addVertex") && arguments != null && arguments.length == 11) {
                quad.append(String.format(Locale.ROOT, " %.6f %.6f %.6f %.6f %.6f",
                        (Float) arguments[0], (Float) arguments[1], (Float) arguments[2], (Float) arguments[4], (Float) arguments[5]));
                if (++count[0] == 4) {
                    lines.add("Q" + quad);
                    quad.setLength(0);
                    count[0] = 0;
                }
                return null;
            }
            if (method.getReturnType() == VertexConsumer.class) return proxy;
            return null;
        };
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, handler);
    }
}
