"""In-game smoke tests for Manhattan on the "Manhatten Testing" profile.

  python tests/ingame/run_tests.py                 install, launch, run every test, close the game
  python tests/ingame/run_tests.py fill_water eat  only these tests
  python tests/ingame/run_tests.py --keep-open     leave the game running afterwards (drive it with bridge.py)
  python tests/ingame/run_tests.py --attach        use the testing instance that is already running

Exit code 0 = every test passed. Never touches any other Minecraft instance.
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import litematic  # noqa: E402
import mc  # noqa: E402
from mc import BridgeError, log  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCEN = os.path.join(HERE, "scenarios")

# the flat test world: bedrock -64, dirt -63..-62, grass -61, the player stands at -60
HOME = (100, -60, 100)
POOL = [(x, y, z) for y in (-62, -61) for x in (103, 104, 105) for z in (99, 100, 101)]


class Fail(Exception):
    pass


class Ctx:
    def __init__(self, b):
        self.b = b
        self.mark = 0

    def cmd(self, *commands):
        for c in commands:
            self.b.cmd(c)
        time.sleep(0.3)

    def start_log(self):
        self.mark = self.b("chatlog 0")["next"]

    def lines(self):
        return self.b("chatlog %d" % self.mark)["lines"]

    def wait_chat(self, text, timeout):
        return self.b.wait(lambda: next((l for l in self.lines() if text in l), None), timeout, what="chat '%s'" % text)

    def run(self, scenario):
        self.b("runfile " + os.path.join(SCEN, scenario))

    def state(self):
        return self.b("state")

    def block(self, p):
        return self.b("block %d %d %d" % p)["block"]

    def count(self, item):
        return self.b("count " + item)["count"]

    def script_done(self, timeout):
        self.b.wait(lambda: not self.b("script")["running"], timeout, what="the script to end")


def reset_area(t, gamemode="survival"):
    """Clean ground around HOME, nothing else alive, daylight that stays, the player there with an empty inventory."""
    t.cmd("/gamemode " + gamemode, "/effect clear @s", "/clear @s",
          "/time set noon", "/weather clear",
          "/kill @e[type=!player]",
          "/fill 92 -63 92 112 -62 112 dirt", "/fill 92 -61 92 112 -61 112 grass_block",
          "/fill 92 -60 92 112 -50 112 air",
          "/tp @s %d %d %d 0 0" % HOME)
    time.sleep(1)


# ------------------------------------------------------------------ tests

def test_smoke(t):
    """The script language runs, and the editor opens."""
    t.run("smoke.txt")
    t.wait_chat("TEST smoke n=3 names=2", 15)
    t.script_done(10)
    t.b("editor")
    scr = t.b.wait(lambda: t.b("screen")["screen"], 5, what="the editor")
    if not scr.endswith("QueueScreen"):
        raise Fail("editor did not open, screen is %s" % scr)
    t.b("screen close")
    return "script printed, editor opened"


def test_fill_water(t):
    """fill_water("dirt") fills a 3x3, 2-deep pool, never a cell above water."""
    reset_area(t)
    t.cmd("/fill 103 -62 99 105 -61 101 water", "/give @s dirt 64")
    time.sleep(1)
    wet = [p for p in POOL if "water" in t.block(p)]
    if len(wet) != len(POOL):
        raise Fail("pool setup failed: %d of %d cells hold water" % (len(wet), len(POOL)))
    dirt0 = t.count("dirt")
    t.run("fill-water.txt")
    violations, end = [], time.time() + 180
    done_at = None
    while time.time() < end:
        cells = {p: t.block(p) for p in POOL}
        for (x, y, z), bl in cells.items():
            if y == -61 and "water" not in bl and "water" in cells[(x, -62, z)]:
                v = "(%d %d %d) filled while (%d -62 %d) below was still wet" % (x, y, z, x, z)
                if v not in violations:
                    violations.append(v)
        if not t.b("script")["running"]:
            done_at = time.time()
            break
        time.sleep(0.5)
    if done_at is None:
        t.b("stopscript")
        raise Fail("fill_water did not finish within 180 s; still wet: %s"
                   % [p for p in POOL if "water" in t.block(p)])
    still = [p for p in POOL if "water" in t.block(p)]
    msg = next((l for l in t.lines() if "TEST fill_water" in l), None)
    if still:
        raise Fail("cells still wet: %s (%s)" % (still, msg))
    if violations:
        raise Fail("not bottom-up: " + "; ".join(violations))
    if not msg or "left=0" not in msg:
        raise Fail("script did not report left=0: %s" % msg)
    used = dirt0 - t.count("dirt")
    if used < 1:
        raise Fail("no dirt was used from the inventory")
    return "18 cells filled bottom-up, %d dirt used" % used


def test_eat(t):
    """on food < 14 ... eat() fires and eats bread."""
    reset_area(t)
    t.cmd("/give @s bread 8")
    t.run("eat.txt")
    t.wait_chat("TEST eat armed", 10)
    t.cmd("/effect give @s minecraft:hunger 60 50 true")
    t.b.wait(lambda: t.state()["food"] < 14, 60, what="food < 14")
    t.cmd("/effect clear @s minecraft:hunger")
    line = t.wait_chat("TEST ate", 40)
    t.b("stopscript")
    bread = t.count("bread")
    if bread >= 8:
        raise Fail("the handler ran but no bread was eaten (%s)" % line)
    return "%s, %d bread left" % (line.split("TEST ", 1)[1], bread)


def test_defend(t):
    """on mobs > 0 ... defend() kills a zombie."""
    reset_area(t)
    t.cmd("/give @s diamond_sword 1", "/effect give @s minecraft:resistance 120 4 true", "/time set midnight")
    t.run("defend.txt")
    t.wait_chat("TEST defend armed", 10)
    t.cmd("/summon minecraft:zombie 104 -60 100 {PersistenceRequired:1b}")
    t.b.wait(lambda: t.b("entities zombie 20")["count"] > 0, 5, what="the zombie to appear")
    line = t.wait_chat("TEST defended", 60)
    t.b.wait(lambda: t.b("entities zombie 20")["count"] == 0, 10, what="the zombie to die")
    t.b("stopscript")
    t.cmd("/time set noon")
    return "zombie killed (%s)" % line.split("TEST ", 1)[1]


def test_clear_resume(t):
    """clear_area() is interrupted by eat() and carries on at the same stop instead of starting over."""
    reset_area(t)
    t.cmd("/fill 102 -60 92 108 -59 112 dirt", "/give @s diamond_shovel 1", "/give @s bread 8")
    t.run("clear-resume.txt")
    t.wait_chat("TEST clear armed", 10)
    t.wait_chat("Clearing the area from the top", 10)
    time.sleep(4)   # let it break a few blocks first
    t.cmd("/effect give @s minecraft:hunger 60 50 true")
    t.b.wait(lambda: t.state()["food"] < 14, 60, what="food < 14")
    t.cmd("/effect clear @s minecraft:hunger")
    t.wait_chat("TEST ate", 40)
    line = t.wait_chat("TEST cleared", 240)
    lines = t.lines()
    starts = sum("Clearing the area from the top" in l for l in lines)
    resumed = [l for l in lines if "Carrying on clearing" in l]
    if not resumed:
        raise Fail("no 'Carrying on clearing' after eating: it did not resume (%s)" % line)
    if starts != 1:
        raise Fail("clear_area started from the top %d times (should be once)" % starts)
    solid = [(x, y, z) for x in range(102, 109) for y in (-60, -59) for z in range(92, 113)
             if "air" not in t.block((x, y, z))]
    if solid:
        raise Fail("%d blocks left in the strip, e.g. %s (%s)" % (len(solid), solid[:5], line))
    if "left=0" not in line:
        raise Fail("clear_area did not return 0: " + line)
    return "strip cleared, resumed once eating was done (%s)" % resumed[0].split("] ", 1)[-1][:80]


def test_dump(t):
    """dump() keeps only what the want-list asks for (the better copy) and good food; junk and the hotbar go."""
    reset_area(t)
    t.cmd("/setblock 102 -60 100 chest",
          "/give @s cobblestone 64",                       # lands in the hotbar
          "/give @s diamond_shovel[damage=1000] 1", "/give @s diamond_shovel 1",
          "/give @s iron_shovel 1", "/give @s bow 1", "/give @s leather_chestplate 1",
          "/give @s rotten_flesh 10", "/give @s bread 8", "/give @s golden_apple 1")
    t.run("dump.txt")
    t.wait_chat("TEST dumped", 60)
    expect = {"diamond_shovel": 1, "iron_shovel": 0, "bow": 0, "leather_chestplate": 0, "rotten_flesh": 0,
              "cobblestone": 0, "bread": 8, "golden_apple": 1}
    got = {k: t.count(k) for k in expect}
    wrong = {k: (got[k], v) for k, v in expect.items() if got[k] != v}
    if wrong:
        raise Fail("inventory after dump (have, expected): %s" % wrong)
    return "kept 1 diamond shovel, bread and the golden apple; junk and the hotbar's cobblestone went into the chest"


# ------------------------------------------------------------------ schematic builder (Litematica placement + Baritone)

SCHEM_ORIGIN = (108, -60, 108)
SCHEM_SIZE = (5, 3, 5)


def schem_model():
    """The test schematic: stone floor, cobblestone walls 2 high, stairs and slabs at the corners, a wall torch,
    one redstone dust inside. Everything else inside is air."""
    b = {}
    for x in range(5):
        for z in range(5):
            b[(x, 0, z)] = "minecraft:stone"
            ring = x in (0, 4) or z in (0, 4)
            corner = x in (0, 4) and z in (0, 4)
            if ring and not corner:
                b[(x, 1, z)] = "minecraft:cobblestone"
                b[(x, 2, z)] = "minecraft:cobblestone"
            if corner:
                face = {(0, 0): "north", (4, 0): "east", (0, 4): "south", (4, 4): "west"}[(x, z)]
                half = "top" if (x + z) % 8 == 0 else "bottom"   # (0,0) and (4,4) upside down
                b[(x, 1, z)] = "minecraft:oak_stairs[facing=%s,half=%s,shape=straight,waterlogged=false]" % (face, half)
                b[(x, 2, z)] = "minecraft:oak_slab[type=%s,waterlogged=false]" % ("top" if (x, z) == (0, 0) else "bottom")
    b[(3, 2, 0)] = "minecraft:oak_log[axis=x]"
    b[(2, 2, 1)] = "minecraft:wall_torch[facing=south]"
    b[(2, 1, 2)] = "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]"
    return b


def prepare_schem():
    """The schematic file and Litematica's placement file for the testing world (before the game starts)."""
    sch = os.path.join(mc.PROFILE, "schematics", "manhattan-test-house.litematic")
    litematic.write(sch, "manhattan-test-house", SCHEM_SIZE, schem_model())
    cfgdir = os.path.join(mc.PROFILE, "config", "litematica")
    os.makedirs(cfgdir, exist_ok=True)
    with open(os.path.join(cfgdir, "litematica_%s_dim_minecraft_overworld.json" % mc.WORLD), "w", encoding="utf-8") as f:
        f.write(litematic.placements_json([("test house", sch, SCHEM_ORIGIN, "NONE", "NONE", True)]))


