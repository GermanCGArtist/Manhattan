package dev.baritonequeue;

import dev.baritonequeue.api.ManhattanAreas;

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

    static void assertTrue(String name, boolean condition) {
        if (condition) { passes++; return; }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: true\n  got:      false");
    }

    static void assertFalse(String name, boolean condition) {
        if (!condition) { passes++; return; }
        fails++;
        System.out.println("FAIL " + name + "\n  expected: false\n  got:      true");
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
        } catch (Exception ex) {
            fails++;
            System.out.println("FAIL ledger: " + ex);
            ex.printStackTrace(System.out);
        }
    }

    /** job "name" ... end: skip when done, restore variables, FAILED on error, duplicate-name warning. */
    static void jobs() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("manhattan-jobs");
            java.nio.file.Path f = dir.resolve("jobs.json");
            Jobs.useLedger(Ledger.load(f));
            Jobs.startRun();
            String script = "let total = 0\nlet spot = null\nlet names = []\nlet fn = null\n"
                    + "job \"a\"\n  out(\"ran a\")\n  total = 5\n  total += 2\n  spot = pos(1, 2, 3)\n  push(names, \"x\")\n  let m = {k: [1, 2.5, \"t\"]}\n  fn = func() return 1 end\nend\n"
                    + "out(total, spot, names, m, type(fn))";
            same("job first run", run(script), "ran a | 7 1 2 3 [\"x\"] {k: [1, 2.5, \"t\"]} function");
            same("job done in ledger", Jobs.ledger().state("a", null), "DONE");
            same("job saved vars", new java.util.TreeSet<>(Jobs.ledger().get("a").vars.keySet()), "[m, names, spot, total]");
            // a second run (the ledger is read back from the file): the body is skipped, the variables come back
            Jobs.useLedger(Ledger.load(f));
            Jobs.startRun();
            same("job skip and restore", run(script), "7 1 2 3 [\"x\"] {k: [1, 2.5, \"t\"]} null");
            same("restored position is a position", run("let spot = null\njob \"a\"\n  out(\"no\")\nend\nout(spot.x + spot.y + spot.z, type(spot))"), "6 pos");
            // reset: it runs again
            Jobs.reset("a");
            Jobs.startRun();
            same("job after reset runs again", run("job \"a\"\n  out(\"ran a\")\nend"), "ran a");
            // an error: FAILED with the message, the script stops, the next run goes into the job again
            Jobs.useLedger(Ledger.load(dir.resolve("fail.json")));
            Jobs.startRun();
            String bad = "job \"one\"\n  out(\"one\")\nend\njob \"two\"\n  out(\"two\")\n  let x = nothing\nend\nout(\"after\")";
            same("job error stops the script", run(bad), "one | two | ERROR line 6: 'nothing' is not defined. Give it a value first (let nothing = ...).");
            same("job failed state", Jobs.ledger().state("one", null) + " " + Jobs.ledger().state("two", null), "DONE FAILED");
            same("job failed reason", Jobs.ledger().get("two").reason, "line 6: 'nothing' is not defined. Give it a value first (let nothing = ...).");
            Jobs.startRun();
            same("next run continues at the failed job", run("job \"one\"\n  out(\"one\")\nend\njob \"two\"\n  out(\"two\")\nend\nout(\"after\")"), "two | after");
            // lines outside jobs always run
            Jobs.startRun();
            same("lines outside jobs run again", run("out(\"top\")\njob \"one\"\n  out(\"never\")\nend\nout(\"end\")"), "top | end");
            same("job names", Jobs.jobName(" a/b ", 1), "a-b");
            same("script name for the ledger file", Jobs.fileSafe("my build.txt") + " " + Jobs.fileSafe(null) + " " + Jobs.fileSafe("main"), "my_build main main");
            // parse errors and the check
            same("job needs a name", run("job\nend"), "PARSE line 1: 'job' needs a name in quotes: job \"build\" ... end");
            same("job not closed", run("job \"a\"\n  out(1)\n"), "PARSE line 1: 'job' (line 1) is never closed with 'end'.");
            same("no nested jobs", run("job \"a\"\n  job \"b\"\n  end\nend"), "PARSE line 2: A 'job' cannot be inside another job (line 2). Put them one after the other.");
            same("reserved job name", run("job \"script\"\nend"), "ERROR line 1: The job name 'script' is reserved for the lines outside jobs. Pick another name.");
            List<ScriptCheck.Entry> chk = ScriptCheck.run(List.of("job \"a\"", "  out(1)", "end", "job \"a\"", "  out(2)", "end", "job \"b\"", "  func f()", "  end", "end"));
            String warnings = chk.stream().filter(e -> e.level() != ScriptCheck.Level.OK).map(e -> e.line() + ": " + e.text()).toList().toString();
            same("duplicate job name warning", warnings.contains("4: There is already a job called \"a\" (line 1)"), true);
            same("function in a job warning", warnings.contains("8: The function 'f' is defined inside a job"), true);
            same("job is a keyword", Lexer.KEYWORDS.contains("job"), true);
        } catch (Exception ex) {
            fails++;
            System.out.println("FAIL jobs: " + ex);
            ex.printStackTrace(System.out);
        }
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
        check("shim goto", "#goto 100 64 200\nout(\"done\")", "done");
        check("shim mine", "#mine iron_ore\nout(\"done\")", "done");
        check("shim stop", "#stop\nout(\"done\")", "done");
        check("shim cancel", "#cancel\nout(\"done\")", "done");
        check("native goto pos", "let p = pos(1, 2, 3)\ngoto(p)\nout(\"done\")", "done");
        check("native goto xz", "goto(10, 20)\nout(\"done\")", "done");
        check("native mine", "mine(\"diamond_ore\", \"iron_ore\")\nout(\"done\")", "done");
        check("native follow error", "follow()\nout(\"done\")", "ERROR line 1: Not in a world.");
        check("native tunnel error", "tunnel()\nout(\"done\")", "ERROR line 1: Not in a world.");
        check("native cancel", "cancel()\nout(\"done\")", "done");
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
        same("ui layout default", Modules.get("ui.layout", 1), "edit");
        Modules.set("ui.layout", "monitor", 1);
        same("ui layout monitor", Modules.get("ui.layout", 1), "monitor");
        Modules.set("ui.layout", "edit", 1);
        String badLayout = "accepted";
        try {
            Modules.set("ui.layout", "docked", 1);
        } catch (RuntimeException e) {
            badLayout = "rejected";
        }
        same("ui layout bad value rejected", badLayout, "rejected");
        same("ui layout unchanged", Modules.get("ui.layout", 1), "edit");

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
        ledger();
        jobs();
        heavymaticaApi();
        logoffAndDisconnect();
        treeZones();
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** Heavymatica API & metadata tests. */
    static void heavymaticaApi() {
        // 1. Old config without areaMeta loads; every area reads as manual / custom
        String oldConfigJson = "{\"areas\":{\"old_manual\":[[0,64,0,10,70,10]]}}";
        Config oldCfg = new com.google.gson.Gson().fromJson(oldConfigJson, Config.class);
        Config.cfg = oldCfg;
        same("old config owner", Areas.ownerOf("old_manual"), "manual");
        same("old config role", Areas.roleOf("old_manual"), "custom");

        // 2. ManhattanAreas.put -> owner HEAVYMATICA; area_box on that name in a run -> owner SCRIPT and warning fires once
        List<String> listenerEvents = new ArrayList<>();
        ManhattanAreas.addListener((name, owner) -> listenerEvents.add(name + ":" + owner));

        var putRes = ManhattanAreas.put("heavy1", List.of(new int[]{0, 64, 0, 5, 70, 5}), ManhattanAreas.Role.DIG, false);
        same("put heavy1", putRes, ManhattanAreas.Result.OK);
        same("owner heavy1", Areas.ownerOf("heavy1"), "heavymatica");
        same("role heavy1", Areas.roleOf("heavy1"), "dig");
        assertTrue("listener fired on put", listenerEvents.contains("heavy1:HEAVYMATICA"));

        // Run script with area_box on heavy1
        run("area_box(\"heavy1\", pos(0, 64, 0), pos(2, 65, 2))");
        same("owner heavy1 after script area_box", Areas.ownerOf("heavy1"), "script");
        assertTrue("listener fired on script overwrite", listenerEvents.contains("heavy1:SCRIPT"));
        long warningCount = LogBuffer.entries(LogBuffer.Filter.ALL).stream()
                .filter(e -> e.text().contains("replaces the area Heavymatica sent"))
                .count();
        same("warning fired once", warningCount, 1L);

        // Run a second area_box for heavy1 in the same run to ensure warning does NOT fire twice
        run("area_box(\"heavy1\", pos(0, 64, 0), pos(3, 65, 3))");
        long warningCount2 = LogBuffer.entries(LogBuffer.Filter.ALL).stream()
                .filter(e -> e.text().contains("replaces the area Heavymatica sent"))
                .count();
        same("warning still fired once", warningCount2, 1L);

        // 3. put on a script-owned name -> SCRIPT_OWNED; with replaceScriptOwned = true -> OK
        var putScriptOwned = ManhattanAreas.put("heavy1", List.of(new int[]{0, 64, 0, 5, 70, 5}), ManhattanAreas.Role.DIG, false);
        same("put on script-owned without replace", putScriptOwned, ManhattanAreas.Result.SCRIPT_OWNED);

        var putReplaceScriptOwned = ManhattanAreas.put("heavy1", List.of(new int[]{0, 64, 0, 5, 70, 5}), ManhattanAreas.Role.CLEAR, true);
        same("put on script-owned with replace", putReplaceScriptOwned, ManhattanAreas.Result.OK);
        same("role updated", Areas.roleOf("heavy1"), "clear");
        same("owner updated to heavymatica", Areas.ownerOf("heavy1"), "heavymatica");

        // 4. put on loadedName while running -> IN_USE; selection is unchanged after any put
        Areas.load("heavy1");
        List<int[]> selBefore = Areas.read();
        same("inUse false before runner starts", Areas.inUse("heavy1"), false);

        QueueMod.runner.start(List.of("sleep()"));
        same("inUse true while runner running", Areas.inUse("heavy1"), true);

        var putInUse = ManhattanAreas.put("heavy1", List.of(new int[]{100, 64, 100, 110, 70, 110}), ManhattanAreas.Role.DIG, true);
        same("put in use", putInUse, ManhattanAreas.Result.IN_USE);

        same("selection unchanged", Areas.read().size(), selBefore.size());
        same("selection corner x1", Areas.read().get(0)[0], selBefore.get(0)[0]);

        QueueMod.runner.reset();
        same("inUse false after runner reset", Areas.inUse("heavy1"), false);

        // 5. invalid input (empty name, "last", no boxes, > MAX_BOXES) -> INVALID
        same("empty name invalid", ManhattanAreas.put("", List.of(new int[]{0, 64, 0, 1, 65, 1}), ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);
        same("blank name invalid", ManhattanAreas.put("   ", List.of(new int[]{0, 64, 0, 1, 65, 1}), ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);
        same("last name invalid", ManhattanAreas.put("last", List.of(new int[]{0, 64, 0, 1, 65, 1}), ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);
        same("no boxes invalid", ManhattanAreas.put("valid", List.of(), ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);
        same("null boxes invalid", ManhattanAreas.put("valid", null, ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);

        List<int[]> tooMany = new ArrayList<>();
        for (int i = 0; i <= ManhattanAreas.MAX_BOXES; i++) {
            tooMany.add(new int[]{0, 64, 0, 1, 65, 1});
        }
        same("too many boxes invalid", ManhattanAreas.put("overflow", tooMany, ManhattanAreas.Role.CUSTOM, true), ManhattanAreas.Result.INVALID);

        // 6. remove only deletes heavymatica-owned areas, listener fires on changes
        Areas.save("manual_area");
        same("manual_area owner", Areas.ownerOf("manual_area"), "manual");
        assertFalse("remove manual area fails", ManhattanAreas.remove("manual_area"));
        assertTrue("manual_area still exists", Areas.getBoxes("manual_area") != null);

        ManhattanAreas.put("heavy_to_remove", List.of(new int[]{0, 64, 0, 1, 65, 1}), ManhattanAreas.Role.CUSTOM, false);
        listenerEvents.clear();
        assertTrue("remove heavymatica area succeeds", ManhattanAreas.remove("heavy_to_remove"));
        same("heavy_to_remove no longer exists", Areas.getBoxes("heavy_to_remove"), null);
        assertTrue("listener fired on delete with null owner", listenerEvents.contains("heavy_to_remove:null"));

        // 7. Coordinate normalization and name trimming in ManhattanAreas.put() / get() / names() / select() / selection()
        same("put inverted coords with padded name",
                ManhattanAreas.put("  norm_area  ", List.of(new int[]{10, 80, 20, -5, 64, 5}), ManhattanAreas.Role.DIG, false),
                ManhattanAreas.Result.OK);
        var normInfo = ManhattanAreas.get(" norm_area ");
        assertTrue("get trimmed name present", normInfo.isPresent());
        same("normalized area name", normInfo.get().name(), "norm_area");
        same("normalized x1", normInfo.get().boxes().get(0)[0], -5);
        same("normalized y1", normInfo.get().boxes().get(0)[1], 64);
        same("normalized z1", normInfo.get().boxes().get(0)[2], 5);
        same("normalized x2", normInfo.get().boxes().get(0)[3], 10);
        same("normalized y2", normInfo.get().boxes().get(0)[4], 80);
        same("normalized z2", normInfo.get().boxes().get(0)[5], 20);
        assertTrue("get missing empty", ManhattanAreas.get("does_not_exist").isEmpty());
        assertTrue("names contains norm_area", ManhattanAreas.names().contains("norm_area"));

        assertTrue("select trimmed name succeeds", ManhattanAreas.select("  norm_area  "));
        assertFalse("select missing fails", ManhattanAreas.select("does_not_exist"));
        assertFalse("select blank fails", ManhattanAreas.select("   "));
        List<int[]> selCopy = ManhattanAreas.selection();
        same("selection has 1 box", selCopy.size(), 1);
        selCopy.get(0)[0] = 99999;
        same("selection copy isolated", ManhattanAreas.selection().get(0)[0], -5);

        // 8. protectedAreas() and Areas.delete() unprotecting PROTECT areas
        same("put protect area",
                ManhattanAreas.put("prot_zone", List.of(new int[]{0, 64, 0, 4, 70, 4}), ManhattanAreas.Role.PROTECT, false),
                ManhattanAreas.Result.OK);
        assertTrue("protectedAreas contains prot_zone",
                ManhattanAreas.protectedAreas().stream().anyMatch(a -> "prot_zone".equals(a.name())));
        assertTrue("Areas.delete removes prot_zone", Areas.delete("prot_zone"));
        assertFalse("protectedAreas no longer contains prot_zone",
                ManhattanAreas.protectedAreas().stream().anyMatch(a -> "prot_zone".equals(a.name())));
        assertTrue("buildtools zones no longer contains prot_zone",
                dev.buildtools.Api.zones().stream().noneMatch(z -> z.contains("prot_zone")));

        // 9. Areas.move() preserves owner/role and fires listener
        ManhattanAreas.put("move_me", List.of(new int[]{0, 64, 0, 2, 66, 2}), ManhattanAreas.Role.FLOOD, false);
        listenerEvents.clear();
        same("Areas.move succeeds", Areas.move("move_me", 10, 0, -5), null);
        same("move preserves owner", Areas.ownerOf("move_me"), "heavymatica");
        same("move preserves role", Areas.roleOf("move_me"), "flood");
        assertTrue("move fires listener", listenerEvents.contains("move_me:HEAVYMATICA"));
        same("moved x1", Areas.getBoxes("move_me").get(0)[0], 10);
        same("moved z2", Areas.getBoxes("move_me").get(0)[5], -3);

        // 10. loadedName cleared on clearSelection / setPos1 / setPos2 / apply
        QueueMod.runner.start(List.of("sleep()"));
        Areas.load("heavy1");
        assertTrue("inUse after load", Areas.inUse("heavy1"));
        Areas.clearSelection();
        assertFalse("inUse cleared by clearSelection", Areas.inUse("heavy1"));

        Areas.load("heavy1");
        assertTrue("inUse after load 2", Areas.inUse("heavy1"));
        Areas.setPos1(new net.minecraft.core.BlockPos(1, 64, 1));
        assertFalse("inUse cleared by setPos1", Areas.inUse("heavy1"));

        Areas.load("heavy1");
        assertTrue("inUse after load 3", Areas.inUse("heavy1"));
        Areas.setPos2(new net.minecraft.core.BlockPos(2, 65, 2));
        assertFalse("inUse cleared by setPos2", Areas.inUse("heavy1"));

        Areas.load("heavy1");
        assertTrue("inUse after load 4", Areas.inUse("heavy1"));
        Areas.apply(List.of(new int[]{0, 64, 0, 1, 65, 1}));
        assertFalse("inUse cleared by apply", Areas.inUse("heavy1"));
        QueueMod.runner.reset();

        // 11. AreasView.AreaEntry owner / role / tag()
        var entryH = new AreasView.AreaEntry("a", 1, 8, "summary", false, "heavymatica", "dig");
        same("entryH owner", entryH.owner(), "heavymatica");
        same("entryH role", entryH.role(), "dig");
        same("entryH tag", entryH.tag(), "[H]");
        var entryS = new AreasView.AreaEntry("b", 1, 8, "summary", false, "script", "clear");
        same("entryS owner", entryS.owner(), "script");
        same("entryS role", entryS.role(), "clear");
        same("entryS tag", entryS.tag(), "[S]");
        var entryM = new AreasView.AreaEntry("c", 1, 8, "summary", false);
        same("entryM owner", entryM.owner(), "manual");
        same("entryM role", entryM.role(), "custom");
        same("entryM tag", entryM.tag(), "[M]");
    }

    /** Logoff options and disconnect() builtin tests. */
    static void logoffAndDisconnect() {
        check("option queue.logoffOnDone", "option(\"queue.logoffOnDone\", true)\nout(option(\"queue.logoffOnDone\"))", "true");
        same("cfg.logoffOnDone is true", Config.cfg.logoffOnDone, true);

        check("option queue.logoffOnError", "option(\"queue.logoffOnError\", true)\nout(option(\"queue.logoffOnError\"))", "true");
        same("cfg.logoffOnError is true", Config.cfg.logoffOnError, true);

        check("option survival.logoffOnMissingBed", "option(\"survival.logoffOnMissingBed\", true)\nout(option(\"survival.logoffOnMissingBed\"))", "true");
        same("survival logoffOnMissingBed is true", dev.survivalkit.Api.isLogoffOnMissingBed(), true);

        dev.survivalkit.Api.resetDisconnectSuppression();
        assertFalse("disconnect suppression initially false", dev.survivalkit.Api.isDisconnectSuppressed());
        run("disconnect(\"user requested\")");
        assertTrue("disconnect suppressed reconnect", dev.survivalkit.Api.isDisconnectSuppressed());

        dev.survivalkit.Api.resetDisconnectSuppression();
        boolean[] disconnected = {false};
        Config.cfg.logoffOnDone = true;
        QueueMod.runner.start(List.of("let x = 1"));
        dev.survivalkit.Api.onBeforeDisconnect = () -> disconnected[0] = true;
        while (QueueMod.runner.running()) {
            QueueMod.runner.tick();
        }
        assertTrue("runner called disconnect on done", disconnected[0]);
        assertTrue("runner disconnect on done set suppression", dev.survivalkit.Api.isDisconnectSuppressed());

        dev.survivalkit.Api.resetDisconnectSuppression();
        disconnected[0] = false;
        Config.cfg.logoffOnError = true;
        QueueMod.runner.start(List.of("error(\"test fatal error\")"));
        dev.survivalkit.Api.onBeforeDisconnect = () -> disconnected[0] = true;
        while (QueueMod.runner.running()) {
            QueueMod.runner.tick();
        }
        assertTrue("runner called disconnect on error", disconnected[0]);
        assertTrue("runner disconnect on error set suppression", dev.survivalkit.Api.isDisconnectSuppressed());

        dev.survivalkit.Api.onBeforeDisconnect = null;
        dev.survivalkit.Api.resetDisconnectSuppression();
        Config.cfg.logoffOnDone = false;
        Config.cfg.logoffOnError = false;
        Modules.set("survival.logoffOnMissingBed", false, 0);
    }

    /** Cylinder scaffold zones and TreeZoneOptions headless tests. */
    static void treeZones() {
        ScaffoldZones.clear();
        assertTrue("scaffold zones initially empty", ScaffoldZones.all().isEmpty());

        // 1x1 trunk with diameter 9 -> cx=10.5, cz=20.5, r=4.5, y=[63..71]
        ScaffoldZones.Zone z1 = ScaffoldZones.addWithDiameter(10, 64, 20, 10, 70, 20, 9);
        same("zone1 cx", z1.cx, 10.5);
        same("zone1 cz", z1.cz, 20.5);
        same("zone1 r", z1.r, 4.5);
        same("zone1 y0", z1.y0, 63);
        same("zone1 y1", z1.y1, 71);
        assertTrue("zone1 contains trunk center", z1.contains(new net.minecraft.core.BlockPos(10, 64, 20)));
        assertTrue("zone1 inAny trunk center", ScaffoldZones.inAny(new net.minecraft.core.BlockPos(10, 64, 20)));
        assertTrue("zone1 contains y0-1", z1.contains(new net.minecraft.core.BlockPos(10, 63, 20)));
        assertTrue("zone1 contains y1+1", z1.contains(new net.minecraft.core.BlockPos(10, 71, 20)));
        assertFalse("zone1 excludes below y0-1", z1.contains(new net.minecraft.core.BlockPos(10, 62, 20)));
        assertFalse("zone1 excludes above y1+1", z1.contains(new net.minecraft.core.BlockPos(10, 72, 20)));
        assertTrue("zone1 contains inside radius", z1.contains(new net.minecraft.core.BlockPos(14, 65, 20)));
        assertFalse("zone1 excludes outside radius", z1.contains(new net.minecraft.core.BlockPos(15, 65, 20)));

        // Wide 2x2 trunk with small diameter 3 -> radius expands to half + 1.5 = 2.5
        ScaffoldZones.Zone z2 = ScaffoldZones.addWithDiameter(100, 64, 100, 101, 75, 101, 3);
        same("zone2 cx", z2.cx, 101.0);
        same("zone2 cz", z2.cz, 101.0);
        same("zone2 expanded r", z2.r, 2.5);
        same("zone2 y0", z2.y0, 63);
        same("zone2 y1", z2.y1, 76);

        // Very wide 10x10 canopy with diameter 9 -> radius expands to 5.0 + 1.5 = 6.5
        ScaffoldZones.Zone z3 = ScaffoldZones.addWithDiameter(200, 64, 200, 209, 72, 209, 9);
        same("zone3 wide r", z3.r, 6.5);

        // Clamped diameter < 3
        ScaffoldZones.Zone z4 = ScaffoldZones.addWithDiameter(300, 64, 300, 300, 66, 300, 1);
        same("zone4 clamped r", z4.r, 2.0);

        // clearCurrentFlags clears current without clearing pending
        z1.current = true;
        z1.pending = true;
        z2.current = true;
        ScaffoldZones.clearCurrentFlags();
        assertFalse("z1 current cleared", z1.current);
        assertFalse("z2 current cleared", z2.current);
        assertTrue("z1 pending preserved", z1.pending);
        ScaffoldZones.clear();

        // TreeZoneOptions setters and clamping
        assertFalse("autoSweepSuppressed initially false", dev.buildtools.TreeZoneOptions.isAutoSweepSuppressed());
        same("setAutoSweepSuppressed true returns false", dev.buildtools.TreeZoneOptions.setAutoSweepSuppressed(true), false);
        assertTrue("autoSweepSuppressed is true", dev.buildtools.TreeZoneOptions.isAutoSweepSuppressed());
        same("setAutoSweepSuppressed false returns true", dev.buildtools.TreeZoneOptions.setAutoSweepSuppressed(false), true);
        assertFalse("autoSweepSuppressed restored false", dev.buildtools.TreeZoneOptions.isAutoSweepSuppressed());

        int origDiam = dev.buildtools.TreeZoneOptions.setDiameter(13);
        same("diameter set to 13", dev.buildtools.TreeZoneOptions.diameter(), 13);
        same("setDiameter clamps <3 to 3", dev.buildtools.TreeZoneOptions.setDiameter(1), 13);
        same("diameter clamped at 3", dev.buildtools.TreeZoneOptions.diameter(), 3);
        dev.buildtools.TreeZoneOptions.setDiameter(origDiam);

        boolean origShow = dev.buildtools.TreeZoneOptions.setShow(false);
        assertFalse("show set to false", dev.buildtools.TreeZoneOptions.show());
        same("setShow true returns false", dev.buildtools.TreeZoneOptions.setShow(true), false);
        assertTrue("show set to true", dev.buildtools.TreeZoneOptions.show());
        dev.buildtools.TreeZoneOptions.setShow(origShow);
    }
}
