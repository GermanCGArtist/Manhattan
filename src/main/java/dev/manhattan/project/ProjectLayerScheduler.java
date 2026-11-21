package dev.manhattan.project;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java multi-project priority layer orchestrator built on top of {@link PriorityScheduler}.
 *
 * <p>Projects are ordered in a {@link ProjectQueue} like layers in Adobe Media Encoder: index {@code 0}
 * is the topmost layer and always receives bot capacity first. When a higher-priority layer becomes runnable
 * (reordered upward, added above, enabled, or reset/retried), the lower-priority project cleanly yields at the
 * next safe point (never mid-emergency, combat, vitals, or base trip) while preserving its ledger progress so
 * it resumes automatically once the higher-priority layer finishes. Zero Minecraft imports.
 */
public final class ProjectLayerScheduler {

    public enum LayerStatus {
        RUNNING,
        QUEUED,
        DONE,
        FAILED,
        DISABLED,
        EMPTY,
        MISSING
    }

    public record LayerTelemetry(
            boolean running,
            boolean paused,
            int activeLayerIndex,
            int totalLayers,
            int enabledLayers,
            String activeProjectId,
            String activeProjectName,
            PriorityScheduler.Telemetry scheduler,
            long elapsedMillis,
            String lastError
    ) {
    }

    /** Host seam for loading/activating/deactivating projects and receiving queue-level completion callbacks. */
    public interface Host {
        Project loadProject(String projectId);

        TaskQueue.TaskLedger loadLedger(String projectId);

        void activateProject(Project project);

        void deactivateProject(Project project);

        void onAllComplete(boolean success, String summary, boolean logoff);

        void onLog(String message);
    }

    private record LayerCandidate(int index, Project project, TaskQueue.TaskLedger ledger) {
    }

    private final Host host;
    private final PriorityScheduler scheduler;
    private final Set<String> failedInRun = new HashSet<>();

    private ProjectQueue queue;
    private Project currentProject;
    private String currentProjectId;
    private boolean running;
    private boolean paused;
    private boolean suppressingCompletion;
    private long startedAtMillis;
    private String lastError;

    public ProjectLayerScheduler(PriorityScheduler.Env env, Host host) {
        Objects.requireNonNull(env, "env");
        this.host = Objects.requireNonNull(host, "host");
        this.scheduler = new PriorityScheduler(new InterceptingEnv(env));
    }

    /** Starts executing the given {@link ProjectQueue} from the topmost runnable layer; returns error or {@code null}. */
    public String start(ProjectQueue projectQueue) {
        Objects.requireNonNull(projectQueue, "projectQueue");
        projectQueue.ensureInitialized();
        if (running) {
            return "Project layer queue is already running.";
        }
        this.queue = projectQueue;
        this.failedInRun.clear();
        this.lastError = null;

        LayerCandidate top = findTopRunnableLayer();
        if (top == null) {
            return "No enabled projects with pending tasks in the layer queue.";
        }

        this.running = true;
        this.paused = false;
        this.startedAtMillis = System.currentTimeMillis();
        host.onLog("Started project layer queue (" + projectQueue.enabledCount() + " enabled layer(s)).");
        activateAndStart(top);
        return null;
    }

    /** Stops the layer queue and currently running project without marking active tasks as failed. */
    public void stop(String reason) {
        boolean wasRunning = running;
        suppressingCompletion = true;
        try {
            scheduler.stop(reason);
        } finally {
            suppressingCompletion = false;
        }
        deactivateCurrent();
        running = false;
        paused = false;
        if (wasRunning) {
            String suffix = (reason != null && !reason.isBlank()) ? ": " + reason : ".";
            host.onLog("Stopped project layer queue" + suffix);
        }
    }

    public void pause() {
        if (!running || paused) return;
        paused = true;
        scheduler.pause();
    }

    public void resume() {
        if (!running || !paused) return;
        paused = false;
        scheduler.resume();
    }

    public boolean skipCurrent() {
        return running && scheduler.skipCurrent();
    }

