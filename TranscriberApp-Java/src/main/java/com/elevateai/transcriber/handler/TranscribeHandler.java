package com.elevateai.transcriber.handler;

import com.elevateai.transcriber.service.ElevateAiTranscriber;
import com.elevateai.transcriber.service.TranscriptionResult;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TranscribeHandler implements HttpHandler {

    private static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String fileId = params.get("fileId");
        String token = params.get("token");

        if (fileId == null || token == null) {
            byte[] err = "Missing fileId or token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        String filePath = Path.of(System.getProperty("java.io.tmpdir"), "elevateai_" + fileId).toString();

        // SSE headers
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0); // chunked

        OutputStream os = exchange.getResponseBody();
        // Lock object for thread-safe SSE writes — multiple WebSocket listeners
        // and streaming threads call onMessage concurrently
        final Object writeLock = new Object();

        try {
            TranscriptionResult result = ElevateAiTranscriber.transcribeFile(token, filePath, message -> {
                try {
                    String sseData = "data: " + message.replace("\n", "\ndata: ") + "\n\n";
                    byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);
                    synchronized (writeLock) {
                        os.write(bytes);
                        os.flush();
                    }
                } catch (IOException e) {
                    throw new RuntimeException("SSE write failed", e);
                }
            });

            // Send result as a structured JSON event with segments for turn-by-turn display
            List<SegmentPayload> segments = result.getSegments().stream()
                    .map(s -> new SegmentPayload(s.participant(), s.phrase(),
                            s.startTimeOffset(), s.endTimeOffset()))
                    .toList();
            ResultPayload payload = new ResultPayload(
                    result.getInteractionIdentifier(),
                    segments
            );
            String jsonPayload = GSON.toJson(payload);

            synchronized (writeLock) {
                os.write(("event: transcript\ndata: " + jsonPayload + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("event: done\ndata: complete\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception e) {
            synchronized (writeLock) {
                os.write(("data: ERROR: " + e.getMessage() + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("event: done\ndata: error\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } finally {
            os.close();
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private record SegmentPayload(String participant, String phrase,
                                   int startMs, int endMs) {
    }

    private record ResultPayload(String interactionIdentifier,
                                 List<SegmentPayload> segments) {
    }
}
