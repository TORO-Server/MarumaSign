"""Minecraft 1.14 以降向けの実装 (adapters/ 以下) のうち、バージョンによって細部が違うファイルを書き出す。

    python tools/gen_adapters.py

API の細かい違いごとに同じ内容のファイルを手で保つのは間違いのもとなので、違う部分だけを差し替えて生成している。
生成したファイルもリポジトリに入れてあるので、ビルドにこのスクリプトは必要ない。
対象のファイルを変更するときは、このスクリプトを直して実行し直すこと (直接編集すると、次の実行で上書きされる)。
1.7.10 - 1.12.2 向け (adapters/legacy, v1_7 - v1_12) と、adapters/common, adapters/renderstate は手書きで、対象外。

層の構成:
  modern/       どのバージョンでも共通 (Adapter, PlatformImpl)
  classic/      1.21.8 以前: 従来の看板レンダラーと画面入力
  renderstate/  1.21.9 以降: レンダー状態を使う看板レンダラーとイベント形式の画面入力
  tex_*/        テクスチャの作り方の違い
  v*/           バージョンごとの差分 (Mc, UiScreen, SignRendererWrapper)
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
PK = "java/marumasa/marumasa_sign/adapter"


def write(layer, name, text):
    path = REPO / "adapters" / layer / "main" / PK / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.lstrip("\n"), encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- modern

write("modern", "Adapter.java", r'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.InputConstants;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.OptionsFile;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft 1.14 以降向けの実装の入口。バージョンによって違う部分は Mc クラスなどにまとめてある。
 * ローダーの API を使わずに済むよう、タイマーから定期的にメインスレッドへ処理を渡している。
 */
public final class Adapter {

    // 処理を呼ぶ間隔 (ミリ秒)。ゲームの 1 tick と同じ
    private static final long INTERVAL = 50;
    // GLFW の B キー
    private static final int KEY_B = 66;

    private static final AtomicBoolean started = new AtomicBoolean();
    // メインスレッドに渡した処理がまだ実行されていないかどうか
    private static final AtomicBoolean pending = new AtomicBoolean();

    private static String loaderName;
    // この実装が core を初期化したかどうか (他の経路で既に起動していれば false のまま)
    private static boolean active;
    private static boolean initialized;
    private static KeyMapping openMenu;

    private Adapter() {
    }

    // loader: 起動したローダーの名前
    public static void start(String loader) {
        if (!started.compareAndSet(false, true)) return;
        loaderName = loader;
        new Timer("MarumaSign-Tick", true).schedule(new TimerTask() {
            @Override
            public void run() {
                final Minecraft mc = Minecraft.getInstance();
                if (mc == null || !pending.compareAndSet(false, true)) return;
                mc.execute(Adapter::tick);
            }
        }, INTERVAL, INTERVAL);
    }

    private static void tick() {
        pending.set(false);
        final Minecraft mc = Minecraft.getInstance();
        if (!initialized) {
            // 設定の読み込みが終わるまで待つ
            if (mc.options == null) return;
            initialized = true;
            if (MarumaSign.isInitialized()) return;
            MarumaSign.init(new PlatformImpl(loaderName));
            active = true;
            registerKey(mc);
        }
        if (!active) return;

        SignRendererBase.install(mc);
        SignItems.install(mc);
        MarumaSign.tick();
        if (mc.player != null && "forge".equals(loaderName)) Mc.avoidExactAngles(mc);

        while (openMenu.consumeClick()) {
            if (mc.player != null && Mc.screen(mc) == null) MarumaSign.openMenu();
        }

        // 看板の編集画面が開かれたら、予約されている文字列を書き込んで閉じる
        if (MarumaSign.hasPendingWrite() && Mc.isSignEdit(Mc.screen(mc))) {
            final String[] lines = MarumaSign.takePendingWrite();
            if (lines != null) Mc.fillSign(Mc.screen(mc), lines);
            // 画面を閉じると、看板の内容がサーバーへ送信される
            Mc.setScreen(mc, null);
        }
    }

    // キーバインド登録
    private static void registerKey(Minecraft mc) {
        openMenu = Mc.newKey("MarumaSign", KEY_B);
        final KeyMapping[] current = mc.options.keyMappings;
        final KeyMapping[] extended = new KeyMapping[current.length + 1];
        System.arraycopy(current, 0, extended, 0, current.length);
        extended[current.length] = openMenu;
        Reflect.set(Reflect.fieldHolding(mc.options, current), mc.options, extended);
        // 保存されているキーの割り当てを反映する
        final String saved = OptionsFile.read(mc.gameDirectory, "key_MarumaSign");
        if (saved != null && !saved.isEmpty()) {
            try {
                openMenu.setKey(InputConstants.getKey(saved));
            } catch (RuntimeException e) {
                // 読めない値なら既定のキーのままにする
            }
        }
        KeyMapping.resetMapping();
    }
}
''')

write("modern", "PlatformImpl.java", r'''
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
''')

JOIN_REAL = r'''
package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * 動作確認のときの、サーバーへの接続。
 * Minecraft 1.14 - 1.15.1 には、起動と同時に接続すると入れない組み合わせがあるので、起動が済んでから接続する。
 */
final class Join {

    private Join() {
    }

    // タイトル画面 (または、接続に失敗したあとの画面) が出ていて、リソースの読み込みが終わっていれば、接続を始める
    static boolean start(Minecraft mc, String host, int port) {
        final Screen screen = mc.screen;
        if (mc.getOverlay() != null || !(screen instanceof TitleScreen || screen instanceof DisconnectedScreen)) return false;
        mc.setScreen(new ConnectScreen(new TitleScreen(), mc, host, port));
        return true;
    }
}
'''
JOIN_NONE = r'''
package marumasa.marumasa_sign.adapter;

import net.minecraft.client.Minecraft;

/**
 * 動作確認のときの、サーバーへの接続。このバージョンでは起動時の指定で接続できるので、何もしない。
 */
final class Join {

    private Join() {
    }

    static boolean start(Minecraft mc, String host, int port) {
        return false;
    }
}
'''
# 描画の層は世代ごとに 1 つだけ入るので、接続の実装もそこに置く
write("ber14", "Join.java", JOIN_REAL)
write("ber16", "Join.java", JOIN_REAL)
JOIN_LATER = r'''
package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 動作確認のときの、サーバーへの接続。
 * Minecraft 1.17 は、起動と同時に接続すると落ちる (1.17.1 で直っている) ので、起動が済んでから接続する。
 */
final class Join {

    private Join() {
    }

    // タイトル画面 (または、接続に失敗したあとの画面) が出ていて、リソースの読み込みが終わっていれば、接続を始める
    static boolean start(Minecraft mc, String host, int port) {
        final Screen screen = mc.screen;
        if (mc.getOverlay() != null || !(screen instanceof TitleScreen || screen instanceof DisconnectedScreen)) return false;
        // 接続を始めるメソッドは、バージョンが上がるにつれて後ろの引数が増えているので、先頭の引数の型で探す
        for (Method method : ConnectScreen.class.getMethods()) {
            final Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || types.length < 3) continue;
            if (types[0] != Screen.class || types[1] != Minecraft.class || types[2] != ServerAddress.class) continue;
            final Object[] arguments = new Object[types.length];
            arguments[0] = new TitleScreen();
            arguments[1] = mc;
            arguments[2] = ServerAddress.parseString(host + ":" + port);
            for (int i = 3; i < types.length; i++) {
                if (types[i] == boolean.class) arguments[i] = Boolean.FALSE;
            }
            Reflect.invoke(method, null, arguments);
            return true;
        }
        return false;
    }
}
'''
write("classic_ber", "Join.java", JOIN_LATER)
write("renderstate", "Join.java", JOIN_NONE)

# ---------------------------------------------------------------- tex


def tex_source(id_type, id_import, make_id, render_import, render_factory, texture_new, set_pixel, note):
    return f'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.marumasa_sign.core.MarumaSign;
import net.minecraft.client.Minecraft;
{render_import}
import net.minecraft.client.renderer.texture.DynamicTexture;
{id_import}

import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。{note}
 */
final class Tex {{

    private static final AtomicInteger nextId = new AtomicInteger();

    final {id_type} id;
    private final DynamicTexture texture;
    // ワールドに描くときの描画設定。文字用のものを使う。
    // 半透明に対応し、周囲の明るさは反映するが、面の向きによる陰影は付かない
    final RenderType renderType;

    private Tex({id_type} id, DynamicTexture texture) {{
        this.id = id;
        this.texture = texture;
        this.renderType = {render_factory}(id);
    }}

    private static void copy(NativeImage image, int width, int height, int[] argb) {{
        for (int y = 0; y < height; y++) {{
            final int row = y * width;
            for (int x = 0; x < width; x++) {{
                {set_pixel}
            }}
        }}
    }}

    static Tex create(Minecraft mc, int width, int height, int[] argb) {{
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final {id_type} id = {make_id}(MarumaSign.MOD_ID, "dynamic/" + nextId.incrementAndGet());
        final DynamicTexture texture = {texture_new};
        mc.getTextureManager().register(id, texture);
        return new Tex(id, texture);
    }}

    void update(int width, int height, int[] argb) {{
        copy(texture.getPixels(), width, height, argb);
        texture.upload();
    }}

    void release(Minecraft mc) {{
        mc.getTextureManager().release(id);
    }}
}}
'''


RL = ("ResourceLocation", "import net.minecraft.resources.ResourceLocation;")
ID = ("Identifier", "import net.minecraft.resources.Identifier;")
RT_OLD = ("import net.minecraft.client.renderer.RenderType;", "RenderType.text")
RT_NEW = ("import net.minecraft.client.renderer.rendertype.RenderType;\nimport net.minecraft.client.renderer.rendertype.RenderTypes;", "RenderTypes.text")
PIXEL_ARGB = "image.setPixel(x, y, argb[row + x]);"
# 古い API は ABGR の並びで受け取る
PIXEL_ABGR = ("final int color = argb[row + x];\n"
              "                image.setPixelRGBA(x, y, (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >> 16) & 0xFF));")

write("tex_5", "Tex.java", tex_source(*ID, "Identifier.fromNamespaceAndPath", *RT_NEW,
                                      'new DynamicTexture(() -> "marumasa_sign", image)', PIXEL_ARGB, "Minecraft 1.21.11 以降の作り方。"))
write("tex_4", "Tex.java", tex_source(*RL, "ResourceLocation.fromNamespaceAndPath", *RT_OLD,
                                      'new DynamicTexture(() -> "marumasa_sign", image)', PIXEL_ARGB, "Minecraft 1.21.5 - 1.21.10 の作り方。"))
write("tex_3", "Tex.java", tex_source(*RL, "ResourceLocation.fromNamespaceAndPath", *RT_OLD,
                                      "new DynamicTexture(image)", PIXEL_ARGB, "Minecraft 1.21.2 - 1.21.4 の作り方。"))
write("tex_2", "Tex.java", tex_source(*RL, "ResourceLocation.fromNamespaceAndPath", *RT_OLD,
                                      "new DynamicTexture(image)", PIXEL_ABGR, "Minecraft 1.21 - 1.21.1 の作り方。"))
TEX_1 = tex_source(*RL, "new ResourceLocation", *RT_OLD,
                   "new DynamicTexture(image)", PIXEL_ABGR, "Minecraft 1.20.6 以前の作り方。")
write("tex_1", "Tex.java", TEX_1)


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise SystemExit(f"expected exactly one {old!r}")
    return source.replace(old, new)


# 1.15 - 1.15.2: テクスチャを登録するメソッドの戻り値の型が 1.15.2 で変わったので、引数の型で探して呼ぶ
TEX_1R = replace_once(TEX_1, "        mc.getTextureManager().register(id, texture);\n", """        if (register == null) {
            register = Reflect.methodWithParameters(TextureManager.class, ResourceLocation.class, AbstractTexture.class);
        }
        Reflect.invoke(register, mc.getTextureManager(), id, texture);
