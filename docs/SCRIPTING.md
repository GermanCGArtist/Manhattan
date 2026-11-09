# Manhattan – the script language

Open the editor with **J**. **Run** starts the script, **Test** checks it without running it, **Stop** ends it, **Skip** ends the action that runs now and carries on, **Pick block** lets you aim at a block and put its position into the script.

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
| `clear_area()` | waits; mines out the selection itself instead of Baritone's builder (details below). Returns the blocks left that it could not get at (not counting the ones next to water or lava, or protected) |
| `drain()` | waits; removes flowing water that blocks the builder |
| `flood()` | waits; fills the selection with water, using buckets |
| `collect_scaffold()` | waits; takes down the blocks Baritone placed to reach things |
| `protect("name" [, corner1, corner2])` | safe area: Baritone never breaks or places there (no corners = the selection) |
| `unprotect("name")` | |

How `fill_water` works (2.1.0): a **wet cell** is anything holding water that is not solid, so plain water, seagrass, kelp and bubble columns all count. A cell is only filled when **no wet cell is left below it in its column**. Kelp is broken first.

It looks through the **whole selection** for the nearest cell it can do next. After a bed trip, a meal or a fight it walks back by itself. From dry ground (or water 1 deep, head dry) it reaches about 3 blocks down.

Deeper cells are done in **dive mode** (on by default; switch it off with `option("buildtools.dive", false)`). It picks a spot on the bottom next to the cell, with open water straight up to the surface, so it is never under a roof. It walks to the shore, swims there on the surface, sinks down, places what it can reach lowest first, and comes up when its air drops below about a third. It never builds in the column it dives in. Sand or gravel (`fill_water("sand", chest)`) sink to the bottom by themselves.

**clear_area** (2.2.0):
* It works from the top in passes 3 layers thick, walking lanes along the long side 6 blocks apart and stopping every 3 blocks.
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
| `area("1")` | selects a saved area (`/queue area save 1`) |
| `select(corner1, corner2)`, `add_box(…)`, `clear_selection()`, `save_area("x")`, `selection()`, `areas()` | |
| `timeout(600)` / `timeout(null)` | longest time one action may take |
| `stall(30)` / `stall(null)` | a Baritone task without progress this many minutes is skipped |

**What the game looks like** (the getters are read without `()`):

`health`, `food`, `mobs` (hostile mobs within 7), `bed_time`, `restock_needed`, `night`, `day`, `raining`, `thundering`, `full`, `free_slots`, `busy`, `here` (your position), `looking`, `daytime`, `dimension`, `clock` ("HH:MM").
`count("dirt")`, `has("dirt" [, n])`, `block(pos)`, `is_wet(pos)`, `dist(a [, b])`, `count_blocks("stone", …)` and `water_left()` (both count in the selection), `pos()` (where you stand).

**General:** `print`, `log`, `str`, `num`, `int`, `type`, `len`, `range`, `push`, `pop`, `insert`, `remove`, `contains`, `index_of`, `keys`, `values`, `join`, `split`, `upper`, `lower`, `trim`, `replace`, `starts_with`, `ends_with`, `sort`, `reverse`, `copy`, `sum`, `min`, `max`, `abs`, `floor`, `ceil`, `round`, `sqrt`, `random`, `error`, `assert`, `now`, `stop`.
`list.push(x)` is the same as `push(list, x)`; that works for every function.

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
