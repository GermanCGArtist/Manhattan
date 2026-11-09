# Baritone Script 2.1.0: handoff for a new session

Project: Baritone tools for Minecraft 26.1.1 (Fabric, Mojang names, Java 25, Baritone API 1.18.0).
The previous notes (state 1.9.6, with separate addons) are in `WATER_NOTES-old-1.9.6.md`.

## What changed

The separate addons are gone. Everything is one mod, **baritonequeue 2.0.0** ("Baritone Script"), with a real script language. The user asked for this on 2026-11-07:

* Everything the addons could do must be possible from the script.
* The addons (SurvivalKit, BuildTools, LoadChest) are **not installed any more**. Their source folders stay in place and are not deleted.
* The script must be a proper scripting language.
* Positions (chests, beds …) are fetched in the editor by looking at the block and pressing Tab, like /setblock, or with the Pick block button.

The language reference for the user is **`SCRIPTING.md`** in this folder. Read it first.

## Script only (2.0.1, BaritoneUI 1.10.1)

The user chose "script only, auto off" on 2026-11-07:
* On first start, 2.0.1 switches off these settings once (`helpersManual` in baritonequeue.json): survival.eat, defence, sleep and restock; buildtools.drainAuto, escape and scaffoldAuto; loadchest.auto and pickup.
* Respawn, resume after death and reconnect stay on, because they only bring the running script back.
* Script calls still work with these settings off: `eat()`, `sleep()`, `restock()`, `drain()`, `collect_scaffold()`, `unload()` and `pickup()`.
* The `provides` entry was removed from fabric.mod.json. BaritoneUI 1.10.1 hides the LoadChest, SurvivalKit and BuildTools tabs when those mods are not installed.
* One helper can be switched back on per script, for example `option("survival.eat", true)` (this is saved).

## 2.0.2 (after the first in-game run)

* First run: fill_water filled 516 cells, then ran out of dirt with 4349 still water. The converted script had no refill loop, so it went on to area 2. Eat, sleep and defence did nothing because of the "auto off" change in 2.0.1.
* `fill_water("dirt", chest [, per_trip])` now fetches more dirt by itself (take_upto per_trip, default 512) whenever it runs out.
* `enable("eat", "sleep", "defence")` makes helpers act only while the script runs. This uses the system property `baritonequeue.helpers.on`, read by `Util.scriptOn` in each package. `disable` is the opposite.
* The user's script in `baritonequeue.json` was replaced with the version using enable + refill + a stop when water is left. The previous script is saved as the preset "Water job (converted, before 2.0.2)". Bed 1790 74 -839 and supply 1786 74 -841 are set in survivalkit.json.

## 2.3.0: survival decided by the script (installed 2026-11-08, not yet tested in game)

* The user wants everything done by the script, defence included, and none of it by the helpers on their own.
* New: `defend()` (DefenceProcess.forced), and the getters `mobs`, `mobs_near(r)`, `restock_needed` (RestockProcess.needed) and `bed_time` (SleepProcess.bedTime: night, a thunderstorm, or time to leave for the bed). `sleep()` also starts at bed time before dusk. `eat()` eats until the bar is full.
* `on` fires again when its condition is still true a minute after its block ended (Runner, Handler.lastEnd).
* New places from the user: tool or supply chest 1787 74 -841, return chest 1789 74 -841, bed 1791 74 -839. The dirt chest (1794 74 -840) and the dump chest (1787 74 -837) are unchanged.
* The script was written into baritonequeue.json (the old one is the preset "Water job (before 2.3.0)") and is also in `versions/water-job-script.txt`. It sets those places with option(), and has on-blocks for mobs, food, bed_time, restock_needed and full, then fill_water and clear_area.
* Respawn, resume and reconnect stay on: the script cannot run while dead or disconnected.

## 2.2.0: clear_area (installed with 2.3.0, not yet tested in game)

