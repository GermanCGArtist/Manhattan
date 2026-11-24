# Liskov — Shared Minecraft Filesystem & ModsUndBots UI Library Spec

> **Status:** Design specification for implementation in a dedicated session.  
> **Named after:** Barbara Liskov (pioneer of data abstraction, modularity, and the Liskov Substitution Principle).  
> **Consumers:**
> - **Full UI + FS (`liskov-core` + `liskov-ui`):** `Manhattan`, `Vitruvius`, `SchematicArray`
> - **Headless / Server FS (`liskov-core` only):** `Heavymatica`, `manhattan-fleet-server`

---

## 1. Overview & Two-Module Architecture

Across the workspace, **Manhattan**, **Vitruvius**, and **SchematicArray** all run Dear ImGui (`ImGuiNatives`) in the same Fabric client, while **Heavymatica** and **manhattan-fleet-server** need the same unified filesystem, folder metadata, and reload detection without any UI dependencies.

**Liskov** unifies both needs as a two-module shared library mod (`liskov`):

```text
liskov/
├── liskov-core/        # Pure Java 25 + Gson (ZERO Minecraft / ImGui / LWJGL imports)
│   ├── LiskovFs        # Namespace registry, unified root paths, safe migration, atomic writes
│   ├── FolderCatalog   # Color-coded folders, archive flag, sharing metadata
│   ├── CompletionTag   # Generic completion/lock tags (DONE, IN_PROGRESS, PAUSED, LOCKED, ERROR)
│   ├── ReloadWatcher   # 2s timestamp polling (Windows-safe, zero WatchService handle locks)
│   └── LockGuard       # Author / OP ("UNLOCK") lock validation helpers
│
└── liskov-ui/          # Fabric Client + Dear ImGui (depends on liskov-core)
    ├── ImGuiHost       # Single shared ImGuiContext, font atlas, RenderPearl hook, input routing
    ├── MubTheme        # 4 ModsUndBots presets + 8 semantic roles + 16x16 dot-grid backdrop
    ├── MubWidgets      # Sharp 0px buttons, badges, section headers, pixel segment bars, modals
    ├── LiskovPanel     # Reusable File Explorer UI (Local Namespaces + Server Shared Folders)
    ├── CommandPalette  # Shared ^P command palette registry across mods
    └── MenuOverview    # Registry for toggleable Title Screen & ESC Pause Menu overview cards
```

### Why This Split Matters
1. **Zero ImGui Bloat on Server / Non-UI Mods:** `manhattan-fleet-server` and `Heavymatica` depend only on `liskov-core`.
2. **One ImGui Context in Client:** `Manhattan`, `Vitruvius`, and `SchematicArray` stop maintaining 3 separate `ImGuiNatives` / `UiTheme` / input-capture stacks and instead register panels into one `ImGuiHost` with synchronized theme presets and UI scale.
3. **One File Explorer Across Mods:** Every registered mod namespace appears in `LiskovPanel`, making it trivial to copy LLM paths, open Explorer, color-code/archive folders, and reload disk edits without restarting Minecraft.

---

## 2. Canonical Directory Layout & Namespaces

### 2.1 Client Root (`<gameDir>/manhattan/` + `<gameDir>/liskov/`)

Each mod registers a `LiskovNamespace` via `LiskovFs.registerNamespace(...)`. Manhattan's own canonical namespace lives under `<gameDir>/manhattan/` (or `<gameDir>/liskov/<modId>/` for new mods), exposed side-by-side inside `LiskovPanel`:

```text
<gameDir>/
├── manhattan/                            # Registered namespace: "manhattan"
│   ├── .migrated                         # Safe migration receipt JSON
│   ├── config/
│   │   └── manhattan.json                # Core Config (was config/manhattan.json)
│   ├── projects/
│   │   └── <sanitizedWorld>/
│   │       ├── _active.txt
│   │       ├── _queue.json
│   │       ├── _folders.json             # FolderCatalog (colors, archive, share, order)
│   │       └── <projectId>.json          # Project definitions (author/folder/lock fields)
│   ├── scripts/
│   │   └── *.txt                         # Script editor tabs (was <gameDir>/scripts/)
│   ├── capture/
│   │   ├── <world>_<set>.txt             # Capture sheets (was <gameDir>/manhattan-context/)
│   │   └── <world>_<set>.context.txt     # Full LLM context bundles
│   └── logs/
│       └── manhattan-latest.log
└── liskov/
    ├── liskov-ui.json                    # Shared active ModsUndBots preset, UI scale, menu toggles
    ├── vitruvius/                        # Registered namespace: "vitruvius"
    ├── schematicarray/                   # Registered namespace: "schematicarray"
    └── heavymatica/                      # Registered namespace: "heavymatica"
```

