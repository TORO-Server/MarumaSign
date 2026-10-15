package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.renderer.ItemMeshDefinition;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;


/**
 * 看板アイテムのモデル取得を差し替える Mesher。
 * MarumaSign のデータを持つ看板アイテムに対して、TileEntityItemStackRenderer 経由で描画させるモデルを返す。
 */
final class SignItemModelMesher extends ItemModelMesher {

    private final ItemModelMesher original;
    private IBakedModel signModel;
    private IBakedModel cachedBaseModel;

    SignItemModelMesher(ItemModelMesher original) {
        super(original.getModelManager());
        this.original = original;
    }

    @Override
    public IBakedModel getItemModel(ItemStack stack) {
        if (stack != null && SignItems.isSign(stack.getItem())) {
            final SignImage sign = SignItems.find(stack);
            if (sign != null) {
                return getSignModel();
            }
        }
        return original.getItemModel(stack);
    }

    // 専用の描画に回すためのモデルを返す。手に持ったときなどの位置と向きは、通常の看板のモデルと同じにする
    private IBakedModel getSignModel() {
        IBakedModel base = original.getItemModel(new ItemStack(Items.sign));
        if (base == null) {
            base = getModelManager().getMissingModel();
        }
        if (signModel == null || cachedBaseModel != base) {
            cachedBaseModel = base;
            // 専用の描画に回すモデルは、枠の中でブロックのように斜めから見た向きになる。
            // 看板のアイテムは平らな絵なので、正面から見た向きのままにする
            signModel = new BuiltInModel(base.getItemCameraTransforms()) {
                @Override
                public boolean isGui3d() {
                    return false;
                }
            };
        }
        return signModel;
    }

    @Override
    public TextureAtlasSprite getParticleIcon(Item item) {
        return original.getParticleIcon(item);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(Item item, int meta) {
        return original.getParticleIcon(item, meta);
    }

    @Override
    public void register(Item item, int meta, ModelResourceLocation location) {
        original.register(item, meta, location);
    }

    @Override
    public void register(Item item, ItemMeshDefinition definition) {
        original.register(item, definition);
    }

    @Override
    public ModelManager getModelManager() {
        return original.getModelManager();
    }

    @Override
    public void rebuildCache() {
        original.rebuildCache();
        signModel = null;
        cachedBaseModel = null;
    }
}
