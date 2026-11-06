package io.github.almeida96.tooltape;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayServerTest {
    private static final String INIT = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
        """;
    private static final String READY = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";

    private ObjectNode object(String json) throws Exception { return (ObjectNode) Json.read(json); }
    private ObjectNode tool(String name) {
        ObjectNode result = Json.MAPPER.createObjectNode().put("name", name).put("description", "Synthetic test tool");
        result.putObject("inputSchema").put("type", "object");
        result.putObject("annotations").put("readOnlyHint", false).put("destructiveHint", true)
                .put("idempotentHint", true).put("openWorldHint", true);
        return result;
    }
    private ObjectNode outcome(String text) {
        ObjectNode outcome = Json.MAPPER.createObjectNode();
        outcome.putObject("result").putArray("content").addObject().put("type", "text").put("text", text);
        return outcome;
    }
    private Model.Tape tape(List<ObjectNode> tools, List<Model.Exchange> calls) throws Exception {
        return TapeIO.create(Model.PROTOCOL, object("{\"name\":\"source\",\"version\":\"1\"}"), tools, calls);
    }
    private Model.Tape single() throws Exception {
        return tape(List.of(tool("lookup")), List.of(new Model.Exchange("one", "lookup", object("{\"id\":1}"), outcome("private-result"), 0)));
    }
    private String call(String id, String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}\n";
    }
    private List<JsonNode> run(ReplayServer server, String messages) throws Exception {
        return run(server, messages.getBytes(StandardCharsets.UTF_8));
    }
    private List<JsonNode> run(ReplayServer server, byte[] messages) throws Exception {
        var out = new ByteArrayOutputStream();
        server.run(new ByteArrayInputStream(messages), out);
        var result = new ArrayList<JsonNode>();
        for (String line : out.toString(StandardCharsets.UTF_8).lines().toList()) result.add(Json.read(line));
        return result;
    }
    private static void code(JsonNode response, int code) {
        assertEquals(code, response.path("error").path("code").intValue(), response.toPrettyString());
    }

    @Test void requiresLifecycleAndNegotiatesSupportedProtocol() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}\n";
        var result = run(new ReplayServer(single()), list + INIT.replace("2025-11-25", "unknown") + list + READY + list);
        code(result.get(0), -32002);
        assertEquals(Model.PROTOCOL, result.get(1).path("result").path("protocolVersion").asText());
        assertEquals("tooltape-replay", result.get(1).path("result").path("serverInfo").path("name").asText());
        assertTrue(result.get(1).path("result").path("instructions").asText().contains("not live data"));
        code(result.get(2), -32002);
        assertEquals(1, result.get(3).path("result").path("tools").size());
    }

    @Test void sequenceMismatchDoesNotAdvanceOrDiscloseRecordedValues() throws Exception {
        var result = run(new ReplayServer(single()), INIT + READY + call("2", "lookup", "{\"id\":999}")
                + call("\"fresh-id\"", "lookup", "{\"id\":1}") + call("4", "lookup", "{\"id\":1}"));
        code(result.get(1), -32010);
        assertEquals("REPLAY_MISMATCH", result.get(1).path("error").path("data").path("code").asText());
        assertFalse(result.get(1).toString().contains("private-result"));
        assertFalse(result.get(1).path("error").toString().contains("999"));
        assertEquals("fresh-id", result.get(2).path("id").asText());
        assertEquals("private-result", result.get(2).path("result").path("content").get(0).path("text").asText());
        code(result.get(3), -32011);
    }

    @Test void duplicateSignaturesConsumeDistinctOutcomesAndResetForAnotherRun() throws Exception {
        var tape = tape(List.of(tool("lookup")), List.of(
                new Model.Exchange("first", "lookup", object("{\"id\":1}"), outcome("first response"), 0),
                new Model.Exchange("second", "lookup", object("{\"id\":1}"), outcome("second response"), 0)));
        var server = new ReplayServer(tape);
        String input = INIT + READY + call("2", "lookup", "{\"id\":1}") + call("3", "lookup", "{\"id\":1}");
        var result = run(server, input);
        assertEquals("first response", result.get(1).path("result").path("content").get(0).path("text").asText());
        assertEquals("second response", result.get(2).path("result").path("content").get(0).path("text").asText());
        assertEquals(result, run(server, input));
    }

    @Test void canonicalMatchingIgnoresKeyOrderAndNumericScaleButNotArrayOrder() throws Exception {
        var tape = tape(List.of(tool("lookup")), List.of(new Model.Exchange("one", "lookup",
                object("{\"number\":1,\"nested\":{\"b\":2,\"a\":[1,2]}}"), outcome("match"), 0)));
        var result = run(new ReplayServer(tape), INIT + READY
                + call("2", "lookup", "{\"nested\":{\"a\":[2,1],\"b\":2},\"number\":1.0}")
                + call("3", "lookup", "{\"nested\":{\"a\":[1,2],\"b\":2.00},\"number\":1e0}"));
        code(result.get(1), -32010);
        assertEquals("match", result.get(2).path("result").path("content").get(0).path("text").asText());
    }

    @Test void recordedToolErrorsAndRpcErrorsAreReplayedExactlyAndConsumed() throws Exception {
        var toolFailure = object("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"not found\"}],\"isError\":true}}");
        var rpcFailure = object("{\"error\":{\"code\":-32099,\"message\":\"recorded failure\",\"data\":{\"retry\":false}}}");
        var tape = tape(List.of(tool("lookup")), List.of(
                new Model.Exchange("one", "lookup", object("{}"), toolFailure, 0),
                new Model.Exchange("two", "lookup", object("{}"), rpcFailure, 0)));
        var result = run(new ReplayServer(tape), INIT + READY + call("55", "lookup", "{}")
                + call("56", "lookup", "{}") + call("57", "lookup", "{}"));
        assertEquals(toolFailure.get("result"), result.get(1).get("result"));
        assertEquals(rpcFailure.get("error"), result.get(2).get("error"));
        assertEquals(56, result.get(2).path("id").asInt());
        code(result.get(3), -32011);
    }

    @Test void replayAnnotationsDescribeOfflineStatefulBehaviorWithoutChangingSchemas() throws Exception {
        Model.Tape tape = single();
        var result = run(new ReplayServer(tape), INIT + READY + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n");
        var replayed = result.get(1).path("result").path("tools").get(0);
        assertTrue(replayed.path("annotations").path("readOnlyHint").asBoolean());
        assertFalse(replayed.path("annotations").path("destructiveHint").asBoolean());
        assertFalse(replayed.path("annotations").path("openWorldHint").asBoolean());
        assertFalse(replayed.path("annotations").path("idempotentHint").asBoolean());
        assertEquals(tape.tools().getFirst().path("inputSchema"), replayed.path("inputSchema"));
        assertTrue(tape.tools().getFirst().path("annotations").path("destructiveHint").asBoolean());
    }

    @Test void constructorTakesPrivateSnapshotOfMutableJson() throws Exception {
        Model.Tape tape = single();
        var server = new ReplayServer(tape);
        tape.calls().getFirst().arguments().put("id", 999);
        ((ObjectNode) tape.calls().getFirst().outcome().path("result").path("content").get(0)).put("text", "mutated");
        tape.tools().getFirst().put("name", "mutated");
        var response = run(server, INIT + READY + call("2", "lookup", "{\"id\":1}"));
        assertEquals("private-result", response.get(1).path("result").path("content").get(0).path("text").asText());
    }

    @Test void invalidArgumentsAndNotificationsCannotConsumeTheSequence() throws Exception {
        String invalid = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lookup\",\"arguments\":{},\"task\":{}}}\n";
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"lookup\",\"arguments\":{\"id\":1}}}\n";
        var result = run(new ReplayServer(single()), INIT + READY + invalid + notification
                + call("3", "unknown", "{}") + call("4", "lookup", "[]") + call("5", "lookup", "{\"id\":1}"));
        assertEquals(5, result.size());
        code(result.get(1), -32602); code(result.get(2), -32602); code(result.get(3), -32602);
        assertTrue(result.get(4).has("result"));
    }

    @Test void malformedProgressMetadataCannotConsumeTheSequence() throws Exception {
        String invalid = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"lookup\",\"arguments\":{\"id\":1},\"_meta\":{\"progressToken\":{}}}}\n";
        var result = run(new ReplayServer(single()), INIT + READY + invalid + call("3", "lookup", "{\"id\":1}"));
        code(result.get(1), -32602);
        assertTrue(result.get(2).has("result"));
    }

    @Test void malformedUtf8AndOversizedFramesAreDrainedWithoutLosingTheNextRequest() throws Exception {
        var input = new ByteArrayOutputStream();
        input.write(new byte[]{(byte) 0xc3, 0x28, '\n'});
        input.write("x".repeat(Model.MAX_FRAME_BYTES + 1).getBytes(StandardCharsets.UTF_8)); input.write('\n');
        input.write((INIT + READY + call("2", "lookup", "{\"id\":1}")).getBytes(StandardCharsets.UTF_8));
        var result = run(new ReplayServer(single()), input.toByteArray());
        assertEquals(4, result.size()); code(result.get(0), -32700); code(result.get(1), -32700);
        assertTrue(result.get(3).has("result"));
    }

    @Test void toolsArePagedWithStrictCursors() throws Exception {
        var tools = new ArrayList<ObjectNode>();
        for (int i = 0; i < 40; i++) tools.add(tool("t" + i));
        var tape = tape(tools, List.of(new Model.Exchange("one", "t0", object("{}"), outcome("x"), 0)));
        String requests = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"32\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{\"cursor\":\"-1\"}}\n";
        var result = run(new ReplayServer(tape), INIT + READY + requests);
        assertEquals(32, result.get(1).path("result").path("tools").size());
        assertEquals("32", result.get(1).path("result").path("nextCursor").asText());
        assertEquals(8, result.get(2).path("result").path("tools").size());
        assertFalse(result.get(2).path("result").has("nextCursor")); code(result.get(3), -32602);
    }

    @Test void toolPaginationAlsoRespectsSerializedByteLimits() throws Exception {
        var tools = new ArrayList<ObjectNode>();
        for (int i = 0; i < 6; i++) tools.add(tool("large" + i).put("description", "x".repeat(200_000)));
        var tape = tape(tools, List.of(new Model.Exchange("one", "large0", object("{}"), outcome("x"), 0)));
        var result = run(new ReplayServer(tape), INIT + READY
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"5\"}}\n");
        assertEquals(5, result.get(1).path("result").path("tools").size());
        assertEquals("5", result.get(1).path("result").path("nextCursor").asText());
        assertEquals(1, result.get(2).path("result").path("tools").size());
        assertTrue(Json.compact(result.get(1)).getBytes(StandardCharsets.UTF_8).length <= Model.MAX_FRAME_BYTES);
    }

    @Test void fixtureReturnsDeterministicSuccessAndMissingTicketResults() throws Exception {
        var out = new ByteArrayOutputStream();
        new FixtureServer("baseline").run(new ByteArrayInputStream((INIT + READY
                + call("2", "lookup_ticket", "{\"id\":\"INC-1042\"}")
                + call("3", "lookup_ticket", "{\"id\":\"INC-0000\"}")
                + call("4", "list_incidents", "{\"service\":\"payments-api\"}")).getBytes(StandardCharsets.UTF_8)), out);
        var replies = out.toString(StandardCharsets.UTF_8).lines().map(line -> {
            try { return Json.read(line); } catch (Exception error) { throw new IllegalStateException(error); }
        }).toList();
        assertEquals("investigating", replies.get(1).path("result").path("structuredContent").path("ticket").path("status").asText());
        assertTrue(replies.get(2).path("result").path("isError").asBoolean());
        assertEquals("not_found", replies.get(2).path("result").path("structuredContent").path("status").asText());
        assertEquals("major", replies.get(3).path("result").path("structuredContent").path("incidents").get(0).path("severity").asText());
    }
}
