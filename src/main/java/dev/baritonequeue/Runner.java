package dev.baritonequeue;

import dev.baritonequeue.project.GameProjectRuntime;
import dev.midtown.api.RuntimeBus;
import dev.midtown.api.RuntimeEvent;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.baritonequeue.Config.cfg;

/**
 * Runs the script. The program runs on a fiber; whenever it waits for an action (a Baritone task, a chest trip ...)
 * this class drives the action on the game thread, tick by tick, and wakes the program when it is finished.
 * "on" blocks are checked every half second; when one fires, the running action is suspended, the block runs, and the
 * action carries on afterwards. They nest by order: while one runs, only the "on" blocks written above it can interrupt
 * it (so a fight interrupts the walk to bed, but not the other way round). A death or a dropped connection (with the
 * respawn/reconnect helpers) suspends and resumes the same way.
 */
public final class Runner {
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
    private int blocksMoved;
    private int stuckCount;
    private int interruptsHandled;
    private int deaths;
    private double lastX, lastY, lastZ;
    private boolean hasLastPos;
    private boolean projectSubScript;
    private boolean externallySuspended;
    private String lastFailure;

    static final class Handler {
        final Ast.On on;
        final Ast.Env env;
        boolean wasTrue;
        int fired;
        long lastEnd = Long.MIN_VALUE / 2;   // tick when its block last finished
        long lastStart;                       // tick when its block last fired
        long rearm = 1200;                    // ticks until it may fire again while its condition stays true

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

    /** One {@code on ... end} block for the UI: its line, condition text, first body line, how often it fired,
     *  ticks since its block last finished (-1 = never), and whether it is running right now. */
    record HandlerInfo(int line, String condition, String body, int fired, long ticksSinceEnd, boolean active) {}

