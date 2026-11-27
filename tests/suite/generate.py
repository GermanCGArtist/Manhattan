"""Generates the test suite's cases (docs/TEST_SUITE.md).

    python tests/suite/generate.py                 # about 2500 cases into tmp/suite/cases
    python tests/suite/generate.py --total 200     # fewer
    python tests/suite/generate.py --smoke         # one case per family (the smoke gate)
    python tests/suite/generate.py --list          # families and their parameters

Every family is a function that turns a parameter set and a seed into a case: setup commands (run on the server,
@p is the player), a Manhattan script and the end state the shared checker (src/ingame/.../SuiteCase.java) tests.
Parameter sets are pairwise combinations (every pair of values at least once), topped up with random rows so each
family gets its share of the total. Case ids are stable: the same family, parameters and seed give the same id.

Coordinates: the flat benchmark world has bedrock at y -64, dirt -63..-62, grass -61; the player stands at
100 -60 100. The test bridge force-loads x/z 80..200, so flat cases stay inside 84..196.
"""
import argparse, hashlib, itertools, json, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "tests", "ingame"))
import litematic  # noqa: E402

GROUND = -61          # top of the flat world's ground (grass)
Y = -60               # where the player stands
O = (100, Y, 100)     # start
WORLD = "manhattan-benchmark"
REAL_SEEDS = [1, 42, 1337, 20261109, 8675309, -5584399987456711267, 3257840388504953787, 164311266871034]


# ------------------------------------------------------------------ helpers

