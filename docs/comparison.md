# Comparison semantics

`compare` evaluates two recordings offline. `verify` runs the baseline's ordered calls against the explicitly supplied server and compares the new observations. Both use the same equality rules after their input and protocol checks succeed.

```sh
tooltape compare --baseline demo-output/baseline.tape.json --candidate demo-output/candidate.tape.json --format json
```

Exit code `0` means `PASS`; `2` means `DRIFT`. Input, process and protocol failures use exit code `1`. A failed live recording is not treated as a passing comparison.

If the live server has removed a tool used by the baseline, `verify` stops with exit code `1` rather than creating an incomplete candidate tape or emitting a `DRIFT` report. Offline `compare` can report inventory additions and removals between two complete, valid recordings.

## What changes the verdict

| Compared | Rule |
|---|---|
| Tool inventory | Match by tool name; additions and removals are changes. Inventory order alone is ignored. |
| Tool definitions | Compare the entire definition, including schemas, descriptions, annotations and metadata. |
| Calls | Compare by sequence position, including author-assigned ID, tool name, arguments and outcome. |
| Objects | Key order is ignored. A missing member differs from an explicit JSON `null`. |
| Arrays | Position and length are significant. |
| Numbers | Exact decimal equality: `1`, `1.0` and `1e0` match. |
| Strings | Exact character equality. JSON-looking text is still text and is not parsed again. |
| Errors | RPC errors and tool results with `isError: true` remain distinct. |

The verdict excludes `durationMs`, `serverInfo`, `toolVersion`, `protocolVersion`, `schemaVersion` and `integritySha256`. Format and protocol validation still apply before comparison. Tool inventory order is excluded; array order inside a tool definition remains significant, including a schema's `required` or `enum` arrays. These conservative rules can report semantically equivalent schema rewrites as changes. ToolTape does not perform schema entailment analysis.

Call IDs label evidence; they do not make sequence order interchangeable. Swapping two calls changes the recorded behavior. `callsCompared` is the smaller call count. Extra calls still produce differences.

## Explicit ignores

An ignore policy targets values within the outcome of a named call:

```json
{
  "schemaVersion": 1,
  "ignore": [
    {"callId": "ticket-open", "pointer": "/result/content/0/text"},
    {"callId": "incidents-api", "pointer": "/result/content/0/text"}
  ]
}
```

This example works with the bundled support fixture. Its text blocks duplicate `structuredContent`; excluding those two text blocks leaves the structured field changes visible. It also means any additional changes confined to those text blocks will be ignored. Use it only if that is the intended comparison policy.

Save the policy as `ignore.json`, then run:

```sh
tooltape compare --baseline demo-output/baseline.tape.json --candidate demo-output/candidate.tape.json --ignore ignore.json --format md
```

Allowed paths are descendants of `/result/structuredContent/`, or exactly `/result/content/<index>/text`. An index is a nonnegative decimal integer with no leading zero unless the index is `0`. Paths use RFC 6901 escaping: `/` within a key is `~1`, and `~` is `~0`.

Both the call ID and the pointed-to value must exist in both recordings. Explicit `null` counts as an existing value. Missing paths cause an error; they never silently match. The policy may contain at most 256 entries, each pointer at most 2,048 characters. Duplicate and overlapping paths within a call are rejected. Root-level masks, error masks, `isError` masks, argument masks and inventory masks are not supported.

An accepted mask ignores the whole value at its path, including type and descendants if it is a container. Keep paths narrow. The full policy is included in the comparison's `ignored` array. Ignores do not modify either source file, sanitize output, or alter replay matching.

## Evidence

Each stored difference includes:

| Field | Meaning |
|---|---|
| `scope` | `call` or `tool:<name>`. |
| `callId` | Baseline call ID, or candidate ID for an added call; `null` for inventory changes. |
| `beforePointer` | JSON Pointer into the original baseline cassette, or `null` when absent. |
| `afterPointer` | JSON Pointer into the original candidate cassette, or `null` when absent. |
| `before`, `after` | Original values at those pointers. |
| `kind` | `ADDED`, `REMOVED` or `CHANGED`. |

Separate source pointers matter when the server reorders its inventory. A changed tool can appear at different indices in each file. Missing and JSON-null values can both serialize as `null` in the value fields; the pointer and `kind` distinguish them. For reordered calls, `callId` names the baseline slot; consult each source pointer for both IDs.

Objects are traversed in sorted key order and arrays by index. Added or removed subtrees count as one difference; matching container types are traversed to their changed children. A type change counts as one difference. This is an observation count, not a severity score.

The report stores the first 1,000 differences while counting all detected differences. If `truncated` is `true`, `differenceCount` exceeds the stored evidence count. The omitted evidence still contributes to `DRIFT`; the report is incomplete for manual investigation.

## Interpreting a result

`PASS` means the captured tool definitions and ordered observations match under the recorded ignore policy. It does not prove compatibility for other arguments, server state, accounts, timing or model behavior. ToolTape is not a latency benchmark, an agent evaluator or a complete MCP conformance suite.

The demo uses deterministic synthetic data. Its changed fixture produces altered ticket priority/status and incident severity/status. Those changes demonstrate evidence collection; they are not measurements from a production system.

For a reproducible baseline, use a controlled test account and data set. Review which tools a scenario invokes before `record` or `verify`: both execute real calls against the supplied process. Offline `compare` and `serve` do not contact the original server.
