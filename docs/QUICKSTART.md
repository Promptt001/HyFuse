# HyFuse Quickstart

> **Goal of this page:** get from a vanilla Minecraft 26.2 client to a live
> agent tool call in ~10 minutes, then show the three ways to put a brain
> behind the tools. If you just want the 60-second version, the
> [README](../README.md) has it.

<img src="images/hero-banner.webp" alt="HyFuse quickstart" width="720">

## 0. What you need

- Minecraft **26.2** client with **Fabric Loader 0.19.5+** and **Fabric API**
- The HyFuse jar (from a release zip or `./gradlew build`)
- Optional but strongly recommended: **Baritone** (navigation) and
  **Meteor Client** (combat) builds for MC 26.2, in the same `mods/` folder
- Any MCP client — OpenWebUI, Claude Desktop, or plain `curl`

## 1. Install the mod

1. Drop `hyfuse-<version>.jar` into your client's `mods/` folder (with
   Baritone and Meteor if you have them).
2. Launch the client **with no special flags**. HyFuse boots the MCP server
   on `http://127.0.0.1:25581/mcp`, loopback-only, no token.
3. That's it. Join any world or server — the server runs alongside the game.

Verify it's alive from a terminal on the same machine:

```bash
python3 tools/tool_probe.py --host 127.0.0.1 get-agent-snapshot
# or
curl http://127.0.0.1:25581/mcp/openapi.json | head -c 200
```

You should see live data — position, health, inventory, nearby entities.

## 2. Call your first tool

With `curl` (JSON-RPC), no MCP client at all:

```bash
curl http://127.0.0.1:25581/mcp -H 'Content-Type: application/json' -d '{
  "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": { "name": "get-world-time", "arguments": {} }
}'
```

Or on the OpenAPI surface:

```bash
curl -X POST http://127.0.0.1:25581/mcp/tools/get-world-time -d '{}'
```

## 3. Attach a brain

### Option A — OpenWebUI (the reference integration)

1. In OpenWebUI, add a **tool server** of type **OpenAPI**.
2. URL: `http://<client-host>:25581/mcp`, spec path `openapi.json`.
3. Bearer token: whatever you set with `-Dhyfuse.token` (loopback-only setups
   don't need one — add a token if the client and OpenWebUI are on different
   machines, and set `-Dhyfuse.bind` to the interface OpenWebUI can reach).
4. Open a chat with a model that supports tool calling and ask:
   *"Take a snapshot of yourself and tell me where you are."*

If the model lists tools as text instead of calling them, the model isn't
tool-call-capable — pick a model with native function calling.

### Option B — any MCP client

Point your MCP client at `http://127.0.0.1:25581/mcp` (Streamable HTTP
transport). `tools/list` returns all 79 tools with full JSON Schemas.

### Option C — no brain at all: drive it yourself

The embedded agent loop needs `config/hyfuse/agent.json`:

```json
{ "url": "https://api.openai.com/v1/chat/completions",
  "apiKey": "sk-...", "model": "gpt-4o", "maxIterations": 8 }
```

Then in game, at the keyboard (these are local-only commands — they never
travel over MCP):

```
/hyfuse set goal "mine 10 iron ore and smelt it into ingots"
/hyfuse status
```

The loop decomposes the goal into tool calls, executes them, and reports.
`/hyfuse set agent_mode on` arms KillAura and the reactive defense rows
(auto-eat, flee at low health, water escape) while it works.

## 4. A first real task

Ask your agent this and watch it chain tools:

> "Craft an iron pickaxe. Mine the iron yourself if you don't have it, and
> report what you actually ended up with."

Expected tool flow: `get-agent-snapshot` → `craft-with-deps` (sees the
shortfall) → `find-ore-veins` / `mine-blocks` → `smelt-item` → `craft-item`
→ `get-agent-snapshot` to verify. HyFuse tools are designed to be chained —
`enqueue-tasks` can run the whole list as one server-side composite with a
single LLM round-trip.

## 5. Leave it working: standing processes

A chat shouldn't have to babysit a farm. `standing-start` runs goal cycles in
the background, depositing loot at your base chest:

```json
{ "type": "mine-and-deposit",
  "name": "iron-farm",
  "args": { "block": "iron_ore", "item": "raw_iron",
            "countPerCycle": 8, "chest": "base" } }
```

Cycles repeat with backoff on failure, park after three consecutive
failures, and pause on death. Check progress anytime:
`standing-status`, `standing-stop` to end it.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `curl` gets connection refused | Client not running, or you set `-Dhyfuse.port=0`. Default bind is loopback — from another machine you must set `-Dhyfuse.bind` **and** a token. |
| 401 / auth errors | Token mismatch between client flags and the MCP/OpenAPI client config. One key on both sides. |
| OpenWebUI "failed to verify" | Stale bearer key or the tool server's `config.enable` flag is off (OpenWebUI-side settings, not HyFuse). |
| Pathfinding tools fail | Baritone missing — HyFuse dispatches its `#goto`/`#mine` chat commands; without it, movement degrades gracefully. |
| Combat does nothing | Meteor Client absent — `toggle-meteor-module` reports `meteorInstalled: false`; built-in melee fallbacks apply. |
| Config wiped after a jar swap | Known: back up `config/hyfuse/` before upgrading (README warning). |

## Next steps

- [TOOL_CATALOG.md](TOOL_CATALOG.md) — every tool, every argument
- [CODEBASE_REFERENCE.md](CODEBASE_REFERENCE.md) — architecture, for studying or contributing
- [IMAGE_PROMPTS.md](IMAGE_PROMPTS.md) — how this repo's artwork was made