    List<HandlerInfo> handlerInfos() {
        if (!running) return List.of();
        java.util.Set<Handler> activeSet = new java.util.HashSet<>();
        for (Frame fr : stack) activeSet.add(fr.h());
        List<HandlerInfo> out = new ArrayList<>(handlers.size());
        for (Handler h : handlers) {
            int ln = h.on.line;
            String cond = lineText(ln);
            if (cond.startsWith("on ")) cond = cond.substring(3).trim();
            long since = h.lastEnd < 0 ? -1 : tickCounter - h.lastEnd;
            out.add(new HandlerInfo(ln, cond, lineText(ln + 1), h.fired, since, activeSet.contains(h)));
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
        RuntimeBus.setAddonsOff(switchedOff);
        RuntimeBus.setHelpersOn(switchedOn);
    }

    void addHandler(Ast.On on, Ast.Env env) {
        for (Handler h : handlers) if (h.on == on) return;   // running the same "on" line again changes nothing
        handlers.add(new Handler(on, env));
    }

    // ------------------------------------------------------------------ start / stop

    /** Parses and starts the script; returns an error text or null. */
    String start(List<String> script) {
        return start(script, Jobs.SCRIPT);
    }

    /** As start, for the script of this name (the editor tab; its ledger file is per world and name). */
    String start(List<String> script, String scriptName) {
        return startInternal(script, scriptName, null, null, false);
    }

    /**
     * Starts a project {@code SCRIPT} task with injected {@code projectPlaces} and {@code params}.
     * Suppresses benchmark emission and automatic disconnect so {@link GameProjectRuntime} owns lifecycle.
     */
    public String startWithParams(
            List<String> script,
            String scriptName,
            Map<String, Object> params,
            Map<String, Object> projectPlaces) {
        return startInternal(script, scriptName, params, projectPlaces, true);
    }

    public String lastFailure() {
        return lastFailure;
    }

    public void suspendExternal() {
        if (!running || externallySuspended) return;
        externallySuspended = true;
        suspendCurrent();
    }

    public void resumeExternal() {
        if (!running || !externallySuspended) return;
        externallySuspended = false;
        Fiber f = active();
        if (f != null && f.action != null && !f.action.done) {
            try {
                f.action.resume();
            } catch (Throwable t) {
                f.action.fail("Could not resume '" + f.actionText + "' (" + t + ").");
            }
        }
    }

    private String startInternal(
            List<String> script,
            String scriptName,
            Map<String, Object> params,
            Map<String, Object> projectPlaces,
            boolean subScript) {
        if (running) return "Already running (" + current() + ").";
        dev.survivalkit.Api.resetIndexRefresh();   // index_refresh() lasts for one run
        RuntimeBus.setRecoveryGaveUpReason(null);
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
        RuntimeBus.clearHelpers();
        stepTimeout = 0;
        actions = 0;
        blocksMoved = 0;
        stuckCount = 0;
        interruptsHandled = 0;
        deaths = 0;
        hasLastPos = false;
        projectSubScript = subScript;
        externallySuspended = false;
        lastFailure = null;
        var mc = net.minecraft.client.Minecraft.getInstance();
        var p = mc != null ? mc.player : null;
        if (p != null) {
            lastX = p.getX();
            lastY = p.getY();
            lastZ = p.getZ();
            hasLastPos = true;
        }
        awaitRecovery = false;
        if (!subScript) {
            dev.survivalkit.Api.onBeforeDisconnect = () -> { if (running) halt("Disconnected."); };
        }
        Areas.scripted.clear();
        Areas.restoreIfNeeded(text);
        if (!subScript) {
            String resume = Jobs.startRun(scriptName);   // the saved ledger of this world: what an earlier run finished
            if (resume != null) Util.msg(resume);
            java.nio.file.Path telDir = (mc != null) ? QueueMod.projectGameDir().resolve("manhattan-telemetry") : null;
            RuntimeBus.startRunTelemetry(
                    telDir,
                    "script-" + (scriptName == null || scriptName.isBlank() ? "main" : scriptName) + "-" + System.currentTimeMillis());
            RuntimeBus.emit(RuntimeEvent.Type.RUN_START, RuntimeEvent.Category.SYSTEM,
                    "Runner", "Started script " + (scriptName == null ? "main" : scriptName));
        }
        globals = new Ast.Env(Builtins.root());
        globals.define("chunk_needs", Values.newMap());
        globals.define("chunk_id", -1.0);
        if (projectPlaces != null) {
            for (var entry : projectPlaces.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    globals.define(entry.getKey(), toScriptValue(entry.getValue()));
                }
            }
        }
        if (params != null) {
            for (var entry : params.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    globals.define(entry.getKey(), toScriptValue(entry.getValue()));
                }
            }
        }
        main = new Fiber("queue-script", () -> Ast.run(program, mainIn, globals));
        mainIn = new Interp(this, main);
        condIn = new Interp(this, null);
        stack.clear();
        startedAt = System.currentTimeMillis();
        running = true;
        Util.msg(subScript ? "Project script started: " + scriptName : "Script started.");
        return null;
    }

