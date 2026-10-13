package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Minecraft 26.1 - 26.1.2 に固有の部分。
 */
final class Mc {

    // 看板のブロックエンティティタイプ
    static final BlockEntityType<?> SIGN = BlockEntityType.SIGN;

    private Mc() {
    }

    static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    static void chat(Minecraft mc, Component message) {
        mc.gui.getChat().addClientSystemMessage(message);
    }

    static KeyMapping newKey(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, KeyMapping.Category.MISC);
    }

    // 看板に書いてある文字 (表と裏) を 1 つの文字列にする
    static String signText(SignBlockEntity sign) {
        final StringBuilder text = new StringBuilder();
        final SignText front = sign.getFrontText();
        final SignText back = sign.getBackText();
        for (int i = 0; i < 4; i++) {
            text.append(front.getMessage(i, false).getString());
        }
        for (int i = 0; i < 4; i++) {
            text.append(back.getMessage(i, false).getString());
        }
        return text.toString();
    }
}