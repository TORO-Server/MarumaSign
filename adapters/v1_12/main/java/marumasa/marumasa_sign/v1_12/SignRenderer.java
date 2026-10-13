package marumasa.marumasa_sign.v1_12;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.block.BlockWallSign;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntitySign;

/**
 * 看板のレンダラー。MarumaSign の看板なら、看板の代わりに画像を描く。
 */
final class SignRenderer extends TileEntitySignRenderer {

    private final Minecraft mc = Minecraft.getMinecraft();

    // 看板に書いてある文字を 1 つの文字列にする
    static String read(TileEntitySign sign) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (sign.signText[i] != null) text.append(sign.signText[i].getUnformattedText());
        }
        return text.toString();
    }

    @Override
    public void render(TileEntitySign sign, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        final SignImage image = MarumaSign.sign(read(sign));
        if (image == null) {
            super.render(sign, x, y, z, partialTicks, destroyStage, alpha);
            return;
        }
        // スペクテイターには看板そのものも見せる
        if (mc.player != null && mc.player.isSpectator()) {
            super.render(sign, x, y, z, partialTicks, destroyStage, alpha);
        }
        if (destroyStage >= 0) return;

        final IBlockState state = sign.getWorld().getBlockState(sign.getPos());
        final boolean wall = state.getBlock() == Blocks.WALL_SIGN;
        final float yaw;
        if (wall) {
            yaw = state.getValue(BlockWallSign.FACING).getHorizontalAngle();
        } else if (state.getBlock() == Blocks.STANDING_SIGN) {
            yaw = sign.getBlockMetadata() * 22.5f;
        } else {
            return;
        }

        final float[] mesh = image.mesh(yaw, wall);
        bindTexture(((PlatformImpl.Texture) image.texture()).location);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);

        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        for (int i = 0; i < SignImage.VERTEX_COUNT; i++) {
            final int o = i * SignImage.STRIDE;
            buffer.pos(mesh[o], mesh[o + 1], mesh[o + 2]).tex(mesh[o + 3], mesh[o + 4]).endVertex();
        }
        tessellator.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }
}
