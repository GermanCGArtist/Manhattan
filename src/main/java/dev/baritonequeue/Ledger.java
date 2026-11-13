package dev.baritonequeue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a script has finished, saved to disk: one JSON file per world and script, one entry per job id (see Jobs for
 * the ids). Written through a temp file and a move on every change, so a crash never leaves half a file. Pure
 * (a Path and Gson), so it is tested in LangTest.
 *
 * <pre>
 * { "version": 1, "jobs": { "script/schem_build@12(all,add)#1/1748,-1400": {
 *     "state": "DONE", "tries": 1, "left": 0, "reason": null, "sig": "...", "vars": null, "time": 1760000000000 } } }
 * </pre>
 */
final class Ledger {
    enum State { PENDING, RUNNING, DONE, DEFERRED, FAILED }

    static final class Entry {
        State state = State.PENDING;
        int tries;
        int left;           // blocks still wrong when it last ended
        String reason;      // why it is deferred / failed, with an example position
        String sig;         // what the job was made for (a chunk: the plan's corner and size); another one = the entry is stale
        Map<String, Object> vars;   // saved variables of a script job (plain values only)
        long time;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class File {
        int version = 1;
        Map<String, Entry> jobs = new LinkedHashMap<>();
    }

    private final Path path;
    private File data = new File();

    Ledger(Path path) {
        this.path = path;
    }

    Path path() { return path; }

    /** Reads the file (an empty ledger when there is none or it cannot be read). */
    static Ledger load(Path path) {
        Ledger l = new Ledger(path);
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    File f = GSON.fromJson(r, File.class);
                    if (f != null && f.jobs != null) l.data = f;
                }
            }
        } catch (Exception e) {
            System.out.println("[Queue] could not read " + path + " (" + e + "), starting with an empty ledger");
        }
        return l;
    }

    /** config/manhattan-jobs/<world>_<script>.json */
    static Path fileFor(Path configDir, String world, String script) {
        return configDir.resolve("manhattan-jobs").resolve(world + "_" + script + ".json");
    }

    void save() {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(data, w);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[Queue] could not save " + path + " (" + e + ")");
        }
    }

    // ------------------------------------------------------------------ entries

    /** The entry, or null. An entry with another signature than the one given counts as not there. */
    Entry get(String id, String sig) {
        Entry e = data.jobs.get(id);
        if (e == null) return null;
        if (sig != null && e.sig != null && !e.sig.equals(sig)) return null;
        return e;
    }

    Entry get(String id) { return data.jobs.get(id); }

    State state(String id, String sig) {
        Entry e = get(id, sig);
        return e == null ? State.PENDING : e.state;
    }

    /** Sets an entry and saves. */
    Entry set(String id, State state, int tries, int left, String reason, String sig) {
        Entry e = data.jobs.computeIfAbsent(id, k -> new Entry());
        e.state = state;
        e.tries = tries;
        e.left = left;
        e.reason = reason;
        if (sig != null) e.sig = sig;
        e.time = System.currentTimeMillis();
        save();
        return e;
    }

    /** Saved variables of a script job. */
    void putVars(String id, Map<String, Object> vars) {
        Entry e = data.jobs.computeIfAbsent(id, k -> new Entry());
        e.vars = vars;
        e.time = System.currentTimeMillis();
        save();
    }

    /** Is the id the prefix itself or below it (prefix + "/...")? */
    static boolean under(String id, String prefix) {
        return id.equals(prefix) || id.startsWith(prefix + "/");
    }

    /** Removes the entries at and below the prefix (null = all); returns how many. */
    int reset(String prefix) {
        int n = 0;
        for (var it = data.jobs.keySet().iterator(); it.hasNext(); ) {
            String id = it.next();
            if (prefix == null || under(id, prefix)) { it.remove(); n++; }
        }
        if (n > 0) save();
        return n;
    }

    /** DEFERRED and FAILED entries at and below the prefix go back to PENDING with no tries; returns how many. */
    int retry(String prefix) {
        int n = 0;
        for (var e : data.jobs.entrySet()) {
            if (!under(e.getKey(), prefix)) continue;
            Entry v = e.getValue();
            if (v.state == State.DEFERRED || v.state == State.FAILED || v.state == State.RUNNING) {
                v.state = State.PENDING;
                v.tries = 0;
                v.reason = null;
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }

    /** Entries at and below the prefix (null = all) in the state (null = any). */
    List<String> ids(String prefix, State state) {
        List<String> out = new ArrayList<>();
        for (var e : data.jobs.entrySet()) {
            if (prefix != null && !under(e.getKey(), prefix)) continue;
            if (state != null && e.getValue().state != state) continue;
            out.add(e.getKey());
        }
        return out;
    }

    int size() { return data.jobs.size(); }
}
