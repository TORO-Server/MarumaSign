package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.Window;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MarumaSign の画面。描画と入力を core の UiRoot に橋渡しするだけ。
 */
final class UiScreen extends Screen {

    private final UiRoot ui = MarumaSign.ui();

    UiScreen() {
        super(Component.literal("MarumaSign"));
        ui.opened();
    }

    @Override
    protected void init() {
        final Window window = minecraft.getWindow();
        ui.layout(window.getWidth(), window.getHeight());
    }

    @Override
    public void removed() {
        ui.closed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // マウスの座標 (ウィンドウ上の位置) をフレームバッファのピクセル単位にする
    private double pixelX(double x) {
        final Window window = minecraft.getWindow();
        return x * window.getWidth() / Math.max(1, window.getScreenWidth());
    }

    private double pixelY(double y) {
        final Window window = minecraft.getWindow();
        return y * window.getHeight() / Math.max(1, window.getScreenHeight());
    }

    private double mouseX() {
        return pixelX(minecraft.mouseHandler.xpos());
    }

    private double mouseY() {
        return pixelY(minecraft.mouseHandler.ypos());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        final Window window = minecraft.getWindow();
        ui.layout(window.getWidth(), window.getHeight());
        ui.mouseMoved(mouseX(), mouseY());

        final Object texture = ui.texture();
        if (texture == null) return;

        // GUI の拡大率を打ち消して、ピクセル単位で描く
        final float unscale = 1f / window.getGuiScale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(unscale, unscale);
        graphics.blit(((PlatformImpl.Texture) texture).id,
                ui.originX(), ui.originY(), ui.originX() + ui.pixelWidth(), ui.originY() + ui.pixelHeight(),
                0f, 1f, 0f, 1f);
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) ui.mousePressed(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) ui.mouseReleased(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        ui.mouseScrolled(mouseX(), mouseY(), scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        final int key = Key.fromGlfw(event.key());
        if (key != Key.UNKNOWN) ui.keyPressed(key, Key.modifiersFromGlfw(event.modifiers()));
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        final String text = event.codepointAsString();
        for (int i = 0; i < text.length(); i++) {
            ui.charTyped(text.charAt(i));
        }
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        final List<File> files = new ArrayList<File>();
        for (Path path : paths) {
            files.add(path.toFile());
        }
        ui.filesDropped(files);
    }
}
