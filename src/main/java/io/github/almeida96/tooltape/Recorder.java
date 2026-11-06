package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Records an explicit sequence; protocol failures do not return a partial tape. */
public final class Recorder {
    private Recorder() {}

    public static Model.Tape record(List<String> command, List<Model.Call> calls, int timeoutMs) throws IOException {
        if (calls == null || calls.isEmpty() || calls.size() > Model.MAX_CALLS)
            throw new IOException("A recording needs between 1 and 100 calls");
        // Snapshot and validate before launching a server, including programmatic API callers.
        var stable = new ArrayList<Model.Call>();
        var ids = new HashSet<String>();
        for (Model.Call call : calls) {
            if (call == null || call.id() == null || call.id().isBlank() || call.id().length() > 128
                    || call.name() == null || call.name().isBlank() || call.name().length() > 128 || call.arguments() == null)
                throw new IOException("Calls require nonempty IDs, tool names and object arguments");
            if (!ids.add(call.id())) throw new IOException("Duplicate scenario call ID");
            Json.validString(call.id()); Json.validString(call.name());
            String encoded = Json.compact(call.arguments());
            // Reserve room for the JSON-RPC envelope and tool name, before any side effects.
            if (encoded.getBytes(StandardCharsets.UTF_8).length > Model.MAX_FRAME_BYTES - 4096)
                throw new IOException("Scenario arguments exceed the MCP frame budget (1 MiB minus 4096 bytes)");
            ObjectNode arguments = (ObjectNode) Json.read(encoded);
            stable.add(new Model.Call(call.id(), call.name(), arguments));
        }
        try (var client = new McpClient(command, timeoutMs)) {
            ObjectNode initialized = client.initialize();
            List<ObjectNode> tools = client.listTools();
            TapeIO.validateTools(tools);
            var definitions = new HashMap<String, ObjectNode>();
            tools.forEach(tool -> definitions.put(tool.path("name").asText(), tool));
            // Complete preflight avoids executing an earlier side effect before discovering a typo later.
            for (Model.Call call : stable) {
                ObjectNode definition = definitions.get(call.name());
                if (definition == null) throw new IOException("Scenario references a tool absent from the server inventory");
                if (definition.path("execution").path("taskSupport").asText().equals("required"))
                    throw new IOException("A scenario tool requires MCP tasks, which this recorder does not support");
            }
            var exchanges = new ArrayList<Model.Exchange>();
            long totalBytes = Json.compact(tools).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            for (Model.Call call : stable) {
                long started = System.nanoTime();
                ObjectNode outcome = client.callTool(call.name(), call.arguments());
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                TapeIO.validateOutcome(outcome);
                Model.Exchange exchange = new Model.Exchange(call.id(), call.name(), call.arguments().deepCopy(), outcome.deepCopy(), duration);
                totalBytes += Json.compact(exchange).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (totalBytes > Model.MAX_FILE_BYTES) throw new IOException("Recording exceeds 16 MiB");
                exchanges.add(exchange);
            }
            return TapeIO.create(initialized.path("protocolVersion").asText(), (ObjectNode) initialized.path("serverInfo"), tools, exchanges);
        }
    }
}
