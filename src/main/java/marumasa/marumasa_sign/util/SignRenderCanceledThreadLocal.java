package marumasa.marumasa_sign.util;

import net.minecraft.world.level.block.state.BlockState;

public class SignRenderCanceledThreadLocal {
    public static final ThreadLocal<BlockState> targetState = new ThreadLocal<>();
}
