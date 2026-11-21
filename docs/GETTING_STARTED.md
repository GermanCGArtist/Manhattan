# Getting started

## What you need

- Minecraft **26.1.1** with **Fabric Loader** 0.19.3 or newer and **Fabric API**
- Java 25
- optional: **Litematica**, to build schematics

Baritone is **not** needed any more (since 2.12). Manhattan moves with its own engine, Midtown. Remove old Baritone
jars and the old SurvivalKit, BuildTools and LoadChest addon jars: everything they did is built in.

## Install

Put `manhattan-<version>.jar` into the `mods` folder of your Fabric profile. To build it yourself, see
[Building](../README.md#building).

## Your first script

1. Join a world and press **J**. The workspace opens with the script editor.
2. Type:

   ```
   print("hello from {dimension}")
   let start = pos()
   goto(start.x + 10, start.z)
   goto(start)
   print("back home")
   ```

3. Press **Run**. The bot walks 10 blocks east and back. **Stop** ends it at any time.

## A real job

Let the bot dig out an area while it keeps itself alive.

1. Stand near the area and press **K** (Capture). Choose the role **mine area**, press **Aim in world**, look at one
   corner and press J, then look at the opposite corner and press J. Do the same with the role **dump** for a chest
   and **bed** for your bed. Press **Copy**.
2. Press **J**, paste the sheet into the editor and add the work below it:

   ```
   // pasted from Capture:
   let dump_chest = pos(1787, 74, -837)
   let bed = pos(1791, 74, -839)
   area_box("mine_1", pos(1700, 60, -900), pos(1720, 74, -880))

   option("survival.bed", bed)

   on mobs > 0 do defend() end
   on food < 14 do eat() end
   on bed_time do sleep() end
   on full do dump(dump_chest) end

   area("mine_1")
   clear_area()
   dump(dump_chest)
   ```

3. Press **Test** first. It tells you if the chest or bed is not where the script says. Then press **Run**.
4. Press **J** and switch to **Monitor** to watch the progress, the map and the inventory.

## Where to go next

- [SCRIPTING.md](SCRIPTING.md): the language (variables, loops, `on` blocks, resumable `job`s)
- [FUNCTIONS.md](FUNCTIONS.md): every built-in function
- [GUIDES.md](GUIDES.md): water filling, digging, trees, schematics, chest lists, long AFK scripts
- [WORKSPACE.md](WORKSPACE.md): the editor, Monitor, presets and Capture
- [COMMANDS.md](COMMANDS.md) and [OPTIONS.md](OPTIONS.md): chat commands and settings
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md): when something goes wrong
- `scripts/` in this repository: real scripts (a water job, canals, a schematic build, trenches)
