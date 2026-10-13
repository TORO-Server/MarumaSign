package marumasa.marumasa_sign.v1_12;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Forge から起動されたときの入口。
 */
public final class Entry {

    public static void start() {
        // クライアント専用
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        if (!Adapter.init("forge")) return;
        MinecraftForge.EVENT_BUS.register(new Entry());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Adapter.tick();
    }
}
