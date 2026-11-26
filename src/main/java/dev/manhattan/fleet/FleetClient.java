package dev.manhattan.fleet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.manhattan.MapLayers;
import dev.manhattan.fleet.FleetProtocol.*;
import dev.manhattan.fleet.wire.FleetWire;
import dev.manhattan.project.Project;
import dev.midtown.plan.PeerTraffic;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side Fleet Coordinator bridge connecting Manhattan to {@code manhattan-fleet-server}.
 * Gracefully stays inert on servers that do not have {@code manhattan-fleet-server} installed.
 *
 * <p>Protocol v2 ({@link FleetWire}):
 * <ul>
 *   <li>chunk reports go through a persistent {@link FleetOutbox} and are resent until acknowledged, so a
 *       report made while disconnected or lost on the way is never lost;</li>
 *   <li>every report and heartbeat names the grant's {@code leaseEpoch}, so a report on a lease another bot
 *       has taken over is refused instead of overwriting that bot's work;</li>
 *   <li>a DONE report asks for the next chunk in the same round trip (hand-off), so workers do not idle;</li>
 *   <li>the next steps of the current path are sent as path intents and peers' intents are fed into
 *       {@link PeerTraffic}, so Midtown routes around other bots;</li>
 *   <li>deaths are shared as hazards, and chest contents seen by any bot are shared as stock with
 *       reservations.</li>
 * </ul>
 */
public final class FleetClient {
    private static final Logger LOG = LoggerFactory.getLogger("manhattan-fleet");
    private static final FleetClient INSTANCE = new FleetClient();
    private static final String FLEET_MAP_LAYER_ID = "fleet_peers";
    /** Remember this many reported (chunk, epoch) pairs to ignore late copies of their leases. */
    private static final int RECENT_REPORTS = 64;
    /** Path intents at most this often while moving. */
    private static final long INTENT_INTERVAL_MS = 500L;

    public static FleetClient instance() {
        return INSTANCE;
    }

    private boolean initialized;
    private boolean serverConnected;
    private boolean editorAllowed;
    private String worldId = "";
    private String serverKey = "";
    private String lastNotice = "";
    private boolean lastNoticeError;

    private FleetMode mode = FleetMode.LOCAL;
    private List<String> tags = new ArrayList<>();

    private FleetSnapshot latestSnapshot = new FleetSnapshot();
    private final Map<String, String> cachedProjects = new ConcurrentHashMap<>();
    private final Map<String, ChestSync> syncedChests = new ConcurrentHashMap<>();
    private final Map<String, ChestLockState> chestLocks = new ConcurrentHashMap<>();

    // Worker lease state
    private WorkGrant activeGrant;
    private volatile Project lastFleetProject;
    private boolean claimInFlight;
    private String pendingClaimId;
    private boolean serverSaidIdle;
    private long lastClaimRequestMs;
    private long lastHeartbeatMs;
    private final Deque<String> recentReported = new ArrayDeque<>();
    private final Map<String, Long> grantEpochs = new LinkedHashMap<>();
    private FleetOutbox outbox;
    private long lastOutboxCheckMs;
    private int refusedReports;

    // Path intents
    private long lastIntentMs;
    private String lastIntentSig = "";

    // Hazards seen before the connection came up (sent on welcome)
    private final List<JsonObject> pendingHazards = new ArrayList<>();

    // Coordinated 1-player sleep state
    private boolean designatedSleeper;
    private int[] sleepBedPos;
    private long sleepExpiresAtMs;

    // Safety halt latch: blocks claiming/restarting fleet work until explicitly cleared
    private volatile String safetyHaltReason;
    private final FleetWire.PacketReassembler packetReassembler = new FleetWire.PacketReassembler();

    private record ChestLockState(boolean granted, String lockedBy, long expiresAtMs, long requestedAtMs) {}

    private FleetClient() {}

    public void initialize(FleetMode initialMode, List<String> initialTags) {
        this.mode = initialMode != null ? initialMode : FleetMode.LOCAL;
        this.tags = initialTags != null ? new ArrayList<>(initialTags) : new ArrayList<>();
        if (initialized) return;
        initialized = true;

        try {
            PayloadTypeRegistry.serverboundPlay().register(FleetPayload.TYPE, FleetPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // integrated LAN server with manhattan-fleet-server in the same JVM registered it first
        }
        try {
            PayloadTypeRegistry.clientboundPlay().register(FleetPayload.TYPE, FleetPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // see above
        }

        ClientPlayNetworking.registerGlobalReceiver(FleetPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    handleServerMessage(payload.json());
                } catch (RuntimeException e) {
                    LOG.error("[Fleet] Could not handle a fleet message: {}", e.toString(), e);
                    setNotice("Fleet message failed: " + e, true);
                }
            });
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetSession();
            var sd = client.getCurrentServer();
            serverKey = sd != null && sd.ip != null ? sd.ip : "local";
            client.execute(this::sendHelloIfSupported);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
    }

