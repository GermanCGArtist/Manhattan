package dev.baritonequeue;

import dev.baritonequeue.Ledger.State;

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
final class Jobs {
    private Jobs() {}

    /** The ledger file is per world and script; scripts have no names yet, so there is one per world. */
    static final String SCRIPT = "main";
    static final String NO_JOB = "script";

    enum Kind { JOB, ACTION, CHUNK }

    static final class Node {
        final Kind kind;
        final String id;
        final String label;
        final Node parent;
        final List<Node> kids = new ArrayList<>();
        Chunker.Box box;
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

    /** The name of the script job that runs now (stage 3 sets it); null outside a job block. */
    static String currentJob;

    static Node root() { return root; }

    // ------------------------------------------------------------------ ledger

    /** The ledger of this world (the one in use, until the world changes). */
    static Ledger ledger() {
        Path p;
        try {
            p = Ledger.fileFor(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), Capture.world(), SCRIPT);
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

    /** A script starts: a fresh tree and counters. Returns the "Resuming" text when the ledger has state, else null. */
    static String startRun() {
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

    /** A chunk under an action. */
    static Node chunk(Node action, Chunker.Box box) {
        Node c = new Node(Kind.CHUNK, action.id + "/" + box.key(), box.text(), action);
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

    // ------------------------------------------------------------------ chat: /queue jobs | reset | retry

    private static final Pattern CHUNK = Pattern.compile("^(.*)/(-?\\d+),(-?\\d+)$");

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
                    notDone.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add("    " + m.group(2) + "," + m.group(3) + ": " + e.state.name().toLowerCase()
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
