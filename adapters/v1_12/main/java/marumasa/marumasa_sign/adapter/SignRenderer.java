package marumasa.marumasa_sign.adapter;

import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.tileentity.TileEntitySign;

/**
 * 看板のレンダラー。MarumaSign の看板なら、看板を描かない。
 */
final class SignRenderer extends TileEntitySignRenderer {

    @Override
    public void render(TileEntitySign sign, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        // MarumaSign の看板は描かない (画像は WorldPass が描く)。スペクテイターには看板も見せる
        if (SignDraw.find(sign) == null || Mc.isSpectator()) {
            super.render(sign, x, y, z, partialTicks, destroyStage, alpha);
        }
    }
}