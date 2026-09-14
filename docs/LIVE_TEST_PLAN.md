# HyFuse Live Acceptance Test Plan — hyfuse-1.0.0.jar (84 tools)

> **Status:** READY 2026-09-14 — plan for the first live client run against
> build `13fae95` (jar 565,776 bytes). This closes the live-verification
> TODOs from the T4.4–T4.10 records: `use-item-on-block`, `entity-interact`,
> `bucket-fluid`, `farm-plot`, and `villager-trade` have never executed
> against a real client — the smoke suites are headless and pin only
> registry/dispatch/OpenAPI shape.
>
> **Method:** the operator drives tool calls one at a time (invariant §7.7:
> MCP calls to a running client are sequential-only — never fire two
> concurrent calls). Probe from a terminal on the same machine:
> `python3 tools/tool_probe.py --host 127.0.0.1 <tool> '<json>'`, or raw
> curl JSON-RPC per QUICKSTART §2. Record every row in §14.
> Budget: 60–90 min for the full pass; phases are independent after A+B.

## 1. Pre-flight (before launching)

- [ ] Back up any existing `config/hyfuse/` from a previous HyFuse install
      (jar swap wipes journals + memory — invariant §7.8). Fresh install
      has nothing to lose.
- [ ] `mods/` contains: `hyfuse-1.0.0.jar` + Baritone (26.2 build) +
      Meteor Client (26.2 build).
- [ ] Launch the client with no special flags; MCP boots on
      `http://127.0.0.1:25581/mcp` (loopback, no token).
- [ ] Console/terminal open on the same machine for probes; screenshot tool
      ready; keep the client log window findable for FAIL evidence.

## 2. World & staging setup

Singleplayer world, **Survival, cheats ON**, flat-ish plains, `/time set
day`, `/weather clear`. Peaceful ON until Phase G (real mobs needed there).
Everything staged within ~20 blocks of a marked "post" coordinate — write
the post x/y/z down; several tests reference it.

### 2.1 Player inventory (stage via /give or creative pre-stock)

| Item | Qty | Used by (phase) |
|---|---|---|
| flint_and_steel | 1 | D5 portal ignition — **P1 headline** |
| obsidian | 14 | D5 frame rebuild if needed |
| oak_lever | 2 | D3 lever toggle |
| oak_door | 1 | D4 door toggle |
| cobblestone | 64 | D2 place-block, I2 deposit, crafting fallback |
| oak_planks | 32 | H crafting |
| crafting_table | 1 | H 3×3 craft (or use the placed one) |
| furnace | 1 | H smelt (or use the placed one) |
| coal | 16 | H smelt fuel, J torches |
| raw_iron | 8 | H smelt feedstock (guaranteed, no mining dependency) |
| sticks | 4 | H pickaxe |
| wooden_axe, stone_shovel, stone_pickaxe, stone_sword | 1 ea | D1 autoTool, G7 combat |
| hoe (any tier) | 1 | F1 till |
| wheat_seeds | 8 | F3 plant |
| bone_meal | 8 | F4 fertilize |
| wheat | 12 | G2 breed cows, G6 farmer trade |
| bones | 8 | G4 wolf taming (probabilistic — bring plenty) |
| bucket | 2 (one kept empty) + water_bucket 1 | E fill/place, G3 milk |
| emeralds | 12 | G6 villager trade payment |
| bread | 6 | H7 eat-food (food bar must be < full first) |
| torch | 32 | J torchEvery, general |
| lead | 1 | optional G animal leading |

### 2.2 Nearby blocks, containers, entities (within ~20 blocks of post)

1. **Unlit nether portal frame** — obsidian 4×5 (2×3 interior), ≥8 blocks
   from everything else. D5.
2. **Infinite water pool** (2×2 dug + filled) with a solid block face free
   beside it, and a short-grass (replaceable) spot nearby. E.
3. Optional, CAUTION: **1-block lava source pit**, contained, away from
   wood/portal. E5.
4. **Wall with a lever mounted + oak door in a 1×2 doorway.** D3/D4.
5. **Chest "base"** pre-stocked (cobblestone ×64, bread ×12, wheat ×16) +
   an **empty barrel** beside it. I.
