package dev.baritonequeue;

import java.util.ArrayList;
import java.util.List;

/** Runs scripts through the real parser and interpreter, outside Minecraft. The main thread plays the game thread. */
public class LangTest {
    static final List<String> out = new ArrayList<>();
    static int fails, passes;

    static String run(String src) {
        out.clear();
        List<Ast.Stmt> prog;
        try {
            prog = Parser.parse(src);
        } catch (Parser.ParseError e) {
            return "PARSE line " + e.line + ": " + e.getMessage();
        }
        Ast.Env env = new Ast.Env(Builtins.root());
        env.define("out", new Values.Callable() {
            public Object call(Interp in, List<Object> args, int line) {
                List<String> p = new ArrayList<>();
                for (Object o : args) p.add(Values.str(o));
                out.add(String.join(" ", p));
                return null;
            }
            public String name() { return "out"; }
        });
        Runner r = new Runner();
        Interp[] holder = new Interp[1];
        Fiber f = new Fiber("test", () -> Ast.run(prog, holder[0], env));
        holder[0] = new Interp(r, f);
        int ticks = 0;
        while (!f.done && ticks++ < 100000) {
            if (!f.resume()) return "TIMEOUT";
            if (f.wanted != null && f.action == null) {   // an action: just finish it with null
                f.action = new Actions.Action() {
                    void start() {}
                    void tick() {}
                };
                f.action.finish(null);
            }
        }
        if (f.failure instanceof Values.ScriptError se) out.add("ERROR line " + se.line + ": " + se.getMessage());
        else if (f.failure != null) { f.failure.printStackTrace(); out.add("CRASH " + f.failure); }
        if (f.exited) out.add("EXIT");
        return String.join(" | ", out);
    }

    static void check(String name, String src, String expected) {
        String got = run(src);
        if (got.equals(expected)) { passes++; return; }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
    }

    static void same(String name, Object got, Object expected) {
        if (String.valueOf(got).equals(String.valueOf(expected))) { passes++; return; }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: " + expected + "\n  got:      " + got);
    }

    /** The capture sheet's text handling. */
    static void sheet() {
        String t = CaptureSheet.header("w", "main");
        t = CaptureSheet.add(t, CaptureSheet.PLACES, "let dump_chest = pos(1, 2, 3)   // dump: x; chest");
        t = CaptureSheet.add(t, CaptureSheet.AREAS, "area_box(\"mine_1\", pos(5, 6, 7), pos(1, 2, 3))   // mine; 5x5x5");
        t = CaptureSheet.add(t, CaptureSheet.PLACES, "// let bed = pos(4, 5, 6)   // bed");
        same("sheet sections", t, CaptureSheet.header("w", "main")
                + "\n// --- places\nlet dump_chest = pos(1, 2, 3)   // dump: x; chest\n// let bed = pos(4, 5, 6)   // bed\n\n// --- areas\n"
                + "area_box(\"mine_1\", pos(5, 6, 7), pos(1, 2, 3))   // mine; 5x5x5\n");
        same("sheet names", CaptureSheet.names(t), "[dump_chest, bed, mine_1]");
        same("sheet unique", CaptureSheet.unique("mine", CaptureSheet.names(t), true) + " " + CaptureSheet.unique("dump_chest", CaptureSheet.names(t), false)
                + " " + CaptureSheet.unique("tool_chest", CaptureSheet.names(t), false), "mine_2 dump_chest_2 tool_chest");
        var es = CaptureSheet.entries(t);
        same("sheet entries", es.size(), 3);
        same("sheet point", es.get(0).name() + " " + es.get(0).role() + " " + es.get(0).active() + " " + java.util.Arrays.toString(es.get(0).pos()), "dump_chest dump true [1, 2, 3]");
        same("sheet off", es.get(1).name() + " " + es.get(1).role() + " " + es.get(1).active(), "bed bed false");
        same("sheet box", es.get(2).name() + " " + es.get(2).role() + " " + java.util.Arrays.toString(es.get(2).box()), "mine_1 mine [1, 2, 3, 5, 6, 7]");
        same("sheet clean", CaptureSheet.cleanName(" Storage East! ") + " " + CaptureSheet.cleanName("2nd"), "storage_east _2nd");
        // the sheet is plain script: it parses
        try {
            Parser.parse(t.replace("area_box", "out"));
            passes++;
        } catch (Parser.ParseError e) {
            fails++;
            System.out.println("FAIL sheet parses: " + e.getMessage());
        }
    }