""")
TEX_1R = replace_once(TEX_1R, "    private static final AtomicInteger nextId = new AtomicInteger();\n",
                      """    private static final AtomicInteger nextId = new AtomicInteger();
    // テクスチャを登録するメソッド。1.15.2 で戻り値の型が変わったので、どのバージョンでも動くように引数の型で探す
    private static Method register;
""")
TEX_1R = replace_once(TEX_1R, "import net.minecraft.client.renderer.texture.DynamicTexture;\n",
                      "import net.minecraft.client.renderer.texture.AbstractTexture;\n"
                      "import net.minecraft.client.renderer.texture.DynamicTexture;\n"
                      "import net.minecraft.client.renderer.texture.TextureManager;\n")
TEX_1R = replace_once(TEX_1R, "import marumasa.marumasa_sign.core.MarumaSign;\n",
                      "import marumasa.marumasa_sign.core.MarumaSign;\nimport marumasa.marumasa_sign.core.util.Reflect;\n")
TEX_1R = replace_once(TEX_1R, "import java.util.concurrent.atomic.AtomicInteger;\n",
                      "import java.lang.reflect.Method;\nimport java.util.concurrent.atomic.AtomicInteger;\n")
TEX_1R = replace_once(TEX_1R, "Minecraft 1.20.6 以前の作り方。", "Minecraft 1.15 - 1.15.2 の作り方。")
write("tex_1r", "Tex.java", TEX_1R)

# ---------------------------------------------------------------- classic

write("classic", "UiScreenBase.java", r'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.Window;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.ui.Key;
import marumasa.marumasa_sign.core.ui.UiRoot;
import net.minecraft.client.gui.screens.Screen;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MarumaSign の画面。入力を core の UiRoot に橋渡しする。
 * 描画のメソッドはバージョンによって引数が違うため、UiScreen 側で実装する。
 */
abstract class UiScreenBase extends Screen {

    protected final UiRoot ui = MarumaSign.ui();

    protected UiScreenBase() {
        super(Mc.text("MarumaSign"));
        ui.opened();
    }

    @Override
    protected void init() {
        final Window window = Mc.window(minecraft);
        ui.layout(window.getWidth(), window.getHeight());
    }

    @Override
    public void removed() {
        ui.closed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // 描画の直前に呼ぶ。画面に貼るテクスチャを返す (なければ null)
    protected final Tex prepare() {
        final Window window = Mc.window(minecraft);
        ui.layout(window.getWidth(), window.getHeight());
        ui.mouseMoved(mouseX(), mouseY());
        return (Tex) ui.texture();
    }

    // GUI の拡大率を打ち消して、ピクセル単位で描くための倍率
    protected final float unscale() {
        return (float) (1.0 / Mc.window(minecraft).getGuiScale());
    }

    // マウスの座標 (ウィンドウ上の位置) をフレームバッファのピクセル単位にする
    private double mouseX() {
        final Window window = Mc.window(minecraft);
        return minecraft.mouseHandler.xpos() * window.getWidth() / Math.max(1, window.getScreenWidth());
    }

    private double mouseY() {
        final Window window = Mc.window(minecraft);
        return minecraft.mouseHandler.ypos() * window.getHeight() / Math.max(1, window.getScreenHeight());
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) ui.mousePressed(mouseX(), mouseY());
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) ui.mouseReleased(mouseX(), mouseY());
        return true;
    }

    // スクロールのメソッドはバージョンによって引数の数が違う。
    // 両方を用意しておき、そのバージョンにあるほうが呼ばれる (そのため @Override は付けない)
    public boolean mouseScrolled(double x, double y, double amount) {
        ui.mouseScrolled(mouseX(), mouseY(), amount);
        return true;
    }

    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        ui.mouseScrolled(mouseX(), mouseY(), scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        final int key = Key.fromGlfw(keyCode);
        if (key != Key.UNKNOWN) ui.keyPressed(key, Key.modifiersFromGlfw(modifiers));
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        ui.charTyped(character);
        return true;
    }

    // ファイルのドロップは 1.15 以降にしかないため、@Override は付けない
    public void onFilesDrop(List<Path> paths) {
        final List<File> files = new ArrayList<File>();
        for (Path path : paths) {
            files.add(path.toFile());
        }
        ui.filesDropped(files);
    }
}
''')

write("classic_ber", "SignRendererBase.java", r'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 * render の引数はバージョンによって違うため、SignRendererWrapper 側で実装する。
 */
abstract class SignRendererBase implements BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    protected final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    protected SignRendererBase(BlockEntityRenderer<SignBlockEntity> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererBase) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> replaced = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?>>(renderers);
        replaced.put(Mc.SIGN, new SignRendererWrapper((BlockEntityRenderer<SignBlockEntity>) current));
        Reflect.set(renderersField, dispatcher, replaced);
    }

    /**
     * MarumaSign の看板なら画像を描く。
     * 看板そのものも描くべきなら true を返す (MarumaSign の看板でないとき、またはスペクテイターのとき)。
     */
    protected final boolean drawImage(SignBlockEntity sign, PoseStack poseStack, MultiBufferSource buffers, int light) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        if (image == null) return true;

        final BlockState blockState = sign.getBlockState();
        final boolean wall;
        final float yaw;
        if (blockState.getBlock() instanceof WallSignBlock) {
            wall = true;
            yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            wall = false;
            yaw = blockState.getValue(StandingSignBlock.ROTATION) * 22.5f;
        } else {
            return true;
        }

        for (SignImage.Batch batch : image.batches(yaw, wall, distance(sign))) {
            Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
        }
        MarumaSign.drawn(image);
        return mc.player != null && mc.player.isSpectator();
    }

    // カメラから看板までの距離 (ブロック)
    private double distance(SignBlockEntity sign) {
        final Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        final double x = sign.getBlockPos().getX() + 0.5 - camera.x;
        final double y = sign.getBlockPos().getY() + 0.5 - camera.y;
        final double z = sign.getBlockPos().getZ() + 0.5 - camera.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    // 以下のメソッドはバージョンによって有無や引数が違う。
    // 両方を用意しておき、そのバージョンにあるほうが使われる (そのため @Override は付けない)

    public boolean shouldRenderOffScreen() {
        return true;
    }

    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }

    public int getViewDistance() {
        return 512;
    }

    public boolean shouldRender(SignBlockEntity sign, Vec3 cameraPosition) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        final double distance = image == null ? 64.0 : image.viewDistance();
        final double x = sign.getBlockPos().getX() + 0.5 - cameraPosition.x;
        final double y = sign.getBlockPos().getY() + 0.5 - cameraPosition.y;
        final double z = sign.getBlockPos().getZ() + 0.5 - cameraPosition.z;
        return x * x + y * y + z * z < distance * distance;
    }
}
''')

# ---------------------------------------------------------------- per-era files


def screen_source(graphics_import, graphics_type, method, body):
    return f'''
package marumasa.marumasa_sign.adapter;

{graphics_import}

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {{

    @Override
    public void {method}({graphics_type} graphics, int mouseX, int mouseY, float partialTicks) {{
        final Tex texture = prepare();
        if (texture == null) return;

{body}
    }}
}}
'''


# 1.21.6 以降: 2 次元の行列スタックと、範囲を指定する blit
BLIT_2D = '''        final float unscale = unscale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(unscale, unscale);
        graphics.blit(texture.id,
                ui.originX(), ui.originY(), ui.originX() + ui.pixelWidth(), ui.originY() + ui.pixelHeight(),
                0f, 1f, 0f, 1f);
        graphics.pose().popMatrix();'''

SCREEN_EXTRACTOR = screen_source("import net.minecraft.client.gui.GuiGraphicsExtractor;", "GuiGraphicsExtractor", "extractRenderState", BLIT_2D)
SCREEN_GRAPHICS_2D = screen_source("import net.minecraft.client.gui.GuiGraphics;", "GuiGraphics", "render", BLIT_2D)


def wrapper_renderstate(camera_import, frustum):
    return f'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import {camera_import};
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * 看板のレンダラーを包むクラスの、描画の受け口。
 */
final class SignRendererWrapper extends SignRendererBase {{

    SignRendererWrapper(BlockEntityRenderer<SignBlockEntity, BlockEntityRenderState> delegate) {{
        super(delegate);
    }}

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {{
        if (drawsVanilla(state)) delegate.submit(state.inner, poseStack, collector, camera);
{frustum}
    }}
}}
'''


WRAPPER_RS_26 = wrapper_renderstate("net.minecraft.client.renderer.state.level.CameraRenderState",
                                    "        submitImage(state, poseStack, collector, camera.cullFrustum);")
WRAPPER_RS_121 = wrapper_renderstate("net.minecraft.client.renderer.state.CameraRenderState",
                                     "        // このバージョンのカメラの状態は視錐台を持たないため、カリングは描画側に任せる\n"
                                     "        submitImage(state, poseStack, collector, null);")


def wrapper_classic(extra_param, extra_arg, extra_import):
    return f'''
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
{extra_import}
/**
 * 看板のレンダラーを包むクラスの、描画の受け口。
 */
final class SignRendererWrapper extends SignRendererBase {{

    SignRendererWrapper(BlockEntityRenderer<SignBlockEntity> delegate) {{
        super(delegate);
    }}

    @Override
    public void render(SignBlockEntity sign, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay{extra_param}) {{
        if (drawImage(sign, poseStack, buffers, light)) {{
            delegate.render(sign, partialTicks, poseStack, buffers, light, overlay{extra_arg});
        }}
    }}
}}
'''


WRAPPER_CLASSIC_VEC3 = wrapper_classic(", Vec3 cameraPosition", ", cameraPosition", "import net.minecraft.world.phys.Vec3;\n")
WRAPPER_CLASSIC = wrapper_classic("", "", "")


def mc_source(title, imports, sign_type, screen_get, screen_set, chat, text, key, version, sign_text, edit_class, look, select, quads,
              window="mc.getWindow()"):
    quads_block = ""
    if quads:
        quads_block = f'''
    // 頂点を描画用のバッファに書き込む
    static void quads(VertexConsumer vertices, PoseStack poseStack, SignImage.Batch batch, int light) {{
{quads}
    }}
'''
    return f'''
package marumasa.marumasa_sign.adapter;

{imports}

/**
 * {title} に固有の部分。
 */
final class Mc {{

    // 看板のブロックエンティティタイプ
    static final BlockEntityType<?> SIGN = {sign_type};

    private static Field signMessages;

    private Mc() {{
    }}

    static String version() {{
        return {version};
    }}

    static Window window(Minecraft mc) {{
        return {window};
    }}

    static Screen screen(Minecraft mc) {{
        return {screen_get};
    }}

    static void setScreen(Minecraft mc, Screen screen) {{
        {screen_set};
    }}

    static Component text(String text) {{
        return {text};
    }}

    // チャット欄にクライアント側だけのメッセージを表示する
    static void chat(Minecraft mc, String message) {{
        {chat};
    }}

    static KeyMapping newKey(String name, int keyCode) {{
        return {key};
    }}

    // 看板に書いてある文字を 1 つの文字列にする
    static String signText(SignBlockEntity sign) {{
        final StringBuilder text = new StringBuilder();
{sign_text}
        return text.toString();
    }}

    static boolean isSignEdit(Screen screen) {{
        return screen instanceof {edit_class};
    }}

    // 看板の編集画面の内容を書き換える。画面を閉じるとサーバーへ送信される
    static void fillSign(Screen screen, String[] lines) {{
        if (signMessages == null) signMessages = Reflect.fieldOfType({edit_class}.class, String[].class);
        final String[] messages = Reflect.get(signMessages, screen);
        System.arraycopy(lines, 0, messages, 0, 4);
    }}
{quads_block}
    // ---------- 動作確認用 ----------

    static void look(Minecraft mc, float pitch) {{
        {look};
    }}

    // ホットバーにある看板を手に持つ
    static void selectSign(Minecraft mc) {{
        for (int slot = 0; slot < 9; slot++) {{
            if (mc.player.{select_item(select)}) {{
                {select};
            }}
        }}
    }}
}}
'''


def select_item(select):
    if ".inventory." in select:
        return "inventory.getItem(slot).getItem() == Items.OAK_SIGN"
    return "getInventory().getItem(slot).is(Items.OAK_SIGN)"


BASE_IMPORTS = [
    "com.mojang.blaze3d.platform.InputConstants",
    "com.mojang.blaze3d.platform.Window",
    "marumasa.marumasa_sign.core.util.Reflect",
    "net.minecraft.SharedConstants",
    "net.minecraft.client.KeyMapping",
    "net.minecraft.client.Minecraft",
    "net.minecraft.client.gui.screens.Screen",
    "net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen",
    "net.minecraft.network.chat.Component",
    "net.minecraft.world.item.Items",
    "net.minecraft.world.level.block.entity.BlockEntityType",
    "net.minecraft.world.level.block.entity.SignBlockEntity",
]


def imports(*extra, drop=()):
    names = sorted(set(BASE_IMPORTS + list(extra)) - set(drop))
    return "\n".join(f"import {name};" for name in names) + "\n\nimport java.lang.reflect.Field;"


SIGN_TEXT_FRONT_BACK = '''        final SignText front = sign.getFrontText();
        final SignText back = sign.getBackText();
        for (int i = 0; i < 4; i++) {
            text.append(front.getMessage(i, false).getString());
        }
        for (int i = 0; i < 4; i++) {
            text.append(back.getMessage(i, false).getString());
        }'''
SIGN_TEXT_SLOTS = '''        // 表、裏の順に並んでいる
        for (SignTextSlot slot : SignTextSlot.values()) {
            for (Component line : sign.getText(slot).getMessages(false)) {
                text.append(line.getString());
            }
        }'''
QUADS_ADD_VERTEX = '''        final PoseStack.Pose pose = poseStack.last();
        final float[] mesh = batch.vertices;
        final int[] colors = batch.colors;
        for (int i = 0; i < batch.vertexCount; i++) {
            final int o = i * SignImage.Batch.STRIDE;
            final int color = colors == null ? 0xFFFFFFFF : colors[i];
            vertices.addVertex(pose, mesh[o], mesh[o + 1], mesh[o + 2])
                    .setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24)
                    .setUv(mesh[o + 3], mesh[o + 4])
                    .setLight(light);
        }'''
CLASSIC_IMPORTS = ("com.mojang.blaze3d.vertex.PoseStack", "com.mojang.blaze3d.vertex.VertexConsumer",
                   "marumasa.marumasa_sign.core.sign.SignImage")
KEY_STRING_CATEGORY = 'new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, "key.categories.misc")'
KEY_CATEGORY = "new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, KeyMapping.Category.MISC)"
KEY_CATEGORY_263 = "new KeyMapping(name, InputConstants.Type.KEYBOARD, keyCode, KeyMapping.Category.MISC)"

ERAS = {
    # 26.3
    "v26_3": dict(
        mc=mc_source("Minecraft 26.3",
                     imports("net.minecraft.world.level.block.entity.BlockEntityTypes", "net.minecraft.world.level.block.entity.SignTextSlot"),
                     "BlockEntityTypes.SIGN", "mc.gui.screen()", "mc.gui.setScreen(screen)",
                     "mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_CATEGORY_263, "SharedConstants.getCurrentVersion().name()", SIGN_TEXT_SLOTS, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", "mc.player.getInventory().setSelectedSlot(slot)", None),
    ),
    # 26.2
    "v26_2": dict(
        mc=mc_source("Minecraft 26.2",
                     imports("net.minecraft.world.level.block.entity.BlockEntityTypes", "net.minecraft.world.level.block.entity.SignText"),
                     "BlockEntityTypes.SIGN", "mc.gui.screen()", "mc.gui.setScreen(screen)",
                     "mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_CATEGORY, "SharedConstants.getCurrentVersion().name()", SIGN_TEXT_FRONT_BACK, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", "mc.player.getInventory().setSelectedSlot(slot)", None),
    ),
    # 26.1 - 26.1.2
    "v26_1": dict(
        mc=mc_source("Minecraft 26.1 - 26.1.2",
                     imports("net.minecraft.world.level.block.entity.SignText"),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     "mc.gui.getChat().addClientSystemMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_CATEGORY, "SharedConstants.getCurrentVersion().name()", SIGN_TEXT_FRONT_BACK, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", "mc.player.getInventory().setSelectedSlot(slot)", None),
    ),
    # 1.21.9 - 1.21.11
    "v1_21_9": dict(
        mc=mc_source("Minecraft 1.21.9 - 1.21.11",
                     imports("net.minecraft.world.level.block.entity.SignText"),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     "mc.gui.getChat().addMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_CATEGORY, "SharedConstants.getCurrentVersion().name()", SIGN_TEXT_FRONT_BACK, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", "mc.player.getInventory().setSelectedSlot(slot)", None),
        screen=SCREEN_GRAPHICS_2D, wrapper=WRAPPER_RS_121,
    ),
    # 1.21.6 - 1.21.8
    "v1_21_6": dict(
        mc=mc_source("Minecraft 1.21.6 - 1.21.8",
                     imports("net.minecraft.world.level.block.entity.SignText", *CLASSIC_IMPORTS),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     "mc.gui.getChat().addMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_STRING_CATEGORY, "SharedConstants.getCurrentVersion().name()", SIGN_TEXT_FRONT_BACK, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", "mc.player.getInventory().setSelectedSlot(slot)", QUADS_ADD_VERTEX),
        screen=SCREEN_GRAPHICS_2D, wrapper=WRAPPER_CLASSIC_VEC3,
    ),
}

# ---- 1.20 - 1.21.5 ----

# 1.21.2 - 1.21.5: 描画設定を渡す blit
SCREEN_GRAPHICS_FN = screen_source(
    "import net.minecraft.client.gui.GuiGraphics;\nimport net.minecraft.client.renderer.RenderType;", "GuiGraphics", "render",
    """        final float unscale = unscale();
        graphics.pose().pushPose();
        graphics.pose().scale(unscale, unscale, 1f);
        graphics.blit(RenderType::guiTextured, texture.id, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        graphics.pose().popPose();""")
# 1.20 - 1.21.1: 半透明を有効にしてから描く
SCREEN_GRAPHICS_OLD = screen_source(
    "import com.mojang.blaze3d.systems.RenderSystem;\nimport net.minecraft.client.gui.GuiGraphics;", "GuiGraphics", "render",
    """        final float unscale = unscale();
        RenderSystem.enableBlend();
        graphics.pose().pushPose();
        graphics.pose().scale(unscale, unscale, 1f);
        graphics.blit(texture.id, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        graphics.pose().popPose();
        RenderSystem.disableBlend();""")

QUADS_VERTEX = """        final Matrix4f matrix = poseStack.last().pose();
        final float[] mesh = batch.vertices;
        final int[] colors = batch.colors;
        for (int i = 0; i < batch.vertexCount; i++) {
            final int o = i * SignImage.Batch.STRIDE;
            final int color = colors == null ? 0xFFFFFFFF : colors[i];
            vertices.vertex(matrix, mesh[o], mesh[o + 1], mesh[o + 2])
                    .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, color >>> 24)
                    .uv(mesh[o + 3], mesh[o + 4])
                    .uv2(light)
                    .endVertex();
        }"""
SELECT_FIELD = "mc.player.getInventory().selected = slot"
SELECT_METHOD = "mc.player.getInventory().setSelectedSlot(slot)"
VERSION_GET = "SharedConstants.getCurrentVersion().getName()"
VERSION_NAME = "SharedConstants.getCurrentVersion().name()"


def classic_mc(title, version, select, quads, *extra):
    return mc_source(title, imports("net.minecraft.world.level.block.entity.SignText", *CLASSIC_IMPORTS, *extra),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     "mc.gui.getChat().addMessage(Component.literal(message))", "Component.literal(text)",
                     KEY_STRING_CATEGORY, version, SIGN_TEXT_FRONT_BACK, "AbstractSignEditScreen",
                     "mc.player.setXRot(pitch)", select, quads)


ERAS.update({
    "v1_21_5": dict(mc=classic_mc("Minecraft 1.21.5", VERSION_GET, SELECT_METHOD, QUADS_ADD_VERTEX),
                    screen=SCREEN_GRAPHICS_FN, wrapper=WRAPPER_CLASSIC_VEC3),
    "v1_21_2": dict(mc=classic_mc("Minecraft 1.21.2 - 1.21.4", VERSION_GET, SELECT_FIELD, QUADS_ADD_VERTEX),
                    screen=SCREEN_GRAPHICS_FN, wrapper=WRAPPER_CLASSIC),
    "v1_21": dict(mc=classic_mc("Minecraft 1.21 - 1.21.1", VERSION_GET, SELECT_FIELD, QUADS_ADD_VERTEX),
                  screen=SCREEN_GRAPHICS_OLD, wrapper=WRAPPER_CLASSIC),
    "v1_20": dict(mc=classic_mc("Minecraft 1.20 - 1.20.6", VERSION_GET, SELECT_FIELD, QUADS_VERTEX, "org.joml.Matrix4f"),
                  screen=SCREEN_GRAPHICS_OLD, wrapper=WRAPPER_CLASSIC),
})
# ---- 1.17 - 1.19.4 ----

# 1.17 - 1.19.4: 行列スタックを直接受け取り、テクスチャを指定してから描く
SCREEN_POSE = """
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        RenderSystem.setShaderTexture(0, texture.id);
        RenderSystem.enableBlend();
        poseStack.pushPose();
        poseStack.scale(unscale, unscale, 1f);
        GuiComponent.blit(poseStack, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}
"""

SIGN_TEXT_SINGLE = """        for (int i = 0; i < 4; i++) {
            text.append(sign.getMessage(i, false).getString());
        }"""
LITERAL = ("mc.gui.getChat().addMessage(Component.literal(message))", "Component.literal(text)")
TEXT_COMPONENT = ("mc.gui.getChat().addMessage(new TextComponent(message))", "new TextComponent(text)")
SIGN_EDIT_OLD = "net.minecraft.client.gui.screens.inventory.SignEditScreen"
SIGN_EDIT_NEW = "net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen"


def single_sign_mc(title, component, edit_import, matrix_import, *extra):
    edit_class = edit_import.rsplit(".", 1)[1]
    return mc_source(title, imports(edit_import, matrix_import, *CLASSIC_IMPORTS, *extra, drop=(SIGN_EDIT_NEW,) if edit_import != SIGN_EDIT_NEW else ()),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     component[0], component[1],
                     KEY_STRING_CATEGORY, VERSION_GET, SIGN_TEXT_SINGLE, edit_class,
                     "mc.player.setXRot(pitch)", SELECT_FIELD, QUADS_VERTEX)


ERAS.update({
    "v1_19_3": dict(mc=single_sign_mc("Minecraft 1.19.3 - 1.19.4", LITERAL, SIGN_EDIT_NEW, "org.joml.Matrix4f"),
                    screen=SCREEN_POSE, wrapper=WRAPPER_CLASSIC),
    "v1_19": dict(mc=single_sign_mc("Minecraft 1.19 - 1.19.2", LITERAL, SIGN_EDIT_OLD, "com.mojang.math.Matrix4f"),
                  screen=SCREEN_POSE, wrapper=WRAPPER_CLASSIC),
    "v1_17": dict(mc=single_sign_mc("Minecraft 1.17 - 1.18.2", TEXT_COMPONENT, SIGN_EDIT_OLD, "com.mojang.math.Matrix4f",
                                    "net.minecraft.network.chat.TextComponent"),
                  screen=SCREEN_POSE, wrapper=WRAPPER_CLASSIC),
})

# ---- 1.15 - 1.16.5 ----

# 1.16.5 以前: 看板レンダラーは抽象クラスで、ディスパッチャーを受け取る
write("ber16", "SignRendererBase.java", r"""
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 */
final class SignRendererBase extends BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    private final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    private SignRendererBase(BlockEntityRenderDispatcher dispatcher, BlockEntityRenderer<SignBlockEntity> delegate) {
        super(dispatcher);
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = BlockEntityRenderDispatcher.instance;
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(Mc.SIGN);
        if (current == null || current instanceof SignRendererBase) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<BlockEntityType<?>, BlockEntityRenderer<?>> replaced = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?>>(renderers);
        replaced.put(Mc.SIGN, new SignRendererBase(dispatcher, (BlockEntityRenderer<SignBlockEntity>) current));
        Reflect.set(renderersField, dispatcher, replaced);
    }

    @Override
    public void render(SignBlockEntity sign, float partialTicks, PoseStack poseStack, MultiBufferSource buffers,
                       int light, int overlay) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        // スペクテイターには看板そのものも見せる
        if (image == null || (mc.player != null && mc.player.isSpectator())) {
            delegate.render(sign, partialTicks, poseStack, buffers, light, overlay);
        }
        if (image == null) return;

        final BlockState blockState = sign.getBlockState();
        final boolean wall;
        final float yaw;
        if (blockState.getBlock() instanceof WallSignBlock) {
            wall = true;
            yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            wall = false;
            yaw = blockState.getValue(StandingSignBlock.ROTATION) * 22.5f;
        } else {
            return;
        }

        for (SignImage.Batch batch : image.batches(yaw, wall, distance(sign))) {
            Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
        }
        MarumaSign.drawn(image);
    }

    // カメラから看板までの距離 (ブロック)
    private double distance(SignBlockEntity sign) {
        final Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        final double x = sign.getBlockPos().getX() + 0.5 - camera.x;
        final double y = sign.getBlockPos().getY() + 0.5 - camera.y;
        final double z = sign.getBlockPos().getZ() + 0.5 - camera.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Override
    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }
}
""")

# 1.16: 行列スタックを受け取り、テクスチャを結び付けてから描く
SCREEN_116 = """
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        minecraft.getTextureManager().bind(texture.id);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        poseStack.pushPose();
        poseStack.scale(unscale, unscale, 1f);
        GuiComponent.blit(poseStack, ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        poseStack.popPose();
        RenderSystem.disableBlend();
    }
}
"""
# 1.15: 行列スタックを受け取らない
SCREEN_115 = """
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        minecraft.getTextureManager().bind(texture.id);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.pushMatrix();
        RenderSystem.scalef(unscale, unscale, 1f);
        blit(ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        RenderSystem.popMatrix();
        RenderSystem.disableBlend();
    }
}
"""

SIGN_TEXT_OLD = """        for (int i = 0; i < 4; i++) {
            text.append(sign.getMessage(i).getString());
        }"""
SELECT_OLD = "mc.player.inventory.selected = slot"


def old_mc(title):
    return mc_source(title,
                     imports(SIGN_EDIT_OLD, "com.mojang.math.Matrix4f", "net.minecraft.network.chat.TextComponent",
                             *CLASSIC_IMPORTS, drop=(SIGN_EDIT_NEW,)),
                     "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                     TEXT_COMPONENT[0], TEXT_COMPONENT[1],
                     KEY_STRING_CATEGORY, VERSION_GET, SIGN_TEXT_OLD, "SignEditScreen",
                     "mc.player.xRot = pitch", SELECT_OLD, QUADS_VERTEX)


# 1.16.1 以前の編集画面は、看板のブロックエンティティを直接書き換える
FILL_VIA_MESSAGES = """        if (signMessages == null) signMessages = Reflect.fieldOfType(SignEditScreen.class, String[].class);
        final String[] messages = Reflect.get(signMessages, screen);
        System.arraycopy(lines, 0, messages, 0, 4);"""
FILL_VIA_SIGN = """        if (signMessages == null) signMessages = Reflect.fieldOfType(SignEditScreen.class, SignBlockEntity.class);
        final SignBlockEntity sign = Reflect.get(signMessages, screen);
        for (int i = 0; i < 4; i++) {
            sign.setMessage(i, new TextComponent(lines[i]));
        }"""


def old_mc_direct(title):
    source = old_mc(title)
    if source.count(FILL_VIA_MESSAGES) != 1:
        raise SystemExit("fillSign body not found")
    return (source.replace(FILL_VIA_MESSAGES, FILL_VIA_SIGN)
            .replace("    private static Field signMessages;", "    // 編集画面が持っている看板のフィールド\n    private static Field signMessages;"))


# 1.16 系には看板の文字を取り出すメソッドがないため、フィールドから読む
SIGN_TEXT_FIELD = """        if (signLines == null) signLines = Reflect.fieldOfType(SignBlockEntity.class, Component[].class);
        final Component[] lines = Reflect.get(signLines, sign);
        for (Component line : lines) {
            if (line != null) text.append(line.getString());
        }"""


def mc_116():
    source = old_mc("Minecraft 1.16 - 1.16.5")
    if source.count(SIGN_TEXT_OLD) != 1:
        raise SystemExit("signText body not found")
    return (source.replace(SIGN_TEXT_OLD, SIGN_TEXT_FIELD)
            .replace("    private static Field signMessages;", "    private static Field signMessages;\n    private static Field signLines;"))


ERAS.update({
    "v1_16": dict(mc=mc_116(), screen=SCREEN_116),
    "v1_15": dict(mc=old_mc_direct("Minecraft 1.15 - 1.15.2"), screen=SCREEN_115),
})

# ---- 1.14 ----

# 1.14: 描画設定の仕組みがまだないので、テクスチャの識別子だけを持つ
write("tex_0", "Tex.java", r"""
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.marumasa_sign.core.MarumaSign;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。Minecraft 1.14 の作り方。
 */
