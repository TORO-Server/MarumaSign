package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 実機での動作確認を自動で行うための仕組み。
 * システムプロパティ marumasign.selftest に結果の出力先フォルダを指定したときだけ動く。
 * ワールドに入ったあと、画面を開いて画像を選び、看板を設置して画像が表示されるまでを確認する。
 */
final class SelfTest {

    private static final int WAIT_WORLD = 0;
    private static final int OPEN_UI = 1;
    private static final int FILL = 2;
    private static final int WAIT_WRITE_MODE = 3;
    private static final int USE_ITEM = 4;
    private static final int WAIT_RENDER = 5;
    private static final int LOOK_AT_SIGN = 6;
    private static final int FINISHED = 7;

    private static final String[] STEP_NAMES = {
            "WAIT_WORLD", "OPEN_UI", "FILL", "WAIT_WRITE_MODE", "USE_ITEM", "WAIT_RENDER", "LOOK_AT_SIGN", "FINISHED"
    };

    // 1 つの手順にかけられる時間の上限 (tick)
    private static final int STEP_TIMEOUT = 20 * 120;

    private final Platform platform;
    private final File directory;
    private final String image;

    private int step = WAIT_WORLD;
    private int ticksInStep;
    private int worldTicks;
    private int useAttempts;
    private int quitCountdown = -1;

    private SelfTest(Platform platform, File directory, String image) {
        this.platform = platform;
        this.directory = directory;
        this.image = image;
    }

    static SelfTest createIfRequested(Platform platform) {
        final String directory = System.getProperty("marumasign.selftest");
        if (directory == null || directory.isEmpty()) return null;
        final String image = System.getProperty("marumasign.selftest.image", "");
        MarumaSign.LOGGER.info("Self test enabled: " + directory);
        return new SelfTest(platform, new File(directory), image);
    }

    private void next(int nextStep) {
        step = nextStep;
        ticksInStep = 0;
    }

    void tick() {
        if (quitCountdown >= 0) {
            if (quitCountdown-- == 0) platform.quit();
            return;
        }
        if (step == FINISHED) return;
        if (++ticksInStep > STEP_TIMEOUT) {
            finish(false, "Timed out at step " + STEP_NAMES[step]);
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            MarumaSign.LOGGER.error("Self test failed", e);
            finish(false, "Exception at step " + STEP_NAMES[step] + ": " + e);
        }
    }

    private void run() {
        final UiRoot ui = MarumaSign.ui();
        switch (step) {
            case WAIT_WORLD:
                // ワールドに入ってから、サーバー側の準備 (アイテムの付与など) が済むまで待つ
                worldTicks = platform.isInWorld() ? worldTicks + 1 : 0;
                if (worldTicks > 20 * 8) next(OPEN_UI);
                break;

            case OPEN_UI:
                if (ticksInStep == 1) MarumaSign.openMenu();
                if (ui.isOpen() && ticksInStep > 10) next(FILL);
                break;

            case FILL:
                if (ticksInStep == 1) {
                    // キー入力の経路も通すため、1 文字入力してから消す
                    ui.charTyped('x');
                    ui.keyPressed(Key.BACKSPACE, 0);
                    ui.mainPage().setSource(image);
                    ui.mainPage().setParameters(1f, 1f, 0f, 1f, 0f, 0f, 0f, 0f);
                }
                if (ticksInStep == 20) {
                    ui.mainPage().place();
                    next(WAIT_WRITE_MODE);
                }
                break;

            case WAIT_WRITE_MODE:
                if (MarumaSign.hasPendingWrite()) {
                    next(USE_ITEM);
                } else if (!ui.mainPage().isBusy() && ticksInStep > 5) {
                    finish(false, "Could not prepare the sign: " + ui.mainPage().statusText());
                }
                break;

            case USE_ITEM:
                if (!MarumaSign.hasPendingWrite()) {
                    next(WAIT_RENDER);
                } else if (ticksInStep % 40 == 20) {
                    if (++useAttempts > 20) {
                        finish(false, "The sign edit screen did not open");
                        return;
                    }
                    // 少し先の地面を見て看板を置く
                    platform.selfTestLook(50f);
                    platform.selfTestUseItem();
                }
                break;

            case WAIT_RENDER:
                // 画像の全体が見えるように正面を向く
                if (ticksInStep == 1) platform.selfTestLook(0f);
                if (MarumaSign.loadedRenderCount() > 0 && ticksInStep > 40) next(LOOK_AT_SIGN);
                break;

            case LOOK_AT_SIGN:
                // 看板そのものが消えていることを外部で確認できるように、看板の位置を見下ろす
                if (ticksInStep == 1) platform.selfTestLook(50f);
                if (ticksInStep == 30) writeFile("sign-view.txt", "ready\n");
                // 最後に、画像の全体が写る向きに戻して終了する
                if (ticksInStep == 80) platform.selfTestLook(0f);
                if (ticksInStep == 110) finish(true, "Rendered " + MarumaSign.loadedRenderCount() + " times");
                break;

            default:
                break;
        }
    }

    // 外部の確認用スクリプトに進み具合を知らせるファイルを書く
    private void writeFile(String name, String content) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
            Files.write(new File(directory, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            MarumaSign.LOGGER.error("Failed to write " + name, e);
        }
    }

    private void finish(boolean ok, String detail) {
        step = FINISHED;
        final String result = (ok ? "OK" : "FAIL") + "\n" + detail + "\n"
                + "minecraft=" + platform.minecraftVersion() + "\n"
                + "loader=" + platform.loaderName() + "\n";
        MarumaSign.LOGGER.info("Self test result: " + (ok ? "OK" : "FAIL") + " - " + detail);
        writeFile("result.txt", result);
        // 結果を書いてから、外部で画面を記録できるように少し待って終了する
        quitCountdown = 100;
    }
}