6. **Furnace** placed (empty) and **crafting table** placed. H.
7. **Bed under a roof** (skylight blocked, mob-safe). K2.
8. **5×5 grass/dirt farm patch** with a water source at its edge
   (hydrated), plus a second dry patch 10+ blocks from any water. F.
9. **Cow pen** (5×5 fence) with ≥2 adult cows close together. G2/G3.
10. **Untamed wolf** penned or tied nearby. G4.
11. **Villager** in a pen **with a composter** (locks farmer profession —
    wheat/emerald trades). G5/G6.
12. **Iron-ore wall**: 3–4 `iron_ore` blocks in an exposed wall ≤30 blocks
    from post. B5, J1/J2.
13. A couple of oak trees/logs. Optional J chop-tree.
14. A **closed zombie pit** ≥15 blocks away (spawn via egg, peaceful OFF
    for it). G7/J5.

## 3. Phase A — connectivity & protocol sanity (5 min)

| # | Test | Probe | Expected |
|---|---|---|---|
| A1 | MCP boot | `curl http://127.0.0.1:25581/mcp/openapi.json \| head -c 200` | 200, spec JSON |
| A2 | OpenAPI spec sanity | `curl .../openapi.json \| jq '.paths \| length'` | **84** tool paths |
| A3 | JSON-RPC tools/list | tool_probe `tools/list` via raw curl | `tools:[...]` present |
| A4 | tools/list count | count entries | **84** |
| A5 | OpenAPI door call | `POST /mcp/tools/get-world-time -d '{}'` | ok, day/time fields |

## 4. Phase B — sensing & telemetry (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| B1 | Combined snapshot | `get-agent-snapshot` | position ≈ post, vitals, inventory, entities, world time |
| B2 | Events present | `get-events` | version ≥ 0, no unfiltered surprise |
| B3 | Block detail | `get-block-info` on the chest | name `chest`, container flags, hardness |
| B4 | Batch blocks | `get-blocks` [post, portal frame corner, water pool] | chest / obsidian / water names |
| B5 | Find blocks | `find-blocks blockType=iron_ore maxDistance=32 count=5` | the staged iron wall positions |
| B6 | Raycast | `raycast-look` (aim at portal) | entity=none, block=obsidian |
| B7 | Find entity | `find-entity type=cow maxDistance=32` | a staged cow, id recorded |
| B8 | Scan entities | `scan-nearby-entities radius=32 filter=passive` | cows, wolf, villager listed |
| B9 | Capabilities | `get-capabilities` | **meteorPresent:true, baritonePresent:true**, feature flags |
| B9b | (no Baritone/Meteor installed) | same | presence keys false/absent — graceful, Ring-2 hidden |
| B10 | Chat read | `read-chat count=5` | ok (local chat history) |

## 5. Phase C — navigation & movement (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| C1 | Exact goto + arrival gate | `goto-coords` (30 blocks from post) | arrive within ~2.5 blocks, `#stop` |
| C2 | Profile set | `set-movement-profile allowSprinting=false` | ok, canSprint reported |
| C3 | Stuck recovery | box the player in 2-high dirt (creative), `recover-stuck` | digs out, moves clear, restores profile |
| C4 | Water escape reflex | stand in 2-deep water, drain oxygen (or simulate low O2), `escape-water` | moves to dry cell, WATER_ESCAPE event in `get-events` |
| C5 | Explore (Ring-2) | `explore maxRadius=128 maxChunks=4` | background process, newly cached chunks/notables |
| C6 | Cancel | `cancel-current-action` during C5 | stops cleanly, returns cancelled kind |

