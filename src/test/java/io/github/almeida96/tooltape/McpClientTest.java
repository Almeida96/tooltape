package io.github.almeida96.tooltape;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {
    @TempDir Path temp;

    static List<String> command(String mode, Path marker) throws Exception {
        var classpath = new LinkedHashSet<String>();
        for (Class<?> type : List.of(Fixture.class, Model.class, ObjectMapper.class, JsonFactory.class, JsonCreator.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(executable, "-cp", String.join(File.pathSeparator, classpath), Fixture.class.getName(), mode, marker.toString());
    }

    @Test void negotiatesAndDiscoversPagesBeforeCallingTools() throws Exception {
        try (var client = new McpClient(command("normal", temp.resolve("pid")), 5000)) {
            assertThrows(IOException.class, client::listTools);
            assertEquals(Model.PROTOCOL, client.initialize().path("protocolVersion").asText());
            var tools = client.listTools();
            assertEquals(List.of("echo", "fail"), tools.stream().map(t -> t.path("name").asText()).toList());
            var args = Json.MAPPER.createObjectNode().put("message", "hello");
            var outcome = client.callTool("echo", args);
            assertEquals(args, outcome.path("result").path("structuredContent"));
            assertEquals(-32001, client.callTool("fail", args).path("error").path("code").asInt());
            assertThrows(IOException.class, client::initialize);
        }
    }

    @Test void supportsPriorProtocolAndRejectsUnknownVersions() throws Exception {
        try (var client = new McpClient(command("previous", temp.resolve("previous")), 5000)) {
            assertEquals("2025-06-18", client.initialize().path("protocolVersion").asText());
        }
        try (var client = new McpClient(command("unsupported", temp.resolve("bad")), 5000)) {
            assertThrows(IOException.class, client::initialize);
        }
    }

    @Test void correlatesIdsAndRejectsMalformedFrames() throws Exception {
        for (String mode : List.of("bad-id", "malformed", "utf8", "oversized", "both-outcomes", "truncated")) {
            try (var client = new McpClient(command(mode, temp.resolve(mode)), 5000)) {
                assertThrows(IOException.class, client::initialize, mode);
            }
        }
    }

    @Test void boundsInventoryAndRejectsDuplicateNamesOrCursorCycles() throws Exception {
        for (String mode : List.of("duplicate-tools", "cursor-cycle", "many-tools", "many-pages")) {
            try (var client = new McpClient(command(mode, temp.resolve(mode)), 5000)) {
                client.initialize();
                assertThrows(IOException.class, client::listTools, mode);
            }
        }
    }

    @Test void handlesPingAndRejectsUnnegotiatedSamplingWithoutInvokingAnything() throws Exception {
        try (var client = new McpClient(command("server-requests", temp.resolve("pid")), 5000)) {
            client.initialize();
            assertEquals(2, client.listTools().size());
        }
    }

    @Test void notificationFloodIsBoundedAndStderrCannotDeadlock() throws Exception {
        try (var client = new McpClient(command("stderr", temp.resolve("stderr")), 5000)) {
            client.initialize();
            assertEquals(2, client.listTools().size());
        }
        try (var client = new McpClient(command("notifications", temp.resolve("notifications")), 5000)) {
            client.initialize();
            assertThrows(IOException.class, client::listTools);
        }
    }

    @Test void timeoutTerminatesTheLaunchedServer() throws Exception {
        Path marker = temp.resolve("pid");
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            try (var client = new McpClient(command("timeout", marker), 1000)) {
                assertThrows(IOException.class, client::initialize);
            }
        });
        long pid = Long.parseLong(Files.readString(marker));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void writeTimeoutDoesNotHangWhenServerStopsReading() throws Exception {
        Path marker = temp.resolve("pid");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (var client = new McpClient(command("blocked-write", marker), 2000)) {
                client.initialize();
                client.listTools();
                var args = Json.MAPPER.createObjectNode();
                args.put("a", "a".repeat(200_000)).put("b", "b".repeat(200_000)).put("c", "c".repeat(200_000));
                assertThrows(IOException.class, () -> client.callTool("echo", args));
            }
        });
        long pid = Long.parseLong(Files.readString(marker));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void timeoutAlsoTerminatesOwnedDescendants() throws Exception {
        Path marker = temp.resolve("parent");
        Path childMarker = Path.of(marker + ".child");
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
                try (var client = new McpClient(command("spawn-child", marker), 3000)) {
                    assertThrows(IOException.class, client::initialize);
                }
            });
            assertTrue(Files.exists(childMarker), "Fixture must have launched its descendant");
            for (Path pidFile : List.of(marker, childMarker)) {
                long pid = Long.parseLong(Files.readString(pidFile));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), pidFile.toString());
            }
        } finally {
            for (Path pidFile : List.of(marker, childMarker)) {
                if (Files.exists(pidFile)) ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                        .filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test void validatesConstructorAndProgrammaticUnicode() throws Exception {
        assertThrows(IOException.class, () -> new McpClient(List.of(), 5000));
        assertThrows(IOException.class, () -> new McpClient(List.of("irrelevant"), 0));
        try (var client = new McpClient(command("normal", temp.resolve("pid")), 5000)) {
            client.initialize();
            assertThrows(IOException.class, () -> client.callTool("echo", Json.MAPPER.createObjectNode().put("bad", "\uD800")));
        }
    }

    @Test void changingInventoryAbortsWithoutRetryingAToolCall() throws Exception {
        Path marker = temp.resolve("pid");
        try (var client = new McpClient(command("list-change", marker), 5000)) {
            client.initialize(); client.listTools();
            assertThrows(IOException.class, () -> client.callTool("echo", Json.MAPPER.createObjectNode()));
        }
        assertEquals(List.of("called"), Files.readAllLines(Path.of(marker + ".calls")));
    }

    /** Separate JVM process used by tests; no production server or network dependency. */
    public static final class Fixture {
        private static boolean ready;
        private static String mode;
        private static Path marker;
        private static int pages;
        private static BufferedReader reader;

        public static void main(String[] args) throws Exception {
            mode = args[0]; marker = Path.of(args[1]);
            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
            if (mode.equals("timeout")) { Thread.sleep(60_000); return; }
            if (mode.equals("spawn-child")) {
                String executable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        Fixture.class.getName(), "timeout", marker + ".child").start();
                Thread.sleep(60_000); return;
            }
            reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                ObjectNode request = (ObjectNode) Json.read(line);
                String method = request.path("method").asText();
                if (method.equals("notifications/initialized")) { ready = true; continue; }
                if (method.equals("initialize")) initialize(request);
                else if (method.equals("tools/list")) inventory(request);
                else if (method.equals("tools/call")) call(request);
            }
        }
        private static void initialize(ObjectNode request) throws Exception {
            if (mode.equals("malformed")) { System.out.println("startup log is not JSON"); return; }
            if (mode.equals("utf8")) { System.out.write(new byte[]{(byte) 0xc3, (byte) 0x28, '\n'}); System.out.flush(); return; }
            if (mode.equals("oversized")) { System.out.print("x".repeat(Model.MAX_FRAME_BYTES + 1) + "\n"); System.out.flush(); return; }
            if (mode.equals("truncated")) { System.out.print("{\"jsonrpc\":\"2.0\"}"); System.out.flush(); System.exit(0); }
            ObjectNode response = envelope(request);
            if (mode.equals("bad-id")) response.put("id", 987);
            ObjectNode result = response.putObject("result");
            result.put("protocolVersion", mode.equals("unsupported") ? "9999-01-01" : mode.equals("previous") ? "2025-06-18" : Model.PROTOCOL);
            result.putObject("serverInfo").put("name", "fixture").put("version", "1.0");
            result.putObject("capabilities").putObject("tools");
            if (mode.equals("both-outcomes")) response.putObject("error").put("code", -32000).put("message", "bad");
            emit(response);
        }
        private static void inventory(ObjectNode request) throws Exception {
            if (!ready) throw new IllegalStateException("Client omitted initialized notification");
            if (mode.equals("server-requests")) {
                emit(Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", "ping-id").put("method", "ping"));
                var pong = Json.read(reader.readLine());
                if (!pong.path("id").asText().equals("ping-id") || !pong.path("result").isObject()) throw new IllegalStateException("Missing pong");
                emit(Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", "sampling-id").put("method", "sampling/createMessage"));
                var refusal = Json.read(reader.readLine());
                if (refusal.path("error").path("code").asInt() != -32601) throw new IllegalStateException("Sampling was not refused");
            }
            if (mode.equals("stderr")) {
                System.err.print("private stderr log\n".repeat(200_000)); System.err.flush();
            }
            if (mode.equals("notifications")) for (int i = 0; i < 300; i++)
                emit(Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/message"));
            var response = envelope(request); var result = response.putObject("result"); var tools = result.putArray("tools");
            if (mode.equals("many-tools")) for (int i = 0; i < 257; i++) tools.add(tool("tool" + i));
            else if (mode.equals("many-pages")) { tools.add(tool("tool" + pages)); result.put("nextCursor", "page" + ++pages); }
            else if (mode.equals("cursor-cycle")) { tools.add(tool("tool" + pages++)); result.put("nextCursor", "again"); }
            else if (mode.equals("duplicate-tools")) { tools.add(tool("echo")); tools.add(tool("echo")); }
            else if (mode.equals("blocked-write")) tools.add(tool("echo"));
            else if (!request.path("params").has("cursor")) { tools.add(tool("echo")); result.put("nextCursor", "second"); }
            else tools.add(tool("fail"));
            emit(response);
            if (mode.equals("blocked-write")) Thread.sleep(60_000);
        }
        private static ObjectNode tool(String name) {
            var tool = Json.MAPPER.createObjectNode().put("name", name);
            tool.putObject("inputSchema").put("type", "object");
            if (mode.equals("tasks")) tool.putObject("execution").put("taskSupport", "required");
            return tool;
        }
        private static void call(ObjectNode request) throws Exception {
            Files.writeString(Path.of(marker + ".calls"), "called\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (mode.equals("list-change")) emit(Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"));
            var response = envelope(request);
            if (request.path("params").path("name").asText().equals("fail"))
                response.putObject("error").put("code", -32001).put("message", "Fixture failure").putObject("data").put("retryable", false);
            else {
                var result = response.putObject("result");
                if (mode.equals("invalid-result")) result.put("content", "wrong shape");
                else result.putArray("content").addObject().put("type", "text").put("text", "echo response");
                result.set("structuredContent", request.path("params").path("arguments").deepCopy());
            }
            emit(response);
        }
        private static ObjectNode envelope(ObjectNode request) {
            ObjectNode response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0"); response.set("id", request.get("id")); return response;
        }
        private static void emit(ObjectNode value) { System.out.println(Json.compact(value)); System.out.flush(); }
    }
}
