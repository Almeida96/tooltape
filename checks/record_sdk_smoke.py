"""Record an official Python MCP SDK server, detect drift, and replay through the SDK.

Requires Python 3.10+, mcp==1.26.0, Java 21, and a ToolTape installDist directory.
The child --server mode exposes deterministic synthetic data over stdio. No network
service, credentials, existing files, or user data are used.
"""

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from datetime import timedelta


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def run_server(variant: str) -> None:
    from mcp.server.fastmcp import FastMCP
    from mcp.types import CallToolResult, TextContent

    server = FastMCP("tooltape-sdk-fixture", log_level="ERROR")

    @server.tool()
    def lookup_ticket(ticket_id: str) -> dict[str, str | int]:
        """Read a synthetic support ticket used only by the interoperability check."""
        return {
            "ticketId": ticket_id,
            "status": "open" if variant == "baseline" else "escalated",
            "priority": 2 if variant == "baseline" else 1,
        }

    @server.tool()
    def read_attachment(attachment_id: str) -> CallToolResult:
        """Return a deterministic tool error for a missing synthetic attachment."""
        return CallToolResult(
            content=[TextContent(type="text", text=f"Attachment {attachment_id} was not found.")],
            isError=True,
            _meta={"source": "synthetic-sdk-fixture"},
        )

    server.run(transport="stdio")


def installed_command(install_dir: Path) -> list[str]:
    libraries = install_dir.resolve() / "lib"
    if not libraries.is_dir() or not any(libraries.glob("tooltape-*.jar")):
        raise ValueError("--install-dir must point to the ToolTape installDist directory")
    executable = "java.exe" if os.name == "nt" else "java"
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / executable) if java_home else shutil.which(executable)
    if not java or not Path(java).is_file():
        raise ValueError("Java was not found; set JAVA_HOME to a Java 21 installation")
    # Invoke the installed CLI directly, avoiding platform-specific shell wrappers.
    return [java, "-cp", str(libraries / "*"), "io.github.almeida96.tooltape.Main"]


def cli(command: list[str], arguments: list[str], expected_exit: int = 0) -> dict:
    completed = subprocess.run(
        command + arguments,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=60,
        check=False,
    )
    if completed.returncode != expected_exit:
        raise AssertionError(
            f"CLI exited {completed.returncode}, expected {expected_exit}: {completed.stderr.strip()}"
        )
    return json.loads(completed.stdout)


async def replay_with_sdk(command: list[str], tape_path: Path, tape: dict) -> None:
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    from mcp.shared.exceptions import McpError

    parameters = StdioServerParameters(
        command=command[0],
        args=command[1:] + ["serve", "--tape", str(tape_path)],
    )
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=10)) as session:
            await session.initialize()
            inventory = await session.list_tools()
            require({tool.name for tool in inventory.tools} == {"lookup_ticket", "read_attachment"},
                    "Replay changed the SDK tool inventory")
            require(bool(next(tool for tool in inventory.tools if tool.name == "lookup_ticket").outputSchema),
                    "SDK structured output schema was not preserved")
            for exchange in tape["calls"]:
                actual = await session.call_tool(exchange["name"], exchange["arguments"])
                result = actual.model_dump(mode="json", by_alias=True, exclude_none=True)
                require(result == exchange["outcome"]["result"], f"Replay changed call {exchange['id']}")
            try:
                await session.call_tool("lookup_ticket", {"ticket_id": "SUP-17"})
            except McpError as error:
                require(error.error.code == -32011, "Unexpected replay exhaustion error")
            else:
                raise AssertionError("Exhausted replay must report a JSON-RPC error")


def run_check(install_dir: Path) -> None:
    command = installed_command(install_dir)
    fixture = [sys.executable, str(Path(__file__).resolve()), "--server"]
    scenario = {
        "schemaVersion": 1,
        "calls": [
            {"id": "ticket", "name": "lookup_ticket", "arguments": {"ticket_id": "SUP-17"}},
            {"id": "attachment", "name": "read_attachment", "arguments": {"attachment_id": "missing-1"}},
        ],
    }
    with tempfile.TemporaryDirectory(prefix="tooltape-sdk-") as temporary:
        directory = Path(temporary)
        scenario_path = directory / "scenario.json"
        tape_path = directory / "recording.tape.json"
        scenario_path.write_text(json.dumps(scenario), encoding="utf-8")
        summary = cli(command, [
            "record", "--scenario", str(scenario_path), "--out", str(tape_path),
            "--timeout-ms", "15000", "--", *fixture, "--variant", "baseline",
        ])
        require(summary["calls"] == 2, "Recorder did not capture both scenario calls")
        tape = json.loads(tape_path.read_text(encoding="utf-8"))
        require({tool["name"] for tool in tape["tools"]} == {"lookup_ticket", "read_attachment"},
                "Recorder changed the SDK tool inventory")
        require(tape["calls"][0]["outcome"]["result"]["structuredContent"] == {
            "ticketId": "SUP-17", "status": "open", "priority": 2,
        }, "Recorder changed the SDK structured result")
        require(tape["calls"][1]["outcome"]["result"]["isError"] is True, "Tool error was not preserved")
        require(tape["calls"][1]["outcome"]["result"]["_meta"]["source"] == "synthetic-sdk-fixture",
                "Tool result metadata was not preserved")
        for variant, expected, exit_code in [("baseline", "PASS", 0), ("changed", "DRIFT", 2)]:
            comparison = cli(command, [
                "verify", "--tape", str(tape_path), "--timeout-ms", "15000", "--",
                *fixture, "--variant", variant,
            ], expected_exit=exit_code)
            require(comparison["status"] == expected, f"Expected {expected} for SDK fixture variant {variant}")
        asyncio.run(asyncio.wait_for(replay_with_sdk(command, tape_path, tape), timeout=30))
    print("Official Python MCP SDK: recording, tool errors, stable verify, drift detection and offline replay passed.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path)
    parser.add_argument("--server", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--variant", choices=["baseline", "changed"], default="baseline", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.server:
        run_server(args.variant)
    elif args.install_dir is None:
        parser.error("--install-dir is required")
    else:
        run_check(args.install_dir)


if __name__ == "__main__":
    main()
