package dev.baritonequeue;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

import static dev.baritonequeue.Config.cfg;

/**
 * Runs the script. The program runs on a fiber; whenever it waits for an action (a Baritone task, a chest trip ...)
 * this class drives the action on the game thread, tick by tick, and wakes the program when it is finished.
 * "on" blocks are checked every half second; when one fires, the running action is suspended, the block runs, and the
 * action carries on afterwards. They nest by order: while one runs, only the "on" blocks written above it can interrupt
 * it (so a fight interrupts the walk to bed, but not the other way round). A death or a dropped connection (with the
 * respawn/reconnect helpers) suspends and resumes the same way.
 */
final class Runner {
    private boolean running;
    private Ast.Env globals;
    private Fiber main;
    private final List<Handler> handlers = new ArrayList<>();
    private Interp mainIn, condIn;

    /** An "on" block that is running; the top of the stack has control. */
    private record Frame(Handler h, Fiber f, Interp in) {}

    private final java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
    private String[] lines = new String[0];
    private int handlerTick, actions;
    private long startedAt;
    private boolean awaitRecovery;
    private int recoveryWait;
    private int stepTimeout;   // ticks; 0 = none
    private final java.util.Set<String> switchedOff = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> switchedOn = new java.util.LinkedHashSet<>();

    static final class Handler {
        final Ast.On on;
        final Ast.Env env;
        boolean wasTrue;
        int fired;
        long lastEnd = Long.MIN_VALUE / 2;   // tick when its block last finished

        Handler(Ast.On on, Ast.Env env) {
            this.on = on;
            this.env = env;
        }
    }

    // ------------------------------------------------------------------ state for the display

    boolean running() {
        return running;
    }

    /** The fiber that has control now (an "on" block while it runs, else the main program). */
    private Fiber active() {
        return stack.isEmpty() ? main : stack.peek().f();
    }

    private Interp activeIn() {
        return stack.isEmpty() ? mainIn : stack.peek().in();
    }

    Actions.Action action() {
        Fiber f = active();
        return f == null ? null : f.action;
    }

    /** The line being run (1-based), or 0. */
    int line() {
        Fiber f = active();
        if (f == null) return 0;
        if (f.action != null) return f.actionLine;
        Interp in = activeIn();
        return in == null ? 0 : in.line;
    }

    String current() {
        Fiber f = active();
        if (f == null) return "";
        String prefix = stack.isEmpty() ? "" : "[on, line " + stack.peek().h().on.line + "] ";
        if (f.action != null && f.actionText != null) return prefix + f.actionText;
        Interp in = activeIn();
        if (in != null && in.activity != null) return prefix + in.activity;
        return prefix + "line " + line();
    }

    String lineText(int line) {
        if (line < 1 || line > lines.length) return "";
        return Util.stripComment(lines[line - 1]).trim();
    }

    int actionsDone() {
        return actions;
    }

    long runningMillis() {
        return running ? System.currentTimeMillis() - startedAt : 0;
    }

    String status() {
        if (!running) return "Not running.";
        return "Running, line " + line() + ": " + current();
    }

    List<String> scriptLines() {
        return running && lines.length > 0 ? List.of(lines) : cfg.script;
    }

    List<String> interrupts() {
        if (!running) return List.of();
        List<String> out = new ArrayList<>();
        for (Frame fr : stack) {
            int ln = fr.h().on.line;
            out.add("active: line " + ln + " (" + lineText(ln) + ")");
        }
        for (Handler h : handlers) {
            int ln = h.on.line;
            out.add("armed: line " + ln + " (fired " + h.fired + "x)");
        }
        return out;
    }

    static int stallLimit() {
        return Math.max(0, cfg.stallMinutes) * 1200;
    }

    // ------------------------------------------------------------------ script controls (called from builtins)

    void setTimeout(double seconds) {
        stepTimeout = seconds <= 0 ? 0 : (int) (seconds * 20);
    }

    /** disable("escape", "sleep"): these helpers stay off while this script runs (none named = all). */
    void switchOff(List<String> names) {
        List<String> n = names.isEmpty() ? List.of("all") : names;
        if (n.contains("all")) switchedOn.clear();
        else n.forEach(switchedOn::remove);
        switchedOff.addAll(n);
        publish();
    }

    /** enable("eat", "sleep", "defence"): these helpers run by themselves while this script runs (none named = all). */
    void switchOn(List<String> names) {
        List<String> n = names.isEmpty() ? List.of("all") : names;
        if (n.contains("all")) switchedOff.clear();
        else n.forEach(switchedOff::remove);
        switchedOn.addAll(n);
        publish();
    }

    private void publish() {
        if (switchedOff.isEmpty()) System.clearProperty("baritonequeue.addons.off");
        else System.setProperty("baritonequeue.addons.off", String.join(",", switchedOff));
        if (switchedOn.isEmpty()) System.clearProperty("baritonequeue.helpers.on");
        else System.setProperty("baritonequeue.helpers.on", String.join(",", switchedOn));
    }

    void addHandler(Ast.On on, Ast.Env env) {
        for (Handler h : handlers) if (h.on == on) return;   // running the same "on" line again changes nothing
        handlers.add(new Handler(on, env));
    }