    private FleetOutbox outbox() {
        if (outbox == null) {
            outbox = new FleetOutbox(FabricLoader.getInstance().getConfigDir().resolve("manhattan-fleet-outbox.json"));
        }
        return outbox;
    }

    /** For tests: use another outbox file. */
    public void useOutboxForTests(FleetOutbox box) {
        this.outbox = box;
    }

    private void resetSession() {
        serverConnected = false;
        editorAllowed = false;
        worldId = "";
        activeGrant = null;
        lastFleetProject = null;
        claimInFlight = false;
        pendingClaimId = null;
        serverSaidIdle = false;
        designatedSleeper = false;
        sleepBedPos = null;
        sleepExpiresAtMs = 0L;
        safetyHaltReason = null;
        latestSnapshot = new FleetSnapshot();
        syncedChests.clear();
        chestLocks.clear();
        packetReassembler.clearAll();
        lastIntentSig = "";
        PeerTraffic.global().clear();
        MapLayers.unregister(FLEET_MAP_LAYER_ID);
        // the outbox is kept on purpose: its reports go out after the next welcome
    }

    // ------------------------------------------------------------------ Configuration & Status

    public FleetMode mode() {
        return mode;
    }

    public void setMode(FleetMode newMode) {
        this.mode = newMode != null ? newMode : FleetMode.LOCAL;
        this.safetyHaltReason = null;
        if (!this.mode.canWork()) {
            activeGrant = null;
            claimInFlight = false;
        }
        sendHelloIfSupported();
    }

    public List<String> tags() {
        return Collections.unmodifiableList(tags);
    }

    public void setTags(List<String> newTags) {
        this.tags = new ArrayList<>();
        if (newTags != null) {
            for (String t : newTags) {
                if (t != null && !t.isBlank()) this.tags.add(t.trim());
            }
        }
        sendHelloIfSupported();
    }

    public boolean isConnected() {
        return serverConnected;
    }

    public boolean isEditorAllowed() {
        return serverConnected && editorAllowed && mode.canEdit();
    }

    public boolean isWorkerActive() {
        return serverConnected && mode.canWork() && safetyHaltReason == null;
    }

    public boolean isSafetyHalted() {
        return safetyHaltReason != null;
    }

    public String safetyHaltReason() {
        return safetyHaltReason;
    }

    /**
     * Latches a safety halt on this fleet worker: releases any active chunk lease as DEFERRED,
     * prevents claiming or restarting work, and surfaces the reason in fleet heartbeats.
     */
    public void engageSafetyHalt(String reason) {
        String clean = (reason == null || reason.isBlank()) ? "Halted for safety." : reason;
        WorkGrant g = this.activeGrant;
        this.safetyHaltReason = clean;
        if (g != null && !g.idle && g.jobId != null && g.chunkKey != null) {
            reportChunk(g.jobId, g.chunkKey, FleetWire.OUTCOME_DEFERRED, clean);
        }
        this.activeGrant = null;
        this.claimInFlight = false;
        this.serverSaidIdle = false;
        setNotice("SAFETY HALT: " + clean, true);
    }

    public void clearSafetyHalt() {
        if (this.safetyHaltReason != null) {
            this.safetyHaltReason = null;
            setNotice("Safety halt cleared.", false);
        }
    }

    public String worldId() {
        return worldId;
    }

    public String lastNotice() {
        return lastNotice;
    }

    public boolean lastNoticeError() {
        return lastNoticeError;
    }

    public FleetSnapshot snapshot() {
        return latestSnapshot;
    }

    public WorkGrant activeGrant() {
        return activeGrant;
    }

    /** Reports waiting for an acknowledgement from this server (shown in the Fleet panel). */
    public int pendingReports() {
        return outbox().pending(serverKey);
    }

    /** Reports the server refused this session (stale lease, chunk held by another bot...). */
    public int refusedReports() {
        return refusedReports;
    }

    public String cachedProjectJson(String hash) {
        return hash != null ? cachedProjects.get(hash) : null;
    }

    /** Last Project received via a Fleet work grant (kept while connected so idle workers still see base markers). */
    public Project lastFleetProject() {
        return lastFleetProject;
    }

    /** Synced chest state reported by the Fleet Server ("EMPTY", "FULL", "AVAILABLE"), or null if unknown. */
    public String chestSyncState(String dimension, BlockPos pos) {
        if (!serverConnected || pos == null) return null;
        ChestSync cs = syncedChests.get(chestKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
        return cs != null ? cs.state : null;
    }

    // ------------------------------------------------------------------ Tick & Heartbeat

    public void tick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return;
        if (!serverConnected) return;

        long now = System.currentTimeMillis();
        if (designatedSleeper && sleepExpiresAtMs > 0 && now >= sleepExpiresAtMs) {
            designatedSleeper = false;
            sleepBedPos = null;
        }

        // Send heartbeat every 3 seconds.
        if (now - lastHeartbeatMs >= 3_000L) {
            lastHeartbeatMs = now;
            sendHeartbeat(mc.player);
        }

        // Resend unacknowledged reports.
        if (now - lastOutboxCheckMs >= 500L) {
            lastOutboxCheckMs = now;
            for (FleetOutbox.Entry e : outbox().due(serverKey, now)) {
                send(e.type, e.body());
            }
        }

        // Tell nearby bots where this one is about to walk.
        if (now - lastIntentMs >= INTENT_INTERVAL_MS) {
            lastIntentMs = now;
            sendPathIntent(mc.player);
        }
    }

