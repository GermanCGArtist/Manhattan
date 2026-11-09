"""In-game smoke tests for Manhattan on the "Manhatten Testing" profile.

  python tests/ingame/run_tests.py                 install, launch, run every test, close the game
  python tests/ingame/run_tests.py fill_water eat  only these tests
  python tests/ingame/run_tests.py --keep-open     leave the game running afterwards (drive it with bridge.py)
  python tests/ingame/run_tests.py --attach        use the testing instance that is already running

Exit code 0 = every test passed. Never touches any other Minecraft instance.
"""
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
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


def test_hotbar_tools(t):
    """Wanted tools that only sit in the main inventory are moved into the hotbar (not over food or pinned slots)."""
    reset_area(t)
    t.cmd(*["/item replace entity @s hotbar.%d with cobblestone 64" % i for i in range(8)],
          "/item replace entity @s hotbar.8 with bread 8",
          "/give @s diamond_pickaxe 1", "/give @s diamond_shovel 1")
    t.run("hotbar-tools.txt")
    t.wait_chat("TEST hotbar done", 30)
    hb = t.state()["hotbar"]
    missing = [x for x in ("diamond_pickaxe", "diamond_shovel") if x not in hb]
    if missing:
        raise Fail("not in the hotbar: %s (hotbar %s)" % (missing, hb))
    if hb[8] != "bread":
        raise Fail("the food slot was taken: hotbar %s" % hb)
    return "pickaxe and shovel moved into the hotbar, bread stayed in slot 9 (%s)" % hb


def test_air(t):
    """Shut in under water (a stone lid one block over the water): the air guard breaks out before drowning."""
    reset_area(t)
    t.cmd("/fill 102 -62 98 106 -54 102 stone", "/fill 103 -62 99 105 -55 101 water",
          "/fill 103 -54 99 105 -54 101 stone",   # the lid: water right up to it, no air pocket
          "/give @s diamond_pickaxe 1", "/tp @s 104 -60 100")
    t.run("air.txt")
    t.wait_chat("TEST air armed", 10)
    t.wait_chat("Running out of air", 40)
    t.b.wait(lambda: (lambda s: s if not s["underWater"] else None)(t.state()), 40, what="the head above water")
    st = t.state()
    t.b("stopscript")
    if any("drowned" in l or " died" in l for l in t.lines()) or st["health"] <= 0:
        raise Fail("drowned (%s)" % [l for l in t.lines() if "air" in l.lower() or "drown" in l][-4:])
    return "came up for air (health %.0f, air %d)" % (st["health"], st["air"])


TESTS = [("smoke", test_smoke), ("fill_water", test_fill_water), ("eat", test_eat), ("defend", test_defend), ("clear_resume", test_clear_resume), ("dump", test_dump), ("hotbar_tools", test_hotbar_tools), ("air", test_air)]


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


def main(argv):
    keep = "--keep-open" in argv
    attach = "--attach" in argv
    names = [a for a in argv if not a.startswith("--")]
    tests = [(n, f) for n, f in TESTS if not names or n in names]
    if names and len(tests) != len(names):
        print("unknown test; known: " + ", ".join(n for n, _ in TESTS))
        return 2

    proc = None
    b = mc.Bridge()
    if attach:
        b.connect()
    else:
        mc.stop_testing_instance()
        mc.install()
        proc = mc.launch()
        b.wait_ready(proc=proc)
    log("bridge connected")
    results = []
    try:
        t = enter_world(b)
        for name, fn in tests:
            log("running", name, "...")
            t0 = time.time()
            t.start_log()
            try:
                info = fn(t)
                results.append((name, True, info, time.time() - t0))
            except Exception as e:   # one failing test does not stop the others
                detail = str(e) if isinstance(e, (Fail, BridgeError)) else traceback.format_exc()
                try:
                    b("stopscript")
                    chat = t.lines()[-15:]
                except Exception:
                    chat = []
                if chat:
                    detail += "\n      chat:\n        " + "\n        ".join(chat)
                results.append((name, False, detail, time.time() - t0))
    finally:
        if not keep and not attach:
            log("closing the game")
            mc.stop_testing_instance(b)
        b.close()

    print()
    print("=" * 60)
    for name, ok, info, dt in results:
        print("%s  %-11s %5.1fs  %s" % ("PASS" if ok else "FAIL", name, dt, info))
    failed = sum(1 for r in results if not r[1])
    print("=" * 60)
    print("%d passed, %d failed   (game log: %s)" % (len(results) - failed, failed,
                                                    os.path.join(mc.PROFILE, "logs", "latest.log")))
    return 0 if failed == 0 and results else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
