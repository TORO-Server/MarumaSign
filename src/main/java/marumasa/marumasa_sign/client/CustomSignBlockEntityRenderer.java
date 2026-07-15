package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.model.CustomSign;
import marumasa.marumasa_sign.model.CustomSignHolder;
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
        public final Quaternionf signRotationY = new Quaternionf();
        public boolean isWallSignBlock;
        public AABB signAABB;
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
            final CustomSign customSign = CustomSignProvider.get(blockEntity);
            customState.customSign = customSign;
            final BlockState blockState = blockEntity.getBlockState();
            customState.blockState = blockState;
            final LocalPlayer player = client.player;
            customState.isSpectator = player != null && player.isSpectator();

            if (customSign != null) {
                final Block block = blockState.getBlock();
                if (block instanceof SignBlock) {
                    final boolean isWallSign = block instanceof WallSignBlock;
                    customState.isWallSignBlock = isWallSign;

                    float rot;
                    if (isWallSign) {
                        rot = blockState.getValue(WallSignBlock.FACING).toYRot();
                    } else {
                        rot = RotationSegment.convertToDegrees(blockState.getValue(StandingSignBlock.ROTATION));
                    }
                    customState.signRotationY.fromAxisAngleDeg(0, 1, 0, -rot - 180);

                    final CustomSignHolder holder = (CustomSignHolder) blockEntity;
                    AABB aabb = holder.marumasa$getCustomSignAABB();
                    if (aabb == null) {
                        aabb = calculateSignAABB(customSign, customState.signRotationY, isWallSign, blockEntity.getBlockPos());
                        holder.marumasa$setCustomSignAABB(aabb);
                    }
                    customState.signAABB = aabb;
                }
            }
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

            // 視錐台カリングの実行
            if (customState.signAABB != null && !camera.cullFrustum.isVisible(customState.signAABB)) {
                return;
            }

            render(customState.customSign, customState.signRotationY, customState.isWallSignBlock, poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY);
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
        
        final Vector3f temp = new Vector3f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        final float px = (float) blockPos.getX();
        final float py = (float) blockPos.getY();
        final float pz = (float) blockPos.getZ();

        // 1: mi_mi
        signRotationY.transform(ver.mi_mi(), temp);
        float x = temp.x * 0.5f + 0.5f + px;
        float y = temp.y * 0.5f + 0.5f + py;
        float z = temp.z * 0.5f + 0.5f + moveZ + pz;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;

        // 2: mi_pl
        signRotationY.transform(ver.mi_pl(), temp);
        x = temp.x * 0.5f + 0.5f + px;
        y = temp.y * 0.5f + 0.5f + py;
        z = temp.z * 0.5f + 0.5f + moveZ + pz;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;

        // 3: pl_pl
        signRotationY.transform(ver.pl_pl(), temp);
        x = temp.x * 0.5f + 0.5f + px;
        y = temp.y * 0.5f + 0.5f + py;
        z = temp.z * 0.5f + 0.5f + moveZ + pz;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;

        // 4: pl_mi
        signRotationY.transform(ver.pl_mi(), temp);
        x = temp.x * 0.5f + 0.5f + px;
        y = temp.y * 0.5f + 0.5f + py;
        z = temp.z * 0.5f + 0.5f + moveZ + pz;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
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
                    // Front face
                    vertexConsumer.addVertex(pose, mi_mi.x, mi_mi.y, mi_mi.z + moveZ + 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, 1.0F);

                    vertexConsumer.addVertex(pose, mi_pl.x, mi_pl.y, mi_pl.z + moveZ + 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, 1.0F);

                    vertexConsumer.addVertex(pose, pl_pl.x, pl_pl.y, pl_pl.z + moveZ + 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, 1.0F);

                    vertexConsumer.addVertex(pose, pl_mi.x, pl_mi.y, pl_mi.z + moveZ + 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, 1.0F);

                    // Back face
                    vertexConsumer.addVertex(pose, pl_mi.x, pl_mi.y, pl_mi.z + moveZ - 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, -1.0F);

                    vertexConsumer.addVertex(pose, pl_pl.x, pl_pl.y, pl_pl.z + moveZ - 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(0, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, -1.0F);

                    vertexConsumer.addVertex(pose, mi_pl.x, mi_pl.y, mi_pl.z + moveZ - 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 0)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, -1.0F);

                    vertexConsumer.addVertex(pose, mi_mi.x, mi_mi.y, mi_mi.z + moveZ - 0.005F)
                            .setColor(255, 255, 255, 255)
                            .setUv(1, 1)
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(pose, 0.0F, 0.0F, -1.0F);
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
