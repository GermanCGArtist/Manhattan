package dev.manhattan.fleet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Messages that must reach the fleet server: chunk reports. Each gets a {@code msgId}, is kept (also on disk,
 * {@code config/manhattan-fleet-outbox.json}) and resent with backoff until the server acknowledges it.
 * A report made while disconnected, or lost on the way, is therefore delivered once the bot is back, and the
 * server applies it only once (it remembers the last {@code msgId} per chunk).
 *
 * <p>Pure Java (no Minecraft classes), so it is tested offline in {@code FleetClientTest}.
 */
public final class FleetOutbox {
    private static final Logger LOG = LoggerFactory.getLogger("manhattan-fleet");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** First resend after this long; doubles up to {@link #MAX_RESEND_MS}. */
    public static final long RESEND_MS = 3_000L;
    public static final long MAX_RESEND_MS = 15_000L;
    /** Unacknowledged entries older than this are dropped (the server let the lease go long ago). */
    public static final long MAX_AGE_MS = 6L * 60L * 60_000L;
    public static final int MAX_ENTRIES = 500;

    public static final class Entry {
        public String msgId;
        public String serverKey;
        public String type;
        public String bodyJson;
        public long createdMs;
        public long nextSendMs;
        public int attempts;

        public JsonObject body() {
            return JsonParser.parseString(bodyJson).getAsJsonObject();
        }
    }

    private final Path file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    public FleetOutbox(Path file) {
        this.file = file;
    }

    /** Queues {@code body} (a {@code msgId} is added to it) and returns the id. Saved at once. */
    public synchronized String add(String serverKey, String type, JsonObject body, long nowMs) {
        ensureLoaded();
        String id = UUID.randomUUID().toString();
        body.addProperty("msgId", id);
        Entry e = new Entry();
        e.msgId = id;
        e.serverKey = serverKey == null ? "" : serverKey;
        e.type = type;
        e.bodyJson = GSON.toJson(body);
        e.createdMs = nowMs;
        e.nextSendMs = nowMs; // send at once when connected
        entries.put(id, e);
        while (entries.size() > MAX_ENTRIES) {
            String oldest = entries.keySet().iterator().next();
            LOG.warn("[Fleet] Outbox full: dropping the oldest unacknowledged report {}", oldest);
            entries.remove(oldest);
        }
        save();
        return id;
    }

    /** Entries for {@code serverKey} due now; each is rescheduled with backoff (the caller sends them). */
    public synchronized List<Entry> due(String serverKey, long nowMs) {
        ensureLoaded();
        List<Entry> out = new ArrayList<>();
        boolean dropped = false;
        for (var it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (nowMs - e.createdMs > MAX_AGE_MS) {
                LOG.warn("[Fleet] Dropping report {} ({}): not acknowledged for {} h", e.msgId, e.type,
                        MAX_AGE_MS / 3_600_000L);
                it.remove();
                dropped = true;
                continue;
            }
            if (!e.serverKey.equals(serverKey == null ? "" : serverKey) || nowMs < e.nextSendMs) continue;
            e.attempts++;
            long backoff = Math.min(MAX_RESEND_MS, RESEND_MS << Math.min(3, e.attempts - 1));
            e.nextSendMs = nowMs + backoff;
            out.add(e);
        }
        if (dropped) save();
        return out;
    }

    /** {@code msgId} was just sent: the next resend comes after the first backoff. */
    public synchronized void markSent(String msgId, long nowMs) {
        ensureLoaded();
        Entry e = entries.get(msgId);
        if (e == null) return;
        e.attempts++;
        e.nextSendMs = nowMs + RESEND_MS;
    }

    /** The server answered {@code msgId}: forget it. True when it was pending. */
    public synchronized boolean ack(String msgId) {
        ensureLoaded();
        if (msgId == null || entries.remove(msgId) == null) return false;
        save();
        return true;
    }

    /** After (re)connecting: everything for this server is due at once. */
    public synchronized void rearm(String serverKey, long nowMs) {
        ensureLoaded();
        for (Entry e : entries.values()) {
            if (e.serverKey.equals(serverKey == null ? "" : serverKey)) e.nextSendMs = nowMs;
        }
    }

    public synchronized int pending(String serverKey) {
        ensureLoaded();
        int n = 0;
        for (Entry e : entries.values()) if (e.serverKey.equals(serverKey == null ? "" : serverKey)) n++;
        return n;
    }

    public synchronized int size() {
        ensureLoaded();
        return entries.size();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.exists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Entry> m = GSON.fromJson(json, new TypeToken<LinkedHashMap<String, Entry>>() {}.getType());
            if (m != null) {
                for (Entry e : m.values()) {
                    if (e == null || e.msgId == null || e.bodyJson == null) continue;
                    if (e.serverKey == null) e.serverKey = "";
                    entries.put(e.msgId, e);
                }
            }
            if (!entries.isEmpty()) LOG.info("[Fleet] {} unacknowledged fleet report(s) waiting to be resent.", entries.size());
        } catch (Exception ex) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                LOG.error("[Fleet] Could not move unreadable outbox {} aside: {}", file, moveFailed.toString());
            }
            LOG.error("[Fleet] Outbox {} was unreadable ({}); kept as {} and starting empty.", file, ex.toString(), aside);
        }
    }

    private void save() {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(entries), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("[Fleet] Could not save the outbox {}: {}", file, e.toString());
        }
    }
}
