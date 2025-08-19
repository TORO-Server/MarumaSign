package marumasa.marumasa_sign.http.service;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import marumasa.marumasa_sign.client.sign.SignWriteManager;
import marumasa.marumasa_sign.http.ServerManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static marumasa.marumasa_sign.util.Utils.Base64Decoder;
import static marumasa.marumasa_sign.util.Utils.UploadFile;

public class APIService {

    public static void Handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        switch (path) {
            case "/upload" -> Upload(exchange);
            case "/give" -> Give(exchange);
        }
    }

    private static void Upload(HttpExchange exchange) throws IOException {

        // リクエストボディ取得
        String reqBody = new String(exchange.getRequestBody().readAllBytes());
        // リクエストボディに書かれている json を解析
        UploadJson json = gson.fromJson(reqBody, UploadJson.class);
        // base64 から バイナリに変換

        // レスポンスヘッダを設定
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/json");

        String imgURL = UploadFile(
                "https://catbox.moe/user/api.php",
                Base64Decoder(json.file.split(";base64,", 2)[1]),
                json.name
        );

        // レスポンスボディを設定
        String resBody = gson.toJson(new ResponseJson(imgURL, true));
        // レスポンスを送信
        exchange.sendResponseHeaders(200, resBody.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBody.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void Give(HttpExchange exchange) throws IOException {

        // リクエストボディ取得
        String reqBody = new String(exchange.getRequestBody().readAllBytes());
        // リクエストボディに書かれている json を解析
        GiveJson json = gson.fromJson(reqBody, GiveJson.class);
        // 看板に書かれる文字を生成
        String signText = json.signText();

        // レスポンスヘッダを設定
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/json");

        // 看板自動入力をリクエストする
        SignWriteManager.request(json.signText());

        // レスポンスボディを設定
        String resBody = gson.toJson(new ResponseJson(signText, true));
        // レスポンスを送信
        exchange.sendResponseHeaders(200, resBody.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBody.getBytes(StandardCharsets.UTF_8));
        }

        // サーバーを閉じる
        ServerManager.closeMenu();
    }

    private static final Gson gson = new Gson();

    private record UploadJson(
            String file,
            String name
    ) {
    }

    private record GiveJson(
            String address,
            float width, float height,
            float x, float y, float z,
            float rx, float ry, float rz
    ) {
        public String signText() {
            return String.format(
                    "%s|%s|%s|%s|%s|%s|%s|%s|%s",
                    address,
                    floatFormat(x), floatFormat(y), floatFormat(z),
                    floatFormat(height), floatFormat(width),
                    floatFormat(rx), floatFormat(ry), floatFormat(rz)
            );
        }

        private String floatFormat(float value) {
            return Float.toString(value);
        }
    }

    private record ResponseJson(String result, boolean status) {
    }
}
