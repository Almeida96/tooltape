#!/usr/bin/env python3
"""Exercise the installed CLI and replay endpoint with the official MCP Python SDK.

Install checks/requirements.txt first. This check uses only the bundled synthetic
ticket server, a temporary directory and child processes. No account is required.
"""

from __future__ import annotations

import argparse
import asyncio
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from datetime import timedelta

import jsonschema
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def cli(command: list[str], *args: str, expected: int = 0) -> dict:
    result = subprocess.run(
        [*command, *args], capture_output=True, text=True, encoding="utf-8", timeout=60,
    )
    require(result.returncode == expected,
            f"CLI exited {result.returncode}, expected {expected}: {result.stderr}")
    return json.loads(result.stdout)


async def expect_rpc_error(session: ClientSession, tool: str, arguments: dict,
                           code: int, position: int | None = None) -> dict:
    try:
        await session.call_tool(tool, arguments)
    except McpError as error:
        require(error.error.code == code, f"Unexpected RPC error: {error.error}")
        value = error.error.model_dump(mode="json", by_alias=True, exclude_none=True)
        if position is not None:
            require(value.get("data", {}).get("position") == position,
                    "Replay moved the cursor after a rejected call")
            require(set(value["data"]) == {"code", "position"},
                    "Replay control errors must not expose recorded arguments/results")
        return value
    raise AssertionError(f"Expected RPC error {code} from {tool}")


async def replay(command: list[str], tape_path: Path, tape: dict) -> dict:
    parameters = StdioServerParameters(command=command[0], args=[
        *command[1:], "serve", "--tape", str(tape_path),
    ])
    async with stdio_client(parameters) as (reader, writer):
        async with ClientSession(reader, writer, read_timeout_seconds=timedelta(seconds=10)) as session:
            initialized = await session.initialize()
            require(initialized.serverInfo.name == "tooltape-replay", "Wrong replay server identity")
            require("not live data" in (initialized.instructions or ""), "Offline disclosure is missing")
            inventory = await session.list_tools()
            require(inventory.nextCursor is None, "Unexpected fixture inventory pagination")
            tools = {tool.name: tool for tool in inventory.tools}
            require(set(tools) == {"lookup_ticket", "list_incidents"}, "Replay changed the tool names")
            for recorded in tape["tools"]:
                tool = tools[recorded["name"]]
                require(tool.inputSchema == recorded["inputSchema"], "Replay changed an input schema")
                require(tool.outputSchema == recorded["outputSchema"], "Replay changed an output schema")
                require(tool.annotations is not None, "Replay safety annotations are missing")
                require(tool.annotations.readOnlyHint is True, "Replay must be read-only")
                require(tool.annotations.destructiveHint is False, "Replay cannot be destructive")
                require(tool.annotations.openWorldHint is False, "Replay cannot contact a backend")
                require(tool.annotations.idempotentHint is False, "Matching calls consume the sequence")

            mismatch = await expect_rpc_error(session, "lookup_ticket", {"id": "INC-9999"}, -32010, 0)
            require(mismatch["data"]["code"] == "REPLAY_MISMATCH", "Wrong mismatch classification")
            await expect_rpc_error(session, "unknown_tool", {}, -32602)
            await expect_rpc_error(session, "list_incidents", {"service": "payments-api"}, -32010, 0)

            matched = 0
            for exchange in tape["calls"]:
                expected = exchange["outcome"]
                if "error" in expected:
                    actual = await expect_rpc_error(session, exchange["name"], exchange["arguments"],
                                                    expected["error"]["code"])
                    require(actual == expected["error"], "Recorded RPC error changed during replay")
                else:
                    result = await session.call_tool(exchange["name"], exchange["arguments"])
                    actual = result.model_dump(mode="json", by_alias=True, exclude_none=True)
                    require(actual == expected["result"], f"Replay changed call {exchange['id']}")
                    # Validate both success and tool-error structured payloads against the original schema.
                    jsonschema.validate(result.structuredContent, tools[exchange["name"]].outputSchema)
                    require(json.loads(result.content[0].text) == result.structuredContent,
                            "Fixture text and structured content disagree")
                matched += 1

            exhausted = await expect_rpc_error(session, "lookup_ticket", {"id": "INC-1042"}, -32011, matched)
            require(exhausted["data"]["code"] == "REPLAY_EXHAUSTED", "Wrong exhaustion classification")
            return {"protocol": initialized.protocolVersion, "tools": len(tools), "matchedCalls": matched}