def schem_supplies(t):
    t.cmd("/give @s stone 64", "/give @s cobblestone 64", "/give @s oak_stairs 8", "/give @s oak_slab 8",
          "/give @s torch 8", "/give @s redstone 8", "/give @s dirt 64", "/give @s oak_log 8", "/give @s diamond_pickaxe 1")


def schem_wrong(t, ignore=()):
    """Positions whose block differs from the model: [(pos, wanted, got)]. Air for every position the model leaves empty."""
    model, bad = schem_model(), []
    for x in range(5):
        for y in range(3):
            for z in range(5):
                pos = (SCHEM_ORIGIN[0] + x, SCHEM_ORIGIN[1] + y, SCHEM_ORIGIN[2] + z)
                if pos in ignore:
                    continue
                want = model.get((x, y, z), "minecraft:air").split("[")[0]
                got = t.block(pos).split("|")[0]
                if got != want:
                    bad.append((pos, want.replace("minecraft:", ""), got.replace("minecraft:", "")))
                elif "[" in model.get((x, y, z), ""):
                    props = dict(kv.split("=") for kv in model[(x, y, z)].split("[")[1][:-1].split(","))
                    st = t.b("blockstate %d %d %d" % pos)["state"]
                    have = dict(kv.split("=") for kv in st.split("[")[1][:-1].split(",")) if "[" in st else {}
                    for k, v in props.items():
                        if k in ("shape", "waterlogged", "power", "north", "east", "south", "west"):
                            continue
                        if have.get(k) != v:
                            bad.append((pos, "%s %s=%s" % (want.replace("minecraft:", ""), k, v), "%s=%s" % (k, have.get(k))))
    return bad


