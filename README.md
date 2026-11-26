# Manhattan

A script language and project automation suite for Minecraft 26.3 (Fabric client mod, mod id `manhattan`), built on [Liskov](https://github.com/GermanCGArtist/Liskov).

Everything the bot does is one script: variables, `if`/`while`/`for`, functions, `try`/`catch`, resumable `job`
phases, and `on <condition> ... end` blocks that interrupt the main script to fight, eat, sleep or restock. Movement
uses Manhattan's own path engine, **Midtown**.

Built-in jobs: filling water bottom-up (`fill_water`), digging out areas (`clear_area`), clearing trees, flooding and
draining, Litematica schematics (`schem_build`), chest trips backed by a per-world chest index, safe areas, and the
survival helpers (`defend`, `eat`, `sleep`, `restock`). Big areas are worked chunk by chunk, and a stopped script
carries on where it left off.

```
let dirt_chest = pos(1794, 74, -840)
option("survival.bed", pos(1791, 74, -839))

on mobs > 0 do defend() end
on bed_time do sleep() end

job "fill"
  area("pond")
  fill_water("dirt", dirt_chest)
end

job "dig"
  area("pond")
  clear_area()
end
```

Press **J** in game to open the workspace (script editor, live monitor, map), **K** for Capture.

## Documentation

| | |
|---|---|
| [Getting started](docs/GETTING_STARTED.md) | install, first script, a first real job |
| [The language](docs/SCRIPTING.md) | syntax, `on` blocks, jobs, legacy `#` lines |
| [Functions](docs/FUNCTIONS.md) | every built-in function and getter |
| [Guides](docs/GUIDES.md) | AFK scripts, chest index, water, digging, trees, schematics, chunked runs, safe areas |
| [Fleets](docs/FLEET.md) | several bots sharing one job through manhattan-fleet-server |
| [Workspace](docs/WORKSPACE.md) | editor, Monitor, presets, Capture |
| [Chat commands](docs/COMMANDS.md) | `/queue`, `/schem`, `/survival`, `/buildtools`, `/loadchest` |
| [Options](docs/OPTIONS.md) | `option()`, `set()`, config files |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | symptoms, causes, fixes |
| [Changelog](docs/CHANGELOG.md) | changes per version |

For developers: [how the code is built](docs/DESIGN.md), [testing](docs/TESTING.md),
[Heavymatica API](docs/HEAVYMATICA_API.md), [Midtown roadmap](docs/MIDTOWN_ROADMAP.md),
[Deming benchmarks](docs/DEMING_BENCHMARKS.md).

Example scripts are in [scripts/](scripts/), for example [water-job.txt](scripts/water-job.txt) (fill an area's
water bottom-up, then mine two areas).

## Requirements

- **Minecraft:** `26.3`
- **Fabric Loader:** `0.20.1+` and **Fabric API**
- **Java:** `25`
- **[Liskov](https://github.com/GermanCGArtist/Liskov):** `0.2.3+mc26.3` (shared ImGui UI host, Drives filesystem, Command Palette, and server sync)
- **Optional:** Litematica (for schematics) and [Manhattan Fleet Server](https://github.com/GermanCGArtist/manhattan-fleet-server) (for multi-bot fleets). Baritone is not needed.

## Related Suite Mods

- **[Liskov](https://github.com/GermanCGArtist/Liskov)** — Shared UI host (`Ctrl+P` Command Palette, `F8` File Explorer), Drives filesystem, `.liskov-vcs/` version control, and `liskov:sync`
- **[Manhattan Fleet Server](https://github.com/GermanCGArtist/manhattan-fleet-server)** — Server-side multi-bot fleet coordinator
- **[Vitruvius](https://github.com/GermanCGArtist/Vitruvius)** — Blender-style 3D schematic scene editor (`F6`)
- **Neurath** — Transit network diagram, JourneyMap vector overlay, and Spectate mode (`N` / `V`)

## Building

Needs JDK 25. `build.bat` (Windows) or `./build.sh` (Linux/macOS) builds `build/libs/manhattan-<version>.jar`
and runs the offline tests (language, script parsing, Midtown, chunking). It uses a portable JDK in `tools/jdk-25*` if present, otherwise `JAVA_HOME`.

```
build.bat
install.bat
```

`install.bat` copies the jar into the "26.1.1 AFK" Modrinth profile and moves older copies, Baritone and the old
addon jars to `mods-disabled`. It refuses while that profile is running.

In-game tests start the game on the "Manhatten Testing" profile, run scripts, check the world and close it:
`tests\ingame\run.bat`. See [docs/TESTING.md](docs/TESTING.md).

## License

MIT, see [LICENSE](LICENSE).