def raw_protocol_check(command: list[str], tape_path: Path, tape: dict) -> None:
    requests = [
        {"jsonrpc": "2.0", "id": "init", "method": "initialize", "params": {
            "protocolVersion": "2025-11-25", "capabilities": {},
            "clientInfo": {"name": "tooltape-raw-check", "version": "1"},
        }},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": "bad-args", "method": "tools/call", "params": {
            "name": "lookup_ticket", "arguments": [],
        }},
        {"jsonrpc": "2.0", "id": "bad-meta", "method": "tools/call", "params": {
            "name": "lookup_ticket", "arguments": {"id": "INC-1042"}, "_meta": [],
        }},
        {"jsonrpc": "2.0", "id": "bad-wrapper", "method": "tools/call", "params": {
            "name": "lookup_ticket", "arguments": {"id": "INC-1042"}, "unexpected": True,
        }},
        {"jsonrpc": "2.0", "id": "matching", "method": "tools/call", "params": {
            "name": tape["calls"][0]["name"], "arguments": tape["calls"][0]["arguments"],
        }},
    ]
    result = subprocess.run(
        [*command, "serve", "--tape", str(tape_path)],
        input="".join(json.dumps(request) + "\n" for request in requests),
        capture_output=True, text=True, encoding="utf-8", timeout=30,
    )
    require(result.returncode == 0, f"Raw replay failed: {result.stderr}")
    responses = [json.loads(line) for line in result.stdout.splitlines()]
    require(len(responses) == 5, "Notifications produced responses or stdout contains non-protocol output")
    for index, request_id in enumerate(("bad-args", "bad-meta", "bad-wrapper"), start=1):
        require(responses[index]["id"] == request_id, "RPC correlation ID changed")
        require(responses[index]["error"]["code"] == -32602, "Invalid call parameters were accepted")
    require(responses[4] == {"jsonrpc": "2.0", "id": "matching", **tape["calls"][0]["outcome"]},
            "Rejected parameters consumed a recorded call")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, default=Path("build/install/tooltape"))
    args = parser.parse_args()
    install_dir = args.install_dir.resolve(strict=True)
    require(bool(list((install_dir / "lib").glob("tooltape-*.jar"))), "Build installDist before running this check")
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / f"java{suffix}") if java_home else shutil.which("java")
    require(java is not None, "Java 21+ must be available through JAVA_HOME or PATH")
    # Explicit entries survive a Java command nested after another Java launcher's arguments on Windows.
    classpath = os.pathsep.join(str(jar) for jar in sorted((install_dir / "lib").glob("*.jar")))
    command = [java, "-cp", classpath, "io.github.almeida96.tooltape.Main"]
    with tempfile.TemporaryDirectory(prefix="tooltape-mcp-") as temporary:
        directory = Path(temporary)
        scenario_path = directory / "scenario.json"
        calls = [
            {"id": "first", "name": "lookup_ticket", "arguments": {"id": "INC-1042"}},
            {"id": "second", "name": "lookup_ticket", "arguments": {"id": "INC-1042"}},
            {"id": "incidents", "name": "list_incidents", "arguments": {"service": "payments-api"}},
            {"id": "missing", "name": "lookup_ticket", "arguments": {"id": "INC-0000"}},
            {"id": "invalid-id", "name": "lookup_ticket", "arguments": {"id": "invalid"}},
        ]
        scenario_path.write_text(json.dumps({"schemaVersion": 1, "calls": calls}), encoding="utf-8")
        tape_path = directory / "baseline.tape.json"
        cli(command, "record", "--scenario", str(scenario_path), "--out", str(tape_path),
            "--", *command, "fixture-server", "--variant", "baseline")
        original = tape_path.read_bytes()
        tape = json.loads(original)
        require(len(tape["calls"]) == len(calls), "Recorder omitted an exchange")
        require(tape["calls"][3]["outcome"]["result"]["isError"] is True, "Missing ticket must be a tool error")
        require(tape["calls"][4]["outcome"]["error"]["code"] == -32602, "Recorder omitted the fixture RPC error")
        passed = cli(command, "verify", "--tape", str(tape_path), "--", *command,
                     "fixture-server", "--variant", "baseline")
        drift = cli(command, "verify", "--tape", str(tape_path), "--", *command,
                    "fixture-server", "--variant", "changed", expected=2)
        require(passed["status"] == "PASS", "An unchanged server did not pass verification")
        require(drift["status"] == "DRIFT", "The changed fixture did not produce drift")
        result = asyncio.run(replay(command, tape_path, tape))
        raw_protocol_check(command, tape_path, tape)
        require(tape_path.read_bytes() == original, "Serving or verifying modified the source tape")
        print(json.dumps({"status": "passed", "mcpSdk": importlib.metadata.version("mcp"), **result,
                          "liveVerification": "PASS", "changedVerification": "DRIFT",
                          "mismatchDoesNotAdvance": True, "recordedErrorsPreserved": True,
                          "tapeUnchanged": True}, indent=2))


if __name__ == "__main__":
    main()
