package marumasa.marumasa_sign.boot;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge 1.8 以降の入口。
 * メソッドやフィールドに Forge や Minecraft の型を使わないこと
 * (古い Forge はこのクラスの全メソッドをリフレクションで調べるため)。
 */
@Mod(
        value = "marumasa_sign",
        modid = "marumasa_sign",
        name = "MarumaSign",
        version = BootInfo.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*"
)
public final class ForgeMod {

    public ForgeMod() {
        Boot.start("forge", minecraftVersion());
    }

    private static String minecraftVersion() {
        // 1.13 以降
        String version = Boot.staticChain("net.minecraftforge.versions.mcp.MCPVersion", "getMCVersion");
        // 1.8 - 1.12.2
        if (version == null) version = Boot.staticField("net.minecraftforge.fml.common.Loader", "MC_VERSION");
        if (version == null) version = Boot.staticChain("net.minecraftforge.fml.loading.FMLLoader", "versionInfo", "mcVersion");
        if (version == null) version = Boot.fromVersionJson();
        return version;
    }
}
