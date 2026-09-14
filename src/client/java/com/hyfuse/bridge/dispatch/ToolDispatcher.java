package com.hyfuse.bridge.dispatch;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hyfuse.bridge.HyFuseClient;
import com.hyfuse.bridge.agent.AgentLoop;
import com.hyfuse.bridge.chat.ChatBuffer;
import com.hyfuse.bridge.sense.DeathMemory;
import com.hyfuse.bridge.sense.EventBuffer;
import com.hyfuse.bridge.sense.BrainStore;
import com.hyfuse.bridge.sense.MemoryStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.core.Holder;
import net.minecraft.util.context.ContextMap;
import net.minecraft.util.Unit;
import com.mojang.datafixers.util.Either;
import java.util.Optional;
import java.util.stream.Stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ToolDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("hyfuse");
    private static final double DEFAULT_SCAN_RADIUS = 16;
    private static final int DEFAULT_FIND_BLOCK_COUNT = 1;
    private static final int MAX_FIND_BLOCK_COUNT = 256;

    @FunctionalInterface
    private interface ToolHandler {
        JsonObject handle(Minecraft client, JsonObject args) throws Throwable;
    }

    private static final Map<String, ToolHandler> HANDLERS = buildHandlers();

    /** The single ToolDispatcher instance (the MCP server owns one;
     * handlers reach it via DISPATCHER for the agent loop's dispatches). */
    private static final ToolDispatcher DISPATCHER = new ToolDispatcher();

    /**
     * Names of the tools with a wired handler. The smoke suite
     * diffs this against tools/list so a registered-but-UNHANDLED tool
     * Fails the build, not the field.
     */
    public static java.util.Set<String> handlerNames() {
        return java.util.Collections.unmodifiableSet(HANDLERS.keySet());
    }

    private static Map<String, ToolHandler> buildHandlers() {
        return Map.ofEntries(
                // ── Navigation (existing, proven) ──
                entry("navigate-v2", ToolDispatcher::navigate),
                entry("cancel-current-action", (c, a) -> dispatchBaritone(c, "#stop", "stop")),

                // ── Tier A: Perception / World State ──
                entry("get-world-time", ToolDispatcher::getWorldTime),
                entry("get-weather", ToolDispatcher::getWeather),
                entry("get-block-light", ToolDispatcher::getBlockLight),
                entry("detect-gamemode", ToolDispatcher::detectGamemode),
                entry("look-at", ToolDispatcher::lookAt),
                entry("send-chat", ToolDispatcher::sendChat),
                entry("raycast-look", ToolDispatcher::raycastLook),
                entry("get-block-info", ToolDispatcher::getBlockInfo),
                entry("get-blocks", ToolDispatcher::getBlocks),
                entry("find-blocks", ToolDispatcher::findBlocks),

                // ── Tier A: Entities ──
                entry("find-entity", ToolDispatcher::findEntity),
                entry("scan-nearby-entities", ToolDispatcher::scanNearbyEntities),

                // ── Tier B: Block Interaction ──
                entry("dig-block", ToolDispatcher::digBlock),
                entry("place-block", ToolDispatcher::placeBlock),
                entry("use-item-on-block", ToolDispatcher::useItemOnBlock),
                entry("entity-interact", ToolDispatcher::entityInteract),
                entry("bucket-fluid", ToolDispatcher::bucketFluid),
                entry("farm-plot", ToolDispatcher::farmPlot),
                entry("villager-trade", ToolDispatcher::villagerTrade),
                entry("scan-area", ToolDispatcher::scanArea),

                // ── Tier C: Inventory Basics ──
                entry("list-inventory", ToolDispatcher::listInventory),
                entry("find-item", ToolDispatcher::findItem),
                entry("equip-item", ToolDispatcher::equipItem),

                // ── Tier D: Chat ──
                entry("read-chat", ToolDispatcher::readChat),
                // ── Tier E: Survival & Inventory Management ──
                entry("eat-food", ToolDispatcher::eatFood),
                entry("place-torch", ToolDispatcher::placeTorch),
                entry("auto-equip-best-gear", ToolDispatcher::autoEquipBestGear),
                entry("move-item", ToolDispatcher::moveItem),
                entry("organize-inventory", ToolDispatcher::organizeInventory),
                // ── Tier F: Container Operations ──
                entry("open-container", ToolDispatcher::openContainer),
                entry("deposit-items", ToolDispatcher::depositItems),
                entry("withdraw-items", ToolDispatcher::withdrawItems),
                // ── Tier G: Crafting & Smelting ──
                entry("craft-item", ToolDispatcher::craftItemStandalone),
                entry("can-craft", ToolDispatcher::canCraft),
                entry("smelt-item", ToolDispatcher::smeltItemStandalone),

                // Tier H: Survival (combat, sleep, flight, item pickup)
                entry("attack-entity", ToolDispatcher::attackEntity),
                entry("sleep-in-bed", ToolDispatcher::sleepInBed),
                entry("fly-to", ToolDispatcher::flyTo),

                // Tier I: Composite / Movement
                entry("move-in-direction", ToolDispatcher::moveInDirection),
                entry("follow-entity", ToolDispatcher::followEntity),
                entry("flee-from", ToolDispatcher::fleeFrom),
                entry("path-safely", ToolDispatcher::pathSafely),
                entry("find-safe-location", ToolDispatcher::findSafeLocation),
                entry("set-movement-profile", ToolDispatcher::setMovementProfile),
                entry("recover-stuck", ToolDispatcher::recoverStuck),
                entry("escape-water", ToolDispatcher::escapeWater),

                // Tier J: Process & composite-sense (Baritone delegation + Java primitives + NYI stubs)
                entry("get-agent-snapshot", ToolDispatcher::getAgentSnapshot),
                entry("collect-drops", ToolDispatcher::collectDrops),
                entry("follow-player", ToolDispatcher::followPlayerStandalone),
                entry("scan-volume", ToolDispatcher::scanVolume),
                entry("resolve-material", ToolDispatcher::resolveMaterial),
                entry("find-ore-veins", ToolDispatcher::findOreVeins),
                entry("get-to-block", ToolDispatcher::getToBlockStandalone),
                entry("explore", ToolDispatcher::exploreStandalone),
                entry("guard-area", ToolDispatcher::guardAreaStandalone),
                entry("mine-blocks", ToolDispatcher::mineBlocksStandalone),
                entry("build-structure", ToolDispatcher::buildStructureStandalone),
                entry("enqueue-tasks", ToolDispatcher::enqueueTasks),

                // Tier K: Meteor Client integration (soft dependency via reflection)
                entry("toggle-meteor-module", ToolDispatcher::toggleMeteorModule),
                entry("set-meteor-keybind", ToolDispatcher::setMeteorKeybind),
                entry("list-meteor-modules", ToolDispatcher::listMeteorModules),

                // Tier L: former legacy toolset, re-implemented in Java (pure reads
                // the all-in-one migration; pure reads over the shared sense
                // buffers — no Minecraft world access needed).
                entry("get-capabilities", ToolDispatcher::getCapabilities),
                entry("get-events", ToolDispatcher::getEvents),
                entry("get-last-death", ToolDispatcher::getLastDeath),

                // Remainder: get-current-action — pure
                // read over the queue-runner's telemetry snapshot.
                entry("get-current-action", ToolDispatcher::getCurrentAction),
                entry("craft-with-deps", ToolDispatcher::craftWithDeps),

                // (item 4 increment 1): persistent memory.
                // Additive tools over HyFuseClient.memoryStore() — no Minecraft
                // world access needed, so they never touch the proven hot paths.
                entry("memory-save", ToolDispatcher::memorySave),
                entry("memory-read", ToolDispatcher::memoryRead),
                entry("memory-forget", ToolDispatcher::memoryForget),

                // The two-layer brain. get-playbook serves the
                // server-agnostic INTELLIGENCE.md (runtime read from the
                // config dir — user edits between releases without a
                // redeploy); journal-read/append serve the per-server
                // markdown journal (100 KB cap enforced mod-side).
                // None need Minecraft world access; server-id resolution
                // reads the current-server entry only.
                entry("get-playbook", ToolDispatcher::getPlaybook),
                entry("journal-read", ToolDispatcher::journalRead),
                entry("journal-append", ToolDispatcher::journalAppend),

                // (item 4 increment 2, ): standing
                // processes. standing-start/status are light client-thread
                // reads/writes of the supervisor registry; standing-stop
                // uses a dedicated single-thread executor (its bounded
                // wait-loop sleeps — never on the client thread, and it
                // must not hit the QUEUE_ACTIVE gate: stopping mid-cycle
                // must not QUEUE_BUSY).
                entry("standing-start", ToolDispatcher::standingStart),
                entry("standing-status", ToolDispatcher::standingStatus),
                entry("standing-stop", ToolDispatcher::standingStop),
                // ── (agent-mode increment 1): user-only lifecycle ──
                entry("agent-start", ToolDispatcher::agentStart),
                entry("agent-stop", ToolDispatcher::agentStop),
                entry("agent-status", ToolDispatcher::agentStatus),

                // (item 4 increment 3, ): reactive
                // policy layer. policy-save/forget are flat MemoryStore
                // writes; policy-read folds the engine's audit ring in.
                entry("policy-save", ToolDispatcher::policySave),
                entry("policy-read", ToolDispatcher::policyRead),
                entry("policy-forget", ToolDispatcher::policyForget),

                //: goto-coords was registered in
                // McpToolRegistry (advertised via tools/list) but never
                // added here, so HANDLERS.get returned null and the live
                // 1.2.6 deploy answered UNKNOWN_TOOL for the advertised
                // tool. queueGotoCoords already served the queue-rail case
                // (dispatchQueueTask) and the standalone-composite
                // predicate; this entry wires the standalone handler.
                entry("goto-coords", ToolDispatcher::queueGotoCoords)
        );
    }

    private static Map.Entry<String, ToolHandler> entry(String name, ToolHandler handler) {
        return Map.entry(name, handler);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier L: legacy toolset — pure reads over the shared sense
    // buffers (HyFuseClient.EVENT_BUFFER / DEATH_MEMORY); no client/world
    // dereference needed.
    // ──────────────────────────────────────────────────────────────────────

    /** get-capabilities: static map of what this Fabric build supports. */
    private static JsonObject getCapabilities(Minecraft client, JsonObject args) {
        JsonObject caps = new JsonObject();
        // M1 pure-read enrichment caps — all implemented in this dispatcher.
        caps.addProperty("toolSelector", true); // dig-block autoTool + toolUsed telemetry
        caps.addProperty("blockInfo", true); // get-block-info destroySpeed/light/states
        caps.addProperty("inventoryEnrich", true); // list-inventory durability
        caps.addProperty("biomeFix", false); // snapshot carries position but no biome name (honest)
        caps.addProperty("goals", true); // navigate-v2 goal param (Baritone goal taxonomy)
        caps.addProperty("processEngine", false); // no 20 Hz ProcessEngine here; the queue worker owns composites
        caps.addProperty("worldCache", true); // scan-volume + find-ore-veins read layers
        caps.addProperty("scanVolume", true);
        caps.addProperty("findOreVeins", true);
        caps.addProperty("eventFilter", true); // get-events filter + sinceVersion
        caps.addProperty("materialPalette", true); // place-block family/fallbackBlocks
        caps.addProperty("mineProcess", true); // mine-blocks queue handler 
        caps.addProperty("buildProcess", true); // build-structure queue handler 
        caps.addProperty("containers", true); // open/deposit/withdraw container ops
        caps.addProperty("craftWithDeps", true); // Finale: craft-with-deps now served from Java
        caps.addProperty("getToBlockProcess", true); // get-to-block queue handler 
        caps.addProperty("followProcess", true); // follow-player queue handler 
        caps.addProperty("exploreProcess", true); // explore queue handler 
        caps.addProperty("guardProcess", true); // guard-area queue handler (live-verified v0.19.0, KillAura 
        caps.addProperty("costColumns", true); // set-movement-profile dig/place/liquid/entity costs
        caps.addProperty("inventoryOrganize", true); // move-item + organize-inventory
        caps.addProperty("queueProcess", true); // enqueue-tasks linear queue
        caps.addProperty("standingProcesses", true); // Supervisor + cycle serialization

        // T4.2 presence keys — runtime mod presence (not feature flags),
        // consulted by AgentLoop's Ring-2 gating (docs/AGENT_TOOLSET.md).
        caps.addProperty("meteorPresent", classExists("meteordevelopment.meteorclient.systems.Systems"));
        caps.addProperty("baritonePresent", classExists("baritone.api.BaritoneAPI"));

        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.add("capabilities", caps);
        return result;
    }

    /** True when the named class is on the classpath (mod presence probe). */
    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** get-events: poll-and-drain of the shared EventBuffer with optional filter/sinceVersion. */
    private static JsonObject getEvents(Minecraft client, JsonObject args) {
        List<EventBuffer.Event> events = HyFuseClient.EVENT_BUFFER.drain();
        // M3 filter:[types] — keep only events whose type is in the list.
        if (args.has("filter") && args.get("filter").isJsonArray()) {
            java.util.Set<String> wanted = new java.util.HashSet<>();
            for (var f: args.getAsJsonArray("filter")) {
                wanted.add(f.getAsString());
            }
            events.removeIf(e -> !wanted.contains(e.type));
        }
        // M3 sinceVersion — drop events at-or-before the version.
        if (args.has("sinceVersion") && args.get("sinceVersion").isJsonPrimitive()) {
            long since = args.get("sinceVersion").getAsLong();
            events.removeIf(e -> e.stateVersion <= since);
        }
        JsonArray eventsJson = new JsonArray();
        int urgent = 0;
        for (EventBuffer.Event e : events) {
            JsonObject o = new JsonObject();
            o.addProperty("type", e.type);
            o.addProperty("timestamp", e.timestamp);
            o.addProperty("stateVersion", e.stateVersion);
            if (e.entity != null) o.addProperty("entity", e.entity);
            if (e.cause != null) o.addProperty("cause", e.cause);
            if (e.health != null) o.addProperty("health", e.health);
            if (e.hostile != null) o.addProperty("hostile", e.hostile);
            if (e.weather != null) o.addProperty("weather", e.weather);
            if (e.message != null) o.addProperty("message", e.message);
            eventsJson.add(o);
            if (("entityHurt".equals(e.type) && "self".equals(e.entity))
                    || "death".equals(e.type)
                    || ("entitySpawn".equals(e.type) && Boolean.TRUE.equals(e.hostile))) {
                urgent++;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("count", eventsJson.size());
        result.addProperty("urgent", urgent);
        result.add("events", eventsJson);
        return result;
    }

    /** get-last-death: read the shared DeathMemory (latest + causeCounts + optional history). */
    private static JsonObject getLastDeath(Minecraft client, JsonObject args) {
        DeathMemory memory = HyFuseClient.DEATH_MEMORY;
        DeathMemory.DeathRecord last = memory.last();
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        if (last == null) {
            result.addProperty("died", false);
            result.add("causeCounts", new JsonObject());
            return result;
        }
        long now = System.currentTimeMillis();
        JsonObject lastDeath = new JsonObject();
        lastDeath.addProperty("id", last.id);
        lastDeath.addProperty("cause", last.cause);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", last.x);
        pos.addProperty("y", last.y);
        pos.addProperty("z", last.z);
        lastDeath.add("position", pos);
        if (last.message != null) lastDeath.addProperty("message", last.message);
        lastDeath.addProperty("ageSeconds", (now - last.timestamp) / 1000);
        lastDeath.addProperty("timestamp", last.timestamp);

        JsonObject causeCounts = new JsonObject();
        memory.causeCounts().forEach(causeCounts::addProperty);

        result.addProperty("died", true);
        result.add("lastDeath", lastDeath);
        result.add("causeCounts", causeCounts);
        result.addProperty("totalDeaths", memory.size());
        if (args.has("includeHistory") && args.get("includeHistory").getAsBoolean()) {
            JsonArray history = new JsonArray();
            for (DeathMemory.DeathRecord d : memory.history()) {
                JsonObject h = new JsonObject();
                h.addProperty("id", d.id);
                h.addProperty("cause", d.cause);
                JsonObject p = new JsonObject();
                p.addProperty("x", d.x);
                p.addProperty("y", d.y);
                p.addProperty("z", d.z);
                h.add("position", p);
                h.addProperty("ageSeconds", (now - d.timestamp) / 1000);
                history.add(h);
            }
            result.add("history", history);
        }
        return result;
    }

    /**
     * get-current-action: read-only telemetry of the currently running queue /
     * composite operation — kind, start time, last progress, elapsed ms, and
     * queue detail (current task index/tool, total tasks, named task). Returns
     * {active:false} when idle. Java shape mirrors the legacy describeOperation()
     * (§4.3) plus queue-runner detail; values are honest to the queue worker
     * (no legacy operation registry exists here).
     */
    private static JsonObject getCurrentAction(Minecraft client, JsonObject args) {
        return QueueTelemetry.snapshot();
    }

    /**
     * craft-with-deps: READ-only recursive recipe resolution (see
     * {@link CraftResolver}). Returns the deepest-first subCrafts
     * trail (craft/smelt/gather per step, with ingredients) plus the
     * gather-leaf shortfall after subtracting current inventory. Craft and
     * smelt recipes come from the client recipe book; the smelting table
     * below adds ore→ingot + common smelts the display entries may miss.
     * Depth-capped (default 4, max 8); cycles detected and reported, not
     * expanded. The resolution core is CraftResolver (framework-free).
     */
    private static JsonObject craftWithDeps(Minecraft client, JsonObject args) {
        String item = simpleName(requiredString(args, "item"));
        int count = optionalInt(args, "count", 1);
        if (count < 1) count = 1;
        int maxDepth = optionalInt(args, "maxDepth", CraftResolver.DEFAULT_CRAFT_DEPTH);
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 8) maxDepth = 8;

        Map<String, List<CraftResolver.ResolvedRecipe>> recipeCache = new HashMap<>();
        java.util.function.Function<String, List<CraftResolver.ResolvedRecipe>> recipeLookup = name -> {
            synchronized (recipeCache) {
                if (recipeCache.containsKey(name)) return recipeCache.get(name);
            }
            List<CraftResolver.ResolvedRecipe> resolved = resolveRecipesFor(client, name);
            synchronized (recipeCache) {
                recipeCache.put(name, resolved);
            }
            return resolved;
        };

        CraftResolver.CraftNode tree = CraftResolver.resolveCraftTree(item, count, recipeLookup::apply, maxDepth);
        List<CraftResolver.TrailEntry> trail = CraftResolver.flattenTrail(tree);
        Map<String, Integer> inventory = new HashMap<>();
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                inventory.merge(simpleName(itemRegistryName(stack)), stack.getCount(), Integer::sum);
            }
        }
        Map<String, Integer> shortfall = CraftResolver.computeShortfall(tree, inventory);

        JsonArray serialTrail = new JsonArray();
        for (CraftResolver.TrailEntry t : trail) {
            JsonObject step = new JsonObject();
            step.addProperty("item", t.item);
            step.addProperty("count", t.count);
            step.addProperty("kind", t.kind);
            JsonArray ings = new JsonArray();
            for (CraftResolver.Ingredient ing : t.ingredients) {
                JsonObject g = new JsonObject();
                g.addProperty("name", ing.name);
                g.addProperty("count", ing.count);
                ings.add(g);
            }
            step.add("ingredients", ings);
            step.addProperty("depth", t.depth);
            serialTrail.add(step);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("target", item);
        result.addProperty("count", count);
        result.addProperty("craftable", tree.recipe != null);
        JsonObject rootRecipe = null;
        if (tree.recipe != null) {
            rootRecipe = new JsonObject();
            rootRecipe.addProperty("kind", tree.recipe.smelt ? "smelt": "craft");
            rootRecipe.addProperty("resultCount", tree.recipe.resultCount);
            JsonArray ings = new JsonArray();
            for (CraftResolver.Ingredient ing : tree.recipe.ingredients) {
                JsonObject g = new JsonObject();
                g.addProperty("name", ing.name);
                g.addProperty("count", ing.count);
                ings.add(g);
            }
            rootRecipe.add("ingredients", ings);
        }
        result.add("rootRecipe", rootRecipe == null ? JsonNull.INSTANCE: rootRecipe);
        result.add("subCrafts", serialTrail);
        JsonObject sf = new JsonObject();
        for (Map.Entry<String, Integer> e : shortfall.entrySet()) sf.addProperty(e.getKey(), e.getValue());
        result.add("shortfall", sf);
        result.addProperty("cycle", CraftResolver.hasCycle(tree));
        return result;
    }

    /**
     * Resolve recipes for an output item from the client recipe book (craft
     * display entries) plus the smelting table below (the legacy SMELTING_TABLE
     * parity — minecraft-data-style ore→ingot + common smelts). Filters
     * storage-block decompression recipes (single ingredient yielding 9/4 of
     * a base material from its "_block" form) so the resolver treats raw
     * materials as gather/smelt leaves rather than recursing into the block
     * form (the iron ← iron_block ← iron cycle source).
     */
    private static List<CraftResolver.ResolvedRecipe> resolveRecipesFor(Minecraft client, String itemName) {
        List<CraftResolver.ResolvedRecipe> out = new ArrayList<>();
        int scanned = 0;
        for (RecipeDisplayEntry entry : collectAllDisplayEntries(client.player)) {
            RecipeDisplay display = entry.display();
            if (!(display instanceof ShapedCraftingRecipeDisplay)
                    && !(display instanceof ShapelessCraftingRecipeDisplay)) {
                continue;
            }
            scanned++;
            // SlotDisplayToItemName returns NAMESPACED names
            // ("minecraft:iron_pickaxe") while itemName arrives simple-named
            // from craftWithDeps ("iron_pickaxe") — the old equals() never
            // matched, so every craft item resolved to zero recipes. Normalize
            // to simple names on BOTH sides (result + ingredients) so the
            // CraftResolver world (SMELTING_TABLE keys, inventory shortfall
            // keys, isStorageBlockDecompression suffix checks — all simple)
            // stays consistent.
            String resultName = simpleName(slotDisplayToItemName(display.result()));
            if (resultName == null || resultName.isEmpty() || !resultName.equals(itemName)) continue;
            List<String> ingredientNames = getDisplayIngredientNames(display, client.player);
            if (ingredientNames.isEmpty()) continue;
            List<String> simpleIngredientNames = new ArrayList<>(ingredientNames.size());
            for (String n : ingredientNames) {
                String s = simpleName(n);
                if (s != null && !s.isEmpty()) simpleIngredientNames.add(s);
            }
            if (simpleIngredientNames.isEmpty()) continue;
            Map<String, Integer> counts = countIngredients(simpleIngredientNames);
            List<CraftResolver.Ingredient> ingredients = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                ingredients.add(new CraftResolver.Ingredient(e.getKey(), e.getValue()));
            }
            int resultCount = getResultCountFor(display);
            CraftResolver.ResolvedRecipe r = new CraftResolver.ResolvedRecipe(
                    resultName, resultCount, ingredients, false);
            if (!isStorageBlockDecompression(r)) out.add(r);
        }
        // Instrumentation: surface empty lookups for live diagnosis.
        if (out.isEmpty()) {
            com.hyfuse.bridge.HyFuseClient.LOGGER.debug(
                    " ResolveRecipesFor: no crafting recipes for {} ({} display entries scanned)",
                    itemName, scanned);
        }
        out.addAll(smeltingRecipesFor(itemName));
        return out;
    }

    /**
     * Storage-block decompression filter (the legacy isStorageBlockDecompression
     * parity): single-ingredient craft yielding 9 or 4 of the result from a
     * "_block"/"s_block" ingredient form. Not a crafting path a player uses
     * to MAKE the base material — filtering keeps the resolver treating
     * raw_iron/iron_ingot etc. as gather-or-smelt leaves.
     */
    private static boolean isStorageBlockDecompression(CraftResolver.ResolvedRecipe r) {
        if (r.smelt) return false;
        if (r.ingredients.size() != 1) return false;
        if (r.resultCount != 9 && r.resultCount != 4) return false;
        String ing = r.ingredients.get(0).name;
        return ing.endsWith("_block") || ing.endsWith("s_block");
    }

    /** vanilla smelting baseline (the ore→ingot + common smelts). */
    private static final Map<String, String> SMELTING_TABLE = Map.ofEntries(
            Map.entry("raw_iron", "iron_ingot"),
            Map.entry("raw_copper", "copper_ingot"),
            Map.entry("raw_gold", "gold_ingot"),
            Map.entry("iron_ore", "iron_ingot"),
            Map.entry("copper_ore", "copper_ingot"),
            Map.entry("gold_ore", "gold_ingot"),
            Map.entry("deepslate_iron_ore", "iron_ingot"),
            Map.entry("deepslate_gold_ore", "gold_ingot"),
            Map.entry("deepslate_copper_ore", "copper_ingot"),
            Map.entry("nether_gold_ore", "gold_ingot"),
            Map.entry("sand", "glass"),
            Map.entry("red_sand", "glass"),
            Map.entry("cobblestone", "stone"),
            Map.entry("clay_ball", "brick"),
            Map.entry("netherrack", "nether_brick"),
            Map.entry("wet_sponge", "sponge"),
            Map.entry("kelp", "dried_kelp"),
            Map.entry("cactus", "green_dye"),
            Map.entry("stone", "smooth_stone")
    );

    /**
     * Result count for a recipe display: the number of items one craft batch
     * produces. Reads the result SlotDisplay's stack count when it carries
     * one (ItemStackSlotDisplay); defaults to 1 otherwise (most displays are
     * count-1). See the unit test note: this cannot be exercised without a
     * live recipe book, so it's compile-verified only for now.
     */
    private static int getResultCountFor(RecipeDisplay display) {
        SlotDisplay result = display.result();
        if (result instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay) {
            ItemStackTemplate template = stackDisplay.stack();
            ItemStack stack = template.create();
            if (!stack.isEmpty() && template.count() > 0) {
                return template.count();
            }
        }
        return 1;
    }

    /** Reverse-lookup smelting recipes for an output. */
    private static List<CraftResolver.ResolvedRecipe> smeltingRecipesFor(String output) {
        List<CraftResolver.ResolvedRecipe> out = new ArrayList<>();
        for (Map.Entry<String, String> e : SMELTING_TABLE.entrySet()) {
            if (e.getValue().equals(output)) {
                out.add(new CraftResolver.ResolvedRecipe(output, 1,
                        List.of(new CraftResolver.Ingredient(e.getKey(), 1)), true));
            }
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Dispatch
    // ──────────────────────────────────────────────────────────────────────

    public CompletableFuture<JsonObject> dispatch(String tool, JsonObject args) {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        // Queue composites AND standalone composite tools (mine-blocks,
        // build-structure) contain waits and polling loops. Never run them on
        // the render/client thread: doing so freezes ticks, input, rendering,
        // Baritone, and Meteor modules. The queue worker marshals each short
        // Minecraft operation back through callOnClient instead.
        boolean isQueueTool = "enqueue-tasks".equals(tool);
        boolean isStandaloneComposite =
                "mine-blocks".equals(tool) || "build-structure".equals(tool)
                        || "craft-item".equals(tool) || "smelt-item".equals(tool)
                        || "get-to-block".equals(tool) || "explore".equals(tool)
                        || "follow-player".equals(tool) || "guard-area".equals(tool)
                        || "open-container".equals(tool) || "deposit-items".equals(tool)
                        || "withdraw-items".equals(tool)
                        || "goto-coords".equals(tool)
                        // T5.3/D1-1: dig-block's break-completion poll loop
                        // sleeps on the calling thread. On the client thread
                        // that froze the render loop — the server's
                        // block-update packet could never be applied mid-loop,
                        // so getBlockState never observed the break and every
                        // multi-tick dig reported timeout while the server
                        // actually broke the block (live T5.2: destroyStage -1,
                        // get-blocks air immediately after). Same family as
                        // collect-drops/eat-food below.
                        || "dig-block".equals(tool)
                        // T5.3/D1-1 same family: farm-plot harvest shares the
                        // breakBlockAt poll loop; till/plant/fertilize use
                        // bounded hand-waits + settle sleeps.
                        || "farm-plot".equals(tool);
        // Attack-entity / follow-entity / flee-from / path-safely
        // contain Thread.sleep polling loops around Baritone #goto. On the
        // client thread (the non-queue dispatch below) those sleeps froze the
        // game tick — Baritone never processed the #goto (live pin on 1.2.1:
        // movedBlocks 0.0, attacks 0, approach budget elapsed to the last ms).
        // Run them on the queue worker thread like mine-blocks/hunt-hostile.
        boolean isSleepingComposite = "attack-entity".equals(tool)
                || "follow-entity".equals(tool)
                || "flee-from".equals(tool)
                || "path-safely".equals(tool)
                // Defect 3: collect-drops' poll loops slept on the
                // client thread — frozen ticks meant Baritone #goto never
                // executed (live: 6 drops within 1.6-5.7 blocks, 0 picked).
                || "collect-drops".equals(tool)
                // Defect 4: eat-food's 1700ms sleeps froze the client
                // thread, so the food-level sync landed after the response
                // was read (live: response 6->6/20, snapshot later 20/20).
                || "eat-food".equals(tool);
        if (isQueueTool || isStandaloneComposite || isSleepingComposite) {
            if (!QUEUE_ACTIVE.compareAndSet(false, true)) {
                future.completeExceptionally(new ToolException("QUEUE_BUSY", "Another queue or composite task is already running"));
                return future;
            }
            QUEUE_EXECUTOR.execute(() -> {
                try {
                    QueueTelemetry.begin(tool);
                    callOnClient(client, () -> {
                        if (client.player == null || client.level == null) {
                            throw new ToolException("CLIENT_NOT_READY", "Join a world before calling client tools");
                        }
                        return null;
                    });
                    if (isQueueTool) {
                        future.complete(enqueueTasks(client, args));
                    } else {
                        ToolHandler handler = HANDLERS.get(tool);
                        if (handler == null) {
                            throw new ToolException("UNKNOWN_TOOL", "Unknown tool: " + tool);
                        }
                        future.complete(handler.handle(client, args));
                    }
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    QueueTelemetry.end();
                    QUEUE_ACTIVE.set(false);
                }
            });
            return future;
        }

        client.execute(() -> {
            try {
                if (client.player == null || client.level == null) {
                    throw new ToolException("CLIENT_NOT_READY", "Join a world before calling client tools");
                }
                ToolHandler handler = HANDLERS.get(tool);
                if (handler == null) {
                    throw new ToolException("UNKNOWN_TOOL", "Unknown tool: " + tool);
                }
                future.complete(handler.handle(client, args));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private static <T> T callOnClient(Minecraft client, java.util.concurrent.Callable<T> action) {
        if (client.isSameThread()) {
            try {
                return action.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        client.execute(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.join();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Navigation (existing, proven — unchanged logic)
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject navigate(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        return dispatchBaritone(client, "#goto " + x + " " + y + " " + z, "goto");
    }

    private static JsonObject dispatchBaritone(Minecraft client, String command, String action) {
        client.player.connection.sendChat(command);
        JsonObject result = new JsonObject();
        result.addProperty("status", "dispatched");
        result.addProperty("action", action);
        result.addProperty("command", command);
        result.addProperty("completionObserved", false);
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier A: Perception / World State
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject getWorldTime(Minecraft client, JsonObject args) {
        ClientLevel level = client.level;
        long dayTime = level.getOverworldClockTime();
        JsonObject result = new JsonObject();
        result.addProperty("gameTime", level.getGameTime());
        result.addProperty("dayTime", dayTime);
        // Calculate time-of-day (0-23999, where 0=06:00)
        long timeOfDay = dayTime % 24000;
        if (timeOfDay < 0) timeOfDay += 24000;
        result.addProperty("timeOfDay", timeOfDay);
        result.addProperty("dayCount", dayTime / 24000);
        // Moon phase (0-7): calculated from dayTime
        int moonPhase = (int) (dayTime / 24000L) % 8;
        if (moonPhase < 0) moonPhase += 8;
        result.addProperty("moonPhase", moonPhase);
        boolean isDay = timeOfDay < 13000;
        result.addProperty("isDay", isDay);
        result.addProperty("isNight", !isDay);
        return result;
    }

    private static JsonObject getWeather(Minecraft client, JsonObject args) {
        ClientLevel level = client.level;
        JsonObject result = new JsonObject();
        result.addProperty("raining", level.isRaining());
        result.addProperty("thundering", level.isThundering());
        result.addProperty("rainLevel", level.getRainLevel(1.0f));
        result.addProperty("thunderLevel", level.getThunderLevel(1.0f));
        return result;
    }

    private static JsonObject getBlockLight(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        JsonObject result = new JsonObject();
        result.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
        result.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
        return result;
    }

    private static JsonObject detectGamemode(Minecraft client, JsonObject args) {
        LocalPlayer player = client.player;
        JsonObject result = new JsonObject();
        String gamemode;
        if (player.isCreative()) gamemode = "creative";
        else if (player.isSpectator()) gamemode = "spectator";
        else gamemode = "survival";
        result.addProperty("gamemode", gamemode);
        result.addProperty("isCreative", player.isCreative());
        result.addProperty("isSpectator", player.isSpectator());
        result.addProperty("isSurvival", !player.isCreative() && !player.isSpectator());
        return result;
    }

    private static JsonObject lookAt(Minecraft client, JsonObject args) {
        double x = requiredDouble(args, "x");
        double y = requiredDouble(args, "y");
        double z = requiredDouble(args, "z");
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horizontalDistance) * 180.0 / Math.PI);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        JsonObject result = new JsonObject();
        result.addProperty("status", "looked");
        result.addProperty("yaw", yaw);
        result.addProperty("pitch", pitch);
        return result;
    }

    //: send-chat pre-flight validation. The
    // vanilla chat packet encoder hard-caps outgoing chat at 256
    // chars and throws in the netty pipeline on the CLIENT thread
    // — a longer message DISCONNECTS the client ("kicked by a long
    // chat"), and no try/catch can intercept it. The message must
    // never reach the wire. The published contract (max 200 chars,
    // one send per 3 seconds) is enforced here, before sendChat().
    private static final int CHAT_MAX_LENGTH = 200;
    private static final long CHAT_RATE_LIMIT_MS = 3000L;
    private static long lastChatSendMs = 0L;

    private static JsonObject sendChat(Minecraft client, JsonObject args) {
        String message = requiredString(args, "message");
        if (message.isEmpty()) {
            throw new ToolException("CHAT_REJECTED", "Message must not be empty");
        }
        // Bare '/' (empty command) rejected pre-wire like the others.
        if ("/".equals(message.trim())) {
            throw new ToolException("CHAT_REJECTED", "Message must not be a bare '/'");
        }
        if (message.length() > CHAT_MAX_LENGTH) {
            throw new ToolException("CHAT_REJECTED",
                    "Message too long: " + message.length() + " chars (max " + CHAT_MAX_LENGTH
                    + "). NOTE: the client hard-caps chat at 256 chars and a longer message"
                    + " disconnects it — this send was blocked BEFORE the wire. Shorten the"
                    + " message or split it across send-chat calls at least 3 seconds apart.");
        }
        long now = System.currentTimeMillis();
        long sinceLast = now - lastChatSendMs;
        if (lastChatSendMs > 0 && sinceLast < CHAT_RATE_LIMIT_MS) {
            throw new ToolException("CHAT_REJECTED",
                    "Rate limited: last send-chat was " + String.format("%.1f", sinceLast / 1000.0)
                    + "s ago; wait " + String.format("%.1f", (CHAT_RATE_LIMIT_MS - sinceLast) / 1000.0)
                    + "s more (one send per 3 seconds)");
        }
        lastChatSendMs = now;
        JsonObject result = new JsonObject();
        //: messages starting with '/' are SERVER COMMANDS, not
        // chat. sendChat() puts them on the chat wire, where servers treat
        // them as literal text ("/pv 1" echoed as a chat line; /bed same).
        // The correct transport is ClientPacketListener.sendCommand(), which
        // sends a signed command packet. Baritone '#'-prefixed strings stay
        // on sendChat() — Baritone intercepts chat, not commands.
        if (message.startsWith("/")) {
            String command = message.substring(1);
            client.player.connection.sendCommand(command);
            result.addProperty("status", "sent_command");
            result.addProperty("command", command);
            return result;
        }
        client.player.connection.sendChat(message);
        result.addProperty("status", "sent");
        result.addProperty("message", message);
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier D: Chat
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject readChat(Minecraft client, JsonObject args) {
        int count = optionalInt(args, "count", 10);
        boolean drain = !"peek".equals(optionalString(args, "mode", "drain"));
        ChatBuffer buffer = HyFuseClient.CHAT_BUFFER;
        java.util.List<ChatBuffer.ChatEntry> entries = drain
                ? buffer.drain(count)
                : buffer.peek(count);
        JsonArray messages = new JsonArray();
        for (ChatBuffer.ChatEntry entry : entries) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", entry.type);
            if (!entry.sender.isEmpty()) {
                msg.addProperty("sender", entry.sender);
            }
            msg.addProperty("content", entry.content);
            msg.addProperty("timestamp", entry.timestamp.toString());
            messages.add(msg);
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("mode", drain ? "drained": "peeked");
        result.addProperty("returned", messages.size());
        result.addProperty("remaining", buffer.size());
        result.add("messages", messages);
        return result;
    }

    private static JsonObject raycastLook(Minecraft client, JsonObject args) {
        double maxDistance = optionalDouble(args, "maxDistance", 64.0);
        HitResult hit = client.player.pick(maxDistance, 0.0f, false);
        JsonObject result = new JsonObject();
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            result.addProperty("type", "block");
            BlockPos pos = blockHit.getBlockPos();
            result.addProperty("x", pos.getX());
            result.addProperty("y", pos.getY());
            result.addProperty("z", pos.getZ());
            result.addProperty("direction", blockHit.getDirection().getName());
            BlockState state = client.level.getBlockState(pos);
            result.addProperty("block", blockRegistryName(state));
            Vec3 hitVec = blockHit.getLocation();
            result.addProperty("hitX", hitVec.x);
            result.addProperty("hitY", hitVec.y);
            result.addProperty("hitZ", hitVec.z);
        } else if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            result.addProperty("type", "entity");
            result.addProperty("entityType", entityRegistryName(entity));
            result.addProperty("entityId", entity.getId());
            result.addProperty("x", entity.getX());
            result.addProperty("y", entity.getY());
            result.addProperty("z", entity.getZ());
            if (entity instanceof LivingEntity living) {
                result.addProperty("health", living.getHealth());
            }
        } else {
            result.addProperty("type", "miss");
        }
        result.addProperty("distance", hit.getLocation().distanceTo(client.player.getEyePosition(0.0f)));
        return result;
    }

    private static JsonObject getBlockInfo(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        BlockState state = level.getBlockState(pos);
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("block", blockRegistryName(state));
        result.addProperty("isAir", state.isAir());
        result.addProperty("canBeReplaced", state.canBeReplaced());
        result.addProperty("destroySpeed", state.getDestroySpeed(level, pos));
        result.addProperty("lightEmission", state.getLightEmission());
        result.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
        result.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
        // Block state properties
        JsonObject states = new JsonObject();
        for (Property<?> prop : state.getProperties()) {
            states.addProperty(prop.getName(), state.getValue(prop).toString());
        }
        if (states.size() > 0) {
            result.add("states", states);
        }
        return result;
    }

    private static JsonObject getBlocks(Minecraft client, JsonObject args) {
        JsonElement positionsEl = args.get("positions");
        if (positionsEl == null || !positionsEl.isJsonArray()) {
            throw new ToolException("INVALID_ARGUMENT", "Argument 'positions' must be an array of {x,y,z} objects");
        }
        JsonArray positions = positionsEl.getAsJsonArray();
        if (positions.size() > 512) {
            throw new ToolException("INVALID_ARGUMENT", "Positions array exceeds maximum of 512");
        }
        ClientLevel level = client.level;
        JsonArray blocks = new JsonArray();
        for (JsonElement el : positions) {
            if (!el.isJsonObject()) continue;
            JsonObject posObj = el.getAsJsonObject();
            int x = requiredInt(posObj, "x");
            int y = requiredInt(posObj, "y");
            int z = requiredInt(posObj, "z");
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            JsonObject blockEntry = new JsonObject();
            blockEntry.addProperty("x", x);
            blockEntry.addProperty("y", y);
            blockEntry.addProperty("z", z);
            blockEntry.addProperty("block", blockRegistryName(state));
            blocks.add(blockEntry);
        }
        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        result.addProperty("count", blocks.size());
        return result;
    }

    private static JsonObject findBlocks(Minecraft client, JsonObject args) {
        String blockType = requiredString(args, "blockType");
        int maxDistance = optionalInt(args, "maxDistance", (int) DEFAULT_SCAN_RADIUS);
        int maxCount = optionalInt(args, "count", DEFAULT_FIND_BLOCK_COUNT);
        if (maxCount > MAX_FIND_BLOCK_COUNT) maxCount = MAX_FIND_BLOCK_COUNT;
        String targetName = normalizeResourceId(blockType);

        ClientLevel level = client.level;
        BlockPos origin = client.player.blockPosition();
        int radius = Math.min(maxDistance, 128);

        List<JsonObject> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius && found.size() < maxCount; dx++) {
            for (int dy = -radius; dy <= radius && found.size() < maxCount; dy++) {
                for (int dz = -radius; dz <= radius && found.size() < maxCount; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    String name = blockRegistryName(state);
                    if (name.equals(targetName) || name.endsWith(":" + targetName)) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("x", pos.getX());
                        entry.addProperty("y", pos.getY());
                        entry.addProperty("z", pos.getZ());
                        entry.addProperty("block", name);
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        entry.addProperty("distance", dist);
                        found.add(entry);
                    }
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.get("distance").getAsDouble(), b.get("distance").getAsDouble()));
        JsonArray results = new JsonArray();
        for (JsonObject entry : found) {
            results.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("blocks", results);
        result.addProperty("count", found.size());
        result.addProperty("blockType", blockType);
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier A: Entities
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject findEntity(Minecraft client, JsonObject args) {
        String type = optionalString(args, "type", "");
        double maxDistance = optionalDouble(args, "maxDistance", DEFAULT_SCAN_RADIUS);
        String typeName = normalizeResourceId(type);

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) continue;
            if (!typeName.isEmpty()) {
                String eName = entityRegistryName(entity);
                String simple = stripNamespace(eName).toLowerCase();
                String needle = stripNamespace(typeName).toLowerCase();
                if (!simple.equals(needle) && !simple.contains(needle)) continue;
            }
            double dist = entity.distanceToSqr(client.player);
            if (dist <= maxDistance * maxDistance && dist < nearestDist) {
                nearest = entity;
                nearestDist = dist;
            }
        }
        JsonObject result = new JsonObject();
        if (nearest != null) {
            result.addProperty("found", true);
            result.addProperty("type", entityRegistryName(nearest));
            result.addProperty("id", nearest.getId());
            result.addProperty("x", nearest.getX());
            result.addProperty("y", nearest.getY());
            result.addProperty("z", nearest.getZ());
            result.addProperty("distance", Math.sqrt(nearestDist));
            if (nearest instanceof LivingEntity living) {
                result.addProperty("health", living.getHealth());
                result.addProperty("maxHealth", living.getMaxHealth());
            }
        } else {
            result.addProperty("found", false);
        }
        return result;
    }

    private static JsonObject scanNearbyEntities(Minecraft client, JsonObject args) {
        double radius = optionalDouble(args, "radius", DEFAULT_SCAN_RADIUS);
        String filter = optionalString(args, "filter", "");
        int maxCount = optionalInt(args, "maxCount", 20);
        String filterName = normalizeResourceId(filter);

        List<Entity> matched = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) continue;
            double dist = entity.distanceToSqr(client.player);
            if (dist > radius * radius) continue;
            if (!filterName.isEmpty()) {
                String eName = entityRegistryName(entity);
                String simple = stripNamespace(eName).toLowerCase();
                String needle = stripNamespace(filterName).toLowerCase();
                if (!simple.equals(needle) && !simple.contains(needle)) continue;
            }
            matched.add(entity);
            if (matched.size() >= maxCount) break;
        }
        JsonArray entities = new JsonArray();
        for (Entity entity : matched) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", entityRegistryName(entity));
            entry.addProperty("id", entity.getId());
            entry.addProperty("x", entity.getX());
            entry.addProperty("y", entity.getY());
            entry.addProperty("z", entity.getZ());
            entry.addProperty("distance", entity.distanceTo(client.player));
            if (entity instanceof LivingEntity living) {
                entry.addProperty("health", living.getHealth());
                entry.addProperty("maxHealth", living.getMaxHealth());
            }
            entities.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("entities", entities);
        result.addProperty("count", entities.size());
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier B: Block Interaction
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject digBlock(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        BlockState state = level.getBlockState(pos);
        String blockName = blockRegistryName(state);
        if (state.isAir()) {
            JsonObject result = new JsonObject();
            result.addProperty("status", "already_air");
            result.addProperty("x", x);
            result.addProperty("y", y);
            result.addProperty("z", z);
            result.addProperty("block", blockName);
            return result;
        }

        // Rotate to look at the block center so the server accepts the break
        Direction face = computeFaceTowards(player, pos);
        lookAtBlockCenter(client, pos);

        // Equip the best tool for this block (autoTool)
        String toolUsed = equipBestToolForBlock(client, state);

        int timeoutMs = optionalInt(args, "timeoutMs", 25000);
        long deadline = System.currentTimeMillis() + timeoutMs;

        if (player.isCreative()) {
            // Creative: instant break
            client.gameMode.destroyBlock(pos);
        } else {
            // T5.3-live/D1-2: this handler now runs on the QUEUE worker
            // (T5.3/D1-1 routing). Every client-state touch below is
            // marshalled through callOnClient — the raw client-thread loop
            // raced the render thread on multi-tick digs (live: "Tried to
            // access render state from outside the main render thread",
            // the break then never landed server-side and the client's
            // predicted break resynced back to the real block).
            final BlockPos fPos = pos;
            final Direction fFace = face;
            callOnClient(client, () -> {
                client.gameMode.startDestroyBlock(fPos, fFace);
                client.player.swing(InteractionHand.MAIN_HAND);
                return null;
            });
            boolean airNow = Boolean.TRUE.equals(callOnClient(client,
                    () -> Boolean.valueOf(client.level.getBlockState(fPos).isAir())));
            while (!airNow && System.currentTimeMillis() < deadline) {
                callOnClient(client, () -> {
                    client.gameMode.continueDestroyBlock(fPos, fFace);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    return null;
                });
                try {
                    Thread.sleep(50); // one tick
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                airNow = Boolean.TRUE.equals(callOnClient(client,
                        () -> Boolean.valueOf(client.level.getBlockState(fPos).isAir())));
            }
            if (!airNow) {
                // Timed out — stop breaking (marshalled)
                callOnClient(client, () -> {
                    client.gameMode.stopDestroyBlock();
                    return null;
                });
                Integer stage = callOnClient(client,
                        () -> Integer.valueOf(client.gameMode.getDestroyStage()));
                JsonObject result = new JsonObject();
                result.addProperty("status", "timeout");
                result.addProperty("x", x);
                result.addProperty("y", y);
                result.addProperty("z", z);
                result.addProperty("block", blockName);
                result.addProperty("tool", toolUsed);
                result.addProperty("destroyStage", stage == null ? -1 : stage.intValue());
                return result;
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("status", "broken");
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("block", blockName);
        result.addProperty("tool", toolUsed);
        return result;
    }

    /**
     * use-item-on-block (T4.4): right-click interaction primitive — hold an
     * item (optional) and use it against a block face. Covers nether-portal
     * ignition (flint-and-steel on obsidian), doors/trapdoors, levers,
     * buttons, repeaters... anything a player right-clicks with an item.
     * Mirrors placeBlock's equip + bounded hand-wait + useItemOn shape.
     */
    private static JsonObject useItemOnBlock(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        String item = optionalString(args, "item", "");
        String faceDirName = optionalString(args, "faceDirection", "up");
        Direction faceDir = Direction.byName(faceDirName.toLowerCase());
        if (faceDir == null) faceDir = Direction.UP;

        // Optional: equip the named item first (e.g. flint_and_steel).
        if (!item.isEmpty()) {
            equipItemByName(client, item);
            String desiredSimple = simpleName(normalizeResourceId(item));
            boolean inHand = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                ItemStack held = client.player.getMainHandItem();
                if (!held.isEmpty()
                        && simpleName(itemRegistryName(held)).equals(desiredSimple)) {
                    inHand = true;
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!inHand) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("status", "no_item_in_hand");
                result.addProperty("x", x);
                result.addProperty("y", y);
                result.addProperty("z", z);
                result.addProperty("item", item);
                return result;
            }
        }

        // Same face-click geometry as placeBlock: click on the face of the
        // target block itself (we are USING the target, not placing beside it).
        lookAtFacePoint(client, pos, faceDir);
        Vec3 hitVec = facePointHitVec(pos, faceDir);
        BlockHitResult hitResult = new BlockHitResult(hitVec, faceDir, pos, false);

        InteractionResult interactionResult = client.gameMode.useItemOn(
                player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        // Brief settle for server round-trip, then verify by state change.
        try { Thread.sleep(150); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        BlockState after = level.getBlockState(pos);
        String afterName = blockRegistryName(after);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("status", "used");
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("item", item.isEmpty() ? "(held)" : item);
        result.addProperty("interactionResult", String.valueOf(interactionResult));
        result.addProperty("blockAfter", afterName);
        // T5.3/D5-1: fire/portal forms at the FACE-RELATIVE destination cell
        // (flint_and_steel on the interior bottom obsidian UP-face ignites the
        // cell ABOVE the clicked block), not at the clicked block itself —
        // the old check read the clicked obsidian and always reported false
        // even though the portal lit (live T5.2). Check the destination cell
        // plus the cells above it (portal column) for portal/fire state.
        BlockPos dest = pos.relative(faceDir);
        String destName = blockRegistryName(level.getBlockState(dest));
        boolean portalLit = destName.contains("portal") && !destName.contains("frame");
        boolean fireLit = destName.contains("fire");
        // Bounded re-check (total ≤1s): a public server's block-update packet
        // can land after the 150ms settle — poll until portal/fire appears or
        // the budget runs out (same stale-snapshot family as D1-1/H4).
        long litDeadline = System.currentTimeMillis() + 1000;
        while (!portalLit && !fireLit && System.currentTimeMillis() < litDeadline) {
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            destName = blockRegistryName(level.getBlockState(dest));
            portalLit = destName.contains("portal") && !destName.contains("frame");
            fireLit = destName.contains("fire");
        }
        for (int up = 1; !portalLit && up <= 3; up++) {
            String upName = blockRegistryName(level.getBlockState(dest.above(up)));
            portalLit = upName.contains("portal") && !upName.contains("frame");
        }
        result.addProperty("blockAtDestination", destName);
        result.addProperty("fireLit", fireLit);
        result.addProperty("portalIgnited", portalLit);
        return result;
    }

    private static JsonObject entityInteract(Minecraft client, JsonObject args) {
        String entityName = optionalString(args, "entityName", "");
        int entityId = optionalInt(args, "entityId", -1);
        if (entityName.isEmpty() && entityId < 0) {
            return errorJson("Provide entityName or entityId");
        }
        String item = optionalString(args, "item", "");
        int attempts = optionalInt(args, "attempts", 3);
        if (attempts < 1) attempts = 1;
        if (attempts > 5) attempts = 5;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        // Resolve the target: by numeric id (find-entity reports it), by
        // name/custom-name (resolveAttackTarget), or not at all.
        Entity target = null;
        if (entityId >= 0) {
            int id = entityId;
            target = callOnClient(client, () -> level.getEntity(id));
        }
        if (target == null && !entityName.isEmpty()) {
            target = callOnClient(client, () ->
                    resolveAttackTarget(level, player, entityName));
        }
        if (target == null) {
            return errorJson("No entity matching "
                    + (entityName.isEmpty() ? ("id " + entityId) : ("'" + entityName + "'"))
                    + " found nearby");
        }

        // Optional: equip the named item first (breeding food, lead, dyes).
        if (!item.isEmpty()) {
            equipItemByName(client, item);
            String desiredSimple = simpleName(normalizeResourceId(item));
            boolean inHand = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                ItemStack held = callOnClient(client, () ->
                        client.player.getMainHandItem());
                if (!held.isEmpty()
                        && simpleName(itemRegistryName(held)).equals(desiredSimple)) {
                    inHand = true;
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!inHand) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("status", "no_item_in_hand");
                result.addProperty("item", item);
                return result;
            }
        }

        // One-shot interaction with a small bounded retry: server cooldowns
        // can eat a click, so re-click while the result is a plain PASS, but
        // stop as soon as the server signals the action was consumed.
        String lastResult = "";
        boolean consumed = false;
        for (int i = 0; i < attempts; i++) {
            final Entity fTarget = target;
            final String result2 = callOnClient(client, () -> {
                if (fTarget.isRemoved() || !fTarget.isAlive()
                        || level.getEntity(fTarget.getId()) == null) return "gone";
                lookAtEntity(client, fTarget);
                EntityHitResult hit = new EntityHitResult(fTarget);
                InteractionResult r = client.gameMode.interact(
                        player, fTarget, hit, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
                return String.valueOf(r);
            });
            if ("gone".equals(result2)) break;
            lastResult = result2;
            // MC 26.2 InteractionResult is a sealed interface; SUCCESS and
            // its variants consume the action — a Pass means nothing happened.
            consumed = result2.startsWith("Success") || result2.contains("Consume");
            if (consumed) break;
            try { Thread.sleep(300); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Brief settle for the server round-trip, then soft verification:
        // no block-state diff exists for entities, so report the interaction
        // result plus the breed signal (Animal.isInLove) when applicable.
        try { Thread.sleep(200); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        final Entity fTarget2 = target;
        Boolean inLove = callOnClient(client, () ->
                fTarget2 instanceof Animal a ? a.isInLove() : null);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("status", consumed ? "interacted" : "passed");
        result.addProperty("entityType", callOnClient(client, () ->
                entityRegistryName(fTarget2)));
        result.addProperty("entityId", callOnClient(client, () ->
                fTarget2.getId()));
        result.addProperty("x", callOnClient(client, () -> fTarget2.getX()));
        result.addProperty("y", callOnClient(client, () -> fTarget2.getY()));
        result.addProperty("z", callOnClient(client, () -> fTarget2.getZ()));
        double dist = callOnClient(client, () ->
                Math.sqrt(fTarget2.distanceToSqr(client.player)));
        result.addProperty("distance", dist);
        result.addProperty("item", item.isEmpty() ? "(held)" : item);
        result.addProperty("interactionResult", lastResult);
        result.addProperty("inLove", inLove == null ? false : inLove);
        result.addProperty("inLoveApplicable", inLove != null);
        return result;
    }

    private static JsonObject bucketFluid(Minecraft client, JsonObject args) {
        String action = optionalString(args, "action", "fill").toLowerCase();
        if (!action.equals("fill") && !action.equals("place")) {
            return errorJson("action must be 'fill' or 'place'");
        }
        String fluid = optionalString(args, "fluid", "water").toLowerCase();
        if (!fluid.equals("water") && !fluid.equals("lava")
                && !fluid.equals("powder_snow")) {
            return errorJson("fluid must be 'water', 'lava', or 'powder_snow'");
        }
        String faceDirName = optionalString(args, "faceDirection", "up");
        Direction faceDir = Direction.byName(faceDirName.toLowerCase());
        if (faceDir == null) faceDir = Direction.UP;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        // Resolve the target position. fill: click ON the fluid source
        // itself (vanilla scoops at the clicked position). place: click a
        // solid block face; the fluid appears at pos.relative(face) — or at
        // the clicked pos itself when the block there is replaceable.
        BlockPos pos = null;
        boolean hasCoords = args.has("x") && args.has("y") && args.has("z");
        if (hasCoords) {
            pos = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"),
                    requiredInt(args, "z"));
        } else if (action.equals("fill")) {
            // Auto-find: nearest fluid source of the requested type (fluids
            // are not reliably discoverable through find-blocks).
            BlockPos origin = player.blockPosition();
            pos = callOnClient(client, () ->
                    findNearestFluidSource(level, origin, 12, fluid));
            if (pos == null) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("status", "no_fluid_found");
                result.addProperty("fluid", fluid);
                return result;
            }
        } else {
            return errorJson("place requires x/y/z (the block face to click)");
        }
        final BlockPos fPos = pos;

        // Equip the required item: empty bucket for fill, <fluid>_bucket for
        // place. The fluid arg doubles as the item name for place.
        String desiredItem = action.equals("fill") ? "bucket" : fluid + "_bucket";
        equipItemByName(client, desiredItem);
        String desiredSimple = simpleName(normalizeResourceId(desiredItem));
        boolean inHand = false;
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            ItemStack held = callOnClient(client, () -> client.player.getMainHandItem());
            if (!held.isEmpty()
                    && simpleName(itemRegistryName(held)).equals(desiredSimple)) {
                inHand = true;
                break;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!inHand) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("status", "no_item_in_hand");
            result.addProperty("item", desiredItem);
            result.addProperty("x", fPos.getX());
            result.addProperty("y", fPos.getY());
            result.addProperty("z", fPos.getZ());
            return result;
        }

        // Clicks and world reads are marshalled to the client thread. Fill
        // wants the source gone; place wants fluid present at placeTarget.
        boolean replaceableAtPos = callOnClient(client, () ->
                level.getBlockState(fPos).canBeReplaced());
        BlockPos placeTarget = replaceableAtPos ? fPos : fPos.relative(faceDir);
        boolean wasSource = callOnClient(client, () ->
                level.getFluidState(fPos).isSource());

        String lastResult = "";
        for (int i = 0; i < 3; i++) {
            final Direction fFace = faceDir;
            final String fAction = action; // kept for clarity; helper is face-only
            String r = useBucketAt(client, player, fPos, fFace);
            lastResult = r;
            boolean changed;
            if (action.equals("fill")) {
                changed = !callOnClient(client, () ->
                        level.getFluidState(fPos).isSource());
            } else {
                changed = !callOnClient(client, () ->
                        level.getFluidState(placeTarget).isEmpty());
            }
            if (changed || r.startsWith("Success") || r.contains("Consume")) break;
            try { Thread.sleep(300); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        try { Thread.sleep(200); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        String status;
        String fluidAfter;
        if (action.equals("fill")) {
            boolean gone = !callOnClient(client, () ->
                    level.getFluidState(fPos).isSource());
            status = gone ? "filled" : "not_filled";
            fluidAfter = callOnClient(client, () ->
                    fluidRegistryName(level.getFluidState(fPos)));
        } else {
            boolean placed = !callOnClient(client, () ->
                    level.getFluidState(placeTarget).isEmpty());
            if (!placed) {
                placed = !callOnClient(client, () ->
                        level.getFluidState(fPos).isEmpty());
            }
            status = placed ? "placed" : "not_placed";
            fluidAfter = callOnClient(client, () ->
                    fluidRegistryName(level.getFluidState(placeTarget)));
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", status.equals("filled") || status.equals("placed"));
        result.addProperty("status", status);
        result.addProperty("action", action);
        result.addProperty("fluid", fluid);
        result.addProperty("x", fPos.getX());
        result.addProperty("y", fPos.getY());
        result.addProperty("z", fPos.getZ());
        result.addProperty("item", desiredItem);
        result.addProperty("fluidAfter", fluidAfter);
        result.addProperty("interactionResult", lastResult);
        return result;
    }
    /**
     * Click a bucket at a position: look + useItemOn + swing; returns
     * String.valueOf(InteractionResult) for consumed checks.
     * T5.3-live/E-fill: BucketItem has NO useOn override — bucket pickup
     * lives in BucketItem.use, which the vanilla client reaches only via
     * the plain use-item FALLBACK packet it sends after useItemOn PASSes
     * (javap: net.minecraft.world.item.BucketItem has use(Level,Player,Hand)
     * but no useOn; emptying a filled bucket DOES ride useItemOn via
     * DispensibleContainerItem on the block). HyFuse never sent the
     * follow-up → live fill deterministically PASSed and never scooped.
     * Fix: after a PASS/TRY_WITH_EMPTY_HAND from useItemOn, send
     * gameMode.useItem(player, MAIN_HAND) — the vanilla right-click flow.
     */
    private static String useBucketAt(Minecraft client, LocalPlayer player,
                                      BlockPos pos, Direction faceDir) {
        return callOnClient(client, () -> {
            lookAtFacePoint(client, pos, faceDir);
            Vec3 hitVec = facePointHitVec(pos, faceDir);
            BlockHitResult hitResult = new BlockHitResult(hitVec, faceDir, pos, false);
            InteractionResult r = client.gameMode.useItemOn(
                    player, InteractionHand.MAIN_HAND, hitResult);
            if (String.valueOf(r).contains("Pass")
                    || String.valueOf(r).contains("TryWithEmptyHand")) {
                // Vanilla fallback: send the use-item packet so BucketItem.use
                // (the actual scoop) runs server-side.
                r = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            player.swing(InteractionHand.MAIN_HAND);
            return String.valueOf(r);
        });
    }

    /** Registry name of the fluid in a FluidState ("water", "lava", ...). */
    private static String fluidRegistryName(FluidState state) {
        Identifier key = BuiltInRegistries.FLUID.getKey(state.getType());
        return key == null ? "unknown" : key.toString();
    }

    /** Nearest fluid source of the named type within a cube radius. */
    private static BlockPos findNearestFluidSource(ClientLevel level, BlockPos origin,
                                                   int radius, String fluid) {
        String needle = stripNamespace(fluid).toLowerCase();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getFluidState(pos).isSource()) continue;
                    String name = fluidRegistryName(level.getFluidState(pos));
                    if (!name.contains(needle)) continue;
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (dist < nearestDist) {
                        nearest = pos;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }
    /**
     * farm-plot (T4.8): farming primitive — till farmland with a hoe, plant
     * seeds on farmland, harvest a grown crop (break + drops), or fertilize
     * with bonemeal. Auto-equips the needed item (hoe / seeds / bonemeal;
     * harvest uses the best tool or hand) using the placeBlock-style bounded
     * hand-wait, then verifies by block-state change after a settle.
     */
    private static JsonObject farmPlot(Minecraft client, JsonObject args) {
        String action = optionalString(args, "action", "till").toLowerCase();
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        String item = optionalString(args, "item", "");
        BlockState before = level.getBlockState(pos);
        String beforeName = blockRegistryName(before);

        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("action", action);
        result.addProperty("blockBefore", beforeName);

        if (action.equals("till")) {
            if (before.getBlock() instanceof FarmlandBlock) {
                result.addProperty("ok", true);
                result.addProperty("status", "already_farmland");
                result.addProperty("item", "(held)");
                result.addProperty("blockAfter", beforeName);
                return result;
            }
            String hoeName = item.isEmpty() ? anyHoeInInventory(client) : item;
            if (hoeName == null) {
                result.addProperty("ok", false);
                result.addProperty("status", "no_hoe");
                result.addProperty("error", "No hoe in inventory - craft one first (craft-item).");
                return result;
            }
            equipItemByName(client, hoeName);
            if (!waitForItemInHand(client, hoeName, 2000)) {
                result.addProperty("ok", false);
                result.addProperty("status", "no_item_in_hand");
                result.addProperty("item", hoeName);
                return result;
            }
            result.addProperty("item", hoeName);
            String r = clickBlockFace(client, player, pos, Direction.UP);
            result.addProperty("interactionResult", r);
            // T5.3-live/F-verify: poll (≤1.5s) for the farmland change
            // instead of a fixed settle that raced the server sync.
            BlockState settled = settlePollDescribe(client, pos,
                    st -> st.getBlock() instanceof FarmlandBlock, result);
            result.addProperty("status",
                    settled.getBlock() instanceof FarmlandBlock ? "farmland" : "not_farmland_after");
        } else if (action.equals("plant")) {
            if (item.isEmpty()) {
                result.addProperty("ok", false);
                result.addProperty("status", "no_seed_item");
                result.addProperty("error", "Provide the seed item name to plant (e.g. wheat_seeds).");
                return result;
            }
            equipItemByName(client, item);
            if (!waitForItemInHand(client, item, 2000)) {
                result.addProperty("ok", false);
                result.addProperty("status", "no_item_in_hand");
                result.addProperty("item", item);
                return result;
            }
            result.addProperty("item", item);
            String r = clickBlockFace(client, player, pos, Direction.UP);
            result.addProperty("interactionResult", r);
            // T5.3-live/F-verify: poll for a crop block above, then for the
            // farmland under it. The planted crop occupies the block ABOVE
            // the farmland — poll both (crop at y+1, farmland at y).
            BlockPos cropPos = pos.above();
            BlockState cropState = settlePollDescribe(client, cropPos,
                    st -> st.getBlock() instanceof CropBlock, new JsonObject());
            settlePollDescribe(client, pos,
                    st -> st.getBlock() instanceof FarmlandBlock || st.getBlock() instanceof CropBlock,
                    result);
            if (cropState.getBlock() instanceof CropBlock) {
                result.addProperty("status", "planted");
                result.addProperty("cropAt", cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ());
            } else {
                result.addProperty("status", "not_planted");
            }
        } else if (action.equals("harvest")) {
            String r = breakBlockAt(client, pos, before);
            result.addProperty("interactionResult", r);
            result.addProperty("status", "harvested");
            BlockState after = level.getBlockState(pos);
            result.addProperty("blockAfter", blockRegistryName(after));
            if (after.getBlock() instanceof CropBlock crop) {
                result.addProperty("cropAge", crop.getAge(after));
                result.addProperty("cropMaxAge", crop.getMaxAge());
                result.addProperty("cropMaxAgeReached", crop.isMaxAge(after));
            }
            result.addProperty("ok", true);
            return result;
        } else if (action.equals("fertilize")) {
            String bmName = "bone_meal";
            equipItemByName(client, bmName);
            if (!waitForItemInHand(client, bmName, 2000)) {
                result.addProperty("ok", false);
                result.addProperty("status", "no_item_in_hand");
                result.addProperty("item", bmName);
                return result;
            }
            result.addProperty("item", bmName);
            String r = clickBlockFace(client, player, pos, Direction.UP);
            result.addProperty("interactionResult", r);
            settleThenDescribe(client, pos, result);
            String afterName = blockRegistryName(level.getBlockState(pos));
            result.addProperty("status", "fertilized");
        } else {
            return errorJson("Unknown action '" + action
                    + "' - expected till, plant, harvest, or fertilize");
        }
        result.addProperty("ok", true);
        return result;
    }

    /** Wait (bounded) until the named item is in the main hand; true on success. */
    private static boolean waitForItemInHand(Minecraft client, String itemName, long timeoutMs) {
        String desired = simpleName(normalizeResourceId(itemName));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ItemStack held = client.player.getMainHandItem();
            if (!held.isEmpty()
                    && simpleName(itemRegistryName(held)).equals(desired)) {
                return true;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Click a block face with the main hand (use-item-on-block core click). */
    private static String clickBlockFace(Minecraft client, LocalPlayer player,
                                         BlockPos pos, Direction faceDir) {
        return callOnClient(client, () -> {
            lookAtFacePoint(client, pos, faceDir);
            Vec3 hitVec = facePointHitVec(pos, faceDir);
            BlockHitResult hitResult = new BlockHitResult(hitVec, faceDir, pos, false);
            InteractionResult r = client.gameMode.useItemOn(
                    player, InteractionHand.MAIN_HAND, hitResult);
            player.swing(InteractionHand.MAIN_HAND);
            return String.valueOf(r);
        });
    }

    /**
     * T5.3-live/F-verify: bounded poll (up to 1.5s) of the expected state
     * change, then record the block at pos into result as blockAfter.
     * Replaces the fixed 150ms settle that raced the live server's block
     * sync (till/plant landed server-side but the immediate read saw the
     * pre-change state → false `not_farmland_after` / `not_planted`).
     * Marshalled through callOnClient (runs on the QUEUE worker).
     * Returns the settled BlockState (fresh read, not the stale one).
     */
    private static BlockState settlePollDescribe(Minecraft client, BlockPos pos,
            java.util.function.Predicate<BlockState> expected, JsonObject result) {
        BlockState after = callOnClient(client, () -> client.level.getBlockState(pos));
        long deadline = System.currentTimeMillis() + 1500;
        while (after == null || !expected.test(after)) {
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(50); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            after = callOnClient(client, () -> client.level.getBlockState(pos));
        }
        if (after == null) after = callOnClient(client, () -> client.level.getBlockState(pos));
        result.addProperty("blockAfter", blockRegistryName(after));
        if (after.getBlock() instanceof CropBlock crop) {
            result.addProperty("cropAge", crop.getAge(after));
            result.addProperty("cropMaxAge", crop.getMaxAge());
            result.addProperty("cropMaxAgeReached", crop.isMaxAge(after));
        }
        return after;
    }

    /** 150ms settle then record the block at pos into result as blockAfter. */
    private static void settleThenDescribe(Minecraft client, BlockPos pos, JsonObject result) {
        try { Thread.sleep(150); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        BlockState after = client.level.getBlockState(pos);
        result.addProperty("blockAfter", blockRegistryName(after));
        if (after.getBlock() instanceof CropBlock crop) {
            result.addProperty("cropAge", crop.getAge(after));
            result.addProperty("cropMaxAge", crop.getMaxAge());
            result.addProperty("cropMaxAgeReached", crop.isMaxAge(after));
        }
    }

    /**
     * Any hoe in the inventory (registry name), or null when none.
     * T5.3-live/F-no_hoe: scan the WHOLE inventory (hotbar + main storage),
     * not just the hotbar — live: diamond_hoe sat in main storage and till
     * reported no_hoe. equipItemByName handles the hotbar move from there.
     * Marshalled: runs on the QUEUE worker.
     */
    private static String anyHoeInInventory(Minecraft client) {
        String found = callOnClient(client, () -> {
            Inventory inv = client.player.getInventory();
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && simpleName(itemRegistryName(st)).contains("hoe")) {
                    return itemRegistryName(st);
                }
            }
            for (int i = 9; i < 36; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && simpleName(itemRegistryName(st)).contains("hoe")) {
                    return itemRegistryName(st);
                }
            }
            return null;
        });
        return found;
    }

    /**
     * Break the block at pos (digBlock core, without auto-tool). Returns
     * "broken" or "timeout". T5.3-live/D1-2: farm-plot runs on the QUEUE
     * worker — every client-state touch is marshalled through callOnClient
     * (same fix as digBlock; the unmarshalled loop hit "Tried to access
     * render state from outside the main render thread" live on harvest).
     */
    private static String breakBlockAt(Minecraft client, BlockPos pos, BlockState state) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        Direction face = computeFaceTowards(player, pos);
        lookAtBlockCenter(client, pos);
        long deadline = System.currentTimeMillis() + 25000;
        if (player.isCreative()) {
            callOnClient(client, () -> {
                client.gameMode.destroyBlock(pos);
                return null;
            });
        } else {
            callOnClient(client, () -> {
                client.gameMode.startDestroyBlock(pos, face);
                client.player.swing(InteractionHand.MAIN_HAND);
                return null;
            });
            boolean airNow = Boolean.TRUE.equals(callOnClient(client,
                    () -> Boolean.valueOf(client.level.getBlockState(pos).isAir())));
            while (!airNow && System.currentTimeMillis() < deadline) {
                callOnClient(client, () -> {
                    client.gameMode.continueDestroyBlock(pos, face);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    return null;
                });
                try { Thread.sleep(50); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                airNow = Boolean.TRUE.equals(callOnClient(client,
                        () -> Boolean.valueOf(client.level.getBlockState(pos).isAir())));
            }
            if (!airNow) {
                callOnClient(client, () -> {
                    client.gameMode.stopDestroyBlock();
                    return null;
                });
                return "timeout";
            }
        }
        return "broken";
    }
    /**
     * villager-trade (T4.10): trade with a villager / wandering trader.
     * Resolves the merchant (entityId or name), opens the trade screen by
     * right-clicking it, waits for the MerchantMenu to open, then either
     * lists offers (action=list) or executes a trade (action=trade): select
     * the offer via setSelectionHint + handleInventoryButtonClick, move the
     * payment items into PAYMENT slots 0/1 (ContainerInput.SWAP), and take
     * the result from RESULT slot 2 (QUICK_MOVE). Verifies by result-stack
     * pickup and reports the offer list / traded items.
     */
    private static JsonObject villagerTrade(Minecraft client, JsonObject args) {
        String action = optionalString(args, "action", "list").toLowerCase();
        String entityName = optionalString(args, "entityName", "");
        int entityId = optionalInt(args, "entityId", -1);
        if (entityName.isEmpty() && entityId < 0) {
            return errorJson("Provide entityName or entityId");
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        // Resolve the merchant entity (same resolution as entity-interact).
        Entity merchant = null;
        if (entityId >= 0) {
            int id = entityId;
            merchant = callOnClient(client, () -> level.getEntity(id));
        }
        if (merchant == null && !entityName.isEmpty()) {
            merchant = callOnClient(client, () ->
                    resolveAttackTarget(level, player, entityName));
        }
        if (merchant == null) {
            return errorJson("No entity matching "
                    + (entityName.isEmpty() ? ("id " + entityId) : ("'" + entityName + "'"))
                    + " found nearby");
        }
        if (!(merchant instanceof AbstractVillager villager)) {
            return errorJson("Entity is not a villager/trader: "
                    + entityRegistryName(merchant));
        }

        JsonObject result = new JsonObject();
        result.addProperty("merchant", entityRegistryName(merchant));
        result.addProperty("action", action);

        // Open the trade screen by right-clicking the villager (no item).
        if (callOnClient(client, () -> client.player.containerMenu.containerId) != 0) {
            closeContainer(client);
        }
        final Entity target = merchant;
        InteractionResult openResult = callOnClient(client, () -> {
            lookAtEntity(client, target);
            return client.gameMode.interact(player, target,
                    new EntityHitResult(target), InteractionHand.MAIN_HAND);
        });
        player.swing(InteractionHand.MAIN_HAND);
        result.addProperty("interactResult", String.valueOf(openResult));

        // Wait (bounded) for the MerchantMenu to open.
        long deadline = System.currentTimeMillis() + 2000;
        AbstractContainerMenu menu = null;
        while (System.currentTimeMillis() < deadline) {
            AbstractContainerMenu current = client.player.containerMenu;
            if (current != null && current instanceof MerchantMenu) {
                menu = current;
                break;
    }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (menu == null) {
            result.addProperty("ok", false);
            result.addProperty("status", "no_trade_screen");
            result.addProperty("error", "Trade screen did not open - villager may be busy or trading locked.");
            return result;
        }
        MerchantMenu merchantMenu = (MerchantMenu) menu;
        int containerId = merchantMenu.containerId;

        // Offers snapshot (list from the menu, which mirrors server offers).
        MerchantOffers offers = callOnClient(client, () -> villager.getOffers());
        if (offers == null || offers.isEmpty()) {
            result.addProperty("ok", false);
            result.addProperty("screenOpened", true);
            result.addProperty("status", "no_offers");
            result.addProperty("error", "Villager has no trade offers.");
            closeContainer(client);
            return result;
        }
        JsonArray offersJson = new JsonArray();
        int idx = 0;
        for (MerchantOffer offer : offers) {
            JsonObject o = new JsonObject();
            o.addProperty("index", idx);
            o.addProperty("costA", itemRegistryName(offer.getCostA()));
            o.addProperty("costACount", offer.getCostA().getCount());
            o.addProperty("costB", offer.getCostB().isEmpty()
                    ? "" : itemRegistryName(offer.getCostB()));
            o.addProperty("costBCount", offer.getCostB().getCount());
            o.addProperty("result", itemRegistryName(offer.getResult()));
            o.addProperty("resultCount", offer.getResult().getCount());
            o.addProperty("outOfStock", offer.isOutOfStock());
            idx++;
            offersJson.add(o);
        }
        result.add("offers", offersJson);
        result.addProperty("offerCount", offers.size());
        if (action.equals("list")) {
            result.addProperty("ok", true);
            result.addProperty("status", "listed");
            closeContainer(client);
            return result;
        }

        if (!action.equals("trade")) {
            closeContainer(client);
            return errorJson("Unknown action '" + action + "' - expected list or trade");
        }
        int tradeIndex = optionalInt(args, "tradeIndex", -1);
        if (tradeIndex < 0 || tradeIndex >= offers.size()) {
            closeContainer(client);
            return errorJson("Invalid tradeIndex " + tradeIndex
                    + " - must be 0.." + (offers.size() - 1));
        }
        MerchantOffer offer = offers.get(tradeIndex);
        if (offer.isOutOfStock()) {
            closeContainer(client);
            result.addProperty("ok", false);
            result.addProperty("status", "out_of_stock");
            return result;
        }

        // Select the trade, then move payment into PAYMENT1 slot 0.
        callOnClient(client, () -> {
            merchantMenu.setSelectionHint(tradeIndex);
            return true;
        });
        client.gameMode.handleInventoryButtonClick(containerId, tradeIndex);
        try { Thread.sleep(150); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Pay: swap payment items from the player inventory into PAYMENT slots.
        // PAYMENT1 = slot 0, PAYMENT2 = slot 1 (only when costB present).
        String costAName = itemRegistryName(offer.getCostA());
        int costACount = offer.getCostA().getCount();
        movedIntoPaymentSlot(client, containerId, 0, costAName, costACount);
        if (!offer.getCostB().isEmpty()) {
            String costBName = itemRegistryName(offer.getCostB());
            int costBCount = offer.getCostB().getCount();
            movedIntoPaymentSlot(client, containerId, 1, costBName, costBCount);
        }
        try { Thread.sleep(150); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Take the result: QUICK_MOVE from RESULT slot 2 into inventory.
        ItemStack before = callOnClient(client, () ->
                client.player.containerMenu.getSlot(2).getItem().copy());
        client.gameMode.handleContainerInput(containerId, 2, 0,
                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, client.player);
        try { Thread.sleep(150); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ItemStack after = callOnClient(client, () ->
                client.player.containerMenu.getSlot(2).getItem().copy());

        result.addProperty("tradeIndex", tradeIndex);
        result.addProperty("costA", costAName);
        result.addProperty("costACount", costACount);
        if (!offer.getCostB().isEmpty()) {
            result.addProperty("costB", itemRegistryName(offer.getCostB()));
            result.addProperty("costBCount", offer.getCostB().getCount());
        }
        result.addProperty("resultItem", itemRegistryName(offer.getResult()));
        boolean taken = after.isEmpty() && !before.isEmpty();
        result.addProperty("ok", taken);
        result.addProperty("status", taken ? "traded" : "not_traded");
        if (!taken) {
            result.addProperty("error", "Result slot did not clear - payment may be "
                    + "insufficient (check cost counts) or trade locked.");
        }
        closeContainer(client);
        return result;
    }

    /** Move payment items from player inventory into a MerchantMenu payment slot. */
    private static void movedIntoPaymentSlot(Minecraft client, int containerId,
                                              int paymentSlot, String itemName, int count) {
        Inventory inv = client.player.getInventory();
        int remaining = count;
        // First try hotbar + main inventory; SWAP whole stacks into the slot.
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            int from = pass == 0 ? 0 : 9;
            int to = pass == 0 ? 9 : inv.getContainerSize();
            for (int i = from; i < to && remaining > 0; i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) continue;
                if (!itemRegistryName(st).equals(itemName)) continue;
                int windowSlot = invSlotToWindow(i);
                client.gameMode.handleContainerInput(
                        containerId, windowSlot, paymentSlot,
                        net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
                remaining -= st.getCount();
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    private static JsonObject placeBlock(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        // Determine the desired block to place (optional: equip it from inventory)
        String desiredBlock = optionalString(args, "block", "");
        String family = optionalString(args, "family", "");
        if (!desiredBlock.isEmpty()) {
            equipItemByName(client, desiredBlock);
        } else if (!family.isEmpty()) {
            // Try to equip any item matching the family suffix
            equipItemByFamily(client, family);
        }
        // The server must see the desired item
        // in hand before we click - the SWAP + carried-item packets take a
        // tick to land. Bounded wait on the client main-hand view.
        if (!desiredBlock.isEmpty()) {
            String desiredSimple = simpleName(normalizeResourceId(desiredBlock));
            boolean inHand = false;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                ItemStack held = client.player.getMainHandItem();
                if (!held.isEmpty()
                        && simpleName(itemRegistryName(held)).equals(desiredSimple)) {
                    inHand = true;
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!inHand) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("status", "no_material_in_hand");
                result.addProperty("x", x);
                result.addProperty("y", y);
                result.addProperty("z", z);
                result.addProperty("block", desiredBlock);
                return result;
            }
        }

        // The face direction: which side of the neighbor block we're placing against
        String faceDirName = optionalString(args, "faceDirection", "up");
        Direction faceDir = Direction.byName(faceDirName.toLowerCase());
        if (faceDir == null) faceDir = Direction.UP;

        // We place AT (x,y,z) by clicking on the neighbor block in the faceDir direction.
        // The neighbor is pos.offset(faceDir.opposite()) — the block we right-click on.
        BlockPos neighbor = pos.relative(faceDir.getOpposite());
        BlockState neighborState = level.getBlockState(neighbor);

        // If the target pos is occupied, we can't place there
        if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced()) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("status", "occupied");
            result.addProperty("x", x);
            result.addProperty("y", y);
            result.addProperty("z", z);
            result.addProperty("block", blockRegistryName(level.getBlockState(pos)));
            return result;
        }

        // If neighbor is air (no solid surface), use the target pos itself as the hit
        // (place against air — works for e.g. placing on replaceable blocks)
        BlockPos hitPos = neighbor;
        Direction hitDir = faceDir;
        if (neighborState.isAir() || neighborState.canBeReplaced()) {
            // Try placing against the target position itself (self-click for replaceable)
            hitPos = pos;
            hitDir = faceDir;
        }

        // Look at and click a point ON the hit face -
        // MC 1.21.11 rejects block-center hit vectors claimed against a face.
        lookAtFacePoint(client, hitPos, hitDir);
        Vec3 hitVec = facePointHitVec(hitPos, hitDir);
        BlockHitResult hitResult = new BlockHitResult(hitVec, hitDir, hitPos, false);

        // Perform the placement
        InteractionResult interactionResult = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        // Check if the block was placed (short delay for server round-trip)
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        BlockState afterState = level.getBlockState(pos);
        boolean placed = !afterState.isAir() && !blockRegistryName(afterState).equals("minecraft:air");

        JsonObject result = new JsonObject();
        result.addProperty("ok", placed);
        result.addProperty("status", placed ? "placed": "failed");
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("block", blockRegistryName(afterState));
        result.addProperty("interactionResult", interactionResult.toString());
        if (!desiredBlock.isEmpty()) result.addProperty("requestedBlock", desiredBlock);
        return result;
    }

    private static JsonObject scanArea(Minecraft client, JsonObject args) {
        int radius = optionalInt(args, "radius", 8);
        if (radius > 16) radius = 16;
        int yRange = optionalInt(args, "yRange", 3);
        if (yRange > 8) yRange = 8;
        String blockFilter = optionalString(args, "blockFilter", "");
        String filterName = normalizeResourceId(blockFilter);

        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        BlockPos origin = player.blockPosition();

        Map<String, Integer> counts = new HashMap<>();
        List<JsonObject> matches = new ArrayList<>();
        JsonArray hazards = new JsonArray();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -yRange; dy <= yRange; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    String name = blockRegistryName(state);
                    counts.merge(name, 1, Integer::sum);

                    // Hazard detection
                    if (isHazard(name)) {
                        JsonObject h = new JsonObject();
                        h.addProperty("block", name);
                        h.addProperty("x", pos.getX());
                        h.addProperty("y", pos.getY());
                        h.addProperty("z", pos.getZ());
                        hazards.add(h);
                    }

                    // Filter matches
                    if (!filterName.isEmpty() && (name.equals(filterName) || name.endsWith(":" + filterName))) {
                        JsonObject m = new JsonObject();
                        m.addProperty("x", pos.getX());
                        m.addProperty("y", pos.getY());
                        m.addProperty("z", pos.getZ());
                        m.addProperty("block", name);
                        matches.add(m);
                    }
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("radius", radius);
        result.addProperty("yRange", yRange);
        result.addProperty("centerX", origin.getX());
        result.addProperty("centerY", origin.getY());
        result.addProperty("centerZ", origin.getZ());

        JsonObject blockCounts = new JsonObject();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            blockCounts.addProperty(e.getKey(), e.getValue());
        }
        result.add("blockCounts", blockCounts);
        result.addProperty("uniqueTypes", counts.size());
        result.add("hazards", hazards);
        result.addProperty("hazardCount", hazards.size());

        if (!filterName.isEmpty()) {
            JsonArray matchArray = new JsonArray();
            for (JsonObject m : matches) {
                matchArray.add(m);
            }
            result.add("matches", matchArray);
            result.addProperty("matchCount", matches.size());
            result.addProperty("blockFilter", blockFilter);
        }

        return result;
    }

    // ── Tier B helpers ──

    private static Direction computeFaceTowards(LocalPlayer player, BlockPos pos) {
        // Determine which face of the block the player is closest to
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double absX = Math.abs(dx);
        double absY = Math.abs(dy);
        double absZ = Math.abs(dz);
        if (absY > absX && absY > absZ) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX > absZ) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static void lookAtBlockCenter(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        double tx = pos.getX() + 0.5;
        double ty = pos.getY() + 0.5;
        double tz = pos.getZ() + 0.5;
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horizontalDistance) * 180.0 / Math.PI);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
    }

    /**
     * SetSelectedSlot() is CLIENT-LOCAL only -
     * the server tracks the held slot from ServerboundSetCarriedItemPacket.
     * Every equip must send it or the server's held item diverges from the
     * client's (place-block then places whatever the server last knew was
     * held). All setSelectedSlot callers route through this helper now.
     */
    private static void syncSelectedSlot(Minecraft client, int slot) {
        Inventory inv = client.player.getInventory();
        inv.setSelectedSlot(slot);
        client.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * Compute a hit vector that lies ON the clicked face.
     * Vec3.atCenterOf(pos) claims the block CENTER while hitDir claims a
     * face - MC 1.21.11 server-side validation rejects that combination
     * (empty class_9859[] interaction result). Center + half-block toward
     * the face, pulled an epsilon inside so the point stays on the face
     * plane (not on the boundary between blocks).
     */
    private static Vec3 facePointHitVec(BlockPos pos, Direction dir) {
        double f = 0.5 - 0.001;
        return Vec3.atCenterOf(pos).add(
                dir.getStepX() * f,
                dir.getStepY() * f,
                dir.getStepZ() * f);
    }

    /** Look AT the face point (the server cross-checks the
     * eye ray against the hit result, so look target and hit vec must agree). */
    private static void lookAtFacePoint(Minecraft client, BlockPos pos, Direction dir) {
        Vec3 target = facePointHitVec(pos, dir);
        LocalPlayer player = client.player;
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horizontalDistance) * 180.0 / Math.PI);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
    }

    private static String equipBestToolForBlock(Minecraft client, BlockState state) {
        // Determine the preferred tool type for this block
        String preferredType = preferredToolType(state);
        if (preferredType.isEmpty()) return "none";

        Inventory inv = client.player.getInventory();
        // Search hotbar for a tool of the preferred type
        int bestSlot = -1;
        int bestTier = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.contains(preferredType + "_pickaxe") || name.contains(preferredType + "_axe")
                    || name.contains(preferredType + "_shovel") || name.contains(preferredType + "_hoe")) {
                int tier = toolTier(name);
                if (tier > bestTier) {
                    bestTier = tier;
                    bestSlot = i;
                }
            }
        }
        if (bestSlot >= 0) {
            syncSelectedSlot(client, bestSlot);
            return itemRegistryName(inv.getItem(bestSlot));
        }
        // No specialized tool found — keep current selection
        // T5.3/D1-1: syncSelectedSlot changes the selected slot server-side
        // without immediately updating the client inventory snapshot, so the
        // OLD held item was echoed in `tool:` when no specialized tool was
        // found. Re-read the selected stack AFTER the swap so the field
        // reports the item actually used for the dig.
        String heldAfterSwap = itemRegistryName(inv.getItem(inv.getSelectedSlot()));
        return heldAfterSwap.isEmpty() ? "none" : heldAfterSwap;
    }

    private static String preferredToolType(BlockState state) {
        String blockName = blockRegistryName(state);
        if (blockName.contains("stone") || blockName.contains("cobblestone") || blockName.contains("ore")
                || blockName.contains("iron") || blockName.contains("diamond") || blockName.contains("gold")
                || blockName.contains("emerald") || blockName.contains("redstone") || blockName.contains("lapis")
                || blockName.contains("netherrack") || blockName.contains("basalt") || blockName.contains("granite")
                || blockName.contains("diorite") || blockName.contains("andesite") || blockName.contains("brick")
                || blockName.contains("terracotta") || blockName.contains("obsidian")) {
            return "pickaxe";
        }
        if (blockName.contains("log") || blockName.contains("planks") || blockName.contains("wood")
                || blockName.contains("fence") || blockName.contains("door") || blockName.contains("chest")) {
            return "axe";
        }
        if (blockName.contains("dirt") || blockName.contains("grass") || blockName.contains("sand")
                || blockName.contains("gravel") || blockName.contains("snow") || blockName.contains("soul_sand")) {
            return "shovel";
        }
        return "";
    }

    private static int toolTier(String itemName) {
        if (itemName.contains("netherite")) return 5;
        if (itemName.contains("diamond")) return 4;
        if (itemName.contains("iron")) return 3;
        if (itemName.contains("stone")) return 2;
        if (itemName.contains("golden") || itemName.contains("gold")) return 1;
        if (itemName.contains("wooden") || itemName.contains("wood")) return 1;
        return 0;
    }

    private static boolean isHazard(String blockName) {
        return blockName.equals("minecraft:lava") || blockName.equals("minecraft:flowing_lava")
                || blockName.equals("minecraft:fire") || blockName.equals("minecraft:soul_fire")
                || blockName.equals("minecraft:cactus") || blockName.equals("minecraft:magma_block")
                || blockName.equals("minecraft:powder_snow") || blockName.equals("minecraft:wither_rose")
                || blockName.equals("minecraft:sweet_berry_bush") || blockName.equals("minecraft:campfire")
                || blockName.equals("minecraft:soul_campfire") || blockName.equals("minecraft:pointed_dripstone");
    }

    private static void equipItemByName(Minecraft client, String itemName) {
        // A stale open container menu (auto-opened table/chest)
        // changes the window layout under us — close it before clicking.
        if (client.player.containerMenu.containerId != 0) closeContainer(client);
        String targetName = normalizeResourceId(itemName);
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.equals(targetName) || name.endsWith(":" + targetName)
                    || name.contains(itemName)) {
                syncSelectedSlot(client, i);
                return;
            }
        }
        // Search main inventory and swap if found
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.equals(targetName) || name.endsWith(":" + targetName)
                    || name.contains(itemName)) {
                int destSlot = -1;
                for (int j = 0; j < 9; j++) {
                    if (inv.getItem(j).isEmpty()) { destSlot = j; break; }
                }
                if (destSlot < 0) destSlot = inv.getSelectedSlot();
                int syncId = client.player.containerMenu.containerId;
                client.gameMode.handleContainerInput(
                        syncId, invSlotToWindow(i), destSlot,
                        net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
                syncSelectedSlot(client, destSlot);
                return;
            }
        }
    }

    private static void equipItemByFamily(Minecraft client, String family) {
        // Close any stale open container menu before clicking.
        if (client.player.containerMenu.containerId != 0) closeContainer(client);
        // Equip any held item whose name contains the family suffix (e.g. "planks" → any *_planks)
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.contains(family)) {
                syncSelectedSlot(client, i);
                return;
            }
        }
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.contains(family)) {
                int destSlot = -1;
                for (int j = 0; j < 9; j++) {
                    if (inv.getItem(j).isEmpty()) { destSlot = j; break; }
                }
                if (destSlot < 0) destSlot = inv.getSelectedSlot();
                int syncId = client.player.containerMenu.containerId;
                client.gameMode.handleContainerInput(
                        syncId, invSlotToWindow(i), destSlot,
                        net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
                syncSelectedSlot(client, destSlot);
                return;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier C: Inventory Basics
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject listInventory(Minecraft client, JsonObject args) {
        Inventory inv = client.player.getInventory();
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("name", itemRegistryName(stack));
            entry.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                entry.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
                entry.addProperty("maxDurability", stack.getMaxDamage());
            }
            items.add(entry);
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        result.addProperty("count", items.size());
        result.addProperty("selectedSlot", inv.getSelectedSlot());
        return result;
    }

    private static JsonObject findItem(Minecraft client, JsonObject args) {
        String nameOrType = requiredString(args, "nameOrType");
        String targetName = normalizeResourceId(nameOrType);
        Inventory inv = client.player.getInventory();
        JsonArray found = new JsonArray();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            String itemName = itemRegistryName(stack);
            if (itemName.equals(targetName) || itemName.endsWith(":" + targetName)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot);
                entry.addProperty("name", itemName);
                entry.addProperty("count", stack.getCount());
                found.add(entry);
            }
        }
        JsonObject result = new JsonObject();
        result.add("items", found);
        result.addProperty("count", found.size());
        result.addProperty("found", found.size() > 0);
        return result;
    }

    private static JsonObject equipItem(Minecraft client, JsonObject args) {
        String itemName = requiredString(args, "itemName");
        String targetName = normalizeResourceId(itemName);
        Inventory inv = client.player.getInventory();

        // Search hotbar first (slots 0-8)
        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.equals(targetName) || name.endsWith(":" + targetName)) {
                hotbarSlot = i;
                break;
            }
        }

        JsonObject result = new JsonObject();
        if (hotbarSlot >= 0) {
            syncSelectedSlot(client, hotbarSlot);
            result.addProperty("status", "equipped");
            result.addProperty("slot", hotbarSlot);
            result.addProperty("item", itemRegistryName(inv.getItem(hotbarSlot)));
            return result;
        }

        // A stale open container menu (auto-opened table/chest)
        // shifts the window layout — close it before the SWAP click below.
        if (client.player.containerMenu.containerId != 0) closeContainer(client);
        // Not in hotbar — search main inventory (slots 9-35)
        int mainSlot = -1;
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.equals(targetName) || name.endsWith(":" + targetName)) {
                mainSlot = i;
                break;
            }
        }

        if (mainSlot >= 0) {
            // Swap to first empty hotbar slot, or current selected slot
            int destSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).isEmpty()) {
                    destSlot = i;
                    break;
                }
            }
            if (destSlot < 0) destSlot = inv.getSelectedSlot();

            // Use inventory click to swap: player inventory slots are offset by 9 in the container
            int syncId = client.player.containerMenu.containerId;
            client.gameMode.handleContainerInput(
                    syncId, invSlotToWindow(mainSlot), destSlot,
                    net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
            syncSelectedSlot(client, destSlot);
            result.addProperty("status", "equipped");
            result.addProperty("slot", destSlot);
            result.addProperty("swappedFrom", mainSlot);
            result.addProperty("item", itemName);
            return result;
        }

        result.addProperty("status", "not_found");
        result.addProperty("item", itemName);
        return result;
    }


    // ──────────────────────────────────────────────────────────────────────
    // Tier E: Survival & Inventory Management
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject eatFood(Minecraft client, JsonObject args) {
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();
        String foodName = optionalString(args, "foodName", "");
        int minCount = optionalInt(args, "minCount", 18);
        int startFood = player.getFoodData().getFoodLevel();

        if (startFood >= minCount) {
            JsonObject result = new JsonObject();
            result.addProperty("ate", "");
            result.addProperty("food", startFood);
            result.addProperty("saturation", player.getFoodData().getSaturationLevel());
            result.addProperty("message", "Food already at " + startFood + "/20 (target " + minCount + ") — no need to eat");
            return result;
        }

        // Find the best food item in inventory
        int bestSlot = -1;
        int bestQuality = -1;
        String bestName = "";
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties foodProps = stack.get(DataComponents.FOOD);
            if (foodProps == null) continue; // not edible
            String name = itemRegistryName(stack);
            // If a specific food is requested, only consider matching items
            if (!foodName.isEmpty()) {
                String target = normalizeResourceId(foodName);
                if (!name.equals(target) && !name.endsWith(":" + foodName)) continue;
            }
            int quality = foodProps.nutrition() + (int) foodProps.saturation();
            if (quality > bestQuality) {
                bestQuality = quality;
                bestSlot = i;
                bestName = name;
            }
        }

        JsonObject result = new JsonObject();
        if (bestSlot < 0) {
            result.addProperty("ate", "");
            result.addProperty("food", startFood);
            result.addProperty("error", foodName.isEmpty()
                    ? "No edible food in inventory"
: "No '" + foodName + "' (or it is not edible) in inventory");
            return result;
        }

        // Equip the food to main hand
        equipItemByName(client, bestName);

        // Eat: start using item, keep using until done or food level reached
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        int iterations = 0;
        int eatenCount = 0;
        java.util.List<String> eaten = new ArrayList<>();

        while (player.getFoodData().getFoodLevel() < minCount && iterations < 20) {
            iterations++;
            // Check if we still have food in hand
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty() || held.get(DataComponents.FOOD) == null) {
                // Find more food
                bestSlot = -1;
                bestQuality = -1;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    FoodProperties fp = stack.get(DataComponents.FOOD);
                    if (fp == null) continue;
                    String name = itemRegistryName(stack);
                    if (!foodName.isEmpty()) {
                        String target = normalizeResourceId(foodName);
                        if (!name.equals(target) && !name.endsWith(":" + foodName)) continue;
                    }
                    int q = fp.nutrition() + (int) fp.saturation();
                    if (q > bestQuality) { bestQuality = q; bestSlot = i; bestName = name; }
                }
                if (bestSlot < 0) break;
                equipItemByName(client, bestName);
            }

            // Start eating
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            // Wait for eating to complete (32 ticks = 1.6 seconds)
            try {
                Thread.sleep(1700);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            eaten.add(bestName);
            eatenCount++;
        }

        // Defect 4: settle wait so the server food-level sync is
        // applied by the client tick before the response reads it (the
        // old body read it while the eat loop's own sleeps had the client
        // thread frozen). Now on the queue worker, ticks flow during the
        // sleeps; the settle gives the last item's sync a moment.
        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        int endFood = player.getFoodData().getFoodLevel();
        JsonArray eatenArr = new JsonArray();
        for (String s : eaten) eatenArr.add(s);
        result.add("ate", eatenArr);
        result.addProperty("food", endFood);
        result.addProperty("saturation", player.getFoodData().getSaturationLevel());
        result.addProperty("message", "Ate " + eatenCount + " item(s): food " + startFood + " -> " + endFood + "/20");
        return result;
    }

    private static JsonObject placeTorch(Minecraft client, JsonObject args) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        Inventory inv = player.getInventory();

        // Find a torch (or soul torch) in inventory
        String torchName = "";
        int torchSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String name = itemRegistryName(stack);
            if (name.equals("minecraft:torch") || name.equals("minecraft:soul_torch")) {
                torchName = name;
                torchSlot = i;
                break;
            }
        }

        JsonObject result = new JsonObject();
        if (torchSlot < 0) {
            result.addProperty("placed", false);
            result.addProperty("error", "No torch in inventory. Craft torches first (stick + coal/charcoal).");
            return result;
        }

        // Equip the torch
        equipItemByName(client, torchName);

        // Try placing a torch at neighboring positions
        BlockPos feet = player.blockPosition();
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] offset : offsets) {
            BlockPos spot = feet.offset(offset[0], 0, offset[1]);
            BlockState spotState = level.getBlockState(spot);
            BlockState floorState = level.getBlockState(spot.below());
            if (!spotState.isAir() && !spotState.canBeReplaced()) continue;
            if (!floorState.isFaceSturdy(level, spot.below(), Direction.UP)) continue;

            // Look at the floor block and place on top
            lookAtFacePoint(client, spot.below(), Direction.UP);
            Vec3 hitVec = facePointHitVec(spot.below(), Direction.UP);
            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, spot.below(), false);
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            player.swing(InteractionHand.MAIN_HAND);

            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            BlockState afterState = level.getBlockState(spot);
            if (!afterState.isAir() && !blockRegistryName(afterState).equals("minecraft:air")) {
                result.addProperty("placed", true);
                JsonObject pos = new JsonObject();
                pos.addProperty("x", spot.getX());
                pos.addProperty("y", spot.getY());
                pos.addProperty("z", spot.getZ());
                result.add("position", pos);
                result.addProperty("block", blockRegistryName(afterState));
                result.addProperty("message", "Placed torch at (" + spot.getX() + ", " + spot.getY() + ", " + spot.getZ() + ")");
                return result;
            }
        }

        result.addProperty("placed", false);
        result.addProperty("error", "Could not find a valid spot to place a torch next to the bot (need air with a solid block below)");
        return result;
    }

    private static JsonObject autoEquipBestGear(Minecraft client, JsonObject args) {
        boolean doArmor = optionalBool(args, "armor", true);
        boolean doWeapon = optionalBool(args, "weapon", true);
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();

        JsonArray equipped = new JsonArray();
        JsonArray skipped = new JsonArray();

        if (doArmor) {
            // Check each armor slot: head, chest, legs, feet
            EquipmentSlot[] armorSlots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
            };
            for (EquipmentSlot slot : armorSlots) {
                ItemStack current = player.getItemBySlot(slot);
                int currentTier = current.isEmpty() ? -1 : armorTier(current);
                // Search inventory for best armor for this slot
                int bestSlot = -1;
                int bestTier = currentTier;
                String bestName = "";
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                    if (equippable == null) continue;
                    if (equippable.slot() != slot) continue;
                    int tier = armorTier(stack);
                    if (tier > bestTier) {
                        bestTier = tier;
                        bestSlot = i;
                        bestName = itemRegistryName(stack);
                    }
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot.getName());
                if (bestSlot >= 0) {
                    // Equip by right-clicking the item (uses the inventory mechanic)
                    // Use shift-click to move armor to its slot
                    int syncId = player.containerMenu.containerId;
                    client.gameMode.handleContainerInput(
                            syncId, invSlotToWindow(bestSlot), 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
                    entry.addProperty("from", current.isEmpty() ? "(empty)": itemRegistryName(current));
                    entry.addProperty("to", bestName);
                    entry.addProperty("tierDelta", bestTier - Math.max(currentTier, 0));
                    equipped.add(entry);
                } else {
                    entry.addProperty("slot", slot.getName());
                    entry.addProperty("reason", current.isEmpty() ? "none_in_inventory": "current_is_best_or_equal");
                    skipped.add(entry);
                }
            }
        }

        if (doWeapon) {
            // Find the best weapon in inventory (sword tiers + bow/crossbow/trident)
            int bestSlot = -1;
            int bestTier = -1;
            String bestName = "";
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                int tier = weaponTier(stack);
                if (tier > bestTier) {
                    bestTier = tier;
                    bestSlot = i;
                    bestName = name;
                }
            }
            ItemStack currentWeapon = inv.getItem(inv.getSelectedSlot());
            int currentTier = weaponTier(currentWeapon);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", "mainhand");
            if (bestSlot >= 0 && bestTier > currentTier) {
                equipItemByName(client, bestName);
                entry.addProperty("from", currentWeapon.isEmpty() ? "(empty)": itemRegistryName(currentWeapon));
                entry.addProperty("to", bestName);
                entry.addProperty("tierDelta", bestTier - currentTier);
                equipped.add(entry);
            } else {
                entry.addProperty("reason", currentWeapon.isEmpty() ? "none_in_inventory": "current_is_best_or_equal");
                skipped.add(entry);
            }
        }

        JsonObject result = new JsonObject();
        result.add("equipped", equipped);
        result.add("skipped", skipped);
        result.addProperty("message", equipped.size() > 0
                ? "Equipped " + equipped.size() + " slot(s)"
: "No upgrades available; skipped " + skipped.size() + " slot(s)");
        return result;
    }

    private static JsonObject moveItem(Minecraft client, JsonObject args) {
        int sourceSlot = requiredInt(args, "sourceSlot");
        int destSlot = requiredInt(args, "destSlot");
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();

        JsonObject result = new JsonObject();
        if (sourceSlot == destSlot) {
            result.addProperty("moved", false);
            result.addProperty("sourceSlot", sourceSlot);
            result.addProperty("destSlot", destSlot);
            result.addProperty("reason", "same_slot");
            return result;
        }

        // The legacy uses window slots 9-35 (main), 36-44 (hotbar), 45 (offhand).
        // Minecraft inventory container slots: hotbar = 0-8, main = 9-35, armor = 5-8, offhand = 45.
        // We need to map: the legacy sourceSlot → MC container slot.
        // The legacy slot convention: slot 9-35 = main storage (MC container 9-35), 36-44 = hotbar (MC 0-8), 45 = offhand.
        int mcSourceSlot = nodeToMcSlot(sourceSlot);
        int mcDestSlot = nodeToMcSlot(destSlot);

        // Check source is not empty
        ItemStack sourceItem = getInventorySlot(inv, sourceSlot);
        if (sourceItem.isEmpty()) {
            result.addProperty("moved", false);
            result.addProperty("sourceSlot", sourceSlot);
            result.addProperty("destSlot", destSlot);
            result.addProperty("reason", "empty_source");
            return result;
        }

        // Capture BEFORE the clicks — PICKUP mutates the live
        // ItemStack in place (older code reported item=air/movedCount=0 because
        // it read the emptied source stack after the fact).
        String sourceName = itemRegistryName(sourceItem);
        int sourceCount = sourceItem.getCount();

        int syncId = player.containerMenu.containerId;
        // Use PICKUP click: click source, then click dest (handles merge + swap)
        client.gameMode.handleContainerInput(
                syncId, mcSourceSlot, 0,
                net.minecraft.world.inventory.ContainerInput.PICKUP, player);
        client.gameMode.handleContainerInput(
                syncId, mcDestSlot, 0,
                net.minecraft.world.inventory.ContainerInput.PICKUP, player);

        try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        ItemStack afterItem = getInventorySlot(inv, destSlot);
        result.addProperty("moved", true);
        result.addProperty("sourceSlot", sourceSlot);
        result.addProperty("destSlot", destSlot);
        result.addProperty("item", sourceName);
        result.addProperty("movedCount", sourceCount);
        if (!afterItem.isEmpty()) {
            result.addProperty("destCount", afterItem.getCount());
        }
        return result;
    }

    private static JsonObject organizeInventory(Minecraft client, JsonObject args) {
        String strategy = requiredString(args, "strategy");
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();

        JsonObject result = new JsonObject();
        if (strategy.equals("compact")) {
            // Merge same-name stacks: iterate main storage + hotbar, merge duplicates
            int mergeCount = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                // Try to merge into earlier slots of the same type
                for (int j = 0; j < i; j++) {
                    ItemStack earlier = inv.getItem(j);
                    if (earlier.isEmpty()) continue;
                    String earlierName = itemRegistryName(earlier);
                    if (!earlierName.equals(name)) continue;
                    if (earlier.getCount() >= earlier.getMaxStackSize()) continue;
                    // Merge: pickup from i, place on j
                    int syncId = player.containerMenu.containerId;
                    int mcI = nodeToMcSlot(i);
                    int mcJ = nodeToMcSlot(j);
                    client.gameMode.handleContainerInput(syncId, mcI, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                    client.gameMode.handleContainerInput(syncId, mcJ, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                    mergeCount++;
                    try { Thread.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    break;
                }
            }
            // Count occupied slots before and after
            int afterSlots = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (!inv.getItem(i).isEmpty()) afterSlots++;
            }
            result.addProperty("strategy", "compact");
            result.addProperty("executed", mergeCount);
            result.addProperty("afterSlots", afterSlots);
            result.addProperty("message", "Compacted inventory: " + mergeCount + " merge(s)");
            return result;
        }

        if (strategy.equals("hotbar-preference")) {
            // Parse preference array
            JsonElement prefElem = args.get("preference");
            if (prefElem == null || !prefElem.isJsonArray()) {
                result.addProperty("strategy", "hotbar-preference");
                result.addProperty("reason", "no_preference");
                JsonArray empty = new JsonArray();
                result.add("placed", empty);
                result.add("notFound", new JsonArray());
                return result;
            }
            JsonArray prefArr = prefElem.getAsJsonArray();
            JsonArray placed = new JsonArray();
            JsonArray notFound = new JsonArray();

            for (int idx = 0; idx < prefArr.size() && idx < 9; idx++) {
                String prefName = prefArr.get(idx).getAsString();
                String target = normalizeResourceId(prefName);
                int hotbarSlot = idx; // hotbar slots 0-8

                // Check if the desired item is already in this hotbar slot
                ItemStack current = inv.getItem(hotbarSlot);
                if (!current.isEmpty()) {
                    String curName = itemRegistryName(current);
                    if (curName.equals(target) || curName.endsWith(":" + prefName)) {
                        JsonObject p = new JsonObject();
                        p.addProperty("slot", hotbarSlot + 36); // legacy slot convention
                        p.addProperty("item", curName);
                        placed.add(p);
                        continue;
                    }
                }

                // Search main inventory for the item
                int foundSlot = -1;
                for (int i = 9; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) continue;
                    String name = itemRegistryName(stack);
                    if (name.equals(target) || name.endsWith(":" + prefName)) {
                        foundSlot = i;
                        break;
                    }
                }

                if (foundSlot >= 0) {
                    // Swap: move item from main to hotbar slot
                    int syncId = player.containerMenu.containerId;
                    client.gameMode.handleContainerInput(
                            syncId, invSlotToWindow(foundSlot), hotbarSlot,
                            net.minecraft.world.inventory.ContainerInput.SWAP, player);
                    try { Thread.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    JsonObject p = new JsonObject();
                    p.addProperty("slot", hotbarSlot + 36);
                    p.addProperty("item", itemRegistryName(inv.getItem(hotbarSlot)));
                    placed.add(p);
                } else {
                    notFound.add(prefName);
                }
            }

            result.addProperty("strategy", "hotbar-preference");
            result.add("placed", placed);
            result.add("notFound", notFound);
            result.addProperty("message", "Placed " + placed.size() + " item(s) into hotbar");
            return result;
        }

        throw new ToolException("INVALID_ARGUMENT", "Strategy must be 'compact' or 'hotbar-preference'");
    }


    // ──────────────────────────────────────────────────────────────────────
    // Tier F: Container Operations
    // ──────────────────────────────────────────────────────────────────────

    private static final double CONTAINER_REACH = 4.5;

    private static JsonObject openContainerLegacy(Minecraft client, JsonObject args) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        // Locate the container block
        BlockPos pos = locateContainerBlock(client, args);
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        // Check reach
        double dist = player.position().distanceTo(Vec3.atCenterOf(pos));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            result.addProperty("distance", dist);
            return result;
        }

        // Verify it's a container
        BlockState state = level.getBlockState(pos);
        String blockName = blockRegistryName(state);
        if (!isContainerBlock(state)) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "no container block at (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ") (found " + blockName + ")");
            return result;
        }

        // Right-click the container to open it
        Direction face = computeFaceTowards(player, pos);
        lookAtFacePoint(client, pos, face);
        Vec3 hitVec = facePointHitVec(pos, face);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        // Wait for the container menu to open (server round-trip)
        int waitMs = 0;
        int originalContainerId = player.containerMenu.containerId;
        while (player.containerMenu.containerId == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
        }

        if (player.containerMenu.containerId == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        // Read container contents: the container slots are the first N slots
        // (before the player inventory area which starts at slot 27 for chests)
        int playerInvStart = 27; // Default for chests (27 container slots)
        // For double chests: 54 container slots
        int totalSlots = player.containerMenu.slots.size();
        if (totalSlots >= 90) playerInvStart = 54; // Double chest
        else if (totalSlots >= 46) playerInvStart = 27; // Single chest / barrel
        else if (totalSlots >= 39) playerInvStart = 9; // Dispenser/dropper (9 slots)
        else playerInvStart = totalSlots - 36; // Fallback

        JsonArray containerItems = new JsonArray();
        for (int i = 0; i < playerInvStart; i++) {
            ItemStack stack = player.containerMenu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", i);
            entry.addProperty("name", itemRegistryName(stack));
            entry.addProperty("count", stack.getCount());
            containerItems.add(entry);
        }

        JsonArray inventoryItems = new JsonArray();
        for (int i = playerInvStart; i < totalSlots; i++) {
            ItemStack stack = player.containerMenu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", i - playerInvStart);
            entry.addProperty("name", itemRegistryName(stack));
            entry.addProperty("count", stack.getCount());
            inventoryItems.add(entry);
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("block", blockName);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        result.add("position", position);
        result.add("container", containerItems);
        result.add("inventory", inventoryItems);
        result.addProperty("containerId", player.containerMenu.containerId);

        // Close the container
        closeContainer(client);

        result.addProperty("message", "Opened " + blockName + " at (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")");
        return result;
    }

    private static JsonObject depositItemsLegacy(Minecraft client, JsonObject args) {
        String name = requiredString(args, "name");
        int count = optionalInt(args, "count", 1);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        BlockPos pos = locateContainerBlock(client, args);
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        double dist = player.position().distanceTo(Vec3.atCenterOf(pos));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            return result;
        }

        // Open the container
        Direction face = computeFaceTowards(player, pos);
        lookAtFacePoint(client, pos, face);
        Vec3 hitVec = facePointHitVec(pos, face);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        int waitMs = 0;
        int originalContainerId = player.containerMenu.containerId;
        while (player.containerMenu.containerId == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
        }

        if (player.containerMenu.containerId == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        String targetName = normalizeResourceId(name);
        int totalSlots = player.containerMenu.slots.size();
        int playerInvStart = totalSlots - 36;
        int syncId = player.containerMenu.containerId;

        // Find matching items in the player inventory area and move them to container
        int moved = 0;
        int remaining = count;
        for (int i = playerInvStart; i < totalSlots && remaining > 0; i++) {
            ItemStack stack = player.containerMenu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String itemName = itemRegistryName(stack);
            if (!itemName.equals(targetName) && !itemName.endsWith(":" + name)) continue;

            int toMove = Math.min(remaining, stack.getCount());
            // Use QUICK_MOVE (shift-click) to transfer the stack
            for (int j = 0; j < toMove; j++) {
                // We need to move partial stacks — use PICKUP to grab, then place in container
            }
            // Simpler: use QUICK_MOVE to shift-click the whole stack, then count what moved
            int beforeCount = stack.getCount();
            client.gameMode.handleContainerInput(syncId, i, 0,
                    net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            ItemStack after = player.containerMenu.getSlot(i).getItem();
            int actualMoved = beforeCount - (after.isEmpty() ? 0 : after.getCount());
            moved += actualMoved;
            remaining -= actualMoved;
        }

        closeContainer(client);

        JsonObject result = new JsonObject();
        result.addProperty("ok", moved > 0);
        result.addProperty("moved", moved);
        result.addProperty("requested", count);
        result.addProperty("remaining", count - moved);
        result.addProperty("reason", moved > 0 ? "ok": "item_not_in_inventory");
        result.addProperty("message", "Deposited " + moved + " " + name);
        return result;
    }

    private static JsonObject withdrawItemsLegacy(Minecraft client, JsonObject args) {
        String name = requiredString(args, "name");
        int count = optionalInt(args, "count", 1);
        ClientLevel level = client.level;
        LocalPlayer player = client.player;

        BlockPos pos = locateContainerBlock(client, args);
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        double dist = player.position().distanceTo(Vec3.atCenterOf(pos));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            return result;
        }

        // Open the container
        Direction face = computeFaceTowards(player, pos);
        lookAtFacePoint(client, pos, face);
        Vec3 hitVec = facePointHitVec(pos, face);
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);

        int waitMs = 0;
        int originalContainerId = player.containerMenu.containerId;
        while (player.containerMenu.containerId == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
        }

        if (player.containerMenu.containerId == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        String targetName = normalizeResourceId(name);
        int totalSlots = player.containerMenu.slots.size();
        int playerInvStart = totalSlots - 36;
        int syncId = player.containerMenu.containerId;

        // Find matching items in the container area and move them to player
        int moved = 0;
        int remaining = count;
        for (int i = 0; i < playerInvStart && remaining > 0; i++) {
            ItemStack stack = player.containerMenu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String itemName = itemRegistryName(stack);
            if (!itemName.equals(targetName) && !itemName.endsWith(":" + name)) continue;

            int beforeCount = stack.getCount();
            // Use QUICK_MOVE (shift-click) to transfer the stack to player inventory
            client.gameMode.handleContainerInput(syncId, i, 0,
                    net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            ItemStack after = player.containerMenu.getSlot(i).getItem();
            int actualMoved = beforeCount - (after.isEmpty() ? 0 : after.getCount());
            moved += actualMoved;
            remaining -= actualMoved;
        }

        closeContainer(client);

        JsonObject result = new JsonObject();
        result.addProperty("ok", moved > 0);
        result.addProperty("moved", moved);
        result.addProperty("requested", count);
        result.addProperty("remaining", count - moved);
        result.addProperty("reason", moved > 0 ? "ok": "item_not_in_container");
        result.addProperty("message", "Withdrew " + moved + " " + name);
        return result;
    }

    // ── 
    // open-container / deposit-items / withdraw-items previously ran via
    // client.execute() on the render thread, where their Thread.sleep(50)
    // container-open poll loops froze ticks and guaranteed
    // timeout_opening_container (verified live 3x on 1.1.5). dispatch() now
    // routes these three onto QUEUE_EXECUTOR; these bodies marshal every
    // Minecraft op through callOnClient (mirroring queueSmeltItem) and
    // defensively close any stale open menu first — a pre-open menu would
    // make the containerId-change poll unsatisfiable.
    // The old render-thread bodies are preserved above as *Legacy for
    // reference and are no longer called.

    private static int[] openContainerMenuAt(Minecraft client, BlockPos pos) {
        // One client-thread hop: look at the container's near face, right-click
        // it, swing, and
        // return [originalContainerId, containerIdAfterOpenAttempt].
        return callOnClient(client, () -> {
            LocalPlayer player = client.player;
            Direction face = computeFaceTowards(player, pos);
            lookAtFacePoint(client, pos, face);
            Vec3 hitVec = facePointHitVec(pos, face);
            BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            player.swing(InteractionHand.MAIN_HAND);
            return new int[]{player.containerMenu.containerId, player.containerMenu.containerId};
        });
    }

    private static JsonObject openContainer(Minecraft client, JsonObject args) {
        // Defensive close-at-start.
        callOnClient(client, () -> {
            if (client.player.containerMenu.containerId != 0) closeContainer(client);
            return null;
        });

        // Locate + validate the container block (client-thread reads).
        BlockPos pos = callOnClient(client, () -> locateContainerBlock(client, args));
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        double dist = callOnClient(client, () ->
                client.player.position().distanceTo(Vec3.atCenterOf(pos)));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            result.addProperty("distance", dist);
            return result;
        }

        BlockState state = callOnClient(client, () -> client.level.getBlockState(pos));
        String blockName = blockRegistryName(state);
        if (!isContainerBlock(state)) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "no container block at (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ") (found " + blockName + ")");
            return result;
        }

        // Open the container; poll for the server round-trip on the worker
        // thread so ticks keep running (a render-thread poll would deadlock
        // the client).
        int originalContainerId = openContainerMenuAt(client, pos)[0];
        int waitMs = 0;
        int cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        while (cur == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
            cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        }

        if (cur == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        // Read container + player inventory areas in one atomic client-thread
        // hop (same slot-count heuristics as the legacy body: 54 double chest,
        // 27 single chest/barrel, 9 dispenser/dropper, else totalSlots-36).
        JsonObject read = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            int totalSlots = player.containerMenu.slots.size();
            int playerInvStart;
            if (totalSlots >= 90) playerInvStart = 54; // Double chest
            else if (totalSlots >= 46) playerInvStart = 27; // Single chest / barrel
            else if (totalSlots >= 39) playerInvStart = 9; // Dispenser/dropper (9 slots)
            else playerInvStart = totalSlots - 36; // Fallback
            JsonArray containerItems = new JsonArray();
            for (int i = 0; i < playerInvStart; i++) {
                ItemStack stack = player.containerMenu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", i);
                entry.addProperty("name", itemRegistryName(stack));
                entry.addProperty("count", stack.getCount());
                containerItems.add(entry);
            }
            JsonArray inventoryItems = new JsonArray();
            for (int i = playerInvStart; i < totalSlots; i++) {
                ItemStack stack = player.containerMenu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", i - playerInvStart);
                entry.addProperty("name", itemRegistryName(stack));
                entry.addProperty("count", stack.getCount());
                inventoryItems.add(entry);
            }
            JsonObject r = new JsonObject();
            r.add("container", containerItems);
            r.add("inventory", inventoryItems);
            r.addProperty("containerId", player.containerMenu.containerId);
            return r;
        });

        // Close the container (client-thread hop; closeContainer sleeps).
        callOnClient(client, () -> { closeContainer(client); return null; });

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("block", blockName);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        result.add("position", position);
        result.add("container", read.get("container"));
        result.add("inventory", read.get("inventory"));
        result.addProperty("containerId", read.get("containerId").getAsInt());
        result.addProperty("message", "Opened " + blockName + " at (" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")");
        // Auto-record: container baseline into persistent memory
        recordContainerBaseline(pos, blockName, read.get("container").getAsJsonArray());
        return result;
    }

    private static JsonObject depositItems(Minecraft client, JsonObject args) {
        String name = requiredString(args, "name");
        int count = optionalInt(args, "count", 1);
        String targetName = normalizeResourceId(name);

        // Defensive close-at-start.
        callOnClient(client, () -> {
            if (client.player.containerMenu.containerId != 0) closeContainer(client);
            return null;
        });

        BlockPos pos = callOnClient(client, () -> locateContainerBlock(client, args));
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        double dist = callOnClient(client, () ->
                client.player.position().distanceTo(Vec3.atCenterOf(pos)));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            result.addProperty("distance", dist);
            return result;
        }

        // Open + poll (worker thread; same shape as openContainer above).
        int originalContainerId = openContainerMenuAt(client, pos)[0];
        int waitMs = 0;
        int cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        while (cur == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
            cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        }

        if (cur == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        // ── Count-exact deposit via cursor PICKUP split ──
        // The old block QUICK_MOVE'd whole stacks into the container,
        // ignoring `count` (requested 10 cobblestone, moved 50, reported
        // remaining -40 — the same whole-stack defect furnace slots had).
        // Now: pick up the whole matching stack, place
        // exactly the wanted count into a container slot (right click = one
        // item per click), return the cursor remainder to the source slot.
        // One callOnClient hop = atomic on the client thread. Returns total
        // moved, honestly capped at `count`.
        int moved = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            int totalSlots = player.containerMenu.slots.size();
            int playerInvStart = totalSlots - 36;
            int syncId = player.containerMenu.containerId;
            int movedTotal = 0;
            int remainingLocal = count;
            for (int i = playerInvStart; i < totalSlots && remainingLocal > 0; i++) {
                ItemStack stack = player.containerMenu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                String itemName = itemRegistryName(stack);
                if (!itemName.equals(targetName) && !itemName.endsWith(":" + name)) continue;
                // Find a container slot to place into: empty, or same item
                // with stack space (right-click merge needs headroom).
                int target = -1;
                for (int c = 0; c < playerInvStart; c++) {
                    ItemStack cs = player.containerMenu.getSlot(c).getItem();
                    if (cs.isEmpty()
                            || (cs.getCount() < cs.getMaxStackSize()
                                && itemRegistryName(cs).equals(itemName))) { target = c; break; }
                }
                if (target < 0) break; // container cannot take more of this item
                // Pick up the whole matching stack onto the cursor.
                client.gameMode.handleContainerInput(syncId, i, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                int cursorCount = player.containerMenu.getCarried().getCount();
                int toPlace = Math.min(remainingLocal, cursorCount);
                if (toPlace >= cursorCount) {
                    // Whole stack wanted: one left-click places everything
                    // (any merge overflow stays on the cursor and returns).
                    client.gameMode.handleContainerInput(syncId, target, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                } else {
                    // Right-click places ONE item per click — count-exact.
                    for (int n = 0; n < toPlace; n++) {
                        client.gameMode.handleContainerInput(syncId, target, 1,
                                net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                    }
                }
                // Return any cursor remainder to the source slot.
                if (!player.containerMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                }
                ItemStack after = player.containerMenu.getSlot(i).getItem();
                int actualMoved = cursorCount - (after.isEmpty() ? 0 : after.getCount());
                movedTotal += actualMoved;
                remainingLocal -= actualMoved;
            }
            return movedTotal;
        });
        // Close the container (client-thread hop; closeContainer sleeps).
        callOnClient(client, () -> { closeContainer(client); return null; });

        JsonObject result = new JsonObject();
        result.addProperty("ok", moved > 0);
        result.addProperty("moved", moved);
        result.addProperty("requested", count);
        result.addProperty("remaining", count - moved);
        result.addProperty("reason", moved > 0 ? "ok": "item_not_in_inventory");
        result.addProperty("message", "Deposited " + moved + " " + name);
        return result;
    }

    private static JsonObject withdrawItems(Minecraft client, JsonObject args) {
        String name = requiredString(args, "name");
        int count = optionalInt(args, "count", 1);
        String targetName = normalizeResourceId(name);

        // Defensive close-at-start.
        callOnClient(client, () -> {
            if (client.player.containerMenu.containerId != 0) closeContainer(client);
            return null;
        });

        BlockPos pos = callOnClient(client, () -> locateContainerBlock(client, args));
        if (pos == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "Container not found — pass either x,y,z or containerName");
            return result;
        }

        double dist = callOnClient(client, () ->
                client.player.position().distanceTo(Vec3.atCenterOf(pos)));
        if (dist > CONTAINER_REACH) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "out_of_reach");
            result.addProperty("distance", dist);
            return result;
        }

        // Open + poll (worker thread; same shape as openContainer above).
        int originalContainerId = openContainerMenuAt(client, pos)[0];
        int waitMs = 0;
        int cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        while (cur == originalContainerId && waitMs < 2000) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            waitMs += 50;
            cur = callOnClient(client, () -> client.player.containerMenu.containerId);
        }

        if (cur == originalContainerId) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "timeout_opening_container");
            return result;
        }

        // T5.3/I3-1: the containerId flips as soon as the screen opens, but
        // the server's slot-contents sync can land AFTER that — scanning
        // immediately sees an empty container and wrongly reports
        // item_not_in_container (live T5.2: stone x5 visible in the open view,
        // withdraw failed while cobblestone from the same chest worked).
        // Bounded wait (1s) for any non-empty container-side slot before
        // scanning. Skips instantly for genuinely empty containers at the
        // deadline (an empty chest then still fails honestly below).
        {
            long syncDeadline = System.currentTimeMillis() + 1000;
            boolean contentsSeen = false;
            while (System.currentTimeMillis() < syncDeadline) {
                Boolean anyNonEmpty = callOnClient(client, () -> {
                    int total = client.player.containerMenu.slots.size();
                    int invStart = total - 36;
                    for (int i = 0; i < invStart; i++) {
                        if (!client.player.containerMenu.getSlot(i).getItem().isEmpty()) {
                            return Boolean.TRUE;
                        }
                    }
                    return Boolean.FALSE;
                });
                if (Boolean.TRUE.equals(anyNonEmpty)) { contentsSeen = true; break; }
                try { Thread.sleep(50); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            if (!contentsSeen) {
                com.hyfuse.bridge.HyFuseClient.LOGGER.warn(
                        "withdraw-items: container slot contents did not sync within 1s of open");
            }
        }

        // ── Count-exact withdraw via cursor PICKUP split ──
        // Mirror of the depositItems fix: the old block QUICK_MOVE'd whole
        // stacks out of the container, ignoring `count` (observed live on
        // 1.1.7: requested 10, moved 50, remaining -40). Now: pick up the
        // whole matching stack from the container slot, place exactly the
        // wanted count into a player-inventory slot (right click = one item
        // per click), return the cursor remainder to the container slot.
        // One callOnClient hop = atomic on the client thread.
        int moved = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            int totalSlots = player.containerMenu.slots.size();
            int playerInvStart = totalSlots - 36;
            int syncId = player.containerMenu.containerId;
            int movedTotal = 0;
            int remainingLocal = count;
            for (int i = 0; i < playerInvStart && remainingLocal > 0; i++) {
                ItemStack stack = player.containerMenu.getSlot(i).getItem();
                if (stack.isEmpty()) continue;
                String itemName = itemRegistryName(stack);
                if (!itemName.equals(targetName) && !itemName.endsWith(":" + name)) continue;
                // Find a player-inventory slot to place into: empty, or same
                // item with stack space (right-click merge needs headroom).
                int target = -1;
                for (int c = playerInvStart; c < totalSlots; c++) {
                    ItemStack cs = player.containerMenu.getSlot(c).getItem();
                    if (cs.isEmpty()
                            || (cs.getCount() < cs.getMaxStackSize()
                                && itemRegistryName(cs).equals(itemName))) { target = c; break; }
                }
                if (target < 0) break; // player inventory cannot take more
                // Pick up the whole matching stack onto the cursor.
                client.gameMode.handleContainerInput(syncId, i, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                int cursorCount = player.containerMenu.getCarried().getCount();
                int toPlace = Math.min(remainingLocal, cursorCount);
                if (toPlace >= cursorCount) {
                    // Whole stack wanted: one left-click places everything
                    // (any merge overflow stays on the cursor and returns).
                    client.gameMode.handleContainerInput(syncId, target, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                } else {
                    // Right-click places ONE item per click — count-exact.
                    for (int n = 0; n < toPlace; n++) {
                        client.gameMode.handleContainerInput(syncId, target, 1,
                                net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                    }
                }
                // Return any cursor remainder to the source (container) slot.
                if (!player.containerMenu.getCarried().isEmpty()) {
                    client.gameMode.handleContainerInput(syncId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, player);
                }
                ItemStack after = player.containerMenu.getSlot(i).getItem();
                int actualMoved = cursorCount - (after.isEmpty() ? 0 : after.getCount());
                movedTotal += actualMoved;
                remainingLocal -= actualMoved;
            }
            return movedTotal;
        });
        // Close the container (client-thread hop; closeContainer sleeps).
        callOnClient(client, () -> { closeContainer(client); return null; });

        JsonObject result = new JsonObject();
        result.addProperty("ok", moved > 0);
        result.addProperty("moved", moved);
        result.addProperty("requested", count);
        result.addProperty("remaining", count - moved);
        result.addProperty("reason", moved > 0 ? "ok": "item_not_in_container");
        result.addProperty("message", "Withdrew " + moved + " " + name);
        return result;
    }

    // ─── Container helpers ───

    private static BlockPos locateContainerBlock(Minecraft client, JsonObject args) {
        ClientLevel level = client.level;
        // Explicit coords
        int x = optionalInt(args, "x", Integer.MIN_VALUE);
        int y = optionalInt(args, "y", Integer.MIN_VALUE);
        int z = optionalInt(args, "z", Integer.MIN_VALUE);
        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && z != Integer.MIN_VALUE) {
            return new BlockPos(x, y, z);
        }
        // Search by containerName
        String containerName = optionalString(args, "containerName", "");
        if (containerName.isEmpty()) return null;
        String target = normalizeResourceId(containerName);
        // Scan nearby blocks
        BlockPos origin = client.player.blockPosition();
        int radius = 16;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    String name = blockRegistryName(state);
                    if (name.equals(target) || name.endsWith(":" + containerName)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isContainerBlock(BlockState state) {
        String name = blockRegistryName(state);
        return name.equals("minecraft:chest") || name.equals("minecraft:trapped_chest")
                || name.equals("minecraft:barrel") || name.equals("minecraft:shulker_box")
                || name.equals("minecraft:dispenser") || name.equals("minecraft:dropper")
                || name.equals("minecraft:hopper") || name.contains("shulker_box")
                || name.equals("minecraft:crafting_table") || name.equals("minecraft:furnace")
                || name.equals("minecraft:blast_furnace") || name.equals("minecraft:smoker")
                || name.equals("minecraft:brewing_stand") || name.equals("minecraft:loom")
                || name.equals("minecraft:cartography_table") || name.equals("minecraft:smithing_table")
                || name.equals("minecraft:grindstone") || name.equals("minecraft:stonecutter")
                || name.equals("minecraft:anvil") || name.contains("anvil")
                || name.equals("minecraft:chiseled_bookshelf") || name.equals("minecraft:ender_chest");
    }

    private static void closeContainer(Minecraft client) {
        // Actually CLOSE the open container. The old body only
        // clicked slot -999 PICKUP — that drops the cursor stack but leaves the
        // menu OPEN; every later InventoryMenu-coordinate click then targeted
        // the foreign menu's layout (observed live on 1.1.4: equip SWAPs
        // landing one slot low under a stale CraftingMenu). player.closeContainer()
        // is the LocalPlayer public override that sends the real
        // ServerboundContainerClosePacket and restores the inventory menu.
        if (client.player.containerMenu.containerId != 0) {
            // Safety first: drop any stray cursor stack outside the window.
            client.gameMode.handleContainerInput(
                    client.player.containerMenu.containerId, -999, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP, client.player);
            client.player.closeContainer();
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static String blockRegistryName(BlockState state) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null ? key.toString(): "minecraft:air";
    }

    private static String entityRegistryName(Entity entity) {
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null ? key.toString(): "unknown";
    }

    private static String itemRegistryName(ItemStack stack) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString(): "minecraft:air";
    }

    /** Strip the namespace ("minecraft:iron_ingot" → "iron_ingot") — the legacy craft-resolver
     * uses bare names, so the craft-with-deps path normalizes to simple names. */
    private static String simpleName(String namespaced) {
        if (namespaced == null) return null;
        int idx = namespaced.indexOf(':');
        return idx >= 0 ? namespaced.substring(idx + 1) : namespaced;
    }

    private static String normalizeResourceId(String name) {
        if (name == null || name.isEmpty()) return "";
        String trimmed = name.trim();
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return "minecraft:" + trimmed;
    }

    private static int requiredInt(JsonObject args, String name) {
        JsonElement value = args.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGUMENT", "Argument '" + name + "' must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ToolException("INVALID_ARGUMENT", "Argument '" + name + "' must be a 32-bit integer");
        }
        return (int) number;
    }

    private static double requiredDouble(JsonObject args, String name) {
        JsonElement value = args.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGUMENT", "Argument '" + name + "' must be a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new ToolException("INVALID_ARGUMENT", "Argument '" + name + "' must be a finite number");
        }
        return number;
    }

    private static String requiredString(JsonObject args, String name) {
        JsonElement value = args.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ToolException("INVALID_ARGUMENT", "Argument '" + name + "' must be a string");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject args, String name, String defaultValue) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return defaultValue;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        return defaultValue;
    }

    private static int optionalInt(JsonObject args, String name, int defaultValue) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return defaultValue;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            if (Double.isFinite(number) && number == Math.rint(number)) {
                return (int) number;
            }
        }
        return defaultValue;
    }

    private static double optionalDouble(JsonObject args, String name, double defaultValue) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return defaultValue;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            if (Double.isFinite(number)) return number;
        }
        return defaultValue;
    }


    private static boolean optionalBool(JsonObject args, String name, boolean defaultValue) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return defaultValue;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return defaultValue;
    }

    private static int armorTier(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        Equippable eq = stack.get(DataComponents.EQUIPPABLE);
        if (eq == null) return -1;
        // Only count armor slots (not weapons like swords held in equipment slot)
        if (eq.slot() == EquipmentSlot.MAINHAND || eq.slot() == EquipmentSlot.OFFHAND) return -1;
        String name = itemRegistryName(stack);
        if (name.contains("netherite")) return 5;
        if (name.contains("diamond")) return 4;
        if (name.contains("iron")) return 3;
        if (name.contains("chainmail")) return 2;
        if (name.contains("golden") || name.contains("gold")) return 1;
        if (name.contains("leather")) return 1;
        return 0;
    }

    private static int weaponTier(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        String name = itemRegistryName(stack);
        // Swords: ranked by material tier
        if (name.contains("sword")) {
            if (name.contains("netherite")) return 15;
            if (name.contains("diamond")) return 14;
            if (name.contains("iron")) return 13;
            if (name.contains("stone")) return 12;
            if (name.contains("golden") || name.contains("gold")) return 11;
            if (name.contains("wooden") || name.contains("wood")) return 10;
            return 9;
        }
        // Ranged weapons
        if (name.contains("bow")) return 8;
        if (name.contains("crossbow")) return 8;
        if (name.contains("trident")) return 8;
        // Other tools have minor combat value
        if (name.contains("axe")) {
            if (name.contains("netherite")) return 7;
            if (name.contains("diamond")) return 6;
            if (name.contains("iron")) return 5;
            if (name.contains("stone")) return 4;
            if (name.contains("wooden") || name.contains("wood")) return 2;
        }
        return 0;
    }

    /**
     * Map legacy inventory slot numbers to Minecraft container slot numbers.
     * legacy slot convention: 9-35 = main storage, 36-44 = hotbar, 45 = offhand.
     * MC container: 0-8 = hotbar, 9-35 = main storage, 5-8 = armor, 45 = offhand.
     */
    private static int nodeToMcSlot(int nodeSlot) {
        // The the legacy slot convention IS the InventoryMenu window convention:
        // 9-35 main storage, 36-44 hotbar, 45 offhand — identity for all of
        // those. Only the direct-hotbar shorthand 0-8 needs +36. (
        // and earlier returned raw Inventory indices for 36-44, so clicks
        // landed on the crafting grid / armor / result slots.)
        if (nodeSlot >= 0 && nodeSlot <= 8) {
            return nodeSlot + 36; // direct hotbar index -> window slot
        }
        return nodeSlot; // 9-35 main, 36-44 hotbar, 45 offhand (identity)
    }

    /**
     * Map an Inventory slot index to the player
     * InventoryMenu WINDOW slot required by handleContainerInput.
     * Inventory: 0-8 hotbar, 9-35 main, 36-39 armor (feet,legs,chest,head),
     * 40 offhand. Window: 0=result, 1-4=craft grid, 5-8=armor
     * (head,chest,legs,feet), 9-35 main, 36-44 hotbar, 45 offhand.
     * Mirrors the live-verified toScreen mapping used by drop-items.
     */
    private static int invSlotToWindow(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return invSlot + 36;  // hotbar
        if (invSlot >= 36 && invSlot <= 39) return 44 - invSlot; // armor: 39(head)→5 … 36(feet)→8
        if (invSlot == 40) return 45;                           // offhand
        return invSlot;                                          // 9-35 main storage
    }

    /**
     * Get an inventory item by legacy slot number.
     * legacy slot convention: 9-35 = main storage (inv slots 9-35), 36-44 = hotbar (inv slots 0-8), 45 = offhand.
     */
    private static ItemStack getInventorySlot(Inventory inv, int nodeSlot) {
        if (nodeSlot >= 36 && nodeSlot <= 44) {
            return inv.getItem(nodeSlot - 36); // hotbar
        }
        if (nodeSlot == 45) {
            return inv.getItem(Inventory.SLOT_OFFHAND); // offhand
        }
        // 9-35 = main storage
        if (nodeSlot >= 9 && nodeSlot <= 35) {
            return inv.getItem(nodeSlot);
        }
        // 0-8 = direct hotbar access
        if (nodeSlot >= 0 && nodeSlot <= 8) {
            return inv.getItem(nodeSlot);
        }
        return ItemStack.EMPTY;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier G: Crafting & Smelting
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Resolve a SlotDisplay to an item registry name string.
     * Returns null if the display is not an item display (e.g. AnyFuel, Empty, Tag, etc.).
     */
    private static String slotDisplayToItemName(SlotDisplay display) {
        return slotDisplayToItemName(display, null);
    }

    /**
     * Overload with player context so tag ingredient slots
     * (#planks, #logs,...) project to a concrete member item, preferring
     * one the player actually holds. Result displays keep the 1-arg form
     * (vanilla results are not tags).
     */
    private static String slotDisplayToItemName(SlotDisplay display, LocalPlayer player) {
        if (display instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
            Holder<Item> holder = itemDisplay.item();
            Item item = holder.value();
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            return key != null ? key.toString() : null;
        } else if (display instanceof net.minecraft.world.item.crafting.display.SlotDisplay.TagSlotDisplay tagDisplay) {
            // Tag slot (#planks/#logs/...) - the vanilla norm for
            // wood-family recipes. Project to a concrete member: prefer one
            // the player holds, else the first member. Null only if empty.
            net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tagKey = tagDisplay.tag();
            String firstMember = null;
            for (net.minecraft.core.Holder<net.minecraft.world.item.Item> holder
                    : BuiltInRegistries.ITEM.getTagOrEmpty(tagKey)) {
                Identifier key = BuiltInRegistries.ITEM.getKey(holder.value());
                if (key == null) continue;
                if (firstMember == null) firstMember = key.toString();
                if (player != null) {
                    Inventory inv = player.getInventory();
                    for (int si = 0; si < inv.getContainerSize(); si++) {
                        ItemStack st = inv.getItem(si);
                        if (!st.isEmpty() && itemRegistryName(st).equals(key.toString())) {
                            return key.toString();
                        }
                    }
                }
            }
            return firstMember;
        } else if (display instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay) {
            ItemStack stack = stackDisplay.stack().create();
            if (!stack.isEmpty()) {
                return itemRegistryName(stack);
            }
        } else if (display instanceof SlotDisplay.Composite composite) {
            // Composite: return the first resolvable item
            for (SlotDisplay sub : composite.contents()) {
                String name = slotDisplayToItemName(sub, player);
                if (name != null) return name;
            }
        } else if (display instanceof SlotDisplay.WithRemainder withRemainder) {
            return slotDisplayToItemName(withRemainder.input(), player);
        }
        return null;
    }

    /**
     * Build a StackedItemContents from the player's inventory for recipe craftability checks.
     */
    /** String form of a recipe entry for diagnostics. */
    private static String safeEntryDesc(RecipeDisplayEntry entry) {
        try {
            return entry.id() != null ? String.valueOf(entry.id()): "no-display-id";
        } catch (Exception e) {
            return "entry-desc-error";
        }
    }

    /** Per-slot inventory snapshot for diagnostics. */
    private static String inventorySnapshot(LocalPlayer player) {
        StringBuilder sb = new StringBuilder("{");
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(simpleName(itemRegistryName(stack))).append('x').append(stack.getCount());
            }
        }
        return sb.append("}").toString();
    }

    private static StackedItemContents buildStackedContents(LocalPlayer player) {
        StackedItemContents contents = new StackedItemContents();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                contents.accountSimpleStack(stack);
            }
        }
        return contents;
    }

    /**
     * Collect all RecipeDisplayEntry objects from the client recipe book.
     * The client recipe book stores entries organized into RecipeCollections.
     */
    private static List<RecipeDisplayEntry> collectAllDisplayEntries(LocalPlayer player) {
        List<RecipeDisplayEntry> entries = new ArrayList<>();
        ClientRecipeBook recipeBook = player.getRecipeBook();
        for (RecipeCollection collection : recipeBook.getCollections()) {
            entries.addAll(collection.getRecipes());
        }
        return entries;
    }

    /**
     * Get the context map for resolving slot displays (needed for result items).
     */
    private static ContextMap getDisplayContext(Minecraft client) {
        return SlotDisplayContext.fromLevel(client.level);
    }

    /**
     * Get the result item name from a RecipeDisplayEntry.
     */
    private static String getEntryResultName(RecipeDisplayEntry entry, ContextMap ctx) {
        SlotDisplay resultDisplay = entry.display().result();
        return slotDisplayToItemName(resultDisplay);
    }

    /**
     * Get ingredient item names from a RecipeDisplay (for shaped/shapeless crafting).
     */
    private static List<String> getDisplayIngredientNames(RecipeDisplay display) {
        return getDisplayIngredientNames(display, null);
    }

    /** Player-aware variant (tag slots project to held members). */
    private static List<String> getDisplayIngredientNames(RecipeDisplay display, LocalPlayer player) {
        List<String> names = new ArrayList<>();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            for (SlotDisplay slot : shaped.ingredients()) {
                String name = slotDisplayToItemName(slot, player);
                if (name != null) names.add(name);
            }
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            for (SlotDisplay slot : shapeless.ingredients()) {
                String name = slotDisplayToItemName(slot, player);
                if (name != null) names.add(name);
            }
        } else if (display instanceof FurnaceRecipeDisplay furnace) {
            String name = slotDisplayToItemName(furnace.ingredient(), player);
            if (name != null) names.add(name);
        }
        return names;
    }

    /**
     * Count ingredient occurrences, returning a map of item name → count.
     */
    private static Map<String, Integer> countIngredients(List<String> ingredientNames) {
        Map<String, Integer> counts = new HashMap<>();
        for (String name : ingredientNames) {
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Check if the player has enough of each ingredient in their inventory.
     */
    private static JsonObject checkIngredients(LocalPlayer player, Map<String, Integer> required) {
        Map<String, Integer> available = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String name = itemRegistryName(stack);
                available.merge(name, stack.getCount(), Integer::sum);
            }
        }
        JsonArray missing = new JsonArray();
        int missingTotal = 0;
        for (Map.Entry<String, Integer> req : required.entrySet()) {
            int have = available.getOrDefault(req.getKey(), 0);
            if (have < req.getValue()) {
                int deficit = req.getValue() - have;
                missingTotal += deficit;
                JsonObject m = new JsonObject();
                m.addProperty("name", req.getKey());
                m.addProperty("count", deficit);
                missing.add(m);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("canCraft", missingTotal == 0);
        result.addProperty("missingTotal", missingTotal);
        result.add("missing", missing);
        return result;
    }

    /**
     * Find recipe display entries matching an output item name.
     * Returns exact matches first, then partial matches.
     */
    private static List<RecipeDisplayEntry> findEntriesForItem(LocalPlayer player, String query, ContextMap ctx) {
        String normalized = query.trim().toLowerCase();
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        String partial = normalized.replace("minecraft:", "");

        List<RecipeDisplayEntry> exact = new ArrayList<>();
        List<RecipeDisplayEntry> partialMatches = new ArrayList<>();

        for (RecipeDisplayEntry entry : collectAllDisplayEntries(player)) {
            String resultName = getEntryResultName(entry, ctx);
            if (resultName == null) continue;
            String resultNorm = resultName.toLowerCase();
            if (resultNorm.equals(normalized)) {
                exact.add(entry);
            } else if (resultNorm.contains(partial) || resultNorm.contains(normalized)) {
                partialMatches.add(entry);
            }
        }
        return exact.isEmpty() ? partialMatches : exact;
    }

    /**
     * can-craft: Check if the bot can craft a specific item with current inventory.
     */
    private static JsonObject canCraft(Minecraft client, JsonObject args) {
        String itemName = requiredString(args, "itemName");
        LocalPlayer player = client.player;
        ContextMap ctx = getDisplayContext(client);
        StackedItemContents stacked = buildStackedContents(player);

        List<RecipeDisplayEntry> entries = findEntriesForItem(player, itemName, ctx);
        if (entries.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("canCraft", false);
            result.addProperty("message", "No recipe found for " + itemName);
            return result;
        }

        // Find the first craftable entry, or the one with fewest missing ingredients
        JsonObject bestCannotCraft = null;
        int bestMissingTotal = Integer.MAX_VALUE;
        String bestResultName = null;

        for (RecipeDisplayEntry entry : entries) {
            String resultName = getEntryResultName(entry, ctx);
            if (resultName == null) continue;

            boolean canCraft = entry.canCraft(stacked);
            if (canCraft) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("canCraft", true);
                result.addProperty("message", "Yes, can craft " + resultName + ". Have all required ingredients.");
                return result;
            }

            List<String> ingredientNames = getDisplayIngredientNames(entry.display());
            Map<String, Integer> ingredientCounts = countIngredients(ingredientNames);
            JsonObject check = checkIngredients(player, ingredientCounts);
            int missingTotal = check.get("missingTotal").getAsInt();
            if (missingTotal < bestMissingTotal) {
                bestMissingTotal = missingTotal;
                bestCannotCraft = check;
                bestResultName = resultName;
            }
        }

        if (bestCannotCraft != null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("canCraft", false);
            result.addProperty("missingTotal", bestMissingTotal);
            result.add("missing", bestCannotCraft.get("missing"));
            result.addProperty("message", "Cannot craft " + bestResultName + ". Missing: " + bestMissingTotal + " items.");
            return result;
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("canCraft", false);
        result.addProperty("message", "No recipe found for " + itemName);
        return result;
    }

    /**
     * craft-item: Craft an item using a crafting recipe.
     * Uses the client recipe book to find the recipe display ID, then
     * handlePlaceRecipe to fill the crafting grid, and picks up the result.
     */
    /** Standalone delegate — routes through the queue worker so the long
     * recipe-place/transfer waits never run on the render/client thread.
     * Mirrors mineBlocksStandalone -> queueMineBlocks. */
    private static JsonObject craftItemStandalone(Minecraft client, JsonObject args) {
        return queueCraftItem(client, args);
    }

    /**
     * queueCraftItem: Craft `amount` of an output item using the player's
     * 2x2 inventory grid or an open crafting table. Async-safe: runs on the
     * queue worker thread; every Minecraft state read/mutate is marshalled
     * through callOnClient so ticks, rendering, Baritone and Meteor keep
     * running. The short Thread.sleep waits between hops happen on the
     * worker thread, never on the client thread.
     */
    private static JsonObject queueCraftItem(Minecraft client, JsonObject args) {
        String outputItem = requiredString(args, "outputItem");
        int amount = optionalInt(args, "amount", 1);
        // Optional crafting-table coords for 3x3 recipes. When the recipe needs a
        // table and none is open, queueCraftItem auto-opens the table here so the
        // whole craft stays one queue task (no separate open-container step).
        int tableX = optionalInt(args, "tableX", Integer.MIN_VALUE);
        int tableY = optionalInt(args, "tableY", Integer.MIN_VALUE);
        int tableZ = optionalInt(args, "tableZ", Integer.MIN_VALUE);
        boolean hasTableCoords = tableX != Integer.MIN_VALUE && tableY != Integer.MIN_VALUE && tableZ != Integer.MIN_VALUE;

        // ── Recipe resolution (pure reads, one callOnClient hop) ──
        // Resolution result: either a ready-to-craft pick or an early-out error JSON.
        record CraftResolve(RecipeDisplayEntry entry, String resultName, RecipeDisplayId displayId,
                            boolean needsTable, JsonObject errorResult) {
            boolean isError() { return errorResult != null; }
        }
        CraftResolve resolved = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            ContextMap ctx = getDisplayContext(client);
            StackedItemContents stacked = buildStackedContents(player);

            List<RecipeDisplayEntry> entries = findEntriesForItem(player, outputItem, ctx);
            if (entries.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Recipe not found for " + outputItem);
                return new CraftResolve(null, null, null, false, err);
            }

            // Find a craftable entry, tracking the closest-to-craftable fallback
            RecipeDisplayEntry craftableEntry = null;
            JsonObject bestMissingInfo = null;
            int bestMissingTotal = Integer.MAX_VALUE;

            for (RecipeDisplayEntry entry : entries) {
                if (entry.canCraft(stacked)) {
                    craftableEntry = entry;
                    break;
                }
                String resultName = getEntryResultName(entry, ctx);
                List<String> ingredientNames = getDisplayIngredientNames(entry.display());
                Map<String, Integer> ingredientCounts = countIngredients(ingredientNames);
                JsonObject check = checkIngredients(player, ingredientCounts);
                int missingTotal = check.get("missingTotal").getAsInt();
                if (missingTotal < bestMissingTotal) {
                    bestMissingTotal = missingTotal;
                    bestMissingInfo = check;
                }
            }

            if (craftableEntry == null) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Missing ingredients");
                err.addProperty("missingTotal", bestMissingTotal);
                if (bestMissingInfo != null) {
                    err.add("missing", bestMissingInfo.get("missing"));
                }
                return new CraftResolve(null, null, null, false, err);
            }

            String resultName = getEntryResultName(craftableEntry, ctx);
            RecipeDisplayId displayId = craftableEntry.id();

            // Determine if we need a crafting table (3x3) or if 2x2 inventory grid suffices.
            boolean needsTable = false;
            if (craftableEntry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
                if (shaped.width() > 2 || shaped.height() > 2) {
                    needsTable = true;
                }
            }
            if (craftableEntry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
                if (shapeless.ingredients().size() > 4) {
                    needsTable = true;
                }
            }
            return new CraftResolve(craftableEntry, resultName, displayId, needsTable, null);
        });

        if (resolved.isError()) {
            return resolved.errorResult();
        }

        // ── If the recipe needs a crafting table, ensure one is open ──
        // Auto-open the table at the provided coords if none is open yet, so a
        // 3x3 craft (e.g. furnace = 8 cobblestone) works inside a queue without
        // a separate open-container task. We close it again after crafting.
        boolean openedTable = false;
        if (resolved.needsTable()) {
            boolean tableOpen = callOnClient(client, () -> client.player.containerMenu.containerId != 0);
            if (!tableOpen) {
                if (!hasTableCoords) {
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", false);
                    result.addProperty("error", "This recipe requires a crafting table. Provide tableX/tableY/tableZ or open one first.");
                    result.addProperty("craftedCount", 0);
                    return result;
                }
                final BlockPos tablePos = new BlockPos(tableX, tableY, tableZ);
                // Verify it's a crafting table + in reach, then open it.
                String openErr = callOnClient(client, () -> {
                    LocalPlayer player = client.player;
                    String bn = blockRegistryName(client.level.getBlockState(tablePos));
                    if (!bn.equals("minecraft:crafting_table")) {
                        return "No crafting table at (" + tableX + ", " + tableY + ", " + tableZ + "): " + bn;
                    }
                    if (player.position().distanceTo(Vec3.atCenterOf(tablePos)) > CONTAINER_REACH) {
                        return "out_of_reach";
                    }
                    Direction face = computeFaceTowards(player, tablePos);
                    lookAtFacePoint(client, tablePos, face);
                    Vec3 hitVec = facePointHitVec(tablePos, face);
                    BlockHitResult hitResult = new BlockHitResult(hitVec, face, tablePos, false);
                    client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                    player.swing(InteractionHand.MAIN_HAND);
                    return (String) null;
                });
                if (openErr != null) {
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", false);
                    result.addProperty("error", openErr);
                    result.addProperty("craftedCount", 0);
                    return result;
                }
                // Wait for the table container to open (worker-thread sleep + callOnClient poll)
                int waitMs = 0;
                boolean opened = false;
                try {
                    while (waitMs < 2000) {
                        Thread.sleep(50);
                        waitMs += 50;
                        boolean isOpen = callOnClient(client, () -> client.player.containerMenu.containerId != 0);
                        if (isOpen) { opened = true; break; }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (!opened) {
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", false);
                    result.addProperty("error", "timeout_opening_crafting_table");
                    result.addProperty("craftedCount", 0);
                    return result;
                }
                openedTable = true;
            }
        }

        // ── Crafting loop (worker thread; per-iteration Minecraft ops via callOnClient) ──
        int craftedCount = 0;
        // T5.3/H4: zero-count aborts are no longer blanket-reported as
        // "ran out of ingredients" — track the true reason so cursor races
        // and server-sync timeouts are reported as what they are.
        int strandedOutputs = 0;
        String abortReason = "ran_out_of_ingredients";
        try {
            for (int attempt = 0; attempt < amount; attempt++) {
                // Re-check craftability + container each iteration on the client thread.
                // Returns: 0 = stop (ran out of ingredients), -1 = needs table but none open,
                // >0 = containerId ready for crafting.
                final RecipeDisplayEntry entry = resolved.entry();
                final boolean needsTable = resolved.needsTable();
                final int attemptNum = attempt;
                // The re-check returns Object — a String is a
                // canCraft=false diagnostic (logged), an Integer is -1 (needs
                // table, none open) or the containerId (0 = the player's own
                // inventory menu, VALID for 2x2 no-table recipes — previously
                // conflated with "ran out of ingredients" and aborted every
                // 2x2 craft at attempt 0).
                Object checkRes = callOnClient(client, () -> {
                    LocalPlayer player = client.player;
                    StackedItemContents currentStacked = buildStackedContents(player);
                    if (!entry.canCraft(currentStacked)) {
                        return " CanCraft=false at attempt " + attemptNum
                                + " (resolution validated the same entry earlier) entry="
                                + safeEntryDesc(entry) + " inventory=" + inventorySnapshot(player);
                    }
                    int cid = player.containerMenu.containerId;
                    if (needsTable && cid == 0) {
                        return Integer.valueOf(-1);
                    }
                    return Integer.valueOf(cid);
                });
                int containerId;
                if (checkRes instanceof String diag) {
                    com.hyfuse.bridge.HyFuseClient.LOGGER.warn(diag);
                    if (attempt == 0) {
                        // Defensive path: resolution validated
                        // craftability on this same entry with the same inventory
                        // moments earlier — proceed with the attempt. The re-check
                        // stays authoritative for attempts > 0 (real depletion).
                        containerId = callOnClient(client, () -> {
                            int cid = client.player.containerMenu.containerId;
                            if (needsTable && cid == 0) return Integer.valueOf(-1);
                            return Integer.valueOf(cid);
                        });
                    } else {
                        abortReason = "ingredient_depleted_at_attempt_" + attempt;
                        break; // genuine depletion at attempt > 0
                    }
                } else {
                    containerId = (Integer) checkRes;
                }
                if (containerId == -1) {
                    // Should not happen: we opened a table above if coords were given.
                    // (-1 is now the ONLY abort signal here; a plain
                    // 0 is the valid inventory-menu containerId for 2x2 recipes.)
                    if (openedTable) {
                        callOnClient(client, () -> { closeContainer(client); return null; });
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", false);
                    result.addProperty("error", "This recipe requires a crafting table. Provide tableX/tableY/tableZ or open one first.");
                    result.addProperty("craftedCount", craftedCount);
                    return result;
                }
                final int cid = containerId;
                final RecipeDisplayId displayId = resolved.displayId();

                // Shift=false places EXACTLY ONE batch per iteration.
                // (made the result pickup + cursor placement swap-safe — see the
                // The old shift=true filled the grid with the max batch, the
                // loop then picked a single result and lost the grid remainder
                // on container close (observed live: 64 planks -> 4 sticks,
                // 62 vanished; 8 acacia logs -> nothing). amount = batches.
                callOnClient(client, () -> {
                    client.gameMode.handlePlaceRecipe(cid, displayId, false);
                    return null;
                });
                // T5.3/H4: a fixed 100ms sleep raced the server's result-slot
                // sync on live servers — the result PICKUP then clicked an
                // empty result slot, the crafted output stranded in the grid,
                // and the tool reported "ran out of ingredients" while the
                // craft actually executed server-side (live T5.2: chest/furnace
                // crafted, hoe/torch vanished). Poll (bounded 2s) until the
                // result slot is actually non-empty before clicking it.
                boolean resultReady = false;
                long readyDeadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < readyDeadline) {
                    Boolean filled = callOnClient(client, () -> Boolean
                            .valueOf(!client.player.containerMenu.getSlot(0).getItem().isEmpty()));
                    if (filled != null && filled.booleanValue()) { resultReady = true; break; }
                    Thread.sleep(50);
                }
                if (!resultReady) {
                    com.hyfuse.bridge.HyFuseClient.LOGGER.warn(
                            " Result slot never filled at attempt " + attempt
                                    + " — server did not confirm the craft within 2s");
                    abortReason = "result_slot_timeout";
                    break;
                }

                // Pick up the result from the result slot (slot 0 for crafting).
                // Only click when the cursor is EMPTY. A live
                // stack on the cursor here puts that stack INTO the result slot
                // (observed live: phantom ingredient consumption + vanished
                // crafted output). Guard + deposit-or-abort instead.
                final int resultSlot = 0;
                Boolean cursorWasEmpty = callOnClient(client, () -> Boolean
                        .valueOf(client.player.containerMenu.getCarried().isEmpty()));
                if (cursorWasEmpty != null && !cursorWasEmpty.booleanValue()) {
                    // Error state: deposit the orphan cursor stack into a truly
                    // empty slot so we do not click the result slot with it.
                    Object deposited = callOnClient(client, () -> {
                        LocalPlayer player = client.player;
                        int totalSlots = player.containerMenu.slots.size();
                        ItemStack cursor = player.containerMenu.getCarried();
                        for (int i = 9; i < totalSlots; i++) {
                            Slot slot = player.containerMenu.getSlot(i);
                            if (slot.getItem().isEmpty() && slot.mayPlace(cursor)) {
                                client.gameMode.handleContainerInput(cid, i, 0, ContainerInput.PICKUP, player);
                                return Boolean.valueOf(player.containerMenu.getCarried().isEmpty());
                            }
                        }
                        return Boolean.FALSE;
                    });
                    boolean clean = deposited instanceof Boolean b && b.booleanValue();
                    if (!clean) {
                        com.hyfuse.bridge.HyFuseClient.LOGGER.warn(
                                " Cursor non-empty before result pickup and no empty slot to deposit; aborting attempt " + attempt);
                        abortReason = "cursor_stuck_before_result_pickup";
                        break;
                    }
                }
                callOnClient(client, () -> {
                    client.gameMode.handleContainerInput(cid, resultSlot, 0, ContainerInput.PICKUP, client.player);
                    return null;
                });
                Thread.sleep(50);

                // The result is now on the cursor — click a TRULY EMPTY inventory slot
                // to place it. The old condition accepted any
                // mayPlace-true non-empty slot, which SWAPS with the occupant
                // instead of placing; the swapped occupant then rides the cursor
                // into the next iteration's result PICKUP and is phantom-consumed
                // (observed live: crafting_table vanished, 0 planks from 4 logs).
                // Only isEmpty() slots are ever clicked here.
                int playerInvStart = needsTable ? 10 : 9;
                Boolean placedOk = callOnClient(client, () -> {
                    LocalPlayer player = client.player;
                    int totalSlots = player.containerMenu.slots.size();
                    boolean placed = false;
                    ItemStack cursorStack = player.containerMenu.getCarried();
                    if (!cursorStack.isEmpty()) {
                        for (int i = playerInvStart; i < totalSlots && !placed; i++) {
                            Slot slot = player.containerMenu.getSlot(i);
                            if (slot.getItem().isEmpty() && slot.mayPlace(cursorStack)) {
                                client.gameMode.handleContainerInput(cid, i, 0, ContainerInput.PICKUP, player);
                                placed = player.containerMenu.getCarried().isEmpty();
                            }
                        }
                    }
                    return Boolean.valueOf(placed);
                });
                if (placedOk == null || !placedOk.booleanValue()) {
                    // No empty slot accepted the output (full
                    // inventory) or the click did not clear the cursor. Do NOT
                    // fall back to shift-clicking the result slot (that path
                    // ping-pongs between result and grid slots). Deposit the
                    // cursor into any truly-empty slot we can find (including
                    // grid-side search from 9), else abort the whole loop.
                    Object deposited = callOnClient(client, () -> {
                        LocalPlayer player = client.player;
                        int totalSlots = player.containerMenu.slots
                                .size();
                        ItemStack cursor = player.containerMenu.getCarried();
                        if (cursor.isEmpty()) return Boolean.TRUE;
                        for (int i = 9; i < totalSlots; i++) {
                            Slot slot = player.containerMenu.getSlot(i);
                            if (slot.getItem().isEmpty() && slot.mayPlace(cursor)) {
                                client.gameMode.handleContainerInput(cid, i, 0, ContainerInput.PICKUP, player);
                                if (player.containerMenu.getCarried().isEmpty()) return Boolean.TRUE;
                            }
                        }
                        return Boolean.FALSE;
                    });
                    boolean clean = deposited instanceof Boolean b && b.booleanValue();
                    if (!clean) {
                        com.hyfuse.bridge.HyFuseClient.LOGGER.warn(
                                " Output not placed and cursor not depositable at attempt " + attempt + "; aborting");
                        abortReason = "cursor_stuck_after_result_pickup";
                        break;
                    }
                    // Cursor clean but output is stranded on the result slot or
                    // was lost; do not count this iteration as crafted.
                    com.hyfuse.bridge.HyFuseClient.LOGGER.warn(
                            " Output stranded at attempt " + attempt + "; not counted");
                    strandedOutputs++;
                    continue;
                }
                Thread.sleep(50);

                craftedCount++;
                // Wait for inventory to sync before next attempt
                Thread.sleep(100);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            // Close the crafting table we auto-opened, so the queue's next task
            // (e.g. place-block) starts with the player's inventory screen closed.
            if (openedTable) {
                try { callOnClient(client, () -> { closeContainer(client); return null; }); }
                catch (Exception ignored) {}
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", craftedCount > 0);
        result.addProperty("craftedCount", craftedCount);
        result.addProperty("item", resolved.resultName());
        if (craftedCount > 0) {
            result.addProperty("message", "Successfully crafted " + resolved.resultName() + " " + craftedCount + " time(s)");
        } else {
            if (strandedOutputs > 0 && "ran_out_of_ingredients".equals(abortReason)) {
                abortReason = "output_stranded_not_counted";
            }
            // T5.3/H4: honest abort reporting — cursor races and result-slot
            // sync timeouts were previously misreported as missing ingredients.
            result.addProperty("message", "Failed to craft " + outputItem + ": " + abortReason);
            result.addProperty("abortReason", abortReason);
            if (strandedOutputs > 0) {
                result.addProperty("strandedOutputs", strandedOutputs);
            }
        }
        return result;
    }

    /** Standalone delegate — routes through the queue worker so the long
     * furnace-open wait and the up-to-60s smelt-output poll loop never run
     * on the render/client thread. Mirrors smeltItemStandalone -> queueSmeltItem. */
    private static JsonObject smeltItemStandalone(Minecraft client, JsonObject args) {
        return queueSmeltItem(client, args);
    }

    /**
     * queueSmeltItem: Smelt items using a furnace-like block. Opens the
     * furnace, shift-clicks input + fuel into the ingredient/fuel slots, then
     * polls the result slot until output is ready (or timeout). Async-safe:
     * runs on the queue worker thread; every Minecraft state read/mutate is
     * marshalled through callOnClient so ticks, rendering, Baritone and
     * Meteor keep running. All Thread.sleep waits happen on the worker thread.
     *
     * Furnace menu slots: 0=ingredient, 1=fuel, 2=result, 3-38=player inv
     * (AbstractFurnaceMenu: INGREDIENT_SLOT=0, FUEL_SLOT=1, RESULT_SLOT=2).
     */
    /** T5.3/H3-1 helper: current count of the furnace result slot (0 when empty).
     *  MUST be called via callOnClient — reads live menu state. */
    private static int countOfResult(Minecraft client) {
        ItemStack s = client.player.containerMenu.getSlot(2).getItem();
        return s.isEmpty() ? 0 : s.getCount();
    }

    private static JsonObject queueSmeltItem(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        String inputItemName = requiredString(args, "inputItem");
        int inputCount = optionalInt(args, "inputCount", 1);
        String fuelItemName = requiredString(args, "fuelItem");
        int fuelCount = optionalInt(args, "fuelCount", 1);
        boolean takeOutput = optionalBool(args, "takeOutput", true);
        int timeoutMs = optionalInt(args, "timeoutMs", 60000);
        final BlockPos pos = new BlockPos(x, y, z);
        final String inputNorm = inputItemName.toLowerCase();
        final String fuelNorm = fuelItemName.toLowerCase();

        // ── Verify furnace block + reach (one callOnClient hop) ──
        // Returns an error JSON string or null if OK.
        String precheckError = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            String blockName = blockRegistryName(level.getBlockState(pos));
            if (!blockName.equals("minecraft:furnace") && !blockName.equals("minecraft:blast_furnace")
                    && !blockName.equals("minecraft:smoker")) {
                return "No furnace block found at (" + x + ", " + y + ", " + z + ")";
            }
            double dist = player.position().distanceTo(Vec3.atCenterOf(pos));
            if (dist > CONTAINER_REACH) {
                return "out_of_reach";
            }
            return (String) null;
        });
        if (precheckError != null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", precheckError);
            return result;
        }

        // ── Find input and fuel items in inventory (one callOnClient hop) ──
        // Returns: [inputName, fuelName, resolvedInputCount, resolvedFuelCount]
        // or an error JSON string if input/fuel not found.
        record InventoryPick(String inputName, String fuelName, int resolvedInputCount, int resolvedFuelCount,
                             String error) {}
        InventoryPick pick = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            Inventory inv = player.getInventory();
            ItemStack inputStack = null;
            ItemStack fuelStack = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                if (name.contains(inputNorm) && inputStack == null) {
                    inputStack = stack;
                }
                if (name.contains(fuelNorm) && fuelStack == null) {
                    fuelStack = stack;
                }
            }
            if (inputStack == null) {
                return new InventoryPick(null, null, 0, 0,
                        "Couldn't find any item matching '" + inputItemName + "' in inventory");
            }
            if (fuelStack == null) {
                return new InventoryPick(null, null, 0, 0,
                        "Couldn't find any fuel item matching '" + fuelItemName + "' in inventory");
            }
            int resolvedInputCount = Math.min(inputCount, inputStack.getCount());
            int resolvedFuelCount = Math.min(fuelCount, fuelStack.getCount());
            return new InventoryPick(itemRegistryName(inputStack), itemRegistryName(fuelStack),
                    resolvedInputCount, resolvedFuelCount, null);
        });
        if (pick.error() != null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", pick.error());
            return result;
        }

        try {
            // ── Open the furnace (one callOnClient hop) ──
            final int originalContainerId = callOnClient(client, () -> {
                LocalPlayer player = client.player;
                int cid = player.containerMenu.containerId;
                lookAtBlockCenter(client, pos);
                Vec3 hitVec = Vec3.atCenterOf(pos);
                Direction face = computeFaceTowards(player, pos);
                BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
                client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                player.swing(InteractionHand.MAIN_HAND);
                return cid;
            });

            // ── Wait for container to open (worker-thread sleep + callOnClient poll) ──
            int furnaceContainerId = -1;
            int waitMs = 0;
            while (waitMs < 2000) {
                Thread.sleep(50);
                waitMs += 50;
                final int expectedOriginal = originalContainerId;
                int currentId = callOnClient(client, () -> client.player.containerMenu.containerId);
                if (currentId != expectedOriginal) {
                    furnaceContainerId = currentId;
                    break;
                }
            }
            if (furnaceContainerId == -1) {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "timeout_opening_furnace");
                return result;
            }
            final int furnaceCid = furnaceContainerId;

            // ── 
            // The old block QUICK_MOVE'd the first matching stack — the WHOLE
            // stack, ignoring inputCount/fuelCount (observed 4x live on 1.1.5:
            // 58 oak_planks + 3 cherry_log in one call). Now each placement is
            // a cursor PICKUP split: pick up the whole matching stack, place
            // exactly the resolved count into the target furnace slot (left
            // click = whole stack when wanted; right click = ONE item per
            // click otherwise), return any cursor remainder to the source
            // slot. One callOnClient hop = atomic on the client thread.
            // Returns: [inputPlaced, fuelPlaced]
            boolean[] placed = callOnClient(client, () -> {
                LocalPlayer player = client.player;
                boolean inputPlaced = false;
                boolean fuelPlaced = false;
                for (int side = 0; side < 2; side++) {
                    String matchNorm = (side == 0) ? inputNorm : fuelNorm;
                    int furnaceSlot = (side == 0) ? 0 : 1;
                    int toPlace = (side == 0) ? pick.resolvedInputCount() : pick.resolvedFuelCount();
                    if (toPlace <= 0) continue;
                    // Find the first matching player-inv slot (menu slots 3+).
                    int source = -1;
                    for (int i = 3; i < player.containerMenu.slots.size(); i++) {
                        ItemStack stack = player.containerMenu.getSlot(i).getItem();
                        if (stack.isEmpty()) continue;
                        if (itemRegistryName(stack).contains(matchNorm)) { source = i; break; }
                    }
                    if (source < 0) continue;
                    // Pick up the whole matching stack onto the cursor.
                    client.gameMode.handleContainerInput(furnaceCid, source, 0, ContainerInput.PICKUP, player);
                    int cursorCount = player.containerMenu.getCarried().getCount();
                    if (toPlace >= cursorCount) {
                        // Whole stack wanted: one left-click places everything.
                        client.gameMode.handleContainerInput(furnaceCid, furnaceSlot, 0, ContainerInput.PICKUP, player);
                    } else {
                        // Right-click places ONE item per click — count-exact.
                        for (int n = 0; n < toPlace; n++) {
                            client.gameMode.handleContainerInput(furnaceCid, furnaceSlot, 1, ContainerInput.PICKUP, player);
                        }
                        // Return any cursor remainder to the source slot.
                        if (!player.containerMenu.getCarried().isEmpty()) {
                            client.gameMode.handleContainerInput(furnaceCid, source, 0, ContainerInput.PICKUP, player);
                        }
                    }
                    if (side == 0) inputPlaced = true; else fuelPlaced = true;
                }
                return new boolean[]{inputPlaced, fuelPlaced};
            });
            // Give the server a moment to process the shift-click transfers
            Thread.sleep(100);

            if (!placed[0]) {
                callOnClient(client, () -> { closeContainer(client); return null; });
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Failed to place input item in furnace");
                return result;
            }
            if (!placed[1]) {
                callOnClient(client, () -> { closeContainer(client); return null; });
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "Failed to place fuel in furnace");
                return result;
            }

            if (!takeOutput) {
                callOnClient(client, () -> { closeContainer(client); return null; });
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("message", "Started smelting " + pick.resolvedInputCount() + " " +
                        pick.inputName() + " with " + pick.resolvedFuelCount() + " " +
                        pick.fuelName());
                return result;
            }

            // ── Poll the result slot for output (worker-thread sleep + callOnClient read) ──
            // Slot 2 = result. Smelting takes ~10s/item; this loop runs entirely off the client thread.
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean outputReady = false;
            while (System.currentTimeMillis() < deadline) {
                boolean ready = callOnClient(client, () -> {
                    ItemStack outputStack = client.player.containerMenu.getSlot(2).getItem();
                    return !outputStack.isEmpty();
                });
                if (ready) { outputReady = true; break; }
                Thread.sleep(500);
            }

            if (!outputReady) {
                callOnClient(client, () -> { closeContainer(client); return null; });
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);
                result.addProperty("error", "No output after " + timeoutMs + "ms");
                return result;
            }

            // ── Take the output (shift-click result slot 2) + read output identity ──
            record OutputInfo(String outputName, int outputCount, String resultSlotAfter) {}
            OutputInfo out = callOnClient(client, () -> {
                ItemStack outputStack = client.player.containerMenu.getSlot(2).getItem();
                String outputName = itemRegistryName(outputStack);
                // T5.3/H3-1: read the count BEFORE the take-click. The
                // client-side menu-click prediction shrinks the same live
                // stack object in place, so the previous post-click read
                // returned 0 on live servers while the take actually
                // succeeded (live T5.2: "Smelted 0" with 1 iron_ingot taken).
                // Within this single client-thread hop no tick elapses between
                // the read and the click, so no server merge can interleave.
                int outputCount = outputStack.getCount();
                client.gameMode.handleContainerInput(furnaceCid, 2, 0, ContainerInput.QUICK_MOVE, client.player);
                String slotAfter = itemRegistryName(client.player.containerMenu.getSlot(2).getItem());
                return new OutputInfo(outputName, outputCount, slotAfter);
            });
            Thread.sleep(100);
            // Bounded verification that the take landed server-side: the
            // result slot empties (or refills with the NEXT smelt output).
            long takeDeadline = System.currentTimeMillis() + 2000;
            boolean takeConfirmed = false;
            {
                String before = out.resultSlotAfter();
                while (System.currentTimeMillis() < takeDeadline) {
                    String now = callOnClient(client, () -> itemRegistryName(
                            client.player.containerMenu.getSlot(2).getItem()));
                    int nowCount = callOnClient(client, () -> countOfResult(client));
                    if (now.isEmpty() || !now.equals(before) || nowCount != out.outputCount()) {
                        takeConfirmed = true;
                        break;
                    }
                    Thread.sleep(100);
                }
            }

            callOnClient(client, () -> { closeContainer(client); return null; });

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("message", "Smelted " + out.outputCount() + " " + out.outputName());
            result.addProperty("output", out.outputName());
            result.addProperty("outputCount", out.outputCount());
            result.addProperty("takeConfirmed", takeConfirmed);
            return result;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Best-effort close on interrupt
            try { callOnClient(client, () -> { closeContainer(client); return null; }); } catch (Exception ignored) {}
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "interrupted");
            return result;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier H: Survival (combat, sleep, flight, item pickup)
    // ──────────────────────────────────────────────────────────────────────

    private static final double MELEE_REACH = 3.5;
    private static final long ATTACK_COOLDOWN_MS = 650L;
    private static final java.util.List<String> WEAPON_PRIORITY = java.util.List.of(
            "netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "golden_sword", "wooden_sword",
            "netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "golden_axe", "wooden_axe");

    /**
     * attack-entity: Equip the best weapon and attack a target entity until it dies,
     * disappears, or the timeout expires. Mirrors the legacy survival-tools shape.
     * Strategy is accepted but the Fabric client only performs melee attacks here
     * (ranged bow aiming requires projectile-physics simulation not feasible in a
     * single client tick; ranged falls back to melee with a noted weapon).
     */
    private static JsonObject attackEntity(Minecraft client, JsonObject args) {
        String entityName = requiredString(args, "entityName");
        String strategy = optionalString(args, "strategy", "melee");
        long timeoutMs = optionalInt(args, "timeoutMs", 15000);
        if (timeoutMs < 1000) timeoutMs = 1000;
        if (timeoutMs > 60000) timeoutMs = 60000;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        // Resolve the target entity by name or 'nearest_hostile'.
        Entity target = resolveAttackTarget(level, player, entityName);
        if (target == null) {
            return errorJson("No entity matching '" + entityName + "' found nearby");
        }

        String targetDesc = describeEntityForCombat(target);

        // Equip best available weapon from the hotbar/inventory.
        String weapon = equipBestWeapon(client);

        // All entity reads/interactions below are marshalled onto the
        // client thread (the old loop ran them raw on the MCP worker thread).
        double startDistance = callOnClient(client, () ->
                Math.sqrt(target.distanceToSqr(client.player)));
        Vec3 startPos = callOnClient(client, () -> client.player.position());
        BlockPos startPosI = BlockPos.containing(startPos.x, startPos.y, startPos.z);

        long start = System.currentTimeMillis();
        int attacks = 0;
        String outcome = "timeout";
        boolean eliminated = false;
        boolean approached = false;
        long approachMs = 0L;

        try {
            // ── Defect 2: KillAura-led combat (hunt-hostile pattern,
            // live-verified clearing 6 skeletons in <15s). Enable before the
            // approach so its tick handler attacks on every tick while
            // Baritone paths; the marshalled direct-attack loop below stays
            // as fallback (Meteor may be absent).
            toggleMeteorModuleByName(client, "kill-aura", true);

            // ── APPROACH phase: Baritone paths toward the target. ──
            // The old loop had no approach: beyond MELEE_REACH it slept to the
            // timeout (live-pinned: bat 15 blocks away, 0 attacks in 20s).
            BlockPos targetPos = callOnClient(client, target::blockPosition);
            callOnClient(client, () -> {
                client.player.connection.sendChat(
                        "#goto " + targetPos.getX() + " " + targetPos.getY() + " " + targetPos.getZ());
                return null;
            });

            // Approach poll: stop when dead, within reach, or approach budget
            // (half the overall timeout) is spent.
            long approachDeadline = System.currentTimeMillis() + Math.max(2000L, timeoutMs / 2);
            while (System.currentTimeMillis() < approachDeadline) {
                Thread.sleep(200L);
                int state = callOnClient(client, () -> {
                    if (target.isRemoved() || !target.isAlive()
                            || client.level.getEntity(target.getId()) == null) return 2;
                    return Math.sqrt(target.distanceToSqr(client.player)) <= MELEE_REACH ? 1 : 0;
                });
                if (state == 2) {
                    outcome = "target eliminated";
                    eliminated = true;
                    break;
                }
                if (state == 1) break;
            }
            approachMs = System.currentTimeMillis() - start;
            double distanceNow = callOnClient(client, () ->
                    Math.sqrt(target.distanceToSqr(client.player)));
            approached = distanceNow <= MELEE_REACH;
            if (approached) {
                callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
            }

            // ── ATTACK phase: marshalled look + attack + swing. ──
            if (!eliminated) {
                long killDeadline = System.currentTimeMillis() + (timeoutMs - approachMs);
                while (System.currentTimeMillis() < killDeadline) {
                    int attacked = callOnClient(client, () -> {
                        if (target.isRemoved() || !target.isAlive()
                                || client.level.getEntity(target.getId()) == null) return 2;
                        double d = Math.sqrt(target.distanceToSqr(client.player));
                        if (d <= MELEE_REACH) {
                            // Look at the target's torso so the server accepts the swing.
                            lookAtEntity(client, target);
                            client.gameMode.attack(client.player, target);
                            client.player.swing(InteractionHand.MAIN_HAND);
                            return 1;
                        }
                        return 0;
                    });
                    if (attacked == 2) {
                        outcome = "target eliminated";
                        eliminated = true;
                        break;
                    }
                    if (attacked == 1) attacks++;
                    Thread.sleep(ATTACK_COOLDOWN_MS);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
        } finally {
            // Defect 2: never leave KillAura active between calls.
            toggleMeteorModuleByName(client, "kill-aura", false);
            callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
        }

        // Re-evaluate target state after the loop (client-thread reads).
        boolean stillAlive = callOnClient(client, () ->
                !target.isRemoved() && target.isAlive()
                        && client.level.getEntity(target.getId()) != null);
        double finalDistance = callOnClient(client, () ->
                Math.sqrt(target.distanceToSqr(client.player)));
        Vec3 endPos = callOnClient(client, () -> client.player.position());
        double moved = Math.sqrt(
                (endPos.x - startPos.x) * (endPos.x - startPos.x)
                        + (endPos.z - startPos.z) * (endPos.z - startPos.z));

        JsonObject result = new JsonObject();
        result.addProperty("target", targetDesc);
        result.addProperty("strategy", strategy);
        result.addProperty("weapon", weapon != null ? weapon: "fist");
        result.addProperty("attacks", attacks);
        result.addProperty("outcome", outcome);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        // Diagnostics: did the approach phase engage at all?
        result.addProperty("movedBlocks", Math.round(moved * 10.0) / 10.0);
        result.addProperty("finalDistance", Math.round(finalDistance * 10.0) / 10.0);
        result.addProperty("approached", approached);
        result.addProperty("approachMs", approachMs);
        if (stillAlive && target instanceof LivingEntity living) {
            result.addProperty("targetHealthRemaining",
                    callOnClient(client, living::getHealth));
        }
        if (player instanceof LivingEntity self) {
            result.addProperty("botHealth",
                    callOnClient(client, self::getHealth));
        }

        String summary = eliminated
                ? "Eliminated " + targetDesc + " with " + (weapon != null ? weapon: "fists")
                + " (" + attacks + " attack(s), " + strategy + ")"
: "Combat timed out after " + Math.round((System.currentTimeMillis() - start) / 1000.0) + "s: "
                + targetDesc + " still alive. Landed " + attacks + " attack(s). Bot health: "
                + (player instanceof LivingEntity self ? self.getHealth(): "?") + "/20.";
        result.addProperty("message", summary);
        return result;
    }

    /**
     * sleep-in-bed: Locate a bed (by coords or nearby search) and sleep in it.
     * Uses Player.startSleepInBed(BlockPos) which returns Either<BedSleepingProblem, Unit>.
     */
    private static JsonObject sleepInBed(Minecraft client, JsonObject args) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        if (player.isSleeping()) {
            JsonObject result = new JsonObject();
            result.addProperty("sleeping", true);
            result.addProperty("message", "Already sleeping");
            return result;
        }

        // Time-of-day check (sleep only possible at night or during a thunderstorm).
        long dayTime = level.getOverworldClockTime();
        long timeOfDay = dayTime % 24000;
        if (timeOfDay < 0) timeOfDay += 24000;
        boolean isNight = timeOfDay >= 13000;
        boolean thundering = level.isThundering();
        if (!isNight && !thundering) {
            return errorJson("Cannot sleep now — it is daytime (tick " + timeOfDay + "). "
                    + "Sleep is only possible at night or during a thunderstorm.");
        }

        BlockPos bedPos;
        if (args.has("x") && args.has("y") && args.has("z")) {
            int x = requiredInt(args, "x");
            int y = requiredInt(args, "y");
            int z = requiredInt(args, "z");
            bedPos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(bedPos);
            if (!(state.getBlock() instanceof BedBlock)) {
                return errorJson("No bed found at (" + x + ", " + y + ", " + z + ")"
                        + " — found " + blockRegistryName(state));
            }
        } else {
            // Search nearby blocks for a bed (HEAD part preferred; FOOT also accepted,
            // but sleeping requires the HEAD block position).
            bedPos = findNearestBedHead(level, player.blockPosition(), 16);
            if (bedPos == null) {
                return errorJson("No bed found within 16 blocks. Provide coordinates or craft/place a bed first.");
            }
        }

        // If too far, the server will reject — warn the caller rather than pathfind
        // (the client cannot drive Baritone from this synchronous handler reliably).
        double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(bedPos)));
        if (distance > 4.0) {
            JsonObject result = new JsonObject();
            result.addProperty("sleeping", false);
            result.add("bed", posJson(bedPos));
            result.addProperty("distance", distance);
            result.addProperty("error", "Bed is " + String.format("%.1f", distance)
                    + " blocks away — move closer (use navigate-v2) then retry.");
            return result;
        }

        // Look at the bed, then attempt to sleep via the vanilla client flow.
        lookAtBlockCenter(client, bedPos);
        // Use the bed block via useItemOn so the server processes the interaction,
        // then call startSleepInBed to mirror the client-side sleep request.
        Vec3 hitVec = Vec3.atCenterOf(bedPos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, bedPos, false);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        player.swing(InteractionHand.MAIN_HAND);
        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Either<Player.BedSleepingProblem, Unit> sleepResult = player.startSleepInBed(bedPos);
        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        JsonObject result = new JsonObject();
        if (sleepResult.right().isPresent() || player.isSleeping()) {
            result.addProperty("sleeping", true);
            result.add("bed", posJson(bedPos));
            result.addProperty("message", "Sleeping in bed at (" + bedPos.getX() + ", "
                    + bedPos.getY() + ", " + bedPos.getZ() + "). The bot will wake automatically at dawn.");
        } else {
            String problem = sleepResult.left()
                    .map(Player.BedSleepingProblem::message)
                    .map(java.util.Optional::ofNullable)
                    .orElse(java.util.Optional.empty())
                    .map(net.minecraft.network.chat.Component::getString)
.orElse("UNKNOWN");
            result.addProperty("sleeping", false);
            result.add("bed", posJson(bedPos));
            result.addProperty("error", "Could not sleep in bed at (" + bedPos.getX() + ", "
                    + bedPos.getY() + ", " + bedPos.getZ() + "): " + problem);
        }
        return result;
    }

    /**
     * fly-to: Creative-flight toward a destination coordinate. Toggles flying on,
     * sets delta movement toward the target in steps until close enough or timeout.
     * Requires mayfly/flying abilities (creative or op).
     */
    private static JsonObject flyTo(Minecraft client, JsonObject args) {
        double x = requiredDouble(args, "x");
        double y = requiredDouble(args, "y");
        double z = requiredDouble(args, "z");
        long timeoutMs = optionalInt(args, "timeoutMs", 20000);
        if (timeoutMs < 1000) timeoutMs = 1000;
        if (timeoutMs > 60000) timeoutMs = 60000;

        LocalPlayer player = client.player;
        Abilities abilities = player.getAbilities();

        if (!abilities.mayfly) {
            return errorJson("Creative mode / flight is not available. Cannot fly.");
        }

        boolean wasFlying = abilities.flying;
        // Enable flying if not already.
        if (!abilities.flying) {
            abilities.flying = true;
            player.onUpdateAbilities();
        }

        Vec3 dest = new Vec3(x, y, z);
        long start = System.currentTimeMillis();
        boolean reached = false;
        double speed = abilities.getFlyingSpeed() > 0 ? abilities.getFlyingSpeed() * 20.0 : 11.0;

        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                Vec3 cur = player.position();
                double dx = dest.x - cur.x;
                double dy = dest.y - cur.y;
                double dz = dest.z - cur.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < 0.6) {
                    reached = true;
                    break;
                }
                // Normalize direction and scale by per-tick speed; apply as delta movement.
                double vx = (dx / dist) * speed;
                double vy = (dy / dist) * speed;
                double vz = (dz / dist) * speed;
                player.setDeltaMovement(vx, vy, vz);
                // Look toward the destination for natural orientation.
                double horiz = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
                float pitch = (float) (-Math.atan2(dy, horiz) * 180.0 / Math.PI);
                player.setYRot(yaw);
                player.setXRot(pitch);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            // Stop forward motion; restore prior flying state.
            player.setDeltaMovement(0, 0, 0);
            if (!wasFlying) {
                abilities.flying = false;
                player.onUpdateAbilities();
            }
        }

        JsonObject result = new JsonObject();
        if (reached) {
            result.addProperty("ok", true);
            result.addProperty("message", "Successfully flew to position (" + x + ", " + y + ", " + z + ").");
        } else {
            Vec3 here = player.position();
            result.addProperty("ok", false);
            result.addProperty("error", "Flight timed out after " + (timeoutMs / 1000) + " seconds. "
                    + "The destination may be unreachable. Current position: ("
                    + (int) here.x + ", " + (int) here.y + ", " + (int) here.z + ")");
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier I: Composite / Movement (Baritone delegation + Java primitives)
    // ──────────────────────────────────────────────────────────────────────

    private static final long MOVE_POLL_INTERVAL_MS = 500L;
    private static final int FIND_SAFE_STEP = 4;
    private static final int FIND_SAFE_MAX_RADIUS = 48;
    private static final java.util.Set<String> HAZARD_BLOCK_NAMES =
            java.util.Set.of("lava", "fire", "soul_fire", "magma_block", "cactus", "sweet_berry_bush", "powder_snow");
    private static final java.util.Set<String> FLUID_BLOCK_NAMES =
            java.util.Set.of("water", "lava");

    /**
     * move-in-direction: Hold a movement key (W/A/S/D) for `duration` ms, then
     * release. The real client tick reads client.options.keyXxx, so we drive those
     * KeyMappings directly via setDown(true/false). Sprint is enabled for 'forward'
     * to match vanilla sprinting (
     * setControlState + timed release).
     */
    private static JsonObject moveInDirection(Minecraft client, JsonObject args) {
        String direction = requiredString(args, "direction").toLowerCase();
        int duration = optionalInt(args, "duration", 1000);
        if (duration < 0) duration = 0;

        Options opts = client.options;
        KeyMapping key;
        boolean sprint;
        switch (direction) {
            case "forward": key = opts.keyUp; sprint = true; break;
            case "back": key = opts.keyDown; sprint = false; break;
            case "left": key = opts.keyLeft; sprint = false; break;
            case "right": key = opts.keyRight; sprint = false; break;
            default:
                throw new ToolException("INVALID_ARGUMENT",
                        "direction must be one of forward/back/left/right, got '" + direction + "'");
        }
        boolean wasSprinting = client.player.isSprinting();
        boolean wasKeyDown = key.isDown();
        try {
            key.setDown(true);
            if (sprint) {
                client.player.setSprinting(true);
                opts.keySprint.setDown(true);
            }
            try { Thread.sleep(duration); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        } finally {
            key.setDown(wasKeyDown);
            if (sprint) {
                opts.keySprint.setDown(false);
                client.player.setSprinting(wasSprinting);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("moved", direction);
        result.addProperty("duration", duration);
        result.addProperty("message", "Moved " + direction + " for " + duration + "ms");
        return result;
    }

    /**
     * follow-entity: Resolve a target entity by name, then follow it by
     * repeatedly dispatching Baritone #goto to the entity's LIVE block
     * coordinates — re-issued whenever the target's block position changes —
     * and #stop when within the follow distance. The old delegation
     * `#follow <name>` was broken for non-players: Baritone's #follow matches
     * PLAYER names / selector keywords, so "#follow cow" silently no-oped
     * (pinned live 3x in Zero movement, clean timeout return).
     * #goto is the navigate-v2-proven path. Poll loop mirrors attackEntity
     * (MOVE_POLL_INTERVAL_MS, marshalled reads via callOnClient, #stop in
     * finally) and runs on QUEUE_EXECUTOR via the Composite predicate.
     */
    private static JsonObject followEntity(Minecraft client, JsonObject args) {
        String entityName = requiredString(args, "entityName");
        double distance = optionalDouble(args, "distance", 3.0);
        long timeoutMs = optionalInt(args, "timeoutMs", 10000);
        if (timeoutMs < 500) timeoutMs = 500;
        if (timeoutMs > 60000) timeoutMs = 60000;
        if (distance <= 0) distance = 3.0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        Entity target = resolveAttackTarget(level, player, entityName);
        if (target == null) {
            return errorJson("No entity matching '" + entityName + "' found nearby");
        }
        String label = describeEntityForCombat(target);

        BlockPos startPosI = callOnClient(client, () ->
                BlockPos.containing(client.player.getX(), client.player.getY(), client.player.getZ()));

        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        String reason = "timeout";
        boolean gotoActive = false;
        int reissues = 0;
        BlockPos lastGotoPos = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(MOVE_POLL_INTERVAL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); reason = "interrupted"; break; }
                if (level.getEntity(target.getId()) == null || target.isRemoved() || !target.isAlive()) {
                    reason = "target disappeared";
                    break;
                }
                double dist = callOnClient(client, () ->
                        Math.sqrt(target.distanceToSqr(client.player)));
                if (dist <= distance) {
                    if (gotoActive) {
                        client.player.connection.sendChat("#stop");
                        gotoActive = false;
                        lastGotoPos = null;
                    }
                    continue; // within follow range — hold position
                }
                BlockPos targetPosI = callOnClient(client, () ->
                        BlockPos.containing(target.getX(), target.getY(), target.getZ()));
                if (!gotoActive || lastGotoPos == null || !targetPosI.equals(lastGotoPos)) {
                    client.player.connection.sendChat("#goto "
                            + targetPosI.getX() + " " + targetPosI.getY() + " " + targetPosI.getZ());
                    gotoActive = true;
                    lastGotoPos = targetPosI;
                    reissues++;
                }
                // else: goto already active toward the target's current block —
                // Baritone walks there; next re-issue happens when it moves.
            }
        } finally {
            client.player.connection.sendChat("#stop");
        }

        BlockPos endPosI = callOnClient(client, () ->
                BlockPos.containing(client.player.getX(), client.player.getY(), client.player.getZ()));
        double moved = Math.sqrt(startPosI.distManhattan(endPosI));
        double finalDist = target.isRemoved() ? -1 :
                callOnClient(client, () -> Math.sqrt(target.distanceToSqr(client.player)));
        JsonObject result = new JsonObject();
        result.addProperty("followed", label);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("stoppedBecause", reason);
        result.addProperty("movedBlocks", Math.round(moved * 10.0) / 10.0);
        result.addProperty("gotoReissues", reissues);
        if (finalDist >= 0) result.addProperty("finalDistance", Math.round(finalDist * 10) / 10.0);
        result.addProperty("message", "Followed " + label + " for "
                + ((System.currentTimeMillis() - start) / 1000) + "s (stopped: " + reason + ")"
                + ", moved " + (Math.round(moved * 10.0) / 10.0) + " blocks"
                + (finalDist >= 0 ? ", now " + (Math.round(finalDist * 10) / 10.0) + " blocks away": ""));
        return result;
    }

    /**
     * flee-from: Find the threat (nearest_hostile or a named mob), compute an
     * escape point `minDistance` blocks directly away from it, dispatch Baritone
     * #goto the escape point with sprint enabled, and poll until the deadline.
     * 
     * 
     */
    private static JsonObject fleeFrom(Minecraft client, JsonObject args) {
        String fromType = optionalString(args, "fromType", "nearest_hostile");
        double minDistance = optionalDouble(args, "minDistance", 20.0);
        long timeoutMs = optionalInt(args, "timeoutMs", 10000);
        if (timeoutMs < 500) timeoutMs = 500;
        if (timeoutMs > 30000) timeoutMs = 30000;
        if (minDistance <= 0) minDistance = 20.0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        Entity threat;
        if (fromType.equalsIgnoreCase("nearest_hostile")) {
            threat = resolveAttackTarget(level, player, "nearest_hostile");
        } else {
            threat = resolveAttackTarget(level, player, fromType);
        }
        if (threat == null) {
            JsonObject result = new JsonObject();
            result.addProperty("fled", false);
            result.addProperty("reason", "no threat found");
            result.addProperty("message", fromType.equalsIgnoreCase("nearest_hostile")
                    ? "No hostile mobs nearby — nothing to flee from"
: "No '" + fromType + "' found nearby");
            return result;
        }
        String threatLabel = describeEntityForCombat(threat);
        double startDist = Math.sqrt(threat.distanceToSqr(player));

        // Compute escape point directly away from the threat (horizontal only).
        double dx = player.getX() - threat.getX();
        double dz = player.getZ() - threat.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.01) { dx = 1.0; dz = 0.0; horiz = 1.0; }
        dx /= horiz; dz /= horiz;
        int escX = (int) Math.floor(player.getX() + dx * (minDistance + 4));
        int escZ = (int) Math.floor(player.getZ() + dz * (minDistance + 4));
        int escY = player.getBlockY();

        // Sprint for the flee.
        boolean wasSprinting = player.isSprinting();
        client.player.connection.sendChat("#goto " + escX + " " + escY + " " + escZ);
        player.setSprinting(true);
        client.options.keySprint.setDown(true);
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(MOVE_POLL_INTERVAL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                if (level.getEntity(threat.getId()) == null || threat.isRemoved()) break;
                if (Math.sqrt(threat.distanceToSqr(player)) >= minDistance) break;
            }
        } finally {
            client.player.connection.sendChat("#stop");
            client.options.keySprint.setDown(false);
            player.setSprinting(wasSprinting);
        }
        double finalDist = threat.isRemoved() ? minDistance :
                Math.sqrt(threat.distanceToSqr(player));
        boolean escaped = finalDist >= minDistance || level.getEntity(threat.getId()) == null;

        JsonObject result = new JsonObject();
        result.addProperty("fled", true);
        result.addProperty("escaped", escaped);
        result.addProperty("threat", threatLabel);
        result.addProperty("startDistance", Math.round(startDist * 10) / 10.0);
        result.addProperty("finalDistance", Math.round(finalDist * 10) / 10.0);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("message", escaped
                ? "Escaped from " + threatLabel + ": " + (Math.round(startDist * 10) / 10.0) + " → " + (Math.round(finalDist * 10) / 10.0) + " blocks"
: "Fled from " + threatLabel + " but only reached " + (Math.round(finalDist * 10) / 10.0) + "/" + minDistance + " blocks — consider fleeing again or fighting");
        return result;
    }

    /**
     * path-safely: Pre-scan the straight-line corridor for hazards (lava, fire,
     * cactus, water, cliffs) + hostile mobs near the route, then dispatch Baritone
     * #goto the target and poll. The hazard scan is informational (Baritone does
     * its own pathing); the report lets the agent reason about the route. Mirrors
     * the legacy scanCorridorHazards + scanCorridorThreats but read-only.
     */
    private static JsonObject pathSafely(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        boolean avoidMobs = optionalBool(args, "avoidMobs", true);
        long timeoutMs = optionalInt(args, "timeoutMs", 30000);
        if (timeoutMs < 50) timeoutMs = 50;
        double safeDistance = optionalDouble(args, "safeDistance", 5.0);
        if (safeDistance <= 0) safeDistance = 5.0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        BlockPos from = player.blockPosition();

        JsonArray hazards = scanCorridorHazards(level, from, new BlockPos(x, y, z));
        JsonArray threats = new JsonArray();
        if (avoidMobs) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == player) continue;
                if (!(entity instanceof Enemy)) continue;
                double distToSegment = pointToSegmentDistance(
                        player.getX(), player.getY(), player.getZ(),
                        x, y, z,
                        entity.getX(), entity.getY(), entity.getZ());
                if (distToSegment <= safeDistance) {
                    JsonObject t = new JsonObject();
                    t.addProperty("name", describeEntityForCombat(entity));
                    t.addProperty("distance", Math.round(Math.sqrt(entity.distanceToSqr(player)) * 10) / 10.0);
                    threats.add(t);
                }
            }
        }

        client.player.connection.sendChat("#goto " + x + " " + y + " " + z);
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean arrived = false;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(MOVE_POLL_INTERVAL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= 1.0) { arrived = true; break; }
        }
        client.player.connection.sendChat("#stop");

        JsonObject danger = new JsonObject();
        danger.add("hazardsOnRoute", hazards);
        danger.add("threatsNearRoute", threats);
        JsonObject result = new JsonObject();
        result.addProperty("arrived", arrived);
        result.add("finalPosition", posJson(player.blockPosition()));
        result.add("dangerReport", danger);
        result.addProperty("message", arrived
                ? "Arrived near (" + x + ", " + y + ", " + z + "). Route had " + hazards.size() + " hazard(s) and " + threats.size() + " hostile(s) — avoided."
: "Failed to reach (" + x + ", " + y + ", " + z + ") within " + timeoutMs + "ms. Detected " + hazards.size() + " hazard(s), " + threats.size() + " hostile(s) near the route. Now at (" + player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ() + ").");
        return result;
    }

    /**
     * find-safe-location: Scan a radius around the bot for the nearest standable,
     * lit, dry, ideally-enclosed spot away from hostiles. Pure read — no movement.
     * Mirrors the legacy find-safe-location scoring (light, enclosure, hostile
     * distance, distance-from-bot). Uses level.getBrightness(LightLayer.SKY/BLOCK).
     */
    private static JsonObject findSafeLocation(Minecraft client, JsonObject args) {
        int maxDistance = optionalInt(args, "maxDistance", 32);
        boolean requireLit = optionalBool(args, "requireLit", true);
        if (maxDistance > FIND_SAFE_MAX_RADIUS) maxDistance = FIND_SAFE_MAX_RADIUS;
        if (maxDistance < 1) maxDistance = 1;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        BlockPos center = player.blockPosition();

        // Gather hostiles once for nearest-hostile-distance scoring.
        java.util.List<Entity> hostiles = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Enemy) hostiles.add(entity);
        }

        BlockPos best = null;
        String bestType = "";
        int bestLight = 0;
        int bestEnclosure = 0;
        double bestScore = -Double.MAX_VALUE;
        double bestDist = 0;
        double bestNearestHostile = 0;
        boolean bestHasRoof = false;

        for (int dx = -maxDistance; dx <= maxDistance; dx += FIND_SAFE_STEP) {
            for (int dz = -maxDistance; dz <= maxDistance; dz += FIND_SAFE_STEP) {
                if (Math.sqrt(dx * dx + dz * dz) > maxDistance) continue;
                for (int dy = 6; dy >= -6; dy--) {
                    BlockPos feet = center.offset(dx, dy, dz);
                    BlockPos floorPos = feet.below();
                    BlockState floor = level.getBlockState(floorPos);
                    BlockState feetBlock = level.getBlockState(feet);
                    BlockState head = level.getBlockState(feet.above());
                    String floorName = blockRegistryName(floor);
                    if (HAZARD_BLOCK_NAMES.contains(floorName) || FLUID_BLOCK_NAMES.contains(floorName)) break;
                    if (floor.isAir() || floor.canBeReplaced()) continue; // need a solid floor
                    if (!feetBlock.isAir() || !feetBlock.canBeReplaced()) continue;
                    if (!head.isAir() && !head.canBeReplaced()) continue;

                    int blockLight = level.getBrightness(LightLayer.BLOCK, feet);
                    int skyLight = level.getBrightness(LightLayer.SKY, feet);
                    int light = Math.max(blockLight, skyLight);
                    if (requireLit && light <= 7) break;

                    int enclosure = 0;
                    for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
                        BlockState wallFeet = level.getBlockState(feet.relative(dir));
                        BlockState wallHead = level.getBlockState(feet.relative(dir).above());
                        if (!wallFeet.isAir() && !wallFeet.canBeReplaced()) enclosure++;
                        if (!wallHead.isAir() && !wallHead.canBeReplaced()) enclosure++;
                    }
                    boolean hasRoof = false;
                    for (int ry = 2; ry <= 5; ry++) {
                        BlockState roof = level.getBlockState(feet.above(ry));
                        if (!roof.isAir() && !roof.canBeReplaced()) { hasRoof = true; break; }
                    }

                    double nearestHostile = hostiles.isEmpty() ? 999 :
                            hostiles.stream().mapToDouble(h -> Math.sqrt(h.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5))).min().orElse(999);
                    double dist = Math.sqrt(center.distManhattan(feet));

                    String type = hasRoof
                            ? (skyLight == 0 ? (feet.getY() < 50 ? "underground": "cave"): "structure")
: "surface_lit";
                    double score = (light > 7 ? 20 : 0) + enclosure * 3 + (hasRoof ? 10 : 0)
                            + Math.min(nearestHostile, 30) - dist * 0.5;

                    if (score > bestScore) {
                        bestScore = score;
                        best = feet;
                        bestType = type;
                        bestLight = light;
                        bestEnclosure = enclosure;
                        bestDist = dist;
                        bestNearestHostile = nearestHostile;
                        bestHasRoof = hasRoof;
                    }
                    break; // found standable Y for this column
                }
            }
        }

        if (best == null) {
            JsonObject result = new JsonObject();
            result.addProperty("found", false);
            result.addProperty("message", "No safe" + (requireLit ? " lit": "") + " location found within " + maxDistance + " blocks. Try requireLit=false, a larger radius, or place torches.");
            return result;
        }
        JsonObject result = new JsonObject();
        result.addProperty("found", true);
        result.add("position", posJson(best));
        result.addProperty("type", bestType);
        result.addProperty("light", bestLight);
        result.addProperty("enclosure", bestEnclosure);
        result.addProperty("distance", Math.round(bestDist * 10) / 10.0);
        result.addProperty("nearestHostileDistance", bestNearestHostile > 100 ? -1: Math.round(bestNearestHostile * 10) / 10.0);
        result.addProperty("score", Math.round(bestScore * 10) / 10.0);
        result.addProperty("description", bestType.replace('_', ' ') + " at Y=" + best.getY() + ", light " + bestLight + ", "
                + bestEnclosure + "/8 walls" + (bestHasRoof ? ", roofed": "") + ", nearest hostile "
                + (bestNearestHostile > 100 ? "none": Math.round(bestNearestHostile) + " blocks"));
        result.addProperty("message", "Safe spot: " + bestType.replace('_', ' ') + " at Y=" + best.getY() + ", light " + bestLight + ", "
                + bestEnclosure + "/8 walls" + (bestHasRoof ? ", roofed": "") + " — at (" + best.getX() + ", " + best.getY() + ", " + best.getZ() + "), "
                + (Math.round(bestDist * 10) / 10.0) + " blocks away");
        return result;
    }

    /**
     * set-movement-profile: Configure Baritone's pathfinding behavior via chat
     * commands. The legacy Movements fields are
     * (allowSprinting, canDig, maxDropDown, blocksToAvoid, cost columns); the
     * Baritone equivalents are settings commands. We dispatch each setting as a
     * Baritone #set command and report what was applied. Sprint is clamped by the
     * player's food level (a starving player cannot sprint).
     */
    private static JsonObject setMovementProfile(Minecraft client, JsonObject args) {
        boolean allowSprinting = optionalBool(args, "allowSprinting", true);
        boolean allowJumping = optionalBool(args, "allowJumping", true);
        boolean canDig = optionalBool(args, "canDig", true);
        int maxDropDown = optionalInt(args, "maxDropDown", 4);
        if (maxDropDown < 0) maxDropDown = 0;

        LocalPlayer player = client.player;
        int food = player.getFoodData().getFoodLevel();
        boolean canSprint = allowSprinting && food > 6;
        boolean sprintClamped = allowSprinting && !canSprint;

        java.util.List<String> commands = new ArrayList<>();
        commands.add("#set allowSprint " + canSprint);
        commands.add("#set allowParkour " + allowJumping);
        commands.add("#set allowBreak " + canDig);
        commands.add("#set maxFallHeight " + maxDropDown);
        for (String cmd : commands) {
            client.player.connection.sendChat(cmd);
            try { Thread.sleep(60); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }

        JsonObject applied = new JsonObject();
        applied.addProperty("allowSprinting", canSprint);
        applied.addProperty("allowJumping", allowJumping);
        applied.addProperty("canDig", canDig);
        applied.addProperty("maxDropDown", maxDropDown);
        JsonObject result = new JsonObject();
        result.add("applied", applied);
        JsonObject gates = new JsonObject();
        gates.addProperty("canSprint", canSprint);
        gates.addProperty("food", food);
        gates.addProperty("sprintClamped", sprintClamped);
        result.add("gates", gates);
        result.addProperty("message", "Movement profile applied: sprint=" + canSprint + ", parkour=" + allowJumping + ", dig=" + canDig + ", maxDrop=" + maxDropDown
                + (sprintClamped ? ". Sprint clamped: food " + food + " <= 6": ""));
        return result;
    }

    /**
     * recover-stuck: Deterministic stuck-recovery reflex. Stops Baritone, jumps,
     * and (if the head block is solid) digs it, then re-dispatches #goto the
     * previousTarget if supplied. Mirrors the legacy runRecoverStuck: stop pathfinder
     * → dig exit → move laterally → retry. Without a pathfinder we lean on Baritone
     * #stop + a jump + dig of the obstructing block + a retry goto. Reports what
     * was cleared and whether the player moved.
     */
    private static JsonObject recoverStuck(Minecraft client, JsonObject args) {
        int maxDigBlocks = optionalInt(args, "maxDigBlocks", 2);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 8000);
        if (maxDurationMs < 500) maxDurationMs = 500;
        if (maxDigBlocks < 0) maxDigBlocks = 0;
        if (maxDigBlocks > 16) maxDigBlocks = 16;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        long start = System.currentTimeMillis();

        // Stop any in-flight Baritone path.
        client.player.connection.sendChat("#stop");
        BlockPos startBlock = player.blockPosition();

        boolean boxedIn = false;
        BlockState headState = level.getBlockState(startBlock.above());
        if (!headState.isAir() && !headState.canBeReplaced()) boxedIn = true;

        java.util.List<String> cleared = new ArrayList<>();
        // Jump to un-wedge.
        client.options.keyJump.setDown(true);
        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        client.options.keyJump.setDown(false);

        // Dig the head obstruction if present and digging is allowed.
        if (boxedIn && maxDigBlocks > 0) {
            BlockPos headPos = startBlock.above();
            if (!level.getBlockState(headPos).isAir()) {
                try {
                    client.gameMode.startDestroyBlock(headPos, Direction.UP);
                    client.gameMode.stopDestroyBlock();
                    client.player.swing(InteractionHand.MAIN_HAND);
                    cleared.add(blockRegistryName(level.getBlockState(headPos)));
                } catch (Exception ignored) { /* best-effort dig */ }
            }
        }

        // Retry the original target if supplied.
        boolean retried = false;
        boolean arrived = false;
        if (args.has("previousTarget") && args.get("previousTarget").isJsonObject()) {
            JsonObject tgt = args.getAsJsonObject("previousTarget");
            int tx = requiredInt(tgt, "x");
            int ty = requiredInt(tgt, "y");
            int tz = requiredInt(tgt, "z");
            client.player.connection.sendChat("#goto " + tx + " " + ty + " " + tz);
            retried = true;
            long deadline = start + maxDurationMs;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(MOVE_POLL_INTERVAL_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                double dx = player.getX() - tx, dy = player.getY() - ty, dz = player.getZ() - tz;
                if (dx * dx + dy * dy + dz * dz <= 1.0) { arrived = true; break; }
            }
            client.player.connection.sendChat("#stop");
        }

        BlockPos endBlock = player.blockPosition();
        double movedBlocks = Math.sqrt(startBlock.distManhattan(endBlock));
        boolean recovered = movedBlocks > 0.5 || arrived || !cleared.isEmpty();

        JsonObject result = new JsonObject();
        result.addProperty("recovered", recovered);
        result.addProperty("reason", recovered ? "unstuck": "still_stuck");
        result.addProperty("boxedIn", boxedIn);
        JsonArray clearedArr = new JsonArray();
        for (String c : cleared) clearedArr.add(c);
        result.add("blocksCleared", clearedArr);
        result.addProperty("movedBlocks", Math.round(movedBlocks * 10) / 10.0);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("retriedPreviousTarget", retried);
        result.addProperty("arrivedOnRetry", arrived);
        result.addProperty("message", recovered
                ? "Recovered: " + (cleared.isEmpty() ? "jump unstuck": "cleared " + cleared.size() + " block(s)") + ", moved " + (Math.round(movedBlocks * 10) / 10.0) + " blocks in " + (System.currentTimeMillis() - start) + "ms"
: "recover-stuck still_stuck: boxedIn=" + boxedIn + ", cleared " + cleared.size() + " block(s), moved " + (Math.round(movedBlocks * 10) / 10.0) + " blocks");
        return result;
    }

    /**
     * escape-water: Drowning reflex. When the player's head is in water, ring-search
     * outward for the nearest standable dry cell, dispatch Baritone #goto it, and
     * hold jump until the head is no longer water or the deadline elapses. Mirrors
     * the legacy runEscapeWater. The the legacy version emits a WATER_ESCAPE event; the
     * Fabric bridge has no event sink, so we report only.
     */
    private static JsonObject escapeWater(Minecraft client, JsonObject args) {
        long maxDurationMs = optionalInt(args, "maxDurationMs", 8000);
        int oxygenThreshold = optionalInt(args, "oxygenThreshold", 6);
        int ringMax = optionalInt(args, "ringMax", 8);
        if (maxDurationMs < 500) maxDurationMs = 500;
        if (ringMax < 1) ringMax = 1;
        if (ringMax > 64) ringMax = 64;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        long start = System.currentTimeMillis();
        int startOxygen = player.getAirSupply();

        if (!player.isUnderWater() && !player.isInWater()) {
            JsonObject result = new JsonObject();
            result.addProperty("escaped", false);
            result.addProperty("reason", "not in water");
            result.addProperty("startOxygen", startOxygen);
            result.addProperty("endOxygen", player.getAirSupply());
            result.addProperty("durationMs", 0);
            result.addProperty("message", "Not in water — nothing to escape");
            return result;
        }

        // Ring-search outward for the nearest standable dry cell at the player's Y level.
        BlockPos origin = player.blockPosition();
        BlockPos shore = null;
        outer:
        for (int r = 1; r <= ringMax; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring edge only
                    for (int dy = 2; dy >= -2; dy--) {
                        BlockPos feet = origin.offset(dx, dy, dz);
                        BlockState floor = level.getBlockState(feet.below());
                        BlockState feetBlock = level.getBlockState(feet);
                        BlockState head = level.getBlockState(feet.above());
                        String floorName = blockRegistryName(floor);
                        if (FLUID_BLOCK_NAMES.contains(floorName) || HAZARD_BLOCK_NAMES.contains(floorName)) continue;
                        if (floor.isAir() || floor.canBeReplaced()) continue;
                        String feetName = blockRegistryName(feetBlock);
                        String headName = blockRegistryName(head);
                        if (FLUID_BLOCK_NAMES.contains(feetName) || FLUID_BLOCK_NAMES.contains(headName)) continue;
                        if (!feetBlock.isAir() && !feetBlock.canBeReplaced()) continue;
                        if (!head.isAir() && !head.canBeReplaced()) continue;
                        shore = feet;
                        break outer;
                    }
                }
            }
        }

        if (shore != null) {
            client.player.connection.sendChat("#goto " + shore.getX() + " " + shore.getY() + " " + shore.getZ());
        }
        // Hold jump (swim up / climb out) until the head is out of water or deadline.
        boolean escaped = false;
        long deadline = start + maxDurationMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                client.options.keyJump.setDown(true);
                if (!player.isUnderWater() && !player.isInWater()) { escaped = true; break; }
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            client.options.keyJump.setDown(false);
            client.player.connection.sendChat("#stop");
        }

        int endOxygen = player.getAirSupply();
        String reason = escaped ? "reached_shore": (shore == null ? "no_shore_found": "timeout");
        JsonObject result = new JsonObject();
        result.addProperty("escaped", escaped);
        result.addProperty("reason", reason);
        result.addProperty("startOxygen", startOxygen);
        result.addProperty("endOxygen", endOxygen);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        if (shore != null) result.add("shore", posJson(shore));
        result.addProperty("message", escaped
                ? "Escaped water (" + reason + "): o2 " + startOxygen + "->" + endOxygen + " in " + (System.currentTimeMillis() - start) + "ms"
                + (shore != null ? ", shore (" + shore.getX() + "," + shore.getY() + "," + shore.getZ() + ")": "")
: "escape-water " + reason + ": o2 " + startOxygen + "->" + endOxygen + " in " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tier J: Process & composite-sense (Baritone delegation + Java primitives + NYI stubs)
    // ─────────────────────────────────────────────────────────────────────

    private static final long DEFAULT_COLLECT_DROPS_TIMEOUT_MS = 30000L;
    private static final int DEFAULT_COLLECT_DROPS_RADIUS = 16;
    private static final int COLLECT_DROPS_MAX_PASSES = 24;
    private static final long GUARD_POLL_INTERVAL_MS = 500L;
    private static final long MINE_POLL_INTERVAL_MS = 1000L;
    private static final long EXPLORE_POLL_INTERVAL_MS = 1000L;
    private static final int EXPLORE_CHUNK_SIZE = 16;
    private static final int SCAN_VOLUME_MAX_DIM = 32;
    private static final int SCAN_VOLUME_MAX_Y = 16;
    // Ores/logs/containers surfaced as notableBlocks by scan-volume + find-ore-veins.
    private static final java.util.Set<String> NOTABLE_ORE_NAMES =
            java.util.Set.of("coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore",
                    "lapis_ore", "diamond_ore", "emerald_ore", "nether_gold_ore", "ancient_debris",
                    "nether_quartz_ore", "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore",
                    "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_lapis_ore",
                    "deepslate_diamond_ore", "deepslate_emerald_ore");
    private static final java.util.Set<String> NOTABLE_LOG_NAMES =
            java.util.Set.of("oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log",
                    "dark_oak_log", "cherry_log", "mangrove_log", "crimson_stem", "warped_stem");
    private static final java.util.Set<String> NOTABLE_CONTAINER_NAMES =
            java.util.Set.of("chest", "trapped_chest", "barrel", "ender_chest", "shulker_box",
                    "furnace", "blast_furnace", "smoker", "brewing_stand", "dispenser", "dropper");

    /**
     * NYI stubs — mine-blocks, build-structure, enqueue-tasks. These legacy
     * tools depend on the ProcessEngine (composite GoalBreak/BuildProcess/queue
     * runner) which is absent in Fabric mode (wiredEngine = undefined). Rather
     * than silently dropping them, return a structured not-yet-implemented
     * response so the agent gets a clean signal and can fall back to primitives
     * (dig-block, place-block, navigate-v2, follow-entity).
     */
    private static JsonObject notYetImplementedResponse(String tool) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", "not_yet_implemented");
        result.addProperty("tool", tool);
        result.addProperty("message", "'" + tool + "' is not yet implemented by the Fabric body. "
                + "It is a ProcessEngine-composite (server-side multi-step process) with no Baritone/Java equivalent yet. "
                + "Use the primitive tools instead: dig-block, place-block, navigate-v2, get-to-block, follow-entity.");
        return result;
    }

    /** Standalone (non-queue) entry point for mine-blocks — delegates to the
     * same queue-safe handler (which uses callOnClient, working correctly on
     * the client thread when called directly). */
    private static JsonObject mineBlocksStandalone(Minecraft client, JsonObject args) {
        return queueMineBlocks(client, args);
    }

    /** Standalone (non-queue) entry point for build-structure — delegates to the
     * same queue-safe handler (which uses callOnClient, working correctly on
     * the client thread when called directly). */
    private static JsonObject buildStructureStandalone(Minecraft client, JsonObject args) {
        return queueBuildStructure(client, args);
    }

    /** Standalone (non-queue) entry points for the v0.19.0 composite tools —
     * delegate to the queue-safe handlers so their polling loops never run on
     * the render/client thread. Mirrors mineBlocksStandalone -> queueMineBlocks. */
    private static JsonObject getToBlockStandalone(Minecraft client, JsonObject args) {
        return queueGetToBlock(client, args);
    }

    private static JsonObject exploreStandalone(Minecraft client, JsonObject args) {
        return queueExplore(client, args);
    }

    private static JsonObject followPlayerStandalone(Minecraft client, JsonObject args) {
        return queueFollowPlayer(client, args);
    }

    private static JsonObject guardAreaStandalone(Minecraft client, JsonObject args) {
        return queueGuardArea(client, args);
    }

    // ── Queue composites (v0.18) ─────────────────────────────────────────

    // ──: policy action rail ──────────────────────

    /**
     * Tools a policy row may name as its action: the queue-rail tools
     * (dispatchQueueTask path) plus the survival reflexes that dispatch
     * through HANDLERS on the queue executor (eat-food / flee-from /
     * escape-water — the isSleepingComposite set) and the Meteor toggle.
     */
    private static final java.util.Set<String> POLICY_ACTION_TOOLS =
            java.util.Set.of("eat-food", "flee-from", "escape-water",
                    "toggle-meteor-module", "place-torch", "goto-coords",
                    "mine-blocks", "get-to-block", "explore", "guard-area",
                    "follow-player", "hunt-hostile", "collect-drops",
                    "deposit-items", "withdraw-items", "drop-items");

    /**
     * Execute one policy action through the single-Baritone
     * queue rail — the ActionSink the PolicyEngine calls. Non-blocking:
     * the work runs on the queue worker thread and the returned future
     * completes with the action's result JSON. Never bypasses the
     * QUEUE_ACTIVE gate; a busy rail answers queue_busy (the engine
     * rolls its cooldown back so the next event retries).
     */
    public static java.util.concurrent.CompletableFuture<JsonObject> runPolicyAction(
            com.hyfuse.bridge.sense.PolicyEngine.Action action) {
        Minecraft client = Minecraft.getInstance();
        java.util.concurrent.CompletableFuture<JsonObject> future =
                new java.util.concurrent.CompletableFuture<>();
        if (!POLICY_ACTION_TOOLS.contains(action.tool())) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "unsupported_policy_action");
            r.addProperty("tool", action.tool());
            future.complete(r);
            return future;
        }
        if (!QUEUE_ACTIVE.compareAndSet(false, true)) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "queue_busy");
            future.complete(r);
            return future;
        }
        QUEUE_EXECUTOR.execute(() -> {
            try {
                QueueTelemetry.begin("policy:" + action.tool());
                callOnClient(client, () -> {
                    if (client.player == null || client.level == null) {
                        throw new ToolException("CLIENT_NOT_READY",
                                "Join a world before calling client tools");
                    }
                    return null;
                });
                JsonObject args = action.args() != null ? action.args() : new JsonObject();
                JsonObject result;
                if (QUEUE_SUPPORTED_TOOLS.contains(action.tool())) {
                    result = dispatchQueueTask(client, action.tool(), args);
                } else {
                    ToolHandler handler = HANDLERS.get(action.tool());
                    result = handler != null ? handler.handle(client, args) : null;
                    if (result == null) {
                        result = new JsonObject();
                        result.addProperty("ok", false);
                        result.addProperty("reason", "unknown_tool");
                    }
                }
                future.complete(result);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            } finally {
                QueueTelemetry.end();
                QUEUE_ACTIVE.set(false);
            }
        });
        return future;
    }

    private static final java.util.Set<String> QUEUE_SUPPORTED_TOOLS =
            java.util.Set.of("mine-blocks", "build-structure", "get-to-block", "explore",
                    "follow-player", "guard-area", "hunt-hostile", "drop-items",
                    "break-entity", "replace-blocks", "place-block",
                    "craft-item", "smelt-item",
                    // Mine-and-deposit cycles deposit at base chest.
                    "deposit-items", "withdraw-items",
                    // Standing-cycle goto to explicit coords.
                    "goto-coords");

    private static final long QUEUE_RETURN_ORIGIN_TIMEOUT_MS = 45000L;
    private static final java.util.concurrent.ExecutorService QUEUE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mc-agent-task-queue");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean QUEUE_ACTIVE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * enqueue-tasks: Run a linear list of process-tool tasks sequentially, with
     * cross-task context ($name.field resolution). Each task dispatches to its
     * handler via dispatchQueueTask. Optional returnToOrigin navigates back.
     */
    private static JsonObject enqueueTasks(Minecraft client, JsonObject args) {
        long startTime = System.currentTimeMillis();
        JsonElement tasksEl = args.get("tasks");
        if (tasksEl == null || !tasksEl.isJsonArray()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("error", "INVALID_ARGUMENT");
            r.addProperty("message", "enqueue-tasks requires a 'tasks' array");
            return r;
        }
        JsonArray taskArr = tasksEl.getAsJsonArray();
        if (taskArr.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("completed", 0);
            r.addProperty("skipped", 0);
            r.addProperty("total", 0);
            r.add("results", new JsonArray());
            r.addProperty("reason", "empty");
            r.addProperty("durationMs", System.currentTimeMillis() - startTime);
            return r;
        }
        boolean returnToOrigin = optionalBool(args, "returnToOrigin", true);
        BlockPos origin = callOnClient(client, () -> client.player.blockPosition());
        java.util.Map<String, JsonObject> context = new java.util.HashMap<>();
        int total = taskArr.size();
        int completed = 0;
        int skipped = 0;
        boolean aborted = false;
        JsonArray results = new JsonArray();

        for (int i = 0; i < total; i++) {
            JsonElement el = taskArr.get(i);
            if (el == null || !el.isJsonObject()) {
                JsonObject skip = childResultJson("<invalid>", false, "invalid_task_spec", null);
                results.add(skip);
                skipped++;
                continue;
            }
            JsonObject spec = el.getAsJsonObject();
            String tool = optionalString(spec, "tool", "");
            String taskName = optionalString(spec, "name", "");
            String onFail = optionalString(spec, "onFail", "continue");
            JsonObject rawArgs = spec.has("args") && spec.get("args").isJsonObject()
                    ? spec.getAsJsonObject("args"): new JsonObject();
            JsonObject childArgs = resolveTaskArgs(rawArgs, context);

            if (!QUEUE_SUPPORTED_TOOLS.contains(tool)) {
                JsonObject skip = childResultJson(tool, false, "unknown_tool", null);
                results.add(skip);
                skipped++;
                if (!onFail.equals("abort")) continue;
                aborted = true;
                for (int j = i + 1; j < total; j++) {
                    results.add(childResultJson(toolNameAt(taskArr, j), false, "skipped", null));
                    skipped++;
                }
                break;
            }

            QueueTelemetry.markTask(i, tool, total, taskName);
            JsonObject childResult;
            try {
                childResult = dispatchQueueTask(client, tool, childArgs);
            } catch (Throwable t) {
                childResult = new JsonObject();
                childResult.addProperty("ok", false);
                childResult.addProperty("reason", "error");
                childResult.addProperty("error", t.getMessage() == null ? t.getClass().getSimpleName(): t.getMessage());
            }

            boolean childOk = childResult.has("ok") && childResult.get("ok").isJsonPrimitive()
                    && childResult.get("ok").getAsBoolean();
            String childReason = childResult.has("reason") && childResult.get("reason").isJsonPrimitive()
                    ? childResult.get("reason").getAsString()
: (childOk ? "done": "failed");
            results.add(childResultJson(tool, childOk, childReason, childResult));
            completed++;
            if (!taskName.isEmpty()) {
                context.put(taskName, childResult);
            }
            if (childOk || !onFail.equals("abort")) continue;
            aborted = true;
            for (int j = i + 1; j < total; j++) {
                results.add(childResultJson(toolNameAt(taskArr, j), false, "skipped", null));
                skipped++;
            }
            break;
        }

        boolean returnedToOrigin = false;
        if (returnToOrigin) {
            try {
                returnedToOrigin = returnPlayerTo(client, origin, QUEUE_RETURN_ORIGIN_TIMEOUT_MS);
            } catch (Throwable ignored) {
                returnedToOrigin = false;
            }
        }

        boolean allOk = completed > 0 && !aborted && allResultsOk(results);
        String reason = aborted ? "aborted": (allOk ? "done": "partial");
        JsonObject result = new JsonObject();
        result.addProperty("ok", allOk);
        result.addProperty("completed", completed);
        result.addProperty("skipped", skipped);
        result.addProperty("total", total);
        result.add("results", results);
        result.addProperty("reason", reason);
        result.addProperty("returnToOrigin", returnToOrigin);
        result.addProperty("returnedToOrigin", returnedToOrigin);
        result.add("origin", posJson(origin));
        result.addProperty("durationMs", System.currentTimeMillis() - startTime);
        result.addProperty("message", "Queue " + reason + ": " + completed + "/" + total
                + " tasks done"
                + (returnToOrigin ? (returnedToOrigin ? ", returned to origin": ", return-to-origin incomplete"): ""));
        return result;
    }

    /** Dispatch a single queue task by tool name to its handler. */
    private static JsonObject dispatchQueueTask(Minecraft client, String tool, JsonObject childArgs) {
        switch (tool) {
            case "get-to-block": return queueGetToBlock(client, childArgs);
            case "explore": return queueExplore(client, childArgs);
            case "follow-player": return queueFollowPlayer(client, childArgs);
            case "guard-area": return queueGuardArea(client, childArgs);
            case "mine-blocks": return queueMineBlocks(client, childArgs);
            case "build-structure": return queueBuildStructure(client, childArgs);
            case "hunt-hostile": return queueHuntHostile(client, childArgs);
            case "drop-items": return queueDropItems(client, childArgs);
            case "break-entity": return queueBreakEntity(client, childArgs);
            case "replace-blocks": return queueReplaceBlocks(client, childArgs);
            case "place-block": return callOnClient(client, () -> placeBlock(client, childArgs));
            case "craft-item": return queueCraftItem(client, childArgs);
            case "smelt-item": return queueSmeltItem(client, childArgs);
            // Reachable from standing-process cycles.
            case "deposit-items": return depositItems(client, childArgs);
            // Defect 1: arrival-gated coordinate goto for standing
            // cycles (queue-only; standalone tool remains navigate-v2).
            case "goto-coords": return queueGotoCoords(client, childArgs);
            case "withdraw-items": return withdrawItems(client, childArgs);
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("reason", "unknown_tool");
        r.addProperty("tool", tool);
        return r;
    }

    /** Recursively resolve $name.field references in task args. */
    private static JsonObject resolveTaskArgs(JsonObject args, java.util.Map<String, JsonObject> context) {
        JsonObject resolved = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> e : args.entrySet()) {
            resolved.add(e.getKey(), resolveTaskValue(e.getValue(), context));
        }
        return resolved;
    }

    /** Resolve a single JsonElement: recurse into objects/arrays, resolve $refs in strings. */
    private static JsonElement resolveTaskValue(JsonElement value, java.util.Map<String, JsonObject> context) {
        if (value == null) return JsonNull.INSTANCE;
        if (value.isJsonObject()) return resolveTaskArgs(value.getAsJsonObject(), context);
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : value.getAsJsonArray()) {
                out.add(resolveTaskValue(el, context));
            }
            return out;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String s = value.getAsString();
            if (s.startsWith("$") && s.length() > 1) {
                JsonElement looked = resolveDollarRef(s.substring(1), context);
                if (looked != null) return looked;
            }
        }
        return value;
    }

    /** Resolve a dotted $name.field.path reference against the task context map. */
    private static JsonElement resolveDollarRef(String ref, java.util.Map<String, JsonObject> context) {
        int dot = ref.indexOf('.');
        if (dot < 0) return null;
        String name = ref.substring(0, dot);
        JsonObject base = context.get(name);
        if (base == null) return null;
        JsonElement cur = base;
        for (String part: ref.substring(dot + 1).split("\\.")) {
            if (cur == null || !cur.isJsonObject()) return null;
            JsonObject obj = cur.getAsJsonObject();
            if (!obj.has(part)) return null;
            cur = obj.get(part);
        }
        return cur;
    }

    /** Build a child-result envelope: { tool, ok, reason, result }. */
    private static JsonObject childResultJson(String tool, boolean ok, String reason, JsonObject childResult) {
        JsonObject r = new JsonObject();
        r.addProperty("tool", tool);
        r.addProperty("ok", ok);
        r.addProperty("reason", reason);
        r.add("result", childResult != null ? childResult: JsonNull.INSTANCE);
        return r;
    }

    /** Extract the tool name from task i in the array (for skip messages). */
    private static String toolNameAt(JsonArray taskArr, int i) {
        JsonElement el = taskArr.get(i);
        if (el != null && el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("tool") && o.get("tool").isJsonPrimitive()) {
                return o.get("tool").getAsString();
            }
        }
        return "<unknown>";
    }

    /** True if every result in the array has ok=true. */
    private static boolean allResultsOk(JsonArray results) {
        for (JsonElement e : results) {
            if (e == null || !e.isJsonObject()) return false;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("ok") || !o.get("ok").getAsBoolean()) return false;
        }
        return true;
    }

    /** Navigate the player back to origin without blocking the client thread. */
    private static boolean returnPlayerTo(Minecraft client, BlockPos origin, long timeoutMs) {
        callOnClient(client, () -> {
            client.player.connection.sendChat("#goto " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
            return null;
        });
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean arrived = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500L);
                arrived = callOnClient(client, () -> {
                    LocalPlayer player = client.player;
                    double dx = player.getX() - origin.getX();
                    double dy = player.getY() - origin.getY();
                    double dz = player.getZ() - origin.getZ();
                    return dx * dx + dy * dy + dz * dz <= 4.0;
                });
                if (arrived) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
        }
        return arrived;
    }

    /** Hunt a hostile while keeping rendering, ticks, Freecam and Baritone responsive.
     * Optional entity type filter via the "entity"/"type"/"entityName" key
     * (e.g. "zombie", "minecraft:creeper"). When omitted, hunts the nearest hostile. */
    private static JsonObject queueHuntHostile(Minecraft client, JsonObject args) {
        int maxDistance = optionalInt(args, "maxDistance", 24);
        double killRange = optionalDouble(args, "killRange", 5.0);
        long killTimeoutMs = optionalInt(args, "killTimeoutMs", 30000);
        double collectRadius = optionalDouble(args, "collectRadius", 8.0);
        String entityType = optionalString(args, "entity", optionalString(args, "type", optionalString(args, "entityName", "")));
        String typeNeedle = stripNamespace(entityType).toLowerCase();

        LivingEntity target = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            LivingEntity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Entity e : client.level.entitiesForRendering()) {
                if (!(e instanceof LivingEntity le)) continue;
                if (!(e instanceof Monster) && !(e instanceof Enemy)) continue;
                if (!typeNeedle.isEmpty()
                        && !stripNamespace(entityRegistryName(e)).toLowerCase().contains(typeNeedle)) continue;
                double d = e.distanceToSqr(player);
                if (d <= maxDistance * maxDistance && d < nearestDist) {
                    nearest = le;
                    nearestDist = d;
                }
            }
            return nearest;
        });

        if (target == null) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "no_hostile");
            r.addProperty("message", "hunt-hostile: no " + (typeNeedle.isEmpty() ? "hostile": entityType) + " within " + maxDistance + " blocks");
            r.add("loot", new JsonArray());
            return r;
        }

        BlockPos targetPos = callOnClient(client, target::blockPosition);
        String targetType = callOnClient(client, () -> entityRegistryName(target));
        java.util.Map<String, Integer> before = callOnClient(client, () -> inventoryCounts(client.player));

        // Enable KillAura before approach — its tick handler will attack the target
        // as soon as it enters range, even while Baritone is pathing toward it.
        toggleMeteorModuleByName(client, "kill-aura", true);

        callOnClient(client, () -> {
            client.player.connection.sendChat("#goto " + targetPos.getX() + " " + targetPos.getY() + " " + targetPos.getZ());
            return null;
        });

        long start = System.currentTimeMillis();
        boolean killed = false;
        String reason = "timeout";
        try {
            // Approach phase: Baritone paths toward target; KillAura may kill it
            // during approach. Poll for death or arrival within killRange.
            long approachDeadline = start + 20000L;
            while (System.currentTimeMillis() < approachDeadline) {
                Thread.sleep(200L);
                int state = callOnClient(client, () -> {
                    if (target.isRemoved() || !target.isAlive()) return 2;
                    return Math.sqrt(target.distanceToSqr(client.player)) <= killRange ? 1 : 0;
                });
                if (state == 2) { killed = true; reason = "killed_by_killaura"; break; }
                if (state == 1) break;
            }

            if (!killed) {
                callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
                // Kill phase: KillAura is still active and attacking on ticks.
                // Also do direct attacks as fallback (in case KillAura is absent
                // or targeting a different mob).
                long killDeadline = System.currentTimeMillis() + killTimeoutMs;
                while (System.currentTimeMillis() < killDeadline) {
                    boolean dead = callOnClient(client, () -> {
                        if (target.isRemoved() || !target.isAlive()) return true;
                        lookAtEntity(client, target);
                        client.gameMode.attack(client.player, target);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        return false;
                    });
                    if (dead) { killed = true; reason = "killed"; break; }
                    Thread.sleep(ATTACK_COOLDOWN_MS);
                }
                if (!killed) {
                    killed = callOnClient(client, () -> target.isRemoved() || !target.isAlive());
                    if (killed) reason = "killed_by_killaura";
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } finally {
            // Disable KillAura after the hunt — don't leave it active between tasks.
            toggleMeteorModuleByName(client, "kill-aura", false);
            callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
        }

        JsonArray loot = new JsonArray();
        if (killed) {
            BlockPos deathPos = callOnClient(client, () -> target.isRemoved() ? targetPos : target.blockPosition());
            collectDropsAt(client, deathPos, collectRadius, 20000L);
            java.util.Map<String, Integer> after = callOnClient(client, () -> inventoryCounts(client.player));
            for (java.util.Map.Entry<String, Integer> e : after.entrySet()) {
                int gained = e.getValue() - before.getOrDefault(e.getKey(), 0);
                for (int k = 0; k < gained; k++) loot.add(e.getKey());
            }
        }

        BlockPos finalPos = callOnClient(client, () -> target.isRemoved() ? targetPos : target.blockPosition());
        JsonObject r = new JsonObject();
        r.addProperty("ok", killed);
        r.addProperty("reason", killed ? "killed": reason);
        r.addProperty("target", targetType);
        r.addProperty("killed", killed);
        r.add("position", posJson(finalPos));
        r.add("loot", loot);
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", killed ? "Hunted " + targetType + ", collected " + loot.size() + " item(s)": "hunt-hostile: " + reason);
        return r;
    }

    /** Drop items in one short client-thread transaction.
     * Accepts the items array as either primitive strings (drop whole stacks)
     * or objects {name, count} for partial drops. */
    private static JsonObject queueDropItems(Minecraft client, JsonObject args) {
        int walkBlocks = optionalInt(args, "walkBlocks", 0);
        boolean dropAll = optionalBool(args, "dropAll", false);
        java.util.Map<String, Integer> wantedCounts = new java.util.HashMap<>();
        JsonElement itemsEl = args.get("items");
        if (itemsEl != null && itemsEl.isJsonArray()) {
            for (JsonElement e : itemsEl.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    wantedCounts.merge(stripNamespace(e.getAsString()), Integer.MAX_VALUE, (a, b) -> a);
                } else if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    String name = o.has("name") && o.get("name").isJsonPrimitive()
                            ? stripNamespace(o.get("name").getAsString()): null;
                    if (name == null || name.isEmpty()) continue;
                    int cnt = o.has("count") && o.get("count").isJsonPrimitive()
                            ? o.get("count").getAsInt(): Integer.MAX_VALUE;
                    if (cnt <= 0) continue;
                    wantedCounts.merge(name, cnt, (a, b) -> a);
                }
            }
        }
        if (walkBlocks > 0) walkBlocksDirectional(client, walkBlocks, optionalString(args, "direction", "north"), 15000L);

        java.util.List<String> dropped = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            Inventory inv = player.getInventory();
            java.util.List<String> out = new java.util.ArrayList<>();
            // PlayerScreenHandler slot map: 0=craft output, 1-4 craft grid, 5-8 armor,
            // 9-35 main storage, 36-44 hotbar, 45 offhand.
            // Inventory.getItem(slot) uses 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand.
            // Map inv-slot to screen-handler slot for handleContainerInput.
            java.util.function.IntUnaryOperator toScreen = invSlot -> {
                if (invSlot >= 0 && invSlot <= 8) return invSlot + 36;  // hotbar 0-8 -> 36-44
                if (invSlot >= 9 && invSlot <= 35) return invSlot;     // main storage -> same
                if (invSlot == 40) return 45;                          // offhand -> 45
                return -1;
            };
            for (int slot = 0; slot <= 40; slot++) {
                if (slot >= 36 && slot <= 39) continue;
                ItemStack stack = inv.getItem(slot);
                if (stack.isEmpty()) continue;
                String name = stripNamespace(itemRegistryName(stack));
                if (!dropAll && !wantedCounts.containsKey(name)) continue;
                int stackCount = stack.getCount();
                int want = dropAll ? stackCount : Math.min(stackCount, wantedCounts.getOrDefault(name, stackCount));
                int screenSlot = toScreen.applyAsInt(slot);
                if (screenSlot < 0) continue;
                if (want >= stackCount) {
                    // Drop entire stack: THROW button=0
                    client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
                            screenSlot, 0, ContainerInput.THROW, player);
                    for (int k = 0; k < stackCount; k++) out.add(name);
                } else {
                    // Partial drop via PICKUP + right-click + drop-cursor:
                    // 1. PICKUP left-click -> cursor gets entire stack, slot empty
                    client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
                            screenSlot, 0, ContainerInput.PICKUP, player);
                    // 2. Right-click (stackCount - want) times -> places that many back,
                    // cursor ends with exactly 'want' items
                    int returnCount = stackCount - want;
                    for (int r = 0; r < returnCount; r++) {
                        client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
                                screenSlot, 1, ContainerInput.PICKUP, player);
                    }
                    // 3. Click slot -999 with PICKUP -> drops entire cursor (want items) to world
                    client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
                            -999, 0, ContainerInput.PICKUP, player);
                    for (int k = 0; k < want; k++) out.add(name);
                }
            }
            return out;
        });
        try { Thread.sleep(200L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        BlockPos pos = callOnClient(client, () -> client.player.blockPosition());
        JsonArray droppedArr = new JsonArray();
        for (String d : dropped) droppedArr.add(d);
        JsonObject r = new JsonObject();
        r.addProperty("ok", true); r.addProperty("reason", "dropped");
        r.add("dropped", droppedArr); r.addProperty("droppedCount", dropped.size());
        r.add("position", posJson(pos));
        r.addProperty("message", "Dropped " + dropped.size() + " item(s) at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        return r;
    }

    /** Break an entity using short direct-attack transactions. */
    private static JsonObject queueBreakEntity(Minecraft client, JsonObject args) {
        String type = optionalString(args, "type", "boat");
        int maxDistance = optionalInt(args, "maxDistance", 32);
        long attackTimeoutMs = optionalInt(args, "attackTimeoutMs", 15000);
        String needle = stripNamespace(type).toLowerCase();
        Entity target = callOnClient(client, () -> {
            Entity nearest = null; double nearestDist = Double.MAX_VALUE;
            for (Entity e : client.level.entitiesForRendering()) {
                if (e == client.player) continue;
                if (!stripNamespace(entityRegistryName(e)).toLowerCase().contains(needle)) continue;
                double d = e.distanceToSqr(client.player);
                if (d <= maxDistance * maxDistance && d < nearestDist) { nearest = e; nearestDist = d; }
            }
            return nearest;
        });
        if (target == null) {
            JsonObject r = new JsonObject(); r.addProperty("ok", false); r.addProperty("reason", "not_found");
            r.addProperty("message", "break-entity: no " + type + " within " + maxDistance + " blocks"); return r;
        }
        final BlockPos[] lastPos = { callOnClient(client, target::blockPosition) };
        callOnClient(client, () -> { BlockPos p = lastPos[0]; client.player.connection.sendChat("#goto " + p.getX() + " " + p.getY() + " " + p.getZ()); return null; });
        long start = System.currentTimeMillis(); boolean broken = false; String reason = "timeout";
        try {
            while (System.currentTimeMillis() < start + attackTimeoutMs) {
                Thread.sleep(200L);
                int state = callOnClient(client, () -> {
                    if (target.isRemoved() || !target.isAlive()) return 2;
                    lastPos[0] = target.blockPosition();
                    if (Math.sqrt(target.distanceToSqr(client.player)) > 4.5) return 0;
                    client.player.connection.sendChat("#stop");
                    lookAtEntity(client, target); client.gameMode.attack(client.player, target); client.player.swing(InteractionHand.MAIN_HAND);
                    return 1;
                });
                if (state == 2) { broken = true; reason = "broken"; break; }
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); reason = "interrupted"; }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }
        if (!broken) broken = callOnClient(client, () -> target.isRemoved() || !target.isAlive());
        BlockPos finalPos = callOnClient(client, () -> target.isRemoved() ? lastPos[0] : target.blockPosition());
        String finalType = callOnClient(client, () -> stripNamespace(entityRegistryName(target)));
        JsonObject r = new JsonObject(); r.addProperty("ok", broken); r.addProperty("reason", broken ? "broken": reason);
        r.addProperty("type", finalType); r.add("position", posJson(finalPos)); r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", broken ? "Broke " + type + " at " + finalPos.getX() + "," + finalPos.getY() + "," + finalPos.getZ(): "break-entity: " + reason);
        return r;
    }

    /** Replace blocks via Baritone commands, polling through client transactions. */
    private static JsonObject queueReplaceBlocks(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x"), y = requiredInt(args, "y"), z = requiredInt(args, "z");
        String fromSimple = stripNamespace(optionalString(args, "from", ""));
        String toSimple = stripNamespace(requiredString(args, "to"));
        long timeoutMs = optionalInt(args, "timeoutMs", 30000);
        BlockPos pos = new BlockPos(x, y, z);
        String current = callOnClient(client, () -> simpleBlockName(client.level.getBlockState(pos)));
        if (!fromSimple.isEmpty() && !current.equals(fromSimple)) {
            JsonObject r = new JsonObject(); r.addProperty("ok", false); r.addProperty("reason", "source_mismatch");
            r.addProperty("found", current); r.addProperty("expected", fromSimple); return r;
        }
        callOnClient(client, () -> {
            client.player.connection.sendChat("#sel clear");
            client.player.connection.sendChat("#sel pos1 " + x + " " + y + " " + z);
            client.player.connection.sendChat("#sel pos2 " + x + " " + y + " " + z);
            client.player.connection.sendChat(fromSimple.isEmpty() ? "#sel set " + toSimple: "#sel replace " + fromSimple + " " + toSimple);
            return null;
        });
        long start = System.currentTimeMillis(), deadline = start + timeoutMs; boolean replaced = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500L);
                if (callOnClient(client, () -> simpleBlockName(client.level.getBlockState(pos)).equals(toSimple))) { replaced = true; break; }
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); client.player.connection.sendChat("#sel clear"); return null; }); }
        JsonObject r = new JsonObject(); r.addProperty("ok", replaced); r.addProperty("reason", replaced ? "replaced": "timeout");
        r.addProperty("from", fromSimple); r.addProperty("to", toSimple); r.add("position", posJson(pos));
        r.addProperty("durationMs", System.currentTimeMillis() - start); return r;
    }

    /** Collect nearby drops without blocking game ticks. */
    private static void collectDropsAt(Minecraft client, BlockPos center, double radius, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                ItemEntity nearest = callOnClient(client, () -> {
                    ItemEntity found = null; double nd = Double.MAX_VALUE;
                    for (Entity e : client.level.entitiesForRendering()) {
                        if (!(e instanceof ItemEntity ie)) continue;
                        double d = e.distanceToSqr(center.getX() + .5, center.getY() + .5, center.getZ() + .5);
                        if (d <= radius * radius && d < nd) { found = ie; nd = d; }
                    }
                    return found;
                });
                if (nearest == null) break;
                BlockPos itemPos = callOnClient(client, nearest::blockPosition);
                callOnClient(client, () -> { client.player.connection.sendChat("#goto " + itemPos.getX() + " " + itemPos.getY() + " " + itemPos.getZ()); return null; });
                long itemDeadline = System.currentTimeMillis() + 8000L; boolean gotIt = false;
                while (System.currentTimeMillis() < itemDeadline) {
                    Thread.sleep(500L);
                    gotIt = callOnClient(client, () -> nearest.isRemoved() || nearest.distanceToSqr(client.player) <= 2.25);
                    if (gotIt) break;
                }
                if (!gotIt) break;
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }
    }

    /** Walk N blocks via Baritone while waits stay on the queue worker. */
    private static void walkBlocksDirectional(Minecraft client, int blocks, String direction, long timeoutMs) {
        BlockPos origin = callOnClient(client, () -> client.player.blockPosition());
        int dx = 0, dz = 0;
        switch (direction.toLowerCase()) { case "north": dz=-blocks; break; case "south": dz=blocks; break; case "east": dx=blocks; break; case "west": dx=-blocks; break; default: dz=-blocks; }
        BlockPos dest = origin.offset(dx, 0, dz);
        callOnClient(client, () -> { client.player.connection.sendChat("#goto " + dest.getX() + " " + dest.getY() + " " + dest.getZ()); return null; });
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500L);
                boolean arrived = callOnClient(client, () -> { double x=client.player.getX()-dest.getX(), z=client.player.getZ()-dest.getZ(); return x*x+z*z <= 4.0; });
                if (arrived) break;
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }
    }

    /**
     * Defect 1: queue-only goto to explicit x/y/z with an arrival
     * gate (the navigate-v2 tool is fire-and-forget 'dispatched'; standing
     * cycles need to KNOW arrival before depositing). Poll loop mirrors
     * queueGetToBlock: MOVE_POLL_INTERVAL_MS sleep + marshalled distance
     * check + #stop in finally.
     */
    private static JsonObject queueGotoCoords(Minecraft client, JsonObject args) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        int z = requiredInt(args, "z");
        Vec3 startPos = callOnClient(client, () -> client.player.position());
        callOnClient(client, () -> {
            client.player.connection.sendChat("#goto " + x + " " + y + " " + z);
            return null;
        });
        long start = System.currentTimeMillis();
        long deadline = start + 60000L;
        boolean arrived = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MOVE_POLL_INTERVAL_MS);
                arrived = callOnClient(client, () -> {
                    double dx = client.player.getX() - (x + 0.5);
                    double dy = client.player.getY() - (y + 0.5);
                    double dz = client.player.getZ() - (z + 0.5);
                    return dx * dx + dy * dy + dz * dz <= 6.25; // ~2.5-block arrival radius
                });
                if (arrived) break;
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }
        Vec3 endPos = callOnClient(client, () -> client.player.position());
        double moved = Math.sqrt(
                (endPos.x - startPos.x) * (endPos.x - startPos.x)
                        + (endPos.z - startPos.z) * (endPos.z - startPos.z));
        JsonObject r = new JsonObject();
        r.addProperty("ok", arrived);
        if (!arrived) r.addProperty("reason", "arrival_timeout");
        r.addProperty("movedBlocks", Math.round(moved * 10.0) / 10.0);
        r.addProperty("message", arrived
                ? "goto-coords arrived at (" + x + "," + y + "," + z + ")"
: "goto-coords timed out en route to (" + x + "," + y + "," + z + ")");
        return r;
    }

    // ── Queue-safe process handlers (v0.19) ─────────────────────────────

    /**
     * queueGetToBlock: Navigate to the nearest target block via Baritone #goto,
     * optionally right-click on arrival. All client reads/actions go through
     * callOnClient transactions; the poll loop sleeps on the queue worker.
     */
    private static JsonObject queueGetToBlock(Minecraft client, JsonObject args) {
        String block = optionalString(args, "block", "");
        java.util.List<String> targets = new ArrayList<>();
        JsonElement filterEl = args.get("filter");
        if (filterEl != null && filterEl.isJsonArray()) {
            for (JsonElement e : filterEl.getAsJsonArray()) if (e.isJsonPrimitive()) targets.add(e.getAsString());
        }
        if (!block.isEmpty() && targets.isEmpty()) targets.add(block);
        if (targets.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false); r.addProperty("reason", "no_target");
            r.addProperty("message", "get-to-block: no target (pass `block` or `filter`)");
            return r;
        }
        int maxDistance0 = optionalInt(args, "maxDistance", 64);
        boolean rightClick = optionalBool(args, "rightClick", true);
        if (maxDistance0 < 1) maxDistance0 = 1; if (maxDistance0 > 128) maxDistance0 = 128;
        final int maxDistance = maxDistance0;

        java.util.Set<String> matchNames = new java.util.HashSet<>();
        for (String t: targets) { matchNames.add(stripNamespace(t)); matchNames.add("minecraft:" + stripNamespace(t)); }

        BlockPos origin = callOnClient(client, () -> client.player.blockPosition());
        // Scan for matching blocks on the client thread.
        java.util.List<BlockPos> found = callOnClient(client, () -> {
            ClientLevel level = client.level;
            java.util.List<BlockPos> hits = new ArrayList<>();
            for (int dx = -maxDistance; dx <= maxDistance && hits.size() < 64; dx++) {
                for (int dy = -maxDistance; dy <= maxDistance && hits.size() < 64; dy++) {
                    for (int dz = -maxDistance; dz <= maxDistance && hits.size() < 64; dz++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        String simple = simpleBlockName(level.getBlockState(pos));
                        if (matchNames.contains(simple)) hits.add(pos);
                    }
                }
            }
            return hits;
        });
        if (found.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false); r.addProperty("reason", "no_known_targets");
            r.addProperty("message", "get-to-block: no_known_targets (use explore to find some)");
            return r;
        }
        found.sort((a, b) -> Long.compare(a.distManhattan(origin), b.distManhattan(origin)));
        BlockPos target = found.get(0);
        String targetName = callOnClient(client, () -> simpleBlockName(client.level.getBlockState(target)));

        callOnClient(client, () -> { client.player.connection.sendChat("#goto " + target.getX() + " " + target.getY() + " " + target.getZ()); return null; });
        long start = System.currentTimeMillis();
        long deadline = start + 45000L;
        boolean arrived = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MOVE_POLL_INTERVAL_MS);
                arrived = callOnClient(client, () -> {
                    double dx = client.player.getX() - target.getX();
                    double dy = client.player.getY() - target.getY();
                    double dz = client.player.getZ() - target.getZ();
                    return dx * dx + dy * dy + dz * dz <= 4.0;
                });
                if (arrived) break;
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }

        boolean rightClicked = false;
        if (arrived && rightClick) {
            rightClicked = callOnClient(client, () -> {
                lookAtBlock(client, target);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5),
                                Direction.UP, target, false));
                client.player.swing(InteractionHand.MAIN_HAND);
                return true;
            });
        }

        BlockPos playerPos = callOnClient(client, () -> client.player.blockPosition());
        JsonObject r = new JsonObject();
        r.addProperty("ok", arrived);
        r.addProperty("arrived", arrived);
        r.addProperty("rightClicked", rightClicked);
        r.addProperty("target", targetName);
        r.add("position", arrived ? posJson(playerPos): posJson(target));
        r.addProperty("reason", arrived ? "reached": "timeout_or_unreachable");
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", arrived
                ? "Got to " + targetName + " at " + target.getX() + "," + target.getY() + "," + target.getZ()
                    + (rightClicked ? " (opened/used)": "")
: "get-to-block: timeout_or_unreachable");
        return r;
    }

    /**
     * queueExplore: Walk toward a far destination biased by `direction`,
     * scanning for notable blocks along the way. All client reads/actions
     * through callOnClient; poll loop on the queue worker.
     */
    private static JsonObject queueExplore(Minecraft client, JsonObject args) {
        String direction = optionalString(args, "direction", "none");
        int maxRadius = optionalInt(args, "maxRadius", 16);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 30000);
        if (maxDurationMs < 1000) maxDurationMs = 1000;
        if (maxRadius < 1) maxRadius = 1; if (maxRadius > 64) maxRadius = 64;

        BlockPos origin = callOnClient(client, () -> client.player.blockPosition());
        int distBlocks = maxRadius * EXPLORE_CHUNK_SIZE;
        int dx = 0, dz = 0;
        switch (direction) {
            case "north": dz = -distBlocks; break;
            case "south": dz = distBlocks; break;
            case "east": dx = distBlocks; break;
            case "west": dx = -distBlocks; break;
            default: dx = distBlocks; dz = distBlocks; break;
        }
        BlockPos dest = origin.offset(dx, 0, dz);
        callOnClient(client, () -> { client.player.connection.sendChat("#goto " + dest.getX() + " " + dest.getY() + " " + dest.getZ()); return null; });

        long start = System.currentTimeMillis();
        long deadline = start + maxDurationMs;
        java.util.Set<String> notablesFound = new java.util.LinkedHashSet<>();
        int chunksExplored = 0;
        final BlockPos[] lastChunk = { origin };
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(EXPLORE_POLL_INTERVAL_MS);
                callOnClient(client, () -> {
                    BlockPos cur = client.player.blockPosition();
                    if (Math.abs(cur.getX() - lastChunk[0].getX()) >= EXPLORE_CHUNK_SIZE
                            || Math.abs(cur.getZ() - lastChunk[0].getZ()) >= EXPLORE_CHUNK_SIZE) {
                        lastChunk[0] = cur;
                    }
                    scanNotablesAround(client.level, cur, 4, notablesFound);
                    return null;
                });
                // Check arrival
                boolean reached = callOnClient(client, () -> {
                    double ddx = client.player.getX() - dest.getX();
                    double ddz = client.player.getZ() - dest.getZ();
                    return ddx * ddx + ddz * ddz <= 16.0;
                });
                if (reached) break;
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }

        // chunksExplored = number of 16-block boundary crossings recorded
        chunksExplored = callOnClient(client, () -> {
            int chunkDistX = Math.abs(client.player.blockPosition().getX() - origin.getX()) / EXPLORE_CHUNK_SIZE;
            int chunkDistZ = Math.abs(client.player.blockPosition().getZ() - origin.getZ()) / EXPLORE_CHUNK_SIZE;
            return Math.max(chunkDistX, chunkDistZ);
        });

        JsonArray notableArr = new JsonArray();
        for (String n : notablesFound) notableArr.add(n);
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("chunksExplored", chunksExplored);
        r.add("notablesFound", notableArr);
        r.addProperty("reason", "explored");
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", "Explored " + chunksExplored + " chunks, " + notablesFound.size()
                + " notables (" + direction + " " + maxRadius + " chunks)");
        return r;
    }

    /**
     * queueFollowPlayer: Follow a player/mob via Baritone #follow, polling
     * distance through callOnClient. Stops when in range, target leaves,
     * or timeout.
     */
    private static JsonObject queueFollowPlayer(Minecraft client, JsonObject args) {
        String username = optionalString(args, "username", "");
        int entityId = optionalInt(args, "entityId", Integer.MIN_VALUE);
        double range0 = optionalDouble(args, "range", 3.0);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 30000);
        if (range0 < 1) range0 = 1; if (range0 > 64) range0 = 64;
        if (maxDurationMs < 1000) maxDurationMs = 1000;
        final double range = range0;

        // Find the target entity on the client thread.
        Entity[] targetHolder = new Entity[1];
        String followName = callOnClient(client, () -> {
            LocalPlayer player = client.player;
            ClientLevel level = client.level;
            Entity target = null;
            if (entityId != Integer.MIN_VALUE) {
                target = level.getEntity(entityId);
            } else if (!username.isEmpty()) {
                String needle = username.toLowerCase();
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity == player) continue;
                    net.minecraft.network.chat.Component custom = entity.getCustomName();
                    if (custom != null && custom.getString().toLowerCase().contains(needle)) {
                        target = entity; break;
                    }
                    if (entity instanceof Player && entity.getName().getString().toLowerCase().contains(needle)) {
                        target = entity; break;
                    }
                }
            }
            if (target == null) return null;
            targetHolder[0] = target;
            net.minecraft.network.chat.Component custom = target.getCustomName();
            if (custom != null && !custom.getString().isEmpty()) return custom.getString();
            if (target instanceof Player) return target.getName().getString();
            return simpleEntityName(target);
        });

        if (targetHolder[0] == null) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false); r.addProperty("reason", "no_target");
            r.addProperty("message", "follow-player: no target (pass `username` or `entityId`)");
            return r;
        }
        Entity target = targetHolder[0];
        int targetId = target.getId();

        callOnClient(client, () -> { client.player.connection.sendChat("#follow " + followName); return null; });
        long start = System.currentTimeMillis();
        long deadline = start + maxDurationMs;
        boolean inRange = false;
        String reason = "timeout";
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MOVE_POLL_INTERVAL_MS);
                int state = callOnClient(client, () -> {
                    Entity ent = client.level.getEntity(targetId);
                    if (ent == null || ent.isRemoved() || !ent.isAlive()) return 2; // target_left
                    double dist = Math.sqrt(ent.distanceToSqr(client.player));
                    return dist <= range ? 1 : 0;
                });
                if (state == 2) { reason = "target_left"; break; }
                if (state == 1) { inRange = true; reason = "in_range"; break; }
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); reason = "interrupted"; }
        finally { callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; }); }

        double finalDist = callOnClient(client, () -> {
            Entity ent = client.level.getEntity(targetId);
            if (ent == null || ent.isRemoved()) return -1.0;
            return Math.sqrt(ent.distanceToSqr(client.player));
        });
        JsonObject r = new JsonObject();
        r.addProperty("ok", inRange);
        r.addProperty("followed", followName);
        r.addProperty("reason", reason);
        r.addProperty("finalDistance", Math.round(finalDist * 10.0) / 10.0);
        r.add("position", callOnClient(client, () -> posJson(client.player.blockPosition())));
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", inRange
                ? "Followed " + followName + " (in_range at " + Math.round(finalDist * 10.0) / 10.0 + " blocks)"
: "follow-player: " + reason);
        return r;
    }

    /**
     * queueGuardArea: Hold a post, melee-attack hostiles within the radius,
     * return to post when clear. All client reads/actions through callOnClient;
     * poll loop on the queue worker.
     */
    private static JsonObject queueGuardArea(Minecraft client, JsonObject args) {
        int cx = optionalInt(args, "x", Integer.MIN_VALUE);
        int cy = optionalInt(args, "y", Integer.MIN_VALUE);
        int cz = optionalInt(args, "z", Integer.MIN_VALUE);
        double radius0 = optionalDouble(args, "radius", 10.0);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 30000);
        if (radius0 < 1) radius0 = 1; if (radius0 > 64) radius0 = 64;
        if (maxDurationMs < 1000) maxDurationMs = 1000;
        final double radius = radius0;

        BlockPos post = (cx != Integer.MIN_VALUE && cy != Integer.MIN_VALUE && cz != Integer.MIN_VALUE)
                ? new BlockPos(cx, cy, cz)
                : callOnClient(client, () -> client.player.blockPosition());

        // Equip best weapon on the client thread.
        callOnClient(client, () -> { equipBestWeapon(client); return null; });

        // Enable KillAura for the whole guard — its tick handler attacks any
        // hostile that enters range while we hold the post or chase (mirrors
        // queueHuntHostile, v0.19.1). Direct melee below stays as fallback for
        // when Meteor/kill-aura is absent or targeting something else.
        toggleMeteorModuleByName(client, "kill-aura", true);

        long start = System.currentTimeMillis();
        long deadline = start + maxDurationMs;
        int threatsEngaged = 0;
        int attacks = 0;
        boolean returnedToPost = false;
        String lastTarget = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                // Find nearest hostile within radius of the post — on the client thread.
                Entity[] threatHolder = new Entity[1];
                double[] threatDistArr = { Double.MAX_VALUE };
                String[] threatName = { null };
                callOnClient(client, () -> {
                    LocalPlayer player = client.player;
                    ClientLevel level = client.level;
                    Entity nearest = null; double nearestDist = Double.MAX_VALUE;
                    for (Entity entity : level.entitiesForRendering()) {
                        if (!(entity instanceof Enemy)) continue;
                        double pdx = entity.getX() - post.getX();
                        double pdy = entity.getY() - post.getY();
                        double pdz = entity.getZ() - post.getZ();
                        double fromPost = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
                        if (fromPost > radius) continue;
                        double dToBot = entity.distanceToSqr(player);
                        if (dToBot < nearestDist) { nearest = entity; nearestDist = dToBot; }
                    }
                    if (nearest != null) {
                        threatHolder[0] = nearest;
                        threatDistArr[0] = nearestDist;
                        threatName[0] = simpleEntityName(nearest);
                    }
                    return null;
                });

                Entity threat = threatHolder[0];
                if (threat != null) {
                    threatsEngaged++;
                    lastTarget = threatName[0];
                    int threatId = threat.getId();
                    // Path toward the threat.
                    callOnClient(client, () -> {
                        Entity ent = client.level.getEntity(threatId);
                        if (ent != null && ent.isAlive())
                            client.player.connection.sendChat("#goto " + (int) ent.getX() + " " + (int) ent.getY() + " " + (int) ent.getZ());
                        return null;
                    });
                    // Attack sub-loop: chase and hit until dead/gone or sub-deadline.
                    long threatDeadline = System.currentTimeMillis() + 10000;
                    while (System.currentTimeMillis() < threatDeadline) {
                        int state = callOnClient(client, () -> {
                            Entity ent = client.level.getEntity(threatId);
                            if (ent == null || ent.isRemoved() || !ent.isAlive()) return 2; // dead
                            double dist = Math.sqrt(ent.distanceToSqr(client.player));
                            if (dist <= 3.5) {
                                client.player.connection.sendChat("#stop");
                                lookAtEntity(client, ent);
                                client.gameMode.attack(client.player, ent);
                                client.player.swing(InteractionHand.MAIN_HAND);
                                return 1; // attacked
                            }
                            return 0; // still chasing
                        });
                        if (state == 2) break;
                        Thread.sleep(state == 1 ? 500L : GUARD_POLL_INTERVAL_MS);
                    }
                    callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
                    attacks++;
                } else {
                    // No threat — return to post if far.
                    boolean atPost = callOnClient(client, () -> {
                        double pdx = client.player.getX() - post.getX();
                        double pdz = client.player.getZ() - post.getZ();
                        return pdx * pdx + pdz * pdz <= 4.0;
                    });
                    if (atPost) {
                        returnedToPost = true;
                        callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
                    } else {
                        callOnClient(client, () -> { client.player.connection.sendChat("#goto " + post.getX() + " " + post.getY() + " " + post.getZ()); return null; });
                    }
                    Thread.sleep(GUARD_POLL_INTERVAL_MS);
                }
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally {
            // Always disable KillAura on exit — don't leave it active between
            // tasks (same cleanup discipline as queueHuntHostile).
            toggleMeteorModuleByName(client, "kill-aura", false);
            callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
        }

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("killAuraUsed", true);
        r.addProperty("threatsEngaged", threatsEngaged);
        r.addProperty("attacks", attacks);
        r.addProperty("returnedToPost", returnedToPost);
        if (lastTarget != null) r.addProperty("lastTarget", lastTarget);
        r.add("position", callOnClient(client, () -> posJson(client.player.blockPosition())));
        r.addProperty("reason", "duration_elapsed");
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", "Guarded: " + threatsEngaged + " threat(s), " + attacks + " attack(s)"
                + (lastTarget != null ? " (last: " + lastTarget + ")": ""));
        return r;
    }

    // ── Queue-safe mine/build handlers ──────────────────────────

    /**
     * queueMineBlocks: Mine `count` of a target block via Baritone #mine,
     * polling inventory count delta via callOnClient until the requested
     * count is acquired or timeout. Supports mine-blocks and chop-tree modes.
     * Strip-mine mode uses #goto a far point at yLevel, digging along the way.
     * All client reads/actions through callOnClient; poll loop on the worker.
     */
    private static JsonObject queueMineBlocks(Minecraft client, JsonObject args) {
        String block = optionalString(args, "block", "");
        java.util.List<String> targets = new java.util.ArrayList<>();
        JsonElement filterEl = args.get("filter");
        if (filterEl != null && filterEl.isJsonArray()) {
            for (JsonElement e : filterEl.getAsJsonArray())
                if (e.isJsonPrimitive()) targets.add(e.getAsString());
        }
        if (!block.isEmpty() && targets.isEmpty()) targets.add(block);
        if (targets.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "no_target");
            r.addProperty("message", "mine-blocks: no target (pass `block` or `filter`)");
            return r;
        }
        String mode = optionalString(args, "mode", "mine-blocks");
        int count0 = optionalInt(args, "count", 1);
        if (count0 < 1) count0 = 1;
        final int count = count0;
        int maxDistance0 = optionalInt(args, "maxDistance", 64);
        if (maxDistance0 < 1) maxDistance0 = 1;
        if (maxDistance0 > 128) maxDistance0 = 128;
        final int maxDistance = maxDistance0;
        int torchEvery = optionalInt(args, "torchEvery", 0);
        int yLevel = optionalInt(args, "yLevel", Integer.MIN_VALUE);
        int length = optionalInt(args, "length", 32);

        // Normalize target names (strip namespace for matching).
        java.util.List<String> simpleTargets = new java.util.ArrayList<>();
        for (String t : targets) simpleTargets.add(stripNamespace(t));

        // Snapshot inventory before mining to compute the delta.
        java.util.Map<String, Integer> before = callOnClient(client, () -> inventoryCounts(client.player));
        // Count how many of the target items we already have.
        int haveAlready = 0;
        for (String t : simpleTargets) haveAlready += before.getOrDefault(t, 0);
        // Also check raw variants (e.g. deepslate_iron_ore drops iron_ore? No —
        // deepslate_iron_ore drops raw_iron, iron_ore drops raw_iron). We count
        // the item drops, not the block. But the target names are block names.
        // Baritone #mine handles the tool selection and block breaking; we just
        // check if we acquired the items. The item names may differ from block
        // names (e.g. iron_ore block → raw_iron item). So we use a broader
        // check: count ALL items that increased.
        // Actually, for simplicity and correctness, we track total inventory
        // count delta for the target item families. For logs, the block name
        // matches the item name (oak_log → oak_log). For ores, the item name
        // differs (iron_ore → raw_iron). Baritone #mine by block name handles
        // the mining; we just need to detect when we've acquired enough.
        // The simplest reliable approach: send #mine, wait for Baritone to finish
        // (it stops when it can't find more), then check the inventory delta.
        // But Baritone #mine doesn't stop automatically — it keeps looking.
        // So we poll: check if any target-related items increased by `count`.
        // For item-name matching, we check both the block name and common
        // drop names. For logs, block name = item name. For ores, we check
        // a mapping. But this is complex. Simpler: just count total inventory
        // size increase — if we gained `count` items, we're done. This works
        // because mining adds items to inventory.
        int totalBefore = 0;
        for (int v : before.values()) totalBefore += v;

        long start = System.currentTimeMillis();
        long deadline = start + 60000L; // 60s max for mining
        int mined = 0;
        int blacklisted = 0;
        String reason = "timeout";
        boolean torchPlaced = false;

        if (mode.equals("strip-mine")) {
            // Strip-mine: navigate to a far point at yLevel, digging along the way.
            // Simple approach: #goto a point `length` blocks away at yLevel.
            BlockPos origin = callOnClient(client, () -> client.player.blockPosition());
            int targetY = (yLevel != Integer.MIN_VALUE) ? yLevel : origin.getY();
            // Dig in a straight line (north by default) at the target Y.
            BlockPos dest = origin.offset(0, targetY - origin.getY(), -length);
            callOnClient(client, () -> {
                client.player.connection.sendChat("#goto " + dest.getX() + " " + dest.getY() + " " + dest.getZ());
                return null;
            });
            try {
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(MOVE_POLL_INTERVAL_MS);
                    boolean arrived = callOnClient(client, () -> {
                        double dx = client.player.getX() - dest.getX();
                        double dz = client.player.getZ() - dest.getZ();
                        return dx * dx + dz * dz <= 4.0;
                    });
                    // Place torches every torchEvery blocks if dark
                    if (torchEvery > 0 && !torchPlaced) {
                        // Simplified: place one torch at the start
                        // (full torch-every-N would need distance tracking)
                    }
                    if (arrived) { reason = "done"; break; }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                reason = "interrupted";
            } finally {
                callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
            }
            // Count what we mined (any blocks broken along the path)
            java.util.Map<String, Integer> after = callOnClient(client, () -> inventoryCounts(client.player));
            int totalAfter = 0;
            for (int v : after.values()) totalAfter += v;
            mined = Math.max(0, totalAfter - totalBefore);
            // For strip-mine, success is reaching the destination
            JsonObject r = new JsonObject();
            r.addProperty("ok", reason.equals("done"));
            r.addProperty("mode", mode);
            r.addProperty("mined", mined);
            JsonArray tArr = new JsonArray();
            for (String t : simpleTargets) tArr.add(t);
            r.add("target", tArr);
            r.addProperty("requested", count);
            r.addProperty("reason", reason);
            r.addProperty("blacklisted", 0);
            r.addProperty("durationMs", System.currentTimeMillis() - start);
            r.addProperty("message", "strip-mine " + (reason.equals("done") ? "complete": reason)
                    + ", mined " + mined + " blocks along path");
            return r;
        }

        // mine-blocks and chop-tree modes: use Baritone #mine
        // For chop-tree, Baritone #mine with log names works (it mines connected logs).
        // Build the #mine command with all target block names.
        String mineCmd = "#mine " + String.join(" ", simpleTargets);
        callOnClient(client, () -> {
            client.player.connection.sendChat(mineCmd);
            return null;
        });

        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MINE_POLL_INTERVAL_MS);
                // Check inventory: did we gain `count` items?
                int[] snapshot = callOnClient(client, () -> {
                    java.util.Map<String, Integer> inv = inventoryCounts(client.player);
                    int total = 0;
                    for (int v : inv.values()) total += v;
                    // Count specific target items
                    int targetItems = 0;
                    for (String t : simpleTargets) targetItems += inv.getOrDefault(t, 0);
                    return new int[]{ total, targetItems };
                });
                int totalNow = snapshot[0];
                int targetNow = snapshot[1];
                // Check if we've acquired enough. Use target-specific count first,
                // fall back to total inventory delta (for ores where item name differs).
                int targetGained = targetNow - before.getOrDefault(simpleTargets.get(0), 0);
                // For multi-target, sum all
                targetGained = 0;
                for (String t : simpleTargets) {
                    targetGained += (targetNow - before.getOrDefault(t, 0));
                }
                // Simpler: total inventory delta (works for all block types)
                int totalGained = totalNow - totalBefore;
                // Use the larger of the two — for logs, targetGained is accurate;
                // for ores, totalGained captures the raw_iron etc.
                mined = Math.max(targetGained, totalGained);
                if (mined >= count) {
                    reason = "done";
                    break;
                }
                // Check if Baritone has stopped (no more targets found)
                // We can't easily detect this, so we rely on the timeout.
                // But if inventory hasn't changed for a long time, we concede.
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } finally {
            callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
        }

        // Collect any dropped items near the bot.
        BlockPos playerPos = callOnClient(client, () -> client.player.blockPosition());
        collectDropsAt(client, playerPos, 8.0, 10000L);

        // Re-check inventory after drop collection.
        int[] finalSnapshot = callOnClient(client, () -> {
            java.util.Map<String, Integer> inv = inventoryCounts(client.player);
            int total = 0;
            for (int v : inv.values()) total += v;
            int targetItems = 0;
            for (String t : simpleTargets) targetItems += inv.getOrDefault(t, 0);
            return new int[]{ total, targetItems };
        });
        int finalTotal = finalSnapshot[0];
        int finalTarget = finalSnapshot[1];
        int finalTargetGained = 0;
        for (String t : simpleTargets) {
            finalTargetGained += (finalTarget - before.getOrDefault(t, 0));
        }
        // The pathfinder consumes mined material as scaffold/
        // bridge blocks, so both inventory deltas can go NEGATIVE (observed
        // -118 live). Floor each at 0 before the max() so `mined` can never
        // be negative, and surface the consumption as a lower bound in the
        // result instead of silently swallowing it.
        int finalTotalGained = Math.max(0, finalTotal - totalBefore);
        int clampedTargetGained = Math.max(0, finalTargetGained);
        int consumedAsScaffold = Math.max(0, totalBefore - finalTotal);
        mined = Math.max(clampedTargetGained, finalTotalGained);
        if (mined >= count) reason = "done";

        JsonObject r = new JsonObject();
        r.addProperty("ok", mined >= count);
        r.addProperty("mode", mode);
        r.addProperty("mined", mined);
        // Lower bound on mined material consumed as scaffold/
        // bridge blocks during the run (0 when nothing was consumed).
        r.addProperty("consumedAsScaffold", consumedAsScaffold);
        JsonArray tArr2 = new JsonArray();
        for (String t : simpleTargets) tArr2.add(t);
        r.add("target", tArr2);
        r.addProperty("requested", count);
        r.addProperty("reason", mined >= count ? "done": reason);
        r.addProperty("blacklisted", blacklisted);
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", mined >= count
                ? "Mined " + mined + "/" + count + " " + String.join(",", simpleTargets)
: "mine-blocks partial: " + mined + "/" + count + " — " + reason);
        return r;
    }

    /**
     * queueBuildStructure: Build a structure from a blueprint by navigating
     * to each block position and placing via callOnClient. Per-pass re-diff:
     * after each pass, re-check which blocks still need placing. Reports
     * shortfall for missing materials by family. All client reads/actions
     * through callOnClient; poll loop on the queue worker.
     */
    private static JsonObject queueBuildStructure(Minecraft client, JsonObject args) {
        long start = System.currentTimeMillis();

        // Parse the blueprint — may arrive as a JSON string or an object.
        JsonElement bpEl = args.get("blueprint");
        if (bpEl == null) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "no_blueprint");
            r.addProperty("message", "build-structure: no 'blueprint' provided");
            return r;
        }
        JsonObject blueprint = null;
        if (bpEl.isJsonPrimitive() && bpEl.getAsJsonPrimitive().isString()) {
            try { blueprint = com.google.gson.JsonParser.parseString(bpEl.getAsString()).getAsJsonObject(); }
            catch (Exception e) {
                JsonObject r = new JsonObject();
                r.addProperty("ok", false);
                r.addProperty("reason", "invalid_blueprint");
                r.addProperty("message", "build-structure: blueprint string is not valid JSON: " + e.getMessage());
                return r;
            }
        } else if (bpEl.isJsonObject()) {
            blueprint = bpEl.getAsJsonObject();
        }
        if (blueprint == null || !blueprint.has("blocks") || !blueprint.get("blocks").isJsonArray()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "invalid_blueprint");
            r.addProperty("message", "build-structure: blueprint must have a 'blocks' array");
            return r;
        }

        // Parse origin (default: bot position).
        BlockPos defaultOrigin = callOnClient(client, () -> client.player.blockPosition());
        int ox = optionalInt(args, "x", defaultOrigin.getX());
        int oy = optionalInt(args, "y", defaultOrigin.getY());
        int oz = optionalInt(args, "z", defaultOrigin.getZ());
        JsonElement originEl = args.get("origin");
        if (originEl != null && originEl.isJsonObject()) {
            JsonObject oo = originEl.getAsJsonObject();
            if (oo.has("x")) ox = oo.get("x").getAsInt();
            if (oo.has("y")) oy = oo.get("y").getAsInt();
            if (oo.has("z")) oz = oo.get("z").getAsInt();
        }
        final int originX = ox, originY = oy, originZ = oz;

        int maxPasses0 = optionalInt(args, "maxPasses", 8);
        if (maxPasses0 < 1) maxPasses0 = 1;
        if (maxPasses0 > 32) maxPasses0 = 32;
        final int maxPasses = maxPasses0;

        // Collect all block specs from the blueprint.
        java.util.List<JsonObject> blockSpecs = new java.util.ArrayList<>();
        for (JsonElement be: blueprint.getAsJsonArray("blocks")) {
            if (be.isJsonObject()) blockSpecs.add(be.getAsJsonObject());
        }
        if (blockSpecs.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", "empty_blueprint");
            r.addProperty("message", "build-structure: blueprint has no blocks");
            return r;
        }

        // Compute absolute positions and resolve block families.
        // Each block spec: {x, y, z, block} where x/y/z are relative to origin.
        // Check shortfall: for each unique block, check if inventory has enough
        // (summed across family members).
        java.util.Map<String, Integer> requiredByFamily = new java.util.HashMap<>();
        for (JsonObject bs : blockSpecs) {
            String blockName = bs.has("block") ? bs.get("block").getAsString(): "anything";
            if (blockName.equals("anything")) continue;
            String family = describeFamily(blockName);
            String key = family.isEmpty() ? stripNamespace(blockName) : family;
            requiredByFamily.merge(key, 1, Integer::sum);
        }

        // Check inventory for shortfall.
        java.util.Map<String, Integer> inv = callOnClient(client, () -> inventoryCounts(client.player));
        JsonObject shortfall = new JsonObject();
        for (java.util.Map.Entry<String, Integer> req : requiredByFamily.entrySet()) {
            String key = req.getKey();
            int needed = req.getValue();
            int have = 0;
            // Check if key is a family name
            java.util.List<String> members = familyMembers(key);
            if (!members.isEmpty()) {
                for (String m : members) have += inv.getOrDefault(m, 0);
            } else {
                have = inv.getOrDefault(key, 0);
            }
            if (have < needed) {
                shortfall.addProperty(key, needed - have);
            }
        }

        // If there's a shortfall, don't attempt to build — return immediately.
        int totalBlocks = blockSpecs.size();
        if (shortfall.size() > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("pass", 0);
            r.addProperty("placed", 0);
            r.addProperty("broken", 0);
            r.addProperty("remaining", totalBlocks);
            r.add("shortfall", shortfall);
            r.addProperty("reason", "shortfall");
            r.addProperty("durationMs", System.currentTimeMillis() - start);
            r.addProperty("message", "build-structure: insufficient materials — shortfall: " + shortfall.toString());
            return r;
        }

        // Build with per-pass re-diff.
        int placed = 0;
        int broken = 0;
        int passNum = 0;
        String reason = "done";
        boolean allPlaced = false;

        for (passNum = 1; passNum <= maxPasses; passNum++) {
            // Re-diff: find blocks that still need placing.
            java.util.List<JsonObject> toPlace = new java.util.ArrayList<>();
            java.util.List<BlockPos> toPlacePos = new java.util.ArrayList<>();
            for (JsonObject bs : blockSpecs) {
                int rx = bs.has("x") ? bs.get("x").getAsInt(): 0;
                int ry = bs.has("y") ? bs.get("y").getAsInt(): 0;
                int rz = bs.has("z") ? bs.get("z").getAsInt(): 0;
                BlockPos absPos = new BlockPos(originX + rx, originY + ry, originZ + rz);
                String desiredBlock = bs.has("block") ? bs.get("block").getAsString(): "anything";
                if (desiredBlock.equals("anything")) continue; // skip wildcard cells
                // Check if the block is already correct (or a family member).
                String current = callOnClient(client, () -> simpleBlockName(client.level.getBlockState(absPos)));
                if (current.equals(stripNamespace(desiredBlock))) continue;
                // Check family match
                String family = describeFamily(desiredBlock);
                if (!family.isEmpty() && current.endsWith("_" + family.replace("s", ""))) continue;
                // Also check if family member is already there
                if (!family.isEmpty()) {
                    java.util.List<String> members = familyMembers(family);
                    boolean familyMatch = false;
                    for (String m : members) {
                        if (current.equals(m)) { familyMatch = true; break; }
                    }
                    if (familyMatch) continue;
                }
                toPlace.add(bs);
                toPlacePos.add(absPos);
            }

            if (toPlace.isEmpty()) {
                allPlaced = true;
                reason = "done";
                break;
            }

            // Sort by distance from current position (reach-first).
            BlockPos curPos = callOnClient(client, () -> client.player.blockPosition());
            java.util.List<Integer> order = new java.util.ArrayList<>();
            for (int i = 0; i < toPlace.size(); i++) order.add(i);
            order.sort((a, b) -> Long.compare(
                    toPlacePos.get(a).distManhattan(curPos),
                    toPlacePos.get(b).distManhattan(curPos)));

            int passPlaced = 0;
            for (int idx : order) {
                JsonObject bs = toPlace.get(idx);
                BlockPos absPos = toPlacePos.get(idx);
                String desiredBlock = bs.has("block") ? bs.get("block").getAsString(): "anything";
                if (desiredBlock.equals("anything")) continue;

                // Check if already placed (may have been placed in this pass).
                String current = callOnClient(client, () -> simpleBlockName(client.level.getBlockState(absPos)));
                boolean isReplaceable = current.equals("air") || current.equals("cave_air")
                        || callOnClient(client, () -> client.level.getBlockState(absPos).canBeReplaced());
                if (!isReplaceable) {
                    // Check if it matches the desired block or family
                    String family = describeFamily(desiredBlock);
                    if (current.equals(stripNamespace(desiredBlock))) { passPlaced++; continue; }
                    if (!family.isEmpty()) {
                        boolean familyMatch = false;
                        for (String m : familyMembers(family)) {
                            if (current.equals(m)) { familyMatch = true; break; }
                        }
                        if (familyMatch) { passPlaced++; continue; }
                    }
                    // Block occupied by something else — skip for now
                    continue;
                }

                // Navigate near the target position if not within reach.
                double distToTarget = callOnClient(client, () -> {
                    double dx = client.player.getX() - (absPos.getX() + 0.5);
                    double dy = client.player.getY() - (absPos.getY() + 0.5);
                    double dz = client.player.getZ() - (absPos.getZ() + 0.5);
                    return Math.sqrt(dx * dx + dy * dy + dz * dz);
                });
                if (distToTarget > 4.5) {
                    // Navigate to a standable position adjacent to the target.
                    // Try to stand at the target position itself (Baritone will
                    // find a path to stand there, and we place from there).
                    BlockPos standPos = findStandableNear(client, absPos, 3);
                    BlockPos navTarget = (standPos != null) ? standPos : absPos;
                    callOnClient(client, () -> {
                        client.player.connection.sendChat("#goto " + navTarget.getX() + " " + navTarget.getY() + " " + navTarget.getZ());
                        return null;
                    });
                    long navDeadline = System.currentTimeMillis() + 20000L;
                    boolean arrived = false;
                    try {
                        while (System.currentTimeMillis() < navDeadline) {
                            Thread.sleep(MOVE_POLL_INTERVAL_MS);
                            arrived = callOnClient(client, () -> {
                                double dx = client.player.getX() - (navTarget.getX() + 0.5);
                                double dy = client.player.getY() - (navTarget.getY() + 0.5);
                                double dz = client.player.getZ() - (navTarget.getZ() + 0.5);
                                return dx * dx + dy * dy + dz * dz <= 9.0;
                            });
                            if (arrived) break;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
                    if (!arrived) continue; // skip this block, try next pass
                }

                // Place the block via callOnClient.
                JsonObject placeArgs = new JsonObject();
                placeArgs.addProperty("x", absPos.getX());
                placeArgs.addProperty("y", absPos.getY());
                placeArgs.addProperty("z", absPos.getZ());
                placeArgs.addProperty("block", desiredBlock);
                String fam = describeFamily(desiredBlock);
                if (!fam.isEmpty()) placeArgs.addProperty("family", fam);
                // Determine face direction: place against the block below.
                placeArgs.addProperty("faceDirection", "up");

                JsonObject placeResult = callOnClient(client, () -> placeBlock(client, placeArgs));
                boolean wasPlaced = placeResult.has("ok") && placeResult.get("ok").getAsBoolean();
                if (wasPlaced) {
                    passPlaced++;
                }
            }
            placed += passPlaced;

            if (passPlaced == 0) {
                // No progress this pass — stop.
                reason = shortfall.size() > 0 ? "shortfall": "max_passes";
                break;
            }
        }

        if (!allPlaced && passNum >= maxPasses) reason = "max_passes";

        // Count remaining.
        int remaining = 0;
        for (JsonObject bs : blockSpecs) {
            String desiredBlock = bs.has("block") ? bs.get("block").getAsString(): "anything";
            if (desiredBlock.equals("anything")) continue;
            int rx = bs.has("x") ? bs.get("x").getAsInt(): 0;
            int ry = bs.has("y") ? bs.get("y").getAsInt(): 0;
            int rz = bs.has("z") ? bs.get("z").getAsInt(): 0;
            BlockPos absPos = new BlockPos(originX + rx, originY + ry, originZ + rz);
            String current = callOnClient(client, () -> simpleBlockName(client.level.getBlockState(absPos)));
            if (current.equals(stripNamespace(desiredBlock))) continue;
            String family = describeFamily(desiredBlock);
            if (!family.isEmpty()) {
                boolean familyMatch = false;
                for (String m : familyMembers(family)) {
                    if (current.equals(m)) { familyMatch = true; break; }
                }
                if (familyMatch) continue;
            }
            remaining++;
        }

        JsonObject r = new JsonObject();
        r.addProperty("ok", remaining == 0);
        r.addProperty("pass", passNum);
        r.addProperty("placed", placed);
        r.addProperty("broken", broken);
        r.addProperty("remaining", remaining);
        r.add("shortfall", shortfall);
        r.addProperty("reason", remaining == 0 ? "done": reason);
        r.addProperty("durationMs", System.currentTimeMillis() - start);
        r.addProperty("message", remaining == 0
                ? "Built " + placed + " block(s) in " + passNum + " pass(es)"
: "build-structure partial: " + placed + " placed, " + remaining + " remaining — " + reason
                        + (shortfall.size() > 0 ? " (shortfall: " + shortfall.toString() + ")": ""));
        return r;
    }

    /** Find a standable position near the given target (for building). */
    private static BlockPos findStandableNear(Minecraft client, BlockPos target, int radius) {
        ClientLevel level = client.level;
        // Check positions adjacent to the target at the same Y or one below.
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    BlockPos pos = target.offset(dx, dy, dz);
                    // Standable: feet position is air, block below is solid.
                    BlockState feet = level.getBlockState(pos);
                    BlockState head = level.getBlockState(pos.above());
                    BlockState floor = level.getBlockState(pos.below());
                    if ((feet.isAir() || feet.canBeReplaced())
                            && (head.isAir() || head.canBeReplaced())
                            && !floor.isAir() && !floor.canBeReplaced()) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }


    /** Enable/disable a Meteor module by name (best-effort, swallows errors). */
    private static void toggleMeteorModuleByName(Minecraft client, String module, boolean enable) {
        JsonObject tArgs = new JsonObject();
        tArgs.addProperty("module", module);
        tArgs.addProperty("action", enable ? "enable": "disable");
        try {
            toggleMeteorModule(client, tArgs);
        } catch (Throwable ignored) {
            // Best-effort; hunt-hostile continues even if KillAura toggle fails.
        }
    }

    /**
     * get-agent-snapshot: Combined fast sensing. One call collapses position +
     * vitals + world-time + weather + inventory + nearby entities + movement
     * telemetry + warnings. All
     * sub-reads use the same APIs as the existing Tier A-H handlers.
     */
    private static JsonObject getAgentSnapshot(Minecraft client, JsonObject args) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        String detail = optionalString(args, "detail", "fast");
        int mapRadius = optionalInt(args, "mapRadius", 0);
        boolean includeEntities = optionalBool(args, "includeEntities", true);
        boolean includeInventory = optionalBool(args, "includeInventory", true);
        boolean includeMovement = optionalBool(args, "includeMovement", true);

        BlockPos bp = player.blockPosition();
        JsonObject position = new JsonObject();
        position.addProperty("x", bp.getX());
        position.addProperty("y", bp.getY());
        position.addProperty("z", bp.getZ());

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
        snapshot.add("position", position);
        Vec3 vel = player.getDeltaMovement();
        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", Math.round(vel.x * 100.0) / 100.0);
        velocity.addProperty("y", Math.round(vel.y * 100.0) / 100.0);
        velocity.addProperty("z", Math.round(vel.z * 100.0) / 100.0);
        snapshot.add("velocity", velocity);
        snapshot.addProperty("onGround", player.onGround());
        snapshot.addProperty("health", player.getHealth());
        snapshot.addProperty("food", player.getFoodData().getFoodLevel());
        snapshot.addProperty("oxygen", player.getAirSupply());
        long dayTime = level.getOverworldClockTime();
        long timeOfDay = dayTime % 24000L;
        snapshot.addProperty("timeOfDay", timeOfDay);
        snapshot.addProperty("isNight", timeOfDay >= 13000 && timeOfDay < 23000);
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        snapshot.addProperty("weather", thundering ? "thunder": (raining ? "rain": "clear"));

        if (includeInventory) {
            Inventory inv = player.getInventory();
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                String simple = name.contains(":") ? name.substring(name.indexOf(':') + 1): name;
                counts.merge(simple, stack.getCount(), Integer::sum);
            }
            JsonObject inventory = new JsonObject();
            for (var e : counts.entrySet()) inventory.addProperty(e.getKey(), e.getValue());
            snapshot.add("inventory", inventory);
            JsonObject equipped = new JsonObject();
            equipped.addProperty("mainHand", simpleItemName(inv.getItem(inv.getSelectedSlot())));
            equipped.addProperty("helmet", simpleItemName(inv.getItem(39)));
            equipped.addProperty("chestplate", simpleItemName(inv.getItem(38)));
            equipped.addProperty("leggings", simpleItemName(inv.getItem(37)));
            equipped.addProperty("boots", simpleItemName(inv.getItem(36)));
            snapshot.add("equipment", equipped);
        }

        if (includeEntities) {
            java.util.List<JsonObject> hostiles = new ArrayList<>();
            java.util.List<JsonObject> animals = new ArrayList<>();
            java.util.List<JsonObject> players = new ArrayList<>();
            int dropCount = 0;
            java.util.List<JsonObject> dropSamples = new ArrayList<>();
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == player) continue;
                double dist = Math.sqrt(entity.distanceToSqr(player));
                if (dist > 16) continue;
                if (entity instanceof ItemEntity itemEntity) {
                    dropCount++;
                    if (dropSamples.size() < 8) {
                        JsonObject d = new JsonObject();
                        d.addProperty("name", simpleItemName(itemEntity.getItem()));
                        d.addProperty("count", itemEntity.getItem().getCount());
                        d.addProperty("distance", Math.round(dist * 10.0) / 10.0);
                        dropSamples.add(d);
                    }
                    continue;
                }
                if (entity instanceof Player) {
                    if (players.size() < 8) players.add(describeEntityBrief(entity, dist));
                    continue;
                }
                if (entity instanceof Enemy) {
                    if (hostiles.size() < 8) hostiles.add(describeEntityBrief(entity, dist));
                } else if (entity instanceof LivingEntity) {
                    if (animals.size() < 8) animals.add(describeEntityBrief(entity, dist));
                }
            }
            JsonObject nearby = new JsonObject();
            nearby.add("hostiles", toJsonArray(hostiles));
            nearby.add("animals", toJsonArray(animals));
            nearby.add("players", toJsonArray(players));
            nearby.addProperty("drops", dropCount);
            nearby.add("dropSamples", toJsonArray(dropSamples));
            snapshot.add("nearby", nearby);
        }

        if (includeMovement) {
            // No operation registry in the Fabric body; report idle. The legacy
            // get-current-action owns the real registry telemetry.
            JsonObject movement = new JsonObject();
            movement.addProperty("active", false);
            movement.addProperty("elapsedMs", 0);
            snapshot.add("movement", movement);
        }

        java.util.List<String> warnings = new ArrayList<>();
        if (player.getHealth() <= 6) warnings.add("CRITICAL_HEALTH");
        else if (player.getHealth() <= 12) warnings.add("low_health");
        if (player.getFoodData().getFoodLevel() <= 6) warnings.add("very_hungry");
        if (player.getAirSupply() < 12) warnings.add("low_oxygen");
        Entity nearestHostile = resolveAttackTarget(level, player, "nearest_hostile");
        if (nearestHostile != null && nearestHostile.distanceToSqr(player) <= 36) {
            warnings.add("hostile_within_6:" + simpleEntityName(nearestHostile));
        }
        JsonArray warnArr = new JsonArray();
        for (String w : warnings) warnArr.add(w);
        snapshot.add("warnings", warnArr);

        // (item 4 increment 1): persistent memory summary — one boot
        // call answers "what do I know" (base/chest POIs, mob notes, notes).
        try {
            snapshot.add("memory", memorySummaryWithServer());
        } catch (Throwable t) {
            // Memory is additive telemetry; never fail the snapshot over it.
        }

        if (detail.equals("full") && mapRadius > 0) {
            java.util.Map<String, Integer> notable = new java.util.LinkedHashMap<>();
            java.util.List<JsonObject> hazards = new ArrayList<>();
            for (int dx = -mapRadius; dx <= mapRadius; dx++) {
                for (int dz = -mapRadius; dz <= mapRadius; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = bp.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(p);
                        String simple = simpleBlockName(state);
                        if (simple.equals("air") || simple.equals("cave_air")) continue;
                        if (HAZARD_BLOCK_NAMES.contains(simple) && hazards.size() < 20) {
                            JsonObject h = new JsonObject();
                            h.addProperty("name", simple);
                            h.addProperty("x", p.getX()); h.addProperty("y", p.getY()); h.addProperty("z", p.getZ());
                            hazards.add(h);
                        }
                        if (NOTABLE_ORE_NAMES.contains(simple) || NOTABLE_LOG_NAMES.contains(simple) || simple.equals("water")) {
                            notable.merge(simple, 1, Integer::sum);
                        }
                    }
                }
            }
            JsonArray notableArr = new JsonArray();
            for (var e : notable.entrySet()) {
                JsonObject n = new JsonObject(); n.addProperty("name", e.getKey()); n.addProperty("count", e.getValue());
                notableArr.add(n);
            }
            snapshot.add("notableBlocks", notableArr);
            snapshot.add("hazards", toJsonArray(hazards));
        }

        snapshot.addProperty("message", "Snapshot: at (" + bp.getX() + "," + bp.getY() + "," + bp.getZ()
                + ") hp" + player.getHealth() + "/food" + player.getFoodData().getFoodLevel()
                + "/o2" + player.getAirSupply()
                + (warnings.isEmpty() ? "": " ⚠ " + String.join(",", warnings)));
        return snapshot;
    }

    /**
     * The MemoryStore for the CURRENT server (copy-on-first-connect
     * per-server file), or the global store when disconnected/singleplayer.
     * Policy rows never come through here (policy-save/forget use the
     * global store directly — they are server-agnostic rails).
     */
    private static MemoryStore storeForCurrentServer() {
        return HyFuseClient.brainStore().storeFor(HyFuseClient.currentServerId());
    }

    /**
     * get-playbook: the server-agnostic strategy layer —
     * config/hyfuse/INTELLIGENCE.md (runtime read, user-edited between
     * releases) or the packaged default when absent. Call FIRST in a fresh
     * chat, alongside get-agent-snapshot, to prime context.
     */
    private static JsonObject getPlaybook(Minecraft client, JsonObject args) {
        BrainStore brain = HyFuseClient.brainStore();
        String content = brain.readPlaybook();
        JsonObject result = new JsonObject();
        if (content == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "playbook unavailable (no config/hyfuse/INTELLIGENCE.md "
                    + "and no packaged default — report this)");
            return result;
        }
        result.addProperty("ok", true);
        result.addProperty("source", brain.playbookFromConfig() ? "config": "default");
        result.addProperty("length", content.length());
        result.addProperty("content", content);
        result.addProperty("message", "Playbook (" + (brain.playbookFromConfig() ? "user config": "packaged default")
                + ", " + content.length() + " chars) — read it, then get-agent-snapshot");
        return result;
    }

    /**
     * journal-read: the per-server markdown journal (goal, base,
     * players, server facts). Paged by offset/maxChars; the full journal is
     * capped at 100 KB so a few reads cover it.
     */
    private static JsonObject journalRead(Minecraft client, JsonObject args) {
        String serverId = HyFuseClient.currentServerId();
        JsonObject result = new JsonObject();
        if (serverId == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "join a server first — the journal is per-server");
            return result;
        }
        BrainStore brain = HyFuseClient.brainStore();
        String journal = brain.readJournal(serverId);
        int offset = optionalInteger(args, "offset") != null ? optionalInteger(args, "offset"): 0;
        Integer maxRaw = optionalInteger(args, "maxChars");
        int maxChars = maxRaw != null ? Math.min(maxRaw, 16000) : 4000;
        if (offset < 0) {
            offset = 0;
        }
        if (journal == null) {
            result.addProperty("ok", true);
            result.addProperty("serverId", serverId);
            result.addProperty("content", "");
            result.addProperty("offset", 0);
            result.addProperty("totalChars", 0);
            result.addProperty("message", "no journal for '" + serverId + "' yet — journal-append creates it");
            return result;
        }
        int total = journal.length();
        String page = offset >= total ? "": journal.substring(offset, Math.min(total, offset + maxChars));
        result.addProperty("ok", true);
        result.addProperty("serverId", serverId);
        result.addProperty("content", page);
        result.addProperty("offset", offset);
        result.addProperty("totalChars", total);
        result.addProperty("message", "Journal '" + serverId + "': chars " + offset + "-" + (offset + page.length())
                + " of " + total + (offset + page.length() < total ? " (more — pass offset=" + (offset + page.length()) + ")": ""));
        return result;
    }

    /**
     * journal-append: append an entry newest-first under a section
     * (1-10, default 9 = rolling event log) of the per-server journal.
     * Entry ≤ 2000 chars; journal hard-capped at 100 KB (journal_full
     * rejects BEFORE mutating anything on disk).
     */
    private static JsonObject journalAppend(Minecraft client, JsonObject args) {
        String serverId = HyFuseClient.currentServerId();
        String text = requiredString(args, "text");
        Integer sectionRaw = optionalInteger(args, "section");
        int section = sectionRaw != null ? sectionRaw : 9;
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneOffset.systemDefault())
                .format(java.time.Instant.now());
        JsonObject outcome = HyFuseClient.brainStore().appendJournal(serverId, section, text, stamp);
        if (outcome.get("ok") != null && outcome.get("ok").getAsBoolean()) {
            outcome.addProperty("message", "Journal '" + serverId + "' §" + section + " entry appended");
        }
        return outcome;
    }

    /**
     * Snapshot memory block with server routing — per-server store
     * when connected (else global) plus the per-server journal size.
     */
    private static JsonObject memorySummaryWithServer() {
        String serverId = HyFuseClient.currentServerId();
        JsonObject summary = HyFuseClient.brainStore().storeFor(serverId).summary();
        summary.addProperty("scope", serverId != null ? serverId: "global");
        if (serverId != null) {
            summary.addProperty("journalBytes", HyFuseClient.brainStore().journalSize(serverId));
        }
        return summary;
    }

    /**
     * memory-save:
     * upsert a record into the persistent memory store by id — the
     * per-server file when connected (config/hyfuse_memory_<server-id>.json,
     * copy-on-first-connect), else the global config/hyfuse_memory.json.
     * Records are
     * flat JSON objects: {id, kind, position?, note?,...extras}. Position, when
     * present, is {x,y,z} and enables position-based queries in memory-read.
     * Suggested kinds: poi, mob, container, note. The mod owns the file — the
     * persistent store owns it exclusively.
     */
    private static JsonObject memorySave(Minecraft client, JsonObject args) {
        String id = requiredString(args, "id");
        String kind = optionalString(args, "kind", "note");
        JsonObject record = new JsonObject();
        record.addProperty("id", id);
        record.addProperty("kind", kind);
        JsonElement noteEl = args.get("note");
        if (noteEl != null && noteEl.isJsonPrimitive() && noteEl.getAsJsonPrimitive().isString()) {
            record.addProperty("note", noteEl.getAsString());
        }
        JsonElement posEl = args.get("position");
        if (posEl != null && posEl.isJsonObject()) {
            JsonObject pos = new JsonObject();
            for (String axis: new String[]{"x", "y", "z"}) {
                JsonElement v = posEl.getAsJsonObject().get(axis);
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    pos.addProperty(axis, v.getAsInt());
                }
            }
            if (pos.size() == 3) {
                record.add("position", pos);
            }
        }
        for (var e : args.entrySet()) {
            String key = e.getKey();
            if (key.equals("id") || key.equals("kind") || key.equals("note")
                    || key.equals("position")) continue;
            record.add(key, e.getValue().deepCopy());
        }
        JsonObject stored = storeForCurrentServer().upsert(record);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("saved", true);
        result.add("record", stored);
        result.addProperty("totalRecords", storeForCurrentServer().summary()
.get("records").getAsInt());
        result.addProperty("message", "Saved memory record '" + id + "' (kind " + kind + ")");
        return result;
    }

    /**
     * memory-read: query the persistent store. Filter by kind, id, or
     * exact position; no filters -> all records. `dump:true` returns the raw
     * full store (capped the same as the file: 512 records).
     */
    private static JsonObject memoryRead(Minecraft client, JsonObject args) {
        MemoryStore store = storeForCurrentServer();
        String kind = optionalString(args, "kind", null);
        String id = optionalString(args, "id", null);
        Integer x = optionalInteger(args, "x");
        Integer y = optionalInteger(args, "y");
        Integer z = optionalInteger(args, "z");
        java.util.List<JsonObject> found = store.query(kind, id, x, y, z);
        JsonArray recordsArr = new JsonArray();
        for (JsonObject rec : found) recordsArr.add(rec);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("records", recordsArr);
        result.addProperty("count", found.size());
        result.addProperty("totalRecords", store.summary().get("records").getAsInt());
        result.addProperty("message", "Memory: " + found.size() + " record(s)"
                + (kind != null ? " kind=" + kind: "")
                + (id != null ? " id=" + id: ""));
        return result;
    }

    /**
     * memory-forget: delete a record by id. Honest result — ok:false
     * when the id was never stored.
     */
    private static JsonObject memoryForget(Minecraft client, JsonObject args) {
        String id = requiredString(args, "id");
        boolean removed = storeForCurrentServer().forget(id);
        JsonObject result = new JsonObject();
        result.addProperty("ok", removed);
        if (!removed) {
            result.addProperty("error", "no record with id '" + id + "'");
        }
        result.addProperty("totalRecords", HyFuseClient.memoryStore().summary()
.get("records").getAsInt());
        result.addProperty("message", removed
                ? "Forgot memory record '" + id + "'"
: "No memory record '" + id + "' to forget");
        return result;
    }

    // ──: policy tools ──────────────────────────────

    /**
     * policy-save: upsert a policy row {id, kind:"policy", trigger,
     * threshold?, action, args?, cooldownMs?, enabled} into the persistent
     * store (flat extras pass through like memory-save). Editing an
     * existing id overwrites it — user edits take effect on the next
     * matching event, no restart needed.
     */
    private static JsonObject policySave(Minecraft client, JsonObject args) {
        String id = requiredString(args, "id");
        String trigger = requiredString(args, "trigger");
        JsonObject record = new JsonObject();
        record.addProperty("id", id);
        record.addProperty("kind", "policy");
        record.addProperty("trigger", trigger);
        JsonElement thresholdEl = args.get("threshold");
        if (thresholdEl != null && thresholdEl.isJsonPrimitive()) {
            record.add("threshold", thresholdEl.deepCopy());
        }
        String action = optionalString(args, "action", null);
        if (action != null) record.addProperty("action", action);
        JsonElement argsEl = args.get("args");
        if (argsEl != null && argsEl.isJsonObject()) {
            record.add("args", argsEl.deepCopy());
        }
        JsonElement cooldownEl = args.get("cooldownMs");
        if (cooldownEl != null && cooldownEl.isJsonPrimitive()) {
            record.add("cooldownMs", cooldownEl.deepCopy());
        }
        JsonElement enabledEl = args.get("enabled");
        if (enabledEl != null && enabledEl.isJsonPrimitive()) {
            record.add("enabled", enabledEl.deepCopy());
        }
        for (var e : args.entrySet()) {
            String key = e.getKey();
            if (key.equals("id") || key.equals("kind") || key.equals("trigger")
                    || key.equals("threshold") || key.equals("action")
                    || key.equals("args") || key.equals("cooldownMs")
                    || key.equals("enabled")) continue;
            record.add(key, e.getValue().deepCopy());
        }
        JsonObject stored = HyFuseClient.memoryStore().upsert(record);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("saved", true);
        result.add("policy", stored);
        result.addProperty("message", "Saved policy '" + id + "' (trigger " + trigger
                + (action != null ? " -> " + action: " (audit-only)") + ")");
        return result;
    }

    /**
     * policy-read: list policy rows (all by default, one by id) plus the
     * engine's audit ring (last 100 firings: trigger, policy, action,
     * result, ok, timestamp) — the user-visible WHY.
     */
    private static JsonObject policyRead(Minecraft client, JsonObject args) {
        MemoryStore store = HyFuseClient.memoryStore();
        String id = optionalString(args, "id", null);
        java.util.List<JsonObject> found = store.query("policy", id, null, null, null);
        JsonArray policies = new JsonArray();
        for (JsonObject rec : found) policies.add(rec);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("policies", policies);
        result.addProperty("count", found.size());
        result.add("audit", HyFuseClient.POLICY_ENGINE.auditJson());
        result.addProperty("message", "Policies: " + found.size()
                + " row(s); audit ring included");
        return result;
    }

    /** policy-forget: delete a policy row by id (honest ok:false when absent). */
    private static JsonObject policyForget(Minecraft client, JsonObject args) {
        String id = requiredString(args, "id");
        boolean removed = HyFuseClient.memoryStore().forget(id);
        JsonObject result = new JsonObject();
        result.addProperty("ok", removed);
        if (!removed) {
            result.addProperty("error", "no policy with id '" + id + "'");
        }
        result.addProperty("message", removed
                ? "Forgot policy '" + id + "'"
: "No policy '" + id + "' to forget");
        return result;
    }

    /**
     * Auto-record: open-container refreshes the
     * container baseline record `container:<x>,<y>,<z>` with the observed
     * contents — ground truth captured at the moment of opening.
     */
    private static void recordContainerBaseline(BlockPos pos, String blockName, JsonArray contents) {
        try {
            JsonObject record = new JsonObject();
            record.addProperty("id", "container:" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            record.addProperty("kind", "container");
            JsonObject p = new JsonObject();
            p.addProperty("x", pos.getX());
            p.addProperty("y", pos.getY());
            p.addProperty("z", pos.getZ());
            record.add("position", p);
            record.addProperty("block", blockName);
            record.add("contents", contents.deepCopy());
            int itemCount = 0;
            for (JsonElement el : contents) {
                if (el.isJsonObject()) itemCount += el.getAsJsonObject().get("count").getAsInt();
            }
            record.addProperty("itemCount", itemCount);
            HyFuseClient.memoryStore().upsert(record);
        } catch (Throwable t) {
            // Auto-record is best-effort; open-container results are unaffected.
        }
    }

    /** Integer or null — position filters for memory-read. */
    private static Integer optionalInteger(JsonObject args, String name) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            if (Double.isFinite(number) && number == Math.rint(number)) {
                return (int) number;
            }
        }
        return null;
    }

    // ── Standing processes ──────────────────────

    // ──────────────────────────────────────────────────────────────────────
    // Agent-mode increment 1 — user-only loop lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private static JsonObject agentStart(Minecraft client, JsonObject args) {
        return agentStart(client, args, null);
    }

    private static JsonObject agentStart(Minecraft client, JsonObject args, String goal) {
        JsonObject out = new JsonObject();
        if (!AgentLoop.isConfigured()) {
            out.addProperty("ok", false);
            out.addProperty("reason", "agent_disabled");
            out.addProperty("message", "config/hyfuse/agent.json is absent or has a blank url "
                    + "— the agent loop is off. Create it with {url, apiKey, model, "
                    + "maxIterations} to enable.");
            return out;
        }
        if (!AgentLoop.start()) {
            out.addProperty("ok", false);
            out.addProperty("reason", "agent_busy");
            out.addProperty("message", "An agent loop is already running. agent-status for "
                    + "telemetry; agent-stop to stop it.");
            return out;
        }
        JsonObject cfg = AgentLoop.loadConfig();
        int maxIter = cfg.has("maxIterations") ? cfg.get("maxIterations").getAsInt(): 8;
        String model = cfg.has("model") ? cfg.get("model").getAsString(): "default";
        String url = cfg.get("url").getAsString();
        String apiKey = cfg.has("apiKey") ? cfg.get("apiKey").getAsString(): "";
        JsonObject snapshot = getAgentSnapshot(client, new JsonObject());
        String playbook = HyFuseClient.brainStore().readPlaybook();
        AGENT_EXECUTOR.execute(() -> {
            AgentLoop loop = new AgentLoop(
                    (name, a) -> dispatchRef(name, a),
                    new AgentLoop.HttpTransport(url, apiKey));
            loop.setModel(model);
            try {
                if (goal != null && !goal.isBlank()) {
                    loop.run(maxIter, snapshot, playbook, goal);
                } else {
                    loop.run(maxIter, snapshot, playbook);
                }
            } catch (Throwable t) {
                LOGGER.error("agent loop crashed: {}", t.toString());
            }
        });
        out.addProperty("ok", true);
        out.addProperty("message", "Agent loop started (model=" + model + ", maxIterations="
                + maxIter + "). Poll agent-status.");
        return out;
    }

    private static JsonObject agentStop(Minecraft client, JsonObject args) {
        AgentLoop.requestStop();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("message", "Stop requested; the loop exits after the current "
                + "iteration. Poll agent-status until active=false.");
        return out;
    }

    /**
     * Start a GOAL session (the /hyfuse set goal <text> entry point;
     * also usable from MCP). Config identical to agent-start; the seed
     * message differs (goal decomposition directive). Reflect mode rides
     * along — see AgentMode.
     */
    public static JsonObject agentGoalStart(Minecraft client, JsonObject args) {
        String goal = optionalString(args, "goal", null);
        if (goal == null || goal.isBlank()) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("reason", "goal_required");
            out.addProperty("message", "A goal text is required: /hyfuse set goal <text> or arg goal");
            return out;
        }
        return com.hyfuse.bridge.agent.GoalSession.start(
                client, goal, () -> (a, b) -> DISPATCHER.dispatch(a, b));
    }

    /** Public wrapper for the goal session's snapshot read. */
    public static JsonObject getAgentSnapshotPublic(Minecraft client) {
        return getAgentSnapshot(client, new JsonObject());
    }

    /** Public wrapper for AgentMode's KillAura toggle (Meteor-optional). */
    public static boolean toggleMeteorModuleByNamePublic(Minecraft client, String module, boolean enable) {
        JsonObject tArgs = new JsonObject();
        tArgs.addProperty("module", module);
        tArgs.addProperty("action", enable ? "enable": "disable");
        JsonObject r;
           try {
            r = toggleMeteorModule(client, tArgs);
        } catch (Throwable t) {
            return false;
        }
        return r.has("ok") && r.get("ok").getAsBoolean()
                && r.has("nowActive") && r.get("nowActive").getAsBoolean();
    }

    /**
     * Enable/disable agent_mode reflect posture. on = KillAura on +
     * standing reflect rows installed; off = KillAura off + reflect rows
     * removed. Returns the AgentMode status JSON.
     */
    public static JsonObject agentModeSet(Minecraft client, JsonObject args) {
        boolean on = "on".equalsIgnoreCase(optionalString(args, "mode", "off"))
                || Boolean.parseBoolean(optionalString(args, "agent_mode", "false"))
                || Boolean.parseBoolean(optionalString(args, "value", "false"));
        JsonObject out = new JsonObject();
        try {
            JsonObject result = com.hyfuse.bridge.agent.AgentMode.set(
                    client, on, DISPATCHER::dispatch);
            out.addProperty("ok", true);
            out.add("status", result);
            if (result.has("killAura")) out.addProperty("killAura", result.get("killAura").getAsBoolean());
            out.addProperty("message", "agent_mode " + (on ? "on": "off"));
        } catch (Throwable e) {
            out.addProperty("ok", false);
            out.addProperty("reason", "agent_mode_error");
            out.addProperty("message", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private static JsonObject agentStatus(Minecraft client, JsonObject args) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("configured", AgentLoop.isConfigured());
        out.addProperty("status", AgentLoop.status());
        try {
            out.add("statusJson", JsonParser.parseString(AgentLoop.status()).getAsJsonObject());
        } catch (Exception ignored) { }
        return out;
    }

    /** Queue-rail dispatch used by the agent loop (instance dispatch()). */
    private static CompletableFuture<JsonObject> dispatchRef(String name, JsonObject args) {
        return DISPATCHER.dispatch(name, args);
    }

    private static final java.util.concurrent.ExecutorService AGENT_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hyfuse-agent-loop");
                t.setDaemon(true);
                return t;
            });

    /** Bounded executor for standing-stop's wait loop (hard lesson: sleeps
     * never on the client thread; but NOT the queue executor — a mid-cycle
     * stop must not QUEUE_BUSY against the cycle itself). */
    private static final java.util.concurrent.ExecutorService STANDING_STOP_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hyfuse-standing-stop");
                t.setDaemon(true);
                return t;
            });

    /** One-time wiring of the standing engine to the dispatcher rail. */
    private static void wireStandingEngine(Minecraft client) {
        StandingProcessEngine.wire(
                (tasks, name, type) -> runStandingCycle(client, tasks, name, type),
                () -> callOnClient(client, () -> client.player == null
                        || client.player.isDeadOrDying()),
                () -> HyFuseClient.memoryStore(),
                // Defect 1: nearest-container lookup from the CURRENT
                // player position (client-thread scan; last-resort tier).
                p -> callOnClient(client, () -> {
                    BlockPos origin = client.player.blockPosition();
                    int radius = 128;
                    BlockPos best = null; int bd = Integer.MAX_VALUE;
                    for (int dx = -radius; dx <= radius; dx += 2) {
                        for (int dy = -8; dy <= 8; dy += 2) {
                            for (int dz = -radius; dz <= radius; dz += 2) {
                                BlockPos q = origin.offset(dx, dy, dz);
                                if (!isContainerBlock(client.level.getBlockState(q))) continue;
                                int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                                if (d < bd) { bd = d; best = q; }
                            }
                        }
                    }
                    return best == null ? null : new int[]{best.getX(), best.getY(), best.getZ()};
                }));
    }

    /**
     * Run one standing-process cycle through the enqueue rail. The engine's
     * supervisor thread calls this; the rail runs the tasks exactly like
     * enqueue-tasks does (QUEUE_ACTIVE gate, dispatchQueueTask switch,
     * callOnClient marshalling, telemetry marked with the standing prefix).
     * A mid-cycle gate conflict (an ad-hoc composite running) returns a
     * failed-cycle result; the engine backs off and retries.
     */
    private static JsonObject runStandingCycle(Minecraft client, JsonArray tasks,
                                               String processName, String processType) {
        QueueTelemetry.begin("standing:" + processName + ":" + processType);
        try {
            if (!QUEUE_ACTIVE.compareAndSet(false, true)) {
                JsonObject r = new JsonObject();
                r.addProperty("ok", false);
                r.addProperty("reason", "queue_busy");
                r.addProperty("message", "standing cycle deferred: queue busy");
                return r;
            }
            try {
                return runQueueTaskList(client, tasks, false);
            } finally {
                QUEUE_ACTIVE.set(false);
            }
        } finally {
            QueueTelemetry.end();
        }
    }

    /**
     * Core of enqueueTasks' loop, factored out so the standing-cycle runner
     * executes the same per-task dispatch with $name.field context, onFail,
     * and childResultJson result shaping. returnToOrigin=false always here.
     */
    private static JsonObject runQueueTaskList(Minecraft client, JsonArray taskArr,
                                               boolean returnToOrigin) {
        long startTime = System.currentTimeMillis();
        java.util.Map<String, JsonObject> context = new java.util.HashMap<>();
        int total = taskArr.size();
        int completed = 0;
        int skipped = 0;
        boolean aborted = false;
        JsonArray results = new JsonArray();

        for (int i = 0; i < total; i++) {
            JsonElement el = taskArr.get(i);
            if (el == null || !el.isJsonObject()) {
                JsonObject skip = childResultJson("<invalid>", false, "invalid_task_spec", null);
                results.add(skip);
                skipped++;
                continue;
            }
            JsonObject spec = el.getAsJsonObject();
            String tool = optionalString(spec, "tool", "");
            String taskName = optionalString(spec, "name", "");
            String onFail = optionalString(spec, "onFail", "continue");
            JsonObject rawArgs = spec.has("args") && spec.get("args").isJsonObject()
                    ? spec.getAsJsonObject("args"): new JsonObject();
            JsonObject childArgs = resolveTaskArgs(rawArgs, context);

            if (!QUEUE_SUPPORTED_TOOLS.contains(tool)) {
                JsonObject skip = childResultJson(tool, false, "unknown_tool", null);
                results.add(skip);
                skipped++;
                if (!onFail.equals("abort")) continue;
                aborted = true;
                for (int j = i + 1; j < total; j++) {
                    results.add(childResultJson(toolNameAt(taskArr, j), false, "skipped", null));
                    skipped++;
                }
                break;
            }

            JsonObject childResult;
            try {
                childResult = dispatchQueueTask(client, tool, childArgs);
            } catch (Throwable t) {
                childResult = new JsonObject();
                childResult.addProperty("ok", false);
                childResult.addProperty("reason", "error");
                childResult.addProperty("error", t.getMessage() == null ? t.getClass().getSimpleName(): t.getMessage());
            }

            boolean childOk = childResult.has("ok") && childResult.get("ok").isJsonPrimitive()
                    && childResult.get("ok").getAsBoolean();
            String childReason = childResult.has("reason") && childResult.get("reason").isJsonPrimitive()
                    ? childResult.get("reason").getAsString()
: (childOk ? "done": "failed");
            results.add(childResultJson(tool, childOk, childReason, childResult));
            completed++;

            if (!taskName.isEmpty()) {
                context.put(taskName, childResult);
            }
            if (childOk || !onFail.equals("abort")) continue;
            aborted = true;
            for (int j = i + 1; j < total; j++) {
                results.add(childResultJson(toolNameAt(taskArr, j), false, "skipped", null));
                skipped++;
            }
            break;
        }

        boolean allOk = completed > 0 && !aborted && allResultsOk(results);
        String reason = aborted ? "aborted": (allOk ? "done": "partial");
        JsonObject result = new JsonObject();
        result.addProperty("ok", allOk);
        result.addProperty("completed", completed);
        result.addProperty("skipped", skipped);
        result.addProperty("total", total);
        result.add("results", results);
        result.addProperty("reason", reason);
        result.addProperty("returnToOrigin", false);
        result.addProperty("durationMs", System.currentTimeMillis() - startTime);
        result.addProperty("message", "Queue " + reason + ": " + completed + "/" + total + " tasks done");
        return result;
    }

    private static JsonObject standingStart(Minecraft client, JsonObject args) {
        wireStandingEngine(client);
        String name = optionalString(args, "name", "");
        String type = optionalString(args, "type", "mine-and-deposit");
        JsonObject argSpec = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args"): new JsonObject();
        JsonObject stopWhen = args.has("stopWhen") && args.get("stopWhen").isJsonObject()
                ? args.getAsJsonObject("stopWhen"): new JsonObject();
        long intervalMs = (long) optionalDouble(args, "intervalMs", 0);
        return StandingProcessEngine.start(name, type, argSpec, stopWhen, intervalMs);
    }

    private static JsonObject standingStatus(Minecraft client, JsonObject args) {
        wireStandingEngine(client);
        String name = optionalString(args, "name", "");
        return StandingProcessEngine.status(name);
    }

    private static JsonObject standingStop(Minecraft client, JsonObject args) {
        wireStandingEngine(client);
        String name = optionalString(args, "name", "");
        boolean wait = optionalBool(args, "wait", false);
        // The wait loop sleeps — run it off-thread. Light stop (no wait)
        // returns immediately from the client thread.
        if (!wait) {
            return StandingProcessEngine.stop(name, false);
        }
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> StandingProcessEngine.stop(name, true), STANDING_STOP_EXECUTOR)
                    .get(130, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("error", "STOP_EXEC_ERROR");
            r.addProperty("message", e.getMessage() == null ? e.getClass().getSimpleName(): e.getMessage());
            return r;
        }
    }

    /**
     * collect-drops: Hardened v2 of collect-dropped-items. Finds item entities
     * within `radius`, filters by an exact-name `expectedItems` set, dispatches
     * Baritone #goto to the nearest, polls for entity removal, and confirms via
     * an inventory-delta check (snapshot counts before vs after). 24-pass cap +
     * timeoutMs bound the loop. 
     */
    private static JsonObject collectDrops(Minecraft client, JsonObject args) {
        int radius = optionalInt(args, "radius", DEFAULT_COLLECT_DROPS_RADIUS);
        long timeoutMs = optionalInt(args, "timeoutMs", (int) DEFAULT_COLLECT_DROPS_TIMEOUT_MS);
        if (timeoutMs < 500) timeoutMs = 500;
        java.util.Set<String> expected = null;
        JsonElement expectedEl = args.get("expectedItems");
        if (expectedEl != null && expectedEl.isJsonArray()) {
            expected = new java.util.HashSet<>();
            for (JsonElement e : expectedEl.getAsJsonArray()) {
                if (e.isJsonPrimitive()) expected.add(normalizeResourceId(e.getAsString()));
            }
            if (expected.isEmpty()) expected = null;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        // Snapshot inventory BEFORE for the delta check.
        java.util.Map<String, Integer> before = inventoryCounts(player);

        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        int iterations = 0;
        int remainingDrops = 0;

        try {
            for (int pass = 0; pass < COLLECT_DROPS_MAX_PASSES; pass++) {
                if (System.currentTimeMillis() > deadline) break;
                iterations++;
                java.util.List<Entity> drops = new ArrayList<>();
                for (Entity entity : level.entitiesForRendering()) {
                    if (!(entity instanceof ItemEntity itemEntity)) continue;
                    double dist = Math.sqrt(entity.distanceToSqr(player));
                    if (dist > radius) continue;
                    if (expected != null) {
                        String name = itemRegistryName(itemEntity.getItem());
                        if (!expected.contains(name)) continue;
                    }
                    drops.add(entity);
                }
                drops.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
                if (drops.isEmpty()) { remainingDrops = 0; break; }

                Entity nearest = drops.get(0);
                double dist = Math.sqrt(nearest.distanceToSqr(player));
                try {
                    if (dist <= 1.5) {
                        Thread.sleep(300);
                    } else {
                        // Defect 3: collectDropsAt pattern (internal,
                        // working — hunt-hostile picks up loot with it): goto
                        // the drop's BLOCK position and wait for removal or
                        // <=1.5 blocks. goto target = the block the drop sits
                        // on/in (standable), not the drop's air cell.
                        BlockPos itemPos = callOnClient(client, nearest::blockPosition);
                        callOnClient(client, () -> {
                            client.player.connection.sendChat(
                                    "#goto " + itemPos.getX() + " " + itemPos.getY() + " " + itemPos.getZ());
                            return null;
                        });
                        long subDeadline = System.currentTimeMillis() + Math.min(8000, deadline - System.currentTimeMillis());
                        boolean gotIt = false;
                        while (System.currentTimeMillis() < subDeadline) {
                            Thread.sleep(MOVE_POLL_INTERVAL_MS);
                            gotIt = callOnClient(client, () ->
                                    nearest.isRemoved() || Math.sqrt(nearest.distanceToSqr(client.player)) <= 2.25);
                            if (gotIt) break;
                        }
                        if (gotIt) { Thread.sleep(300); }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    remainingDrops = drops.size() - 1;
                    break;
                } finally {
                    callOnClient(client, () -> { client.player.connection.sendChat("#stop"); return null; });
                }
                remainingDrops = countDrops(level, player, radius, expected);
            }
        } finally {
            client.player.connection.sendChat("#stop");
        }

        // Inventory-delta confirmation.
        java.util.Map<String, Integer> after = inventoryCounts(player);
        JsonObject collected = new JsonObject();
        for (var e : after.entrySet()) {
            int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (delta > 0) collected.addProperty(e.getKey(), delta);
        }
        long durationMs = System.currentTimeMillis() - start;
        boolean success = collected.size() > 0;
        JsonObject result = new JsonObject();
        result.add("collected", collected);
        result.addProperty("remainingDrops", remainingDrops);
        result.addProperty("durationMs", durationMs);
        result.addProperty("success", success);
        result.addProperty("iterations", iterations);
        result.addProperty("message", success
                ? "Collected " + collected.entrySet().stream()
.map(e -> e.getKey() + " " + e.getValue().getAsString())
.reduce((a, b) -> a + ", " + b).orElse("") + " in " + durationMs + "ms (" + remainingDrops + " drop(s) remaining)"
: "No items collected in " + durationMs + "ms (" + remainingDrops + " drop(s) still on the ground)");
        return result;
    }

    /**
     * follow-player: Resolve a player/mob by username or entityId, then delegate
     * to Baritone #follow <name>. Polls for target disappearance / range reached /
     * timeout. Uses the 
     * same Baritone #follow delegation as Tier I follow-entity.
     */
    private static JsonObject followPlayer(Minecraft client, JsonObject args) {
        String username = optionalString(args, "username", "");
        int entityId = optionalInt(args, "entityId", Integer.MIN_VALUE);
        double range = optionalDouble(args, "range", 3.0);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 0);
        if (range < 1) range = 1;
        if (range > 64) range = 64;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        Entity target = null;
        if (entityId != Integer.MIN_VALUE) {
            target = level.getEntity(entityId);
        } else if (!username.isEmpty()) {
            String needle = username.toLowerCase();
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == player) continue;
                net.minecraft.network.chat.Component custom = entity.getCustomName();
                if (custom != null && custom.getString().toLowerCase().contains(needle)) {
                    target = entity; break;
                }
                // Also match players by GameProfile name via getName().
                if (entity instanceof Player && entity.getName().getString().toLowerCase().contains(needle)) {
                    target = entity; break;
                }
            }
        }
        if (target == null) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "no target (pass `username` or `entityId`)");
            return result;
        }

        // Baritone #follow takes a player/entity name. Use the custom/registry name.
        String followName = "";
        net.minecraft.network.chat.Component custom = target.getCustomName();
        if (custom != null && !custom.getString().isEmpty()) followName = custom.getString();
        else if (target instanceof Player) followName = target.getName().getString();
        else followName = simpleEntityName(target);
        client.player.connection.sendChat("#follow " + followName);

        long start = System.currentTimeMillis();
        long deadline = maxDurationMs > 0 ? start + maxDurationMs : Long.MAX_VALUE;
        boolean inRange = false;
        String reason = "timeout";
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MOVE_POLL_INTERVAL_MS);
                if (level.getEntity(target.getId()) == null || target.isRemoved() || !target.isAlive()) {
                    reason = "target_left"; break;
                }
                double dist = Math.sqrt(target.distanceToSqr(player));
                if (dist <= range) { inRange = true; reason = "in_range"; break; }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } finally {
            if (inRange) client.player.connection.sendChat("#stop");
        }

        double finalDist = target.isRemoved() ? -1 : Math.sqrt(target.distanceToSqr(player));
        JsonObject result = new JsonObject();
        result.addProperty("ok", inRange);
        result.addProperty("followed", followName);
        result.addProperty("reason", reason);
        result.addProperty("finalDistance", Math.round(finalDist * 10.0) / 10.0);
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("message", inRange
                ? "Followed " + followName + " (in_range at " + Math.round(finalDist * 10.0) / 10.0 + " blocks)"
: "follow-player " + reason);
        return result;
    }

    /**
     * scan-volume: Scan a box (x1,y1,z1)→(x2,y2,z2) into a compact cell-class
     * grid (AIR/SOLID/WATER/AVOID) plus a notableBlocks list. Caps at 32x16x32.
     * `compressed` returns a base64 2-bit grid; `block` returns the raw array.
     * Pure read — no movement, no engine. 
     */
    private static JsonObject scanVolume(Minecraft client, JsonObject args) {
        int x1 = requiredInt(args, "x1"), y1 = requiredInt(args, "y1"), z1 = requiredInt(args, "z1");
        int x2 = requiredInt(args, "x2"), y2 = requiredInt(args, "y2"), z2 = requiredInt(args, "z2");
        String resolution = optionalString(args, "resolution", "compressed");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        if (sx > SCAN_VOLUME_MAX_DIM || sy > SCAN_VOLUME_MAX_Y || sz > SCAN_VOLUME_MAX_DIM) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", "volume too large: max " + SCAN_VOLUME_MAX_DIM + "x" + SCAN_VOLUME_MAX_Y + "x" + SCAN_VOLUME_MAX_DIM
                    + ", got " + sx + "x" + sy + "x" + sz);
            return result;
        }
        ClientLevel level = client.level;
        // 2 bits/cell: 0=AIR, 1=SOLID, 2=WATER, 3=AVOID. Pack LSB-first into bytes.
        int cellCount = sx * sy * sz;
        byte[] cells = new byte[cellCount];
        java.util.List<JsonObject> notables = new ArrayList<>();
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                    BlockState state = level.getBlockState(pos);
                    String simple = simpleBlockName(state);
                    byte cls;
                    if (state.isAir() || state.canBeReplaced()) {
                        cls = FLUID_BLOCK_NAMES.contains(simple) ? (byte) 2 : 0; // water is replaceable; classify separately
                        if (FLUID_BLOCK_NAMES.contains(simple)) cls = 2;
                    } else if (FLUID_BLOCK_NAMES.contains(simple)) {
                        cls = 2;
                    } else if (HAZARD_BLOCK_NAMES.contains(simple)) {
                        cls = 3;
                    } else {
                        cls = 1;
                    }
                    cells[i++] = cls;
                    if (NOTABLE_ORE_NAMES.contains(simple) || NOTABLE_LOG_NAMES.contains(simple) || NOTABLE_CONTAINER_NAMES.contains(simple)) {
                        if (notables.size() < 128) {
                            JsonObject n = new JsonObject();
                            n.addProperty("name", simple);
                            n.addProperty("x", pos.getX()); n.addProperty("y", pos.getY()); n.addProperty("z", pos.getZ());
                            notables.add(n);
                        }
                    }
                }
            }
        }
        JsonObject dims = new JsonObject();
        dims.addProperty("sx", sx); dims.addProperty("sy", sy); dims.addProperty("sz", sz);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("dims", dims);
        result.addProperty("cellCount", cellCount);
        result.add("notableBlocks", toJsonArray(notables));
        result.addProperty("scannedAt", System.currentTimeMillis() / 1000.0);
        if (resolution.equals("block")) {
            JsonArray cellArr = new JsonArray();
            for (byte c : cells) cellArr.add(c);
            result.add("cells", cellArr);
        } else {
            result.addProperty("base64", encode2BitGrid(cells));
        }
        result.addProperty("message", "scan-volume " + sx + "x" + sy + "x" + sz + ": " + notables.size() + " notable");
        return result;
    }

    /**
     * resolve-material: Resolve a desired block through its material family
     * using the bot's current inventory (oak_planks → cherry_planks when held).
     * Returns resolved name + family + per-family shortfall. Pure data + read.
     */
    private static JsonObject resolveMaterial(Minecraft client, JsonObject args) {
        String block = requiredString(args, "block");
        int count = optionalInt(args, "count", 1);
        String family = describeFamily(block);
        java.util.List<String> members = familyMembers(family);
        java.util.Map<String, Integer> inv = inventoryCounts(client.player);
        String resolved = block;
        int haveCount = inv.getOrDefault(stripNamespace(block), 0);
        // If the bot doesn't have the requested block but has a family member, resolve to it.
        if (haveCount < count && !members.isEmpty()) {
            for (String member : members) {
                int have = inv.getOrDefault(member, 0);
                if (have >= count) { resolved = "minecraft:" + member; haveCount = have; break; }
            }
        }
        // Shortfall across the whole family.
        int familyHave = 0;
        for (String member : members) familyHave += inv.getOrDefault(member, 0);
        JsonObject shortfall = new JsonObject();
        if (familyHave < count && !members.isEmpty()) {
            shortfall.addProperty(family, count - familyHave);
        } else if (haveCount < count && members.isEmpty()) {
            shortfall.addProperty(stripNamespace(block), count - haveCount);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("requested", block);
        result.addProperty("resolved", resolved);
        result.addProperty("family", family);
        result.add("shortfall", shortfall);
        result.addProperty("haveCount", Math.max(haveCount, familyHave));
        result.addProperty("message", shortfall.size() > 0
                ? block + " → " + resolved + " (shortfall: " + family + " need " + (count - Math.max(haveCount, familyHave)) + ")"
: block + " → " + resolved + (family.isEmpty() ? "": " (family " + family + ")"));
        return result;
    }

    /**
     * find-ore-veins: Scan a radius for tracked ores, then group them into veins
     * via a 26-connectivity flood-fill over live world blocks. Returns
     * [{type, center, count, exposed, nearestStand}] nearest-first. No WorldCache
     * in Fabric mode, so we seed purely from a fresh radius scan.
     */
    private static JsonObject findOreVeins(Minecraft client, JsonObject args) {
        String oreType = optionalString(args, "oreType", "any");
        int maxDistance = optionalInt(args, "maxDistance", 64);
        int maxVeins = optionalInt(args, "maxVeins", 8);
        boolean exposedOnly = optionalBool(args, "exposedOnly", false);
        if (maxVeins < 1) maxVeins = 1; if (maxVeins > 32) maxVeins = 32;
        if (maxDistance < 1) maxDistance = 1; if (maxDistance > 128) maxDistance = 128;

        java.util.Set<String> names = new java.util.HashSet<>();
        if (oreType.equals("any") || oreType.isEmpty()) names.addAll(NOTABLE_ORE_NAMES);
        else names.add(stripNamespace(oreType));
        // Also include deepslate variants of a bare request like "iron_ore".
        java.util.Set<String> matchNames = new java.util.HashSet<>(names);
        for (String n: new java.util.HashSet<>(names)) matchNames.add("deepslate_" + n);

        ClientLevel level = client.level;
        BlockPos origin = client.player.blockPosition();
        int radius = Math.min(maxDistance, 128);
        // Seed: scan a radius for matching ore cells.
        java.util.List<BlockPos> seeds = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    String simple = simpleBlockName(level.getBlockState(pos));
                    if (matchNames.contains(simple)) seeds.add(pos);
                }
            }
        }
        if (seeds.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.add("veins", new JsonArray());
            result.addProperty("count", 0);
            result.addProperty("message", "no " + oreType + " veins within " + maxDistance + " blocks");
            return result;
        }

        // 26-connectivity flood-fill, grouped by ore type.
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.List<JsonObject> veins = new ArrayList<>();
        for (BlockPos seed : seeds) {
            String key = seed.getX() + "," + seed.getY() + "," + seed.getZ();
            if (!visited.add(key)) continue;
            String type = simpleBlockName(level.getBlockState(seed));
            if (!matchNames.contains(type)) continue;
            java.util.List<BlockPos> group = new ArrayList<>();
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            queue.add(seed);
            boolean exposed = false;
            BlockPos center = seed;
            while (!queue.isEmpty() && group.size() < 256) {
                BlockPos cur = queue.poll();
                String ck = cur.getX() + "," + cur.getY() + "," + cur.getZ();
                if (!visited.add(ck)) continue;
                String ct = simpleBlockName(level.getBlockState(cur));
                if (!ct.equals(type)) continue;
                group.add(cur);
                center = cur; // last added; center computed below
                // 26 neighbours.
                for (int nx = -1; nx <= 1; nx++)
                    for (int ny = -1; ny <= 1; ny++)
                        for (int nz = -1; nz <= 1; nz++) {
                            if (nx == 0 && ny == 0 && nz == 0) continue;
                            BlockPos nb = cur.offset(nx, ny, nz);
                            String nbType = simpleBlockName(level.getBlockState(nb));
                            if (nbType.equals(type)) queue.add(nb);
                            else if (nbType.equals("air") || level.getBlockState(nb).canBeReplaced()) exposed = true;
                        }
            }
            if (group.isEmpty()) continue;
            // Center = average of the group.
            long cx = 0, cy = 0, cz = 0;
            for (BlockPos g : group) { cx += g.getX(); cy += g.getY(); cz += g.getZ(); }
            BlockPos centerPos = new BlockPos((int)(cx / group.size()), (int)(cy / group.size()), (int)(cz / group.size()));
            BlockPos nearestStand = findStandableNear(level, centerPos, 4);
            if (exposedOnly && !exposed) continue;
            JsonObject vein = new JsonObject();
            vein.addProperty("type", type);
            vein.add("center", posJson(centerPos));
            vein.addProperty("count", group.size());
            vein.addProperty("exposed", exposed);
            vein.add("nearestStand", nearestStand != null ? posJson(nearestStand): null);
            double dist = centerPos.distManhattan(origin);
            vein.addProperty("_dist", dist);
            veins.add(vein);
            if (veins.size() >= maxVeins * 2) break; // over-scan then trim
        }
        veins.sort((a, b) -> Double.compare(a.get("_dist").getAsDouble(), b.get("_dist").getAsDouble()));
        java.util.List<JsonObject> capped = veins.subList(0, Math.min(maxVeins, veins.size()));
        for (JsonObject v: capped) v.remove("_dist");
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("veins", toJsonArray(capped));
        result.addProperty("count", capped.size());
        result.addProperty("message", capped.isEmpty()
                ? "no " + (exposedOnly ? "exposed ": "") + oreType + " veins within " + maxDistance + " blocks"
: "Found " + capped.size() + " " + oreType + " vein(s); nearest at "
                    + capped.get(0).get("center").getAsJsonObject().get("x") + ","
                    + capped.get(0).get("center").getAsJsonObject().get("y") + ","
                    + capped.get(0).get("center").getAsJsonObject().get("z"));
        return result;
    }

    /**
     * get-to-block: Scan for a target block, dispatch Baritone #goto the nearest,
     * optionally right-click on arrival (open chest/crafting table/furnace/door).
     * Blacklists the closest on path failure. `explore` flag falls back to a far
     * #goto in the look direction. 
     * (no WorldCache, no composite GoalGetToBlock).
     */
    private static JsonObject getToBlock(Minecraft client, JsonObject args) {
        String block = optionalString(args, "block", "");
        java.util.List<String> targets = new ArrayList<>();
        JsonElement filterEl = args.get("filter");
        if (filterEl != null && filterEl.isJsonArray()) {
            for (JsonElement e : filterEl.getAsJsonArray()) if (e.isJsonPrimitive()) targets.add(e.getAsString());
        }
        if (!block.isEmpty() && targets.isEmpty()) targets.add(block);
        if (targets.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false); r.addProperty("error", "no target (pass `block` or `filter`)");
            return r;
        }
        int maxDistance = optionalInt(args, "maxDistance", 64);
        boolean rightClick = optionalBool(args, "rightClick", true);
        boolean exploreFlag = optionalBool(args, "explore", false);
        if (maxDistance < 1) maxDistance = 1; if (maxDistance > 128) maxDistance = 128;

        java.util.Set<String> matchNames = new java.util.HashSet<>();
        for (String t: targets) { matchNames.add(stripNamespace(t)); matchNames.add("minecraft:" + stripNamespace(t)); }

        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        BlockPos origin = player.blockPosition();
        java.util.List<BlockPos> found = new ArrayList<>();
        for (int dx = -maxDistance; dx <= maxDistance && found.size() < 64; dx++) {
            for (int dy = -maxDistance; dy <= maxDistance && found.size() < 64; dy++) {
                for (int dz = -maxDistance; dz <= maxDistance && found.size() < 64; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    String simple = simpleBlockName(level.getBlockState(pos));
                    if (matchNames.contains(simple)) found.add(pos);
                }
            }
        }
        if (found.isEmpty()) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", false);
            r.addProperty("reason", exploreFlag ? "no_known_targets": "no_known_targets");
            r.addProperty("message", "get-to-block: no_known_targets"
                    + (exploreFlag ? " (would explore — use the explore tool)": " (use explore to find some)"));
            return r;
        }
        found.sort((a, b) -> Long.compare(a.distManhattan(origin), b.distManhattan(origin)));

        BlockPos target = found.get(0);
        client.player.connection.sendChat("#goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        long deadline = System.currentTimeMillis() + 45000;
        boolean arrived = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(MOVE_POLL_INTERVAL_MS);
                double dx = player.getX() - target.getX();
                double dy = player.getY() - target.getY();
                double dz = player.getZ() - target.getZ();
                if (dx * dx + dy * dy + dz * dz <= 4.0) { arrived = true; break; }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            client.player.connection.sendChat("#stop");
        }

        boolean rightClicked = false;
        if (arrived && rightClick) {
            // Look at the block then use item on it (open chest/crafting table/furnace/door).
            lookAtBlock(client, target);
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5),
                            Direction.UP, target, false));
            player.swing(InteractionHand.MAIN_HAND);
            rightClicked = true;
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", arrived);
        result.addProperty("arrived", arrived);
        result.addProperty("rightClicked", rightClicked);
        result.addProperty("target", simpleBlockName(level.getBlockState(target)));
        result.add("position", arrived ? posJson(player.blockPosition()): posJson(target));
        result.addProperty("reason", arrived ? "reached": "timeout_or_unreachable");
        result.addProperty("message", arrived
                ? "Got to " + simpleBlockName(level.getBlockState(target)) + " at " + target.getX() + "," + target.getY() + "," + target.getZ()
                    + (rightClicked ? " (opened/used)": "")
: "get-to-block: timeout_or_unreachable");
        return result;
    }

    /**
     * explore: Simplified Baritone walk + scan. No WorldCache "uncached chunk"
     * concept in Fabric mode, so we pick a far destination biased by `direction`,
     * dispatch #goto, scan notable blocks along the way each poll tick, and report
     * chunksExplored + notablesFound. Documented simplification vs the legacy ExploreProcess.
     */
    private static JsonObject explore(Minecraft client, JsonObject args) {
        String direction = optionalString(args, "direction", "none");
        int maxRadius = optionalInt(args, "maxRadius", 16);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 30000);
        if (maxDurationMs < 1000) maxDurationMs = 1000;
        if (maxRadius < 1) maxRadius = 1; if (maxRadius > 64) maxRadius = 64;

        LocalPlayer player = client.player;
        BlockPos origin = player.blockPosition();
        // Destination: maxRadius chunks away in the biased direction.
        int distBlocks = maxRadius * EXPLORE_CHUNK_SIZE;
        int dx = 0, dz = 0;
        switch (direction) {
            case "north": dz = -distBlocks; break;
            case "south": dz = distBlocks; break;
            case "east": dx = distBlocks; break;
            case "west": dx = -distBlocks; break;
            default:
                // Spiral/emerald: pick a diagonal far point.
                dx = distBlocks; dz = distBlocks; break;
        }
        BlockPos dest = origin.offset(dx, 0, dz);
        client.player.connection.sendChat("#goto " + dest.getX() + " " + dest.getY() + " " + dest.getZ());

        long start = System.currentTimeMillis();
        long deadline = start + maxDurationMs;
        java.util.Set<String> notablesFound = new java.util.LinkedHashSet<>();
        int chunksExplored = 0;
        BlockPos lastChunk = player.blockPosition();
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(EXPLORE_POLL_INTERVAL_MS);
                BlockPos cur = player.blockPosition();
                // Count a chunk as explored when the player crosses a 16-block boundary.
                if (Math.abs(cur.getX() - lastChunk.getX()) >= EXPLORE_CHUNK_SIZE
                        || Math.abs(cur.getZ() - lastChunk.getZ()) >= EXPLORE_CHUNK_SIZE) {
                    chunksExplored++;
                    lastChunk = cur;
                }
                // Scan a small radius around the current position for notables.
                scanNotablesAround(client.level, cur, 4, notablesFound);
                // Reached destination?
                double ddx = player.getX() - dest.getX();
                double ddz = player.getZ() - dest.getZ();
                if (ddx * ddx + ddz * ddz <= 16.0) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            client.player.connection.sendChat("#stop");
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("chunksExplored", chunksExplored);
        JsonArray notableArr = new JsonArray();
        for (String n : notablesFound) notableArr.add(n);
        result.add("notablesFound", notableArr);
        result.addProperty("reason", "explored");
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("message", "Explored " + chunksExplored + " chunks, " + notablesFound.size()
                + " notables (" + direction + "" + maxRadius + " chunks)");
        return result;
    }

    /**
     * guard-area: Hold a post via Baritone, melee-attack hostiles that enter the
     * radius, then return to the post. Poll loop on the render thread. Mirrors
     * a simplified guard (no ProcessEngine priority/
     * temporary composition; melee only).
     */
    private static JsonObject guardArea(Minecraft client, JsonObject args) {
        int cx = optionalInt(args, "x", Integer.MIN_VALUE);
        int cy = optionalInt(args, "y", Integer.MIN_VALUE);
        int cz = optionalInt(args, "z", Integer.MIN_VALUE);
        double radius = optionalDouble(args, "radius", 10.0);
        long maxDurationMs = optionalInt(args, "maxDurationMs", 30000);
        if (radius < 1) radius = 1; if (radius > 64) radius = 64;
        if (maxDurationMs < 1000) maxDurationMs = 1000;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        BlockPos post = (cx != Integer.MIN_VALUE && cy != Integer.MIN_VALUE && cz != Integer.MIN_VALUE)
                ? new BlockPos(cx, cy, cz) : player.blockPosition();

        long start = System.currentTimeMillis();
        long deadline = start + maxDurationMs;
        int threatsEngaged = 0;
        int attacks = 0;
        boolean returnedToPost = false;
        String lastTarget = null;
        try {
            equipBestWeapon(client);
            while (System.currentTimeMillis() < deadline) {
                // Find the nearest hostile within the radius.
                Entity threat = null;
                double nearestDist = Double.MAX_VALUE;
                for (Entity entity : level.entitiesForRendering()) {
                    if (!(entity instanceof Enemy)) continue;
                    double dPost = Math.sqrt(entity.distanceToSqr(player.getX() - post.getX() + player.getX(),
                            player.getY() - post.getY() + player.getY(), player.getZ() - post.getZ() + player.getZ()));
                    // Simpler: distance from post.
                    double pdx = entity.getX() - post.getX();
                    double pdy = entity.getY() - post.getY();
                    double pdz = entity.getZ() - post.getZ();
                    double fromPost = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
                    if (fromPost > radius) continue;
                    double dToBot = entity.distanceToSqr(player);
                    if (dToBot < nearestDist) { threat = entity; nearestDist = dToBot; }
                }
                if (threat != null) {
                    threatsEngaged++;
                    lastTarget = simpleEntityName(threat);
                    // Path toward the threat then attack until it dies/leaves.
                    client.player.connection.sendChat("#goto " + (int) threat.getX() + " " + (int) threat.getY() + " " + (int) threat.getZ());
                    long threatDeadline = System.currentTimeMillis() + 10000;
                    while (System.currentTimeMillis() < threatDeadline) {
                        if (level.getEntity(threat.getId()) == null || threat.isRemoved() || !threat.isAlive()) break;
                        double dist = Math.sqrt(threat.distanceToSqr(player));
                        if (dist <= 3.5) {
                            client.player.connection.sendChat("#stop");
                            lookAtEntity(client, threat);
                            client.gameMode.attack(player, threat);
                            player.swing(InteractionHand.MAIN_HAND);
                            attacks++;
                            Thread.sleep(500);
                        } else {
                            Thread.sleep(GUARD_POLL_INTERVAL_MS);
                        }
                    }
                    client.player.connection.sendChat("#stop");
                } else {
                    // Return to post if far away.
                    double pdx = player.getX() - post.getX();
                    double pdz = player.getZ() - post.getZ();
                    if (pdx * pdx + pdz * pdz > 4.0) {
                        client.player.connection.sendChat("#goto " + post.getX() + " " + post.getY() + " " + post.getZ());
                    } else {
                        client.player.connection.sendChat("#stop");
                        returnedToPost = true;
                    }
                    Thread.sleep(GUARD_POLL_INTERVAL_MS);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            client.player.connection.sendChat("#stop");
        }

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("threatsEngaged", threatsEngaged);
        result.addProperty("attacks", attacks);
        result.addProperty("returnedToPost", returnedToPost);
        if (lastTarget != null) result.addProperty("lastTarget", lastTarget);
        result.addProperty("reason", "duration_elapsed");
        result.addProperty("durationMs", System.currentTimeMillis() - start);
        result.addProperty("message", "Guarded: " + threatsEngaged + " threat(s), " + attacks + " attack(s)"
                + (lastTarget != null ? " (last: " + lastTarget + ")": ""));
        return result;
    }

    // ── Tier J helpers ──

    /** Strip the "minecraft:" namespace from a registry name. */
    private static String stripNamespace(String name) {
        if (name == null) return "";
        String t = name.trim();
        int idx = t.indexOf(':');
        return idx >= 0 ? t.substring(idx + 1) : t;
    }

    /** Simple (namespace-stripped) block name from a BlockState. */
    private static String simpleBlockName(BlockState state) {
        String name = blockRegistryName(state);
        return stripNamespace(name);
    }

    /** Simple (namespace-stripped) item name from an ItemStack. */
    private static String simpleItemName(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return stripNamespace(itemRegistryName(stack));
    }

    /** Simple (namespace-stripped) entity type name. */
    private static String simpleEntityName(Entity entity) {
        return stripNamespace(entityRegistryName(entity));
    }

    /** Brief JSON description of an entity for the snapshot's nearby lists. */
    private static JsonObject describeEntityBrief(Entity entity, double distance) {
        JsonObject o = new JsonObject();
        o.addProperty("type", simpleEntityName(entity));
        o.addProperty("id", entity.getId());
        o.addProperty("x", Math.round(entity.getX() * 10.0) / 10.0);
        o.addProperty("y", Math.round(entity.getY() * 10.0) / 10.0);
        o.addProperty("z", Math.round(entity.getZ() * 10.0) / 10.0);
        o.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        if (entity instanceof LivingEntity living) {
            o.addProperty("health", living.getHealth());
            o.addProperty("maxHealth", living.getMaxHealth());
        }
        net.minecraft.network.chat.Component custom = entity.getCustomName();
        if (custom != null && !custom.getString().isEmpty()) o.addProperty("name", custom.getString());
        return o;
    }

    /** Convert a List<JsonObject> to a JsonArray. */
    private static JsonArray toJsonArray(java.util.List<JsonObject> list) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : list) arr.add(o);
        return arr;
    }

    /** Snapshot the player's inventory as a {simpleName: count} map. */
    private static java.util.Map<String, Integer> inventoryCounts(LocalPlayer player) {
        Inventory inv = player.getInventory();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String simple = simpleItemName(stack);
            if (simple == null) continue;
            counts.merge(simple, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Count item entities within radius matching the expected set (or all if null). */
    private static int countDrops(ClientLevel level, LocalPlayer player, int radius, java.util.Set<String> expected) {
        int count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (Math.sqrt(entity.distanceToSqr(player)) > radius) continue;
            if (expected != null && !expected.contains(itemRegistryName(itemEntity.getItem()))) continue;
            count++;
        }
        return count;
    }

    /** Encode a 2-bit-per-cell grid as base64 (LSB-first packing). */
    private static String encode2BitGrid(byte[] cells) {
        int packedLen = (cells.length + 3) / 4; // 4 cells per byte
        byte[] packed = new byte[packedLen];
        for (int i = 0; i < cells.length; i++) {
            int byteIdx = i / 4;
            int shift = (i % 4) * 2;
            packed[byteIdx] |= (byte) ((cells[i] & 0x03) << shift);
        }
        return java.util.Base64.getEncoder().encodeToString(packed);
    }

    /** Look at a block's center so a use-item-on lands on the correct face. */
    private static void lookAtBlock(Minecraft client, BlockPos target) {
        LocalPlayer player = client.player;
        double tx = target.getX() + 0.5;
        double ty = target.getY() + 0.5;
        double tz = target.getZ() + 0.5;
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horiz) * 180.0 / Math.PI);
        player.setYRot(yaw); player.setXRot(pitch);
        player.yRotO = yaw; player.xRotO = pitch;
        player.setYHeadRot(yaw);
    }

    /** Find a standable cell (solid floor + 2 passable above) near `center`. */
    private static BlockPos findStandableNear(ClientLevel level, BlockPos center, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos floor = center.offset(dx, dy - 1, dz);
                        BlockPos feet = center.offset(dx, dy, dz);
                        BlockPos head = center.offset(dx, dy + 1, dz);
                        BlockState floorState = level.getBlockState(floor);
                        if (floorState.isAir() || floorState.canBeReplaced()) continue;
                        if (level.getBlockState(feet).isAir() && level.getBlockState(head).isAir()) {
                            return feet;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Scan a small radius around `center` for notable blocks; add simple names to `out`. */
    private static void scanNotablesAround(ClientLevel level, BlockPos center, int radius, java.util.Set<String> out) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    String simple = simpleBlockName(level.getBlockState(center.offset(dx, dy, dz)));
                    if (NOTABLE_ORE_NAMES.contains(simple) || NOTABLE_LOG_NAMES.contains(simple)
                            || NOTABLE_CONTAINER_NAMES.contains(simple)) {
                        out.add(simple);
                    }
                }
            }
        }
    }

    /** Material family for a block name ("planks", "logs", "stone", etc.) or "" if none. */
    private static String describeFamily(String block) {
        String s = stripNamespace(block);
        if (s.endsWith("_planks") || s.equals("planks")) return "planks";
        if (s.endsWith("_log") || s.equals("log") || s.endsWith("_stem")) return "logs";
        if (s.endsWith("_ore") || s.contains("ore")) return "ores";
        if (s.endsWith("_door") || s.equals("door")) return "doors";
        if (s.endsWith("_fence") || s.equals("fence")) return "fences";
        if (s.endsWith("_slab") || s.equals("slab")) return "slabs";
        if (s.endsWith("_stairs") || s.equals("stairs")) return "stairs";
        if (s.endsWith("_button") || s.equals("button")) return "buttons";
        if (s.endsWith("_pressure_plate") || s.equals("pressure_plate")) return "pressure_plates";
        if (s.endsWith("_trapdoor") || s.equals("trapdoor")) return "trapdoors";
        if (s.endsWith("_sign") || s.endsWith("_wall_sign") || s.endsWith("_hanging_sign")) return "signs";
        if (s.endsWith("_wall") || s.equals("wall")) return "walls";
        return "";
    }

    /** Members of a material family (the block variants that can substitute). */
    private static java.util.List<String> familyMembers(String family) {
        switch (family) {
            case "planks": return java.util.List.of("oak_planks", "birch_planks", "spruce_planks", "jungle_planks",
                    "acacia_planks", "dark_oak_planks", "cherry_planks", "mangrove_planks",
                    "crimson_planks", "warped_planks", "bamboo_planks");
            case "logs": return java.util.List.of("oak_log", "birch_log", "spruce_log", "jungle_log",
                    "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log",
                    "crimson_stem", "warped_stem");
            case "doors": return java.util.List.of("oak_door", "birch_door", "spruce_door", "jungle_door",
                    "acacia_door", "dark_oak_door", "cherry_door", "mangrove_door", "crimson_door", "warped_door", "iron_door");
            case "trapdoors": return java.util.List.of("oak_trapdoor", "birch_trapdoor", "spruce_trapdoor",
                    "jungle_trapdoor", "acacia_trapdoor", "dark_oak_trapdoor", "cherry_trapdoor",
                    "mangrove_trapdoor", "crimson_trapdoor", "warped_trapdoor", "iron_trapdoor");
            case "fences": return java.util.List.of("oak_fence", "birch_fence", "spruce_fence", "jungle_fence",
                    "acacia_fence", "dark_oak_fence", "cherry_fence", "mangrove_fence", "nether_brick_fence");
            case "slabs": return java.util.List.of("oak_slab", "birch_slab", "spruce_slab", "jungle_slab",
                    "acacia_slab", "dark_oak_slab", "cherry_slab", "mangrove_slab", "crimson_slab", "warped_slab", "bamboo_slab");
            case "stairs": return java.util.List.of("oak_stairs", "birch_stairs", "spruce_stairs", "jungle_stairs",
                    "acacia_stairs", "dark_oak_stairs", "cherry_stairs", "mangrove_stairs", "crimson_stairs", "warped_stairs", "bamboo_stairs");
            default: return java.util.List.of();
        }
    }

    // ── Tier I helpers ──

    /**
     * Sample 1-block steps along the straight line from→to and report hazardous
     * blocks (lava, fire, cactus, magma, powder snow) and cliff drops (>3 air below).
     */
    private static JsonArray scanCorridorHazards(ClientLevel level, BlockPos from, BlockPos to) {
        JsonArray hazards = new JsonArray();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.max(1, Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        double sx = dx / length, sy = dy / length, sz = dz / length;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i <= length && hazards.size() < 20; i++) {
            int px = (int) Math.floor(from.getX() + sx * i);
            int py = (int) Math.floor(from.getY() + sy * i);
            int pz = (int) Math.floor(from.getZ() + sz * i);
            String key = px + "," + pz;
            if (!seen.add(key)) continue;
            for (int oy = 1; oy >= -1; oy--) {
                BlockPos pos = new BlockPos(px, py + oy, pz);
                BlockState state = level.getBlockState(pos);
                String name = blockRegistryName(state);
                String simple = name.contains(":") ? name.substring(name.indexOf(':') + 1): name;
                if (HAZARD_BLOCK_NAMES.contains(simple) || FLUID_BLOCK_NAMES.contains(simple)) {
                    JsonObject h = new JsonObject();
                    h.addProperty("type", simple);
                    h.addProperty("x", pos.getX());
                    h.addProperty("y", pos.getY());
                    h.addProperty("z", pos.getZ());
                    hazards.add(h);
                    break;
                }
            }
            // Cliff: >3 air below.
            int airBelow = 0;
            for (int oy = -1; oy >= -4; oy--) {
                BlockState below = level.getBlockState(new BlockPos(px, py + oy, pz));
                if (!below.isAir() && !below.canBeReplaced()) break;
                airBelow++;
            }
            if (airBelow > 3 && hazards.size() < 20) {
                JsonObject h = new JsonObject();
                h.addProperty("type", "cliff");
                h.addProperty("x", px);
                h.addProperty("y", py);
                h.addProperty("z", pz);
                hazards.add(h);
            }
        }
        return hazards;
    }

    /** Perpendicular distance from a point to the segment (fx,fy,fz)→(tx,ty,tz). */
    private static double pointToSegmentDistance(
            double fx, double fy, double fz,
            double tx, double ty, double tz,
            double px, double py, double pz) {
        double segDx = tx - fx, segDy = ty - fy, segDz = tz - fz;
        double lenSq = segDx * segDx + segDy * segDy + segDz * segDz;
        double t = lenSq == 0 ? 0 : ((px - fx) * segDx + (py - fy) * segDy + (pz - fz) * segDz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double cx = fx + t * segDx, cy = fy + t * segDy, cz = fz + t * segDz;
        double ddx = px - cx, ddy = py - cy, ddz = pz - cz;
        return Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
    }

    // ── Tier H helpers ──

    private static JsonObject errorJson(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static JsonObject posJson(BlockPos pos) {
        JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        return p;
    }

    /** Resolve an attack target by registry name, custom name, or 'nearest_hostile'. */
    private static Entity resolveAttackTarget(ClientLevel level, LocalPlayer player, String entityName) {
        if (entityName.equalsIgnoreCase("nearest_hostile")) {
            Entity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == player) continue;
                if (!(entity instanceof Enemy)) continue;
                double dist = entity.distanceToSqr(player);
                if (dist < nearestDist) {
                    nearest = entity;
                    nearestDist = dist;
                }
            }
            return nearest;
        }
        String needle = entityName.toLowerCase();
        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player) continue;
            String regName = entityRegistryName(entity).toLowerCase();
            boolean nameMatch = regName.contains(needle);
            // Also match custom name (e.g. players with usernames, renamed entities).
            if (!nameMatch) {
                net.minecraft.network.chat.Component custom = entity.getCustomName();
                if (custom != null && custom.getString().toLowerCase().contains(needle)) {
                    nameMatch = true;
                }
            }
            if (!nameMatch) continue;
            double dist = entity.distanceToSqr(player);
            if (dist < nearestDist) {
                nearest = entity;
                nearestDist = dist;
            }
        }
        return nearest;
    }

    /** Look at the torso of an entity (eye height * 0.8). */
    private static void lookAtEntity(Minecraft client, Entity target) {
        LocalPlayer player = client.player;
        double tx = target.getX();
        double ty = target.getY() + target.getEyeHeight() * 0.8;
        double tz = target.getZ();
        Vec3 eye = player.getEyePosition(0.0f);
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (-Math.atan2(dy, horizontalDistance) * 180.0 / Math.PI);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
    }

    /** A short human-readable label for a combat target. */
    private static String describeEntityForCombat(Entity target) {
        String reg = entityRegistryName(target);
        String simple = reg.contains(":") ? reg.substring(reg.indexOf(':') + 1): reg;
        net.minecraft.network.chat.Component custom = target.getCustomName();
        if (custom != null && !custom.getString().isEmpty()) {
            return custom.getString() + " (" + simple + ")";
        }
        return simple;
    }

    /** Label for a dropped item entity: "item_name x<count>". */
    private static String describeDrop(Entity drop) {
        if (drop instanceof ItemEntity itemEntity) {
            String name = itemRegistryName(itemEntity.getItem());
            String simple = name.contains(":") ? name.substring(name.indexOf(':') + 1): name;
            int count = itemEntity.getItem().getCount();
            return simple + " x" + count;
        }
        return "item";
    }

    /** Equip the best weapon in the hotbar/inventory, preferring swords then axes. */
    private static String equipBestWeapon(Minecraft client) {
        // Close any stale open container menu before clicking.
        if (client.player.containerMenu.containerId != 0) closeContainer(client);
        Inventory inv = client.player.getInventory();
        for (String weaponName : WEAPON_PRIORITY) {
            String target = "minecraft:" + weaponName;
            // Hotbar first (0-8).
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                if (name.equals(target)) {
                    syncSelectedSlot(client, i);
                    return weaponName;
                }
            }
        }
        // Not in hotbar — search main inventory and swap into the selected slot.
        for (String weaponName : WEAPON_PRIORITY) {
            String target = "minecraft:" + weaponName;
            for (int i = 9; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                String name = itemRegistryName(stack);
                if (name.equals(target)) {
                    int destSlot = client.player.getInventory().getSelectedSlot();
                    int syncId = client.player.containerMenu.containerId;
                    client.gameMode.handleContainerInput(
                            syncId, invSlotToWindow(i), destSlot,
                            net.minecraft.world.inventory.ContainerInput.SWAP, client.player);
                    syncSelectedSlot(client, destSlot);
                    return weaponName;
                }
            }
        }
        return null;
    }

    /**
     * Find the nearest bed HEAD-part block within a cubed radius of the origin.
     * Returns the BlockPos of the HEAD part (the block startSleepInBed expects),
     * or null if none found.
     */
    private static BlockPos findNearestBedHead(ClientLevel level, BlockPos origin, int radius) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof BedBlock)) continue;
                    // We want the HEAD part — sleeping uses the head block position.
                    BedPart part = state.getValue(BedBlock.PART);
                    if (part != BedPart.HEAD) continue;
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (dist < nearestDist) {
                        nearest = pos;
                        nearestDist = dist;
                    }
                }
            }
        }
        return nearest;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier K: Meteor Client integration (soft dependency via reflection)
    //
    // Meteor Client (https://meteorclient.com/) is a client-side utility mod
    // that provides high-performance modules like KillAura, AutoEat, etc.
    // The bridge declares `suggests: { meteor-client: "*" }` in fabric.mod.json.
    // These handlers use reflection so the bridge works whether or not Meteor
    // is installed — no compile-time dependency on Meteor classes.
    //
    // API surface used (all via reflection):
    // meteordevelopment.meteorclient.systems.modules.Modules.get() → Modules
    // Modules.get(String name) → Module (case-insensitive)
    // Module.toggle() → void (toggles active state)
    // Module.enable() → void (activates if not active)
    // Module.disable() → void (deactivates if active)
    // Module.isActive() → boolean
    // Module.name → String (public field)
    // Module.keybind → Keybind (public field)
    // Keybind.set(boolean isKey, int value, int modifiers) → void
    // Keybind.toString() → human-readable label
    // meteordevelopment.meteorclient.systems.Systems.save() → void (persist)
    // ──────────────────────────────────────────────────────────────────────

    /** Cached Meteor Modules singleton (resolved once via reflection). */
    private static volatile Object cachedMeteorModules = null;
    private static volatile boolean meteorChecked = false;

    /** True when Meteor Client is installed and the Modules system is reachable. */
    private static boolean meteorPresent() {
        if (meteorChecked) return cachedMeteorModules != null;
        meteorChecked = true;
        try {
            Class<?> systemsClass = Class.forName("meteordevelopment.meteorclient.systems.Systems");
            java.lang.reflect.Method getMethod = systemsClass.getMethod("get", Class.class);
            Class<?> modulesClass = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            cachedMeteorModules = getMethod.invoke(null, modulesClass);
        } catch (Throwable ignored) {
            cachedMeteorModules = null;
        }
        return cachedMeteorModules != null;
    }

    /**
     * toggle-meteor-module: Toggle (or explicitly enable/disable) a Meteor Client
     * module by name. Non-blocking — the module's own tick handler does the work.
     *
     * Args:
     * module (required) — module name, e.g. "KillAura", "AutoEat"
     * action (optional) — "toggle" (default), "enable", "disable"
     *
     * Returns: { ok, module, wasActive, isActive, action }
     */
    private static JsonObject toggleMeteorModule(Minecraft client, JsonObject args) {
        String moduleName = requiredString(args, "module");
        String action = optionalString(args, "action", "toggle");

        if (!meteorPresent()) {
            return meteorAbsentJson("toggle-meteor-module");
        }

        Object module = resolveMeteorModule(moduleName);
        if (module == null) {
            return errorJson("No Meteor module named '" + moduleName + "' found. "
                    + "Use list-meteor-modules to see available modules.");
        }

        try {
            java.lang.reflect.Method isActiveMethod = module.getClass().getMethod("isActive");
            boolean wasActive = (boolean) isActiveMethod.invoke(module);

            String effectiveAction = action.toLowerCase();
            java.lang.reflect.Method targetMethod;
            switch (effectiveAction) {
                case "enable":
                    targetMethod = module.getClass().getMethod("enable");
                    break;
                case "disable":
                    targetMethod = module.getClass().getMethod("disable");
                    break;
                case "toggle":
                default:
                    targetMethod = module.getClass().getMethod("toggle");
                    effectiveAction = "toggle";
                    break;
            }
            targetMethod.invoke(module);

            boolean nowActive = (boolean) isActiveMethod.invoke(module);

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("module", moduleName);
            result.addProperty("action", effectiveAction);
            result.addProperty("wasActive", wasActive);
            result.addProperty("isActive", nowActive);
            result.addProperty("status", nowActive ? "on": "off");
            return result;
        } catch (Throwable error) {
            return errorJson("Failed to toggle Meteor module '" + moduleName + "': " + error.getMessage());
        }
    }

    /**
     * set-meteor-keybind: Assign a keyboard hotkey to a Meteor module.
     * Persists the keybind via Systems.save() so it survives restarts.
     *
     * Args:
     * module (required) — module name, e.g. "KillAura"
     * key (required) — key name (single char "C", or named: "F5", "SPACE",
     * "LEFT_SHIFT", "NUM_1", etc.) — case-insensitive
     * modifiers (optional) — array of modifier names: "shift", "ctrl", "alt", "super"
     * (default: none)
     *
     * Returns: { ok, module, key, keybindLabel, modifiers }
     */
    private static JsonObject setMeteorKeybind(Minecraft client, JsonObject args) {
        String moduleName = requiredString(args, "module");
        String keyName = requiredString(args, "key");

        if (!meteorPresent()) {
            return meteorAbsentJson("set-meteor-keybind");
        }

        Object module = resolveMeteorModule(moduleName);
        if (module == null) {
            return errorJson("No Meteor module named '" + moduleName + "' found.");
        }

        int glfwKey = parseGlfwKey(keyName);
        if (glfwKey < 0) {
            return errorJson("Unknown key name '" + keyName + "'. Use a single letter (A-Z, 0-9), "
                    + "or a named key like SPACE, LEFT_SHIFT, F1-F12, NUM_0-NUM_9.");
        }

        int modifierFlags = parseModifiers(args);

        try {
            // Module.keybind is a public field of type Keybind
            java.lang.reflect.Field keybindField = module.getClass().getField("keybind");
            Object keybind = keybindField.get(module);

            // Keybind.set(boolean isKey, int value, int modifiers)
            java.lang.reflect.Method setMethod = keybind.getClass().getMethod("set", boolean.class, int.class, int.class);
            setMethod.invoke(keybind, true, glfwKey, modifierFlags);

            String keybindLabel = keybind.toString();

            // Persist to Meteor's config so the keybind survives restarts
            persistMeteorSystems();

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("module", moduleName);
            result.addProperty("key", keyName.toUpperCase());
            result.addProperty("glfwCode", glfwKey);
            result.addProperty("keybindLabel", keybindLabel);
            result.addProperty("modifiers", modifierFlags);
            return result;
        } catch (Throwable error) {
            return errorJson("Failed to set keybind for '" + moduleName + "': " + error.getMessage());
        }
    }

    /**
     * list-meteor-modules: List all available Meteor modules with their active
     * state and current keybind. Useful for discovery and verification.
     *
     * Args:
     * filter (optional) — substring filter on module name (case-insensitive)
     *
     * Returns: { ok, count, modules: [{ name, isActive, keybind }] }
     */
    private static JsonObject listMeteorModules(Minecraft client, JsonObject args) {
        String filter = optionalString(args, "filter", "").toLowerCase();

        if (!meteorPresent()) {
            return meteorAbsentJson("list-meteor-modules");
        }

        try {
            Object modules = cachedMeteorModules;
            // Modules.getAll() → Collection<Module>
            java.lang.reflect.Method getAllMethod = modules.getClass().getMethod("getAll");
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> allModules = (java.util.Collection<Object>) getAllMethod.invoke(modules);

            JsonArray moduleArray = new JsonArray();
            int count = 0;
            for (Object mod : allModules) {
                String name = readMeteorModuleName(mod);
                if (name == null) continue;
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;

                java.lang.reflect.Method isActiveMethod = mod.getClass().getMethod("isActive");
                boolean active = (boolean) isActiveMethod.invoke(mod);

                java.lang.reflect.Field keybindField = mod.getClass().getField("keybind");
                Object keybind = keybindField.get(mod);
                String keybindLabel = keybind != null ? keybind.toString(): "None";

                JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                entry.addProperty("isActive", active);
                entry.addProperty("keybind", keybindLabel);
                moduleArray.add(entry);
                count++;
            }

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("count", count);
            result.add("modules", moduleArray);
            return result;
        } catch (Throwable error) {
            return errorJson("Failed to list Meteor modules: " + error.getMessage());
        }
    }

    // ── Meteor reflection helpers ──────────────────────────────────────────

    /**
     * Read a Meteor Module's name as a String, handling both legacy (plain String
     * field) and newer Meteor versions (StringSetting field). In newer Meteor,
     * Module.name is a StringSetting; calling.get() on it returns the actual
     * String value.
     */
    private static String readMeteorModuleName(Object mod) {
        try {
            java.lang.reflect.Field nameField = mod.getClass().getField("name");
            Object nameVal = nameField.get(mod);
            if (nameVal instanceof String) return (String) nameVal;
            // StringSetting (or any Setting<String>) — call.get() to extract the value
            java.lang.reflect.Method getMethod = nameVal.getClass().getMethod("get");
            Object result = getMethod.invoke(nameVal);
            return result != null ? result.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolve a Meteor Module by name (case-insensitive).
     * Iterates over all modules manually so we are independent of Meteor's
     * internal Modules.get(String) implementation, which may also be broken
     * when Module.name is a StringSetting rather than a plain String.
     */
    private static Object resolveMeteorModule(String name) {
        try {
            // First try Meteor's own lookup (works on older Meteor versions)
            Object modules = cachedMeteorModules;
            try {
                java.lang.reflect.Method getMethod = modules.getClass().getMethod("get", String.class);
                Object result = getMethod.invoke(modules, name);
                if (result != null) return result;
            } catch (Throwable ignored) {
                // Fall through to manual iteration
            }
            // Manual iteration fallback (handles StringSetting name fields)
            java.lang.reflect.Method getAllMethod = modules.getClass().getMethod("getAll");
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> allModules = (java.util.Collection<Object>) getAllMethod.invoke(modules);
            String lowerName = name.toLowerCase();
            for (Object mod : allModules) {
                String modName = readMeteorModuleName(mod);
                if (modName != null && modName.toLowerCase().equals(lowerName)) return mod;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Call Systems.save() to persist Meteor module config (keybinds, active state). */
    private static void persistMeteorSystems() {
        try {
            Class<?> systemsClass = Class.forName("meteordevelopment.meteorclient.systems.Systems");
            java.lang.reflect.Method saveMethod = systemsClass.getMethod("save");
            saveMethod.invoke(null);
        } catch (Throwable ignored) {
            // Best-effort persistence; keybind still works in-session without it.
        }
    }

    /** Standard error response when Meteor Client is not installed. */
    private static JsonObject meteorAbsentJson(String toolName) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("meteorInstalled", false);
        result.addProperty("error", "Meteor Client is not installed. Install the Meteor Client mod "
                + "to use " + toolName + ". The bridge declares `suggests: meteor-client` — it is optional.");
        return result;
    }

    /**
     * Parse a key name to its GLFW key code.
     * Accepts: single letters A-Z, digits 0-9, function keys F1-F25,
     * named keys (SPACE, ENTER, LEFT_SHIFT, etc.), and numeric keypad NUM_0-NUM_9.
     * Returns -1 for unrecognized keys.
     */
    private static int parseGlfwKey(String keyName) {
        if (keyName == null || keyName.isEmpty()) return -1;
        String upper = keyName.trim().toUpperCase();

        // GLFW constant names: GLFW_KEY_<NAME>
        // We map common names to their GLFW constants.
        switch (upper) {
            case "SPACE": case "SPACEBAR": return org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
            case "APOSTROPHE": case "APOS": return org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
            case "COMMA": return org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA;
            case "MINUS": case "DASH": return org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
            case "PERIOD": case "DOT": return org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD;
            case "SLASH": return org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH;
            case "SEMICOLON": case "SEMI": return org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON;
            case "EQUAL": case "EQUALS": return org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
            case "LEFT_BRACKET": case "LBRACKET": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
            case "RIGHT_BRACKET": case "RBRACKET": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
            case "BACKSLASH": return org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH;
            case "GRAVE": case "BACKTICK": return org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "ENTER": case "RETURN": return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
            case "TAB": return org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
            case "BACKSPACE": case "BACK": return org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
            case "INSERT": return org.lwjgl.glfw.GLFW.GLFW_KEY_INSERT;
            case "DELETE": case "DEL": return org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
            case "RIGHT": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
            case "LEFT": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
            case "DOWN": return org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
            case "UP": return org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
            case "PAGE_UP": case "PAGEUP": return org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
            case "PAGE_DOWN": case "PAGEDOWN": return org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
            case "HOME": return org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
            case "END": return org.lwjgl.glfw.GLFW.GLFW_KEY_END;
            case "CAPS_LOCK": case "CAPSLOCK": return org.lwjgl.glfw.GLFW.GLFW_KEY_CAPS_LOCK;
            case "SCROLL_LOCK": case "SCROLLLOCK": return org.lwjgl.glfw.GLFW.GLFW_KEY_SCROLL_LOCK;
            case "NUM_LOCK": case "NUMLOCK": return org.lwjgl.glfw.GLFW.GLFW_KEY_NUM_LOCK;
            case "ESCAPE": case "ESC": return org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
            // Modifier keys (can be bound, not just used as modifiers)
            case "LEFT_SHIFT": case "LSHIFT": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RIGHT_SHIFT": case "RSHIFT": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "LEFT_CONTROL": case "LCTRL": case "LEFT_CTRL": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RIGHT_CONTROL": case "RCTRL": case "RIGHT_CTRL": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "LEFT_ALT": case "LALT": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
            case "RIGHT_ALT": case "RALT": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT;
            case "LEFT_SUPER": case "LSUPER": case "LEFT_CMD": return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
            case "RIGHT_SUPER": case "RSUPER": case "RIGHT_CMD": return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
            default: break;
        }

        // Function keys F1-F25
        if (upper.matches("F([1-9]|1[0-9]|2[0-5])")) {
            int num = Integer.parseInt(upper.substring(1));
            return org.lwjgl.glfw.GLFW.GLFW_KEY_F1 + (num - 1);
        }

        // Numeric keypad NUM_0-NUM_9
        if (upper.matches("NUM_([0-9])")) {
            int num = Integer.parseInt(upper.substring(4));
            return org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0 + num;
        }

        // Single letter A-Z → GLFW_KEY_A through GLFW_KEY_Z
        if (upper.length() == 1) {
            char c = upper.charAt(0);
            if (c >= 'A' && c <= 'Z') return org.lwjgl.glfw.GLFW.GLFW_KEY_A + (c - 'A');
            // Single digit 0-9 → GLFW_KEY_0 through GLFW_KEY_9
            if (c >= '0' && c <= '9') return org.lwjgl.glfw.GLFW.GLFW_KEY_0 + (c - '0');
        }

        return -1; // unrecognized
    }

    /**
     * Parse the optional "modifiers" array into GLFW modifier bit flags.
     * Accepts: "shift", "ctrl" (or "control"), "alt", "super" (or "cmd", "win")
     */
    private static int parseModifiers(JsonObject args) {
        JsonElement modsElement = args.get("modifiers");
        if (modsElement == null || modsElement.isJsonNull() || !modsElement.isJsonArray()) {
            return 0;
        }
        JsonArray modsArray = modsElement.getAsJsonArray();
        int flags = 0;
        for (JsonElement el : modsArray) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
            String mod = el.getAsString().trim().toLowerCase();
            switch (mod) {
                case "shift": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT; break;
                case "ctrl": case "control": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL; break;
                case "alt": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_ALT; break;
                case "super": case "cmd": case "win": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER; break;
                case "capslock": case "caps_lock": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_CAPS_LOCK; break;
                case "numlock": case "num_lock": flags |= org.lwjgl.glfw.GLFW.GLFW_MOD_NUM_LOCK; break;
                default: break;
            }
        }
        return flags;
    }

    public static final class ToolException extends RuntimeException {
        private final String code;
        public ToolException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
