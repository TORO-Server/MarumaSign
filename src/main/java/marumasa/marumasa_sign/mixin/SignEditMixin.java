package marumasa.marumasa_sign.mixin;

import net.minecraft.client.gui.screen.ingame.SignEditScreen;

import java.io.IOException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import marumasa.marumasa_sign.MarumaSign;

@Mixin(SignEditScreen.class)
public class SignEditMixin {
    @Inject(at = @At("RETURN"), method = "init()V")
    public void init(CallbackInfo info) {
        // もしAutotypeが設定されているなら
        if (System.getenv("MARUMASIGN_AUTOTYPE") != null) {
            // もし書き込み情報が保存されているなら
            if(MarumaSign._globalSignContent != "") {
                try {
                    Runtime.getRuntime().exec(new String[]{System.getenv("MARUMASIGN_AUTOTYPE"), MarumaSign._globalSignContent});
                } catch (IOException e) {
                    MarumaSign.LOGGER.error(e.getMessage());
                }
            }
        } else {
            // Autotype未設定
            MarumaSign.LOGGER.info("Autotype isn't installed");
        }
    }
}
