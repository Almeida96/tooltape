package io.github.almeida96.tooltape;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecorderTest {
    @TempDir Path temp;

    @Test void preflightsAllArgumentFrameSizesBeforeLaunchingAServer() throws Exception {
        Path marker = temp.resolve("pid");
        var oversized = Json.MAPPER.createObjectNode();
        for (String key : List.of("a", "b", "c", "d")) oversized.put(key, "x".repeat(262_000));
        var calls = List.of(
                new Model.Call("first", "echo", Json.MAPPER.createObjectNode()),
                new Model.Call("second", "echo", oversized));
        assertThrows(IOException.class, () -> Recorder.record(McpClientTest.command("normal", marker), calls, 5000));
        assertFalse(Files.exists(marker), "All argument sizes must be checked before launching the server");
    }
    private Model.Call call(String id, String name) {
        return new Model.Call(id, name, Json.MAPPER.createObjectNode().put("message", "hello"));
    }
    @Test void recordsSuccessfulResultsAndRpcErrorsWithoutProcessDetails() throws Exception {
        Path marker = temp.resolve("pid");
        Model.Tape tape = Recorder.record(McpClientTest.command("normal", marker), List.of(call("one", "echo"), call("two", "fail")), 5000);
        assertEquals(2, tape.calls().size());
        assertEquals("hello", tape.calls().getFirst().outcome().path("result").path("structuredContent").path("message").asText());
        assertEquals(-32001, tape.calls().getLast().outcome().path("error").path("code").asInt());
        assertTrue(tape.calls().stream().allMatch(exchange -> exchange.durationMs() >= 0));
        assertFalse(Json.compact(tape).contains(marker.toString()));
        Path output = temp.resolve("recorded.tape.json");
        TapeIO.writeNew(output, tape);
        assertEquals(tape.integritySha256(), TapeIO.read(output).integritySha256());
    }
    @Test void rejectsEveryUnknownToolBeforeExecutingTheFirstCall() throws Exception {
        Path marker = temp.resolve("pid");
        assertThrows(IOException.class, () -> Recorder.record(McpClientTest.command("normal", marker),
                List.of(call("valid", "echo"), call("typo", "missing")), 5000));
        assertFalse(Files.exists(Path.of(marker + ".calls")));
    }
    @Test void rejectsTaskRequiredToolsBeforeCalling() throws Exception {
        Path marker = temp.resolve("pid");
        assertThrows(IOException.class, () -> Recorder.record(McpClientTest.command("tasks", marker), List.of(call("one", "echo")), 5000));
        assertFalse(Files.exists(Path.of(marker + ".calls")));
    }
    @Test void malformedOutcomeNeverProducesATape() throws Exception {
        assertThrows(IOException.class, () -> Recorder.record(McpClientTest.command("invalid-result", temp.resolve("pid")), List.of(call("one", "echo")), 5000));
    }
    @Test void validatesScenarioBeforeStartingAProcess() throws Exception {
        Path marker = temp.resolve("pid");
        assertThrows(IOException.class, () -> Recorder.record(McpClientTest.command("normal", marker), List.of(call("same", "echo"), call("same", "echo")), 5000));
        assertFalse(Files.exists(marker));
    }
}
