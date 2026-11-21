package dev.manhattan.fleet;

/**
 * Operating mode for a Manhattan client when connected to a server running {@code manhattan-fleet-server}.
 */
public enum FleetMode {
    /** Standalone single-client behavior (ignores fleet server work). */
    LOCAL("Local (Solo)"),
    /** Submits and monitors fleet jobs; never claims work. */
    EDITOR("Editor"),
    /** Claims and executes work from the fleet server autonomously, even when no Editor is online. */
    WORKER("Worker"),
    /** Can both submit/monitor jobs and claim work. */
    BOTH("Editor + Worker");

    public final String label;

    FleetMode(String label) {
        this.label = label;
    }

    public boolean canEdit() {
        return this == EDITOR || this == BOTH;
    }

    public boolean canWork() {
        return this == WORKER || this == BOTH;
    }

    public static FleetMode parse(String raw) {
        if (raw == null || raw.isBlank()) return LOCAL;
        try {
            return FleetMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOCAL;
        }
    }
}
