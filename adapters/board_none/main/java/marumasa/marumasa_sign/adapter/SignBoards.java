package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * 看板の板を隠す。(Minecraft 26.1 まで)
 * このバージョンでは、看板の板は看板のレンダラーが描くので、レンダラーを包むだけで消せる。ここでは何もしない。
 */
final class SignBoards {

    private SignBoards() {
    }

    static void install(Minecraft mc) {
    }

    static void track(SignBlockEntity sign, boolean hide) {
    }
}