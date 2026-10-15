package marumasa.marumasa_sign.adapter;

import net.minecraft.init.Items;
import net.minecraftforge.client.MinecraftForgeClient;

/**
 * Forge 1.7.10 向けに看板アイテムのレンダラーを登録する。
 */
public final class ForgeInstaller {

    private static boolean installed;

    private ForgeInstaller() {
    }

    public static void install() {
        if (installed) return;
        MinecraftForgeClient.registerItemRenderer(Items.sign, new ForgeItemSignRenderer());
        installed = true;
    }
}
