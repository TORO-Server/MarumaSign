package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

/**
 * Forge 1.7.10 向けの看板アイテムレンダラー。
 */
final class ForgeItemSignRenderer implements IItemRenderer {

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        // MarumaSign の看板アイテムでなければ false を返し、Forge の標準描画に任せる
        return ItemSignFinder.find(item) != null;
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        // 地面に落ちたアイテムの回転と浮遊は有効にする
        if (helper == ItemRendererHelper.ENTITY_ROTATION) return true;
        if (helper == ItemRendererHelper.ENTITY_BOBBING) return true;
        // 看板と同じ平らなアイテムとして描くため、ブロック用の変換ヘルパーは使わない
        return false;
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        final SignImage image = ItemSignFinder.find(item);
        if (image == null) return;

        switch (type) {
            case INVENTORY:
                SignItemDraw.drawInGui(image, 0.0f, 0.0f, 0.0f);
                break;
            case EQUIPPED:
            case EQUIPPED_FIRST_PERSON:
                SignItemDraw.drawEquipped(image);
                break;
            case ENTITY:
                SignItemDraw.drawEntity(image);
                break;
            default:
                break;
        }
    }
}
