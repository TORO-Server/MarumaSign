package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.client.sign.CustomSignProvider;
import marumasa.marumasa_sign.model.CustomSign;
import marumasa.marumasa_sign.util.SignRenderCanceledThreadLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
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
        if (state.getBlock() instanceof SignBlock) {
            final Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.isSpectator()) {
                return;
            }

            BlockPos pos = SignRenderCanceledThreadLocal.targetPos.get();
            BlockAndTintGetter level = SignRenderCanceledThreadLocal.targetRegion.get();

            if (pos != null && level != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof SignBlockEntity signBlockEntity) {
                    final String signText = CustomSign.read(signBlockEntity);
                    if (CustomSignProvider.toParameters(signText) != null) {
                        cir.setReturnValue(RenderShape.INVISIBLE);
                    }
                }
            }
        }
    }
}
