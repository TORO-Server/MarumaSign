package marumasa.marumasa_sign.adapter;

/**
 * Forge から起動されたときの入口。
 */
public final class Entry {

    private Entry() {
    }

    public static void start() {
        // クライアントにしかないクラスの有無で、サーバーではないことを確かめる
        try {
            Class.forName("net.minecraft.client.Minecraft", false, Entry.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        Adapter.start("forge");
    }
}