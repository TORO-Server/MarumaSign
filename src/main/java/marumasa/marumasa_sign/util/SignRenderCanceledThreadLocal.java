package marumasa.marumasa_sign.util;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;

public class SignRenderCanceledThreadLocal {
    public static final ThreadLocal<BlockPos> targetPos = new ThreadLocal<>();
    public static final ThreadLocal<BlockAndTintGetter> targetRegion = new ThreadLocal<>();
}
