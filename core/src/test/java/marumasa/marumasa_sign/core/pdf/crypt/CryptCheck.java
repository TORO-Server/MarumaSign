package marumasa.marumasa_sign.core.pdf.crypt;

import java.nio.charset.StandardCharsets;

/**
 * 暗号の部品の試験: AES (FIPS 197 の既知の答え)、RC4 (RFC 6229 の既知の答え)、復号の往復。
 */
public final class CryptCheck {

    private CryptCheck() {
    }

    public static void run() {
        // FIPS 197 の附属書 C の例
        final byte[] plain = hex("00112233445566778899aabbccddeeff");
        check(hex("69c4e0d86a7b0430d8cdb78070b4c55a"), SecurityHandler.encryptBlockForTest(hex("000102030405060708090a0b0c0d0e0f"), plain), "AES-128");
        check(hex("dda97ca4864cdfe06eaf70a0ec0d7191"), SecurityHandler.encryptBlockForTest(hex("000102030405060708090a0b0c0d0e0f1011121314151617"), plain), "AES-192");
        check(hex("8ea2b7ca516745bfeafc49904b496089"),
                SecurityHandler.encryptBlockForTest(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), plain), "AES-256");
        // 復号は暗号化の逆
        for (String key : new String[]{"000102030405060708090a0b0c0d0e0f", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"}) {
            final Aes aes = new Aes(hex(key));
            final byte[] iv = hex("0f0e0d0c0b0a09080706050403020100");
            final byte[] data = "The quick brown fox jumps over the lazy dog!!!!!".getBytes(StandardCharsets.ISO_8859_1);
            final byte[] encrypted = new byte[data.length];
            aes.encryptCbc(iv, data, data.length, encrypted);
            final byte[] decrypted = new byte[data.length];
            aes.decryptCbc(iv, encrypted, 0, data.length, decrypted);
            check(data, decrypted, "AES CBC round trip");
        }
        // RFC 6229 (鍵 0102030405、先頭の 16 バイト)
        check(hex("b2396305f03dc027ccc3524a0a1118a8"), Rc4.apply(hex("0102030405"), new byte[16]), "RC4");
    }

    private static void check(byte[] expected, byte[] actual, String name) {
        if (!java.util.Arrays.equals(expected, actual)) throw new AssertionError(name + " gives a wrong result");
    }

    private static byte[] hex(String text) {
        final byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
