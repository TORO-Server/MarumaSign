package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * 使用キー (右クリック) の代わり。ゲームより先にクリックを受け取り、PDF の看板のページ送りに使ったものはゲームに渡さない。
 * ゲームの設定にある使用キーをこれに置き換える (キーの割り当ては元のものを引き継ぐ)。
 */
final class UseKey extends KeyBinding {

    private UseKey(KeyBinding original) {
        super(original.getKeyDescription(), original.getKeyCode(), original.getKeyCategory());
    }

    @Override
    public boolean isPressed() {
        while (super.isPressed()) {
            if (!MarumaSign.useClicked()) return true;
            // 押し続けたままだと、ゲームが続けて使用するので、離されたことにする
            KeyBinding.setKeyBindState(getKeyCode(), false);
        }
        return false;
    }

    // 設定の使用キーを置き換える。KeyBinding.resetKeyBindingArrayAndHash を呼ぶ前に使う
    static void install(Minecraft mc) {
        final KeyBinding original = mc.gameSettings.keyBindUseItem;
        if (original instanceof UseKey) return;
        final UseKey replacement = new UseKey(original);
        Reflect.set(Reflect.fieldHolding(mc.gameSettings, original), mc.gameSettings, replacement);
        removeFromAllBindings(original);
        final KeyBinding[] bindings = mc.gameSettings.keyBindings;
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] == original) bindings[i] = replacement;
        }
    }

    /**
     * すべてのキーの割り当ての一覧から、元の使用キーを除く。
     * 1.11.2 までの一覧はリストで、置き換えたあとも元のものが残る。Forge 1.9 - 1.11 は同じキーの割り当てのうち
     * 先に見つかったものにだけ押されたことを伝えるので、残っていると置き換えたものが押されず、看板を置けなかった。
     * 1.12 からの一覧は名前で引く表で、同じ名前で作ったときに置き換わっている。
     */
    private static void removeFromAllBindings(KeyBinding original) {
        for (java.lang.reflect.Field field : KeyBinding.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !java.util.List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            final java.util.List<?> list = Reflect.get(field, null);
            if (list != null) list.remove(original);
        }
    }
}
