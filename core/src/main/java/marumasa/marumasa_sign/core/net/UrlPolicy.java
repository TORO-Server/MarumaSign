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
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty() || isLocalName(host)) return false;
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

    private static boolean isLocalName(String host) {
        final String name = host.toLowerCase(Locale.ROOT);
        return name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")
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
            // IPv4 を埋め込んだもの (::ffff:a.b.c.d)
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) mapped = false;
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    return isLocal(InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]}));
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return false;
    }
}
