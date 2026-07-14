package marumasa.marumasa_sign.client.sign;

import marumasa.marumasa_sign.mixin.SignEditScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.Objects;

public class SignWriteManager {
    private static String requestText;

    public static void request(String signText) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        Component text = Component.translatable("text.maruma_sign.write_mode").withStyle(ChatFormatting.YELLOW);
        client.player.sendSystemMessage(text);
        requestText = signText;
    }

    // 文字列を均等に 4 つに分ける
    private static String[] splitStringEvenly(String text) {
        String[] result = new String[4];

        if (text == null || text.isEmpty()) {
            Arrays.fill(result, "");
            return result;
        }

        int len = text.length();
        int basePartSize = len / 4;
        int remainder = len % 4;

        int startIndex = 0;
        for (int i = 0; i < 4; i++) {
            int partSize = basePartSize + (i < remainder ? 1 : 0);

            if (startIndex >= len) {
                result[i] = "";
            } else {
                int endIndex = startIndex + partSize;
                result[i] = text.substring(startIndex, endIndex);
                startIndex = endIndex;
            }
        }

        return result;
    }

    public static void init() {

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (requestText == null) return;
            // 開かれたスクリーンが看板の編集のGUIなら
            if (screen instanceof SignEditScreen signEditScreen) {

                SignEditScreenAccessor accessor = (SignEditScreenAccessor) signEditScreen;

                SignBlockEntity signBlockEntity = accessor.getBlockEntity();

                String[] signLines = splitStringEvenly(requestText);

                // 看板を更新する
                ServerboundSignUpdatePacket packet = new ServerboundSignUpdatePacket(
                        signBlockEntity.getBlockPos(),
                        signBlockEntity.isFacingFrontText(client.player),
                        signLines[0],
                        signLines[1],
                        signLines[2],
                        signLines[3]
                );
                Objects.requireNonNull(client.getConnection()).send(packet);
                requestText = null;
                signEditScreen.onClose();
            }
        });
    }
}