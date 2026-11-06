package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import static io.github.almeida96.tooltape.Model.*;

/** Bounded, checksummed recordings. Stored content is evidence, never executable instructions. */
public final class TapeIO {
    private TapeIO() {}
    private static final Set<String> TAPE_FIELDS = Set.of("schemaVersion", "toolVersion", "protocolVersion",
            "serverInfo", "tools", "calls", "integritySha256");

    public static List<Call> readScenario(Path path) throws IOException {
        JsonNode root = readJson(path);
        fields(root, Set.of("schemaVersion", "calls"), "Scenario");
        version(root);
        JsonNode calls = array(root, "calls", 1, MAX_CALLS);
        var result = new ArrayList<Call>();
        var ids = new HashSet<String>();
        for (JsonNode call : calls) {
            fields(call, Set.of("id", "name", "arguments"), "Scenario call");
            String id = label(call, "id", 128), name = label(call, "name", 128);
            if (!ids.add(id)) throw new IOException("Scenario call IDs must be unique");
            ObjectNode arguments = object(call, "arguments").deepCopy();
            frameSize(arguments);
            result.add(new Call(id, name, arguments));
        }
        return List.copyOf(result);
    }

    public static Tape read(Path path) throws IOException {
        return parseTape(readJson(path));
    }

    public static Tape create(String protocol, ObjectNode serverInfo, List<ObjectNode> tools,
            List<Exchange> calls) throws IOException {
        if (serverInfo == null || tools == null || calls == null) throw new IOException("Recording fields cannot be null");
        ObjectNode root = Json.MAPPER.valueToTree(new Tape(1, VERSION, protocol, serverInfo, tools, calls, ""));
        root.remove("integritySha256");
        root.put("integritySha256", Json.hash(canonical(root)));
        // Serialize/reparse both bounds arbitrary programmatic input and makes a deep snapshot.
        byte[] serialized = Json.compact(root).getBytes(StandardCharsets.UTF_8);
        if (serialized.length > MAX_FILE_BYTES) throw new IOException("Tape exceeds 16 MiB");
        return parseTape(Json.read(new String(serialized, StandardCharsets.UTF_8)));
    }

