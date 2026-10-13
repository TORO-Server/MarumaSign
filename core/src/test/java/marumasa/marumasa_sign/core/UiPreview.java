package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Minecraft を起動せずに画面の見た目を確認するための道具。
 * 引数: 出力先フォルダ, 言語コード, 拡大率
 */
public final class UiPreview {

    private static final class FakePlatform implements Platform {
        private final String language;
        int[] pixels;
        int width, height;

        FakePlatform(String language) {
            this.language = language;
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
            task.run();
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
            return this;
        }

        @Override
        public void updateTexture(Object texture, int width, int height, int[] argb) {
            this.pixels = argb.clone();
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

    private static void save(FakePlatform platform, UiRoot ui, File file) throws Exception {
        ui.texture();
        final BufferedImage image = new BufferedImage(platform.width, platform.height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, platform.width, platform.height, platform.pixels, 0, platform.width);
        ImageIO.write(image, "png", file);
        System.out.println("Wrote " + file);
    }

    public static void main(String[] args) throws Exception {
        final File output = new File(args[0]);
        final String language = args.length > 1 ? args[1] : "ja_jp";
        final int scale = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create " + output);

        final FakePlatform platform = new FakePlatform(language);
        MarumaSign.init(platform);
        final UiRoot ui = MarumaSign.ui();
        ui.opened();
        ui.layout(UiRoot.WIDTH * scale, UiRoot.HEIGHT * scale);
        save(platform, ui, new File(output, "main-" + language + ".png"));

        // 画像を指定した状態
        if (args.length > 3) {
            ui.mainPage().setSource(args[3]);
            for (int i = 0; i < 40; i++) MarumaSign.tick();
            Thread.sleep(1500);
            save(platform, ui, new File(output, "main-image-" + language + ".png"));
        }

        // ファイル選択画面 (参照ボタンまで Tab で移動して押す)
        ui.keyPressed(Key.TAB, 0);
        ui.keyPressed(Key.ENTER, 0);
        save(platform, ui, new File(output, "browser-" + language + ".png"));
    }
}
