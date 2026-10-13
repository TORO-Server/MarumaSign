package marumasa.marumasa_sign.v1_12;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * MarumaSign の画面。描画と入力を core の UiRoot に橋渡しするだけ。
 */
final class UiScreen extends GuiScreen {

    private final UiRoot ui = MarumaSign.ui();

    UiScreen() {
        ui.opened();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ui.layout(mc.displayWidth, mc.displayHeight);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ui.closed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // マウスの座標をフレームバッファのピクセル単位にする (上が 0)
    private int pixelY(int y) {
        return mc.displayHeight - y - 1;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ui.layout(mc.displayWidth, mc.displayHeight);
        ui.mouseMoved(Mouse.getX(), pixelY(Mouse.getY()));

        final Object texture = ui.texture();
        if (texture == null) return;
        mc.getTextureManager().bindTexture(((PlatformImpl.Texture) texture).location);

        final double x0 = ui.originX();
        final double y0 = ui.originY();
        final double x1 = x0 + ui.pixelWidth();
        final double y1 = y0 + ui.pixelHeight();

        GlStateManager.pushMatrix();
        // GUI の拡大率を打ち消して、ピクセル単位で描く
        final double unscale = (double) width / mc.displayWidth;
        GlStateManager.scale(unscale, unscale, 1.0);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);

        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x0, y1, 0).tex(0, 1).endVertex();
        buffer.pos(x1, y1, 0).tex(1, 1).endVertex();
        buffer.pos(x1, y0, 0).tex(1, 0).endVertex();
        buffer.pos(x0, y0, 0).tex(0, 0).endVertex();
        tessellator.draw();

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    @Override
    public void handleMouseInput() {
        final int x = Mouse.getEventX();
        final int y = pixelY(Mouse.getEventY());
        if (Mouse.getEventButton() == 0) {
            if (Mouse.getEventButtonState()) ui.mousePressed(x, y);
            else ui.mouseReleased(x, y);
        }
        final int wheel = Mouse.getEventDWheel();
        if (wheel != 0) ui.mouseScrolled(x, y, wheel);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        final boolean ctrl = isCtrlKeyDown();
        final int key = Key.fromLwjgl2(keyCode);
        if (key != Key.UNKNOWN) ui.keyPressed(key, Key.modifiers(isShiftKeyDown(), ctrl));
        if (!ctrl && typedChar >= 0x20 && typedChar != 0x7F) ui.charTyped(typedChar);
    }
}