final class Tex {

    private static final AtomicInteger nextId = new AtomicInteger();

    final ResourceLocation id;
    private final DynamicTexture texture;

    private Tex(ResourceLocation id, DynamicTexture texture) {
        this.id = id;
        this.texture = texture;
    }

    private static void copy(NativeImage image, int width, int height, int[] argb) {
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                // この API は ABGR の並びで受け取る
                final int color = argb[row + x];
                image.setPixelRGBA(x, y, (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >> 16) & 0xFF));
            }
        }
    }

    static Tex create(Minecraft mc, int width, int height, int[] argb) {
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final ResourceLocation id = new ResourceLocation(MarumaSign.MOD_ID, "dynamic/" + nextId.incrementAndGet());
        final DynamicTexture texture = new DynamicTexture(image);
        mc.getTextureManager().register(id, texture);
        return new Tex(id, texture);
    }

    void update(int width, int height, int[] argb) {
        copy(texture.getPixels(), width, height, argb);
        texture.upload();
    }

    void release(Minecraft mc) {
        mc.getTextureManager().release(id);
    }
}
""")

# 1.14: 看板レンダラーはカメラからの位置を直接受け取り、OpenGL で描く
write("ber14", "SignRendererBase.java", r"""
package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 看板のレンダラーを包んで、MarumaSign の看板なら看板の代わりに画像を描く。
 * 元のレンダラーをそのまま使うので、他の Mod が差し替えたレンダラーとも共存できる。
 */
