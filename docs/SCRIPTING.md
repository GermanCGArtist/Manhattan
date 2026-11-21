# The Manhattan language

Everything the bot does is one script. You write it in the workspace editor (key **J**, see
[WORKSPACE.md](WORKSPACE.md)) and start it with **Run**. This page covers the language. Every built-in function is
listed in [FUNCTIONS.md](FUNCTIONS.md), and [GUIDES.md](GUIDES.md) has worked examples of the big jobs.

```
// fill the pond with dirt from the chest, fight and sleep on the way
let dirt_chest = pos(1794, 74, -840)
option("survival.bed", pos(1791, 74, -839))

on mobs > 0 do defend() end
on bed_time do sleep() end

area("pond")
let left = fill_water("dirt", dirt_chest)
print("done, {left} cells left")
```

## Lines

```
// a comment              /* a longer comment */
#goto 100 64 -20          a legacy engine command (see below)
#goto {home}              {…} puts a value in:  #goto 100 64 -20
/time query daytime       a chat command (server or client): the script waits for it
```

A `#` or `/` line runs to the end of the line, and `{expression}` inside it is filled in. Everything else is the
language. Separate several statements on one line with `;`.

## Values

| kind | written | notes |
|---|---|---|
| number | `3`, `2.5`, `-7` | all numbers are decimals; `int()` cuts off the fraction |
| text | `"hello {name}"`, `'no {braces} here'` | `"…"` fills in `{expression}`; `'…'` never does. `\n` is a new line, `\{` a literal brace |
| bool | `true`, `false` | |
| null | `null` | "nothing", e.g. `looking` when you look at the sky |
| list | `[1, 2, 3]` | `xs[0]` is the first item, `xs[-1]` the last; `len(xs)` |
| map | `{dirt: 64, sand: 32}` | `m.dirt` or `m["dirt"]`; `keys(m)`, `values(m)` |
| position | `pos(10, 64, -3)` | `p.x`, `p.y`, `p.z`; `pos()` is where you stand; `p + pos(0, 1, 0)` moves it |
| function | `func(x) return x * 2 end` | can be stored in a variable and passed around |

Lists and maps are shared, not copied: after `let b = a`, changing `b` also changes `a`. Use `copy(a)` to get a
separate copy.

## Variables and operators

```
let home = pos(100, 64, -20)      // a new variable
let n = 3
n += 1                             // also -= *= /=
let names = ["2", "3"]
names[0] = "1"
let stock = {dirt: 64}
stock.sand = 32
```

* Arithmetic: `+ - * / %`. `+` also joins texts and lists, and adds positions.
* Comparison: `== != < <= > >=`, `in`, `not in` (these work on lists, map keys and texts).
* Logic: `and or not` (or `&& || !`).
* The editor reports a single `=` inside a condition as a mistake. Use `==`.
* A variable needs `let` the first time. Setting a variable inside a block without `let` changes the outer one.

## Control flow

```
if food < 8 and has("bread") then  // then / do are optional
  eat()
elif night                         // also: elseif
  sleep()
else
  print("fine")
end

while count("dirt") < 256 do … end
for name in names … end            // over a list, a map's keys, or a text's letters
for i, name in names … end         // with the index
for i in range(5) … end
repeat 3 times … end               // "times" is optional
loop … end                         // forever, until break, return or stop()
break   continue

wait 30                            // seconds
wait until day                     // any condition, checked every tick
stop("done")                       // ends the script
```

## Functions

```
func refill(n)                     // also: function refill(n)
  take_upto(dirt_chest, "dirt", n)
  return count("dirt")
end

let have = refill(512)
let double = func(x) return x * 2 end
```

Functions see the script's variables, and `let` inside a function makes a local one. Define functions at the top
level, not inside a `job` (see below).

## Errors: try and catch

When a function that waits fails (the chest has no dirt, there is no path to the bed), or the script calls
`error("...")`, the script stops and shows the line and the reason. `try` catches the error:

```
try
  take(dirt_chest, "dirt", 64)
catch err
  print("could not: {err}")
end
```

`catch` also works without a name. `assert(condition, "text")` stops the script with that text when the condition is
false.

## on blocks

An `on` block reacts whenever its condition **becomes** true, whatever the script is doing at that moment:

```
on mobs > 0 do defend() end
on food < 14 do eat() end
on bed_time do sleep() end
on restock_needed do restock() end
on full
  dump(dump_chest)
end
```

* An `on` block switches on when the script reaches it and stays on until the script ends, so put them near the top.
* Conditions are checked twice a second (every 10 ticks). When one becomes true, the running action pauses, the
  block runs, and then the action carries on (a walk is planned again from where you are).
* A block fires again only after its condition was false in between, or when the condition is still true a minute
  after the block last ran.
* Order matters: a block written higher up can interrupt one written below it, but not the other way round. Put the
  most urgent one (fighting) first.
* The script ends after its last line, and the `on` blocks end with it. To keep them watching, end the script with
  something that waits, for example `wait until false`.

