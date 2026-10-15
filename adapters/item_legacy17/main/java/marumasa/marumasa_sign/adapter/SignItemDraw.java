package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import org.lwjgl.opengl.GL11;

/**
 * 看板アイテムの画像や 3D モデルを各場面の OpenGL 座標系に描く。
 */
final class SignItemDraw {

    private SignItemDraw() {
    }

    /**
     * インベントリやホットバー枠 (GUI) に描く。
     * 16x16 ピクセルの枠の中央に、上下正しく収まるように描く。
     */
    static void drawInGui(SignImage image, float x, float y, float zLevel) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_POLYGON_BIT);
        GL11.glTranslatef(x + 8.0f, y + 8.0f, zLevel);
        // GUI は上が -Y。itemBatches は上が +Y、正面が +Z なので、Y を反転して 16 倍にする
        GL11.glScalef(16.0f, -16.0f, 16.0f);
        // Y 軸反転によって面の巻き方向が CW になるため、3D モデルのカリングを正しくする
        GL11.glFrontFace(GL11.GL_CW);
        GL11.glTranslatef(-0.5f, -0.5f, -0.5f);
        SignDraw.drawBatches(image.itemBatches(1.0));
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        MarumaSign.drawn(image);
    }

    /**
     * 手に持ったとき (一人称・三人称) の位置に描く。
     * 看板と同じ変換が適用された座標系で、看板と同じ位置と向きに描く。
     */
    static void drawEquipped(SignImage image) {
        GL11.glPushMatrix();
        // 看板アイテムの表面 (Z=0) と中心を合わせる
        GL11.glTranslatef(0.0f, 0.0f, -0.5f);
        SignDraw.drawBatches(image.itemBatches(1.0));
        GL11.glPopMatrix();
        MarumaSign.drawn(image);
    }

    /**
     * 地面に落ちているとき、または額縁の中央に描く。
     */
    static void drawEntity(SignImage image) {
        GL11.glPushMatrix();
        // 立方体の中心 (0.5, 0.5, 0.5) を原点に合わせる
        GL11.glTranslatef(-0.5f, -0.5f, -0.5f);
        SignDraw.drawBatches(image.itemBatches(1.0));
        GL11.glPopMatrix();
        MarumaSign.drawn(image);
    }
}
