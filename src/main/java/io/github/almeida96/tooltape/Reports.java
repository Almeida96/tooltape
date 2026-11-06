package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static io.github.almeida96.tooltape.Model.*;

public final class Reports {
    private Reports() {}

    public static ObjectNode summary(Tape tape) {
        var summary = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("toolVersion", tape.toolVersion());
        summary.put("protocolVersion", tape.protocolVersion()).put("integritySha256", tape.integritySha256());
        var server = summary.putObject("server");
        server.put("name", tape.serverInfo().path("name").asText()).put("version", tape.serverInfo().path("version").asText());
        summary.put("tools", tape.tools().size()).put("calls", tape.calls().size());
        var calls = summary.putArray("sequence");
        for (int i = 0; i < tape.calls().size(); i++) {
            Exchange call = tape.calls().get(i);
            calls.addObject().put("position", i).put("id", call.id()).put("tool", call.name())
                .put("outcome", outcome(call)).put("durationMs", call.durationMs())
                .put("sourcePointer", "/calls/" + i);
        }
        summary.put("note", "Summary omits arguments and results. Tapes themselves retain them; duration is informational and excluded from drift checks.");
        return summary;
    }

    private static String outcome(Exchange call) {
        return call.outcome().has("error") ? "RPC_ERROR" : call.outcome().path("result").path("isError").asBoolean() ? "TOOL_ERROR" : "RESULT";
    }

    public static String inspection(Tape tape, String format) {
        var report = Json.MAPPER.createObjectNode().put("kind", "inspection").put("status", "RECORDED");
        report.set("baseline", summary(tape));
        return render(report, format);
    }

    public static String comparison(Tape baseline, Tape candidate, Comparison comparison, String format) {
        var report = Json.MAPPER.createObjectNode().put("kind", "comparison").put("status", comparison.status());
        report.set("baseline", summary(baseline)); report.set("candidate", summary(candidate));
        report.set("comparison", Json.MAPPER.valueToTree(comparison));
        return render(report, format);
    }

    private static String render(ObjectNode report, String format) {
        return switch (format) {
            case "json" -> Json.pretty(report);
            case "md" -> markdown(report);
            case "html" -> html(report);
            default -> throw new IllegalArgumentException("Format must be json, md or html");
        };
    }

    private static String markdown(JsonNode report) {
        var text = new StringBuilder("# ToolTape\n\n**Status:** " + report.path("status").asText() + "\n\n");
        JsonNode baseline = report.path("baseline");
        text.append("Server: ").append(md(baseline.path("server").path("name").asText())).append("\n\n");
        text.append("| Position | Call | Tool | Recorded outcome |\n|---:|---|---|---|\n");
        for (JsonNode call : baseline.path("sequence")) text.append("| ").append(call.path("position").asInt())
            .append(" | ").append(md(call.path("id").asText())).append(" | ").append(md(call.path("tool").asText()))
            .append(" | ").append(call.path("outcome").asText()).append(" |\n");
        if (report.has("comparison")) {
            JsonNode comparison = report.path("comparison");
            text.append("\n## Differences\n\n").append(comparison.path("differenceCount").asInt()).append(" differences detected.\n\n");
            if (comparison.path("truncated").asBoolean()) text.append("Only the first 1,000 differences are included.\n\n");
            for (JsonNode difference : comparison.path("differences")) {
                text.append("### ").append(md(difference.path("scope").asText())).append(" · ").append(md(difference.path("callId").asText("")))
                    .append("\n\n").append(md(difference.path("kind").asText())).append("\n\n");
                text.append("Before: ").append(md(difference.path("beforePointer").asText("absent")))
                    .append("\n\nAfter: ").append(md(difference.path("afterPointer").asText("absent"))).append("\n\n");
                text.append("````json\n").append(safeCode(Json.pretty(difference))).append("````\n\n");
            }
            text.append("## Comparison policy\n\n");
            for (JsonNode ignored : comparison.path("ignored")) text.append("- Ignored for ")
                .append(md(ignored.path("callId").asText())).append(": ").append(md(ignored.path("pointer").asText())).append('\n');
            for (JsonNode limitation : comparison.path("limitations")) text.append("- ").append(md(limitation.asText())).append('\n');
        }
        return text.append("\nDurations are informational. A matching recording does not prove server compatibility outside this scenario.\n").toString();
    }

