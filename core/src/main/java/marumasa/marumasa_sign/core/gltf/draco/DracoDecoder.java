package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

/**
 * Draco で圧縮されたメッシュ (KHR_draco_mesh_compression) を展開する入口。
 * 壊れたデータや悪意のあるデータでも、終わらなくなったりメモリを使い切ったりせず IOException にする。
 */
public final class DracoDecoder {

    private DracoDecoder() {
    }

    public static DracoMesh decodeMesh(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty or null Draco data");
        }
        try {
            return MeshDecoding.decode(data);
        } catch (RuntimeException e) {
            // 配列の範囲外などは、すべてデータの誤りとして扱う
            throw new IOException("Invalid Draco data: " + e, e);
        }
    }
}
