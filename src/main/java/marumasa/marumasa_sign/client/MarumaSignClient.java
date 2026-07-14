package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.Config;
import marumasa.marumasa_sign.MarumaSign;
import marumasa.marumasa_sign.client.screen.ConfigScreen;
import marumasa.marumasa_sign.client.sign.SignWriteManager;
import marumasa.marumasa_sign.http.ServerManager;
import marumasa.marumasa_sign.util.GifPlayer;
import marumasa.marumasa_sign.util.ImageRequest;
import marumasa.marumasa_sign.util.Utils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import static net.minecraft.client.Minecraft.getInstance;

public class MarumaSignClient implements ClientModInitializer {

    // 看板のブロックエンティティタイプ 取得
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityTypes.SIGN;

    private static final Minecraft client = getInstance();

    @Override
    public void onInitializeClient() {
        // 看板のブロックエンティティのレンダラーを置き換える
        BlockEntityRenderers.register(signType, CustomSignBlockEntityRenderer::new);

        loop.start();
        SignWriteManager.init();

        // キーバインド登録
        KeyMapping open_menu = KeyMappingHelper.registerKeyMapping(
                Utils.createKeyBinding("open_menu", GLFW.GLFW_KEY_B)
        );

        // クライアントティックイベントにキーバインドの処理を登録
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            // もしキーが押されたら
            while (open_menu.consumeClick()) {
                Component text = Component.translatable("text.maruma_sign.open_menu");
                client.player.sendSystemMessage(text);
                ServerManager.openMenu();
            }
        });

        // キーバインド登録
        KeyMapping binding2 = KeyMappingHelper.registerKeyMapping(
                Utils.createKeyBinding("remove_cache", GLFW.GLFW_KEY_M)
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            // もしキーが押されたら
            while (binding2.consumeClick()) {
                client.gui.setScreen(new ConfigScreen(client.gui.screen()));
            }
        });
    }

    private static class loop {
        public static void start() {
            // 10ms timer
            new java.util.Timer(true).schedule(new loopGifPlayer(), 0, 10);
            // 100ms timer
            new java.util.Timer(true).schedule(new loopImageRequest(MarumaSign.CONFIG), 0, 100);
        }

        private static class loopGifPlayer extends java.util.TimerTask {
            @Override
            public void run() {
                if (client.level == null) return;
                client.execute(GifPlayer::load);
            }
        }

        private static class loopImageRequest extends java.util.TimerTask {

            private final Config config;

            public loopImageRequest(Config config) {
                this.config = config;
            }

            @Override
            public void run() {
                if (client.level == null) return;
                client.execute(() -> ImageRequest.load(config.getMaxThreads()));
            }
        }
    }
}
