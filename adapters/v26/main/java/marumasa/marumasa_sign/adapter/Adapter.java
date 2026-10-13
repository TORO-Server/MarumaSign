package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import java.lang.reflect.Field;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft 26.x 向けの実装の入口。バージョンによって違う部分は Mc クラスにまとめてある。
 * ローダーの API を使わずに済むよう、タイマーから定期的にメインスレッドへ処理を渡している。
 */
public final class Adapter {

    // 処理を呼ぶ間隔 (ミリ秒)。ゲームの 1 tick と同じ
    private static final long INTERVAL = 50;
    // GLFW の B キー
    private static final int KEY_B = 66;

    private static final AtomicBoolean started = new AtomicBoolean();
    // メインスレッドに渡した処理がまだ実行されていないかどうか
    private static final AtomicBoolean pending = new AtomicBoolean();

    private static String loaderName;
    // この実装が core を初期化したかどうか (他の経路で既に起動していれば false のまま)
    private static boolean active;
    private static boolean initialized;
    private static KeyMapping openMenu;
    private static Field signMessages;

    private Adapter() {
    }

    // loader: 起動したローダーの名前
    public static void start(String loader) {
        if (!started.compareAndSet(false, true)) return;
        loaderName = loader;
        new Timer("MarumaSign-Tick", true).schedule(new TimerTask() {
            @Override
            public void run() {
                final Minecraft mc = Minecraft.getInstance();
                if (mc == null || !pending.compareAndSet(false, true)) return;
                mc.execute(Adapter::tick);
            }
        }, INTERVAL, INTERVAL);
    }

    private static void tick() {
        pending.set(false);
        final Minecraft mc = Minecraft.getInstance();
        if (!initialized) {
            // 設定の読み込みが終わるまで待つ
            if (mc.options == null || mc.getWindow() == null) return;
            initialized = true;
            if (MarumaSign.isInitialized()) return;
            MarumaSign.init(new PlatformImpl(loaderName));
            active = true;
            registerKey(mc);
        }
        if (!active) return;

        SignRendererWrapper.install(mc);
        MarumaSign.tick();

        while (openMenu.consumeClick()) {
            if (mc.player != null && Mc.screen(mc) == null) MarumaSign.openMenu();
        }

        // 看板の編集画面が開かれたら、予約されている文字列を書き込んで閉じる
        final Screen screen = Mc.screen(mc);
        if (MarumaSign.hasPendingWrite() && screen instanceof AbstractSignEditScreen) {
            writeSign(mc, (AbstractSignEditScreen) screen);
        }
    }

    // キーバインド登録
    private static void registerKey(Minecraft mc) {
        openMenu = Mc.newKey("MarumaSign", KEY_B);
        final KeyMapping[] current = mc.options.keyMappings;
        final KeyMapping[] extended = new KeyMapping[current.length + 1];
        System.arraycopy(current, 0, extended, 0, current.length);
        extended[current.length] = openMenu;
        Reflect.set(Reflect.fieldHolding(mc.options, current), mc.options, extended);
        // 保存されているキーの割り当てを読み直す
        mc.options.load();
        KeyMapping.resetMapping();
    }

    private static void writeSign(Minecraft mc, AbstractSignEditScreen screen) {
        if (signMessages == null) signMessages = Reflect.fieldOfType(AbstractSignEditScreen.class, String[].class);
        final String[] messages = Reflect.get(signMessages, screen);
        final String[] lines = MarumaSign.takePendingWrite();
        if (messages != null && lines != null) {
            System.arraycopy(lines, 0, messages, 0, 4);
        }
        // 画面を閉じると、看板の内容がサーバーへ送信される
        Mc.setScreen(mc, null);
    }
}
