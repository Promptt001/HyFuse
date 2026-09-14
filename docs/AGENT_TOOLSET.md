# HyFuse Agent Toolset Manifest (Tier-1)

> **Status:** PROPOSAL — awaiting operator sign-off before AgentLoop implements it.
> **Purpose:** define exactly which of the 79 registered tools the *embedded
> agent loop* (`AgentLoop` → `toolsForApi()`) exposes to the LLM. The full
> 79-tool surface remains untouched on the MCP and OpenAPI doors (operator
> surface). This is the "routing layer" answer to the over-tooled-agent
> problem: research shows selection accuracy degrades measurably past
> 15–20 tools; HyFuse currently sends all 79 unfiltered.
>
> **Rule of thumb:** the agent gets the **deliberative** surface. Reflexes
> (policies), repetition (standing processes), and manual real-time control
> (steering primitives) are deliberately *not* the LLM's job — they are
> either background machinery or operator/Baritone territory.

## Ring 1 — Core agent set (default exposure)

The day-1 → month-1 survival loop, validated by the new-player thought
exercise (see HANDOVER §18). Every tool here earns its slot through a
concrete recurring goal-task.

### Sense & orient (5)
| Tool | Earns its slot by |
|---|---|
| `get-agent-snapshot` | The one-call brain refresh; absorbs position/vitals/inventory/mobs/time/weather |
| `get-events` | Reactive attention: damage, spawns, weather the snapshot missed |
| `find-blocks` | Target acquisition for nearly every gather task |
| `find-ore-veins` | Mining economy: grouped veins with exposure info |
| `scan-nearby-entities` | Threat/food/target assessment |

### Navigate (2)
| Tool | Earns its slot by |
|---|---|
| `goto-coords` | **The** travel verb — Baritone `#goto` + arrival gate. Blessed by the goal-seed MOVEMENT directive |
| `recover-stuck` | The reflex the agent may invoke itself when boxed in |

**Deliberately ONE nav verb.** `navigate-v2` (profiles, goal taxonomy,
stall detection) stays operator-only. The goal seed should teach
`goto-coords` alone; `navigate-v2`'s knobs are tuning, not deliberation.

### Act on the world (9)
| Tool | Earns its slot by |
|---|---|
| `get-to-block` | Walk-to-target-block as a background process |
| `mine-blocks` | Bulk gather — the backbone of every resource goal |
| `dig-block` | Single-block precision work (shelter, clearance) |
| `place-block` | Building primitive (materialPalette handles substitution) |
| `build-structure` | Project-scale construction from blueprints |
| `attack-entity` | Hunting/threat removal (best-weapon auto-equip) |
| `collect-drops` | Turning kills/mines into inventory |
| `eat-food` | Hunger reflex the agent triggers deliberately |
| `sleep-in-bed` | Night skip — a whole day-1 goal in one call |

### Craft & process (4)
| Tool | Earns its slot by |
|---|---|
| `craft-item` | The make verb |
| `can-craft` | Pre-flight check; avoids wasted-craft iterations |
| `craft-with-deps` | Recipe-tree understanding for multi-step crafting |
| `smelt-item` | Ore → ingot; required by the iron/diamond loop |

### Inventory & gear (6)
| Tool | Earns its slot by |
|---|---|
| `list-inventory` | What do I have (post-gather, pre-craft) |
| `auto-equip-best-gear` | One-call gear-up after tier upgrades |
| `equip-item` | Deliberate tool/weapon selection |
| `open-container` | Base storage access |
| `deposit-items` | Haul-home-and-store half of the mining loop |
| `withdraw-items` | Retrieve half |

### Autonomy & memory (10)
| Tool | Earns its slot by |
|---|---|
| `memory-save` / `memory-read` | Persistent facts (base, hazards, waypoints) |
| `journal-read` / `journal-append` | Session continuity, §8 plans, §9 event log |
| `get-playbook` | Re-read the rules when unsure |
| `policy-save` / `policy-read` | **Install a reflex** — shrink future deliberation |
| `standing-start` / `standing-status` | Run goal cycles without LLM round-trips |
| `get-capabilities` | Startup self-model (also drives gating, below) |

### Supervision & meta (5)
| Tool | Earns its slot by |
|---|---|
| `get-current-action` | Watch a running composite |
| `cancel-current-action` | The agent's own abort switch |
| `enqueue-tasks` | Linear multi-step plans |
| `agent-status` | Self-observation for the loop |
| `get-last-death` | DeathMemory: don't die to the same ravine twice |

