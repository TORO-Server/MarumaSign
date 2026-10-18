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
 * Minecraft 1.15 - 1.15.2 に固有の部分。
 */
final class Mc {

    // 看板のブロックエンティティタイプ
    static final BlockEntityType<?> SIGN = BlockEntityType.SIGN;

    // 編集画面が持っている看板のフィールド
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
        mc.player.xRot = pitch;
    }

    static void turn(Minecraft mc, float yaw) {
        mc.player.yRot = yaw;
    }

    // ホットバーにある看板を手に持つ
    static void selectSign(Minecraft mc) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.inventory.getItem(slot).getItem() == Items.OAK_SIGN) {
                mc.player.inventory.selected = slot;
            }
        }
    }

    // Forge は、カメラの向きが軸にぴったり沿っていると看板を描かないことがあるので、見た目に分からない程度だけずらす
    static void avoidExactAngles(Minecraft mc) {
        mc.player.xRot = awayFromAxis(mc.player.xRot, true);
        mc.player.yRot = awayFromAxis(mc.player.yRot, false);
    }

    // 90 度の倍数から 0.05 度以内なら、0.05 度だけ離す (画面では 1 ピクセルほどの違い)。
    // これより小さいずれは、向きを行列にする計算の途中で丸められて消えることがある
    private static float awayFromAxis(float degrees, boolean vertical) {
        final float nearest = Math.round(degrees / 90f) * 90f;
        final float offset = degrees - nearest;
        if (Math.abs(offset) >= 0.05f) return degrees;
        // 上下の向きは、真上と真下を超えないように内側へずらす
        if (vertical && nearest != 0f) return nearest > 0f ? nearest - 0.05f : nearest + 0.05f;
        return nearest + (offset < 0f ? -0.05f : 0.05f);
    }
}
