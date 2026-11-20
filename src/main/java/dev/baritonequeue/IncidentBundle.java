package dev.baritonequeue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.baritonequeue.project.GameProjectRuntime;
import dev.baritonequeue.project.Project;
import dev.baritonequeue.project.TaskQueue;
import dev.midtown.api.RuntimeBus;
import dev.midtown.api.RuntimeEvent;
import dev.midtown.api.TelemetryProfiler;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained diagnostic bundle dumped to {@code <gameDir>/manhattan-incidents/incident-<ts>.json}
 * whenever a run halts, errors, exhausts restock, or the user requests an on-demand snapshot.
 * Designed for direct consumption by the local MCP bridge and AI assistants.
 */
public final class IncidentBundle {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private static volatile Path lastIncidentPath;
    private static volatile String lastIncidentJson;
    private static volatile String lastTriggerReason;
    private static volatile long lastIncidentAtMs;

    private IncidentBundle() {}

    public static final class Bundle {
        public int version = 1;
        public String timestampIso;
        public long timestampMs;
        public String world;
        public String runId;
        public String telemetryFile;
        public String triggerReason;
        public String detail;
        public String mode; // "project" | "script" | "idle"
        public String currentStep;
        public int scriptLine;
        public String scriptName;
        public Project activeProject;
        public PlayerSnapshot player;
        public LedgerSummary ledger;
        public BottleneckSummary telemetry;
        public List<EventRecord> recentEvents = new ArrayList<>();
    }

    public static final class PlayerSnapshot {
        public int[] pos;
        public double health;
        public double maxHealth;
        public int food;
        public int freeSlots;
        public List<String> wornTools = new ArrayList<>();
    }

    public static final class LedgerSummary {
        public int done;
        public int running;
        public int failed;
        public int skipped;
        public int pending;
        public List<String> failedIds = new ArrayList<>();
    }

    public static final class BottleneckSummary {
        public long totalSampledMs;
        public Map<String, Long> categoryMs = new LinkedHashMap<>();
        public int replanCount;
        public int stuckCount;
        public int edgeBlacklistCount;
        public int interruptCount;
        public int restockExhaustedCount;
        public int deathCount;
        public List<String> bottlenecks = new ArrayList<>();
    }

    public static final class EventRecord {
        public long seq;
        public long ts;
        public String type;
        public String category;
        public String source;
        public String message;
        public int[] pos;
        public long durationMs;
        public Map<String, String> meta;
    }

    public static Path lastIncidentPath() { return lastIncidentPath; }
    public static String lastIncidentJson() { return lastIncidentJson; }
    public static String lastTriggerReason() { return lastTriggerReason; }
    public static long lastIncidentAtMs() { return lastIncidentAtMs; }

    public static void clearLast() {
        lastIncidentPath = null;
        lastIncidentJson = null;
        lastTriggerReason = null;
        lastIncidentAtMs = 0L;
    }

    /** Pure-Java serialization helper (safe in headless unit tests). */
    public static String toJson(Bundle bundle) {
        return GSON.toJson(bundle);
    }

    /** Builds a {@link Bundle} from pure-Java runtime data (no Minecraft client required). */
    public static Bundle buildHeadless(
            String world,
            String triggerReason,
            String detail,
            Project project,
            List<RuntimeEvent> events,
            TelemetryProfiler.Snapshot profilerSnap) {
        long now = System.currentTimeMillis();
        Bundle b = new Bundle();
        b.timestampMs = now;
        b.timestampIso = Instant.ofEpochMilli(now).toString();
        b.world = (world == null || world.isBlank()) ? "default" : world;
        b.runId = RuntimeBus.activeRunId();
        Path tf = RuntimeBus.activeTelemetryFile();
        b.telemetryFile = tf != null ? tf.toString() : null;
        b.triggerReason = triggerReason == null ? "unknown" : triggerReason;
        b.detail = detail == null ? "" : detail;
        b.currentStep = RuntimeBus.currentStep();
        b.activeProject = project;
        b.mode = project != null ? "project" : (RuntimeBus.isScriptActive() ? "script" : "idle");

        if (profilerSnap != null) {
            BottleneckSummary ts = new BottleneckSummary();
            ts.totalSampledMs = profilerSnap.totalMs();
            for (Map.Entry<RuntimeEvent.Category, Long> e : profilerSnap.categoryMs().entrySet()) {
                ts.categoryMs.put(e.getKey().name(), e.getValue());
            }
            ts.replanCount = profilerSnap.replanCount();
            ts.stuckCount = profilerSnap.stuckCount();
            ts.edgeBlacklistCount = profilerSnap.edgeBlacklistCount();
            ts.interruptCount = profilerSnap.interruptCount();
            ts.restockExhaustedCount = profilerSnap.restockExhaustedCount();
            ts.deathCount = profilerSnap.deathCount();
            for (TelemetryProfiler.Bottleneck bn : profilerSnap.bottlenecks()) {
                ts.bottlenecks.add("[" + bn.severity() + "] " + bn.title() + " — " + bn.detail());
            }
            b.telemetry = ts;
        }

        if (events != null) {
            for (RuntimeEvent e : events) {
                EventRecord r = new EventRecord();
                r.seq = e.seq();
                r.ts = e.timestampMs();
                r.type = e.type().name();
                r.category = e.category().name();
                r.source = e.source();
                r.message = e.message();
                if (e.hasPos()) r.pos = new int[]{e.x(), e.y(), e.z()};
                r.durationMs = e.durationMs();
                if (!e.meta().isEmpty()) r.meta = new LinkedHashMap<>(e.meta());
                b.recentEvents.add(r);
            }
        }
        return b;
    }