def schem_run(t, scenario, timeout=240):
    t.run(scenario)
    t.b.wait(lambda: not t.b("script")["running"], timeout, step=1, what="the schematic build to end")
    return next((l for l in t.lines() if "TEST schem" in l), "(no TEST line)")


def schem_report(t):
    t.start_log()
    t.cmd("/schem check 1 fix")
    time.sleep(1)
    return [l.split("] ", 1)[-1] for l in t.lines() if "Check " in l or "wants" in l]


def test_schem_list(t):
    """/schem list finds the placement from Litematica (live or its config file), /schem materials and check run."""
    reset_area(t)
    schem_supplies(t)
    t.start_log()
    t.cmd("/schem list")
    time.sleep(1)
    lines = t.lines()
    if not any("test house" in l for l in lines):
        raise Fail("/schem list did not show the placement: %s" % lines)
    t.cmd("/schem materials 1 add")
    time.sleep(1)
    mat = " | ".join(l.split("] ", 1)[-1] for l in t.lines() if "x" in l and "Materials" not in l)
    for need in ("stone x25", "cobblestone x23", "oak_stairs x4", "oak_slab x4", "torch x1", "redstone x1"):
        if need not in mat:
            raise Fail("material list lacks '%s': %s" % (need, mat))
    return "listed; materials %s" % mat[:150]