* This is the "pathfinding additions" discussed with the user: Baritone stays for travel only; area clearing gets its own executor, `dev.baritonequeue.ClearProcess` (a Baritone process, priority 11).
  * Passes 3 layers thick from the top. Lanes along the long side, 6 apart, a stop every 3 blocks; stops with nothing in reach are skipped without walking there.
  * Breaking: highest first, then nearest, only blocks it can see. An instant-break block (getDestroyProgress >= 1) is broken one per tick with startDestroyBlock; others use continueDestroyBlock, with an 8 s timeout. Blocks next to fluid are skipped.
  * Moving: a straight level walk of up to 6 cells is done with its own keyUp hold; anything else is Baritone GoalBlock with allowBreak on (restored at the end). PICK walks to drops within 6 that can be reached straight.
  * At the end it prints the time split (breaking, walking, Baritone paths, picking up, planning) in chat and in the log (`[Queue] clear_area:`).
  * The user's script should use `if clear_area() > 0 then #sel ca end` for areas 2 and 3 (to do once the game is closed).
* The user asked about the tool chest: `restock()` / `enable("restock")` (supply 1786 74 -841, want-list has diamond tools and food). Eating needs `enable("eat")` in the script.

## 2.1.2 (installed 2026-11-07, not yet tested in game)

* Fourth run: 3 cells filled, 1172 left. The columns next to the rest were filled up to the surface, and under y 60 there is no floor within 3 blocks, so no dive spot was found. The script's own `stop()` then ended everything.
* Hover dives: `diveSpotFor` also accepts spots without a floor (wet below, open water up to the surface); spots with a floor are preferred. `holdPosition` holds the depth there (jump below the spot, sneak above it) and steers back over the column.
* `fill_water` runs more rounds while a round filled anything (at most 30), and fetches more blocks when it runs out. It only returns water left when a whole round filled nothing.
* The user's saved script should drop the `stop()` and the `put()` back into the dirt chest, which keeps getting refilled. Written into baritonequeue.json after install; the previous script is saved as the preset "Water job (before 2.1.2)".

## 2.1.1 (installed 2026-11-07, not yet tested in game)

* Third run (log 22:57 to 23:11): dives worked (509 + 445 cells filled). The run ended with "Could not come up from the water" at the dive spot 1756 57 -1094: the player had drifted sideways under blocks it had just placed.
* Every start first tried the unreachable stand 1747 59 -885. Baritone ran 4 to 7 searches of 13 to 34 million moves for it, because the user's Baritone settings are `allowBreak false` and `allowPlace false`.
* The dirt chest was full again when the leftovers were put back. Something refills it (a hopper or a farm?). Ask the user.
* Fixes:
  * `fill_water` sets allowBreak true while it runs, via `RegionRules.setOwnBreak`, so safe areas still win. The old value comes back at the end.
  * `standFor` falls back to dug stands (`digStand`/`diggable`): hardness at most 3, not protected, not in the selection, not on Baritone's do-not-break list, no water next to it.
  * Under water, it never places into a column the body overlaps, nor into the dive column.
  * RISE steers to the nearest column that is open to the surface, or breaks the block above its head. It gives up after 30 s.

## 2.1.0: dive mode (installed 2026-11-07, not yet tested in game)

