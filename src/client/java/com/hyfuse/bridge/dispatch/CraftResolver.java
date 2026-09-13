package com.hyfuse.bridge.dispatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CraftResolver — craft-with-deps support.
 *
 * Recursive recipe resolution with cycle detection (iron ← iron_block ←
 * iron) and a depth cap (default 4), producing
 * the deepest-first `subCrafts` trail plus the gather-leaf `shortfall` after
 * subtracting the bot's inventory. A node with no recipe is a GATHER leaf
 * (mine/find it, not craft). This is a READ — `craft-item`/`smelt-item` do
 * the actual crafting.
 *
 * Layering mirrors the legacy: this class is framework-free (plain Java, no
 * Minecraft imports) so the resolve/flatten/shortfall logic is unit-testable
 * offline — same design point as this package's
 * QueueTelemetry. The RecipeLookup interface is injected; the ToolDispatcher
 * handler builds it from the client recipe book + a smelting table.
 */
final class CraftResolver {

    private CraftResolver() {
    }

    /** Default depth cap. */
    static final int DEFAULT_CRAFT_DEPTH = 4;

    /** A single ingredient (name + count). the legacy parity. */
    static final class Ingredient {
        final String name;
        final int count;

        Ingredient(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    /** A resolved recipe (craft or smelt), name-based. the legacy parity. */
    static final class ResolvedRecipe {
        final String result;
        final int resultCount;
        final List<Ingredient> ingredients;
        final boolean smelt; // false = craft, true = smelt

        ResolvedRecipe(String result, int resultCount, List<Ingredient> ingredients, boolean smelt) {
            this.result = result;
            this.resultCount = resultCount;
            this.ingredients = ingredients;
            this.smelt = smelt;
        }
    }

    /**
     * The recipe source the resolver queries. Returns ALL recipes for an item;
     * the resolver picks the best. Empty list (or null) = no recipe (a gather
     * leaf).
     */
    interface RecipeLookup {
        List<ResolvedRecipe> recipes(String itemName);
    }

    /** A node in the craft tree. the legacy parity. */
    static final class CraftNode {
        final String item;
        final int count;
        ResolvedRecipe recipe;     // null = gather leaf
        final List<CraftNode> subCrafts = new ArrayList<>();
        boolean cycle;
        final int depth;

        CraftNode(String item, int count, int depth) {
            this.item = item;
            this.count = count;
            this.depth = depth;
        }
    }

    /** A flat trail entry. the legacy parity (kind: craft | smelt | gather). */
    static final class TrailEntry {
        final String item;
        final int count;
        final String kind; // "craft" | "smelt" | "gather"
        final List<Ingredient> ingredients;
        final int depth;

        TrailEntry(String item, int count, String kind, List<Ingredient> ingredients, int depth) {
            this.item = item;
            this.count = count;
            this.kind = kind;
            this.ingredients = ingredients;
            this.depth = depth;
        }
    }

    // ── resolveCraftTree — the recursive core ─────────────────────────────

    /**
     * Resolve the craft tree for {@code target} × {@code count}. Picks the best
     * recipe for each item, recurses on each ingredient, detects cycles, and
     * caps at {@code maxDepth}. Items with no recipe are gather leaves.
     */
    static CraftNode resolveCraftTree(String target, int count, RecipeLookup lookup, int maxDepth) {
        return resolveNode(target, count, lookup, 0, maxDepth, new ArrayDeque<>());
    }

    private static CraftNode resolveNode(String item, int count, RecipeLookup lookup,
                                         int depth, int maxDepth, Deque<String> path) {
        // Cycle: this item is already on the current path → mark + stop.
        if (path.contains(item)) {
            CraftNode n = new CraftNode(item, count, depth);
            n.cycle = true;
            return n;
        }
        // Depth cap: stop recursing (treat as a gather leaf at this depth).
        if (depth >= maxDepth) {
            return new CraftNode(item, count, depth);
        }
        List<ResolvedRecipe> recipes = lookup.recipes(item);
        if (recipes == null || recipes.isEmpty()) {
            return new CraftNode(item, count, depth); // gather leaf
        }
        ResolvedRecipe recipe = pickBestRecipe(recipes);
        CraftNode n = new CraftNode(item, count, depth);
        n.recipe = recipe;
        // Scale ingredient counts to produce `count` of the result.
        int batches = Math.ceilDiv(count, recipe.resultCount);
        path.push(item);
        try {
            for (Ingredient ing : recipe.ingredients) {
                n.subCrafts.add(resolveNode(ing.name, ing.count * batches, lookup, depth + 1, maxDepth, path));
            }
        } finally {
            path.pop();
        }
        return n;
    }

    /**
     * Pick the best recipe: fewest distinct ingredients, then smelt over craft
     * (the natural gather→refined direction — avoids block-compression cycles
     * like iron_ingot ← iron_block ← iron_ingot), then highest yield.
     * Lower score is better. Stable on input order.
     */
    static ResolvedRecipe pickBestRecipe(List<ResolvedRecipe> recipes) {
        ResolvedRecipe best = recipes.get(0);
        int bestScore = scoreRecipe(best);
        for (int i = 1; i < recipes.size(); i++) {
            int s = scoreRecipe(recipes.get(i));
            if (s < bestScore) {
                best = recipes.get(i);
                bestScore = s;
            }
        }
        return best;
    }

    private static int scoreRecipe(ResolvedRecipe r) {
        return r.ingredients.size() * 1000 + (r.smelt ? 0 : 100) - r.resultCount;
    }

    // ── flattenTrail — deepest-first subCrafts list ────────────────────────

    /**
     * Flatten the tree into a linear trail, ordered deepest-first (craft the
     * leaves before the root). Cycles are skipped. Gather leaves appear as
     * {item, count, kind:"gather"} so the caller knows what to mine/find.
     */
    static List<TrailEntry> flattenTrail(CraftNode node) {
        List<TrailEntry> out = new ArrayList<>();
        walk(node, out);
        out.sort((a, b) -> Integer.compare(b.depth, a.depth));
        return out;
    }

    private static void walk(CraftNode node, List<TrailEntry> out) {
        if (node.cycle) return;
        if (node.recipe != null) {
            out.add(new TrailEntry(node.item, node.count, node.recipe.smelt ? "smelt": "craft",
                    node.recipe.ingredients, node.depth));
        } else {
            out.add(new TrailEntry(node.item, node.count, "gather", List.of(), node.depth));
        }
        for (CraftNode child : node.subCrafts) walk(child, out);
    }

    // ── computeShortfall — what's still missing after inventory ───────────

    /**
     * Walk the tree and compute the gather-leaf shortfall: for each gather
     * leaf (no recipe, no cycle), how many the bot still needs after
     * subtracting what it holds. Fully-held leaves are omitted. Returns the
     * {name: needed} map (insertion-ordered for stable output).
     */
    static Map<String, Integer> computeShortfall(CraftNode node, Map<String, Integer> inventory) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        collectLeaves(node, needed);
        if (inventory == null) return needed;
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            int have = inventory.getOrDefault(e.getKey(), 0);
            int missing = Math.max(0, e.getValue() - have);
            if (missing > 0) shortfall.put(e.getKey(), missing);
        }
        return shortfall;
    }

    private static void collectLeaves(CraftNode node, Map<String, Integer> out) {
        if (node.cycle) return;
        if (node.recipe == null) {
            out.merge(node.item, node.count, Integer::sum);
            return;
        }
        for (CraftNode child : node.subCrafts) collectLeaves(child, out);
    }

    /**
     * True if any node in the tree carries a cycle flag (the tool's `cycle`
     * output field).
     */
    static boolean hasCycle(CraftNode node) {
        if (node.cycle) return true;
        for (CraftNode child : node.subCrafts) {
            if (hasCycle(child)) return true;
        }
        return false;
    }
}
