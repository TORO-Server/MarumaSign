package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;

/**
 * 看板アイテムの見た目の差し替えの入口。毎 tick 呼ばれる。
 */
public final class SignItems {

    // ローダーの仕組みで差し替えを済ませている場合は true (Forge)。そのときは、ここでは何もしない
    public static boolean handledByLoader;

    private SignItems() {
    }

    public static void install(Minecraft mc) {
        if (handledByLoader) return;
        // ローダーに仕組みがない場合 (LiteLoader) は、ゲームが持っているレンダラーを差し替える。
        // 元に戻されることがあるので、毎 tick 確認する
        VanillaInstaller.install(mc);
    }
}