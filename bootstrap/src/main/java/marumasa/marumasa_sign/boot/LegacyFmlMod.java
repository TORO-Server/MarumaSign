package marumasa.marumasa_sign.boot;

import cpw.mods.fml.common.Mod;

/**
 * Forge 1.7.10 の入口。
 */
@Mod(
        modid = "marumasa_sign",
        name = "MarumaSign",
        version = BootInfo.VERSION,
        acceptableRemoteVersions = "*"
)
public final class LegacyFmlMod {

    public LegacyFmlMod() {
        Boot.start("forge", Boot.staticField("cpw.mods.fml.common.Loader", "MC_VERSION"));
    }
}