## 6. Phase D — blocks & building (15 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| D1 | Auto-tool dig | `dig-block` on a staged log, autoTool=true | axe auto-equipped, tool used + durability in result |
| D2 | Place + family | `place-block` cobblestone at free spot, `block=oak_planks` with planks held (family resolution) | resolved-block reported, block placed |
| D3 | Lever toggle | `use-item-on-block` on lever, faceDirection=up, no item | blockAfter powered flips, interactionResult consumed |
| D4 | Door toggle | `use-item-on-block` on oak_door | open=true state change visible |
| D5 | **P1: portal ignition** | `use-item-on-block` item=flint_and_steel on the interior bottom obsidian | **portalIgnited:true**, portal blocks appear (verify in F3 screen!) |
| D6 | Torch | `place-torch` (torch in inv) | torch placed near player |
| D7 | Use-on-block face sweep | repeat D3 with all six faceDirections | PASS results when face visible/reachable, consistent mapping |

## 7. Phase E — bucket-fluid (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| E1 | Auto-find fill | `bucket-fluid action=fill fluid=water` (no x/y/z) | bucket→water_bucket, source gone or refillable from infinite pool |
| E2 | Explicit fill | `bucket-fluid action=fill fluid=water x/y/z` of pool cell | filled at the exact source |
| E3 | Place vs face | `bucket-fluid action=place` against pool-side solid block face | fluid lands at relative(face), fluidAfter=water |
| E4 | Place on replaceable | `bucket-fluid action=place` on short-grass pos | fluid at the pos itself |
| E5 | Lava fill (CAUTION) | `bucket-fluid action=fill fluid=lava` on staged pit | filled; **do not place** it back casually |

## 8. Phase F — farm-plot (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| F1 | Till hydrated | `farm-plot action=till` on wet patch grass | farmland, `blockAfter` |
| F2 | Till dry | same on dry patch | farmland (dry) — behavior noted |
| F3 | Plant | `farm-plot action=plant item=wheat_seeds` on F1 farmland | wheat crop, age 0 |
| F4 | Fertilize | `farm-plot action=fertilize` on F3 crop | age increases (bonemeal) |
| F5 | Harvest | `farm-plot action=harvest` on a `/setblock` aged wheat (age=7) | crop broken, drops fall; then `collect-drops radius=4` |
| F6 | Crop state report | `get-block-info` on remaining crop | age/maxAge fields visible |

## 9. Phase G — entity-interact & villager-trade (15 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| G1 | Resolve by id vs name | `entity-interact entityId=<B7 id>` vs `entityName=cow` | same target both ways |
| G2 | **P2: breed cows** | `entity-interact entityName=cow item=wheat` on 2 cows fed within 5 min | **inLove:true** on both, hearts, baby follows |
| G3 | Milk a cow | `entity-interact entityName=cow item=bucket` (empty) | milk_bucket in inventory (compositional win) |
| G4 | Tame wolf | `entity-interact entityName=wolf item=bone` up to ×8 attempts | tamed=true eventually (probabilistic) |
| G5 | **P3: trade list** | `villager-trade action=list entityName=villager` | offers with costA/result, farmer wheat↔emerald trades present |
| G6 | **P3: execute trade** | `villager-trade action=trade tradeIndex=<wheat→emerald>` with wheat in inv | emerald gained, wheat consumed, offer repeat-counted, container closed |
| G7 | Attack & collect | peaceful OFF; `attack-entity entityName=zombie strategy=melee` then `collect-drops` | zombie dies, rotten flesh collected |

## 10. Phase H — crafting, smelting, gear (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| H1 | Can-craft | `can-craft itemName=iron_pickaxe` | false (no ingots yet) — honest |
| H2 | Deps resolve | `craft-with-deps item=iron_pickaxe` | subCrafts trail, shortfall incl. raw_iron→ingot smelt leaf |
| H3 | Smelt | `smelt-item` at staged furnace: raw_iron ×8, fuel coal ×2, takeOutput=true | iron_ingot ×8 in inventory |
| H4 | Craft | `craft-item outputItem=iron_pickaxe` at table | pickaxe crafted (uses H3 ingots + sticks) |
| H5 | Craft queue-safe 3×3 | `craft-item outputItem=furnace` with tableX/Y/Z named | opens table, crafts, closes |
| H6 | Gear up | `auto-equip-best-gear` | iron pickaxe equipped to hotbar hand |
| H7 | Eat | drain food bar (sprint/jump 1 min), then `eat-food foodName=bread` | food level rises, minCount honored |

