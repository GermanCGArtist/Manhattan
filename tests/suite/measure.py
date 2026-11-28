"""Throughput measurement for the test suite (docs/TEST_SUITE.md, "Throughput: measure first").

Runs N copies of one benchmark scenario in one Deming wave and samples every game's RAM and CPU while they run.

    python tests/suite/measure.py -n 1 --mode default
    python tests/suite/measure.py -n 32 --mode lean
    python tests/suite/measure.py -n 32 --mode lean --tick-rate 60

--mode lean gives every game its own game dir with an options.txt that keeps rendering cheap (render distance 2,
10 fps, no clouds/particles) and a 320x240 window. Results go to tmp/measure/<label>/report.json and one summary
line is printed. Needs Python 3.9+ (standard library only) and Windows PowerShell for the process samples.
"""
import argparse, glob, json, os, re, statistics, subprocess, sys, threading, time

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "tests", "deming"))
import waves  # noqa: E402  (find_deming, mod_version, liskov handling)

LEAN_OPTIONS = """onboardAccessibility:false
pauseOnLostFocus:false
rawMouseInput:false
renderDistance:2
simulationDistance:5
maxFps:10
enableVsync:false
graphicsPreset:"fast"
graphicsMode:0
renderClouds:"false"
particles:2
entityShadows:false
ao:false
biomeBlendRadius:0
mipmapLevels:0
soundCategory_master:0.0
"""

PS_SAMPLE = r"""
$p = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
  Where-Object { $_.CommandLine -like '*-Dmanhattan.measure=%s*' }
$p | ForEach-Object { [pscustomobject]@{ pid=$_.ProcessId; ws=$_.WorkingSetSize; priv=$_.PrivatePageCount;
  cpu=($_.KernelModeTime + $_.UserModeTime) / 1e7 } } | ConvertTo-Json -Compress
"""


def free_mb():
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],
                         capture_output=True, text=True).stdout.strip()
    return int(out) // 1024 if out.isdigit() else -1