    /** The chunk loop's pure parts: boxes, alignment, nearest-open order. */
    static void chunker() {
        var whole = new Chunker.Box(100, 60, -50, 229, 80, 20);   // 130 x 71 blocks
        var cs = Chunker.split(whole, 32);
        same("chunk count", cs.size(), 5 * 3);
        same("chunk first", cs.get(0), new Chunker.Box(100, 60, -50, 131, 80, -19));
        same("chunks aligned to the min corner", cs.get(3).x0() + " " + cs.get(3).z0() + " " + cs.get(1).z0(), "132 -50 -18");
        same("chunk last is smaller", cs.get(cs.size() - 1), new Chunker.Box(228, 60, 14, 229, 80, 20));
        long cells = 0;
        for (var b : cs) cells += (long) b.sx() * b.sy() * b.sz();
        same("chunks cover the box", cells, (long) whole.sx() * whole.sy() * whole.sz());
        same("small box is one chunk", Chunker.split(new Chunker.Box(0, 0, 0, 31, 5, 31), 32).size(), 1);
        same("chunk key", cs.get(0).key() + " " + cs.get(0).text(), "100,-50 100 -50 .. 131 -19");
        // nearest open chunk by centre
        var line = Chunker.split(new Chunker.Box(0, 0, 0, 319, 10, 9), 32);   // 10 chunks in a row
        var open = new java.util.HashSet<>(line);
        same("next from the west end", Chunker.next(line, new net.minecraft.core.BlockPos(2, 5, 5), open::contains).x0(), 0);
        same("next from the middle", Chunker.next(line, new net.minecraft.core.BlockPos(170, 5, 5), open::contains).x0(), 160);
        open.remove(line.get(5));
        same("next skips a closed chunk", Chunker.next(line, new net.minecraft.core.BlockPos(170, 5, 5), open::contains).x0(), 128);   // 128 and 192 are equally far: the earlier one
        open.clear();
        same("next with none open", Chunker.next(line, new net.minecraft.core.BlockPos(0, 0, 0), open::contains), null);
        open.addAll(line);
        var up = Chunker.upcoming(line, line.get(3), 4, open::contains);
        same("upcoming order", up.stream().map(b -> String.valueOf(b.x0() / 32)).collect(java.util.stream.Collectors.joining(",")), "3,2,1,0");
        same("upcoming runs out", Chunker.upcoming(line, line.get(8), 20, open::contains).size(), 10);
    }

