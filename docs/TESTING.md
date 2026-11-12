# Testing Manhattan

Two levels:

| | what | needs Minecraft |
|---|---|---|
| `build.bat` | `langTest` (the language through the real parser and interpreter) and `parseScripts` (every `scripts/*.txt` parses) | no |
| `tests\ingame\run.bat` | builds, installs into the **"Manhatten Testing"** Modrinth profile, starts the game, runs scripts in a flat test world, checks the results, closes the game | yes, started by the test |

## In-game tests: one command

```
tests\ingame\run.bat
```

It prints one line per test and a summary, and exits with 0 only if every test passed:

```
PASS  smoke         0.6s  script printed, editor opened
PASS  fill_water   14.8s  18 cells filled bottom-up, 18 dirt used
PASS  eat          20.5s  ate food=20, 6 bread left
PASS  defend        5.1s  zombie killed (defended)
PASS  clear_resume 156.8s  strip cleared, resumed once eating was done (...)
5 passed, 0 failed   (game log: ...\profiles\Manhatten Testing\logs\latest.log)
```

Options (passed through to `tests/ingame/run_tests.py`):

```
tests\ingame\run.bat fill_water eat     only these tests
tests\ingame\run.bat --keep-open        leave the game running afterwards
tests\ingame\run.bat --attach           use the testing instance that is already running (no build/install/launch)
```

Needs: Python 3.9+ (standard library only), the Modrinth App with Minecraft 26.1.1 + Fabric Loader 0.20.1 already
downloaded once (its `meta` folder; any 26.1.1 Fabric 0.20.1 profile that has been started once is enough), and the
`26.1.1 AFK` profile's `mods` folder for the Baritone jars.

Nothing to do by hand: there is **no Microsoft login**. The test starts the game itself in offline mode (user
`ManhattanTest`, single-player only), not through the Modrinth App.

### The tests (`tests/ingame/run_tests.py`, scripts in `tests/ingame/scenarios/`)

| test | what it checks |
|---|---|
| `smoke` | a script with variables, a list and `print` runs and prints; the editor (`QueueScreen`) opens |
| `imgui_spike` | F9 opens the Dear ImGui runner window (`ImGuiManager`), shows `RunnerView` state while a script runs, blocks J/K keys from leaking while focused, handles window resize (960×540), and stops the script via the ImGui Stop button |
| `viewport` | in `ui.mode = docked`, verifies the top-right quarter Game Viewport geometry, keyboard focus gating (clicking outside blocks E/J, clicking inside focuses game), walking, Baritone mining, Pick block (middle-click and `QueueScreen` button), opening a chest and clicking slots via remapped viewport coordinates, window resize, GUI scale changes, and F8 fullscreen toggle |
| `docked_shell` | in `ui.mode = docked`, verifies the 2x2 `DockSpace` (`2x2 Docked`), the Top Bar **Reset layout** button and `config/manhattan-imgui.ini` persistence, `ProgressModel` segmented step bar and telemetry (controller, elapsed, estimate, health, hunger, position, active interrupts) while a multi-step script runs, and the Top Bar **Stop** button |
| `panels` | in `ui.mode = docked`, verifies all Stage 3 panels: `Script Editor` (file tabs, `Syntax`, `Suggest` Tab completion, `ScriptCheck` Test button, Pick block, Run button and running-line highlight), `Presets` (insert into editor), `Chests` (`ChestIndexView` search, sort, category/low-stock filters, Show in world, Copy `pos(...)`, Go there with Baritone), `Areas` & `Capture` (Fetch looked-at, Aim in world), and `Log` (`LogBuffer` All/Errors/Baritone filters, Copy, Clear) |
| `fill_water` | a 3×3 pool, 2 deep, next to the player; `fill_water("dirt")` returns 0, every cell is dry afterwards, dirt came out of the inventory, and **no top cell was filled while the cell under it was still wet** (checked twice a second while it runs) |
| `eat` | `on food < 14 … eat()`: a hunger effect drains the food bar, the handler fires and bread is eaten |
| `defend` | `on mobs > 0 … defend()`: at night a zombie is summoned 4 blocks away; the handler fires and kills it |
| `clear_resume` | a 7×21×2 strip of dirt; `clear_area()` is interrupted by `on food < 14 … eat()` and must carry on ("Carrying on clearing"), not start from the top again; afterwards the strip is empty and it returns 0 |
| `dump` | junk in the inventory (a worse second diamond shovel, an iron shovel, a bow, a leather chestplate, rotten flesh, cobblestone in the hotbar) plus bread and a golden apple; with `want("diamond_shovel", 1)`, `dump()` must leave exactly one diamond shovel, the bread and the golden apple |
| `hotbar_tools` | the hotbar full of cobblestone and bread in slot 9, a diamond pickaxe and shovel in the main inventory; with both on the want-list they must end up in the hotbar, the bread stays |
| `air` | shut in a water-filled stone box with a one-block lid; the air guard must break out before drowning (fails on a "drowned" chat line) |
| `clear_wet` | a 7×5 dirt layer with water beside one end; the three dirt blocks touching the water stay, the report says "next to water" and `clear_area()` returns 0 |