## 11. Phase I — inventory, containers & memory (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| I1 | List inventory | `list-inventory` | full staged inventory, durability on tools |
| I2 | Deposit | `deposit-items name=cobblestone count=32` (nearest chest) | moved=32, honest remaining |
| I3 | Withdraw | `withdraw-items name=wheat count=8` from chest | moved=8, partial-count honesty if chest holds less |
| I4 | Out-of-reach guard | `open-container` on a chest 10+ blocks away | `out_of_reach` error, no walk |
| I5 | Memory round-trip | `memory-save id=base kind=poi note=post` + `memory-read` | record persisted + readable |
| I6 | Journal | `journal-append text="live test session"` section=9 | ok, then `journal-read` shows it |
| I7 | Move/organize | `move-item sourceSlot=10 destSlot=40` + `organize-inventory strategy=compact` | honest slot reporting |

## 12. Phase J — processes, queues, policies (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| J1 | Mine composites | `mine-blocks block=iron_ore count=2 maxDistance=48` | 2 ore mined, inventory delta, report |
| J2 | Linear queue | `enqueue-tasks` [goto iron wall → dig-block → collect-drops] | sequential execution, per-task results |
| J3 | Standing process | `standing-start name=ironrun type=mine-and-deposit` (base chest) | cycles run, deposits at chest |
| J4 | Standing status/stop | `standing-status`, then `standing-stop wait=true` | RUNNING → STOPPED, totals preserved |
| J5 | Guard | `guard-area` at post radius=8 (zombie pit open) | threats engaged, returns to post |
| J6 | Policy | `policy-save` food<14 → eat; drain food; verify firing via `policy-read` audit | audit row shows the eat action |
| J7 | KillAura toggle | `toggle-meteor-module module=kill-aura action=enable` / disable | toggles; `list-meteor-modules` confirms state |

## 13. Phase K — agent loop & chat boundary (10 min)

| # | Test | Tool (args) | Expected |
|---|---|---|---|
| K1 | Agent lifecycle | `/hyfuse set goal "craft 2 torches"` (local cmd), `agent-status` | iterations climb, toolCallsTotal > 0, completes |
| K2 | Sleep | `sleep-in-bed` (staged bed, night) | night skipped, spawn reset (verify `/time` after) |
| K3 | Ring-1 exposure | during K1, confirm loop only sees Ring-1 (34) tools | no anti-list tools in its tools/list |
| K4 | Chat guard | `send-chat message` twice within 3s | second REJECTED with CHAT_REJECTED (rate limit) |
| K5 | Chat slash passthrough | `send-chat message=/time set day` | server command executed, time changes |

## 14. Results ledger

| Phase | Pass | Fail | Notes / evidence |
|---|---|---|---|
| A connectivity | — | — | |
| B sensing | — | — | |
| C navigation | — | — | |
| D blocks (incl. P1 portal) | — | — | |
| E fluids | — | — | |
| F farming | — | — | |
| G entities (incl. P2 breed, P3 trade) | — | — | |
| H craft/smelting | — | — | |
| I containers/memory | — | — | |
| J processes/queues/policy | — | — | |
| K agent loop/chat boundary | — | — | |

**Priority tests if time is short:** D5 (portal), G2 (breed), G5+G6
(trade), F1–F5 (farm loop), E1+E3 (bucket), A2+A4 (pins). These are the
five live-verification TODOs never yet exercised on a real client.

## 15. Defect protocol

- Any FAIL: capture the raw JSON-RPC response + client log line + a
  screenshot; note the row id (e.g. D5) — these map 1:1 to GitHub issues.
- A phase FAIL does not block the next phase unless the same subsystem is
  implicated (A/B failures DO block: everything depends on connectivity
  and sensing).
- Smoke-suites in the sandbox are NOT re-run for live findings — live
  findings are code-defect candidates, verified by reading the handler,
  not by re-running headless suites.
- After the session: file each FAIL as a numbered defect in the live-test
  record section of the handover (§19), with phase row, evidence, and
  suspected handler.
