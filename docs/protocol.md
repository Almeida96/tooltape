# MCP behavior

ToolTape implements the stdio tool subset of MCP. The relevant protocol definitions are [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle), [stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), and [tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## Recording

The client starts the executable after `--` with `ProcessBuilder`, without interpolating a shell command. Working directory and environment are inherited. It negotiates `2025-11-25` or `2025-06-18`, sends `notifications/initialized`, reads all tool inventory pages, and executes the scenario sequentially. The full inventory and all requested names are validated before any tool call. A server that requires task execution is unsupported.

JSON-RPC frames are newline-delimited UTF-8. Duplicate JSON keys, invalid UTF-8, invalid Unicode, unexpected response IDs and malformed outcomes fail the recording. The client answers server pings and rejects unsupported server requests. It does not advertise sampling, roots or elicitation. It does not treat a protocol failure as a valid tool failure or retry a tool call.

The request deadline covers writing the request and waiting for its response. The default is 5,000 ms and the maximum is 60,000 ms. There is no latency comparison: recorded duration includes protocol overhead and is informational. Stderr is drained and discarded to prevent a blocked child or accidental storage of credentials in logs. A tools-list change notification after inventory capture aborts the run, since the recorded definitions could be stale.

On exit or failure, the client terminates its process and observed descendants. This is cleanup, not process isolation: a deliberately detached process may escape observation. Run trusted servers in an appropriate test environment. ToolTape has no rollback mechanism for effects caused by recorded calls.

## Replay

`serve` reads and validates the tape before responding to MCP traffic. Each process has its own sequence position. It supports `initialize`, `ping`, `tools/list`, and `tools/call`. Initialization must finish before tools are used. Tool inventory is paginated with opaque numeric cursors. The server emits only JSON-RPC on stdout.

Tool definitions retain their names, descriptions, input schemas and output schemas. Replay replaces annotation hints with `readOnlyHint: true`, `destructiveHint: false`, `openWorldHint: false`, and `idempotentHint: false`. The last value reflects the sequence position changing after a matched call. Asynchronous execution metadata is removed. These hints describe replay, not the original server's safety.

A matching call receives the original `result` or `error`, with the current request ID. Both a successful result and a recorded error advance the sequence. Matching compares canonical JSON arguments: object order and numeric spelling do not matter; strings and array order do.

| Situation | Response | Sequence |
|---|---|---|
| Matching next call | Original result or RPC error | Advances |
| Valid tool, different name/arguments than next call | RPC `-32010`, `REPLAY_MISMATCH` | Unchanged |
| Valid tool after the last call | RPC `-32011`, `REPLAY_EXHAUSTED` | Unchanged |
| Unknown tool or invalid parameters | RPC `-32602` | Unchanged |
| Tools requested before initialization | RPC `-32002` | Unchanged |
| Unsupported method | RPC `-32601` | Unchanged |

Replay control failures are RPC errors so they do not fabricate structured results that violate the recorded tool's output schema. Mismatch responses report the position but do not reveal expected argument values. Restart the process to reset the sequence. There is no reset tool that would alter the captured catalog.

## Boundaries

ToolTape does not proxy an interactive agent session. A recording is created from an explicit scenario. It does not record notifications, progress streams or user interactions; it captures completed tool outcomes. There is no parallel call scheduler, cancellation replay, HTTP authentication, resource loading or model invocation.

Descriptions and captured text remain untrusted data. Replay does not sanitize prompt injection or make remote content safe. JSON Schema is structurally checked and preserved, not fully evaluated. Successful replay proves that the captured sequence can be reproduced under these rules, not that a model will make the same decisions.
