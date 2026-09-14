#!/usr/bin/env python3
"""Generate docs/TOOL_CATALOG.md from the real MCP tool registry source.

Reads src/client/java/com/hyfuse/bridge/mcp/McpToolRegistry.java and extracts
each register("name", <desc expr>, objectSchema(<arg decls>)) block.
Categories are curated here; counts are computed, not hand-counted.
Every tool must be categorized exactly once — the script fails loudly if the
registry and the category map drift apart.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/client/java/com/hyfuse/bridge/mcp/McpToolRegistry.java"
OUT = ROOT / "docs/TOOL_CATALOG.md"

CATEGORIES = [
    ("Sensing & world", "sensing--world",
     "Perception: everything the agent uses to build a picture of the world "
     "and itself before acting. Read-only, parallel-safe.",
     ["get-agent-snapshot", "get-block-info", "get-blocks", "get-block-light",
      "find-blocks", "find-ore-veins", "scan-area", "scan-volume", "raycast-look",
      "find-entity", "scan-nearby-entities", "get-events", "get-world-time",
      "get-weather", "detect-gamemode", "get-last-death"]),
    ("Movement & navigation", "movement--navigation",
     "Getting from A to B — all pathfinding is delegated to Baritone. The "
     "reflexes (stuck recovery, water escape) run without an LLM in the loop.",
     ["navigate-v2", "goto-coords", "path-safely", "find-safe-location", "explore",
      "get-to-block", "follow-player", "follow-entity", "move-in-direction",
      "recover-stuck", "escape-water", "fly-to", "set-movement-profile"]),
    ("Blocks & building", "blocks--building",
     "Direct world manipulation, from single blocks to blueprint structures.",
     ["dig-block", "place-block", "use-item-on-block", "build-structure", "look-at", "place-torch"]),
    ("Crafting & processing", "crafting--processing",
     "The recipe pipeline: resolution against the live recipe book, recursive "
     "dependency planning, furnace operation, and body maintenance.",
     ["craft-item", "can-craft", "craft-with-deps", "smelt-item", "resolve-material",
      "eat-food", "sleep-in-bed"]),
    ("Combat & survival", "combat--survival",
     "Threat response. Doctrine: Meteor KillAura for combat when present, "
     "built-in handlers otherwise; Baritone for positioning.",
     ["attack-entity", "guard-area", "flee-from", "toggle-meteor-module",
      "set-meteor-keybind", "list-meteor-modules", "auto-equip-best-gear"]),
    ("Inventory & containers", "inventory--containers",
     "Item management: self-inventory, chests and barrels, and honest move "
     "accounting (moved vs requested vs remaining).",
     ["list-inventory", "find-item", "equip-item", "move-item", "organize-inventory",
      "open-container", "deposit-items", "withdraw-items", "collect-drops"]),
    ("Processes & queues", "processes--queues",
     "The economics layer: run multi-tool goal chains as one server-side "
     "composite, and standing processes that keep working after the chat ends.",
     ["enqueue-tasks", "mine-blocks", "get-to-block", "explore", "guard-area",
      "standing-start", "standing-status", "standing-stop",
      "cancel-current-action", "get-current-action"]),
    ("Memory, journal & policy", "memory--journal",
     "Persistent state and reactive intelligence. Policies fire from the "
     "EventBuffer before the LLM is ever consulted.",
     ["memory-save", "memory-read", "memory-forget", "journal-read", "journal-append",
      "policy-save", "policy-read", "policy-forget", "get-playbook"]),
    ("Agent & operator", "agent--operator",
     "The embedded brain loop, capability negotiation, and chat I/O.",
     ["agent-start", "agent-stop", "agent-status", "get-capabilities", "send-chat",
      "read-chat"]),
]
# tools intentionally listed in two categories:
DUPES = {"get-to-block", "explore", "guard-area", "find-item", "find-blocks"}

def parse_registry(text):
    """Split the file at each register("...") boundary: everything up to the
    next register() (or EOF) is that tool's body — description literals first,
    then the objectSchema block with its typed arg declarations."""
    tools = {}
    parts = re.split(r'register\("([a-z0-9-]+)"\s*,', text)
    # parts: [pre, name1, body1, name2, body2, ...]
    for i in range(1, len(parts) - 1, 2):
        name, body = parts[i], parts[i + 1]
        head = body.split("objectSchema", 1)[0]
        desc = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', head)).replace('\\"', '"')
        args = []
        seen = set()
        for a in re.finditer(
                r'\.(string|integer|number|bool|enumeration|array|object)\(\s*"([a-zA-Z0-9_]+)"',
                body):
            kind, arg = a.group(1), a.group(2)
            if (arg, kind) not in seen:
                seen.add((arg, kind))
                args.append((arg, kind))
        if name not in tools:  # first registration wins
            tools[name] = {"desc": desc.strip(), "args": args}
    return tools

TYPE = {"string": "string", "integer": "int", "number": "number", "bool": "bool",
        "enumeration": "enum", "array": "array", "object": "object"}

def main():
    tools = parse_registry(SRC.read_text())
    categorized = [t for _, _, _, lst in CATEGORIES for t in lst]
    dupes_ok = {t for t in categorized if categorized.count(t) > 1} <= DUPES
    missing = [n for n in tools if n not in categorized]
    unknown = [t for t in categorized if t not in tools]
    assert dupes_ok, "uncategorized duplicate appeared"
    assert not missing, f"registry tools missing from catalog: {missing}"
    assert not unknown, f"catalog lists unknown tools: {unknown}"

    lines = [
        "# HyFuse Tool Catalog",
        "",
        "> All **%d tools** of the HyFuse MCP surface, generated directly from" % len(tools),
        "> `McpToolRegistry.java` by `tools/gen_tool_catalog.py` — this file never",
        "> drifts from the code. Each entry: what it does, its arguments, and when",
        "> an agent should reach for it.",
        "",
        "<img src=\"images/tool-map.png\" alt=\"The 79 tools grouped by category\" width=\"720\">",
        "",
    ]
    for title, anchor, blurb, lst in CATEGORIES:
        lines += [f"## {title} <a id=\"{anchor}\"></a>", "", blurb, "", "| Tool | Arguments | Description |", "|---|---|---|"]
        for name in lst:
            t = tools[name]
            args = ", ".join(f"`{a}` <sub>{TYPE[k]}</sub>" for a, k in t["args"]) or "—"
            d = t["desc"]
            if len(d) > 300:
                d = d[:297].rsplit(" ", 1)[0] + "…"
            d = d.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| [`{name}`](#{name}) | {args} | {d} |")
        lines.append("")
    lines += [
        "---",
        "",
        f"**{len(tools)} tools total** (a few appear in more than one category).",
        "",
        "### Queue-only task types",
        "",
        "These four are not standalone tools — they run **only inside `enqueue-tasks`**",
        "as task entries:",
        "",
        "| Task type | What it does |",
        "|---|---|",
        "| `hunt-hostile` | find + goto + KillAura + collect loot, one composite |",
        "| `drop-items` | drop a named list or all-non-hotbar, optional walkBlocks |",
        "| `break-entity` | goto + attack until the target is removed, returns position |",
        "| `replace-blocks` | Baritone `#sel pos1/pos2/replace <from> <with>` region edit |",
        "",
    ]
    OUT.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({len(tools)} tools, {len(lines)} lines)")

if __name__ == "__main__":
    main()
