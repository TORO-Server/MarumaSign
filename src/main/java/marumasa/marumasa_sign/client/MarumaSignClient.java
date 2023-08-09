package marumasa.marumasa_sign.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public class MarumaSignClient implements ClientModInitializer {
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityType.SIGN;

    @Override
    public void onInitializeClient() {

        BlockEntityRendererFactories.register(signType, CustomSignBlockEntityRenderer::new);
    }
}
