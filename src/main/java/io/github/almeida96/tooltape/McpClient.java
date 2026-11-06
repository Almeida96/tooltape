package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single-session stdio client. The configured executable is launched directly, without a shell. */
public final class McpClient implements AutoCloseable {
    private static final Set<String> PROTOCOLS = Set.of(Model.PROTOCOL, "2025-06-18");
    private static final int MAX_AUXILIARY_MESSAGES = 256;
    private final Process process;
    private final int timeoutMs;
    private final BlockingQueue<Event> incoming = new ArrayBlockingQueue<>(16);
    private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(daemon("tooltape-stdin"));
    private final Thread reader;
    private final Thread stderr;
    private final Thread tracker;
    private long nextId = 1;
    private boolean initialized;
    private boolean inventoryRead;

    public McpClient(List<String> command, int timeoutMs) throws IOException {
        if (command == null || command.isEmpty() || command.stream().anyMatch(Objects::isNull)
                || command.getFirst().isBlank()) throw new IOException("Provide an executable and its arguments");
        if (timeoutMs < 1 || timeoutMs > 60_000) throw new IOException("Timeout must be between 1 and 60000 milliseconds");
        this.timeoutMs = timeoutMs;
        try {
            process = new ProcessBuilder(List.copyOf(command)).start();
        } catch (IOException | IllegalArgumentException e) {
            writer.shutdownNow();
            throw new IOException("Cannot start the configured MCP server", e);
        }
        reader = daemon("tooltape-stdout").newThread(this::readStdout);
        stderr = daemon("tooltape-stderr").newThread(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) != -1) { /* Drain without retaining potentially sensitive logs. */ }
            } catch (IOException ignored) { /* Process shutdown closes the pipe. */ }
        });
        tracker = daemon("tooltape-process-tracker").newThread(() -> {
            while (!closed.get() && process.isAlive()) {
                rememberDescendants();
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) { return; }
            }
        });
        reader.start();
        stderr.start();
        tracker.start();
    }

    public synchronized ObjectNode initialize() throws IOException {
        if (initialized) throw new IOException("MCP session is already initialized");
        ObjectNode params = object().put("protocolVersion", Model.PROTOCOL);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "tooltape").put("version", Model.VERSION);
        ObjectNode result = lifecycleResult(request("initialize", params), "initialize");
        if (!result.path("protocolVersion").isTextual() || !PROTOCOLS.contains(result.path("protocolVersion").textValue()))
            throw fatal("Server selected an unsupported MCP protocol version");
        JsonNode info = result.path("serverInfo"), capabilities = result.path("capabilities");
        if (!info.isObject() || !nonempty(info.get("name"), 512) || !nonempty(info.get("version"), 512)
                || !capabilities.isObject() || !capabilities.path("tools").isObject())
            throw fatal("Initialize response requires serverInfo and tools capability");
        try {
            send(object().put("jsonrpc", "2.0").put("method", "notifications/initialized"), deadline());
        } catch (IOException failure) { close(); throw failure; }
        initialized = true;
        return result.deepCopy();
    }

    public synchronized List<ObjectNode> listTools() throws IOException {
        requireReady();
        var tools = new ArrayList<ObjectNode>();
        var names = new HashSet<String>();
        var cursors = new HashSet<String>();
        String cursor = null;
        for (int page = 0; page < 32; page++) {
            ObjectNode params = object();
            if (cursor != null) params.put("cursor", cursor);
            ObjectNode result = lifecycleResult(request("tools/list", params), "tools/list");
            if (!result.path("tools").isArray()) throw fatal("tools/list requires a tools array");
            for (JsonNode definition : result.get("tools")) {
                if (!definition.isObject() || !nonempty(definition.get("name"), 128))
                    throw fatal("Tool definitions require a nonempty name of at most 128 characters");
                if (!names.add(definition.get("name").textValue())) throw fatal("Duplicate tool name in inventory");
                if (tools.size() == Model.MAX_TOOLS) throw fatal("Tool inventory exceeds 256 tools");
                tools.add(((ObjectNode) definition).deepCopy());
            }
            if (!result.has("nextCursor")) {
                inventoryRead = true;
                return List.copyOf(tools);
            }
            if (!nonempty(result.get("nextCursor"), 4096)) throw fatal("Invalid tools/list pagination cursor");
            cursor = result.get("nextCursor").textValue();
            if (!cursors.add(cursor)) throw fatal("Repeated tools/list pagination cursor");
        }
        throw fatal("Tool inventory exceeds 32 pages");
    }

    public synchronized ObjectNode callTool(String name, ObjectNode arguments) throws IOException {
        requireReady();
        if (name == null || name.isBlank() || name.length() > 128 || arguments == null)
            throw new IOException("Tool call requires a name and object arguments");
        ObjectNode params = object().put("name", name);
        params.set("arguments", arguments.deepCopy());
        return request("tools/call", params);
    }

    private ObjectNode request(String method, ObjectNode params) throws IOException {
        if (closed.get()) throw new IOException("MCP session is closed");
        long id = nextId++;
        long until = deadline();
        try {
            ObjectNode request = object().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", params);
            send(request, until);
            int auxiliary = 0;
            while (true) {
                Event event;
                try { event = incoming.poll(remaining(until), TimeUnit.NANOSECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException("MCP request interrupted", interrupted);
                }
                if (event == null) throw new IOException("MCP request timed out");
                if (event.failure != null) throw event.failure;
                JsonNode frame = event.message;
                if (!frame.isObject() || !frame.path("jsonrpc").isTextual() || !frame.get("jsonrpc").textValue().equals("2.0"))
                    throw new IOException("Invalid JSON-RPC message from server");
                if (frame.has("method")) {
                    if (++auxiliary > MAX_AUXILIARY_MESSAGES) throw new IOException("Too many server notifications or requests");
                    handleAuxiliary((ObjectNode) frame, until);
                    continue;
                }
                if (!frame.has("id") || !frame.get("id").isIntegralNumber() || !frame.get("id").canConvertToLong()
                        || frame.get("id").longValue() != id)
                    throw new IOException("Unexpected JSON-RPC response id");
                if (frame.has("result") == frame.has("error") || !only(frame, Set.of("jsonrpc", "id", "result", "error")))
                    throw new IOException("Response must contain exactly one result or error");
                String key = frame.has("result") ? "result" : "error";
                if (!frame.get(key).isObject()) throw new IOException("MCP response outcome must be an object");
                if (key.equals("error")) validateRpcError(frame.get(key));
                ObjectNode outcome = object();
                outcome.set(key, frame.get(key).deepCopy());
                return outcome;
            }
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    private void handleAuxiliary(ObjectNode frame, long until) throws IOException {
        if (!nonempty(frame.get("method"), 256) || frame.has("result") || frame.has("error")
                || !only(frame, Set.of("jsonrpc", "id", "method", "params"))
                || (frame.has("params") && !frame.get("params").isObject()))
            throw new IOException("Invalid server request or notification");
        if (!frame.has("id")) {
            if (inventoryRead && frame.get("method").textValue().equals("notifications/tools/list_changed"))
                throw new IOException("Server tool inventory changed during recording; capture a stable session");
            return;
        }
        JsonNode id = frame.get("id");
        if (!(id.isTextual() || id.isIntegralNumber())) throw new IOException("Invalid server request id");
        ObjectNode response = object().put("jsonrpc", "2.0");
        response.set("id", id.deepCopy());
        if (frame.get("method").textValue().equals("ping")) response.putObject("result");
        else response.putObject("error").put("code", -32601).put("message", "Client capability is not available");
        send(response, until);
    }

    private void send(ObjectNode frame, long until) throws IOException {
        String text = Json.compact(frame);
        // Validate programmatically built argument strings before UTF-8 can replace lone surrogates.
        Json.read(text);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Model.MAX_FRAME_BYTES) throw new IOException("Outgoing MCP frame exceeds 1 MiB");
        Future<?> written;
        try {
            written = writer.submit(() -> {
                try {
                    process.getOutputStream().write(bytes);
                    process.getOutputStream().write('\n');
                    process.getOutputStream().flush();
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (RejectedExecutionException closedWriter) { throw new IOException("MCP session is closed"); }
        try { written.get(remaining(until), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            written.cancel(true); Thread.currentThread().interrupt(); throw new IOException("MCP write interrupted", interrupted);
        } catch (TimeoutException timeout) {
            written.cancel(true); throw new IOException("MCP request timed out while writing");
        } catch (ExecutionException failure) { throw new IOException("Cannot write to MCP server", failure.getCause()); }
    }

    private void readStdout() {
        try (var input = new BufferedInputStream(process.getInputStream())) {
            byte[] line = new byte[Model.MAX_FRAME_BYTES];
            int length = 0;
            int value;
            while (!closed.get() && (value = input.read()) != -1) {
                if (value == '\n') {
                    publish(new Event(parse(line, length), null));
                    length = 0;
                } else {
                    if (length == line.length) throw new IOException("Incoming MCP frame exceeds 1 MiB");
                    line[length++] = (byte) value;
                }
            }
            if (!closed.get()) {
                if (length != 0) throw new IOException("Server output ended without a newline-delimited frame");
                throw new IOException("MCP server closed its output");
            }
        } catch (IOException failure) { publish(new Event(null, failure)); }
        catch (RuntimeException failure) { publish(new Event(null, new IOException("Invalid server output", failure))); }
    }

    private static JsonNode parse(byte[] line, int length) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line, 0, length)).toString();
        try { return Json.read(text); }
        catch (IOException failure) { throw new IOException("Server output is not one valid UTF-8 JSON document", failure); }
    }

    private void publish(Event event) {
        try {
            while (!closed.get() && !incoming.offer(event, 100, TimeUnit.MILLISECONDS)) { /* Bounded backpressure. */ }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private ObjectNode lifecycleResult(ObjectNode outcome, String method) throws IOException {
        if (outcome.has("error")) throw fatal("Server rejected " + method);
        return (ObjectNode) outcome.get("result");
    }
    private void requireReady() throws IOException {
        if (!initialized || closed.get()) throw new IOException("Initialize the MCP session before using tools");
    }
    private IOException fatal(String message) { close(); return new IOException(message); }
    private long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs); }
    private static long remaining(long deadline) throws IOException {
        long result = deadline - System.nanoTime();
        if (result <= 0) throw new IOException("MCP request timed out");
        return result;
    }
    private static void validateRpcError(JsonNode error) throws IOException {
        if (!error.path("code").isIntegralNumber() || !error.get("code").canConvertToInt()
                || !error.path("message").isTextual() || !only(error, Set.of("code", "message", "data")))
            throw new IOException("Invalid JSON-RPC error");
    }
    private static boolean only(JsonNode object, Set<String> keys) {
        var names = object.fieldNames();
        while (names.hasNext()) if (!keys.contains(names.next())) return false;
        return true;
    }
    private static boolean nonempty(JsonNode node, int length) {
        return node != null && node.isTextual() && !node.textValue().isBlank() && node.textValue().length() <= length;
    }
    private static ObjectNode object() { return Json.MAPPER.createObjectNode(); }
    private static ThreadFactory daemon(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }
    private void rememberDescendants() {
        try { process.descendants().forEach(descendants::add); }
        catch (SecurityException ignored) { /* The launched process is still terminated below. */ }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        rememberDescendants();
        // Closing stdin normally lets a stdio server exit. Do it on the writer because a blocked
        // write may own the pipe lock; no close path is allowed to hang behind that lock.
        try { writer.submit(() -> { try { process.getOutputStream().close(); } catch (IOException ignored) {} }); }
        catch (RejectedExecutionException ignored) {}
        try { process.waitFor(150, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        rememberDescendants();
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        if (process.isAlive()) process.destroy();
        try { process.waitFor(150, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
        long exitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        try { process.waitFor(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (ProcessHandle descendant : descendants) {
            long left = exitDeadline - System.nanoTime();
            if (left <= 0 || Thread.currentThread().isInterrupted()) break;
            if (descendant.isAlive()) {
                try { descendant.onExit().get(left, TimeUnit.NANOSECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                catch (ExecutionException | TimeoutException ignored) { /* Cleanup is bounded even for unresponsive processes. */ }
            }
        }
        writer.shutdownNow();
        reader.interrupt(); stderr.interrupt(); tracker.interrupt();
    }

    private record Event(JsonNode message, IOException failure) {}
}
