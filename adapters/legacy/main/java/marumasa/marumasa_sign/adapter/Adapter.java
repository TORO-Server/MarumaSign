package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.OptionsFile;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.tileentity.TileEntitySign;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

/**
 * Minecraft 1.7.10 - 1.12.2 向けの実装の入口。ローダーごとの処理から init と tick が呼ばれる。
 * バージョンによって違う部分は Mc クラスにまとめてある。
 */
public final class Adapter {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // この実装が core を初期化したかどうか (他の経路で既に起動していれば false のまま)
    private static boolean active;
    private static KeyBinding openMenu;
    private static boolean rendererInstalled;
    private static Field editSignTile;

    private Adapter() {
    }

    // loaderName: 起動したローダーの名前。初期化した場合は true を返す
    public static synchronized boolean init(String loaderName) {
        if (MarumaSign.isInitialized()) return false;
        MarumaSign.init(new PlatformImpl(loaderName));
        active = true;

        // キーバインド登録 (48 は B キー)
        openMenu = new KeyBinding("MarumaSign", 48, "key.categories.misc");
        final KeyBinding[] bindings = Arrays.copyOf(mc.gameSettings.keyBindings, mc.gameSettings.keyBindings.length + 1);
        bindings[bindings.length - 1] = openMenu;
        mc.gameSettings.keyBindings = bindings;
        // 保存されているキーの割り当てを反映する
        final String saved = OptionsFile.read(Mc.gameDirectory(), "key_MarumaSign");
        if (saved != null) {
            try {
                openMenu.setKeyCode(Integer.parseInt(saved));
            } catch (NumberFormatException e) {
                // 読めない値なら既定のキーのままにする
            }
        }
        UseKey.install(mc);
        KeyBinding.resetKeyBindingArrayAndHash();
        return true;
    }

    // クライアントの tick の最後に呼ばれる
    public static void tick() {
        if (!active) return;
        if (!rendererInstalled) installRenderer();
        SignItems.install(mc);

        MarumaSign.tick();

        while (openMenu.isPressed()) {
            if (Mc.inWorld() && mc.currentScreen == null) MarumaSign.openMenu();
        }

        // 看板の編集画面が開かれたら、予約されている文字列を書き込んで閉じる
        if (MarumaSign.hasPendingWrite() && mc.currentScreen instanceof GuiEditSign) {
            writeSign((GuiEditSign) mc.currentScreen);
        }
    }

    // ワールドの描画の最後に呼ばれる
    public static void renderWorld() {
        if (active) WorldPass.render();
    }

    // 看板のレンダラーを置き換える
    private static void installRenderer() {
        final TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        final Map<Class<?>, Object> renderers = Reflect.get(Reflect.fieldOfType(TileEntityRendererDispatcher.class, Map.class), dispatcher);
        final SignRenderer renderer = new SignRenderer();
        Mc.attach(renderer, dispatcher);
        renderers.put(TileEntitySign.class, renderer);
        rendererInstalled = true;
    }

    private static void writeSign(GuiEditSign screen) {
        if (editSignTile == null) editSignTile = Reflect.fieldOfType(GuiEditSign.class, TileEntitySign.class);
        final TileEntitySign sign = Reflect.get(editSignTile, screen);
        final String[] lines = MarumaSign.takePendingWrite();
        if (sign != null && lines != null) {
            Mc.setSignText(sign, lines);
            sign.markDirty();
        }
        // 画面を閉じると、看板の内容がサーバーへ送信される
        mc.displayGuiScreen(null);
    }
}