Instead of `on` blocks you can write `enable("eat", "sleep", "defence")`. That lets the helpers act by themselves
with their saved settings ([OPTIONS.md](OPTIONS.md#survival)).

## Jobs: resumable phases

You can split a long script into named **jobs**. Manhattan remembers which jobs finished, per world and per script
tab. If the script was stopped (a crash, a disconnect, Stop), pressing Run again carries on where it left off:

```
job "fill"
  area("canal")
  fill_water("dirt", dirt_chest)
end

job "dig"
  area("canal")
  clear_area()
end
```

* A finished job is **skipped** on the next run ("Job 'fill' is done from an earlier run: skipped"). The variables it
  set are restored, so later jobs can still use them.
* A job runs again if it failed (an error that was not caught) or was interrupted (Stop, `stop()`, a disconnect).
* Inside a job, the big actions (`fill_water`, `clear_area`, `schem_build` ...) also remember which **chunks** they
  finished, so even a half-done job does not start from zero ([Chunked runs](GUIDES.md#chunked-runs-and-resuming)).
* The editor checks these rules: job names are texts in quotes and must be unique, `"script"` is reserved, and jobs
  cannot be nested. Put `on` blocks and `func` definitions **outside** jobs. Inside a job they only exist while it
  runs, and not at all when it is skipped.
* The memory lives in the file `config/manhattan-jobs/<world>_<script>.json`. It is **not** cleared when a script
  finishes. `/queue jobs` shows it; `/queue reset <job>` makes a finished job run again, and `/queue reset` alone
  starts the whole script from zero. `/queue retry <job>` only opens the failed or deferred parts again (after you
  refilled a chest or cleared an obstacle). These work on the editor's open tab and not while a script runs.

## Positions in the script

You don't have to type coordinates. The editor can fill them in:

* **Tab, like /setblock**: after `(`, `,` or `=` the completion list offers `pos(x, y, z)` of the block you looked at
  when you opened the editor (up to 96 blocks away). Inside `pos(` it offers just the numbers, and in a `#` line
  `x y z`.
* **Pick block**: the workspace closes, you aim at any block and press **J**. On an empty line it writes
  `let chest = pos(…)   // chest` (or `bed`, `barrel` …). Anywhere else it puts `pos(…)` at the cursor.
* **Capture (K)**: collects many chests, beds and areas at once into a sheet you can paste
  ([WORKSPACE.md](WORKSPACE.md#capture-k)).

## Legacy # lines

Before 2.12, Manhattan drove Baritone, and `#` lines were Baritone commands. Baritone is no longer needed: movement
now goes through Manhattan's own engine, **Midtown**. Old `#` lines still work for the common commands, which are
translated to Midtown. The editor marks them as legacy.

| line | does |
|---|---|
| `#goto x y z` / `#goto x z` | `goto(...)` |
| `#mine [n] block ...` | `mine(...)` |
| `#stop`, `#cancel` | `cancel()` |
| `#pause`, `#p` / `#resume`, `#r` | pauses / resumes the engine |
| `#sel pos1 [x y z]`, `#sel pos2 [x y z]`, `#sel set x1 y1 z1 x2 y2 z2`, `#sel clear` | the selection |
| `#sel ca` | `clear_area()` on the selection |
| `#sel replace <blocks> air` | mines those blocks in the selection |
| `#set <setting> <value>` / `#<setting> <value>` | `set(...)` for the [engine settings](OPTIONS.md#path-engine-settings-set) |

Baritone-only settings such as `#buildInLayers` or `#acceptableThrowawayItems` are accepted and ignored. Commands
that never finish by themselves (`#mine` without a number, `#follow`, `#farm`, `#explore`) block the rest of the
script, and the editor warns about them. New scripts should use the functions (`goto()`, `mine()`, `clear_area()`).

## Checking a script

**Test** in the editor checks the script without running it. It checks the language itself and, in a world, what
the script will meet:

* a chest, barrel or shulker box at every `pos(…)` used with take/put/dump/fill_water and at the survival chests
* a bed at `survival.bed`
* the Litematica placements named in `schem_*`
* items a `schem_build` needs that are neither in your inventory nor in any chest seen open
* chests seen open that lack an item `take`/`take_upto` asks for
* a bucket and a first water source for `flood()`

Positions that are not loaded are reported, not checked.

While you type, the editor checks the script 400 ms after the last key and underlines problems: unknown functions,
variables used before `let`, wrong item or block names, `=` in a condition, `on` or `func` inside a job, and so on.

## Scripts from before 2.0

The first start of 2.0.0 converted the saved script and your own presets automatically. The old text stays in
`config/manhattan.json` under `scriptBeforeV2`.

| old | new |
|---|---|
| `sel ca` | `#sel ca` or `clear_area()` |
| `def_chest dirt 1 2 3` | `let dirt = pos(1, 2, 3)` |
| `area 1` | `area("1")` |
| `chest dirt get dirt to 512` | `take_upto(dirt, "dirt", 512)` |
| `chest dump put rest` | `dump(dump_chest)` |
| `/buildtools terminator dirt` | `fill_water("dirt")` |
| `if has dirt 64` / `if health below 5` | `if has("dirt", 64)` / `if health < 5` |
| `addons off escape` | `disable("escape")` |
