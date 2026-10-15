package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * 看板のレンダラーを包むクラスの、描画の受け口。
 */
final class SignRendererWrapper extends SignRendererBase {

    SignRendererWrapper(BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate) {
        super(delegate);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (drawsVanilla(state)) delegate.submit(state.inner, poseStack, collector, camera);
        submitImage(state, poseStack, collector, camera.cullFrustum);
    }
}
