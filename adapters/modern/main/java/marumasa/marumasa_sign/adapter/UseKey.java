package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * 使用キー (右クリック) の代わり。ゲームより先にクリックを受け取り、PDF の看板のページ送りに使ったものはゲームに渡さない。
 * ゲームの設定にある使用キーをこれに置き換える (名前と分類、キーの割り当ては元のものを引き継ぐ)。
 */
final class UseKey extends KeyMapping {

    // 既定のキーも元のものを使う (マウスボタンの番号は入力ライブラリによって違い、26.3 からは右クリックが 3 になった)
    private UseKey(KeyMapping original) {
        super(original.getName(), original.getDefaultKey().getType(), original.getDefaultKey().getValue(),
                original.getCategory());
    }

    @Override
    public boolean consumeClick() {
        while (super.consumeClick()) {
            if (!MarumaSign.useClicked()) return true;
            // 押し続けたままだと、ゲームが続けて使用するので、離されたことにする
            KeyMapping.set(InputConstants.getKey(saveString()), false);
        }
        return false;
    }

    // 設定の使用キーを置き換える
    static void install(Minecraft mc) {
        final KeyMapping original = mc.options.keyUse;
        if (original instanceof UseKey) return;
        final UseKey replacement = new UseKey(original);
        replacement.setKey(InputConstants.getKey(original.saveString()));
        Reflect.set(Reflect.fieldHolding(mc.options, original), mc.options, replacement);
        final KeyMapping[] mappings = mc.options.keyMappings;
        for (int i = 0; i < mappings.length; i++) {
            if (mappings[i] == original) mappings[i] = replacement;
        }
        KeyMapping.resetMapping();
    }
}
