package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.util.SignRenderCanceledThreadLocal;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public class BlockStateBaseMixin {

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true)
    private void onGetRenderShape(CallbackInfoReturnable<RenderShape> cir) {
        BlockState state = (BlockState) (Object) this;
        if (SignRenderCanceledThreadLocal.targetState.get() == state) {
            SignRenderCanceledThreadLocal.targetState.set(null);
            cir.setReturnValue(RenderShape.INVISIBLE);
        }
    }
}
