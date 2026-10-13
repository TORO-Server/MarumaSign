package marumasa.marumasa_sign.core;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft の代わりをする、確認用の Platform。
 * テクスチャは最後に作成・更新された内容だけを覚えている。
 */
final class FakePlatform implements Platform {

    private final String language;
    // メインスレッドで実行する処理。runTasks で実行する
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();

    int[] pixels;
    int width, height;
    int textureCount;
    int updateCount;

    FakePlatform(String language) {
        this.language = language;
    }

    // execute で渡された処理を、呼び出したスレッドで実行する
    void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }

    @Override
    public String minecraftVersion() {
        return "preview";
    }

    @Override
    public String loaderName() {
        return "preview";
    }

    @Override
    public File gameDirectory() {
        return new File(System.getProperty("java.io.tmpdir"), "marumasign-preview");
    }

    @Override
    public String languageCode() {
        return language;
    }

    @Override
    public void execute(Runnable task) {
        tasks.add(task);
    }

    @Override
    public void showMessage(String message) {
        System.out.println("[chat] " + message);
    }

    @Override
    public boolean isInWorld() {
        return true;
    }

    @Override
    public int maxSignLineLength() {
        return 80;
    }

    @Override
    public Object createTexture(int width, int height, int[] argb) {
        this.pixels = argb.clone();
        this.width = width;
        this.height = height;
        textureCount++;
        return new Object();
    }

    @Override
    public void updateTexture(Object texture, int width, int height, int[] argb) {
        this.pixels = argb.clone();
        this.width = width;
        this.height = height;
        updateCount++;
    }

    @Override
    public void releaseTexture(Object texture) {
    }

    @Override
    public void openUi() {
    }

    @Override
    public void closeUi() {
    }

    @Override
    public boolean supportsFileDrop() {
        return true;
    }

    @Override
    public String getClipboard() {
        return "";
    }

    @Override
    public void setClipboard(String text) {
    }

    @Override
    public void selfTestLook(float pitch) {
    }

    @Override
    public void selfTestUseItem() {
    }

    @Override
    public void quit() {
    }
}
