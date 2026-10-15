package marumasa.marumasa_sign.core;

import marumasa.marumasa_sign.core.net.Http;

/**
 * 実際のサーバーとの通信を確かめるための道具。
 * 古い Java (Minecraft 1.12.2 以前に付属するもの) でも HTTPS で接続できるかを調べるのに使う。
 * 引数: 取得する URL (複数可)
 */
public final class HttpCheck {

    public static void main(String[] args) {
        System.out.println("java.version=" + System.getProperty("java.version"));
        for (String url : args) {
            if (url.startsWith("shorten:")) {
                try {
                    System.out.println("OK   shorten -> " + Http.shorten(url.substring("shorten:".length())));
                } catch (Exception e) {
                    System.out.println("FAIL shorten -> " + e);
                }
                continue;
            }
            try {
                final byte[] body = Http.download(url);
                System.out.println("OK   " + url + " (" + body.length + " bytes)");
            } catch (Exception e) {
                System.out.println("FAIL " + url + " -> " + e);
            }
        }
    }
}
