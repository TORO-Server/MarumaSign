package marumasa.marumasa_sign.http;

import marumasa.marumasa_sign.util.PortManager;

public class ServerManager {

    private static ServerEngine engine;

    public static void openMenu() {
        if (engine == null)  // サーバーが起動していない場合
            build(); // サーバーを起動
        // ブラウザで起動したサーバーに接続する
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(String.format("http://localhost:%d%s", engine.port, "/index.html")));
        } catch (Exception e) {
            e.printStackTrace();
        }
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
