package marumasa.marumasa_sign.client;

import net.minecraft.block.entity.SignBlockEntity;

import java.util.HashMap;
import java.util.Map;

public class CustomSignProvider {

    private static final Map<String, CustomSign> loaded = new HashMap<>();// 読み込み済み看板マップ

    public static CustomSign get(SignBlockEntity signBlockEntity) {

        // 看板に書いてある文字(表面裏面両方)を String に変更
        final String signText = CustomSign.read(signBlockEntity);

        // 読み込み済み看板マップ から 取得
        CustomSign customSign = loaded.get(signText);

        if (customSign != null) return customSign;


        String[] parameters = toParameters(signText);
        if (parameters.length != 9) return null;

        final String stringURL = parameters[0];

        TextureURL textureURL = TextureURLProvider.get(stringURL, signText);

        customSign = CustomSign.create(textureURL, parameters);

        // 読み込み済み看板 追加
        loaded.put(signText, customSign);

        return customSign;
    }

    public static void changeSignTexture(TextureURL textureURL, String signText) {
        CustomSign customSign = CustomSign.create(textureURL, toParameters(signText));
        loaded.put(signText, customSign);
    }

    private static String[] toParameters(String signText) {
        return signText.split("\\|");
    }
}
