package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 * render の引数はバージョンによって違うため、SignRendererWrapper 側で実装する。
 */
abstract class SignRendererBase implements BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    protected final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    protected SignRendererBase(BlockEntityRenderer<SignBlockEntity> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererBase) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> replaced = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?>>(renderers);
        replaced.put(Mc.SIGN, new SignRendererWrapper((BlockEntityRenderer<SignBlockEntity>) current));
        Reflect.set(renderersField, dispatcher, replaced);
    }

    /**
     * MarumaSign の看板なら画像を描く。
     * 看板そのものも描くべきなら true を返す (MarumaSign の看板でないとき、またはスペクテイターのとき)。
     */
    protected final boolean drawImage(SignBlockEntity sign, PoseStack poseStack, MultiBufferSource buffers, int light) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        if (image == null) return true;

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
            return true;
        }

        final BlockPos pos = sign.getBlockPos();
        for (SignImage.Batch batch : image.batches(yaw, wall, distance(sign), pos.getX(), pos.getY(), pos.getZ())) {
            Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
        }
        MarumaSign.drawn(image);
        return mc.player != null && mc.player.isSpectator();
    }

    // カメラから看板までの距離 (ブロック)
    private double distance(SignBlockEntity sign) {
        final Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        final double x = sign.getBlockPos().getX() + 0.5 - camera.x;
        final double y = sign.getBlockPos().getY() + 0.5 - camera.y;
        final double z = sign.getBlockPos().getZ() + 0.5 - camera.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    // 以下のメソッドはバージョンによって有無や引数が違う。
    // 両方を用意しておき、そのバージョンにあるほうが使われる (そのため @Override は付けない)

    public boolean shouldRenderOffScreen() {
        return true;
    }

    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }

    public int getViewDistance() {
        return 512;
    }

    public boolean shouldRender(SignBlockEntity sign, Vec3 cameraPosition) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        final double distance = image == null ? 64.0 : image.viewDistance();
        final double x = sign.getBlockPos().getX() + 0.5 - cameraPosition.x;
        final double y = sign.getBlockPos().getY() + 0.5 - cameraPosition.y;
        final double z = sign.getBlockPos().getZ() + 0.5 - cameraPosition.z;
        return x * x + y * y + z * z < distance * distance;
    }
}
