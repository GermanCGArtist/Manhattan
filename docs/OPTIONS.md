# Options

There are three kinds of settings:

* **Helper options**: `option("group.name", value)` in a script (or the matching chat command). These are saved in
  the helpers' config files and stay set after the script ends. `options()` prints all of them, `options("survival")`
  one group.
* **Path engine settings**: `set("name", value)` / `setting("name")` (also `#set name value`). These apply until
  the game is restarted.
* **Limits**: `timeout(seconds)` and `stall(minutes)`. See [FUNCTIONS.md](FUNCTIONS.md#settings-and-limits).

Config files are in the game's `config/` folder.

## survival

`config/survivalkit.json`. These helpers only act when the script calls them (`eat()`, `sleep()` ...) or allows
them with `enable(...)`. Respawn, resume and reconnect always work.

| option | default | |
|---|---|---|
| `survival.eat` | true | eating is allowed |
| `survival.eatBelow` | 14 | eat when the food bar (0-20) is at or below this |
| `survival.walkEat` | true | eat while walking instead of stopping |
| `survival.golden` | true | golden apple when health is low and the food bar is already full |
| `survival.defence` | true | fighting is allowed |
| `survival.sleep` | true | sleeping is allowed |
| `survival.bed` | — | the bed, `pos(...)` |
| `survival.bedPlan` | true | leave for the bed early enough to be there before dusk (from the distance) |
| `survival.bedLead` | 20 | extra seconds on top of the walking time |
| `survival.weather` | true | thunderstorms count as night |
| `survival.ticksPerBlock` | 5.5 | learned walking pace to the bed (changes by itself) |
| `survival.restock` | true | restocking is allowed |
| `survival.supply` | — | the supply chest, `pos(...)`; more with `/survival supply add` |
| `survival.returnChest` | — | where worn-out tools go (otherwise the supply chest) |
| `survival.topUp` | true | at the base, also top up stock that is only somewhat low |
| `survival.baseRadius` | 24 | blocks around the chests and the bed that count as the base |
| `survival.always` | false | the helpers also act when no task is running |
| `survival.background` | true | keep running when the game window loses focus |
| `survival.respawn` | true | respawn after dying |
| `survival.resume` | true | after dying with a task running: get healthy, then carry on |
| `survival.maxDeaths` | 2 | deaths within 10 minutes that are still resumed; one more stops the task. `0` = never stop |
| `survival.reconnect` | true | reconnect after the server dropped the connection, then carry on |
| `survival.logoffOnMissingBed` | false | disconnect when the bed at `survival.bed` is gone |

The want-list and work items are set with `want()` and `work_item()` (or `/survival want`, `/survival workitem`).

## buildtools

`config/buildtools.json`.

| option | default | |
|---|---|---|
| `buildtools.dive` | true | `fill_water` dives for cells too deep to reach from the shore |
| `buildtools.dam` | true | also plug the water just outside the selection, so it cannot flow back in |
| `buildtools.drainAuto` | true | when building stalls on flowing water, drain it and carry on |
| `buildtools.escape` | true | swim to dry land when stuck going in circles in water |
| `buildtools.protect` | true | safe areas are on |
| `buildtools.avoidBlocks` | true | while safe areas are on: never break chests, hoppers, beds, furnaces, redstone parts |
| `buildtools.trim` | true | cut selections around safe areas |
| `buildtools.reachBlocks` | 6 | how close to a safe area still counts as "able to reach into it" |
| `buildtools.scaffoldRecord` | true | remember placed scaffolding blocks, so they can be picked up later |
| `buildtools.scaffoldAuto` | true | pick them up by itself once `scaffoldBatch` stand nearby |
| `buildtools.scaffoldBatch` | 25 | |

## loadchest

`config/loadchest.json`. Used by `unload()`, `pickup()` and the `autoempty` / `pickup` helpers.

| option | default | |
|---|---|---|
| `loadchest.auto` | false | empty the inventory by itself when it is full while mining |
| `loadchest.nearBase` | true | also empty when passing the base, not only when full |
| `loadchest.baseRadius` | 24 | |
| `loadchest.includeHotbar` | false | also empty the hotbar |
| `loadchest.pickup` | false | pick up drops by itself |
| `loadchest.pickupAll` | false | every item nearby, not only drops of blocks it broke |
| `loadchest.pickupBatch` | 40 | start collecting once this many drops lie around |
| `loadchest.pickupWait` | 90 | ... or once a drop has lain this many seconds (items despawn after 300) |
| `loadchest.pickupRadius` | 32 | how far from you drops are looked for |

## queue

`config/manhattan.json`.

| option | default | |
|---|---|---|
| `queue.stallMinutes` | 30 | a task without progress for this long is skipped (`stall()`, `/queue stall`) |
| `queue.logoffOnDone` | false | disconnect cleanly when the script finishes normally |
| `queue.logoffOnError` | false | disconnect cleanly on a fatal script error (and do not reconnect) |
| `ui.layout` | `"edit"` | the layout J opens: `"edit"` or `"monitor"` |

## Chunked runs

See [Chunked runs and resuming](GUIDES.md#chunked-runs-and-resuming).

| option | default | |
|---|---|---|
| `chunk.enabled` | true | split big selections into chunks |
| `chunk.size` | 32 | chunk width in blocks |
| `chunk.order` | `"serpentine"` | `"serpentine"` (row by row, back and forth) or `"nearest"` |
| `chunk.sizeY` | 16 | `clear_area`: band height in blocks, dug top band first (0 = no bands, 4 to 320) |
| `chunk.gap` | 8 | `clear_area`: boxes further apart than this many blocks (any direction) are separate regions (-1 = off) |

## Path engine settings: set()

| setting | default | |
|---|---|---|
| `allowBreak` | true | the engine may break blocks to get somewhere |
| `allowPlace` | true | the engine may place blocks (bridges, pillars) |
| `allowParkourPlace` | false | it may place a block to land a jump |
| `itemSaver` | false | stop using a tool that is about to break |
| `itemSaverThreshold` | 0 | durability left at which the item saver stops using a tool |
| `stuckTicks` | 20 | ticks without moving before a step counts as stuck |
| `edgeTimeoutTicks` | 80 | longest time one step may take |
| `blacklistTicks` | 100 | how long a failed step is avoided |
| `maxReplans` | 25 | how often a walk may be planned again before it fails |

Scaffolding (bridges, pillars) uses dirt, cobblestone, stone, cobbled deepslate, netherrack or sandstone from the
inventory.

`option("engine", ...)` and the JVM property `-Dmanhattan.engine=` exist for benchmarks. Both names (`midtown`,
`baritone`) currently run Midtown.
