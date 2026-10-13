package marumasa.marumasa_sign.adapter;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric から起動されたときの入口。
 */
public final class FabricEntry implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Adapter.start("fabric");
    }
}