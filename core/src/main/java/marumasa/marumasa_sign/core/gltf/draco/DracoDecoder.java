package marumasa.marumasa_sign.core.gltf.draco;

import java.io.IOException;

public final class DracoDecoder {
    private DracoDecoder() {}

    public static DracoMesh decodeMesh(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty or null Draco data");
        }
        try {
            DecoderBuffer buffer = new DecoderBuffer(data);
            DracoPointCloud cloud = DracoMesh.decode(buffer, true);
            if (!(cloud instanceof DracoMesh)) {
                throw new IOException("Decoded geometry is not a Draco triangular mesh");
            }
            return (DracoMesh) cloud;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Draco decode error: " + e.getMessage(), e);
        }
    }
}