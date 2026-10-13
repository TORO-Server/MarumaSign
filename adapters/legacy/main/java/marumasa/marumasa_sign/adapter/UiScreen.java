package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

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

        final float x0 = ui.originX();
        final float y0 = ui.originY();
        final float x1 = x0 + ui.pixelWidth();
        final float y1 = y0 + ui.pixelHeight();

        // 変更した状態は glPopAttrib で元に戻す
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glPushMatrix();
        // GUI の拡大率を打ち消して、ピクセル単位で描く
        final float unscale = (float) width / mc.displayWidth;
        GL11.glScalef(unscale, unscale, 1f);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex3f(x0, y1, 0f);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex3f(x1, y1, 0f);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex3f(x1, y0, 0f);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex3f(x0, y0, 0f);
        GL11.glEnd();

        GL11.glPopMatrix();
        GL11.glPopAttrib();
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