def fill(x1, y1, z1, x2, y2, z2, block, mode=""):
    """fill commands split so none touches more than 32768 blocks."""
    x1, x2 = sorted((x1, x2)); y1, y2 = sorted((y1, y2)); z1, z2 = sorted((z1, z2))
    per_layer = (x2 - x1 + 1) * (z2 - z1 + 1)
    out = []
    if per_layer > 32768:
        step = max(1, 32768 // ((x2 - x1 + 1) * (y2 - y1 + 1)))
        for z in range(z1, z2 + 1, step):
            out += fill(x1, y1, z, x2, y2, min(z2, z + step - 1), block, mode)
        return out
    layers = max(1, 32768 // per_layer)
    for y in range(y1, y2 + 1, layers):
        out.append("fill %d %d %d %d %d %d %s%s" % (x1, y, z1, x2, min(y2, y + layers - 1), z2, block, (" " + mode) if mode else ""))
    return out


def box(x1, y1, z1, x2, y2, z2):
    x1, x2 = sorted((x1, x2)); y1, y2 = sorted((y1, y2)); z1, z2 = sorted((z1, z2))
    return [x1, y1, z1], [x2, y2, z2]


def vol(a, b):
    return (abs(b[0] - a[0]) + 1) * (abs(b[1] - a[1]) + 1) * (abs(b[2] - a[2]) + 1)


def P(p):
    return "pos(%d, %d, %d)" % tuple(p)


def base_setup(difficulty="easy", time="noon"):
    # The benchmark world is a test preset whose ground is not the classic flat layout (and it has village
    # buildings), so every flat case rebuilds its area: bedrock -64, dirt -63..-62, grass -61, air -60..-40.
    reset = (fill(84, -63, 84, 196, -62, 196, "dirt") + fill(84, -61, 84, 196, -61, 196, "grass_block")
             + fill(84, -60, 84, 196, -40, 196, "air"))
    return (["tp @p 100 -30 100 0 0", "kill @e[type=!player]"] + reset +
            ["kill @e[type=item]", "gamemode survival @p", "difficulty %s" % difficulty, "tick rate 20",
             "effect clear @p", "clear @p", "time set %s" % time, "weather clear", "tp @p 100 -60 100 0 0"])


def give(item, n=1):
    out = []
    while n > 0:
        out.append("give @p %s %d" % (item, min(n, 64)))
        n -= 64
    return out


TOOLS = {
    "diamond": ["diamond_pickaxe[unbreakable={}]", "diamond_shovel[unbreakable={}]", "diamond_axe[unbreakable={}]"],
    "iron": ["iron_pickaxe[unbreakable={}]", "iron_shovel[unbreakable={}]", "iron_axe[unbreakable={}]"],
    "stone": ["stone_pickaxe[unbreakable={}]", "stone_shovel[unbreakable={}]", "stone_axe[unbreakable={}]"],
    "haste": ["diamond_pickaxe[unbreakable={}]", "diamond_shovel[unbreakable={}]", "diamond_axe[unbreakable={}]"],
}
TOOL_SPEED = {"haste": 0.35, "diamond": 1.0, "iron": 1.5, "stone": 2.6}
MAT_SPEED = {"stone": 1.0, "cobblestone": 1.1, "dirt": 0.55, "sand": 0.55, "gravel": 0.6, "oak_planks": 1.1,
             "deepslate": 1.7, "andesite": 1.0, "netherrack": 0.4, "mixed": 1.0, "glass": 0.6}
MIXED = ["stone", "dirt", "gravel", "oak_planks", "cobblestone", "sand", "andesite"]


def tools(kind):
    cmds = ["give @p %s 1" % t for t in TOOLS[kind]]
    if kind == "haste":
        cmds.append("effect give @p haste infinite 2 true")
    return cmds


def fill_material(a, b, mat, rng):
    if mat != "mixed":
        return fill(*a, *b, mat)
    cmds = []
    for x in range(a[0], b[0] + 1):
        for z in range(a[2], b[2] + 1):
            cmds += fill(x, a[1], z, x, b[1], z, rng.choice(MIXED))
    return cmds


def clear_job(a, b):
    return ["select(%s, %s)" % (P(a), P(b)), "clear_area()"]


def check_air(a, b, label="area cleared", wet_ok=False, tol=0):
    c = {"type": "blocks", "from": a, "to": b, "is": ["air", "cave_air"], "label": label}
    if wet_ok:
        c["allow_wet_adjacent"] = True
    if tol:
        c["tolerance"] = tol
    return c


def near(p, r=1.5, label="reached target", ignore_y=False, rel=False):
    c = {"type": "player_near", "pos": list(p), "radius": r, "label": label}
    if ignore_y:
        c["ignore_y"] = True
    if rel:
        c["rel"] = True
    return c


ALIVE = {"type": "alive", "label": "alive"}


def clear_seconds(n, mat, tool):
    return n * 0.28 * MAT_SPEED.get(mat, 1.0) * TOOL_SPEED[tool]


# ------------------------------------------------------------------ families
# each: params (name -> values), est (params -> seconds, None = skip combination), build (params, rng) -> dict

FAMILIES = {}


def family(name, area, params, weight=1.0):
    def deco(fn):
        FAMILIES[name] = {"name": name, "area": area, "params": params, "build": fn, "weight": weight}
        return fn
    return deco


# --- navigation -------------------------------------------------------------

@family("nav_walls", "navigation", {"dist": [20, 35, 50, 70], "walls": [1, 2, 3, 4], "height": [1, 2, 3, 5],
                                    "width": [3, 9, 21], "material": ["stone", "dirt", "glass"]})
def nav_walls(p, rng):
    tx = O[0] + p["dist"]
    setup = base_setup() + tools("diamond") + give("dirt", 64)
    xs = sorted(rng.sample(range(O[0] + 4, tx - 2), min(p["walls"], max(1, p["dist"] - 8))))
    for x in xs:
        zc = O[2] + rng.randint(-3, 3)
        setup += fill(x, Y, zc - p["width"] // 2, x, Y + p["height"] - 1, zc + p["width"] // 2, p["material"])
    est = 15 + p["dist"] * 0.6 + p["walls"] * p["height"] * 4
    return dict(setup=setup, script=["goto(%s)" % P((tx, Y, O[2]))], checks=[ALIVE, near((tx, Y, O[2]))], est=est)


@family("nav_gaps", "navigation", {"gap": [1, 2, 3, 4], "depth": [1, 2, 3], "count": [1, 2, 3],
                                   "blocks": [0, 64], "dist": [30, 60]})
def nav_gaps(p, rng):
    tx = O[0] + p["dist"]
    setup = base_setup() + tools("diamond") + give("dirt", p["blocks"])
    xs = sorted(rng.sample(range(O[0] + 5, tx - 5, 6), min(p["count"], len(range(O[0] + 5, tx - 5, 6)))))
    for x in xs:
        setup += fill(x, GROUND - p["depth"] + 1, 85, x + p["gap"] - 1, GROUND, 115, "air")
    return dict(setup=setup, script=["goto(%s)" % P((tx, Y, O[2]))], checks=[ALIVE, near((tx, Y, O[2]))],
                est=15 + p["dist"] * 0.6 + p["count"] * p["gap"] * 6)


@family("nav_fluids", "navigation", {"fluid": ["water", "lava"], "width": [1, 2, 3, 5], "depth": [1, 2, 3],
                                     "blocks": [0, 64], "dist": [30, 50]})
def nav_fluids(p, rng):
    if p["fluid"] == "lava" and p["width"] >= 3 and p["blocks"] == 0:
        p = dict(p, blocks=64)   # no fair way across wide lava without blocks
    tx = O[0] + p["dist"]
    x = O[0] + rng.randint(8, p["dist"] - 8)
    setup = base_setup() + tools("diamond") + give("dirt", p["blocks"])
    setup += fill(x, GROUND - p["depth"] + 1, 84, x + p["width"] - 1, GROUND, 116, p["fluid"])
    return dict(setup=setup, script=["goto(%s)" % P((tx, Y, O[2]))], checks=[ALIVE, near((tx, Y, O[2]))],
                est=20 + p["dist"] * 0.7 + p["width"] * 8)


@family("nav_climb", "navigation", {"height": [2, 3, 5, 8, 12], "blocks": [0, 64], "dist": [10, 20, 30],
                                    "approach": ["plain", "steps", "overhang"]})
def nav_climb(p, rng):
    tx, h = O[0] + p["dist"], p["height"]
    top = Y + h
    setup = base_setup() + tools("diamond") + give("dirt", p["blocks"])
    setup += fill(tx - 1, Y, O[2] - 1, tx + 1, top - 1, O[2] + 1, "stone")
    if p["approach"] == "steps":
        for i in range(1, h):
            # two wide, against the tower's west face (beside it, the last step up would only touch it diagonally)
            setup += fill(tx - 1 - i, Y, O[2] - 1, tx - 1 - i, top - 1 - i, O[2], "cobblestone")
    elif p["approach"] == "overhang":
        setup += fill(tx - 2, top - 1, O[2] - 2, tx + 2, top - 1, O[2] + 2, "stone")
    return dict(setup=setup, script=["goto(%s)" % P((tx, top, O[2]))], checks=[ALIVE, near((tx, top, O[2]), 1.8)],
                est=20 + p["dist"] * 0.6 + h * 5)


@family("nav_descend", "navigation", {"height": [3, 5, 8, 12, 20, 30], "water_bucket": [0, 1],
                                      "landing": ["grass", "stone", "water_pool"], "dist": [10, 25]})
def nav_descend(p, rng):
    h = p["height"]
    setup = base_setup() + tools("diamond") + give("dirt", 64)
    setup += fill(O[0] - 1, Y, O[2] - 1, O[0] + 1, Y + h - 1, O[2] + 1, "stone")
    tx = O[0] + p["dist"]
    if p["landing"] == "stone":
        setup += fill(O[0] + 2, GROUND, 90, tx + 3, GROUND, 110, "stone")
    elif p["landing"] == "water_pool":
        setup += fill(O[0] + 2, GROUND - 1, O[2] - 2, O[0] + 4, GROUND, O[2] + 2, "water")
    if p["water_bucket"]:
        setup += give("water_bucket", 1)
    setup.append("tp @p %d %d %d 0 0" % (O[0], Y + h, O[2]))
    return dict(setup=setup, script=["goto(%s)" % P((tx, Y, O[2]))], checks=[ALIVE, near((tx, Y, O[2]))],
                est=20 + p["dist"] + h * 3)


def maze(n, rng):
    """n odd; returns set of wall cells (i, j) in an n x n grid, corridor from (1,1) to (n-2,n-2)."""
    walls = {(i, j) for i in range(n) for j in range(n)}
    stack = [(1, 1)]
    walls.discard((1, 1))
    while stack:
        i, j = stack[-1]
        nb = [(i + di, j + dj, i + di // 2, j + dj // 2) for di, dj in ((2, 0), (-2, 0), (0, 2), (0, -2))
              if 0 < i + di < n - 1 and 0 < j + dj < n - 1 and (i + di, j + dj) in walls]
        if not nb:
            stack.pop()
            continue
        a, b, c, d = rng.choice(nb)
        walls.discard((a, b)); walls.discard((c, d))
        stack.append((a, b))
    return walls


@family("nav_maze", "navigation", {"size": [9, 13, 17, 21], "wall_h": [2, 3], "material": ["stone", "glass", "dirt"],
                                   "roof": [0, 1]})
def nav_maze(p, rng):
    n = p["size"]
    x0, z0 = O[0] + 2, O[2] - n // 2
    setup = base_setup() + tools("diamond") + give("dirt", 32)
    for (i, j) in maze(n, rng):
        setup += fill(x0 + i, Y, z0 + j, x0 + i, Y + p["wall_h"] - 1, z0 + j, p["material"])
    if p["roof"]:
        setup += fill(x0, Y + p["wall_h"], z0, x0 + n - 1, Y + p["wall_h"], z0 + n - 1, p["material"])
    setup += fill(x0, Y, z0 + 1, x0, Y + 1, z0 + 1, "air")       # entrance
    target = (x0 + n - 2, Y, z0 + n - 2)
    return dict(setup=setup, script=["goto(%s)" % P(target)], checks=[ALIVE, near(target)], est=25 + n * n * 0.25)


@family("terrain_nav", "navigation", {"seed": list(range(len(REAL_SEEDS))), "dist": [30, 60, 100],
                                      "dir": ["n", "e", "s", "w"]}, weight=1.5)
def terrain_nav(p, rng):
    dx, dz = {"n": (0, -1), "e": (1, 0), "s": (0, 1), "w": (-1, 0)}[p["dir"]]
    sx, sz = rng.randint(-20, 20), rng.randint(-20, 20)
    ox, oz = dx * p["dist"], dz * p["dist"]
    # the start is the nearest dry ground to sx sz, so the target is relative to where the player really starts
    setup = ["kill @e[type=!player]", "difficulty easy", "time set noon", "weather clear", "tick rate 20",
             "effect give @p resistance 10 255 true", "suite:surface %d %d %d" % (sx, sz, p["dist"] + 24),
             "gamemode survival @p", "clear @p"] + tools("diamond") + give("dirt", 64) + give("bread", 8)
    return dict(setup=setup, script=["let p = here", "goto(p.x + %d, p.z + %d)" % (ox, oz)],
                checks=[ALIVE, near((ox, 0, oz), 2.5, ignore_y=True, rel=True)], est=40 + p["dist"] * 1.2,
                world_seed=REAL_SEEDS[p["seed"]], min_start_y=-40)


# --- digging ----------------------------------------------------------------

@family("clear_box", "digging", {"sx": [3, 6, 10, 16, 24], "sz": [3, 6, 10, 16, 24], "sy": [1, 2, 3, 5],
                                  "material": ["stone", "dirt", "sand", "gravel", "oak_planks", "deepslate", "mixed"],
                                  "tool": ["haste", "diamond", "iron", "stone"]}, weight=2.0)
def clear_box(p, rng):
    a = [O[0] + 4, Y, O[2] - p["sz"] // 2]
    b = [a[0] + p["sx"] - 1, Y + p["sy"] - 1, a[2] + p["sz"] - 1]
    est = 20 + clear_seconds(vol(a, b), p["material"], p["tool"])
    if est > 700:
        return None
    setup = base_setup() + tools(p["tool"]) + fill_material(a, b, p["material"], rng)
    return dict(setup=setup, script=clear_job(a, b), checks=[ALIVE, check_air(a, b)], est=est)


@family("clear_wet", "digging", {"size": [5, 8, 12], "sy": [1, 2, 3], "water": ["n", "e", "s", "w", "pocket"],
                                 "fluid": ["water", "lava"]})
def clear_wet(p, rng):
    s = p["size"]
    a = [O[0] + 4, Y, O[2] - s // 2]
    b = [a[0] + s - 1, Y + p["sy"] - 1, a[2] + s - 1]
    setup = base_setup() + tools("haste") + fill(*a, *b, "stone")
    w = p["water"]
    if w == "pocket":
        cx, cz = (a[0] + b[0]) // 2, (a[2] + b[2]) // 2
        setup.append("setblock %d %d %d %s" % (cx, a[1], cz, p["fluid"]))
    else:
        side = {"n": (a[0], a[2] - 1, b[0], a[2] - 1), "s": (a[0], b[2] + 1, b[0], b[2] + 1),
                "w": (a[0] - 1, a[2], a[0] - 1, b[2]), "e": (b[0] + 1, a[2], b[0] + 1, b[2])}[w]
        # a fluid trench beside the box, walled in so it cannot spread elsewhere
        x1, z1, x2, z2 = side
        setup += fill(x1 - 1, Y, z1 - 1, x2 + 1, Y + p["sy"], z2 + 1, "stone", "keep")
        setup += fill(x1, Y, z1, x2, Y + p["sy"] - 1, z2, p["fluid"])
    # every cell of the box must be air, except the ones touching the fluid (Manhattan leaves those as a seal);
    # and no fluid may have got into the box
    checks = [ALIVE, check_air(a, b, "area cleared except the seal", wet_ok=True),
              {"type": "dry", "from": [a[0] + 1, a[1], a[2] + 1], "to": [b[0] - 1, b[1], b[2] - 1],
               "label": "no fluid inside"} if w != "pocket" else
              {"type": "dry", "from": a, "to": [a[0], b[1], a[2]], "label": "no fluid at the corner"}]
    return dict(setup=setup, script=clear_job(a, b), checks=checks, est=30 + vol(a, b) * 0.12)


@family("clear_falling", "digging", {"material": ["sand", "gravel"], "layout": ["columns_above", "layer_on_top", "under"],
                                     "size": [4, 7, 10], "sy": [2, 3]})
def clear_falling(p, rng):
    s = p["size"]
    a = [O[0] + 4, Y, O[2] - s // 2]
    b = [a[0] + s - 1, Y + p["sy"] - 1, a[2] + s - 1]
    setup = base_setup() + tools("haste") + fill(*a, *b, "stone")
    extra = []
    if p["layout"] == "columns_above":
        for _ in range(3):
            x, z = rng.randint(a[0], b[0]), rng.randint(a[2], b[2])
            extra += fill(x, b[1] + 1, z, x, b[1] + 3, z, p["material"])
    elif p["layout"] == "layer_on_top":
        extra += fill(a[0], b[1] + 1, a[2], b[0], b[1] + 1, b[2], p["material"])
    else:
        extra += fill(a[0], a[1], a[2], b[0], a[1], b[2], p["material"])
    # blocks above the selection fall into it; whatever ends up inside must be cleared too
    checks = [ALIVE, check_air(a, b)]
    return dict(setup=setup + extra, script=clear_job(a, b), checks=checks, est=40 + vol(a, b) * 0.15)


@family("clear_tunnel", "digging", {"length": [8, 16, 32], "w": [1, 2, 3], "h": [2, 3], "material": ["stone", "dirt", "mixed"],
                                    "ores": [0, 1]})
def clear_tunnel(p, rng):
    a = [O[0] + 3, Y, O[2] - 3]
    b = [a[0] + p["length"] + 3, Y + 5, a[2] + 6]
    setup = base_setup() + tools("haste") + fill_material(a, b, p["material"], rng)
    # a solid roof: with "mixed", sand and gravel columns would otherwise fall into the tunnel and leave the roof open
    setup += fill(a[0], Y + 5, a[2], b[0], Y + 5, b[2], "stone")
    t1 = [a[0], Y, O[2] - p["w"] // 2]
    t2 = [a[0] + p["length"] - 1, Y + p["h"] - 1, t1[2] + p["w"] - 1]
    if p["ores"]:
        for _ in range(6):
            setup.append("setblock %d %d %d %s" % (rng.randint(t1[0], t2[0]), rng.randint(t1[1], t2[1]),
                                                   rng.randint(t1[2], t2[2]), rng.choice(["coal_ore", "iron_ore", "diamond_ore"])))
    return dict(setup=setup, script=clear_job(t1, t2),
                checks=[ALIVE, check_air(t1, t2, "tunnel dug"),
                        {"type": "blocks", "from": [a[0], Y + 5, a[2]], "to": [b[0], Y + 5, b[2]], "not": ["air"],
                         "label": "roof above the tunnel untouched", "tolerance": 0}],
                est=25 + vol(t1, t2) * 0.2)


@family("clear_tall", "digging", {"height": [6, 10, 16], "size": [2, 4, 6], "material": ["stone", "dirt", "oak_planks"],
                                  "blocks": [0, 64]})
def clear_tall(p, rng):
    a = [O[0] + 4, Y, O[2] - p["size"] // 2]
    b = [a[0] + p["size"] - 1, Y + p["height"] - 1, a[2] + p["size"] - 1]
    setup = base_setup() + tools("haste") + give("dirt", p["blocks"]) + fill(*a, *b, p["material"])
    return dict(setup=setup, script=clear_job(a, b), checks=[ALIVE, check_air(a, b)], est=40 + vol(a, b) * 0.25)


# --- water ------------------------------------------------------------------

@family("fill_pool", "water", {"sx": [2, 3, 5, 7, 9], "sz": [2, 3, 5, 7], "depth": [1, 2, 3],
                               "block": ["dirt", "cobblestone", "stone"], "supply": ["exact", "plenty", "chest"]})
def fill_pool(p, rng):
    a = [O[0] + 3, GROUND - p["depth"] + 1, O[2] - p["sz"] // 2]
    b = [a[0] + p["sx"] - 1, GROUND, a[2] + p["sz"] - 1]
    n = vol(a, b)
    setup = base_setup() + tools("diamond") + fill(*a, *b, "water")
    if p["supply"] == "chest":
        setup.append("setblock 96 -60 100 chest")
        for i in range((n + 63) // 64 + 1):
            setup.append("item replace block 96 -60 100 container.%d with %s 64" % (i, p["block"]))
        script = ["select(%s, %s)" % (P(a), P(b)), "fill_water(\"%s\", pos(96, -60, 100), 32)" % p["block"]]
    else:
        setup += give(p["block"], n if p["supply"] == "exact" else n + 64)
        script = ["select(%s, %s)" % (P(a), P(b)), "fill_water(\"%s\")" % p["block"]]
    checks = [ALIVE, {"type": "dry", "from": a, "to": b, "label": "pool dry"},
              {"type": "blocks", "from": a, "to": b, "not": ["air", "water"], "label": "pool filled"}]
    return dict(setup=setup, script=script, checks=checks, est=30 + n * 1.2)


@family("drain_flowing", "water", {"size": [3, 5, 7], "sources": [1, 2, 4], "block": ["dirt", "cobblestone"]})
def drain_flowing(p, rng):
    s = p["size"]
    a = [O[0] + 3, GROUND - 1, O[2] - s // 2]
    b = [a[0] + s - 1, GROUND, a[2] + s - 1]
    setup = base_setup() + tools("diamond") + give(p["block"], 128)
    # the pit is dug into the ground (dirt and grass around it); a stone ring up to GROUND + 1 would also have put a
    # stone lid over the pit and sealed the water in
    setup += fill(*a, *b, "air")
    for _ in range(p["sources"]):
        setup.append("setblock %d %d %d water" % (rng.randint(a[0], b[0]), GROUND, rng.randint(a[2], b[2])))
    # "wait 3": the water needs a few seconds to spread through the pit before the job looks at it
    return dict(setup=setup, script=["wait 3", "select(%s, %s)" % (P(a), P(b)), "fill_water(\"%s\")" % p["block"]],
                checks=[ALIVE, {"type": "dry", "from": a, "to": b, "label": "pit dry"}], est=40 + s * s * 2)


# --- schematics -------------------------------------------------------------

SCHEM_BLOCKS = {"stone": "minecraft:stone", "planks": "minecraft:oak_planks", "glass": "minecraft:glass",
                "cobble": "minecraft:cobblestone", "bricks": "minecraft:bricks", "log": "minecraft:oak_log[axis=y]"}


def schem_model(shape, size, palette, rng):
    L = {"s": 4, "m": 7, "l": 11}[size]
    b = {}
    pick = lambda: SCHEM_BLOCKS[rng.choice(palette)]
    if shape == "wall":
        dims = (L, max(2, L // 2), 1)
        for x in range(dims[0]):
            for y in range(dims[1]):
                b[(x, y, 0)] = pick()
    elif shape == "floor":
        dims = (L, 1, L)
        for x in range(L):
            for z in range(L):
                b[(x, 0, z)] = pick()
    elif shape == "hut":
        dims = (L, 4, L)
        for x in range(L):
            for z in range(L):
                for y in range(4):
                    edge = x in (0, L - 1) or z in (0, L - 1)
                    if y == 3 or (edge and not (z == 0 and x == L // 2 and y < 2)):
                        b[(x, y, z)] = pick()
    elif shape == "tower":
        dims = (3, L + 2, 3)
        for y in range(dims[1]):
            for x in range(3):
                for z in range(3):
                    if (x, z) != (1, 1):
                        b[(x, y, z)] = pick()
    else:  # overhang: a bridge between two pillars, needs scaffolding
        dims = (L, 5, 1)
        for y in range(5):
            b[(0, y, 0)] = pick()
            b[(L - 1, y, 0)] = pick()
        for x in range(L):
            b[(x, 4, 0)] = pick()
    return dims, b


@family("schem_build", "building", {"shape": ["wall", "floor", "hut", "tower", "bridge"], "size": ["s", "m", "l"],
                                    "palette": ["stone", "planks", "mixed", "glass_mix"], "supply": ["exact", "plenty"],
                                    "clutter": [0, 1]}, weight=2.0)
def schem_build(p, rng):
    pal = {"stone": ["stone"], "planks": ["planks"], "mixed": ["stone", "planks", "cobble", "bricks", "log"],
           "glass_mix": ["glass", "planks", "stone"]}[p["palette"]]
    dims, model = schem_model(p["shape"], p["size"], pal, rng)
    origin = (O[0] + 4, Y, O[2] - dims[2] // 2)
    setup = base_setup() + tools("diamond") + give("dirt", 64)
    need = {}
    for st in model.values():
        item = st.split("[")[0].split(":")[1]
        need[item] = need.get(item, 0) + 1
    for item, n in need.items():
        setup += give(item, n if p["supply"] == "exact" else n + 32)
    if p["clutter"]:
        for _ in range(6):
            x, z = origin[0] + rng.randint(-1, dims[0]), origin[2] + rng.randint(-1, dims[2])
            setup.append("setblock %d %d %d dirt" % (x, Y, z))
    cells = [[origin[0] + x, origin[1] + y, origin[2] + z, st.split("[")[0]] for (x, y, z), st in model.items()]
    name = "suite-%s" % hashlib.sha1(json.dumps(sorted(model.items())).encode()).hexdigest()[:8]
    files = {("schem", name): (dims, model, origin)}
    return dict(setup=setup, script=["schem_build()"],
                checks=[ALIVE, {"type": "block_list", "cells": cells, "label": "schematic built"}],
                est=40 + len(model) * 1.6, files=files)


# --- survival ---------------------------------------------------------------

FOODS = ["bread", "cooked_beef", "golden_carrot", "baked_potato"]


@family("eat_work", "survival", {"hunger": [20, 50, 120], "food": FOODS + ["offhand_bread"], "task": ["clear", "walk", "idle"],
                                 "food_count": [2, 16]})
def eat_work(p, rng):
    setup = base_setup() + tools("haste")
    if p["food"] == "offhand_bread":
        setup.append("item replace entity @p weapon.offhand with bread %d" % p["food_count"])
    else:
        setup += give(p["food"], p["food_count"])
    # strong hunger for 25 s drains the bar below 14 early in every task; the script then runs at least 60 s more
    setup.append("effect give @p hunger 25 %d true" % p["hunger"])
    script = ["on food < 14", "  eat()", "end"]
    checks = [ALIVE]
    if p["task"] == "clear":
        a, b = [O[0] + 4, Y, O[2] - 4], [O[0] + 12, Y + 1, O[2] + 4]
        setup += fill(*a, *b, "dirt")
        script += ["wait 20"] + clear_job(a, b) + ["wait 20"]
        checks.append(check_air(a, b))
    elif p["task"] == "walk":
        t = (O[0] + 80, Y, O[2])
        script += ["wait 20", "goto(%s)" % P(t), "goto(%s)" % P(O), "wait 15"]
        checks.append(near(O, 2))
    else:
        script += ["wait 70"]
    food_item = "bread" if p["food"] == "offhand_bread" else p["food"]
    # the food bar must have been looked after: something was eaten
    checks.append({"type": "item_count", "item": food_item, "max": p["food_count"] - 1, "label": "ate something"})
    return dict(setup=setup, script=script, checks=checks, est=90)


@family("defend_work", "survival", {"mob": ["zombie", "skeleton", "spider", "husk", "creeper"], "count": [1, 2, 3, 5],
                                    "armor": ["none", "iron", "diamond"], "sword": ["none", "iron", "diamond"],
                                    "task": ["clear", "walk", "idle"]}, weight=1.5)
def defend_work(p, rng):
    setup = base_setup("easy", "midnight") + tools("haste")
    if p["armor"] != "none":
        for slot, piece in (("head", "helmet"), ("chest", "chestplate"), ("legs", "leggings"), ("feet", "boots")):
            setup.append("item replace entity @p armor.%s with %s_%s" % (slot, p["armor"], piece))
    if p["sword"] != "none":
        setup += give("%s_sword" % p["sword"], 1)
    setup += give("bread", 16)
    for i in range(p["count"]):
        ang = rng.uniform(0, 6.283)
        import math
        setup.append("summon %s %d %d %d" % (p["mob"], O[0] + int(8 * math.cos(ang)), Y, O[2] + int(8 * math.sin(ang))))
    script = ["on mobs > 0", "  defend()", "end", "on food < 14", "  eat()", "end"]
    checks = [ALIVE]
    if p["task"] == "clear":
        a, b = [O[0] + 3, Y, O[2] - 3], [O[0] + 9, Y + 1, O[2] + 3]
        setup += fill(*a, *b, "dirt")
        script += clear_job(a, b) + ["wait 10"]
        checks.append(check_air(a, b, tol=4 if p["mob"] == "creeper" else 0))
    elif p["task"] == "walk":
        t = (O[0] + 50, Y, O[2])
        script += ["goto(%s)" % P(t), "wait 10"]
        checks.append(near(t, 2.5))
    else:
        script += ["wait 45"]
    return dict(setup=setup, script=script, checks=checks, est=100, max_deaths=0)


@family("drown_escape", "survival", {"depth": [2, 3, 5, 8], "lid": [1, 2], "material": ["stone", "dirt", "glass"],
                                     "tool": ["diamond", "none"]})
def drown_escape(p, rng):
    d = p["depth"]
    if p["tool"] == "none" and p["material"] == "stone":
        return None   # stone by hand under water takes about 37 s a block, longer than the air lasts
    setup = base_setup()
    if p["tool"] != "none":
        setup += tools("diamond")
    setup += fill(O[0] - 1, GROUND, O[2] - 1, O[0] + 1, Y + d + p["lid"] - 1, O[2] + 1, p["material"])
    setup += fill(O[0], Y, O[2], O[0], Y + d - 1, O[2], "water")
    return dict(setup=setup, script=["wait 45"], checks=[ALIVE], est=50, max_deaths=0, no_disable=True)


@family("dump_chest", "logistics", {"junk": [3, 8, 20], "chest_dist": [3, 12, 30], "full_inv": [0, 1],
                                    "chest_state": ["empty", "half", "almost_full"]})
def dump_chest(p, rng):
    junk = ["rotten_flesh", "bone", "string", "cobblestone", "gravel", "andesite", "spider_eye", "gunpowder"]
    cx = O[0] + p["chest_dist"]
    setup = base_setup() + tools("diamond") + give("bread", 12) + give("golden_apple", 2)
    setup.append("setblock %d -60 100 chest" % cx)
    used = {"empty": 0, "half": 13, "almost_full": 24}[p["chest_state"]]
    for i in range(used):
        setup.append("item replace block %d -60 100 container.%d with oak_sapling 1" % (cx, i))
    picks = rng.sample(junk, 4)
    for it in picks:
        setup += give(it, p["junk"])
    if p["full_inv"]:
        setup += give("cobblestone", 64 * 20)
    checks = [ALIVE, {"type": "item_count", "item": "bread", "min": 12, "label": "food kept"},
              {"type": "item_count", "item": "golden_apple", "min": 2, "label": "golden apples kept"},
              {"type": "item_count", "item": "diamond_pickaxe", "min": 1, "label": "pickaxe kept"}]
    room = 27 - used
    if room >= 4 + (20 if p["full_inv"] else 0):
        for it in picks:
            checks.append({"type": "item_count", "item": it, "max": 0, "label": "%s dumped" % it})
    script = ["dump(pos(%d, -60, 100))" % cx]
    return dict(setup=setup, script=script, checks=checks, est=30 + p["chest_dist"])


@family("chest_fetch", "logistics", {"chests": [1, 2, 4], "amount": [16, 64, 200], "item": ["dirt", "torch", "oak_log"],
                                     "spread": [3, 15]})
def chest_fetch(p, rng):
    setup = base_setup() + tools("diamond")
    chests = []
    per = (p["amount"] + p["chests"] - 1) // p["chests"] + 16
    for k in range(p["chests"]):
        x, z = O[0] + rng.randint(2, p["spread"] + 2), O[2] + (k * 2 - p["chests"])
        chests.append((x, -60, z))
        setup.append("setblock %d -60 %d chest" % (x, z))
        for i in range((per + 63) // 64):
            setup.append("item replace block %d -60 %d container.%d with %s %d" % (x, z, i, p["item"], min(64, per - 64 * i)))
    lst = "[" + ", ".join(P(c) for c in chests) + "]"
    script = ["take_upto(%s, \"%s\", %d)" % (lst, p["item"], p["amount"])]
    return dict(setup=setup, script=script,
                checks=[ALIVE, {"type": "item_count", "item": p["item"], "min": p["amount"], "label": "fetched"}],
                est=30 + p["chests"] * 10 + p["spread"])


# --- combinations -----------------------------------------------------------

@family("combo_clear", "combination", {"size": [5, 9, 14], "hunger": [0, 1], "mobs": [0, 2], "water_side": [0, 1],
                                       "sand_above": [0, 1], "tool": ["haste", "iron"], "night": [0, 1]}, weight=2.0)
def combo_clear(p, rng):
    s = p["size"]
    a = [O[0] + 4, Y, O[2] - s // 2]
    b = [a[0] + s - 1, Y + 1, a[2] + s - 1]
    est = 40 + clear_seconds(vol(a, b), "stone", p["tool"]) * 1.5
    if est > 700:
        return None
    setup = base_setup("easy", "midnight" if p["night"] else "noon")
    setup += tools(p["tool"]) + give("bread", 16) + give("iron_sword", 1) + fill(*a, *b, "stone")
    wet = False
    if p["water_side"]:
        setup += fill(b[0] + 1, Y - 1, a[2] - 1, b[0] + 3, Y + 2, b[2] + 1, "stone", "keep")
        setup += fill(b[0] + 2, Y, a[2], b[0] + 2, Y + 1, b[2], "water")
        wet = True
    if p["sand_above"]:
        for _ in range(3):
            x, z = rng.randint(a[0], b[0]), rng.randint(a[2], b[2])
            setup += fill(x, b[1] + 1, z, x, b[1] + 2, z, "sand")
    if p["hunger"]:
        setup.append("effect give @p hunger 120 10 true")
    for _ in range(p["mobs"]):
        setup.append("summon zombie %d -60 %d" % (O[0] - 6, O[2] + rng.randint(-6, 6)))
    script = ["on mobs > 0", "  defend()", "end", "on food < 14", "  eat()", "end"] + clear_job(a, b)
    return dict(setup=setup, script=script, checks=[ALIVE, check_air(a, b, wet_ok=wet)], est=est, max_deaths=0)


@family("combo_build", "combination", {"shape": ["wall", "hut", "bridge"], "size": ["s", "m"], "hunger": [0, 1],
                                       "mobs": [0, 2], "night": [0, 1], "clutter": [0, 1]}, weight=1.5)
def combo_build(p, rng):
    c = schem_build({"shape": p["shape"], "size": p["size"], "palette": "mixed", "supply": "plenty",
                     "clutter": p["clutter"]}, rng)
    setup = base_setup("easy", "midnight" if p["night"] else "noon") + c["setup"][len(base_setup()):]
    setup += give("bread", 16) + give("iron_sword", 1)
    if p["hunger"]:
        setup.append("effect give @p hunger 120 10 true")
    for _ in range(p["mobs"]):
        setup.append("summon zombie %d -60 %d" % (O[0] - 6, O[2] + rng.randint(-6, 6)))
    c["setup"] = setup
    c["script"] = ["on mobs > 0", "  defend()", "end", "on food < 14", "  eat()", "end"] + c["script"]
    c["est"] = c["est"] * 1.4
    c["max_deaths"] = 0
    return c


@family("combo_nav_work", "combination", {"obstacle": ["walls", "gap", "water", "lava"], "dist": [30, 60],
                                          "job": ["clear", "fill", "build"], "return_home": [0, 1]}, weight=1.5)
def combo_nav_work(p, rng):
    tx = O[0] + p["dist"]
    setup = base_setup() + tools("haste") + give("dirt", 128) + give("bread", 8)
    x = O[0] + rng.randint(8, p["dist"] - 15)
    if p["obstacle"] == "walls":
        setup += fill(x, Y, 92, x, Y + 2, 108, "stone")
    elif p["obstacle"] == "gap":
        setup += fill(x, GROUND - 2, 84, x + 2, GROUND, 116, "air")
    else:
        setup += fill(x, GROUND - 1, 84, x + 2, GROUND, 116, p["obstacle"])
    a, b = [tx, Y, O[2] - 3], [tx + 5, Y + 1, O[2] + 3]
    checks = [ALIVE]
    script = ["goto(%s)" % P((tx - 2, Y, O[2]))]
    files = None
    if p["job"] == "clear":
        setup += fill(*a, *b, "cobblestone")
        script += clear_job(a, b)
        checks.append(check_air(a, b))
    elif p["job"] == "fill":
        pa, pb = [tx, GROUND - 1, O[2] - 2], [tx + 3, GROUND, O[2] + 2]
        setup += fill(*pa, *pb, "water")
        script += ["select(%s, %s)" % (P(pa), P(pb)), "fill_water(\"dirt\")"]
        checks.append({"type": "dry", "from": pa, "to": pb, "label": "pool dry"})
    else:
        model = {(i, j, 0): "minecraft:stone" for i in range(5) for j in range(3)}
        origin = (tx, Y, O[2] + 3)
        setup += give("stone", 32)
        name = "suite-wall5x3"
        files = {("schem", name): ((5, 3, 1), model, origin)}
        script += ["schem_build()"]
        checks.append({"type": "block_list", "label": "wall built",
                       "cells": [[origin[0] + i, origin[1] + j, origin[2], "minecraft:stone"] for (i, j, _) in model]})
    if p["return_home"]:
        script += ["goto(%s)" % P(O)]
        checks.append(near(O, 2, "back home"))
    c = dict(setup=setup, script=script, checks=checks, est=60 + p["dist"] * 1.5 + 60)
    if files:
        c["files"] = files
    return c


@family("chain_jobs", "combination", {"jobs": [3, 4, 5], "order": ["a", "b", "c"], "hunger": [0, 1]}, weight=1.0)
def chain_jobs(p, rng):
    setup = base_setup() + tools("haste") + give("dirt", 128) + give("bread", 16)
    if p["hunger"]:
        setup.append("effect give @p hunger 200 5 true")
    script = ["on food < 14", "  eat()", "end"]
    checks = [ALIVE]
    kinds = ["clear", "fill", "walk", "clear2", "fetch"]
    rng.shuffle(kinds)
    for k in kinds[:p["jobs"]]:
        if k in ("clear", "clear2"):
            z0 = O[2] - 12 if k == "clear" else O[2] + 6
            a, b = [O[0] + 6, Y, z0], [O[0] + 11, Y + 1, z0 + 5]
            setup += fill(*a, *b, "stone")
            script += clear_job(a, b)
            checks.append(check_air(a, b, "%s done" % k))
        elif k == "fill":
            a, b = [O[0] - 8, GROUND - 1, O[2] - 2], [O[0] - 5, GROUND, O[2] + 1]
            setup += fill(*a, *b, "water")
            script += ["select(%s, %s)" % (P(a), P(b)), "fill_water(\"dirt\")"]
            checks.append({"type": "dry", "from": a, "to": b, "label": "fill done"})
        elif k == "walk":
            script += ["goto(%s)" % P((O[0] + 40, Y, O[2] + 20))]
        else:
            setup.append("setblock 96 -60 104 chest")
            setup.append("item replace block 96 -60 104 container.0 with torch 64")
            script += ["take(pos(96, -60, 104), \"torch\", 32)"]
            checks.append({"type": "item_count", "item": "torch", "min": 32, "label": "fetch done"})
    script += ["goto(%s)" % P(O)]
    checks.append(near(O, 2, "back home"))
    return dict(setup=setup, script=script, checks=checks, est=60 + p["jobs"] * 60)


@family("terrain_clear", "digging", {"seed": list(range(len(REAL_SEEDS))), "size": [3, 5, 8], "depth": [1, 2, 3]})
def terrain_clear(p, rng):
    sx, sz = rng.randint(-30, 30), rng.randint(-30, 30)
    setup = ["kill @e[type=!player]",
             "difficulty easy", "time set noon", "weather clear", "tick rate 20",
             "effect give @p resistance 10 255 true", "suite:surface %d %d 32" % (sx, sz),
             "gamemode survival @p", "clear @p"] + tools("haste") + give("dirt", 64)
    s, d = p["size"], p["depth"]
    # relative to where the player stands when the script starts: a box beside them, d deep into the ground
    a, b = [2, -d, -(s // 2)], [1 + s, 1, s - 1 - s // 2]
    script = ["let p = here", "select(pos(p.x + %d, p.y + %d, p.z + %d), pos(p.x + %d, p.y + %d, p.z + %d))" % (*a, *b),
              "clear_area()"]
    chk = check_air(a, b, wet_ok=True, tol=2)
    chk["rel"] = True
    return dict(setup=setup, script=script, checks=[ALIVE, chk], est=60 + vol(a, b) * 0.3,
                world_seed=REAL_SEEDS[p["seed"]], min_start_y=-40)


# ------------------------------------------------------------------ pairwise generation

def pairwise_rows(params, rng, extra=0, valid=lambda r: True):
    names = list(params)
    uncovered = set()
    for (i, a), (j, b) in itertools.combinations(enumerate(names), 2):
        for va in range(len(params[a])):
            for vb in range(len(params[b])):
                uncovered.add((i, va, j, vb))
    rows = []
    tries = 0
    while uncovered and tries < 5000:
        best, best_n = None, -1
        for _ in range(60):
            r = [rng.randrange(len(params[n])) for n in names]
            # seed the candidate with one uncovered pair so progress is guaranteed
            i, va, j, vb = next(iter(uncovered)) if rng.random() < 0.5 else rng.choice(tuple(uncovered))
            r[i], r[j] = va, vb
            if not valid(r):
                continue
            k = sum(1 for (x, y) in itertools.combinations(range(len(names)), 2) if (x, r[x], y, r[y]) in uncovered)
            if k > best_n:
                best, best_n = r, k
        tries += 1
        if best is None:
            continue
        if best_n == 0:
            # remaining pairs may be impossible (invalid combinations)
            break
        rows.append(best)
        for x, y in itertools.combinations(range(len(names)), 2):
            uncovered.discard((x, best[x], y, best[y]))
    for _ in range(extra):
        for _ in range(50):
            r = [rng.randrange(len(params[n])) for n in names]
            if valid(r):
                rows.append(r)
                break
    return [{n: params[n][v] for n, v in zip(names, r)} for r in rows]


def make_case(fam, params, seed):
    rng = random.Random("%s|%s|%d" % (fam["name"], json.dumps(params, sort_keys=True), seed))
    c = fam["build"](dict(params), rng)
    if c is None:
        return None
    key = json.dumps({"f": fam["name"], "p": params, "s": seed}, sort_keys=True)
    cid = "%s/%s" % (fam["name"], hashlib.sha1(key.encode()).hexdigest()[:10])
    est = c.pop("est")
    limit = int(min(900, max(120, est * 2.5 + 30)))
    script = ["timeout(%d)" % (limit - 15)] + ([] if c.pop("no_disable", False) else ["disable()"]) + c["script"]
    return {"id": cid, "family": fam["name"], "area": fam["area"], "params": params, "seed": seed,
            "est_s": int(est), "limit_s": limit, "max_deaths": c.get("max_deaths", 0), "world_seed": c.get("world_seed"),
            "min_start_y": c.get("min_start_y"), "setup": c["setup"], "script": script, "checks": c["checks"], "files": c.get("files")}


def write_case(case, out):
    path = os.path.join(out, case["id"].replace("/", "__") + ".json")
    files = case.pop("files", None) or {}
    placements = []
    for (kind, name), (dims, model, origin) in files.items():
        if kind == "schem":
            placements.append((name, dims, model, origin))
    case["schematics"] = [{"name": n, "dims": d, "origin": list(o),
                           "blocks": [[x, y, z, st] for (x, y, z), st in m.items()]} for n, d, m, o in placements]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(case, f, indent=1)
    return path


def generate(total, smoke=False, only=None):
    fams = [f for f in FAMILIES.values() if not only or only in f["name"]]
    wsum = sum(f["weight"] for f in fams)
    cases = []
    for fam in fams:
        rng = random.Random("rows|" + fam["name"])
        quota = 1 if smoke else max(1, int(round(total * fam["weight"] / wsum)))
        rows = pairwise_rows(fam["params"], rng)
        if smoke:
            rows = rows[:3]
        out, seed, i = [], 0, 0
        seen = set()
        while len(out) < quota and i < quota * 20 + 50:
            if rows and i < len(rows):
                params = rows[i]
            else:
                params = {n: rng.choice(v) for n, v in fam["params"].items()}
                seed = rng.randrange(1 << 30)
            i += 1
            k = json.dumps(params, sort_keys=True) + str(seed)
            if k in seen:
                continue
            seen.add(k)
            c = make_case(fam, params, seed)
            if c:
                out.append(c)
        cases += out
    return cases


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--total", type=int, default=2500)
    ap.add_argument("--smoke", action="store_true", help="one case per family")
    ap.add_argument("--only", help="only families whose name contains this")
    ap.add_argument("--out", default=os.path.join(REPO, "tmp", "suite", "cases"))
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        for f in FAMILIES.values():
            print("%-16s %-12s %s" % (f["name"], f["area"], ", ".join("%s=%s" % kv for kv in f["params"].items())))
        return
    cases = generate(a.total, a.smoke, a.only)
    os.makedirs(a.out, exist_ok=True)
    for old in os.listdir(a.out):
        if old.endswith(".json"):
            os.remove(os.path.join(a.out, old))
    for c in cases:
        write_case(c, a.out)
    by = {}
    for c in cases:
        by[c["family"]] = by.get(c["family"], 0) + 1
    print("%d cases in %d families -> %s" % (len(cases), len(by), a.out))
    print("estimated game time %.1f h (plus ~40 s start-up per game)" % (sum(c["est_s"] for c in cases) / 3600))


if __name__ == "__main__":
    main()
