package marumasa.marumasa_sign.core.pdf.crypt;

/**
 * AES (FIPS 197) の暗号化と復号。鍵は 128 / 192 / 256 ビット。
 * <p>
 * Java の javax.crypto を使わないのは、古い Minecraft が使う Java 8 (8u151 まで) では、
 * 法令の制限のために 256 ビットの鍵が既定で使えず、AES-256 で暗号化された PDF が開けないため。
 * 秘密を守るための暗号ではなく (鍵は空のパスワードから作る)、表示のために中身を戻すだけなので、
 * 処理時間の違いから鍵が漏れること (タイミング攻撃) は気にせず、表を引く普通の作りにしている。
 */
final class Aes {

    private static final int[] SBOX = new int[256];
    private static final int[] INVERSE_SBOX = new int[256];
    // 列の混ぜ合わせを、表を引くだけで行うための表 (暗号化用と復号用)
    private static final int[] ENCRYPT0 = new int[256], ENCRYPT1 = new int[256], ENCRYPT2 = new int[256], ENCRYPT3 = new int[256];
    private static final int[] DECRYPT0 = new int[256], DECRYPT1 = new int[256], DECRYPT2 = new int[256], DECRYPT3 = new int[256];

    static {
        // GF(2^8) の掛け算の表を、3 を生成元とするべき乗と対数から作る
        final int[] power = new int[256];
        final int[] log = new int[256];
        int x = 1;
        for (int i = 0; i < 256; i++) {
            power[i] = x;
            log[x] = i;
            x ^= (x << 1) ^ ((x & 0x80) != 0 ? 0x11B : 0);
            x &= 0xFF;
        }
        for (int i = 0; i < 256; i++) {
            // 逆数 (0 の逆数は 0 とする) にアフィン変換をかける
            int inverse = i == 0 ? 0 : power[255 - log[i]];
            int s = inverse;
            for (int k = 0; k < 4; k++) {
                inverse = ((inverse << 1) | (inverse >>> 7)) & 0xFF;
                s ^= inverse;
            }
            s ^= 0x63;
            SBOX[i] = s;
            INVERSE_SBOX[s] = i;
        }
        for (int i = 0; i < 256; i++) {
            final int s = SBOX[i];
            final int e = (multiply(s, 2, power, log) << 24) | (s << 16) | (s << 8) | multiply(s, 3, power, log);
            ENCRYPT0[i] = e;
            ENCRYPT1[i] = Integer.rotateRight(e, 8);
            ENCRYPT2[i] = Integer.rotateRight(e, 16);
            ENCRYPT3[i] = Integer.rotateRight(e, 24);
            final int v = INVERSE_SBOX[i];
            final int d = (multiply(v, 14, power, log) << 24) | (multiply(v, 9, power, log) << 16)
                    | (multiply(v, 13, power, log) << 8) | multiply(v, 11, power, log);
            DECRYPT0[i] = d;
            DECRYPT1[i] = Integer.rotateRight(d, 8);
            DECRYPT2[i] = Integer.rotateRight(d, 16);
            DECRYPT3[i] = Integer.rotateRight(d, 24);
        }
    }

    private static int multiply(int a, int b, int[] power, int[] log) {
        if (a == 0 || b == 0) return 0;
        return power[(log[a] + log[b]) % 255];
    }

    private final int rounds;
    private final int[] encryptKeys;
    private final int[] decryptKeys;

