package marumasa.marumasa_sign.v1_12;

import marumasa.marumasa_sign.core.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;

import java.io.File;

final class PlatformImpl implements Platform {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final String loaderName;

    PlatformImpl(String loaderName) {
        this.loaderName = loaderName;
    }

    // テクスチャのハンドル
    static final class Texture {
        final ResourceLocation location;
        final DynamicTexture texture;

        Texture(ResourceLocation location, DynamicTexture texture) {
            this.location = location;
            this.texture = texture;
        }
    }

    @Override
    public String minecraftVersion() {
        return mc.getVersion();
    }

    @Override
    public String loaderName() {
        return loaderName;
    }

    @Override
    public File gameDirectory() {
        return mc.gameDir;
    }

    @Override
    public String languageCode() {
        return mc.gameSettings.language;
    }

    @Override
    public void execute(Runnable task) {
        mc.addScheduledTask(task);
    }

    @Override
    public void showMessage(String message) {
        if (mc.ingameGUI != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(message));
        }
    }

    @Override
    public boolean isInWorld() {
        return mc.world != null && mc.player != null;
    }

    @Override
    public int maxSignLineLength() {
        return 80;
    }

    @Override
    public Object createTexture(int width, int height, int[] argb) {
        final DynamicTexture texture = new DynamicTexture(width, height);
        System.arraycopy(argb, 0, texture.getTextureData(), 0, width * height);
        texture.updateDynamicTexture();
        return new Texture(mc.getTextureManager().getDynamicTextureLocation("marumasa_sign", texture), texture);
    }

    @Override
    public void updateTexture(Object handle, int width, int height, int[] argb) {
        final DynamicTexture texture = ((Texture) handle).texture;
        System.arraycopy(argb, 0, texture.getTextureData(), 0, width * height);
        texture.updateDynamicTexture();
    }

    @Override
    public void releaseTexture(Object handle) {
        mc.getTextureManager().deleteTexture(((Texture) handle).location);
    }

    @Override
    public void openUi() {
        mc.displayGuiScreen(new UiScreen());
    }

    @Override
    public void closeUi() {
        if (mc.currentScreen instanceof UiScreen) mc.displayGuiScreen(null);
    }

    @Override
    public boolean supportsFileDrop() {
        return false;
    }

    @Override
    public String getClipboard() {
        return GuiScreen.getClipboardString();
    }

    @Override
    public void setClipboard(String text) {
        GuiScreen.setClipboardString(text);
    }

    @Override
    public void selfTestLook(float pitch) {
        if (mc.player != null) mc.player.rotationPitch = pitch;
    }

    @Override
    public void selfTestUseItem() {
        if (mc.player == null) return;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.inventory.mainInventory.get(slot).getItem() == Items.SIGN) {
                mc.player.inventory.currentItem = slot;
            }
        }
        KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
    }

    @Override
    public void quit() {
        mc.shutdown();
    }
}
