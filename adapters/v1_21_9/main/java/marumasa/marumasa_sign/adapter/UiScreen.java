package marumasa.marumasa_sign.adapter;

import net.minecraft.client.gui.GuiGraphics;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final PlatformImpl.Texture texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(unscale, unscale);
        graphics.blit(texture.id,
                ui.originX(), ui.originY(), ui.originX() + ui.pixelWidth(), ui.originY() + ui.pixelHeight(),
                0f, 1f, 0f, 1f);
        graphics.pose().popMatrix();
    }
}
