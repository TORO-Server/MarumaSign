package marumasa.marumasa_sign.http.service;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import marumasa.marumasa_sign.http.ServerManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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


        // コマンドを実行
        boolean status = runCommand(signText);

        // レスポンスボディを設定
        String resBody = gson.toJson(new ResponseJson(signText, true));
        // レスポンスを送信
        exchange.sendResponseHeaders(200, resBody.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resBody.getBytes(StandardCharsets.UTF_8));
        }

        // もし正常にコマンドが実行されたら
        if (status)
            // サーバーを閉じる
            ServerManager.closeMenu();
    }

    private static final Gson gson = new Gson();

    public static String toCommand(String signText) {
        int maxLength = 15;
        List<String> texts = new ArrayList<>();

        for (int i = 0; i < signText.length(); i += maxLength) {
            texts.add(signText.substring(i, Math.min(i + maxLength, signText.length())));
        }

        texts = texts.stream().map(text -> "'[\"" + text + "\"]'").collect(Collectors.toList());

        List<String> frontTexts = Stream.concat(texts.stream(), Stream.generate(() -> "'[\"\"]'")).limit(4).collect(Collectors.toList());
        if (texts.size() <= 4) {
            return "give @p oak_sign[block_entity_data={id:oak_sign,front_text:{messages:[" + String.join(",", frontTexts) + "]}}]";
        } else {
            List<String> backTexts = Stream.concat(texts.stream(), Stream.generate(() -> "'[\"\"]'")).skip(4).limit(4).collect(Collectors.toList());
            return "give @p oak_sign[block_entity_data={id:oak_sign,front_text:{messages:[" + String.join(",", frontTexts) + "]},back_text:{messages:[" + String.join(",", backTexts) + "]}}]";
        }
    }

    // 看板に書かれる文字から give コマンドを生成して実行する
    // 正常にコマンドが実行されたら true が return される
    private static boolean runCommand(String signText) {
        ClientPlayNetworkHandler net = MinecraftClient.getInstance().getNetworkHandler();
        // null の場合は何もしない
        if (net == null) return false;
        // コマンドを生成
        String command = toCommand(signText);
        // コマンドを実行
        return net.sendCommand(command);
    }

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
