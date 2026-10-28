package marumasa.marumasa_sign.core.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;

/**
 * 看板に書かれた URL を読んでよいかどうか。
 * 看板の URL は誰でも書けるので、看板を見ただけで、見た人のパソコンの中 (127.0.0.1 など) や家の中のネットワーク
 * (ルーターなどの 192.168.x.x、10.x.x.x など) にアクセスさせられないように、そうした場所の URL は読まない。
 * リダイレクトの行き先にも同じ確認をする (Http.download)。
 * 確認のための手元のサーバーを使うときだけ、システムプロパティ marumasign.allowLocalUrls=true で許す。
 */
public final class UrlPolicy {

    private UrlPolicy() {
    }

    public static boolean allowLocal() {
        return Boolean.getBoolean("marumasign.allowLocalUrls");
    }

    /**
     * http または https で、インターネット上の場所を指している URL なら true。
     * 名前を引けない URL は、読み込むときに失敗するので true にする (ここで止めるのは、手元や家の中を指すものだけ)。
     */
    public static boolean isAllowed(String url) {
        if (url == null) return false;
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (allowLocal()) return true;
        final String host;
        final int port;
        try {
            final URL parsed = new URL(url);
            host = parsed.getHost();
            port = parsed.getPort() < 0 ? parsed.getDefaultPort() : parsed.getPort();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty() || isLocalName(host) || isBlockedPort(port)) return false;
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return true;
        }
        for (InetAddress address : addresses) {
            if (isLocal(address)) return false;
        }
        return true;
    }

    /**
     * 名前を引き、つないでよい行き先ならそのアドレスを返す。手元や家の中を指していれば null。
     * 引いたアドレスにそのままつなげば、確かめた後に名前の行き先をすり替えられることもない。
     */
    public static InetAddress[] resolve(String host) throws IOException {
        if (!allowLocal() && isLocalName(host)) return null;
        final InetAddress[] addresses = InetAddress.getAllByName(host);
        if (allowLocal()) return addresses;
        for (InetAddress address : addresses) {
            if (isLocal(address)) return null;
        }
        return addresses;
    }

    // ウェブのブラウザーがつながない番号 (WHATWG Fetch の「bad port」)。メールやファイル共有などのサービスで、
    // 看板の URL から HTTP の要求を送りつけさせて、そうしたサービスを操作させないようにする
    private static final int[] BLOCKED_PORTS = {
            0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102, 103, 104, 109, 110,
            111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532,
            540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061,
            6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080,
    };

    public static boolean isBlockedPort(int port) {
        for (int blocked : BLOCKED_PORTS) {
            if (port == blocked) return true;
        }
        return false;
    }

    private static boolean isLocalName(String host) {
        // 最後の点 (localhost. や printer.local. の書き方) を除いてから比べる。除かないと、名前で止められず、
        // 名前を引けない環境では通してしまう
        String name = host.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name.isEmpty() || name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")
                || name.endsWith(".lan") || name.endsWith(".home.arpa");
    }

    static boolean isLocal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        final byte[] raw = address.getAddress();
        if (raw.length == 4) {
            final int a = raw[0] & 0xFF, b = raw[1] & 0xFF;
            // 0.0.0.0/8、共有アドレス 100.64.0.0/10、ベンチマーク用 198.18.0.0/15、予約 240.0.0.0/4
            return a == 0 || (a == 100 && (b & 0xC0) == 64) || (a == 198 && (b & 0xFE) == 18) || a >= 240;
        }
        if (raw.length == 16) {
            // 固有ローカルアドレス fc00::/7
            if ((raw[0] & 0xFE) == 0xFC) return true;
            // IPv4 を埋め込んだもの。埋め込まれた IPv4 のアドレスで確かめる
            boolean zeros = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) zeros = false;
            }
            // ::ffff:a.b.c.d と、古い書き方の ::a.b.c.d
            if (zeros && (((raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) || (raw[10] == 0 && raw[11] == 0))) {
                return isLocalIpv4(raw[12], raw[13], raw[14], raw[15]);
            }
            // NAT64 の 64:ff9b::a.b.c.d
            if ((raw[0] & 0xFF) == 0x00 && (raw[1] & 0xFF) == 0x64 && (raw[2] & 0xFF) == 0xFF && (raw[3] & 0xFF) == 0x9B) {
                boolean nat64 = true;
                for (int i = 4; i < 12; i++) {
                    if (raw[i] != 0) nat64 = false;
                }
                if (nat64) return isLocalIpv4(raw[12], raw[13], raw[14], raw[15]);
            }
            // 6to4 の 2002:a.b.c.d::/48
            if ((raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x02) {
                return isLocalIpv4(raw[2], raw[3], raw[4], raw[5]);
            }
        }
        return false;
    }

    private static boolean isLocalIpv4(byte a, byte b, byte c, byte d) {
        // ::0.0.0.0 (どこでもないアドレス) や ::1 (ループバック) は Java が先に判定している
        try {
            return isLocal(InetAddress.getByAddress(new byte[]{a, b, c, d}));
        } catch (Exception e) {
            return true;
        }
    }
}
