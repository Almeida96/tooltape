package io.github.almeida96.tooltape;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import static io.github.almeida96.tooltape.ReplayServer.Endpoint.*;

/** Bundled synthetic ticket service for executable examples; it has no external dependencies. */
public final class FixtureServer {
    private final boolean changed;

    public FixtureServer(String variant) {
        if (!Set.of("baseline", "changed").contains(variant))
            throw new IllegalArgumentException("Fixture variant must be baseline or changed");
        changed = variant.equals("changed");
    }

    public void run(InputStream input, OutputStream output) throws IOException {
        new ReplayServer.Endpoint("tooltape-ticket-fixture", definitions(),
                "Synthetic local ticket data for ToolTape tests. No production service or model is contacted.",
                this::call).run(input, output);
    }

    private ObjectNode call(String tool, ObjectNode args) throws ReplayServer.Endpoint.InvalidParams {
        return switch (tool) {
            case "lookup_ticket" -> {
                parameters(args, Set.of("id"));
                String id = requiredString(args, "id", 8);
                if (!id.matches("INC-[0-9]{4}")) throw invalid("id must match INC- followed by four digits");
                ObjectNode value = object();
                if (!id.equals("INC-1042")) {
                    value.put("status", "not_found").putNull("ticket");
                    yield result(toolResult(value, true));
                }
                value.put("status", "found").putObject("ticket").put("id", id)
                        .put("title", "Payment authorization retries")
                        .put("status", changed ? "resolved" : "investigating")
                        .put("priority", changed ? "P1" : "P2")
                        .put("service", "payments-api");
                yield result(toolResult(value, false));
            }
            case "list_incidents" -> {
                parameters(args, Set.of("service"));
                String service = requiredString(args, "service", 80);
                ObjectNode value = object().put("service", service);
                var incidents = value.putArray("incidents");
                if (service.equals("payments-api")) incidents.addObject().put("id", "INC-1042")
                        .put("severity", changed ? "critical" : "major")
                        .put("status", changed ? "resolved" : "investigating");
                yield result(toolResult(value, false));
            }
            default -> throw invalid("Unknown tool");
        };
    }

    static List<ObjectNode> definitions() {
        ObjectNode lookup = tool("lookup_ticket", "Look up one synthetic incident ticket by its identifier.");
        lookup.putObject("inputSchema").put("type", "object").put("additionalProperties", false)
                .putObject("properties").putObject("id").put("type", "string").put("pattern", "^INC-[0-9]{4}$").put("maxLength", 8);
        ((ObjectNode) lookup.get("inputSchema")).putArray("required").add("id");
        ObjectNode lookupOutput = lookup.putObject("outputSchema").put("type", "object").put("additionalProperties", false);
        var properties = lookupOutput.putObject("properties");
        properties.putObject("status").put("type", "string").putArray("enum").add("found").add("not_found");
        var ticket = properties.putObject("ticket");
        ticket.putArray("type").add("object").add("null");
        ticket.put("additionalProperties", false);
        var ticketProperties = ticket.putObject("properties");
        for (String field : List.of("id", "title", "status", "priority", "service")) ticketProperties.putObject(field).put("type", "string");
        ticket.putArray("required").add("id").add("title").add("status").add("priority").add("service");
        lookupOutput.putArray("required").add("status").add("ticket");

        ObjectNode incidents = tool("list_incidents", "List deterministic synthetic incidents for one service.");
        ObjectNode incidentInput = incidents.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
        incidentInput.putObject("properties").putObject("service").put("type", "string").put("minLength", 1).put("maxLength", 80);
        incidentInput.putArray("required").add("service");
        ObjectNode incidentOutput = incidents.putObject("outputSchema").put("type", "object").put("additionalProperties", false);
        var incidentProperties = incidentOutput.putObject("properties");
        incidentProperties.putObject("service").put("type", "string");
        var item = incidentProperties.putObject("incidents").put("type", "array").putObject("items")
                .put("type", "object").put("additionalProperties", false);
        var itemProperties = item.putObject("properties");
        for (String field : List.of("id", "severity", "status")) itemProperties.putObject(field).put("type", "string");
        item.putArray("required").add("id").add("severity").add("status");
        incidentOutput.putArray("required").add("service").add("incidents");
        return List.of(lookup, incidents);
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode tool = object().put("name", name).put("description", description);
        tool.putObject("annotations").put("readOnlyHint", true).put("destructiveHint", false)
                .put("openWorldHint", false).put("idempotentHint", true);
        return tool;
    }
}
