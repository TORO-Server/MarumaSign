package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * インベントリ枠や地面・額縁のアイテムを描くレンダラー。MarumaSign の看板アイテムを差し替える。
 */
final class VanillaRenderItem extends RenderItem {

    @Override
    public void renderItemIntoGUI(FontRenderer fr, TextureManager tm, ItemStack stack, int x, int y) {
        final SignImage image = ItemSignFinder.find(stack);
        if (image != null) {
            SignItemDraw.drawInGui(image, x, y, this.zLevel);
            return;
        }
        super.renderItemIntoGUI(fr, tm, stack, x, y);
    }

    @Override
    public void renderItemAndEffectIntoGUI(FontRenderer fr, TextureManager tm, ItemStack stack, int x, int y) {
        final SignImage image = ItemSignFinder.find(stack);
        if (image != null) {
            SignItemDraw.drawInGui(image, x, y, this.zLevel);
            return;
        }
        super.renderItemAndEffectIntoGUI(fr, tm, stack, x, y);
    }

    @Override
    public void doRender(EntityItem entityItem, double x, double y, double z, float yaw, float partialTicks) {
        final ItemStack stack = entityItem.getEntityItem();
        final SignImage image = ItemSignFinder.find(stack);
        if (image != null) {
            GL11.glPushMatrix();
            GL11.glTranslatef((float) x, (float) y, (float) z);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            if (RenderItem.renderInFrame) {
                // 額縁内の変換 (0.5128205 倍、下へ 0.05 移動、正面を向くよう 180 度回転)
                GL11.glScalef(0.5128205F, 0.5128205F, 0.5128205F);
                GL11.glTranslatef(0.0F, -0.05F, 0.0F);
                GL11.glRotatef(180.0F, 0.0F, 1.0F, 0.0F);
            } else {
                // 地面に落ちているアイテムの変換 (0.5 倍、時間経過による Y 軸回転)
                final float rotation = ((float) entityItem.age + partialTicks) / 20.0F + entityItem.hoverStart;
                GL11.glScalef(0.5F, 0.5F, 0.5F);
                GL11.glRotatef(rotation * 57.295776F, 0.0F, 1.0F, 0.0F);
            }
            SignItemDraw.drawEntity(image);
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glPopMatrix();
            return;
        }
        super.doRender(entityItem, x, y, z, yaw, partialTicks);
    }
}
