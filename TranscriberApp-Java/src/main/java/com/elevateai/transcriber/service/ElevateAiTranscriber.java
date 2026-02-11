package com.elevateai.transcriber.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class ElevateAiTranscriber {

    private static final int MAX_RETRIES = 3;
    private static final int SESSION_END_TIMEOUT_SECONDS = 10;
    private static final int CHUNK_SIZE = 8192;

    public static TranscriptionResult transcribeFile(String apiToken, String filePath,
                                                     Consumer<String> onMessage) throws Exception {
        return transcribeFile(apiToken, filePath, onMessage, "en", 16000);
    }

    public static TranscriptionResult transcribeFile(String apiToken, String filePath,
                                                     Consumer<String> onMessage,
                                                     String languageTag, int sampleRate) throws Exception {
        int channelCount = detectChannelCount(filePath);
        onMessage.accept("Detected " + channelCount + " audio channel(s).");

        if (channelCount >= 2) {
            return transcribeStereo(apiToken, filePath, onMessage, languageTag, sampleRate);
        } else {
            return transcribeMono(apiToken, filePath, onMessage, languageTag, sampleRate);
        }
    }

    private static TranscriptionResult transcribeMono(String apiToken, String filePath,
                                                      Consumer<String> onMessage,
                                                      String languageTag, int sampleRate) throws Exception {
        onMessage.accept("Converting audio to PCM (" + sampleRate + " Hz, mono, 16-bit)...");
        byte[] pcmData = convertToPcm(filePath, sampleRate, -1);
        onMessage.accept("Conversion complete. PCM size: " + String.format("%,d", pcmData.length) + " bytes");

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String sessionId = UUID.randomUUID().toString();
            onMessage.accept("Attempt " + attempt + "/" + MAX_RETRIES + " — Session ID: " + sessionId);

            try {
                return doTranscribeMono(apiToken, pcmData, sessionId, onMessage, languageTag, sampleRate);
            } catch (Exception e) {
                lastError = e;
                onMessage.accept("Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    onMessage.accept("Waiting 2s before retry...");
                    Thread.sleep(2000);
                    onMessage.accept("Retrying with new session...");
                }
            }
        }
        throw new Exception("Failed after " + MAX_RETRIES + " attempts: " + lastError.getMessage(), lastError);
    }

    private static TranscriptionResult doTranscribeMono(String apiToken, byte[] pcmData, String sessionId,
                                                        Consumer<String> onMessage,
                                                        String languageTag, int sampleRate) throws Exception {
        URI uri = buildUri(languageTag, sessionId, 1, 0, "Agent", sampleRate);
        onMessage.accept("Connecting to ElevateAI WebSocket...");

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<String> interactionIdFuture = new CompletableFuture<>();
        CompletableFuture<String> sessionEndedJsonFuture = new CompletableFuture<>();

        WebSocket ws = connectWebSocket(client, uri, apiToken,
                new SessionListener(onMessage, interactionIdFuture, sessionEndedJsonFuture), onMessage);

        onMessage.accept("WebSocket connected.");
        streamAudio(ws, pcmData, onMessage);

        onMessage.accept("All audio sent. Sending sessionEnd...");
        ws.sendText("{\"type\":\"sessionEnd\"}", true).join();

        onMessage.accept("Waiting for sessionEnded (timeout: " + SESSION_END_TIMEOUT_SECONDS + "s)...");
        String endedJson = sessionEndedJsonFuture.get(SESSION_END_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return TranscriptionResult.fromSessionEnded(endedJson);
    }

    private static TranscriptionResult transcribeStereo(String apiToken, String filePath,
                                                        Consumer<String> onMessage,
                                                        String languageTag, int sampleRate) throws Exception {
        onMessage.accept("Converting channel 0 (Agent) to PCM...");
        byte[] pcmChannel0 = convertToPcm(filePath, sampleRate, 0);
        onMessage.accept("Channel 0 PCM size: " + String.format("%,d", pcmChannel0.length) + " bytes");

        onMessage.accept("Converting channel 1 (Customer) to PCM...");
        byte[] pcmChannel1 = convertToPcm(filePath, sampleRate, 1);
        onMessage.accept("Channel 1 PCM size: " + String.format("%,d", pcmChannel1.length) + " bytes");

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String sessionId = UUID.randomUUID().toString();
            onMessage.accept("Attempt " + attempt + "/" + MAX_RETRIES + " — Session ID: " + sessionId);

            try {
                return doTranscribeStereo(apiToken, pcmChannel0, pcmChannel1, sessionId,
                        onMessage, languageTag, sampleRate);
            } catch (Exception e) {
                lastError = e;
                onMessage.accept("Attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < MAX_RETRIES) {
                    onMessage.accept("Waiting 2s before retry...");
                    Thread.sleep(2000);
                    onMessage.accept("Retrying with new session...");
                }
            }
        }
        throw new Exception("Failed after " + MAX_RETRIES + " attempts: " + lastError.getMessage(), lastError);
    }

    private static TranscriptionResult doTranscribeStereo(String apiToken,
                                                          byte[] pcmChannel0, byte[] pcmChannel1,
                                                          String sessionId, Consumer<String> onMessage,
                                                          String languageTag, int sampleRate) throws Exception {
        URI uri0 = buildUri(languageTag, sessionId, 2, 0, "Agent", sampleRate);
        URI uri1 = buildUri(languageTag, sessionId, 2, 1, "Customer", sampleRate);

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<String> interactionIdFuture = new CompletableFuture<>();
        CompletableFuture<String> sessionEndedJsonFuture = new CompletableFuture<>();

        // Both listeners share the same futures — sessionStarted/sessionEnded arrive on both,
        // but we only need to capture once
        onMessage.accept("Connecting channel 0 (Agent)...");
        WebSocket ws0 = connectWebSocket(client, uri0, apiToken,
                new SessionListener(onMessage, interactionIdFuture, sessionEndedJsonFuture), onMessage);
        onMessage.accept("Channel 0 connected.");

        onMessage.accept("Connecting channel 1 (Customer)...");
        WebSocket ws1 = connectWebSocket(client, uri1, apiToken,
                new SessionListener(onMessage, interactionIdFuture, sessionEndedJsonFuture), onMessage);
        onMessage.accept("Channel 1 connected.");

        // Stream both channels in parallel
        CompletableFuture<Void> send0 = CompletableFuture.runAsync(() -> {
            try {
                onMessage.accept("Streaming channel 0 audio...");
                streamAudio(ws0, pcmChannel0, msg -> {});
                onMessage.accept("Channel 0 audio sent.");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<Void> send1 = CompletableFuture.runAsync(() -> {
            try {
                onMessage.accept("Streaming channel 1 audio...");
                streamAudio(ws1, pcmChannel1, msg -> {});
                onMessage.accept("Channel 1 audio sent.");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for both channels to finish streaming
        CompletableFuture.allOf(send0, send1).join();

        // sessionEnd only needs to be sent on one channel
        onMessage.accept("All audio sent. Sending sessionEnd...");
        ws0.sendText("{\"type\":\"sessionEnd\"}", true).join();

        onMessage.accept("Waiting for sessionEnded (timeout: " + SESSION_END_TIMEOUT_SECONDS + "s)...");
        String endedJson = sessionEndedJsonFuture.get(SESSION_END_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        return TranscriptionResult.fromSessionEnded(endedJson);
    }

    private static WebSocket connectWebSocket(HttpClient client, URI uri, String apiToken,
                                                 WebSocket.Listener listener,
                                                 Consumer<String> onMessage) throws Exception {
        try {
            return client.newWebSocketBuilder()
                    .header("X-API-TOKEN", apiToken)
                    .buildAsync(uri, listener)
                    .join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof WebSocketHandshakeException wse) {
                int status = wse.getResponse().statusCode();
                // Make a diagnostic HTTPS GET to see the actual response body
                String diagBody = "";
                try {
                    URI httpsUri = URI.create(uri.toString().replaceFirst("^wss://", "https://"));
                    var diagResp = client.send(
                            java.net.http.HttpRequest.newBuilder(httpsUri)
                                    .header("X-API-TOKEN", apiToken)
                                    .GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    diagBody = diagResp.body();
                    if (diagBody.length() > 500) diagBody = diagBody.substring(0, 500);
                    onMessage.accept("Diagnostic response (HTTP " + diagResp.statusCode() + "): " + diagBody);
                } catch (Exception diagErr) {
                    onMessage.accept("Diagnostic probe failed: " + diagErr.getMessage());
                }
                throw new Exception("WebSocket handshake failed (HTTP " + status + "): " + diagBody, cause);
            }
            throw new Exception("WebSocket connection failed: " + cause.getMessage(), cause);
        }
    }

    private static URI buildUri(String languageTag, String sessionId,
                                int channels, int channelIndex, String participantRole,
                                int sampleRate) {
        return URI.create(
                "wss://api.elevateai.com/v1/audio/" + languageTag + "/default"
                        + "?session_identifier=" + sessionId
                        + "&channels=" + channels
                        + "&channel_index=" + channelIndex
                        + "&participant_role=" + participantRole
                        + "&codec=pcm&sample_rate=" + sampleRate);
    }

    private static void streamAudio(WebSocket ws, byte[] pcmData, Consumer<String> onMessage) {
        int totalChunks = (pcmData.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        onMessage.accept("Streaming " + totalChunks + " audio chunks...");

        for (int offset = 0; offset < pcmData.length; offset += CHUNK_SIZE) {
            int remaining = Math.min(CHUNK_SIZE, pcmData.length - offset);
            ByteBuffer buffer = ByteBuffer.wrap(pcmData, offset, remaining);
            ws.sendBinary(buffer, true).join();
        }
    }

    // --- ffmpeg helpers ---

    private static int detectChannelCount(String inputPath) throws Exception {
        // Use ffmpeg -i to detect channels (ffprobe may not be available)
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-i", inputPath, "-hide_banner");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (InputStream is = proc.getInputStream()) {
            output = new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
        proc.waitFor(); // exit code will be non-zero (no output specified) — that's expected

        // Look for "stereo" or "N channels" in the stream info line
        // e.g. "Stream #0:0: Audio: aac, 44100 Hz, stereo, fltp"
        // e.g. "Stream #0:0: Audio: pcm_s16le, 16000 Hz, 2 channels, s16"
        if (output.contains("stereo") || output.contains("2 channels")) {
            return 2;
        }
        return 1;
    }

    /**
     * Convert audio to raw PCM. If channelIndex is -1, downmix all channels to mono.
     * If channelIndex is 0 or 1, extract that specific channel from a stereo file.
     */
    private static byte[] convertToPcm(String inputPath, int sampleRate, int channelIndex) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("ffmpeg");
        cmd.add("-i");
        cmd.add(inputPath);

        if (channelIndex >= 0) {
            // Extract specific channel: pan filter maps the desired channel to mono output
            cmd.add("-af");
            cmd.add("pan=1c|c0=c" + channelIndex);
        }

        cmd.add("-f");
        cmd.add("s16le");
        cmd.add("-acodec");
        cmd.add("pcm_s16le");
        cmd.add("-ar");
        cmd.add(String.valueOf(sampleRate));
        cmd.add("-ac");
        cmd.add("1");
        cmd.add("pipe:1");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        byte[] pcmData;
        try (InputStream stdout = proc.getInputStream()) {
            pcmData = readAllBytes(stdout);
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            byte[] errBytes;
            try (InputStream stderr = proc.getErrorStream()) {
                errBytes = readAllBytes(stderr);
            }
            throw new IOException("ffmpeg failed (exit " + exitCode + "): "
                    + new String(errBytes, StandardCharsets.UTF_8));
        }
        return pcmData;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // --- WebSocket listener ---

    private static class SessionListener implements WebSocket.Listener {
        private final Consumer<String> onMessage;
        private final CompletableFuture<String> interactionIdFuture;
        private final CompletableFuture<String> sessionEndedJsonFuture;
        private final StringBuilder messageBuffer = new StringBuilder();

        SessionListener(Consumer<String> onMessage,
                        CompletableFuture<String> interactionIdFuture,
                        CompletableFuture<String> sessionEndedJsonFuture) {
            this.onMessage = onMessage;
            this.interactionIdFuture = interactionIdFuture;
            this.sessionEndedJsonFuture = sessionEndedJsonFuture;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            webSocket.request(1);

            if (!last) {
                return CompletableFuture.completedFuture(null);
            }

            String json = messageBuffer.toString();
            messageBuffer.setLength(0);

            onMessage.accept("[WS] " + json);

            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (!root.has("type")) {
                    return CompletableFuture.completedFuture(null);
                }

                String msgType = root.get("type").getAsString();

                if ("sessionStarted".equals(msgType)) {
                    JsonObject content = root.getAsJsonObject("content");
                    if (content != null && content.has("interactionIdentifier")) {
                        String iid = content.get("interactionIdentifier").getAsString();
                        interactionIdFuture.complete(iid);
                        onMessage.accept("Interaction ID: " + iid);
                    }
                } else if ("sessionEnded".equals(msgType)) {
                    // Complete with the full JSON so the caller can parse the transcript
                    sessionEndedJsonFuture.complete(json);
                }
            } catch (Exception e) {
                sessionEndedJsonFuture.completeExceptionally(e);
            }

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!sessionEndedJsonFuture.isDone()) {
                sessionEndedJsonFuture.completeExceptionally(
                        new Exception("WebSocket closed before sessionEnded (code=" + statusCode
                                + ", reason=" + reason + ")"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!sessionEndedJsonFuture.isDone()) {
                sessionEndedJsonFuture.completeExceptionally(error);
            }
        }
    }
}
