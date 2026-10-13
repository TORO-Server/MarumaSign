package marumasa.marumasa_sign.boot;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge の入口。
 */
@Mod("marumasa_sign")
public final class NeoForgeMod {

    public NeoForgeMod() {
        Boot.start("neoforge", minecraftVersion());
    }

    private static String minecraftVersion() {
        String version = Boot.staticChain("net.neoforged.fml.loading.FMLLoader", "versionInfo", "mcVersion");
        if (version == null) version = Boot.staticChain("net.neoforged.fml.loading.FMLLoader", "getCurrent", "getVersionInfo", "mcVersion");
        if (version == null) version = Boot.fromVersionJson();
        return version;
    }
}
