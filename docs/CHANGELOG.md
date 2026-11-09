# Changelog

Newest first. "Tested" means seen working in game on the 26.1.1 AFK profile.

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
