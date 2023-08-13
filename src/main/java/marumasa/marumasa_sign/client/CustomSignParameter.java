package marumasa.marumasa_sign.client;

import com.google.common.io.BaseEncoding;
import marumasa.marumasa_sign.MarumaSign;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

public class CustomSignParameter {

    public final String StringURL;
    public final Translation translation;
    public final Scale scale;
    public final Quaternionf rotation;

    public final RenderLayer renderLayer;

    public CustomSignParameter(
            // 画像のURL
            String StringURL,
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

        // URL (文字列型)
        this.StringURL = StringURL;

        final TextureURL textureURL;

        // URL の画像を 読み込もうとしたことがあるか
        if (GetImage.loadedURL.containsKey(this.StringURL)) {
            textureURL = GetImage.loadedURL.get(this.StringURL);
        } else {
            // URL の画像を まだ読み込んだことがなかったら (読み込み済みリストに なかったら)

            final Identifier identifier = new Identifier(MarumaSign.MOD_ID, URLtoID());

            new GetImage(this.StringURL, identifier).start();
            // 読み込み済みリストに 追加
            GetImage.loadedURL.put(this.StringURL, null);

            textureURL = new TextureURL(new Identifier("none"), 16, 16);
        }

        // getText で 透過と半透明 対応の RenderLayer 生成
        // RenderLayer.getEntityTranslucent() では プレイヤーの向いている角度によって明度が変わってしまう
        // 確認したバージョン 1.20.1
        this.renderLayer = RenderLayer.getText(textureURL.identifier());

        // 位置を設定
        this.translation = new Translation(
                TranslationX,
                TranslationY,
                TranslationZ
        );

        int w = textureURL.width();
        int h = textureURL.height();

        if (w > h) {
            // 大きさを設定
            this.scale = new Scale(ScaleX, ScaleZ * h / w);
        } else {
            // 大きさを設定
            this.scale = new Scale(ScaleX * w / h, ScaleZ);
        }


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

    public static CustomSignParameter load(SignBlockEntity sign) {

        // 看板の表面に書かれている文字を取得
        Text[] textFront = sign.getFrontText().getMessages(true);
        // 看板の裏面に書かれている文字を取得
        Text[] textBack = sign.getBackText().getMessages(true);

        StringBuilder textAll = new StringBuilder();

        for (Text text : textFront) {
            textAll.append(text.getString());
        }
        for (Text text : textBack) {
            textAll.append(text.getString());
        }

        String[] parameters = textAll.toString().split("\\|");
        if (parameters.length != 9) return null;

        try {
            return new CustomSignParameter(
                    parameters[0],
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
            return null;
        }
    }

    public record Translation(float x, float y, float z) {
    }

    // 画像の大きさを
    public record Scale(float x, float z) {
    }

    // URLを Identifier で使える ID に変換
    private String URLtoID() {
        // base32 に変換
        String base32 = BaseEncoding.base32().encode(StringURL.getBytes());
        // Identifier は 大文字使えないので すべて小文字にする
        base32 = base32.toLowerCase();
        // Identifier は イコール という文字が使えないので アンダーバー に置き換える
        base32 = base32.replace('=', '_');
        return base32;
    }
}