class Sampler(threading.Thread):
    def __init__(self, label, every):
        super().__init__(daemon=True)
        self.label, self.every, self.stop = label, every, threading.Event()
        self.procs = {}        # pid -> {peak_ws, peak_priv, first (t, cpu), last (t, cpu)}
        self.totals = []       # (t, games, sum ws MB, sum cpu cores over the last interval)
        self.min_free = free_mb()

    def run(self):
        prev = {}
        while not self.stop.is_set():
            t = time.time()
            out = subprocess.run(["powershell", "-NoProfile", "-Command", PS_SAMPLE % self.label],
                                 capture_output=True, text=True).stdout.strip()
            rows = []
            if out:
                try:
                    rows = json.loads(out)
                    rows = rows if isinstance(rows, list) else [rows]
                except ValueError:
                    rows = []
            cores = 0.0
            for r in rows:
                s = self.procs.setdefault(r["pid"], {"peak_ws": 0, "peak_priv": 0, "first": (t, r["cpu"])})
                s["peak_ws"] = max(s["peak_ws"], r["ws"] or 0)
                s["peak_priv"] = max(s["peak_priv"], r["priv"] or 0)
                s["last"] = (t, r["cpu"])
                if r["pid"] in prev and t > prev[r["pid"]][0]:
                    cores += (r["cpu"] - prev[r["pid"]][1]) / (t - prev[r["pid"]][0])
                prev[r["pid"]] = (t, r["cpu"])
            self.totals.append((t, len(rows), sum((r["ws"] or 0) for r in rows) / 2**20, cores))
            f = free_mb()
            if f >= 0:
                self.min_free = min(self.min_free, f)
            self.stop.wait(self.every)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-n", type=int, default=1, help="games at once")
    ap.add_argument("--mode", choices=["default", "lean"], default="lean")
    ap.add_argument("--tick-rate", type=float, help="world tick rate (the test bridge's default is 50)")
    # nav_flat_obstacle is no use here: its goto always hits timeout(180) and still logs pass=true
    ap.add_argument("--scenario", default="chunk_clear_2x2_easy")
    ap.add_argument("--base", default="bench-chunk-clear", help="spec in tests/deming the instance is copied from")
    ap.add_argument("--ram-mb", type=int, default=4096, help="heap per game")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--every", type=float, default=5.0, help="seconds between samples")
    ap.add_argument("--label")
    a = ap.parse_args()

    label = a.label or "%s-%d%s" % (a.mode, a.n, "-t%g" % a.tick_rate if a.tick_rate else "")
    out_dir = os.path.join(REPO, "tmp", "measure", label)
    os.makedirs(out_dir, exist_ok=True)
    ver = waves.mod_version()
    base = json.load(open(os.path.join(REPO, "tests", "deming", a.base + ".json"), encoding="utf-8"))["instances"][0]

    inst = []
    for k in range(a.n):
        i = json.loads(json.dumps(base))
        i["id"] = "m%03d" % k
        i["account"] = {"offline": {"username": "Meas%03d" % k}}
        i["ram_mb"] = a.ram_mb
        i["cpu_cores"] = 0
        i["mods"] = [m for m in i["mods"] if "local_path" not in m] + [
            {"local_path": os.path.join(REPO, "build", "libs", "manhattan-%s.jar" % ver)},
            {"local_path": os.path.join(REPO, "build", "libs", "manhattan-testbridge-%s.jar" % ver)}]
        waves.add_liskov(i["mods"])
        i["jvm_args"] = [x for x in i["jvm_args"] if not x.startswith("-Dmanhattan.benchmark=")] + [
            "-Dmanhattan.benchmark=%s" % a.scenario,
            "-Dmanhattan.testbridge.port=%d" % (25600 + k),
            "-Dmanhattan.measure=%s" % label]
        if a.tick_rate:
            i["jvm_args"].append("-Dmanhattan.benchmark.tickRate=%g" % a.tick_rate)
        if a.mode == "lean":
            gd = os.path.join(out_dir, "games", i["id"])
            os.makedirs(gd, exist_ok=True)
            open(os.path.join(gd, "options.txt"), "w").write(LEAN_OPTIONS)
            i["game_dir"] = gd
            i["game_args"] = ["--width", "320", "--height", "240"]
        rx = "BENCHMARK_RESULT engine=midtown scenario=%s " % a.scenario
        i["test_rule"] = {"log_pattern": {"success_regex": rx + "pass=true", "failure_regex": rx + "pass=false|FATAL"}}
        inst.append(i)
    spec = {"name": "measure-" + label, "instances": inst, "server": None, "timeout_secs": a.timeout}
    spec_path = os.path.join(out_dir, "spec.json")
    json.dump(spec, open(spec_path, "w"), indent=2)
    cfg = os.path.join(out_dir, "deming.json")
    json.dump({"workspace_dir": os.path.join(os.environ.get("LOCALAPPDATA", ""), "deming"),
               "global_limits": {"max_total_ram_mb": a.n * a.ram_mb, "max_concurrent_instances": a.n,
                                 "default_instance_ram_mb": 2048, "default_cpu_cores_per_instance": 2,
                                 "process_priority": "below_normal"},
               "retention": {"max_runs": 50, "compress_after_runs": 10, "auto_prune": True},
               "curseforge_api_key": None}, open(cfg, "w"), indent=2)

    env = dict(os.environ)
    env.setdefault("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "0")
    env.setdefault("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "0")
    sampler = Sampler(label, a.every)
    free_before = sampler.min_free
    sampler.start()
    t0 = time.time()
    with open(os.path.join(out_dir, "result.json"), "w") as f:
        rc = subprocess.call([waves.DEMING_DEFAULT, "--json", "--config", cfg, "run", "submit", spec_path],
                             stdout=f, cwd=REPO, env=env)
    wall = time.time() - t0
    sampler.stop.set()
    sampler.join()

    res = json.load(open(os.path.join(out_dir, "result.json"), encoding="utf-8"))
    run_dirs = glob.glob(os.path.join(os.environ.get("LOCALAPPDATA", ""), "deming", "runs", "*%s*" % res["run_id"]))
    elapsed, passed = [], 0
    for r in res["instances"]:
        passed += bool(r.get("passed"))
        lp = r.get("log_path") or ""
        cands = [lp] + [os.path.join(d, lp) for d in run_dirs]
        if a.mode == "lean":
            cands.append(os.path.join(out_dir, "games", r["instance_id"], "logs", "latest.log"))
        for c in cands:
            if c and os.path.isfile(c):
                m = re.search(r"BENCHMARK_RESULT .*?elapsed_ms=(\d+)", open(c, encoding="utf-8", errors="replace").read())
                if m:
                    elapsed.append(int(m.group(1)) / 1000)
                break

    peaks_ws = [p["peak_ws"] / 2**20 for p in sampler.procs.values()]
    peaks_priv = [p["peak_priv"] / 2**20 for p in sampler.procs.values()]
    avg_cores = [(p["last"][1] - p["first"][1]) / (p["last"][0] - p["first"][0])
                 for p in sampler.procs.values() if p.get("last") and p["last"][0] > p["first"][0]]
    busy = [t for t in sampler.totals if t[1] >= max(1, a.n * 0.9)]
    rep = {
        "label": label, "games": a.n, "mode": a.mode, "tick_rate": a.tick_rate or 50, "scenario": a.scenario,
        "deming_exit": rc, "status": res.get("status"), "passed": passed, "wall_s": round(wall),
        "peak_ws_mb_per_game": {"median": round(statistics.median(peaks_ws)) if peaks_ws else None,
                                "max": round(max(peaks_ws)) if peaks_ws else None},
        "peak_private_mb_per_game": {"median": round(statistics.median(peaks_priv)) if peaks_priv else None},
        "avg_cores_per_game": round(statistics.median(avg_cores), 2) if avg_cores else None,
        "peak_total_ws_mb": round(max((t[2] for t in sampler.totals), default=0)),
        "peak_total_cores": round(max((t[3] for t in sampler.totals), default=0), 1),
        "busy_total_cores_median": round(statistics.median([t[3] for t in busy]), 1) if busy else None,
        "free_mb_before": free_before, "free_mb_min": sampler.min_free,
        "elapsed_s": {"n": len(elapsed),
                      "median": round(statistics.median(elapsed), 1) if elapsed else None,
                      "min": min(elapsed, default=None), "max": max(elapsed, default=None),
                      "stdev": round(statistics.pstdev(elapsed), 1) if len(elapsed) > 1 else None},
        "run_id": res.get("run_id"),
    }
    json.dump(rep, open(os.path.join(out_dir, "report.json"), "w"), indent=2)
    print(json.dumps(rep))


if __name__ == "__main__":
    main()