### 2.2 Server Shared Folders (`<serverWorldRoot>/manhattan/shared-folders/`)

```text
<serverWorldRoot>/
└── manhattan/
    ├── data/
    │   └── manhattan-fleet.json          # Existing FleetStore state
    └── shared-folders/
        └── <folderId>/
            ├── _meta.json                # Folder id, name, colorRole, ownerUuid, ownerName, sharedWith, updatedAtMs
            └── <projectId>.json          # Shared Project JSON payloads
```

---

## 3. Safe Non-Destructive Migration (`LiskovFs.migrateLegacyOnce`)

On startup (before `Config.load()` and `ProjectStore`), Manhattan invokes `LiskovFs.migrateLegacyOnce(...)` once:

| Legacy Path | New Canonical Path | Mode |
|---|---|---|
| `<legacyConfigDir>/manhattan.json` (and `baritonequeue.json`) | `<gameDir>/manhattan/config/manhattan.json` | Copy if target missing |
| `<legacyConfigDir>/manhattan-projects/**` | `<gameDir>/manhattan/projects/**` | Recursive copy if target file missing |
| `<gameDir>/scripts/*.txt` | `<gameDir>/manhattan/scripts/*.txt` | Copy if target file missing |
| `<gameDir>/manhattan-context/*` | `<gameDir>/manhattan/capture/*` | Copy if target file missing |

### Safety Guarantees
- **Never delete or overwrite** existing files at the canonical target.
- **Leave legacy directories intact** on disk as a fallback backup.
- Write `<gameDir>/manhattan/.migrated` JSON receipt (`version`, `migratedAtMs`, `copiedFiles`).
- Honor an explicit `cfg.captureDir` override when set via `/queue capture dir <folder>`.

---

## 4. `liskov-core` Package & API (`dev.liskov.core`)

Pure Java + Gson + `java.nio.file`. Zero UI or Minecraft client imports.

### 4.1 `LiskovFs` & `LiskovNamespace`
- `LiskovFs.init(Path gameDir, Path configDir)`
- `LiskovNamespace registerNamespace(String modId, String displayName, Path rootDir, SemanticColor accentRole)`
- Standard subdirectory accessors (`configDir()`, `projectsDir(world)`, `scriptsDir()`, `captureDir()`, `logsDir()`)
- `writeAtomicUtf8(Path target, String content)` (temp sibling + `ATOMIC_MOVE` with `REPLACE_EXISTING` fallback)
- `copyPathToClipboard(Path)` (delegated via a small platform bridge registered by `liskov-ui` or AWT fallback)
- `openInExplorer(Path)` (Windows `explorer.exe` / macOS `open` / Linux `xdg-open`)

### 4.2 `FolderCatalog` (`_folders.json`)
Manages color-coded folders per namespace/world:

```java
public final class FolderCatalog {
    public int version = 1;
    public List<FolderEntry> folders = new ArrayList<>();

    public static final class FolderEntry {
        public String id;                  // sanitized slug, e.g. "active_builds"
        public String name;                // display title
        public String colorRole = "focus"; // "focus"|"bot"|"player"|"mr"|"cf"|"warn"|"meta"|"danger"
        public boolean archived = false;
        public boolean shared = false;
        public List<String> sharedWith = new ArrayList<>(); // player names/UUIDs or ["ALL_EDITORS"]
        public int sortOrder = 0;
        public long updatedAtMs = 0L;
    }
}
```

- Always ensures a `"default"` folder exists.
- Operations: `createFolder`, `renameFolder`, `setColorRole`, `setArchived`, `assignItem(itemId, folderId)`.

### 4.3 `CompletionTag`
Generic badge state consumed by `LiskovPanel` and Manhattan's Queue UI:

| Tag | Semantic Color | Condition (Manhattan Projects) |
|---|---|---|
| `LOCKED` | `danger` | `project.locked == true` |
| `ERROR` | `danger` | Active/paused job has `failedChunks > 0` or recent per-bot errors |
| `IN_PROGRESS` | `mr` | Active local or fleet run (`doneChunks < totalChunks` and `status == ACTIVE`) |
| `PAUSED` | `warn` | Job status is `PAUSED` |
| `DONE` | `player` | `totalChunks > 0 && doneChunks == totalChunks` (or all local tasks completed) |
| `NOT_STARTED` | `ink_muted` | No active run and 0 completed chunks/tasks |