Before each world test the area around (100, -60, 100) is reset: dirt/grass ground, air above, all entities
except the player killed, survival mode, empty inventory, noon. The world rules are set once: no daylight cycle,
no weather, no mob spawning, difficulty easy.

Adding a test: write a scenario script in `tests/ingame/scenarios/` that prints `TEST …` lines, add a
`test_<name>(t)` function to `run_tests.py` (set up with `t.cmd("/…")`, start with `t.run("x.txt")`, wait with
`t.wait_chat("TEST …", seconds)`, check with `t.block((x, y, z))`, `t.count("item")`, `t.state()`, raise `Fail`)
and put it in `TESTS`.

## Parallel runs, schematic tests, no focus stealing (2.5.0)

`tests\ingame
un.bat` starts several games at once when there is RAM for it (about 3 GB each, at most 3; `-j N` /
`--workers N` sets it, `-j 1` is one game in the "Manhatten Testing" profile as before). Each worker is its own game:
game dir `tmp\ingame\wN` (own `config/`, `saves/`, `logs/`, `options.txt`, Litematica placements), bridge port
25600+N, log `tmp\ingame\wN.log`. Workers take tests from a queue (slow ones first), each test has a hard limit
(`--test-timeout`, default 420 s): a stuck test fails and that worker's own game is closed through its own bridge and
restarted. Only games the harness started (matched by their game dir) are ever closed. One combined summary at the end.
Render distance 4, 30 fps, no vsync and an 854x480 window keep the load low.

Test games must not take your mouse or focus: the window is shown without activation, the test bridge jar (which
exists only in test profiles) creates the window with `GLFW_FOCUSED` / `GLFW_FOCUS_ON_SHOW` off through a mixin
(only with `-Dmanhattan.tests=true`) and lets go of the mouse whenever the game grabs it. `tests\ingameocuswatch.py
<seconds> <file>` records the foreground window while a run is going.

Litematica and malilib are copied (read only) from the Bestes_projekt profile into the test profiles when they are
there; the Litematica printer is never installed.

| test | what it checks |
|---|---|
| `schem_list` | `/schem list` finds the placement, `/schem materials` counts stone 25, cobblestone 23, stairs 4, slabs 4, redstone 1 |
| `schem_add` | a 5x3x5 house (stairs facing four ways, two upside down, slabs, a log, a wall torch, redstone dust) is built; a wrong block already on a floor spot and a block inside schematic air are left alone |
| `schem_fix` | the wrong floor block is replaced; schematic air is left alone |
| `schem_fix_wrong` | wrong blocks on the redstone, stair, torch, log and slab spots, and a stair with the wrong facing and half, are all replaced; every block is compared with facing, half, type and axis |
| `schem_exact` | built into a hill of dirt: schematic air is dug out top-down, wrong dirt is replaced, everything matches |
| `canal_layers` | 8 adjacent boxes (4 and 3 deep) of trench_a dug into a dirt plateau by `clear_area(2)`: everything dug, top layers first, nothing around or below touched |
| `canal_scaffold_schem` | a schematic floating above the ground built on dirt scaffolding; `collect_scaffold()` takes the scaffolding down and leaves the schematic whole |
| `canal_water_pond`, `canal_water_river` | `fill_water("dirt", chest)` dries a pond (fetching dirt from the chest) and a flowing river |
| `canal_flood_*` | `flood()` on a 2-deep trench open at both ends: with 2 water buckets, with 16 stacked buckets, 11 wide (temporary blocks where nothing can be clicked), no water outside, ends sealed; with one bucket or none it stops with a clear message |
| `chest_index` | three chests, `take_upto` / `put` with the list of them: a cold index opens the nearest chests until the item is found (2), then only the chest that holds the item (1), an item that is nowhere opens none while the entries are fresh, `index_refresh(0)` opens all three again, `put` goes to the chest that holds the item and the index counts what moved; after leaving and rejoining the world the index is read from its file (nothing nowhere opened, the right chest only for sand) |
| `canal_schem_two`, `canal_schem_needed`, `canal_tools_schem` | two placements with one item missing (skipped and reported), `schem_needed` and `take_upto` from chest/barrel/shulker box, the digger takes pickaxe and shovel |
| `canal_survival_dump`, `canal_survival_restock` | a full inventory triggers `dump(storage)` and the task goes on; `restock()` fetches buckets and tools |
| `trees_small` | an oak and a birch, no chest: `clear_trees()` leaves no log and no dirt in the area, reports the logs, all 64 dirt come back |
| `trees_forest` | oak, birch, fancy oak, 2x2 mega spruce, dark oak, jungle, mangrove, two huge mushrooms and a bee nest; `clear_trees(chest)`: nothing of them left, no scaffolding, the logs in the chest (needs `--test-timeout 1500`: about 11 min) |
| `canal_schem_unsupported` | a wall 3x3x24 and two bricks in a hole in a stone box (unreachable): the wall must be built completely (the unreachable bottom-layer cells are given up after 60 s so the layers above get built), the two are retried once and reported by `schem_left()` as "2 not reached" |
| `canal_smoke` | the whole `scripts/canal.txt` (from "survival" on) on a small copy of the canal: dig, plug the water, dig the water trench, build two placements from a shulker box, fill with water |

