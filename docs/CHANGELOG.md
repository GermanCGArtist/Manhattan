# Changelog

Newest first. "Tested" means seen working in game on the 26.1.1 AFK profile.

## 2.5.0 – build Litematica placements with Baritone (step 1) (2026-11-09)
- `/schem list | build [n|all] [add|fix|exact] | materials | check | stop` and the script functions `schem_list()`,
  `schem_build()`, `schem_materials()`, `schem_check()`, `schem_stop()`. See [SCRIPTING.md](SCRIPTING.md).
- The placement (position, rotation, mirror, sub-regions, enabled) is read from the running Litematica by reflection
  (the mod still loads without it), else from Litematica's per-world file. The `.litematic` is read by the mod itself.
- The placement is wrapped in our own `ISchematic` and handed to Baritone's builder, one phase per run (clear,
  full, shaped, attached, redstone, power), bottom-up per layer. Fluids are listed, not built yet. Safe areas are left out.
- New in-game tests `schem_list`, `schem_add`, `schem_fix`, `schem_exact`; the test profile gets Litematica and
  malilib (copied from the Bestes_projekt profile) and never the Litematica printer.
- New presets, all in the current language and parsed at build time: the canal, building a schematic with materials from
  chests, digging top down with `clear_area(2)`, filling with water (`flood()`), filling water with dirt from a chest.
  The digging presets use `clear_area` instead of `#layerOrder true` + `#sel ca` (which gets stuck), "Remove water
  (layers)" is gone (use the water fill), the helpers use `enable(...)` and `on bed_time`. On the first start, presets
  you never changed are replaced; edited ones stay as "name (yours)".