    private void sendPathIntent(LocalPlayer player) {
        List<int[]> steps;
        try {
            if (!(dev.midtown.api.EngineSelector.current() instanceof dev.midtown.exec.MidtownEngine me)) return;
            steps = me.upcomingSteps(FleetWire.MAX_INTENT_POINTS);
        } catch (RuntimeException e) {
            return;
        }
        StringBuilder sig = new StringBuilder();
        for (int[] s : steps) sig.append(s[0]).append(',').append(s[1]).append(',').append(s[2]).append(';');
        String sigStr = sig.toString();
        if (steps.isEmpty() && lastIntentSig.isEmpty()) return; // standing still, already said so
        if (sigStr.equals(lastIntentSig)) return;
        lastIntentSig = sigStr;
        JsonObject body = new JsonObject();
        JsonArray pts = new JsonArray();
        for (int[] s : steps) {
            JsonArray p = new JsonArray();
            p.add(s[0]);
            p.add(s[1]);
            p.add(s[2]);
            pts.add(p);
        }
        body.add("points", pts);
        send(FleetWire.PATH_INTENT, body);
    }

    // ------------------------------------------------------------------ Editor Actions

    public boolean submitProject(Project project, String dimension, AssignmentPolicy policy, List<ChunkSpec> chunks) {
        if (!isEditorAllowed() || project == null) {
            setNotice("Cannot submit: not connected as authorized Editor.", true);
            return false;
        }
        project.ensureInitialized();
        JsonObject body = new JsonObject();
        body.addProperty("projectId", project.id != null ? project.id : "project");
        body.addProperty("projectName", project.name != null ? project.name : project.id);
        body.addProperty("projectJson", FleetProtocol.GSON.toJson(project));
        body.addProperty("dimension", dimension != null ? dimension : "minecraft:overworld");
        body.add("assignment", FleetProtocol.GSON.toJsonTree(policy != null ? policy : new AssignmentPolicy()));
        body.add("chunks", FleetProtocol.GSON.toJsonTree(chunks != null ? chunks : List.of()));
        body.addProperty("submitId", UUID.randomUUID().toString());

        List<int[]> bedPool = new ArrayList<>();
        if (project.places != null) {
            if (project.places.bedPool != null && !project.places.bedPool.isEmpty()) {
                bedPool.addAll(project.places.bedPool);
            } else if (project.places.bed != null && project.places.bed.length == 3) {
                bedPool.add(project.places.bed);
            }
        }
        body.add("bedPool", FleetProtocol.GSON.toJsonTree(bedPool));
        if (!send(FleetWire.SUBMIT_JOB, body)) {
            setNotice("Cannot submit: the fleet channel is not open.", true);
            return false;
        }
        setNotice("Submitted project '" + project.name + "' (" + (chunks == null ? 0 : chunks.size()) + " chunks).", false);
        return true;
    }

    public void controlJob(String jobId, String action) {
        if (!isEditorAllowed() || jobId == null || jobId.isBlank()) return;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("action", action);
        send(FleetWire.JOB_CONTROL, body);
    }

    public void controlQueue(String action) {
        if (!isEditorAllowed() || action == null || action.isBlank()) return;
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        send(FleetWire.JOB_CONTROL, body);
    }

    public void controlWorker(String botUuid, String action, String targetJobId) {
        if (!isEditorAllowed() || botUuid == null || botUuid.isBlank() || action == null || action.isBlank()) return;
        JsonObject body = new JsonObject();
        body.addProperty("botUuid", botUuid);
        body.addProperty("action", action);
        if (targetJobId != null && !targetJobId.isBlank()) {
            body.addProperty("targetJobId", targetJobId);
        }
        send(FleetWire.WORKER_CONTROL, body);
    }

