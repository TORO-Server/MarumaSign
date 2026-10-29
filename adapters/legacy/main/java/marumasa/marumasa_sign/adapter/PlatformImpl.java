package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.Platform;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.realms.RealmsSharedConstants;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

final class PlatformImpl implements Platform {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final String loaderName;
    // TextureManager の、名前からテクスチャを引く表
    private Field texturesField;

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
        final Texture texture = (Texture) handle;
        final TextureManager manager = mc.getTextureManager();
        manager.deleteTexture(texture.location);
        forget(manager, texture);
    }

    /**
     * テクスチャを TextureManager の表から外す。
     * このバージョンの deleteTexture は OpenGL のテクスチャを消すだけで、表からは外さない。
     * 表に残ると、画素の配列を持った DynamicTexture がいつまでも残り、看板を読み込み直すたびに
     * (使われなくなった看板は 5 分で消して、また見えたら読み込み直す) メモリが増え続ける。
     */
    private void forget(TextureManager manager, Texture texture) {
        if (texturesField == null) {
            // 同じ型の表がほかにもあるので、このテクスチャが入っているもので見分ける
            for (Field field : TextureManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                final Map<?, ?> map = Reflect.get(field, manager);
                if (map != null && map.get(texture.location) == texture.texture) {
                    texturesField = field;
                    break;
                }
            }
            // 表から外されている (外すように直されたローダー) ときは、何もしなくてよい
            if (texturesField == null) return;
        }
        final Map<?, ?> map = Reflect.get(texturesField, manager);
        if (map != null && map.get(texture.location) == texture.texture) map.remove(texture.location);
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
    public double[] crosshair() {
        return Mc.crosshair();
    }

    @Override
    public void selfTestLook(float pitch) {
        Mc.look(pitch);
    }

    @Override
    public void selfTestLook(float yaw, float pitch) {
        Mc.turn(yaw);
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
