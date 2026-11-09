# Manhattan

A script language for Baritone (Minecraft 26.1.1, Fabric client mod, mod id `baritonequeue`).

Everything the bot does is written in one script: variables, `if`/`while`/`for`, functions, `try`/`catch`, and
`on <condition> ... end` blocks that interrupt the main script (fight, eat, sleep, restock). Lines starting with `#`
are Baritone commands, lines starting with `/` are chat commands.

The built-in helpers that used to be separate addons (SurvivalKit, BuildTools, LoadChest) are part of this mod and
only act when the script tells them to: `defend()`, `eat()`, `sleep()`, `restock()`, `fill_water()`, `clear_area()`,
`dump()`, `protect()`, chests, areas, and more.

```
let dirt_chest = pos(1794, 74, -840)
on mobs > 0
  defend()
end
on bed_time
  sleep()
end
area("1")
fill_water("dirt", dirt_chest)
```

## Use
- Press **J** in game to open the editor. **Tab** completes like `/setblock`; **Pick block** fills in the position
  of the block you look at (chests, beds).
- Language guide: [docs/SCRIPTING.md](docs/SCRIPTING.md)
- Example: [scripts/water-job.txt](scripts/water-job.txt) (fill an area's water bottom-up, then mine two areas)
- Change notes: [docs/NOTES.md](docs/NOTES.md)

## Build
Needs JDK 25. `build.bat` uses a portable JDK in `tools\jdk-25*` if present, otherwise `JAVA_HOME`.
The Baritone API jar goes in `libs\` (`baritone-api-fabric-1.18.0.jar`, not in git).

```
build.bat
install.bat
```

`install.bat` copies `build\libs\baritonequeue-<version>.jar` into the "26.1.1 AFK" Modrinth profile and moves older
copies (and the old addon jars) to `mods-disabled`. It refuses while that profile is running.

Requires Fabric Loader, Fabric API and Baritone (API build 1.18.0) for 26.1.1.
