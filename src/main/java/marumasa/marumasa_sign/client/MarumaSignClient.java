package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.util.GifPlayer;
import marumasa.marumasa_sign.util.ImageRequest;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

import java.util.Timer;
import java.util.TimerTask;

public class MarumaSignClient implements ClientModInitializer {

    // 看板のブロックエンティティタイプ 取得
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityType.SIGN;

    private static final MinecraftClient client = MinecraftClient.getInstance();

    @Override
    public void onInitializeClient() {
        // 看板のブロックエンティティのレンダラーを置き換える
        BlockEntityRendererFactories.register(signType, CustomSignBlockEntityRenderer::new);

        Timer timer = new Timer();
        timer.schedule(new loop(), 0, 10);
    }

    private static class loop extends TimerTask {
        @Override
        public void run() {
            if (client.world == null) return;
            GifPlayer.load();
            ImageRequest.load();
        }
    }
}
