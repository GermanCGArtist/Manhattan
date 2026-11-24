package dev.midtown.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Typed runtime telemetry event emitted through {@link RuntimeBus}.
 * Pure Java with zero Minecraft or third-party dependencies so it can run headlessly and serialize to JSONL directly.
 */
public final class RuntimeEvent {

    public enum Type {
        RUN_START,
        RUN_END,
        TASK_START,
        TASK_END,
        CHUNK_STATE,
        INTERRUPT_ENTER,
        INTERRUPT_EXIT,
        PATH_REPLAN,
        STUCK,
        EDGE_BLACKLIST,
        RESTOCK_EXHAUSTED,
        DEATH,
        HALT,
        INCIDENT_DUMP,
        /** Waiting for another bot to clear the next step, or a detour around it. */
        PEER_YIELD
    }

    public enum Category {
        WORK,
        WALK,
        REPLAN,
        STUCK,
        LOGISTICS,
        SURVIVAL,
        WAIT,
        SYSTEM
    }

    private final long seq;
    private final long timestampMs;
    private final Type type;
    private final Category category;
    private final String source;
    private final String message;
    private final int x;
    private final int y;
    private final int z;
    private final boolean hasPos;
    private final long durationMs;
    private final Map<String, String> meta;

    public RuntimeEvent(
            long seq,
            long timestampMs,
            Type type,
            Category category,
            String source,
            String message,
            int x,
            int y,
            int z,
            boolean hasPos,
            long durationMs,
            Map<String, String> meta) {
        this.seq = seq;
        this.timestampMs = timestampMs;
        this.type = Objects.requireNonNull(type, "type");
        this.category = category == null ? defaultCategory(type) : category;
        this.source = source == null ? "" : source;
        this.message = message == null ? "" : message;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hasPos = hasPos;
        this.durationMs = Math.max(0L, durationMs);
        if (meta == null || meta.isEmpty()) {
            this.meta = Map.of();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
            this.meta = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
        }
    }

    public static Category defaultCategory(Type type) {
        if (type == null) return Category.SYSTEM;
        return switch (type) {
            case TASK_START, TASK_END, CHUNK_STATE -> Category.WORK;
            case PATH_REPLAN -> Category.REPLAN;
            case STUCK, EDGE_BLACKLIST -> Category.STUCK;
            case RESTOCK_EXHAUSTED -> Category.LOGISTICS;
            case INTERRUPT_ENTER, INTERRUPT_EXIT, DEATH -> Category.SURVIVAL;
            case RUN_START, RUN_END, HALT, INCIDENT_DUMP -> Category.SYSTEM;
            case PEER_YIELD -> Category.WAIT;
        };
    }

    public long seq() { return seq; }
    public long timestampMs() { return timestampMs; }
    public Type type() { return type; }
    public Category category() { return category; }
    public String source() { return source; }
    public String message() { return message; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public boolean hasPos() { return hasPos; }
    public long durationMs() { return durationMs; }
    public Map<String, String> meta() { return meta; }

    /** Serializes this event as a single compact JSONL line (without trailing newline). */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"seq\":").append(seq);
        sb.append(",\"ts\":").append(timestampMs);
        sb.append(",\"type\":\"").append(type.name()).append('"');
        sb.append(",\"category\":\"").append(category.name()).append('"');
        if (!source.isEmpty()) {
            sb.append(",\"source\":\"");
            appendEscaped(sb, source);
            sb.append('"');
        }
        if (!message.isEmpty()) {
            sb.append(",\"message\":\"");
            appendEscaped(sb, message);
            sb.append('"');
        }
        if (hasPos) {
            sb.append(",\"pos\":[").append(x).append(',').append(y).append(',').append(z).append(']');
        }
        if (durationMs > 0L) {
            sb.append(",\"durationMs\":").append(durationMs);
        }
        if (!meta.isEmpty()) {
            sb.append(",\"meta\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                appendEscaped(sb, e.getKey());
                sb.append("\":\"");
                appendEscaped(sb, e.getValue());
                sb.append('"');
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    static void appendEscaped(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return toJsonLine();
    }
}
