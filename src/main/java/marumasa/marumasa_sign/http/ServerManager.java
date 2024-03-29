package marumasa.marumasa_sign.http;

import marumasa.maruma_sign_webui.util.PortManager;
import net.minecraft.util.Util;

public class ServerManager {

    private static ServerEngine engine;

    public static void openMenu() {
        if (engine == null)  // サーバーが起動していない場合
            build(); // サーバーを起動
        // ブラウザで起動したサーバーに接続する
        Util.getOperatingSystem().open(String.format("http://localhost:%d%s", engine.port, "/index.html"));
    }

    public static void closeMenu() {
        if (engine == null) return;
        // サーバーを閉じる
        engine.server.stop(0);
        engine = null;
    }

    private static void build() {
        // ポート番号を設定
        int port = PortManager.generate();
        // サーバーを起動させる
        engine = new ServerEngine(port);
    }
}
