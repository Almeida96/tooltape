package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.almeida96.tooltape.Model.*;
import static io.github.almeida96.tooltape.TapeIOTest.*;

class DiffTest {
    private Tape single(ObjectNode args, ObjectNode outcome) throws IOException {
        return tape(List.of(tool("lookup")), List.of(exchange("one", "lookup", args, outcome)));
    }
    private ObjectNode structured(String json) throws IOException {
        ObjectNode result = outcome("same"); ((ObjectNode) result.get("result")).set("structuredContent", Json.read(json)); return result;
    }

    @Test void identicalObservationsIgnoreMetadataDurationsAndNumericRendering() throws Exception {
        Tape a = single(object("{\"id\":1,\"v\":null}"), structured("{\"count\":100}"));
        Tape b = TapeIO.create(PROTOCOL, object("{\"name\":\"changed server\",\"version\":\"2\"}"), a.tools(),
                List.of(new Exchange("one", "lookup", object("{\"v\":null,\"id\":1.0}"), structured("{\"count\":1e2}"), 999)));
        Comparison c = Diff.compare(a, b, List.of());
        assertEquals("PASS", c.status()); assertEquals(0, c.differenceCount()); assertEquals(1, c.callsCompared());
    }

    @Test void inventoryOrderDoesNotCauseDriftButEveryDefinitionFieldDoes() throws Exception {
        ObjectNode lookup = tool("lookup"), other = tool("other");
        Tape a = tape(List.of(lookup, other), List.of(exchange("one", "lookup", object("{}"), outcome("ok"))));
        Tape reordered = tape(List.of(other, lookup), a.calls());
        assertEquals("PASS", Diff.compare(a, reordered, List.of()).status());
        ObjectNode changed = lookup.deepCopy(); changed.put("description", "changed contract guidance");
        Tape b = tape(List.of(other, changed), a.calls());
        Difference d = Diff.compare(a, b, List.of()).differences().getFirst();
        assertEquals("tool:lookup", d.scope());
        assertNull(d.beforePointer()); assertEquals("/tools/1/description", d.afterPointer());
        assertEquals("ADDED", d.kind());
    }

    @Test void dualPointersResolveTheActualToolsAfterReordering() throws Exception {
        ObjectNode lookup = tool("lookup").put("description", "before"), other = tool("other");
        Tape a = tape(List.of(lookup, other), List.of(exchange("one", "lookup", object("{}"), outcome("ok"))));
        Tape b = tape(List.of(other, lookup.deepCopy().put("description", "after")), a.calls());
        Difference d = Diff.compare(a, b, List.of()).differences().getFirst();
        assertEquals("/tools/0/description", d.beforePointer()); assertEquals("/tools/1/description", d.afterPointer());
        JsonNode beforeTape = Json.MAPPER.valueToTree(a), afterTape = Json.MAPPER.valueToTree(b);
        assertEquals(d.before(), beforeTape.at(d.beforePointer())); assertEquals(d.after(), afterTape.at(d.afterPointer()));
    }

    @Test void missingAndExplicitNullRemainDifferent() throws Exception {
        Tape a = single(object("{}"), structured("{\"value\":null}"));
        Tape b = single(object("{}"), structured("{}"));
        Difference d = Diff.compare(a, b, List.of()).differences().getFirst();
        assertEquals("REMOVED", d.kind()); assertTrue(d.before().isNull()); assertNull(d.after());
        assertEquals("/calls/0/outcome/result/structuredContent/value", d.beforePointer()); assertNull(d.afterPointer());
    }

    @Test void argumentChangeIsDriftEvenWhenResponseIsIdentical() throws Exception {
        Comparison c = Diff.compare(single(object("{\"id\":1}"), outcome("ok")), single(object("{\"id\":2}"), outcome("ok")), List.of());
        assertEquals("DRIFT", c.status());
        assertEquals("/calls/0/arguments/id", c.differences().getFirst().beforePointer());
    }

    @Test void callOrderAndArrayOrderRemainSignificant() throws Exception {
        Tape a = tape(List.of(tool("lookup")), List.of(exchange("one", "lookup", object("{}"), outcome("one")), exchange("two", "lookup", object("{}"), outcome("two"))));
        Tape b = tape(a.tools(), List.of(a.calls().get(1), a.calls().get(0)));
        assertEquals("DRIFT", Diff.compare(a, b, List.of()).status());
        Comparison array = Diff.compare(single(object("{}"), structured("{\"items\":[1,2]}")), single(object("{}"), structured("{\"items\":[2,1]}")), List.of());
        assertEquals(2, array.differenceCount());
    }

