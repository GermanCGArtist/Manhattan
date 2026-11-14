package dev.midtown.api;

/**
 * Lifecycle state of a {@link PathEngine} task.
 */
public enum EngineStatus {
    /** No active goal or mining task. */
    IDLE,
    /** Computing a path or searching for targets. */
    PLANNING,
    /** Actively executing a path or movement. */
    MOVING,
    /** Making no progress or blocked from continuing. */
    STUCK,
    /** Most recent goal or task completed successfully. */
    DONE,
    /** Most recent goal or task failed or was aborted due to an error. */
    FAILED
}