    Aes(byte[] key) {
        final int words = key.length / 4;
        if (key.length != 16 && key.length != 24 && key.length != 32) throw new IllegalArgumentException("Bad AES key length");
        rounds = words + 6;
        final int total = 4 * (rounds + 1);
        encryptKeys = new int[total];
        for (int i = 0; i < words; i++) {
            encryptKeys[i] = ((key[4 * i] & 0xFF) << 24) | ((key[4 * i + 1] & 0xFF) << 16) | ((key[4 * i + 2] & 0xFF) << 8) | (key[4 * i + 3] & 0xFF);
        }
        int rcon = 1;
        for (int i = words; i < total; i++) {
            int t = encryptKeys[i - 1];
            if (i % words == 0) {
                t = substitute(Integer.rotateLeft(t, 8)) ^ (rcon << 24);
                rcon = (rcon << 1) ^ ((rcon & 0x80) != 0 ? 0x11B : 0);
            } else if (words > 6 && i % words == 4) {
                t = substitute(t);
            }
            encryptKeys[i] = encryptKeys[i - words] ^ t;
        }
        // 復号用の鍵: 順番を逆にし、最初と最後以外は列の混ぜ合わせの逆をかけておく
        decryptKeys = new int[total];
        for (int round = 0; round <= rounds; round++) {
            for (int c = 0; c < 4; c++) {
                int k = encryptKeys[4 * (rounds - round) + c];
                if (round > 0 && round < rounds) {
                    k = DECRYPT0[SBOX[k >>> 24]] ^ DECRYPT1[SBOX[(k >>> 16) & 0xFF]]
                            ^ DECRYPT2[SBOX[(k >>> 8) & 0xFF]] ^ DECRYPT3[SBOX[k & 0xFF]];
                }
                decryptKeys[4 * round + c] = k;
            }
        }
    }

    private static int substitute(int word) {
        return (SBOX[word >>> 24] << 24) | (SBOX[(word >>> 16) & 0xFF] << 16) | (SBOX[(word >>> 8) & 0xFF] << 8) | SBOX[word & 0xFF];
    }