## How it works

### The testing profile
`C:\Users\PC-Julian\AppData\Roaming\ModrinthApp\profiles\Manhatten Testing` (spelled that way). `mc.install()`
keeps exactly this in its `mods` folder, copying only when a file changed:

| jar | from |
|---|---|
| `fabric-api-0.145.4+26.1.1.jar` | the Gradle cache (downloaded from maven.fabricmc.net for building this mod) |
| `baritone-api-fabric-1.18.0.jar`, `baritoneui-1.10.1.jar` | the `26.1.1 AFK` profile's `mods` folder (read only) |
| `baritonequeue-<version>.jar` | `build\libs` |
| `manhattan-testbridge-<version>.jar` | `build\libs` (test only, see below) |

Older `baritonequeue-*` / `manhattan-testbridge-*` / `fabric-api-*` jars there are deleted; the old `survivalkit`,
`buildtools` and `loadchest` jars are never installed (and removed if found). It also sets a few `options.txt`
values for unattended runs: no pause when the window loses focus, sound off, no accessibility first-start screen,
render distance 6.

The test world `saves/manhattan-test` is created by the bridge on the first run (flat, creative, cheats on, no
structures, seed 20261109) and reused afterwards. Delete the folder to start fresh.

### Launching (`tests/ingame/mc.py`)
`mc.launch()` builds the command line the way a launcher does, from the Modrinth App's own files:
`meta/versions/26.1.1-0.20.1/26.1.1-0.20.1.json` (libraries, main class `KnotClient`, asset index),
`meta/libraries`, `meta/assets`, Modrinth's Java 25 (`meta/java_versions/zulu25*`, else `tools\jdk-25*`), and its
own copy of the natives in the profile's `.natives` folder. Game output goes to the profile's
`logs/testbridge-stdout.txt`; the normal log is `logs/latest.log`.

**Other Minecraft instances are never touched.** Processes are matched by their `--gameDir`: only a java process
whose game dir is exactly the testing profile counts. A testing instance that is still open from before is closed
through the bridge (`quit`), and force-closed only if it does not exit within a minute — again only processes with
that game dir. `install.bat` (the AFK profile) is separate and unchanged.

### The bridge (`src/ingame`, jar `manhattan-testbridge-<version>.jar`)
A small client mod built by `build.bat` next to the main jar (Gradle source set `ingame`, task `testBridgeJar`).
It is only ever installed into the testing profile. It listens on **127.0.0.1:25599** (loopback only;
`-Dmanhattan.testbridge.port` / env `MANHATTAN_BRIDGE_PORT` change it), one command per line, one JSON answer per
line. It lives in the package `dev.baritonequeue`, so it can start scripts and read the runner directly. It also
closes the pause screen if one opens, so an unfocused window never pauses the test world.

| command | answer |
|---|---|
| `ping` | `{"ok":true,"pong":true}` |
| `state` | screen class, `inWorld`, position, health, food, game mode, dimension, time, number of chat lines, `script` (running) |
| `chat <text>` / `chat /<command>` | sends chat, or a command as the player (client commands like `/queue …` work too) |
| `chatlog [from]` | every chat line since index `from` (also the client-only lines mods print), and `next` |
| `block x y z` | block id at that position from the integrated server, plus `\|minecraft:water` if it holds a fluid |
| `count <item>` | how many the player carries |
| `entities <type> [radius]` | how many living entities of that type are near the player |
| `world <name> [seed]` | opens that single-player world, or creates it (flat, creative, cheats) |
| `leave` | back to the title screen (saves) |
| `runfile <path>` | starts the Manhattan script in that file (the editor's script is left alone) |
| `script` / `stopscript` | runner status / stop the running script |
| `editor` | opens the editor; `screen` names the open screen, `screen close` closes it |
| `quit` | leaves the world and closes the game |

By hand (or from Claude), with the game open (`run.bat --keep-open`):

```
python tests\ingame\bridge.py state
python tests\ingame\bridge.py chat /tp @s 0 -60 0
python tests\ingame\bridge.py chatlog 0
```

Why not an existing MCP mod: there are some for 26.1.x (e.g. MCP Fabric on Modrinth, InventivetalentDev's
minecraft-mcp), but they can only see the game from outside, through chat. The tests need the script runner's
state, client-only chat lines and exact block checks, and the bridge has no outside dependencies to trust or keep
in step with Minecraft versions.