final class SignRendererBase extends BlockEntityRenderer<SignBlockEntity> {

    private static Field renderersField;

    private final BlockEntityRenderer<SignBlockEntity> delegate;
    private final Minecraft mc = Minecraft.getInstance();

    private SignRendererBase(BlockEntityRenderer<SignBlockEntity> delegate) {
        this.delegate = delegate;
    }

    // 看板のレンダラーがまだ包まれていなければ包む
    @SuppressWarnings("unchecked")
    static void install(Minecraft mc) {
        final BlockEntityRenderDispatcher dispatcher = BlockEntityRenderDispatcher.instance;
        if (dispatcher == null) return;
        if (renderersField == null) renderersField = Reflect.fieldOfType(BlockEntityRenderDispatcher.class, Map.class);
        // このバージョンでは、ブロックエンティティのクラスをキーにした表になっている
        final Map<Class<?>, BlockEntityRenderer<?>> renderers = Reflect.get(renderersField, dispatcher);
        if (renderers == null) return;
        final BlockEntityRenderer<?> current = renderers.get(SignBlockEntity.class);
        if (current == null || current instanceof SignRendererBase) return;

        final SignRendererBase wrapper = new SignRendererBase((BlockEntityRenderer<SignBlockEntity>) current);
        wrapper.init(dispatcher);
        renderers.put(SignBlockEntity.class, wrapper);
    }

