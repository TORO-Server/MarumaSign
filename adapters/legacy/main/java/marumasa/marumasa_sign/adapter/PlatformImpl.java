package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.realms.RealmsSharedConstants;
import net.minecraft.util.ResourceLocation;

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
        return RealmsSharedConstants.VERSION_STRING;
    }

    @Override
    public String loaderName() {
        return loaderName;
    }

    @Override
    public File gameDirectory() {
        return Mc.gameDirectory();
    }

    @Override
    public String languageCode() {
        return mc.gameSettings.language;
    }

    @Override
    public void execute(Runnable task) {
        Mc.execute(task);
    }

    @Override
    public void showMessage(String message) {
        if (mc.ingameGUI != null) Mc.chat(message);
    }

    @Override
    public boolean isInWorld() {
        return Mc.inWorld();
    }

    @Override
    public int maxSignLineLength() {
        return Mc.MAX_SIGN_LINE_LENGTH;
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
        Mc.look(pitch);
    }

    @Override
    public void selfTestUseItem() {
        if (!Mc.inWorld()) return;
        Mc.selectSign();
        KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
    }

    @Override
    public void quit() {
        mc.shutdown();
    }
}
