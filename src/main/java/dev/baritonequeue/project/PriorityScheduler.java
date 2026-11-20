package dev.baritonequeue.project;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java tick-driven priority scheduler for Manhattan Projects.
 *
 * <p>Manages a strict priority stack {@code P0_EMERGENCY > P1_COMBAT > P2_VITALS > P3_LOGISTICS > P4_WORK}
 * where higher-priority lanes suspend lower-priority actions and resume them upon completion. Also enforces
 * the pre-dusk sleep gate, per-task {@link ScopedOverrides} apply/restore lifecycle, {@link ProjectTask.PartialPolicy},
 * {@link ProjectTask.ErrorPolicy}, and live queue mutations. Zero Minecraft imports.
 */
public final class PriorityScheduler {

    public enum Lane {
        IDLE(0),
        P0_EMERGENCY(5),
        P1_COMBAT(4),
        P2_VITALS(3),
        P3_LOGISTICS(2),
        P4_WORK(1);

        private final int priority;

        Lane(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    public record Telemetry(
            boolean running,
            boolean paused,
            String projectId,
            String projectName,
            Lane lane,
            String taskId,
            String taskLabel,
            int taskIndex,
            int totalTasks,
            int doneTasks,
            String detail,
            long elapsedMillis,
            String lastError
    ) {
    }

    /** Lifecycle handle for an active action running in one lane of the priority stack. */
    public interface ActionHandle {
        boolean isDone();

        String error();

        int remainingWork();

        void cancel();

        void suspend();

        void resume();

        void tick();

        default Map<String, Integer> materialDeficit() {
            return Map.of();
        }
    }

    /** Environment seam connecting the pure-Java scheduler to Minecraft or a headless test harness. */
    public interface Env {
        boolean isRecovering();

        boolean isEmergencyNeeded();

        int threats(double range);

        double health();

        int food();

        boolean isBedTime();

        /** Ticks remaining until dusk, or {@code -1} if unknown or in Nether/End. */
        int ticksUntilDusk();

        LogisticsPlanner.InventoryView inventoryView();

        default boolean canEatNow() { return true; }

        default boolean canSleepNow() { return true; }

        default boolean canStartBaseTrip() { return true; }

        ActionHandle startEmergency();

        ActionHandle startDefend();

        ActionHandle startEat();

        ActionHandle startSleep();

        ActionHandle startBaseTrip(LogisticsPlanner.BaseTripPlan plan);

        ActionHandle startTask(ProjectTask task, ScopedOverrides overrides);

        void applyOverrides(ScopedOverrides overrides);

        void restoreOverrides();

        void onLog(String message);

        void onQueueComplete(boolean success, String summary, boolean logoff);
    }

    private static final class Frame {
        final Lane lane;
        final String kind;
        final ActionHandle handle;
        final ProjectTask task;
        final ScopedOverrides overrides;
        boolean overridesApplied;
        boolean suspended;
        String detail;

        Frame(Lane lane, String kind, ActionHandle handle, ProjectTask task,
              ScopedOverrides overrides, boolean overridesApplied, String detail) {
            this.lane = lane;
            this.kind = kind;
            this.handle = handle;
            this.task = task;
            this.overrides = overrides;
            this.overridesApplied = overridesApplied;
            this.detail = detail;
        }
    }

    private final Env env;
    private final Deque<Frame> stack = new ArrayDeque<>();

    private Project project;
    private TaskQueue queue;
    private boolean running;
    private boolean paused;
    private long startedAtMillis;
    private String lastError;

    public PriorityScheduler(Env env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public void start(Project project, TaskQueue.TaskLedger ledger) {
        Objects.requireNonNull(project, "project");
        if (running || !stack.isEmpty()) {
            cancelAllFrames();
        }
        project.ensureInitialized();
        this.project = project;
        this.queue = new TaskQueue(project, ledger);
        this.running = true;
        this.paused = false;
        this.lastError = null;
        this.startedAtMillis = System.currentTimeMillis();
        env.onLog("Started project '" + (project.name != null ? project.name : project.id) + "'.");
    }

    public void stop(String reason) {
        boolean wasRunning = this.running;
        cancelAllFrames();
        this.running = false;
        this.paused = false;
        if (wasRunning) {
            String suffix = (reason != null && !reason.isBlank()) ? ": " + reason : ".";
            env.onLog("Stopped project" + suffix);
        }
    }

    public void pause() {
        if (!running || paused) return;
        paused = true;
        Frame top = stack.peek();
        if (top != null && !top.suspended) {
            top.suspended = true;
            top.handle.suspend();
        }
        env.onLog("Scheduler paused.");
    }

    public void resume() {
        if (!running || !paused) return;
        paused = false;
        Frame top = stack.peek();
        if (top != null && top.suspended) {
            top.suspended = false;
            top.handle.resume();
        }
        env.onLog("Scheduler resumed.");
    }

    /** Cancels and skips the currently active P4 work task (even if preempted by P0..P3). */
    public boolean skipCurrent() {
        Frame work = findWorkFrame();
        if (work == null || work.task == null) return false;
        String taskId = work.task.id;
        String label = work.task.displayLabel();
        removeAndCancelWorkFrame(work);
        if (queue != null) {
            queue.skip(taskId);
        }
        env.onLog("Skipped current task: " + label);
        return true;
    }

    public ProjectTask enqueue(ProjectTask task) {
        ensureQueueLoaded();
        return queue.add(task);
    }

    public ProjectTask enqueueAt(int index, ProjectTask task) {
        ensureQueueLoaded();
        return queue.insertAt(index, task);
    }

    public boolean remove(String taskId) {
        if (queue == null || taskId == null) return false;
        cancelWorkFrameIfMatches(taskId);
        return queue.remove(taskId);
    }

    public boolean move(int fromIdx, int toIdx) {
        if (queue == null) return false;
        return queue.move(fromIdx, toIdx);
    }

    public boolean skipTask(String taskId) {
        if (queue == null || taskId == null) return false;
        cancelWorkFrameIfMatches(taskId);
        return queue.skip(taskId);
    }

    public boolean retryTask(String taskId) {
        if (queue == null) return false;
        return queue.retry(taskId);
    }

    public int resetQueue(String taskIdOrNull) {
        if (queue == null) return 0;
        if (taskIdOrNull == null) {
            Frame work = findWorkFrame();
            if (work != null) removeAndCancelWorkFrame(work);
        } else {
            cancelWorkFrameIfMatches(taskIdOrNull);
        }
        return queue.reset(taskIdOrNull);
    }

    public int resetQueue() {
        return resetQueue(null);
    }

    public TaskQueue queue() {
        return queue;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public Lane currentLane() {
        Frame top = stack.peek();
        return top != null ? top.lane : Lane.IDLE;
    }

    public void tick() {
        if (!running || paused || project == null || queue == null) return;
        if (env.isRecovering()) return;

        // 1. Drain any already-completed top frame before evaluating triggers
        if (!stack.isEmpty() && stack.peek().handle.isDone()) {
            Frame done = stack.pop();
            onFrameCompleted(done);
            if (!running || paused) return;
        }

        // 2. Evaluate priority lanes strictly from P0 down to P4
        evaluatePreemptionsAndWork();
        if (!running || paused) return;

        // 3. Tick the active top frame and handle immediate completion
        Frame top = stack.peek();
        if (top != null) {
            if (!top.handle.isDone()) {
                top.handle.tick();
            }
            if (top.handle.isDone()) {
                stack.pop();
                onFrameCompleted(top);
            }
        }
    }

    public Telemetry telemetry() {
        Frame top = stack.peek();
        Frame work = findWorkFrame();
        String taskId = work != null && work.task != null ? work.task.id : null;
        String taskLabel = work != null && work.task != null ? work.task.displayLabel() : null;
        int taskIndex = (queue != null && taskId != null) ? queue.indexOf(taskId) : -1;
        int totalTasks = queue != null ? queue.totalEnabledCount() : 0;
        int doneTasks = queue != null ? queue.doneCount() : 0;
        Lane lane = top != null ? top.lane : Lane.IDLE;
        String detail;
        if (top != null && top.detail != null) {
            detail = top.detail;
        } else if (paused) {
            detail = "Paused";
        } else if (running) {
            detail = "Idle";
        } else {
            detail = "Stopped";
        }
        long elapsed = running ? Math.max(0L, System.currentTimeMillis() - startedAtMillis) : 0L;
        return new Telemetry(
                running,
                paused,
                project != null ? project.id : null,
                project != null ? project.name : null,
                lane,
                taskId,
                taskLabel,
                taskIndex,
                totalTasks,
                doneTasks,
                detail,
                elapsed,
                lastError
        );
    }

    // ------------------------------------------------------------------ internal priority evaluation

    private void evaluatePreemptionsAndWork() {
        Project.SurvivalPolicy survival = project.survival;
        Project.BasePlaces places = project.places;
        ScopedOverrides overrides = activeTaskOverrides();

        // P0: Emergency (lava / drowning / trapped escape)
        if (topPriority() < Lane.P0_EMERGENCY.priority()) {
            boolean suppressed = overrides != null
                    && (overrides.isSuppressed("escape") || overrides.isSuppressed("emergency"));
            if (survival.escapeEnabled && !suppressed && env.isEmergencyNeeded()
                    && preemptWith(Lane.P0_EMERGENCY, "emergency", "Emergency escape", env::startEmergency)) {
                return;
            }
        }

        // P1: Combat defence
        if (topPriority() < Lane.P1_COMBAT.priority()) {
            boolean suppressed = overrides != null
                    && (overrides.isSuppressed("defend") || overrides.isSuppressed("combat"));
            if (survival.defendEnabled
                    && !suppressed
                    && env.health() >= survival.defendMinHealth
                    && env.threats(survival.defendRange) > 0
                    && preemptWith(Lane.P1_COMBAT, "defend", "Defending against threats", env::startDefend)) {
                return;
            }
        }

        // P2: Vitals (eat, sleep)
        if (topPriority() < Lane.P2_VITALS.priority()) {
            boolean eatSuppressed = overrides != null && overrides.isSuppressed("eat");
            if (survival.eatEnabled
                    && !eatSuppressed
                    && (env.food() < survival.eatFoodBelow || env.health() < survival.eatHealHealthBelow)
                    && env.canEatNow()
                    && preemptWith(Lane.P2_VITALS, "eat", "Eating", env::startEat)) {
                return;
            }

            boolean sleepSuppressed = overrides != null && overrides.isSuppressed("sleep");
            if (survival.sleepEnabled && !sleepSuppressed && places.bed != null
                    && env.isBedTime() && env.canSleepNow()
                    && preemptWith(Lane.P2_VITALS, "sleep", "Sleeping", env::startSleep)) {
                return;
            }
        }

        // P3: Batched base logistics (return worn tools, dump, restock, fetch materials)
        if (topPriority() < Lane.P3_LOGISTICS.priority() && env.canStartBaseTrip()) {
            LogisticsPlanner.InventoryView inv = env.inventoryView();
            Map<String, Integer> deficit = activeWorkMaterialDeficit();
            LogisticsPlanner.BaseTripPlan plan = LogisticsPlanner.planBaseTrip(project, overrides, inv, deficit);
            if (!plan.isEmpty()
                    && preemptWith(Lane.P3_LOGISTICS, "base_trip", "Base trip " + plan.steps(), () -> env.startBaseTrip(plan))) {
                return;
            }
        }

        // P4: Project work (only start when stack is empty)
        if (stack.isEmpty()) {
            ProjectTask next = queue.nextRunnableTask();
            if (next == null) {
                finishQueue();
                return;
            }

            // Pre-dusk gate: sleep before starting a new task if dusk is imminent
            boolean nextSleepSuppressed = next.overrides != null && next.overrides.isSuppressed("sleep");
            if (survival.sleepEnabled && survival.preDuskGate && places.bed != null
                    && !nextSleepSuppressed && env.canSleepNow()) {
                int ticksToDusk = env.ticksUntilDusk();
                if (ticksToDusk >= 0 && ticksToDusk <= survival.preDuskMarginTicks) {
                    ActionHandle sleepHandle = env.startSleep();
                    if (sleepHandle != null) {
                        env.onLog("Pre-dusk gate (" + ticksToDusk + " ticks until dusk): sleeping before "
                                + next.displayLabel());
                        pushFrame(new Frame(Lane.P2_VITALS, "sleep", sleepHandle, null, null, false,
                                "Pre-dusk sleep"));
                        return;
                    }
                }
            }

            startWorkTask(next);
        }
    }

    private void startWorkTask(ProjectTask task) {
        task.ensureInitialized();
        ScopedOverrides taskOverrides = task.overrides != null ? task.overrides.copy() : new ScopedOverrides();
        boolean applied = false;
        if (!taskOverrides.isEmpty()) {
            env.applyOverrides(taskOverrides);
            applied = true;
        }

        TaskQueue.TaskLedger.EntryView prev = queue.ledger().get(task.id);
        int prevTries = prev != null ? prev.tries() : 0;
        int prevLeft = prev != null ? prev.left() : 0;
        queue.ledger().set(task.id, TaskQueue.TaskLedger.State.RUNNING, prevTries, prevLeft, null);

        ActionHandle handle = env.startTask(task, taskOverrides);
        if (handle == null) {
            if (applied) {
                env.restoreOverrides();
            }
            handleTaskError(task, "Failed to start task: " + task.displayLabel(), prevLeft);
            return;
        }

        pushFrame(new Frame(Lane.P4_WORK, "task", handle, task, taskOverrides, applied, task.displayLabel()));
    }

    private void onFrameCompleted(Frame completed) {
        if (completed.lane != Lane.P4_WORK) {
            String err = completed.handle.error();
            if (err != null && !err.isBlank()) {
                lastError = err;
                env.onLog(completed.lane + " (" + completed.kind + ") ended with error: " + err);
            }
            resumeTopIfSuspended();
            return;
        }

        // P4_WORK completed: always restore scoped overrides first
        if (completed.overridesApplied) {
            completed.overridesApplied = false;
            env.restoreOverrides();
        }

        ProjectTask task = completed.task;
        String err = completed.handle.error();
        int remaining = Math.max(0, completed.handle.remainingWork());

        if (err != null && !err.isBlank()) {
            handleTaskError(task, err, remaining);
            return;
        }

        TaskQueue.TaskLedger.EntryView prev = queue.ledger().get(task.id);
        int tries = (prev != null ? prev.tries() : 0) + 1;

        if (remaining > task.partialThreshold) {
            handlePartialCompletion(task, tries, remaining);
            return;
        }

        queue.ledger().set(task.id, TaskQueue.TaskLedger.State.DONE, tries, remaining, null);
        env.onLog("Completed task: " + task.displayLabel());
    }

    private void handleTaskError(ProjectTask task, String err, int remaining) {
        lastError = err;
        TaskQueue.TaskLedger.EntryView prev = queue.ledger().get(task.id);
        int tries = (prev != null ? prev.tries() : 0) + 1;
        ProjectTask.ErrorPolicy policy = task.errorPolicy != null ? task.errorPolicy : ProjectTask.ErrorPolicy.STOP;

        switch (policy) {
            case RETRY -> {
                if (tries <= task.maxRetries) {
                    queue.ledger().set(task.id, TaskQueue.TaskLedger.State.PENDING, tries, remaining, err);
                    env.onLog("Task error on " + task.displayLabel() + " (attempt " + tries + "/"
                            + (task.maxRetries + 1) + "), retrying: " + err);
                } else {
                    queue.ledger().set(task.id, TaskQueue.TaskLedger.State.FAILED, tries, remaining, err);
                    running = false;
                    paused = false;
                    String summary = "Task failed after " + tries + " attempts (" + task.displayLabel() + "): " + err;
                    env.onLog(summary);
                    env.onQueueComplete(false, summary, project.survival.logoffOnError);
                }
            }
            case SKIP -> {
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.DEFERRED, tries, remaining, "skipped: " + err);
                env.onLog("Task error on " + task.displayLabel() + " (SKIP): " + err);
            }
            case PAUSE -> {
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.PENDING, tries, remaining, err);
                paused = true;
                env.onLog("Task error on " + task.displayLabel() + " (PAUSE): " + err);
            }
            case STOP -> {
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.FAILED, tries, remaining, err);
                running = false;
                paused = false;
                String summary = "Stopped on task error (" + task.displayLabel() + "): " + err;
                env.onLog(summary);
                env.onQueueComplete(false, summary, project.survival.logoffOnError);
            }
        }
    }

