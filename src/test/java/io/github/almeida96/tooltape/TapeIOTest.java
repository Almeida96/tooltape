package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.almeida96.tooltape.Model.*;

class TapeIOTest {
    @TempDir Path temp;
    static ObjectNode object(String source) throws IOException { return (ObjectNode) Json.read(source); }
    static ObjectNode tool(String name) throws IOException {
        var value = object("{\"name\":\"placeholder\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}}");
        value.put("name", name); return value;
    }
    static ObjectNode outcome(String text) {
        var value = Json.MAPPER.createObjectNode();
        value.putObject("result").putArray("content").addObject().put("type", "text").put("text", text);
        return value;
    }
    static Tape tape(List<ObjectNode> tools, List<Exchange> calls) throws IOException {
        return TapeIO.create(PROTOCOL, object("{\"name\":\"fixture\",\"version\":\"1\"}"), tools, calls);
    }
    static Exchange exchange(String id, String name, ObjectNode args, ObjectNode outcome) {
        return new Exchange(id, name, args, outcome, 12);
    }
    private Path write(String name, String content) throws IOException {
        Path path = temp.resolve(name); Files.writeString(path, content, StandardCharsets.UTF_8); return path;
    }

    @Test void scenarioPreservesEscapedContentAndArgumentTypes() throws Exception {
        var calls = TapeIO.readScenario(write("scenario.json", """
            {"schemaVersion":1,"calls":[{"id":"lookup","name":"lookup_ticket","arguments":{"id":"a\\nb","enabled":false,"value":null,"count":1.0}}]}
            """));
        assertEquals(1, calls.size());
        assertEquals("a\nb", calls.getFirst().arguments().path("id").asText());
        assertTrue(calls.getFirst().arguments().get("value").isNull());
        assertFalse(calls.getFirst().arguments().path("enabled").asBoolean());
    }

    @Test void scenarioRejectsAmbiguousAndUnknownInput() throws Exception {
        for (String source : List.of(
            "{\"schemaVersion\":1,\"calls\":[]}",
            "{\"schemaVersion\":1.0,\"calls\":[]}",
            "{\"schemaVersion\":1,\"calls\":[],\"command\":\"shell\"}",
            "{\"schemaVersion\":1,\"calls\":[{\"id\":\"x\",\"name\":\"tool\",\"arguments\":[]}]}",
            "{\"schemaVersion\":1,\"calls\":[{\"id\":\"x\",\"name\":\"tool\",\"arguments\":{},\"extra\":true}]}",
            "{\"schemaVersion\":1,\"calls\":[{\"id\":\"x\",\"name\":\"tool\",\"arguments\":{}},{\"id\":\"x\",\"name\":\"tool\",\"arguments\":{}}]}",
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"calls\":[]}", "{} {}")) {
            assertThrows(IOException.class, () -> TapeIO.readScenario(write("bad.json", source)), source);
        }
    }

    @Test void inputRejectsMalformedUtf8AndUnpairedUnicode() throws Exception {
        Path bad = temp.resolve("bad.json"); Files.write(bad, new byte[]{(byte)0xc3, (byte)0x28});
        assertThrows(IOException.class, () -> TapeIO.readScenario(bad));
        assertThrows(IOException.class, () -> TapeIO.readScenario(write("unicode.json",
            "{\"schemaVersion\":1,\"calls\":[{\"id\":\"x\",\"name\":\"tool\",\"arguments\":{\"v\":\"\\uD800\"}}]}")));
    }

