# Troubleshooting

## Where to look

- **Alerts** (in both workspace layouts) shows the latest error, a stalled step, worn tools, low health or food, and
  a full inventory.
- The **Log** drawer and tile show what the script and the helpers said.
- The game log `logs/latest.log` in the profile folder has everything, including `log(...)` lines from the script.
  Search it for `[Queue]`, `ScriptError`, `Exception`, or the process names (`TerminatorProcess`, `ClearProcess`,
  `SchemProcess`, `ChestProcess`).
- `/survival report` shows deaths, meals and trips of the session.
- `/survival chests` shows what the chest index believes is in which chest.

## The script

| symptom | why | what to do |
|---|---|---|
| A job is skipped ("done from an earlier run") although you changed it | finished jobs and chunks are remembered per world and script tab | `/queue reset <job>` with the script stopped and its tab open in the editor |
| Run says "Resuming: …" on a script you wanted to start fresh | same: the ledger is never cleared by itself | `/queue reset` |
| Chunks stay "deferred" or "failed" after you fixed the cause | they were given up on in the earlier run | `/queue retry <job>`, then Run |
| `on` blocks stop working after the last line | the `on` blocks end with the script | end the script with `wait until false` |
| An `on` block or function does nothing when its job is skipped | it was written inside the job | move `on` and `func` above the first `job` |
| The script never gets past a `#` line | `#mine` without a number, `#follow`, `#farm`, `#explore` never finish | use `mine()`, `clear_area()` or `#mine 64 iron_ore`; **Skip** moves on |
| "made no progress for 30 min, so I stopped it and go on" | the stall limit | `stall(60)` or `/queue stall <minutes\|off>` if the task is just slow |
| The bot stops after dying | more than `survival.maxDeaths` deaths in 10 minutes | raise it, or `0` on keep-inventory servers |
| The game is left on an error and the bot stands around | expected without auto-logoff | `option("queue.logoffOnError", true)` |

## Water and digging

| symptom | why | what to do |
|---|---|---|
| `fill_water` returns > 0 ("cells left that it cannot get at") | the engine bridged over the water and roofed it, deep pockets without open water above, or water just outside reach | `set("allowPlace", false)` and `set("allowBreak", false)` while filling; keep `buildtools.dive` on |
| Water floods the trench while `clear_area()` digs | water just outside the selection flows in when the edge is broken | fill one block wider than you dig, or dig one block narrower than the water area ([guide](GUIDES.md#filling-water-fill_water)) |
| `clear_area()` returns > 0 | it skips blocks next to water or lava and in safe areas on purpose | dry leftovers: run it again or `#sel ca`; never next to water |
| `flood()`: "No water source to refill from within 16 blocks" | not enough water buckets and no source nearby | carry two water buckets, or start next to water |
| Scaffolding towers stay behind | the scaffolding memory was off or forgotten | `collect_scaffold()` at the end of the job |

## Chests and schematics

| symptom | why | what to do |
|---|---|---|
| `take(chests, ...)` returns 0 without walking | no chest in the list is known to hold the item and none is stale | `index_refresh(0)` once (opens them all), or give one chest instead of the list |
| The bot walks to a chest that was emptied by hand | the index is older than what happened | `index_refresh(0)` at the top of the script, or `/survival chests forget` |
| `schem_left()` > 0, "no material with you" | missing items in the chests, or too little inventory space | fetch with `schem_needed()` and `take_upto(chests, item, n, 2)` before building |
| Bottom row missing: "nothing to place against" / "water in the cell" | air or water under the schematic's lowest blocks | `fill_water` or `drain()` below the schematic first |
| `schem_build()` returned 0 but blocks are missing far away | it only sees loaded sections | use `schem_left()` for the total ([guide](GUIDES.md#building-litematica-schematics)) |

## Survival

| symptom | why | what to do |
|---|---|---|
| `sleep()` fails: "No bed at …" | the bed at `survival.bed` is gone or blocked | set it again (Pick block on the bed); `survival.logoffOnMissingBed` leaves the server instead of waiting |
| The helpers do nothing by themselves | since 2.0.1 they only act when the script calls them | use `on` blocks or `enable(...)` |
| `restock()` keeps going back | the supply chest lacks something on the want-list | check `/survival want list` against the chest |

## Installation

- **Old addon jars** (SurvivalKit, BuildTools, LoadChest) or **Baritone** next to Manhattan: remove them. Manhattan
  has had all of this built in since 2.0, and has had its own engine since 2.12.
- The workspace (J) does not open: check the log for ImGui native errors. Windows, Linux and macOS are supported.
