package dev.baritonequeue;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain view-model for script progress, step segments, Baritone controller, estimates, and player telemetry.
 * Shared by the vanilla {@link ProgressView} overlay/editor and the Dear ImGui docked dashboard.
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

    /** Captures a live snapshot of runner progress and player telemetry on the client thread. */
    public static Snapshot snapshot() {
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

        StringBuilder tail = new StringBuilder(pctText);
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

        List<String> interrupts = r != null ? r.interrupts() : List.of();

        return new Snapshot(
                running, line, curStep, segments.size(), overallPct,
                actionsDone, elapsedMillis, elapsedText, controller, currentCmd,
                known, frac, pctText, leftText, etaText, tail.toString(), stallWarn,
                segments, health, maxHealth, food, saturation, px, py, pz, posText, interrupts
        );
    }
}
