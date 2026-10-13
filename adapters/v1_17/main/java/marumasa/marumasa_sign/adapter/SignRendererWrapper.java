package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * 看板のレンダラーを包むクラスの、描画の受け口。
 */
final class SignRendererWrapper extends SignRendererBase {

    SignRendererWrapper(BlockEntityRenderer<SignBlockEntity> delegate) {
        super(delegate);
    }

    @Override
    public void render(SignBlockEntity sign, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        if (drawImage(sign, poseStack, buffers, light)) {
            delegate.render(sign, partialTicks, poseStack, buffers, light, overlay);
        }
    }
}
