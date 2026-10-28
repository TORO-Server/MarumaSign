package marumasa.marumasa_sign.core.pdf.crypt;

import marumasa.marumasa_sign.core.pdf.cos.PdfArray;
import marumasa.marumasa_sign.core.pdf.cos.PdfDict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 暗号化された PDF の復号 (標準のセキュリティハンドラ、ISO 32000-2 の 7.6.4)。
 * 開くためのパスワードがない PDF (閲覧はでき、印刷や編集だけが制限されたもの) だけを、空のパスワードで開く。
 * 対応する方式: RC4 (40 から 128 ビット)、AES-128 (AESV2)、AES-256 (AESV3、R5 と R6)。
 */
public final class SecurityHandler {

    // パスワードの詰め物 (7.6.4.3 の Algorithm 2)
    private static final byte[] PADDING = {
            (byte) 0x28, (byte) 0xBF, (byte) 0x4E, (byte) 0x5E, (byte) 0x4E, (byte) 0x75, (byte) 0x8A, (byte) 0x41,
            (byte) 0x64, (byte) 0x00, (byte) 0x4E, (byte) 0x56, (byte) 0xFF, (byte) 0xFA, (byte) 0x01, (byte) 0x08,
            (byte) 0x2E, (byte) 0x2E, (byte) 0x00, (byte) 0xB6, (byte) 0xD0, (byte) 0x68, (byte) 0x3E, (byte) 0x80,
            (byte) 0x2F, (byte) 0x0C, (byte) 0xA9, (byte) 0xFE, (byte) 0x64, (byte) 0x53, (byte) 0x69, (byte) 0x7A};

    // 復号のしかた
    private enum Method {NONE, RC4, AES128, AES256}

    private final byte[] key;
    private final Method streamMethod;
    private final Method stringMethod;

    private SecurityHandler(byte[] key, Method streamMethod, Method stringMethod) {
        this.key = key;
        this.streamMethod = streamMethod;
        this.stringMethod = stringMethod;
    }

    /**
     * /Encrypt の辞書から、空のパスワードで復号の準備をする。開くのにパスワードが要る PDF なら IOException。
     *
     * @param id トレーラーの /ID の最初の文字列 (なければ空)
     */
    public static SecurityHandler create(PdfDict encrypt, byte[] id) throws IOException {
        final String filter = encrypt.name("Filter");
        if (filter != null && !filter.equals("Standard")) throw new IOException("Unsupported security handler: " + filter);
        final int v = encrypt.integer("V", 0);
        final int r = encrypt.integer("R", 0);
        Method streamMethod;
        Method stringMethod;
        int length = encrypt.integer("Length", 40);
        if (v == 1 || v == 2 || v == 3) {
            streamMethod = stringMethod = Method.RC4;
            if (v == 1) length = 40;
        } else if (v == 4 || v == 5) {
            final PdfDict filters = encrypt.dict("CF");
            streamMethod = cryptFilter(filters, encrypt.name("StmF"));
            stringMethod = cryptFilter(filters, encrypt.name("StrF"));
            if (v == 5) {
                length = 256;
            } else {
                // V4 では、使う暗号フィルタの /Length (バイトかビットかが書き手によって違う) を使う
                final PdfDict standard = filters == null ? null : filters.dict(encrypt.name("StmF") == null ? "StdCF" : encrypt.name("StmF"));
                int filterLength = standard == null ? 16 : standard.integer("Length", 16);
                if (filterLength > 0 && filterLength <= 32) filterLength *= 8;
                length = streamMethod == Method.AES128 ? 128 : filterLength;
            }
        } else {
            throw new IOException("Unsupported encryption version " + v);
        }
        // RC4 と AES-128 の鍵は 40 から 128 ビットで、8 の倍数 (仕様の範囲の外の値は、範囲に収める)。
        // 収めないと、鍵の長さが MD5 の長さ (16 バイト) を超えて、鍵を作るときに範囲外を読む
        if (v < 5) length = Math.max(40, Math.min(128, length - length % 8));
        if (length < 40 || length > 256 || length % 8 != 0) length = 128;
        // 文字列もストリームも暗号化しない指定 (埋め込まれたファイルだけを暗号化したもの) なら、パスワードは要らない
        if (streamMethod == Method.NONE && stringMethod == Method.NONE) return new SecurityHandler(new byte[0], Method.NONE, Method.NONE);

        final byte[] o = bytes(encrypt.string("O"));
        final byte[] u = bytes(encrypt.string("U"));
        // /P は 32 ビットの値。符号なしで書かれたもの (4294967292 など) も、下位 32 ビットとして読む
        final Object pValue = encrypt.get("P");
        final int p = pValue instanceof Number ? (int) (long) ((Number) pValue).doubleValue() : 0;
        final boolean encryptMetadata = encrypt.bool("EncryptMetadata", true);
        final byte[] key;
        if (r >= 5) {
            key = aes256Key(r, o, u, bytes(encrypt.string("OE")), bytes(encrypt.string("UE")));
        } else {
            if (r < 2) throw new IOException("Unsupported encryption revision " + r);
            final int keyLength = r == 2 ? 5 : length / 8;
            key = rc4Key(r, keyLength, o, u, p, id, encryptMetadata);
        }
        return new SecurityHandler(key, streamMethod, stringMethod);
    }

