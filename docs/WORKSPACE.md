# The workspace

Press **J** in game to open the workspace, and **J** again to close it. It opens in the layout you used last.
On macOS, use Cmd wherever this page says Ctrl.

When it is closed, the game is full size with normal mouse look. While a script runs, a small pill at the top of the
screen shows the step and the progress, and errors pop up as toasts for 8 seconds.

## The two layouts

**Edit** is for writing scripts: the activity bar on the left, the script editor (about 60% of the width), and on the
right the live game above a **Now running** card (percent, step, estimate, step bar, Skip/Stop) and the Vitals,
Tools and Alerts tiles.

**Monitor** is for watching a long run:

- the top strip: the current step, the step bar, the percent, an estimate, Skip and Stop
- left: Steps, Vitals and Handlers (each `on` block with how often it fired and when it last finished)
- middle: the game, with Alerts and the Log under it
- right: a top-down **Map** (saved areas, the selection, chests and captured spots, positions written in the script
  as `let name = pos(...)`, the player, and the chunks of a chunked run) and the **Inventory**

**Alerts** shows the latest error, a stalled step, a tool close to the item-saver limit, low health or food, and a
full inventory.

Switch layouts with the activity bar, **Ctrl+P**, or `/queue ui edit|monitor|close`. The activity bar also opens
**Presets**, **Chests**, **Areas**, **Capture** and **Log** as a drawer over the left side.

Clicking into the game gives it the keyboard and mouse, so chests and other screens work inside the game area.
Clicking the panels or pressing Esc takes them back. There is no mouse look while the workspace is open.

## The editor

| key / button | |
|---|---|
| **Run** | runs the script of this tab |
| **Test** | checks the script and the world without running it ([what it checks](SCRIPTING.md#checking-a-script)) |
| **Stop** / **Skip** | ends the script / ends only the action that runs now |
| **Pick block** | aim at a block and press J: its position goes into the script |
| **Save preset** | saves the selected lines as a preset |
| Tab / Enter | takes the completion (shown as grey ghost text and a list) |
| Up / Down / PgUp / PgDn / Esc | moves through / closes the completion list |
| Ctrl+Space | lists every completion |
| Ctrl+/ | comments or uncomments the selected lines |
| Ctrl+P | command palette: run, test, skip, stop, layouts, drawers, UI scale (100/125/150%), Pick block, insert a preset, open a script tab, go to a chest or spot |

**Tabs**: `main` is the script saved in the mod's config; every `<game folder>/scripts/*.txt` is a tab too. The job
memory ([jobs](SCRIPTING.md#jobs-resumable-phases)) is kept per tab.

Problems are marked 400 ms after you stop typing: a squiggle under the line, with the message.

## Presets

**Presets** inserts ready-made scripts at the cursor: the canal (dig, plug the old water, build the schematics, fill
with water), building a Litematica schematic with materials from chests, digging an area top down, mining one or
several areas, the water job, filling water with dirt, flooding an area, clearing trees, the survival helpers,
staying fed and sleeping, emptying into a chest when full, and mining, farming and travel. Positions are
placeholders: put the cursor on a `pos(...)` and use Pick block.

When a new version brings new presets, the old ones you never changed are replaced. Ones you edited stay, next to the
new one as "name (yours)". Your own presets are never touched.

## Capture (K)

Capture collects chests, beds and areas into a **sheet**: plain script you can paste into a script, or hand to an LLM
to write the script around it.

* **Role**: dump, supply / tools, return chest, material chest, scaffolding chest, bed, mine area, fill area,
  building block area, safe area, other spot, other area, schematic (Litematica). **Name** is optional (other spot and
  other area use it as their name; an area with a name you used before gets one more box).
* **Fetch looked-at** takes the block you looked at when you opened the workspace.
* **Aim in world (J)** closes the workspace: look at a block and press J. With **Rapid: on** every press adds a line and
  you stay in the world (chest after chest). K brings the drawer back, and so does J while looking at the sky.
* Areas take two corners (Fetch or J, twice). The box being made is outlined while you aim. **Use selection** takes
  the current selection instead.
* **Schematic**: pick a Litematica placement, then Fetch. It adds `let canal = "canal"`, so the script can say
  `schem_build(canal)`. **Import saved** adds the chests and areas saved with `/queue chest set` and `/queue area save`,
  and every Litematica placement the sheet does not have yet.
* **Copy** puts the sheet on the clipboard. **Copy full context** adds what an LLM needs: the world, where you stand,
  the inventory, the want-list, what each area holds (air, water cells, the most common blocks), lines that look
  wrong (a "chest" that is now air), the Litematica placements and the list of built-in functions.
* **Show in world** marks the captured places (flames when the block is no longer a chest or bed) and the areas.
* **Set**: several sheets per world (main base, outpost ...): type a name, **Open set**.

The sheet is a file per world and set: `<game folder>/manhattan-context/<world>_<set>.txt`, with the full context next
to it as `<world>_<set>.context.txt`, so an LLM with file access can read it directly. `/queue capture dir <folder>`
puts them somewhere else (`/queue capture dir default` switches back). Edits in the drawer are saved to the file, and
when the file changes outside, the drawer shows the new text.

A sheet looks like this. `//` in front of a line switches it off:

```
// --- places
let dump_chest = pos(1787, 74, -837)   // dump: dump() puts the inventory in here; chest; holds: 640 cobblestone
let dirt_chest = pos(1794, 74, -840)   // material: blocks for fill_water / building; chest; holds: 1728 dirt
let bed = pos(1791, 74, -839)   // bed: sleep() uses it; red_bed

// --- areas
area_box("mine_1", pos(1700, 60, -900), pos(1720, 74, -880))   // mine: clear_area() mines it out; 21x15x21 = 6615 blocks
// area_box("fill_1", pos(1730, 50, -900), pos(1750, 62, -880))   // fill: switched off
protect("protect_1", pos(1779, 68, -844), pos(1797, 80, -832))   // safe: never broken or built in

// --- schematics
let canal = "canal"   // schematic: Litematica placement at 1750 64 -850, 2 regions; schem_build(canal) builds it
```
