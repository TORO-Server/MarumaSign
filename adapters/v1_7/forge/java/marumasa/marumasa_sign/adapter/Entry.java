package marumasa.marumasa_sign.adapter;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Forge 1.7.10 から起動されたときの入口。
 */
public final class Entry {

    public static void start() {
        // クライアント専用
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        if (!Adapter.init("forge")) return;
        // 1.7.10 では tick のイベントは FML のバスに、描画のイベントは Forge のバスに流れる
        final Entry entry = new Entry();
        FMLCommonHandler.instance().bus().register(entry);
        MinecraftForge.EVENT_BUS.register(entry);
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
