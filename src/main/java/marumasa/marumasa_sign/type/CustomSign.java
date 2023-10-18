package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.util.Utils;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import org.apache.commons.lang3.ArrayUtils;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class CustomSign {

    public final RenderLayer renderLayer;
    public final Quaternionf rotation;

    public final Vertex vertex;

    public CustomSign(RenderLayer renderLayer, CustomSign customSign) {
        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = renderLayer;

        this.vector = customSign.vector;
        this.rotation = customSign.rotation;
    }

    public static Vector3f createVertex(

            float x,
            float y,

            float TranslationX,
            float TranslationY,
            float TranslationZ,

            float RotationX,
            float RotationY,
            float RotationZ

    ) {
        Vector3f vec = new Vector3f(x, y, 0);

        vec.rotateX((float) Math.toRadians(RotationX));
        vec.rotateY((float) Math.toRadians(RotationY));
        vec.rotateZ((float) Math.toRadians(RotationZ));

        vec.add(TranslationX, TranslationY, TranslationZ);

        return vec;
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

        final int width = textureURL.width();
        final int height = textureURL.height();
        // 頂点の位置 設定


        Vector2f vector = new Vector2f(width, height);
        vector.normalize();
        vector.mul(ScaleX, ScaleY);

        Vector3f vector_plus = createVertex(vector);
        Vector3f vector_minus = new Vector3f(vector.x, vector.y, vector.z);

        this.vertex = new Vertex(

        );

        vector.add(TranslationX, TranslationY, TranslationZ);

        vector.rotateX((float) Math.toRadians(RotationX));
        vector.rotateY((float) Math.toRadians(RotationY));
        vector.rotateZ((float) Math.toRadians(RotationZ));


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
