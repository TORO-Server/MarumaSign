package marumasa.marumasa_sign.client.screen;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.util.ImageRequest;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

import java.util.Objects;

import static marumasa.marumasa_sign.MarumaSign.CONFIG;

public class ConfigScreen extends Screen {
    // 親スクリーン取得 (開く前のスクリーン)
    private final Screen parent;

    public ConfigScreen(Screen parent) {
        // ナレーションテキスト追加
        super(Component.literal("MarumaSign Config Screen"));
        // 親スクリーン設定
        this.parent = parent;
    }

    // 各ウィジェット定義
    public Button button_remove_cache; // キャッシュ削除ボタン
    public Button asyncload_add; // 非同期ロード数増加ボタン
    public Button asyncload_pull; // 非同期ロード数減少ボタン
    public Button close_configscreen; // 閉じるボタン

    @Override
    protected void init() {
        Minecraft client = Minecraft.getInstance();

        button_remove_cache = Button.builder(Component.translatable("key.marumasa_sign.remove_cache"), button -> {
                    // MarumaSignClient.java から丸パクリ
                    if (ImageRequest.queueSize() != 0) return;
                    CustomSignProvider.removeCache();
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.translatable("text.maruma_sign.remove_cache"));
                    }
                    client.gui.setScreen(parent);
                })
                // レンダリング設定
                .bounds(width / 2 - 105, 20, 200, 20)
                // tooltip設定
                .tooltip(Tooltip.create(Component.translatable("text.maruma_sign.remove_cache_tooltip")))
                .build();
        asyncload_add = Button.builder(Component.literal("+"), button -> {
                    // 画像の同時ロード数 を "1" 追加する (最大32)
                    if (CONFIG.getMaxThreads() < 32) {
                        CONFIG.addMaxThreads(1);
                    }
                })
                // レンダリング設定
                .bounds(width / 2 - 105, 60, 20, 20)
                .build();
        asyncload_pull = Button.builder(Component.literal("-"), button -> {
                    // 1未満にならないようにチェック
                    if (CONFIG.getMaxThreads() > 1) {
                        // 画像の同時ロード数 を "-1" 追加する
                        CONFIG.addMaxThreads(-1);
                    }
                })
                // レンダリング設定
                .bounds(width / 2 - 45, 60, 20, 20)
                .build();
        close_configscreen = Button.builder(Component.translatable("text.maruma_sign.close"), button -> client.gui.setScreen(parent))
                // レンダリング設定
                .bounds(width / 2 - 105, height - 20, 200, 20)
                .build();

        // 選択可能状態でレンダリング
        addRenderableWidget(button_remove_cache);
        addRenderableWidget(asyncload_add);
        addRenderableWidget(asyncload_pull);
        addRenderableWidget(close_configscreen);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, int mouseX, int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        // 非同期処理数 ラベルレンダリング
        graphics.centeredText(font, Component.translatable("text.maruma_sign.asyncprocessnum"), width / 2 - 155, 65, 0xffffff);
        // 非同期処理数 数字レンダリング
        graphics.centeredText(font, Component.literal(String.valueOf(CONFIG.getMaxThreads())), width / 2 - 75, 65, 0xffffff);
    }

}
