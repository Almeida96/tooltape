package io.github.almeida96.tooltape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;
    record Result(int code, String out, String err) {}
    private Result run(String... args) {
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]), new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }
    @Test void rejectsUnknownDuplicateMissingAndMisplacedCommandOptions() {
        assertEquals(0, run("--help").code());
        assertEquals(0, run("--version").code());
        assertEquals(1, run("unknown").code());
        assertEquals(1, run("record", "--scenario", "x", "--out", "y").code());
        assertEquals(1, run("inspect", "--tape").code());
        assertEquals(1, run("inspect", "--tape", "x", "--tape", "y").code());
        assertEquals(1, run("serve", "--tape", "x", "--", "echo").code());
    }
    @Test void existingOutputFailsBeforeAttemptingToLaunchServer() throws Exception {
        Path existing = temp.resolve("existing.tape.json"); Files.writeString(existing, "keep");
        Result result = run("record", "--scenario", "absent", "--out", existing.toString(), "--", "no-such-executable");
        assertEquals(1, result.code()); assertTrue(result.err().contains("already exists"));
        assertEquals("keep", Files.readString(existing));
    }
    @Test void realDemoRecordsComparesAndReplaysThenPreservesExistingDirectory() throws Exception {
        Path directory = temp.resolve("demo");
        Result demo = run("demo", "--dir", directory.toString());
        assertEquals(0, demo.code(), demo.err());
        assertEquals("passed", Json.read(demo.out()).path("offlineReplay").asText());
        Path baseline = directory.resolve("baseline.tape.json"), candidate = directory.resolve("candidate.tape.json");
        assertEquals(0, run("compare", "--baseline", baseline.toString(), "--candidate", baseline.toString()).code());
        assertEquals(2, run("compare", "--baseline", baseline.toString(), "--candidate", candidate.toString()).code());
        assertEquals(0, run("inspect", "--tape", baseline.toString(), "--format", "html").code());
        assertEquals(0, run("show", "--tape", baseline.toString(), "--call", "ticket-open").code());
        assertEquals(1, run("show", "--tape", baseline.toString(), "--call", "missing").code());
        byte[] before = Files.readAllBytes(baseline);
        assertEquals(1, run("demo", "--dir", directory.toString()).code());
        assertArrayEquals(before, Files.readAllBytes(baseline));
    }
    @Test void invalidIgnoreFailsBeforeLiveLaunch() throws Exception {
        Path directory = temp.resolve("demo");
        assertEquals(0, run("demo", "--dir", directory.toString()).code());
        Path policy = temp.resolve("ignore.json");
        Files.writeString(policy, "{\"schemaVersion\":1,\"ignore\":[{\"callId\":\"ticket-open\",\"pointer\":\"/result/structuredContent/missing\"}]}");
        Result result = run("verify", "--tape", directory.resolve("baseline.tape.json").toString(), "--ignore", policy.toString(), "--", "no-such-executable");
        assertEquals(1, result.code()); assertFalse(result.err().contains("Cannot run program"));
    }
}
