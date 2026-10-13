package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.lang.reflect.Field;

/**
 * Minecraft 1.14 - 1.14.4 に固有の部分。
 */
final class Mc {

    // 看板のブロックエンティティタイプ
    static final BlockEntityType<?> SIGN = BlockEntityType.SIGN;

    private static Field signMessages;

    private Mc() {
    }

    static String version() {
        return SharedConstants.getCurrentVersion().getName();
    }

    static Window window(Minecraft mc) {
        return mc.window;
    }

    static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    static Component text(String text) {
        return new TextComponent(text);
    }

    // チャット欄にクライアント側だけのメッセージを表示する
    static void chat(Minecraft mc, String message) {
        mc.gui.getChat().addMessage(new TextComponent(message));
    }

    static KeyMapping newKey(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, "key.categories.misc");
    }

    // 看板に書いてある文字を 1 つの文字列にする
    static String signText(SignBlockEntity sign) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            text.append(sign.getMessage(i).getString());
        }
        return text.toString();
    }

    static boolean isSignEdit(Screen screen) {
        return screen instanceof SignEditScreen;
    }

    // 看板の編集画面の内容を書き換える。画面を閉じるとサーバーへ送信される
    static void fillSign(Screen screen, String[] lines) {
        if (signMessages == null) signMessages = Reflect.fieldOfType(SignEditScreen.class, SignBlockEntity.class);
        final SignBlockEntity sign = Reflect.get(signMessages, screen);
        for (int i = 0; i < 4; i++) {
            sign.setMessage(i, new TextComponent(lines[i]));
        }
    }

    // ---------- 動作確認用 ----------

    static void look(Minecraft mc, float pitch) {
        mc.player.xRot = pitch;
    }

    // ホットバーにある看板を手に持つ
    static void selectSign(Minecraft mc) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.inventory.getItem(slot).getItem() == Items.OAK_SIGN) {
                mc.player.inventory.selected = slot;
            }
        }
    }

    // このバージョンでは、カメラの向きによって看板が描かれなくなる問題はない
    static void avoidExactAngles(Minecraft mc) {
    }
}
