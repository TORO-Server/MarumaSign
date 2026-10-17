package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.io.File;
import java.util.List;

/**
 * Minecraft 1.7.10 に固有の部分。
 */
final class Mc {

    // 1.7.10 のサーバーは 1 行が 15 文字を超える看板を受け付けない
    static final int MAX_SIGN_LINE_LENGTH = 15;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private Mc() {
    }

    static boolean inWorld() {
        return mc.theWorld != null && mc.thePlayer != null;
    }

    // 1.7.10 にスペクテイターモードはない
    static boolean isSpectator() {
        return false;
    }

    static File gameDirectory() {
        return mc.mcDataDir;
    }

    static void execute(Runnable task) {
        mc.addScheduledTask(task);
    }

    static void chat(String message) {
        mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }

    static void attach(SignRenderer renderer, TileEntityRendererDispatcher dispatcher) {
        renderer.func_147497_a(dispatcher);
    }

    // 看板に書いてある文字を 1 つの文字列にする
    static String signText(TileEntitySign sign) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (sign.signText[i] != null) text.append(sign.signText[i]);
        }
        return text.toString();
    }

    static void setSignText(TileEntitySign sign, String[] lines) {
        System.arraycopy(lines, 0, sign.signText, 0, 4);
    }

    static boolean isWallSign(TileEntitySign sign) {
        return sign.getBlockType() == Blocks.wall_sign;
    }

    // ---------- ワールドへの描画 ----------

    static List<?> tileEntities() {
        return mc.theWorld.loadedTileEntityList;
    }

    static double x(TileEntitySign sign) {
        return sign.xCoord;
    }

    static double y(TileEntitySign sign) {
        return sign.yCoord;
    }

    static double z(TileEntitySign sign) {
        return sign.zCoord;
    }

    static void enableLightmap() {
        mc.entityRenderer.enableLightmap(0.0);
    }

    static void disableLightmap() {
        mc.entityRenderer.disableLightmap(0.0);
    }

    static void applyLight(TileEntitySign sign) {
        final int light = mc.theWorld.getLightBrightnessForSkyBlocks(sign.xCoord, sign.yCoord, sign.zCoord, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
    }

    // ---------- 照準 ----------

    // 照準の視線 {目の位置 x, y, z, 視線の向き x, y, z, 照準が合っているブロックまでの距離 (なければ -1)}
    static double[] crosshair() {
        final EntityLivingBase viewer = mc.renderViewEntity;
        if (viewer == null || !inWorld() || mc.currentScreen != null) return null;
        final Vec3 eye = viewer.getPosition(1f);
        final Vec3 look = viewer.getLook(1f);
        final MovingObjectPosition target = mc.objectMouseOver;
        final double hit = target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && target.hitVec != null
                ? target.hitVec.distanceTo(eye) : -1;
        return new double[]{eye.xCoord, eye.yCoord, eye.zCoord, look.xCoord, look.yCoord, look.zCoord, hit};
    }

    // ---------- 動作確認用 ----------

    static void look(float pitch) {
        if (mc.thePlayer != null) mc.thePlayer.rotationPitch = pitch;
    }

    static void turn(float yaw) {
        if (mc.thePlayer != null) mc.thePlayer.rotationYaw = yaw;
    }

    // ホットバーにある看板を手に持つ
    static void selectSign() {
        for (int slot = 0; slot < 9; slot++) {
            final ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
            if (stack != null && stack.getItem() == Items.sign) {
                mc.thePlayer.inventory.currentItem = slot;
            }
        }
    }
}
