package marumasa.marumasa_sign.http.service;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import marumasa.marumasa_sign.client.sign.SignWriteManager;
import marumasa.marumasa_sign.http.ServerManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static marumasa.marumasa_sign.util.Utils.Base64Decoder;
import static marumasa.marumasa_sign.util.Utils.UploadFile;

public class APIService {
 
    public static void Handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/upload" -> Upload(exchange);
                case "/write" -> Write(exchange);
                default -> sendError(exchange, 404, "Not Found");
            }
        } catch (Exception e) {
            marumasa.marumasa_sign.MarumaSign.LOGGER.error("Error processing API request: " + path, e);
            sendError(exchange, 500, "Internal Server Error");
        }
    }
 
    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] response = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static byte[] readRequestBodyLimited(HttpExchange exchange, int limitBytes) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > limitBytes) {
                    throw new IOException("Request body exceeds limit of " + limitBytes + " bytes.");
                }
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private static void Upload(HttpExchange exchange) throws IOException {
        byte[] bodyBytes;
        try {
            bodyBytes = readRequestBodyLimited(exchange, 10 * 1024 * 1024); // 10MB limit
        } catch (IOException e) {
            sendError(exchange, 413, "Payload Too Large");
            return;
        }

        String reqBody = new String(bodyBytes, StandardCharsets.UTF_8);
        UploadJson json;
        try {
            json = gson.fromJson(reqBody, UploadJson.class);
            if (json == null || json.file == null || json.name == null) {
                throw new IllegalArgumentException("Invalid payload structure");
            }
        } catch (Exception e) {
            sendError(exchange, 400, "Bad Request");
            return;
        }

        String[] parts = json.file.split(";base64,", 2);
        if (parts.length < 2) {
            sendError(exchange, 400, "Invalid Base64 Data URI format");
            return;
        }

        byte[] fileBytes;
        try {
            fileBytes = Base64Decoder(parts[1]);
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid Base64 Encoding");
            return;
        }

        // レスポンスヘッダを設定
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/json");

        String imgURL = UploadFile(
                "https://catbox.moe/user/api.php",
                fileBytes,
                json.name
        );

        // レスポンスボディを設定
        String resBody = gson.toJson(new ResponseJson(imgURL, true));
        // レスポンスを送信
        byte[] resBytes = resBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, resBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBytes);
        }
    }

    public static void Write(HttpExchange exchange) throws IOException {
        byte[] bodyBytes;
        try {
            bodyBytes = readRequestBodyLimited(exchange, 65536); // 64KB limit
        } catch (IOException e) {
            sendError(exchange, 413, "Payload Too Large");
            return;
        }

        String reqBody = new String(bodyBytes, StandardCharsets.UTF_8);
        WriteJson json;
        try {
            json = gson.fromJson(reqBody, WriteJson.class);
            if (json == null || json.address == null) {
                throw new IllegalArgumentException("Invalid payload structure");
            }
        } catch (Exception e) {
            sendError(exchange, 400, "Bad Request");
            return;
        }

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
        byte[] resBytes = resBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, resBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBytes);
        }

        // サーバーを閉じる
        Thread closeThread = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ServerManager.closeMenu();
        });
        closeThread.setDaemon(true);
        closeThread.start();
    }

    private static final Gson gson = new Gson();

    private record UploadJson(
            String file,
            String name
    ) {
    }

    private record WriteJson(
            String address,
            float width, float height,
            float x, float y, float z,
            float rx, float ry, float rz
    ) {
        public String signText() {
            String sanitizedAddress = address == null ? "" : address.replace("|", "");
            return String.format(
                    "%s|%s|%s|%s|%s|%s|%s|%s|%s",
                    sanitizedAddress,
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
