"""Runs every Deming spec in tests/deming on up to N games at once.

    python tests/deming/waves.py                 # pack the specs into waves of <= 32 games and run them
    python tests/deming/waves.py --max 16        # smaller waves
    python tests/deming/waves.py --only redesign # specs whose name contains "redesign"
    python tests/deming/waves.py --dry-run       # only write the wave files
    python tests/deming/waves.py --set-version   # point every spec at the jars of mod_version (gradle.properties)

The specs are packed largest first into waves of at most --max instances. Every instance gets a unique id
(<spec>__<id>), its own test bridge port (25600 + n) and an absolute jar path, so specs that were written to run
alone can share a wave, and is not pinned to CPUs (cpu_cores 0; see --pin). The waves run one after another with a Deming config that allows --max games and
--ram MB in total (Deming queues games beyond that), at below-normal priority. Wave files, Deming's JSON output and a
summary (summary.txt) go to tmp/deming-waves/.
"""
import argparse, copy, glob, json, os, re, subprocess, sys, time

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPECS = os.path.join(REPO, "tests", "deming")
OUT = os.path.join(REPO, "tmp", "deming-waves")


LISKOV_JAR = None


def find_deming():
    """deming.exe from a Deming checkout next to the repo (or next to any parent, for worktrees), else PATH."""
    d = REPO
    while True:
        for target in ("target-manhattan", "target"):
            exe = os.path.join(os.path.dirname(d), "Deming", target, "release", "deming.exe")
            if os.path.exists(exe):
                return exe
        if os.path.dirname(d) == d:
            return "deming"
        d = os.path.dirname(d)


DEMING_DEFAULT = find_deming()


def mod_version():
    for line in open(os.path.join(REPO, "gradle.properties"), encoding="utf-8"):
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("no mod_version in gradle.properties")


def fleet_version():
    """mod_version of the manhattan-fleet-server checkout next to this repo (for the fleet specs)."""
    p = os.path.join(os.path.dirname(REPO), "manhattan-fleet-server", "gradle.properties")
    if not os.path.exists(p):
        return None
    for line in open(p, encoding="utf-8"):
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    return None


def mc_version():
    for line in open(os.path.join(REPO, "gradle.properties"), encoding="utf-8"):
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("no minecraft_version in gradle.properties")


def set_version(ver):
    """Rewrites only the jar names and Minecraft versions, so the files keep their formatting."""
    fver = fleet_version()
    mc = mc_version()
    for f in sorted(glob.glob(os.path.join(SPECS, "*.json"))):
        t = open(f, newline="", encoding="utf-8").read()
        n = re.sub(r"build/libs/manhattan-testbridge-[\w.\-]+?\.jar", "build/libs/manhattan-testbridge-%s.jar" % ver, t)
        n = re.sub(r"(?<![\w-])build/libs/manhattan-(?!testbridge|fleet)[\w.\-]+?\.jar", "build/libs/manhattan-%s.jar" % ver, n)
        n = re.sub(r'"mc_version": "[^"]+"', '"mc_version": "%s"' % mc, n)
        if fver:
            n = re.sub(r"manhattan-fleet-server-[\d][\w.\-]*?\.jar", "manhattan-fleet-server-%s.jar" % fver, n)
            n = re.sub(r"manhattan-fleet-benchbridge-[\w.\-]+?\.jar", "manhattan-fleet-benchbridge-%s.jar" % fver, n)
        if n != t:
            open(f, "w", newline="", encoding="utf-8").write(n)
            print("updated", os.path.basename(f))


def pack(specs, limit):
    waves = []  # [ [names], count ]
    for name, d in sorted(specs.items(), key=lambda kv: -len(kv[1]["instances"])):
        n = len(d["instances"])
        if d.get("server"):
            waves.append([[name], limit + 1])  # brings its own server: runs alone (never shares a wave)
            continue
        if n > limit:
            sys.exit("%s has %d instances, more than --max %d" % (name, n, limit))
        for w in waves:
            if w[1] <= limit and w[1] + n <= limit:
                w[0].append(name)
                w[1] += n
                break
        else:
            waves.append([[name], n])
    return [w[0] for w in waves]


LISKOV_DIR = os.path.join(os.path.dirname(REPO), "Liskov", "liskov-ui", "build", "libs")


def liskov_jar():
    """Manhattan needs Liskov since 2.16.0. The jar named by --liskov, else the one matching liskov_version in
    gradle.properties, else the newest built one in ../Liskov/liskov-ui/build/libs."""
    if LISKOV_JAR:
        return LISKOV_JAR
    jars = [j for j in glob.glob(os.path.join(LISKOV_DIR, "liskov-*.jar")) if not j.endswith("-sources.jar")]
    if not jars:
        sys.exit("no Liskov jar in %s - build ../Liskov first or pass --liskov" % LISKOV_DIR)
    want = None
    for line in open(os.path.join(REPO, "gradle.properties"), encoding="utf-8"):
        if line.startswith("liskov_version="):
            want = os.path.join(LISKOV_DIR, "liskov-%s.jar" % line.split("=", 1)[1].strip())
    if want and os.path.exists(want):
        return want
    def key(j):
        v = re.match(r"liskov-([\d.]+)", os.path.basename(j))
        return [int(x) for x in v.group(1).strip(".").split(".")] if v else []
    return max(jars, key=key)


def add_liskov(mods):
    if not any("liskov" in os.path.basename(m.get("local_path", "")) for m in mods):
        mods.append({"local_path": liskov_jar()})


def absolute_mods(name, mods):
    for m in mods:
        if "local_path" in m:
            p = os.path.normpath(os.path.join(REPO, *m["local_path"].split("/")))
            if not os.path.exists(p):
                sys.exit("%s: %s is missing - build first (also ../manhattan-fleet-server for fleet specs), "
                         "or run --set-version" % (name, p))
            m["local_path"] = p


