package dev.buildtools;

import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

/**
 * A temporary Baritone process: it takes control from #mine/#farm/#build/... while it runs and hands it back
 * afterwards, without cancelling the task underneath.
 *
 * An error inside a feature never reaches Baritone (it would crash the game): it is reported in chat, the feature
 * resets itself and carries on.
 */
abstract class Feature implements IBaritoneProcess {
    private final String name;
    private final double priority;

    Feature(String name, double priority) {
        this.name = name;
        this.priority = priority;
    }

    static PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    static PathingCommand defer() {
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /** Whether this feature wants control right now. */
    abstract boolean active();

    /** One tick while in control. */
    abstract PathingCommand step(boolean calcFailed, boolean isSafeToCancel);

    @Override
    public final boolean isActive() {
        try {
            if (PauseWatch.paused || Util.addonOff(name)) {   // #pause or the queue's "addons off": stand down completely
                if (busy()) onLostControl();
                return false;
            }
            return active();
        } catch (Throwable t) {
            crashed(t);
            return false;
        }
    }

    @Override
    public final PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return step(calcFailed, isSafeToCancel);
        } catch (Throwable t) {
            crashed(t);
            return defer();
        }
    }

    private void crashed(Throwable t) {
        t.printStackTrace();
        Util.warn("error-" + name, name + " hit an error (" + t + ") and reset itself. Details are in the game log.");
        try {
            onLostControl();
        } catch (Throwable ignored) {
            // nothing more we can do
        }
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public String displayName0() {
        return name;
    }

    /** Called once per client tick; used for cheap trigger checks that should not run inside isActive(). */
    void tick() {}

    /** Whether this feature is in the middle of something (other features wait for it). */
    abstract boolean busy();
}
