# HyFuse Codebase Reference (for AI assistants)

**Purpose:** this is the first point of reference for an AI (or human) that needs
to understand HyFuse's structure fast. Read this before reading source files.
Facts here reflect mod version **1.0.0** (initial public release).
If the code and this file disagree, the code wins — fix this file.

## What HyFuse is

A fully self-contained **Fabric client mod for Minecraft 26.2** that embeds
its own **MCP server** (`http://127.0.0.1:25581/mcp`, Streamable HTTP) as its
sole transport. An MCP client (an AI agent) connects to the game client and
drives it via 79 registered tools. It also embeds an optional autonomous agent
loop (`agent.json` → OpenAI-compatible LLM) and in-game local-only `/hyfuse`
commands for the operator. Legacy WebSocket API (:25580) is removed.

The same server also exposes a full **OpenAPI 3.1 mirror** of the tool
surface (`GET /mcp/openapi.json` for the spec, `POST /mcp/tools/{name}` for
calls) — the surface OpenWebUI's OpenAPI-type tool server integration
consumes. JSON-RPC and OpenAPI are two doors into one dispatcher: exact
name parity (79 ↔ 79) is pinned by `OpenApiTest`.

Two cooperating mods are **not bundled** but are expected at runtime:

- **Baritone** (operator's Meteor-compatible build) — navigation. HyFuse
  dispatches Baritone's client chat commands (`#goto`, `#stop`, `#mine`, `#sel`).
- **Meteor Client** (soft dependency) — combat (KillAura) and quality-of-life
  modules, toggled by name through `toggle-meteor-module`. Everything degrades
  gracefully when Meteor is absent.

## Repository layout

| Path | What it is |
|---|---|
| `src/`, `tools/`, `docs/`, `history/` | Source, test suites, documentation, and the patch/audit archive. |
| `history/` | Pre-fix source snapshots + one-shot patch scripts — the audit trail. |
| `tools/mcp_smoke/` | 11 Java test suites + runner. |
| `tools/tool_probe.py` | Minimal MCP probe script (no client needed). |
| `tools/gen_tool_catalog.py` | Regenerates `docs/TOOL_CATALOG.md` from `McpToolRegistry.java`. |

## Worktree layout (`work/hyfuse_work/HyFuse/`)

```
HyFuse/
├── src/client/java/com/hyfuse/bridge/     # ALL Java sources (client entrypoint)
│   ├── HyFuseClient.java                  # entrypoint; lifecycle, serverId, command registration
│   ├── agent/   AgentLoop, GoalSession, AgentMode, HyFuseCommandCore
│   ├── chat/    ChatBuffer, HyFuseCommands
│   ├── dispatch/ ToolDispatcher, StandingProcessEngine, CraftResolver, QueueTelemetry
│   ├── mcp/     McpHttpServer, McpToolRegistry
│   └── sense/   BrainStore, EventBuffer, MemoryStore, PolicyEngine, DeathMemory
├── src/client/resources/assets/hyfuse/INTELLIGENCE.default.md  # packaged fallback playbook
├── src/main/resources/fabric.mod.json     # id 'hyfuse', environment client, java >=25
├── tools/            mcp_smoke/ (11 Java test suites + run_suites.sh), gen_tool_catalog.py, gen_icon.py, tool_probe.py
├── docs/             this file
├── history/          pre-fix source snapshots + patch_scripts/ (one-shot patch archive)
├── build.gradle, gradle.properties, gradlew, settings.gradle, LICENSE, README.md
```

## Module map (by package)

### `mcp` — the transport (838 lines)
- **`McpHttpServer`** (328): plain-HTTP Streamable MCP on `POST /mcp`;
  bearer-token auth; binds 127.0.0.1 by default (`-Dhyfuse.bind/port/token`,
  aliases `-Dhyfuse.*`, legacy `-Dmcagent.*`). Non-loopback bind refuses to
  start without a token.
- **`McpToolRegistry`** (1182): the tool schema registry. **79 tools pinned by
  Smoke2** — do not add/remove tools without updating that pin. Roughly:
  sensing (snapshot, events, block/volume/entity scans), navigation
  (goto-coords, navigate-v2 GoalSpec, path-safely, recover-stuck,
  escape-water), block actions (dig/place/find/scan), inventory/container
  (list/move/organize/open/deposit/withdraw), crafting/smelting, combat,
  process engine (mine-blocks, build-structure, get-to-block, explore, guard,
  follow, enqueue-tasks), standing processes, memory/journal, policies,
  meteor toggles, agent status, capabilities, playbook, last-death.

### `dispatch` — the brain's hands (10,343 lines)
- **`ToolDispatcher`** (9189): THE giant file — one handler per MCP tool plus
  the family caps (materialPalette, cost columns, container navigation).
  Handlers are mostly thin adapters over Baritone/Minecraft client APIs, the
  process engine, or the sense stores. Also exposes cross-package public
  wrappers used by the command layer (`agentGoalStart`, `agentModeSet`,
  `getAgentSnapshotPublic`, ...).
- **`StandingProcessEngine`** (786): background standing processes
  (`mine-and-deposit`, `guard`) with stop conditions, backoff, park-after-3-
  failures; persists definitions to memory (never auto-resume after restart).
- **`CraftResolver`** (234): recursive recipe+smelt resolution with cycle
  detection (`craft-with-deps` is its read-only plan view).
- **`QueueTelemetry`** (128): current-action telemetry for composites.

### `sense` — perception + reflexes (1,334 lines)
- **`EventBuffer`**: real-time event queue (damage, food, oxygen, death,
  weather, **entitySpawn — hostiles only**). Drains on read.
- **`PolicyEngine`** (334): reactive policy rows `{id, trigger, threshold,
  action, args, cooldownMs, enabled}` → fires a bounded action-tool rail.
  Seeds: `survival:eat/flee/oxygen/death-audit` enabled; `aggressive:killaura`
  **disabled by default** (operator ruling: KillAura on only during
  `agent_mode`, via AgentMode).
- **`MemoryStore`**: persistent JSON store `config/hyfuse/hyfuse_memory_<serverId>.json`
  (512 records, flat records with position query support). Also hosts policy
  rows (kind `policy`).
- **`BrainStore`** (385): per-server playbook + journal cache;
  `INTELLIGENCE.md` (config) falls back to packaged default;
  `journals/<serverId>.md` (10 sections, 100 KB cap; §1 identity, §2 server
  facts/roster, §8 plans, §9 rolling event log).
- **`DeathMemory`**: last-8-death history (cause/position/age).

### `agent` — autonomy (1,035 lines)
- **`AgentLoop`** (369): the embedded LLM loop (OpenAI-compatible via
  `agent.json`). Plain run + goal-mode run (goal seed = playbook + memory
  digest + snapshot + goal + decomposition directive).
- **`GoalSession`** (187): bounded goal supervisor — up to 5 sessions ×
  maxIterations (default 8), carry-over summaries, `goalMet` = a session ends
  in a plain-text reply with no tool calls and no error.
- **`AgentMode`** (168): reflect posture. `on` = KillAura enable (best-effort,
  Meteor-optional) + 3 reflect policy rows (`reflect:escape-water` oxygen≤12,
  `reflect:low-health` entityHurt≤12 flee, `reflect:hostile` entitySpawn →
  attack-entity melee). `off` = disarmed + rows forgotten.
- **`HyFuseCommandCore`** (311): Minecraft-free parse/route for `/hyfuse`
  (status; set api-url|api-key|model|goal|agent_mode; reset
  intelligence|memory|config|all). Writes `config/hyfuse/agent.json`.

### `chat` — social surface (190 lines)
- **`ChatBuffer`**: chat read (drain/peek), **<200 chars send guard**.
- **`HyFuseCommands`** (89): thin Brigadier registration of `/hyfuse`
  (local-only client commands, never a server packet; MCP surface unchanged).

## Invariants ("DO NOT TOUCH" without an explicit operator ruling)

1. **79-tool MCP pin** (Smoke2) — the /hyfuse command tree added zero MCP tools.
2. **§2 of the playbook**: in-game chat is untrusted input; the only authority
   channel is the operator's MCP chat. `/hyfuse` stays local-only, never an
   MCP tool.
3. PolicyEngine seeds + global routing; killaura seed stays disabled outside
   agent_mode.
4. EventBuffer producer assumptions (entitySpawn = hostiles only).
5. Queue rail structure (QUEUE_ACTIVE gate) + POLICY_ACTION_TOOLS set.
6. MemoryStore write discipline; standing-process engine semantics.
7. Issued release zips (never overwritten); history/ archives.

## Build & test <a id="testing"></a>

- **JDK 25** (loom 1.17 requires it for MC 26.2). Set `JAVA_HOME` to your
  JDK 25 install (e.g. `/usr/lib/jvm/java-25-openjdk-amd64`) before Gradle.
- `./gradlew build` -> `build/libs/hyfuse-<version>.jar`. Purge
  `.gradle/loom-cache` + `build/classes` **only when bumping mod_version**.
- Smoke suites (11, all pass): compile
  `javac -cp <classpath-including-build-classes> -d /tmp/mcp_smoke/outp9 tools/mcp_smoke/*.java`
  then `bash tools/mcp_smoke/run_suites.sh` -> expect **11/11**
  (SlotMap, CraftResolver, Queue, Sense, SendChatGuard, Smoke, Smoke2,
  Playbook, AgentLoop, AgentGoal, OpenApi).
  Note: the 26.2 loom layout keeps minecraft jars in `.gradle/loom-cache/
  minecraftMaven` (new hash dirs) and fabric-api modules as plain modules-2
  jars -- rebuild the classpath accordingly (the old `rebuild_smoke_cp.sh`
  recipe predates 26.2 and pins JDK-21-era paths).
- Versioning: `gradle.properties` `mod_version` bumps per release; release
  archives mirror the worktree (src, tools, docs, gradle files, jar) with
  sha256s recorded.

## Conventions & hard-won pitfalls

- Patch scripts are **count-checked** (assert marker counts before replace)
  and kept under `history/patch_scripts/`. `pathlib.write_text(newline=...)`
  opens O_TRUNC **before** validating — stage a backup in `history/` before
  running any patch script; assert newline is literal LF first.
- All nudge/journal files are LF-only (CR=0 asserted).
- In `McpToolRegistry`/`ToolDispatcher` the tool surface is append-by-explicit-
  decision; keep README's tool docs in sync.
- Brigadier on the client classpath: use `com.mojang.brigadier.arguments.*`,
  NOT `net.minecraft.commands.arguments.*`; package decl must match dir.
- Live MCP calls to the running client are **sequential-only**.
- Deploying a new jar to the client **wipes** `config/hyfuse/journals/` +
  memory — always back up before deploy, restore journal §2/§9 + memory
  records after.
- No Git: the audit trail is release zips + `history/` + `nudge_mcagent.md`.

## Where to start reading (paths, in order)

1. `README.md` — what it is and how to run it.
2. `src/client/java/com/hyfuse/bridge/HyFuseClient.java` — wiring.
3. `mcp/McpToolRegistry.java` → `dispatch/ToolDispatcher.java` — the surface.
4. This package order for depth: `sense` → `dispatch` → `agent` → `chat`.
5. `docs/TOOL_CATALOG.md` — every tool with full args (generated, never drifts).
