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

    // x, y, z は描画の原点から見た看板の位置、camX, camY, camZ はブロックの角を原点としたカメラの位置
    static void draw(SignImage image, TileEntitySign sign, double x, double y, double z,
                     float camX, float camY, float camZ) {
        final boolean wall = Mc.isWallSign(sign);
        final int metadata = sign.getBlockMetadata();
        final float yaw = wall ? wallYaw(metadata) : metadata * 22.5f;
        final SignImage.Batch[] batches = image.batches(yaw, wall, Math.sqrt(camX * camX + camY * camY + camZ * camZ),
                camX, camY, camZ, (int) Mc.x(sign), (int) Mc.y(sign), (int) Mc.z(sign));

        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);
            drawBatches(batches);
        } finally {
            GL11.glPopMatrix();
        }
        MarumaSign.drawn(image);
    }

    /**
     * 四角形のまとまりを、現在の座標系に描く。看板のアイテムを描くときにも使う。
     */
    static void drawBatches(SignImage.Batch[] batches) {
        // 変更した状態は glPopAttrib で元に戻す
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        // 3D モデルは陰影を計算済みなので、影 Mod には上を向いた面として扱ってもらう (絵は面ごとに向きを指定する)
        GL11.glNormal3f(0f, 1f, 0f);

        try {
            for (SignImage.Batch batch : batches) {
                Minecraft.getMinecraft().getTextureManager().bindTexture(((PlatformImpl.Texture) batch.texture).location);
                if (batch.colors != null) {
                    // 3D モデルは裏を向いた面と、透明な部分を描かない
                    if (batch.doubleSided) GL11.glDisable(GL11.GL_CULL_FACE);
                    else GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                    GL11.glDepthMask(!batch.blend);
                    ModelArrays.draw(batch);
                    if (batch.blend && batch.doubleSided) depthPass(batch);
                    continue;
                }
                final float[] mesh = batch.vertices;
                GL11.glBegin(GL11.GL_QUADS);
                for (int i = 0; i < batch.vertexCount; i++) {
                    final int o = i * SignImage.Batch.STRIDE;
                    if ((i & 3) == 0 && i + 2 < batch.vertexCount) {
                        // 面の向き。影 Mod が光の当たり方を計算するのに使う (通常の描画では使われない)
                        final int b = o + SignImage.Batch.STRIDE, c = b + SignImage.Batch.STRIDE;
                        final float ux = mesh[b] - mesh[o], uy = mesh[b + 1] - mesh[o + 1], uz = mesh[b + 2] - mesh[o + 2];
                        final float vx = mesh[c] - mesh[o], vy = mesh[c + 1] - mesh[o + 1], vz = mesh[c + 2] - mesh[o + 2];
                        final float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                        final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (length > 0) GL11.glNormal3f(nx / length, ny / length, nz / length);
                    }
                    GL11.glTexCoord2f(mesh[o + 3], mesh[o + 4]);
                    GL11.glVertex3f(mesh[o], mesh[o + 1], mesh[o + 2]);
                }
                GL11.glEnd();
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * 半透明の面 (ガウシアンスプラッティング) の、ほぼ不透明な部分の奥行きだけを記録する。
     * 色は奥行きを記録せずに描くので、そのままだと後から描かれる空や雲 (影 Mod では特に) に上書きされてしまう。
     * 色を描き終えてから奥行きだけを書くので、重なりの順番には影響しない。
     */
    private static void depthPass(SignImage.Batch batch) {
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.5f);
        ModelArrays.draw(batch);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glColorMask(true, true, true, true);
    }
}
