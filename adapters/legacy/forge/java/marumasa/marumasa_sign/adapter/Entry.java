package marumasa.marumasa_sign.adapter;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Forge 1.8 - 1.12.2 から起動されたときの入口。
 */
public final class Entry {

    public static void start() {
        // クライアント専用
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        if (!Adapter.init("forge")) return;
        // 古いバージョンでは tick のイベントが FML のバスに流れる。同じバスなら 2 回目の登録は無視される
        final Entry entry = new Entry();
        MinecraftForge.EVENT_BUS.register(entry);
        FMLCommonHandler.instance().bus().register(entry);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Adapter.tick();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Adapter.renderWorld();
    }
}
