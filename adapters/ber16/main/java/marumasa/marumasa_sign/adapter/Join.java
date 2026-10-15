package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * 動作確認のときの、サーバーへの接続。
 * Minecraft 1.14 - 1.15.1 には、起動と同時に接続すると入れない組み合わせがあるので、起動が済んでから接続する。
 */
final class Join {

    private Join() {
    }

    // タイトル画面 (または、接続に失敗したあとの画面) が出ていて、リソースの読み込みが終わっていれば、接続を始める
    static boolean start(Minecraft mc, String host, int port) {
        final Screen screen = mc.screen;
        if (mc.getOverlay() != null || !(screen instanceof TitleScreen || screen instanceof DisconnectedScreen)) return false;
        mc.setScreen(new ConnectScreen(new TitleScreen(), mc, host, port));
        return true;
    }
}