    /** The ledger file: save, load, states, reset, retry; the job queue's description and ids. */
    static void ledger() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("manhattan-ledger");
            java.nio.file.Path f = Ledger.fileFor(dir, "my_world", "main");
            Ledger l = Ledger.load(f);
            same("ledger starts empty", l.size(), 0);
            String a = "script/schem_build@12(all,add)#1";
            l.set(a + "/0,0", Ledger.State.DONE, 1, 0, null, "sig1");
            l.set(a + "/32,0", Ledger.State.DEFERRED, 2, 7, "attached: 7 left", "sig1");
            l.set(a + "/64,0", Ledger.State.RUNNING, 1, 30, null, "sig1");
            l.putVars("build", java.util.Map.of("n", 5.0));
            same("ledger file written", java.nio.file.Files.exists(f), true);
            same("ledger no temp file left", java.nio.file.Files.exists(f.resolveSibling(f.getFileName() + ".tmp")), false);
            Ledger r = Ledger.load(f);
            same("ledger size", r.size(), 4);
            same("ledger state", r.state(a + "/0,0", "sig1") + " " + r.state(a + "/32,0", "sig1") + " " + r.state(a + "/64,0", "sig1"), "DONE DEFERRED RUNNING");
            var e = r.get(a + "/32,0");
            same("ledger fields", e.tries + " " + e.left + " " + e.reason + " " + e.sig, "2 7 attached: 7 left sig1");
            same("ledger vars", r.get("build").vars, "{n=5.0}");
            same("ledger stale signature is ignored", r.state(a + "/0,0", "other"), "PENDING");
            same("ledger unknown id", r.state("nope", null), "PENDING");
            same("ledger ids by state", r.ids(a, Ledger.State.DONE), "[" + a + "/0,0]");
            same("ledger under", Ledger.under(a + "/0,0", a) + " " + Ledger.under(a + "#2/0,0", a), "true false");
            same("ledger retry", r.retry(a), 2);
            same("ledger retried states", r.state(a + "/32,0", null) + " " + r.get(a + "/32,0").tries + " " + r.get(a + "/0,0").state, "PENDING 0 DONE");
            Jobs.useLedger(r);
            same("describe counts", String.join("|", Jobs.describe()).contains("1 of 3 chunks done"), true);
            same("ledger reset one", r.reset(a + "/0,0"), 1);
            same("ledger reset job", r.reset(a), 2);
            same("ledger reset all", r.reset(null), 1);
            same("ledger reset saved", Ledger.load(f).size(), 0);
            // the start of a run reports what is done
            Ledger g = Ledger.load(f);
            g.set(a + "/0,0", Ledger.State.DONE, 1, 0, null, null);
            g.set(a + "/32,0", Ledger.State.PENDING, 0, 0, null, null);
            g.set("canal", Ledger.State.DONE, 1, 0, null, null);
            g.set("walls", Ledger.State.FAILED, 1, 0, "boom", null);
            Jobs.useLedger(g);
            same("resume text", Jobs.startRun(), "Resuming: 1 of 2 jobs done (1 of 2 chunks).");
            Jobs.useLedger(Ledger.load(dir.resolve("none.json")));
            same("no resume text on a fresh ledger", Jobs.startRun(), null);
            // job ids
            Jobs.Node n1 = Jobs.action("schem_build", 12, "all,add", true), n2 = Jobs.action("schem_build", 12, "all,add", true);
            same("job ids", n1.id + " " + n2.id, "script/schem_build@12(all,add)#1 script/schem_build@12(all,add)#2");
            same("chunk id", Jobs.chunk(n1, Chunker.split(new Chunker.Box(100, 60, -50, 229, 80, 20), 32).get(0)).id, "script/schem_build@12(all,add)#1/100,-50");
        } catch (Exception ex) {
            fails++;
            System.out.println("FAIL ledger: " + ex);
            ex.printStackTrace(System.out);
        }
    }

    /** chunk_needed's engine: items per chunk from the world where it is loaded, from the plan alone where it is not. */
    static void chunkNeeded() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable t) {
            System.out.println("  (chunk_needed test skipped: Minecraft cannot start offline: " + t + ")");
            return;
        }
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        Schem.Plan plan = new Schem.Plan();
        plan.name = "test";
        plan.min = new net.minecraft.core.BlockPos(0, 0, 0);
        plan.sx = 64;
        plan.sy = 1;
        plan.sz = 2;
        plan.st = new net.minecraft.world.level.block.state.BlockState[64 * 2];
        java.util.Arrays.fill(plan.st, stone);
        plan.excluded = new java.util.BitSet(plan.st.length);
        var chunks = Chunker.split(plan.box(), 32);
        same("test plan has two chunks", chunks.size(), 2);
        var placed = new java.util.HashSet<net.minecraft.core.BlockPos>();   // stone already there
        Schem.World world = new Schem.World() {
            public boolean loaded(net.minecraft.core.BlockPos p) { return p.getX() < 32; }
            public net.minecraft.world.level.block.state.BlockState at(net.minecraft.core.BlockPos p) { return placed.contains(p) ? stone : air; }
        };
        var stoneItem = net.minecraft.world.item.Items.STONE;
        same("needed: loaded chunk", Schem.needed(plan, Schem.Mode.ADD, world, chunks.subList(0, 1)).get(stoneItem), 64);
        same("needed: unloaded chunk from the plan alone", Schem.needed(plan, Schem.Mode.ADD, world, chunks.subList(1, 2)).get(stoneItem), 64);
        same("needed: both", Schem.needed(plan, Schem.Mode.ADD, world, chunks).get(stoneItem), 128);
        for (int x = 0; x < 32; x++) placed.add(new net.minecraft.core.BlockPos(x, 0, 0));
        same("needed: half built", Schem.needed(plan, Schem.Mode.ADD, world, chunks).get(stoneItem), 96);
        same("count with a box ignores the rest", Schem.count(plan, Schem.Mode.ADD, world, chunks.get(0), false).missing, 32);
        same("count of the unloaded chunk without the assumption", Schem.count(plan, Schem.Mode.ADD, world, chunks.get(1), false).unloaded, 64);
        same("plan cells in a box", plan.cells(chunks.get(1)), 64);
        // markOpen on arrival: only the box, only loaded cells
        plan.markOpen(world, chunks.get(0));
        same("markOpen box: open cell", plan.wasOpen(plan.idx(0, 0, 1)), true);
        same("markOpen box: built cell", plan.wasOpen(plan.idx(0, 0, 0)), false);
        same("markOpen box: other chunk untouched", plan.wasOpen(plan.idx(40, 0, 1)), false);
        // the wrapper answers only inside its box, with Baritone's coordinates relative to the box's corner
        var w = new Schem.Wrapper(plan, Schem.Mode.ADD, Schem.Phase.FULL, null, chunks.get(1));
        same("wrapper size and origin", w.widthX() + " " + w.heightY() + " " + w.lengthZ() + " " + w.origin(), "32 1 2 " + new net.minecraft.core.BlockPos(32, 0, 0));
        same("wrapper in box (add mode, cell not marked open)", w.inSchematic(0, 0, 0, air), true);
        same("wrapper outside box", w.inSchematic(32, 0, 0, air), false);
        same("wrapper desired state", w.desiredState(5, 0, 1, air, null), stone);
    }

    /** Chunked clear_area / fill_water / drain: the selection as chunks, the ledger rule. */
    static void chunkedMining() {
        // a selection of two boxes: 100 x 10 x 40 and, 300 blocks away, 20 x 5 x 20
        List<int[]> sel = List.of(new int[]{0, 60, 0, 99, 69, 39}, new int[]{400, 60, 10, 419, 64, 29});
        var cs = Actions.Chunked.chunksOf(sel, 32);
        same("mining chunks: only those that hold a part of the selection", cs.size(), 4 * 2 + 2);   // 4 x 2 over the first box, 2 over the second (x 384 and 416 columns)
        same("mining chunks: bounds", Actions.Chunked.bounds(sel), new Chunker.Box(0, 60, 0, 419, 69, 39));
        same("mining chunks: none for no selection", Actions.Chunked.chunksOf(List.of(), 32).size(), 0);
        same("small selection is one chunk", Actions.Chunked.chunksOf(List.of(new int[]{5, 1, 5, 20, 9, 30}), 32).size(), 1);
        var nar = Actions.Chunked.narrow(sel, new Chunker.Box(96, 60, 32, 127, 69, 39));
        same("narrow: cut to the chunk", nar.size() + " " + java.util.Arrays.toString(nar.get(0)), "1 [96, 60, 32, 99, 69, 39]");
        var far = Actions.Chunked.narrow(sel, new Chunker.Box(384, 60, 0, 415, 69, 31));
        same("narrow: the other box", java.util.Arrays.toString(far.get(0)), "[400, 60, 10, 415, 64, 29]");
        same("narrow: nothing there", Actions.Chunked.narrow(sel, new Chunker.Box(200, 60, 0, 231, 69, 31)).size(), 0);
        long cells = 0;
        for (var c : cs) for (int[] b : Actions.Chunked.narrow(sel, c)) cells += (b[3] - b[0] + 1L) * (b[4] - b[1] + 1L) * (b[5] - b[2] + 1L);
        same("narrowed chunks cover the selection once", cells, 100L * 10 * 40 + 20L * 5 * 20);
        same("signature follows the selection", Actions.Chunked.signature(sel, 32).equals(Actions.Chunked.signature(sel, 32))
                + " " + Actions.Chunked.signature(sel, 32).equals(Actions.Chunked.signature(sel.subList(0, 1), 32)), "true false");
        // loaded: every chunk column the boxes touch
        java.util.function.Predicate<net.minecraft.core.BlockPos> loadedX = p -> p.getX() < 50;
        same("loaded boxes", Actions.Chunked.loadedBoxes(List.of(new int[]{0, 60, 0, 40, 69, 39}), loadedX) + " " + Actions.Chunked.loadedBoxes(List.of(new int[]{0, 60, 0, 60, 69, 39}), loadedX), "true false");
        same("loaded boxes: the far edge counts", Actions.Chunked.loadedBoxes(List.of(new int[]{40, 60, 0, 50, 69, 5}), loadedX), false);

        // the ledger rule: DONE is never entered again, no recount; anything else is worked on; a stale entry does not count
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("manhattan-mining");
            Ledger l = Ledger.load(Ledger.fileFor(dir, "w", "main"));
            Jobs.useLedger(l);
            Jobs.startRun();
            Jobs.Node act = Jobs.action("clear_area", 7, "2", true);
            String sig = Actions.Chunked.signature(sel, 32);
            same("mining action id", act.id, "script/clear_area@7(2)#1");
            Jobs.Node c0 = Jobs.chunk(act, cs.get(0)), c1 = Jobs.chunk(act, cs.get(1)), c2 = Jobs.chunk(act, cs.get(2));
            same("mining chunk id", c0.id, "script/clear_area@7(2)#1/0,0");
            l.set(c0.id, Ledger.State.DONE, 1, 0, null, sig);
            l.set(c1.id, Ledger.State.DEFERRED, 2, 5, "5 blocks left", sig);
            l.set(c2.id, Ledger.State.DONE, 1, 0, null, "another selection");
            same("verdict DONE: skipped", Actions.Chunked.verdict(l.get(c0.id, sig)), ChunkLoop.Verdict.SKIP);
            same("verdict DEFERRED: worked on", Actions.Chunked.verdict(l.get(c1.id, sig)), ChunkLoop.Verdict.RUN);
            same("verdict no entry: worked on", Actions.Chunked.verdict(l.get("script/clear_area@7(2)#1/9,9", sig)), ChunkLoop.Verdict.RUN);
            same("verdict stale entry: worked on", Actions.Chunked.verdict(l.get(c2.id, sig)), ChunkLoop.Verdict.RUN);
            l.reset(null);
            same("verdict after reset", Actions.Chunked.verdict(l.get(c0.id, sig)), ChunkLoop.Verdict.RUN);
        } catch (Exception ex) {
            fails++;
            System.out.println("FAIL chunked mining ledger: " + ex);
            ex.printStackTrace(System.out);
        }
    }

    /** Protection: a block an enabled schematic wants is not mined. */
    static void wantedAt() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable t) {
            System.out.println("  (wantedAt test skipped: Minecraft cannot start offline: " + t + ")");
            return;
        }
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var dirt = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        Schem.Plan plan = new Schem.Plan();
        plan.name = "test";
        plan.min = new net.minecraft.core.BlockPos(10, 60, 10);
        plan.sx = 4;
        plan.sy = 2;
        plan.sz = 1;
        plan.st = new net.minecraft.world.level.block.state.BlockState[8];
        plan.st[plan.idx(0, 0, 0)] = stone;
        plan.st[plan.idx(1, 0, 0)] = air;      // the schematic wants air here
        plan.st[plan.idx(2, 0, 0)] = water;    // fluids are not protected
        plan.st[plan.idx(3, 0, 0)] = dirt;
        plan.excluded = new java.util.BitSet(8);
        net.minecraft.core.BlockPos p0 = new net.minecraft.core.BlockPos(10, 60, 10);
        same("wantedAt: the wanted block is there", Schem.wantedAt(plan, p0, stone), true);
        same("wantedAt: another block where stone is wanted is not protected", Schem.wantedAt(plan, p0, dirt), false);
        same("wantedAt: schematic air protects nothing", Schem.wantedAt(plan, p0.east(), stone), false);
        same("wantedAt: fluids are not protected", Schem.wantedAt(plan, p0.east(2), water), false);
        same("wantedAt: second wanted block", Schem.wantedAt(plan, p0.east(3), dirt), true);
        same("wantedAt: outside the plan", Schem.wantedAt(plan, p0.east(4), stone) + " " + Schem.wantedAt(plan, p0.above(10), stone) + " " + Schem.wantedAt(plan, p0.north(), stone), "false false false");
        same("wantedAt: no plan", Schem.wantedAt(null, p0, stone), false);
        Schem.useWanted(plan);
        same("wantedAt: the cached plan", Schem.wantedAt(p0, stone) + " " + Schem.wantedAt(p0.above(), stone), "true false");
        Schem.useWanted(null);
        same("wantedAt: cleared", Schem.wantedAt(p0, stone), false);
    }

    public static void main(String[] a) {
        check("arith", "out(1 + 2 * 3, (1 + 2) * 3, 7 / 2, 7 % 3, -2 * 3)", "7 9 3.5 1 -6");
        check("strings", "let n = 5\nout(\"n is {n}, twice {n * 2}\", 'x{y}', \"a\" + 1)", "n is 5, twice 10 x{y} a1");
        check("if", "let x = 3\nif x > 2 then out(\"big\") elif x > 1 then out(\"mid\") else out(\"small\") end", "big");
        check("if-multiline", "let x = 1\nif x > 2\n  out(\"big\")\nelse if x == 1\n  out(\"one\")\nelse\n  out(\"other\")\nend", "one");
        check("while", "let i = 0\nwhile i < 3\n  out(i)\n  i += 1\nend", "0 | 1 | 2");
        check("repeat", "let s = 0\nrepeat 4\n  s += 2\nend\nout(s)", "8");
        check("for list", "for x in [\"a\", \"b\"]\n  out(x)\nend", "a | b");
        check("for index", "for i, x in [\"a\", \"b\"]\n  out(i, x)\nend", "0 a | 1 b");
        check("for range", "let t = 0\nfor i in range(1, 5) do t += i end\nout(t)", "10");
        check("for map", "let m = {a: 1, b: 2}\nfor k, v in m\n  out(k, v)\nend", "a 1 | b 2");
        check("break/continue", "for i in range(10)\n  if i == 2 then continue end\n  if i == 4 then break end\n  out(i)\nend", "0 | 1 | 3");
        check("loop", "let n = 0\nloop\n  n += 1\n  if n >= 5 then break end\nend\nout(n)", "5");
        check("func", "func add(a, b)\n  return a + b\nend\nout(add(2, 3))", "5");
        check("recursion", "func fib(n)\n  if n < 2 then return n end\n  return fib(n - 1) + fib(n - 2)\nend\nout(fib(15))", "610");
        check("closure", "func counter()\n  let c = 0\n  return func()\n    c += 1\n    return c\n  end\nend\nlet k = counter()\nk()\nk()\nout(k())", "3");
        check("lists", "let l = [3, 1, 2]\npush(l, 9)\nl.push(4)\nout(len(l), l[0], l[-1], sort(l), 2 in l, 7 not in l)", "5 3 4 [1, 2, 3, 4, 9] true true");
        check("maps", "let m = {}\nm.dirt = 5\nm[\"sand\"] = 2\nout(m.dirt + m[\"sand\"], keys(m))", "7 [\"dirt\", \"sand\"]");
        check("logic", "out(true and false, 1 or 2, not 0, null or \"x\")", "false 1 true x");
        check("try", "try\n  error(\"boom\")\n  out(\"no\")\ncatch e\n  out(\"caught\", e)\nend\nout(\"after\")", "caught boom | after");
        check("runtime error line", "let a = 1\nlet b = a + nothing", "ERROR line 2: 'nothing' is not defined. Give it a value first (let nothing = ...).");
        check("type error", "out(1 < \"a\")", "ERROR line 1: Cannot compare number with text.");
        check("stop", "out(1)\nstop()\nout(2)", "1 | EXIT");
        check("bare call", "func hi()\n  out(\"hi\")\nend\nhi", "hi");
        check("comments", "// top\nlet x = 2 // after\n/* block\ncomment */ out(x)", "2");
        check("semicolons", "let a = 1; let b = 2; out(a + b)", "3");
        check("baritone line", "let p = pos(1, 2, 3)\n#goto {p}\nout(\"done\")", "done");
        check("pos", "let p = pos(1, 2, 3)\nout(p.x + p.y + p.z, p + pos(1, 1, 1), \"{p}\")", "6 2 3 4 1 2 3");
        check("wait stmt", "wait 2\nout(\"w\")", "w");
        check("long loop yields", "let s = 0\nfor i in range(100000)\n  s += 1\nend\nout(s)", "100000");
        check("min/max/round", "out(min(3, 1, 2), max([4, 9, 2]), round(2.567, 2), floor(2.7), abs(-3))", "1 9 2.57 2 3");
        check("split/join", "out(join(split(\"a b  c\"), \"-\"), upper(\"x\"), replace(\"aXa\", \"X\", \"y\"))", "a-b-c X aya");
        check("parse err if", "if 1\n  out(1)\n", "PARSE line 1: 'if' (line 1) is never closed with 'end'.");
        check("parse err =", "if x = 3 then out(1) end", "PARSE line 1: Use == to compare (a single = gives a variable a value).");
        check("parse err end", "out(1)\nend", "PARSE line 2: 'end' without a matching start (if / while / for / repeat / loop / func / on / try).");
        check("parse err old style", "sel ca", "PARSE line 1: Unexpected 'ca' after the statement (one statement per line, or separate them with ;).");
        check("builtin shadow", "let count = 3\nout(count)", "3");
        check("assign builtin name", "len = 4\nout(len)", "4");
        check("nested funcs", "func outer(x)\n  func inner(y)\n    return y * 2\n  end\n  return inner(x) + 1\nend\nout(outer(5))", "11");
        check("arg count", "func f(a)\n  return a\nend\nout(f(1, 2))", "ERROR line 4: f() takes 1 value, not 2.");
        check("builtin args", "out(len())", "ERROR line 1: len is used like this: len(list | text | map)");
        check("chat line", "/say hello {1 + 1}\nout(\"ok\")", "ok");
        check("raw json kept", "/tellraw @a {\"text\":\"hi\"}\nout(\"ok\")", "ok");
        check("string index", "let s = \"abc\"\nout(s[1], len(s), s.length)", "b 3 3");
        check("random range", "let r = random(1, 6)\nout(r >= 1 and r <= 6)", "true");
        check("deep recursion", "func f(n)\n  return f(n + 1)\nend\nf(0)", "ERROR line 2: Too many calls inside each other (a function that never stops calling itself?).");
        same("ui mode default", Modules.get("ui.mode", 1), "vanilla");
        Modules.set("ui.mode", "docked", 1);
        same("ui mode docked", Modules.get("ui.mode", 1), "docked");
        Modules.set("ui.mode", "vanilla", 1);

        List<String> progScript = List.of(
                "// header comment",
                "let a = 1",
                "#goto 100 -60 100",
                "fill_water(\"dirt\")",
                "dump(pos(1, 2, 3))"
        );
        List<ProgressModel.Segment> segs = ProgressModel.computeSegments(progScript, true, 3, 0.5);
        same("progress segments count", String.valueOf(segs.size()), "4");
        same("progress seg 0 done", segs.get(0).state().name(), "DONE");
        same("progress seg 1 running", segs.get(1).state().name(), "RUNNING");
        same("progress seg 2 pending", segs.get(2).state().name(), "PENDING");
        same("progress overall pct", String.valueOf(ProgressModel.computeOverallPercent(segs, true)), "38");

        // Stage 3 view-models: LogBuffer and ChestIndexView
        LogBuffer.clear();
        LogBuffer.append("Script started.");
        LogBuffer.append("PROBLEM: No water source within 16 blocks.");
        LogBuffer.append("#goto 100 -60 100");
        same("log total", String.valueOf(LogBuffer.count(LogBuffer.Filter.ALL)), "3");
        same("log errors", String.valueOf(LogBuffer.count(LogBuffer.Filter.ERRORS)), "1");
        same("log baritone", String.valueOf(LogBuffer.count(LogBuffer.Filter.BARITONE)), "1");

        List<dev.survivalkit.Api.IndexedChest> sampleChests = List.of(
                new dev.survivalkit.Api.IndexedChest("minecraft:overworld", 100, -60, 102, System.currentTimeMillis(), "just now", 8,
                        java.util.Map.of("minecraft:dirt", 128, "minecraft:diamond_pickaxe", 2, "minecraft:bread", 8))
        );
        List<ChestIndexView.Row> allChestRows = ChestIndexView.filterAndSort(sampleChests, "", ChestIndexView.Category.ALL, false, ChestIndexView.SortColumn.COUNT, false);
        same("chest rows total", String.valueOf(allChestRows.size()), "3");
        same("chest top count item", allChestRows.get(0).itemName(), "dirt");
        List<ChestIndexView.Row> lowRows = ChestIndexView.filterAndSort(sampleChests, "", ChestIndexView.Category.ALL, true, ChestIndexView.SortColumn.ITEM, true);
        same("chest low stock count", String.valueOf(lowRows.size()), "2");
        List<ChestIndexView.Row> toolRows = ChestIndexView.filterAndSort(sampleChests, "pickaxe", ChestIndexView.Category.TOOLS, false, ChestIndexView.SortColumn.ITEM, true);
        same("chest tool search", String.valueOf(toolRows.size()), "1");

        // the old script of the user, converted
        List<String> old = List.of(
                "def_chest dirt 1794 74 -840",
                "def_chest dump 1787 74 -837",
                "",
                "area 1",
                "chest dirt get dirt to 512",
                "/buildtools terminator dirt",
                "chest dirt put dirt",
                "",
                "buildInLayers true      // layer settings only matter for the sel ca steps",
                "layerOrder true",
                "area 2",
                "sel ca",
                "chest dump put rest",
                "if not has dirt 64",
                "  wait until 06:30",
                "end",
                "addons off escape drain",
                "timeout off");
        List<String> conv = Migrate.convert(old);
        conv.forEach(l -> System.out.println("  | " + l));
        try {
            Parser.parse(String.join("\n", conv));
            passes++;
        } catch (Parser.ParseError e) {
            fails++;
            System.out.println("FAIL converted script does not parse: line " + e.line + ": " + e.getMessage());
        }
        sheet();
        chunker();
        ledger();
        chunkNeeded();
        chunkedMining();
        wantedAt();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
