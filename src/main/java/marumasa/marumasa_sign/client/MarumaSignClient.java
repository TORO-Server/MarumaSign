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

import static net.minecraft.client.MinecraftClient.getInstance;

public class MarumaSignClient implements ClientModInitializer {

    // 看板のブロックエンティティタイプ 取得
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityType.SIGN;

    private static final MinecraftClient client = getInstance();

    @Override
    public void onInitializeClient() {
        // 看板のブロックエンティティのレンダラーを置き換える
        BlockEntityRendererFactories.register(signType, CustomSignBlockEntityRenderer::new);

        loop.start();
        SignWriteManager.init();

        // キーバインド登録
        KeyBinding open_menu = KeyBindingHelper.registerKeyBinding(
                Utils.createKeyBinding("open_menu", GLFW.GLFW_KEY_B)
        );

        // クライアントティックイベントにキーバインドの処理を登録
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            // もしキーが押されたら
            while (open_menu.wasPressed()) {
                Text text = Text.translatable("text.maruma_sign.open_menu");
                client.player.sendMessage(text, false);
                ServerManager.openMenu();
            }
        });

        // キーバインド登録
        KeyBinding binding2 = KeyBindingHelper.registerKeyBinding(
                Utils.createKeyBinding("remove_cache", GLFW.GLFW_KEY_M)
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            // もしキーが押されたら
            while (binding2.wasPressed()) {
                getInstance().setScreen(new ConfigScreen(getInstance().currentScreen));
            }
        });
    }

    private static class loop {
        public static void start() {
            // 10ms timer
            new Timer().schedule(new loopGifPlayer(), 0, 10);
            // 100ms timer
            new Timer().schedule(new loopImageRequest(MarumaSign.CONFIG), 0, 100);
        }

        private static class loopGifPlayer extends TimerTask {
            @Override
            public void run() {
                if (client.world == null || client.getBlockRenderManager() == null) return;
                GifPlayer.load();
            }
        }

        private static class loopImageRequest extends TimerTask {

            private final Config config;

            public loopImageRequest(Config config) {
                this.config = config;
            }

            @Override
            public void run() {
                if (client.world == null || client.getBlockRenderManager() == null) return;
                ImageRequest.load(config.getMaxThreads());
            }
        }
    }
}