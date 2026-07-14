package marumasa.marumasa_sign.type;

import marumasa.marumasa_sign.util.Utils;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class CustomSign {

    private final RenderType renderLayer;
    public final Vertex vertex;
    public final String stringURL;
    private final long startTime;
    private final double viewDistance;
    private final boolean isAnimated;
    private final boolean isLoading;

    public CustomSign(TextureURL textureURL, Object[] parameters) {
        this(new CustomSignParameters(
                textureURL,
                (parameters != null && parameters.length > 1) ? (float) parameters[1] : 0.0f,
                (parameters != null && parameters.length > 2) ? (float) parameters[2] : 0.0f,
                (parameters != null && parameters.length > 3) ? (float) parameters[3] : 0.0f,
                (parameters != null && parameters.length > 4) ? (float) parameters[4] : 1.0f,
                (parameters != null && parameters.length > 5) ? (float) parameters[5] : 1.0f,
                (parameters != null && parameters.length > 6) ? (float) parameters[6] : 0.0f,
                (parameters != null && parameters.length > 7) ? (float) parameters[7] : 0.0f,
                (parameters != null && parameters.length > 8) ? (float) parameters[8] : 0.0f
        ), (parameters != null && parameters.length > 0) ? (String) parameters[0] : "");
    }

    public CustomSign(CustomSignParameters csp, String stringURL) {
        this.stringURL = stringURL;
        this.startTime = System.currentTimeMillis();

        // getEntityTranslucent で 透過と半透明と裏面表示 対応の RenderLayer 生成
        this.renderLayer = Utils.getRenderLayer(csp.textureURL != null ? csp.textureURL.identifier() : TextureURL.error.identifier());

        final int width = csp.textureURL != null ? csp.textureURL.width() : 1;
        final int height = csp.textureURL != null ? csp.textureURL.height() : 1;
        // 頂点の位置 設定

        double maxVal = Math.max(width, height);
        if (maxVal <= 0.0) {
            maxVal = 1.0;
        }
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

        double maxDistSqr = 0;
        maxDistSqr = Math.max(maxDistSqr, this.vertex.mi_mi().lengthSquared());
        maxDistSqr = Math.max(maxDistSqr, this.vertex.mi_pl().lengthSquared());
        maxDistSqr = Math.max(maxDistSqr, this.vertex.pl_pl().lengthSquared());
        maxDistSqr = Math.max(maxDistSqr, this.vertex.pl_mi().lengthSquared());
        double maxOffset = Math.sqrt(maxDistSqr);
        this.viewDistance = Math.min(512.0, 64.0 + maxOffset * 2.0);
        this.isAnimated = marumasa.marumasa_sign.util.GifPlayer.gifMap.containsKey(stringURL);
        this.isLoading = (csp.textureURL == TextureURL.loading);
    }

    public RenderType getRenderLayer() {
        if (!this.isAnimated) {
            return this.renderLayer;
        }
        return marumasa.marumasa_sign.util.GifPlayer.getRenderType(this.stringURL, this.renderLayer, this.startTime);
    }

    public double getViewDistance() {
        return viewDistance;
    }

    public boolean isLoading() {
        return isLoading;
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
