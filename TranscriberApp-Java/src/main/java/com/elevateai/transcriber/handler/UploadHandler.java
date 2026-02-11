package com.elevateai.transcriber.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class UploadHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            sendJson(exchange, 400, "{\"error\":\"Expected multipart/form-data\"}");
            return;
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing boundary\"}");
            return;
        }

        byte[] body;
        try (InputStream is = exchange.getRequestBody()) {
            body = is.readAllBytes();
        }

        // Parse the multipart body to extract the file
        String originalFileName = "upload";
        byte[] fileBytes = extractFilePart(body, boundary, originalFileName);
        if (fileBytes == null) {
            sendJson(exchange, 400, "{\"error\":\"No file found in upload\"}");
            return;
        }

        // Extract original filename from Content-Disposition
        originalFileName = extractFileName(body, boundary);

        String ext = "";
        int dot = originalFileName.lastIndexOf('.');
        if (dot >= 0) {
            ext = originalFileName.substring(dot);
        }

        String fileId = UUID.randomUUID().toString();
        Path tempFile = Path.of(System.getProperty("java.io.tmpdir"), "elevateai_" + fileId + ext);
        Files.write(tempFile, fileBytes);

        String json = "{\"fileId\":\"" + fileId + ext + "\",\"fileName\":\"" + escapeJson(originalFileName) + "\",\"size\":" + fileBytes.length + "}";
        sendJson(exchange, 200, json);
    }

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String b = trimmed.substring("boundary=".length());
                if (b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private byte[] extractFilePart(byte[] body, String boundary, String defaultName) {
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        String delim = "--" + boundary;
        int partStart = bodyStr.indexOf(delim);
        if (partStart < 0) return null;

        while (partStart >= 0) {
            int headerEnd = bodyStr.indexOf("\r\n\r\n", partStart);
            if (headerEnd < 0) break;

            String headers = bodyStr.substring(partStart, headerEnd);
            if (headers.contains("filename=")) {
                int dataStart = headerEnd + 4; // skip \r\n\r\n
                int dataEnd = bodyStr.indexOf("\r\n" + delim, dataStart);
                if (dataEnd < 0) dataEnd = body.length;

                byte[] fileData = new byte[dataEnd - dataStart];
                System.arraycopy(body, dataStart, fileData, 0, fileData.length);
                return fileData;
            }

            partStart = bodyStr.indexOf(delim, partStart + delim.length());
        }
        return null;
    }

    private String extractFileName(byte[] body, String boundary) {
        String bodyStr = new String(body, StandardCharsets.ISO_8859_1);
        String delim = "--" + boundary;
        int partStart = bodyStr.indexOf(delim);

        while (partStart >= 0) {
            int headerEnd = bodyStr.indexOf("\r\n\r\n", partStart);
            if (headerEnd < 0) break;

            String headers = bodyStr.substring(partStart, headerEnd);
            int fnIdx = headers.indexOf("filename=\"");
            if (fnIdx >= 0) {
                int nameStart = fnIdx + "filename=\"".length();
                int nameEnd = headers.indexOf("\"", nameStart);
                if (nameEnd > nameStart) {
                    return headers.substring(nameStart, nameEnd);
                }
            }

            partStart = bodyStr.indexOf(delim, partStart + delim.length());
        }
        return "upload";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