    public void reassignJob(String jobId, AssignmentPolicy policy) {
        if (!isEditorAllowed() || jobId == null || jobId.isBlank() || policy == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("action", "reassign");
        body.add("assignment", FleetProtocol.GSON.toJsonTree(policy));
        send(FleetWire.JOB_CONTROL, body);
    }

    // ------------------------------------------------------------------ Worker Lease API

    public void requestWorkIfIdle() {
        if (!isWorkerActive()) return;
        if (activeGrant != null && !activeGrant.idle) return;
        long now = System.currentTimeMillis();
        if (claimInFlight && now - lastClaimRequestMs < 4_000L) return;
        if (!claimInFlight && now - lastClaimRequestMs < 2_500L) return;

        claimInFlight = true;
        serverSaidIdle = false;
        lastClaimRequestMs = now;
        pendingClaimId = UUID.randomUUID().toString();
        JsonObject body = new JsonObject();
        String known = knownProjectHash();
        if (known != null) body.addProperty("knownProjectHash", known);
        body.addProperty("claimId", pendingClaimId);
        send(FleetWire.CLAIM_WORK, body);
    }

    private String knownProjectHash() {
        if (activeGrant != null && activeGrant.projectHash != null) return activeGrant.projectHash;
        Project p = lastFleetProject;
        if (p == null) return null;
        for (Map.Entry<String, String> e : cachedProjects.entrySet()) {
            if (e.getValue() != null) return e.getKey();
        }
        return null;
    }

    public ChunkLeaseProvider createLeaseProvider(String expectedJobId) {
        return new ChunkLeaseProvider() {
            @Override
            public ClaimResult pollNextChunk() {
                if (!isWorkerActive()) return ClaimResult.noWork();
                WorkGrant g = activeGrant;
                if (g != null && !g.idle && g.chunkKey != null) {
                    if (expectedJobId.equals(g.jobId)) {
                        return ClaimResult.granted(g.chunkKey);
                    }
                    // Server leased a chunk from a different job: exit this ChunkRunner cleanly so
                    // the fleet worker immediately starts the new job.
                    return ClaimResult.noWork();
                }
                if (serverSaidIdle) {
                    return ClaimResult.noWork();
                }
                requestWorkIfIdle();
                return ClaimResult.waiting();
            }

            @Override
            public void reportChunk(String chunkKey, String outcome, String reason) {
                FleetClient.this.reportChunk(expectedJobId, chunkKey, outcome, reason);
            }
        };
    }

    /**
     * Reports a chunk's outcome. The report is queued in the outbox (saved to disk) and sent now when connected,
     * then resent until the server acknowledges it. A DONE or DEFERRED report asks for the next chunk too.
     */
    public void reportChunk(String jobId, String chunkKey, String outcome, String reason) {
        if (jobId == null || chunkKey == null) return;
        long now = System.currentTimeMillis();
        String out = outcome == null ? FleetWire.OUTCOME_DEFERRED : outcome;
        long epoch = 0L;
        if (activeGrant != null && chunkKey.equals(activeGrant.chunkKey) && jobId.equals(activeGrant.jobId)) {
            epoch = activeGrant.leaseEpoch;
        } else {
            Long e = grantEpochs.get(jobId + "|" + chunkKey);
            if (e != null) epoch = e;
        }
        boolean wantNext = isWorkerActive() && !FleetWire.OUTCOME_FAILED.equals(out) && safetyHaltReason == null;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("chunkKey", chunkKey);
        body.addProperty("outcome", out);
        body.addProperty("reason", reason == null ? "" : reason);
        body.addProperty("leaseEpoch", epoch);
        body.addProperty("wantNext", wantNext);
        String known = activeGrant != null ? activeGrant.projectHash : knownProjectHash();
        if (known != null) body.addProperty("knownProjectHash", known);
        String id = outbox().add(serverKey, FleetWire.CHUNK_REPORT, body, now);
        rememberReported(chunkKey, epoch);
        if (serverConnected) {
            if (send(FleetWire.CHUNK_REPORT, body)) {
                outbox().markSent(id, now); // first resend after the normal backoff, not on the next tick
            }
        }
        LOG.debug("[Fleet] Report {} {} (epoch {}, msg {})", chunkKey, out, epoch, id);

        if (activeGrant != null && chunkKey.equals(activeGrant.chunkKey)) {
            activeGrant = null;
            serverSaidIdle = false;
            if (wantNext && serverConnected) {
                // the server answers with the next lease (hand-off); wait for it like for a claim
                claimInFlight = true;
                pendingClaimId = null;
                lastClaimRequestMs = now;
            } else {
                claimInFlight = false;
                lastClaimRequestMs = 0L; // claim the next chunk at once
            }
        }
    }

    private void rememberReported(String chunkKey, long epoch) {
        recentReported.addLast(chunkKey + "#" + epoch);
        while (recentReported.size() > RECENT_REPORTS) recentReported.removeFirst();
    }

    // ------------------------------------------------------------------ Coordinated 1-Player Sleep API

    public boolean isDesignatedSleeper() {
        if (!serverConnected || !designatedSleeper) return false;
        if (sleepExpiresAtMs > 0 && System.currentTimeMillis() >= sleepExpiresAtMs) {
            designatedSleeper = false;
            return false;
        }
        return true;
    }

    public int[] assignedBedPos() {
        if (sleepBedPos != null && sleepBedPos.length == 3) return sleepBedPos.clone();
        if (activeGrant != null && activeGrant.assignedBed != null && activeGrant.assignedBed.length == 3) {
            return activeGrant.assignedBed.clone();
        }
        return null;
    }

    /** Updates the local assigned bed position after a safety failover to a backup bed. */
    public void overrideAssignedBedPos(int[] bedPos) {
        int[] copy = (bedPos != null && bedPos.length >= 3) ? new int[]{bedPos[0], bedPos[1], bedPos[2]} : null;
        if (sleepBedPos != null) {
            sleepBedPos = copy;
        }
        if (activeGrant != null && activeGrant.assignedBed != null) {
            activeGrant.assignedBed = copy;
        }
    }

    public void reportSleepStatus(String status) {
        if (!serverConnected) return;
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        send(FleetWire.SLEEP_STATUS, body);
        if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            designatedSleeper = false;
            sleepBedPos = null;
            sleepExpiresAtMs = 0L;
        }
    }

