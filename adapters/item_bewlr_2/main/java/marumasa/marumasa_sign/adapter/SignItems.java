package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StandingSignBlock;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 看板の文字を持っている看板アイテムを、その看板と同じ内容 (画像・動画・3D モデル) で描く。(Minecraft 1.19.3 - 1.21.1)
 * アイテムのモデルを選ぶ部分を包んで、MarumaSign の看板のデータを持つアイテムには「専用の描画を行うモデル」を返し、
 * 専用の描画を行う部分を包んで、そのアイテムのときに画像を描く。
 */
final class SignItems {

    private static Field shaperField;
    private static Field rendererField;

    private SignItems() {
    }

    // まだ包まれていなければ包む。毎 tick 確認する
    static void install(Minecraft mc) {
        final ItemRenderer itemRenderer = mc.getItemRenderer();
        if (itemRenderer == null) return;

        if (rendererField == null) {
            rendererField = Reflect.fieldOfType(ItemRenderer.class, BlockEntityWithoutLevelRenderer.class);
            shaperField = Reflect.fieldOfType(ItemRenderer.class, ItemModelShaper.class);
        }
        final BlockEntityWithoutLevelRenderer renderer = Reflect.get(rendererField, itemRenderer);
        if (renderer != null && !(renderer instanceof Renderer)) {
            Reflect.set(rendererField, itemRenderer, new Renderer(renderer, mc));
        }
        final ItemModelShaper shaper = Reflect.get(shaperField, itemRenderer);
        if (shaper != null && !(shaper instanceof Shaper)) {
            Reflect.set(shaperField, itemRenderer, new Shaper(shaper, mc));
        }
    }

    // アイテムが持っている看板のデータから、表示する内容を探す。MarumaSign の看板でなければ null
    static SignImage find(ItemStack stack) {
        // 立て看板になるアイテムだけを対象にする (吊り看板は対象外)
        final Item item = stack.getItem();
        if (!(item instanceof BlockItem) || !(((BlockItem) item).getBlock() instanceof StandingSignBlock)) return null;
        final CompoundTag tag = SignItemData.blockEntityTag(stack);
        if (tag == null) return null;

        final String[] lines = new String[8];
        if (tag.contains("front_text")) {
            // 1.20 以降: 表と裏
            readLines(tag.getCompound("front_text"), lines, 0);
            readLines(tag.getCompound("back_text"), lines, 4);
        } else {
            for (int i = 0; i < 4; i++) {
                lines[i] = tag.getString("Text" + (i + 1));
            }
        }
        return MarumaSign.itemSign(lines);
    }

    private static void readLines(CompoundTag text, String[] lines, int offset) {
        // 8 は文字列のタグの種類
        final ListTag messages = text.getList("messages", 8);
        for (int i = 0; i < 4 && i < messages.size(); i++) {
            lines[offset + i] = messages.getString(i);
        }
    }

    /**
     * アイテムのモデルを選ぶ部分。MarumaSign の看板のデータを持つアイテムには、専用の描画を行うモデルを返す。
     */
    private static final class Shaper extends ItemModelShaper {

        private final ItemModelShaper delegate;
        // 元のモデルごとの、専用の描画を行うモデル。位置と向きは元のモデルと同じ
        private final Map<BakedModel, BakedModel> builtIn = new IdentityHashMap<BakedModel, BakedModel>();

        Shaper(ItemModelShaper delegate, Minecraft mc) {
            super(mc.getModelManager());
            this.delegate = delegate;
        }

        @Override
        public BakedModel getItemModel(ItemStack stack) {
            final BakedModel model = delegate.getItemModel(stack);
            if (model == null || find(stack) == null) return model;
            BakedModel replaced = builtIn.get(model);
            if (replaced == null) {
                replaced = new BuiltInModel(model.getTransforms(), ItemOverrides.EMPTY, model.getParticleIcon(), model.usesBlockLight());
                builtIn.put(model, replaced);
            }
            return replaced;
        }

        @Override
        public BakedModel getItemModel(Item item) {
            return delegate.getItemModel(item);
        }

        @Override
        public ModelManager getModelManager() {
            return delegate.getModelManager();
        }

        @Override
        public void register(Item item, ModelResourceLocation location) {
            delegate.register(item, location);
        }

        @Override
        public void rebuildCache() {
            delegate.rebuildCache();
            builtIn.clear();
        }
    }

    /**
     * 専用の描画を行う部分。MarumaSign の看板アイテムのときだけ画像を描き、それ以外は元の処理に任せる。
     */
    private static final class Renderer extends BlockEntityWithoutLevelRenderer {

        private final BlockEntityWithoutLevelRenderer delegate;

        Renderer(BlockEntityWithoutLevelRenderer delegate, Minecraft mc) {
            super(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
            this.delegate = delegate;
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                                 int light, int overlay) {
            final SignImage image = find(stack);
            if (image == null) {
                delegate.renderByItem(stack, context, poseStack, buffers, light, overlay);
                return;
            }
            // 距離は分からないので、手元にあるものとして扱う
            for (SignImage.Batch batch : image.itemBatches(1.0)) {
                Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
            }
            MarumaSign.drawn(image);
        }

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
            delegate.onResourceManagerReload(resourceManager);
        }
    }
}