    private static int word(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void put(byte[] b, int o, int w) {
        b[o] = (byte) (w >>> 24);
        b[o + 1] = (byte) (w >>> 16);
        b[o + 2] = (byte) (w >>> 8);
        b[o + 3] = (byte) w;
    }

    // 16 バイトを 1 つ暗号化する
    void encryptBlock(byte[] in, int inOffset, byte[] out, int outOffset) {
        final int[] k = encryptKeys;
        int s0 = word(in, inOffset) ^ k[0], s1 = word(in, inOffset + 4) ^ k[1];
        int s2 = word(in, inOffset + 8) ^ k[2], s3 = word(in, inOffset + 12) ^ k[3];
        int p = 4;
        for (int round = 1; round < rounds; round++, p += 4) {
            final int t0 = ENCRYPT0[s0 >>> 24] ^ ENCRYPT1[(s1 >>> 16) & 0xFF] ^ ENCRYPT2[(s2 >>> 8) & 0xFF] ^ ENCRYPT3[s3 & 0xFF] ^ k[p];
            final int t1 = ENCRYPT0[s1 >>> 24] ^ ENCRYPT1[(s2 >>> 16) & 0xFF] ^ ENCRYPT2[(s3 >>> 8) & 0xFF] ^ ENCRYPT3[s0 & 0xFF] ^ k[p + 1];
            final int t2 = ENCRYPT0[s2 >>> 24] ^ ENCRYPT1[(s3 >>> 16) & 0xFF] ^ ENCRYPT2[(s0 >>> 8) & 0xFF] ^ ENCRYPT3[s1 & 0xFF] ^ k[p + 2];
            final int t3 = ENCRYPT0[s3 >>> 24] ^ ENCRYPT1[(s0 >>> 16) & 0xFF] ^ ENCRYPT2[(s1 >>> 8) & 0xFF] ^ ENCRYPT3[s2 & 0xFF] ^ k[p + 3];
            s0 = t0;
            s1 = t1;
            s2 = t2;
            s3 = t3;
        }
        put(out, outOffset, lastEncrypt(s0, s1, s2, s3) ^ k[p]);
        put(out, outOffset + 4, lastEncrypt(s1, s2, s3, s0) ^ k[p + 1]);
        put(out, outOffset + 8, lastEncrypt(s2, s3, s0, s1) ^ k[p + 2]);
        put(out, outOffset + 12, lastEncrypt(s3, s0, s1, s2) ^ k[p + 3]);
    }

    private static int lastEncrypt(int a, int b, int c, int d) {
        return (SBOX[a >>> 24] << 24) | (SBOX[(b >>> 16) & 0xFF] << 16) | (SBOX[(c >>> 8) & 0xFF] << 8) | SBOX[d & 0xFF];
    }

    // 16 バイトを 1 つ復号する
    void decryptBlock(byte[] in, int inOffset, byte[] out, int outOffset) {
        final int[] k = decryptKeys;
        int s0 = word(in, inOffset) ^ k[0], s1 = word(in, inOffset + 4) ^ k[1];
        int s2 = word(in, inOffset + 8) ^ k[2], s3 = word(in, inOffset + 12) ^ k[3];
        int p = 4;
        for (int round = 1; round < rounds; round++, p += 4) {
            final int t0 = DECRYPT0[s0 >>> 24] ^ DECRYPT1[(s3 >>> 16) & 0xFF] ^ DECRYPT2[(s2 >>> 8) & 0xFF] ^ DECRYPT3[s1 & 0xFF] ^ k[p];
            final int t1 = DECRYPT0[s1 >>> 24] ^ DECRYPT1[(s0 >>> 16) & 0xFF] ^ DECRYPT2[(s3 >>> 8) & 0xFF] ^ DECRYPT3[s2 & 0xFF] ^ k[p + 1];
            final int t2 = DECRYPT0[s2 >>> 24] ^ DECRYPT1[(s1 >>> 16) & 0xFF] ^ DECRYPT2[(s0 >>> 8) & 0xFF] ^ DECRYPT3[s3 & 0xFF] ^ k[p + 2];
            final int t3 = DECRYPT0[s3 >>> 24] ^ DECRYPT1[(s2 >>> 16) & 0xFF] ^ DECRYPT2[(s1 >>> 8) & 0xFF] ^ DECRYPT3[s0 & 0xFF] ^ k[p + 3];
            s0 = t0;
            s1 = t1;
            s2 = t2;
            s3 = t3;
        }
        put(out, outOffset, lastDecrypt(s0, s3, s2, s1) ^ k[p]);
        put(out, outOffset + 4, lastDecrypt(s1, s0, s3, s2) ^ k[p + 1]);
        put(out, outOffset + 8, lastDecrypt(s2, s1, s0, s3) ^ k[p + 2]);
        put(out, outOffset + 12, lastDecrypt(s3, s2, s1, s0) ^ k[p + 3]);
    }

    private static int lastDecrypt(int a, int b, int c, int d) {
        return (INVERSE_SBOX[a >>> 24] << 24) | (INVERSE_SBOX[(b >>> 16) & 0xFF] << 16)
                | (INVERSE_SBOX[(c >>> 8) & 0xFF] << 8) | INVERSE_SBOX[d & 0xFF];
    }

    /**
     * CBC で復号する。length は 16 の倍数 (端数は呼ぶ側で切る)。
     */
    void decryptCbc(byte[] iv, byte[] in, int offset, int length, byte[] out) {
        final byte[] previous = iv.clone();
        final byte[] block = new byte[16];
        for (int i = 0; i < length; i += 16) {
            decryptBlock(in, offset + i, block, 0);
            for (int j = 0; j < 16; j++) {
                final byte cipher = in[offset + i + j];
                out[i + j] = (byte) (block[j] ^ previous[j]);
                previous[j] = cipher;
            }
        }
    }

    /**
     * CBC で暗号化する (詰め物なし)。length は 16 の倍数。
     */
    void encryptCbc(byte[] iv, byte[] in, int length, byte[] out) {
        final byte[] chain = iv.clone();
        for (int i = 0; i < length; i += 16) {
            for (int j = 0; j < 16; j++) chain[j] ^= in[i + j];
            encryptBlock(chain, 0, out, i);
            System.arraycopy(out, i, chain, 0, 16);
        }
    }
}
