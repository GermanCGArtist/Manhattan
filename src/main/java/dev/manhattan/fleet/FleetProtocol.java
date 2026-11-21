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
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_JSON_BYTES = 512 * 1024;
    public static final Gson GSON = new Gson();

    private FleetProtocol() {}

    public record FleetPayload(String json) implements CustomPacketPayload {
        public static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("manhattan_fleet", "msg");
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
    }
}
