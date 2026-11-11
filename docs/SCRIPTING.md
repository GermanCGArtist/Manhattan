# Manhattan – the script language

Open the editor with **J**. **Run** starts the script, **Test** checks it without running it (the script itself, and, in a world, what it will meet: whether there is a chest, barrel or shulker box at every `pos(…)` used with take/put/dump/fill_water and the survival chests, a bed at `survival.bed`, whether the Litematica placements named in `schem_*` exist and are enabled, which items a `schem_build` needs that are in neither your inventory nor any chest seen open, chests seen open that lack an item `take`/`take_upto` asks for, and a bucket and a first water source for `flood()`; positions that are not loaded are reported, not checked), **Stop** ends it, **Skip** ends the action that runs now and carries on, **Pick block** lets you aim at a block and put its position into the script.

The helpers that used to be the SurvivalKit, BuildTools and LoadChest addons are built in. Everything they did is a script function (below); their chat commands (`/survival …`, `/buildtools …`, `/loadchest …`) still work for doing things by hand. None of them acts on its own unless the script switches it on.

## Lines

```
// a comment              /* a longer comment */
#sel ca                   a Baritone command: waits until Baritone is done
#goto {home}              {…} puts a value in:  #goto 100 64 -20
#buildInLayers true       a Baritone setting (same as #set buildInLayers true)
/time query daytime       a chat command (server or client)
```

A `#` or `/` line runs to the end of the line. Everything else is the language:

```
let home = pos(100, 64, -20)      // a variable
let n = 3
n += 1                             // also -= *= /=
let names = ["2", "3"]             // a list:  names[0], len(names), push(names, "4")
let stock = {dirt: 64, sand: 32}   // a map:   stock.dirt, stock["sand"]
print("n is {n}")                  // texts in "…" fill in {…}; '…' never does

if food < 8 and has("bread") then  // then / do are optional
  eat()
elif night
  sleep()
else
  print("fine")
end

while count("dirt") < 256 … end
for name in names … end            // also: for i, name in names … end,  for i in range(5) … end
repeat 3 … end
loop … end                         // forever, until break or stop()
break   continue

func refill(n)
  take_upto(dirt_chest, "dirt", n)
  return count("dirt")
end

try
  take(dirt_chest, "dirt", 64)
catch err
  print("could not: {err}")
end

wait 30                            // seconds
wait until day                     // any condition
stop("done")                       // ends the script
```

Comparisons: `== != < <= > >=`, `in`, `not in`. Logic: `and or not`. Numbers: `+ - * / %`. A single `=` inside `if` is reported (use `==`).

### on: things that should happen whenever they become true

All the survival work done by the script itself:

```
on mobs > 0 do defend() end
on food < 14 do eat() end
on bed_time do sleep() end
on restock_needed do restock() end
on full do dump(dump_chest) end
```

```
on full
  dump(dump_chest)
end
```

An `on` block is switched on when the script reaches it, and stays on until the script ends. It is checked twice a second. When its condition **becomes** true (or is still true a minute after the block last ran), the running action is paused (a Baritone task is cancelled), the block runs, then the action starts again. It fires again only after the condition was false in between.

Note: the script ends after its last line, and the `on` blocks end with it. To keep them watching, end with something that waits, for example `wait until false`.

## Picking positions (chests, beds, corners)

* **Tab, like /setblock:** the editor remembers the block under your crosshair when you opened it (up to 96 blocks away). After `(`, `,` or `=`, the list offers `pos(x, y, z)` of that block. Tab or Enter takes it. Inside `pos(` it offers just the numbers, and in a `#` line it offers `x y z`.
* **Pick block button:** the editor closes. Aim at any block and press **J**. The editor comes back with the position put in. On an empty line it writes `let chest = pos(…)   // chest` (or `bed`, `barrel` …). Pressing J while looking at the sky goes back without inserting anything.
* **Tab with nothing shown** asks for completions, like in chat. If there is only one choice it is taken. **Ctrl+Space** lists everything.

## Capture menu (key K): places and areas for the script, or for an LLM

Instead of copying every chest, bed and corner by hand, the capture menu collects them into a **sheet**: plain
script that can be pasted into a script, or handed to an LLM to write the script around it.

* **Role** (drop-down): dump, supply / tools, return chest, material chest, scaffolding chest, bed, mine area, fill
  area, building block area, safe area, other spot, other area, schematic (Litematica). **Name** is optional (other spot and other
  area use it as their name; an area with a name you used before gets one more box).
* **Fetch looked-at** takes the block you were looking at when you opened the menu.
* **Aim in world (J)** closes the menu: look at a block and press J. With **Rapid: on** every press adds a line and
  you stay in the world (chest after chest); K brings the menu back, and so does J while looking at the sky.
