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
