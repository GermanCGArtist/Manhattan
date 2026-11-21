# Manhattan changes for Heavymatica

What Manhattan (`main`, MC 26.1.1, mod id `manhattan`, formerly `baritonequeue`, which it still provides) needs so the Heavymatica addon can send its
areas in directly. Decisions this follows (from the Heavymatica plan review, 2026-11-14):

| # | Decision |
|---|---|
| 1-2 | Manhattan gets a small public API; Heavymatica **pushes** areas into it, **with a role**. `PROTECT` areas reach Midtown's planner without a script line. |
| 3 | **Explicit send** only. A send is refused while a running script uses that area. |
| 4 | **Shared names + owner tag** (`manual` / `script` / `heavymatica`). A script's `area_box` overwriting a Heavymatica area warns; Heavymatica asks before replacing a script-owned area. |
| 7 | Exact boxes; many boxes are allowed, so the outline drawing must cope. |
| 8 | An array is **one area** holding the boxes of all copies. |

Heavymatica stays an optional dependency: Manhattan never references it.

---

## 1. Area metadata (role + owner)

**Recommended deviation from decision 4:** keep `cfg.areas` as it is (`Map<String, List<int[]>>`) and add a parallel
map, instead of changing the value type. `cfg.areas` is read in ~15 places (`AreasView`, `Capture`, `QueueMod`,
`Suggest`, `ScriptCheck`, `GameBuiltins`), and old configs load unchanged, so there is no migration.

`Config.java`:

```java
/** Who wrote a saved area and what it is for; missing = an area from before this existed (treated as manual). */
java.util.Map<String, AreaMeta> areaMeta = new java.util.LinkedHashMap<>();

static final class AreaMeta {
    String owner = "manual";   // "manual" | "script" | "heavymatica"
    String role = "custom";    // "dig" | "clear" | "flood" | "fill" | "protect" | "custom"
    long updated;              // System.currentTimeMillis() of the last write
}
```

Null-check it on load like `areas` (`Config.java:75`).

Every writer in `Areas.java` sets the owner:

| Method | Owner | Note |
|---|---|---|
| `save(name)` (`/queue area save`, `save_area()`) | `manual` | |
| `box(name, …)` (`area_box()`) | `script` | see section 4 for the warning |
| `move(name, …)` | unchanged | bump `updated` |
| `delete(name)` | — | remove the meta entry too |
| new `putOwned(…)` (the API, section 2) | `heavymatica` | |

Helpers: `static AreaMeta meta(String name)` (returns a default `manual`/`custom` when missing) and
`static String ownerOf(String name)`.

## 2. Public API: `dev.baritonequeue.api.ManhattanAreas`

A new public class, the only thing Heavymatica touches. `Areas` is package-private, so either add a few `public`
bridge methods on `Areas` or put a package-private `AreasBridge` next to it that the API calls.

```java
package dev.baritonequeue.api;

public final class ManhattanAreas {
    public static final int API_VERSION = 1;

    public enum Role { DIG, CLEAR, FLOOD, FILL, PROTECT, CUSTOM }
    public enum Owner { MANUAL, SCRIPT, HEAVYMATICA }
    public enum Result { OK, SCRIPT_OWNED, IN_USE, INVALID, NOT_IN_WORLD }

    /** Box = {x1,y1,z1,x2,y2,z2}, any corner order (normalized on the way in). */
    public record AreaInfo(String name, java.util.List<int[]> boxes, Role role, Owner owner, long updated) {}

    /**
     * Saves (replaces) a named area. Client thread only.
     * SCRIPT_OWNED: the name was last written by a script's area_box() and replaceScriptOwned is false.
     * IN_USE:       a running script has this area selected (section 3).
     * INVALID:      empty name, "last", no boxes, a box outside the world's height, or more than MAX_BOXES.
     * Never changes the current selection.
     */
    public static Result put(String name, java.util.List<int[]> boxes, Role role, boolean replaceScriptOwned);

    public static java.util.Optional<AreaInfo> get(String name);
    public static java.util.Set<String> names();

    /** Removes an area, but only one Heavymatica owns; false otherwise. Also drops its protection (section 5). */
    public static boolean remove(String name);

    /** Makes the area the current selection, like area("name"). */
    public static boolean select(String name);

    /** Copy of the current selection, for "New Heavymatic from Selection". */
    public static java.util.List<int[]> selection();

    /** Every protected box the planner knows, for the export's "overlaps a protected area" flag. */
    public static java.util.List<AreaInfo> protectedAreas();

    /** Called on the client thread whenever a saved area changes (name, new owner); null owner = deleted. */
    public static void addListener(java.util.function.BiConsumer<String, Owner> listener);
}
```

