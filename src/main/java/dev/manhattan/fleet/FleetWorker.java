package dev.manhattan.fleet;

import dev.manhattan.QueueMod;
import dev.manhattan.project.GameProjectRuntime;
import dev.manhattan.project.Project;
import dev.manhattan.project.ProjectTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The autonomous fleet worker as one small state machine (it used to live inside {@code GameProjectRuntime}).
 *
 * <pre>
 *   DISABLED  not connected, not a worker, or halted for safety
 *   IDLE      no lease: asks the server for one (throttled by FleetClient)
 *   STARTING  has a lease: unpacks the project and starts the queue on it
 *   WORKING   the project queue runs; ChunkRunner reports chunks and the hand-off brings the next lease
 *   COOLDOWN  after a start failure or a failed run, waits before trying again (never a tight loop)
 * </pre>
 *
 * {@code GameProjectRuntime} calls {@link #tick} every client tick and {@link #onQueueFinished} when a run
 * ends; {@link FleetClient} calls {@link #onStopCommand} when an editor stops this worker.
 */
public final class FleetWorker {
    private static final Logger LOG = LoggerFactory.getLogger("manhattan-fleet");
    private static final FleetWorker INSTANCE = new FleetWorker();

    /** Ticks to wait after a run failed before claiming again. */
    static final int FAILURE_COOLDOWN_TICKS = 200;
    /** Ticks to wait after starting a project (or failing to) before the next start attempt. */
    static final int START_COOLDOWN_TICKS = 60;

    public enum State { DISABLED, IDLE, STARTING, WORKING, COOLDOWN }

    public static FleetWorker instance() {
        return INSTANCE;
    }

    private State state = State.DISABLED;
    private int cooldownTicks;
    private String lastError = "";
    private long starts;
    private long failures;

    private FleetWorker() {}

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public long starts() {
        return starts;
    }

    public long failures() {
        return failures;
    }

    /** Every client tick. {@code queueRunning} = the project queue (or a script) runs right now. */
    public void tick(GameProjectRuntime rt, boolean queueRunning) {
        if (cooldownTicks > 0) cooldownTicks--;
        FleetClient fc = FleetClient.instance();
        if (!fc.isWorkerActive() || rt.safetyHaltReason() != null) {
            state = State.DISABLED;
            return;
        }
        if (queueRunning) {
            state = State.WORKING;
            return;
        }
        if (cooldownTicks > 0) {
            state = State.COOLDOWN;
            return;
        }
        FleetProtocol.WorkGrant grant = fc.activeGrant();
        if (grant == null || grant.idle) {
            state = State.IDLE;
            fc.requestWorkIfIdle();
            return;
        }
        String json = grant.projectJson != null ? grant.projectJson : fc.cachedProjectJson(grant.projectHash);
        if (json == null || json.isBlank()) {
            state = State.IDLE;
            fc.requestWorkIfIdle();
            return;
        }
        state = State.STARTING;
        cooldownTicks = START_COOLDOWN_TICKS;
        try {
            Project project = prepare(json, grant);
            if (project == null) {
                lastError = "lease " + grant.chunkKey + " carries no readable project";
                LOG.error("[Fleet] {}", lastError);
                return;
            }
            starts++;
            String err = rt.startFleetProject(project);
            if (err != null) {
                lastError = err;
                LOG.warn("[Fleet] Could not start fleet project for {}: {}", grant.chunkKey, err);
            }
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOG.error("[Fleet] Starting the fleet project for {} failed: {}", grant.chunkKey, e.toString(), e);
        }
    }

    /** Unpacks a lease's project: imports areas and schematics, applies the assigned bed, scopes the task. */
    static Project prepare(String json, FleetProtocol.WorkGrant grant) {
        Project p = FleetProtocol.GSON.fromJson(json, Project.class);
        if (p == null) return null;
        p.ensureInitialized();
        // Fleet workers stay connected and return to IDLE to claim the next job/chunk instead of disconnecting.
        p.survival.logoffOnDone = false;
        p.survival.logoffOnError = false;
        if (p.places != null && p.places.areas != null && !p.places.areas.isEmpty()) {
            QueueMod.importFleetAreas(p.places.areas);
        }
        if (p.embeddedSchematics != null && !p.embeddedSchematics.isEmpty()) {
            QueueMod.importFleetSchematics(p.embeddedSchematics, p.embeddedSchematicFiles);
        }
        if (grant.assignedBed != null && grant.assignedBed.length == 3) {
            p.places.bed = grant.assignedBed.clone();
            dev.survivalkit.Api.setBedPos(grant.assignedBed);
        }
        scopeToLeasedTask(p, grant.chunkKey);
        return p;
    }

    /**
     * Runs only the task that owns the leased chunk ("<taskId>/<x>,<z>[,<y>]"), so the worker does not run
     * unrelated tasks or report another task's chunk key as unknown.
     */
    public static void scopeToLeasedTask(Project p, String chunkKey) {
        if (chunkKey == null || !chunkKey.contains("/")) return;
        String leasedTaskId = chunkKey.substring(0, chunkKey.indexOf('/'));
        boolean matched = false;
        for (ProjectTask t : p.tasks) {
            if (t != null && leasedTaskId.equals(t.id)) {
                matched = true;
                break;
            }
        }
        if (!matched) return;
        for (ProjectTask t : p.tasks) {
            if (t != null) t.enabled = leasedTaskId.equals(t.id);
        }
    }

    /** A project run ended. A failed run reports its leased chunk FAILED and cools down before claiming again. */
    public void onQueueFinished(boolean success, String summary) {
        if (success) return;
        FleetClient fc = FleetClient.instance();
        if (!fc.isWorkerActive()) return;
        failures++;
        lastError = summary == null ? "" : summary;
        FleetProtocol.WorkGrant grant = fc.activeGrant();
        if (grant != null && !grant.idle && grant.jobId != null && grant.chunkKey != null) {
            fc.reportChunk(grant.jobId, grant.chunkKey, dev.manhattan.fleet.wire.FleetWire.OUTCOME_FAILED, summary);
        }
        cooldownTicks = FAILURE_COOLDOWN_TICKS; // 10 s, so a broken grant never tight-loops
        state = State.COOLDOWN;
    }

    /** An editor stopped this worker (or cancelled its job): stop the running queue. */
    public void onStopCommand(String reason) {
        GameProjectRuntime rt = GameProjectRuntime.instance();
        if (rt.running()) rt.stopQueue(reason);
        state = State.IDLE;
    }

    /** Short status for the Fleet panel. */
    public String describe() {
        return switch (state) {
            case DISABLED -> "not working for the fleet";
            case IDLE -> "waiting for work";
            case STARTING -> "starting the leased project";
            case WORKING -> "working";
            case COOLDOWN -> "cooling down" + (lastError.isBlank() ? "" : " (" + lastError + ")");
        };
    }
}
