package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.sign.PdfControls;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.sign.SignSpec;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 実機での動作確認を自動で行うための仕組み。
 * システムプロパティ marumasign.selftest に結果の出力先フォルダを指定したときだけ動く。
 * ワールドに入ったあと、画面を開いてファイルを選び、看板を設置して表示されるまでを確認する。
 * ファイルを複数指定した場合は、外部のスクリプトにプレイヤーを移動させてもらいながら、順に確認する。
 */
final class SelfTest {

    private static final int WAIT_WORLD = 0;
    private static final int OPEN_UI = 1;
    private static final int FILL = 2;
    private static final int WAIT_WRITE_MODE = 3;
    private static final int USE_ITEM = 4;
    private static final int WAIT_RENDER = 5;
    private static final int LOOK_AT_SIGN = 6;
    private static final int WAIT_MOVE = 7;
    private static final int WAIT_ITEM = 8;
    private static final int FINISHED = 9;
    private static final int PDF_CONTROLS = 10;

    private static final String[] STEP_NAMES = {
            "WAIT_WORLD", "OPEN_UI", "FILL", "WAIT_WRITE_MODE", "USE_ITEM", "WAIT_RENDER", "LOOK_AT_SIGN", "WAIT_MOVE",
            "WAIT_ITEM", "FINISHED", "PDF_CONTROLS"
    };

    // PDF のページ送りを確かめる手順の中の、各段階を始める tick
    private static final int PDF_CLICK = 30;
    private static final int PDF_TURN_TIMEOUT = 200;

    // 看板アイテムを受け取ってから、描かれるのを待つ時間の上限 (tick)
    private static final int ITEM_TIMEOUT = 20 * 15;

    // 1 つの手順にかけられる時間の上限 (tick)
    private static final int STEP_TIMEOUT = 20 * 120;

    private final Platform platform;
    private final File directory;
    // 看板に表示して確かめるファイル
    private final String[] images;
    // 確認中のファイルの番号
    private int index;
    // 確認中の看板の URL と、それを描画した回数
    private volatile String expectedUrl;
    private volatile int drawnCount;
    private final StringBuilder summary = new StringBuilder();
    // 看板の文字を持った看板アイテムの見た目も確かめるかどうか
    private final boolean checkItem = Boolean.getBoolean("marumasign.selftest.item");
    // 看板アイテムを受け取った時点と、画面の記録を頼んだ時点 (その手順での tick)。まだなら -1
    private int itemGivenAt = -1;
    private int itemViewAt = -1;

    // 起動が済んでから自分で接続するサーバー (host:port)。指定がなければ、起動時の指定に任せる
    private final String server = System.getProperty("marumasign.selftest.server", "");
    private boolean joining;
    private int joinAttempts;

    private int step = WAIT_WORLD;
    private int ticksInStep;
    private int worldTicks;
    private int useAttempts;
    private int quitCountdown = -1;

    private SelfTest(Platform platform, File directory, String[] images) {
        this.platform = platform;
        this.directory = directory;
        this.images = images;
    }

    static SelfTest createIfRequested(Platform platform) {
        final String directory = System.getProperty("marumasign.selftest");
        if (directory == null || directory.isEmpty()) return null;
        // 複数のファイルは | で区切って指定する
        final String images = System.getProperty("marumasign.selftest.image", "");
        MarumaSign.LOGGER.info("Self test enabled: " + directory);
        return new SelfTest(platform, new File(directory), images.split("\\|"));
    }

    // 看板を描こうとして内容を調べた回数と、そのうち MarumaSign の看板だった回数。
    // 表示されないときに、看板の描画そのものが呼ばれていないのか、内容を読み取れていないのかを見分ける
    private volatile int lookups;
    private volatile int recognised;

    void onLookup(boolean found) {
        lookups++;
        if (found) recognised++;
    }

    // 読み込みが完了した看板が描画されたとき
    void onDrawn(SignImage image) {
        final String url = expectedUrl;
        if (url != null && url.equals(image.spec.url)) {
            drawnCount++;
            drawnImage = image;
        }
    }