    private static byte[] bytes(byte[] value) {
        return value == null ? new byte[0] : value;
    }

    private static Method cryptFilter(PdfDict filters, String name) throws IOException {
        if (name == null || name.equals("Identity")) return Method.NONE;
        final PdfDict filter = filters == null ? null : filters.dict(name);
        if (filter == null) throw new IOException("Missing crypt filter " + name);
        final String cfm = filter.name("CFM");
        if (cfm == null || cfm.equals("None")) return Method.NONE;
        if (cfm.equals("V2")) return Method.RC4;
        if (cfm.equals("AESV2")) return Method.AES128;
        if (cfm.equals("AESV3")) return Method.AES256;
        throw new IOException("Unsupported crypt filter method " + cfm);
    }

    // ---------- R2 から R4 (RC4 と AES-128) ----------

    // 空のパスワードを、利用者のパスワードとして、それでだめなら所有者のパスワードとして試す
    private static byte[] rc4Key(int r, int keyLength, byte[] o, byte[] u, int p, byte[] id, boolean encryptMetadata) throws IOException {
        final byte[] user = new byte[0];
        byte[] key = computeKey(r, keyLength, user, o, p, id, encryptMetadata);
        if (checkUser(r, key, u, id)) return key;
        // 所有者のパスワード (Algorithm 7): O を戻すと利用者のパスワードになる
        final byte[] ownerKey = ownerKey(r, keyLength, user);
        byte[] userPassword = Arrays.copyOf(o, 32);
        if (r == 2) {
            userPassword = Rc4.apply(ownerKey, userPassword);
        } else {
            for (int i = 19; i >= 0; i--) userPassword = Rc4.apply(xor(ownerKey, i), userPassword);
        }
        key = computeKey(r, keyLength, userPassword, o, p, id, encryptMetadata);
        if (checkUser(r, key, u, id)) return key;
        throw new IOException("The PDF is protected by a password");
    }

