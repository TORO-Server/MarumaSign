package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.item.ItemStack;

/**
 * 看板アイテムの描画を横取りする RenderItem。
 * バニラの描画変換 (GUI、手持ち、ドロップ、額縁) が適用されたあと、看板の文字に応じた画像を描く。
 */
final class SignRenderItem extends RenderItem {

    SignRenderItem(Minecraft mc, RenderItem original) {
        super(mc.getTextureManager(),
                original.getItemModelMesher().getModelManager(),
                Reflect.get(Reflect.fieldOfType(RenderItem.class, ItemColors.class), original));
        // original が持っている ItemModelMesher (Forge の ItemModelMesherForge など) をそのまま引き継ぐ
        Reflect.set(Reflect.fieldOfType(RenderItem.class, ItemModelMesher.class), this, original.getItemModelMesher());
    }

    @Override
    public void renderItem(ItemStack stack, IBakedModel model) {
        final SignImage image = SignItems.find(stack);
        if (image != null) {
            GlStateManager.pushMatrix();
            // 一辺 1 の立方体 (0 - 1) の中央を中心にする
            GlStateManager.translate(-0.5f, -0.5f, -0.5f);
            SignDraw.drawBatches(image.itemBatches(1.0));
            GlStateManager.popMatrix();
            MarumaSign.drawn(image);
            return;
        }
        super.renderItem(stack, model);
    }
}
