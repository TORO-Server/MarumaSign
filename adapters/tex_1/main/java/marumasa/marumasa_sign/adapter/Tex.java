package marumasa.marumasa_sign.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * テクスチャのハンドル。Minecraft 1.20.6 以前の作り方。
 */
final class Tex {

    private static final AtomicInteger nextId = new AtomicInteger();
    // TextureManager の、名前からテクスチャを引く表
    private static Field texturesField;

    final ResourceLocation id;
    private final DynamicTexture texture;
    // ワールドに描くときの描画設定。文字用のものを使う。
    // 半透明に対応し、周囲の明るさは反映するが、面の向きによる陰影は付かない
    final RenderType renderType;

    private Tex(ResourceLocation id, DynamicTexture texture) {
        this.id = id;
        this.texture = texture;
        this.renderType = RenderType.text(id);
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
        final TextureManager manager = mc.getTextureManager();
        manager.release(id);
        // 古いバージョンの release は OpenGL のテクスチャを消すだけで、表から外さず、画素 (Java の外のメモリ) も解放しない。
        // そのままでは、看板を読み込み直すたびに (使われなくなった看板は 5 分で消して、また見えたら読み込み直す) メモリが増え続けるので、
        // 表から外して閉じる。外して閉じるようになったバージョンでは、どちらも何もしない (閉じたものを閉じても何も起きない)
        forget(manager);
        texture.close();
    }

    // テクスチャを TextureManager の表から外す
    private void forget(TextureManager manager) {
        if (texturesField == null) {
            // 同じ型の表がほかにもあるので、このテクスチャが入っているもので見分ける
            for (Field field : TextureManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                final Map<?, ?> map = Reflect.get(field, manager);
                if (map != null && map.get(id) == texture) {
                    texturesField = field;
                    break;
                }
            }
            // release が表から外すバージョンでは見つからない
            if (texturesField == null) return;
        }
        final Map<?, ?> map = Reflect.get(texturesField, manager);
        if (map != null && map.get(id) == texture) map.remove(id);
    }
}
