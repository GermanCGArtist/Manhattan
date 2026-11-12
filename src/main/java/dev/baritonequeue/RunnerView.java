package dev.baritonequeue;

/**
 * View-model over {@link Runner} and Baritone's active process so both the vanilla screens and the Dear ImGui panels
 * read and control script execution through one place.
 */
public final class RunnerView {
    private RunnerView() {}

    public static boolean running() {
        return QueueMod.runner.running();
    }

    public static int line() {
        return QueueMod.runner.line();
    }

    public static int actionsDone() {
        return QueueMod.runner.actionsDone();
    }

    public static long runningMillis() {
        return QueueMod.runner.runningMillis();
    }

    public static String elapsedText() {
        return Estimate.duration(runningMillis() / 50);
    }

    public static String current() {
        return QueueMod.runner.current();
    }

    public static String status() {
        return QueueMod.runner.status();
    }

    /** The Baritone process in control ("Builder", "SurvivalKit sleep", ...), or "idle". */
    public static String controller() {
        try {
            var control = Util.baritone().getPathingControlManager().mostRecentInControl();
            if (control.isEmpty()) return "idle";
            return String.valueOf(control.get().displayName());
        } catch (Throwable t) {
            return "";
        }
    }

    /** Starts the current config script; returns an error message or null. */
    public static String start() {
        return QueueMod.runner.start(Config.cfg.script);
    }

    /** Stops the running script and cancels any active Baritone task. */
    public static void stop() {
        if (QueueMod.runner.running()) {
            QueueMod.runner.halt("Script stopped.");
        }
        try {
            Util.baritone().getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
        }
    }

    /** Skips the currently running action and continues the script. */
    public static void skip() {
        QueueMod.runner.skip();
    }
}