**Ring 1 total: 41** — still above the 15–20 comfort band, but every entry
is load-bearing for the survival loop; further cuts would remove real
capability, not distractors. (Sensing composites could eventually shrink
this: e.g. if `scan-nearby-entities`+`find-blocks` merged into snapshot
follow-ups.)

## Ring 2 — Conditional (exposed only when a capability is true)

| Tool | Gate (new capability key) |
|---|---|
| `toggle-meteor-module`, `list-meteor-modules`, `set-meteor-keybind` | `meteorPresent` (Meteor Client loaded) |
| `guard-area` | `guardProcess` AND (`meteorPresent` OR accept melee-only guarding) |
| `flee-from` | `baritonePresent` |
| `explore`, `follow-player`, `fly-to` | `baritonePresent` (movement composites) |

Note: `get-capabilities` today reports *feature flags*, not *runtime
presence*. Task-2 implementation adds presence keys (`meteorPresent`,
`baritonePresent`) to that map; the filter consults them, and the agent's
startup `get-capabilities` call doubles as discovery.

## Anti-list — never exposed to the agent loop (30, verified)

Grouped by reason; **none are deleted** — all remain on the MCP/OpenAPI
operator doors.

- **Eaten by composites:** `get-world-time`, `get-weather`, `get-block-light`,
  `detect-gamemode` (snapshot covers time/weather/light context; gamemode is
  a one-time operator curiosity)
- **Manual real-time steering — LLMs are bad at this, Baritone is better:**
  `move-in-direction`, `look-at`, `raycast-look`, `path-safely`,
  `set-movement-profile`
- **Duplicate nav/follow verbs:** `navigate-v2`, `follow-entity`,
  `find-safe-location` (reflex territory: policies + `goto-coords` away)
- **Rare deliberate surveys = operator diagnostics:** `get-block-info`,
  `get-blocks`, `scan-area`, `scan-volume`, `find-entity`, `find-item`
- **Inventory micro-management = operator/troubleshooting:**
  `move-item`, `organize-inventory`, `resolve-material`
- **Reflex/install-once machinery:** `escape-water` (auto + policy),
  `policy-forget`, `standing-stop` (handled via `standing-status` +
  supervisor; keep the operator door)
- **Social/chat — trust boundary (playbook §2):** `send-chat`, `read-chat`
- **Operator-only lifecycle:** `agent-start`, `agent-stop` — the operator's
  reins on the loop itself (never let the agent turn itself off-and-on or
  spawn loops)
- **Destructive-forget primitives:** `memory-forget`, `policy-forget`,
  `standing-stop` (operator-gated; agent uses `standing-status` and the
  supervisor instead)
- **Misc operator conveniences:** `place-torch` (place-block + materialPalette
  covers it), `resolve-material` (place-block does this internally)

Verified membership (79 = 41 + 8 + 30): `get-block-info`, `get-blocks`,
`get-block-light`, `scan-area`, `scan-volume`, `raycast-look`,
`find-entity`, `get-world-time`, `get-weather`, `detect-gamemode`,
`navigate-v2`, `path-safely`, `find-safe-location`, `follow-entity`,
`move-in-direction`, `escape-water`, `set-movement-profile`, `look-at`,
`place-torch`, `resolve-material`, `find-item`, `move-item`,
`organize-inventory`, `standing-stop`, `memory-forget`, `policy-forget`,
`agent-start`, `agent-stop`, `send-chat`, `read-chat`.

## Prompt contract changes (for task 2)

1. Goal-seed MOVEMENT directive names **`goto-coords` only** (drop
   `/ navigate-v2`).
2. Seed adds: "Many tools are intentionally hidden from you. If a task seems
   impossible with your toolset, achieve it differently — never assume a
   missing tool exists."
3. `toolsForApi()` = Ring 1 ∪ gated Ring 2, order stable (registry order),
   so token cost and prompt layout are deterministic.

## Governance

- This manifest is **hand-maintained** (unlike TOOL_CATALOG) — it is policy,
  not generated structure. Changes require: update this doc → update the
  AgentLoop filter constant → `AgentLoopTest` pins the new set → 11/11 gate.
- The 79-tool MCP/OpenAPI pin (§7 invariant 1) is **untouched** by all of
  this; `Smoke2`/`OpenApiTest` keep guarding the operator surface.
