# Contributing

Use Java 21. Run `./gradlew test installDist distZip` (`gradlew.bat` on Windows). The Gradle wrapper and dependency lockfile are checked in.

For the independent MCP checks, use Python 3.12 in a virtual environment:

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -r checks/requirements.txt
python checks/cli_smoke.py --install-dir build/install/tooltape
python checks/mcp_smoke.py --install-dir build/install/tooltape
python checks/record_sdk_smoke.py --install-dir build/install/tooltape
```

On Windows, activate `.venv\Scripts\Activate.ps1`. The checks launch actual subprocesses and use temporary directories. No API keys are needed.

Changes to canonicalization, replay order, ignore policy or exit codes need a regression test showing the affected behavior. Keep protocol errors distinct from recorded tool failures. Avoid adding retries to recording: tools can have side effects. Never add production recordings or credentials as fixtures.

The format is versioned. An incompatible cassette change needs a new schema version and a documented migration policy. Update CLI help, documentation and smoke checks when command flags change.

Open an issue with a sanitized scenario, the expected behavior, ToolTape/Java versions and the command used. Include a recording only if it is safe to share. Reports and tapes may contain complete input and output payloads.