    /** Clears per-run failure status for {@code projectId} (or all projects if {@code null}) so it can run again. */
    public void clearFailed(String projectId) {
        if (projectId == null) {
            failedInRun.clear();
        } else {
            failedInRun.remove(ProjectStore.sanitizeId(projectId));
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused || (running && scheduler.isPaused());
    }

    public Project currentProject() {
        return currentProject;
    }

    public String currentProjectId() {
        return currentProjectId;
    }

    public PriorityScheduler scheduler() {
        return scheduler;
    }

    public ProjectQueue queue() {
        return queue;
    }

    /** Advances the layer queue by one tick, enforcing topmost-layer priority preemption at safe boundaries. */
    public void tick() {
        if (!running || paused || queue == null) return;
        if (scheduler.isPaused()) return;

        LayerCandidate top = findTopRunnableLayer();

        if (currentProject == null) {
            if (top != null) {
                activateAndStart(top);
            } else {
                boolean allOk = failedInRun.isEmpty();
                finishAll(allOk,
                        allOk ? "All queued project layers completed." : "Project layer queue finished with errors.",
                        allOk && queue.logoffWhenAllDone);
                return;
            }
        } else if (top == null) {
            if (canSafelySwitch()) {
                yieldCurrent("No remaining runnable project layers.");
                boolean allOk = failedInRun.isEmpty();
                finishAll(allOk,
                        allOk ? "All queued project layers completed." : "Project layer queue finished with errors.",
                        allOk && queue.logoffWhenAllDone);
                return;
            }
        } else if (!top.project.id.equals(currentProjectId)) {
            if (canSafelySwitch()) {
                String prevName = displayName(currentProject);
                String nextName = displayName(top.project);
                host.onLog("Layer priority shift: switching bot capacity from '" + prevName
                        + "' to higher-priority layer '" + nextName + "'.");
                yieldCurrent("Preempted by higher-priority layer '" + top.project.id + "'.");
                activateAndStart(top);
            }
        }

        if (running && !paused && scheduler.isRunning()) {
            scheduler.tick();
        }
    }

    /** Computes the display status of a layer in {@code projectQueue}. */
    public LayerStatus layerStatus(ProjectQueue.Layer layer) {
        if (layer == null || layer.projectId == null || layer.projectId.isBlank()) {
            return LayerStatus.MISSING;
        }
        String id = ProjectStore.sanitizeId(layer.projectId);
        if (running && id.equals(currentProjectId)) {
            return LayerStatus.RUNNING;
        }
        if (!layer.enabled) {
            return LayerStatus.DISABLED;
        }
        Project p = host.loadProject(id);
        if (p == null) {
            return LayerStatus.MISSING;
        }
        p.ensureInitialized();
        TaskQueue.TaskLedger ledger = host.loadLedger(id);
        TaskQueue tq = new TaskQueue(p, ledger);
        int enabledTasks = tq.totalEnabledCount();
        if (enabledTasks == 0) {
            return LayerStatus.EMPTY;
        }
        if (failedInRun.contains(id)) {
            return LayerStatus.FAILED;
        }
        if (tq.nextRunnableTask() != null) {
            return LayerStatus.QUEUED;
        }
        boolean anyFailed = false;
        for (ProjectTask t : p.tasks) {
            if (t != null && t.enabled && tq.stateOf(t.id) == TaskQueue.TaskLedger.State.FAILED) {
                anyFailed = true;
                break;
            }
        }
        return anyFailed ? LayerStatus.FAILED : LayerStatus.DONE;
    }

    public LayerTelemetry telemetry() {
        int activeIdx = (queue != null && currentProjectId != null) ? queue.indexOf(currentProjectId) : -1;
        int totalLayers = queue != null ? queue.snapshot().size() : 0;
        int enabledLayers = queue != null ? queue.enabledCount() : 0;
        long elapsed = running ? Math.max(0L, System.currentTimeMillis() - startedAtMillis) : 0L;
        PriorityScheduler.Telemetry st = scheduler.telemetry();
        String err = lastError != null ? lastError : (st != null ? st.lastError() : null);
        return new LayerTelemetry(
                running,
                isPaused(),
                activeIdx,
                totalLayers,
                enabledLayers,
                currentProjectId,
                currentProject != null ? displayName(currentProject) : null,
                st,
                elapsed,
                err
        );
    }

    // ------------------------------------------------------------------ internal orchestration

    private boolean canSafelySwitch() {
        PriorityScheduler.Lane lane = scheduler.currentLane();
        return lane == PriorityScheduler.Lane.IDLE || lane == PriorityScheduler.Lane.P4_WORK;
    }

    private LayerCandidate findTopRunnableLayer() {
        if (queue == null) return null;
        var layers = queue.snapshot();
        for (int i = 0; i < layers.size(); i++) {
            ProjectQueue.Layer layer = layers.get(i);
            if (layer == null || !layer.enabled || layer.projectId == null || layer.projectId.isBlank()) continue;
            String id = ProjectStore.sanitizeId(layer.projectId);
            if (failedInRun.contains(id)) continue;
            Project p = host.loadProject(id);
            if (p == null) continue;
            p.ensureInitialized();
            TaskQueue.TaskLedger ledger = host.loadLedger(id);
            TaskQueue tq = new TaskQueue(p, ledger);
            if (tq.nextRunnableTask() != null) {
                return new LayerCandidate(i, p, ledger);
            }
        }
        return null;
    }

    private void activateAndStart(LayerCandidate cand) {
        currentProject = cand.project;
        currentProjectId = cand.project.id;
        host.activateProject(cand.project);
        host.onLog("Activating layer #" + (cand.index + 1) + ": '" + displayName(cand.project) + "'.");
        scheduler.start(cand.project, cand.ledger);
    }

    private void yieldCurrent(String reason) {
        suppressingCompletion = true;
        try {
            scheduler.stop(reason);
        } finally {
            suppressingCompletion = false;
        }
        deactivateCurrent();
    }

    private void deactivateCurrent() {
        Project prev = currentProject;
        currentProject = null;
        currentProjectId = null;
        if (prev != null) {
            host.deactivateProject(prev);
        }
    }

    private void onInnerProjectComplete(boolean success, String summary, boolean logoff) {
        if (suppressingCompletion || !running) return;

        String finishedId = currentProjectId;
        String finishedName = currentProject != null ? displayName(currentProject) : String.valueOf(finishedId);
        deactivateCurrent();

        if (!success) {
            lastError = summary;
            if (finishedId != null) {
                failedInRun.add(finishedId);
            }
            if (queue == null || queue.stopOnProjectError) {
                finishAll(false, summary, logoff);
                return;
            }
            host.onLog("Layer '" + finishedName + "' failed (continuing to next layer): " + summary);
        } else {
            host.onLog("Layer '" + finishedName + "' finished.");
        }

        LayerCandidate next = findTopRunnableLayer();
        if (next != null) {
            activateAndStart(next);
            return;
        }

        boolean allOk = failedInRun.isEmpty() && success;
        boolean shouldLogoff = allOk ? ((queue != null && queue.logoffWhenAllDone) || logoff) : logoff;
        String finalSummary = allOk
                ? "All queued project layers completed."
                : ("Project layer queue completed with errors: " + (lastError != null ? lastError : summary));
        finishAll(allOk, finalSummary, shouldLogoff);
    }

    private void finishAll(boolean success, String summary, boolean logoff) {
        running = false;
        paused = false;
        deactivateCurrent();
        host.onLog(summary);
        host.onAllComplete(success, summary, logoff);
    }

    private static String displayName(Project p) {
        if (p == null) return "(none)";
        return (p.name != null && !p.name.isBlank()) ? p.name : p.id;
    }

    private final class InterceptingEnv implements PriorityScheduler.Env {
        private final PriorityScheduler.Env delegate;

        InterceptingEnv(PriorityScheduler.Env delegate) {
            this.delegate = delegate;
        }

        @Override public boolean isRecovering() { return delegate.isRecovering(); }
        @Override public boolean isEmergencyNeeded() { return delegate.isEmergencyNeeded(); }
        @Override public int threats(double range) { return delegate.threats(range); }
        @Override public double health() { return delegate.health(); }
        @Override public int food() { return delegate.food(); }
        @Override public boolean isBedTime() { return delegate.isBedTime(); }
        @Override public int ticksUntilDusk() { return delegate.ticksUntilDusk(); }
        @Override public LogisticsPlanner.InventoryView inventoryView() { return delegate.inventoryView(); }
        @Override public boolean canEatNow() { return delegate.canEatNow(); }
        @Override public boolean canSleepNow() { return delegate.canSleepNow(); }
        @Override public boolean canStartBaseTrip() { return delegate.canStartBaseTrip(); }
        @Override public PriorityScheduler.ActionHandle startEmergency() { return delegate.startEmergency(); }
        @Override public PriorityScheduler.ActionHandle startDefend() { return delegate.startDefend(); }
        @Override public PriorityScheduler.ActionHandle startEat() { return delegate.startEat(); }
        @Override public PriorityScheduler.ActionHandle startSleep() { return delegate.startSleep(); }
        @Override public PriorityScheduler.ActionHandle startBaseTrip(LogisticsPlanner.BaseTripPlan plan) { return delegate.startBaseTrip(plan); }
        @Override public PriorityScheduler.ActionHandle startTask(ProjectTask task, ScopedOverrides overrides) { return delegate.startTask(task, overrides); }
        @Override public void applyOverrides(ScopedOverrides overrides) { delegate.applyOverrides(overrides); }
        @Override public void restoreOverrides() { delegate.restoreOverrides(); }
        @Override public void onLog(String message) { delegate.onLog(message); }

        @Override
        public void onQueueComplete(boolean success, String summary, boolean logoff) {
            onInnerProjectComplete(success, summary, logoff);
        }
    }
}
