package marumasa.maruma_sign_webui.util;

import java.net.InetSocketAddress;
import java.net.Socket;

public class PortManager {

    public static int generate() {
        // ポート番号をランダムに設定
        int port = random();
        // もしそのポート番号が既に使用されていたら
        // 使用されていないポート番号にする
        while (isPortInUse(port)) port = random();
        // ポート番号を返す
        return port;
    }

    // ポート番号が既に使用されているかどうか確認するメソッド
    private static boolean isPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 100);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int random() {
        // ランダムな整数を生成
        int min = 2071;
        int max = 2121;

        double randomDouble = Math.random(); // 0.0 以上 1.0 未満の実数
        return (int) (randomDouble * (max - min + 1)) + min;
    }
}
