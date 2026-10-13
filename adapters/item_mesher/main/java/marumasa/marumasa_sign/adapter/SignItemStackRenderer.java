package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.item.ItemStack;

/**
 * 看板アイテムの描画を行う TileEntityItemStackRenderer のラッパー。
 */
final class SignItemStackRenderer extends TileEntityItemStackRenderer {

    private final TileEntityItemStackRenderer parent;

    SignItemStackRenderer(TileEntityItemStackRenderer parent) {
        this.parent = parent;
    }

    @Override
    public void renderByItem(ItemStack stack) {
        if (stack != null && SignItems.isSign(stack.getItem())) {
            final SignImage sign = SignItems.find(stack);
            if (sign != null) {
                SignItems.drawItem(sign);
                return;
            }
        }
        if (parent != null) {
            parent.renderByItem(stack);
        } else {
            super.renderByItem(stack);
        }
    }
}