Notes:

* **Thread:** check `Minecraft.getInstance().isSameThread()` and throw otherwise; `cfg` and `Config.save()` are not
  thread-safe.
* **`MAX_BOXES`:** a hard safety limit (e.g. 4096). Heavymatica's own warning at ~64 boxes is a UI matter.
* **Listener:** fire it from `save`, `box`, `move`, `delete` and `putOwned`. That is how a Heavymatica row learns it
  went "out of sync" when a script rewrites the area.
* Heavymatica side: `"suggests": { "manhattan": "*" }` in its `fabric.mod.json`, a
  `FabricLoader.isModLoaded("manhattan")` check, and an `API_VERSION` check before calling anything.

## 3. "In use": when a send is refused

Today nothing records *which* saved area is selected: `area("x")` copies the boxes into the selection
(`Areas.load` → `apply`). Add:

```java
/** The saved area the selection was last loaded from; null after any other change to the selection. */
private static String loadedName;
```

* Set it in `load(name)`; clear it in `apply` (when not called from `load`), `clearSelection`, `setPos1/2`,
  `setSingleBox`, `addBox` and in `TreeClear`'s restore (`TreeClear.java:371`).
* In use = `RunnerView.running() && name.equalsIgnoreCase(loadedName)`.

Why refuse: actions copy the selection when they start (`ClearProcess.begin`, `ClearProcess.java:104`), so a running
`clear_area()` would not notice anyway. But `ClearProcess.carryOn()` (`ClearProcess.java:148`) compares the
selection with the boxes it paused on, and the script's next `area("canal")` would pick up the new boxes halfway
through a job. `put` must **never** touch the current selection, even when it is not in use.

## 4. Script overwrites a Heavymatica area

In `Areas.box(…)` (`Areas.java:122`), on the first write of a name in a run (`scripted.add(n)` returned true):

```java
if ("heavymatica".equals(ownerOf(n))) {
    Util.msg("area_box(\"" + n + "\") replaces the area Heavymatica sent. It will show as out of sync there.");
}
```

Then set owner `script` and fire the listener. Heavymatica's own direction ("ask before replacing a script area")
is the `SCRIPT_OWNED` result from `put`.

## 5. `PROTECT` areas reach the planner (needs fixes that exist without Heavymatica too)

`protect()` goes `GameBuiltins` → `dev.buildtools.Api.protect` → `Zones.put`, which saves a `Config.Zone` in
buildtools' config and calls `EngineSelector.current().addProtectedArea(name, area)`. The planner only sees
`MidtownEngine.protectedAreas` (copied into `CalculationContext` at `MidtownEngine.java:301`). Three problems.
**Problems 1 and 3 are fixed already:** `Zones.syncEngines()` re-sends all safe areas (none while the switch is off)
at start, after every change and to each new engine (`EngineSelector.onCreate`). Only problem 2 is left.

1. **Saved safe areas are not given back to Midtown after a restart.** `addProtectedArea` is only called from
   `Zones.put`, so after a game restart `protectedAreas` is empty and the planner will plan through safe areas.
   `Api.protectedAt` still falls back to `cfg.zones`, so breaking checks work, but path planning does not.
   **Fix:** after `dev.buildtools.Config.load()`, and whenever `EngineSelector` switches or creates an engine,
   register every zone (`clearProtectedAreas()` then `addProtectedArea` for each). This is a bug today, independent
   of Heavymatica.
2. **One box per name.** `MidtownEngine.protectedAreas` is a `Map<String, Area>` and a `Zone` is one box, so a
   multi-box protected area of one name keeps only its last box. **Fix:** make it `Map<String, List<Area>>` with
   `setProtectedArea(String name, List<Area> boxes)` (replace) next to the existing methods. On the buildtools side,
   give `Config.Zone` a `group` field (null = its own name) and add `Api.protectGroup(name, boxes)` /
   `unprotect(name)` that replace / remove all zones of that group.
