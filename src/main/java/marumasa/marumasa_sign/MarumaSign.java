package marumasa.marumasa_sign;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

public class MarumaSign implements ModInitializer {
    // ModのID
    public static final String MOD_ID = "marumasa_sign";
    // ロガー
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final Config CONFIG = new Config();

    // 保存された看板の内容
    public static String _globalSignContent = null;

    @Override
    public void onInitialize() {
        LOGGER.info("Start: " + MOD_ID);
    }
}
