package marumasa.marumasa_sign.adapter;

import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.tileentity.TileEntitySign;

/**
 * 看板のレンダラー。MarumaSign の看板なら、看板を描かない。
 */
final class SignRenderer extends TileEntitySignRenderer {

    // Minecraft 1.8 では、看板を描くメソッドに分かりやすい名前が付いていない
    @Override
    public void func_180541_a(TileEntitySign sign, double x, double y, double z, float partialTicks, int destroyStage) {
        // MarumaSign の看板は描かない (画像は WorldPass が描く)。スペクテイターには看板も見せる
        if (SignDraw.find(sign) == null || Mc.isSpectator()) {
            super.func_180541_a(sign, x, y, z, partialTicks, destroyStage);
        }
    }
}