    // Algorithm 2
    private static byte[] computeKey(int r, int keyLength, byte[] password, byte[] o, int p, byte[] id, boolean encryptMetadata) {
        final MessageDigest md5 = digest("MD5");
        md5.update(pad(password));
        md5.update(Arrays.copyOf(o, 32));
        md5.update(new byte[]{(byte) p, (byte) (p >> 8), (byte) (p >> 16), (byte) (p >> 24)});
        md5.update(id);
        if (r >= 4 && !encryptMetadata) md5.update(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        byte[] hash = md5.digest();
        if (r >= 3) {
            for (int i = 0; i < 50; i++) {
                md5.update(hash, 0, keyLength);
                hash = md5.digest();
            }
        }
        return Arrays.copyOf(hash, keyLength);
    }

    // Algorithm 4 と 5
    private static boolean checkUser(int r, byte[] key, byte[] u, byte[] id) {
        if (r == 2) return Arrays.equals(Rc4.apply(key, PADDING), Arrays.copyOf(u, 32));
        final MessageDigest md5 = digest("MD5");
        md5.update(PADDING);
        md5.update(id);
        byte[] value = Rc4.apply(key, md5.digest());
        for (int i = 1; i <= 19; i++) value = Rc4.apply(xor(key, i), value);
        return u.length >= 16 && Arrays.equals(value, Arrays.copyOf(u, 16));
    }

    private static byte[] ownerKey(int r, int keyLength, byte[] password) {
        final MessageDigest md5 = digest("MD5");
        byte[] hash = md5.digest(pad(password));
        if (r >= 3) {
            for (int i = 0; i < 50; i++) hash = md5.digest(hash);
        }
        return Arrays.copyOf(hash, keyLength);
    }

    private static byte[] pad(byte[] password) {
        final byte[] result = new byte[32];
        final int n = Math.min(32, password.length);
        System.arraycopy(password, 0, result, 0, n);
        System.arraycopy(PADDING, 0, result, n, 32 - n);
        return result;
    }

    private static byte[] xor(byte[] key, int value) {
        final byte[] result = new byte[key.length];
        for (int i = 0; i < key.length; i++) result[i] = (byte) (key[i] ^ value);
        return result;
    }

    // ---------- R5 と R6 (AES-256) ----------

    private static byte[] aes256Key(int r, byte[] o, byte[] u, byte[] oe, byte[] ue) throws IOException {
        if (o.length < 48 || u.length < 48) throw new IOException("Broken encryption dictionary");
        final byte[] password = new byte[0];
        final byte[] u48 = Arrays.copyOf(u, 48);
        // 利用者のパスワード (Algorithm 11)
        if (Arrays.equals(hash(r, password, Arrays.copyOfRange(u, 32, 40), new byte[0]), Arrays.copyOf(u, 32))) {
            return unwrap(hash(r, password, Arrays.copyOfRange(u, 40, 48), new byte[0]), ue);
        }
        // 所有者のパスワード (Algorithm 12)
        if (Arrays.equals(hash(r, password, Arrays.copyOfRange(o, 32, 40), u48), Arrays.copyOf(o, 32))) {
            return unwrap(hash(r, password, Arrays.copyOfRange(o, 40, 48), u48), oe);
        }
        throw new IOException("The PDF is protected by a password");
    }

    // 暗号化された鍵 (UE / OE) を戻す: AES-256、CBC、IV は 0、詰め物なし
    private static byte[] unwrap(byte[] intermediate, byte[] wrapped) throws IOException {
        if (wrapped.length < 32) throw new IOException("Broken encryption dictionary");
        final byte[] key = new byte[32];
        new Aes(intermediate).decryptCbc(new byte[16], wrapped, 0, 32, key);
        return key;
    }

    /**
     * パスワードのハッシュ。R5 は SHA-256 だけ、R6 は Algorithm 2.B (SHA-256/384/512 と AES-128 の繰り返し)。
     */
    static byte[] hash(int r, byte[] password, byte[] salt, byte[] userKey) {
        final byte[] pw = password.length > 127 ? Arrays.copyOf(password, 127) : password;
        final MessageDigest sha256 = digest("SHA-256");
        sha256.update(pw);
        sha256.update(salt);
        sha256.update(userKey);
        byte[] k = sha256.digest();
        if (r == 5) return k;
        final MessageDigest sha384 = digest("SHA-384");
        final MessageDigest sha512 = digest("SHA-512");
        int round = 0;
        while (true) {
            final int unit = pw.length + k.length + userKey.length;
            final byte[] k1 = new byte[unit * 64];
            for (int i = 0; i < 64; i++) {
                System.arraycopy(pw, 0, k1, i * unit, pw.length);
                System.arraycopy(k, 0, k1, i * unit + pw.length, k.length);
                System.arraycopy(userKey, 0, k1, i * unit + pw.length + k.length, userKey.length);
            }
            final byte[] e = new byte[k1.length];
            new Aes(Arrays.copyOf(k, 16)).encryptCbc(Arrays.copyOfRange(k, 16, 32), k1, k1.length, e);
            // 最初の 16 バイトを 1 つの数とみなした、3 で割った余り (各バイトの和の余りと同じ)
            int sum = 0;
            for (int i = 0; i < 16; i++) sum += e[i] & 0xFF;
            final MessageDigest next = sum % 3 == 0 ? sha256 : sum % 3 == 1 ? sha384 : sha512;
            k = next.digest(e);
            round++;
            if (round >= 64 && (e[e.length - 1] & 0xFF) <= round - 32) break;
        }
        return Arrays.copyOf(k, 32);
    }

    private static MessageDigest digest(String name) {
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            // Java には必ずあるもの
            throw new IllegalStateException(e);
        }
    }

