package marumasa.marumasa_sign.core.pdf.crypt;

/**
 * RC4。古い PDF の暗号化に使われる。暗号化と復号は同じ処理。
 */
final class Rc4 {

    private Rc4() {
    }

    static byte[] apply(byte[] key, byte[] data) {
        final int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        if (key.length > 0) {
            for (int i = 0, j = 0; i < 256; i++) {
                j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
                final int t = s[i];
                s[i] = s[j];
                s[j] = t;
            }
        }
        final byte[] out = new byte[data.length];
        for (int n = 0, i = 0, j = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            final int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[n] = (byte) (data[n] ^ s[(s[i] + s[j]) & 0xFF]);
        }
        return out;
    }
}
