package dev.manhattan.fleet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side wire protocol matching {@code manhattan-fleet-server} ({@code manhattan_fleet:msg}, v1).
 */
public final class FleetProtocol {
    public static final int PROTOCOL_VERSION = dev.manhattan.fleet.wire.FleetWire.PROTOCOL_VERSION;
    public static final int MAX_JSON_BYTES = dev.manhattan.fleet.wire.FleetWire.MAX_JSON_BYTES;
    public static final Gson GSON = new Gson();

    private FleetProtocol() {}

    public record FleetPayload(String json) implements CustomPacketPayload {
        public static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath(
                dev.manhattan.fleet.wire.FleetWire.CHANNEL_NAMESPACE, dev.manhattan.fleet.wire.FleetWire.CHANNEL_PATH);
        public static final Type<FleetPayload> TYPE = new Type<>(CHANNEL_ID);

        public static final StreamCodec<FriendlyByteBuf, FleetPayload> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeUtf(payload.json() == null ? "{}" : payload.json(), MAX_JSON_BYTES),
                buf -> new FleetPayload(buf.readUtf(MAX_JSON_BYTES))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static String encodeEnvelope(String type, JsonElement body) {
        JsonObject env = new JsonObject();
        env.addProperty("v", PROTOCOL_VERSION);
        env.addProperty("type", type);
        env.add("body", body == null ? new JsonObject() : body);
        return GSON.toJson(env);
    }

    public record Envelope(int version, String type, JsonObject body) {}

    public static Envelope decodeEnvelope(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            int v = root.has("v") ? root.get("v").getAsInt() : 0;
            String type = root.has("type") ? root.get("type").getAsString() : "";
            JsonObject body = root.has("body") && root.get("body").isJsonObject()
                    ? root.getAsJsonObject("body")
                    : new JsonObject();
            return new Envelope(v, type, body);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ DTOs matching manhattan-fleet-server

    public enum AssignmentMode {
        EXPLICIT,
        AUTO_POOL
    }

    public static final class AssignmentPolicy {
        public AssignmentMode mode = AssignmentMode.AUTO_POOL;
        public List<String> explicitBots = new ArrayList<>();
        public String poolTag = "";
        public int maxBots = 2;
        public int maxDistance = 500;
        public int priority = 0;
    }

    public static final class ChunkSpec {
        public String chunkKey;
        public int cx;
        public int cz;
        public int centerX;
        public int centerZ;

        public ChunkSpec() {}

        public ChunkSpec(String chunkKey, int cx, int cz, int centerX, int centerZ) {
            this.chunkKey = chunkKey;
            this.cx = cx;
            this.cz = cz;
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }

    public static final class WorkGrant {
        public boolean idle;
        public String jobId;
        public String projectId;
        public String projectHash;
        public String projectJson;
        public String chunkKey;
        public int cx;
        public int cz;
        public int[] assignedBed;
        public long leaseExpiresAtMs;
        public boolean inheritedFromGrace;
        /** Sent back with every report and heartbeat for this chunk (protocol v2). */
        public long leaseEpoch;
        /** Echo of the claim this grant answers; null for a hand-off after a report. */
        public String claimId;
    }

    public static final class BotInfo {
        public String uuid;
        public String name;
        public String mode;
        public List<String> tags = new ArrayList<>();
        public boolean online;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public double health;
        public int food;
        public int freeSlots;
        public boolean inDanger;
        public boolean held;
        public String activeJobId;
        public String activeChunkKey;
        public String phase;
        public String alertSummary;
        public int[] localBed;
        public int[] assignedBed;
    }

    public static final class JobSummary {
        public String jobId;
        public String projectId;
        public String projectName;
        public String dimension;
        public String status;
        public AssignmentPolicy assignment;
        public int totalChunks;
        public int doneChunks;
        public int claimedChunks;
        public int failedChunks;
        public String statusReason;
        public List<String> activeWorkers = new ArrayList<>();
    }

    public static final class PeerClaim {
        public String jobId;
        public String chunkKey;
        public int cx;
        public int cz;
        public int centerX;
        public int centerZ;
        public String ownerName;
        public String state;
        public long leaseEpoch;
    }

    public static final class FleetSnapshot {
        public long serverTimeMs;
        public String designatedSleeperUuid;
        public String designatedSleeperName;
        public List<BotInfo> bots = new ArrayList<>();
        public List<JobSummary> jobs = new ArrayList<>();
        public List<PeerClaim> activeClaims = new ArrayList<>();
    }

    public static final class ChestSync {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public String state;
        public String updatedBy;
        public long updatedAtMs;
        /** Item id -> count as last seen inside this chest by a fleet bot. */
        public java.util.Map<String, Integer> stock = new java.util.LinkedHashMap<>();
        public long stockAtMs;
        public String stockBy;
        public List<Reservation> reservations = new ArrayList<>();

        /** Count of {@code item} not reserved by bots other than {@code selfUuid}; -1 when nobody has looked. */
        public int unreserved(String item, String selfUuid) {
            if (stockAtMs <= 0) return -1;
            int have = stock == null ? 0 : stock.getOrDefault(item, 0);
            int reserved = 0;
            long now = System.currentTimeMillis();
            if (reservations != null) {
                for (Reservation r : reservations) {
                    if (r == null || !item.equals(r.item) || r.expiresAtMs < now) continue;
                    if (selfUuid != null && selfUuid.equals(r.botUuid)) continue;
                    reserved += r.count;
                }
            }
            return Math.max(0, have - reserved);
        }
    }

    public static final class Reservation {
        public String botUuid;
        public String botName;
        public String item;
        public int count;
        public long expiresAtMs;
    }

    /** A place the fleet avoids (a bot died there). */
    public static final class Hazard {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public String kind;
        public String reportedBy;
        public long timestampMs;
    }
}