def test_schem_add(t):
    """add: empty spots are filled, a wrong block that is already there is left alone, schematic air is ignored."""
    reset_area(t)
    schem_supplies(t)
    wrong_at = (109, -60, 109)
    t.cmd("/setblock %d %d %d diamond_block" % wrong_at, "/setblock 109 -59 110 dirt")   # a wrong floor block, a block inside the air
    line = schem_run(t, "schem-add.txt")
    bad = schem_wrong(t, ignore=[wrong_at, (109, -59, 110)])
    keep = t.block(wrong_at), t.block((109, -59, 110))
    report = schem_report(t)
    if bad:
        raise Fail("add mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    if "diamond_block" not in keep[0] or "dirt" not in keep[1]:
        raise Fail("add mode touched existing blocks: %s" % (keep,))
    return "built; foreign blocks left alone (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))


def test_schem_fix(t):
    """fix: a wrong block is broken and replaced; schematic air is still ignored."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/setblock 109 -60 109 diamond_block", "/setblock 109 -59 110 dirt")
    line = schem_run(t, "schem-fix.txt")
    bad = schem_wrong(t, ignore=[(109, -59, 110)])
    report = schem_report(t)
    if bad:
        raise Fail("fix mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    if "dirt" not in t.block((109, -59, 110)):
        raise Fail("fix mode dug schematic air (that is exact mode's job)")
    return "wrong block replaced; air left (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))


def test_schem_exact(t):
    """exact: built into a hill, schematic air is dug out first (top-down), then everything is built."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/fill 106 -60 106 114 -57 114 dirt")
    line = schem_run(t, "schem-exact.txt")
    bad = schem_wrong(t)
    report = schem_report(t)
    if bad:
        raise Fail("exact mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    outside = t.block((106, -59, 106))
    if "dirt" not in outside:
        raise Fail("exact mode dug outside the schematic: (106 -59 106) is %s" % outside)
    return "dug into the hill and built (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))

def test_schem_fix_wrong(t):
    """fix: wrong blocks sitting exactly where the schematic wants redstone, a stair, a wall torch, a log and a slab are
    broken and the right block is placed (also a stair that stands there with the wrong facing)."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/setblock 110 -59 110 dirt",                                   # redstone dust spot
          "/setblock 108 -59 108 cobblestone",                           # stair spot, other block
          "/setblock 112 -59 108 oak_stairs[facing=south,half=top]",     # stair spot, same block, wrong facing and half
          "/setblock 110 -58 109 cobblestone",                           # wall torch spot
          "/setblock 111 -58 108 stone",                                 # log spot
          "/setblock 112 -58 108 oak_slab[type=top]")                    # slab spot, wrong type
    line = schem_run(t, "schem-fix.txt")
    bad = schem_wrong(t)
    report = schem_report(t)
    if bad:
        raise Fail("fix mode left %d positions wrong, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    return "six wrong blocks on redstone/stair/torch/log/slab spots all replaced (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:1]))


TESTS = [("smoke", test_smoke), ("fill_water", test_fill_water), ("eat", test_eat), ("defend", test_defend), ("clear_resume", test_clear_resume), ("dump", test_dump),
         ("schem_list", test_schem_list), ("schem_add", test_schem_add), ("schem_fix", test_schem_fix), ("schem_fix_wrong", test_schem_fix_wrong), ("schem_exact", test_schem_exact)]


# ------------------------------------------------------------------ main

def enter_world(b):
    st = b.wait(lambda: (lambda s: s if s["inWorld"] or s["screen"].endswith("TitleScreen") else None)(b("state")),
                240, step=1, what="the title screen")
    if not st["inWorld"]:
        time.sleep(3)   # let the resource reload finish
        r = b("world " + mc.WORLD)
        log("world '%s' %s" % (mc.WORLD, "created (flat, creative, cheats)" if r.get("created") else "opened"))
        b.wait(lambda: b("state")["inWorld"], 300, step=1, what="the world to load")
    b.wait(lambda: b("state")["screen"] == "", 60, step=1, what="the loading screen to close")
    time.sleep(3)
    t = Ctx(b)
    # world rules for repeatable tests; the answers go to the log for checking
    t.start_log()
    t.cmd("/gamerule advance_time false", "/gamerule advance_weather false", "/gamerule spawn_mobs false",
          "/difficulty easy")
    for l in t.lines():
        log("  setup:", l)
    return t


TEST_TIMEOUT = 420          # seconds; a test that takes longer fails and its game is restarted
RAM_PER_CLIENT_GB = 3.0
LONG_FIRST = ["clear_resume", "schem_exact", "schem_fix_wrong", "schem_fix", "fill_water", "schem_add"]   # start the slow ones first


def free_ram_gb():
    try:
        import ctypes

        class MS(ctypes.Structure):
            _fields_ = [("l", ctypes.c_ulong), ("load", ctypes.c_ulong), ("total", ctypes.c_ulonglong),
                        ("avail", ctypes.c_ulonglong), ("a", ctypes.c_ulonglong), ("b", ctypes.c_ulonglong),
                        ("c", ctypes.c_ulonglong), ("d", ctypes.c_ulonglong), ("e", ctypes.c_ulonglong)]
        m = MS()
        m.l = ctypes.sizeof(MS)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return m.avail / 2 ** 30
    except Exception:
        return 6.0


def run_one(t, name, fn, timeout):
    """Runs one test in a thread with a hard time limit. Returns (ok, info, seconds, hung)."""
    t0 = time.time()
    t.start_log()
    box = {}

    def body():
        try:
            box["info"] = fn(t)
        except Exception as e:   # noqa: BLE001
            box["err"] = e
            box["tb"] = traceback.format_exc()

    th = threading.Thread(target=body, daemon=True)
    th.start()
    th.join(timeout)
    if th.is_alive():
        return False, "hard timeout after %d s: the test did not finish (the game is restarted)" % timeout, time.time() - t0, True
    if "err" in box:
        e = box["err"]
        detail = str(e) if isinstance(e, (Fail, BridgeError)) else box["tb"]
        try:
            t.b("stopscript")
            chat = t.lines()[-15:]
        except Exception:
            chat = []
        if chat:
            detail += "\n      chat:\n        " + "\n        ".join(chat)
        return False, detail, time.time() - t0, False
    return True, box.get("info", ""), time.time() - t0, False


def start_game(b, attach):
    if attach:
        b.connect()
        return None
    mc.stop_testing_instance()
    mc.install()
    prepare_schem()
    proc = mc.launch()
    b.wait_ready(proc=proc)
    return proc


def worker(claim, keep, attach, timeout, tag=""):
    """Starts one game, takes tests from claim() until it returns None, returns [(name, ok, info, seconds)]."""
    b = mc.Bridge()
    start_game(b, attach)
    log(tag + "bridge connected")
    results = []
    try:
        t = enter_world(b)
        while True:
            item = claim()
            if item is None:
                break
            name, fn = item
            log(tag + "running", name, "...")
            ok, info, dt, hung = run_one(t, name, fn, timeout)
            results.append((name, ok, info, dt))
            if hung:   # a stuck game is closed through its own bridge, then a fresh one carries on
                try:
                    mc.stop_testing_instance(b)
                except Exception as e:   # noqa: BLE001
                    log(tag + "closing the stuck game:", e)
                b.close()
                b = mc.Bridge()
                start_game(b, False)
                t = enter_world(b)
    finally:
        if not keep and not attach:
            log(tag + "closing the game")
            mc.stop_testing_instance(b)
        b.close()
    return results


def summary(results, wall, workers):
    print()
    print("=" * 60)
    order = [n for n, _ in TESTS]
    for r in sorted(results, key=lambda r: order.index(r[0])):
        print("%s  %-11s %5.1fs  %s%s" % ("PASS" if r[1] else "FAIL", r[0], r[3], r[2], ("   [worker %s]" % r[4]) if len(r) > 4 else ""))
    failed = sum(1 for r in results if not r[1])
    print("=" * 60)
    print("%d passed, %d failed   in %.0f s on %d game%s   (game log: %s)" % (
        len(results) - failed, failed, wall, workers, "" if workers == 1 else "s", os.path.join(mc.PROFILE, "logs", "latest.log")))
    return 0 if failed == 0 and results else 1


def opt(argv, flag, default):
    if flag in argv:
        i = argv.index(flag)
        return argv[i + 1]
    return default


def main(argv):
    keep = "--keep-open" in argv
    attach = "--attach" in argv
    timeout = int(opt(argv, "--test-timeout", TEST_TIMEOUT))
    values = {opt(argv, f, None) for f in ("--workers", "-j", "--worker", "--queue", "--test-timeout")}
    names = [a for a in argv if not a.startswith("-") and a not in values]
    tests = [(n, f) for n, f in TESTS if not names or n in names]
    if names and len(tests) != len(names):
        print("unknown test; known: " + ", ".join(n for n, _ in TESTS))
        return 2

    # internal: one parallel worker, tests come from the queue directory
    if "--worker" in argv:
        k, qdir = opt(argv, "--worker", "1"), opt(argv, "--queue", None)
        table = dict(TESTS)

        def claim():
            for n in open(os.path.join(qdir, "tests.txt")).read().split():
                try:
                    os.mkdir(os.path.join(qdir, "claimed-" + n))   # atomic: only one worker gets it
                except FileExistsError:
                    continue
                return n, table[n]
            return None
        res = worker(claim, False, False, timeout, tag="[w%s] " % k)
        with open(os.path.join(qdir, "results-%s.json" % k), "w", encoding="utf-8") as f:
            json.dump([list(r) + [k] for r in res], f)
        return 0

    default_workers = max(1, min(3, int(free_ram_gb() // RAM_PER_CLIENT_GB), len(tests)))
    workers = 1 if attach or keep else int(opt(argv, "--workers", opt(argv, "-j", default_workers)))
    workers = max(1, min(workers, len(tests)))
    t0 = time.time()
    if workers == 1:
        todo = list(tests)
        res = worker(lambda: todo.pop(0) if todo else None, keep, attach, timeout)
        return summary(res, time.time() - t0, 1)

    # parallel: every worker is its own game with its own game dir, world, bridge port and log under tmp/ingame/
    log("%d games in parallel (free RAM %.1f GB, about %.0f GB each)" % (workers, free_ram_gb(), RAM_PER_CLIENT_GB))
    root = os.path.join(mc.REPO, "tmp", "ingame")
    qdir = os.path.join(root, "queue")
    shutil.rmtree(qdir, ignore_errors=True)
    os.makedirs(qdir)
    order = sorted((n for n, _ in tests), key=lambda n: LONG_FIRST.index(n) if n in LONG_FIRST else 99)
    with open(os.path.join(qdir, "tests.txt"), "w") as f:
        f.write("\n".join(order))
    procs = []
    for k in range(1, workers + 1):
        home = os.path.join(root, "w%d" % k)
        os.makedirs(home, exist_ok=True)
        env = dict(os.environ, MANHATTAN_PROFILE=home, MANHATTAN_BRIDGE_PORT=str(25600 + k), MANHATTAN_XMX="3G")
        out = open(os.path.join(root, "w%d.log" % k), "w", encoding="utf-8")
        procs.append((k, subprocess.Popen([sys.executable, "-u", os.path.abspath(__file__), "--worker", str(k), "--queue", qdir,
                                           "--test-timeout", str(timeout)], env=env, stdout=out, stderr=subprocess.STDOUT)))
        log("worker %d: game dir %s, port %d, log %s" % (k, home, 25600 + k, os.path.join(root, "w%d.log" % k)))
        time.sleep(15)   # stagger the starts: loading several clients at once is slower than one after the other
    for k, pr in procs:
        pr.wait()
    res = []
    for k, _ in procs:
        fn = os.path.join(qdir, "results-%d.json" % k)
        if os.path.exists(fn):
            res += [tuple(r) for r in json.load(open(fn, encoding="utf-8"))]
    done = {r[0] for r in res}
    for n in order:
        if n not in done:
            res.append((n, False, "never ran (a worker died; see tmp/ingame/w*.log)", 0.0, "?"))
    return summary(res, time.time() - t0, workers)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
