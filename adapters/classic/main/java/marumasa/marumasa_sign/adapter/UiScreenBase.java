package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.Window;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.screens.Screen;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MarumaSign の画面。入力を core の UiRoot に橋渡しする。
 * 描画のメソッドはバージョンによって引数が違うため、UiScreen 側で実装する。
 */
abstract class UiScreenBase extends Screen {

    protected final UiRoot ui = MarumaSign.ui();

    protected UiScreenBase() {
        super(Mc.text("MarumaSign"));
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

    // 描画の直前に呼ぶ。画面に貼るテクスチャを返す (なければ null)
    protected final Tex prepare() {
        final Window window = minecraft.getWindow();
        ui.layout(window.getWidth(), window.getHeight());
        ui.mouseMoved(mouseX(), mouseY());
        return (Tex) ui.texture();
    }

    // GUI の拡大率を打ち消して、ピクセル単位で描くための倍率
    protected final float unscale() {
        return (float) (1.0 / minecraft.getWindow().getGuiScale());
    }

    // マウスの座標 (ウィンドウ上の位置) をフレームバッファのピクセル単位にする
    private double mouseX() {
        final Window window = minecraft.getWindow();
        return minecraft.mouseHandler.xpos() * window.getWidth() / Math.max(1, window.getScreenWidth());
    }

    private double mouseY() {
        final Window window = minecraft.getWindow();
        return minecraft.mouseHandler.ypos() * window.getHeight() / Math.max(1, window.getScreenHeight());
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) ui.mousePressed(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) ui.mouseReleased(mouseX(), mouseY());
        return true;
    }

    // スクロールのメソッドはバージョンによって引数の数が違う。
    // 両方を用意しておき、そのバージョンにあるほうが呼ばれる (そのため @Override は付けない)
    public boolean mouseScrolled(double x, double y, double amount) {
        ui.mouseScrolled(mouseX(), mouseY(), amount);
        return true;
    }

    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        ui.mouseScrolled(mouseX(), mouseY(), scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        final int key = Key.fromGlfw(keyCode);
        if (key != Key.UNKNOWN) ui.keyPressed(key, Key.modifiersFromGlfw(modifiers));
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        ui.charTyped(character);
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