- Canal work (tested end to end by the new `canal_smoke` test, which runs `scripts/canal.txt` on a small canal):
  - `flood()` works in trenches of any width: it stands or swims in the water (head always dry, comes up for air),
    puts water on temporary blocks where there is nothing to click against, empties a bucket only when the line of
    sight really ends on the intended face (no more water on the rim), never lets Baritone dig while filling, takes
    out blocks Baritone pillared into the trench, refills several buckets per trip and checks every scoop.
  - `clear_area(2)`: 2 layers per pass (Baritone's own `#layerOrder` top-down `#sel ca` gets stuck).
  - `collect_scaffold()` never digs through a fresh build (Baritone only walks while it collects); `fill_water` steps
    out of the last wet cell when it stands in it; `take_upto` takes exactly the count still needed.
  - `schem_build(..., "skip")` (default) builds what it has items for and leaves out the rest; the builder takes
    items from the whole inventory; the digger uses the fastest tool; Test checks the world too (chests, bed,
    placements, materials, water for `flood()`).
  - After the first live canal run: schematic blocks wanted where water is now are placed by our own placer (Baritone's
    builder stopped the whole bottom layer at "Unreplaceable liquids", so nothing above it was built), and Baritone
    may not place blocks while `flood()` walks (it pillared dirt 30 blocks up out of the water at cells it could not
    reach). New tests `canal_schem_water`, `canal_move`; `canal_flood_wide` checks that nothing stands above the trench.
  - After the second live run (9 hours on the last step, nothing filled): a chest is never opened while sneaking with a
    block or bucket in hand (that put a stone brick on top of the tools chest, which then never opened again; the
    message now says when a block is on top); a restock trip that cannot reach or open the chest waits 1, 2, 4 ... 30
    minutes and `restock_needed` waits as long; `flood()` carries on after an `on` block with the same cells and skips
    instead of starting over; where Baritone finds no path (it never walks through flowing water) `flood()` walks or
    swims the last few blocks itself; our schematic placer may stand in water. New test `canal_flood_resume`.
- `/queue move <dx> <dy> <dz>` moves the whole setup (every saved area, saved chest and Litematica placement) by an
  offset, to run a test again somewhere else; `/queue area move <name|all> ...` and `/schem move <n|all> ...` move
  one kind. Positions written into a script as `pos(...)` stay where they are.

## 2.4.4 – clear_area says why blocks are left (2026-11-09)
- `clear_area` returned every block left, so `if clear_area() > 0 then #sel ca end` handed the blocks next to the
  water to Baritone, whose builder will not break them either: on the AFK run it walked back and forth for 15 min.
  It now returns only the blocks it could not get at, and the chat line splits the rest: "415 left: 400 next to
  water or lava (left alone so nothing floods), 15 it could not get at."
- `scripts/water-job.txt` no longer runs `#sel ca` after `clear_area`.

## 2.4.3 – air guard, tools in the hotbar, Baritone giving up (2026-11-09)
- Drowned on the AFK run: `#sel ca` after `clear_area` dug at the blocks next to the water that `clear_area` leaves
  alone on purpose, and Baritone got stuck under water. New always-on air guard (script or not, before fights and
  everything else): with less than half the air left under water it swims up to open water in the same pool, breaks
  the block over the head when closed in (with the best hotbar tool), waits for the air to fill, then swims to dry
  land. The water fill's own dives are left alone.
- A Baritone build (`#sel ca`, `#build`) that pauses itself ("Unable to do it. Pausing, resume to resume") is
  cancelled after 10 s and the script goes on; it used to wait there for good.
- While a script runs, the want-list tools always sit in the hotbar (Baritone only uses tools from the hotbar): once
  a second one that is only in the main inventory is swapped into an empty slot, else over blocks or other things -
  never over food, another wanted tool, the selected slot or a slot `hotbar()` pinned.
- New in-game tests `hotbar_tools` and `air`.

## 2.4.2 – dump() keeps only the wanted tools (2026-11-09)
- `dump()` kept everything that can wear out and all food. Bows, armour and tools from the mobs `defend()` killed,
  spare shovels and rotten flesh piled up, and the hotbar was never emptied: on the AFK run a dump trip carried only
  100–200 items instead of 350–400, so it walked to the dump chest twice as often.
- Now tools and armour stay only as far as the want-list asks for them (the best copies first; with no want-list, the
  best one of each kind). Food stays except rotten flesh, spider eyes, poisonous potatoes, pufferfish and raw chicken.
  The hotbar is emptied too. Buckets and the `keep` rules stay as before.
- New in-game test `dump`.

## 2.4.1 – clear_area carries on instead of starting over (2026-11-09)
- An `on` block (eating, a fight, the bed, the dump chest) used to restart `clear_area` from the first stop of the
  first lane, forgetting what it had given up on: on the AFK run that was 128 restarts in under 5 hours, each walking
  back towards old leftovers and often ending in failed Baritone searches. Now it pauses and carries on at the same
  stop ("Carrying on clearing at stop N of M"); a different selection still starts fresh.
- The 45 s travel timeout only counts while Baritone gets no closer, so a long walk back from the bed is not cut short.
- Breaking: the game's 5-tick pause between two blocks counts down while it turns to the next block (about 13% faster
  breaking in the test: 10.4 → 9.0 ticks per dirt block with a diamond shovel).
- Every 5 minutes of work a line goes into the game log: `[Queue] clear_area progress: broken=… ticks break=… walk=…
  travel=… pick=… resumes=… travelFails=…`; the chat report at the end also says how long it was paused.
- New in-game test `clear_resume`.

## 2.4.0 – housekeeping (2026-11-09)
- The project is now **Manhattan** (github.com/GermanCGArtist/Manhattan). Mod id and jar name stay `baritonequeue`,
  so saved scripts and configs carry over. Shown names: "Manhattan (Baritone Script)" in the mod list, "Manhattan"
  in the controls, "Manhattan script" on the editor.
- `build.bat` / `install.bat` work from the project folder; `install.bat` reads the version from `gradle.properties`.
- The offline language tests live in `src/test` and run with every build (`gradlew langTest`); every script in
  `scripts/` is parse-checked too (`gradlew parseScripts`).
- Docs: this changelog and [DESIGN.md](DESIGN.md) replace the old handoff notes; stale "addon" wording removed.
- No behaviour changes.

