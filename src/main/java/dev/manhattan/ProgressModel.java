package dev.manhattan;

import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.PriorityScheduler;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import dev.manhattan.project.TaskQueue;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain view-model for script progress, step segments, Baritone controller, estimates, and player telemetry.
 * Read by the workspace tiles ({@link Widgets}) and the closed-state {@link Overlay}.
 */
public final class ProgressModel {
    private ProgressModel() {}

    public enum State {
        DONE,
        RUNNING,
        PENDING
    }

    public record Segment(int line, String text, State state, double fraction) {}

    public record Snapshot(
            boolean running,
            int line,
            int currentStep,
            int totalSteps,
            int overallPercent,
            int actionsDone,
            long elapsedMillis,
            String elapsedText,
            String controller,
            String currentCommand,
            boolean stepKnown,
            double stepFraction,
            String stepPercentText,
            String stepLeftText,
            String stepEtaText,
            String stepTailText,
            String stallWarning,
            List<Segment> segments,
            float health,
            float maxHealth,
            int food,
            float saturation,
            int posX,
            int posY,
            int posZ,
            String posText,
            List<String> interrupts
    ) {}

    /** Computes the segmented step list for a script (usable both in-game and in offline unit tests). */
    public static List<Segment> computeSegments(List<String> scriptLines, boolean running, int currentLine, double runningFraction) {
        List<Segment> raw = new ArrayList<>();
        if (scriptLines != null) {
            for (int i = 0; i < scriptLines.size(); i++) {
                String clean = Util.stripComment(scriptLines.get(i)).trim();
                if (clean.isEmpty() || clean.equals("end") || clean.equals("else")) continue;
                raw.add(new Segment(i + 1, clean, State.PENDING, 0.0));
            }
        }
        if (!running || raw.isEmpty()) return raw;

        int activeIdx = -1;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).line() <= currentLine) {
                activeIdx = i;
            } else {
                break;
            }
        }
        if (activeIdx < 0 && currentLine > 0) activeIdx = 0;

        double fill = runningFraction >= 0.0 ? Math.max(0.0, Math.min(1.0, runningFraction)) : 0.5;
        List<Segment> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Segment s = raw.get(i);
            if (i < activeIdx) {
                out.add(new Segment(s.line(), s.text(), State.DONE, 1.0));
            } else if (i == activeIdx) {
                out.add(new Segment(s.line(), s.text(), State.RUNNING, fill));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** Computes overall script completion percent (0..100) from the step segments. */
    public static int computeOverallPercent(List<Segment> segments, boolean running) {
        if (!running || segments.isEmpty()) return 0;
        double sum = 0.0;
        for (Segment s : segments) {
            if (s.state() == State.DONE) sum += 1.0;
            else if (s.state() == State.RUNNING) sum += Math.max(0.0, Math.min(1.0, s.fraction()));
        }
        return (int) Math.round((sum / segments.size()) * 100.0);
    }

    /** Captures a live snapshot of runner (or active project queue) progress and player telemetry on the client thread. */
    public static Snapshot snapshot() {
        GameProjectRuntime rt = safeProjectRuntime();
        if (rt != null && rt.running()) {
            return snapshotProject(rt);
        }

        Runner r = QueueMod.runner;
        boolean running = r != null && r.running();
        int line = running ? r.line() : 0;
        int actionsDone = r == null ? 0 : r.actionsDone();
        long elapsedMillis = running ? r.runningMillis() : 0L;
        String elapsedText = Estimate.duration(elapsedMillis / 50L);
        String controller = RunnerView.controller();
        String currentCmd = running ? r.current() : "";

        Actions.Action a = running ? r.action() : null;
        Estimate e = a == null ? new Estimate() : a.estimate;
        boolean known = e.known();
        double frac = known ? Math.max(0.0, Math.min(1.0, e.fraction())) : -1.0;
        String pctText = known ? Math.round(frac * 100.0) + "%" : (running ? "running" : "idle");
        String leftText = running ? e.left() : "";
        long etaTicks = running ? e.etaTicks() : -1L;
        String etaText = etaTicks >= 0 ? "about " + Estimate.duration(etaTicks) + " left" : "";

        String chunk = running ? Jobs.chunkText() : null;
        StringBuilder tail = new StringBuilder(chunk == null || chunk.isEmpty() ? pctText : chunk + "  -  " + pctText);
        if (!leftText.isEmpty()) tail.append("  -  ").append(leftText);
        if (!etaText.isEmpty()) tail.append("  -  ").append(etaText);
        else if (!known && a != null) tail.append("  -  ").append(Estimate.duration(a.ticks));

        String stallWarn = "";
        int limit = Runner.stallLimit();
        if (a instanceof Actions.BaritoneCommand bc && limit > 0 && bc.noProgressTicks() > 1200) {
            stallWarn = "No progress for " + Estimate.duration(bc.noProgressTicks()) + " - skipping after " + Estimate.duration(limit);
        }

        List<String> lines = r != null ? r.scriptLines() : Config.cfg.script;
        List<Segment> segments = computeSegments(lines, running, line, frac);
        int curStep = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).state() == State.RUNNING) {
                curStep = i + 1;
                break;
            }
        }
        int overallPct = computeOverallPercent(segments, running);

        return buildSnapshot(running, line, curStep, segments.size(), overallPct,
                actionsDone, elapsedMillis, elapsedText, controller, currentCmd,
                known, frac, pctText, leftText, etaText, tail.toString(), stallWarn,
                segments, r != null ? r.interrupts() : List.of());
    }

    private static Snapshot snapshotProject(GameProjectRuntime rt) {
        PriorityScheduler.Telemetry t = rt.telemetry();
        Project proj = rt.runningProject();
        if (proj == null) proj = rt.activeProject();
        Runner r = QueueMod.runner;
        boolean scriptRunning = r != null && r.running();

        int line = scriptRunning ? r.line() : 0;
        Actions.Action a = scriptRunning ? r.action() : null;
        Estimate e = a == null ? new Estimate() : a.estimate;
        boolean scriptKnown = scriptRunning && e.known();
        double scriptFrac = scriptKnown ? Math.max(0.0, Math.min(1.0, e.fraction())) : 0.5;

        List<Segment> segments = new ArrayList<>();
        int curStep = 0;
        if (proj != null && proj.tasks != null) {
            int taskNum = 0;
            String activeTaskId = t != null ? t.taskId() : null;
            TaskQueue.TaskLedger projLedger = proj.id != null ? rt.loadLedgerCached(proj.id) : null;
            for (ProjectTask pt : proj.tasks) {
                if (pt == null || !pt.enabled) continue;
                taskNum++;
                TaskQueue.TaskLedger.State st = TaskQueue.TaskLedger.State.PENDING;
                if (pt.id != null) {
                    if (projLedger != null) {
                        var ev = projLedger.get(pt.id);
                        if (ev != null && ev.state() != null) st = ev.state();
                    } else {
                        st = rt.taskState(pt.id);
                    }
                }
                State segState;
                double segFrac;
                if (activeTaskId != null && activeTaskId.equals(pt.id)) {
                    segState = State.RUNNING;
                    segFrac = scriptFrac;
                    curStep = taskNum;
                } else if (st == TaskQueue.TaskLedger.State.DONE) {
                    segState = State.DONE;
                    segFrac = 1.0;
                } else if (st == TaskQueue.TaskLedger.State.RUNNING) {
                    segState = State.RUNNING;
                    segFrac = scriptFrac;
                    if (curStep == 0) curStep = taskNum;
                } else {
                    segState = State.PENDING;
                    segFrac = 0.0;
                }
                segments.add(new Segment(taskNum, pt.displayLabel(), segState, segFrac));
            }
        }

        int totalSteps = segments.size();
        if (totalSteps == 0 && t != null && t.totalTasks() > 0) {
            totalSteps = t.totalTasks();
        }
        if (curStep == 0 && totalSteps > 0 && t != null) {
            curStep = Math.min(totalSteps, t.doneTasks() + 1);
        }

        int overallPct = segments.isEmpty()
                ? (totalSteps > 0 && t != null ? (int) Math.round((t.doneTasks() / (double) totalSteps) * 100.0) : 0)
                : computeOverallPercent(segments, true);

        long elapsedMillis = t != null ? t.elapsedMillis() : 0L;
        String elapsedText = Estimate.duration(elapsedMillis / 50L);
        String controller = RunnerView.controller();
        int actionsDone = t != null ? t.doneTasks() : 0;

        String laneTag = formatProjectLane(t, curStep, totalSteps);
        String detail = (t != null && t.detail() != null) ? t.detail() : "";
        String taskLabel = (t != null && t.taskLabel() != null) ? t.taskLabel() : "";

        String currentCmd;
        boolean known;
        double frac;
        String pctText;
        String leftText;
        String etaText;
        String tailText;
        String stallWarn = "";

        if (scriptRunning) {
            String rCmd = r.current();
            currentCmd = laneTag + " " + (rCmd.isEmpty() ? taskLabel : rCmd);
            known = scriptKnown;
            frac = scriptKnown ? scriptFrac : -1.0;
            pctText = scriptKnown ? Math.round(scriptFrac * 100.0) + "%" : "running";
            leftText = e.left();
            long etaTicks = e.etaTicks();
            etaText = etaTicks >= 0 ? "about " + Estimate.duration(etaTicks) + " left" : "";
            String chunk = Jobs.chunkText();
            StringBuilder tail = new StringBuilder(laneTag);
            if (chunk != null && !chunk.isEmpty()) tail.append("  -  ").append(chunk);
            tail.append("  -  ").append(pctText);
            if (!leftText.isEmpty()) tail.append("  -  ").append(leftText);
            if (!etaText.isEmpty()) tail.append("  -  ").append(etaText);
            else if (!scriptKnown && a != null) tail.append("  -  ").append(Estimate.duration(a.ticks));
            tailText = tail.toString();

            int limit = Runner.stallLimit();
            if (a instanceof Actions.BaritoneCommand bc && limit > 0 && bc.noProgressTicks() > 1200) {
                stallWarn = "No progress for " + Estimate.duration(bc.noProgressTicks()) + " - skipping after " + Estimate.duration(limit);
            }
        } else {
            String body = !detail.isEmpty() ? detail : taskLabel;
            currentCmd = body.isEmpty() ? laneTag : laneTag + " " + body;
            known = totalSteps > 0;
            frac = totalSteps > 0 ? Math.max(0.0, Math.min(1.0, (t != null ? t.doneTasks() : 0) / (double) totalSteps)) : -1.0;
            pctText = overallPct + "%";
            leftText = totalSteps > 0 ? (actionsDone + "/" + totalSteps + " tasks") : "";
            etaText = "";
            StringBuilder tail = new StringBuilder(laneTag);
            if (!taskLabel.isEmpty() && !taskLabel.equals(detail)) tail.append("  -  ").append(taskLabel);
            if (!detail.isEmpty()) tail.append("  -  ").append(detail);
            if (!leftText.isEmpty()) tail.append("  -  ").append(leftText);
            tailText = tail.toString();
        }

        return buildSnapshot(true, line, curStep, totalSteps, overallPct,
                actionsDone, elapsedMillis, elapsedText, controller, currentCmd,
                known, frac, pctText, leftText, etaText, tailText, stallWarn,
                segments, r != null ? r.interrupts() : List.of());
    }

    private static String formatProjectLane(PriorityScheduler.Telemetry t, int curStep, int totalSteps) {
        if (t == null || t.lane() == null) return "[Project]";
        return switch (t.lane()) {
            case P0_EMERGENCY -> "[P0 Emergency]";
            case P1_COMBAT -> "[P1 Combat]";
            case P2_VITALS -> "[P2 Vitals]";
            case P3_LOGISTICS -> "[P3 Base Trip]";
            case P4_WORK -> "[P4 Task " + Math.max(1, curStep) + "/" + Math.max(1, totalSteps) + "]";
            case IDLE -> t.paused() ? "[Paused]" : "[Idle]";
        };
    }

    private static GameProjectRuntime safeProjectRuntime() {
        try {
            return GameProjectRuntime.instance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Snapshot buildSnapshot(
            boolean running, int line, int curStep, int totalSteps, int overallPct,
            int actionsDone, long elapsedMillis, String elapsedText, String controller, String currentCmd,
            boolean known, double frac, String pctText, String leftText, String etaText, String tailText, String stallWarn,
            List<Segment> segments, List<String> interrupts) {
        float health = 20f, maxHealth = 20f, saturation = 5f;
        int food = 20, px = 0, py = 0, pz = 0;
        String posText = "-";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                var p = mc.player;
                health = p.getHealth();
                maxHealth = p.getMaxHealth();
                food = p.getFoodData().getFoodLevel();
                saturation = p.getFoodData().getSaturationLevel();
                px = p.getBlockX();
                py = p.getBlockY();
                pz = p.getBlockZ();
                posText = px + ", " + py + ", " + pz;
            }
        } catch (Throwable ignored) {
        }

        return new Snapshot(
                running, line, curStep, totalSteps, overallPct,
                actionsDone, elapsedMillis, elapsedText, controller, currentCmd,
                known, frac, pctText, leftText, etaText, tailText, stallWarn,
                segments, health, maxHealth, food, saturation, px, py, pz, posText, interrupts
        );
    }
}
