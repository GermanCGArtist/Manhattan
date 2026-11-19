package dev.baritonequeue.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single unit of work inside a {@link Project}. Keyed by a stable unique {@link #id} (e.g. {@code "t_001"})
 * rather than list index or script line number, so live reordering never invalidates ledger progress.
 * Pure Java + Gson (no Minecraft imports).
 */
public final class ProjectTask {
    private static final AtomicInteger AUTO_SEQ = new AtomicInteger(0);

    public enum Kind {
        FILL_WATER,
        CLEAR_AREA,
        CLEAR_TREES,
        SCHEM_BUILD,
        FLOOD,
        DRAIN,
        MINE,
        DUMP,
        RESTOCK,
        SCRIPT
    }

    public enum PartialPolicy {
        CONTINUE,
        WARN_CONTINUE,
        PAUSE,
        STOP
    }

    public enum ErrorPolicy {
        STOP,
        SKIP,
        PAUSE,
        RETRY
    }

    public String id;
    public String label;
    public boolean enabled = true;
    public Kind kind = Kind.CLEAR_AREA;

    public String areaName;
    /** Horizontal expansion: 0 = exact, +1 = 1-block outward expand, -1 = 1-block inward inset. */
    public int expand = 0;
    public String block;
    public int layers = 3;
    public String placement;
    public String schemMode = "add";
    public String schemMissing = "skip";
    public List<String> mineBlocks = new ArrayList<>();
    public boolean collectScaffoldAfter = false;
    public boolean pickupAfter = false;
    public boolean dumpAfter = false;
    public String scriptPath;
    public String inlineScript;
    public Map<String, Object> params = new LinkedHashMap<>();

    public ScopedOverrides overrides = new ScopedOverrides();

    public PartialPolicy partialPolicy = PartialPolicy.WARN_CONTINUE;
    public int partialThreshold = 0;

    public ErrorPolicy errorPolicy = ErrorPolicy.STOP;
    public int maxRetries = 1;

    public ProjectTask() {
    }

    public ProjectTask(String id, String label, Kind kind) {
        this.id = id;
        this.label = label;
        this.kind = kind != null ? kind : Kind.CLEAR_AREA;
    }

    /** Ensures nullable fields have safe defaults (e.g. after Gson deserialization). */
    public void ensureInitialized() {
        if (kind == null) kind = Kind.CLEAR_AREA;
        if (schemMode == null || schemMode.isBlank()) schemMode = "add";
        if (schemMissing == null || schemMissing.isBlank()) schemMissing = "skip";
        if (mineBlocks == null) mineBlocks = new ArrayList<>();
        if (params == null) params = new LinkedHashMap<>();
        if (overrides == null) overrides = new ScopedOverrides();
        overrides.ensureInitialized();
        if (partialPolicy == null) partialPolicy = PartialPolicy.WARN_CONTINUE;
        if (errorPolicy == null) errorPolicy = ErrorPolicy.STOP;
        if (maxRetries < 0) maxRetries = 0;
        if (partialThreshold < 0) partialThreshold = 0;
    }

    /** Returns a human-readable label for UI/logs, falling back to kind + target/id. */
    public String displayLabel() {
        if (label != null && !label.isBlank()) return label;
        String k = kind != null ? kind.name() : "TASK";
        if (areaName != null && !areaName.isBlank()) return k + " (" + areaName + ")";
        if (placement != null && !placement.isBlank()) return k + " (" + placement + ")";
        if (scriptPath != null && !scriptPath.isBlank()) return k + " (" + scriptPath + ")";
        return id != null && !id.isBlank() ? k + " [" + id + "]" : k;
    }

    /**
     * Creates a task for an area and semantic role:
     * {@code "dig"} / {@code "clear"} -> {@link Kind#CLEAR_AREA},
     * {@code "fill"} -> {@link Kind#FILL_WATER},
     * {@code "flood"} -> {@link Kind#FLOOD},
     * default -> {@link Kind#CLEAR_AREA}.
     */
    public static ProjectTask forAreaRole(String areaName, String role) {
        String cleanArea = areaName == null ? "" : areaName.trim();
        String normRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);

        Kind resolvedKind;
        String verb;
        switch (normRole) {
            case "fill" -> {
                resolvedKind = Kind.FILL_WATER;
                verb = "Fill water";
            }
            case "flood" -> {
                resolvedKind = Kind.FLOOD;
                verb = "Flood";
            }
            case "dig", "clear" -> {
                resolvedKind = Kind.CLEAR_AREA;
                verb = "Clear area";
            }
            default -> {
                resolvedKind = Kind.CLEAR_AREA;
                verb = "Clear area";
            }
        }

        String generatedId = String.format(Locale.ROOT, "t_%03d", AUTO_SEQ.incrementAndGet());
        String generatedLabel = cleanArea.isEmpty() ? verb : verb + ": " + cleanArea;

        ProjectTask task = new ProjectTask(generatedId, generatedLabel, resolvedKind);
        task.areaName = cleanArea;
        if (resolvedKind == Kind.FILL_WATER) {
            task.block = "dirt";
        }
        return task;
    }
}
