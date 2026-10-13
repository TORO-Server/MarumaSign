package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/**
 * 手に持ったアイテムを描くレンダラー。MarumaSign の看板アイテムを差し替える。
 */
final class VanillaItemRenderer extends ItemRenderer {

    VanillaItemRenderer(Minecraft mc) {
        super(mc);
    }

    @Override
    public void renderItem(EntityLivingBase entity, ItemStack stack, int pass) {
        final SignImage image = ItemSignFinder.find(stack);
        if (image != null) {
            GL11.glPushMatrix();
            // 看板アイテムと同じ手持ち変換 (スケール 1.5、回転、位置調整) を適用する
            GL11.glScalef(1.5F, 1.5F, 1.5F);
            GL11.glRotatef(50.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(335.0F, 0.0F, 0.0F, 1.0F);
            GL11.glTranslatef(-0.9375F, -0.0625F, 0.0F);
            SignItemDraw.drawEquipped(image);
            GL11.glPopMatrix();
            return;
        }
        super.renderItem(entity, stack, pass);
    }
}
