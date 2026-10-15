package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.Platform;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.io.File;

final class PlatformImpl implements Platform {

    private final Minecraft mc = Minecraft.getInstance();
    private final String loaderName;

    PlatformImpl(String loaderName) {
        this.loaderName = loaderName;
    }

    @Override
    public String minecraftVersion() {
        return Mc.version();
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
        Mc.chat(mc, message);
    }

    @Override
    public boolean isInWorld() {
        return mc.level != null && mc.player != null;
    }

    @Override
    public int maxSignLineLength() {
        return 80;
    }

    @Override
    public Object createTexture(int width, int height, int[] argb) {
        return Tex.create(mc, width, height, argb);
    }

    @Override
    public void updateTexture(Object handle, int width, int height, int[] argb) {
        ((Tex) handle).update(width, height, argb);
    }

    @Override
    public void releaseTexture(Object handle) {
        ((Tex) handle).release(mc);
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
        if (mc.player != null) Mc.look(mc, pitch);
    }

    @Override
    public void selfTestUseItem() {
        if (mc.player == null) return;
        Mc.selectSign(mc);
        KeyMapping.click(mc.options.keyUse.getDefaultKey());
    }

    @Override
    public boolean selfTestJoin(String host, int port) {
        return Join.start(mc, host, port);
    }

    @Override
    public void quit() {
        mc.stop();
    }
}
