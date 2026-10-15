package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        minecraft.getTextureManager().bind(texture.id);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        GlStateManager.pushMatrix();
        GlStateManager.scalef(unscale, unscale, 1f);
        blit(ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        GlStateManager.popMatrix();
        GlStateManager.disableBlend();
    }
}
