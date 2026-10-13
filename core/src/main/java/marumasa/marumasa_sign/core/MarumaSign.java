package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignManager;
import marumasa.marumasa_sign.core.sign.SignSpec;
import marumasa.marumasa_sign.core.ui.UiRoot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minecraft のバージョンに依存しない、MarumaSign の本体。
 * 各バージョンの実装は init で Platform を渡し、毎 tick tick を呼ぶ。
 */
public final class MarumaSign {
    // ModのID
    public static final String MOD_ID = "marumasa_sign";
    // ロガー
    public static final Logger LOGGER = LogManager.getLogger("MarumaSign");

    private static Platform platform;
    private static Config config;
    private static ImageLoader loader;
    private static SignManager signs;
    private static UiRoot ui;
    private static SelfTest selfTest;

    // 看板に書き込む予定の文字列
    private static volatile String pendingWrite;
    // 読み込みが完了した画像を描画した回数 (動作確認用)
    private static volatile int loadedRenderCount;

    private MarumaSign() {
    }

    public static synchronized void init(Platform implementation) {
        if (platform != null) return;
        platform = implementation;
        config = new Config(implementation.gameDirectory());
        loader = new ImageLoader();
        signs = new SignManager(implementation, loader);
        ui = new UiRoot(implementation);
        selfTest = SelfTest.createIfRequested(implementation);
        LOGGER.info("Start: " + MOD_ID + " (Minecraft " + implementation.minecraftVersion() + ", " + implementation.loaderName() + ")");
    }

    public static boolean isInitialized() {
        return platform != null;
    }

    public static Platform platform() {
        return platform;
    }

    public static Config config() {
        return config;
    }

    public static SignManager signs() {
        return signs;
    }

    public static UiRoot ui() {
        return ui;
    }

    // クライアントの tick ごとに呼ぶ
    public static void tick() {
        if (platform == null) return;
        if (platform.isInWorld()) loader.pump(config.getMaxThreads());
        signs.tick();
        ui.tick();
        if (selfTest != null) selfTest.tick();
    }

    // 画面を開くキーが押されたとき
    public static void openMenu() {
        if (platform == null || ui.isOpen()) return;
        platform.openUi();
    }

    /**
     * 看板の文字列に対応する画像を返す。MarumaSign の看板でなければ null。
     */
    public static SignImage sign(String text) {
        return signs == null ? null : signs.get(text);
    }

    // 画像を実際に描画したときに呼ぶ (動作確認用の記録)
    public static void drawn(SignImage image) {
        if (image.isLoaded()) loadedRenderCount++;
    }

    // スレッドを問わず呼べる、MarumaSign の看板かどうかの判定
    public static boolean isSign(String text) {
        return SignSpec.isSign(text);
    }

    // ---------- 看板への書き込み ----------

    // 次に開かれた看板の編集画面に、文字列を自動で書き込むよう予約する
    public static void requestWrite(String signText) {
        pendingWrite = signText;
        chat("text.write_mode");
    }

    // チャットの色。Mod の名前はオレンジ、本文は黄緑で表示する
    private static final String CHAT_PREFIX = "§6[MarumaSign] §a";

    // 選択中の言語でチャットにメッセージを表示する
    public static void chat(String key) {
        Lang.select(platform.languageCode());
        platform.showMessage(CHAT_PREFIX + Lang.get(key));
    }

    public static boolean hasPendingWrite() {
        return pendingWrite != null;
    }

    // 予約されている文字列を看板 4 行分に分けて取り出す
    public static String[] takePendingWrite() {
        final String text = pendingWrite;
        pendingWrite = null;
        if (text == null) return null;
        final String[] lines = SignSpec.splitLines(text, platform.maxSignLineLength());
        if (lines != null) chat("text.write_done");
        return lines;
    }

    static int loadedRenderCount() {
        return loadedRenderCount;
    }
}