    // ---------- 復号 ----------

    public byte[] decryptString(int number, int generation, byte[] data) {
        return decrypt(stringMethod, number, generation, data);
    }

    public byte[] decryptStream(int number, int generation, byte[] data) {
        return decrypt(streamMethod, number, generation, data);
    }

    private byte[] decrypt(Method method, int number, int generation, byte[] data) {
        switch (method) {
            case RC4:
                return Rc4.apply(objectKey(number, generation, false), data);
            case AES128:
                return aes(objectKey(number, generation, true), data);
            case AES256:
                return aes(key, data);
            default:
                return data;
        }
    }

    // Algorithm 1: オブジェクトごとの鍵
    private byte[] objectKey(int number, int generation, boolean aes) {
        final MessageDigest md5 = digest("MD5");
        md5.update(key);
        md5.update(new byte[]{(byte) number, (byte) (number >> 8), (byte) (number >> 16), (byte) generation, (byte) (generation >> 8)});
        if (aes) md5.update(new byte[]{0x73, 0x41, 0x6C, 0x54}); // "sAlT"
        return Arrays.copyOf(md5.digest(), Math.min(key.length + 5, 16));
    }

    // 先頭の 16 バイトが IV。最後の詰め物 (PKCS#5) は、正しい形のときだけ取り除く
    private static byte[] aes(byte[] key, byte[] data) {
        if (data.length < 32) return new byte[0];
        final int length = (data.length - 16) / 16 * 16;
        final byte[] out = new byte[length];
        new Aes(key).decryptCbc(Arrays.copyOf(data, 16), data, 16, length, out);
        final int padding = out[length - 1] & 0xFF;
        if (padding >= 1 && padding <= 16) {
            boolean valid = true;
            for (int i = length - padding; i < length; i++) {
                if ((out[i] & 0xFF) != padding) {
                    valid = false;
                    break;
                }
            }
            if (valid) return Arrays.copyOf(out, length - padding);
        }
        return out;
    }

    // /ID の最初の文字列
    public static byte[] firstId(PdfArray ids) {
        if (ids == null || ids.size() == 0) return new byte[0];
        final Object first = ids.get(0);
        return first instanceof marumasa.marumasa_sign.core.pdf.cos.PdfString
                ? ((marumasa.marumasa_sign.core.pdf.cos.PdfString) first).bytes : new byte[0];
    }

    // テスト用: AES の実装を外から確かめる
    static byte[] encryptBlockForTest(byte[] key, byte[] block) {
        final byte[] out = new byte[16];
        new Aes(key).encryptBlock(block, 0, out, 0);
        return out;
    }

    static String describe(byte[] value) {
        return new String(value, StandardCharsets.ISO_8859_1);
    }
}