    public static void writeNew(Path path, Tape tape) throws IOException {
        ObjectNode root = Json.MAPPER.valueToTree(tape);
        parseTape(root);
        byte[] bytes = Json.pretty(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Tape exceeds 16 MiB");
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Tape parseTape(JsonNode raw) throws IOException {
        fields(raw, TAPE_FIELDS, "Tape");
        version(raw);
        String toolVersion = label(raw, "toolVersion", 128), protocol = label(raw, "protocolVersion", 128);
        if (!Set.of(PROTOCOL, "2025-06-18").contains(protocol)) throw new IOException("Unsupported recorded MCP protocol");
        ObjectNode server = object(raw, "serverInfo").deepCopy();
        label(server, "name", 256); label(server, "version", 128);
        JsonNode toolsArray = array(raw, "tools", 1, MAX_TOOLS);
        var tools = new ArrayList<ObjectNode>();
        for (JsonNode tool : toolsArray) {
            if (!tool.isObject()) throw new IOException("Each tool definition must be an object");
            tools.add(((ObjectNode) tool).deepCopy());
        }
        validateTools(tools);
        Set<String> names = new HashSet<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        JsonNode callsArray = array(raw, "calls", 1, MAX_CALLS);
        var calls = new ArrayList<Exchange>();
        var ids = new HashSet<String>();
        for (JsonNode call : callsArray) {
            fields(call, Set.of("id", "name", "arguments", "outcome", "durationMs"), "Recorded call");
            String id = label(call, "id", 128), name = label(call, "name", 128);
            if (!ids.add(id)) throw new IOException("Recorded call IDs must be unique");
            if (!names.contains(name)) throw new IOException("Recorded call refers to a tool outside the inventory");
            ObjectNode args = object(call, "arguments").deepCopy();
            ObjectNode outcome = object(call, "outcome").deepCopy();
            frameSize(args); validateOutcome(outcome);
            JsonNode duration = call.get("durationMs");
            if (duration == null || !duration.isIntegralNumber() || !duration.canConvertToLong() || duration.longValue() < 0)
                throw new IOException("durationMs must be a nonnegative integer");
            calls.add(new Exchange(id, name, args, outcome, duration.longValue()));
        }
        String checksum = label(raw, "integritySha256", 64);
        if (!checksum.matches("[a-f0-9]{64}")) throw new IOException("Invalid tape integrity hash");
        ObjectNode unsigned = ((ObjectNode) raw).deepCopy();
        unsigned.remove("integritySha256");
        if (!Json.hash(canonical(unsigned)).equals(checksum)) throw new IOException("Tape integrity hash does not match its content");
        return new Tape(1, toolVersion, protocol, server, List.copyOf(tools), List.copyOf(calls), checksum);
    }

    /** Validates the supported MCP inventory envelope, not full JSON Schema semantics. */
    public static void validateTools(List<ObjectNode> tools) throws IOException {
        if (tools == null || tools.isEmpty() || tools.size() > MAX_TOOLS) throw new IOException("Inventory requires 1..256 tools");
        var names = new HashSet<String>();
        for (ObjectNode tool : tools) {
            if (tool == null) throw new IOException("Tool definition cannot be null");
            String name = label(tool, "name", 128);
            if (!names.add(name)) throw new IOException("Tool names must be unique");
            ObjectNode input = object(tool, "inputSchema");
            if (!input.path("type").isTextual() || !input.path("type").asText().equals("object"))
                throw new IOException("Tool inputSchema must declare type object");
            if (tool.has("outputSchema")) {
                ObjectNode output = object(tool, "outputSchema");
                if (!output.path("type").isTextual() || !output.path("type").asText().equals("object"))
                    throw new IOException("Tool outputSchema must declare type object");
            }
            for (String field : List.of("description", "title")) if (tool.has(field) && !tool.get(field).isTextual())
                throw new IOException("Tool " + field + " must be text");
            for (String field : List.of("annotations", "_meta")) if (tool.has(field)) object(tool, field);
            if (tool.has("execution")) {
                ObjectNode execution = object(tool, "execution");
                if (execution.has("taskSupport") && !Set.of("forbidden", "optional", "required").contains(execution.path("taskSupport").asText()))
                    throw new IOException("Invalid tool taskSupport declaration");
                if (execution.path("taskSupport").asText().equals("required"))
                    throw new IOException("Task-required tools cannot be recorded by this tools-only client");
            }
            frameSize(tool);
        }
    }

    /** Outcomes hold the result or error member, never request IDs or JSON-RPC envelopes. */
    public static void validateOutcome(ObjectNode outcome) throws IOException {
        if (outcome == null || outcome.size() != 1 || !(outcome.has("result") ^ outcome.has("error")))
            throw new IOException("Outcome must contain exactly one result or error");
        frameSize(outcome);
        if (outcome.has("error")) {
            ObjectNode error = object(outcome, "error");
            fields(error, Set.of("code", "message", "data"), "RPC error");
            JsonNode code = error.get("code");
            if (code == null || !code.isIntegralNumber() || !code.canConvertToInt()) throw new IOException("RPC error code must be an integer");
            text(error, "message", 262144, false);
            return;
        }
        ObjectNode result = object(outcome, "result");
        fields(result, Set.of("content", "structuredContent", "isError", "_meta"), "Tool result");
        JsonNode content = array(result, "content", 0, 4096);
        if (result.has("structuredContent")) object(result, "structuredContent");
        if (result.has("_meta")) object(result, "_meta");
        if (result.has("isError") && !result.get("isError").isBoolean()) throw new IOException("isError must be boolean");
        for (JsonNode block : content) {
            if (!block.isObject()) throw new IOException("MCP content block must be an object");
            String type = label(block, "type", 32);
            switch (type) {
                case "text" -> text(block, "text", 262144, false);
                case "image", "audio" -> {
                    label(block, "mimeType", 256);
                    base64(block, "data");
                }
                case "resource_link" -> {
                    uri(block, "uri"); label(block, "name", 1024);
                }
                case "resource" -> {
                    ObjectNode resource = object(block, "resource");
                    uri(resource, "uri");
                    if (resource.has("text") == resource.has("blob"))
                        throw new IOException("Embedded resource must contain text or blob");
                    if (resource.has("text")) text(resource, "text", 262144, false);
                    else base64(resource, "blob");
                }
                default -> throw new IOException("Unsupported MCP content block type");
            }
        }
    }

    public static List<Ignore> readIgnores(Path path) throws IOException {
        JsonNode root = readJson(path);
        fields(root, Set.of("schemaVersion", "ignore"), "Ignore policy");
        version(root);
        JsonNode rows = array(root, "ignore", 0, 256);
        var ignores = new ArrayList<Ignore>();
        for (JsonNode row : rows) {
            fields(row, Set.of("callId", "pointer"), "Ignore entry");
            ignores.add(new Ignore(label(row, "callId", 128), text(row, "pointer", 2048, true)));
        }
        validateIgnores(ignores);
        return List.copyOf(ignores);
    }

    static void validateIgnores(List<Ignore> ignores) throws IOException {
        if (ignores == null || ignores.size() > 256) throw new IOException("At most 256 ignore paths are supported");
        var seen = new HashSet<String>();
        var previous = new ArrayList<Ignore>();
        for (Ignore ignore : ignores) {
            if (ignore == null || ignore.callId() == null || ignore.callId().isBlank() || ignore.callId().length() > 128
                    || ignore.callId().chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid ignore call ID");
            Json.validString(ignore.callId());
            String pointer = ignore.pointer();
            if (pointer == null || pointer.length() > 2048 || !pointer.startsWith("/")) throw new IOException("Invalid ignore JSON pointer");
            Json.validString(pointer);
            for (int i = 0; i < pointer.length(); i++) if (pointer.charAt(i) == '~') {
                if (++i == pointer.length() || (pointer.charAt(i) != '0' && pointer.charAt(i) != '1'))
                    throw new IOException("Invalid JSON pointer escape");
            }
            if (!(pointer.startsWith("/result/structuredContent/") || pointer.matches("/result/content/(0|[1-9][0-9]*)/text")))
                throw new IOException("Ignore paths must target structuredContent children or a content text value");
            if (!seen.add(ignore.callId() + "\0" + pointer)) throw new IOException("Duplicate ignore path");
            for (Ignore earlier : previous) if (earlier.callId().equals(ignore.callId())
                    && (earlier.pointer().startsWith(pointer + "/") || pointer.startsWith(earlier.pointer() + "/")))
                throw new IOException("Ignore paths cannot overlap within a call");
            previous.add(ignore);
        }
    }

    /** Deterministic JSON comparison form, not a cryptographic signature or an RFC 8785 claim. */
    public static String canonical(JsonNode node) {
        var out = new StringBuilder();
        canonical(node, out, 0);
        return out.toString();
    }

    private static void canonical(JsonNode node, StringBuilder out, int depth) {
        if (node == null || node.isMissingNode()) throw new IllegalArgumentException("Missing JSON value");
        if (depth > 128) throw new IllegalArgumentException("JSON nesting exceeds 128");
        if (node.isObject()) {
            out.append('{'); var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            boolean separator = false;
            for (String key : keys) {
                validUnicode(key); if (separator) out.append(','); separator = true;
                out.append(Json.compact(key)).append(':'); canonical(node.get(key), out, depth + 1);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('['); boolean separator = false;
            for (JsonNode item : node) { if (separator) out.append(','); separator = true; canonical(item, out, depth + 1); }
            out.append(']');
        } else if (node.isNumber()) {
            if (node.isFloatingPointNumber() && (node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue()))
                throw new IllegalArgumentException("JSON number must be finite");
            out.append(node.decimalValue().stripTrailingZeros().toString());
        } else if (node.isTextual()) { validUnicode(node.textValue()); out.append(Json.compact(node.textValue())); }
        else if (node.isBoolean() || node.isNull()) out.append(node.toString());
        else throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static void validUnicode(String value) {
        try { Json.validString(value); } catch (IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
    private static JsonNode readJson(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Input exceeds 16 MiB");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return Json.read(text);
    }
    private static void version(JsonNode root) throws IOException {
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1)
            throw new IOException("schemaVersion must be 1");
    }
    private static void fields(JsonNode node, Set<String> names, String description) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(description + " must be an object");
        var keys = node.fieldNames();
        while (keys.hasNext()) if (!names.contains(keys.next())) throw new IOException(description + " contains an unknown field");
    }
    private static ObjectNode object(JsonNode parent, String key) throws IOException {
        JsonNode value = parent.get(key);
        if (value == null || !value.isObject()) throw new IOException(key + " must be an object");
        return (ObjectNode) value;
    }
    private static JsonNode array(JsonNode parent, String key, int minimum, int maximum) throws IOException {
        JsonNode value = parent.get(key);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum)
            throw new IOException(key + " must be an array of " + minimum + ".." + maximum + " items");
        return value;
    }
    private static String label(JsonNode parent, String key, int maximum) throws IOException {
        String value = text(parent, key, maximum, true);
        if (value.chars().anyMatch(Character::isISOControl)) throw new IOException(key + " cannot contain control characters");
        return value;
    }
    private static String text(JsonNode parent, String key, int maximum, boolean nonblank) throws IOException {
        JsonNode node = parent.get(key);
        if (node == null || !node.isTextual() || node.textValue().length() > maximum || (nonblank && node.textValue().isBlank()))
            throw new IOException(key + " must be " + (nonblank ? "nonempty " : "") + "text of at most " + maximum + " characters");
        Json.validString(node.textValue());
        return node.textValue();
    }
    private static void base64(JsonNode parent, String key) throws IOException {
        String value = text(parent, key, 262144, false);
        try { Base64.getDecoder().decode(value); } catch (IllegalArgumentException e) { throw new IOException("Invalid base64 content"); }
    }
    private static void uri(JsonNode parent, String key) throws IOException {
        String value = label(parent, key, 4096);
        try { if (!URI.create(value).isAbsolute()) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new IOException("Resource uri must be an absolute URI"); }
    }
    private static void frameSize(JsonNode value) throws IOException {
        // Leave room for a JSON-RPC ID/envelope added during replay.
        if (Json.compact(value).getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES - 4096)
            throw new IOException("Recorded value exceeds the 1 MiB protocol frame limit");
    }
}