    // 確認中の看板として最後に描画されたもの
    private volatile SignImage drawnImage;

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
        // ワールドに入るまでは、影 Mod のシェーダーの準備などで時間がかかることがあるので、長めに待つ
        if (++ticksInStep > (step == WAIT_WORLD ? STEP_TIMEOUT * 3 : STEP_TIMEOUT)) {
            finish(false, "Timed out at step " + STEP_NAMES[step]
                    + (step == WAIT_RENDER ? " (signs looked up " + lookups + " times, recognised " + recognised + " times)" : ""));
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
                if (!server.isEmpty() && !platform.isInWorld()) {
                    // 初期の Forge には、接続が途中で止まることがある不具合がある。入れないまま時間が経ったら、やり直す
                    if (joining && ticksInStep > 20 * 40) joining = false;
                    if (!joining && ticksInStep > 60 && joinAttempts < 6) {
                        final int colon = server.lastIndexOf(':');
                        joining = platform.selfTestJoin(server.substring(0, colon), Integer.parseInt(server.substring(colon + 1)));
                        if (joining) {
                            joinAttempts++;
                            // 接続を始めた時点から、待ち時間を数え直す
                            ticksInStep = 0;
                        }
                    }
                }
                worldTicks = platform.isInWorld() ? worldTicks + 1 : 0;
                if (worldTicks > 20 * 8) {
                    worldTicks = 0;
                    next(OPEN_UI);
                }
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
                    ui.mainPage().setSource(images[index]);
                    ui.mainPage().setParameters(1f, 1f, 0f, 1f, 0f, 0f, 0f, 0f);
                }
                if (ticksInStep == 20) {
                    ui.mainPage().place();
                    next(WAIT_WRITE_MODE);
                }
                break;

