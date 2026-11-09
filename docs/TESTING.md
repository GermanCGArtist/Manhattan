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
