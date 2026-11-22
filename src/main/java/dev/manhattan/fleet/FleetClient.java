package dev.manhattan.fleet;

import com.google.gson.JsonObject;
import dev.manhattan.MapLayers;
import dev.manhattan.fleet.FleetProtocol.*;
import dev.manhattan.project.Project;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side Fleet Coordinator bridge connecting Manhattan to {@code manhattan-fleet-server}.
 * Gracefully stays inert on servers that do not have {@code manhattan-fleet-server} installed.
 */
public final class FleetClient {
    private static final FleetClient INSTANCE = new FleetClient();
    private static final String FLEET_MAP_LAYER_ID = "fleet_peers";

    public static FleetClient instance() {
        return INSTANCE;
    }

    private boolean initialized;
    private boolean serverConnected;
    private boolean editorAllowed;
    private String worldId = "";
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
    private boolean serverSaidIdle;
    private long lastClaimRequestMs;
    private long lastHeartbeatMs;

    // Coordinated 1-player sleep state
    private boolean designatedSleeper;
    private int[] sleepBedPos;
    private long sleepExpiresAtMs;

    private record ChestLockState(boolean granted, String lockedBy, long expiresAtMs, long requestedAtMs) {}

    private FleetClient() {}

    public void initialize(FleetMode initialMode, List<String> initialTags) {
        this.mode = initialMode != null ? initialMode : FleetMode.LOCAL;
        this.tags = initialTags != null ? new ArrayList<>(initialTags) : new ArrayList<>();
        if (initialized) return;
        initialized = true;

        try {
            PayloadTypeRegistry.serverboundPlay().register(FleetPayload.TYPE, FleetPayload.CODEC);
        } catch (IllegalArgumentException ignored) {
            // Already registered (e.g. integrated LAN server with manhattan-fleet-server in same JVM).
        }
        try {
            PayloadTypeRegistry.clientboundPlay().register(FleetPayload.TYPE, FleetPayload.CODEC);
        } catch (IllegalArgumentException ignored) {
        }

        ClientPlayNetworking.registerGlobalReceiver(FleetPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleServerMessage(payload.json()));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetSession();
            client.execute(this::sendHelloIfSupported);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
    }

    private void resetSession() {
        serverConnected = false;
        editorAllowed = false;
        worldId = "";
        activeGrant = null;
        lastFleetProject = null;
        claimInFlight = false;
        serverSaidIdle = false;
        designatedSleeper = false;
        sleepBedPos = null;
        sleepExpiresAtMs = 0L;
        latestSnapshot = new FleetSnapshot();
        syncedChests.clear();
        chestLocks.clear();
        MapLayers.unregister(FLEET_MAP_LAYER_ID);
    }

    // ------------------------------------------------------------------ Configuration & Status

    public FleetMode mode() {
        return mode;
    }

    public void setMode(FleetMode newMode) {
        this.mode = newMode != null ? newMode : FleetMode.LOCAL;
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
        return serverConnected && mode.canWork();
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

        List<int[]> bedPool = new ArrayList<>();
        if (project.places != null) {
            if (project.places.bedPool != null && !project.places.bedPool.isEmpty()) {
                bedPool.addAll(project.places.bedPool);
            } else if (project.places.bed != null && project.places.bed.length == 3) {
                bedPool.add(project.places.bed);
            }
        }
        body.add("bedPool", FleetProtocol.GSON.toJsonTree(bedPool));
        send("submit_job", body);
        setNotice("Submitted project '" + project.name + "' (" + (chunks == null ? 0 : chunks.size()) + " chunks).", false);
        return true;
    }

    public void controlJob(String jobId, String action) {
        if (!isEditorAllowed() || jobId == null || jobId.isBlank()) return;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("action", action);
        send("job_control", body);
    }

