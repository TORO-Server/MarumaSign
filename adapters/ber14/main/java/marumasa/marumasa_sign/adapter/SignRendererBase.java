package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 */
final class SignRendererBase extends BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    private final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    private SignRendererBase(BlockEntityRenderer<SignBlockEntity> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = BlockEntityRenderDispatcher.instance;
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        // このバージョンでは、ブロックエンティティのクラスをキーにした表になっている
        final Map<Class<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(SignBlockEntity.class);
        if (current == null || current instanceof SignRendererBase) return;

        final SignRendererBase wrapper = new SignRendererBase((BlockEntityRenderer<SignBlockEntity>) current);
        wrapper.init(dispatcher);
        renderers.put(SignBlockEntity.class, wrapper);
    }

    @Override
    public void render(SignBlockEntity sign, double x, double y, double z, float partialTicks, int destroyStage) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        // スペクテイターには看板そのものも見せる
        if (image == null || (mc.player != null && mc.player.isSpectator())) {
            delegate.render(sign, x, y, z, partialTicks, destroyStage);
        }
        if (image == null || destroyStage >= 0) return;

        final BlockState blockState = sign.getBlockState();
        final boolean wall;
        final float yaw;
        if (blockState.getBlock() instanceof WallSignBlock) {
            wall = true;
            yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            wall = false;
            yaw = blockState.getValue(StandingSignBlock.ROTATION) * 22.5f;
        } else {
            return;
        }
        final float[] mesh = image.mesh(yaw, wall);

        mc.getTextureManager().bind(((Tex) image.texture(Math.sqrt(x * x + y * y + z * z))).id);

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

    @Override
    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }
}
