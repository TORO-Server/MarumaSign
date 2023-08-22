package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.MarumaSign;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.util.Identifier;

public class MarumaSignClient implements ClientModInitializer {

    // 看板のブロックエンティティタイプ 取得
    private static final BlockEntityType<SignBlockEntity> signType = BlockEntityType.SIGN;

    public static final CustomSign.TextureURL Loading = new CustomSign.TextureURL(
            new Identifier("textures/gui/container/anvil.png"), 1, 1
    );
    public static final CustomSign.TextureURL Error = new CustomSign.TextureURL(
            new Identifier(MarumaSign.MOD_ID, "textures/misc/error.png"), 960, 576
    );

    @Override
    public void onInitializeClient() {
        // 看板のブロックエンティティのレンダラーを置き換える
        BlockEntityRendererFactories.register(signType, CustomSignBlockEntityRenderer::new);
    }
}
