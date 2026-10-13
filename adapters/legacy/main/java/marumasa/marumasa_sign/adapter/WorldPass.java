package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntitySign;

import java.util.List;

/**
 * ワールドの描画の最後に、すべての MarumaSign の画像を描く。
 * 看板のレンダラーから描くと、看板のブロックが視界の外にあるときや遠いときに
 * 画像ごと省かれてしまうため、看板とは別に自前で描いている。
 */
public final class WorldPass {

    private WorldPass() {
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
                Mc.enableLightmap();
                lightmap = true;
                // 影 Mod が有効なら、ブロックエンティティ用のシェーダーで描く
                shaders = ShaderHooks.begin();
            }
            // 看板の位置の明るさで描く
            Mc.applyLight(sign);
            SignDraw.draw(image, sign, x, y, z);
        }
        if (shaders) ShaderHooks.end();
        if (lightmap) Mc.disableLightmap();
    }
}