            case WAIT_WRITE_MODE:
                if (MarumaSign.hasPendingWrite()) {
                    final SignSpec spec = SignSpec.parse(MarumaSign.pendingWriteText());
                    drawnCount = 0;
                    expectedUrl = spec == null ? null : spec.url;
                    useAttempts = 0;
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
                // 画像の全体が見えるように正面を向く。
                // サーバーからの位置の補正で向きが戻されることがあるので、毎 tick 向き直す
                platform.selfTestLook(0f);
                if (drawnCount > 0 && ticksInStep > 40) next(LOOK_AT_SIGN);
                break;

            case LOOK_AT_SIGN:
                // 外部のスクリプトが画面を記録できるように、合図のファイルを書きながら進める。
                // まず、画像の全体が写っている状態
                if (ticksInStep < 60) platform.selfTestLook(0f);
                if (ticksInStep == 20) writeFile("view-" + index + ".txt", "ready\n");
                // 次に、看板そのものが消えていることを確認できるように、看板の位置を見下ろす
                if (ticksInStep >= 60 && ticksInStep < 140) platform.selfTestLook(50f);
                if (ticksInStep == 90) writeFile("sign-view-" + index + ".txt", "ready\n");
                if (ticksInStep >= 140) platform.selfTestLook(0f);
                if (ticksInStep == 150) {
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(new File(images[index]).getName()).append(" rendered ").append(drawnCount).append(" times");
                    final SignImage drawn = drawnImage;
                    if (drawn != null && drawn.textureEntry().isPdf()) {
                        // PDF なら、ページ送りの操作も確かめる
                        final double[] ray = platform.crosshair();
                        homeYaw = ray == null ? 0f : (float) Math.toDegrees(Math.atan2(-ray[3], ray[5]));
                        pdfPhase = 0;
                        next(PDF_CONTROLS);
                    } else {
                        nextMedium();
                    }
                }
                break;

            case PDF_CONTROLS:
                checkPdfControls();
                break;

            case WAIT_MOVE:
                // 外部のスクリプトがプレイヤーを移動させるのを待つ
                if (new File(directory, "moved-" + index + ".txt").isFile()) {
                    if (worldTicks == 0) worldTicks = ticksInStep;
                    // 移動先の地形が届くまで少し待つ
                    if (ticksInStep - worldTicks > 80) {
                        worldTicks = 0;
                        expectedUrl = null;
                        index++;
                        next(OPEN_UI);
                    }
                }
                break;

            case WAIT_ITEM:
                if (itemGivenAt < 0) {
                    // 渡したアイテムの URL が書かれたファイルを待つ
                    final String url = readFile("item-given.txt");
                    if (url != null) {
                        drawnCount = 0;
                        expectedUrl = url.trim();
                        itemGivenAt = ticksInStep;
                    }
                } else if (itemViewAt < 0) {
                    // アイテムは手持ちの枠 (ホットバー) に入るので、画面の下に描かれる
                    if (drawnCount > 0 && ticksInStep - itemGivenAt > 40) {
                        writeFile("item-view.txt", "ready\n");
                        itemViewAt = ticksInStep;
                    } else if (ticksInStep - itemGivenAt > ITEM_TIMEOUT) {
                        finish(true, summary + ", item not rendered");
                    }
                } else if (ticksInStep - itemViewAt > 60) {
                    finish(true, summary + ", item rendered " + drawnCount + " times");
                }
                break;

            default:
                break;
        }
    }

    // 確認中のファイルが終わったので、次のファイルか、看板アイテムの確認に進む
    private void nextMedium() {
        if (index + 1 < images.length) {
            // 次のファイルは、前の看板が見えない場所で確認する
            writeFile("move-" + index + ".txt", "ready\n");
            next(WAIT_MOVE);
        } else if (checkItem) {
            // 外部のスクリプトに、看板の文字を持った看板アイテムを渡してもらう
            expectedUrl = null;
            writeFile("item-request.txt", "ready\n");
            next(WAIT_ITEM);
        } else {
            finish(true, summary.toString());
        }
    }

    // PDF の確認を始めたときに向いていた方角
    private float homeYaw;
    // PDF の確認の段階と、その段階を始めた tick
    private int pdfPhase;
    private int pdfPhaseAt;

    private void pdfPhase(int phase) {
        pdfPhase = phase;
        pdfPhaseAt = ticksInStep;
    }

    // 照準を、PDF の看板の指定した部分に合わせる
    private boolean aimAt(int part) {
        final double[] target = MarumaSign.signs().pdfControls().lastTarget(part);
        final double[] ray = platform.crosshair();
        if (target == null || ray == null) return false;
        final double dx = target[0] - ray[0], dy = target[1] - ray[1], dz = target[2] - ray[2];
        final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        final float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        platform.selfTestLook(yaw, pitch);
        return true;
    }

    /**
     * PDF のページ送りを確かめる。
     * 進めるボタンに照準を合わせて帯が出ることと、使用キーで 2 ページ目に進むこと、
     * 照準を外すと帯が消えることを、順に確かめる。画面の記録は外部のスクリプトに頼む。
     */
    private void checkPdfControls() {
        final PdfControls controls = MarumaSign.signs().pdfControls();
        final String name = new File(images[index]).getName();
        final int elapsed = ticksInStep - pdfPhaseAt;
        switch (pdfPhase) {
            case 0:
                // 進めるボタンに照準を合わせる
                if (!aimAt(PdfControls.NEXT)) {
                    if (elapsed > 40) finish(false, summary + ": " + name + " page controls have no position");
                    return;
                }
                if (elapsed == 20) writeFile("pdf-ui-" + index + ".txt", "ready\n");
                if (elapsed >= PDF_CLICK) {
                    final int[] page = controls.hoveredPage();
                    if (controls.hoverPart() != PdfControls.NEXT || page == null) {
                        finish(false, summary + ": " + name + " page controls not shown on the next button (part "
                                + controls.hoverPart() + ")");
                        return;
                    }
                    if (page[0] != 0 || page[1] < 2) {
                        finish(false, summary + ": " + name + " expected page 1 of 2 or more, got " + (page[0] + 1) + " / " + page[1]);
                        return;
                    }
                    platform.selfTestUseItem();
                    pdfPhase(1);
                }
                break;
            case 1:
                // 2 ページ目が表示されるのを待つ
                aimAt(PdfControls.NEXT);
                final int[] page = controls.hoveredPage();
                if (page != null && page[0] == 1 && page[2] == 0) {
                    pdfPhase(2);
                } else if (elapsed > PDF_TURN_TIMEOUT) {
                    finish(false, summary + ": " + name + " did not turn to page 2 ("
                            + (page == null ? "not hovered" : (page[0] + 1) + " / " + page[1]) + ")");
                }
                break;
            case 2:
                // ページの中央を見て、2 ページ目を画面に記録してもらう
                aimAt(PdfControls.PAGE);
                if (elapsed == 20) writeFile("pdf-page-" + index + ".txt", "ready\n");
                if (elapsed >= 40) pdfPhase(3);
                break;
            case 3:
                // 真上を見て照準を外すと、帯が消える
                platform.selfTestLook(homeYaw, -90f);
                if (elapsed == 20) {
                    if (controls.isHovering()) {
                        finish(false, summary + ": " + name + " page controls still shown after looking away");
                        return;
                    }
                    writeFile("pdf-hidden-" + index + ".txt", "ready\n");
                }
                if (elapsed >= 30) {
                    platform.selfTestLook(homeYaw, 0f);
                    summary.append(", ").append(name).append(" turned to page 2 with the page controls");
                    nextMedium();
                }
                break;
            default:
                break;
        }
    }

    // 外部のスクリプトが書いたファイルを読む。まだなければ null
    private String readFile(String name) {
        final File file = new File(directory, name);
        if (!file.isFile()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
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
