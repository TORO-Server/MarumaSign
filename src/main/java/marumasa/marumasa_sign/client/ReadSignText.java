package marumasa.marumasa_sign.client;

import org.joml.Quaternionf;
import org.joml.Vector3d;

public class ReadSignText {

    public String URL;
    public final Translation translation;
    public final Scale scale;
    public final Quaternionf rotation;

    public ReadSignText() {

        translation = new Translation(0.5f, 0.5f, 0.5f);

        scale = new Scale(0.5f, 0.5f, 0.5f);

        Quaternionf qx = new Quaternionf();
        // X 軸に どれくらい回転するか 設定
        qx.fromAxisAngleDeg(1, 0, 0, 0);

        Quaternionf qy = new Quaternionf();
        // Y 軸に どれくらい回転するか 設定
        qy.fromAxisAngleDeg(0, 1, 0, 0);

        Quaternionf qz = new Quaternionf();
        // Z 軸に どれくらい回転するか 設定
        qz.fromAxisAngleDeg(0, 0, 1, 0);

        // クォータニオンの積 計算
        rotation = new Quaternionf().mul(qx).mul(qy).mul(qz);
    }

    public static class Translation {
        public float x;
        public float y;
        public float z;

        public Translation(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class Scale {
        public float x;
        public float y;
        public float z;

        public Scale(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
