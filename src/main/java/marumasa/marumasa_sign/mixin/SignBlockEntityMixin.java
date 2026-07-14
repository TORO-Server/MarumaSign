package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.type.CustomSign;
import marumasa.marumasa_sign.type.CustomSignHolder;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SignBlockEntity.class)
public class SignBlockEntityMixin implements CustomSignHolder {
    @Unique
    private CustomSign customSign;
    @Unique
    private SignText lastFrontText;
    @Unique
    private SignText lastBackText;

    @Override
    public CustomSign marumasa$getCustomSign() {
        return this.customSign;
    }

    @Override
    public void marumasa$setCustomSign(CustomSign customSign) {
        this.customSign = customSign;
    }

    @Override
    public SignText marumasa$getLastFrontText() {
        return this.lastFrontText;
    }

    @Override
    public void marumasa$setLastFrontText(SignText frontText) {
        this.lastFrontText = frontText;
    }

    @Override
    public SignText marumasa$getLastBackText() {
        return this.lastBackText;
    }

    @Override
    public void marumasa$setLastBackText(SignText backText) {
        this.lastBackText = backText;
    }
}
