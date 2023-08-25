package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.util.Utils;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import org.apache.commons.lang3.ArrayUtils;
import org.joml.Quaternionf;

public class CustomSign {

    public final RenderLayer renderLayer;
    public final Vertex vertex;
    public final Quaternionf rotation;

    public CustomSign(RenderLayer renderLayer, CustomSign customSign) {
        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = renderLayer;

        this.vertex = customSign.vertex;
        this.rotation = customSign.rotation;
    }

    public CustomSign(
            // 画像のURL
            TextureURL textureURL,
            // 位置
            float TranslationX,
            float TranslationY,
            float TranslationZ,
            // 大きさ
            float ScaleX,
            float ScaleY,
            // 回転
            float RotationX,
            float RotationY,
            float RotationZ
    ) {

        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = Utils.getRenderLayer(textureURL.identifier());

        final int w = textureURL.width();
        final int h = textureURL.height();
        if (w > h) {
            // 大きさを設定
            ScaleY *= (double) h / w;
        } else {
            // 大きさを設定
            ScaleX *= (double) w / h;
        }
        // 頂点の位置 設定
        this.vertex = new Vertex(
                ScaleX + TranslationX * 2,
                ScaleY + TranslationY * 2,
                TranslationZ * 2,
                -ScaleX + TranslationX * 2,
                -ScaleY + TranslationY * 2
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

        // 表面と裏面に書いてある文字を合わせる
        final Text[] textAll = ArrayUtils.addAll(
                // 看板の表面に書かれている文字を取得
                sign.getFrontText().getMessages(true),
                // 看板の裏面に書かれている文字を取得
                sign.getBackText().getMessages(true)
        );

        // return で 返す String型の 文字 を作成するためのもの
        final StringBuilder StringAll = new StringBuilder();

        // textAll を読み取り StringAll に追加
        for (Text text : textAll) StringAll.append(text.getString());

        return StringAll.toString();
    }

    public static CustomSign create(TextureURL textureURL, Object[] parameters) {
        return new CustomSign(
                textureURL,
                (float) parameters[1],
                (float) parameters[2],
                (float) parameters[3],
                (float) parameters[4],
                (float) parameters[5],
                (float) parameters[6],
                (float) parameters[7],
                (float) parameters[8]
        );
    }

    public record Vertex(
            float plusX, float plusY,
            float Z,
            float minusX, float minusY
    ) {
    }
}
