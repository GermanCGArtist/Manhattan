package dev.manhattan.fleet.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The Manhattan Fleet wire contract, shared by the Manhattan client and manhattan-fleet-server.
 *
 * <p>This file is kept byte-identical in both repositories ({@code src/main/java/dev/manhattan/fleet/wire/}).
 * Both test suites compare it with the sibling checkout when one is present, so a change made on one side only
 * fails the build. Change it in both places, and raise {@link #PROTOCOL_VERSION} when an old peer could
 * misread a message.
 *
 * <p>Every message is one JSON envelope {@code {"v": <version>, "type": "<type>", "body": {...}}} on the
 * custom payload channel {@code manhattan_fleet:msg}. Envelopes larger than {@link #MAX_SINGLE_PACKET_BYTES}
 * are transparently gzip-compressed and split into {@link #PACKET_CHUNK} frames so every C2S/S2C packet stays
 * safely below Minecraft's 32,767-byte serverbound custom-payload limit.
 */
public final class FleetWire {
    private FleetWire() {}

    /** Version 2: lease epochs, acknowledged reports, work hand-off, path intents, hazards, chest stock. */
    public static final int PROTOCOL_VERSION = 2;
    /** Oldest protocol version a peer may speak and still be served. */
    public static final int MIN_COMPATIBLE_VERSION = 2;

    public static final String CHANNEL_NAMESPACE = "manhattan_fleet";
    public static final String CHANNEL_PATH = "msg";
    /** Upper bound for one JSON envelope (project definitions with many areas and embedded schematics). */
    public static final int MAX_JSON_BYTES = 512 * 1024;
    /**
     * Envelopes larger than this (UTF-8 bytes) are gzip-compressed and split into {@link #PACKET_CHUNK} frames
     * so every packet stays safely under Minecraft's 32,767-byte C2S {@code ServerboundCustomPayloadPacket} cap.
     */
    public static final int MAX_SINGLE_PACKET_BYTES = 24 * 1024;
    /** Maximum Base64 characters per {@link #PACKET_CHUNK} frame (~20.2 KB total JSON envelope). */
    public static final int CHUNK_DATA_CHARS = 20 * 1024;
    /** Maximum allowed decompressed JSON size (16 MiB) to guard against malformed/zip-bomb packets. */
    public static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
    /** Hard cap on chunks per single framed transfer. */
    public static final int MAX_CHUNKS_PER_TRANSFER = 2048;
    /** Incomplete chunked transfers older than this are evicted. */
    public static final long CHUNK_TTL_MS = 60_000L;

    // ------------------------------------------------------------------ bidirectional framing
    /** Transparent gzip + chunk wrapper used when an envelope exceeds {@link #MAX_SINGLE_PACKET_BYTES}. */
    public static final String PACKET_CHUNK = "packet_chunk";

    // ------------------------------------------------------------------ client -> server
    public static final String HELLO = "hello";
    public static final String HEARTBEAT = "heartbeat";
    public static final String CLAIM_WORK = "claim_work";
    /** Reliable: carries {@code msgId}; the server answers every one with {@link #REPORT_ACK}. */
    public static final String CHUNK_REPORT = "chunk_report";
    public static final String CHEST_REPORT = "chest_report";
    public static final String CHEST_LOCK = "chest_lock";
    public static final String SLEEP_STATUS = "sleep_status";
    public static final String SUBMIT_JOB = "submit_job";
    public static final String JOB_CONTROL = "job_control";
    public static final String WORKER_CONTROL = "worker_control";
    /** The next steps of a bot's current path, relayed to nearby bots so their planners route around it. */
    public static final String PATH_INTENT = "path_intent";
    /** A place where a bot died; shared with the whole fleet. */
    public static final String HAZARD_REPORT = "hazard_report";
    /** What a bot saw inside a chest it opened (item id -> count). */
    public static final String STOCK_REPORT = "stock_report";
    /** Asks to reserve items in a chest before walking there to take them. */
    public static final String RESERVE_ITEMS = "reserve_items";

    // ------------------------------------------------------------------ server -> client
    public static final String WELCOME = "welcome";
    public static final String FLEET_SNAPSHOT = "fleet_snapshot";
    public static final String WORK_LEASE = "work_lease";
    public static final String REPORT_ACK = "report_ack";
    public static final String SLEEP_ORDER = "sleep_order";
    public static final String CHEST_SYNC = "chest_sync";
    public static final String CHEST_LOCK_REPLY = "chest_lock_reply";
    public static final String WORKER_COMMAND = "worker_command";
    public static final String JOB_ACK = "job_ack";
    public static final String ERROR = "error";
    public static final String PEER_PATHS = "peer_paths";
    public static final String HAZARD_SYNC = "hazard_sync";
    public static final String STOCK_SYNC = "stock_sync";
    public static final String RESERVE_REPLY = "reserve_reply";

    // ------------------------------------------------------------------ chunk outcomes
    public static final String OUTCOME_DONE = "DONE";
    public static final String OUTCOME_DEFERRED = "DEFERRED";
    public static final String OUTCOME_FAILED = "FAILED";

    // ------------------------------------------------------------------ limits shared by both sides
    /** Path intent points per message (the next steps only, not the whole path). */
    public static final int MAX_INTENT_POINTS = 16;
    /** A bot sends at most this many path intents per second. */
    public static final int MAX_INTENTS_PER_SECOND = 4;
    /** Path intents are relayed only to bots within this many blocks. */
    public static final int INTENT_RELAY_RADIUS = 160;
    /** A path intent older than this is ignored by planners. */
    public static final long INTENT_TTL_MS = 4_000L;
    /** Shared hazards are forgotten after this long. */
    public static final long HAZARD_TTL_MS = 3L * 60L * 60_000L;
    public static final int MAX_HAZARDS = 200;
    /** How long an item reservation holds before it lapses. */
    public static final long RESERVATION_TTL_MS = 90_000L;

    /**
     * A short text that changes whenever the message set changes. Peers exchange it in {@link #HELLO} and
     * {@link #WELCOME} and log a warning when it differs although the version matches.
     */
    public static final String SCHEMA = "fleet-v2:hello,heartbeat,claim_work,chunk_report,chest_report,chest_lock,"
            + "sleep_status,submit_job,job_control,worker_control,path_intent,hazard_report,stock_report,reserve_items,packet_chunk|"
            + "welcome,fleet_snapshot,work_lease,report_ack,sleep_order,chest_sync,chest_lock_reply,worker_command,"
            + "job_ack,error,peer_paths,hazard_sync,stock_sync,reserve_reply,packet_chunk";

    /** True when a peer speaking {@code version} can be served. */
    public static boolean isCompatible(int version) {
        return version >= MIN_COMPATIBLE_VERSION && version <= PROTOCOL_VERSION;
    }

    /** The message shown when a peer's version cannot be served. */
    public static String incompatibleMessage(int peerVersion) {
        return "Manhattan Fleet protocol mismatch: this side speaks v" + PROTOCOL_VERSION
                + " (accepts v" + MIN_COMPATIBLE_VERSION + "-v" + PROTOCOL_VERSION + "), the other side v" + peerVersion
                + ". Update Manhattan to 2.15.0+ and manhattan-fleet-server to 0.2.0+.";
    }

    // ------------------------------------------------------------------ compression & chunk framing

    /**
     * Frames a JSON envelope for transmission over {@code manhattan_fleet:msg}.
     * Envelopes at or below {@link #MAX_SINGLE_PACKET_BYTES} are returned unchanged in a single-element list.
     * Larger envelopes are gzip-compressed, Base64-encoded, and split into {@link #PACKET_CHUNK} frames each
     * well below Minecraft's 32,767-byte C2S custom-payload limit.
     */
    public static List<String> frameForWire(String envelopeJson) {
        String raw = envelopeJson == null ? "{}" : envelopeJson;
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_SINGLE_PACKET_BYTES) {
            return List.of(raw);
        }
        String encoded;
        try {
            encoded = Base64.getEncoder().encodeToString(gzip(utf8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to gzip-compress fleet envelope", e);
        }
        String transferId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int total = Math.max(1, (encoded.length() + CHUNK_DATA_CHARS - 1) / CHUNK_DATA_CHARS);
        List<String> frames = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_DATA_CHARS;
            int end = Math.min(encoded.length(), start + CHUNK_DATA_CHARS);
            String slice = encoded.substring(start, end);
            String frame = "{\"v\":" + PROTOCOL_VERSION
                    + ",\"type\":\"" + PACKET_CHUNK
                    + "\",\"body\":{\"id\":\"" + transferId
                    + "\",\"i\":" + i
                    + ",\"n\":" + total
                    + ",\"gz\":true,\"d\":\"" + slice + "\"}}";
            frames.add(frame);
        }
        return frames;
    }

    /** Gzip-compresses {@code raw} bytes. */
    public static byte[] gzip(byte[] raw) throws IOException {
        byte[] src = raw == null ? new byte[0] : raw;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(src.length, 4096));
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(src);
        }
        return baos.toByteArray();
    }

    /** Decompresses gzip bytes with a hard {@code maxBytes} ceiling. */
    public static byte[] gunzip(byte[] compressed, int maxBytes) throws IOException {
        if (compressed == null || compressed.length == 0) return new byte[0];
        int limit = Math.max(1, maxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int n;
            while ((n = gis.read(buf)) >= 0) {
                total += n;
                if (total > limit) {
                    throw new IOException("Decompressed fleet payload exceeds limit of " + limit + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /**
     * Stateful reassembler for incoming {@link #PACKET_CHUNK} frames. Thread-safe and TTL-bounded.
     */
    public static final class PacketReassembler {
        private static final class Pending {
            final long createdAtMs;
            final boolean gzipped;
            final String[] parts;
            int received;

            Pending(long nowMs, int total, boolean gzipped) {
                this.createdAtMs = nowMs;
                this.gzipped = gzipped;
                this.parts = new String[total];
            }
        }

        private final Map<String, Pending> transfers = new LinkedHashMap<>();

        /**
         * Accepts one chunk; returns the complete decompressed JSON envelope once all chunks have arrived,
         * or {@code null} while waiting for remaining chunks.
         */
        public synchronized String accept(
                String senderKey,
                String transferId,
                int index,
                int total,
                boolean gzipped,
                String data
        ) throws IOException {
            if (transferId == null || transferId.isBlank() || data == null) return null;
            if (total < 1 || total > MAX_CHUNKS_PER_TRANSFER || index < 0 || index >= total) return null;
            long now = System.currentTimeMillis();
            evictExpired(now);
            String sender = senderKey == null ? "" : senderKey;
            String key = sender + "|" + transferId;
            Pending p = transfers.get(key);
            if (p == null) {
                p = new Pending(now, total, gzipped);
                transfers.put(key, p);
            }
            if (p.parts.length != total) return null;
            if (p.parts[index] == null) {
                p.parts[index] = data;
                p.received++;
            }
            if (p.received < p.parts.length) return null;
            transfers.remove(key);
            int totalChars = 0;
            for (String s : p.parts) totalChars += s.length();
            StringBuilder sb = new StringBuilder(totalChars);
            for (String s : p.parts) sb.append(s);
            String joined = sb.toString();
            if (!p.gzipped) return joined;
            byte[] compressed = Base64.getDecoder().decode(joined);
            byte[] raw = gunzip(compressed, MAX_DECOMPRESSED_BYTES);
            return new String(raw, StandardCharsets.UTF_8);
        }

        public synchronized void clearSender(String senderKey) {
            if (senderKey == null) return;
            String prefix = senderKey + "|";
            transfers.keySet().removeIf(k -> k.startsWith(prefix));
        }

        public synchronized void clearAll() {
            transfers.clear();
        }

        private void evictExpired(long nowMs) {
            Iterator<Map.Entry<String, Pending>> it = transfers.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue().createdAtMs > CHUNK_TTL_MS) {
                    it.remove();
                }
            }
        }
    }
}
