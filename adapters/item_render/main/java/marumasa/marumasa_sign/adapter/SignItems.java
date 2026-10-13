package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 看板アイテムの見た目を差し替える。
 * ゲームがアイテムを描画する共通の RenderItem をラップし、看板の文字に応じた見た目に差し替える。
 */
final class SignItems {

    private SignItems() {
    }

    // 毎 tick 呼ばれ、RenderItem が差し替えられていなければ差し込む
    static void install(Minecraft mc) {
        final RenderItem current = mc.getRenderItem();
        if (current instanceof SignRenderItem) return;

        final SignRenderItem custom = new SignRenderItem(mc, current);

        // 1. mc.renderItem を差し替える (GUI などの共通経路)
        replaceField(mc, current, custom);

        // 2. ItemRenderer.itemRenderer を差し替える (手持ちアイテムの描画)
        final ItemRenderer itemRenderer = mc.getItemRenderer();
        if (itemRenderer != null) {
            replaceField(itemRenderer, current, custom);
        }

        // 3. RenderManager 以下のエンティティレンダラー (地面のドロップアイテム、額縁など)
        final RenderManager renderManager = mc.getRenderManager();
        if (renderManager != null) {
            replaceField(renderManager, current, custom);
            final Field renderMapField = Reflect.fieldOfType(RenderManager.class, Map.class);
            final Map<?, ?> map = Reflect.get(renderMapField, renderManager);
            if (map != null) {
                for (Object renderer : map.values()) {
                    replaceField(renderer, current, custom);
                }
            }
        }
    }

    // instance のフィールドのうち、oldValue を保持しているものを newValue に書き換える
    private static void replaceField(Object instance, Object oldValue, Object newValue) {
        if (instance == null) return;
        for (Class<?> type = instance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                try {
                    if (field.get(instance) == oldValue) {
                        field.set(instance, newValue);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    // 看板アイテムから MarumaSign の画像を読み取る。MarumaSign でなければ null
    static SignImage find(ItemStack stack) {
        if (stack == null || stack.getItem() != Items.SIGN) return null;
        if (!stack.hasTagCompound()) return null;
        final NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey("BlockEntityTag", 10)) return null;
        final NBTTagCompound bet = tag.getCompoundTag("BlockEntityTag");
        final String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            final NBTBase lineTag = bet.getTag("Text" + (i + 1));
            if (lineTag instanceof NBTTagString) {
                lines[i] = ((NBTTagString) lineTag).getString();
            } else if (lineTag != null) {
                lines[i] = lineTag.toString();
            } else {
                lines[i] = "";
            }
        }
        return MarumaSign.itemSign(lines);
    }
}
