package marumasa.marumasa_sign.adapter;

import marumasa.marumasa_sign.core.MarumaSign;
import marumasa.marumasa_sign.core.sign.SignImage;
import net.minecraft.item.ItemSign;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

/**
 * 看板アイテムのデータから MarumaSign の画像を判定・取得する。
 */
final class ItemSignFinder {

    private ItemSignFinder() {
    }

    /**
     * 看板アイテムに対応する画像を返す。MarumaSign の看板でなければ null。
     */
    static SignImage find(ItemStack stack) {
        if (stack == null) return null;
        if (!(stack.getItem() instanceof ItemSign)) return null;

        final NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) return null;

        // 1.7.10 では Ctrl+選択による BlockEntityTag は標準では存在しないが、
        // /give や Mod 等で BlockEntityTag または直下に看板テキストが設定される場合がある
        final NBTTagCompound blockEntityTag = tag.hasKey("BlockEntityTag", 10) ? tag.getCompoundTag("BlockEntityTag") : tag;
        if (!blockEntityTag.hasKey("Text1")) return null;

        final String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = readLine(blockEntityTag, "Text" + (i + 1));
        }
        return MarumaSign.itemSign(lines);
    }

    private static String readLine(NBTTagCompound tag, String key) {
        if (!tag.hasKey(key)) return "";
        final NBTBase base = tag.getTag(key);
        if (base instanceof NBTTagString) {
            return ((NBTTagString) base).getString();
        }
        return base != null ? base.toString() : "";
    }
}