    private static String html(JsonNode report) {
        var text = new StringBuilder("""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>ToolTape — behavioral review</title><style>
            *{box-sizing:border-box}body{margin:0;background:#f3f2ec;color:#202c2c;font:16px/1.65 system-ui,sans-serif}main{max-width:1150px;margin:auto;padding:42px 28px}
            header{border-top:5px solid #185b51;padding:22px 0 30px;border-bottom:1px solid #c9d1c9}.overline{font:12px ui-monospace,monospace;letter-spacing:.2em;color:#536a64}h1{font-size:52px;line-height:1;margin:20px 0 18px;letter-spacing:-.05em}h2{font-size:24px;margin:28px 0 12px}p{margin:12px 0}
            .status{display:inline-block;font:600 13px ui-monospace,monospace;padding:5px 12px;border-radius:4px;background:#dfebe4;color:#225a39}.DRIFT{background:#f5dfb9;color:#6b4311}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:18px;margin:24px 0}.metric{border-left:3px solid #b1c4ba;padding:4px 18px}.metric strong{display:block;font-size:32px;line-height:1.4}.muted{color:#556861}.label{font-size:13px;color:#556861}
            .table{overflow:auto}table{width:100%;border-collapse:collapse;background:#fffdf8;font-size:14px}th,td{text-align:left;padding:12px;border-bottom:1px solid #d9dfd8}th{font-weight:550;color:#556861}td{overflow-wrap:anywhere}article{padding:20px 22px;margin:18px 0;background:#fffdf8;border:1px solid #d3dbd2;border-radius:5px}article h3{font:600 15px ui-monospace,monospace;margin:0;color:#185b51}code{font:12px ui-monospace,monospace;overflow-wrap:anywhere}.evidence{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.evidence>div{min-width:0}pre{background:#eaf0e9;padding:12px;overflow:auto;max-height:300px;font:12px/1.5 ui-monospace,monospace}details{margin:22px 0}summary{cursor:pointer}li{margin:7px 0}footer{border-top:1px solid #c9d1c9;margin-top:30px;padding-top:15px;color:#556861;font-size:13px}
            @media(max-width:680px){main{padding:22px 16px}.metrics,.evidence{grid-template-columns:1fr}h1{font-size:42px}}
            </style><main><header><div class=overline>MCP / RECORD · REPLAY · VERIFY</div><h1>ToolTape</h1>
            """);
        String status = report.path("status").asText();
        text.append("<span class=\"status ").append(esc(status)).append("\">").append(esc(status)).append("</span>")
            .append("<p class=muted>Review the exact tool calls and response changes in a recorded scenario.</p></header>");
        JsonNode baseline = report.path("baseline"), comparison = report.path("comparison");
        text.append("<div class=metrics><div class=metric><span class=label>Recorded tools</span><strong>").append(baseline.path("tools").asInt())
            .append("</strong></div><div class=metric><span class=label>Scenario calls</span><strong>").append(baseline.path("calls").asInt())
            .append("</strong></div><div class=metric><span class=label>Differences</span><strong>").append(comparison.path("differenceCount").asInt())
            .append("</strong></div></div><h2>Call sequence</h2><p class=muted>Server: ").append(esc(baseline.path("server").path("name").asText()))
            .append(" · Protocol ").append(esc(baseline.path("protocolVersion").asText())).append("</p><div class=table><table><thead><tr><th>Step</th><th>Call</th><th>Tool</th><th>Recorded outcome</th></tr></thead><tbody>");
        for (JsonNode call : baseline.path("sequence")) text.append("<tr><td>").append(call.path("position").asInt() + 1)
            .append("</td><td>").append(esc(call.path("id").asText())).append("</td><td><code>")
            .append(esc(call.path("tool").asText())).append("</code></td><td>").append(esc(call.path("outcome").asText())).append("</td></tr>");
        text.append("</tbody></table></div>");
        if (report.has("comparison")) {
            text.append("<h2>Changed evidence</h2>");
            if (comparison.path("differences").isEmpty()) text.append("<p>The inventory, call sequence, arguments and outcomes match under the selected policy.</p>");
            if (comparison.path("truncated").asBoolean()) text.append("<p>Showing the first 1,000 differences. The total includes all detected changes.</p>");
            for (JsonNode diff : comparison.path("differences")) {
                text.append("<article><h3>").append(esc(diff.path("scope").asText())).append(" · ").append(esc(diff.path("callId").asText("")))
                    .append("</h3><p class=muted>").append(esc(diff.path("kind").asText())).append("</p><div class=evidence>");
                for (String side : List.of("before", "after")) text.append("<div><span class=label>").append(side.equals("before") ? "Baseline" : "Candidate")
                    .append("</span><p><code>").append(esc(diff.path(side + "Pointer").asText("absent"))).append("</code></p><pre>")
                    .append(esc(Json.pretty(diff.path(side)))).append("</pre></div>");
                text.append("</div></article>");
            }
            if (!comparison.path("ignored").isEmpty()) {
                text.append("<h2>Explicit exclusions</h2><ul>");
                for (JsonNode ignored : comparison.path("ignored")) text.append("<li><code>").append(esc(ignored.path("callId").asText()))
                    .append("</code>: <code>").append(esc(ignored.path("pointer").asText())).append("</code></li>");
                text.append("</ul>");
            }
            text.append("<h2>Interpretation</h2><ul>");
            for (JsonNode limitation : comparison.path("limitations")) text.append("<li>").append(esc(limitation.asText())).append("</li>");
            text.append("</ul>");
        }
        return text.append("<details><summary>Structured report and source hashes</summary><pre>").append(esc(Json.pretty(report)))
            .append("</pre></details><footer>ToolTape 0.1.0 · Local report · Durations do not affect the verdict · No external assets</footer></main></html>\n").toString();
    }
    static String esc(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String md(String value) { return esc(value).replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]").replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
    private static String safeCode(String value) { return value.replace("`", "\\u0060").replace("<", "\\u003c"); }
}
