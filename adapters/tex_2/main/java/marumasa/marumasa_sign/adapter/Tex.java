package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.splat.SplatScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。Minecraft 1.21 - 1.21.1 の作り方。
 */
final class Tex {

    private static final AtomicInteger nextId = new AtomicInteger();

    final ResourceLocation id;
    private final DynamicTexture texture;
    // ワールドに描くときの描画設定。文字用のものを使う。
    // 半透明に対応し、周囲の明るさは反映するが、面の向きによる陰影は付かない
    final RenderType renderType;

    private Tex(ResourceLocation id, DynamicTexture texture, boolean splat) {
        this.id = id;
        this.texture = texture;
        final RenderType text = RenderType.text(id);
        if (splat) {
            // ガウシアンスプラッティングの四角形は、SplatScene が奥から手前の順に並べてある。
            // 文字用の設定は描く直前に四角形を並べ替え直すが、NeoForge 1.21.1 の Iris 1.8.14 と Sodium 0.8 の
            // 組み合わせではこの並べ替えで順番が崩れ、手前の粒が奥の粒を隠して、粒が 1 つずつ浮き出て見える。
            // そのため、並べ替えだけをしない同じ設定で描く
            this.renderType = new RenderType(MarumaSign.MOD_ID + "_splat", text.format(), text.mode(),
                    text.bufferSize(), false, false, text::setupRenderState, text::clearRenderState) {
            };
        } else {
            this.renderType = text;
        }
    }

    private static void copy(NativeImage image, int width, int height, int[] argb) {
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                final int color = argb[row + x];
                image.setPixelRGBA(x, y, (color & 0xFF00FF00) | ((color & 0xFF) << 16) | ((color >> 16) & 0xFF));
            }
        }
    }

    static Tex create(Minecraft mc, int width, int height, int[] argb) {
        final NativeImage image = new NativeImage(width, height, false);
        copy(image, width, height, argb);
        final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MarumaSign.MOD_ID, "dynamic/" + nextId.incrementAndGet());
        final DynamicTexture texture = new DynamicTexture(image);
        mc.getTextureManager().register(id, texture);
        return new Tex(id, texture, isSplatTexture(width, height, argb));
    }

    // ガウシアンスプラッティングの粒に使う、共通のテクスチャかどうか (中身で見分ける)
    private static boolean isSplatTexture(int width, int height, int[] argb) {
        return width == SplatScene.TEXTURE_SIZE && height == SplatScene.TEXTURE_SIZE
                && Arrays.equals(argb, SplatScene.gaussianTexture());
    }

    void update(int width, int height, int[] argb) {
        copy(texture.getPixels(), width, height, argb);
        texture.upload();
    }

    void release(Minecraft mc) {
        mc.getTextureManager().release(id);
    }
}
