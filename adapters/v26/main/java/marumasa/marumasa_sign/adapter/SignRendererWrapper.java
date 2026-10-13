package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 */
final class SignRendererWrapper implements BlockEntityRenderer<SignBlockEntity, SignRendererWrapper.State> {

    static final class State extends BlockEntityRenderState {
        // 元のレンダラーの状態
        BlockEntityRenderState inner;
        SignImage image;
        float yaw;
        boolean wall;
        boolean spectator;
    }

    private static Field renderersField;

    private final BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    private SignRendererWrapper(BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?, ?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererWrapper) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> replaced = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?, ?>>(renderers);
        replaced.put(Mc.SIGN, new SignRendererWrapper((BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState>) current));
        Reflect.set(renderersField, dispatcher, replaced);
    }


    @Override
    public State createRenderState() {
        final State state = new State();
        state.inner = delegate.createRenderState();
        return state;
    }

    @Override
    public void extractRenderState(SignBlockEntity sign, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        delegate.extractRenderState(sign, state.inner, partialTicks, cameraPosition, breakProgress);
        BlockEntityRenderState.extractBase(sign, state, breakProgress);

        state.image = MarumaSign.sign(Mc.signText(sign));
        if (state.image == null) return;
        state.spectator = mc.player != null && mc.player.isSpectator();

        final BlockState blockState = sign.getBlockState();
        if (blockState.getBlock() instanceof WallSignBlock) {
            state.wall = true;
            state.yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            state.wall = false;
            state.yaw = RotationSegment.convertToDegrees(blockState.getValue(StandingSignBlock.ROTATION));
        } else {
            state.image = null;
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        final SignImage image = state.image;
        // スペクテイターには看板そのものも見せる
        if (image == null || state.spectator) {
            delegate.submit(state.inner, poseStack, collector, camera);
        }
        if (image == null) return;

        // 視錐台カリング
        final float[] bounds = image.bounds(state.yaw, state.wall);
        final BlockPos pos = state.blockPos;
        if (camera.cullFrustum != null && !camera.cullFrustum.isVisible(new AABB(
                pos.getX() + bounds[0], pos.getY() + bounds[1], pos.getZ() + bounds[2],
                pos.getX() + bounds[3], pos.getY() + bounds[4], pos.getZ() + bounds[5]))) {
            return;
        }

        final float[] mesh = image.mesh(state.yaw, state.wall);
        final int light = state.lightCoords;
        collector.submitCustomGeometry(poseStack, ((PlatformImpl.Texture) image.texture()).renderType, (pose, vertices) -> {
            for (int i = 0; i < SignImage.VERTEX_COUNT; i++) {
                final int o = i * SignImage.STRIDE;
                vertices.addVertex(pose, mesh[o], mesh[o + 1], mesh[o + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(mesh[o + 3], mesh[o + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, mesh[o + 5], mesh[o + 6], mesh[o + 7]);
            }
        });
        MarumaSign.drawn(image);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public boolean shouldRender(SignBlockEntity sign, Vec3 cameraPosition) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        if (image == null) return delegate.shouldRender(sign, cameraPosition);
        return Vec3.atCenterOf(sign.getBlockPos()).closerThan(cameraPosition, image.viewDistance());
    }
}