* Areas take two corners (Fetch or J, twice); the box being made shows in sparks while you aim.
  **Use selection** takes the current `#sel` selection instead.
* **Schematic**: pick a Litematica placement in the second drop-down, then Fetch: it adds `let canal = "canal"`, so
  the script can say `schem_build(canal)`. **Import saved** adds the chests and areas saved with `/queue chest set`
  and `/queue area save`, and every Litematica placement the sheet does not have yet.
* **Copy** puts the sheet on the clipboard. **Copy full context** adds what an LLM needs: the world, where you stand,
  the inventory, the want-list, what each area holds (air, water cells, the most common blocks), lines that look
  wrong (a "chest" that is now air), the Litematica placements and the list of built-in functions.
* **Show in world** marks the captured places (green; flames when the block is no longer a chest or bed) and the
  areas (white edges, safe areas in small flames).
* **Set**: several sheets per world (main base, outpost ...): type a name, **Open set**.

The sheet is a file, one per world and set: `<game folder>/manhattan-context/<world>_<set>.txt`, and the full context
next to it as `<world>_<set>.context.txt`, so an LLM with file access can read it directly. `/queue capture dir
<folder>` puts them somewhere else (`/queue capture dir default` back). Edits in the menu are saved to the file; when
the file is changed outside, the menu shows the new text.

A sheet looks like this; `//` in front of a line switches it off:

```
// --- places
let dump_chest = pos(1787, 74, -837)   // dump: dump() puts the inventory in here; chest; holds: 640 cobblestone
let dirt_chest = pos(1794, 74, -840)   // material: blocks for fill_water / building; chest; holds: 1728 dirt
let bed = pos(1791, 74, -839)   // bed: sleep() uses it; red_bed

// --- areas
area_box("mine_1", pos(1700, 60, -900), pos(1720, 74, -880))   // mine: clear_area() mines it out; 21x15x21 = 6615 blocks
// area_box("fill_1", pos(1730, 50, -900), pos(1750, 62, -880))   // fill: switched off
protect("protect_1", pos(1779, 68, -844), pos(1797, 80, -832))   // safe: Baritone never breaks or places blocks here

// --- schematics
let canal = "canal"   // schematic: Litematica placement at 1750 64 -850, 2 regions; schem_build(canal) builds it
```

## Functions

The ones marked **waits** take time in the world: the script waits until they are finished. When one fails (for example the chest has no dirt), the script stops with the line number, unless it is inside `try`.

**Chests** (a chest is `pos(x, y, z)`, or `chest("name")` for one saved with `/queue chest set <name>`):

| function | |
|---|---|
| `take(chest, "dirt" [, n \| "all"])` | waits; takes one stack, n, or all; returns how many |
| `take_upto(chest, "dirt", 512)` | waits; tops up until you carry 512 |
| `put(chest, "dirt" [, n])` | waits; puts in all of it (or n) |
| `dump(chest)` | waits; everything (hotbar included) except the tools and armour the want-list asks for (best first; with no want-list, the best one of each kind), food (not rotten flesh, spider eyes, raw chicken ...), buckets and the `keep` rules |
| `unload([chest] [, "filter"])` | waits; LoadChest's trip: nearest or saved chest, its keep rules |
| `find_item("diamond")` | chests that held it when last opened: list of `{pos, count}` |
| `chest_contents(chest)` | what it held when last opened: map item → count |

**Water and building:**

| function | |
|---|---|
| `fill_water(["dirt" [, chest [, per_trip]]])` | waits; fills the selection's water. With a chest it fetches more (512 per trip) whenever the blocks run out. Returns the number of wet cells left (0 = done) |
| `clear_area([layers])` | waits; mines out the selection itself instead of Baritone's builder, from the top, 3 layers per pass (or 2: `clear_area(2)`; details below). Returns the blocks left that it could not get at (not counting the ones next to water or lava, or protected) |
| `drain()` | waits; removes flowing water that blocks the builder |
| `flood()` | waits; fills the selection with water, using buckets |
| `collect_scaffold()` | waits; takes down the blocks Baritone placed to reach things |
| `protect("name" [, corner1, corner2])` | safe area: Baritone never breaks or places there (no corners = the selection) |
| `unprotect("name")` | |

**Litematica schematics** (2.5.0): builds your Litematica placements with Baritone's own builder (never the Litematica printer).
A placement is its number in `schem_list()`, or (2.6.0) its name in Litematica: `schem_build("canal")`.