    private static Object toScriptValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String || v instanceof Values.Pos) return v;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof int[] arr && arr.length == 3) {
            return new Values.Pos(arr[0], arr[1], arr[2]);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(toScriptValue(item));
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<Object, Object> out = Values.newMap();
            for (var e : map.entrySet()) {
                out.put(toScriptValue(e.getKey()), toScriptValue(e.getValue()));
            }
            return out;
        }
        return String.valueOf(v);
    }

    /** Stops running; the script text itself is untouched. */
    void halt(String why) {
        if (!running) return;
        long elapsedMs = System.currentTimeMillis() - startedAt;
        boolean wasSub = projectSubScript;
        lastFailure = why;
        if (!wasSub && why != null && !why.toLowerCase(java.util.Locale.ROOT).contains("stopped")) {
            IncidentBundle.captureAndWrite("script_halted", why);
        }
        running = false;
        externallySuspended = false;
        endAll();
        if (!wasSub) {
            RuntimeBus.emit(RuntimeEvent.Type.RUN_END, RuntimeEvent.Category.SYSTEM, "Runner", why == null ? "Halted" : why);
            RuntimeBus.stopRunTelemetry();
        }
        Util.warn("queue", why);
        if (!wasSub) emitBenchmark(false, elapsedMs);
    }

    void reset() {
        running = false;
        externallySuspended = false;
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
        RuntimeBus.clearHelpers();
        RuntimeBus.setScriptActive(GameProjectRuntime.instance().running());
        RuntimeBus.setCurrentStep(null);
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
        boolean projectRunning = GameProjectRuntime.instance().running();
        if (running) {
            RuntimeBus.setScriptActive(true);
            Fiber f = active();
            if (f != null && f.actionText != null && f.action != null) RuntimeBus.setCurrentStep(f.actionText);   // BuildTools tells scaffolding from real blocks with it
            else RuntimeBus.setCurrentStep(null);
            if (!projectSubScript) {
                RuntimeEvent.Category cat = !stack.isEmpty()
                        ? RuntimeEvent.Category.SURVIVAL
                        : dev.midtown.api.EngineSelector.current().status() == dev.midtown.api.EngineStatus.STUCK
                        ? RuntimeEvent.Category.STUCK
                        : dev.midtown.api.EngineSelector.current().status() == dev.midtown.api.EngineStatus.PLANNING
                        ? RuntimeEvent.Category.REPLAN
                        : RuntimeEvent.Category.WORK;
                RuntimeBus.sampleCategory(cat);
            }
        } else if (!projectRunning) {
            RuntimeBus.setScriptActive(false);
            RuntimeBus.setCurrentStep(null);
        }
        String reason = RuntimeBus.consumeHaltReason();   // safe-area stop, water loop ...
        if (reason != null) {
            if (running) halt(reason);
            else if (projectRunning) GameProjectRuntime.instance().stopQueue(reason);
        }
        if (!running || externallySuspended) return;
        if (recovering()) return;
        if (Shaft.climbing()) return;   // on the way out of the hole by the stairs: the script waits

        if (++handlerTick % 10 == 0) checkHandlers();
        if (Shaft.climbing()) return;   // an "on" block just fired inside the hole: its first line waits for the climb
        var mc = net.minecraft.client.Minecraft.getInstance();
        var p = mc == null ? null : mc.player;
        if (p != null) {
            if (hasLastPos) {
                double dx = p.getX() - lastX, dy = p.getY() - lastY, dz = p.getZ() - lastZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist >= 1.0) {
                    blocksMoved += (int) Math.floor(dist);
                    lastX = p.getX();
                    lastY = p.getY();
                    lastZ = p.getZ();
                }
            } else {
                lastX = p.getX();
                lastY = p.getY();
                lastZ = p.getZ();
                hasLastPos = true;
            }
        }
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
                    stuckCount++;
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
                Frame popped = stack.pop();
                popped.h().lastEnd = tickCounter;
                // a block that ended at once did nothing (no food, nothing to fight): wait longer before the next try,
                // up to 10 minutes, instead of interrupting the work every minute
                Handler ph = popped.h();
                ph.rearm = tickCounter - ph.lastStart < 100 ? Math.min(12000, ph.rearm * 2) : 1200;
                if (referencesVar(popped.h().on.cond, "chunk_needs")) {
                    popped.h().env.assign("chunk_needs", Values.newMap());
                }
                interruptsHandled++;
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
            boolean wasSub = projectSubScript;
            running = false;
            externallySuspended = false;
            long elapsedMs = System.currentTimeMillis() - startedAt;
            long secs = elapsedMs / 1000;
            String summary = actions + " action" + (actions == 1 ? "" : "s") + ", " + Estimate.duration(secs * 20);
            endAll();
            Util.msg((wasSub ? "Project script finished (" : "Script finished (") + summary + ").");
            if (!wasSub) {
                String stamped = summary + "|" + System.currentTimeMillis();
                RuntimeBus.setFinishedSummary(stamped);
                System.setProperty("baritonequeue.finished", stamped);
                emitBenchmark(true, elapsedMs);
                if (Config.cfg.logoffOnDone) {
                    dev.survivalkit.Api.disconnect("Script complete.");
                }
            }
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
        boolean wasSub = projectSubScript;
        lastFailure = "Line " + line + ": " + text;
        if (!wasSub) {
            IncidentBundle.captureAndWrite("script_error", lastFailure);
        }
        running = false;
        externallySuspended = false;
        endAll();
        if (!wasSub) {
            RuntimeBus.emit(RuntimeEvent.Type.RUN_END, RuntimeEvent.Category.SYSTEM, "Runner", "ERROR: " + lastFailure);
            RuntimeBus.stopRunTelemetry();
        }
        Util.msg("PROBLEM in line " + line + (line > 0 && line <= lines.length ? " (" + lineText(line) + ")" : "") + ": " + text);
        Util.msg("The script stopped. Fix it and press Run again.");
        if (!wasSub && Config.cfg.logoffOnError) {
            dev.survivalkit.Api.disconnect("Script stopped: " + text);
        }
    }

    private void checkHandlers() {
        // while an "on" block runs, only those written above it may interrupt it
        int limit = handlers.size();
        if (!stack.isEmpty()) limit = handlers.indexOf(stack.peek().h());
        for (int i = 0; i < limit; i++) {
            Handler h = handlers.get(i);
            if (referencesVar(h.on.cond, "chunk_needs")) continue;
            boolean inStack = false;
            for (Frame fr : stack) if (fr.h() == h) inStack = true;
            if (inStack) continue;
            boolean now;
            condIn.resetBudget();
            try {
                now = Values.truthy(h.on.cond.eval(condIn, h.env));
            } catch (Values.ScriptError e) {
                boolean wasSub = projectSubScript;
                lastFailure = "Line " + h.on.line + ": " + e.getMessage();
                running = false;
                externallySuspended = false;
                endAll();
                Util.msg("PROBLEM in the 'on' condition of line " + h.on.line + ": " + e.getMessage());
                if (!wasSub && Config.cfg.logoffOnError) {
                    dev.survivalkit.Api.disconnect("Script stopped: " + e.getMessage());
                }
                return;
            }
            // when it becomes true; and again a minute after it last ran if it is still true (no food, a mob left ...)
            boolean fire = now && (!h.wasTrue || tickCounter - h.lastEnd > h.rearm);
            h.wasTrue = now;
            if (!now) h.rearm = 1200;
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
        h.lastStart = tickCounter;
        Ast.Env env = new Ast.Env(h.env);
        Interp[] holder = new Interp[1];
        Fiber f = new Fiber("queue-on-" + h.on.line, () -> Ast.run(h.on.body, holder[0], env));
        holder[0] = new Interp(this, f);
        stack.push(new Frame(h, f, holder[0]));
        RuntimeBus.emit(RuntimeEvent.Type.INTERRUPT_ENTER, RuntimeEvent.Category.SURVIVAL,
                "Runner", "on " + lineText(h.on.line));
        Util.msg("'on' in line " + h.on.line + " (" + lineText(h.on.line) + ") fired.");
    }

    /** Fires an active {@code on} handler referencing {@code chunk_needs}, populating {@code chunk_needs} and {@code chunk_id}. */
    public boolean triggerChunkNeeds(java.util.Map<String, Integer> deficit, ChunkGrid.ColumnBox chunk) {
        if (!running || deficit == null || deficit.isEmpty() || handlers.isEmpty()) return false;
        int limit = handlers.size();
        if (!stack.isEmpty()) limit = handlers.indexOf(stack.peek().h());
        java.util.Map<Object, Object> needsMap = Values.newMap();
        deficit.forEach((k, v) -> needsMap.put(k, v == null ? 0.0 : v.doubleValue()));
        double chunkId = chunk != null ? (double) chunk.index() : -1.0;

        for (int i = 0; i < limit; i++) {
            Handler h = handlers.get(i);
            if (!referencesVar(h.on.cond, "chunk_needs")) continue;
            boolean inStack = false;
            for (Frame fr : stack) if (fr.h() == h) { inStack = true; break; }
            if (inStack) continue;

            h.env.assign("chunk_needs", needsMap);
            h.env.assign("chunk_id", chunkId);
            condIn.resetBudget();
            boolean matches;
            try {
                matches = Values.truthy(h.on.cond.eval(condIn, h.env));
            } catch (Values.ScriptError e) {
                boolean wasSub = projectSubScript;
                lastFailure = "Line " + h.on.line + ": " + e.getMessage();
                running = false;
                externallySuspended = false;
                endAll();
                Util.msg("PROBLEM in the 'on' condition of line " + h.on.line + ": " + e.getMessage());
                if (!wasSub && Config.cfg.logoffOnError) {
                    dev.survivalkit.Api.disconnect("Script stopped: " + e.getMessage());
                }
                return false;
            }
            if (!matches) continue;
            h.wasTrue = true;
            h.fired++;
            fire(h);
            return true;
        }
        return false;
    }

    private static boolean referencesVar(Ast.Expr e, String name) {
        if (e == null) return false;
        if (e instanceof Ast.Var v) return name.equals(v.name);
        if (e instanceof Ast.Unary u) return referencesVar(u.e, name);
        if (e instanceof Ast.Logic l) return referencesVar(l.a, name) || referencesVar(l.b, name);
        if (e instanceof Ast.Binary b) return referencesVar(b.a, name) || referencesVar(b.b, name);
        if (e instanceof Ast.Call c) {
            if (referencesVar(c.callee, name)) return true;
            for (Ast.Expr arg : c.args) if (referencesVar(arg, name)) return true;
            return false;
        }
        if (e instanceof Ast.Index ix) return referencesVar(ix.obj, name) || referencesVar(ix.index, name);
        if (e instanceof Ast.Member m) return referencesVar(m.obj, name);
        if (e instanceof Ast.Concat cc) {
            for (Ast.Expr p : cc.parts) if (referencesVar(p, name)) return true;
            return false;
        }
        if (e instanceof Ast.ListLit ll) {
            for (Ast.Expr item : ll.items) if (referencesVar(item, name)) return true;
            return false;
        }
        if (e instanceof Ast.MapLit ml) {
            for (Ast.Expr k : ml.keys) if (referencesVar(k, name)) return true;
            for (Ast.Expr v : ml.values) if (referencesVar(v, name)) return true;
            return false;
        }
        return false;
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
        var mc = Minecraft.getInstance();
        var p = mc == null ? null : mc.player;
        if (p != null && p.isDeadOrDying()) {
            if (!awaitRecovery) {
                deaths++;
                suspendCurrent();
            }
            awaitRecovery = true;
            recoveryWait = 0;
            return true;
        }
        if (!awaitRecovery) return false;
        if (RuntimeBus.consumeSafeAfterRespawn()) {
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
        if (RuntimeBus.isRecovering()) {
            recoveryWait = 0;
        } else if (++recoveryWait > 100) {   // nobody is bringing us back
            awaitRecovery = false;
            String why = RuntimeBus.consumeRecoveryGaveUpReason();   // SurvivalKit tried to bring us back and gave up
            halt(why != null ? "The script stopped after dying: " + why
                    : "The script stopped (you died or the connection dropped, and auto-respawn/resume is off).");
        }
        return true;
    }

    private void emitBenchmark(boolean pass, long elapsedMs) {
        String scenario = System.getProperty("manhattan.benchmark");
        if (scenario == null || scenario.isBlank()) {
            scenario = System.getProperty("manhattan.scenario");
        }
        if (scenario != null && !scenario.isBlank()) {
            String engine = dev.midtown.api.EngineSelector.selectedName();
            dev.midtown.api.BenchmarkLogger.emit(
                    engine,
                    scenario,
                    pass,
                    elapsedMs,
                    blocksMoved,
                    stuckCount,
                    interruptsHandled,
                    deaths
            );
        }
    }
}
