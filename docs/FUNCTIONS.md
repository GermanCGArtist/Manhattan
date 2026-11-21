# Function reference

Every built-in function and getter of the Manhattan script language, grouped like the editor's completion list.
The language itself (variables, `if`, `on`, `job` ...) is in [SCRIPTING.md](SCRIPTING.md).

How to read the tables:

* **waits**: the function takes time in the world (walking, mining, a chest trip). The script pauses until it is
  finished. While it waits, `on` blocks can still interrupt it; afterwards it carries on where it was.
* `[ ... ]` marks optional arguments; `a | b` means one or the other.
* A **chest** argument is `pos(x, y, z)`, or the name of a chest saved with `/queue chest set <name>` (also
  `chest("name")`). `take`, `take_upto` and `put` also accept a **list of chests** (see [The chest index](GUIDES.md#the-chest-index)).
* A **position** is `pos(x, y, z)`. Most functions also take three numbers instead (`block(10, 64, -3)`).
* Item and block names are the Minecraft ids without `minecraft:` (`"dirt"`, `"oak_log"`). `"food"` means any
  food where noted; `"log"` in `count()` counts every log.
* When a waiting function fails (no chest there, item missing, no path), the script stops with the line number and
  the reason, unless the call is inside `try ... catch`.
* Every function can also be written as a method of its first argument: `names.push("x")` is `push(names, "x")`.

Getters (marked *getter*) are read **without** parentheses: `if food < 10`.

---

## World: what the game looks like

| function | returns |
|---|---|
| `here` *getter* | where you stand, as a position |
| `pos(x, y, z)` / `pos()` | a position; `pos()` alone is where you stand. A position has `.x`, `.y`, `.z` |
| `health` *getter* | your health, 0 to 20 |
| `food` *getter* | your food bar, 0 to 20 |
| `night` *getter* | true at night (in the overworld) |
| `day` *getter* | true by day (always true outside the overworld) |
| `raining` *getter* | true while it rains |
| `thundering` *getter* | true during a thunderstorm |
| `daytime` *getter* | the time of day in ticks, 0 to 23999 (0 = morning, 13000 = dusk) |
| `dimension` *getter* | `"overworld"`, `"the_nether"` or `"the_end"` |
| `full` *getter* | true when no inventory slot is free |
| `free_slots` *getter* | how many inventory slots are empty |
| `busy` *getter* | true while the path engine (or a helper) works on a task |
| `mobs` *getter* | hostile mobs within 7 blocks that are worth fighting |
| `mobs_near(blocks)` | hostile mobs within that many blocks |
| `restock_needed` *getter* | true when a tool is worn out or something on the want-list is below half |
| `bed_time` *getter* | true at night or in a thunderstorm, or when it is time to leave so you reach the bed before dusk |
| `looking` *getter* | the block you look at, as a position (`null` if none) |
| `count("item")` | how many of it you carry (`"log"` counts every log, `"food"` every food) |
| `has("item" [, n])` | true if you carry at least n (default 1) |
| `block(position)` | the block there, like `"stone"` or `"water"` |
| `is_wet(position)` | true if the cell holds water and is not solid (water, seagrass, kelp, bubble columns) |
| `dist(a [, b])` | blocks from you to a (or from a to b) |
| `count_blocks("block", ...)` | how many of these blocks are in the selection |
| `water_left()` | wet cells in the selection |

## Movement and the path engine

All movement goes through **Midtown**, Manhattan's own path engine (see [DESIGN.md](DESIGN.md)).

| function | |
|---|---|
| `goto(position)` / `goto(x, z)` | waits; walks to a block, or to an (x, z) column at any height |
| `mine("block", ...)` | waits; mines matching blocks nearby |
| `follow(["name"])` | waits; walks to the nearest entity (only ones whose name matches, if given) |
| `tunnel([blocks])` | waits; digs a tunnel straight ahead (default 16 blocks) |
| `cancel()` | stops whatever the path engine is doing; the script goes on |
| `wait(seconds)` | waits; does nothing for a while. Also written `wait 30` and `wait until <condition>` |
| `baritone("command")` | waits; the same as a `#` line, with a text you built (see [legacy # lines](SCRIPTING.md#legacy--lines)) |
| `chat("/command")` | waits; the same as a `/` line: runs a chat command (server or client) |
| `disconnect([reason])` | stops the script cleanly, switches off automatic reconnect and leaves the server or world |
| `stop([text])` | ends the script here (with a message) |

## Building, digging and water

These work on the **selection** (set it with `area()`, `select()`, `add_box()` or `#sel`). Big selections are split
into 32x32 chunks and worked chunk by chunk; finished chunks are remembered (see [Chunked runs](GUIDES.md#chunked-runs-and-resuming)).

| function | |
|---|---|
| `fill_water(["block" [, chest [, per_trip]]])` | waits; fills the selection's water, every column strictly bottom-up. With a chest it fetches more blocks whenever they run out (`per_trip`, default 512). Returns the wet cells left (0 = done). [Details](GUIDES.md#filling-water-fill_water) |
| `clear_area([layers])` | waits; mines out the selection from the top, 3 layers per pass (`clear_area(2)`: 2), picking up drops on the way. Leaves blocks next to water or lava and in safe areas alone. Returns the blocks it could not get at. [Details](GUIDES.md#digging-out-an-area-clear_area) |
| `clear_trees([chest])` | waits; mines every tree in the selection, nearest first, takes its scaffolding down after each tree, then picks up saplings. With a chest, a full inventory goes into it. Returns what was gained as a map item → count. [Details](GUIDES.md#clearing-trees-clear_trees) |
| `drain()` | waits; removes the flowing water that blocks building (in the selection, or 8 blocks around you) |
| `flood()` | waits; fills the selection **with** water, using buckets (seals the open sides first) |
| `collect_scaffold()` | waits; takes down the blocks the engine placed to reach things, and picks them up |

## Litematica schematics

A placement is its number in `schem_list()` or its name in Litematica. With only one placement enabled you can leave
it out. Litematica itself is optional: without it, placements are read from Litematica's file in `config/litematica/`.

| function | |
|---|---|
| `schem_list()` | lists the placements in chat; returns how many there are |
| `schem_build([n \| "name" \| "all"] [, "add" \| "fix" \| "exact"] [, "skip" \| "strict"])` | waits; builds the placement phase by phase (clear, full blocks, shaped, attached, redstone, power). Returns the blocks still wrong (0 = done). [Modes](GUIDES.md#building-litematica-schematics) |
| `schem_materials([which] [, mode])` | prints the items still needed against the inventory; returns how many items you lack |
| `schem_needed([which] [, mode])` | the items still needed (minus what is built, not minus the inventory) as a map, e.g. `{stone: 120, oak_stairs: 8}` |
| `schem_check([which] [, mode])` | prints what differs between schematic and world; returns the blocks that still need work |
| `schem_left(["reset"])` | blocks of everything built so far that are **still not there**, over all `schem_build()` calls (also in sections no longer loaded), grouped by reason. 0 = really done. `"reset"` forgets the list |
| `schem_stop()` | stops the running build |

## Chests

| function | |
|---|---|
| `take(chest \| chests, "item" [, n \| "all"])` | waits; takes one stack, n, or all. Returns how many it took |
| `take_upto(chest \| chests, "item", n [, keep_free])` | waits; takes only as many as needed so you carry n in all. With a list of chests it stops when only `keep_free` slots are empty. Returns how many it took |
| `put(chest \| chests, "item" [, n])` | waits; puts the item in (default all of it). Returns how many |
| `dump(chest)` | waits; puts in everything except the tools and armour you need, food, buckets and the `keep` rules. Returns how many |
| `unload([chest] [, "filter"])` | waits; walks to a chest (the given one, the saved LoadChest chest or the nearest) and empties the inventory, keeping the `keep` rules |
| `chest("name")` | the position of a chest saved with `/queue chest set <name>` |
| `find_item("item")` | chests that held it when last seen open: a list of `{pos, count}`, nearest first. Never walks |
| `chest_contents(chest)` | what the chest held when last open: a map item → count (`null` if never seen). Never walks |
| `chest_age(chest)` | minutes since the chest was last seen open (`null` if never seen). Never walks |
| `index_refresh([minutes])` | how old a chest index entry may get before a list fetch opens that chest again (default 30, `0` = always). With a number it sets it for this run. Returns the value |

## Survival helpers

| function | |
|---|---|
| `eat()` | waits; eats until the food bar is full (also food held in the off hand). False if it was full already |
| `defend()` | waits; fights every hostile mob nearby until none is left; backs away from a creeper about to blow. False if there was none |
| `sleep()` | waits; walks to the saved bed (`survival.bed`) and sleeps. Leaves early enough to be there before dusk. False by day |
| `restock()` | waits; goes to the supply chest(s) and takes what the want-list says; hands in worn-out tools |
| `pickup()` | waits; picks up every dropped item nearby |
| `want("item", count)` | `restock()` keeps this many with you (`"food"` = any food). 0 removes the rule |
| `work_item("rule")` | a tool with enchantments to keep, e.g. `work_item("diamond_pickaxe 2 efficiency 5")` |
| `keep("item", count)` | `dump()` and `unload()` leave this many in the inventory (-1 = all of it; `"food"` = any food) |
| `hotbar(slot, "item")` | puts that item into hotbar slot 1-9 (`"food"` = any food, `"pickaxe"` = any pickaxe). False if you have none |
| `enable("feature", ...)` | lets helpers act **by themselves** while this script runs. Names: `eat`, `sleep`, `defence`, `restock`, `escape`, `drain`, `scaffold`, `pickup`, `autoempty`. None = all |
| `disable("feature", ...)` | keeps helpers from acting by themselves while this script runs (none = all) |

The helpers never act on their own unless the script says so. `on` blocks (see [SCRIPTING.md](SCRIPTING.md#on-blocks))
give you full control; `enable(...)` is the short way. Respawn, resume after death and reconnect are always on
(see [OPTIONS.md](OPTIONS.md#survival)).

## Areas and the selection

| function | |
|---|---|
| `area("name")` | selects a saved area |
| `area_box("name", corner1, corner2)` | an area written in the script (the Capture drawer writes these). The first `area_box` of a name in a run replaces what was saved under it; more of the same name add boxes. It does not select it: `area("name")` does |
| `select(corner1, corner2)` | makes the selection this box (replaces it) |
| `add_box(corner1, corner2)` | adds a box to the selection |
| `clear_selection()` | no selection any more |
| `save_area("name")` | saves the selection under a name |
| `selection()` | the boxes of the selection: a list of `[corner1, corner2]` |
| `areas()` | the names of the saved areas |
| `stairs("name", corner1, corner2)` | the stair shaft of a dig: an area (at least 2 x 2) inside the area `clear_area()` digs. It is never mined; a spiral staircase is carved along its ring, a step deeper with every layer, and the bot leaves the hole by it when an `on` block fires. [Details](GUIDES.md#a-stair-shaft-for-getting-out-of-the-hole) |
| `protect("name" [, corner1, corner2])` | a safe area: the engine may walk through it but never breaks or places blocks there (no corners: the selection) |
| `unprotect("name")` | removes a safe area; true if it existed |

## Settings and limits

| function | |
|---|---|
| `set("setting", value)` | changes a path engine setting (see [OPTIONS.md](OPTIONS.md#path-engine-settings-set)) |
| `setting("setting")` | the value of a path engine setting |
| `option("group.name" [, value])` | reads or changes a helper option, e.g. `option("survival.eatBelow", 10)` (see [OPTIONS.md](OPTIONS.md)) |
| `options(["group"])` | prints every option and its value (or those of one group) |
| `timeout(seconds \| null)` | an action that takes longer is stopped and the script goes on (`null`: no limit) |
| `stall(minutes \| null)` | a task without progress for this long is skipped (`null`: never). Default 30, see `/queue stall` |

## General

| function | |
|---|---|
| `print(value, ...)` | writes a message in the chat |
| `log(value, ...)` | writes a line into the game log only (`logs/latest.log`) |
| `str(value)` | the value as text |
| `num(text)` | the text as a number |
| `int(value)` | the whole-number part (rounds down) |
| `type(value)` | `"number"`, `"text"`, `"list"`, `"map"`, `"pos"`, `"bool"`, `"null"` or `"function"` |
| `len(list \| text \| map)` | how many items or letters |
| `range(n)` / `range(from, to [, step])` | a list of numbers: `range(3)` is `[0, 1, 2]`, `range(1, 4)` is `[1, 2, 3]` |
| `push(list, value)` | adds a value at the end |
| `pop(list [, index])` | takes the last (or that) item out and returns it |
| `insert(list, index, value)` | puts a value in at that place |
| `remove(list \| map, value \| key)` | takes a value out of a list, or a key out of a map; true if it was there |
| `contains(list \| map \| text, value)` | true if it is in there (same as `value in list`) |
| `index_of(list \| text, value)` | where it is (from 0), or -1 |
| `keys(map)` / `values(map)` | the keys / values of a map, as a list |
| `join(list [, between])` | the items as one text |
| `split(text [, separator])` | cuts a text into a list (at spaces, or at the separator) |
| `upper(text)` / `lower(text)` / `trim(text)` | capital letters / small letters / without spaces at the ends |
| `replace(text, old, new)` | every `old` replaced |
| `starts_with(text, start)` / `ends_with(text, end)` | true if the text starts / ends like that |
| `sort(list)` / `reverse(list)` | a sorted / reversed copy |
| `copy(list \| map)` | a copy, so changing one does not change the other |
| `sum(list)` | all numbers added up |
| `min(a, b, ...)` / `max(a, b, ...)` | the smallest / biggest (also of one list) |
| `abs(n)`, `floor(n)`, `ceil(n)`, `round(n [, digits])`, `sqrt(n)` | the usual maths |
| `random()` / `random(to)` / `random(from, to)` | `random()` is 0..1; `random(1, 6)` is a whole number from 1 to 6 |
| `now()` | milliseconds since 1970: `(now() - start) / 1000` is seconds passed |
| `clock` *getter* | the real (wall-clock) time as `"HH:MM"`: `wait until clock == "06:30"` |
| `error(text)` | stops with this message (`try`/`catch` can catch it) |
| `assert(condition [, text])` | stops with a message if the condition is false |
