# Guides

How the big built-in jobs work, and how to put a long unattended script together. The language is described in
[SCRIPTING.md](SCRIPTING.md), and every function is listed in [FUNCTIONS.md](FUNCTIONS.md).

- [A complete AFK script](#a-complete-afk-script)
- [The chest index](#the-chest-index)
- [Filling water: fill_water](#filling-water-fill_water)
- [Digging out an area: clear_area](#digging-out-an-area-clear_area)
- [Clearing trees: clear_trees](#clearing-trees-clear_trees)
- [Flooding and draining](#flooding-and-draining)
- [Building Litematica schematics](#building-litematica-schematics)
- [Chunked runs and resuming](#chunked-runs-and-resuming)
- [Safe areas](#safe-areas)
- [Moving a whole setup](#moving-a-whole-setup)

---

## A complete AFK script

A long unattended script usually has the same parts in this order: places, safe areas, options, want-list,
`on` blocks, then the work split into jobs.

```
// --- places (Capture K or Pick block fills these in)
let bed          = pos(1791, 74, -839)
let tool_chest   = pos(1790, 74, -837)   // supply: tools and food
let dump_chest   = pos(1787, 74, -837)   // dump: what was mined
let dirt_chest   = pos(1794, 74, -840)   // dirt for fill_water

// --- the base must never be dug into
protect("base", pos(1779, 68, -844), pos(1797, 80, -832))

// --- survival and AFK safety
option("survival.bed", bed)
option("survival.supply", tool_chest)
option("survival.eatBelow", 16)
option("queue.logoffOnError", true)      // leave cleanly instead of standing around after a fatal error

want("diamond_pickaxe", 2)
want("diamond_shovel", 1)
want("food", 32)
keep("dirt", 0)

// --- reactions, most urgent first, outside the jobs
on mobs > 0 and health >= 8 do defend() end
on food < 16 do eat() end
on bed_time do sleep() end
on restock_needed do restock() end
on full do dump(dump_chest) end

// --- the work
job "fill"
  area("pond")
  let left = fill_water("dirt", dirt_chest)
  if left > 0 then error("{left} water cells could not be reached") end
end

job "dig"
  area("pond")
  clear_area()
  collect_scaffold()
  pickup()
  dump(dump_chest)
end
```

On a server with keep-inventory, `option("survival.maxDeaths", 0)` makes the bot carry on after every death.
The default (2 deaths in 10 minutes) stops the task after the third.

## The chest index

Every time a chest, barrel or shulker box is open, Manhattan notes what is in it. That includes the script's own
chest actions (`take`, `put`, `dump`, `take_upto`, `fill_water` / `clear_trees` with a chest), restocking,
`unload()`, and chests you open yourself. What a script takes out or puts in is counted too, so the entry is still
right after the chest is closed.

The index is saved per server or single-player world in `config/manhattan-chests/sp-<world>.json` or
`mp-<server>.json`, so it survives a restart. `/survival chests` lists it, and `/survival chests forget` empties it
for the current world.

With a **list of chests**, `take`, `take_upto` and `put` use the index instead of opening every chest:

- `take(chests, "stone", n)` / `take_upto(chests, "stone", n)` first go to the chests the index knows hold the item,
  nearest first, until you have enough. If those don't hold enough, chests the index doesn't know (never opened) or
  whose entry is older than the refresh interval (`index_refresh`, default 30 min) are opened once each, nearest
  first. Chests seen recently without the item are not visited. When no chest is known to hold the item and nothing
  is stale, it walks nowhere: it says so in chat and returns 0.
- `put(chests, "stone" [, n])` only goes to chests the index knows hold that item, nearest first, until all of it is
  in. Full chests are skipped. If no such chest exists it puts nothing, so give a single chest to start a new pile.
- A chest that fails (not reachable, no chest there any more) is reported in chat and skipped. It does not stop the
  script. A single chest as the first argument is always opened, as before.

```
let chests = [pos(1767, 68, -847), pos(1767, 68, -846), pos(1768, 68, -847)]
index_refresh(120)                           // nobody else touches these: trust the index for 2 hours
take_upto(chests, "stone_bricks", 720, 2)    // keep 2 slots free
put(chests, "stone_bricks")                  // leftovers go back to a chest that holds stone bricks
```

`find_item`, `chest_contents` and `chest_age` read the index without walking anywhere.

## Filling water: fill_water

`fill_water("dirt", dirt_chest)` fills every water cell of the selection with blocks.

- A **wet cell** is anything that holds water and is not solid: plain water, seagrass, kelp and bubble columns all
  count. Kelp is broken first.
- A cell is only filled when **no wet cell is left below it in its column**, so it always works strictly bottom-up.
- It searches the **whole selection** for the nearest cell it can do next. After a bed trip, a meal or a fight it
  walks back by itself. From dry ground (or water 1 deep, head dry) it reaches about 3 blocks down.
- Deeper cells are done in **dive mode** (`option("buildtools.dive", false)` switches it off). It picks a spot on the
  bottom next to the cell with open water straight up to the surface, so it is never under a roof. It swims there,
  sinks, places what it can reach (lowest first), and comes up when its air drops below about a third. Narrow shafts
  are filled by pillaring up inside them.
- Sand and gravel sink to the bottom by themselves: `fill_water("sand", chest)`.
- With a chest it fetches `per_trip` blocks (default 512) whenever it runs out.
- It returns the wet cells it could not get at (0 = done).
- With `buildtools.dam` on (the default), it also plugs water just outside the selection's edge, so water cannot
  flow back in.

For a trench next to open water, it helps to grow the selection by one block on each side before filling and to
forbid bridging while filling, so the bot does not build roofs over the water:

```
set("allowPlace", false)
set("allowBreak", false)
area("trench")
fill_water("dirt", dirt_chest)
set("allowPlace", true)
set("allowBreak", true)
```

## Digging out an area: clear_area

`clear_area()` mines out the selection with Manhattan's own digger:

- It works from the top in passes 3 layers thick (`clear_area(2)`: 2 layers), walking lanes along the long side
  6 blocks apart and stopping every 3 blocks.
- At every stop it breaks everything in reach, highest first. Blocks that break instantly (Haste + Efficiency) go at
  one per tick, others as fast as the game allows. It uses the fastest tool, but not one that the item saver
  protects.
- It walks short level steps itself and picks up drops right away. For high pillars and floating blocks it pillars
  up beside them instead of standing on them.
- It leaves blocks next to water or lava alone (so nothing floods), and it respects safe areas.
- At the end it prints what is left and why (next to water or lava, protected, could not get at) and where the time
  went. It returns only the blocks it could not get at, since wet and protected ones are never worth another try.
- An `on` block (eating, a fight, a trip to the bed or the dump chest) only pauses it. Afterwards it walks back and
  carries on.
- It does not bridge over gaps just to tear the bridge down again: a spot it can only reach by placing blocks in
  mid-air waits until the end of the pass (digging may open a way), and is only bridged once, at the very end, when
  six or more spots lie behind it. Otherwise it is left and reported ("spots across a gap").

### A stair shaft for getting out of the hole

A deep hole is hard to leave. Give the dig a **stair shaft**: a saved area of at least 2 x 2 blocks inside the area
you dig, with the role `stairs`:

```
area_box("pit", pos(100, 40, 100), pos(131, 70, 131))
stairs("pit_stairs", pos(100, 40, 100), pos(102, 70, 102))   // 3 x 3 in the corner
area("pit")
clear_area()
```

- The shaft is never mined as a whole. A spiral staircase is carved along its outer ring (clockwise from the
  minimum corner), one block lower per step, with three blocks of air above every step; the centre and the blocks
  between two turns stay as the floor. The steps grow with the hole: every pass carves them down to the level it
  just reached, so the stairs always reach the floor you work on.
- Nothing has to be linked: a `stairs` area belongs to the dig area it lies in. It is saved like any area, so it
  survives a restart.
- When an `on` block fires (eating, restock, the bed ...) while the bot is in the hole, it first climbs the stairs to
  ground level, then the block runs. Afterwards `clear_area` walks back down and carries on.
- Heavymatica: give a region the role **STAIRS** next to the DIG region and send the Heavymatic; Manhattan picks it
  up by itself (needs a Manhattan with API version 2).

Don't hand the leftovers to `#sel ca` next to water: it will not break those blocks either, and just walks back and
forth.

## Clearing trees: clear_trees

`clear_trees([chest])` removes every tree in the selection, one at a time, nearest first.

- A tree is every log, wood, stem, mangrove root, mushroom stem or cap and nether wart block touching another one
  (also diagonally). Vines, bee nests, cocoa and hanging moss within 3 blocks go with it. Every log in the selection
  counts, including player-built ones. Leaves are not mined: they decay by themselves.
- For each tree it mines just that tree, climbing on scaffolding blocks (dirt, cobblestone, stone, ...) where it
  must, then picks up the drops and takes its own scaffolding down before moving on, so no towers stay behind.
  A tree that is not completely gone gets up to three tries, then it is left and named in chat.
- At the end it waits up to 60 s for leaves that no longer touch a log, then walks the area once and picks up
  saplings, sticks and apples.
- With a chest, a nearly full inventory (fewer than 6 free slots) is emptied into it between trees, and once more at
  the end. The scaffolding blocks stay with you.
- It returns what was gained as a map item → count, including what went into the chest.

## Flooding and draining

- `flood()` fills the selection **with** water using buckets. It seals the open sides first, then places the first
  two sources with a one-block gap so the water between them refills. Carry at least one water bucket and have a
  water source within 16 blocks.
- `drain()` removes flowing water that is in the way (in the selection, or 8 blocks around you). With
  `buildtools.drainAuto` on, this happens by itself when building stalls on flowing water.

## Building Litematica schematics

`schem_build()` builds your Litematica placements with Manhattan's own builder, never with the Litematica printer.
A placement is its number in `schem_list()` or its name in Litematica: `schem_build("canal")`.

Modes:

- **add** (default): schematic air is ignored. Blocks that are already there are left alone, and only empty or
  replaceable spots (air, water, grass ...) are filled.
- **fix**: schematic air is ignored, and wrong blocks are broken and replaced.
- **exact**: like fix, but schematic air is dug out first (its own phase, top-down), for example to build into a
  hill.

Missing items:

- **skip** (default): blocks you carry no item for are left out, and the rest is built.
- **strict**: the phase stops as soon as an item is missing.

How it builds:

- The phases are clear (exact only) → full blocks → shaped (stairs, slabs, fences, panes ...) → attached (torches,
  doors, signs, carpets, plants ...) → redstone (dust, repeaters, pistons ...) → power sources (levers, buttons,
  plates, redstone torches). Each phase is built bottom-up, layer by layer. Fluids are not built yet; the report
  lists them.
- The material list counts what is still missing: a double slab is 2 slabs, a door or bed 1 item, redstone dust is
  `redstone`, and water or lava is a (reusable) bucket.
- Blocks inside safe areas are never built or dug.
- Placements come from the running Litematica (found by reflection; the mod also works without it) or, failing that,
  from Litematica's own file in `config/litematica/`. Position, rotation, mirror, enabled state and sub-regions are
  used. Where placements overlap, the later one in the list wins (air never overwrites a block).

A long build stretches over areas that are not all loaded at once. `schem_build()` only sees what is loaded, so use
`schem_left()` for the honest total:

```
schem_left("reset")
let need = schem_needed("canal")
for item in keys(need)
  take_upto(material_chests, item, need[item], 2)
end
schem_build("canal", "add")
let left = schem_left()             // over everything built so far, grouped by reason
if left > 0 then print("{left} blocks still missing") end
```

The same functions as chat commands: `/schem list`, `/schem build [n|all] [add|fix|exact] [skip|strict]`,
`/schem materials [n|all] [mode]`, `/schem check [n|all] [mode]`, `/schem move <n|all> <dx> <dy> <dz>`, `/schem stop`.

## Chunked runs and resuming

`fill_water`, `clear_area`, `drain` and `schem_build` split a selection that is more than 32 blocks long in x or
z into 32x32 columns ("chunks", independent of Minecraft's chunks) and work them one after another. `clear_area`
also cuts a tall selection into bands 16 blocks high (the top band first, so a hole is dug out band by band), and
treats boxes that are more than 8 blocks apart in any direction as separate regions that are worked one after the
other, so the bot never crosses the empty space between them. A chunk that holds nothing to dig (a cave, an earlier
dig) is ticked off without walking there.

1. **Primary**: every chunk in order: walk there, wait until it is loaded, check whether it still needs work, work
   it.
2. **Deferred retry**: chunks that could not be finished (not reachable yet, missing material) are tried once more.
3. **Verification**: every chunk is checked again, and anything still left is reported.

The Monitor map shows the chunks (to do / working / done / failed) and counts them ("6/15 chunks done").

Finished chunks are saved in the job ledger (`config/manhattan-jobs/<world>_<script>.json`). Run the same script
again after a crash or a disconnect and it says "Resuming: 6 of 15 chunks done" and skips them. The ledger is kept
until you reset it: `/queue jobs` shows it, `/queue reset` starts from zero, and `/queue retry <job>` tries only the
failed and deferred chunks again. Outside a `job` block the job is called `script`, so `/queue reset script` resets
just the lines outside jobs.

Options ([OPTIONS.md](OPTIONS.md#chunked-runs)):

```
option("chunk.enabled", false)     // one big run instead of chunks
option("chunk.size", 48)           // chunk width in blocks (default 32)
option("chunk.order", "nearest")   // or "serpentine" (default: row by row, back and forth)
option("chunk.sizeY", 24)          // band height for clear_area in blocks (default 16, 0 = no bands)
option("chunk.gap", 12)            // boxes further apart than this are separate regions (default 8, -1 = off)
```

## Safe areas

`protect("base", corner1, corner2)` makes a box the engine may walk through but never breaks or places blocks in.
This applies to digging, building, scaffolding and schematics alike. Chests, hoppers, beds, furnaces and redstone
parts are avoided everywhere while safe areas are on (`buildtools.avoidBlocks`). Safe areas are saved, so they
survive restarts. `unprotect("base")` removes one, and `/buildtools protect list` shows them all. A safe area can also allow mining or
bridging inside it ([COMMANDS.md](COMMANDS.md#buildtools)).

Areas pushed by Heavymatica with the `PROTECT` role become safe areas by themselves (see
[HEAVYMATICA_API.md](HEAVYMATICA_API.md)).

## Moving a whole setup

To run the same thing somewhere else, `/queue move <dx> <dy> <dz>` moves every saved area, saved chest and Litematica
placement by that offset. `/queue area move <name|all> <dx> <dy> <dz>` moves only areas, and
`/schem move <n|all> <dx> <dy> <dz>` only placements. Positions written into the script as `pos(...)` do not move:
add the offset to them yourself.
