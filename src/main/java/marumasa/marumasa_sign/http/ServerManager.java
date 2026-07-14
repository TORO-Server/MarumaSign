package marumasa.marumasa_sign.http;

import marumasa.marumasa_sign.util.PortManager;

public class ServerManager {

    private static ServerEngine engine;

    public static synchronized void openMenu() {
        if (engine == null)  // サーバーが起動していない場合
            build(); // サーバーを起動
        if (engine == null || engine.server == null) {
            return;
        }
        // ブラウザで起動したサーバーに接続する
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(String.format("http://127.0.0.1:%d%s", engine.port, "/index.html")));
            } else {
                marumasa.marumasa_sign.MarumaSign.LOGGER.warn("AWT Desktop browsing is not supported on this platform. Access WebUI manually at: http://127.0.0.1:{}", engine.port);
            }
        } catch (Exception e) {
            marumasa.marumasa_sign.MarumaSign.LOGGER.error("Failed to open WebUI in browser", e);
        }
    }

    public static synchronized void closeMenu() {
        if (engine == null) return;
        // サーバーを閉じる
        if (engine.server != null) {
            engine.server.stop(1); // 1秒間の猶予期間
        }
        engine = null;
    }

    private static void build() {
        // ポート番号を設定
        int port = PortManager.generate();
        // サーバーを起動させる
        engine = new ServerEngine(port);
        if (engine.server == null) {
            engine = null;
        }
    }
}