    @Test void tapeRoundTripHasVerifiedIntegrityAndNoOverwrite() throws Exception {
        Tape original = tape(List.of(tool("lookup")), List.of(exchange("first", "lookup", object("{\"id\":42}"), outcome("line 1\nline 2"))));
        Path file = temp.resolve("recording.json"); TapeIO.writeNew(file, original);
        Tape loaded = TapeIO.read(file);
        assertEquals(original.integritySha256(), loaded.integritySha256());
        assertEquals("line 1\nline 2", loaded.calls().getFirst().outcome().path("result").path("content").get(0).path("text").asText());
        byte[] before = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> TapeIO.writeNew(file, original));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void tamperedResponseAndContextFailChecksumVerification() throws Exception {
        Tape original = tape(List.of(tool("lookup")), List.of(exchange("first", "lookup", object("{}"), outcome("original"))));
        for (String pointer : List.of("response", "server")) {
            ObjectNode encoded = Json.MAPPER.valueToTree(original);
            if (pointer.equals("response")) ((ObjectNode) encoded.path("calls").get(0).path("outcome").path("result").path("content").get(0)).put("text", "tampered");
            else ((ObjectNode) encoded.get("serverInfo")).put("version", "2");
            assertThrows(IOException.class, () -> TapeIO.read(write(pointer + ".json", Json.pretty(encoded))));
        }
    }

    @Test void createSnapshotsMutableInputAndWriteRejectsLaterMutation() throws Exception {
        ObjectNode definition = tool("lookup"), args = object("{\"id\":1}"), response = outcome("first");
        Tape tape = tape(List.of(definition), List.of(exchange("one", "lookup", args, response)));
        definition.put("description", "changed"); args.put("id", 2); response.putObject("result").putArray("content");
        assertFalse(tape.tools().getFirst().has("description"));
        assertEquals(1, tape.calls().getFirst().arguments().path("id").asInt());
        tape.calls().getFirst().arguments().put("id", 9);
        Path file = temp.resolve("changed.json");
        assertThrows(IOException.class, () -> TapeIO.writeNew(file, tape));
        assertFalse(Files.exists(file));
    }

