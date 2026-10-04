# SkyCraft YS development

This repository starts from upstream SkyCraft 0.1.2. User-facing project
communication and new project documentation should be in Russian.

Read `docs/YS-ROADMAP.md` for requested features and unresolved decisions.
`tools/skills/skycraft-modding/SKILL.md` contains the focused modding workflow.
Upstream `docs/DESIGN.md` is a draft; verify implementation in current code.

Use `powershell -ExecutionPolicy Bypass -File tools/dev.ps1 -Action Check`
to inspect prerequisites. `BuildFabric` runs the existing Gradle build and
tests. `BuildSkse` runs CMake with automatic deployment disabled. Local paths
belong in ignored `.tools/dev-local.json`, not shared source files.
Gradle caches must be outside Skyrim's game directory. `dev.ps1` defaults to
`%LOCALAPPDATA%/SkyCraft/Gradle`; configure `gradleUserHome` for another path.
The game can scan disabled MO2 mod folders and fail on paths of 260 bytes.

`NetworkSmoke` runs an isolated, automatically closing Minecraft client against
a local HTTP proxy fixture to verify the actual login handshake and Mixins.
It requires a working graphics environment. Its output is under
`.tools/network-smoke-game`, separate from installed Prism instances and saves.
`AssistantWorldSmoke` exercises file control, host/probe/failed join/recovery in
a separate `.tools/assistant-world-smoke` Minecraft world without Skyrim; it
refuses to run while Skyrim is open. It does not establish two-PC gameplay.
It now uses an isolated shared-memory stand-in (`sync-smoke-fixture.py`, Python)
and verifies three recoveries plus native-style position feedback. Configure
the optional `python` path in ignored `.tools/dev-local.json` when needed.
`create-retest-table.py` adds the automatic network.4 plan to the workbook;
run it after the legacy `create-network-test-kit.py` generator.

Keep `protocol/skycraft_protocol.h` and the Java `Proto.java` mirror in sync.
Use the pinned CommonLib submodule. Do not equate a successful Java build
with verification of C++ hooks or gameplay. Report unrun checks explicitly.

The parent directory is an MO2 addon folder; `source/` is development source,
not a release archive. Release/install only the intended runtime files.
