package marumasa.marumasa_sign.client.sign;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.util.Identifier;

public record TextureURL(Identifier identifier, int width, int height) {
    public static final TextureURL loading = new TextureURL(
            new Identifier("textures/gui/container/anvil.png"), 1, 1
    );
    public static final TextureURL error = new TextureURL(
            new Identifier(MarumaSign.MOD_ID, "textures/misc/error.png"), 960, 576
    );
}
