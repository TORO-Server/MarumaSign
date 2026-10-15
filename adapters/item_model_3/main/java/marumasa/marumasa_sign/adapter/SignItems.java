package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.StandingSignBlock;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 看板の文字を持っている看板アイテムを、その看板と同じ内容 (画像・動画・3D モデル) で描く。(Minecraft 1.21.6 - 1.21.8)
 * アイテムのモデルの表にある看板アイテムのモデルを包み、MarumaSign の看板のデータを持つときだけ描き方を差し替える。
 */
final class SignItems {

    private static Field modelsField;
    private static Field layersField;
    private static Field layerCountField;

    private SignItems() {
    }

    // 看板アイテムのモデルがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    static void install(Minecraft mc) {
        // アイテムの情報は、ワールドに入るまで読めないバージョンがある
        if (mc.level == null) return;
        final ModelManager manager = mc.getModelManager();
        if (manager == null) return;

        // 看板アイテムと、そのモデルの名前
        final List<Object> ids = new ArrayList<Object>();
        for (Item item : BuiltInRegistries.ITEM) {
            // 立て看板になるアイテムだけを対象にする (吊り看板は対象外)
            if (!(item instanceof BlockItem) || !(((BlockItem) item).getBlock() instanceof StandingSignBlock)) continue;
            final Object id = new ItemStack(item).get(DataComponents.ITEM_MODEL);
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return;

        if (modelsField == null) {
            // アイテムのモデルの表は、同じ型のフィールドがほかにもあるので、中身で見分ける
            for (Field field : ModelManager.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                final Map<Object, Object> map = Reflect.get(field, manager);
                if (map != null && map.get(ids.get(0)) instanceof ItemModel) {
                    modelsField = field;
                    break;
                }
            }
            // まだモデルが読み込まれていない
            if (modelsField == null) return;
        }

        final Map<Object, ItemModel> models = Reflect.get(modelsField, manager);
        if (models == null) return;
        boolean wrapped = true;
        for (Object id : ids) {
            final ItemModel model = models.get(id);
            if (model != null && !(model instanceof Model)) wrapped = false;
        }
        if (wrapped) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<Object, ItemModel> replaced = new HashMap<Object, ItemModel>(models);
        for (Object id : ids) {
            final ItemModel model = replaced.get(id);
            if (model != null && !(model instanceof Model)) replaced.put(id, new Model(model));
        }
        Reflect.set(modelsField, manager, replaced);
    }

    // アイテムが持っている看板のデータから、表示する内容を探す。MarumaSign の看板でなければ null
    static SignImage find(ItemStack stack) {
        final CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return null;
        final CompoundTag tag = data.getUnsafe();
        final String[] lines = new String[8];
        readLines(tag.getCompoundOrEmpty("front_text"), lines, 0);
        readLines(tag.getCompoundOrEmpty("back_text"), lines, 4);
        return MarumaSign.itemSign(lines);
    }

    private static void readLines(CompoundTag text, String[] lines, int offset) {
        final ListTag messages = text.getListOrEmpty("messages");
        for (int i = 0; i < 4 && i < messages.size(); i++) {
            final Tag message = messages.get(i);
            // 文字だけの行は文字列で、装飾のある行は複合タグで保存されている
            lines[offset + i] = message instanceof StringTag ? ((StringTag) message).value() : String.valueOf(message);
        }
    }

    /**
     * 看板アイテムのモデルを包み、MarumaSign の看板のデータを持つときだけ、中身を画像に差し替える。
     */
    private static final class Model implements ItemModel {

        private final ItemModel delegate;

        Model(ItemModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
                           ClientLevel level, LivingEntity entity, int seed) {
            final SignImage image = find(stack);
            if (image == null) {
                delegate.update(state, stack, resolver, context, level, entity, seed);
                return;
            }

            // 元のモデルに層を作らせて、手に持ったときや枠の中での位置と向きをそのまま使う
            if (layersField == null) {
                layersField = Reflect.fieldOfType(ItemStackRenderState.class, ItemStackRenderState.LayerRenderState[].class);
                layerCountField = Reflect.fieldOfType(ItemStackRenderState.class, int.class);
            }
            final int before = Reflect.<Integer>get(layerCountField, state);
            delegate.update(state, stack, resolver, context, level, entity, seed);
            final int after = Reflect.<Integer>get(layerCountField, state);
            final ItemStackRenderState.LayerRenderState[] layers = Reflect.get(layersField, state);

            if (after > before) {
                // 元の絵 (板の形) は描かず、最初の層で画像を描く。残りの層には何も描かせない
                layers[before].setupSpecialModel(Renderer.INSTANCE, image);
                for (int i = before + 1; i < after; i++) layers[i].setupSpecialModel(Renderer.INSTANCE, null);
            } else {
                state.newLayer().setupSpecialModel(Renderer.INSTANCE, image);
            }
            // 動画などで内容が変わるので、枠の中の絵を使い回さず毎回描き直してもらう
            state.appendModelIdentityElement(image);
            state.setAnimated();
        }
    }

    /**
     * 画像を描く部分。
     */
    private static final class Renderer implements SpecialModelRenderer<SignImage> {

        static final Renderer INSTANCE = new Renderer();

        @Override
        public void render(SignImage image, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                           int light, int overlay, boolean foil) {
            if (image == null) return;
            // 距離は分からないので、手元にあるものとして扱う
            for (SignImage.Batch batch : image.itemBatches(1.0)) {
                Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
            }
            MarumaSign.drawn(image);
        }

        // アイテムが占める範囲。一辺 1 の立方体の中に収まる
        @Override
        public void getExtents(Set<Vector3f> output) {
            output.add(new Vector3f(0f, 0f, 0f));
            output.add(new Vector3f(1f, 1f, 1f));
        }

        @Override
        public SignImage extractArgument(ItemStack stack) {
            return find(stack);
        }
    }
}
