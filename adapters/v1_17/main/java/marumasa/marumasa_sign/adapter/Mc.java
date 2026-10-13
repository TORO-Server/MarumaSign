package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix4f;
import marumasa.marumasa_sign.core.sign.SignImage;
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
 * Minecraft 1.17 - 1.18.2 に固有の部分。
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
        return mc.getWindow();
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
            text.append(sign.getMessage(i, false).getString());
        }
        return text.toString();
    }

    static boolean isSignEdit(Screen screen) {
        return screen instanceof SignEditScreen;
    }

    // 看板の編集画面の内容を書き換える。画面を閉じるとサーバーへ送信される
    static void fillSign(Screen screen, String[] lines) {
        if (signMessages == null) signMessages = Reflect.fieldOfType(SignEditScreen.class, String[].class);
        final String[] messages = Reflect.get(signMessages, screen);
        System.arraycopy(lines, 0, messages, 0, 4);
    }

    // 頂点を描画用のバッファに書き込む
    static void quads(VertexConsumer vertices, PoseStack poseStack, float[] mesh, int light) {
        final Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < SignImage.VERTEX_COUNT; i++) {
            final int o = i * SignImage.STRIDE;
            vertices.vertex(matrix, mesh[o], mesh[o + 1], mesh[o + 2])
                    .color(255, 255, 255, 255)
                    .uv(mesh[o + 3], mesh[o + 4])
                    .uv2(light)
                    .endVertex();
        }
    }

    // ---------- 動作確認用 ----------

    static void look(Minecraft mc, float pitch) {
        mc.player.setXRot(pitch);
    }

    // ホットバーにある看板を手に持つ
    static void selectSign(Minecraft mc) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.OAK_SIGN)) {
                mc.player.getInventory().selected = slot;
            }
        }
    }
}
