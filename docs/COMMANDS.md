# Chat commands

Everything a script does can also be done by hand with chat commands. Any of them can also stand in a script as a
`/` line (the script waits for it). Option names in `/survival` etc. map to the [options](OPTIONS.md).

- [/queue: the script, chests, areas, workspace](#queue)
- [/schem: Litematica schematics](#schem)
- [/survival: eat, sleep, fight, restock, death and reconnect](#survival)
- [/buildtools: water, scaffolding, safe areas](#buildtools)
- [/loadchest: emptying the inventory, picking up drops](#loadchest)

## /queue

| command | |
|---|---|
| `/queue` | prints the script's lines |
| `/queue open` | opens the workspace (same as **J**) |
| `/queue run` / `/queue start` | runs the script in the main tab |
| `/queue stop` | stops the script |
| `/queue skip` | ends the action that runs now; the script carries on with the next line |
| `/queue jobs` | the job ledger: which jobs and chunks earlier runs finished, and what is left (of the running script, or else of the editor's open tab) |
| `/queue reset [<job>]` | forgets what that job (or, without a name, the whole script) finished, so the next Run does it again. Not while a script runs |
| `/queue retry <job>` | opens the failed, deferred or interrupted parts of a job again; finished parts stay done. Not while a script runs |
| `/queue list` | prints the script's lines |
| `/queue add <line>` | adds a line at the end of the script |
| `/queue remove <n>` | removes line n |
| `/queue clear` | empties the script |
| `/queue stall` | shows the stall limit |
| `/queue stall <minutes>` / `/queue stall off` | a task without progress for this long is skipped (default 30; same as `stall()`) |
| `/queue ui` | says which layout J opens |
| `/queue ui edit` / `monitor` / `close` | switches the workspace layout, or closes it |
| `/queue capture` | opens the Capture drawer (same as **K**) |
| `/queue capture dir <folder>` / `dir default` | where the capture sheets are written |
| `/queue move <dx> <dy> <dz>` | moves every saved area, saved chest and Litematica placement by that offset |

**Saved chests** (used as `chest("name")` or by name in chest functions):

| command | |
|---|---|
| `/queue chest list` | lists them |
| `/queue chest set <name>` | saves the chest you look at |
| `/queue chest add <name> <x> <y> <z>` | saves a chest by position |
| `/queue chest remove <name>` | forgets it |

**Saved areas** (used with `area("name")`):

| command | |
|---|---|
| `/queue area list` | lists them |
| `/queue area pos1` / `pos2` [`<x> <y> <z>`] | sets a corner of the selection (where you stand, or the given position) |
| `/queue area set <x1> <y1> <z1> <x2> <y2> <z2>` | makes the selection this box |
| `/queue area clear` | clears the selection |
| `/queue area save <name>` | saves the selection under a name |
| `/queue area load <name>` | selects a saved area |
| `/queue area move <name\|all> <dx> <dy> <dz>` | moves saved areas |
| `/queue area delete <name>` | deletes one |

## /schem

| command | |
|---|---|
| `/schem` | short help |
| `/schem list` | the Litematica placements with their numbers |
| `/schem build [n\|all] [add\|fix\|exact] [skip\|strict]` | builds, like `schem_build()` ([modes](GUIDES.md#building-litematica-schematics)) |
| `/schem materials [n\|all] [mode]` | items still needed against the inventory |
| `/schem check [n\|all] [mode]` | what differs between schematic and world |
| `/schem move <n\|all> <dx> <dy> <dz>` | moves placements |
| `/schem stop` | stops the build |

## /survival

`/survival` alone (or `/survival status`) shows what is switched on and where the bed and chests are.

**Switches** (`/survival <name> on|off`):

| name | option | |
|---|---|---|
| `eat` | `eat` | eats when the food bar is low |
| `defence` | `defence` | fights hostile mobs nearby |
| `sleep` | `sleep` | goes to bed at night |
| `restock` | `restock` | fetches the want-list from the supply chest |
| `respawn` | `respawn` | respawns after dying |
| `resume` | `resume` | carries on with the task after dying |
| `reconnect` | `reconnect` | reconnects after the server dropped the connection, then carries on |
| `golden` | `golden` | golden apple when health is low and the food bar is already full |
| `weather` | `weather` | thunderstorms count as night (go to bed) |
| `bedplan` | `bedPlan` | leaves for the bed early enough to be there before dusk |
| `walkeat` | `walkEat` | eats while walking instead of stopping |
| `topup` | `topUp` | tops up stock that is only somewhat low when passing the base |
| `always` | `always` | the helpers also act when no task is running |
| `background` | `background` | keeps running when the game window loses focus |
| `debug` | | debug messages |

`/survival sleep now` and `/survival restock now` do it at once.

**Numbers:**

| command | |
|---|---|
| `/survival eatbelow <1-19>` | eats when the food bar is at this or lower (default 14) |
| `/survival bedlead <seconds>` | extra slack for the bed trip on top of the walking time (default 20) |
| `/survival baseradius <blocks>` | how far around the chests and bed counts as "the base" (default 24) |
| `/survival maxdeaths <n>` | deaths within 10 minutes that are still resumed; one more stops the task. `0` = never stop (keep-inventory servers). Default 2 |

**Places** (`set` uses the block you look at, or a position):

| command | |
|---|---|
| `/survival bed set [<x> <y> <z>]` / `bed clear` | the bed for `sleep()` |
| `/survival supply set [...]` / `supply clear` | the supply chest for `restock()` |
| `/survival supply add` / `remove` / `list` | more supply chests (the one you look at) |
| `/survival return set [...]` / `return clear` | where worn-out tools are dropped off (otherwise the supply chest) |

**Want-list, work items, chest index, report:**

| command | |
|---|---|
| `/survival want list` | the want-list |
| `/survival want <item> <count>` | keep this many with you (`food` = any food), like `want()` |
| `/survival want remove <item>` | |
| `/survival workitem <rule>` | a tool with enchantments to keep, e.g. `diamond_pickaxe 2 efficiency 5` |
| `/survival workitem list` / `clear` / `remove <rule>` | |
| `/survival chests` | the chest index of this world |
| `/survival chests forget` | empties it |
| `/survival find <item>` | chests that held the item when last seen |
| `/survival report` | the session report (deaths, meals, trips ...) |
| `/survival report reset` | saves it to `config/survivalkit-sessions.log` and resets the counters |

## /buildtools

`/buildtools` alone (or `status`) shows the switches. `/buildtools stop` stops whatever it is doing.

| command | |
|---|---|
| `/buildtools terminator <block>` | fills the selection's water (like `fill_water("block")`) |
| `/buildtools terminator stop` | stops it |
| `/buildtools drain now` | drains flowing water now (like `drain()`) |
| `/buildtools drain on\|off` | drains by itself when building stalls on flowing water (`drainAuto`) |
| `/buildtools dam on\|off` | also plugs the water just outside the selection (`dam`) |
| `/buildtools escape on\|off` | swims to land when stuck going in circles in water (`escape`) |
| `/buildtools scaffold collect` | takes down the placed scaffolding (like `collect_scaffold()`) |
| `/buildtools scaffold list` / `forget` | the remembered scaffolding blocks |
| `/buildtools scaffold record on\|off` | remembers scaffolding blocks (`scaffoldRecord`) |
| `/buildtools scaffold auto on\|off` | collects them by itself once enough stand nearby (`scaffoldAuto`) |
| `/buildtools scaffold batch <3-500>` | how many count as "enough" (`scaffoldBatch`, default 25) |

**Safe areas** (`/buildtools protect ...`):

| command | |
|---|---|
| `protect on\|off` | safe areas on or off altogether (`protect`) |
| `protect list` | lists them |
| `protect add [<name>]` | the selection as a safe area |
| `protect pos1` / `pos2`, then `protect save [<name>]` | corners by hand (where you stand) |
| `protect box <x1> <y1> <z1> <x2> <y2> <z2> [<name>]` | by coordinates |
| `protect here <radius> [<name>]` | a box around you |
| `protect sites [<margin>]` | protects the saved bed and chests (survival and loadchest), each with a margin around it (default 3) |
| `protect remove <name>` / `protect clear` | |
| `protect mining <name> allow\|deny` | allow breaking blocks inside that area |
| `protect bridging <name> allow\|deny` | allow placing blocks inside that area |
| `protect reach <name> on\|off` | the rules also apply when you are within reach of the area, not only inside |
| `protect reachradius <1-12>` | what "within reach" means (`reachBlocks`, default 6) |
| `protect avoid on\|off` | plan around chests, hoppers, beds, furnaces and redstone everywhere (`avoidBlocks`) |
| `protect trim on\|off` | cut selections around safe areas (`trim`) |

## /loadchest

| command | |
|---|---|
| `/loadchest [<filter>]` | walks to the chest and empties the inventory (only items matching the filter) |
| `/loadchest nearest [<filter>]` | uses the nearest chest |
| `/loadchest at <x> <y> <z> [<filter>]` | uses that chest |
| `/loadchest set [<x> <y> <z>]` / `clear` | saves the chest you look at (or a position) / forgets it |
| `/loadchest baseline` | remembers the current inventory: only what was added later is "mined" |
| `/loadchest mined [<filter>]` | empties only what was gained since the baseline |
| `/loadchest keep list` / `keep <item> <count>` / `keep remove <item>` | what stays in the inventory (like `keep()`) |
| `/loadchest hotbar on\|off` | also empties the hotbar (`includeHotbar`) |
| `/loadchest auto on\|off` | empties by itself when the inventory is full while mining (`auto`) |
| `/loadchest nearbase on\|off` / `nearbase <radius>` | also empties when passing the base (`nearBase`, `baseRadius`) |
| `/loadchest pickup on\|mined\|all\|off` | picks up drops by itself: only from blocks it broke (`mined`), or every item (`all`) |
| `/loadchest batch <n>` / `wait <seconds>` / `radius <blocks>` | when and how far to pick up |
| `/loadchest status` / `stop` / `debug on\|off` | |
