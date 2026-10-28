package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.image.ImageLoader;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignManager;
import marumasa.marumasa_sign.core.sign.SignSpec;
import marumasa.marumasa_sign.core.sign.SignText;
import marumasa.marumasa_sign.core.ui.UiRoot;

/**
 * Minecraft のバージョンに依存しない、MarumaSign の本体。
 * 各バージョンの実装は init で Platform を渡し、毎 tick tick を呼ぶ。
 */
public final class MarumaSign {
    // ModのID
    public static final String MOD_ID = "marumasa_sign";
    // ロガー。記録する文字は無害にしてから書く (SafeLog)
    public static final SafeLog LOGGER = new SafeLog("MarumaSign");

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

    public static ImageLoader loader() {
        return loader;
    }

    // クライアントの tick ごとに呼ぶ
    // 消すのを待っているテクスチャ ({実装, テクスチャ, 残りの tick 数})
    private static final java.util.List<Object[]> pendingReleases = new java.util.ArrayList<Object[]>();
    private static final int RELEASE_DELAY = 20;

    /**
     * テクスチャを、少し後で (約 1 秒後に) 消す。
     * 影 Mod (Iris / Oculus) は、直前まで使われていたテクスチャを、描画の状態を元に戻すときにもう一度使うことがある。
     * 使い終わった直後に消すと、存在しないテクスチャを使うことになって OpenGL のエラーが出るので、すぐには消さない。
     */
    public static void releaseTexture(Platform owner, Object texture) {
        if (texture == null) return;
        synchronized (pendingReleases) {
            pendingReleases.add(new Object[] {owner, texture, RELEASE_DELAY});
        }
    }

    private static void releasePending() {
        final java.util.List<Object[]> due = new java.util.ArrayList<Object[]>();
        synchronized (pendingReleases) {
            for (java.util.Iterator<Object[]> it = pendingReleases.iterator(); it.hasNext(); ) {
                final Object[] item = it.next();
                final int left = (Integer) item[2] - 1;
                item[2] = left;
                if (left <= 0) {
                    due.add(item);
                    it.remove();
                }
            }
        }
        for (Object[] item : due) {
            ((Platform) item[0]).releaseTexture(item[1]);
        }
    }

    public static void tick() {
        if (platform == null) return;
        // tick の中の例外が外に出ると、古いバージョンではゲームが落ちる。1 つの処理が失敗しても、ほかの処理は続ける
        try {
            releasePending();
        } catch (RuntimeException e) {
            reportOnce("releasing textures", e);
        }
        try {
            if (platform.isInWorld()) loader.pump(config.getMaxThreads());
            signs.tick();
        } catch (RuntimeException e) {
            reportOnce("updating signs", e);
        }
        try {
            ui.tick();
        } catch (RuntimeException e) {
            reportOnce("updating the window", e);
        }
        if (selfTest != null) selfTest.tick();
    }

    // 記録した例外の目印 (場所、種類、投げた位置)。毎 tick や毎フレーム同じ例外が出ても、記録は 1 回だけにする
    private static final java.util.Set<String> reported = new java.util.HashSet<String>();

    /**
     * ゲームから呼ばれる処理 (tick、描画、入力) の中で出た例外を記録する。同じものは 1 回だけ記録する。
     * 各バージョンの実装も、ゲームに例外を渡さないために使う。
     *
     * @param where 何をしているときか (記録に書く)
     */
    public static void reportOnce(String where, Throwable error) {
        final StackTraceElement[] trace = error.getStackTrace();
        final String key = where + "|" + error.getClass().getName() + "|" + (trace.length > 0 ? trace[0] : "");
        synchronized (reported) {
            // 種類が増え続けても、記録の目印でメモリを使い切らないようにする
            if (reported.size() >= 256) reported.clear();
            if (!reported.add(key)) return;
        }
        LOGGER.error("Error while " + where, error);
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
        SignImage image = null;
        try {
            image = signs == null ? null : signs.get(text);
        } catch (RuntimeException e) {
            // 誰でも書ける看板の文字のせいで、描画の途中でゲームが落ちないようにする
            reportOnce("looking up a sign", e);
        }
        if (selfTest != null) selfTest.onLookup(image != null);
        return image;
    }

    // 画像を実際に描画したときに呼ぶ (動作確認用の記録)
    /**
     * アイテムのデータに保存されている看板の文字から、表示する画像を返す。MarumaSign の看板でなければ null。
     * 行ごとのデータは、文字そのままでも、テキストコンポーネント (JSON) でもよい。
     */
    public static SignImage itemSign(String[] rawLines) {
        if (signs == null || rawLines == null) return null;
        final StringBuilder text = new StringBuilder();
        try {
            for (String raw : rawLines) {
                text.append(SignText.plain(raw));
            }
            return signs.get(text.toString());
        } catch (RuntimeException e) {
            reportOnce("looking up a sign item", e);
            return null;
        }
    }

    public static void drawn(SignImage image) {
        if (!image.isLoaded()) return;
        loadedRenderCount++;
        if (selfTest != null) selfTest.onDrawn(image);
    }

    // スレッドを問わず呼べる、MarumaSign の看板かどうかの判定
    public static boolean isSign(String text) {
        return SignSpec.isSign(text);
    }

    // ---------- 使用キー (右クリック) ----------

    /**
     * 使用キーがクリックされたときに、ゲームが処理する前に呼ぶ。
     * 照準が PDF の看板に合っていればページ送りに使い、true を返す。
     * そのときはゲームにクリックを渡さず、キーも離されたことにする (押し続けてブロックを置いたりしないように)。
     */
    public static boolean useClicked() {
        try {
            return signs != null && signs.pdfControls().click();
        } catch (RuntimeException e) {
            // 失敗したときは、クリックをゲームに渡す
            reportOnce("turning a page", e);
            return false;
        }
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

    // 看板に書き込む予定の文字列。なければ null
    static String pendingWriteText() {
        return pendingWrite;
    }

    static int loadedRenderCount() {
        return loadedRenderCount;
    }
}
