# Recording format

ToolTape stores a scenario's tool inventory, arguments and outcomes in a UTF-8 JSON file. The file does not store a command to execute. `serve` reads the recording; `record` and `verify` require an executable supplied separately on the command line.

## Scenario

The bundled [support scenario](../examples/support-scenario.json) makes three ordered calls:

```json
{
  "schemaVersion": 1,
  "calls": [
    {"id": "ticket-open", "name": "lookup_ticket", "arguments": {"id": "INC-1042"}},
    {"id": "incidents-api", "name": "list_incidents", "arguments": {"service": "payments-api"}},
    {"id": "ticket-missing", "name": "lookup_ticket", "arguments": {"id": "INC-0000"}}
  ]
}
```

`id` is a unique label assigned by the scenario author. It identifies evidence and ignore policies; it is not the JSON-RPC request ID. Repeated calls to the same tool require different IDs. `arguments` must be an object, including `{}` for an argument-free call. Unknown fields, duplicate JSON object keys and duplicate call IDs are rejected.

## Cassette

Generate complete recordings with valid checksums using:

```sh
tooltape demo --dir demo-output
```

The output includes `baseline.tape.json` and `candidate.tape.json`. The top-level schema is:

| Field | Meaning |
|---|---|
| `schemaVersion` | Integer `1`. |
| `toolVersion` | ToolTape version that created the recording. |
| `protocolVersion` | Negotiated MCP version: `2025-11-25` or `2025-06-18`. |
| `serverInfo` | Recorded server metadata, including required `name` and `version`. |
| `tools` | Complete recorded tool definitions with unique names. |
| `calls` | Ordered exchanges described below. |
| `integritySha256` | Lowercase SHA-256 of the canonical content without this field. |

Each exchange has exactly `id`, `name`, `arguments`, `outcome` and `durationMs`. The first three fields come from the scenario. `name` must exist in the recorded inventory. `durationMs` is a nonnegative integer and is informational.

An outcome contains exactly one of the following members:

```json
{"result":{"content":[{"type":"text","text":"not found"}],"isError":true}}
```

```json
{"error":{"code":-32602,"message":"Invalid arguments","data":{"field":"id"}}}
```

The first is an MCP tool result marked as an application error. The second is a JSON-RPC error. They remain distinct in recordings, comparisons and replay. JSON-RPC envelopes, request IDs and transport notifications are not stored as outcomes.

A result requires a `content` array and may include object-valued `structuredContent`, boolean `isError` and object-valued `_meta`. Supported content types are `text`, `image`, `audio`, `resource` and `resource_link`. ToolTape checks their required basic shape, base64 fields and absolute resource URIs. It does not fetch resource URIs or validate MIME data against an image/audio decoder.

Each tool requires an object-valued `inputSchema` declaring `"type":"object"`; an optional `outputSchema` has the same requirement. Tool definitions are preserved, including annotations and descriptions. Schema references are stored as data and never resolved. The format validator does not implement full JSON Schema validation or prove that an observed result conforms to its declared schema. Tools requiring asynchronous tasks are unsupported.

## Integrity

Canonicalization sorts object keys, retains array order and uses exact decimal values. For example, `1`, `1.0` and `1e0` have the same canonical value, while `"1"` remains a string. No binary floating-point rounding is used for parsed decimal numbers. Strings retain their characters without trimming, case folding or Unicode normalization.

The checksum covers every stored field except `integritySha256`, including fields excluded from the comparison verdict. A changed server version or duration therefore changes the checksum. Reordering object keys or changing a number's equivalent spelling does not.

The checksum detects accidental content changes; it does not authenticate the author. Anyone able to edit the file can calculate a new checksum. This is a ToolTape comparison representation, not a claim of RFC 8785 compliance. Recordings with a missing or mismatched checksum are rejected. Output files must not already exist.

## Bounds and data handling

| Boundary | Limit |
|---|---:|
| Scenario, cassette or ignore-policy input | 16 MiB |
| Written cassette, including whitespace | 16 MiB |
| Calls per scenario/cassette | 1–100 |
| Tool definitions per cassette | 1–256 |
| Call IDs and tool names | 128 characters, nonblank, no control characters |
| Individual tool definition, arguments or outcome | 1 MiB minus 4,096 bytes, serialized as compact UTF-8 JSON |
| JSON nesting | 128 levels |
| JSON string values | 262,144 characters; some named fields have lower limits |
| JSON number text | 1,000 characters |
| Content blocks per result | 4,096 |

The reserved frame bytes allow replay to add a JSON-RPC envelope. Base64 content is also subject to the string limit. Invalid UTF-8, unpaired Unicode surrogates and multiple JSON documents in one file are rejected.

Recordings contain raw arguments and results. There is no automatic redaction or encryption. Use sanitized fixtures before committing a cassette. Comparison ignores affect equality only: ignored values remain in the source recording and can appear inside reported parent values. A summary omits arguments and results; `show`, detailed differences and replay expose recorded evidence.

See [comparison semantics](comparison.md) for verdicts and ignore policies, and [the protocol boundary](protocol.md) for replay behavior.
