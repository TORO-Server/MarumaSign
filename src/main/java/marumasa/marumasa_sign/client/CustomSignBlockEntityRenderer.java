package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.type.CustomSign;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.StandingSignRenderer;
import net.minecraft.client.renderer.blockentity.state.StandingSignRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;

public class CustomSignBlockEntityRenderer extends StandingSignRenderer {

    private final Minecraft client;

    public CustomSignBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        super(ctx);
        this.client = Minecraft.getInstance();
    }

    public static class CustomSignRenderState extends StandingSignRenderState {
        public CustomSign customSign;
        public boolean isSpectator;
        public BlockState blockState;
    }

    @Override
    public StandingSignRenderState createRenderState() {
        return new CustomSignRenderState();
    }

    @Override
    public void extractRenderState(
            final SignBlockEntity blockEntity,
            final StandingSignRenderState state,
            final float partialTicks,
            final Vec3 cameraPosition,
            final ModelFeatureRenderer.@org.jspecify.annotations.Nullable CrumblingOverlay breakProgress
    ) {
        super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);

        if (state instanceof CustomSignRenderState customState) {
            customState.customSign = CustomSignProvider.get(blockEntity);
            customState.blockState = blockEntity.getBlockState();
            final LocalPlayer player = client.player;
            customState.isSpectator = player != null && player.isSpectator();
        }
    }

    @Override
    public void submit(
            final StandingSignRenderState state,
            final PoseStack poseStack,
            final SubmitNodeCollector submitNodeCollector,
            final CameraRenderState camera
    ) {
        if (state instanceof CustomSignRenderState customState && customState.customSign != null) {
            if (customState.isSpectator) {
                super.submit(state, poseStack, submitNodeCollector, camera);
            }

            final BlockState blockState = customState.blockState;
            final Block block = blockState.getBlock();
            if (block instanceof SignBlock signBlock) {
                Quaternionf signRotationY = new Quaternionf();
                float rot;
                if (blockState.getBlock() instanceof WallSignBlock) {
                    rot = blockState.getValue(WallSignBlock.FACING).toYRot();
                } else {
                    rot = RotationSegment.convertToDegrees(blockState.getValue(StandingSignBlock.ROTATION));
                }
                signRotationY.fromAxisAngleDeg(0, 1, 0, -rot - 180);

                boolean isWallSignBlock = blockState.getBlock() instanceof WallSignBlock;

                // 視錐台カリングの実行
                final AABB signAABB = calculateSignAABB(customState.customSign, signRotationY, isWallSignBlock, state.blockPos);
                if (!camera.cullFrustum.isVisible(signAABB)) {
                    return;
                }

                render(customState.customSign, signRotationY, isWallSignBlock, poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY);
            }
            return;
        }

        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    private AABB calculateSignAABB(
            final CustomSign customSign,
            final Quaternionf signRotationY,
            final boolean isWallSignBlock,
            final BlockPos blockPos
    ) {
        float moveZ = isWallSignBlock ? 0.4375f * 2 : 0f;
        final CustomSign.Vertex ver = customSign.vertex;
        
        Vector3f[] vertices = new Vector3f[]{
                ver.mi_mi(),
                ver.mi_pl(),
                ver.pl_pl(),
                ver.pl_mi()
        };
        
        AABB.Builder builder = new AABB.Builder();
        for (Vector3f v : vertices) {
            Vector3f transformed = signRotationY.transform(v, new Vector3f());
            transformed.mul(0.5f);
            transformed.add(0.5f, 0.5f, 0.5f + moveZ);
            transformed.add((float) blockPos.getX(), (float) blockPos.getY(), (float) blockPos.getZ());
            builder.include(transformed);
        }
        return builder.build();
    }

    public void render(CustomSign customSign, Quaternionf signRotationY, boolean isWallSignBlock, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int light, int overlay) {
        float moveZ = isWallSignBlock ? 0.4375f * 2 : 0f;

        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.scale(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(signRotationY);

        final CustomSign.Vertex ver = customSign.vertex;
        final Vector3f mi_mi = ver.mi_mi();
        final Vector3f mi_pl = ver.mi_pl();
        final Vector3f pl_pl = ver.pl_pl();
        final Vector3f pl_mi = ver.pl_mi();

        submitNodeCollector.submitCustomGeometry(
                poseStack,
                customSign.getRenderLayer(),
                (pose, vertexConsumer) -> {
                    vertexConsumer.addVertex(pose, mi_mi.x, mi_mi.y, mi_mi.z + moveZ)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 1.0F, 0.0F);

                    vertexConsumer.addVertex(pose, mi_pl.x, mi_pl.y, mi_pl.z + moveZ)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 1.0F, 0.0F);

                    vertexConsumer.addVertex(pose, pl_pl.x, pl_pl.y, pl_pl.z + moveZ)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 1.0F, 0.0F);

                    vertexConsumer.addVertex(pose, pl_mi.x, pl_mi.y, pl_mi.z + moveZ)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 1.0F, 0.0F);
                }
        );
        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(final SignBlockEntity blockEntity, final Vec3 cameraPosition) {
        final CustomSign customSign = CustomSignProvider.get(blockEntity);
        if (customSign == null) {
            return Vec3.atCenterOf(blockEntity.getBlockPos()).closerThan(cameraPosition, 64.0);
        }
        return Vec3.atCenterOf(blockEntity.getBlockPos()).closerThan(cameraPosition, customSign.getViewDistance());
    }
}
