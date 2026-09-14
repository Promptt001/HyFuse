# HyFuse Agent Toolset Manifest (Tier-1)

> **Status:** RATIFIED — lean set (operator 2026-09-14), trimmed from 41 → 24
> to sit near the 15–20 accuracy band. Implemented in `AgentLoop` (T4.2).
> The full registry (now 84 tools) stays untouched on the MCP/OpenAPI doors
> (operator surface). Selection accuracy degrades measurably past 15–20
> tools; HyFuse previously sent all 79 unfiltered.
>
> **Rule of thumb:** the agent gets the **deliberative** surface. Reflexes
> (policies), repetition (standing processes), and manual real-time control
> are deliberately *not* the LLM's job.

## Ring 1 — Lean agent set (34 tools, default exposure)

### Sense & orient (3)
- `get-agent-snapshot` — one-call brain refresh; absorbs 4 standalone tools
- `get-events` — reactive attention (damage/spawns/oxygen)
- `find-blocks` — target acquisition for nearly every gather task

### Navigate (2)
- `goto-coords` — the sole blessed travel verb (Baritone #goto + arrival gate)
- `recover-stuck` — the reflex the agent may invoke itself when boxed in

### Act on the world (11)
- `mine-blocks` — bulk gather backbone
- `dig-block` — single-block precision (shelter, clearance)
- `place-block` — building primitive (materialPalette substitution)
- `attack-entity` — hunting/threat removal (kills but never picks up)
- `entity-interact` — entity right-click: breed/tame/lead/trade (T4.5)
- `bucket-fluid` — water/lava/powder-snow: fill or place (T4.6)
- `farm-plot` — till/plant/harvest/fertilize crops (T4.8)
- `villager-trade` — list offers + execute trades (T4.10)
- `collect-drops` — turns kills/mines into inventory (load-bearing)
- `eat-food` — hunger reflex triggered deliberately
- `sleep-in-bed` — skip the night, reset spawn (T4.9 re-add)

### Craft & process (4)
- `craft-item` — the make verb
- `can-craft` — pre-flight check, avoids wasted iterations
- `smelt-item` — ore→ingot, required by the iron/diamond loop
- `craft-with-deps` — recursive recipe trees (T4.9 re-add)

### Inventory & gear (4)
- `list-inventory` — what do I have (post-gather, pre-craft)
- `auto-equip-best-gear` — one-call gear-up after tier upgrades
- `deposit-items` — haul-home-and-store half of the mining loop
- `withdraw-items` — restock from base chests (T4.9 re-add)

### Autonomy & memory (6)
- `memory-save` / `memory-read` — persistent facts (base, hazards, waypoints)
- `journal-append` — session continuity, event log (T4.9 re-add)
- `policy-save` — install-a-reflex; shrinks future deliberation
- `standing-start` — run goal cycles without LLM round-trips
- `standing-status` — observe installed reflexes (T4.9 re-add)

### Supervision & meta (3)
- `get-capabilities` — startup self-model; drives Ring-2 gating
- `enqueue-tasks` — linear multi-step plans
- `cancel-current-action` — the agent's own abort switch

**T4.9 re-adds (2026-09-14):** `sleep-in-bed`, `craft-with-deps`,
`withdraw-items`, `journal-append`, `standing-status` — moved from the
anti-list into Ring-1 to make the next release substantial. 33 total.

## Ring 2 — Conditional (exposed only when a capability is true)

| Tool | Gate (new capability key) |
|---|---|
| `toggle-meteor-module`, `list-meteor-modules`, `set-meteor-keybind` | `meteorPresent` |
| `guard-area` | `guardProcess` (melee fallback works without Meteor) |
| `flee-from`, `explore`, `follow-player`, `fly-to` | `baritonePresent` |
| `scan-nearby-entities`, `find-ore-veins` | `worldCache` (sensing gates) |

`get-capabilities` gains presence keys `meteorPresent` / `baritonePresent`
(T4.2); the filter consults them and the agent's startup capabilities call
doubles as discovery. Capabilities map changes are additive — no count pin
exists there, so no Smoke2 change is needed.

## Anti-list — never exposed to the agent loop

The trimmed-out tools join the anti-list *for the agent persona*; all remain
fully available on the MCP/OpenAPI operator doors (84-tool pin intact).

- **Eaten by composites:** `get-world-time`, `get-weather`, `get-block-light`, `detect-gamemode`
- **Manual real-time steering (LLM-inappropriate, Baritone better):** `move-in-direction`, `look-at`, `raycast-look`, `path-safely`, `set-movement-profile`
- **Duplicate nav/follow verbs:** `navigate-v2`, `follow-entity`, `find-safe-location`
- **Rare deliberate surveys / operator diagnostics:** `get-block-info`, `get-blocks`, `scan-area`, `scan-volume`, `find-entity`, `find-item`
- **Inventory micro-management:** `move-item`, `organize-inventory`, `resolve-material`, `equip-item`, `open-container`
- **Reflex/install-once machinery:** `escape-water`, `place-torch`, `standing-stop`, `policy-forget`
- **Social/chat — trust boundary (playbook §2):** `send-chat`, `read-chat`
- **Operator-only lifecycle:** `agent-start`, `agent-stop`
- **Destructive-forget primitives:** `memory-forget`
- **Deferred pending usage data:** `journal-read`, `get-playbook`, `get-current-action`, `agent-status`, `get-last-death` (re-add candidates above)

## Governance

- Hand-maintained policy doc (unlike TOOL_CATALOG). Changes require: update
  this doc → update the `AGENT_TOOLS` constant in `AgentLoop.java` →
  `AgentLoopTest` pins the set → 11/11 gate.
- The 84-tool MCP/OpenAPI pin (§7 invariant 1) is untouched; `Smoke2` /
  `OpenApiTest` keep guarding the operator surface.
