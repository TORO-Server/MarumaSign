package marumasa.marumasa_sign.v1_12;

import com.mumfrey.liteloader.Tickable;
import net.minecraft.client.Minecraft;

import java.io.File;

/**
 * LiteLoader から起動されたときの入口。
 */
public class LiteModMarumaSign implements Tickable {

    @Override
    public String getName() {
        return "MarumaSign";
    }

    @Override
    public String getVersion() {
        return BuildInfo.VERSION;
    }

    @Override
    public void init(File configPath) {
        Adapter.init("liteloader");
    }

    @Override
    public void upgradeSettings(String version, File configPath, File oldConfigPath) {
    }

    @Override
    public void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock) {
        if (clock) Adapter.tick();
    }
}
