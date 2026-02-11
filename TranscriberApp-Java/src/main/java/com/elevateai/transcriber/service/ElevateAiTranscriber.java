package com.elevateai.transcriber.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Self-contained ElevateAI Real-Time transcription service.
 *
 * <p><b>To copy into another project, you need exactly 2 files:</b></p>
 * <ol>
 *   <li>{@code ElevateAiTranscriber.java} (this file)</li>
 *   <li>{@code TranscriptionResult.java} (result model)</li>
 * </ol>
 *
 * <p><b>Dependencies:</b> Gson ({@code com.google.code.gson:gson:2.11.0})</p>
 * <p><b>Input:</b> PCM WAV files (mono or stereo)</p>
 *
 * <p><b>Quick usage — file in, interaction ID out:</b></p>
 * <pre>{@code
 * String interactionId = ElevateAiTranscriber.processFile("your-api-token", "/path/to/audio.wav");
 * }</pre>
 *
 * <p><b>With progress logging:</b></p>
 * <pre>{@code
 * TranscriptionResult result = ElevateAiTranscriber.transcribeFile(
 *     "your-api-token", "/path/to/audio.wav", msg -> System.out.println(msg));
 * String interactionId = result.getInteractionIdentifier();
 * }</pre>
 */
public class ElevateAiTranscriber {

    private static final int MAX_RETRIES = 3;
    private static final int SESSION_END_TIMEOUT_SECONDS = 10;
    private static final int CHUNK_SIZE = 8192;

    // =========================================================================
    // PUBLIC API — copy-paste entry points
    // =========================================================================

    /**
     * Simplest entry point: file in → interaction ID out.
     * Accepts mono or stereo PCM WAV files.
     *
     * @param apiToken ElevateAI API token
     * @param filePath path to a PCM WAV audio file
     * @return the confirmed interaction identifier from sessionEnded
     * @throws Exception if transcription fails after all retries
     */
    public static String processFile(String apiToken, String filePath) throws Exception {
        return transcribeFile(apiToken, filePath, msg -> {}).getInteractionIdentifier();
    }

    /**
     * Full entry point with progress callbacks and complete result.
     */
    public static TranscriptionResult transcribeFile(String apiToken, String filePath,
                                                     Consumer<String> onMessage) throws Exception {
        return transcribeFile(apiToken, filePath, onMessage, "en");
    }

    /**
     * Full entry point with language option.
     */
    public static TranscriptionResult transcribeFile(String apiToken, String filePath,
                                                     Consumer<String> onMessage,
                                                     String languageTag) throws Exception {
        WavInfo wav = readWav(filePath);
        onMessage.accept("WAV: " + wav.channels + " channel(s), " + wav.sampleRate + " Hz, 16-bit PCM. "
                + String.format("%,d", wav.pcmData.length) + " bytes.");

        if (wav.channels >= 2) {
            onMessage.accept("Splitting stereo channels...");
            byte[][] channels = splitChannels(wav.pcmData);
            onMessage.accept("Channel 0 (Agent): " + String.format("%,d", channels[0].length) + " bytes");
            onMessage.accept("Channel 1 (Customer): " + String.format("%,d", channels[1].length) + " bytes");
            return transcribeStereo(apiToken, channels[0], channels[1], onMessage, languageTag, wav.sampleRate);
        } else {
            return transcribeMono(apiToken, wav.pcmData, onMessage, languageTag, wav.sampleRate);
        }
    }

    // =========================================================================
    // WAV reading — uses javax.sound.sampled (JDK built-in, no ffmpeg)
    // =========================================================================

    private record WavInfo(int channels, int sampleRate, byte[] pcmData) {}

    /**
     * Read a WAV file and produce raw 16-bit signed little-endian PCM bytes.
     * Preserves the original channel count and sample rate.
     */
    private static WavInfo readWav(String inputPath) throws Exception {
        File file = new File(inputPath);
        try (AudioInputStream sourceAis = AudioSystem.getAudioInputStream(file)) {
            AudioFormat src = sourceAis.getFormat();

            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    src.getChannels(),
                    src.getChannels() * 2, // frame size: channels × 2 bytes per sample
                    src.getSampleRate(),
                    false // little-endian
            );

            if (src.matches(target)) {
                return new WavInfo(src.getChannels(), (int) src.getSampleRate(), sourceAis.readAllBytes());
            }

            try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, sourceAis)) {
                return new WavInfo(target.getChannels(), (int) target.getSampleRate(), converted.readAllBytes());
            }
        }
    }

    /**
     * Deinterleave stereo 16-bit PCM into two mono streams.
     * Input layout: [L0_lo, L0_hi, R0_lo, R0_hi, L1_lo, L1_hi, R1_lo, R1_hi, ...]
     */
    private static byte[][] splitChannels(byte[] stereoPcm) {
        byte[] left = new byte[stereoPcm.length / 2];
        byte[] right = new byte[stereoPcm.length / 2];
        for (int i = 0, j = 0; i < stereoPcm.length; i += 4, j += 2) {
            left[j]      = stereoPcm[i];
            left[j + 1]  = stereoPcm[i + 1];
            right[j]     = stereoPcm[i + 2];
            right[j + 1] = stereoPcm[i + 3];
        }
        return new byte[][] { left, right };
    }

    // =========================================================================
    // Transcription paths
    // =========================================================================

    private static TranscriptionResult transcribeMono(String apiToken, byte[] pcmData,
                                                      Consumer<String> onMessage,
                                                      String languageTag, int sampleRate) throws Exception {
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

    private static TranscriptionResult transcribeStereo(String apiToken,
                                                        byte[] pcmChannel0, byte[] pcmChannel1,
                                                        Consumer<String> onMessage,
                                                        String languageTag, int sampleRate) throws Exception {
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

    // =========================================================================
    // WebSocket helpers
    // =========================================================================

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
                            HttpRequest.newBuilder(httpsUri)
                                    .header("X-API-TOKEN", apiToken)
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
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

    // =========================================================================
    // WebSocket listener
    // =========================================================================

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
