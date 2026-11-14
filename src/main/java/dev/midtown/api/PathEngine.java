package dev.midtown.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.List;

/**
 * Abstraction over terrain navigation, mining, pause/resume lifecycle, and safety/setting hooks.
 * <p>
 * Implementations include {@code BaritoneEngine} (legacy adapter) and {@code MidtownEngine}.
 */
public interface PathEngine {

    /** Canonical engine identifier (e.g. {@code "baritone"} or {@code "midtown"}). */
    String name();

    /** Starts navigating to {@code goal}, replacing any active goal or mining task. */
    void goTo(GoalSpec goal);

    /**
     * Starts mining blocks matching {@code filter}, optionally bounded to {@code box}.
     *
     * @param filter block filter to match
     * @param box    optional bounding area, or {@code null} for unbounded search
     */
    void mine(BlockFilter filter, Area box);

    /** Starts mining blocks matching {@code filter} without an area bound. */
    default void mine(BlockFilter filter) {
        mine(filter, null);
    }

    /** Suspends the active task and releases movement/input overrides. */
    void pause();

    /** Resumes a previously paused task if one exists. */
    void resume();

    /** Cancels any active task and returns to {@link EngineStatus#IDLE}. */
    void cancel();

    /** Current lifecycle status of this engine. */
    EngineStatus status();

    /** True while the engine is planning, moving, or stuck on an active task. */
    default boolean isBusy() {
        EngineStatus s = status();
        return s == EngineStatus.PLANNING || s == EngineStatus.MOVING || s == EngineStatus.STUCK;
    }

    /** Optional per-client-tick update hook for status tracking and local execution. */
    default void tick() {}

    // ------------------------------------------------------------------ safety & setting hooks

    default void setAllowBreak(boolean allow) {}

    default boolean allowBreak() {
        return true;
    }

    default void setAllowPlace(boolean allow) {}

    default boolean allowPlace() {
        return true;
    }

    default void setAllowParkourPlace(boolean allow) {}

    default boolean allowParkourPlace() {
        return true;
    }

    default void addProtectedArea(String name, Area area) {}

    default void setProtectedArea(String name, List<Area> boxes) {
        removeProtectedArea(name);
        if (boxes != null) {
            for (Area a : boxes) addProtectedArea(name, a);
        }
    }

    default void removeProtectedArea(String name) {}

    default void clearProtectedAreas() {}

    default boolean isProtectedAt(BlockPos pos) {
        return false;
    }

    default void setDisallowedBreakBlocks(Collection<Block> blocks) {}

    /** Applies a named engine setting; returns true if accepted. */
    default boolean setSetting(String name, String value) {
        return false;
    }

    /** Reads a named engine setting as text, or {@code null} if unknown. */
    default String getSetting(String name) {
        return null;
    }
}
