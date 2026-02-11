package com.elevateai.transcriber.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class StaticHandler implements HttpHandler {
    private static final Map<String, String> MIME_TYPES = Map.of(
            ".css", "text/css",
            ".js", "application/javascript",
            ".html", "text/html",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon"
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Strip leading /static/ to get the resource path
        String resourcePath = "static" + path.substring("/static".length());

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] data = is.readAllBytes();

            String contentType = "application/octet-stream";
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                String ext = path.substring(dot);
                contentType = MIME_TYPES.getOrDefault(ext, contentType);
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }
}
