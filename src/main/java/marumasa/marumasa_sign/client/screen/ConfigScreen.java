package marumasa.marumasa_sign.client.screen;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.util.ImageRequest;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import static marumasa.marumasa_sign.MarumaSign.CONFIG;
import static net.minecraft.client.MinecraftClient.getInstance;

public class ConfigScreen extends Screen {
    // 親スクリーン取得 (開く前のスクリーン)
    private final Screen parent;

    public ConfigScreen(Screen parent) {
        // ナレーションテキスト追加
        super(Text.literal("MarumaSign Config Screen"));
        // 親スクリーン設定
        this.parent = parent;
    }

    // 各ウィジェット定義
    public ButtonWidget button_remove_cache; // キャッシュ削除ボタン
    public ButtonWidget asyncload_add; // 非同期ロード数増加ボタン
    public ButtonWidget asyncload_pull; // 非同期ロード数減少ボタン
    public ButtonWidget close_configscreen; // 閉じるボタン

    @Override
    protected void init() {
        button_remove_cache = ButtonWidget.builder(Text.translatable("key.marumasa_sign.remove_cache"), button -> {
                    // MarumaSignClient.java から丸パクリ
                    if (ImageRequest.queueSize() != 0) return;
                    CustomSignProvider.removeCache();
                    getInstance().player.sendMessage(Text.translatable("text.maruma_sign.remove_cache"), false);
                    getInstance().setScreen(parent);
                })
                // レンダリング設定
                .dimensions(width / 2 - 105, 20, 200, 20)
                // tooltip設定
                .tooltip(Tooltip.of(Text.translatable("text.maruma_sign.remove_cache_tooltip")))
                .build();
        asyncload_add = ButtonWidget.builder(Text.literal("+"), button -> {
                    // 画像の同時ロード数 を "1" 追加する
                    CONFIG.addMaxThreads(1);
                })
                // レンダリング設定
                .dimensions(width / 2 - 105, 60, 20, 20)
                .build();
        asyncload_pull = ButtonWidget.builder(Text.literal("-"), button -> {
                    // 1未満にならないようにチェック
                    if (CONFIG.getMaxThreads() > 1) {
                        // 画像の同時ロード数 を "-1" 追加する
                        CONFIG.addMaxThreads(-1);
                    }
                })
                // レンダリング設定
                .dimensions(width / 2 - 45, 60, 20, 20)
                .build();
        close_configscreen = ButtonWidget.builder(Text.translatable("text.maruma_sign.close"), button -> getInstance().setScreen(parent))
                // レンダリング設定
                .dimensions(width / 2 - 105, height - 20, 200, 20)
                .build();

        // 選択可能状態でレンダリング
        addDrawableChild(button_remove_cache);
        addDrawableChild(asyncload_add);
        addDrawableChild(asyncload_pull);
        addDrawableChild(close_configscreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        // 非同期処理数 ラベルレンダリング
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("text.maruma_sign.asyncprocessnum"), width / 2 - 155, 65, 0xffffff);
        // 非同期処理数 数字レンダリング
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(CONFIG.getMaxThreads() + ""), width / 2 - 75, 65, 0xffffff);
    }

}
