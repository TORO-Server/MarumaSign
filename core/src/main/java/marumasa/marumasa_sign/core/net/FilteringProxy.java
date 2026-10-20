package marumasa.marumasa_sign.core.net;

import marumasa.marumasa_sign.core.MarumaSign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FFmpeg の通信を通す、このコンピューターの中だけで待ち受けるプロキシ。
 * FFmpeg はリダイレクトや HLS の分割されたファイルを自分で読みに行くので、その行き先を MarumaSign からは確かめられない。
 * そこで FFmpeg の通信をすべてここに通し、つなぐ前に行き先が手元や家の中のネットワークでないかを確かめる (UrlPolicy)。
 * 確かめたアドレスにそのままつなぐので、名前を引き直して行き先をすり替えることもできない。
 */
public final class FilteringProxy {

    // 要求の先頭 (要求行とヘッダー) の大きさの上限
    private static final int MAX_HEAD = 32 * 1024;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int HEAD_TIMEOUT = 15000;

    private static ServerSocket server;

    private FilteringProxy() {
    }

    /**
     * FFmpeg のプロセスにこのプロキシを使わせる。
     * 環境変数 http_proxy は、FFmpeg の http と tls (https) が、どこから開かれても (再生リストの中の URL でも) 使う。
     * 読むものが URL なら、念のため -http_proxy でも指定する (ファイルを読むときに指定すると、使われない指定として失敗する)。
     *
     * @param input FFmpeg が読むファイルのパスまたは URL
     */
    public static void apply(ProcessBuilder builder, String input) throws IOException {
        final String address = address();
        if (input.startsWith("http://") || input.startsWith("https://")) {
            final List<String> command = builder.command();
            command.add(1, "-http_proxy");
            command.add(2, address);
        }
        final Map<String, String> environment = builder.environment();
        // 「プロキシを使わない行き先」の指定があると、そこへは直接つないでしまう
        environment.remove("no_proxy");
        environment.remove("NO_PROXY");
        environment.put("http_proxy", address);
    }

    static synchronized String address() throws IOException {
        if (server == null || server.isClosed()) {
            final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    accept(socket);
                }
            }, "MarumaSign-Proxy");
            thread.setDaemon(true);
            thread.start();
            server = socket;
        }
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private static void accept(ServerSocket socket) {
        while (!socket.isClosed()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (!socket.isClosed()) MarumaSign.LOGGER.info("Proxy stopped (" + e + ")");
                return;
            }
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(client);
                }
            }, "MarumaSign-ProxyConnection");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(HEAD_TIMEOUT);
            final InputStream input = client.getInputStream();
            final OutputStream output = client.getOutputStream();
            final byte[] head = readHead(input);
            if (head == null) {
                reply(output, "400 Bad Request");
                return;
            }
            final int end = indexOf(head, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) + 4;
            final String text = new String(head, 0, end, StandardCharsets.ISO_8859_1);
            final int lineEnd = text.indexOf("\r\n");
            final String[] request = text.substring(0, lineEnd).split(" ");
            if (request.length != 3) {
                reply(output, "400 Bad Request");
                return;
            }
            final String method = request[0];
            final String target = request[1];
            final String host;
            final int port;
            final boolean tunnel = method.equalsIgnoreCase("CONNECT");
            String path = null;
            if (tunnel) {
                final int colon = target.lastIndexOf(':');
                if (colon <= 0) {
                    reply(output, "400 Bad Request");
                    return;
                }
                host = stripBrackets(target.substring(0, colon));
                port = parsePort(target.substring(colon + 1));
            } else {
                if (!target.toLowerCase(Locale.ROOT).startsWith("http://")) {
                    reply(output, "400 Bad Request");
                    return;
                }
                final URL url = new URL(target);
                host = stripBrackets(url.getHost());
                port = url.getPort() < 0 ? 80 : url.getPort();
                path = url.getFile().isEmpty() ? "/" : url.getFile();
            }
            if (port <= 0 || port > 65535 || host.isEmpty()) {
                reply(output, "400 Bad Request");
                return;
            }
            final InetAddress[] addresses = UrlPolicy.resolve(host);
            if (addresses == null) {
                MarumaSign.LOGGER.info("Blocked a media connection to " + host);
                reply(output, "403 Forbidden");
                return;
            }
            upstream = connect(addresses, port);
            if (upstream == null) {
                reply(output, "502 Bad Gateway");
                return;
            }
            final OutputStream forward = upstream.getOutputStream();
            if (tunnel) {
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } else {
                // 行き先のサーバーには、プロキシ向けの書き方ではない、ふつうの要求として送る
                final StringBuilder rewritten = new StringBuilder();
                rewritten.append(method).append(' ').append(path).append(' ').append(request[2]).append("\r\n");
                for (String header : text.substring(lineEnd + 2, end - 2).split("\r\n")) {
                    if (header.isEmpty() || header.toLowerCase(Locale.ROOT).startsWith("proxy-")) continue;
                    rewritten.append(header).append("\r\n");
                }
                rewritten.append("\r\n");
                forward.write(rewritten.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            // 要求の先頭と一緒に届いていた続き
            if (head.length > end) forward.write(head, end, head.length - end);
            forward.flush();
            client.setSoTimeout(0);
            pipe(client, upstream);
        } catch (IOException e) {
            // FFmpeg か行き先のサーバーが接続を切った
        } finally {
            close(client);
            close(upstream);
        }
    }

    // 要求の先頭を、空行まで読む。読んだ分を返す (空行の後ろまで読んでいることがある)
    private static byte[] readHead(InputStream input) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        final byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        while (bytes.size() < MAX_HEAD) {
            final int read = input.read(buffer);
            if (read < 0) return null;
            bytes.write(buffer, 0, read);
            final byte[] all = bytes.toByteArray();
            if (indexOf(all, terminator) >= 0) return all;
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length() - 1);
        return host;
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Socket connect(InetAddress[] addresses, int port) {
        for (InetAddress address : addresses) {
            final Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT);
                return socket;
            } catch (IOException e) {
                close(socket);
            }
        }
        return null;
    }

    private static void reply(OutputStream output, String status) throws IOException {
        output.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    // 両方向に流す。どちらかが終わったら、もう一方にも終わりを伝える
    private static void pipe(final Socket client, final Socket upstream) throws IOException {
        final Thread back = new Thread(new Runnable() {
            @Override
            public void run() {
                copy(upstream, client);
            }
        }, "MarumaSign-ProxyConnection");
        back.setDaemon(true);
        back.start();
        copy(client, upstream);
        try {
            back.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            final InputStream input = from.getInputStream();
            final OutputStream output = to.getOutputStream();
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
            to.shutdownOutput();
        } catch (IOException e) {
            // 片方が切れたら、両方を閉じて終わらせる
            close(from);
            close(to);
        }
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            // 既に閉じている
        }
    }
}
