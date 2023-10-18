package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.util.GifPlayer;
import marumasa.marumasa_sign.util.ImageRequest;
import marumasa.marumasa_sign.util.Utils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

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

        loop.start();

        // キーバインド登録
        KeyBinding binding2 = KeyBindingHelper.registerKeyBinding(Utils.createKeyBinding("remove_cache", GLFW.GLFW_KEY_M));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            // もしキーが押されたら
            while (binding2.wasPressed()) {
                if (ImageRequest.queueSize() != 0) return;
                CustomSignProvider.removeCache();
                client.player.sendMessage(Text.translatable("text.maruma_sign.remove_cache"), false);
            }
        });
    }

    private static class loop {
        public static void start() {
            Timer timer = new Timer();
            // 10ms timer
            timer.schedule(new loopGifPlayer(), 0, 10);
            // 100ms timer
            timer.schedule(new loopImageRequest(), 0, 100);
        }

        private static class loopGifPlayer extends TimerTask {
            @Override
            public void run() {
                if (client.world == null || client.getBlockRenderManager() == null) return;
                GifPlayer.load();
            }
        }

        public static class loopImageRequest extends TimerTask {
            @Override
            public void run() {
                if (client.world == null || client.getBlockRenderManager() == null) return;
                ImageRequest.load();
            }
        }
    }
}
