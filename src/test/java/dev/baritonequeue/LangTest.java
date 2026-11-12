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
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
