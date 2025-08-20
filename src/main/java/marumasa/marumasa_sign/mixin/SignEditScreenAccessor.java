package marumasa.marumasa_sign.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("blockEntity")
    SignBlockEntity getBlockEntity();
}