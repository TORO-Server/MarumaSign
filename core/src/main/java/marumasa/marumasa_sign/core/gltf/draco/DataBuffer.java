package marumasa.marumasa_sign.core.gltf.draco;

/**
 * 属性の値を詰めたバイト列 (リトルエンディアン)。
 */
public final class DataBuffer {

    private final byte[] buffer;

    DataBuffer(byte[] buffer) {
        this.buffer = buffer;
    }

    public byte[] getBuffer() {
        return buffer;
    }
}