    @Test void canonicalComparisonIgnoresKeyOrderAndNumericFormattingOnly() throws Exception {
        assertEquals(TapeIO.canonical(Json.read("{\"a\":1.00,\"b\":1e2,\"c\":-0.0}")),
            TapeIO.canonical(Json.read("{\"c\":0,\"b\":100,\"a\":1}")));
        assertNotEquals(TapeIO.canonical(Json.read("[1,2]")), TapeIO.canonical(Json.read("[2,1]")));
        assertNotEquals(TapeIO.canonical(Json.read("\"1\"")), TapeIO.canonical(Json.read("1")));
        assertThrows(IllegalArgumentException.class, () -> TapeIO.canonical(Json.MAPPER.getNodeFactory().numberNode(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> TapeIO.canonical(Json.MAPPER.getNodeFactory().textNode("\uD800")));
    }

    @Test void validatesToolInventoryWithoutRunningSchemaReferences() throws Exception {
        ObjectNode definition = tool("lookup");
        ((ObjectNode) definition.get("inputSchema")).put("$ref", "https://invalid.example/schema.json");
        TapeIO.validateTools(List.of(definition));
        assertThrows(IOException.class, () -> TapeIO.validateTools(List.of(definition, definition)));
        assertThrows(IOException.class, () -> TapeIO.validateTools(List.of(object("{\"name\":\"x\",\"inputSchema\":{\"type\":\"array\"}}"))));
        ObjectNode task = tool("task"); task.putObject("execution").put("taskSupport", "required");
        assertThrows(IOException.class, () -> TapeIO.validateTools(List.of(task)));
    }

    @Test void unknownCallToolAndInvalidDurationAreRejected() throws Exception {
        assertThrows(IOException.class, () -> tape(List.of(tool("lookup")), List.of(exchange("one", "missing", object("{}"), outcome("value")))));
        assertThrows(IOException.class, () -> tape(List.of(tool("lookup")), List.of(new Exchange("one", "lookup", object("{}"), outcome("value"), -1))));
    }

    @Test void toolErrorsAndRpcErrorsRemainDistinct() throws Exception {
        ObjectNode toolError = outcome("not found"); ((ObjectNode) toolError.get("result")).put("isError", true);
        ObjectNode rpcError = object("{\"error\":{\"code\":-32602,\"message\":\"bad argument\",\"data\":{\"field\":\"id\"}}}");
        TapeIO.validateOutcome(toolError); TapeIO.validateOutcome(rpcError);
        assertNotEquals(TapeIO.canonical(toolError), TapeIO.canonical(rpcError));
    }

    @Test void validatesSupportedContentBlockShapes() throws Exception {
        for (String block : List.of(
            "{\"type\":\"text\",\"text\":\"hello\"}",
            "{\"type\":\"image\",\"data\":\"aGk=\",\"mimeType\":\"image/png\"}",
            "{\"type\":\"audio\",\"data\":\"aGk=\",\"mimeType\":\"audio/wav\"}",
            "{\"type\":\"resource_link\",\"name\":\"readme\",\"uri\":\"file:///README.md\"}",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"test:resource\",\"text\":\"hello\"}}",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"test:resource\",\"blob\":\"aGk=\"}}"))
            TapeIO.validateOutcome(object("{\"result\":{\"content\":[" + block + "]}}"));
    }

    @Test void rejectsMalformedResultAndProtocolEnvelopes() throws Exception {
        for (String source : List.of(
            "{\"result\":{}}", "{\"result\":{\"content\":[],\"isError\":\"false\"}}",
            "{\"result\":{\"content\":[],\"structuredContent\":[]}}",
            "{\"result\":{\"content\":[]},\"error\":{\"code\":-1,\"message\":\"x\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[]}}",
            "{\"error\":{\"code\":1.5,\"message\":\"x\"}}",
            "{\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"!!!\",\"mimeType\":\"image/png\"}]}}",
            "{\"result\":{\"content\":[{\"type\":\"resource\",\"resource\":{\"uri\":\"relative\",\"text\":\"a\"}}]}}",
            "{\"result\":{\"content\":[{\"type\":\"unknown\"}]}}"))
            assertThrows(IOException.class, () -> TapeIO.validateOutcome(object(source)), source);
    }

    @Test void boundsFileCallsAndFrames() throws Exception {
        Path huge = temp.resolve("huge.json"); Files.write(huge, new byte[MAX_FILE_BYTES + 1]);
        assertThrows(IOException.class, () -> TapeIO.read(huge));
        var calls = new ArrayList<Exchange>();
        for (int i = 0; i <= MAX_CALLS; i++) calls.add(exchange("c" + i, "lookup", object("{}"), outcome("ok")));
        assertThrows(IOException.class, () -> tape(List.of(tool("lookup")), calls));
        ObjectNode response = outcome("ok"); var items = ((ObjectNode) response.get("result")).withArray("content");
        for (int i = 0; i < 20; i++) items.addObject().put("type", "text").put("text", "x".repeat(60000));
        assertThrows(IOException.class, () -> TapeIO.validateOutcome(response));
    }

    @Test void ignorePolicyRequiresSupportedUniqueNonoverlappingPaths() throws Exception {
        List<Ignore> valid = TapeIO.readIgnores(write("ignore.json", """
            {"schemaVersion":1,"ignore":[{"callId":"one","pointer":"/result/structuredContent/a~1b/~0key"}]}
            """));
        assertEquals(1, valid.size());
        for (String pointer : List.of("", "/result", "/error/message", "/result/isError", "/result/structuredContent", "/result/content/01/text", "/result/structuredContent/a~2"))
            assertThrows(IOException.class, () -> TapeIO.validateIgnores(List.of(new Ignore("one", pointer))), pointer);
        assertThrows(IOException.class, () -> TapeIO.validateIgnores(List.of(new Ignore("one", "/result/content/0/text"), new Ignore("one", "/result/content/0/text"))));
        assertThrows(IOException.class, () -> TapeIO.validateIgnores(List.of(new Ignore("one", "/result/structuredContent/a"), new Ignore("one", "/result/structuredContent/a/b"))));
    }
}
