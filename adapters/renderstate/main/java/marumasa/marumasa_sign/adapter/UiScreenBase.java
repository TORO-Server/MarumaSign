package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MarumaSign の画面。入力を core の UiRoot に橋渡しする。
 * 描画のメソッドはバージョンによって名前や引数が違うため、UiScreen 側で実装する。
 */
abstract class UiScreenBase extends Screen {

    // 26.3 から入力が GLFW から SDL に変わり、キーとマウスボタンの番号が変わった (左クリックは 0 から 1 に)。
    // InputConstants の値はコンパイル時に埋め込まれるため、バージョンごとにどちらの番号かが決まる
    private static final boolean SDL = InputConstants.MOUSE_BUTTON_LEFT != 0;

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
        return 1f / minecraft.getWindow().getGuiScale();
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) ui.mousePressed(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) ui.mouseReleased(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        ui.mouseScrolled(mouseX(), mouseY(), scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        final int key = SDL ? sdlKey(event) : Key.fromGlfw(event.key());
        final int modifiers = SDL ? Key.modifiersFromSdl(event.modifiers()) : Key.modifiersFromGlfw(event.modifiers());
        if (key != Key.UNKNOWN) ui.keyPressed(key, modifiers);
        return true;
    }

    // SDL のキー。Ctrl+V などの文字のキーは、Minecraft と同じくキーボードの配列に合わせて判定する
    private static int sdlKey(KeyEvent event) {
        if (event.isSelectAll()) return Key.A;
        if (event.isCopy()) return Key.C;
        if (event.isPaste()) return Key.V;
        if (event.isCut()) return Key.X;
        return Key.fromSdl(event.key());
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
