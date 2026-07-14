package marumasa.marumasa_sign.util;

public class PortManager {

    public static int generate() {
        // ポート番号をOSに自動割り当てしてもらう
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            // 万が一失敗した場合はデフォルトのポート
            return 2071;
        }
    }
}
