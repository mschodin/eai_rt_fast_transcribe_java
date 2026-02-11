package com.elevateai.transcriber;

import com.elevateai.transcriber.handler.HomeHandler;
import com.elevateai.transcriber.handler.StaticHandler;
import com.elevateai.transcriber.handler.TranscribeHandler;
import com.elevateai.transcriber.handler.UploadHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new HomeHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/transcribe", new TranscribeHandler());
        server.createContext("/static/", new StaticHandler());

        // Use virtual threads on Java 21+, fall back to cached thread pool
        try {
            var method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            server.setExecutor((java.util.concurrent.ExecutorService) method.invoke(null));
        } catch (NoSuchMethodException e) {
            server.setExecutor(Executors.newCachedThreadPool());
        }
        server.start();

        System.out.println("ElevateAI Transcriber running at http://localhost:" + port);
    }
}
