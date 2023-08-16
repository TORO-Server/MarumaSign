package marumasa.marumasa_sign.client;

import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.commons.lang3.ArrayUtils;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

public class CustomSign {

    // 看板 読み込み済みリスト
    public static final Map<String, CustomSign> loadedCustomSign = new HashMap<>();

    // 画像 読み込み済みリスト
    public static final Map<String, TextureURL> loadedTextureURL = new HashMap<>();

    public final Quaternionf rotation;
    public final Vertex vertex;
    public final RenderLayer renderLayer;

    public CustomSign(
            // 画像のURL
            TextureURL textureURL,
            // 位置
            float TranslationX,
            float TranslationY,
            float TranslationZ,
            // 大きさ
            float ScaleX,
            float ScaleZ,
            // 回転
            float RotationX,
            float RotationY,
            float RotationZ
    ) {

        // getText で 透過と半透明 対応の RenderLayer 生成
        // RenderLayer.getEntityTranslucent() では プレイヤーの向いている角度によって明度が変わってしまう
        // 確認したバージョン 1.20.1
        this.renderLayer = RenderLayer.getText(textureURL.identifier());

        final int w = textureURL.width();
        final int h = textureURL.height();
        if (w > h) {
            // 大きさを設定
            ScaleZ *= (double) h / w;
        } else {
            // 大きさを設定
            ScaleX *= (double) w / h;
        }
        // 頂点の位置 設定
        this.vertex = new Vertex(
                ScaleX + TranslationX * 2,
                ScaleZ + TranslationZ * 2,
                TranslationY * 2,
                -ScaleX + TranslationX * 2,
                -ScaleZ + TranslationZ * 2
        );

        // X 軸の 回転を設定するための クォータニオン 作成
        Quaternionf qx = new Quaternionf();
        // X 軸に どれくらい回転するか 設定
        qx.fromAxisAngleDeg(1, 0, 0, RotationX);
        // Y 軸の 回転を設定するための クォータニオン 作成
        Quaternionf qy = new Quaternionf();
        // Y 軸に どれくらい回転するか 設定
        qy.fromAxisAngleDeg(0, 1, 0, RotationY);
        // Z 軸の 回転を設定するための クォータニオン 作成
        Quaternionf qz = new Quaternionf();
        // Z 軸に どれくらい回転するか 設定
        qz.fromAxisAngleDeg(0, 0, 1, RotationZ);
        // クォータニオンの積 計算 を 計算して 回転を設定
        this.rotation = new Quaternionf().mul(qx).mul(qy).mul(qz);
    }

    public static String read(SignBlockEntity sign) {
        // 看板の表面に書かれている文字を取得
        final Text[] textFront = sign.getFrontText().getMessages(true);
        // 看板の裏面に書かれている文字を取得
        final Text[] textBack = sign.getBackText().getMessages(true);

        // 表面と裏面に書いてある文字を合わせる
        final Text[] textAll = ArrayUtils.addAll(textFront, textBack);

        // return で 返す String型の 文字 を作成するためのもの
        final StringBuilder StringAll = new StringBuilder();

        // textAll を読み取り StringAll に追加
        for (Text text : textAll) StringAll.append(text.getString());

        // 看板に書いてある文字を返す
        return StringAll.toString();
    }

    public static CustomSign load(TextureURL textureURL, String[] parameters) {
        try {
            return new CustomSign(
                    textureURL,
                    Float.parseFloat(parameters[1]),
                    Float.parseFloat(parameters[2]),
                    Float.parseFloat(parameters[3]),
                    Float.parseFloat(parameters[4]),
                    Float.parseFloat(parameters[5]),
                    Float.parseFloat(parameters[6]),
                    Float.parseFloat(parameters[7]),
                    Float.parseFloat(parameters[8])
            );
        } catch (Exception e) {
            // ログ出力
            MarumaSign.LOGGER.warn(String.valueOf(e));
            return null;
        }
    }

    public static CustomSign load(String signText) {

        // 読み込んだことがあるか
        if (loadedCustomSign.containsKey(signText)) return loadedCustomSign.get(signText);

        String[] parameters = signText.split("\\|");
        if (parameters.length != 9) return null;

        final String StringURL = parameters[0];

        TextureURL textureURL = loadedTextureURL.get(StringURL);

        if (textureURL == null) {
            new GetImage(
                    // 画像のURL
                    StringURL,
                    parameters,
                    // 看板に書かれた文字
                    signText
            ).start();
            textureURL = new TextureURL(MarumaSignClient.Loading, 1, 1);
        }

        CustomSign customSign = load(textureURL, parameters);

        // 読み込み済みリストに 追加
        loadedCustomSign.put(signText, customSign);
        return customSign;

    }

    public record Vertex(
            float plusX, float plusZ,
            float Y,
            float minusX, float minusZ
    ) {
    }

    public record TextureURL(Identifier identifier, int width, int height) {
    }
}
