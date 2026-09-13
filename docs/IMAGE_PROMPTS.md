# HyFuse — Image Prompts for Generated Artwork

The repository's README and docs reference six images under `docs/images/`.
Generate each with your image model (ChatGPT / DALL·E or any generator you
prefer), save it under the base name listed, and install it into
`docs/images/` as **WebP** at 2x the display size (the exact target
dimensions are given per image; the repo ships `.webp` so the README stays
fast). One-line conversion from your generated PNG:

```bash
magick <name>.png -resize <WxH> docs/images/<name>.webp   # or: convert ...
```

The masters used for this repo (full-resolution PNGs) live outside the
worktree; only the compressed `.webp` renditions ship.

## Style foundation (shared by all prompts)

HyFuse's visual identity: **sharp, technical, modern, a little futuristic** —
circuitry meets voxel world. Every prompt below shares this DNA so the set
feels like one product:

- **Palette:** deep navy/near-black backgrounds (#0d1117–#161b22, GitHub-dark),
  glowing cyan/teal accents (#4dd0e1, #2dd4bf), warm amber sparks
  (#f59e0b), off-white text/lighting (#e6edf3). No rainbow palettes.
- **Mood:** night-time engineering, control-room calm, quiet power.
- **Rendering:** clean isometric or flat-technical illustration, crisp edges,
  subtle glow/soft light halos, minimal noise. Think Stripe-landing-page
  polish applied to Minecraft voxels.
- **Text:** avoid text in images wherever possible — it renders garbled at
  small sizes. Where a diagram needs labels, add them with HTML/markdown or
  an editor afterward, not in the generation.

---

## The six images

### 1. `hero-banner.png` — README hero (wide, ~1760×640)

The flagship. First thing visitors see. Landscape, wide banner composition.

**Prompt:**

> A wide cinematic banner for a software project called "HyFuse". Deep navy
> night background fading to near-black. On the right, a stylized low-poly
> voxel Minecraft-style landscape at night — cubic terrain, small glowing
> torches, a distant cubic mountain range — rendered clean and modern, not
> pixelated. On the left, a luminous cyan wireframe geometric structure:
> interconnected hexagonal nodes and flowing data lines suggesting a neural
> network or protocol graph, glowing softly against the dark background,
> with a few warm amber sparks where the data lines meet the world. A
> translucent beam of light bridges the network graph on the left to the
> voxel world on the right. Minimal, elegant, high-contrast, dark-mode tech
> aesthetic. No text. Wide landscape composition, 1760x640.

**Install as:** `docs/images/hero-banner.webp` (1760x640)

### 2. `architecture-diagram.png` — how it works (wide, ~1440×560)

A conceptual diagram of the chain: agent → MCP/OpenAPI → the mod → Baritone &
Meteor. Generated art, then optionally overlaid with real labels in an editor.

**Prompt:**

> A dark-mode technical diagram illustration, wide landscape. Left side: an
> abstract glowing white chat bubble icon floating in dark space, labeled by
> a simple speaker silhouette — represents a conversational AI. Center: a
> radiant cyan hexagonal gateway, like a glowing portal or network hub, with
> clean light rays entering from the left and fanning out to the right;
> faint circuit-trace lines radiate from it. Right side: two glowing
> emerald-green cubic engines (simple isometric cubes with subtle inner
> glow) connected by thin luminous lines to the hexagon, each engine
> connected to a small voxel Minecraft-style terrain fragment below them —
> one cube trails a path of cubic footsteps (navigation), the other emits a
> faint protective aura ring (combat). Deep navy background (#0d1117),
> cyan and teal glows, one warm amber accent line. Clean, flat-technical,
> minimal, no text. 1440x560.

**Install as:** `docs/images/architecture-diagram.webp` (1440x560)

### 3. `tool-map.png` — the 79 tools (wide, ~1440×560)

A visual summary of the tool surface as a constellation or periodic table.

**Prompt:**

> A dark-mode data visualization artwork. Deep navy background. A large
> luminous cyan constellation made of exactly 79 small glowing nodes
> arranged loosely into nine distinct clusters (constellation groups), each
> cluster a different subtle hue tint of cyan/teal/blue/violet, connected
> within itself by thin glowing lines, clusters separated by dark space.
> The overall constellation forms a subtle hexagonal silhouette. A few nodes
> glow brighter amber than the rest — the "featured" tools. Soft bloom on
> the glows, crisp minimal style, flat-technical illustration, no text, wide
> landscape 1440x560.

**Install as:** `docs/images/tool-map.webp` (1440x560)

### 4. `session-example.png` — an agent at work (wide, ~1440×560)

The "it's alive" shot: a chat conversation turning into game action.

**Prompt:**

> A dark-mode illustration, wide landscape. Left third: a sleek dark chat
> interface panel in flat-design style — a rounded dark card with subtle
> border glow containing three empty chat bubbles (no readable text, just
> soft light-gray placeholder bars suggesting conversation) and one cyan
> highlighted bubble with a small hexagonal tool-call icon. From the cyan
> bubble, glowing lines flow rightward and downward. Right two-thirds: a
> stylized isometric voxel Minecraft-style scene at night — a small cubic
> mining pit with glowing ore specks, a stone furnace with warm amber fire
> glow, a chest, and a voxel character with a cyan holographic halo above
> its head, holding a pickaxe, mid-action at the pit. The glowing lines
> from the chat panel connect to the character and the furnace. Deep navy
> palette, cyan and amber glows, clean modern flat-technical render, no
> text. 1440x560.

**Install as:** `docs/images/session-example.webp` (1440x560)

### 5. `memory-vault.png` — the brain's bookshelf (wide, ~1440×560)

Memory, journal, playbook, policies — as a glowing archive.

**Prompt:**

> A dark-mode illustration, wide landscape. Center: a mystical floating
> archive vault made of dark voxel cubes — a small cubic structure
> resembling a glowing library or vault chest, with four softly glowing
> cyan drawers or floating slabs of light hovering around it, each slab a
> thin luminous rectangle like a holographic data tablet. Warm amber light
> seeps from the vault's seams. Above the vault, a thin luminous filament
> rises upward and branches into a delicate neural branch pattern, glowing
> faintly. Deep navy background with subtle starfield specks. Clean,
> elegant, minimal glow, flat-technical style, no text. 1440x560.

**Install as:** `docs/images/memory-vault.webp` (1440x560)

### 6. `creed.png` — the closing banner (wide, ~1280×400)

The quiet, inspirational closing image for the README footer.

**Prompt:**

> A minimal dark-mode closing banner, wide landscape. Deep navy fading to
> black at the edges, vignette. Center: a single small glowing cyan cube
> floating in darkness, casting a soft light halo, with three thin luminous
> orbit rings around it at different angles — like a tiny planet or core.
> Extremely minimal, lots of negative space, quiet and contemplative, high
> contrast between the glowing cube and the darkness. No text. 1280x400.

**Install as:** `docs/images/creed.webp` (1280x400)

---

## Tips for best results

- **Aspect ratio:** all prompts target wide-landscape. If your generator
  only offers fixed sizes, generate the largest landscape option and crop.
- **Consistency:** generate all six in one session if possible — same model,
  same style tokens ("dark-mode tech aesthetic", "cyan glow", "voxel") — so
  the palette stays coherent across the set.
- **Cropping:** `hero-banner.png` may crop tighter than generated; the README
  displays it at width 880, so extra margin is fine.
- **Alternative:** if a prompt produces text artifacts, add "no text, no
  letters, no words" to the end — most models honor this.
- Keep the final files under ~500 KB each (PNG or WebP) so the README stays
  fast on GitHub.
