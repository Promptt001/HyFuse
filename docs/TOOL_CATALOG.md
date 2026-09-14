# HyFuse Tool Catalog

> All **84 tools** of the HyFuse MCP surface, generated directly from
> `McpToolRegistry.java` by `tools/gen_tool_catalog.py` — this file never
> drifts from the code. Each entry: what it does, its arguments, and when
> an agent should reach for it.

<img src="images/tool-map.png" alt="The 79 tools grouped by category" width="720">

## Sensing & world <a id="sensing--world"></a>

Perception: everything the agent uses to build a picture of the world and itself before acting. Read-only, parallel-safe.

| Tool | Arguments | Description |
|---|---|---|
| [`get-agent-snapshot`](#get-agent-snapshot) | `detail` <sub>enum</sub>, `mapRadius` <sub>int</sub>, `includeEntities` <sub>bool</sub>, `includeInventory` <sub>bool</sub>, `includeMovement` <sub>bool</sub>, `includeDeath` <sub>bool</sub> | Combined fast sensing: one call returns position, vitals (health/food/oxygen), inventory counts, nearby hostiles/animals/players/drops, world time+weather, AND movement telemetry. Replaces separate get-position + get-vitals + get-world-time + get-weather + list-inventory + scan calls. Each… |
| [`get-block-info`](#get-block-info) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Get rich information about a block at the specified position: hardness, tool type/tier, estimated break time with the bot's current best tool, drops, solid/passable flags, light levels, and block states (facing, open, powered, age, waterlogged). |
| [`get-blocks`](#get-blocks) | `positions` <sub>array</sub> | Batch: get the block name at many positions in one call (survey a footprint/area). Cells in unloaded chunks report name='unloaded' (distinct from air). |
| [`get-block-light`](#get-block-light) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Get the light levels (block light and sky light) at a position. Hostile mobs spawn at light level 0. |
| [`find-blocks`](#find-blocks) | `blockType` <sub>string</sub>, `maxDistance` <sub>number</sub>, `count` <sub>int</sub> | Find one or more nearby blocks of a specific type |
| [`find-ore-veins`](#find-ore-veins) | `oreType` <sub>string</sub>, `maxDistance` <sub>number</sub>, `maxVeins` <sub>int</sub>, `exposedOnly` <sub>bool</sub> | Find ore veins (or any tracked block's connected groups) near the bot via a 26-connectivity flood-fill over the WorldCache's hits + a fresh local scan. Returns [{type, center, count, exposed, nearestStand}] nearest-first. exposedOnly filters to veins with a face-adjacent air cell. maxVeins caps… |
| [`scan-area`](#scan-area) | `radius` <sub>int</sub>, `yRange` <sub>int</sub>, `blockFilter` <sub>string</sub> | Survey the blocks around the bot: block type counts, hazards (lava, fire, cactus,...), and optional filter matches |
| [`scan-volume`](#scan-volume) | `x1` <sub>int</sub>, `y1` <sub>int</sub>, `z1` <sub>int</sub>, `x2` <sub>int</sub>, `y2` <sub>int</sub>, `z2` <sub>int</sub>, `resolution` <sub>enum</sub> | Scan a box (x1,y1,z1)→(x2,y2,z2) into a compact 2-bit volumetric grid (AIR/SOLID/WATER/AVOID) plus a notableBlocks list of tracked ores/logs/containers. Caps at 32x16x32 cells. `compressed` returns a base64 2-bit grid for spatial reasoning; `block` returns the raw per-cell classes for small… |
| [`raycast-look`](#raycast-look) | `maxDistance` <sub>number</sub> | Raycast from the bot's eyes to find the block and/or entity it is currently looking at |
| [`find-entity`](#find-entity) | `type` <sub>string</sub>, `maxDistance` <sub>number</sub> | Find the nearest entity of a specific type |
| [`scan-nearby-entities`](#scan-nearby-entities) | `radius` <sub>number</sub>, `filter` <sub>string</sub>, `maxCount` <sub>int</sub> | Scan all entities near the bot and return a structured list with position, distance, hostility, and health. Filter by 'hostile', 'passive', 'player', 'item', or a specific mob name. |
| [`get-events`](#get-events) | `filter` <sub>array</sub>, `sinceVersion` <sub>int</sub> | Get real-time game events since the last poll: damage taken, health changes, food-level changes, hostile mob spawns, deaths, oxygen loss, and weather changes. Poll this regularly to react to threats. Accepts filter:[types] to restrict by event type, and sinceVersion to drop events at-or-before a… |
| [`get-world-time`](#get-world-time) | — | Get the in-game time of day, day count, moon phase, and daylight cycle state |
| [`get-weather`](#get-weather) | — | Get the current weather (rain and thunder state) |
| [`detect-gamemode`](#detect-gamemode) | — | Detect the gamemode on game |
| [`get-last-death`](#get-last-death) | `includeHistory` <sub>bool</sub> | Read the most recent death the bot suffered: cause, position, and age in seconds, plus a per-cause count summary across the remembered history (last 8 deaths). Returns {died:false} when the bot has never died. Use to avoid returning to a lethal coordinate or to recognize a repeated cause (e.g.… |

## Movement & navigation <a id="movement--navigation"></a>

Getting from A to B — all pathfinding is delegated to Baritone. The reflexes (stuck recovery, water escape) run without an LLM in the loop.

| Tool | Arguments | Description |
|---|---|---|
| [`navigate-v2`](#navigate-v2) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `range` <sub>number</sub>, `profile` <sub>enum</sub>, `timeoutMs` <sub>int</sub>, `stallMs` <sub>int</sub>, `minimumProgress` <sub>number</sub>, `segmentLength` <sub>int</sub>, `retryPolicy` <sub>enum</sub> | Server-side pathfinding with per-second stall detection: wraps the pathfinder with a timeout, a ~0.3-block minimum-progress check, internal route segmentation, and one recovery retry. Fails fast (~5-7s) when stuck instead of waiting on a long timeout. Movement profile is scoped and always… |
| [`goto-coords`](#goto-coords) | `x` <sub>int</sub>, `y` <sub>int</sub>, `z` <sub>int</sub> | Navigate to exact x/y/z coordinates via Baritone #goto with an arrival gate: polls until within ~2.5 blocks of the destination (60s cap), then #stop. Queue-safe (usable standalone and inside enqueue-tasks / standing-process cycles). Standing mine-and-deposit cycles use this to return to the… |
| [`path-safely`](#path-safely) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `avoidMobs` <sub>bool</sub>, `avoidHazards` <sub>array</sub>, `safeDistance` <sub>number</sub>, `maxRetries` <sub>int</sub>, `timeoutMs` <sub>int</sub> | Move to a position while avoiding hostile mobs and hazards (lava, fire, cactus, water, cliffs). Returns a danger assessment of the route. |
| [`find-safe-location`](#find-safe-location) | `maxDistance` <sub>number</sub>, `requireLit` <sub>bool</sub> | Find the nearest safe spot: standable, lit (mob-spawn-proof), enclosed if possible, and away from hostile mobs |
| [`explore`](#explore) | `direction` <sub>enum</sub>, `maxRadius` <sub>int</sub>, `maintainY` <sub>int</sub>, `maxChunks` <sub>int</sub> | Explore the world as a background process: spirals outward over the CLOSEST UN-CACHED chunks, paths to each, and reports newly cached notables (ores/logs/structures). `direction` biases the spiral (north/south/east/west); `maintainY` holds a Y level. The chat 'go explore and find iron' idiom +… |
| [`get-to-block`](#get-to-block) | `block` <sub>string</sub>, `filter` <sub>array</sub>, `maxDistance` <sub>number</sub>, `rightClick` <sub>bool</sub>, `explore` <sub>bool</sub> | Navigate to the nearest of a target block (or blocks) as a background process: scans the WorldCache + a fresh local scan, paths to the nearest known location, blacklists the closest AND its adjacent instances on path failure (a vein of unreachable blocks blacklists as one cluster), and… |
| [`follow-player`](#follow-player) | `username` <sub>string</sub>, `entityId` <sub>int</sub>, `range` <sub>number</sub>, `maxDurationMs` <sub>int</sub> | Follow a player (or mob) as a background process: paths to the target entity, rebuilding the goal each tick as the entity moves. Concedes when the entity leaves the server / despawns. The 'follow me' chat idiom. Returns {ok, followed, reason}. |
| [`follow-entity`](#follow-entity) | `entityName` <sub>string</sub>, `distance` <sub>number</sub>, `timeoutMs` <sub>int</sub> | Follow a mob or player, maintaining a set distance, until timeout or the target disappears |
| [`move-in-direction`](#move-in-direction) | `direction` <sub>enum</sub>, `duration` <sub>number</sub> | Move the bot in a specific direction for a duration |
| [`recover-stuck`](#recover-stuck) | `maxDigBlocks` <sub>int</sub>, `maxDurationMs` <sub>int</sub>, `restoreProfile` <sub>enum</sub>, `previousTarget` <sub>object</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Deterministic stuck-recovery reflex: when the bot is boxed in (head blocked or surrounded by solid blocks) or trapped in a 1-block pit, stops the pathfinder/dig/controls, temporarily enables a digging movement profile, clears up to maxDigBlocks of the safest exit block(s), moves laterally away… |
| [`escape-water`](#escape-water) | `maxDurationMs` <sub>int</sub>, `oxygenThreshold` <sub>int</sub>, `ringMax` <sub>int</sub> | Drowning reflex: when the bot's head is in water and oxygen is low (< threshold), stops pathfinding/digging, ring-searches outward for the nearest standable dry cell, holds jump and moves toward shore until the head block is no longer water. Emits a WATER_ESCAPE event. Usually triggered… |
| [`fly-to`](#fly-to) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Make the bot fly to a specific position |
| [`set-movement-profile`](#set-movement-profile) | `allowSprinting` <sub>bool</sub>, `allowJumping` <sub>bool</sub>, `canDig` <sub>bool</sub>, `maxDropDown` <sub>int</sub>, `blocksToAvoid` <sub>array</sub>, `digCost` <sub>number</sub>, `placeCost` <sub>number</sub>, `liquidCost` <sub>number</sub>, `entityCost` <sub>number</sub>, `populateScaffold` <sub>bool</sub> | Configure the pathfinder's movement behavior: sprinting, parkour jumps, digging, safe drop height, blocks to avoid, AND the cost columns (digCost/placeCost/liquidCost/entityCost) that price detours vs. dig/bridge/swim. Reports the canSprint (food>6) and hasThrowaway (scaffold blocks from… |

## Blocks & building <a id="blocks--building"></a>

Direct world manipulation, from single blocks to blueprint structures.

| Tool | Arguments | Description |
|---|---|---|
| [`dig-block`](#dig-block) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `timeoutMs` <sub>int</sub>, `autoTool` <sub>bool</sub>, `preserveDurability` <sub>bool</sub> | Dig a block at the specified position. Auto-equips the best tool from inventory by default (M1: autoTool), and reports the tool used + remaining durability. When autoTool is false, the bot digs with whatever it holds. |
| [`place-block`](#place-block) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `faceDirection` <sub>enum</sub>, `timeoutMs` <sub>int</sub>, `block` <sub>string</sub>, `fallbackBlocks` <sub>array</sub>, `family` <sub>string</sub> | Place a block at the specified position. M3 (materialPalette cap): pass `block` to name the desired block and the server resolves it through its material family using the bot's inventory (oak_planks → cherry_planks when that's what's held) + auto-equips the resolved item before placing.… |
| [`use-item-on-block`](#use-item-on-block) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `item` <sub>string</sub>, `faceDirection` <sub>enum</sub> | Right-click interaction primitive: hold an item (optional, auto-equipped when named) and use it against a block face. Covers nether-portal ignition (flint_and_steel on obsidian frames), doors, trapdoors, levers, buttons, and other right-clickable blocks. Reports the block state after use and… |
| [`bucket-fluid`](#bucket-fluid) | `action` <sub>enum</sub>, `fluid` <sub>enum</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `faceDirection` <sub>enum</sub> | Bucket/fluid primitive: fill a bucket from a fluid source (water, lava, powder snow) or place a fluid from a filled bucket. fill auto-finds the nearest source when x/y/z are omitted; place clicks a block face and the fluid appears beside it (or at the position itself when replaceable).… |
| [`farm-plot`](#farm-plot) | `action` <sub>enum</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `item` <sub>string</sub> | Farming primitive: till farmland with a hoe (grass/dirt → farmland), plant seeds on farmland, harvest a grown crop (breaking it so drops fall), or fertilize with bone meal. Auto-equips the required item (hoe, seed item, or bone meal). Verifies by block-state change and reports the block and crop… |
| [`build-structure`](#build-structure) | `origin` <sub>object</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `maxPasses` <sub>int</sub> | Build a structure from a blueprint as a background process (Baritone navigation + placeBlock with per-pass re-diff, MaterialPalette family resolution, shortfall reporting by family). Blueprint = the agent's shelter_3x3.json wire format {blocks:[{x,y,z,block}], digs:[]}. MaterialPalette rewrites… |
| [`look-at`](#look-at) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Make the bot look at a specific position |
| [`place-torch`](#place-torch) | — | Place a torch from the inventory on the ground (or a wall) next to the bot to prevent mob spawning |

## Crafting & processing <a id="crafting--processing"></a>

The recipe pipeline: resolution against the live recipe book, recursive dependency planning, furnace operation, and body maintenance.

| Tool | Arguments | Description |
|---|---|---|
| [`craft-item`](#craft-item) | `outputItem` <sub>string</sub>, `amount` <sub>int</sub>, `tableX` <sub>int</sub>, `tableY` <sub>int</sub>, `tableZ` <sub>int</sub> | Craft an item using a crafting recipe. Queue-safe (worker-thread + client transactions). Optional tableX/tableY/tableZ name a crafting table for 3x3 recipes (e.g. furnace) — the Fabric body auto-opens the table, crafts, and closes, so multi-step pipelines stay one enqueue-tasks call. |
| [`can-craft`](#can-craft) | `itemName` <sub>string</sub> | Check if the bot can craft a specific item with current inventory |
| [`craft-with-deps`](#craft-with-deps) | `item` <sub>string</sub>, `count` <sub>int</sub>, `maxDepth` <sub>int</sub>, `maxMs` <sub>int</sub> | Resolve what it takes to craft an item recursively: returns the `subCrafts` trail (deepest-first — gather/craft the leaves before the root) plus the gather-leaf `shortfall` after subtracting the bot's current inventory. Handles crafting AND smelting (raw_iron → iron_ingot via furnace). Cycles… |
| [`smelt-item`](#smelt-item) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `inputItem` <sub>string</sub>, `inputCount` <sub>int</sub>, `fuelItem` <sub>string</sub>, `fuelCount` <sub>int</sub>, `takeOutput` <sub>bool</sub>, `timeoutMs` <sub>int</sub> | Smelt items using a furnace-like block. Queue-safe (worker-thread + client transactions). |
| [`resolve-material`](#resolve-material) | `block` <sub>string</sub>, `count` <sub>int</sub> | Resolve a desired block through its material family using the bot's current inventory: oak_planks → cherry_planks when that's what's held. Returns the resolved block name + the family + a per-family BOM shortfall (empty when the bot holds enough, or holds count-less/unknown quantity). The… |
| [`eat-food`](#eat-food) | `foodName` <sub>string</sub>, `minCount` <sub>int</sub> | Eat food from the inventory until the food bar reaches the target level. Picks the most nutritious food automatically unless a specific one is named. |
| [`sleep-in-bed`](#sleep-in-bed) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub> | Walk to a bed and sleep through the night (skips night and resets spawn). Fails if it's daytime or monsters are nearby. |

## Combat & survival <a id="combat--survival"></a>

Threat response. Doctrine: Meteor KillAura for combat when present, built-in handlers otherwise; Baritone for positioning.

| Tool | Arguments | Description |
|---|---|---|
| [`attack-entity`](#attack-entity) | `entityName` <sub>string</sub>, `strategy` <sub>enum</sub>, `timeoutMs` <sub>int</sub> | Attack a target entity. Equips the best weapon and fights until the target dies, disappears, or the timeout expires. Strategies: 'melee' (chase and hit), 'ranged' (bow, keeps distance), 'hit_and_retreat' (strike then back off — good at low health). |
| [`entity-interact`](#entity-interact) | `entityName` <sub>string</sub>, `entityId` <sub>int</sub>, `item` <sub>string</sub>, `attempts` <sub>int</sub> | Right-click interaction primitive for entities: hold an item (optional, auto-equipped when named) and use it on a nearby entity. Covers breeding (wheat on cows/sheep), taming (bones on wolves), leading (lead), villager trading, and general entity right-clicks. Resolves the target by numeric id… |
| [`villager-trade`](#villager-trade) | `action` <sub>enum</sub>, `entityName` <sub>string</sub>, `entityId` <sub>int</sub>, `tradeIndex` <sub>int</sub> | Trade with a villager or wandering trader. Right-click opens the trade screen, then either list the offers (action=list, reports cost/result/out-of-stock per offer) or execute a trade (action=trade + tradeIndex): selects the offer, moves the payment items into the payment slots, and takes the… |
| [`guard-area`](#guard-area) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `radius` <sub>number</sub>, `strategy` <sub>enum</sub>, `maxDurationMs` <sub>int</sub> | Guard an area as a background process: holds a post within the radius, melee-attacks hostiles that enter the radius (KillAura-led with melee fallback in the Fabric body), then returns to the post. `strategy:'ranged'` is deferred (melee first). Returns {ok, threatsEngaged, attacks,… |
| [`flee-from`](#flee-from) | `fromType` <sub>string</sub>, `minDistance` <sub>number</sub>, `timeoutMs` <sub>int</sub> | Sprint away from a threat ('nearest_hostile' or a specific mob name) until a minimum distance is reached |
| [`toggle-meteor-module`](#toggle-meteor-module) | `module` <sub>string</sub>, `action` <sub>enum</sub> | Toggle, enable, or disable a Meteor Client module by name. Non-blocking — the module's own tick handler does the work (e.g. KillAura attacks on tick, AutoEat eats on tick). This replaces the laggy blocking attack-entity loop for combat: enable KillAura when hostiles are near, disable when clear.… |
| [`set-meteor-keybind`](#set-meteor-keybind) | `module` <sub>string</sub>, `key` <sub>string</sub>, `modifiers` <sub>array</sub> | Assign a keyboard hotkey to a Meteor Client module. Persists the keybind via Meteor's Systems.save() so it survives restarts. Use this to configure which key toggles a module (e.g. bind kill-aura to 'C'). Accepts single letters (A-Z, 0-9), function keys (F1-F25), named keys (SPACE, ENTER,… |
| [`list-meteor-modules`](#list-meteor-modules) | `filter` <sub>string</sub> | List all available Meteor Client modules with their active state and current keybind. Useful for discovering module names and verifying keybind assignments. Optional filter substring matches module names (case-insensitive). Requires Meteor Client installed. |
| [`auto-equip-best-gear`](#auto-equip-best-gear) | `armor` <sub>bool</sub>, `weapon` <sub>bool</sub>, `toolForCurrentTask` <sub>bool</sub>, `maxDurationMs` <sub>int</sub> | Equip the best available armor and weapon from inventory, ranked by material tier (leather < golden < chainmail/iron < diamond < netherite for armor; sword tiers + bow/crossbow/trident for weapons). Equips a slot ONLY when the candidate is strictly better than what is already worn — never… |

## Inventory & containers <a id="inventory--containers"></a>

Item management: self-inventory, chests and barrels, and honest move accounting (moved vs requested vs remaining).

| Tool | Arguments | Description |
|---|---|---|
| [`list-inventory`](#list-inventory) | — | List all items in the bot's inventory with durability, enchantments, and weapon damage (M1). Items without durability (blocks, non-tools) omit the durability field. |
| [`find-item`](#find-item) | `nameOrType` <sub>string</sub> | Find a specific item in the bot's inventory |
| [`equip-item`](#equip-item) | `itemName` <sub>string</sub>, `destination` <sub>string</sub> | Equip a specific item |
| [`move-item`](#move-item) | `sourceSlot` <sub>int</sub>, `destSlot` <sub>int</sub> | Move an item stack from one inventory slot to another (merges into same-name stacks, swaps otherwise). Slot map: 9-35 main storage, 36-44 hotbar, 45 offhand. Pure inventory shuffle — no container or engine needed. |
| [`organize-inventory`](#organize-inventory) | `strategy` <sub>enum</sub>, `preference` <sub>array</sub> | Reorganize the bot's own inventory with one of two strategies: 'compact' merges same-name stacks toward the hotbar/top of storage (frees slots); 'hotbar-preference' places named items into hotbar slots 36-44 in priority order (evicting occupants to main storage). Reports the plan and slots freed. |
| [`open-container`](#open-container) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `containerName` <sub>string</sub> | Open a chest/barrel/shulker box/etc and list its contents plus the bot's inventory as seen through the window. Pass `containerName` (e.g. 'chest') to find the nearest container block, or explicit `x,y,z`. The bot must be within reach (4.5 blocks) — out-of-reach containers error `out_of_reach`… |
| [`deposit-items`](#deposit-items) | `name` <sub>string</sub>, `count` <sub>int</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `containerName` <sub>string</sub> | Move up to `count` of `name` from the bot's inventory into a container (chest/barrel/etc). Caps to what the bot holds and what the container can accept — reports `moved` + `remaining` honestly (never silently claims success). Target the container by `containerName` (nearest) or explicit `x,y,z`.… |
| [`withdraw-items`](#withdraw-items) | `name` <sub>string</sub>, `count` <sub>int</sub>, `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `containerName` <sub>string</sub> | Move up to `count` of `name` out of a container into the bot's inventory. Caps to what the container holds and what the bot can carry — reports `moved` + `remaining` honestly. Target the container by `containerName` (nearest) or explicit `x,y,z`. Out-of-reach containers error `out_of_reach`… |
| [`collect-drops`](#collect-drops) | `radius` <sub>number</sub>, `timeoutMs` <sub>int</sub>, `expectedItems` <sub>array</sub> | Collect dropped item entities on the ground near the bot: finds item entities within a radius, pathfinds to each in turn, and waits for pickup. Reports exactly which items were collected (an inventory-delta check, not just 'walked near it') so a dig/gather operation can confirm it actually… |

## Processes & queues <a id="processes--queues"></a>

The economics layer: run multi-tool goal chains as one server-side composite, and standing processes that keep working after the chat ends.

| Tool | Arguments | Description |
|---|---|---|
| [`enqueue-tasks`](#enqueue-tasks) | `tasks` <sub>array</sub>, `returnToOrigin` <sub>bool</sub> | Enqueue a linear, pre-ordered list of process-tool tasks and run them sequentially server-side. In Fabric mode the JAR dispatches each child to its Baritone-backed Java handler. Per-task `onFail` policy: 'continue' (default) or 'abort'. A task may carry a `name`; later tasks can reference its… |
| [`mine-blocks`](#mine-blocks) | `block` <sub>string</sub>, `filter` <sub>array</sub>, `count` <sub>int</sub>, `mode` <sub>enum</sub>, `maxDistance` <sub>number</sub>, `yLevel` <sub>int</sub>, `length` <sub>int</sub>, `torchEvery` <sub>int</sub> | Mine `count` of a block (or blocks) as a background process (Baritone #mine + inventory delta polling). Modes: `mine-blocks` (default), `chop-tree` (filter:[*_log] + connected-component targeting), `strip-mine` (branch-mine at yLevel). `torchEvery` places a torch every N blocks if dark. Returns… |
| [`get-to-block`](#get-to-block) | `block` <sub>string</sub>, `filter` <sub>array</sub>, `maxDistance` <sub>number</sub>, `rightClick` <sub>bool</sub>, `explore` <sub>bool</sub> | Navigate to the nearest of a target block (or blocks) as a background process: scans the WorldCache + a fresh local scan, paths to the nearest known location, blacklists the closest AND its adjacent instances on path failure (a vein of unreachable blocks blacklists as one cluster), and… |
| [`explore`](#explore) | `direction` <sub>enum</sub>, `maxRadius` <sub>int</sub>, `maintainY` <sub>int</sub>, `maxChunks` <sub>int</sub> | Explore the world as a background process: spirals outward over the CLOSEST UN-CACHED chunks, paths to each, and reports newly cached notables (ores/logs/structures). `direction` biases the spiral (north/south/east/west); `maintainY` holds a Y level. The chat 'go explore and find iron' idiom +… |
| [`guard-area`](#guard-area) | `x` <sub>number</sub>, `y` <sub>number</sub>, `z` <sub>number</sub>, `radius` <sub>number</sub>, `strategy` <sub>enum</sub>, `maxDurationMs` <sub>int</sub> | Guard an area as a background process: holds a post within the radius, melee-attacks hostiles that enter the radius (KillAura-led with melee fallback in the Fabric body), then returns to the post. `strategy:'ranged'` is deferred (melee first). Returns {ok, threatsEngaged, attacks,… |
| [`standing-start`](#standing-start) | `name` <sub>string</sub>, `type` <sub>string</sub>, `intervalMs` <sub>int</sub> | Start (or resume) a named standing process that runs goal cycles in the background without LLM round-trips. Types: mine-and-deposit (mine-blocks -> goto base chest -> deposit, repeated), guard (guard-area bounded cycles at a post). Stop conditions: stopWhen {totalItems, maxDurationMs}. Recovery:… |
| [`standing-status`](#standing-status) | `name` <sub>string</sub> | Status of one standing process (name) or all (no args): state RUNNING/DONE/PARKED/STOPPED/INTERRUPTED, cycles, totalItems, consecutiveFailures, lastError, lastMessage, last cycle result. |
| [`standing-stop`](#standing-stop) | `name` <sub>string</sub>, `wait` <sub>enum</sub> | Gracefully stop a standing process: finishes or aborts the current cycle, final status preserved (STOPPED). wait=true blocks up to 120s for a clean stop. |
| [`cancel-current-action`](#cancel-current-action) | `reason` <sub>string</sub> | Stop whatever composite operation the bot is currently running (navigation/dig/place/collect). Cancels the in-world action — stops the pathfinder, stops digging, clears movement controls — not just the response. Returns what was cancelled and what cleanup ran. Safe to call when nothing is active. |
| [`get-current-action`](#get-current-action) | — | Read-only telemetry of the currently running queue or composite operation: kind (enqueue-tasks or a standalone composite like mine-blocks), start time, last-progress timestamp, elapsed ms, and queue detail (current task index/tool, total tasks). Returns {active:false} when idle. |

## Memory, journal & policy <a id="memory--journal"></a>

Persistent state and reactive intelligence. Policies fire from the EventBuffer before the LLM is ever consulted.

| Tool | Arguments | Description |
|---|---|---|
| [`memory-save`](#memory-save) | `id` <sub>string</sub>, `kind` <sub>string</sub>, `note` <sub>string</sub> | Upsert a record into the persistent memory store (config/hyfuse_memory.json — owned by the mod, survives client restarts). Record = flat JSON {id, kind, position?, note?, ...extras} keyed by `id` (last-writer-wins). Suggested kinds: poi (base, chest, furnace), mob (identity + doNotAttack),… |
| [`memory-read`](#memory-read) | `kind` <sub>string</sub>, `id` <sub>string</sub>, `x` <sub>int</sub>, `y` <sub>int</sub>, `z` <sub>int</sub> | Read records from the persistent memory store. Filter by kind, id, or exact position (x/y/z); no filters returns all. get-agent-snapshot carries a compact `memory` summary block (record count + kinds + first ids). |
| [`memory-forget`](#memory-forget) | `id` <sub>string</sub> | Delete a record from the persistent memory store by id. Returns ok:false honestly when the id was never stored. |
| [`journal-read`](#journal-read) | `offset` <sub>int</sub>, `maxChars` <sub>int</sub> | Read the per-server journal (goal, base layout, server facts, players, event log — the things that would be WRONG on a different server). Paged: pass offset (from the previous response) and maxChars (default 4000, cap 16000). Hard-capped at 100 KB total. Requires being connected to a server. |
| [`journal-append`](#journal-append) | `text` <sub>string</sub>, `section` <sub>int</sub> | Append an entry newest-first under a section of the per-server journal (1-10; default 9 = rolling event log; 1 = identity/goal; 2 = server facts; 8 = plans). Entry text ≤ 2000 chars; journal hard cap 100 KB (a journal_full rejection mutates nothing — prune stale entries first). Creates the… |
| [`policy-save`](#policy-save) | `id` <sub>string</sub>, `trigger` <sub>string</sub>, `threshold` <sub>int</sub>, `action` <sub>string</sub>, `cooldownMs` <sub>int</sub>, `enabled` <sub>bool</sub> | Upsert a policy row into the reactive policy layer. Row: {id, kind:'policy', trigger, threshold?, action?, args?, cooldownMs?, enabled}. trigger = EventBuffer event type (entityHurt, health, food, oxygen, death, entitySpawn, weatherUpdate); the event's `health` payload carries the level for… |
| [`policy-read`](#policy-read) | `id` <sub>string</sub> | List the reactive policy layer's rows (all by default, one by id) plus the audit ring (last 100 firings: trigger, policy, action, result, ok, timestamp). The audit shows WHY the bot ate/fled/escaped — audit-visible value. |
| [`policy-forget`](#policy-forget) | `id` <sub>string</sub> | Delete a policy row by id. Honest ok:false when the id was never stored. Forgetting every row restores the default seeds on the next boot. |
| [`get-playbook`](#get-playbook) | — | Returns the server-agnostic playbook (survival priorities, tool doctrine, memory discipline) from config/hyfuse/INTELLIGENCE.md (user-editable between releases) or the packaged default. Call this FIRST in a fresh chat, alongside get-agent-snapshot, to prime context before acting. |

## Agent & operator <a id="agent--operator"></a>

The embedded brain loop, capability negotiation, and chat I/O.

| Tool | Arguments | Description |
|---|---|---|
| [`agent-start`](#agent-start) | — | Start the embedded agent loop: the mod drives an OpenAI-compatible LLM endpoint (config/hyfuse/agent.json: url, apiKey, model, maxIterations) with the playbook as system prompt and the full MCP tool list; it parses tool calls, dispatches them, and loops until the model replies with plain text or… |
| [`agent-stop`](#agent-stop) | — | Request the running agent loop to stop after the current iteration (local-only). Poll agent-status until active=false. |
| [`agent-status`](#agent-status) | — | Read-only status of the embedded agent loop: configured, active, iterations, toolCallsTotal, lastError, lastAssistant (truncated). |
| [`get-capabilities`](#get-capabilities) | — | Report which capability features this mod supports. The agent probes this once at startup and delegates to the server only for caps that are true; when false or absent, the agent uses its own fallback. Caps: toolSelector (dig-block autoTool+telemetry), blockInfo (get-block-info enrichment),… |
| [`send-chat`](#send-chat) | `message` <sub>string</sub> | Send a chat message in-game (max 200 characters, non-empty; rate-limited to one send per 3 seconds — violations are REJECTED with a CHAT_REJECTED tool error and never sent; the client hard-caps chat at 256 chars and would disconnect otherwise). A message starting with '/' is sent as a SERVER… |
| [`read-chat`](#read-chat) | `count` <sub>int</sub>, `mode` <sub>string</sub> | Get recent chat messages from players |

---

**84 tools total** (a few appear in more than one category).

### Queue-only task types

These four are not standalone tools — they run **only inside `enqueue-tasks`**
as task entries:

| Task type | What it does |
|---|---|
| `hunt-hostile` | find + goto + KillAura + collect loot, one composite |
| `drop-items` | drop a named list or all-non-hotbar, optional walkBlocks |
| `break-entity` | goto + attack until the target is removed, returns position |
| `replace-blocks` | Baritone `#sel pos1/pos2/replace <from> <with>` region edit |