    // ------------------------------------------------------------------ Shared Chest Sync, Stock & Locks

    public void reportChestState(BlockPos pos, String state) {
        if (!serverConnected || pos == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.addProperty("state", state == null ? "AVAILABLE" : state);
        send(FleetWire.CHEST_REPORT, body);
    }

    /** What this bot just saw inside a chest (item id -> count); shared as fleet stock. */
    public void reportChestStock(BlockPos pos, Map<String, Integer> items) {
        if (!serverConnected || pos == null || items == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.add("items", FleetProtocol.GSON.toJsonTree(items));
        send(FleetWire.STOCK_REPORT, body);
        // optimistic local copy until the server's stock_sync arrives
        String dim = currentDimension();
        ChestSync cs = syncedChests.computeIfAbsent(chestKey(dim, pos.getX(), pos.getY(), pos.getZ()), k -> {
            ChestSync c = new ChestSync();
            c.dimension = dim;
            c.x = pos.getX();
            c.y = pos.getY();
            c.z = pos.getZ();
            return c;
        });
        cs.stock = new LinkedHashMap<>(items);
        cs.stockAtMs = System.currentTimeMillis();
    }

    /** Asks the fleet to hold {@code count} of {@code itemId} in that chest for this bot (no-op when solo). */
    public void reserveItems(BlockPos pos, String itemId, int count) {
        if (!serverConnected || !mode.canWork() || pos == null || itemId == null || count <= 0) return;
        JsonObject body = new JsonObject();
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.addProperty("item", itemId);
        body.addProperty("count", count);
        send(FleetWire.RESERVE_ITEMS, body);
    }

    /**
     * Items of {@code itemId} in that chest that other fleet bots have not reserved, as last seen by any bot;
     * -1 when the fleet has no stock record (or this client is not in a fleet).
     */
    public int fleetUnreserved(String dimension, BlockPos pos, String itemId) {
        if (!serverConnected || pos == null || itemId == null) return -1;
        ChestSync cs = syncedChests.get(chestKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
        if (cs == null) return -1;
        return cs.unreserved(itemId, selfUuid());
    }

    /** The fleet's last stock record of that chest, or null when it has none (or this client is not in a fleet). */
    public ChestSync fleetChest(String dimension, BlockPos pos) {
        if (!serverConnected || pos == null) return null;
        return syncedChests.get(chestKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
    }

    public boolean isChestKnownEmpty(String dimension, BlockPos pos) {
        if (!serverConnected || pos == null) return false;
        ChestSync cs = syncedChests.get(chestKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
        return cs != null && "EMPTY".equalsIgnoreCase(cs.state);
    }

    public boolean isChestKnownFull(String dimension, BlockPos pos) {
        if (!serverConnected || pos == null) return false;
        ChestSync cs = syncedChests.get(chestKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
        return cs != null && "FULL".equalsIgnoreCase(cs.state);
    }

    /**
     * Requests a 1-bot-per-chest lock from the server. Returns {@code true} if already granted or not in fleet mode;
     * returns {@code false} while waiting or locked by another worker.
     */
    public boolean acquireChestLock(BlockPos pos) {
        if (!serverConnected || !mode.canWork() || pos == null) return true;
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        long now = System.currentTimeMillis();
        ChestLockState st = chestLocks.get(key);
        if (st != null && st.granted() && now < st.expiresAtMs()) {
            return true;
        }
        if (st == null || now - st.requestedAtMs() >= 1_500L) {
            chestLocks.put(key, new ChestLockState(false, st != null ? st.lockedBy() : "", 0L, now));
            JsonObject body = new JsonObject();
            body.addProperty("x", pos.getX());
            body.addProperty("y", pos.getY());
            body.addProperty("z", pos.getZ());
            body.addProperty("unlock", false);
            send(FleetWire.CHEST_LOCK, body);
        }
        return false;
    }

    public void releaseChestLock(BlockPos pos) {
        if (!serverConnected || pos == null) return;
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        chestLocks.remove(key);
        JsonObject body = new JsonObject();
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.addProperty("unlock", true);
        send(FleetWire.CHEST_LOCK, body);
    }

    // ------------------------------------------------------------------ Hazards

    /** Shares a death with the fleet (queued until the connection is up). */
    public void reportDeath(String dimension, BlockPos pos) {
        if (pos == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("dimension", dimension == null ? "minecraft:overworld" : dimension);
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.addProperty("kind", "death");
        if (serverConnected) {
            send(FleetWire.HAZARD_REPORT, body);
        } else if (mode != FleetMode.LOCAL) {
            pendingHazards.add(body);
            while (pendingHazards.size() > 20) pendingHazards.remove(0);
        }
    }

    private static void importHazards(JsonElement arr) {
        if (arr == null || !arr.isJsonArray()) return;
        int added = 0;
        for (JsonElement el : arr.getAsJsonArray()) {
            Hazard h;
            try {
                h = FleetProtocol.GSON.fromJson(el, Hazard.class);
            } catch (RuntimeException e) {
                continue;
            }
            if (h == null) continue;
            if (dev.midtown.exec.MidtownEngine.importSharedDeath(h.dimension, h.x, h.y, h.z, h.timestampMs)) added++;
        }
        if (added > 0) LOG.info("[Fleet] Added {} death site(s) reported by other bots to the path planner.", added);
    }

    // ------------------------------------------------------------------ Network Internals

    private void sendHelloIfSupported() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) return;
        if (!ClientPlayNetworking.canSend(FleetPayload.TYPE)) return;

        JsonObject body = new JsonObject();
        body.addProperty("mode", mode.name());
        body.add("tags", FleetProtocol.GSON.toJsonTree(tags));
        body.addProperty("schema", FleetWire.SCHEMA);
        int[] localBed = dev.survivalkit.Api.bedPos();
        if (localBed != null && localBed.length == 3) {
            body.add("localBed", FleetProtocol.GSON.toJsonTree(localBed));
        }
        send(FleetWire.HELLO, body);
    }

    private void sendHeartbeat(LocalPlayer player) {
        JsonObject body = new JsonObject();
        body.addProperty("health", player.getHealth());
        body.addProperty("food", player.getFoodData().getFoodLevel());
        int freeSlots = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) freeSlots++;
        }
        body.addProperty("freeSlots", freeSlots);
        String halt = safetyHaltReason;
        body.addProperty("inDanger", halt != null || player.getHealth() < 10.0f || dev.survivalkit.Api.recovering());
        if (halt != null) {
            body.addProperty("phase", "HALTED");
            body.addProperty("alertSummary", halt);
        } else if (activeGrant != null && !activeGrant.idle) {
            body.addProperty("activeJobId", activeGrant.jobId);
            body.addProperty("activeChunkKey", activeGrant.chunkKey);
            body.addProperty("leaseEpoch", activeGrant.leaseEpoch);
            body.addProperty("phase", "WORKING");
            int pending = outbox().pending(serverKey);
            body.addProperty("alertSummary", pending > 0 ? pending + " report(s) unacknowledged" : "");
        } else {
            body.addProperty("phase", mode == FleetMode.EDITOR ? "EDITOR" : "IDLE");
            body.addProperty("alertSummary", "");
        }
        int[] localBed = dev.survivalkit.Api.bedPos();
        if (localBed != null && localBed.length == 3) {
            body.add("localBed", FleetProtocol.GSON.toJsonTree(localBed));
        }
        send(FleetWire.HEARTBEAT, body);
    }

    private void handleServerMessage(String rawJson) {
        Envelope env = FleetProtocol.decodeEnvelope(rawJson);
        if (env == null) return;
        if (!FleetWire.isCompatible(env.version())) {
            setNotice(FleetWire.incompatibleMessage(env.version()), true);
            return;
        }

        JsonObject body = env.body();
        switch (env.type()) {
            case FleetWire.PACKET_CHUNK -> {
                String id = body.has("id") ? body.get("id").getAsString() : "";
                int idx = body.has("i") ? body.get("i").getAsInt() : -1;
                int total = body.has("n") ? body.get("n").getAsInt() : 0;
                boolean gz = !body.has("gz") || body.get("gz").getAsBoolean();
                String data = body.has("d") ? body.get("d").getAsString() : null;
                try {
                    String full = packetReassembler.accept("server", id, idx, total, gz, data);
                    if (full != null) handleServerMessage(full);
                } catch (java.io.IOException e) {
                    LOG.error("[Fleet] Failed to decompress chunked server message {}: {}", id, e.toString());
                    setNotice("Fleet message decompress failed: " + e.getMessage(), true);
                }
            }

            case FleetWire.WELCOME -> {
                serverConnected = true;
                editorAllowed = body.has("editorAllowed") && body.get("editorAllowed").getAsBoolean();
                worldId = body.has("worldId") ? body.get("worldId").getAsString() : "";
                if (body.has("schema") && !FleetWire.SCHEMA.equals(body.get("schema").getAsString())) {
                    LOG.warn("[Fleet] The fleet server speaks protocol v{} with a different message set; update both.",
                            env.version());
                }
                if (body.has("snapshot")) {
                    latestSnapshot = FleetProtocol.GSON.fromJson(body.get("snapshot"), FleetSnapshot.class);
                }
                if (body.has("chests") && body.get("chests").isJsonArray()) {
                    for (JsonElement el : body.getAsJsonArray("chests")) {
                        ChestSync cs = FleetProtocol.GSON.fromJson(el, ChestSync.class);
                        if (cs != null) syncedChests.put(chestKey(cs.dimension, cs.x, cs.y, cs.z), cs);
                    }
                }
                importHazards(body.get("hazards"));
                for (JsonObject h : pendingHazards) send(FleetWire.HAZARD_REPORT, h);
                pendingHazards.clear();
                registerMapLayer();
                long now = System.currentTimeMillis();
                outbox().rearm(serverKey, now);
                int pending = outbox().pending(serverKey);
                setNotice("Connected to Fleet Server (" + mode.label + ")"
                        + (pending > 0 ? ", resending " + pending + " report(s)." : "."), false);
            }

            case FleetWire.FLEET_SNAPSHOT -> {
                FleetSnapshot snap = FleetProtocol.GSON.fromJson(body, FleetSnapshot.class);
                if (snap != null) {
                    latestSnapshot = snap;
                }
            }

            case FleetWire.WORK_LEASE -> {
                WorkGrant grant = FleetProtocol.GSON.fromJson(body, WorkGrant.class);
                acceptLease(grant);
            }

            case FleetWire.REPORT_ACK -> {
                String msgId = body.has("msgId") ? body.get("msgId").getAsString() : "";
                outbox().ack(msgId);
                boolean accepted = !body.has("accepted") || body.get("accepted").getAsBoolean();
                if (!accepted) {
                    refusedReports++;
                    String reason = body.has("reason") ? body.get("reason").getAsString() : "";
                    String chunk = body.has("chunkKey") ? body.get("chunkKey").getAsString() : "?";
                    LOG.info("[Fleet] Server refused report for {}: {}", chunk, reason);
                    setNotice("Fleet refused report for " + chunk + ": " + reason, false);
                }
            }

            case FleetWire.SLEEP_ORDER -> {
                boolean sleepNow = body.has("sleepNow") && body.get("sleepNow").getAsBoolean();
                designatedSleeper = sleepNow;
                sleepBedPos = body.has("bedPos") && !body.get("bedPos").isJsonNull()
                        ? FleetProtocol.GSON.fromJson(body.get("bedPos"), int[].class)
                        : null;
                sleepExpiresAtMs = body.has("expiresAtMs") ? body.get("expiresAtMs").getAsLong() : 0L;
            }

            case FleetWire.CHEST_SYNC, FleetWire.STOCK_SYNC -> {
                ChestSync cs = FleetProtocol.GSON.fromJson(body, ChestSync.class);
                if (cs != null) {
                    syncedChests.put(chestKey(cs.dimension, cs.x, cs.y, cs.z), cs);
                }
            }

            case FleetWire.RESERVE_REPLY -> {
                int granted = body.has("granted") ? body.get("granted").getAsInt() : 0;
                String item = body.has("item") ? body.get("item").getAsString() : "";
                if (granted > 0) LOG.debug("[Fleet] Reserved {} x{}", item, granted);
            }

            case FleetWire.CHEST_LOCK_REPLY -> {
                int x = body.has("x") ? body.get("x").getAsInt() : 0;
                int y = body.has("y") ? body.get("y").getAsInt() : 0;
                int z = body.has("z") ? body.get("z").getAsInt() : 0;
                boolean granted = body.has("granted") && body.get("granted").getAsBoolean();
                String lockedBy = body.has("lockedBy") ? body.get("lockedBy").getAsString() : "";
                long exp = body.has("expiresAtMs") ? body.get("expiresAtMs").getAsLong() : 0L;
                chestLocks.put(x + "," + y + "," + z, new ChestLockState(granted, lockedBy, exp, System.currentTimeMillis()));
            }

            case FleetWire.PEER_PATHS -> {
                String uuid = body.has("uuid") ? body.get("uuid").getAsString() : null;
                if (uuid == null || uuid.equals(selfUuid())) break;
                List<int[]> pts = new ArrayList<>();
                if (body.has("points") && body.get("points").isJsonArray()) {
                    for (JsonElement el : body.getAsJsonArray("points")) {
                        if (!el.isJsonArray() || el.getAsJsonArray().size() < 3) continue;
                        JsonArray a = el.getAsJsonArray();
                        pts.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                    }
                }
                PeerTraffic.global().intent(uuid,
                        body.has("name") ? body.get("name").getAsString() : "",
                        body.has("dimension") ? body.get("dimension").getAsString() : currentDimension(),
                        body.has("x") ? body.get("x").getAsDouble() : 0,
                        body.has("y") ? body.get("y").getAsDouble() : 0,
                        body.has("z") ? body.get("z").getAsDouble() : 0,
                        pts, System.currentTimeMillis());
            }

            case FleetWire.HAZARD_SYNC -> importHazards(body.get("hazards"));

            case FleetWire.WORKER_COMMAND -> {
                String action = body.has("action") ? body.get("action").getAsString() : "";
                String jobId = body.has("jobId") ? body.get("jobId").getAsString() : "";
                String reason = body.has("reason") ? body.get("reason").getAsString() : "Stopped by Fleet Editor";
                if ("stop".equalsIgnoreCase(action)) {
                    boolean matches = jobId.isBlank()
                            || (activeGrant != null && jobId.equals(activeGrant.jobId));
                    if (matches) {
                        activeGrant = null;
                        claimInFlight = false;
                        serverSaidIdle = false;
                        lastClaimRequestMs = 0L;
                        FleetWorker.instance().onStopCommand(reason);
                        setNotice(reason, false);
                    }
                }
            }

            case FleetWire.JOB_ACK -> {
                String jid = body.has("jobId") ? body.get("jobId").getAsString() : "";
                int cnt = body.has("chunkCount") ? body.get("chunkCount").getAsInt() : 0;
                setNotice("Job " + jid + " queued (" + cnt + " chunks).", false);
            }

            case FleetWire.ERROR -> {
                String msg = body.has("error") ? body.get("error").getAsString() : "Unknown fleet error";
                LOG.warn("[Fleet] Server error: {}", msg);
                setNotice(msg, true);
            }

            default -> {}
        }
    }

    /** A lease arrived (answer to a claim, or a hand-off after a report). */
    void acceptLease(WorkGrant grant) {
        if (grant != null && grant.claimId != null && pendingClaimId != null && !grant.claimId.equals(pendingClaimId)) {
            // answer to an older claim: a newer one is on its way
            if (grant.idle) return;
        }
        claimInFlight = false;
        pendingClaimId = null;
        if (grant == null || grant.idle) {
            if (activeGrant == null) serverSaidIdle = true;
            return;
        }
        if (recentReported.contains(grant.chunkKey + "#" + grant.leaseEpoch)) {
            LOG.debug("[Fleet] Ignoring a late copy of the lease for {} (already reported)", grant.chunkKey);
            return;
        }
        if (activeGrant != null && !activeGrant.idle && !grant.chunkKey.equals(activeGrant.chunkKey)) {
            LOG.warn("[Fleet] Got lease {} while working on {}; keeping the current one.", grant.chunkKey,
                    activeGrant.chunkKey);
            return;
        }
        serverSaidIdle = false;
        if (grant.projectJson != null && grant.projectHash != null) {
            cachedProjects.put(grant.projectHash, grant.projectJson);
        }
        String pJson = grant.projectJson != null ? grant.projectJson : cachedProjectJson(grant.projectHash);
        if (pJson != null && !pJson.isBlank()) {
            try {
                Project parsed = FleetProtocol.GSON.fromJson(pJson, Project.class);
                if (parsed != null) {
                    parsed.ensureInitialized();
                    lastFleetProject = parsed;
                }
            } catch (RuntimeException e) {
                LOG.error("[Fleet] Project {} in lease {} is unreadable: {}", grant.projectHash, grant.chunkKey, e.toString());
            }
        }
        grantEpochs.put(grant.jobId + "|" + grant.chunkKey, grant.leaseEpoch);
        while (grantEpochs.size() > 256) grantEpochs.remove(grantEpochs.keySet().iterator().next());
        activeGrant = grant;
    }

    private void registerMapLayer() {
        MapLayers.register(FLEET_MAP_LAYER_ID, () -> {
            FleetSnapshot snap = latestSnapshot;
            if (snap == null) return List.of();
            List<MapLayers.Region> out = new ArrayList<>();
            if (snap.activeClaims != null) {
                for (PeerClaim pc : snap.activeClaims) {
                    int x1 = pc.centerX - 16;
                    int z1 = pc.centerZ - 16;
                    int x2 = pc.centerX + 15;
                    int z2 = pc.centerZ + 15;
                    MapLayers.State st = "DISCONNECTED_GRACE".equals(pc.state)
                            ? MapLayers.State.PENDING
                            : MapLayers.State.ACTIVE;
                    String label = pc.ownerName != null ? pc.ownerName : pc.chunkKey;
                    out.add(new MapLayers.Region(x1, z1, x2, z2, st, label, pc.jobId + " · " + pc.state));
                }
            }
            return out;
        });
    }

    private boolean send(String type, JsonObject body) {
        if (!ClientPlayNetworking.canSend(FleetPayload.TYPE)) return false;
        String encoded = FleetProtocol.encodeEnvelope(type, body);
        for (String frame : FleetWire.frameForWire(encoded)) {
            ClientPlayNetworking.send(new FleetPayload(frame));
        }
        return true;
    }

    private void setNotice(String msg, boolean err) {
        this.lastNotice = msg == null ? "" : msg;
        this.lastNoticeError = err;
    }

    private static String selfUuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null ? mc.player.getUUID().toString() : "";
    }

    private static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null ? mc.level.dimension().identifier().toString() : "minecraft:overworld";
    }

    private static String chestKey(String dim, int x, int y, int z) {
        return (dim == null ? "minecraft:overworld" : dim) + ":" + x + "," + y + "," + z;
    }
}
