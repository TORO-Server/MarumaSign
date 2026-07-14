package marumasa.marumasa_sign.http.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import marumasa.marumasa_sign.MarumaSign;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class WebUIService {

    // アクセスできるファイルのパス一覧
    private static final String[] allowPaths = {
            "/index.html",
            "/style.css",
            "/script.js",
            "/utils.js",
            "/preview.js",
            "/favicon.ico",
            "/bootstrap.min.css",
            "/sanitize.css",
            "/sans.css"
    };

    public static void Handle(HttpExchange exchange) throws IOException {

        String path = exchange.getRequestURI().getPath();

        MarumaSign.LOGGER.info("Requested: {}", path);

        // 許可リストチェック
        if (!Arrays.asList(allowPaths).contains(path)) {
            sendError(exchange, 404, "Not Found");
            return;
        }

        // リクエストされたパスをリソースディレクトリ内のファイルの場所に変換
        String resource_path = toResourcePath(path);

        // レスポンスボディをバイナリとして取得
        byte[] responseBody = getResourceBytes(resource_path);
        if (responseBody == null) {
            sendError(exchange, 404, "Resource Not Found");
            return;
        }

        // レスポンスヘッダ の設定
        Headers responseHeaders = exchange.getResponseHeaders();
        // Content-Type を拡張子から判定
        String contentType = getContentType(path);
        responseHeaders.set("Content-Type", contentType);

        // レスポンスを送信
        exchange.sendResponseHeaders(200, responseBody.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBody);
        }
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] response = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // リソースにあるファイルをバイナリとして読み込む
    private static byte[] getResourceBytes(String path) {
        try (InputStream is = WebUIService.class.getResourceAsStream(path)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            MarumaSign.LOGGER.error("Failed to load resource: " + path, e);
            return null;
        }
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String toResourcePath(String path) {
        return "/webui" + path;
    }
}