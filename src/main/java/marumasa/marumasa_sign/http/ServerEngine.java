package marumasa.marumasa_sign.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import marumasa.marumasa_sign.http.service.APIService;
import marumasa.marumasa_sign.http.service.WebUIService;

import java.io.IOException;
import java.net.InetSocketAddress;

public class ServerEngine {

    public HttpServer server;
    public final int port;
 
    public ServerEngine(int port) {
        this.port = port;
        HttpServer tempServer = null;
        try {
            tempServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            tempServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("MarumaSign-HttpServer");
                return thread;
            }));
            tempServer.createContext("/", new Handler());
            tempServer.start();
            server = tempServer;
        } catch (IOException e) {
            marumasa.marumasa_sign.MarumaSign.LOGGER.error("Failed to start HttpServer on port " + port, e);
            server = null;
        }
    }

    public static class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // リクエストを処理する
            if ("POST".equals(exchange.getRequestMethod())) {
                APIService.Handle(exchange);// post の場合は APIService
            } else {
                WebUIService.Handle(exchange);// それ以外 の場合は WebUIService
            }
        }
    }
}
