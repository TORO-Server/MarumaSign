package marumasa.marumasa_sign.type;

import net.minecraft.world.level.block.entity.SignText;

public interface CustomSignHolder {
    CustomSign marumasa$getCustomSign();
    void marumasa$setCustomSign(CustomSign customSign);
    SignText marumasa$getLastFrontText();
    void marumasa$setLastFrontText(SignText frontText);
    SignText marumasa$getLastBackText();
    void marumasa$setLastBackText(SignText backText);
}