    // ------------------------------------------------------------------ start / stop

    /** Parses and starts the script; returns an error text or null. */
    String start(List<String> script) {
        if (running) return "Already running (" + current() + ").";
        dev.survivalkit.Api.resetIndexRefresh();   // index_refresh() lasts for one run
        String text = String.join("\n", script);
        List<Ast.Stmt> program;
        try {
            program = Parser.parse(text);
        } catch (Parser.ParseError e) {
            return "Line " + e.line + ": " + e.getMessage() + ScriptCheck.oldStyleHint(script, e.line);
        }
        if (program.isEmpty()) return "Nothing to run. Open the editor (default key J) and write a script.";
        lines = script.toArray(new String[0]);
        handlers.clear();
        switchedOff.clear();
        switchedOn.clear();
        System.clearProperty("baritonequeue.addons.off");
        System.clearProperty("baritonequeue.helpers.on");
        stepTimeout = 0;
        actions = 0;
        awaitRecovery = false;
        Areas.scripted.clear();
        Areas.restoreIfNeeded(text);
        globals = new Ast.Env(Builtins.root());
        main = new Fiber("queue-script", () -> Ast.run(program, mainIn, globals));
        mainIn = new Interp(this, main);
        condIn = new Interp(this, null);
        stack.clear();
        startedAt = System.currentTimeMillis();
        running = true;
        Util.msg("Script started.");
        return null;
    }

    /** Stops running; the script text itself is untouched. */
    void halt(String why) {
        if (!running) return;
        running = false;
        endAll();
        Util.warn("queue", why);
    }

    void reset() {
        running = false;
        endAll();
    }

    private void endAll() {
        QueueMod.chest.cancel();
        List<Fiber> all = new ArrayList<>();
        for (Frame fr : stack) all.add(fr.f());
        all.add(main);
        for (Fiber f : all) {
            if (f == null) continue;
            if (f.action != null) {
                try {
                    f.action.cancel();
                } catch (Throwable ignored) {
                }
            }
            f.kill();
        }
        stack.clear();
        main = null;
        handlers.clear();
        switchedOff.clear();
        switchedOn.clear();
        System.clearProperty("baritonequeue.addons.off");
        System.clearProperty("baritonequeue.helpers.on");
        System.clearProperty("baritonequeue.active");
        System.clearProperty("baritonequeue.step");
    }

    /** Ends the action that runs now (as if it had finished) and goes on with the script. */
    void skip() {
        if (!running) { Util.msg("The script is not running."); return; }
        Fiber f = active();
        if (f != null && f.action != null) {
            f.action.cancel();
            f.action.finish(false);
            Util.msg("Skipped: " + f.actionText);
        } else {
            Util.msg("Nothing to skip right now (no action is running).");
        }
    }

    // ------------------------------------------------------------------ every tick

    private long tickCounter;

    void tick() {
        tickCounter++;
        if (running) {
            System.setProperty("baritonequeue.active", "1");
            Fiber f = active();
            if (f != null && f.actionText != null && f.action != null) System.setProperty("baritonequeue.step", f.actionText);   // BuildTools tells scaffolding from real blocks with it
            else System.clearProperty("baritonequeue.step");
        } else {
            System.clearProperty("baritonequeue.active");
            System.clearProperty("baritonequeue.step");
        }
        String reason = System.getProperty("baritonequeue.halt");   // safe-area stop, water loop ...
        if (reason != null) {
            System.clearProperty("baritonequeue.halt");
            halt(reason);
        }
        if (!running) return;
        if (recovering()) return;

        if (++handlerTick % 10 == 0) checkHandlers();
        Fiber f = active();
        drive(f);
    }

    /** Runs the fiber's action one tick, or lets the fiber run on. */
    private void drive(Fiber f) {
        Actions.Action a = f.action;
        if (a != null) {
            if (!a.done) {
                a.ticks++;
                try {
                    a.tick();
                } catch (Throwable t) {
                    t.printStackTrace();
                    a.fail("The action hit an error (" + t + "). Details are in the game log.");
                }
                if (!a.done && stepTimeout > 0 && a.timed() && a.ticks > stepTimeout) {
                    Util.warn("queue", "'" + f.actionText + "' took longer than " + stepTimeout / 20 + " s, so I stopped it and go on.");
                    a.cancel();
                    a.finish(false);
                }
            }
            if (!a.done) return;
            actions++;
        }
        resume(f);
    }

    private void resume(Fiber f) {
        if (!f.resume()) {
            halt("The script did not hand back control for 15 s and was stopped (an endless loop inside a function?).");
            return;
        }
        Util.flush();
        afterResume(f);
    }

