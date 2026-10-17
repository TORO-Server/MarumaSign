package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntitySign;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * ワールドの描画の最後に、すべての MarumaSign の画像を描く。
 * 看板のレンダラーから描くと、看板のブロックが視界の外にあるときや遠いときに
 * 画像ごと省かれてしまうため、看板とは別に自前で描いている。
 */
public final class WorldPass {

    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    // staticPlayerX/Y/Z を原点としたカメラの位置
    private static double cameraX, cameraY, cameraZ;

    private WorldPass() {
    }

    /**
     * いまの変換行列から、カメラの位置を求める。
     * staticPlayerY は 1.7.10 では目の高さだが、1.8 以降は足元なので、そのままではカメラの位置にならない。
     * 変換行列には目の高さも三人称視点のずれも入っているので、どのバージョンでも同じ方法で求められる。
     */
    private static void readCamera() {
        ((Buffer) MATRIX).clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        final float tx = MATRIX.get(12), ty = MATRIX.get(13), tz = MATRIX.get(14);
        // 回転の逆は転置なので、カメラの位置は -R^T t
        cameraX = -(MATRIX.get(0) * tx + MATRIX.get(1) * ty + MATRIX.get(2) * tz);
        cameraY = -(MATRIX.get(4) * tx + MATRIX.get(5) * ty + MATRIX.get(6) * tz);
        cameraZ = -(MATRIX.get(8) * tx + MATRIX.get(9) * ty + MATRIX.get(10) * tz);
    }

    public static void render() {
        if (!Mc.inWorld()) return;

        final List<?> tileEntities = Mc.tileEntities();
        boolean lightmap = false;
        boolean shaders = false;
        for (int i = 0; i < tileEntities.size(); i++) {
            final Object tileEntity = tileEntities.get(i);
            if (!(tileEntity instanceof TileEntitySign)) continue;
            final TileEntitySign sign = (TileEntitySign) tileEntity;
            final SignImage image = SignDraw.find(sign);
            if (image == null) continue;

            // カメラから見た位置
            final double x = Mc.x(sign) - TileEntityRendererDispatcher.staticPlayerX;
            final double y = Mc.y(sign) - TileEntityRendererDispatcher.staticPlayerY;
            final double z = Mc.z(sign) - TileEntityRendererDispatcher.staticPlayerZ;
            final double distance = image.viewDistance();
            if (x * x + y * y + z * z > distance * distance) continue;

            if (!lightmap) {
                readCamera();
                Mc.enableLightmap();
                lightmap = true;
                // 影 Mod が有効なら、ブロックエンティティ用のシェーダーで描く
                shaders = ShaderHooks.begin();
            }
            // 看板の位置の明るさで描く
            Mc.applyLight(sign);
            SignDraw.draw(image, sign, x, y, z, (float) (cameraX - x), (float) (cameraY - y), (float) (cameraZ - z));
        }
        if (shaders) ShaderHooks.end();
        if (lightmap) Mc.disableLightmap();
    }
}
