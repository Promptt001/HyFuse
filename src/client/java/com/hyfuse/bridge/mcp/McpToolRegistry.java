package com.hyfuse.bridge.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Registry of tools exposed over the integrated MCP (Streamable HTTP) server.
 *
 * Registry of all 79 tools (everything the
 * {@link com.hyfuse.bridge.dispatch.ToolDispatcher} HANDLERS map dispatches),
 * organized in functional tiers (perception, entities, block interaction,
 * inventory, chat, containers, crafting, navigation, queue, standing
 * processes, brain, agent). Each entry pairs an LLM-facing description
 * with a JSON Schema input contract.
 *
 * Schema conventions:
 * - Optional properties are omitted from the "required" array (all
 * properties stay listed under "properties" so the LLM can discover them).
 * - Nested/union/free-form params (enqueue-tasks args, navigate-v2 goal,
 * build-structure blueprint) are permissive: a nested object without strict
 * properties or a plain {} — strict anyOf/record schemas break MCP HTTP
 * bridges (mcpo/Pydantic).
 * - Numeric bounds are kept in the description text (JSON Schema
 * minimum/maximum are ignored by LLM callers and add schema noise).
 */
public final class McpToolRegistry {

    /** A tool entry: dispatch name, LLM-facing description, and JSON Schema. */
    public record Tool(String name, String description, JsonObject inputSchema) {
        JsonObject toJson() {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tool.addProperty("description", description);
            tool.add("inputSchema", inputSchema);
            return tool;
        }
    }

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    /** The full tool list for the agent loop's API request shaping. */
    public static Collection<Tool> tools() {
        return java.util.Collections.unmodifiableCollection(TOOLS.values());
    }

    // ── Shared subschemas ─────────────────────────────────────────────────

    /** {x,y,z} position object used by get-blocks positions and TaskTarget. */
    private static final JsonObject POSITION_SCHEMA = positionSchema();

    /** {type:array, items:string} — array of strings. */
    private static final JsonObject STRINGS_SCHEMA = arraySchema("string");

    /** A single enqueue-tasks task object. */
    private static final JsonObject QUEUE_TASK_SCHEMA = queueTaskSchema();

    private static JsonObject positionSchema() {
        JsonObject pos = new JsonObject();
        pos.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("x", simpleSchema("number", "X coordinate"));
        props.add("y", simpleSchema("number", "Y coordinate"));
        props.add("z", simpleSchema("number", "Z coordinate"));
        pos.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("x");
        req.add("y");
        req.add("z");
        pos.add("required", req);
        return pos;
    }

    private static JsonObject arraySchema(String itemType) {
        JsonObject arr = new JsonObject();
        arr.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", itemType);
        arr.add("items", items);
        return arr;
    }

    private static JsonObject queueTaskSchema() {
        JsonObject task = new JsonObject();
        task.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "string");
        tool.addProperty("description", "The process tool to run");
        JsonArray toolEnum = new JsonArray();
        for (String t: new String[]{"mine-blocks", "build-structure", "get-to-block", "explore",
                "follow-player", "guard-area", "hunt-hostile", "drop-items", "break-entity",
                "replace-blocks", "place-block"}) {
            toolEnum.add(t);
        }
        tool.add("enum", toolEnum);
        props.add("tool", tool);

        JsonObject args = new JsonObject();
        args.addProperty("type", "object");
        args.addProperty("description", "The tool's args (passed through to the runner; may reference earlier task "
                + "results via $name.field)");
        props.add("args", args);

