using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace TranscriberApp.Services;

public static class ElevateAiTranscriber
{
    public static async Task<string> TranscribeFileAsync(
        string apiToken,
        string filePath,
        Action<string> onMessage,
        string languageTag = "en",
        int sampleRate = 16000,
        CancellationToken ct = default)
    {
        onMessage($"Converting audio to PCM WAV ({sampleRate} Hz, mono, 16-bit)...");
        byte[] pcmData = await ConvertToPcmAsync(filePath, sampleRate, ct);
        onMessage($"Conversion complete. PCM data size: {pcmData.Length:N0} bytes");

        string sessionId = Guid.NewGuid().ToString();
        var uri = new Uri(
            $"wss://api.elevateai.com/v1/audio/{languageTag}/default" +
            $"?session_identifier={sessionId}" +
            $"&channels=1&channel_index=0" +
            $"&participant_role=Agent" +
            $"&codec=pcm&sample_rate={sampleRate}");

        onMessage($"Connecting to ElevateAI WebSocket...");
        onMessage($"Session ID: {sessionId}");

        using var ws = new ClientWebSocket();
        ws.Options.SetRequestHeader("X-API-TOKEN", apiToken);
        await ws.ConnectAsync(uri, ct);
        onMessage("WebSocket connected.");

        // Start receiving messages in the background
        var receiveTask = ReceiveUntilSessionEndedAsync(ws, onMessage, ct);

        // Stream all audio as fast as possible in 8 KB chunks
        const int chunkSize = 8192;
        int totalChunks = (pcmData.Length + chunkSize - 1) / chunkSize;
        onMessage($"Streaming {totalChunks} audio chunks...");

        for (int offset = 0; offset < pcmData.Length; offset += chunkSize)
        {
            int remaining = Math.Min(chunkSize, pcmData.Length - offset);
            await ws.SendAsync(
                new ArraySegment<byte>(pcmData, offset, remaining),
                WebSocketMessageType.Binary,
                endOfMessage: true,
                cancellationToken: ct);
        }

        onMessage("All audio sent. Sending sessionEnd...");

        byte[] sessionEnd = Encoding.UTF8.GetBytes("{\"type\":\"sessionEnd\"}");
        await ws.SendAsync(
            new ArraySegment<byte>(sessionEnd),
            WebSocketMessageType.Text,
            endOfMessage: true,
            cancellationToken: ct);

        onMessage("Waiting for final transcript...");
        return await receiveTask;
    }

    private static async Task<string> ReceiveUntilSessionEndedAsync(
        ClientWebSocket ws,
        Action<string> onMessage,
        CancellationToken ct)
    {
        var buffer = new byte[65536];

        while (ws.State == WebSocketState.Open)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult result;

            do
            {
                result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                if (result.MessageType == WebSocketMessageType.Close)
                    throw new Exception("WebSocket closed before sessionEnded was received.");
                ms.Write(buffer, 0, result.Count);
            }
            while (!result.EndOfMessage);

            if (result.MessageType != WebSocketMessageType.Text)
                continue;

            string json = Encoding.UTF8.GetString(ms.ToArray());
            onMessage($"[WS] {json}");

            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (!root.TryGetProperty("type", out var typeProp))
                continue;

            string msgType = typeProp.GetString() ?? "";

            if (msgType == "sessionEnded")
            {
                var segments = root
                    .GetProperty("content")
                    .GetProperty("punctuatedTranscript")
                    .GetProperty("sentenceSegments");

                var transcript = new StringBuilder();
                foreach (var seg in segments.EnumerateArray())
                {
                    if (transcript.Length > 0)
                        transcript.Append(' ');
                    transcript.Append(seg.GetProperty("phrase").GetString());
                }

                return transcript.ToString();
            }
        }

        throw new Exception("WebSocket closed without receiving sessionEnded.");
    }

    private static async Task<byte[]> ConvertToPcmAsync(
        string inputPath,
        int sampleRate,
        CancellationToken ct)
    {
        var psi = new ProcessStartInfo
        {
            FileName = "ffmpeg",
            Arguments = $"-i \"{inputPath}\" -f s16le -acodec pcm_s16le -ar {sampleRate} -ac 1 pipe:1",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };

        using var proc = Process.Start(psi)
            ?? throw new Exception("Failed to start ffmpeg. Is it installed and on PATH?");

        using var ms = new MemoryStream();
        await proc.StandardOutput.BaseStream.CopyToAsync(ms, ct);
        await proc.WaitForExitAsync(ct);

        if (proc.ExitCode != 0)
        {
            string err = await proc.StandardError.ReadToEndAsync(ct);
            throw new Exception($"ffmpeg failed (exit {proc.ExitCode}): {err}");
        }

        return ms.ToArray();
    }
}
