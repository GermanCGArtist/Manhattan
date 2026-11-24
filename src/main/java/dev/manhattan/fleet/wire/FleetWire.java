package dev.manhattan.fleet.wire;

/**
 * The Manhattan Fleet wire contract, shared by the Manhattan client and manhattan-fleet-server.
 *
 * <p>This file is kept byte-identical in both repositories ({@code src/main/java/dev/manhattan/fleet/wire/}).
 * Both test suites compare it with the sibling checkout when one is present, so a change made on one side only
 * fails the build. Change it in both places, and raise {@link #PROTOCOL_VERSION} when an old peer could
 * misread a message.
 *
 * <p>Every message is one JSON envelope {@code {"v": <version>, "type": "<type>", "body": {...}}} on the
 * custom payload channel {@code manhattan_fleet:msg}.
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
            + "sleep_status,submit_job,job_control,worker_control,path_intent,hazard_report,stock_report,reserve_items|"
            + "welcome,fleet_snapshot,work_lease,report_ack,sleep_order,chest_sync,chest_lock_reply,worker_command,"
            + "job_ack,error,peer_paths,hazard_sync,stock_sync,reserve_reply";

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
}