        JsonObject onFail = new JsonObject();
        onFail.addProperty("type", "string");
        onFail.addProperty("description", "Per-task failure policy (default 'continue')");
        JsonArray onFailEnum = new JsonArray();
        onFailEnum.add("continue");
        onFailEnum.add("abort");
        onFail.add("enum", onFailEnum);
        props.add("onFail", onFail);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Optional name; later tasks can reference this task's result via "
                + "$name.<field> (e.g. $boat.position.x)");
        props.add("name", name);

        task.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("tool");
        req.add("args");
        task.add("required", req);
        return task;
    }

    private static JsonObject simpleSchema(String type, String description) {
        JsonObject s = new JsonObject();
        s.addProperty("type", type);
        if (description != null) {
            s.addProperty("description", description);
        }
        return s;
    }

    static {
        // ── Navigation ──
        register("navigate-v2",
                "Server-side pathfinding with per-second stall detection: wraps the pathfinder with a timeout, "
                        + "a ~0.3-block minimum-progress check, internal route segmentation, and one recovery retry. "
                        + "Fails fast (~5-7s) when stuck instead of waiting on a long timeout. Movement profile is "
                        + "scoped and always restored. Result includes movedBlocks/remainingBlocks/blockedBy so the "
                        + "planner can choose a recovery strategy. M2: an optional `goal` (GoalSpec JSON) names a "
                        + "Baritone-style goal (getToBlock/twoBlocks/runAway/adjacent/break/composite/...); with "
                        + "`goal` present the x/y/z target is ignored and the resolved goal drives pathing.",
                objectSchema(schema -> schema
.number("x", "X coordinate (ignored when `goal` is present)")
.number("y", "Y coordinate (ignored when `goal` is present)")
.number("z", "Z coordinate (ignored when `goal` is present)")
.raw("goal",
                                "M2 GoalSpec: a Baritone-style goal in JSON (e.g. {type:'getToBlock',blocks:['oak_log']} "
                                        + "or {type:'twoBlocks',x,y,z} or {type:'composite',members:[...]}). When present, "
                                        + "x/y/z are ignored and the resolved goal drives pathing. Use profile:'work' "
                                        + "with getToBlock to dig through canopies.")
.number("range", "How close to get to the target (default: 2; ignored when a `goal` is present)")
.enumeration("profile", "Movement profile (default: 'safe'). 'work' enables canDig + 1x1 towers "
                                + "— required to dig through leaf canopies to ground-level logs.",
                                "safe", "explore", "work", "emergency")
.integer("timeoutMs", "Overall deadline in ms (default: 30000)")
.integer("stallMs", "No-progress window that triggers a stall failure (default: 5000)")
.number("minimumProgress", "Blocks of progress needed within stallMs (default: 0.3)")
.integer("segmentLength", "Internal route segmentation in blocks (default: 16; ignored when a "
                                + "goal is present — the goal drives a single path)")
.enumeration("retryPolicy", "Recovery policy on stall (default: 'one_recovery_retry')",
                                "none", "one_recovery_retry")));

        register("goto-coords",
                "Navigate to exact x/y/z coordinates via Baritone #goto with an arrival gate: polls until "
                        + "within ~2.5 blocks of the destination (60s cap), then #stop. Queue-safe (usable "
                        + "standalone and inside enqueue-tasks / standing-process cycles). Standing "
                        + "mine-and-deposit cycles use this to return to the resolved base chest before "
                        + "depositing (the navigate-v2 tool reports dispatched/completionObserved:false only).",
                objectSchema(schema -> schema
.integer("x", "Destination X coordinate")
.integer("y", "Destination Y coordinate")
.integer("z", "Destination Z coordinate")));

        register("cancel-current-action",
                "Stop whatever composite operation the bot is currently running (navigation/dig/place/collect). "
                        + "Cancels the in-world action — stops the pathfinder, stops digging, clears movement "
                        + "controls — not just the response. Returns what was cancelled and what cleanup ran. "
                        + "Safe to call when nothing is active.",
                objectSchema(schema -> schema
.string("reason", "Why the action is being cancelled (e.g. 'drowning', 'higher_priority_event'). "
                                + "Default: 'user_requested'")));

        // ── Tier A: Perception / World State ──
        register("get-world-time",
                "Get the in-game time of day, day count, moon phase, and daylight cycle state",
                objectSchema(schema -> { }));

        register("get-weather",
                "Get the current weather (rain and thunder state)",
                objectSchema(schema -> { }));

        register("get-block-light",
                "Get the light levels (block light and sky light) at a position. Hostile mobs spawn at light level 0.",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")));

        register("detect-gamemode",
                "Detect the gamemode on game",
                objectSchema(schema -> { }));

        register("look-at",
                "Make the bot look at a specific position",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")));

        register("send-chat",
                "Send a chat message in-game (max 200 characters, non-empty; rate-limited to one send"
                + " per 3 seconds — violations are REJECTED with a CHAT_REJECTED tool error and"
                + " never sent; the client hard-caps chat at 256 chars and would disconnect otherwise)."
                + " A message starting with '/' is sent as a SERVER COMMAND packet (sendCommand)"
                + " instead of chat — use it for /pv, /bed, /spawn, etc.; Baritone '#' commands"
                + " stay on the chat path",
                objectSchema(schema -> schema
.required("message")
.string("message", "Message to send in chat (max 200 characters)")));

        register("raycast-look",
                "Raycast from the bot's eyes to find the block and/or entity it is currently looking at",
                objectSchema(schema -> schema
.number("maxDistance", "Maximum ray distance (default: 64)")));

        register("get-block-info",
                "Get rich information about a block at the specified position: hardness, tool type/tier, "
                        + "estimated break time with the bot's current best tool, drops, solid/passable flags, "
                        + "light levels, and block states (facing, open, powered, age, waterlogged).",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")));

        register("get-blocks",
                "Batch: get the block name at many positions in one call (survey a footprint/area). Cells in "
                        + "unloaded chunks report name='unloaded' (distinct from air).",
                objectSchema(schema -> schema
.required("positions")
.array("positions", "Up to 512 {x,y,z} positions to survey", POSITION_SCHEMA)));

        register("find-blocks",
                "Find one or more nearby blocks of a specific type",
                objectSchema(schema -> schema
.required("blockType")
.string("blockType", "Type of block to find")
.number("maxDistance", "Maximum search distance (default: 16)")
.integer("count", "Maximum number of blocks to return (default: 1; values above 256 are clamped)")));

        // ── Tier A: Entities ──
        register("find-entity",
                "Find the nearest entity of a specific type",
                objectSchema(schema -> schema
.string("type", "Type of entity to find (empty for any entity)")
.number("maxDistance", "Maximum search distance (default: 16)")));

        register("entity-interact",
                "Right-click interaction primitive for entities: hold an item (optional, auto-equipped when "
                        + "named) and use it on a nearby entity. Covers breeding (wheat on cows/sheep), taming "
                        + "(bones on wolves), leading (lead), villager trading, and general entity right-clicks. "
                        + "Resolves the target by numeric id (from find-entity) or name (nearest match); one-shot "
                        + "with a small bounded retry while the server passes the click. Reports the interaction "
                        + "result and inLove:true when a breeding heart-phase started.",
                objectSchema(schema -> schema
.string("entityName", "Target entity name (e.g. 'cow') or custom name — nearest match wins")
.integer("entityId", "Numeric entity id from find-entity (used when entityName is omitted)")
.string("item", "Optional item to equip and use (e.g. 'wheat', 'bone', 'lead'); when omitted, uses the "
                                + "currently-held item")
.integer("attempts", "Bounded retry count while the server passes the click (default 3, max 5)")));

        register("scan-nearby-entities",
                "Scan all entities near the bot and return a structured list with position, distance, hostility, "
                        + "and health. Filter by 'hostile', 'passive', 'player', 'item', or a specific mob name.",
                objectSchema(schema -> schema
.number("radius", "Search radius in blocks (default: 16)")
.string("filter", "Optional filter: 'hostile', 'passive', 'player', 'item', or a mob name substring")
.integer("maxCount", "Maximum entities to return (default: 20)")));

        // ── Tier B: Block Interaction ──
        register("dig-block",
                "Dig a block at the specified position. Auto-equips the best tool from inventory by default (M1: "
                        + "autoTool), and reports the tool used + remaining durability. When autoTool is false, the "
                        + "bot digs with whatever it holds.",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.integer("timeoutMs", "Timeout in ms before cancelling the dig (default 25000)")
.bool("autoTool", "Auto-equip the best tool for the block (default: true)")
.bool("preserveDurability", "Skip tools near breaking point (itemSaver, default: true)")));

        register("place-block",
                "Place a block at the specified position. M3 (materialPalette cap): pass `block` to name the desired "
                        + "block and the server resolves it through its material family using the bot's inventory "
                        + "(oak_planks → cherry_planks when that's what's held) + auto-equips the resolved item before "
                        + "placing. `fallbackBlocks` adds explicit fallback names tried in order; `family` declares the "
                        + "material family (e.g. 'planks') so any held member satisfies the place. When `block` is "
                        + "omitted the tool places the currently-held item (the legacy behavior).",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.enumeration("faceDirection", "Direction to place against (default: 'down')",
                                "up", "down", "north", "south", "east", "west")
.integer("timeoutMs", "Timeout in ms before cancelling the placement (default 25000)")
.string("block", "Desired block name (e.g. 'oak_planks'); when set + materialPalette cap, the "
                                + "server resolves it through the family + auto-equips the best held member")
.array("fallbackBlocks", "Explicit fallback block names tried in order when `block` isn't held "
                                + "(materialPalette cap)", STRINGS_SCHEMA)
.string("family", "Material family name (e.g. 'planks') so any held family member satisfies the "
                                + "place (materialPalette cap)")));

        register("use-item-on-block",
                "Right-click interaction primitive: hold an item (optional, auto-equipped when named) and use it "
                        + "against a block face. Covers nether-portal ignition (flint_and_steel on obsidian frames), "
                        + "doors, trapdoors, levers, buttons, and other right-clickable blocks. Reports the block "
                        + "state after use and portalIgnited:true when a nether portal formed.",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate of the block to use")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.string("item", "Optional item name to equip and use (e.g. 'flint_and_steel'); when omitted, uses the "
                                + "currently-held item")
.enumeration("faceDirection", "Face of the target block to click (default: 'up')",
                                "up", "down", "north", "south", "east", "west")));

        register("bucket-fluid",
                "Bucket/fluid primitive: fill a bucket from a fluid source (water, lava, powder snow) or place "
                        + "a fluid from a filled bucket. fill auto-finds the nearest source when x/y/z are omitted; "
                        + "place clicks a block face and the fluid appears beside it (or at the position itself "
                        + "when replaceable). Auto-equips the required bucket. Verifies by fluid-state change and "
                        + "reports the fluid found at the position after the action.",
                objectSchema(schema -> schema
.enumeration("action", "'fill' scoops a source into an empty bucket; 'place' empties a filled bucket (default: 'fill')",
                                "fill", "place")
.enumeration("fluid", "Fluid type (default: 'water')", "water", "lava", "powder_snow")
.number("x", "X coordinate — the fluid source (fill) or block face (place)")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.enumeration("faceDirection", "Face of the target block to click for place (default: 'up')",
                                "up", "down", "north", "south", "east", "west")));

        register("farm-plot",
                "Farming primitive: till farmland with a hoe (grass/dirt → farmland), plant seeds on farmland, "
                        + "harvest a grown crop (breaking it so drops fall), or fertilize with bone meal. "
                        + "Auto-equips the required item (hoe, seed item, or bone meal). Verifies by block-state "
                        + "change and reports the block and crop age found at the position after the action.",
                objectSchema(schema -> schema
.required("action")
.enumeration("action", "Farming action", "till", "plant", "harvest", "fertilize")
.required("x", "y", "z")
.number("x", "X coordinate — the block to till, the farmland to plant on, the crop to harvest/fertilize")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.string("item", "Item to use: hoe for till (default: any hoe in inventory), seed item for plant (required for plant, e.g. wheat_seeds)")));

        register("villager-trade",
                "Trade with a villager or wandering trader. Right-click opens the trade screen, then either "
                        + "list the offers (action=list, reports cost/result/out-of-stock per offer) or execute a "
                        + "trade (action=trade + tradeIndex): selects the offer, moves the payment items into the "
                        + "payment slots, and takes the result into inventory. Verifies by result pickup.",
                objectSchema(schema -> schema
.enumeration("action", "'list' shows offers; 'trade' executes tradeIndex (default: 'list')", "list", "trade")
.string("entityName", "Villager or wandering trader name (e.g. 'villager', 'wandering_trader')")
.integer("entityId", "Entity id from find-entity (alternative to entityName)")
.integer("tradeIndex", "Index into the offer list to execute (action=trade)")));

        register("scan-area",
                "Survey the blocks around the bot: block type counts, hazards (lava, fire, cactus,...), and optional "
                        + "filter matches",
                objectSchema(schema -> schema
.integer("radius", "Horizontal survey radius (default: 8, max: 16)")
.integer("yRange", "Vertical range above/below the bot (default: 3, max: 8)")
.string("blockFilter", "Only report positions of blocks whose name contains this string")));

        // ── Tier C: Inventory Basics ──
        register("list-inventory",
                "List all items in the bot's inventory with durability, enchantments, and weapon damage (M1). "
                        + "Items without durability (blocks, non-tools) omit the durability field.",
                objectSchema(schema -> { }));

        register("find-item",
                "Find a specific item in the bot's inventory",
                objectSchema(schema -> schema
.required("nameOrType")
.string("nameOrType", "Name or type of item to find")));

        register("equip-item",
                "Equip a specific item",
                objectSchema(schema -> schema
.required("itemName")
.string("itemName", "Name of the item to equip")
.string("destination", "Where to equip the item (default: 'hand')")));

        // ── Tier D: Chat ──
        register("read-chat",
                "Get recent chat messages from players",
                objectSchema(schema -> schema
.integer("count", "Number of recent messages to retrieve (default: 10, max: 100)")
.string("mode", "'drain' (default) removes messages after reading; 'peek' leaves them")));

        // ── Tier E: Survival & Inventory Management ──
        register("eat-food",
                "Eat food from the inventory until the food bar reaches the target level. Picks the most nutritious "
                        + "food automatically unless a specific one is named.",
                objectSchema(schema -> schema
.string("foodName", "Specific food item to eat (default: best available)")
.integer("minCount", "Eat until food level >= this value (default: 18, max: 20)")));

        register("place-torch",
                "Place a torch from the inventory on the ground (or a wall) next to the bot to prevent mob spawning",
                objectSchema(schema -> { }));

        register("auto-equip-best-gear",
                "Equip the best available armor and weapon from inventory, ranked by material tier (leather < golden "
                        + "< chainmail/iron < diamond < netherite for armor; sword tiers + bow/crossbow/trident for "
                        + "weapons). Equips a slot ONLY when the candidate is strictly better than what is already worn "
                        + "— never downgrades, never re-equips same-tier. `toolForCurrentTask` is an honest no-op when "
                        + "no task context is available (this tool does not know the next activity; use equip-item for "
                        + "a specific tool). Recommended after crafting/collecting gear, before combat, and periodically "
                        + "while idle. Reports each slot changed (from->to, tier delta) and each slot skipped-with-reason.",
                objectSchema(schema -> schema
.bool("armor", "Rank and equip the best armor per slot (default: true)")
.bool("weapon", "Equip the best weapon to the main hand (default: true)")
.bool("toolForCurrentTask", "Attempt to equip a task-appropriate tool (default: false; honest "
                                + "no-op without task context — use equip-item for a specific tool)")
.integer("maxDurationMs", "Hard deadline in ms (default: 10000)")));

        register("move-item",
                "Move an item stack from one inventory slot to another (merges into same-name stacks, swaps otherwise). "
                        + "Slot map: 9-35 main storage, 36-44 hotbar, 45 offhand. Pure inventory shuffle — no container "
                        + "or engine needed.",
                objectSchema(schema -> schema
.required("sourceSlot", "destSlot")
.integer("sourceSlot", "The source window slot (9-35 main, 36-44 hotbar, 45 offhand)")
.integer("destSlot", "The destination window slot (9-35 main, 36-44 hotbar, 45 offhand)")));

        register("organize-inventory",
                "Reorganize the bot's own inventory with one of two strategies: 'compact' merges same-name stacks toward "
                        + "the hotbar/top of storage (frees slots); 'hotbar-preference' places named items into hotbar "
                        + "slots 36-44 in priority order (evicting occupants to main storage). Reports the plan and "
                        + "slots freed.",
                objectSchema(schema -> schema
.required("strategy")
.enumeration("strategy", "compact = merge same-name stacks toward the front; hotbar-preference "
                                + "= place preferred items into hotbar slots 36-44 in order", "compact", "hotbar-preference")
.array("preference", "For hotbar-preference: item names in priority order (first → slot 36, "
                                + "second → 37,...). Ignored for compact.", STRINGS_SCHEMA)));

        // ── Tier F: Container Operations ──
        register("open-container",
                "Open a chest/barrel/shulker box/etc and list its contents plus the bot's inventory as seen through "
                        + "the window. Pass `containerName` (e.g. 'chest') to find the nearest container block, or "
                        + "explicit `x,y,z`. The bot must be within reach (4.5 blocks) — out-of-reach containers error "
                        + "`out_of_reach` until the navigation process is live. This is the missing half of "
                        + "inventory_full: read what's stored before depositing/withdrawing. Returns "
                        + "{container:[{name,count}], inventory:[{name,count}]}.",
                objectSchema(schema -> schema
.number("x", "Container block X (omit to find nearest by containerName)")
.number("y", "Container block Y")
.number("z", "Container block Z")
.string("containerName", "Container block name to find nearest of (e.g. 'chest'); omit when "
                                + "using x,y,z")));

        register("deposit-items",
                "Move up to `count` of `name` from the bot's inventory into a container (chest/barrel/etc). Caps to "
                        + "what the bot holds and what the container can accept — reports `moved` + `remaining` honestly "
                        + "(never silently claims success). Target the container by `containerName` (nearest) or "
                        + "explicit `x,y,z`. Out-of-reach containers error `out_of_reach` until navigation is live. "
                        + "This resolves MineProcess's inventory_full (deposit + resume, no bare FAIL). Returns "
                        + "{ok, moved, requested, remaining, reason}.",
                objectSchema(schema -> schema
.required("name", "count")
.string("name", "Item name to deposit (e.g. 'cobblestone')")
.integer("count", "How many to deposit (min 1; capped to what the bot holds)")
.number("x", "Container block X (omit to find nearest by containerName)")
.number("y", "Container block Y")
.number("z", "Container block Z")
.string("containerName", "Container block name to find nearest of; omit when using x,y,z")));

        register("withdraw-items",
                "Move up to `count` of `name` out of a container into the bot's inventory. Caps to what the container "
                        + "holds and what the bot can carry — reports `moved` + `remaining` honestly. Target the "
                        + "container by `containerName` (nearest) or explicit `x,y,z`. Out-of-reach containers error "
                        + "`out_of_reach` until navigation is live. The long-term base loop's retrieval half (restock "
                        + "from the base chest). Returns {ok, moved, requested, remaining, reason}.",
                objectSchema(schema -> schema
.required("name", "count")
.string("name", "Item name to withdraw (e.g. 'oak_log')")
.integer("count", "How many to withdraw (min 1; capped to what the container holds)")
.number("x", "Container block X (omit to find nearest by containerName)")
.number("y", "Container block Y")
.number("z", "Container block Z")
.string("containerName", "Container block name to find nearest of; omit when using x,y,z")));

        // ── Tier G: Crafting & Smelting ──
        register("craft-item",
                "Craft an item using a crafting recipe. Queue-safe (worker-thread + client transactions). Optional "
                        + "tableX/tableY/tableZ name a crafting table for 3x3 recipes (e.g. furnace) — the Fabric "
                        + "body auto-opens the table, crafts, and closes, so multi-step pipelines stay one "
                        + "enqueue-tasks call.",
                objectSchema(schema -> schema
.required("outputItem")
.string("outputItem", "Name of the item to craft")
.integer("amount", "Number of times to craft (default: 1)")
.integer("tableX", "Crafting table X for 3x3 recipes (e.g. furnace) — auto-opens the table here, "
                                + "crafts, closes. Provide together with tableY/tableZ.")
.integer("tableY", "Crafting table Y for 3x3 recipes — provide together with tableX/tableZ")
.integer("tableZ", "Crafting table Z for 3x3 recipes — provide together with tableX/tableY")));

        register("can-craft",
                "Check if the bot can craft a specific item with current inventory",
                objectSchema(schema -> schema
.required("itemName")
.string("itemName", "Name of the item to check")));

        register("smelt-item",
                "Smelt items using a furnace-like block. Queue-safe (worker-thread + client transactions).",
                objectSchema(schema -> schema
.required("x", "y", "z", "inputItem", "fuelItem")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.string("inputItem", "Name of item to smelt")
.integer("inputCount", "Amount of input to smelt (default: 1)")
.string("fuelItem", "Name of fuel item")
.integer("fuelCount", "Amount of fuel to use (default: 1)")
.bool("takeOutput", "Whether to take output when ready (default: true)")
.integer("timeoutMs", "Timeout waiting for output in ms (default: 60000)")));

        // ── Tier H: Survival (combat, sleep, flight, item pickup) ──
        register("attack-entity",
                "Attack a target entity. Equips the best weapon and fights until the target dies, disappears, or the "
                        + "timeout expires. Strategies: 'melee' (chase and hit), 'ranged' (bow, keeps distance), "
                        + "'hit_and_retreat' (strike then back off — good at low health).",
                objectSchema(schema -> schema
.required("entityName")
.string("entityName", "Target entity name (e.g. 'zombie'), player username, or 'nearest_hostile'")
.enumeration("strategy", "Combat strategy (default: 'melee')", "melee", "ranged", "hit_and_retreat")
.integer("timeoutMs", "Combat timeout in ms (default: 15000, max: 60000)")));

        register("sleep-in-bed",
                "Walk to a bed and sleep through the night (skips night and resets spawn). Fails if it's daytime or "
                        + "monsters are nearby.",
                objectSchema(schema -> schema
.number("x", "Bed X coordinate (default: search for nearby beds)")
.number("y", "Bed Y coordinate")
.number("z", "Bed Z coordinate")));

        register("fly-to",
                "Make the bot fly to a specific position",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")));

        // ── Tier I: Composite / Movement ──
        register("move-in-direction",
                "Move the bot in a specific direction for a duration",
                objectSchema(schema -> schema
.required("direction")
.enumeration("direction", "Direction to move", "forward", "back", "left", "right")
.number("duration", "Duration in milliseconds (default: 1000)")));

        register("follow-entity",
                "Follow a mob or player, maintaining a set distance, until timeout or the target disappears",
                objectSchema(schema -> schema
.required("entityName")
.string("entityName", "Mob or player name to follow")
.number("distance", "Distance to maintain (default: 3)")
.integer("timeoutMs", "Auto-stop after this many ms (default: 10000, max: 60000)")));

        register("flee-from",
                "Sprint away from a threat ('nearest_hostile' or a specific mob name) until a minimum distance is reached",
                objectSchema(schema -> schema
.string("fromType", "Mob type to flee from, or 'nearest_hostile' (default)")
.number("minDistance", "Distance to put between bot and threat (default: 20)")
.integer("timeoutMs", "Flee timeout in ms (default: 10000, max: 30000)")));

        register("path-safely",
                "Move to a position while avoiding hostile mobs and hazards (lava, fire, cactus, water, cliffs). "
                        + "Returns a danger assessment of the route.",
                objectSchema(schema -> schema
.required("x", "y", "z")
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate")
.bool("avoidMobs", "Path around known hostile mobs (default: true)")
.array("avoidHazards", "Hazards to avoid (default: [\"lava\",\"fire\",\"cactus\"]; also "
                                + "supports 'water', 'cliff')", STRINGS_SCHEMA)
.number("safeDistance", "Minimum distance to keep from threats (default: 5)")
.integer("maxRetries", "Alternative path attempts on failure (default: 2)")
.integer("timeoutMs", "Per-attempt timeout in ms (default: 30000)")));

        register("find-safe-location",
                "Find the nearest safe spot: standable, lit (mob-spawn-proof), enclosed if possible, and away from "
                        + "hostile mobs",
                objectSchema(schema -> schema
.number("maxDistance", "Search radius (default: 32, max: 48)")
.bool("requireLit", "Require light level > 7 at the spot (default: true)")));

        register("set-movement-profile",
                "Configure the pathfinder's movement behavior: sprinting, parkour jumps, digging, safe drop height, "
                        + "blocks to avoid, AND the cost columns (digCost/placeCost/liquidCost/entityCost) that price "
                        + "detours vs. dig/bridge/swim. Reports the canSprint (food>6) and hasThrowaway (scaffold "
                        + "blocks from inventory) gates.",
                objectSchema(schema -> schema
.bool("allowSprinting", "Allow sprinting (default: true). Clamped to false when the bot is "
                                + "starving (food <= 6) — a starving bot cannot sprint.")
.bool("allowJumping", "Allow parkour jumps (default: true)")
.bool("canDig", "Allow digging through blocks to reach the destination (default: true)")
.integer("maxDropDown", "Max safe fall distance in blocks (default: 4)")
.array("blocksToAvoid", "Block names the pathfinder must never step on/through (e.g. "
                                + "[\"lava\",\"magma_block\",\"cactus\"])", STRINGS_SCHEMA)
.number("digCost", "Flat multiplier on the dig-labor cost (default: 1). Higher = the pathfinder "
                                + "prefers detours over digging. ~2 mirrors Baritone's dig≈2x walk.")
.number("placeCost", "Flat addition per placed block (default: 1). Higher = the pathfinder "
                                + "prefers detours over bridging. ~4 mirrors Baritone's place≈4x walk.")
.number("liquidCost", "Flat addition when moving through liquid (default: 1). ~2 mirrors "
                                + "Baritone's water≈2x walk.")
.number("entityCost", "Per-entity obstruction cost (default: 1). Higher = the pathfinder avoids "
                                + "entity hitboxes.")
.bool("populateScaffold", "Populate the pathfinder's scaffold/throwaway blocks from the bot's "
                                + "inventory (dirt/cobblestone/etc.) so a bridge is only attempted with real material "
                                + "in hand (default: true)")));

        register("recover-stuck",
                "Deterministic stuck-recovery reflex: when the bot is boxed in (head blocked or surrounded by solid "
                        + "blocks) or trapped in a 1-block pit, stops the pathfinder/dig/controls, temporarily enables a "
                        + "digging movement profile, clears up to maxDigBlocks of the safest exit block(s), moves "
                        + "laterally away from the obstruction, restores the previous movement profile, and (if a "
                        + "previousTarget is supplied) retries the original navigation exactly once. Attempts recovery "
                        + "ONCE and reports honestly what it cleared and whether it moved. No teleport fallback.",
                objectSchema(schema -> schema
.integer("maxDigBlocks", "Maximum blocks to dig to clear an exit (default: 3)")
.integer("maxDurationMs", "Hard deadline in ms (default: 10000)")
.enumeration("restoreProfile", "Profile name to leave active after recovery (informational; the "
                                + "prior profile is always restored during the operation)",
                                "safe", "explore", "work", "emergency")
.object("previousTarget", "Original navigation target to retry once after recovery (e.g. the "
                                + "navigate-v2 goal that stalled)", nested -> nested
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate"))));

        register("escape-water",
                "Drowning reflex: when the bot's head is in water and oxygen is low (< threshold), stops "
                        + "pathfinding/digging, ring-searches outward for the nearest standable dry cell, holds jump and "
                        + "moves toward shore until the head block is no longer water. Emits a WATER_ESCAPE event. "
                        + "Usually triggered automatically by the server reflex layer; this tool is the manual override. "
                        + "Reports escaped/reason/startOxygen/endOxygen/durationMs/shore.",
                objectSchema(schema -> schema
.integer("maxDurationMs", "Hard deadline in ms (default: 8000)")
.integer("oxygenThreshold", "Oxygen level below which the reflex is considered urgent "
                                + "(default: 12)")
.integer("ringMax", "Maximum ring-search radius for a dry cell (default: 24)")));

        // ── Tier J: Process & composite-sense (Baritone delegation) ──
        register("get-agent-snapshot",
                "Combined fast sensing: one call returns position, vitals (health/food/oxygen), inventory counts, "
                        + "nearby hostiles/animals/players/drops, world time+weather, AND movement telemetry. Replaces "
                        + "separate get-position + get-vitals + get-world-time + get-weather + list-inventory + scan "
                        + "calls. Each result carries a monotonic stateVersion. Includes the last death (cause/position/"
                        + "age) so a single sense call carries 'you died at X of Y 90s ago' for the planner.",
                objectSchema(schema -> schema
.enumeration("detail", "Detail level: 'fast' (default) omits the local block map; 'full' adds "
                                + "notableBlocks/hazards within mapRadius", "fast", "full")
.integer("mapRadius", "Block-map radius for 'full' detail (default: 0 = skip the map entirely, "
                                + "max: 16)")
.bool("includeEntities", "Include nearby hostiles/animals/players/drops (default: true)")
.bool("includeInventory", "Include inventory + equipment counts (default: true)")
.bool("includeMovement", "Include active-operation telemetry (default: true)")
.bool("includeDeath", "Include the last death record (default: true when a death memory is "
                                + "wired)")));

        register("collect-drops",
                "Collect dropped item entities on the ground near the bot: finds item entities within a radius, "
                        + "pathfinds to each in turn, and waits for pickup. Reports exactly which items were collected "
                        + "(an inventory-delta check, not just 'walked near it') so a dig/gather operation can confirm "
                        + "it actually acquired the loot. Use after mining, tree chopping, combat, or harvesting.",
                objectSchema(schema -> schema
.number("radius", "Search radius for dropped items (default: 8)")
.integer("timeoutMs", "Overall deadline in ms (default: 8000)")
.array("expectedItems", "Only collect drops whose item name is in this list (default: collect "
                                + "all nearby drops)", STRINGS_SCHEMA)));

        register("follow-player",
                "Follow a player (or mob) as a background process: paths to the target entity, rebuilding the goal each "
                        + "tick as the entity moves. Concedes when the entity leaves the server / despawns. The 'follow "
                        + "me' chat idiom. Returns {ok, followed, reason}.",
                objectSchema(schema -> schema
.string("username", "Player username to follow (e.g. 'Steve')")
.integer("entityId", "Entity id to follow (alternative to username)")
.number("range", "Stop within this distance of the target (default: 3, max: 64)")
.integer("maxDurationMs", "Max follow duration in ms (default no cap)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("scan-volume",
                "Scan a box (x1,y1,z1)→(x2,y2,z2) into a compact 2-bit volumetric grid (AIR/SOLID/WATER/AVOID) plus a "
                        + "notableBlocks list of tracked ores/logs/containers. Caps at 32x16x32 cells. `compressed` "
                        + "returns a base64 2-bit grid for spatial reasoning; `block` returns the raw per-cell classes "
                        + "for small boxes. Replaces the 242-cell ASCII map with a real volumetric picture in one call. "
                        + "Every scan feeds the WorldCache (scanning is caching).",
                objectSchema(schema -> schema
.required("x1", "y1", "z1", "x2", "y2", "z2")
.integer("x1", "Box corner X (inclusive)")
.integer("y1", "Box corner Y (inclusive)")
.integer("z1", "Box corner Z (inclusive)")
.integer("x2", "Opposite corner X (inclusive)")
.integer("y2", "Opposite corner Y (inclusive)")
.integer("z2", "Opposite corner Z (inclusive)")
.enumeration("resolution", "compressed = base64 2-bit grid (default); block = raw cell-class "
                                + "array", "compressed", "block")));

        register("resolve-material",
                "Resolve a desired block through its material family using the bot's current inventory: oak_planks → "
                        + "cherry_planks when that's what's held. Returns the resolved block name + the family + a "
                        + "per-family BOM shortfall (empty when the bot holds enough, or holds count-less/unknown "
                        + "quantity). The place-block family/fallbackBlocks helper and the chat 'can I build this with "
                        + "what I have?' read.",
                objectSchema(schema -> schema
.required("block")
.string("block", "The desired block name (e.g. oak_planks) or a bare family name (e.g. planks)")
.integer("count", "How many of the block are needed (default: 1)")));

        register("find-ore-veins",
                "Find ore veins (or any tracked block's connected groups) near the bot via a 26-connectivity flood-fill "
                        + "over the WorldCache's hits + a fresh local scan. Returns [{type, center, count, exposed, "
                        + "nearestStand}] nearest-first. exposedOnly filters to veins with a face-adjacent air cell. "
                        + "maxVeins caps the result. The MineProcess target source and chat's 'where's the iron' — one "
                        + "call, real vein shapes.",
                objectSchema(schema -> schema
.string("oreType", "Block name to group (e.g. 'iron_ore'); omit or 'any' for all tracked ores")
.number("maxDistance", "Max scan radius (blocks) from the bot (default: 64, max: 512)")
.integer("maxVeins", "Max veins to return (default: 8, max: 32)")
.bool("exposedOnly", "Only veins with a face-adjacent air cell (default false)")));

        register("get-to-block",
                "Navigate to the nearest of a target block (or blocks) as a background process: scans the WorldCache + "
                        + "a fresh local scan, paths to the nearest known location, blacklists the closest AND its "
                        + "adjacent instances on path failure (a vein of unreachable blocks blacklists as one cluster), "
                        + "and optionally right-clicks on arrival (open a chest, use a crafting table, open a door). "
                        + "The chat-UX workhorse ('open the nearest chest') and the container tools' navigator. Returns "
                        + "{ok, arrived, rightClicked, position, reason}. Concedes reason:'no_known_targets' → scheduler "
                        + "answers with explore.",
                objectSchema(schema -> schema
.string("block", "Block name to get to (e.g. 'chest'); omit if using `filter`")
.array("filter", "Multiple target block names (variants); the first found wins", STRINGS_SCHEMA)
.number("maxDistance", "Max scan radius (default: 64, max: 512)")
.bool("rightClick", "Right-click the block on arrival (open chest/crafting table/furnace/door; "
                                + "default true for openable blocks)")
.bool("explore", "If no known locations, explore-for-blocks instead of conceding (default false)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("explore",
                "Explore the world as a background process: spirals outward over the CLOSEST UN-CACHED chunks, paths to "
                        + "each, and reports newly cached notables (ores/logs/structures). `direction` biases the "
                        + "spiral (north/south/east/west); `maintainY` holds a Y level. The chat 'go explore and find "
                        + "iron' idiom + mine-blocks's no_known_targets answer. Returns {ok, chunksExplored, "
                        + "notablesFound, reason}. Concedes 'all_explored' when every chunk in range is cached.",
                objectSchema(schema -> schema
.enumeration("direction", "Direction to bias the spiral (default 'none')",
                                "none", "north", "south", "east", "west")
.integer("maxRadius", "Max chunk radius (default: 16 ≈ 256 blocks, max: 64)")
.integer("maintainY", "Hold this Y level on the way; omit to let the path find its own Y")
.integer("maxChunks", "Max chunks to explore before conceding (default: 256, max: 1024)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("guard-area",
                "Guard an area as a background process: holds a post within the radius, melee-attacks hostiles that "
                        + "enter the radius (KillAura-led with melee fallback in the Fabric body), then returns to the "
                        + "post. `strategy:'ranged'` is deferred (melee first). Returns {ok, threatsEngaged, attacks, "
                        + "returnedToPost, reason}.",
                objectSchema(schema -> schema
.number("x", "Post center X (default: the bot position)")
.number("y", "Post center Y")
.number("z", "Post center Z")
.number("radius", "Guard radius (default: 10, max: 64)")
.enumeration("strategy", "Combat strategy (default 'melee'; 'ranged' deferred)", "melee", "ranged")
.integer("maxDurationMs", "Max guard duration in ms (default no cap)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("mine-blocks",
                "Mine `count` of a block (or blocks) as a background process (Baritone #mine + inventory delta "
                        + "polling). Modes: `mine-blocks` (default), `chop-tree` (filter:[*_log] + connected-component "
                        + "targeting), `strip-mine` (branch-mine at yLevel). `torchEvery` places a torch every N blocks "
                        + "if dark. Returns the quantified result (mined/blacklisted/reason). Concedes "
                        + "reason:'no_known_targets' → scheduler answers with explore.",
                objectSchema(schema -> schema
.string("block", "Block name to mine (mine-blocks mode), e.g. 'iron_ore'")
.array("filter", "Multiple target block names (mine-blocks) or the log filter (chop-tree)",
                                STRINGS_SCHEMA)
.integer("count", "How many to acquire (default: 1)")
.enumeration("mode", "Mining mode (default 'mine-blocks')",
                                "mine-blocks", "chop-tree", "strip-mine")
.number("maxDistance", "Max scan radius (default: 64, max: 512)")
.integer("yLevel", "strip-mine: the Y level to hold")
.integer("length", "strip-mine: branch length (the budget)")
.integer("torchEvery", "Place a torch every N blocks of progress if dark (0=off)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("build-structure",
                "Build a structure from a blueprint as a background process (Baritone navigation + placeBlock with "
                        + "per-pass re-diff, MaterialPalette family resolution, shortfall reporting by family). "
                        + "Blueprint = the agent's shelter_3x3.json wire format {blocks:[{x,y,z,block}], digs:[]}. "
                        + "MaterialPalette rewrites oak_planks → cherry_planks when that's held. Returns "
                        + "passes-to-convergence + placed/broken + shortfall.",
                objectSchema(schema -> schema
.required("blueprint")
.raw("blueprint",
                                "Blueprint {blocks:[{x,y,z,block}], digs:[]} — the shelter_3x3.json shape")
.object("origin", "Anchor origin (default: the bot's position)", nested -> nested
.number("x", "X coordinate")
.number("y", "Y coordinate")
.number("z", "Z coordinate"))
.integer("maxPasses", "Max converge passes (default: 8, max: 32)")
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")));

        register("enqueue-tasks",
                "Enqueue a linear, pre-ordered list of process-tool tasks and run them sequentially server-side. In "
                        + "Fabric mode the JAR dispatches each child to its Baritone-backed Java handler. Per-task "
                        + "`onFail` policy: 'continue' (default) or 'abort'. A task may carry a `name`; later tasks can "
                        + "reference its result via $name.field in their args (e.g. place-block at $boat.position). "
                        + "Supported tools: mine-blocks, build-structure, get-to-block, explore, follow-player, "
                        + "guard-area, hunt-hostile (find+goto+KillAura+collect, returns loot), drop-items (drop a named "
                        + "list or all-non-hotbar, optional walkBlocks), break-entity (find+goto+attack until removed, "
                        + "returns position), replace-blocks (Baritone #sel pos1/pos2/replace <from> <with>), "
                        + "place-block. `returnToOrigin` (default true) snapshots the standing point at queue start and "
                        + "navigates the player back to it after the last task, so a single chat request runs "
                        + "end-to-end between LLM calls. Returns aggregate results (completed/skipped/total + per-task "
                        + "results + returnedToOrigin). Instant-ack (chat) or bounded-slice via `wait:{maxMs}` (agent). "
                        + "Queue ownership split: scheduler.py owns the DAG; this tool accepts only linear, pre-ordered "
                        + "lists.",
                objectSchema(schema -> schema
.required("tasks")
.array("tasks", "The linear, pre-ordered task list", QUEUE_TASK_SCHEMA)
.waitObject("Bounded-slice wait (agent idiom); omit for instant-ack (chat)")
.bool("returnToOrigin", "After the last task, navigate the player back to the standing point "
                                + "captured at queue start (default true). Set false to leave the player where the last "
                                + "task ends.")));

        // ── Tier K: Meteor Client integration (soft dependency via reflection) ──
        register("toggle-meteor-module",
                "Toggle, enable, or disable a Meteor Client module by name. Non-blocking — the module's own tick "
                        + "handler does the work (e.g. KillAura attacks on tick, AutoEat eats on tick). This replaces "
                        + "the laggy blocking attack-entity loop for combat: enable KillAura when hostiles are near, "
                        + "disable when clear. Requires Meteor Client installed (soft dependency — returns "
                        + "meteorInstalled:false if absent).",
                objectSchema(schema -> schema
.required("module")
.string("module", "Meteor module name, e.g. 'kill-aura', 'AutoEat', 'Freecam' (case-insensitive)")
.enumeration("action", "Action: 'toggle' (default), 'enable', or 'disable'",
                                "toggle", "enable", "disable")));

        register("set-meteor-keybind",
                "Assign a keyboard hotkey to a Meteor Client module. Persists the keybind via Meteor's Systems.save() "
                        + "so it survives restarts. Use this to configure which key toggles a module (e.g. bind "
                        + "kill-aura to 'C'). Accepts single letters (A-Z, 0-9), function keys (F1-F25), named keys "
                        + "(SPACE, ENTER, LEFT_SHIFT), and numeric keypad (NUM_0-NUM_9). Optional modifiers: shift, "
                        + "ctrl, alt, super. Requires Meteor Client installed.",
                objectSchema(schema -> schema
.required("module", "key")
.string("module", "Meteor module name, e.g. 'kill-aura'")
.string("key", "Key name: single char 'C', or named: 'SPACE', 'F5', 'LEFT_SHIFT', 'NUM_1' "
                                + "(case-insensitive)")
.array("modifiers", "Modifier keys to combine (default: none)",
                                "shift", "ctrl", "alt", "super", "capslock", "numlock")));

        register("list-meteor-modules",
                "List all available Meteor Client modules with their active state and current keybind. Useful for "
                        + "discovering module names and verifying keybind assignments. Optional filter substring "
                        + "matches module names (case-insensitive). Requires Meteor Client installed.",
                objectSchema(schema -> schema
.string("filter", "Substring filter on module name (case-insensitive), e.g. 'kill' or 'auto'")));

        // ── Tier L: former legacy toolset ──
        register("get-capabilities",
                "Report which capability features this mod supports. The agent probes this once at startup and "
                        + "delegates to the server only for caps that are true; when false or absent, the agent uses "
                        + "its own fallback. Caps: toolSelector (dig-block autoTool+telemetry), blockInfo (get-block-info "
                        + "enrichment), inventoryEnrich (list-inventory durability/enchants), biomeFix (biome name in "
                        + "position reads), goals (navigate-v2 GoalSpec), processEngine (20Hz engine — false here; the "
                        + "task-queue worker owns composites), worldCache/scanVolume/findOreVeins, eventFilter "
                        + "(get-events filter/sinceVersion), materialPalette, mine/build/getToBlock/follow/explore/guard "
                        + "process caps (queue handlers), containers, craftWithDeps, costColumns, inventoryOrganize, "
                        + "queueProcess (enqueue-tasks).",
                objectSchema(schema -> { }));

        register("get-events",
                "Get real-time game events since the last poll: damage taken, health changes, food-level changes, hostile mob spawns, "
                        + "deaths, oxygen loss, and weather changes. Poll this regularly to react to threats. Accepts "
                        + "filter:[types] to restrict by event type, and sinceVersion to drop events at-or-before a "
                        + "stateVersion (for precise threat-polling). Poll-and-drain: reading clears the queue. No "
                        + "push/subscription channel (MCP is request/response).",
                objectSchema(schema -> schema
.array("filter", "Only return events whose `type` is in this list (e.g. [\"entityHurt\",\"death\"])",
                                STRINGS_SCHEMA)
.integer("sinceVersion", "Drop events whose stateVersion is at or below this")));

        register("get-last-death",
                "Read the most recent death the bot suffered: cause, position, and age in seconds, plus a per-cause "
                        + "count summary across the remembered history (last 8 deaths). Returns {died:false} when the "
                        + "bot has never died. Use to avoid returning to a lethal coordinate or to recognize a "
                        + "repeated cause (e.g. drowning twice in 5 min).",
                objectSchema(schema -> schema
.bool("includeHistory", "Include the full bounded death history (default: false — only the "
                                + "latest + cause counts)")));

        // ── (item 4 increment 1): persistent memory ──
        register("memory-save",
                "Upsert a record into the persistent memory store (config/hyfuse_memory.json — owned by "
                        + "the mod, survives client restarts). Record = flat JSON {id, kind, position?, note?, "
                        + "...extras} keyed by `id` (last-writer-wins). Suggested kinds: poi (base, chest, "
                        + "furnace), mob (identity + doNotAttack), container (auto-maintained on open), note. "
                        + "`position` is {x,y,z} and enables position queries in memory-read. Note capped 4096 "
                        + "chars, 512 records max.",
                objectSchema(schema -> schema
.required("id")
.string("id", "Unique record id, e.g. 'base', 'chest:728959,64,-1284118', 'mob:creepo'")
.string("kind", "Record kind: poi | mob | container | note | free-form")
.string("note", "Free-form note text (capped 4096 chars)")
.raw("position", "{x,y,z} object — enables memory-read position queries")
.raw("doNotAttack", "mob records: true to encode 'never attack this named mob'")));

        register("memory-read",
                "Read records from the persistent memory store. Filter by kind, id, or exact position "
                        + "(x/y/z); no filters returns all. get-agent-snapshot carries a compact `memory` "
                        + "summary block (record count + kinds + first ids).", 
                objectSchema(schema -> schema
.string("kind", "Filter by record kind")
.string("id", "Filter by record id")
.integer("x", "Filter by exact position X")
.integer("y", "Filter by exact position Y")
.integer("z", "Filter by exact position Z")));

        register("memory-forget",
                "Delete a record from the persistent memory store by id. Returns ok:false honestly when the "
                        + "id was never stored.",
                objectSchema(schema -> schema
.required("id")
.string("id", "Record id to delete")));

        // ── The two-layer brain ──
        register("get-playbook",
                "Returns the server-agnostic playbook (survival priorities, tool doctrine, memory "
                        + "discipline) from config/hyfuse/INTELLIGENCE.md (user-editable between "
                        + "releases) or the packaged default. Call this FIRST in a fresh chat, alongside "
                        + "get-agent-snapshot, to prime context before acting.",
                objectSchema(schema -> { }));

        register("journal-read",
                "Read the per-server journal (goal, base layout, server facts, players, event log — "
                        + "the things that would be WRONG on a different server). Paged: pass offset "
                        + "(from the previous response) and maxChars (default 4000, cap 16000). "
                        + "Hard-capped at 100 KB total. Requires being connected to a server.",
                objectSchema(schema -> schema
.integer("offset", "Char offset to read from (previous response tells the next)")
.integer("maxChars", "Max chars per page (default 4000, cap 16000)")));

        register("journal-append",
                "Append an entry newest-first under a section of the per-server journal (1-10; default "
                        + "9 = rolling event log; 1 = identity/goal; 2 = server facts; 8 = plans). "
                        + "Entry text ≤ 2000 chars; journal hard cap 100 KB (a journal_full rejection "
                        + "mutates nothing — prune stale entries first). Creates the journal from the "
                        + "template on first append. Keep entries tight: facts, not prose.",
                objectSchema(schema -> schema
.required("text")
.string("text", "Entry text (≤ 2000 chars), newest-first in its section")
.integer("section", "Section number 1-10 (default 9 = event log)")));

        // ── (item 4 increment 3, ): reactive policy layer ──
        register("policy-save",
                "Upsert a policy row into the reactive policy layer. Row: "
                        + "{id, kind:'policy', trigger, threshold?, action?, args?, cooldownMs?, enabled}. "
                        + "trigger = EventBuffer event type (entityHurt, health, food, oxygen, death, "
                        + "entitySpawn, weatherUpdate); the event's `health` payload carries the level "
                        + "for food/health/entityHurt. threshold = fire at-or-below that level (entitySpawn: "
                        + "1 = any sighting). action = a policy-rail tool (eat-food, flee-from, escape-water, "
                        + "toggle-meteor-module, place-torch, goto-coords, mine-blocks, get-to-block, "
                        + "explore, guard-area, follow-player, hunt-hostile, collect-drops, deposit-items, "
                        + "withdraw-items, drop-items) or omitted for audit-only. args = action args with "
                        + "{entity}/{cause}/{health}/{message} substitution. cooldownMs default 15000 "
                        + "(death default 300000). Edits take effect on the next event — no restart.",
                objectSchema(schema -> schema
.required("id", "trigger")
.string("id", "Unique policy id (e.g. 'survival:eat')")
.string("trigger", "EventBuffer event type to match")
.integer("threshold", "Fire at-or-below this level (food/health/entityHurt); entitySpawn 1 = any sighting")
.string("action", "Policy-rail tool to run, or omit for audit-only")
.raw("args", "JSON args for the action ({entity}/{cause}/{health}/{message} substituted)")
.integer("cooldownMs", "Per-policy re-fire guard (default 15000; death 300000)")
.bool("enabled", "false = stored but never fires")));

        register("policy-read",
                "List the reactive policy layer's rows (all by default, one by id) plus the audit "
                        + "ring (last 100 firings: trigger, policy, action, result, ok, timestamp). "
                        + "The audit shows WHY the bot ate/fled/escaped — audit-visible value.",
                objectSchema(schema -> schema
.string("id", "Policy id to read (omit for all rows)")));

        register("policy-forget",
                "Delete a policy row by id. Honest ok:false when the id was never stored. "
                        + "Forgetting every row restores the default seeds on the next boot.",
                objectSchema(schema -> schema
.required("id")
.string("id", "Policy id to delete")));

        register("standing-start",
                "Start (or resume) a named standing process that runs goal cycles in the background "
                        + "without LLM round-trips. Types: mine-and-deposit "
                        + "(mine-blocks -> goto base chest -> deposit, repeated), guard (guard-area "
                        + "bounded cycles at a post). Stop conditions: stopWhen {totalItems, "
                        + "maxDurationMs}. Recovery: failed cycles back off (x2, cap x5); 3 "
                        + "consecutive failures park the process; death pauses it. Definitions "
                        + "persist to memory (kind 'process') but never auto-resume after restart "
                        + "- an explicit standing-start resumes.",
                objectSchema(schema -> schema
.required("name", "type")
.string("name", "Unique process name (no ':' or ',')")
.string("type", "mine-and-deposit | guard")
.raw("args", "Process args: mine-and-deposit {block|filter, item, countPerCycle, chest}, guard {x,y,z,radius}")
.raw("stopWhen", "{totalItems?, maxDurationMs?} stop conditions")
.integer("intervalMs", "Inter-cycle interval (default 5000)")));

        register("standing-status",
                "Status of one standing process (name) or all (no args): state RUNNING/DONE/PARKED/"
                        + "STOPPED/INTERRUPTED, cycles, totalItems, consecutiveFailures, lastError, "
                        + "lastMessage, last cycle result.",
                objectSchema(schema -> schema
.string("name", "Process name (omit for all)")));

        register("standing-stop",
                "Gracefully stop a standing process: finishes or aborts the current cycle, final "
                        + "status preserved (STOPPED). wait=true blocks up to 120s for a clean stop.",
                objectSchema(schema -> schema
.required("name")
.string("name", "Process name")
.enumeration("wait", "true to wait for a clean stop (default false)")));

        register("get-current-action",
                "Read-only telemetry of the currently running queue or composite operation: kind (enqueue-tasks or a "
                        + "standalone composite like mine-blocks), start time, last-progress timestamp, elapsed ms, "
                        + "and queue detail (current task index/tool, total tasks). Returns {active:false} when idle.",
                objectSchema(schema -> { }));

        // ── (agent-mode increment 1): user-only loop lifecycle ──
        register("agent-start",
                "Start the embedded agent loop: the mod "
                        + "drives an OpenAI-compatible LLM endpoint (config/hyfuse/agent.json: "
                        + "url, apiKey, model, maxIterations) with the playbook as system "
                        + "prompt and the full MCP tool list; it parses tool calls, dispatches "
                        + "them, and loops until the model replies with plain text or the "
                        + "budget is spent. Local-only. Requires agent.json (absent/blank "
                        + "url = agent_disabled). Poll agent-status for progress.",
                objectSchema(schema -> { }));
        register("agent-stop",
                "Request the running agent loop to stop after the current iteration "
                        + "(local-only). Poll agent-status until active=false.",
                objectSchema(schema -> { }));
        register("agent-status",
                "Read-only status of the embedded agent loop: configured, active, "
                        + "iterations, toolCallsTotal, lastError, lastAssistant (truncated).",
                objectSchema(schema -> { }));

        register("craft-with-deps",
                "Resolve what it takes to craft an item recursively: returns the `subCrafts` trail (deepest-first — "
                        + "gather/craft the leaves before the root) plus the gather-leaf `shortfall` after subtracting "
                        + "the bot's current inventory. Handles crafting AND smelting (raw_iron → iron_ingot via "
                        + "furnace). Cycles are detected (iron ← iron_block ← iron) and depth-capped at 4. This is a "
                        + "READ — it tells you the plan; call `craft-item` (or `smelt-item`) for each step.",
                objectSchema(schema -> schema
.required("item")
.string("item", "The item name to resolve (e.g. 'iron_pickaxe', 'chest', 'bucket')")
.integer("count", "How many of the item to make (default 1)")
.integer("maxDepth", "Max recursion depth (default 4, max 8)")));
    }

    // ── Registry API ──────────────────────────────────────────────────────

    private McpToolRegistry() {
    }

    private static void register(String name, String description, JsonObject inputSchema) {
        TOOLS.put(name, new Tool(name, description, inputSchema));
    }

    /** All registered tools in registration order. */
    public static Collection<Tool> all() {
        return TOOLS.values();
    }

    /** Look up a tool by dispatch name, or null when unknown. */
    public static Tool get(String name) {
        return name == null ? null : TOOLS.get(name);
    }

    /** {"tools":[...]} JSON-RPC result body for tools/list. */
    public static JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();
        for (Tool tool : TOOLS.values()) {
            tools.add(tool.toJson());
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    // ── JSON Schema builder ───────────────────────────────────────────────

    /**
     * Fluent builder for a root object JSON Schema. The Consumer fills in
     * properties; {@code required} is declared first so the schema reads in a
     * stable order in tools/list output. Any property NOT listed via
     * {@link #required(String...)} is optional (mirrors Zod.optional()).
     */
    static final class SchemaBuilder {
        private final JsonObject schema = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final JsonArray required = new JsonArray();

        SchemaBuilder() {
            schema.addProperty("type", "object");
            schema.add("properties", properties);
            schema.add("required", required);
        }

        SchemaBuilder required(String... names) {
            for (String name : names) {
                required.add(name);
            }
            return this;
        }

        SchemaBuilder number(String name, String description) {
            return property(name, simpleProperty("number", description));
        }

        SchemaBuilder integer(String name, String description) {
            return property(name, simpleProperty("integer", description));
        }

        SchemaBuilder string(String name, String description) {
            return property(name, simpleProperty("string", description));
        }

        SchemaBuilder bool(String name, String description) {
            return property(name, simpleProperty("boolean", description));
        }

        SchemaBuilder enumeration(String name, String description, String... values) {
            JsonObject property = simpleProperty("string", description);
            JsonArray enums = new JsonArray();
            for (String value : values) {
                enums.add(value);
            }
            property.add("enum", enums);
            return property(name, property);
        }

        /** Array param with a prebuilt items subschema (e.g. STRINGS_SCHEMA, POSITION_SCHEMA). */
        SchemaBuilder array(String name, String description, JsonObject itemsSchema) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "array");
            property.addProperty("description", description);
            property.add("items", itemsSchema);
            return property(name, property);
        }

        /** Array param of string enum values. */
        SchemaBuilder array(String name, String description, String... enumValues) {
            JsonObject items = new JsonObject();
            items.addProperty("type", "string");
            JsonArray enums = new JsonArray();
            for (String value : enumValues) {
                enums.add(value);
            }
            items.add("enum", enums);
            JsonObject property = new JsonObject();
            property.addProperty("type", "array");
            property.addProperty("description", description);
            property.add("items", items);
            return property(name, property);
        }

        /** Nested object param with its own builder (all nested props optional unless.required'd). */
        SchemaBuilder object(String name, String description, Consumer<SchemaBuilder> configurer) {
            SchemaBuilder nested = new SchemaBuilder();
            configurer.accept(nested);
            JsonObject property = new JsonObject();
            property.addProperty("description", description);
            property.add("type", nested.schema.get("type"));
            property.add("properties", nested.schema.get("properties"));
            if (nested.required.size() > 0) {
                property.add("required", nested.required);
            }
            return property(name, property);
        }

        /** The {maxMs: integer} bounded-slice wait param shared by process tools. */
        SchemaBuilder waitObject(String description) {
            return object("wait", description, nested -> nested
.integer("maxMs", "Maximum milliseconds to wait for the result (100-55000)"));
        }

        /** Free-form param: plain object schema with no property constraints (mcpo-safe). */
        SchemaBuilder raw(String name, String description) {
            JsonObject property = new JsonObject();
            property.addProperty("type", "object");
            property.addProperty("description", description);
            return property(name, property);
        }

        private SchemaBuilder property(String name, JsonObject property) {
            properties.add(name, property);
            return this;
        }

        private static JsonObject simpleProperty(String type, String description) {
            JsonObject property = new JsonObject();
            property.addProperty("type", type);
            property.addProperty("description", description);
            return property;
        }

        JsonObject build() {
            return schema;
        }
    }

    static JsonObject objectSchema(Consumer<SchemaBuilder> configurer) {
        SchemaBuilder builder = new SchemaBuilder();
        configurer.accept(builder);
        return builder.build();
    }
}
