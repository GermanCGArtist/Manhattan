"""Runs every Deming spec in tests/deming on up to N games at once.

    python tests/deming/waves.py                 # pack the specs into waves of <= 32 games and run them
    python tests/deming/waves.py --max 16        # smaller waves
    python tests/deming/waves.py --only redesign # specs whose name contains "redesign"
    python tests/deming/waves.py --dry-run       # only write the wave files
    python tests/deming/waves.py --set-version   # point every spec at the jars of mod_version (gradle.properties)

The specs are packed largest first into waves of at most --max instances. Every instance gets a unique id
(<spec>__<id>), its own test bridge port (25600 + n) and an absolute jar path, so specs that were written to run
alone can share a wave. The waves run one after another with a Deming config that allows --max games and
--ram MB in total (Deming queues games beyond that), at below-normal priority. Wave files, Deming's JSON output and a
summary (summary.txt) go to tmp/deming-waves/.
"""
import argparse, copy, glob, json, os, re, subprocess, sys, time

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPECS = os.path.join(REPO, "tests", "deming")
OUT = os.path.join(REPO, "tmp", "deming-waves")
DEMING_DEFAULT = os.path.join(os.path.dirname(REPO), "Deming", "target-manhattan", "release", "deming.exe")


def mod_version():
    for line in open(os.path.join(REPO, "gradle.properties"), encoding="utf-8"):
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("no mod_version in gradle.properties")


def set_version(ver):
    """Rewrites only the jar names, so the files keep their formatting."""
    for f in sorted(glob.glob(os.path.join(SPECS, "*.json"))):
        t = open(f, newline="", encoding="utf-8").read()
        n = re.sub(r"build/libs/manhattan-testbridge-[\w.\-]+?\.jar", "build/libs/manhattan-testbridge-%s.jar" % ver, t)
        n = re.sub(r"build/libs/manhattan-(?!testbridge)[\w.\-]+?\.jar", "build/libs/manhattan-%s.jar" % ver, n)
        if n != t:
            open(f, "w", newline="", encoding="utf-8").write(n)
            print("updated", os.path.basename(f))


def pack(specs, limit):
    waves = []  # [ [names], count ]
    for name, d in sorted(specs.items(), key=lambda kv: -len(kv[1]["instances"])):
        n = len(d["instances"])
        if n > limit:
            sys.exit("%s has %d instances, more than --max %d" % (name, n, limit))
        for w in waves:
            if w[1] + n <= limit:
                w[0].append(name)
                w[1] += n
                break
        else:
            waves.append([[name], n])
    return [w[0] for w in waves]


def build_wave(k, names, specs):
    inst, timeout = [], 0
    for name in names:
        d = specs[name]
        timeout = max(timeout, d.get("timeout_secs") or 0)
        for i in d["instances"]:
            i = copy.deepcopy(i)
            i["id"] = "%s__%s" % (name.replace("bench-", ""), i["id"])
            i["jvm_args"] = [a for a in i["jvm_args"] if not a.startswith("-Dmanhattan.testbridge.port=")]
            i["jvm_args"].append("-Dmanhattan.testbridge.port=%d" % (25600 + len(inst)))
            i["account"] = {"offline": {"username": "W%dI%02d" % (k, len(inst))}}
            for m in i["mods"]:
                if "local_path" in m:
                    p = os.path.join(REPO, *m["local_path"].split("/"))
                    if not os.path.exists(p):
                        sys.exit("%s: %s is missing - build first, or run --set-version" % (name, p))
                    m["local_path"] = p
            inst.append(i)
    return {"name": "waves-%d" % k, "instances": inst, "server": None, "timeout_secs": timeout}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--max", type=int, default=32, help="games per wave (default 32)")
    ap.add_argument("--ram", type=int, default=72000, help="total MB Deming may give the games of a wave")
    ap.add_argument("--only", help="only specs whose file name contains this")
    ap.add_argument("--deming", default=DEMING_DEFAULT)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--set-version", action="store_true")
    a = ap.parse_args()
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
        wave = build_wave(k, names, specs)
        path = os.path.join(OUT, "wave-%d.json" % k)
        json.dump(wave, open(path, "w"), indent=2)
        print("wave %d: %d games: %s" % (k, len(wave["instances"]), ", ".join(names)), flush=True)
        if a.dry_run:
            continue
        t0 = time.time()
        with open(os.path.join(OUT, "result-%d.json" % k), "w") as out:
            rc = subprocess.call([a.deming, "--json", "--config", cfg, "run", "submit", path], stdout=out, cwd=REPO)
        line = "wave %d: exit %d after %.0f s" % (k, rc, time.time() - t0)
        print(line, flush=True)
        summary.append(line)
    if summary:
        open(os.path.join(OUT, "summary.txt"), "w").write("\n".join(summary) + "\n")


if __name__ == "__main__":
    main()
