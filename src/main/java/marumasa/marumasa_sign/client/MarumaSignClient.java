package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.util.GifPlayer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

import java.util.Timer;

public class MarumaSignClient implements ClientModInitializer {

    // 看板のブロックエンティティタイプ 取得
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityType.SIGN;

    @Override
    public void onInitializeClient() {
        // 看板のブロックエンティティのレンダラーを置き換える
        BlockEntityRendererFactories.register(signType, CustomSignBlockEntityRenderer::new);

        Timer timer = new Timer();
        timer.schedule(new GifPlayer(), 0, 10);
    }
}
