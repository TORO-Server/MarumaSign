package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.util.Utils;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class CustomSign {

    public final RenderType renderLayer;
    public final Vertex vertex;

    public CustomSign(RenderType renderLayer, CustomSign customSign) {
        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = renderLayer;
        this.vertex = customSign.vertex;
    }

    public CustomSign(TextureURL textureURL, Object[] parameters) {
        this(new CustomSignParameters(
                textureURL,
                (float) parameters[1],
                (float) parameters[2],
                (float) parameters[3],
                (float) parameters[4],
                (float) parameters[5],
                (float) parameters[6],
                (float) parameters[7],
                (float) parameters[8]
        ));
    }

    public CustomSign(CustomSignParameters csp) {

        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = Utils.getRenderLayer(csp.textureURL.identifier());

        final int width = csp.textureURL.width();
        final int height = csp.textureURL.height();
        // 頂点の位置 設定


        double maxVal = Math.max(width, height);
        Vector2f vec = new Vector2f(
                (float) (width / maxVal),
                (float) (height / maxVal)
        );


        vec.mul(csp.ScaleX, csp.ScaleY);

        this.vertex = new Vertex(
                csp.createVertex(-vec.x, -vec.y),
                csp.createVertex(-vec.x, +vec.y),
                csp.createVertex(+vec.x, +vec.y),
                csp.createVertex(+vec.x, -vec.y)
        );
    }

    public static String read(SignBlockEntity sign) {
        final StringBuilder stringAll = new StringBuilder();

        // 表面のテキストを結合
        for (int i = 0; i < 4; i++) {
            stringAll.append(sign.getFrontText().getMessage(i, false).getString());
        }
        // 裏面のテキストを結合
        for (int i = 0; i < 4; i++) {
            stringAll.append(sign.getBackText().getMessage(i, false).getString());
        }

        return stringAll.toString();
    }

    public static CustomSign create(TextureURL textureURL, Object[] parameters) {
        return new CustomSign(textureURL, parameters);
    }

    public record CustomSignParameters(
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
            float Roll,
            float Yaw,
            float Pitch
    ) {
        public Vector3f createVertex(float x, float y) {
            Vector3f vec = new Vector3f(x, y, 0);

            vec.rotateX((float) Math.toRadians(Pitch));
            vec.rotateY((float) Math.toRadians(-Yaw));
            vec.rotateZ((float) Math.toRadians(-Roll));

            vec.add(TranslationX * 2, TranslationY * 2, TranslationZ * 2);

            return vec;
        }
    }


    public record Vertex(
            Vector3f mi_mi,
            Vector3f mi_pl,
            Vector3f pl_pl,
            Vector3f pl_mi
    ) {
    }
}