* Second run (log 22:23–22:31): area 1 is 1738 60 -1334 to 1759 66 -834 (500 long), and there is water below y 60. The y-60 layer was out of reach from the shore. After the bed trip the fill gave up (8 failed approach paths), the 56-block shore search could not cover the area anyway, and `put()` stopped the script because the dirt chest was full.
* TerminatorProcess rewritten with states WORK, WALK, SWIM, SINK, UNDER and RISE:
  * `plan()` scans the whole selection (lowest fillable cell per column, nearest first). For each cell it looks for a dry or wading stand first, else a dive spot (`diveSpotFor`: wet, solid below, open water straight up to the surface, not the cell's own column).
  * It walks to a shore stand with Baritone. Swimming, sinking and rising use its own key holds (keyUp, keyJump, keyShift). `tick()` releases the keys as soon as another process takes control.
  * Air below 110 means it rises. A dive that placed nothing marks that spot bad. Each cell gets a 3 s reach timeout. After 40 plans in a row without placing anything, it finishes.
* Option `buildtools.dive` defaults to true.
* `put()` into a full chest only warns now. `dump()` that fits nothing still stops the script.
* Install with `install-script.bat` (it now installs 2.1.0) once the AFK profile is closed.

## Where things are

* Source: `F:\workflow\Baritone_tools\versions\26.1.1-queue\`. It now contains four packages:
  * `dev.baritonequeue`: the language and the editor.
  * `dev.survivalkit`, `dev.buildtools`, `dev.loadchest`: copies of the addon code, changed only where noted below.
* The addon source folders `26.1.1-survivalkit`, `26.1.1-buildtools` and `26.1.1-fabric` are kept but no longer built or installed. All changes go into the copies inside `26.1.1-queue`.
* The queue as it was at 1.9.6 is in `26.1.1-queue-backup-1.9.6\`.
* `fabric.mod.json` registers four client entrypoints. It no longer declares `provides`, so never put the old addon jars back next to it (duplicate classes).
* Build: run `cmd //c ".\build.bat"` inside `26.1.1-queue` (Bash tool, dangerouslyDisableSandbox). The jar is `build/libs/baritonequeue-2.0.0.jar`.
* Install: `install-script.bat` in this folder. It refuses to run while Minecraft is open. It moves the survivalkit, buildtools, loadchest and old baritonequeue jars into `…\profiles\26.1.1 AFK\mods-disabled\` (they are not deleted) and copies 2.0.0 in. **Never kill Minecraft**: wait until the user says it is closed.
* Offline language tests: `F:\workflow\Baritone_tools\tmp\langtest\` (`LangTest.java`; the classpath comes from `cp.bat`, which writes `cp.txt`). 46 cases plus the conversion of the old script all pass. Compile and run them with the JDK in `tools\`, against `cp.txt`.
* Nothing is pushed to GitHub. Push only when asked.

## Language design (dev.baritonequeue)

* `Lexer` → `Parser` (AST in `Ast`, which runs itself) → `Interp`/`Fiber`/`Runner`.
* Each script runs on its own thread, a **fiber**, but only while the game thread waits for it (semaphore handoff). So game state is never touched by two threads at once. It hands back control every 4000 steps.
* Functions that take time (`#` lines, chest jobs, `fill_water`, `sleep` …) are **Actions** (`Actions.java`). The script sets a factory and yields. The game thread creates the action, ticks it, and wakes the script when it is done.
* `on <cond> … end`: conditions are checked on the game thread every 10 ticks and fire when the condition becomes true. The running action is suspended (Baritone tasks are cancelled), the block runs on its own fiber, then the action starts again. Respawn and reconnect use the same suspend/resume.
* Built-in functions are in `Builtins.java` (general) and `GameBuiltins.java` (game). Getters such as `health` and `night` work without `()`.
* `option("group.field", v)` (`Modules.java`) reads and writes every field of the old addon configs by reflection, and saves to the same json files as before.
* Old scripts and presets are converted once on load (`Migrate.java`, `Config.migrate`). The old script is kept in `baritonequeue.json` → `scriptBeforeV2`.
* Editor: `Syntax` (colours from the lexer), `Suggest` (completions, item and block names inside quotes, Baritone completion after `#`, and the looked-at block as `pos(…)`), `ScriptCheck` (the Test button), `Places` (raycast up to 96 blocks).

## The water fill fix (dev.buildtools.TerminatorProcess, rewritten)

The user reported that the old "terminator" did not fill from the bottom up. The cause was that `Util.isWater` only counted plain water blocks. Seagrass, tall seagrass, kelp and bubble columns on the lake floor were "not water", so the cell above them looked fillable and the water got sealed in.

Now:
* A wet cell is any cell whose fluid is water and that does not block movement.
* A cell is filled only when no wet cell is below it in its column, inside the selection.
* A cell that failed 3 times stays wet, so the cells above it stay blocked too.
* Kelp and other breakable plants are broken first.
* It may stand in water 1 deep with its head dry, which gives about one block more reach.
* With sand, gravel or concrete powder it drops the block into the top wet cell of a column, and the block sinks.
* It warns when there is water right under the bottom of the selection.
* `fill_water()` returns the number of wet cells left. `lastResult` holds {filled, left, not loaded}.

## Changes inside the copied addon code

* `survivalkit/EatProcess`: `forced` flag for `eat()`.
* `survivalkit/Api`, `buildtools/Api`: new public entry points.
* `loadchest/LoadChestMod`: `INSTANCE`, `unloadNow`, `pickupNow` (a forced sweep picks up every item, for at most 2 min), config accessors.
* `buildtools/BuildToolsMod`: the terminator command prints the error that `startNow` now returns.

## The user's water job in the new language

This is also the preset "Water job: fill, then mine (chests)". The positions are from the old script.

```
let dirt_chest = pos(1794, 74, -840)
let dump_chest = pos(1787, 74, -837)

area("1")
loop
  take_upto(dirt_chest, "dirt", 512)
  let left = fill_water("dirt")
  if left == 0 or has("dirt") then break end   // done, or the rest cannot be reached
end
put(dirt_chest, "dirt")

on full
  dump(dump_chest)
end
#buildInLayers true
#layerOrder true
for name in ["2", "3"]
  area(name)
  #sel ca
  dump(dump_chest)
end
```

On first start, the old saved script is converted line by line. `dump` becomes `dump_chest` because `dump` is a function now. The result works, but the version above is better: it fetches more dirt until the water is gone.

## State: NOT tested in game yet

Tested offline only: the parser, the interpreter, and the conversion. 2.0.0 is installed in the 26.1.1 AFK profile (2026-11-07); the old queue and addon jars are in `mods-disabled`. The game that was running at the time used another profile (Bestes_projekt_1.26.0), which does not lock the AFK mods.

First steps in the next session:
1. Installed already. Check that the mods folder has `baritonequeue-2.0.0.jar` and no survivalkit, buildtools or loadchest jar. `baritoneui-1.10.0.jar` stays.
2. Start the game. Check the log for errors: `…\profiles\26.1.1 AFK\logs\latest.log`, grep `baritonequeue`, `Exception`, `[Queue]`.
3. Press J. The old script should show as converted. Press Test.
4. Tab/Pick check: look at a chest, press J, type `let c = ` and press Tab. It should offer `pos(x, y, z)`. Then check that Pick block puts a `let chest = pos(…)` line in.
5. Run the water job from the shore of area 1. Expected chat lines:
   * `Chest: take up to 512 dirt from at …` and `Took … dirt`
   * `Filling the water from the bottom up with dirt.`, maybe `Walking to the selection first`
   * the progress bar counting wet cells down
   * `Water fill done: N cells filled, …`
6. If something stops: the user pastes the `PROBLEM in line N (…)` message, where they stood, and the selection corners.

Things to watch: eating and sleeping during `fill_water` (the queue sets `baritonequeue.active`, so SurvivalKit acts), `on full` interrupting `#sel ca`, and whether Baritone wades into water 1 deep to reach the new standing spots.

## Not built yet

* Exact item counts when moving stacks (chest moves are still whole stacks).
* Taking from any chest via the chest index (`find_item` only lists them).
* `on` conditions are edge-triggered only. There is no "every N seconds" form yet.
* `scriptBeforeV2` can be removed once the user is happy with 2.0.

## 2.3.8 (installed)
- Restock loop: `restock_needed` ignored the "supply chest is short on X - I'll look again in N min" wait, so with no
  diamond_shovel / food in the tool chest the `on restock_needed` block walked back to the base every minute for an hour.
  Now `needed()` skips rules that are still waiting (5, 10, 20, then every 30 min), a running script's `restock()` keeps
  those waits (only a hand-typed `/survival restock now` clears them), and a tool newly worn out still clears its wait.
