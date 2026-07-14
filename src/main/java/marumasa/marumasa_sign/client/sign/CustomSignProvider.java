package marumasa.marumasa_sign.client.sign;

import marumasa.marumasa_sign.type.CustomSign;
import marumasa.marumasa_sign.type.TextureURL;
import marumasa.marumasa_sign.util.GifPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CustomSignProvider {

    private static final Map<String, CustomSign> loaded = new java.util.concurrent.ConcurrentHashMap<>();// 読み込み済み看板マップ

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
        Object[] params = toParameters(signText);
        if (params == null) return;
        CustomSign customSign = CustomSign.create(textureURL, params);
        loaded.put(signText, customSign);
    }
 
    public static void updateSignTexture(String signText, RenderType renderLayer) {
        CustomSign oldSign = loaded.get(signText);
        if (oldSign == null) return;
        CustomSign customSign = new CustomSign(renderLayer, oldSign);
        loaded.put(signText, customSign);
    }
 
    private static Object[] toParameters(String signText) {
        if (signText == null) return null;
        try {
            final String[] parameters = signText.split("\\|");
            if (parameters.length < 9) return null;
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


    public static void removeCache() {
        loaded.clear();
        GifPlayer.gifList.clear();
        GifPlayer.signTextMap.clear();
        TextureURLProvider.removeCache();
    }
}