    @Override
    public void render(SignBlockEntity sign, double x, double y, double z, float partialTicks, int destroyStage) {
        final SignImage image = MarumaSign.sign(Mc.signText(sign));
        // スペクテイターには看板そのものも見せる
        if (image == null || (mc.player != null && mc.player.isSpectator())) {
            delegate.render(sign, x, y, z, partialTicks, destroyStage);
        }
        if (image == null || destroyStage >= 0) return;

        final BlockState blockState = sign.getBlockState();
        final boolean wall;
        final float yaw;
        if (blockState.getBlock() instanceof WallSignBlock) {
            wall = true;
            yaw = blockState.getValue(WallSignBlock.FACING).toYRot();
        } else if (blockState.getBlock() instanceof StandingSignBlock) {
            wall = false;
            yaw = blockState.getValue(StandingSignBlock.ROTATION) * 22.5f;
        } else {
            return;
        }
        final SignImage.Batch[] batches = image.batches(yaw, wall, Math.sqrt(x * x + y * y + z * z));

        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        drawBatches(batches);
        GL11.glPopMatrix();
        MarumaSign.drawn(image);
    }

    /**
     * 四角形のまとまりを、現在の座標系に描く。看板のアイテムを描くときにも使う。
     */
    static void drawBatches(SignImage.Batch[] batches) {
        // 変更した状態は glPopAttrib で元に戻す
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        for (SignImage.Batch batch : batches) {
            Minecraft.getInstance().getTextureManager().bind(((Tex) batch.texture).id);
            if (batch.colors != null) {
                // 3D モデルは裏を向いた面と、透明な部分を描かない
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                GL11.glDepthMask(!batch.blend);
                ModelArrays.draw(batch);
                continue;
            }
            final float[] mesh = batch.vertices;
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < batch.vertexCount; i++) {
                final int o = i * SignImage.Batch.STRIDE;
                if ((i & 3) == 0 && i + 2 < batch.vertexCount) {
                    // 面の向き。影 Mod が光の当たり方を計算するのに使う (通常の描画では使われない)
                    final int b = o + SignImage.Batch.STRIDE, c = b + SignImage.Batch.STRIDE;
                    final float ux = mesh[b] - mesh[o], uy = mesh[b + 1] - mesh[o + 1], uz = mesh[b + 2] - mesh[o + 2];
                    final float vx = mesh[c] - mesh[o], vy = mesh[c + 1] - mesh[o + 1], vz = mesh[c + 2] - mesh[o + 2];
                    final float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                    final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (length > 0) GL11.glNormal3f(nx / length, ny / length, nz / length);
                }
                GL11.glTexCoord2f(mesh[o + 3], mesh[o + 4]);
                GL11.glVertex3f(mesh[o], mesh[o + 1], mesh[o + 2]);
            }
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    @Override
    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return true;
    }
}
""")

write("ber14", "ModelArrays.java", r"""
package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.sign.SignImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 3D モデルの頂点を、配列にまとめて OpenGL に渡す。
 * 頂点が多いので、1 つずつ渡すと時間がかかる。
 */
final class ModelArrays {

    private static final int BYTES_PER_VERTEX = SignImage.Batch.STRIDE * 4;

    private static final class Buffers {
        final FloatBuffer vertices;
        // 面の向き。影 Mod が光の当たり方を計算するのに使う
        final FloatBuffer normals;
        final ByteBuffer colors;
        int revision = -1;

        Buffers(int vertexCount) {
            vertices = BufferUtils.createFloatBuffer(vertexCount * SignImage.Batch.STRIDE);
            normals = BufferUtils.createFloatBuffer(vertexCount * 3);
            colors = BufferUtils.createByteBuffer(vertexCount * 4);
        }
    }

    // まとまりごとのバッファ。まとまりが使われなくなれば、一緒に破棄される
    private static final Map<SignImage.Batch, Buffers> BUFFERS = new WeakHashMap<SignImage.Batch, Buffers>();

    private ModelArrays() {
    }

    static void draw(SignImage.Batch batch) {
        Buffers buffers = BUFFERS.get(batch);
        if (buffers == null) {
            buffers = new Buffers(batch.vertexCount);
            BUFFERS.put(batch, buffers);
        }
        if (buffers.revision != batch.revision) {
            // 内容が変わったときだけ詰め直す。
            // clear などは、新しい Java とは戻り値の型が違うので、どの Java でも同じ呼び出しになるよう Buffer として呼ぶ
            ((Buffer) buffers.vertices).clear();
            buffers.vertices.put(batch.vertices, 0, batch.vertexCount * SignImage.Batch.STRIDE);
            ((Buffer) buffers.colors).clear();
            final int[] colors = batch.colors;
            for (int i = 0; i < batch.vertexCount; i++) {
                final int color = colors[i];
                buffers.colors.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) (color >>> 24));
            }
            ((Buffer) buffers.colors).flip();
            fillNormals(batch, buffers.normals);
            buffers.revision = batch.revision;
        }

        // 変更した状態は glPopClientAttrib で元に戻す
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        // 位置とテクスチャ座標は、同じバッファに交互に入っている
        ((Buffer) buffers.vertices).position(0);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.vertices).position(3);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, buffers.vertices);
        ((Buffer) buffers.colors).position(0);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 0, buffers.colors);
        GL11.glNormalPointer(GL11.GL_FLOAT, 0, buffers.normals);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, batch.vertexCount);
        GL11.glPopClientAttrib();
    }

    // 四角形ごとに、面の向きを求めて入れる。三角形は 3 つ目の頂点を重ねた四角形なので、最初の 3 頂点から求める
    private static void fillNormals(SignImage.Batch batch, FloatBuffer normals) {
        final float[] mesh = batch.vertices;
        ((Buffer) normals).clear();
        for (int i = 0; i + 3 < batch.vertexCount; i += 4) {
            final int a = i * SignImage.Batch.STRIDE, b = a + SignImage.Batch.STRIDE, c = b + SignImage.Batch.STRIDE;
            final float ux = mesh[b] - mesh[a], uy = mesh[b + 1] - mesh[a + 1], uz = mesh[b + 2] - mesh[a + 2];
            final float vx = mesh[c] - mesh[a], vy = mesh[c + 1] - mesh[a + 1], vz = mesh[c + 2] - mesh[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 0) {
                nx /= length;
                ny /= length;
                nz /= length;
            } else {
                // 面積のない面は、上向きとして扱う
                ny = 1;
            }
            for (int k = 0; k < 4; k++) normals.put(nx).put(ny).put(nz);
        }
        ((Buffer) normals).flip();
    }
}
""")

# 1.14: 状態の変更は GlStateManager 経由で行う
SCREEN_114 = """
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * MarumaSign の画面の描画部分。
 */
final class UiScreen extends UiScreenBase {

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        final Tex texture = prepare();
        if (texture == null) return;

