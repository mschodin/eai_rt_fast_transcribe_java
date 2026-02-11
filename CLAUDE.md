# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ElevateAI Real-Time Fast Transcriber — a Java 21 web application that transcribes audio files using the ElevateAI Real-Time WebSocket API. Users upload an audio file (m4a, wav, mp3, etc.), which is converted to raw PCM via ffmpeg and streamed over a WebSocket to ElevateAI for transcription. Live status updates are delivered to the browser via Server-Sent Events (SSE).

## Tech Stack

- **Runtime:** Java 21 (no framework — plain JDK `com.sun.net.httpserver.HttpServer`)
- **Build:** Maven
- **Template Engine:** Thymeleaf (standalone, no Spring)
- **JSON:** Gson
- **CSS:** Bootstrap 5 (CDN)
- **External dependency:** `ffmpeg` (must be on PATH for audio conversion)
- **API:** ElevateAI Real-Time WebSocket API (`wss://api.elevateai.com/v1/audio/...`)

## Build & Run Commands

```bash
# Build fat JAR
mvn -f TranscriberApp-Java/pom.xml clean package

# Run (http://localhost:8080)
java -jar TranscriberApp-Java/target/transcriber-app.jar
```

## Architecture

**Project structure:**
```
TranscriberApp-Java/
├── pom.xml
└── src/main/
    ├── java/com/elevateai/transcriber/
    │   ├── Main.java                      # Entry point, HTTP server on :8080
    │   ├── handler/
    │   │   ├── HomeHandler.java           # GET / → renders Thymeleaf template
    │   │   ├── UploadHandler.java         # POST /upload → saves file, returns JSON {fileId}
    │   │   ├── TranscribeHandler.java     # GET /transcribe?fileId=&token= → SSE stream
    │   │   └── StaticHandler.java         # GET /static/* → serves CSS/JS
    │   └── service/
    │       └── ElevateAiTranscriber.java  # WebSocket transcription logic
    └── resources/
        ├── templates/
        │   └── home.html                  # Thymeleaf template (UI)
        └── static/
            └── app.css                    # Styles
```

**Transcription flow:**
1. User uploads audio via file input → `POST /upload` saves to temp file, returns `{fileId}`
2. Browser opens `EventSource` to `GET /transcribe?fileId=...&token=...` (SSE)
3. Server runs `ffmpeg` via `ProcessBuilder` to produce raw PCM (s16le, mono, 16kHz)
4. Opens WebSocket to ElevateAI with API token in `X-API-TOKEN` header
5. Streams PCM in 8 KB chunks as binary frames, then sends `{"type":"sessionEnd"}`
6. WebSocket listener collects messages, sends each as SSE `data:` to browser
7. On `sessionEnded`, extracts `punctuatedTranscript.sentenceSegments` and sends as `event: transcript`

**Key files:**
- `Main.java` — HTTP server setup, route registration, virtual thread executor
- `ElevateAiTranscriber.java` — core transcription logic (ffmpeg + WebSocket)
- `TranscribeHandler.java` — SSE endpoint bridging transcriber callbacks to browser
- `home.html` — single-page UI with JavaScript for upload + EventSource

## Notes

- Uses Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`)
- Uses `java.net.http.WebSocket` (JDK built-in) for the ElevateAI WebSocket connection
- The `.github/workflows/ci.yml` is a template from RAPID setup and does not apply to this Java project
- The `.claude/` directory contains RAPID workflow agents/skills configured for TanStack/React — these do not match the actual codebase
- The `TranscriberApp/` directory contains the original .NET 8 Blazor implementation (kept for reference)
