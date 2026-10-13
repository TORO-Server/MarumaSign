package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

final class PlatformImpl implements Platform {

    private final Minecraft mc = Minecraft.getInstance();
    private final String loaderName;
    private final AtomicInteger nextTextureId = new AtomicInteger();

    PlatformImpl(String loaderName) {
        this.loaderName = loaderName;
    }

    // テクスチャのハンドル
    static final class Texture {
        final Identifier id;
        final DynamicTexture texture;
        // ワールドに描くときの描画設定
        final RenderType renderType;

        Texture(Identifier id, DynamicTexture texture) {
            this.id = id;
            this.texture = texture;
            this.renderType = RenderTypes.entityTranslucent(id);
        }
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public String loaderName() {
        return loaderName;
    }

    @Override
    public File gameDirectory() {
        return mc.gameDirectory;
    }

    @Override
    public String languageCode() {
        return mc.options.languageCode;
    }

    @Override
    public void execute(Runnable task) {
        mc.execute(task);
    }

    @Override
    public void showMessage(String message) {
        Mc.chat(mc).addClientSystemMessage(Component.literal(message));
    }

    @Override
    public boolean isInWorld() {
        return mc.level != null && mc.player != null;
    }

    @Override
    public int maxSignLineLength() {
        return 80;
    }

    private static void copy(NativeImage image, int width, int height, int[] argb) {
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, argb[row + x]);
            }
        }
    }

    @Override
    public Object createTexture(int width, int height, int[] argb) {
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final Identifier id = Identifier.fromNamespaceAndPath(MarumaSign.MOD_ID, "dynamic/" + nextTextureId.incrementAndGet());
        final DynamicTexture texture = new DynamicTexture(() -> "marumasa_sign", image);
        mc.getTextureManager().register(id, texture);
        return new Texture(id, texture);
    }

    @Override
    public void updateTexture(Object handle, int width, int height, int[] argb) {
        final DynamicTexture texture = ((Texture) handle).texture;
        copy(texture.getPixels(), width, height, argb);
        texture.upload();
    }

    @Override
    public void releaseTexture(Object handle) {
        mc.getTextureManager().release(((Texture) handle).id);
    }

    @Override
    public void openUi() {
        Mc.setScreen(mc, new UiScreen());
    }

    @Override
    public void closeUi() {
        if (Mc.screen(mc) instanceof UiScreen) Mc.setScreen(mc, null);
    }

    @Override
    public boolean supportsFileDrop() {
        return true;
    }

    @Override
    public String getClipboard() {
        return mc.keyboardHandler.getClipboard();
    }

    @Override
    public void setClipboard(String text) {
        mc.keyboardHandler.setClipboard(text);
    }

    @Override
    public void selfTestLook(float pitch) {
        if (mc.player != null) mc.player.setXRot(pitch);
    }

    @Override
    public void selfTestUseItem() {
        if (mc.player == null) return;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.OAK_SIGN)) {
                mc.player.getInventory().setSelectedSlot(slot);
            }
        }
        KeyMapping.click(mc.options.keyUse.getDefaultKey());
    }

    @Override
    public void quit() {
        mc.stop();
    }
}