    private void afterResume(Fiber f) {
        if (!running) return;
        if (f.done) {
            if (f.failure != null) {
                failed(f, f.failure);
                return;
            }
            if (!stack.isEmpty() && f == stack.peek().f() && !f.exited) {
                stack.pop().h().lastEnd = tickCounter;
                Fiber m = active();   // what it interrupted carries on
                if (m != null && m.action != null && !m.action.done) {
                    try {
                        m.action.resume();
                    } catch (Throwable t) {
                        m.action.fail("Could not carry on with '" + m.actionText + "' (" + t + ").");
                    }
                }
                return;
            }
            running = false;
            long secs = (System.currentTimeMillis() - startedAt) / 1000;
            String summary = actions + " action" + (actions == 1 ? "" : "s") + ", " + Estimate.duration(secs * 20);
            endAll();
            Util.msg("Script finished (" + summary + ").");
            System.setProperty("baritonequeue.finished", summary + "|" + System.currentTimeMillis());
            return;
        }
        if (f.wanted != null && f.action == null) {   // the script waits for a new action: make it and start it
            Actions.Action a;
            try {
                a = f.wanted.get();
            } catch (Values.ScriptError e) {
                a = new Actions.Wait(0.05);
                a.fail(e.getMessage());
            }
            f.action = a;
            if (!a.done) {
                try {
                    a.start();
                } catch (Throwable t) {
                    t.printStackTrace();
                    a.fail("Could not start '" + f.actionText + "' (" + t + ").");
                }
            }
            // finished (or failed) at once: drive() wakes the script next tick
        }
    }

    private void failed(Fiber f, Throwable t) {
        int line = t instanceof Values.ScriptError se ? se.line : activeIn().line;
        String text = t instanceof Values.ScriptError ? t.getMessage() : "Internal error: " + t;
        if (!(t instanceof Values.ScriptError)) t.printStackTrace();
        running = false;
        endAll();
        Util.msg("PROBLEM in line " + line + (line > 0 && line <= lines.length ? " (" + lineText(line) + ")" : "") + ": " + text);
        Util.msg("The script stopped. Fix it and press Run again.");
    }

    private void checkHandlers() {
        // while an "on" block runs, only those written above it may interrupt it
        int limit = handlers.size();
        if (!stack.isEmpty()) limit = handlers.indexOf(stack.peek().h());
        for (int i = 0; i < limit; i++) {
            Handler h = handlers.get(i);
            boolean inStack = false;
            for (Frame fr : stack) if (fr.h() == h) inStack = true;
            if (inStack) continue;
            boolean now;
            condIn.resetBudget();
            try {
                now = Values.truthy(h.on.cond.eval(condIn, h.env));
            } catch (Values.ScriptError e) {
                running = false;
                endAll();
                Util.msg("PROBLEM in the 'on' condition of line " + h.on.line + ": " + e.getMessage());
                return;
            }
            // when it becomes true; and again a minute after it last ran if it is still true (no food, a mob left ...)
            boolean fire = now && (!h.wasTrue || tickCounter - h.lastEnd > 1200);
            h.wasTrue = now;
            if (!fire) continue;
            h.fired++;
            fire(h);
            return;
        }
    }

    private void fire(Handler h) {
        Fiber m = active();   // the main program, or an "on" block below this one
        if (m != null && m.action != null && !m.action.done) {
            try {
                m.action.suspend();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        Ast.Env env = new Ast.Env(h.env);
        Interp[] holder = new Interp[1];
        Fiber f = new Fiber("queue-on-" + h.on.line, () -> Ast.run(h.on.body, holder[0], env));
        holder[0] = new Interp(this, f);
        stack.push(new Frame(h, f, holder[0]));
        Util.msg("'on' in line " + h.on.line + " (" + lineText(h.on.line) + ") fired.");
    }

    // ------------------------------------------------------------------ death and reconnects

    /** Called while the connection is down: keep the script, wait for the reconnect helper to bring us back. */
    void holdForReconnect() {
        if (!running || awaitRecovery) return;
        suspendCurrent();
        awaitRecovery = true;
        recoveryWait = 0;
    }

    private void suspendCurrent() {
        Fiber f = active();
        if (f != null && f.action != null && !f.action.done) {
            try {
                f.action.suspend();
            } catch (Throwable ignored) {
            }
        }
    }

    /** After a death or a reconnect the respawn helper gets you back and then signals; the action is run again. */
    private boolean recovering() {
        var p = Minecraft.getInstance().player;
        if (p != null && p.isDeadOrDying()) {
            if (!awaitRecovery) suspendCurrent();
            awaitRecovery = true;
            recoveryWait = 0;
            return true;
        }
        if (!awaitRecovery) return false;
        if (System.getProperty("survivalkit.safe") != null) {
            System.clearProperty("survivalkit.safe");
            awaitRecovery = false;
            Fiber f = active();
            if (f != null && f.action != null && !f.action.done) {
                Util.msg("Carrying on with: " + f.actionText);
                try {
                    f.action.resume();
                } catch (Throwable t) {
                    f.action.fail("Could not carry on with '" + f.actionText + "' after the respawn (" + t + ").");
                }
            }
            return false;
        }
        if (System.getProperty("survivalkit.recovering") != null) {
            recoveryWait = 0;
        } else if (++recoveryWait > 100) {   // nobody is bringing us back
            awaitRecovery = false;
            halt("The script stopped (you died or the connection dropped, and auto-respawn/resume is off).");
        }
        return true;
    }
}