| function | |
|---|---|
| `schem_list()` | lists the placements in chat; returns how many |
| `schem_build([n \| "name" \| "all"] [, "add" \| "fix" \| "exact"] [, "skip" \| "strict"])` | waits; builds placement n (or all enabled ones; with only one enabled you can leave n out). **skip** (default): blocks you carry no item for are left out and the rest is built; **strict**: the phase stops as soon as an item is missing. Returns the blocks still wrong (0 = done) |
| `schem_materials([n \| "name" \| "all"] [, mode])` | prints the items still needed (minus what is built) against the inventory; returns how many items are missing |
| `schem_needed([n \| "name" \| "all"] [, mode])` | the items still needed (minus what is built, not minus the inventory) as a map item → count, e.g. `{stone: 120, oak_stairs: 8}`; use it to fetch from chests |
| `schem_check([n \| "name" \| "all"] [, mode])` | prints the diff: correct, missing, wrong, to dig out, with examples; returns the blocks that still need work |
| `schem_stop()` | stops the running build |

The same as chat commands: `/schem list`, `/schem build [n|all] [add|fix|exact] [skip|strict]`, `/schem materials [n|all] [mode]`, `/schem check [n|all] [mode]`, `/schem move <n|all> <dx> <dy> <dz>`, `/schem stop`.

To run the same thing somewhere else, `/queue move <dx> <dy> <dz>` moves every saved area, saved chest and Litematica placement by that offset (`/queue area move <name|all> <dx> <dy> <dz>` only the areas). Positions written into the script as `pos(...)` do not move: add the offset to them.
A script line `/schem build 1 add` works too (the script waits while it builds).

* **add** (default for build): schematic air is ignored; blocks that are already there are left alone; only empty or replaceable spots (air, water, grass ...) are filled.
* **fix**: schematic air is ignored; wrong blocks are broken and replaced.
* **exact**: like fix, and schematic air is dug out first (its own phase, top-down), for example to build into a hill.
* Phases, each one a Baritone builder run, bottom-up layer by layer: clear (exact only) → full blocks → shaped (stairs, slabs, fences, panes ...) → attached (torches, doors, signs, carpets, plants ...) → redstone (dust, repeaters, pistons ...) → power sources (levers, buttons, plates, redstone torches). Fluids are not built yet; they are listed in the report.
* The material list counts what is still missing: a double slab is 2 slabs, a door or bed 1 item, redstone dust is `redstone`, water or lava a (reusable) bucket.
* Blocks inside safe areas (`protect`) are never built or dug.
* Placements come from the running Litematica (found by reflection; the mod also works without it) or, failing that, from Litematica's own file in `config/litematica/`. Position, rotation, mirror, enabled state and sub-regions are used; where placements overlap, the later one in the list wins (air never overwrites a block).

How `fill_water` works (2.1.0): a **wet cell** is anything holding water that is not solid, so plain water, seagrass, kelp and bubble columns all count. A cell is only filled when **no wet cell is left below it in its column**. Kelp is broken first.

It looks through the **whole selection** for the nearest cell it can do next. After a bed trip, a meal or a fight it walks back by itself. From dry ground (or water 1 deep, head dry) it reaches about 3 blocks down.

Deeper cells are done in **dive mode** (on by default; switch it off with `option("buildtools.dive", false)`). It picks a spot on the bottom next to the cell, with open water straight up to the surface, so it is never under a roof. It walks to the shore, swims there on the surface, sinks down, places what it can reach lowest first, and comes up when its air drops below about a third. It never builds in the column it dives in. Sand or gravel (`fill_water("sand", chest)`) sink to the bottom by themselves.

**clear_area** (2.2.0):
* It works from the top in passes 3 layers thick (`clear_area(2)`: 2 layers per pass), walking lanes along the long side 6 blocks apart and stopping every 3 blocks.
* At every stop it breaks everything in reach, highest first. Blocks that break at once (Haste + Efficiency) go at one per tick, others as fast as the game allows. It takes the fastest tool, but not one the item saver protects.
* Short level steps it walks itself and picks up drops right away. Longer or awkward moves go to Baritone, which may dig while clear_area runs.
* It leaves blocks next to water or lava alone (no flooding), and respects safe areas and Baritone's do-not-break list.
* At the end it prints what is left and why (next to water or lava, protected, could not get at) and where the time
  went. It returns only the blocks it could not get at; the wet and protected ones are never worth another go. Don't
  hand the rest to `#sel ca`: Baritone's builder will not break next to water either and walks back and forth.

**Survival:**

| function | |
|---|---|
| `eat()` | waits; eats until the food bar is full (false if it was full) |
| `defend()` | waits; fights every hostile mob nearby until none is left; backs away from a creeper about to blow |
| `sleep()` | waits; goes to the saved bed and sleeps. Also leaves before dusk when the bed is far, and waits there. At day it returns false |
| `restock()` | waits; fetches what `want` lists from the supply chest |
| `pickup()` | waits; picks up every dropped item nearby |
| `want("food", 32)` | what restock keeps with you (0 removes the rule) |
| `keep("torch", 64)` | what dump/unload leave in the inventory (-1 = all) |
| `work_item("diamond_pickaxe 2 efficiency 5")` | like `/survival workitem` |

