package marumasa.marumasa_sign.client;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class CustomSignBlockEntityRenderer extends SignBlockEntityRenderer {

    private final MinecraftClient client;

    public CustomSignBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
        super(ctx);
        client = MinecraftClient.getInstance();
    }

    // renderメソッドをオーバーライドして レンダリング処理を変更する
    @Override
    public void render(SignBlockEntity sign, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {

        CustomSign customSign = CustomSignProvider.get(sign);

        if (customSign == null) {
            // 通常のMinecraft看板をレンダリングするために
            // 親クラスのrenderメソッドを呼び出して 看板のレンダリング処理をする
            super.render(sign, tickDelta, matrices, vertexConsumers, light, overlay);
            return;
        }

        // クライアントプレイヤー 取得
        final ClientPlayerEntity clientPlayer = client.player;

        // もし プレイヤーがスペクテイターモードだったら
        // 通常のMinecraft看板 も レンダリングするようにする
        if (clientPlayer != null && clientPlayer.isSpectator()) {
            // 親クラスのrenderメソッドを呼び出して 看板のレンダリング処理をする
            super.render(sign, tickDelta, matrices, vertexConsumers, light, overlay);
        }

        // BlockState 取得
        final BlockState blockState = sign.getCachedState();

        final Block block = blockState.getBlock();

        if (block instanceof AbstractSignBlock signBlock) {

            // 看板の Y 軸の 回転を設定するための クォータニオン 作成
            Quaternionf signRotationY = new Quaternionf();
            // 看板の Y 軸に どれくらい回転するか 設定
            signRotationY.fromAxisAngleDeg(0, 1, 0,
                    -signBlock.getRotationDegrees(blockState) - 180
            );

            boolean isWallSignBlock = block instanceof WallSignBlock;

            // 看板URLから画像をレンダリングする
            render(customSign, signRotationY, isWallSignBlock, matrices, vertexConsumers, light, overlay);
        }
    }

    public void render(CustomSign customSign, Quaternionf signRotationY, boolean isWallSignBlock, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {

        float moveZ = isWallSignBlock ? 0.4375f * 2 : 0f;

        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(customSign.renderLayer);

        matrices.translate(0.5f, 0.5f, 0.5f); // 位置を設定
        matrices.scale(0.5f, 0.5f, 0.5f); // 大きさを設定
        matrices.multiply(customSign.rotation); // 回転を設定

        // 看板の回転 設定
        matrices.multiply(signRotationY);

        final MatrixStack.Entry peek = matrices.peek();
        final Matrix4f matrix4f = peek.getPositionMatrix();
        final Matrix3f matrix3f = peek.getNormalMatrix();
        final CustomSign.Vertex vertex = customSign.vertex;

        // 描画処理 開始
        matrices.push();
        vertexConsumer.vertex(matrix4f,
                vertex.minusX(), vertex.minusY(), vertex.Z() + moveZ
        ).color(255, 255, 255, 255).texture(1, 1).overlay(overlay).light(light).normal(matrix3f, 0.0F, 1.0F, 0.0F).next();
        vertexConsumer.vertex(matrix4f,
                vertex.minusX(), vertex.plusY(), vertex.Z() + moveZ
        ).color(255, 255, 255, 255).texture(1, 0).overlay(overlay).light(light).normal(matrix3f, 0.0F, 1.0F, 0.0F).next();
        vertexConsumer.vertex(matrix4f,
                vertex.plusX(), vertex.plusY(), vertex.Z() + moveZ
        ).color(255, 255, 255, 255).texture(0, 0).overlay(overlay).light(light).normal(matrix3f, 0.0F, 1.0F, 0.0F).next();
        vertexConsumer.vertex(matrix4f,
                vertex.plusX(), vertex.minusY(), vertex.Z() + moveZ
        ).color(255, 255, 255, 255).texture(0, 1).overlay(overlay).light(light).normal(matrix3f, 0.0F, 1.0F, 0.0F).next();
        matrices.pop();
        // 描画処理 終了
    }


    @Override
    // ここで ブロックエンティティの表示範囲を設定できる
    public int getRenderDistance() {
        return 512;
    }

    @Override
    // 看板が通常では見えなくなる範囲になってもレンダリングをする
    public boolean rendersOutsideBoundingBox(SignBlockEntity blockEntity) {
        return true;
    }
}