## 2.3.8 – restock loop (2026-11-08)
- `restock_needed` ignored the "supply chest is short on X – I'll look again in N min" wait. With no diamond shovel
  and no food in the tool chest, `on restock_needed` walked back to the base every minute for an hour.
- Now items the chest was short on do not count as needed until their wait is up (5, 10, 20, then every 30 min).
  A running script's `restock()` keeps those waits; only a hand-typed `/survival restock now` clears them. A tool
  that newly wears out still clears its own wait.

## 2.3.7 – stuck at the surface
- `fill_water` refuses dive spots under a bubble column (it bobbed there forever).
- Sinking first centres over the hole, switches to UNDER early, and places on the way down; 40 ticks without
  getting deeper aborts the dive.

## 2.3.1 – 2.3.6 – fill and survival fixes (2026-11-08)
- Long Baritone searches while filling: allowBreak only for spots that need digging, `GoalNear` instead of
  `GoalBlock`, and a 15 s no-progress check (retry once with digging, then mark the spot bad).
- No more walking in circles: it turns before walking when the target is within 2 blocks, only uses stands from
  which the cell can actually be clicked (`Placer.placeableFrom`), and gives targets strikes.
- Eating no longer happens from the off hand while the main hand holds a block (it placed dirt while eating).
- `on` blocks nest by order: a block written higher up interrupts the ones below it (a fight interrupts eating,
  sleeping and restocking).
- Sleep walks to a bed in an unloaded chunk instead of giving up; `option("survival.bedLead", s)` adds slack.
- `hotbar(slot, item)` keeps an item in a hotbar slot (food in slot 9).
- Safe areas: `protect(name, a, b)`; Baritone may not break or place inside, re-forced every tick, so
  `set("allowBreak", true)` in the script cannot override it near the base.
- `protect()` and other game-changing functions run on the game thread (fixed the "RenderSystem called from wrong
  thread" crash).

## 2.3.0 – survival decided by the script (tested)
- `defend()`, getters `mobs`, `mobs_near(r)`, `restock_needed`, `bed_time`; `eat()` eats until full; `sleep()`
  leaves early enough to reach the bed by dusk.
- An `on` block fires again if its condition is still true a minute after it ended.
- Want-list and tool wear from the script: `want("diamond_pickaxe", 1)`, `set("itemSaverThreshold", 50)`,
  `option("survival.returnChest", pos(...))`.

## 2.2.0 – clear_area
- `clear_area()`: its own area-clearing executor (Baritone only travels). Top-down passes 3 layers thick, lanes 6
  apart, a stop every 3 blocks, instant blocks one per tick, drops picked up on the way, a time report at the end.
  Returns what it could not clear (use `#sel ca` for the rest).

## 2.1.0 – 2.1.2 – dive mode (tested)
- `fill_water` plans over the whole selection and dives where the shore cannot reach: sink, place under water,
  rise through an open column (or break the block above the head). Hover dives where there is no floor.
- `fill_water(block, chest[, per_trip])` fetches more blocks by itself and keeps going round after round while
  anything gets filled. Returns the number of wet cells left.
- `put()` into a full chest only warns; `dump()` that fits nothing stops the script.

## 2.0.0 – 2.0.2 – the script language (2026-11-07)
- SurvivalKit, BuildTools and LoadChest merged into this mod; their jars are not installed any more.
- A real language: `let`, `if/elif/else`, `while`, `for`, `repeat`, `loop`, functions, `try/catch`, `wait until`,
  `on <condition> … end`, `#` Baritone lines and `/` chat lines with `{…}` interpolation.
- Editor: Tab completion like `/setblock`, Pick block for positions, Test button, syntax colours.
- Water fill rewritten bottom-up: seagrass, kelp and bubble columns count as water, a cell is only filled when its
  column below is dry.
- "Script only": no helper acts on its own; `enable(...)` / `option(...)` switch one on.
- Old scripts are converted once on load (`Migrate`).
