---
name: skycraft-modding
description: Develop SkyCraft Skyrim/Minecraft integration, including its SKSE C++ plugin, Fabric Java mod, shared-memory protocol, item conversion, and multiplayer transport. Use for SkyCraft source work and build diagnostics.
---

# SkyCraft modding

## Establish the baseline

Identify the checked-out commit, installed SkyCraft version, Skyrim runtime,
Fabric/Minecraft versions, and actual build configuration before editing.
Upstream is https://github.com/chasmlol/SkyCraft. Read repository instructions
and build files; requirements can change between releases.

SkyCraft has two independently built processes: `skse/` (C++, SKSE/CommonLib,
D3D11) and `fabric/` (Java, Fabric/Mixins). Find the code that owns a feature
before choosing which side to change. `docs/DESIGN.md` is a historical design
draft; verify its claims against current code and README limitations.

## Shared-memory contract

`protocol/skycraft_protocol.h` defines the binary layout. Its Java mirror is
`fabric/src/main/java/dev/skycraft/link/Proto.java`. Keep offsets, sizes,
endianness, sequence locks, ring capacity, event ownership, and protocol
version consistent in both. Layout changes require a coordinated version
change and compatible builds on both sides. Check Python stand-ins when
altering fields that they use.

Coordinates use Minecraft space unless a field says otherwise: Y up, Z south,
70 Skyrim units per block in the 0.1.2 baseline. Check conversion functions
before modifying collision, camera, actor, or worldspace handling.

## Build and verification

Prefer the repository's `tools/dev.ps1` when present. Otherwise inspect the
Gradle wrapper and CMake presets. The 0.1.2 baseline uses Java 25, Minecraft
26.3, C++23, and the Visual Studio 18 2026 generator. Use its pinned CommonLib
submodule, not a different revision inferred from the directory's name.

Disable automatic CMake deployment during baseline builds with
`-DSKYCRAFT_DEPLOY_DIR=`. Keep build artifacts and local paths out of commits.
The package script can build and clear `dist`; inspect it before running.
Keep source/build dependency caches outside the Skyrim game directory.
Skyrim may recursively scan `Mods` even without MO2 and fail on a path that
does not fit its 260-byte buffer. Use the external Gradle cache configured by
`tools/dev.ps1`, never a junction back into the game directory.

Run the affected build and existing focused tests. Java geometry tests do
not establish SKSE runtime correctness. For integration changes, report game
testing separately: launching, loading a test save, exercising the change,
saving/reloading, and checking both games' logs. Use separate test saves and
Minecraft worlds when testing inventory or persistence changes.

## Multiplayer changes

Trace join/leave handling, Discord invites, the e4mc dependency, the launcher,
and release packaging together. Do not mistake Minecraft-world multiplayer
for synchronization of Skyrim NPCs, quests, or saves.

Separate the gameplay host from discovery/NAT traversal/relay infrastructure.
Direct peer connectivity cannot be guaranteed behind every NAT, VPN, proxy,
or filtering setup. Prefer application-scoped transport when that is the
requested scope. Make HTTP CONNECT/SOCKS configuration explicit; Java traffic
does not necessarily follow a system proxy. Verify third-party service limits
and current documentation before choosing a provider.

Test direct LAN, different NATs/CGNAT, UDP unavailable, VPN, system proxy,
DNS failure, reconnect, and the user's actual combinations. Never claim
compatibility from a single successful local connection.

## Item conversion

Use a configurable mapping keyed by plugin identity plus a stable local
FormID, or a verified EditorID; a load-order-dependent full FormID is not a
portable key. Unknown items remain in Skyrim unless explicitly requested
otherwise. Define handling for quest items, ownership/stolen status, stacks,
enchantments, full inventory, and mod-added items before destructive transfer.

Treat conversion across processes as a transaction: unique request IDs,
acknowledgements, replay/deduplication, disconnect recovery, and persistence
must prevent silent loss and repeated grants. Define crash recovery rather
than assuming acknowledgement alone provides exactly-once delivery. Perform
game inventory changes on the appropriate game/server thread.
