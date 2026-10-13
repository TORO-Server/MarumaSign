package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;

/**
 * 動作確認のときの、サーバーへの接続。このバージョンでは起動時の指定で接続できるので、何もしない。
 */
final class Join {

    private Join() {
    }

    static boolean start(Minecraft mc, String host, int port) {
        return false;
    }
}
