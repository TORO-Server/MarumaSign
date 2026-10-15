package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 動作確認のときの、サーバーへの接続。
 * Minecraft 1.17 は、起動と同時に接続すると落ちる (1.17.1 で直っている) ので、起動が済んでから接続する。
 */
final class Join {

    private Join() {
    }

    // タイトル画面 (または、接続に失敗したあとの画面) が出ていて、リソースの読み込みが終わっていれば、接続を始める
    static boolean start(Minecraft mc, String host, int port) {
        final Screen screen = mc.screen;
        if (mc.getOverlay() != null || !(screen instanceof TitleScreen || screen instanceof DisconnectedScreen)) return false;
        // 接続を始めるメソッドは、バージョンが上がるにつれて後ろの引数が増えているので、先頭の引数の型で探す
        for (Method method : ConnectScreen.class.getMethods()) {
            final Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || types.length < 3) continue;
            if (types[0] != Screen.class || types[1] != Minecraft.class || types[2] != ServerAddress.class) continue;
            final Object[] arguments = new Object[types.length];
            arguments[0] = new TitleScreen();
            arguments[1] = mc;
            arguments[2] = ServerAddress.parseString(host + ":" + port);
            for (int i = 3; i < types.length; i++) {
                if (types[i] == boolean.class) arguments[i] = Boolean.FALSE;
            }
            Reflect.invoke(method, null, arguments);
            return true;
        }
        return false;
    }
}