def build_wave(k, names, specs, pin=False):
    inst, timeout = [], 0
    if len(names) == 1 and specs[names[0]].get("server"):
        # a fleet spec: its own dedicated server, accounts (the server bench bridge knows them by name) and ports
        d = copy.deepcopy(specs[names[0]])
        d.pop("description", None)
        for i in d["instances"]:
            if not pin:
                i["cpu_cores"] = 0
            absolute_mods(names[0], i["mods"])
            add_liskov(i["mods"])
        absolute_mods(names[0], d["server"]["mods"])
        d["name"] = "waves-%d-%s" % (k, d["name"])
        return d
    for name in names:
        d = specs[name]
        timeout = max(timeout, d.get("timeout_secs") or 0)
        for i in d["instances"]:
            i = copy.deepcopy(i)
            i["id"] = "%s__%s" % (name.replace("bench-", ""), i["id"])
            i["jvm_args"] = [a for a in i["jvm_args"] if not a.startswith("-Dmanhattan.testbridge.port=")]
            i["jvm_args"].append("-Dmanhattan.testbridge.port=%d" % (25600 + len(inst)))
            i["account"] = {"offline": {"username": "W%dI%02d" % (k, len(inst))}}
            if not pin:
                # Deming pins cpu_cores to the LOWEST logical processors, so every game of a wave would share
                # CPUs 0-3 and most never finish booting; 0 = no affinity, Windows spreads them over all threads
                i["cpu_cores"] = 0
            for m in i["mods"]:
                if "local_path" in m:
                    p = os.path.join(REPO, *m["local_path"].split("/"))
                    if not os.path.exists(p):
                        sys.exit("%s: %s is missing - build first, or run --set-version" % (name, p))
                    m["local_path"] = p
            add_liskov(i["mods"])
            inst.append(i)
    return {"name": "waves-%d" % k, "instances": inst, "server": None, "timeout_secs": timeout}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--max", type=int, default=32, help="games per wave (default 32)")
    ap.add_argument("--ram", type=int, default=72000, help="total MB Deming may give the games of a wave")
    ap.add_argument("--only", help="only specs whose file name contains this")
    ap.add_argument("--deming", default=DEMING_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pin", action="store_true", help="keep the specs' cpu_cores (Deming pins them to the lowest CPUs)")
    ap.add_argument("--set-version", action="store_true")
    ap.add_argument("--liskov", help="Liskov jar added to every game (default: liskov_version, else the newest in ../Liskov)")
    ap.add_argument("--out", help="folder for wave files and results (default tmp/deming-waves)")
    a = ap.parse_args()
    global OUT, LISKOV_JAR
    LISKOV_JAR = os.path.abspath(a.liskov) if a.liskov else None
    if a.out:
        OUT = os.path.abspath(a.out)
    if a.set_version:
        set_version(mod_version())
        return
    specs = {}
    for f in sorted(glob.glob(os.path.join(SPECS, "*.json"))):
        name = os.path.basename(f)[:-5]
        if a.only and a.only not in name:
            continue
        specs[name] = json.load(open(f, encoding="utf-8"))
    if not specs:
        sys.exit("no specs")
    os.makedirs(OUT, exist_ok=True)
    cfg = os.path.join(OUT, "deming-%d.json" % a.max)
    json.dump({"workspace_dir": os.path.join(os.environ.get("LOCALAPPDATA", ""), "deming"),
               "global_limits": {"max_total_ram_mb": a.ram, "max_concurrent_instances": a.max,
                                 "default_instance_ram_mb": 2048, "default_cpu_cores_per_instance": 2,
                                 "process_priority": "below_normal"},
               "retention": {"max_runs": 50, "compress_after_runs": 10, "auto_prune": True},
               "curseforge_api_key": None}, open(cfg, "w"), indent=2)
    summary = []
    for k, names in enumerate(pack(specs, a.max), 1):
        wave = build_wave(k, names, specs, a.pin)
        path = os.path.join(OUT, "wave-%d.json" % k)
        json.dump(wave, open(path, "w"), indent=2)
        print("wave %d: %d games: %s" % (k, len(wave["instances"]), ", ".join(names)), flush=True)
        if a.dry_run:
            continue
        t0 = time.time()
        with open(os.path.join(OUT, "result-%d.json" % k), "w") as out:
            env = dict(os.environ)
            if wave.get("server"):
                # The dedicated server's JVM opens a loopback pipe in a unix-domain socket under %TEMP%; under a long
                # temp path that fails ("Unable to establish loopback connection") and the server dies before it is
                # ready. Deming has no server JVM arguments, so point it at a short folder through the environment.
                uds = os.path.join(REPO, "tmp", "uds")
                os.makedirs(uds, exist_ok=True)
                env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Djdk.net.unixdomain.tmpdir=" + uds).strip()
            rc = subprocess.call([a.deming, "--json", "--config", cfg, "run", "submit", path], stdout=out, cwd=REPO, env=env)
        status, passed, total = "unknown", 0, len(wave["instances"])
        try:
            res = json.load(open(os.path.join(OUT, "result-%d.json" % k), encoding="utf-8"))
            status = res.get("status", "unknown")
            passed = sum(1 for i in res.get("instances", []) if i.get("passed"))
        except (OSError, ValueError) as e:
            status = "no result (%s)" % e
        line = "wave %d: %s, %d/%d games passed, exit %d after %.0f s" % (k, status, passed, total, rc, time.time() - t0)
        print(line, flush=True)
        summary.append(line)
    if summary:
        open(os.path.join(OUT, "summary.txt"), "w").write("\n".join(summary) + "\n")


if __name__ == "__main__":
    main()
