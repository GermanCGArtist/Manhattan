package dev.survivalkit;

abstract class Feature {
    private final String name;
    private final double priority;

    Feature(String name, double priority) {
        this.name = name;
        this.priority = priority;
    }

    /** Whether this feature wants control right now. */
    abstract boolean active();

    /** Whether this feature is in the middle of something (other features wait for it). */
    abstract boolean busy();

    void onLostControl() {}

    public final boolean isActive() {
        try {
            if (PauseWatch.paused || Util.addonOff(name)) {
                if (busy()) onLostControl();
                return false;
            }
            return active();
        } catch (Throwable t) {
            crashed(t);
            return false;
        }
    }

    void crashed(Throwable t) {
        t.printStackTrace();
        Util.warn("error-" + name, name + " hit an error (" + t + ") and reset itself. Details are in the game log.");
        try {
            onLostControl();
        } catch (Throwable ignored) {
        }
    }

    public boolean isTemporary() {
        return true;
    }

    public double priority() {
        return priority;
    }

    public String displayName0() {
        return name;
    }

    /** Called once per client tick. */
    void tick() {}
}
