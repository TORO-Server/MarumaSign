package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntitySign;
import org.lwjgl.opengl.GL11;

/**
 * 看板の位置に画像を描く。
 * 頂点の送り方はバージョンごとに API が違うため、OpenGL を直接使って共通にしている。
 */
final class SignDraw {

    private SignDraw() {
    }

    // 看板に対応する画像を返す。MarumaSign の看板でなければ null
    static SignImage find(TileEntitySign sign) {
        return MarumaSign.sign(Mc.signText(sign));
    }

    // 壁掛け看板の向き (メタデータ) を角度にする
    private static float wallYaw(int metadata) {
        switch (metadata) {
            case 2: return 180f;
            case 4: return 90f;
            case 5: return 270f;
            default: return 0f;
        }
    }

    static void draw(SignImage image, TileEntitySign sign, double x, double y, double z) {
        final boolean wall = Mc.isWallSign(sign);
        final int metadata = sign.getBlockMetadata();
        final float yaw = wall ? wallYaw(metadata) : metadata * 22.5f;
        final float[] mesh = image.mesh(yaw, wall);

        // x, y, z はカメラから見た位置なので、そのまま看板までの距離になる
        final double distance = Math.sqrt(x * x + y * y + z * z);
        Minecraft.getMinecraft().getTextureManager().bindTexture(((PlatformImpl.Texture) image.texture(distance)).location);

        // 変更した状態は glPopAttrib で元に戻す
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < SignImage.VERTEX_COUNT; i++) {
            final int o = i * SignImage.STRIDE;
            GL11.glTexCoord2f(mesh[o + 3], mesh[o + 4]);
            GL11.glVertex3f(mesh[o], mesh[o + 1], mesh[o + 2]);
        }
        GL11.glEnd();

        GL11.glPopMatrix();
        GL11.glPopAttrib();
        MarumaSign.drawn(image);
    }
}