        final float unscale = unscale();
        minecraft.getTextureManager().bind(texture.id);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        GlStateManager.pushMatrix();
        GlStateManager.scalef(unscale, unscale, 1f);
        blit(ui.originX(), ui.originY(), 0f, 0f,
                ui.pixelWidth(), ui.pixelHeight(), ui.pixelWidth(), ui.pixelHeight());
        GlStateManager.popMatrix();
        GlStateManager.disableBlend();
    }
}
"""


def mc_114():
    source = mc_source("Minecraft 1.14 - 1.14.4",
                       imports(SIGN_EDIT_OLD, "net.minecraft.network.chat.TextComponent", drop=(SIGN_EDIT_NEW,)),
                       "BlockEntityType.SIGN", "mc.screen", "mc.setScreen(screen)",
                       TEXT_COMPONENT[0], TEXT_COMPONENT[1],
                       KEY_STRING_CATEGORY, VERSION_GET, SIGN_TEXT_OLD, "SignEditScreen",
                       "mc.player.xRot = pitch", SELECT_OLD, None, window="mc.window")
    if source.count(FILL_VIA_MESSAGES) != 1:
        raise SystemExit("fillSign body not found")
    return source.replace(FILL_VIA_MESSAGES, FILL_VIA_SIGN)


ERAS.update({
    "v1_14": dict(mc=mc_114(), screen=SCREEN_114),
})

# Forge の 1.15 - 1.19.4 は、カメラの向きが軸にぴったり沿っているとき (真正面を水平に見ているときなど) に、
# 看板などの「描く範囲に限りがない」ブロックエンティティを、画面に入っていないものとして扱ってしまう
# (範囲の判定で 0 と無限大を掛けることになり、結果が数として扱えなくなるため。Mod を入れていなくても起きる)。
# 看板の絵が突然消えて見えるので、向きがぴったりのときは、見た目に分からない程度だけずらす。
# Forge 1.19.3 は判定の計算が変わっていて (1.19.4 で Forge 側が直した)、ぴったりでなくても 0.02 度ほどまでは同じことが起きる。
ANGLES_FIELDS = """
    // Forge は、カメラの向きが軸にぴったり沿っていると看板を描かないことがあるので、見た目に分からない程度だけずらす
    static void avoidExactAngles(Minecraft mc) {
        mc.player.xRot = awayFromAxis(mc.player.xRot, true);
        mc.player.yRot = awayFromAxis(mc.player.yRot, false);
    }

    // 90 度の倍数から 0.05 度以内なら、0.05 度だけ離す (画面では 1 ピクセルほどの違い)。
    // これより小さいずれは、向きを行列にする計算の途中で丸められて消えることがある
    private static float awayFromAxis(float degrees, boolean vertical) {
        final float nearest = Math.round(degrees / 90f) * 90f;
        final float offset = degrees - nearest;
        if (Math.abs(offset) >= 0.05f) return degrees;
        // 上下の向きは、真上と真下を超えないように内側へずらす
        if (vertical && nearest != 0f) return nearest > 0f ? nearest - 0.05f : nearest + 0.05f;
        return nearest + (offset < 0f ? -0.05f : 0.05f);
    }
"""
ANGLES_METHODS = """
    // Forge は、カメラの向きが軸にぴったり沿っていると看板を描かないことがあるので、見た目に分からない程度だけずらす
    static void avoidExactAngles(Minecraft mc) {
        mc.player.setXRot(awayFromAxis(mc.player.getXRot(), true));
        mc.player.setYRot(awayFromAxis(mc.player.getYRot(), false));
    }

    // 90 度の倍数から 0.05 度以内なら、0.05 度だけ離す (画面では 1 ピクセルほどの違い)。
    // これより小さいずれは、向きを行列にする計算の途中で丸められて消えることがある
    private static float awayFromAxis(float degrees, boolean vertical) {
        final float nearest = Math.round(degrees / 90f) * 90f;
        final float offset = degrees - nearest;
        if (Math.abs(offset) >= 0.05f) return degrees;
        // 上下の向きは、真上と真下を超えないように内側へずらす
        if (vertical && nearest != 0f) return nearest > 0f ? nearest - 0.05f : nearest + 0.05f;
        return nearest + (offset < 0f ? -0.05f : 0.05f);
    }
"""
ANGLES_NONE = """
    // このバージョンでは、カメラの向きによって看板が描かれなくなる問題はない
    static void avoidExactAngles(Minecraft mc) {
    }
"""
ANGLES = {"v1_15": ANGLES_FIELDS, "v1_16": ANGLES_FIELDS, "v1_17": ANGLES_METHODS, "v1_19": ANGLES_METHODS,
          "v1_19_3": ANGLES_METHODS}


def with_angles(era, source):
    """Mc の最後に、カメラの向きをずらすメソッドを足す。"""
    end = source.rstrip().rfind("}")
    return source[:end].rstrip("\n") + "\n" + ANGLES.get(era, ANGLES_NONE) + "}\n"


for era, files in ERAS.items():
    write(era, "Mc.java", with_angles(era, files["mc"]))
    if "screen" in files:
        write(era, "UiScreen.java", files["screen"])
    if "wrapper" in files:
        write(era, "SignRendererWrapper.java", files["wrapper"])

# ---------------------------------------------------------------- 看板アイテムの見た目

def item_model_source(title, read_lines, replace_layers, extra_fields, animated, extents):
    """1.21.4 - 1.21.8: アイテムのモデルの表にある看板アイテムのモデルを包む。"""
    return f"""
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.StandingSignBlock;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 看板の文字を持っている看板アイテムを、その看板と同じ内容 (画像・動画・3D モデル) で描く。({title})
 * アイテムのモデルの表にある看板アイテムのモデルを包み、MarumaSign の看板のデータを持つときだけ描き方を差し替える。
 */
final class SignItems {{

    private static Field modelsField;
    private static Field layersField;
    private static Field layerCountField;{extra_fields}

    private SignItems() {{
    }}

    // 看板アイテムのモデルがまだ包まれていなければ包む。リソースの再読み込みで元に戻るため、毎 tick 確認する
    static void install(Minecraft mc) {{
        // アイテムの情報は、ワールドに入るまで読めないバージョンがある
        if (mc.level == null) return;
        final ModelManager manager = mc.getModelManager();
        if (manager == null) return;

        // 看板アイテムと、そのモデルの名前
        final List<Object> ids = new ArrayList<Object>();
        for (Item item : BuiltInRegistries.ITEM) {{
            // 立て看板になるアイテムだけを対象にする (吊り看板は対象外)
            if (!(item instanceof BlockItem) || !(((BlockItem) item).getBlock() instanceof StandingSignBlock)) continue;
            final Object id = new ItemStack(item).get(DataComponents.ITEM_MODEL);
            if (id != null) ids.add(id);
        }}
        if (ids.isEmpty()) return;

        if (modelsField == null) {{
            // アイテムのモデルの表は、同じ型のフィールドがほかにもあるので、中身で見分ける
            for (Field field : ModelManager.class.getDeclaredFields()) {{
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                final Map<Object, Object> map = Reflect.get(field, manager);
                if (map != null && map.get(ids.get(0)) instanceof ItemModel) {{
                    modelsField = field;
                    break;
                }}
            }}
            // まだモデルが読み込まれていない
            if (modelsField == null) return;
        }}

        final Map<Object, ItemModel> models = Reflect.get(modelsField, manager);
        if (models == null) return;
        boolean wrapped = true;
        for (Object id : ids) {{
            final ItemModel model = models.get(id);
            if (model != null && !(model instanceof Model)) wrapped = false;
        }}
        if (wrapped) return;

        // 元の表は変更できないことがあるので、写しを作って差し替える
        final Map<Object, ItemModel> replaced = new HashMap<Object, ItemModel>(models);
        for (Object id : ids) {{
            final ItemModel model = replaced.get(id);
            if (model != null && !(model instanceof Model)) replaced.put(id, new Model(model));
        }}
        Reflect.set(modelsField, manager, replaced);
    }}

    // アイテムが持っている看板のデータから、表示する内容を探す。MarumaSign の看板でなければ null
    static SignImage find(ItemStack stack) {{
        final CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) return null;
        final CompoundTag tag = data.getUnsafe();
        final String[] lines = new String[8];
{read_lines}
        return MarumaSign.itemSign(lines);
    }}

    /**
     * 看板アイテムのモデルを包み、MarumaSign の看板のデータを持つときだけ、中身を画像に差し替える。
     */
    private static final class Model implements ItemModel {{

        private final ItemModel delegate;

        Model(ItemModel delegate) {{
            this.delegate = delegate;
        }}

