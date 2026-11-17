package dev.baritonequeue;

import dev.baritonequeue.Ledger.State;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The job queue the runner and the UI read: a tree (script job -> action -> chunks) of what the running script is
 * doing, with the state of each node, backed by the Ledger on disk. Chunks of a big task (stage 1: schem_build) are
 * the leaves; mining/water chunks and script jobs use the same nodes.
 *
 * Job id: {@code <job name>/<action>#<n>/<x0>,<z0>}. Outside a job block the job name is "script" and the action is the
 * line and its arguments: {@code script/schem_build@212(all,add)#1/1748,-1400}. n counts how often the same action
 * started in this run (a loop gives #1, #2 ...). A script job itself has just its name as id.
 */
public final class Jobs {
    private Jobs() {}

    /** Name of the ledger file when the script has no name of its own (the editor's first tab, chat /queue run). */
    static final String SCRIPT = "main";
    static final String NO_JOB = "script";

    enum Kind { JOB, ACTION, CHUNK }

    static final class Node {
        final Kind kind;
        final String id;
        final String label;
        final Node parent;
        final List<Node> kids = new ArrayList<>();
        public ChunkGrid.ColumnBox box;
        State state = State.PENDING;
        int tries, left;
        String reason;

        Node(Kind kind, String id, String label, Node parent) {
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.parent = parent;
            if (parent != null) parent.kids.add(this);
        }

        int count(State s) {
            int n = 0;
            for (Node k : kids) if (k.state == s) n++;
            return n;
        }
    }

    private static Node root = new Node(Kind.JOB, "", "script", null);
    private static final Map<String, Integer> ordinals = new LinkedHashMap<>();
    private static final Map<String, Node> jobs = new LinkedHashMap<>();
    private static Ledger ledger;

    /** The name of the script job that runs now (set by job blocks); null outside a job block. */
    static String currentJob;

    /** The script whose ledger is in use: the editor tab that was run ("main" for the first tab). The ledger file is per world and script. */
    static String script = SCRIPT;

    static Node root() { return root; }

    // ------------------------------------------------------------------ ledger

    /** The ledger of this world (the one in use, until the world changes). */
    static Ledger ledger() {
        Path p;
        try {
            p = Ledger.fileFor(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), Capture.world(), script);
        } catch (Throwable t) {
            if (ledger == null) ledger = new Ledger(Path.of("manhattan-jobs-offline.json"));   // outside Fabric: nothing is written unless asked
            return ledger;
        }
        if (ledger == null || !ledger.path().equals(p)) ledger = Ledger.load(p);
        return ledger;
    }

    /** Tests: use this ledger instead. */
    static void useLedger(Ledger l) { ledger = l; }

    // ------------------------------------------------------------------ a run

    static String startRun() { return startRun(SCRIPT); }

    /** The name of the script the editor shows (its tab), for the ledger file; "main" if that cannot be told. */
    static String editorScript() {
        try { return DockedPanels.activeTabName(); } catch (Throwable t) { return SCRIPT; }
    }

    /**
     * For /queue jobs | reset | retry: the ledger of the script that runs now, or (nothing running) of the editor's
     * open tab, the one Run would start.
     */
    static void useCommandScript(boolean running) {
        if (!running) script = fileSafe(editorScript());
    }

    /** A file-name-safe script name: "build.txt" -> "build"; empty -> "main". */
    static String fileSafe(String name) {
        String n = name == null ? "" : name.trim();
        if (n.toLowerCase().endsWith(".txt")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^A-Za-z0-9_.-]", "_");
        return n.isEmpty() ? SCRIPT : n;
    }

    /** A script starts: a fresh tree and counters. Returns the "Resuming" text when the ledger has state, else null. */
    static String startRun(String scriptName) {
        script = fileSafe(scriptName);
        root = new Node(Kind.JOB, "", "script", null);
        ordinals.clear();
        jobs.clear();
        currentJob = null;
        Ledger l = ledger();
        int jobsTotal = 0, jobsDone = 0, chunksTotal = 0, chunksDone = 0;
        for (String id : l.ids(null, null)) {
            boolean done = l.state(id, null) == State.DONE;
            if (CHUNK.matcher(id).matches()) { chunksTotal++; if (done) chunksDone++; }
            else if (id.indexOf('/') < 0) { jobsTotal++; if (done) jobsDone++; }
        }
        if (jobsTotal > 0) return "Resuming: " + jobsDone + " of " + jobsTotal + " jobs done" + (chunksTotal > 0 ? " (" + chunksDone + " of " + chunksTotal + " chunks)" : "") + ".";
        if (chunksTotal > 0) return "Resuming: " + chunksDone + " of " + chunksTotal + " chunks done.";
        return null;
    }

    private static Node jobNode() {
        String name = currentJob == null ? NO_JOB : currentJob;
        return jobs.computeIfAbsent(name, n -> new Node(Kind.JOB, n, n, root));
    }

    /**
     * A new action (a schem_build, a clear_area ...) under the current job. counted = it runs inside a script (n counts
     * up); a chat command always gets #1.
     */
    static Node action(String fn, int line, String args, boolean counted) {
        Node job = jobNode();
        String base = job.id + "/" + fn + "@" + line + "(" + args + ")";
        int n = counted ? ordinals.merge(base, 1, Integer::sum) : 1;
        String id = base + "#" + n;
        for (Node k : job.kids) if (k.id.equals(id)) return k;   // the same action again (a rerun in chat): same node
        return new Node(Kind.ACTION, id, fn + " (line " + line + ")", job);
    }

    /** Registers a spatial chunk leaf node under an action node. */
    static Node chunk(Node action, ChunkGrid.ColumnBox box) {
        String key = box.minX() + "," + box.minZ() + (box.ySlices() > 1 ? "," + box.minY() : "");
        String label = box.minX() + " " + box.minZ() + " .. " + box.maxX() + " " + box.maxZ()
                + (box.ySlices() > 1 ? " (y " + box.minY() + " .. " + box.maxY() + ")" : "");
        Node c = new Node(Kind.CHUNK, action.id + "/" + key, label, action);
        c.box = box;
        return c;
    }

    /** Sets a node's state and writes it into the ledger. */
    static void set(Node n, State s, int tries, int left, String reason, String sig) {
        n.state = s;
        n.tries = tries;
        n.left = left;
        n.reason = reason;
        ledger().set(n.id, s, tries, left, reason, sig);
    }

    /** The action node's own summary is the sum of its chunks. */
    static void settle(Node action) {
        int open = 0, deferred = 0, left = 0;
        for (Node k : action.kids) {
            if (k.state == State.DEFERRED || k.state == State.FAILED) { deferred++; left += k.left; }
            else if (k.state != State.DONE) open++;
        }
        action.left = left;
        action.state = open > 0 ? State.RUNNING : deferred > 0 ? State.DEFERRED : State.DONE;
    }

    // ------------------------------------------------------------------ script jobs: job "name" ... end

    /** Marks a chunk or job the user skipped in the Queue panel: DONE, and never looked at again. */
    static final String SKIPPED = "skipped";

    /** A value that cannot be saved (a function ...). */
    static final Object NOT_PLAIN = new Object();

    /** A line for the player; silent outside the game (tests). */
    static void say(String s) {
        try { Util.msg(s); } catch (Throwable ignored) { }
    }

    /** The name a job is saved under: "/" splits ids, so it becomes "-"; "script" is the name for lines outside jobs. */
    static String jobName(String raw, int line) {
        String n = raw == null ? "" : raw.trim().replace('/', '-');
        if (n.isEmpty()) throw Values.error("A job needs a name: job \"build\" ... end", line);
        if (n.equals(NO_JOB)) throw Values.error("The job name 'script' is reserved for the lines outside jobs. Pick another name.", line);
        return n;
    }

    static boolean isDone(String job) {
        return ledger().state(job, null) == State.DONE;
    }

    /** The variables a finished job saved (restored values), or null. */
    static Map<String, Object> savedVars(String job) {
        Ledger.Entry e = ledger().get(job);
        if (e == null || e.vars == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        e.vars.forEach((k, v) -> out.put(k, unpack(v)));
        return out;
    }

    /** The plain values (see pack: a copy) of the variables the script has defined itself, not the built-in ones: to see what a job changed and to save it. */
    static Map<String, Object> outerValues(Ast.Env env) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Ast.Env e = env; e != null && e.parent != null; e = e.parent) {
            for (var v : e.vars.entrySet()) {
                if (out.containsKey(v.getKey())) continue;
                Object p = pack(v.getValue(), 0);
                if (p != NOT_PLAIN) out.put(v.getKey(), p);
            }
        }
        return out;
    }

    /** A job starts: RUNNING in the ledger, and the actions it starts get ids under its name. */
    static void begin(String job) {
        currentJob = job;
        Node n = jobNode();
        set(n, State.RUNNING, n.tries + 1, 0, null, null);
    }

    /** The job's body ended with an error. */
    static void fail(String job, String message) {
        Node n = jobs.computeIfAbsent(job, k -> new Node(Kind.JOB, k, k, root));
        set(n, State.FAILED, n.tries, 0, message, null);
    }

    /**
     * The body ended normally. DONE (with the variables it gave values to) unless a chunk of one of its actions is
     * still deferred or failed: then DEFERRED, so the next run goes into the job again (finished chunks are skipped
     * by their own entries) and no variables are saved.
     */
    static void finish(String job, Map<String, Object> vars) {
        Node n = jobs.computeIfAbsent(job, k -> new Node(Kind.JOB, k, k, root));
        int open = 0;
        // only the actions this run started: entries of actions that are no longer in the script (their id holds the
        // old line number, e.g. clear_area@116 after it moved to @120) must not keep the job open for ever
        for (String id : ledger().ids(job, null)) {
            if (id.equals(job)) continue;
            boolean current = false;
            for (Node k : n.kids) if (Ledger.under(id, k.id)) { current = true; break; }
            if (!current) continue;
            State s = ledger().state(id, null);
            if (s == State.DEFERRED || s == State.FAILED) open++;
        }
        if (open > 0) {
            set(n, State.DEFERRED, n.tries, open, open + " chunk" + (open == 1 ? "" : "s") + " still open", null);
            say("Job '" + job + "' ended with " + open + " chunk" + (open == 1 ? "" : "s") + " open; the next Run goes into it again.");
            return;
        }
        ledger().putVars(job, vars.isEmpty() ? null : vars);
        set(n, State.DONE, n.tries, 0, null, null);
    }

    private static final int MAX_DEPTH = 12;

    /** A script value as plain data for the ledger: numbers, text, true/false, null, lists, maps with text keys, positions. NOT_PLAIN otherwise. */
    static Object pack(Object v, int depth) {
        if (v == null || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Double d) return Double.isFinite(d) ? d : NOT_PLAIN;
        if (depth > MAX_DEPTH) return NOT_PLAIN;
        if (v instanceof Values.Pos p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("$pos", List.of((double) p.x(), (double) p.y(), (double) p.z()));
            return m;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                Object p = pack(o, depth + 1);
                if (p == NOT_PLAIN) return NOT_PLAIN;
                out.add(p);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) return NOT_PLAIN;
                Object p = pack(e.getValue(), depth + 1);
                if (p == NOT_PLAIN) return NOT_PLAIN;
                out.put(k, p);
            }
            return out;
        }
        return NOT_PLAIN;
    }

    /** The reverse of pack (also for what Gson read back: numbers are doubles, maps are maps). */
    static Object unpack(Object v) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(unpack(o));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            if (m.size() == 1 && m.get("$pos") instanceof List<?> c && c.size() == 3
                    && c.get(0) instanceof Number x && c.get(1) instanceof Number y && c.get(2) instanceof Number z) {
                return new Values.Pos(x.intValue(), y.intValue(), z.intValue());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, o) -> out.put(String.valueOf(k), unpack(o)));
            return out;
        }
        if (v instanceof Number n) return n.doubleValue();
        return v;
    }

    /** "chunk 7/26" for the chunk that is being worked on now (done ones + 1 of all); null when no chunk runs. */
    static String chunkText() {
        for (Node job : root.kids) {
            for (Node a : job.kids) {
                int done = 0;
                boolean running = false;
                for (Node c : a.kids) {
                    if (c.state == State.DONE) done++;
                    else if (c.state == State.RUNNING) running = true;
                }
                if (running) return "chunk " + (done + 1) + "/" + a.kids.size();
            }
        }
        return null;
    }

    /** Snapshot of a spatial chunk for UI/HUD progress reporting. */
    public record ChunkSnapshot(String id, int index, int total, Ledger.State state, BlockPos min, BlockPos max, int left, String reason) {}

    /** Returns chunk snapshots for the currently active chunked action, or empty when none is running. */
    public static List<ChunkSnapshot> activeChunks() {
        Node active = null;
        for (Node job : root.kids) {
            for (Node a : job.kids) {
                if (a.kids.isEmpty()) continue;
                boolean isRunning = a.state == State.RUNNING;
                if (!isRunning) {
                    for (Node c : a.kids) {
                        if (c.state == State.RUNNING) {
                            isRunning = true;
                            break;
                        }
                    }
                }
                if (isRunning) active = a;
            }
        }
        if (active == null) return List.of();
        int total = active.kids.size();
        List<ChunkSnapshot> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Node c = active.kids.get(i);
            int idx = c.box != null ? c.box.index() : i;
            BlockPos min = c.box != null ? c.box.min() : BlockPos.ZERO;
            BlockPos max = c.box != null ? c.box.max() : BlockPos.ZERO;
            out.add(new ChunkSnapshot(c.id, idx, total, c.state, min, max, c.left, c.reason));
        }
        return out;
    }

    // ------------------------------------------------------------------ the Queue panel

    /** One row of the Queue panel (depth 0 job, 1 action, 2 chunk). open = chunks below it that are not DONE. */
    record Row(int depth, String id, String label, State state, int left, String reason, int open) {}

    /** The ledger as rows for the panel: per job (an entry without a slash, or the name before the first slash), its actions, their chunks. */
    static List<Row> rows() {
        Ledger l = ledger();
        Map<String, Ledger.Entry> jobEntries = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> tree = new LinkedHashMap<>();   // job -> action id -> chunk ids
        for (String id : l.ids(null, null)) {
            int slash = id.indexOf('/');
            if (slash < 0) { jobEntries.put(id, l.get(id)); tree.computeIfAbsent(id, k -> new LinkedHashMap<>()); continue; }
            Matcher m = CHUNK.matcher(id);
            String job = id.substring(0, slash);
            if (m.matches()) tree.computeIfAbsent(job, k -> new LinkedHashMap<>()).computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(id);
        }
        List<Row> out = new ArrayList<>();
        for (var je : tree.entrySet()) {
            String job = je.getKey();
            Ledger.Entry e = jobEntries.get(job);
            int jobOpen = 0, jobLeft = 0;
            List<Row> below = new ArrayList<>();
            for (var ae : je.getValue().entrySet()) {
                int open = 0, left = 0, deferred = 0, running = 0;
                List<Row> chunks = new ArrayList<>();
                for (String cid : ae.getValue()) {
                    Ledger.Entry c = l.get(cid);
                    Matcher m = CHUNK.matcher(cid);
                    m.matches();
                    if (c.state != State.DONE) open++;
                    if (c.state == State.DEFERRED || c.state == State.FAILED) { deferred++; left += c.left; }
                    if (c.state == State.RUNNING) running++;
                    chunks.add(new Row(2, cid, m.group(2) + "," + m.group(3) + (m.group(4) == null ? "" : m.group(4)), c.state, c.left, c.reason, 0));
                }
                State s = running > 0 ? State.RUNNING : deferred > 0 ? State.DEFERRED : open > 0 ? State.PENDING : State.DONE;
                below.add(new Row(1, ae.getKey(), ae.getKey().substring(job.length() + 1), s, left, null, open));
                below.addAll(chunks);
                jobOpen += open;
                jobLeft += left;
            }
            State js = e != null ? e.state : jobOpen > 0 ? State.PENDING : State.DONE;
            out.add(new Row(0, job, job, js, jobLeft, e == null ? null : e.reason, jobOpen));
            out.addAll(below);
        }
        return out;
    }

    /**
     * Skip in the Queue panel: the entry and everything below it count as DONE with the reason "skipped". A chunk is
     * never entered again (a schematic chunk is not even re-counted), an action's open chunks are all closed, a job is
     * skipped on the next run (its variables come back only if an earlier run saved them, otherwise they stay
     * undefined). Returns how many entries changed.
     */
    static int skip(String id) {
        Ledger l = ledger();
        int n = 0;
        for (String x : l.ids(id, null)) {
            Ledger.Entry e = l.get(x);
            if (e.state == State.DONE) continue;
            l.set(x, State.DONE, e.tries, 0, SKIPPED, null);
            n++;
        }
        if (id.indexOf('/') < 0 && l.get(id) == null) { l.set(id, State.DONE, 0, 0, SKIPPED, null); n++; }
        return n;
    }

    // ------------------------------------------------------------------ chat: /queue jobs | reset | retry

    private static final Pattern CHUNK = Pattern.compile("^(.*)/(-?\\d+),(-?\\d+)(,-?\\d+)?$");

    /** The ledger as lines: per action (or job) the counts, then what is not done. */
    static List<String> describe() {
        Ledger l = ledger();
        List<String> out = new ArrayList<>();
        Map<String, int[]> perAction = new LinkedHashMap<>();   // [done, deferred/failed, other]
        Map<String, List<String>> notDone = new LinkedHashMap<>();
        List<String> jobLines = new ArrayList<>();
        for (String id : l.ids(null, null)) {
            Ledger.Entry e = l.get(id);
            Matcher m = CHUNK.matcher(id);
            if (m.matches()) {
                int[] c = perAction.computeIfAbsent(m.group(1), k -> new int[3]);
                if (e.state == State.DONE) c[0]++;
                else {
                    if (e.state == State.DEFERRED || e.state == State.FAILED) c[1]++; else c[2]++;
                    notDone.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add("    " + m.group(2) + "," + m.group(3) + (m.group(4) == null ? "" : m.group(4)) + ": " + e.state.name().toLowerCase()
                            + (e.left > 0 ? ", " + e.left + " left" : "") + (e.reason != null ? " (" + e.reason + ")" : ""));
                }
            } else if (id.indexOf('/') < 0) {
                jobLines.add("  job " + id + ": " + e.state.name().toLowerCase() + (e.reason != null ? " (" + e.reason + ")" : ""));
            }
        }
        if (l.size() == 0) { out.add("The job ledger is empty (" + l.path() + ")."); return out; }
        out.add("Jobs in " + l.path().getFileName() + ":");
        out.addAll(jobLines);
        for (var e : perAction.entrySet()) {
            int[] c = e.getValue();
            out.add("  " + e.getKey() + ": " + c[0] + " of " + (c[0] + c[1] + c[2]) + " chunks done" + (c[1] > 0 ? ", " + c[1] + " deferred" : ""));
            List<String> nd = notDone.getOrDefault(e.getKey(), List.of());
            for (int i = 0; i < Math.min(8, nd.size()); i++) out.add(nd.get(i));
            if (nd.size() > 8) out.add("    ... " + (nd.size() - 8) + " more");
        }
        return out;
    }

    /** The ids to act on for a job argument: the job's name, or any id prefix (null = everything). */
    static String reset(String job) {
        Ledger l = ledger();
        int n = l.reset(job);
        root = new Node(Kind.JOB, "", "script", null);
        jobs.clear();
        return n == 0 ? "Nothing to reset" + (job == null ? "" : " for '" + job + "'") + "." : "Reset " + n + " ledger entr" + (n == 1 ? "y" : "ies") + (job == null ? "" : " of '" + job + "'") + ".";
    }

    static String retry(String job) {
        int n = ledger().retry(job);
        return n == 0 ? "Nothing to retry for '" + job + "' (only deferred, failed or interrupted entries can be retried)." : "Retry: " + n + " entr" + (n == 1 ? "y" : "ies") + " of '" + job + "' open again; the next Run takes them up.";
    }
}
