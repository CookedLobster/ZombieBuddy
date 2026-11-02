package me.zed_0xff.zombie_buddy.patches.experimental.http;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import me.zed_0xff.zombie_buddy.patches.experimental.HttpServer;

public class RootHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpServer.logRequest(exchange);
        if (!HttpServer.requireAuth(exchange)) {
            return;
        }
        String response = "ZombieBuddy HTTP Server\n\nEndpoints:\n  /status - server status (no auth)\n  /version - version info (no auth)\n  /lua - POST lua code to execute (requires token; body limit 1 MiB)\n  /log - GET last log lines (requires token; ?lines=N, default 100, max 5000)\n\nSend X-ZombieBuddy-Token: <token> or Authorization: Bearer <token>.\n";
        HttpServer.sendResponse(exchange, 200, response);
    }
}
