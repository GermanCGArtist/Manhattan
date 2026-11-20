package dev.baritonequeue;

import dev.baritonequeue.project.GameProjectRuntime;
import dev.baritonequeue.project.PriorityScheduler;

/**
 * View-model over {@link Runner}, {@link GameProjectRuntime}, and the active path engine so both the vanilla
 * screens and the Dear ImGui panels read and control execution through one place.
 */
public final class RunnerView {
    private RunnerView() {}

    public static boolean running() {
        return QueueMod.runner.running() || projectRunning();
    }

    public static int line() {
        return QueueMod.runner.line();
    }

    public static int actionsDone() {
        if (projectRunning() && !QueueMod.runner.running()) {
            PriorityScheduler.Telemetry t = GameProjectRuntime.instance().telemetry();
            return t != null ? t.doneTasks() : 0;
        }
        return QueueMod.runner.actionsDone();
    }

    public static long runningMillis() {
        if (projectRunning() && !QueueMod.runner.running()) {
            PriorityScheduler.Telemetry t = GameProjectRuntime.instance().telemetry();
            return t != null ? t.elapsedMillis() : 0L;
        }
        return QueueMod.runner.runningMillis();
    }

    public static String elapsedText() {
        return Estimate.duration(runningMillis() / 50);
    }

    public static String current() {
        if (projectRunning()) {
            PriorityScheduler.Telemetry t = GameProjectRuntime.instance().telemetry();
            String proj = formatTelemetry(t);
            if (QueueMod.runner.running()) {
                String scriptCur = QueueMod.runner.current();
                return scriptCur.isEmpty() ? proj : proj + " — " + scriptCur;
            }
            return proj;
        }
        return QueueMod.runner.current();
    }

    public static String status() {
        if (projectRunning()) {
            PriorityScheduler.Telemetry t = GameProjectRuntime.instance().telemetry();
            String projName = (t != null && t.projectName() != null && !t.projectName().isBlank())
                    ? t.projectName()
                    : (t != null && t.projectId() != null ? t.projectId() : "project");
            String base = "Project " + projName + ": " + formatTelemetry(t);
            if (QueueMod.runner.running()) {
                return base + " (line " + QueueMod.runner.line() + ": " + QueueMod.runner.current() + ")";
            }
            return base;
        }
        return QueueMod.runner.status();
    }

    /** The active path engine ("midtown") when busy, or "idle". */
    public static String controller() {
        try {
            var engine = dev.midtown.api.EngineSelector.current();
            return engine.isBusy() ? engine.name() : "idle";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Starts the current config script; returns an error message or null. */
    public static String start() {
        return QueueMod.runner.start(Config.cfg.script);
    }

    /** Stops the running project queue or script and cancels any active path engine task. */
    public static void stop() {
        if (projectRunning()) {
            GameProjectRuntime.instance().stopQueue("Stopped.");
        }
        if (QueueMod.runner.running()) {
            QueueMod.runner.halt("Script stopped.");
        }
        try {
            dev.midtown.api.EngineSelector.current().cancel();
        } catch (Throwable ignored) {
        }
    }

    /** Skips the currently running project task or script action. */
    public static void skip() {
        if (projectRunning()) {
            GameProjectRuntime.instance().skipCurrent();
            return;
        }
        QueueMod.runner.skip();
    }

    private static boolean projectRunning() {
        try {
            GameProjectRuntime rt = GameProjectRuntime.instance();
            return rt != null && rt.running();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String formatTelemetry(PriorityScheduler.Telemetry t) {
        if (t == null) return "Project running";
        String lane = laneTag(t);
        String detail = t.detail() != null && !t.detail().isBlank()
                ? t.detail()
                : (t.taskLabel() != null ? t.taskLabel() : "");
        return detail.isEmpty() ? lane : lane + " " + detail;
    }

    private static String laneTag(PriorityScheduler.Telemetry t) {
        if (t.lane() == null) return "[Project]";
        return switch (t.lane()) {
            case P0_EMERGENCY -> "[P0 Emergency]";
            case P1_COMBAT -> "[P1 Combat]";
            case P2_VITALS -> "[P2 Vitals]";
            case P3_LOGISTICS -> "[P3 Base Trip]";
            case P4_WORK -> {
                int step = t.taskIndex() >= 0 ? (t.taskIndex() + 1) : Math.min(Math.max(1, t.totalTasks()), t.doneTasks() + 1);
                yield "[P4 Task " + step + "/" + Math.max(1, t.totalTasks()) + "]";
            }
            case IDLE -> t.paused() ? "[Paused]" : "[Idle]";
        };
    }
}