        @Override
        public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
                           ClientLevel level, LivingEntity entity, int seed) {{
            final SignImage image = find(stack);
            if (image == null) {{
                delegate.update(state, stack, resolver, context, level, entity, seed);
                return;
            }}

            // 元のモデルに層を作らせて、手に持ったときや枠の中での位置と向きをそのまま使う
            if (layersField == null) {{
                layersField = Reflect.fieldOfType(ItemStackRenderState.class, ItemStackRenderState.LayerRenderState[].class);
                layerCountField = Reflect.fieldOfType(ItemStackRenderState.class, int.class);
            }}
            final int before = Reflect.<Integer>get(layerCountField, state);
            delegate.update(state, stack, resolver, context, level, entity, seed);
            final int after = Reflect.<Integer>get(layerCountField, state);
            final ItemStackRenderState.LayerRenderState[] layers = Reflect.get(layersField, state);

{replace_layers}{animated}
        }}
    }}

    /**
     * 画像を描く部分。
     */
    private static final class Renderer implements SpecialModelRenderer<SignImage> {{

        static final Renderer INSTANCE = new Renderer();

        @Override
        public void render(SignImage image, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
                           int light, int overlay, boolean foil) {{
            if (image == null) return;
            // 距離は分からないので、手元にあるものとして扱う
            for (SignImage.Batch batch : image.itemBatches(1.0)) {{
                Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
            }}
            MarumaSign.drawn(image);
        }}
{extents}
        @Override
        public SignImage extractArgument(ItemStack stack) {{
            return find(stack);
        }}
    }}
}}
"""


# 1.21.4 まで: 文字は JSON の文字列として保存されている
ITEM_LINES_OLD = """        readLines(tag.getCompound("front_text"), lines, 0);
        readLines(tag.getCompound("back_text"), lines, 4);
        return MarumaSign.itemSign(lines);
    }

    private static void readLines(CompoundTag text, String[] lines, int offset) {
        final ListTag messages = text.getList("messages", Tag.TAG_STRING);
        for (int i = 0; i < 4 && i < messages.size(); i++) {
            lines[offset + i] = messages.getString(i);
        }"""
# 1.21.5 以降: 文字だけの行は文字列、装飾のある行は複合タグ
ITEM_LINES_NEW = """        readLines(tag.getCompoundOrEmpty("front_text"), lines, 0);
        readLines(tag.getCompoundOrEmpty("back_text"), lines, 4);
        return MarumaSign.itemSign(lines);
    }

    private static void readLines(CompoundTag text, String[] lines, int offset) {
        final ListTag messages = text.getListOrEmpty("messages");
        for (int i = 0; i < 4 && i < messages.size(); i++) {
            final Tag message = messages.get(i);
            // 文字だけの行は文字列で、装飾のある行は複合タグで保存されている
            lines[offset + i] = message instanceof StringTag ? ((StringTag) message).value() : String.valueOf(message);
        }"""
# 1.21.4: 層は、位置と向きを決めるためのモデルを持つ
ITEM_LAYERS_MODEL = """            if (after > before) {
                // 元の絵 (板の形) は描かず、最初の層で画像を描く。位置と向きは、元のモデルのものを使う
                if (layerModelField == null) layerModelField = Reflect.fieldOfType(ItemStackRenderState.LayerRenderState.class, BakedModel.class);
                final BakedModel model = Reflect.get(layerModelField, layers[before]);
                layers[before].setupSpecialModel(Renderer.INSTANCE, image, model);
                // 残りの層には何も描かせない
                for (int i = before + 1; i < after; i++) layers[i].setupSpecialModel(Renderer.INSTANCE, null, model);
            }"""
# 1.21.5 以降: 層は、描く面の一覧を持つ
ITEM_LAYERS_QUADS = """            if (after > before) {
                // 元の絵 (板の形) は描かず、最初の層で画像を描く。残りの層には何も描かせない
                layers[before].setupSpecialModel(Renderer.INSTANCE, image);
                for (int i = before + 1; i < after; i++) layers[i].setupSpecialModel(Renderer.INSTANCE, null);
            } else {
                state.newLayer().setupSpecialModel(Renderer.INSTANCE, image);
            }"""
ITEM_ANIMATED = """
            // 動画などで内容が変わるので、枠の中の絵を使い回さず毎回描き直してもらう
            state.appendModelIdentityElement(image);
            state.setAnimated();"""
ITEM_EXTENTS = """
        // アイテムが占める範囲。一辺 1 の立方体の中に収まる
        @Override
        public void getExtents(Set<Vector3f> output) {
            output.add(new Vector3f(0f, 0f, 0f));
            output.add(new Vector3f(1f, 1f, 1f));
        }
"""


def item_model(title, read_lines, replace_layers, extra_fields="", animated="", extents=""):
    source = item_model_source(title, "{LINES}", replace_layers, extra_fields, animated, extents)
    # 行の読み取りは、メソッドの終わりまでを含む形で差し込む
    marker = "{LINES}\n        return MarumaSign.itemSign(lines);\n    }"
    if source.count(marker) != 1:
        raise SystemExit("line reader marker not found")
    source = source.replace(marker, read_lines + "\n    }")
    if "Set<Vector3f>" not in source:
        source = source.replace("import org.joml.Vector3f;\n\n", "\n").replace("import java.util.Set;\n", "")
    if "BakedModel" in source:
        source = source.replace("import net.minecraft.client.resources.model.ModelManager;\n",
                                "import net.minecraft.client.resources.model.BakedModel;\nimport net.minecraft.client.resources.model.ModelManager;\n")
    if "StringTag" not in source.split("final class SignItems")[1]:
        source = source.replace("import net.minecraft.nbt.StringTag;\n", "")
    return source


write("item_model_1", "SignItems.java", item_model(
    "Minecraft 1.21.4", ITEM_LINES_OLD, ITEM_LAYERS_MODEL, extra_fields="\n    private static Field layerModelField;"))
write("item_model_2", "SignItems.java", item_model("Minecraft 1.21.5", ITEM_LINES_NEW, ITEM_LAYERS_QUADS))
write("item_model_3", "SignItems.java", item_model(
    "Minecraft 1.21.6 - 1.21.8", ITEM_LINES_NEW, ITEM_LAYERS_QUADS, animated=ITEM_ANIMATED, extents=ITEM_EXTENTS))


def item_bewlr_source(title, context_import, context_type, built_in, shaper_methods, shaper_imports):
    """1.17 - 1.21.3: アイテムのモデルを選ぶ部分と、専用の描画を行う部分を包む。"""
    return f"""
package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.renderer.MultiBufferSource;
{context_import}
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
{shaper_imports}import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StandingSignBlock;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 看板の文字を持っている看板アイテムを、その看板と同じ内容 (画像・動画・3D モデル) で描く。({title})
 * アイテムのモデルを選ぶ部分を包んで、MarumaSign の看板のデータを持つアイテムには「専用の描画を行うモデル」を返し、
 * 専用の描画を行う部分を包んで、そのアイテムのときに画像を描く。
 */
final class SignItems {{

    private static Field shaperField;
    private static Field rendererField;

    private SignItems() {{
    }}

    // まだ包まれていなければ包む。毎 tick 確認する
    static void install(Minecraft mc) {{
        final ItemRenderer itemRenderer = mc.getItemRenderer();
        if (itemRenderer == null) return;

        if (rendererField == null) {{
            rendererField = Reflect.fieldOfType(ItemRenderer.class, BlockEntityWithoutLevelRenderer.class);
            shaperField = Reflect.fieldOfType(ItemRenderer.class, ItemModelShaper.class);
        }}
        final BlockEntityWithoutLevelRenderer renderer = Reflect.get(rendererField, itemRenderer);
        if (renderer != null && !(renderer instanceof Renderer)) {{
            Reflect.set(rendererField, itemRenderer, new Renderer(renderer, mc));
        }}
        final ItemModelShaper shaper = Reflect.get(shaperField, itemRenderer);
        if (shaper != null && !(shaper instanceof Shaper)) {{
            Reflect.set(shaperField, itemRenderer, new Shaper(shaper, mc));
        }}
    }}

    // アイテムが持っている看板のデータから、表示する内容を探す。MarumaSign の看板でなければ null
    static SignImage find(ItemStack stack) {{
        // 立て看板になるアイテムだけを対象にする (吊り看板は対象外)
        final Item item = stack.getItem();
        if (!(item instanceof BlockItem) || !(((BlockItem) item).getBlock() instanceof StandingSignBlock)) return null;
        final CompoundTag tag = SignItemData.blockEntityTag(stack);
        if (tag == null) return null;

        final String[] lines = new String[8];
        if (tag.contains("front_text")) {{
            // 1.20 以降: 表と裏
            readLines(tag.getCompound("front_text"), lines, 0);
            readLines(tag.getCompound("back_text"), lines, 4);
        }} else {{
            for (int i = 0; i < 4; i++) {{
                lines[i] = tag.getString("Text" + (i + 1));
            }}
        }}
        return MarumaSign.itemSign(lines);
    }}

    private static void readLines(CompoundTag text, String[] lines, int offset) {{
        // 8 は文字列のタグの種類
        final ListTag messages = text.getList("messages", 8);
        for (int i = 0; i < 4 && i < messages.size(); i++) {{
            lines[offset + i] = messages.getString(i);
        }}
    }}

    /**
     * アイテムのモデルを選ぶ部分。MarumaSign の看板のデータを持つアイテムには、専用の描画を行うモデルを返す。
     */
    private static final class Shaper extends ItemModelShaper {{

        private final ItemModelShaper delegate;
        // 元のモデルごとの、専用の描画を行うモデル。位置と向きは元のモデルと同じ
        private final Map<BakedModel, BakedModel> builtIn = new IdentityHashMap<BakedModel, BakedModel>();

        Shaper(ItemModelShaper delegate, Minecraft mc) {{
            super(mc.getModelManager());
            this.delegate = delegate;
        }}

        @Override
        public BakedModel getItemModel(ItemStack stack) {{
            final BakedModel model = delegate.getItemModel(stack);
            if (model == null || find(stack) == null) return model;
            BakedModel replaced = builtIn.get(model);
            if (replaced == null) {{
                replaced = {built_in};
                builtIn.put(model, replaced);
            }}
            return replaced;
        }}
{shaper_methods}
    }}

    /**
     * 専用の描画を行う部分。MarumaSign の看板アイテムのときだけ画像を描き、それ以外は元の処理に任せる。
     */
    private static final class Renderer extends BlockEntityWithoutLevelRenderer {{

        private final BlockEntityWithoutLevelRenderer delegate;

        Renderer(BlockEntityWithoutLevelRenderer delegate, Minecraft mc) {{
            super(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
            this.delegate = delegate;
        }}

        @Override
        public void renderByItem(ItemStack stack, {context_type} context, PoseStack poseStack, MultiBufferSource buffers,
                                 int light, int overlay) {{
            final SignImage image = find(stack);
            if (image == null) {{
                delegate.renderByItem(stack, context, poseStack, buffers, light, overlay);
                return;
            }}
            // 距離は分からないので、手元にあるものとして扱う
            for (SignImage.Batch batch : image.itemBatches(1.0)) {{
                Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
            }}
            MarumaSign.drawn(image);
        }}

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {{
            delegate.onResourceManagerReload(resourceManager);
        }}
    }}
}}
"""


# 1.21.1 まで: モデルの表は、アイテムごとに登録する形
SHAPER_METHODS_OLD = """
        @Override
        public BakedModel getItemModel(Item item) {
            return delegate.getItemModel(item);
        }

        @Override
        public ModelManager getModelManager() {
            return delegate.getModelManager();
        }

        @Override
        public void register(Item item, ModelResourceLocation location) {
            delegate.register(item, location);
        }

        @Override
        public void rebuildCache() {
            delegate.rebuildCache();
            builtIn.clear();
        }"""
SHAPER_IMPORTS_OLD = ("import net.minecraft.client.renderer.block.model.ItemOverrides;\n"
                      "import net.minecraft.client.resources.model.ModelManager;\n"
                      "import net.minecraft.client.resources.model.ModelResourceLocation;\n")
BUILT_IN_OLD = "new BuiltInModel(model.getTransforms(), ItemOverrides.EMPTY, model.getParticleIcon(), model.usesBlockLight())"
# 1.21.2 - 1.21.3: モデルは名前で引く
SHAPER_METHODS_NEW = """
        @Override
        public BakedModel getItemModel(ResourceLocation location) {
            return delegate.getItemModel(location);
        }

        @Override
        public void invalidateCache() {
            delegate.invalidateCache();
            builtIn.clear();
        }"""
SHAPER_IMPORTS_NEW = "import net.minecraft.resources.ResourceLocation;\n"
BUILT_IN_NEW = "new BuiltInModel(model.getTransforms(), model.getParticleIcon(), model.usesBlockLight())"


def sorted_imports(source):
    """生成したソースの import を、ほかのファイルと同じ並び (名前順) に直す。"""
    lines = source.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith("import com.mojang"))
    last = next(i for i, line in enumerate(lines) if line.startswith("import net.minecraft.world.level.block.StandingSignBlock"))
    block = sorted(line for line in lines[first:last + 1] if line)
    return "\n".join(lines[:first] + block + lines[last + 1:])


write("item_bewlr_1", "SignItems.java", sorted_imports(item_bewlr_source(
    "Minecraft 1.17 - 1.19.2", "import net.minecraft.client.renderer.block.model.ItemTransforms;", "ItemTransforms.TransformType",
    BUILT_IN_OLD, SHAPER_METHODS_OLD, SHAPER_IMPORTS_OLD)))
write("item_bewlr_2", "SignItems.java", sorted_imports(item_bewlr_source(
    "Minecraft 1.19.3 - 1.21.1", "import net.minecraft.world.item.ItemDisplayContext;", "ItemDisplayContext",
    BUILT_IN_OLD, SHAPER_METHODS_OLD, SHAPER_IMPORTS_OLD)))
write("item_bewlr_3", "SignItems.java", sorted_imports(item_bewlr_source(
    "Minecraft 1.21.2 - 1.21.3", "import net.minecraft.world.item.ItemDisplayContext;", "ItemDisplayContext",
    BUILT_IN_NEW, SHAPER_METHODS_NEW, SHAPER_IMPORTS_NEW)))

# アイテムが持っているブロックエンティティのデータの取り出し方
write("item_nbt", "SignItemData.java", """
package marumasa.marumasa_sign.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * アイテムが持っているブロックエンティティのデータ。(Minecraft 1.20.4 まで: アイテムのタグの中にある)
 */
final class SignItemData {

    private SignItemData() {
    }

    // データがなければ null
    static CompoundTag blockEntityTag(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        // 10 は複合タグの種類
        return tag != null && tag.contains("BlockEntityTag", 10) ? tag.getCompound("BlockEntityTag") : null;
    }
}
""")
write("item_components", "SignItemData.java", """
package marumasa.marumasa_sign.adapter;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * アイテムが持っているブロックエンティティのデータ。(Minecraft 1.20.5 以降: アイテムのコンポーネントになっている)
 */
final class SignItemData {

    private SignItemData() {
    }

    // データがなければ null
    static CompoundTag blockEntityTag(ItemStack stack) {
        final CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        // 読むだけなので、写しを作らずに中身をそのまま使う
        return data == null ? null : data.getUnsafe();
    }
}
""")


def item_static_source(title, renderer_class, renderer_import, render_signature, render_delegate, draw, extra_imports, built_in):
    """1.14 - 1.16.5: 専用の描画を行う部分が、ゲーム全体で 1 つの static なフィールドになっているバージョン。"""
    source = f"""
package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemModelShaper;
{renderer_import}
{extra_imports}import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.StandingSignBlock;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 看板の文字を持っている看板アイテムを、その看板と同じ内容 (画像・動画・3D モデル) で描く。({title})
 * アイテムのモデルを選ぶ部分を包んで、MarumaSign の看板のデータを持つアイテムには「専用の描画を行うモデル」を返し、
 * 専用の描画を行う部分を包んで、そのアイテムのときに画像を描く。
 */
final class SignItems {{

    private static Field shaperField;
    private static Field rendererField;

    private SignItems() {{
    }}

    // まだ包まれていなければ包む。毎 tick 確認する
    static void install(Minecraft mc) {{
        final ItemRenderer itemRenderer = mc.getItemRenderer();
        if (itemRenderer == null) return;

        // 専用の描画を行う部分は、ゲーム全体で 1 つだけあり、書き換えられないフィールドに入っている
        if (rendererField == null) {{
            rendererField = Reflect.fieldOfType({renderer_class}.class, {renderer_class}.class);
            shaperField = Reflect.fieldOfType(ItemRenderer.class, ItemModelShaper.class);
        }}
        final {renderer_class} renderer = Reflect.get(rendererField, null);
        if (renderer != null && !(renderer instanceof Renderer)) {{
            Reflect.setStaticFinal(rendererField, new Renderer(renderer));
        }}
        final ItemModelShaper shaper = Reflect.get(shaperField, itemRenderer);
        if (shaper != null && !(shaper instanceof Shaper)) {{
            Reflect.set(shaperField, itemRenderer, new Shaper(shaper, mc));
        }}
    }}

    // アイテムが持っている看板のデータから、表示する内容を探す。MarumaSign の看板でなければ null
    static SignImage find(ItemStack stack) {{
        // 立て看板になるアイテムだけを対象にする
        final Item item = stack.getItem();
        if (!(item instanceof BlockItem) || !(((BlockItem) item).getBlock() instanceof StandingSignBlock)) return null;
        final CompoundTag tag = SignItemData.blockEntityTag(stack);
        if (tag == null) return null;

        final String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {{
            lines[i] = tag.getString("Text" + (i + 1));
        }}
        return MarumaSign.itemSign(lines);
    }}

    /**
     * アイテムのモデルを選ぶ部分。MarumaSign の看板のデータを持つアイテムには、専用の描画を行うモデルを返す。
     */
    private static final class Shaper extends ItemModelShaper {{

        private final ItemModelShaper delegate;
        // 元のモデルごとの、専用の描画を行うモデル。位置と向きは元のモデルと同じ
        private final Map<BakedModel, BakedModel> builtIn = new IdentityHashMap<BakedModel, BakedModel>();

        Shaper(ItemModelShaper delegate, Minecraft mc) {{
            super(mc.getModelManager());
            this.delegate = delegate;
        }}

        @Override
        public BakedModel getItemModel(ItemStack stack) {{
            final BakedModel model = delegate.getItemModel(stack);
            if (model == null || find(stack) == null) return model;
            BakedModel replaced = builtIn.get(model);
            if (replaced == null) {{
                replaced = {built_in};
                builtIn.put(model, replaced);
            }}
            return replaced;
        }}

        @Override
        public BakedModel getItemModel(Item item) {{
            return delegate.getItemModel(item);
        }}

        @Override
        public ModelManager getModelManager() {{
            return delegate.getModelManager();
        }}

        @Override
        public void register(Item item, ModelResourceLocation location) {{
            delegate.register(item, location);
        }}

        @Override
        public void rebuildCache() {{
            delegate.rebuildCache();
            builtIn.clear();
        }}
    }}

    /**
     * 専用の描画を行う部分。MarumaSign の看板アイテムのときだけ画像を描き、それ以外は元の処理に任せる。
     */
    private static final class Renderer extends {renderer_class} {{

        private final {renderer_class} delegate;

        Renderer({renderer_class} delegate) {{
            this.delegate = delegate;
        }}

        @Override
        public void renderByItem({render_signature}) {{
            final SignImage image = find(stack);
            if (image == null) {{
                delegate.renderByItem({render_delegate});
                return;
            }}
            // 距離は分からないので、手元にあるものとして扱う
{draw}
            MarumaSign.drawn(image);
        }}
    }}
}}
"""
    lines = source.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith("import marumasa.marumasa_sign.core.MarumaSign"))
    last = next(i for i, line in enumerate(lines) if line.startswith("import net.minecraft.world.level.block.StandingSignBlock"))
    block = sorted(line for line in lines[first:last + 1] if line)
    return "\n".join(lines[:first] + block + lines[last + 1:])


ITEM_DRAW_BUFFERS = """            for (SignImage.Batch batch : image.itemBatches(1.0)) {
                Mc.quads(buffers.getBuffer(((Tex) batch.texture).renderType), poseStack, batch, light);
            }"""
ITEM_BUFFER_IMPORTS = "import com.mojang.blaze3d.vertex.PoseStack;\nimport net.minecraft.client.renderer.MultiBufferSource;\n"
BUILT_IN_114 = "new BuiltInModel(model.getTransforms(), ItemOverrides.EMPTY, model.getParticleIcon())"
# 1.15.2 で「横から光を受けるか」の引数が増えた。1.15 と 1.15.1 でも動くように、引数の数で選ぶ。
# 絵は平らなアイテムと同じ光の当て方で描くので、増えた引数は false にする
BUILT_IN_115 = ("Reflect.construct(BuiltInModel.class, model.getTransforms(), ItemOverrides.EMPTY, model.getParticleIcon(), Boolean.FALSE);\n"
                "                if (replaced == null) replaced = Reflect.construct(BuiltInModel.class, model.getTransforms(), ItemOverrides.EMPTY, model.getParticleIcon())")

write("item_static_1", "SignItems.java", item_static_source(
    "Minecraft 1.14 - 1.14.4", "EntityBlockRenderer", "import net.minecraft.client.renderer.EntityBlockRenderer;",
    "ItemStack stack", "stack", "            SignRendererBase.drawBatches(image.itemBatches(1.0));", "", BUILT_IN_114))
write("item_static_2", "SignItems.java", item_static_source(
    "Minecraft 1.15 - 1.15.2", "BlockEntityWithoutLevelRenderer", "import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;",
    "ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay",
    "stack, poseStack, buffers, light, overlay", ITEM_DRAW_BUFFERS, ITEM_BUFFER_IMPORTS, BUILT_IN_115))
write("item_static_3", "SignItems.java", item_static_source(
    "Minecraft 1.16 - 1.16.5", "BlockEntityWithoutLevelRenderer", "import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;",
    "ItemStack stack, ItemTransforms.TransformType context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay",
    "stack, context, poseStack, buffers, light, overlay", ITEM_DRAW_BUFFERS,
    ITEM_BUFFER_IMPORTS + "import net.minecraft.client.renderer.block.model.ItemTransforms;\n", BUILT_IN_OLD))
# 26.x 共通の画面とレンダラーの受け口
write("v26", "UiScreen.java", SCREEN_EXTRACTOR)
write("v26", "SignRendererWrapper.java", WRAPPER_RS_26)
print("generated")