    public void reassignJob(String jobId, AssignmentPolicy policy) {
        if (!isEditorAllowed() || jobId == null || jobId.isBlank() || policy == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("action", "reassign");
        body.add("assignment", FleetProtocol.GSON.toJsonTree(policy));
        send("job_control", body);
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
        JsonObject body = new JsonObject();
        if (activeGrant != null && activeGrant.projectHash != null) {
            body.addProperty("knownProjectHash", activeGrant.projectHash);
        }
        send("claim_work", body);
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
                    // GameProjectRuntime.tickFleetWorkerIdle() immediately starts the new job.
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

    public void reportChunk(String jobId, String chunkKey, String outcome, String reason) {
        if (!serverConnected || jobId == null || chunkKey == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("jobId", jobId);
        body.addProperty("chunkKey", chunkKey);
        body.addProperty("outcome", outcome == null ? "DEFERRED" : outcome);
        body.addProperty("reason", reason == null ? "" : reason);
        send("chunk_report", body);
        if (activeGrant != null && chunkKey.equals(activeGrant.chunkKey)) {
            activeGrant = null;
            claimInFlight = false;
            serverSaidIdle = false;
            lastClaimRequestMs = 0L; // Immediately claim the next chunk without throttle-exiting ChunkRunner
        }
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

    public void reportSleepStatus(String status) {
        if (!serverConnected) return;
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        send("sleep_status", body);
        if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            designatedSleeper = false;
            sleepBedPos = null;
            sleepExpiresAtMs = 0L;
        }
    }

    // ------------------------------------------------------------------ Shared Chest Sync & 1-Bot Locks

    public void reportChestState(BlockPos pos, String state) {
        if (!serverConnected || pos == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("x", pos.getX());
        body.addProperty("y", pos.getY());
        body.addProperty("z", pos.getZ());
        body.addProperty("state", state == null ? "AVAILABLE" : state);
        send("chest_report", body);
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
            send("chest_lock", body);
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
        send("chest_lock", body);
    }

    // ------------------------------------------------------------------ Network Internals

    private void sendHelloIfSupported() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) return;
        if (!ClientPlayNetworking.canSend(FleetPayload.TYPE)) return;

        JsonObject body = new JsonObject();
        body.addProperty("mode", mode.name());
        body.add("tags", FleetProtocol.GSON.toJsonTree(tags));
        int[] localBed = dev.survivalkit.Api.bedPos();
        if (localBed != null && localBed.length == 3) {
            body.add("localBed", FleetProtocol.GSON.toJsonTree(localBed));
        }
        send("hello", body);
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
        body.addProperty("inDanger", player.getHealth() < 10.0f || dev.survivalkit.Api.recovering());
        if (activeGrant != null && !activeGrant.idle) {
            body.addProperty("activeJobId", activeGrant.jobId);
            body.addProperty("activeChunkKey", activeGrant.chunkKey);
            body.addProperty("phase", "WORKING");
        } else {
            body.addProperty("phase", mode == FleetMode.EDITOR ? "EDITOR" : "IDLE");
        }
        body.addProperty("alertSummary", "");
        int[] localBed = dev.survivalkit.Api.bedPos();
        if (localBed != null && localBed.length == 3) {
            body.add("localBed", FleetProtocol.GSON.toJsonTree(localBed));
        }
        send("heartbeat", body);
    }

    private void handleServerMessage(String rawJson) {
        Envelope env = FleetProtocol.decodeEnvelope(rawJson);
        if (env == null) return;
        if (env.version() != FleetProtocol.PROTOCOL_VERSION) {
            setNotice("Fleet protocol mismatch (server v" + env.version() + ").", true);
            return;
        }

        JsonObject body = env.body();
        switch (env.type()) {
            case "welcome" -> {
                serverConnected = true;
                editorAllowed = body.has("editorAllowed") && body.get("editorAllowed").getAsBoolean();
                worldId = body.has("worldId") ? body.get("worldId").getAsString() : "";
                if (body.has("snapshot")) {
                    latestSnapshot = FleetProtocol.GSON.fromJson(body.get("snapshot"), FleetSnapshot.class);
                }
                registerMapLayer();
                setNotice("Connected to Fleet Server (" + mode.label + ").", false);
            }

            case "fleet_snapshot" -> {
                FleetSnapshot snap = FleetProtocol.GSON.fromJson(body, FleetSnapshot.class);
                if (snap != null) {
                    latestSnapshot = snap;
                }
            }

            case "work_lease" -> {
                claimInFlight = false;
                WorkGrant grant = FleetProtocol.GSON.fromJson(body, WorkGrant.class);
                if (grant == null || grant.idle) {
                    activeGrant = null;
                    serverSaidIdle = true;
                } else {
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
                        } catch (Exception ignored) {
                        }
                    }
                    activeGrant = grant;
                }
            }

            case "sleep_order" -> {
                boolean sleepNow = body.has("sleepNow") && body.get("sleepNow").getAsBoolean();
                designatedSleeper = sleepNow;
                sleepBedPos = body.has("bedPos") && !body.get("bedPos").isJsonNull()
                        ? FleetProtocol.GSON.fromJson(body.get("bedPos"), int[].class)
                        : null;
                sleepExpiresAtMs = body.has("expiresAtMs") ? body.get("expiresAtMs").getAsLong() : 0L;
            }

            case "chest_sync" -> {
                ChestSync cs = FleetProtocol.GSON.fromJson(body, ChestSync.class);
                if (cs != null) {
                    syncedChests.put(chestKey(cs.dimension, cs.x, cs.y, cs.z), cs);
                }
            }

            case "chest_lock_reply" -> {
                int x = body.has("x") ? body.get("x").getAsInt() : 0;
                int y = body.has("y") ? body.get("y").getAsInt() : 0;
                int z = body.has("z") ? body.get("z").getAsInt() : 0;
                boolean granted = body.has("granted") && body.get("granted").getAsBoolean();
                String lockedBy = body.has("lockedBy") ? body.get("lockedBy").getAsString() : "";
                long exp = body.has("expiresAtMs") ? body.get("expiresAtMs").getAsLong() : 0L;
                chestLocks.put(x + "," + y + "," + z, new ChestLockState(granted, lockedBy, exp, System.currentTimeMillis()));
            }

            case "job_ack" -> {
                String jid = body.has("jobId") ? body.get("jobId").getAsString() : "";
                int cnt = body.has("chunkCount") ? body.get("chunkCount").getAsInt() : 0;
                setNotice("Job " + jid + " queued (" + cnt + " chunks).", false);
            }

            case "error" -> {
                String msg = body.has("error") ? body.get("error").getAsString() : "Unknown fleet error";
                setNotice(msg, true);
            }

            default -> {}
        }
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

    private void send(String type, JsonObject body) {
        if (!ClientPlayNetworking.canSend(FleetPayload.TYPE)) return;
        ClientPlayNetworking.send(new FleetPayload(FleetProtocol.encodeEnvelope(type, body)));
    }

    private void setNotice(String msg, boolean err) {
        this.lastNotice = msg == null ? "" : msg;
        this.lastNoticeError = err;
    }

    private static String chestKey(String dim, int x, int y, int z) {
        return (dim == null ? "minecraft:overworld" : dim) + ":" + x + "," + y + "," + z;
    }
}
