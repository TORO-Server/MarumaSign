package marumasa.marumasa_sign.http.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import marumasa.marumasa_sign.MarumaSign;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;

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

        String path = exchange.getRequestURI().toString();

        MarumaSign.LOGGER.info("Requested: {}", path);

        // リクエストされたパスをリソースディレクトリ内のファイルの場所に変換
        String resource_path = toResourcePath(path);

        // レスポンスボディを構築
        String responseBody = Arrays.asList(allowPaths).contains(path) ? getResource(resource_path) : "";

        // レスポンスヘッダ の設定
        Headers responseHeaders = exchange.getResponseHeaders();
        // Content-Type を設定
        String contentType = Files.probeContentType(Paths.get(resource_path));
        responseHeaders.set("Content-Type", contentType);

        // レスポンスを送信
        exchange.sendResponseHeaders(200, responseBody.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBody.getBytes(StandardCharsets.UTF_8));
        }
    }

    // リソースにあるファイルを読み込む
    private static String getResource(String path) {

        // ファイルの内容が書き込まれる変数
        StringBuilder fileText = new StringBuilder();

        try (InputStream is = WebUIService.class.getResourceAsStream(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                fileText.append(line); // ファイルの内容を1行ずつ読み込み
                fileText.append("\n"); // 改行する
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // ファイルの内容を return する
        return fileText.toString();
    }

    private static String toResourcePath(String path) {
        return "/webui" + path;
    }
}