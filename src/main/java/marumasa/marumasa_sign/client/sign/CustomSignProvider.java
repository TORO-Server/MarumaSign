package marumasa.marumasa_sign.client.sign;

import marumasa.marumasa_sign.model.CustomSign;
import marumasa.marumasa_sign.model.CustomSignHolder;
import marumasa.marumasa_sign.model.TextureURL;
import marumasa.marumasa_sign.animation.GifPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CustomSignProvider {

    private static final Map<String, CustomSign> loaded = new java.util.concurrent.ConcurrentHashMap<>();// 読み込み済み看板マップ
    public static int cacheVersion = 0; // キャッシュ削除の世代管理用

    public static CustomSign get(SignBlockEntity signBlockEntity) {
        CustomSignHolder holder = (CustomSignHolder) signBlockEntity;
        SignText frontText = signBlockEntity.getFrontText();
        SignText backText = signBlockEntity.getBackText();

        if (frontText == holder.marumasa$getLastFrontText() && backText == holder.marumasa$getLastBackText()) {
            CustomSign cached = holder.marumasa$getCustomSign();
            if (cached != null && cached.getCacheVersion() == cacheVersion) {
                if (cached.isLoading()) {
                    String signText = CustomSign.read(signBlockEntity);
                    CustomSign latest = loaded.get(signText);
                    if (latest != null && !latest.isLoading()) {
                        holder.marumasa$setCustomSign(latest);
                        return latest;
                    }
                }
                return cached;
            }
        }

        // 看板に書いてある文字(表面裏面両方)を String に変更
        String signText = CustomSign.read(signBlockEntity);

        // 読み込み済み看板マップ から 取得
        CustomSign customSign = loaded.get(signText);

        if (customSign != null) {
            holder.marumasa$setLastFrontText(frontText);
            holder.marumasa$setLastBackText(backText);
            holder.marumasa$setCustomSign(customSign);
            return customSign;
        }

        Object[] parameters = toParameters(signText);
        if (parameters == null || parameters.length != 9) return null;

        final String stringURL = (String) parameters[0];



        TextureURL textureURL = TextureURLProvider.get(stringURL, signText);

        customSign = CustomSign.create(textureURL, parameters);

        // 読み込み済み看板 追加
        loaded.put(signText, customSign);

        holder.marumasa$setLastFrontText(frontText);
        holder.marumasa$setLastBackText(backText);
        holder.marumasa$setCustomSign(customSign);

        return customSign;
    }

    public static void changeSignTexture(TextureURL textureURL, String signText) {
        Object[] params = toParameters(signText);
        if (params == null) return;
        CustomSign customSign = CustomSign.create(textureURL, params);
        loaded.put(signText, customSign);
    }
 

    public static Object[] toParameters(String signText) {
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
        cacheVersion++;
        loaded.clear();
        GifPlayer.gifMap.clear();
        TextureURLProvider.removeCache();
    }
}
