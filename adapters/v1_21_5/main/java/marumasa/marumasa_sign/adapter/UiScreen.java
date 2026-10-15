package marumasa.marumasa_sign.adapter;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        graphics.pose().pushPose();
        graphics.pose().scale(unscale, unscale, 1f);
        graphics.blit(RenderType::guiTextured, texture.id, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        graphics.pose().popPose();
    }
}
