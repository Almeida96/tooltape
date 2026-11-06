package io.github.almeida96.tooltape;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.almeida96.tooltape.Model.*;
import static io.github.almeida96.tooltape.TapeIOTest.*;

class ReportsTest {
    @Test void untrustedEvidenceCannotInjectHtmlOrCloseMarkdownFences() throws Exception {
        String payload = "</pre><script>alert('x')</script>\n````\n<img src=x>";
        Tape baseline = tape(List.of(tool("lookup")), List.of(exchange("one", "lookup", object("{}"), outcome("safe"))));
        Tape candidate = tape(baseline.tools(), List.of(exchange("one", "lookup", object("{}"), outcome(payload))));
        Comparison diff = Diff.compare(baseline, candidate, List.of());
        String html = Reports.comparison(baseline, candidate, diff, "html");
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("default-src 'none'"));
        String md = Reports.comparison(baseline, candidate, diff, "md");
        assertFalse(md.contains("<script>")); assertTrue(md.contains("\\u0060\\u0060\\u0060\\u0060"));
        var json = Json.read(Reports.comparison(baseline, candidate, diff, "json"));
        assertEquals(payload, json.path("comparison").path("differences").get(0).path("after").asText());
    }

    @Test void inspectionOmitsPayloadWhileJsonEvidenceRetainsSourcePointers() throws Exception {
        Tape baseline = tape(List.of(tool("lookup")), List.of(exchange("one", "lookup", object("{\"secret\":\"sensitive\"}"), outcome("private result"))));
        String summary = Reports.inspection(baseline, "json");
        assertFalse(summary.contains("sensitive")); assertFalse(summary.contains("private result"));
        assertEquals("/calls/0", Json.read(summary).path("baseline").path("sequence").get(0).path("sourcePointer").asText());
    }
}
