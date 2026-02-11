package com.elevateai.transcriber.service;

import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class TranscriptionResult {
    private final String interactionIdentifier;
    private final List<Segment> segments;

    public TranscriptionResult(String interactionIdentifier, List<Segment> segments) {
        this.interactionIdentifier = interactionIdentifier;
        this.segments = segments;
    }

    public String getInteractionIdentifier() {
        return interactionIdentifier;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public String getParticipantOneTranscript() {
        return buildTranscript("participantOne");
    }

    public String getParticipantTwoTranscript() {
        return buildTranscript("participantTwo");
    }

    public String getFullTranscript() {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segments) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(seg.phrase());
        }
        return sb.toString();
    }

    private String buildTranscript(String participant) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : segments) {
            if (participant.equals(seg.participant())) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(seg.phrase());
            }
        }
        return sb.toString();
    }

    public record Segment(String participant, int startTimeOffset, int endTimeOffset,
                           String phrase, double score) {
    }

    public static TranscriptionResult fromSessionEnded(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        var content = root.getAsJsonObject("content");
        String interactionId = content.has("interactionIdentifier")
                ? content.get("interactionIdentifier").getAsString() : "";

        List<Segment> segments = new ArrayList<>();
        var punctuated = content.getAsJsonObject("punctuatedTranscript");
        if (punctuated != null && punctuated.has("sentenceSegments")) {
            for (var seg : punctuated.getAsJsonArray("sentenceSegments")) {
                var obj = seg.getAsJsonObject();
                segments.add(new Segment(
                        obj.has("participant") ? obj.get("participant").getAsString() : "participantOne",
                        obj.has("startTimeOffset") ? obj.get("startTimeOffset").getAsInt() : 0,
                        obj.has("endTimeOffset") ? obj.get("endTimeOffset").getAsInt() : 0,
                        obj.get("phrase").getAsString(),
                        obj.has("score") ? obj.get("score").getAsDouble() : 0
                ));
            }
        }
        return new TranscriptionResult(interactionId, segments);
    }
}