### 4.4 `ReloadWatcher`
Uses lightweight `2000ms` timestamp + file-size polling (no Windows `WatchService` folder-handle locks):
- Watches any registered directories (`projectsDir`, `scriptsDir`, `captureDir`, plus Vitruvius/SchematicArray folders).
- `reloadNow()` forces an immediate scan (wired to the top-strip `[↻ RELOAD]` button).
- Emits `ReloadEvent(String namespaceId, List<Path> changedFiles)` so Manhattan reloads `ProjectStore`, script tabs, and `Capture` sheets live, and flags any matching active/paused Fleet job for one-click **`Push Update to Fleet Job`**.

### 4.5 `LockGuard` & `Project` Extensions
Add backward-compatible metadata fields to `dev.manhattan.project.Project`:

```java
public String authorUuid = "";
public String authorName = "";
public String description = "";
public String folderId = "default";
public boolean locked = false;
public String lockedBy = "";
public String lockReason = "";
public long lockedAtMs = 0L;
```

**Unlock Authorization (`LockGuard.canUnlock`):**
- **Author (`actorUuid.equals(authorUuid)`):** Unlocks directly.
- **OP (non-author):** Allowed **only** if `isOp && "UNLOCK".equals(confirmText)`.
- **Otherwise:** Rejected.

---

## 5. Server Shared Folders & `FleetWire` v3 (`manhattan-fleet-server`)

### 5.1 `FleetWire.java` (Byte-Identical in `Manhattan` & `manhattan-fleet-server`)
- Bump `PROTOCOL_VERSION = 3` (`MIN_COMPATIBLE_VERSION = 2`).
- Add messages:
  - `JOB_HOT_UPDATE` (`jobId`, `projectHash`, `projectJson`): updates project definition/tasks/chests while preserving all `ChunkState.DONE` chunks.
  - `JOB_LOCK` (`jobId`, `projectId`, `locked`, `reason`, `confirmUnlockText`): enforces `LockGuard`.
  - `FOLDER_SHARE_SYNC` (`action`: `list` | `upsert_folder` | `delete_folder` | `download_folder`, `folderMeta`, `projectsJsonMap`).
  - `FOLDER_SHARE_SNAPSHOT` (`folders`: list of shared folder summaries + project metadata).

### 5.2 `SharedFolderStore.java` (Server)
- Persists shared folders under `<serverWorldRoot>/manhattan/shared-folders/<folderId>/`.
- Uses `liskov-core` atomic JSON writes (`_meta.json` + `<projectId>.json`).
- Enforces `isEditorAllowed(player)` for writes and `sharedWith` ACL filtering for reads.

---

## 6. `liskov-ui` Package & Shared UI System (`dev.liskov.ui`)

### 6.1 `ImGuiHost`
- Replaces per-mod `ImGuiNatives` / `ImGuiManager` boilerplate in `Manhattan`, `Vitruvius`, and `SchematicArray` with one shared `ImGuiContext`, font atlas (`JetBrainsMono.ttf`), RenderPearl quad hook, and focus/keyboard/mouse router.
- Allows each mod to register workspaces/tabs cleanly without input conflicts.

### 6.2 `MubTheme` & `MubWidgets`
Exact port of ModsUndBots (`crates/mub-gui/src/theme.rs`):
- **4 Theme Presets:** `SYNTH_TERMINAL`, `GRUVBOX_PIXEL`, `TOKYO_NIGHT`, `DRAFTING_PAPER` (persisted globally in `<gameDir>/liskov/liskov-ui.json`, so all 3 mods switch theme together).
- **8 Semantic Color Roles:** `focus`, `cta`, `bot`, `player`, `mr`, `cf`, `warn`, `meta`, `danger` (+ `*_bg` tints).
- **Geometry:** `0px` rounding everywhere, `1px` crisp inside borders, `16×16` tiled dot-grid backdrop (`paintDotGridBackdrop`).
- **Primitives:** `termButton`, `termBadge`, `sectionHeader` (`── TITLE`), `termToggle` (`[×]`/`[ ]`), `pixelSegments`, `termRow`, `themeSwatchCard`, `ToastHost`, and shared `CommandPalette` (`Ctrl+P`).