    private void handlePartialCompletion(ProjectTask task, int tries, int remaining) {
        ProjectTask.PartialPolicy policy = task.partialPolicy != null
                ? task.partialPolicy : ProjectTask.PartialPolicy.WARN_CONTINUE;
        String detailMsg = "partial: " + remaining + " remaining (threshold " + task.partialThreshold + ")";

        switch (policy) {
            case CONTINUE -> {
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.DONE, tries, remaining, null);
                env.onLog("Completed task with " + remaining + " remaining (CONTINUE): " + task.displayLabel());
            }
            case WARN_CONTINUE -> {
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.DONE, tries, remaining, detailMsg);
                env.onLog("Warning: " + task.displayLabel() + " ended with " + detailMsg + "; continuing.");
            }
            case PAUSE -> {
                lastError = task.displayLabel() + " ended with " + detailMsg;
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.PENDING, tries, remaining, detailMsg);
                paused = true;
                env.onLog(lastError + "; pausing.");
            }
            case STOP -> {
                lastError = task.displayLabel() + " ended with " + detailMsg;
                queue.ledger().set(task.id, TaskQueue.TaskLedger.State.FAILED, tries, remaining, detailMsg);
                running = false;
                paused = false;
                env.onLog(lastError + "; stopping.");
                env.onQueueComplete(false, lastError, project.survival.logoffOnError);
            }
        }
    }

    private void finishQueue() {
        boolean anyFailed = false;
        for (ProjectTask t : project.tasks) {
            if (t != null && t.enabled && queue.stateOf(t.id) == TaskQueue.TaskLedger.State.FAILED) {
                anyFailed = true;
                break;
            }
        }
        boolean success = !anyFailed;
        running = false;
        paused = false;
        String summary = "Project '" + (project.name != null ? project.name : project.id) + "' finished: "
                + queue.doneCount() + "/" + queue.totalEnabledCount() + " tasks done.";
        env.onLog(summary);
        env.onQueueComplete(success, summary, success ? project.survival.logoffOnDone : project.survival.logoffOnError);
    }

    // ------------------------------------------------------------------ stack helpers

    private boolean preemptWith(
            Lane lane,
            String kind,
            String detail,
            java.util.function.Supplier<ActionHandle> starter
    ) {
        Frame prevTop = stack.peek();
        boolean suspendedPrev = false;
        if (prevTop != null && !prevTop.suspended) {
            prevTop.suspended = true;
            prevTop.handle.suspend();
            suspendedPrev = true;
        }
        ActionHandle h = starter.get();
        if (h == null) {
            if (suspendedPrev) {
                prevTop.suspended = false;
                prevTop.handle.resume();
            }
            return false;
        }
        stack.push(new Frame(lane, kind, h, null, null, false, detail));
        return true;
    }

    private void pushFrame(Frame frame) {
        Frame currentTop = stack.peek();
        if (currentTop != null && !currentTop.suspended) {
            currentTop.suspended = true;
            currentTop.handle.suspend();
        }
        stack.push(frame);
    }

    private void resumeTopIfSuspended() {
        Frame top = stack.peek();
        if (top != null && top.suspended && !paused) {
            top.suspended = false;
            top.handle.resume();
        }
    }

    private int topPriority() {
        Frame top = stack.peek();
        return top != null ? top.lane.priority() : Lane.IDLE.priority();
    }

    private Frame findWorkFrame() {
        for (Frame f : stack) {
            if (f.lane == Lane.P4_WORK) return f;
        }
        return null;
    }

    private ScopedOverrides activeTaskOverrides() {
        Frame work = findWorkFrame();
        return work != null ? work.overrides : null;
    }

    private Map<String, Integer> activeWorkMaterialDeficit() {
        Frame work = findWorkFrame();
        if (work == null || work.handle == null) return Map.of();
        Map<String, Integer> d = work.handle.materialDeficit();
        return d != null ? d : Map.of();
    }

    private void cancelWorkFrameIfMatches(String taskId) {
        Frame work = findWorkFrame();
        if (work != null && work.task != null && taskId.equals(work.task.id)) {
            removeAndCancelWorkFrame(work);
        }
    }

    private void removeAndCancelWorkFrame(Frame work) {
        for (Iterator<Frame> it = stack.iterator(); it.hasNext(); ) {
            Frame f = it.next();
            if (f == work) {
                it.remove();
                break;
            }
        }
        work.handle.cancel();
        if (work.overridesApplied) {
            work.overridesApplied = false;
            env.restoreOverrides();
        }
    }

    private void cancelAllFrames() {
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            try {
                f.handle.cancel();
            } finally {
                if (f.overridesApplied) {
                    f.overridesApplied = false;
                    env.restoreOverrides();
                }
            }
        }
    }

    private void ensureQueueLoaded() {
        if (queue == null) {
            throw new IllegalStateException("No project is loaded in PriorityScheduler; call start() first.");
        }
    }
}
