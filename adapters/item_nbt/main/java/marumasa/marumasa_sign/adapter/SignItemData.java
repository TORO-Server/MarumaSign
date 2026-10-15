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
