package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.almeida96.tooltape.Model.*;

public final class Main {
    public static void main(String[] args) {
        int code = run(args, System.in, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                out.print(HELP); return 0;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                out.println("tooltape " + VERSION); return 0;
            }
            String command = args[0];
            Set<String> allowed = switch (command) {
                case "record" -> Set.of("scenario", "out", "timeout-ms");
                case "verify" -> Set.of("tape", "ignore", "out", "format", "timeout-ms");
                case "compare" -> Set.of("baseline", "candidate", "ignore", "out", "format");
                case "inspect" -> Set.of("tape", "out", "format");
                case "show" -> Set.of("tape", "call");
                case "serve" -> Set.of("tape");
                case "demo" -> Set.of("dir");
                case "fixture-server" -> Set.of("variant");
                default -> throw new IOException("Unknown command: " + command);
            };
            if (args.length == 2 && args[1].equals("--help")) { out.print(HELP); return 0; }
            Options options = parse(args, allowed, command.equals("record") || command.equals("verify"));
            Map<String, String> flags = options.flags();
            String format = flags.getOrDefault("format", "json");
            if (!Set.of("json", "md", "html").contains(format)) throw new IOException("Format must be json, md or html");
            Path output = flags.containsKey("out") ? Path.of(required(flags, "out")) : null;
            if (output != null) checkNewOutput(output);
            switch (command) {
                case "record" -> {
                    if (output == null) throw new IOException("Missing --out");
                    List<Call> calls = TapeIO.readScenario(Path.of(required(flags, "scenario")));
                    Tape tape = Recorder.record(options.child(), calls, timeout(flags));
                    TapeIO.writeNew(output, tape);
                    out.print(Json.pretty(Reports.summary(tape)));
                    return 0;
                }
                case "verify", "compare" -> {
                    Tape baseline = TapeIO.read(Path.of(required(flags, command.equals("verify") ? "tape" : "baseline")));
                    List<Ignore> ignored = flags.containsKey("ignore") ? TapeIO.readIgnores(Path.of(flags.get("ignore"))) : List.of();
                    // Validate the policy against known evidence before a live process is launched.
                    if (!ignored.isEmpty()) Diff.compare(baseline, baseline, ignored);
                    Tape candidate = command.equals("verify")
                        ? Recorder.record(options.child(), baseline.calls().stream()
                            .map(c -> new Call(c.id(), c.name(), c.arguments().deepCopy())).toList(), timeout(flags))
                        : TapeIO.read(Path.of(required(flags, "candidate")));
                    Comparison comparison = Diff.compare(baseline, candidate, ignored);
                    emit(output, Reports.comparison(baseline, candidate, comparison, format), out);
                    return comparison.status().equals("PASS") ? 0 : 2;
                }
                case "inspect" -> {
                    Tape tape = TapeIO.read(Path.of(required(flags, "tape")));
                    emit(output, Reports.inspection(tape, format), out); return 0;
                }
                case "show" -> {
                    Tape tape = TapeIO.read(Path.of(required(flags, "tape")));
                    String id = required(flags, "call");
                    Exchange exchange = tape.calls().stream().filter(c -> c.id().equals(id)).findFirst()
                            .orElseThrow(() -> new IOException("No recorded call has this id"));
                    out.print(Json.pretty(exchange)); return 0;
                }
                case "serve" -> {
                    new ReplayServer(TapeIO.read(Path.of(required(flags, "tape")))).run(in, out); return 0;
                }
                case "fixture-server" -> {
                    String variant = required(flags, "variant");
                    if (!Set.of("baseline", "changed").contains(variant)) throw new IOException("Variant must be baseline or changed");
                    new FixtureServer(variant).run(in, out); return 0;
                }
                case "demo" -> { demo(Path.of(required(flags, "dir")), out); return 0; }
                default -> throw new IOException("Unknown command");
            }
        } catch (IOException | IllegalArgumentException e) {
            err.println("tooltape: " + e.getMessage()); return 1;
        }
    }

    private record Options(Map<String, String> flags, List<String> child) {}
    private static Options parse(String[] args, Set<String> allowed, boolean live) throws IOException {
        var flags = new LinkedHashMap<String, String>();
        List<String> child = List.of();
        for (int i = 1; i < args.length; i += 2) {
            if (args[i].equals("--")) {
                if (!live || i + 1 == args.length) throw new IOException("A server command is accepted only by record/verify after --");
                child = List.copyOf(Arrays.asList(args).subList(i + 1, args.length)); break;
            }
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2)))
                throw new IOException("Unknown option: " + args[i]);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IOException("Missing value for " + args[i]);
            if (flags.putIfAbsent(args[i].substring(2), args[i + 1]) != null) throw new IOException("Repeated option: " + args[i]);
        }
        if (live && child.isEmpty()) throw new IOException("Provide the server executable and arguments after --");
        return new Options(Map.copyOf(flags), child);
    }
    private static String required(Map<String, String> flags, String key) throws IOException {
        String value = flags.get(key);
        if (value == null || value.isBlank()) throw new IOException("Missing --" + key);
        return value;
    }
    private static int timeout(Map<String, String> flags) throws IOException {
        try {
            int value = Integer.parseInt(flags.getOrDefault("timeout-ms", "5000"));
            if (value < 1 || value > 60_000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IOException("--timeout-ms must be an integer from 1 to 60000"); }
    }
    static void checkNewOutput(Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists; choose a new path");
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Output parent directory does not exist");
    }
    private static void emit(Path output, String content, PrintStream out) throws IOException {
        if (output == null) out.print(content);
        else Files.writeString(output, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static List<String> self(String... args) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(executable, "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        command.addAll(List.of(args)); return command;
    }
    private static void demo(Path directory, PrintStream out) throws IOException {
        Files.createDirectory(directory);
        Path scenario = directory.resolve("scenario.json");
        try (var input = Main.class.getResourceAsStream("/support-scenario.json")) {
            if (input == null) throw new IOException("Bundled scenario is missing");
            Files.write(scenario, input.readAllBytes(), StandardOpenOption.CREATE_NEW);
        }
        List<Call> calls = TapeIO.readScenario(scenario);
        Tape baseline = Recorder.record(self("fixture-server", "--variant", "baseline"), calls, 5000);
        Tape candidate = Recorder.record(self("fixture-server", "--variant", "changed"), calls, 5000);
        Path baselinePath = directory.resolve("baseline.tape.json");
        TapeIO.writeNew(baselinePath, baseline);
        TapeIO.writeNew(directory.resolve("candidate.tape.json"), candidate);
        Comparison comparison = Diff.compare(baseline, candidate, List.of());
        if (!comparison.status().equals("DRIFT")) throw new IOException("Demo expected the changed server to produce drift");
        try (var replay = new McpClient(self("serve", "--tape", baselinePath.toString()), 5000)) {
            replay.initialize(); replay.listTools();
            for (Exchange call : baseline.calls()) {
                ObjectNode actual = replay.callTool(call.name(), call.arguments());
                if (!TapeIO.canonical(actual).equals(TapeIO.canonical(call.outcome())))
                    throw new IOException("Offline replay did not preserve a recorded outcome");
            }
        }
        for (String format : List.of("json", "md", "html"))
            emit(directory.resolve("comparison." + format), Reports.comparison(baseline, candidate, comparison, format), out);
        emit(directory.resolve("inventory.json"), Json.pretty(Reports.summary(baseline)), out);
        out.print(Json.pretty(Json.MAPPER.createObjectNode().put("status", "completed")
            .put("dataset", "synthetic-support-fixture").put("callsRecorded", baseline.calls().size())
            .put("offlineReplay", "passed").put("comparison", comparison.status())
            .put("differences", comparison.differenceCount()).put("report", directory.resolve("comparison.html").toString())));
    }
    private static final String HELP = """
        ToolTape 0.1.0 — record, replay and verify MCP tool behavior

        tooltape record --scenario FILE --out NEW.tape.json [--timeout-ms 5000] -- COMMAND ARGS...
        tooltape verify --tape BASELINE [--ignore POLICY] [--format json|md|html] [--out NEW]
            [--timeout-ms 5000] -- COMMAND ARGS...
        tooltape compare --baseline FILE --candidate FILE [--ignore POLICY] [--format json|md|html] [--out NEW]
        tooltape inspect --tape FILE [--format json|md|html] [--out NEW]
        tooltape show --tape FILE --call ID
        tooltape serve --tape FILE
        tooltape demo --dir NEW-DIRECTORY

        record and verify launch the provided executable and invoke the listed tool calls.
        They can perform real actions. Use a test server/account and a reviewed scenario.
        serve replays a strict sequence offline; it never launches the original server.
        Tapes retain arguments and results. They are not redacted, encrypted or uploaded.
        Java 21+. stdio tools only; no HTTP, resources, prompts, sampling or elicitation.
        Exit codes: 0 success/PASS, 1 input/process/protocol failure, 2 behavioral DRIFT.
        Output paths must not exist. Diff results are observations, not compatibility proofs.
        """;
}
