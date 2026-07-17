package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.util.SignRenderCanceledThreadLocal;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"))
    private void onGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        SignRenderCanceledThreadLocal.targetPos.set(pos);
        SignRenderCanceledThreadLocal.targetRegion.set((BlockAndTintGetter) (Object) this);
    }
}
