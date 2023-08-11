package marumasa.marumasa_sign;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

public class MarumaSign implements ModInitializer {
    // ModのID
    public static final String MOD_ID = "marumasa_sign";
    // ロガー
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("Start: " + MOD_ID);
    }
}
