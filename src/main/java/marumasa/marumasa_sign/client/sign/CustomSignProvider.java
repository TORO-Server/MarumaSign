package marumasa.marumasa_sign.client.sign;

import marumasa.marumasa_sign.type.CustomSign;
import marumasa.marumasa_sign.type.TextureURL;
import marumasa.marumasa_sign.util.GifPlayer;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.RenderLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CustomSignProvider {

    private static final Map<String, CustomSign> loaded = new HashMap<>();// 読み込み済み看板マップ

    public static CustomSign get(SignBlockEntity signBlockEntity) {

        // 看板に書いてある文字(表面裏面両方)を String に変更
        String signText = CustomSign.read(signBlockEntity);

        // 読み込み済み看板マップ から 取得
        CustomSign customSign = loaded.get(signText);

        if (customSign != null) return customSign;


        Object[] parameters = toParameters(signText);
        if (parameters == null || parameters.length != 9) return null;

        final String stringURL = (String) parameters[0];

        if (GifPlayer.signTextMap.containsKey(stringURL)) {
            List<String> signTextList = GifPlayer.signTextMap.get(stringURL);
            signTextList.add(signText);
            GifPlayer.signTextMap.put(stringURL, signTextList);
        }

        TextureURL textureURL = TextureURLProvider.get(stringURL, signText);

        customSign = CustomSign.create(textureURL, parameters);

        // 読み込み済み看板 追加
        loaded.put(signText, customSign);

        return customSign;
    }

    public static void changeSignTexture(TextureURL textureURL, String signText) {
        CustomSign customSign = CustomSign.create(textureURL,
                Objects.requireNonNull(toParameters(signText))
        );
        loaded.put(signText, customSign);
    }

    public static void updateSignTexture(String signText, RenderLayer renderLayer) {
        CustomSign customSign = new CustomSign(renderLayer, loaded.get(signText));
        loaded.put(signText, customSign);
    }

    private static Object[] toParameters(String signText) {
        try {
            final String[] parameters = signText.split("\\|");
            return new Object[]{
                    parameters[0],
                    Float.parseFloat(parameters[1]),
                    Float.parseFloat(parameters[2]),
                    Float.parseFloat(parameters[3]),
                    Float.parseFloat(parameters[4]),
                    Float.parseFloat(parameters[5]),
                    Float.parseFloat(parameters[6]),
                    Float.parseFloat(parameters[7]),
                    Float.parseFloat(parameters[8])
            };
        } catch (Exception e) {
            return null;
        }
    }
}
