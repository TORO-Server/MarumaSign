package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 */
final class SignRendererBase extends BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    private final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    private SignRendererBase(BlockEntityRenderDispatcher dispatcher, BlockEntityRenderer<SignBlockEntity> delegate) {
        super(dispatcher);
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = BlockEntityRenderDispatcher.instance;
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererBase) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> replaced = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?>>(renderers);
        replaced.put(Mc.SIGN, new SignRendererBase(dispatcher, (BlockEntityRenderer<SignBlockEntity>) current));
        Reflect.set(renderersField, dispatcher, replaced);
    }

    @Override
    public void render(SignBlockEntity sign, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        // スペクテイターには看板そのものも見せる
        if (image == null || (mc.player != null && mc.player.isSpectator())) {
            delegate.render(sign, partialTicks, poseStack, buffers, light, overlay);
        }
        if (image == null) return;

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

        Mc.quads(buffers.getBuffer(((Tex) image.texture()).renderType), poseStack, image.mesh(yaw, wall), light);
        MarumaSign.drawn(image);
    }

    @Override
    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }
}
