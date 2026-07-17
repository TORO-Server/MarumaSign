package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.model.CustomSign;
import marumasa.marumasa_sign.util.SignRenderCanceledThreadLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void onGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        SignRenderCanceledThreadLocal.targetState.set(null);

        BlockState state = cir.getReturnValue();
        if (state != null && state.getBlock() instanceof SignBlock) {
            final Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.isSpectator()) {
                return;
            }

            BlockEntity blockEntity = ((BlockAndTintGetter) (Object) this).getBlockEntity(pos);
            if (blockEntity instanceof SignBlockEntity signBlockEntity) {
                final String signText = CustomSign.read(signBlockEntity);
                if (CustomSignProvider.toParameters(signText) != null) {
                    SignRenderCanceledThreadLocal.targetState.set(state);
                }
            }
        }
    }
}
