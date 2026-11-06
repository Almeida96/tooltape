package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import static io.github.almeida96.tooltape.Model.*;

/** Conservative observation diff: any unmasked contract or call change is drift. */
public final class Diff {
    private Diff() {}
    private static final int MAX_DIFFERENCES = 1000;
    private static final List<String> LIMITATIONS = List.of(
        "PASS means the recorded tool definitions and ordered calls match under this explicit ignore policy; it is not a full compatibility or correctness proof.",
        "Object key order and numeric formatting are ignored; arrays, strings, tool metadata and call order are significant.",
        "Tool inventory order, recording durations, serverInfo and recording metadata are excluded from the verdict.",
        "Only observed calls are covered. ToolTape does not evaluate JSON Schema entailment, hidden server state or model behavior.",
        "Stored differences are capped at 1,000; differenceCount includes all differences and truncated reports must not be treated as complete evidence.");

    public static Comparison compare(Tape baseline, Tape candidate, List<Ignore> ignores) throws IOException {
        TapeIO.validateIgnores(ignores);
        var baselineCalls = callsById(baseline.calls());
        var candidateCalls = callsById(candidate.calls());
        var beforeOutcomes = outcomes(baseline.calls());
        var afterOutcomes = outcomes(candidate.calls());
        for (Ignore ignore : ignores) {
            if (!baselineCalls.containsKey(ignore.callId()) || !candidateCalls.containsKey(ignore.callId()))
                throw new IOException("Ignore call ID must exist in both recordings");
            replace(beforeOutcomes.get(ignore.callId()), ignore.pointer());
            replace(afterOutcomes.get(ignore.callId()), ignore.pointer());
        }
        var counter = new Counter(baseline, candidate);
        Map<String, IndexedTool> beforeTools = tools(baseline.tools()), afterTools = tools(candidate.tools());
        var toolNames = new TreeSet<String>(); toolNames.addAll(beforeTools.keySet()); toolNames.addAll(afterTools.keySet());
        for (String name : toolNames) {
            IndexedTool before = beforeTools.get(name), after = afterTools.get(name);
            counter.walk("tool:" + name, null, before == null ? null : before.value(), after == null ? null : after.value(),
                    before == null ? null : "/tools/" + before.index(), after == null ? null : "/tools/" + after.index());
        }
        int compared = Math.min(baseline.calls().size(), candidate.calls().size());
        int length = Math.max(baseline.calls().size(), candidate.calls().size());
        for (int i = 0; i < length; i++) {
            Exchange before = i < baseline.calls().size() ? baseline.calls().get(i) : null;
            Exchange after = i < candidate.calls().size() ? candidate.calls().get(i) : null;
            ObjectNode beforeNode = before == null ? null : comparableCall(before, beforeOutcomes.get(before.id()));
            ObjectNode afterNode = after == null ? null : comparableCall(after, afterOutcomes.get(after.id()));
            counter.walk("call", before != null ? before.id() : after.id(), beforeNode, afterNode,
                    before == null ? null : "/calls/" + i, after == null ? null : "/calls/" + i);
        }
        return new Comparison(counter.count == 0 ? "PASS" : "DRIFT", compared, counter.count,
                counter.count > MAX_DIFFERENCES, List.copyOf(counter.differences), List.copyOf(ignores), LIMITATIONS);
    }

    private record IndexedTool(int index, ObjectNode value) {}
    private static Map<String, IndexedTool> tools(List<ObjectNode> tools) {
        var result = new HashMap<String, IndexedTool>();
        for (int i = 0; i < tools.size(); i++) result.put(tools.get(i).path("name").asText(), new IndexedTool(i, tools.get(i)));
        return result;
    }
    private static Map<String, Exchange> callsById(List<Exchange> calls) throws IOException {
        var result = new LinkedHashMap<String, Exchange>();
        for (Exchange call : calls) if (result.putIfAbsent(call.id(), call) != null) throw new IOException("Duplicate call ID");
        return result;
    }
    private static Map<String, ObjectNode> outcomes(List<Exchange> calls) {
        var result = new HashMap<String, ObjectNode>();
        calls.forEach(call -> result.put(call.id(), call.outcome().deepCopy()));
        return result;
    }
    private static ObjectNode comparableCall(Exchange call, ObjectNode outcome) {
        ObjectNode result = Json.MAPPER.createObjectNode().put("id", call.id()).put("name", call.name());
        result.set("arguments", call.arguments()); result.set("outcome", outcome);
        return result;
    }
    private static void replace(ObjectNode outcome, String pointer) throws IOException {
        JsonNode original = outcome.at(pointer);
        if (original.isMissingNode()) throw new IOException("Ignore path must exist in both recorded outcomes: " + pointer);
        int slash = pointer.lastIndexOf('/');
        JsonNode parent = outcome.at(pointer.substring(0, slash));
        String token = pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
        JsonNode marker = TextNode.valueOf("[ToolTape ignored]");
        if (parent.isObject()) ((ObjectNode) parent).set(token, marker);
        else if (parent.isArray()) {
            try { ((ArrayNode) parent).set(Integer.parseInt(token), marker); }
            catch (NumberFormatException | IndexOutOfBoundsException e) { throw new IOException("Invalid array ignore path"); }
        } else throw new IOException("Ignore path must identify a child value");
    }

    private static final class Counter {
        private final JsonNode beforeRoot;
        private final JsonNode afterRoot;
        private int count;
        private final List<Difference> differences = new ArrayList<>();

        private Counter(Tape baseline, Tape candidate) {
            beforeRoot = Json.MAPPER.valueToTree(baseline);
            afterRoot = Json.MAPPER.valueToTree(candidate);
        }

        private void walk(String scope, String callId, JsonNode before, JsonNode after, String beforePointer, String afterPointer) {
            if (before == null || after == null) {
                add(scope, callId, before, after, beforePointer, afterPointer, before == null ? "ADDED" : "REMOVED");
                return;
            }
            if (before.isObject() && after.isObject()) {
                var keys = new TreeSet<String>(); before.fieldNames().forEachRemaining(keys::add); after.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) walk(scope, callId, before.get(key), after.get(key),
                        before.has(key) ? beforePointer + "/" + Json.escape(key) : null,
                        after.has(key) ? afterPointer + "/" + Json.escape(key) : null);
                return;
            }
            if (before.isArray() && after.isArray()) {
                for (int i = 0; i < Math.max(before.size(), after.size()); i++) walk(scope, callId, before.get(i), after.get(i),
                        i < before.size() ? beforePointer + "/" + i : null,
                        i < after.size() ? afterPointer + "/" + i : null);
                return;
            }
            if (!TapeIO.canonical(before).equals(TapeIO.canonical(after)))
                add(scope, callId, before, after, beforePointer, afterPointer, "CHANGED");
        }
        private void add(String scope, String callId, JsonNode before, JsonNode after, String beforePointer, String afterPointer, String kind) {
            count++;
            if (differences.size() < MAX_DIFFERENCES) differences.add(new Difference(scope, callId,
                    beforePointer, afterPointer,
                    beforePointer == null ? null : beforeRoot.at(beforePointer).deepCopy(),
                    afterPointer == null ? null : afterRoot.at(afterPointer).deepCopy(), kind));
        }
    }
}
