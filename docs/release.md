# ToolTape 0.1.0

ToolTape records an explicit sequence of MCP tool calls, replays it offline, and reports changes between recordings. It is a Java 21 command-line utility and stdio MCP replay server.

Download `tooltape-0.1.0.zip`, verify it against `SHA256SUMS`, and extract it. Run `bin/tooltape demo --dir demo-output` (Windows: `bin\tooltape.bat demo --dir demo-output`). The demo records local synthetic data, checks offline replay, and creates an HTML comparison report.

This release includes recording and live verification, strict sequential replay, field-level comparisons with source pointers, explicit ignore policies, and JSON/Markdown/HTML reports. No model account is needed.

Supported MCP versions: 2025-11-25 and 2025-06-18, stdio tools only. Recording executes real calls; replay never starts the original server. Tapes retain complete arguments and outcomes. PASS means no unignored differences were observed in the selected scenario, not proof of compatibility.

[Getting started](https://github.com/Almeida96/tooltape/blob/v0.1.0/README.md) · [Comparison rules](https://github.com/Almeida96/tooltape/blob/v0.1.0/docs/comparison.md) · [Protocol scope](https://github.com/Almeida96/tooltape/blob/v0.1.0/docs/protocol.md)
