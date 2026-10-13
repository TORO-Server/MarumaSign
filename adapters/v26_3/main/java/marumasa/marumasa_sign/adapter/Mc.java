package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.SignTextSlot;

/**
 * Minecraft 26.3 に固有の部分。
 */
final class Mc {

    // 看板のブロックエンティティタイプ
    static final BlockEntityType<?> SIGN = BlockEntityTypes.SIGN;

    private Mc() {
    }

    static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }

    static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    static void chat(Minecraft mc, Component message) {
        mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    static KeyMapping newKey(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, keyCode, KeyMapping.Category.MISC);
    }

    // 看板に書いてある文字 (表と裏) を 1 つの文字列にする
    static String signText(SignBlockEntity sign) {
        final StringBuilder text = new StringBuilder();
        // 表、裏の順に並んでいる
        for (SignTextSlot slot : SignTextSlot.values()) {
            for (Component line : sign.getText(slot).getMessages(false)) {
                text.append(line.getString());
            }
        }
        return text.toString();
    }
}