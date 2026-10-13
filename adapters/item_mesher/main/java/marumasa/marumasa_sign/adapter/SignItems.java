package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import marumasa.marumasa_sign.core.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSign;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;

/**
 * 看板アイテムの見た目を看板と同じ表示にするための管理クラス。
 * 毎 tick 呼ばれ、レンダラーの差し替えを行う。
 */
final class SignItems {

    private static Field itemModelMesherField;

    private SignItems() {
    }

    static void install() {
        install(Minecraft.getMinecraft());
    }

    static void install(Minecraft mc) {
        if (mc == null) return;
        final RenderItem renderItem = mc.getRenderItem();
        if (renderItem == null) return;

        // 1. ItemModelMesher の差し替え
        final ItemModelMesher currentMesher = renderItem.getItemModelMesher();
        if (!(currentMesher instanceof SignItemModelMesher)) {
            if (itemModelMesherField == null) {
                itemModelMesherField = Reflect.fieldOfType(RenderItem.class, ItemModelMesher.class);
            }
            Reflect.set(itemModelMesherField, renderItem, new SignItemModelMesher(currentMesher));
        }

        // 2. TileEntityItemStackRenderer の差し替え
        if (!(TileEntityItemStackRenderer.instance instanceof SignItemStackRenderer)) {
            TileEntityItemStackRenderer.instance = new SignItemStackRenderer(TileEntityItemStackRenderer.instance);
        }
    }

    static boolean isSign(Item item) {
        return item instanceof ItemSign || item == Items.sign;
    }

    // アイテムから看板の文字を取り出し、MarumaSign の看板なら画像を返す
    static SignImage find(ItemStack stack) {
        if (stack == null) return null;
        final NBTTagCompound root = stack.getTagCompound();
        if (root == null) return null;

        final NBTTagCompound tag;
        if (root.hasKey("BlockEntityTag", 10)) {
            tag = root.getCompoundTag("BlockEntityTag");
        } else {
            tag = root;
        }

        boolean hasText = false;
        final String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            final String key = "Text" + (i + 1);
            if (tag.hasKey(key)) {
                hasText = true;
                final NBTBase base = tag.getTag(key);
                if (base instanceof NBTTagString) {
                    lines[i] = ((NBTTagString) base).getString();
                } else if (base != null) {
                    lines[i] = base.toString();
                } else {
                    lines[i] = "";
                }
            } else {
                lines[i] = "";
            }
        }
        if (!hasText) return null;
        return MarumaSign.itemSign(lines);
    }

    // 四角形のまとまりを描く
    static void drawItem(SignImage sign) {
        final SignImage.Batch[] batches = sign.itemBatches(1.0);
        if (batches == null || batches.length == 0) return;

        GL11.glPushMatrix();
        // TileEntityItemStackRenderer 呼び出し時の 180 度回転を相殺し、通常の flat アイテムモデルと同じ座標系にする
        GL11.glTranslated(0.5, 0.5, 0.5);
        GL11.glRotatef(180.0f, 0.0f, 1.0f, 0.0f);
        GL11.glTranslated(-0.5, -0.5, -0.5);

        SignDraw.drawBatches(batches);
        GL11.glPopMatrix();

        MarumaSign.drawn(sign);
    }
}
