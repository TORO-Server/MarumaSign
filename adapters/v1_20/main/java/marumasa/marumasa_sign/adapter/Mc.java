package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.joml.Matrix4f;

import java.lang.reflect.Field;

/**
 * Minecraft 1.20 - 1.20.6 に固有の部分。
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
        return Component.literal(text);
    }

    // チャット欄にクライアント側だけのメッセージを表示する
    static void chat(Minecraft mc, String message) {
        mc.gui.getChat().addMessage(Component.literal(message));
    }

    static KeyMapping newKey(String name, int keyCode) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, "key.categories.misc");
    }

    // 看板に書いてある文字を 1 つの文字列にする
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

    static boolean isSignEdit(Screen screen) {
        return screen instanceof AbstractSignEditScreen;
    }

    // 看板の編集画面の内容を書き換える。画面を閉じるとサーバーへ送信される
    static void fillSign(Screen screen, String[] lines) {
        if (signMessages == null) signMessages = Reflect.fieldOfType(AbstractSignEditScreen.class, String[].class);
        final String[] messages = Reflect.get(signMessages, screen);
        System.arraycopy(lines, 0, messages, 0, 4);
    }

    // 頂点を描画用のバッファに書き込む
    static void quads(VertexConsumer vertices, PoseStack poseStack, SignImage.Batch batch, int light) {
        final Matrix4f matrix = poseStack.last().pose();
        final float[] mesh = batch.vertices;
        final int[] colors = batch.colors;
        for (int i = 0; i < batch.vertexCount; i++) {
            final int o = i * SignImage.Batch.STRIDE;
            final int color = colors == null ? 0xFFFFFFFF : colors[i];
            vertices.vertex(matrix, mesh[o], mesh[o + 1], mesh[o + 2])
                    .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24)
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
