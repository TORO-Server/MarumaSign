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
