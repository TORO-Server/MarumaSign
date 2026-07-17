package marumasa.marumasa_sign.model;

import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.AABB;

public interface CustomSignHolder {
    CustomSign marumasa$getCustomSign();
    void marumasa$setCustomSign(CustomSign customSign);
    SignText marumasa$getLastFrontText();
    void marumasa$setLastFrontText(SignText frontText);
    SignText marumasa$getLastBackText();
    void marumasa$setLastBackText(SignText backText);

    AABB marumasa$getCustomSignAABB();
    void marumasa$setCustomSignAABB(AABB aabb);
}
