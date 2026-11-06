package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public final class Model {
    private Model() {}
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_CALLS = 100;
    public static final int MAX_TOOLS = 256;
    public static final String VERSION = "0.1.0";
    public static final String PROTOCOL = "2025-11-25";
    public record Call(String id, String name, ObjectNode arguments) {}
    public record Exchange(String id, String name, ObjectNode arguments, ObjectNode outcome, long durationMs) {}
    public record Tape(int schemaVersion, String toolVersion, String protocolVersion,
                       ObjectNode serverInfo, List<ObjectNode> tools, List<Exchange> calls,
                       String integritySha256) {}
    public record Ignore(String callId, String pointer) {}
    public record Difference(String scope, String callId, String beforePointer, String afterPointer,
                             JsonNode before, JsonNode after, String kind) {}
    public record Comparison(String status, int callsCompared, int differenceCount, boolean truncated,
                             List<Difference> differences, List<Ignore> ignored, List<String> limitations) {}
}