    /** Atomically writes {@code bundle} to {@code <incidentsDir>/incident-<ts>.json} and updates last-incident cache. */
    public static Path writeBundle(Path incidentsDir, Bundle bundle) throws IOException {
        Files.createDirectories(incidentsDir);
        String name = "incident-" + FILE_TS.format(Instant.ofEpochMilli(bundle.timestampMs)) + ".json";
        Path target = incidentsDir.resolve(name);
        String json = toJson(bundle);
        Path tmp = target.resolveSibling(name + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        lastIncidentPath = target;
        lastIncidentJson = json;
        lastTriggerReason = bundle.triggerReason;
        lastIncidentAtMs = bundle.timestampMs;
        return target;
    }

    /**
     * Collects live in-game context (project/script, player snapshot, ledger, recent RuntimeBus events)
     * and writes an incident bundle to {@code <gameDir>/manhattan-incidents/}. Never throws.
     */
    public static Path captureAndWrite(String triggerReason, String detail) {
        try {
            Minecraft mc = null;
            try {
                mc = Minecraft.getInstance();
            } catch (Throwable ignored) {
            }
            if (mc == null) {
                return null;
            }
            GameProjectRuntime prt = GameProjectRuntime.instance();
            Project activeProject = prt != null ? prt.activeProject() : null;
            boolean projectRunning = prt != null && prt.running();
            String world = QueueMod.projectWorld();
            List<RuntimeEvent> events = RuntimeBus.recentEvents(50);
            TelemetryProfiler.Snapshot profilerSnap = RuntimeBus.telemetrySnapshot();

            Bundle bundle = buildHeadless(world, triggerReason, detail, activeProject, events, profilerSnap);
            if (projectRunning) {
                bundle.mode = "project";
                if (activeProject != null && activeProject.tasks != null) {
                    LedgerSummary ls = new LedgerSummary();
                    for (var task : activeProject.tasks) {
                        if (task == null || task.id == null) continue;
                        TaskQueue.TaskLedger.State st = prt.taskState(task.id);
                        switch (st) {
                            case DONE -> ls.done++;
                            case RUNNING -> ls.running++;
                            case FAILED -> { ls.failed++; ls.failedIds.add(task.id); }
                            case DEFERRED -> ls.skipped++;
                            default -> ls.pending++;
                        }
                    }
                    bundle.ledger = ls;
                }
            } else if (QueueMod.runner != null && QueueMod.runner.running()) {
                bundle.mode = "script";
                bundle.scriptName = DockedPanels.activeTabName();
                ProgressModel.Snapshot snap = ProgressModel.snapshot();
                bundle.scriptLine = snap.line();
            }

            try {
                if (mc.player != null) {
                    PlayerSnapshot ps = new PlayerSnapshot();
                    ps.pos = new int[]{mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()};
                    ps.health = mc.player.getHealth();
                    ps.maxHealth = mc.player.getMaxHealth();
                    ps.food = mc.player.getFoodData().getFoodLevel();
                    PlayerView.Snapshot pv = PlayerView.get();
                    ps.freeSlots = pv.freeSlots();
                    int worn = PlayerView.wornThreshold();
                    for (PlayerView.Slot slot : PlayerView.tools()) {
                        if (slot.durability() <= Math.max(worn * 2, 20)) {
                            ps.wornTools.add(slot.id() + ":" + slot.durability());
                        }
                    }
                    bundle.player = ps;
                }
            } catch (Throwable ignored) {
            }

            Path dir = QueueMod.projectGameDir().resolve("manhattan-incidents");
            Path written = writeBundle(dir, bundle);
            RuntimeBus.emit(RuntimeEvent.Type.INCIDENT_DUMP, RuntimeEvent.Category.SYSTEM,
                    "IncidentBundle", triggerReason + " -> " + written.getFileName());
            return written;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
