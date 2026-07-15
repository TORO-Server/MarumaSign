package marumasa.marumasa_sign.mixin;

import marumasa.marumasa_sign.model.CustomSign;
import marumasa.marumasa_sign.model.CustomSignHolder;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.AABB;
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
    @Unique
    private AABB customSignAABB;

    @Override
    public CustomSign marumasa$getCustomSign() {
        return this.customSign;
    }

    @Override
    public void marumasa$setCustomSign(CustomSign customSign) {
        this.customSign = customSign;
        this.customSignAABB = null;
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

    @Override
    public AABB marumasa$getCustomSignAABB() {
        return this.customSignAABB;
    }

    @Override
    public void marumasa$setCustomSignAABB(AABB aabb) {
        this.customSignAABB = aabb;
    }
}