    @Test void toolErrorsAndRpcErrorsAreCompared() throws Exception {
        ObjectNode response = outcome("missing"); ((ObjectNode) response.get("result")).put("isError", true);
        Tape a = single(object("{}"), response), b = single(object("{}"), object("{\"error\":{\"code\":-32602,\"message\":\"missing\"}}"));
        Comparison comparison = Diff.compare(a, b, List.of());
        assertEquals("DRIFT", comparison.status());
        assertEquals(2, comparison.differenceCount());
    }

    @Test void explicitIgnoresMaskOnlyTheirPresentPathsWithoutMutatingTapes() throws Exception {
        Tape a = single(object("{}"), structured("{\"requestId\":\"before\",\"value\":7}"));
        Tape b = single(object("{}"), structured("{\"requestId\":\"after\",\"value\":7}"));
        String original = Json.compact(a);
        Ignore ignored = new Ignore("one", "/result/structuredContent/requestId");
        Comparison c = Diff.compare(a, b, List.of(ignored));
        assertEquals("PASS", c.status()); assertEquals(List.of(ignored), c.ignored());
        assertEquals(original, Json.compact(a));
        assertEquals("DRIFT", Diff.compare(a, b, List.of()).status());
    }

    @Test void ignoresCannotHideErrorsOrSilentlyMatchMissingEvidence() throws Exception {
        Tape a = single(object("{}"), structured("{\"id\":\"a\"}")), b = single(object("{}"), structured("{}"));
        assertThrows(IOException.class, () -> Diff.compare(a, b, List.of(new Ignore("one", "/result/structuredContent/id"))));
        assertThrows(IOException.class, () -> Diff.compare(a, a, List.of(new Ignore("absent", "/result/structuredContent/id"))));
        assertThrows(IOException.class, () -> Diff.compare(a, a, List.of(new Ignore("one", "/result/isError"))));
    }

    @Test void escapedPointersAndTextContentCanBeIgnoredExactly() throws Exception {
        Tape a = single(object("{}"), structured("{\"a/b\":{\"~key\":1}}")), b = single(object("{}"), structured("{\"a/b\":{\"~key\":2}}"));
        assertEquals("PASS", Diff.compare(a, b, List.of(new Ignore("one", "/result/structuredContent/a~1b/~0key"))).status());
        assertEquals("PASS", Diff.compare(single(object("{}"), outcome("first")), single(object("{}"), outcome("second")), List.of(new Ignore("one", "/result/content/0/text"))).status());
    }

    @Test void extraCallHasNoBaselinePointerAndIsNotSilentlyDropped() throws Exception {
        Tape a = single(object("{}"), outcome("one"));
        Tape b = tape(a.tools(), List.of(a.calls().getFirst(), exchange("two", "lookup", object("{}"), outcome("two"))));
        Comparison c = Diff.compare(a, b, List.of());
        assertEquals("DRIFT", c.status()); assertEquals(1, c.callsCompared());
        assertNull(c.differences().getFirst().beforePointer()); assertEquals("/calls/1", c.differences().getFirst().afterPointer());
    }

    @Test void reportedContainerChangesUseOriginalEvidenceEvenWhenDescendantsAreIgnored() throws Exception {
        Tape a = single(object("{}"), structured("{\"items\":{\"0\":{\"id\":\"before\"}}}"));
        Tape b = single(object("{}"), structured("{\"items\":[{\"id\":\"after\"}]}"));
        Comparison c = Diff.compare(a, b, List.of(new Ignore("one", "/result/structuredContent/items/0/id")));
        assertEquals("DRIFT", c.status());
        Difference changed = c.differences().getFirst();
        JsonNode baseline = Json.MAPPER.valueToTree(a), candidate = Json.MAPPER.valueToTree(b);
        assertEquals(baseline.at(changed.beforePointer()), changed.before());
        assertEquals(candidate.at(changed.afterPointer()), changed.after());
        assertEquals("before", changed.before().path("0").path("id").asText());
        assertEquals("after", changed.after().get(0).path("id").asText());
    }

    @Test void boundedEvidenceStillCountsEveryDifference() throws Exception {
        ObjectNode a = Json.MAPPER.createObjectNode(), b = Json.MAPPER.createObjectNode();
        for (int i = 0; i < 1100; i++) { a.put("key" + i, 1); b.put("key" + i, 2); }
        Comparison c = Diff.compare(single(object("{}"), structured(Json.compact(a))), single(object("{}"), structured(Json.compact(b))), List.of());
        assertEquals("DRIFT", c.status()); assertTrue(c.truncated()); assertEquals(1100, c.differenceCount()); assertEquals(1000, c.differences().size());
    }
}
