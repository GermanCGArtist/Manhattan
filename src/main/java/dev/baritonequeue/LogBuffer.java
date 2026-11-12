package dev.baritonequeue;

import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Ring buffer of Manhattan log lines fed by {@link Util#msg(String)}, {@link Util#warn(String, String)},
 * Baritone actions, and script errors. Supports filtering by All / Errors / Baritone, copying to clipboard,
 * and clearing.
 */
public final class LogBuffer {
    public static final int CAPACITY = 500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public enum Kind { INFO, ERROR, BARITONE }

    public enum Filter {
        ALL("All"),
        ERRORS("Errors"),
        BARITONE("Baritone");

        public final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    public record Entry(long id, String time, Kind kind, String text) {}

    private static final Deque<Entry> buffer = new ArrayDeque<>(CAPACITY);
    private static long nextId = 1;

    private LogBuffer() {}

    /** Classifies a raw message into INFO, ERROR, or BARITONE. */
    public static Kind classify(String text) {
        if (text == null) return Kind.INFO;
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.startsWith("PROBLEM:")
                || lower.contains("error")
                || lower.contains("problem:")
                || lower.contains("stopped:")
                || lower.contains("unknown baritone")
                || lower.contains("is not defined")
                || lower.contains("could not ")
                || lower.contains("no water source")
                || lower.startsWith("line ")) {
            return Kind.ERROR;
        }
        if (text.startsWith("#")
                || text.startsWith("[Baritone]")
                || lower.startsWith("baritone")
                || lower.contains("#sel")
                || lower.contains("#mine")
                || lower.contains("#goto")
                || lower.contains("#build")) {
            return Kind.BARITONE;
        }
        return Kind.INFO;
    }

    public static synchronized Entry append(String text) {
        return append(classify(text), text);
    }

    public static synchronized Entry append(Kind kind, String text) {
        if (text == null) text = "";
        while (buffer.size() >= CAPACITY) {
            buffer.removeFirst();
        }
        Entry e = new Entry(nextId++, LocalTime.now().format(TIME_FMT), kind, text);
        buffer.addLast(e);
        return e;
    }

    public static synchronized List<Entry> entries(Filter filter) {
        List<Entry> out = new ArrayList<>(buffer.size());
        for (Entry e : buffer) {
            if (matches(e, filter)) out.add(e);
        }
        return out;
    }

    public static boolean matches(Entry e, Filter filter) {
        if (filter == null || filter == Filter.ALL) return true;
        if (filter == Filter.ERRORS) return e.kind() == Kind.ERROR;
        if (filter == Filter.BARITONE) return e.kind() == Kind.BARITONE;
        return true;
    }

    public static synchronized int count(Filter filter) {
        if (filter == null || filter == Filter.ALL) return buffer.size();
        int n = 0;
        for (Entry e : buffer) if (matches(e, filter)) n++;
        return n;
    }

    public static synchronized Entry lastError() {
        Entry last = null;
        for (Entry e : buffer) {
            if (e.kind() == Kind.ERROR) last = e;
        }
        return last;
    }

    public static synchronized String formatText(Filter filter) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries(filter)) {
            sb.append('[').append(e.time()).append("] [").append(e.kind().name()).append("] ").append(e.text()).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String copyToClipboard(Filter filter) {
        String text = formatText(filter);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) {
                mc.keyboardHandler.setClipboard(text);
            }
        } catch (Throwable ignored) {
        }
        return text;
    }

    public static synchronized void clear() {
        buffer.clear();
    }
}
