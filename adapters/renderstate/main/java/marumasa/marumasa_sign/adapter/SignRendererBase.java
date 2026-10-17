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
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 * submit の引数の型はバージョンによって違うため、SignRendererWrapper 側で実装する。
 */
abstract class SignRendererBase implements BlockEntityRenderer<SignBlockEntity, SignRendererBase.State> {

    static final class State extends BlockEntityRenderState {
        // 元のレンダラーの状態
        BlockEntityRenderState inner;
        SignImage image;
        float yaw;
        boolean wall;
        boolean spectator;
        // カメラから看板までの距離 (ブロック)
        double distance;
        // ブロックの角を原点としたカメラの位置
        float cameraX, cameraY, cameraZ;
    }

    private static Field renderersField;

    protected final BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    protected SignRendererBase(BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        SignBoards.install(mc);
        final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?, ?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererBase) return;

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
        state.spectator = mc.player != null && mc.player.isSpectator();
        // スペクテイターには看板そのものも見せる
        SignBoards.track(sign, state.image != null && !state.spectator);
        if (state.image == null) return;
        final BlockPos pos = sign.getBlockPos();
        state.distance = Math.sqrt(pos.distToCenterSqr(cameraPosition));
        state.cameraX = (float) (cameraPosition.x - pos.getX());
        state.cameraY = (float) (cameraPosition.y - pos.getY());
        state.cameraZ = (float) (cameraPosition.z - pos.getZ());

        final BlockState blockState = sign.getBlockState();
        if (blockState.getBlock() instanceof WallSignBlock) {
            state.wall = true;
            state.yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            state.wall = false;
            state.yaw = blockState.getValue(StandingSignBlock.ROTATION) * 22.5f;
        } else {
            state.image = null;
        }
    }

    // 画像を描く。MarumaSign の看板でなければ何もしない
    protected final void submitImage(State state, PoseStack poseStack, SubmitNodeCollector collector, Frustum frustum) {
        final SignImage image = state.image;
        if (image == null) return;

        // 視錐台カリング
        final float[] bounds = image.bounds(state.yaw, state.wall);
        final BlockPos pos = state.blockPos;
        if (frustum != null && !frustum.isVisible(new AABB(
                pos.getX() + bounds[0], pos.getY() + bounds[1], pos.getZ() + bounds[2],
                pos.getX() + bounds[3], pos.getY() + bounds[4], pos.getZ() + bounds[5]))) {
            return;
        }

        submitBatches(image.batches(state.yaw, state.wall, state.distance, state.cameraX, state.cameraY, state.cameraZ),
                poseStack, collector, state.lightCoords);
        MarumaSign.drawn(image);
    }

    /**
     * 四角形のまとまりを、現在の座標系に描く。看板のアイテムを描くときにも使う。
     */
    static void submitBatches(SignImage.Batch[] batches, PoseStack poseStack, SubmitNodeCollector collector, final int light) {
        for (final SignImage.Batch batch : batches) {
            collector.submitCustomGeometry(poseStack, ((Tex) batch.texture).renderType, (pose, vertices) -> {
                final float[] mesh = batch.vertices;
                final int[] colors = batch.colors;
                for (int i = 0; i < batch.vertexCount; i++) {
                    final int o = i * SignImage.Batch.STRIDE;
                    final int color = colors == null ? 0xFFFFFFFF : colors[i];
                    vertices.addVertex(pose, mesh[o], mesh[o + 1], mesh[o + 2])
                            .setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24)
                            .setUv(mesh[o + 3], mesh[o + 4])
                            .setLight(light);
                }
            });
        }
    }

    // 看板そのものを描くかどうか。スペクテイターには看板も見せる
    protected static boolean drawsVanilla(State state) {
        return state.image == null || state.spectator;
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
