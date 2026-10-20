package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.Buffer;
import java.nio.FloatBuffer;

/**
 * 看板アイテムの画像や 3D モデルを各場面の OpenGL 座標系に描く。
 */
final class SignItemDraw {

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    private SignItemDraw() {
    }

    /**
     * 画面に映したときに、面の巻き方向が逆になる変換かどうか。
     * 巻き方向は画面上で決まるので、変換行列だけでなく射影行列の反転も数える。
     * 通常の射影 (透視・正射影) は行列式が負なので、射影と変換の行列式の積が正なら、通常とは逆向きになる。
     * GUI の射影は上下が逆 (下が +Y) なので行列式が正になり、変換の Y の反転と打ち消し合う。
     */
    private static boolean mirrored() {
        ((Buffer) MATRIX).clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        final float view = determinant3(MATRIX);
        ((Buffer) MATRIX).clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MATRIX);
        return view * determinant4(MATRIX) > 0;
    }

    // 列優先の 4x4 行列の、左上 3x3 の行列式
    private static float determinant3(FloatBuffer m) {
        final float a = m.get(0), b = m.get(4), c = m.get(8);
        final float d = m.get(1), e = m.get(5), f = m.get(9);
        final float g = m.get(2), h = m.get(6), i = m.get(10);
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    // 列優先の 4x4 行列の行列式 (余因子展開)
    private static float determinant4(FloatBuffer m) {
        float result = 0;
        for (int column = 0; column < 4; column++) {
            // 1 行目の column 列目を除いた 3x3 の行列式
            final float[] minor = new float[9];
            int k = 0;
            for (int row = 1; row < 4; row++) {
                for (int c = 0; c < 4; c++) {
                    if (c != column) minor[k++] = m.get(c * 4 + row);
                }
            }
            final float sub = minor[0] * (minor[4] * minor[8] - minor[5] * minor[7])
                    - minor[1] * (minor[3] * minor[8] - minor[5] * minor[6])
                    + minor[2] * (minor[3] * minor[7] - minor[4] * minor[6]);
            final float sign = column % 2 == 0 ? 1 : -1;
            result += sign * m.get(column * 4) * sub;
        }
        return result;
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
        // 反転を含む変換では面の巻き方向が逆になるので、3D モデルのカリングを正しくするために表の向きを合わせる。
        // ゲーム側も GUI のアイテムに反転を含む変換をかけることがあるため、決め打ちせず、いまの変換行列から判断する
        GL11.glFrontFace(mirrored() ? GL11.GL_CW : GL11.GL_CCW);
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
