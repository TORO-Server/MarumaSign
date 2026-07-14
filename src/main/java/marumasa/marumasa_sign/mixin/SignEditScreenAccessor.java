package marumasa.marumasa_sign.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity getBlockEntity();
}