### 6.3 `LiskovPanel` (Shared File Explorer UI)
- **Top Bar:** Namespace switcher (`Manhattan`, `Vitruvius`, `SchematicArray`, `Heavymatica`), active root path, `[COPY PATH]`, `[OPEN EXPLORER]`, `[↻ RELOAD]`, `[×] AUTO-WATCH`, and `LOCAL` vs. `SERVER SHARED FOLDERS` tabs.
- **Left Column (Folder Rail):** System folders + color-coded job folders (`3px` color stripe, `[ARCH]`, `[SHARED]`), 8-color swatch picker, `[+ NEW FOLDER]`, `[ARCHIVE]`, `[SHARE TO SERVER]`.
- **Right Column (Items):** Displays **Completion Tag** (`LOCKED`, `ERROR`, `IN_PROGRESS`, `PAUSED`, `DONE`, `NOT_STARTED`), author, modified time, size, and one-click `[COPY PATH]` (for LLMs) + `[EXPLORER]` + mod-specific `[OPEN]` callbacks.

### 6.4 `MenuOverview` (Title Screen & ESC Pause Menu)
- `MenuOverviewRegistry` lets each mod contribute a compact ModsUndBots summary card to:
  - **Main Title Screen** (toggleable `[×] MANHATTAN OVERVIEW`): shows Liskov namespaces, saved worlds/folders, completion/lock counts, `[OPEN LISKOV]`, `[COPY PATH]`.
  - **In-Game ESC Pause Menu** (toggleable `[×] MANHATTAN OVERVIEW`): shows live job/fleet progress, active bot states, and quick `[OPEN WORKSPACE (J)]` / `[HOLD ALL BOTS]`.

---

## 7. Packaging & Multi-Mod Integration

- **Standalone Gradle Project (`f:\workflow\Liskov`):**
  - `:liskov-core` — pure Java jar (`maven-publish` to `mavenLocal()` or composite `includeBuild("../Liskov")`).
  - `:liskov-ui` — Fabric client mod jar bundling `:liskov-core` + ImGui natives (`fabric.mod.json` mod id `liskov`).
- **Mod Distribution:**
  - `Manhattan`, `Vitruvius`, and `SchematicArray` declare `depends: { liskov: "*" }` (or bundle `liskov` via Fabric `include` Jar-in-Jar so users only drop one mod or `ModsUndBots` installs `liskov` automatically).
  - `Heavymatica` and `manhattan-fleet-server` include `liskov-core` only via `include` / `implementation`.
- **Migration Order Across Mods:**
  1. Build & test `Liskov` (`liskov-core` + `liskov-ui`).
  2. Migrate `Manhattan` first (removing duplicate `UiTheme`/`ImGuiNatives` and wiring `LiskovFs` + `SharedFolderStore`).
  3. Point `Vitruvius` and `SchematicArray` at `ImGuiHost`, `MubTheme`, and `LiskovFs`.
  4. Point `Heavymatica` at `liskov-core` so its generated schematics/plans appear automatically in `LiskovPanel`.

---

## 8. Implementation Checklist & Verification Plan

### Ordered Checklist
1. Scaffold `Liskov` repo (`:liskov-core` + `:liskov-ui`) with unit tests.
2. Implement `LiskovFs`, `FolderCatalog`, `CompletionTag`, `ReloadWatcher`, `LockGuard`, and `migrateLegacyOnce` in `:liskov-core`.
3. Implement `ImGuiHost`, `MubTheme`, `MubWidgets`, `LiskovPanel`, `CommandPalette`, `ToastHost`, and `MenuOverview` in `:liskov-ui`.
4. Update `FleetWire.java` (both `Manhattan` & `manhattan-fleet-server`) and implement `SharedFolderStore` + `JOB_HOT_UPDATE` / `JOB_LOCK` / `FOLDER_SHARE_SYNC` on the server.
5. Wire `Manhattan` to `liskov-core` + `liskov-ui`, then migrate `Vitruvius`, `SchematicArray`, and `Heavymatica`.

### Automated Tests
- `Liskov` (`.\gradlew.bat test`):
  - `LiskovFsTest`: namespace registration, atomic writes, non-destructive `migrateLegacyOnce()` preserving legacy files.
  - `FolderCatalogTest`: `_folders.json` persistence, 8 color roles, archive toggle, `CompletionTag` derivation.
  - `ReloadWatcherTest`: `reloadNow()` and 2s polling detecting disk edits without Windows file locks.
  - `LockGuardTest`: author unlock, non-OP/non-author rejection, OP requiring exact `"UNLOCK"`.
- `manhattan-fleet-server` (`.\gradlew.bat test`):
  - `JobHotUpdateTest` (`DONE` chunks preserved), `JobLockTest`, `SharedFolderStoreTest`.
- `Manhattan` (`.\build.bat`):
  - `FleetWireParityTest` (byte-identical `FleetWire.java` check) + `Manhattan` integration tests.