The helpers no longer act by themselves (since 2.0.1). To have them act while a script runs, put this near the top of the script: `enable("eat", "sleep", "defence")`. The names are eat, sleep, defence, restock, escape, drain, scaffold, pickup and autoempty; `enable()` alone means all of them. They are switched off again when the script ends. `disable(...)` keeps some from acting. Respawn, resume after death and reconnect stay on.

**Options** (every setting of the built-in helpers, saved in their json files):

```
option("survival.eatBelow", 10)
option("survival.bed", pos(…))          // Pick block on the bed
option("survival.supply", pos(…))
option("buildtools.escape", false)
option("loadchest.auto", true)
options()                                // prints all of them
options("survival")
```

**Baritone and the selection:**

| function | |
|---|---|
| `baritone("sel ca")` | the same as a `#` line, with a text you built |
| `chat("/say hi")` | the same as a `/` line |
| `goto(pos)` | waits |
| `cancel()` | stops Baritone's task (the script goes on) |
| `set("allowPlace", false)` / `setting("allowPlace")` | Baritone settings |
| `area("1")` | selects a saved area (`/queue area save 1`, or written by `area_box`) |
| `area_box("mine_1", corner1, corner2)` | an area written in the script itself (the capture menu writes these). The first `area_box` of a name in a run replaces what was saved under it, more of the same name add boxes. It does not select it: `area("mine_1")` does |
| `select(corner1, corner2)`, `add_box(…)`, `clear_selection()`, `save_area("x")`, `selection()`, `areas()` | |
| `timeout(600)` / `timeout(null)` | longest time one action may take |
| `stall(30)` / `stall(null)` | a Baritone task without progress this many minutes is skipped |

**What the game looks like** (the getters are read without `()`):

`health`, `food`, `mobs` (hostile mobs within 7), `bed_time`, `restock_needed`, `night`, `day`, `raining`, `thundering`, `full`, `free_slots`, `busy`, `here` (your position), `looking`, `daytime`, `dimension`, `clock` ("HH:MM").
`count("dirt")`, `has("dirt" [, n])`, `block(pos)`, `is_wet(pos)`, `dist(a [, b])`, `count_blocks("stone", …)` and `water_left()` (both count in the selection), `pos()` (where you stand).

**General:** `print`, `log`, `str`, `num`, `int`, `type`, `len`, `range`, `push`, `pop`, `insert`, `remove`, `contains`, `index_of`, `keys`, `values`, `join`, `split`, `upper`, `lower`, `trim`, `replace`, `starts_with`, `ends_with`, `sort`, `reverse`, `copy`, `sum`, `min`, `max`, `abs`, `floor`, `ceil`, `round`, `sqrt`, `random`, `error`, `assert`, `now`, `stop`.
`list.push(x)` is the same as `push(list, x)`; that works for every function.

## Presets

**Presets...** in the editor inserts ready-made scripts at the cursor. The ones that come with the mod (2.5.0): the canal
(dig, plug the old water, build the schematics, fill with water), building a Litematica schematic with materials from
chests, digging an area top down (`clear_area(2)`), mining an area or several areas in a row, the water job (fill with
dirt from a chest, then mine), filling water with dirt, filling an area with water (`flood()`), clearing trees, the
survival helpers, staying fed and sleeping, emptying into a chest when full, and the mining, farming and travel ones.
Positions are placeholders: put the cursor on a `pos(...)` and use Pick block.

When a new version brings new presets, the old ones you never changed are replaced; ones you edited stay, next to the
new one as "name (yours)", and your own presets are never touched.

## Old scripts

The first start of 2.0.0 converts the saved script and your own presets automatically. The old text stays in `config/baritonequeue.json` under `scriptBeforeV2`. Some examples:

| old | new |
|---|---|
| `sel ca` | `#sel ca` |
| `def_chest dirt 1 2 3` | `let dirt = pos(1, 2, 3)` |
| `def_chest dump 1 2 3` | `let dump_chest = pos(1, 2, 3)` (dump is a function now) |
| `area 1` | `area("1")` |
| `chest dirt get dirt to 512` | `take_upto(dirt, "dirt", 512)` |
| `chest dump put rest` | `dump(dump_chest)` |
| `/buildtools terminator dirt` | `fill_water("dirt")` |
| `if has dirt 64` / `if health below 5` | `if has("dirt", 64)` / `if health < 5` |
| `addons off escape` | `disable("escape")` |