3. **The `protect` switch is ignored by the planner.** `Api.protectedAt` honours `cfg.protect`; the planner does
   not. Either skip registering when it is off, or pass it into `CalculationContext`. (Per-zone `allowBreak` /
   `allowPlace` are also not seen by the planner; fine for Heavymatica, worth a note in `DESIGN.md`.)

With those fixed, `ManhattanAreas.put(name, boxes, PROTECT, …)` does two things: saves the area in `cfg.areas` (so
`area("name")`, the Areas panel and `areas()` list it like any other) and calls `Api.protectGroup(name, boxes)`.
Changing a `PROTECT` area to another role, or `remove`, calls `unprotect(name)`.

## 6. Outline drawing with many boxes

**Mostly solved already:** the selection is now drawn as a wireframe (`Areas.renderWireframe`, one
`Gizmos.cuboid` per box, hooked on `LevelRenderEvents.BEFORE_GIZMOS`) instead of particles from a shared budget, so
every box is drawn. Left to check: frame time with a few hundred boxes. If it shows, skip boxes far from the player
(the particle version used 96 blocks). Drawing only the outer edges of the combined shape would look cleaner with
many small boxes; leave it for later.

Also check `clear_area()` with a 100+ box area in a Deming run before calling it done. It works on the whole
selection, and I have not measured how its scan time grows with the box count.

## 7. Smaller touchpoints

* `AreasView.AreaEntry`: add `role` and `owner`, so the Areas panel can show a tag (e.g. `[H]`) and the role.
* `/queue area list` (`QueueMod.java:337`): print the owner and role after each area.
* `Capture` export comments (`Capture.java:357`, `:508`): mention the role, e.g. `// canal: dig, from Heavymatica`.
* `docs/SCRIPTING.md`: a short section on areas sent from Heavymatica, the owner warning and protected roles.
* Possibly stale: messages still say "#sel pos1 / pos2" (`Areas.save`, `AreasView.currentSelectionSummary`) now
  that Baritone is gone. Worth checking what a `#sel` line does on this branch.

## 8. Tests

`LangTest` (pure, no game):

* old config without `areaMeta` loads; every area reads as `manual` / `custom`;
* `put` → owner `heavymatica`; `area_box` on that name in a run → owner `script` and the warning fires once;
* `put` on a script-owned name → `SCRIPT_OWNED`; with `replaceScriptOwned` → `OK`;
* `put` on the loaded name while running → `IN_USE`; the selection is unchanged after any `put`;
* invalid input (empty, `last`, no boxes, too many) → `INVALID`.

In-game (`TestBridge` / `tests/ingame`):

* a `PROTECT` area from the API across a straight path: Midtown goes around it;
* the same after a restart (catches section 5, problem 1);
* a 3-box `PROTECT` area: all three boxes are avoided (section 5, problem 2);
* a few-hundred-box selection: every box gets a wireframe and the frame rate holds.

## 9. The stair shaft (API version 2)

`Role.STAIRS`: a saved area whose first box (at least 2 x 2) is the footprint of a spiral staircase for a dig.
There is no link to store: `clear_area()` looks for a `stairs` area lying (in x and z) inside one of the boxes it
digs, so Heavymatica only has to send the shaft as a region next to the DIG region of the same Heavymatic.

* `API_VERSION` is 2. Heavymatica must not send `STAIRS` to version 1 (`ManhattanApi.put` answers `INVALID` itself).
* `put(name, boxes, STAIRS, ...)` is `INVALID` when the first box is narrower than 2 blocks in x or z.
* In a script, Heavymatica's export writes `stairs("name", pos(..), pos(..))` instead of `area_box` lines and puts it
  before the dig.
* Behaviour (what is carved, how the bot climbs out): `GUIDES.md`, "A stair shaft for getting out of the hole".

## Suggested order

1. ~~Section 5, problem 1 (the restart bug)~~: done, together with problem 3.
2. Section 1: area metadata, and the owner set in every writer.
3. Section 3: `loadedName` and "in use".
4. Section 2: the API class, the listener, and section 4's warning.
5. Section 5, problem 2: multi-box protection (`Zones.syncEngine` is where the boxes of a group get registered).
6. Section 6: check wireframe frame time with many boxes; section 7: panel, list, docs.
7. Section 8: tests along the way, the in-game ones at the end.
