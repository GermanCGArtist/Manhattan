"""Runs generated test-suite cases through Deming and writes a report (docs/TEST_SUITE.md).

    python tests/suite/generate.py --smoke && python tests/suite/run.py -j 25        # smoke gate
    python tests/suite/generate.py && python tests/suite/run.py -j 32                # full run
    python tests/suite/run.py --report tmp/suite/runs/<run>                         # report again

Every case is its own game with its own game dir (lean render settings, see measure.py) and
-Dmanhattan.benchmark=suite -Dmanhattan.suite.case=<case.json>. The test bridge runs the case and logs one
SUITE_RESULT line, which is the Deming rule. Cases go to Deming in jobs of --chunk games; Deming starts the next game
as soon as one ends (each game's timeout counts from its own start). Failed cases run once more; a fail followed by
a pass is reported as flaky. Passing games' folders are deleted except their log; failing ones are kept (world
snapshot, log, telemetry) under <run>/games/.
"""
import argparse, glob, json, os, re, shutil, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "tests", "deming"))
sys.path.insert(0, os.path.join(REPO, "tests", "ingame"))
import waves  # noqa: E402
import litematic  # noqa: E402
from measure import LEAN_OPTIONS  # noqa: E402
import report  # noqa: E402

RESULT_RX = re.compile(r"SUITE_RESULT id=(\S+) pass=(\w+) script_pass=(\w+) checks=(\w+) elapsed_ms=(-?\d+) "
                       r"blocks_moved=(-?\d+) stuck_count=(-?\d+) interrupts_handled=(-?\d+) deaths=(-?\d+) reason=(.*)")


def prepare_game(case, case_path, gd):
    if os.path.isdir(gd):
        shutil.rmtree(gd, ignore_errors=True)
    os.makedirs(gd, exist_ok=True)
    with open(os.path.join(gd, "options.txt"), "w") as f:
        f.write(LEAN_OPTIONS)
    items = []
    for s in case.get("schematics") or []:
        path = os.path.join(gd, "schematics", s["name"] + ".litematic")
        litematic.write(path, s["name"], tuple(s["dims"]), {(x, y, z): st for x, y, z, st in s["blocks"]})
        items.append((s["name"], path, tuple(s["origin"]), "NONE", "NONE", True))
    if items:
        cfg = os.path.join(gd, "config", "litematica")
        os.makedirs(cfg, exist_ok=True)
        with open(os.path.join(cfg, "litematica_%s_dim_minecraft_overworld.json" % "manhattan-benchmark"), "w") as f:
            f.write(litematic.placements_json(items))


def instance(case, case_path, gd, k, base, ver, ram):
    i = json.loads(json.dumps(base))
    i["id"] = os.path.basename(case_path)[:-5]
    i["account"] = {"offline": {"username": "S%05d" % k}}
    i["ram_mb"] = ram
    i["cpu_cores"] = 0
    i["mods"] = [m for m in i["mods"] if "local_path" not in m] + [
        {"local_path": os.path.join(REPO, "build", "libs", "manhattan-%s.jar" % ver)},
        {"local_path": os.path.join(REPO, "build", "libs", "manhattan-testbridge-%s.jar" % ver)}]
    waves.add_liskov(i["mods"])
    i["jvm_args"] = ["-Dmanhattan.tests=true", "-Dmanhattan.engine=midtown", "-Dmanhattan.benchmark=suite",
                     "-Dmanhattan.suite.case=%s" % case_path, "-Dmanhattan.testbridge.port=%d" % (26000 + k % 2000)]
    if case.get("world_seed") is not None:
        i["jvm_args"].append("-Dmanhattan.suite.seed=%d" % case["world_seed"])
    i["game_dir"] = gd
    i["game_args"] = ["--width", "320", "--height", "240"]
    rx = "SUITE_RESULT id=%s " % re.escape(case["id"])
    i["test_rule"] = {"log_pattern": {"success_regex": rx + "pass=true", "failure_regex": rx + "pass=false|FATAL"}}
    return i


def parse_log(gd):
    log = os.path.join(gd, "logs", "latest.log")
    if not os.path.isfile(log):
        return None, "no game log (the game never started?)"
    text = open(log, encoding="utf-8", errors="replace").read()
    m = None
    for m in RESULT_RX.finditer(text):
        pass
    if not m:
        tail = [l for l in text.splitlines() if "Exception" in l or "FATAL" in l or "ERROR" in l][-1:]
        return None, "no SUITE_RESULT" + ((": " + tail[0][-200:]) if tail else "")
    keys = ["id", "pass", "script_pass", "checks", "elapsed_ms", "blocks_moved", "stuck_count",
            "interrupts_handled", "deaths", "reason"]
    r = dict(zip(keys, m.groups()))
    for k in ("pass", "script_pass"):
        r[k] = r[k] == "true"
    for k in ("elapsed_ms", "blocks_moved", "stuck_count", "interrupts_handled", "deaths"):
        r[k] = int(r[k])
    r["reason"] = r["reason"].strip()
    # a few signature lines for grouping failures
    r["problems"] = sorted(set(re.sub(r"\d+", "N", l.split("PROBLEM:", 1)[1].strip())[:120]
                               for l in text.splitlines() if "PROBLEM:" in l))[:5]
    return r, None


def run_chunk(cases, run_dir, attempt, a, base, ver, cfg, tag):
    inst, games = [], []
    for k, (case, path) in enumerate(cases):
        gd = os.path.join(run_dir, "games", "%s-a%d" % (os.path.basename(path)[:-5], attempt))
        prepare_game(case, path, gd)
        inst.append(instance(case, path, gd, k, base, ver, a.ram_mb))
        games.append(gd)
    spec = {"name": "suite-%s" % tag, "instances": inst, "server": None,
            # Deming counts from launch; booting 32 games together takes over 2 min, so leave room for that
            # or the case's own limit (counted from its start) never fires and Deming kills the game silently
            "timeout_secs": max(c["limit_s"] for c, _ in cases) + 480}
    spec_path = os.path.join(run_dir, "jobs", "%s.json" % tag)
    os.makedirs(os.path.dirname(spec_path), exist_ok=True)
    json.dump(spec, open(spec_path, "w"), indent=1)
    env = dict(os.environ)
    env.setdefault("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "0")
    env.setdefault("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "0")
    with open(os.path.join(run_dir, "jobs", "%s.result.json" % tag), "w") as out:
        subprocess.call([waves.DEMING_DEFAULT, "--json", "--config", cfg, "run", "submit", spec_path],
                        stdout=out, cwd=REPO, env=env)
    status = {}
    try:
        res = json.load(open(os.path.join(run_dir, "jobs", "%s.result.json" % tag), encoding="utf-8"))
        status = {i["instance_id"]: i for i in res.get("instances", [])}
    except (OSError, ValueError):
        pass
    results = []
    for (case, path), gd in zip(cases, games):
        r, err = parse_log(gd)
        st = status.get(os.path.basename(path)[:-5], {})
        if r is None:
            r = {"id": case["id"], "pass": False, "script_pass": False, "checks": "none", "elapsed_ms": -1,
                 "blocks_moved": -1, "stuck_count": -1, "interrupts_handled": -1, "deaths": -1,
                 "reason": "%s (deming: %s)" % (err, st.get("reason") or st.get("state") or "?"), "problems": []}
        r.update({"family": case["family"], "area": case["area"], "params": case["params"], "seed": case["seed"],
                  "attempt": attempt, "game_dir": gd, "case_file": path, "est_s": case["est_s"], "limit_s": case["limit_s"]})
        results.append(r)
        if r["pass"]:
            keep = os.path.join(run_dir, "logs", os.path.basename(gd) + ".log")
            os.makedirs(os.path.dirname(keep), exist_ok=True)
            try:
                shutil.copy(os.path.join(gd, "logs", "latest.log"), keep)
            except OSError:
                pass
            shutil.rmtree(gd, ignore_errors=True)
        else:
            shutil.rmtree(os.path.join(gd, "mods"), ignore_errors=True)
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cases", default=os.path.join(REPO, "tmp", "suite", "cases"))
    ap.add_argument("-j", type=int, default=32, help="games at once")
    ap.add_argument("--ram-mb", type=int, default=2048, help="heap per game")
    ap.add_argument("--chunk", type=int, default=256, help="games per Deming job")
    ap.add_argument("--only", help="only cases whose id contains this")
    ap.add_argument("--no-retry", action="store_true")
    ap.add_argument("--run", help="run folder name (default: a time stamp)")
    ap.add_argument("--report", help="only write the report for this run folder")
    a = ap.parse_args()
    if a.report:
        report.write(a.report)
        return

    run_dir = os.path.join(REPO, "tmp", "suite", "runs", a.run or time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(run_dir, exist_ok=True)
    paths = sorted(glob.glob(os.path.join(a.cases, "*.json")))
    cases = [(json.load(open(p, encoding="utf-8")), p) for p in paths]
    cases = [(c, p) for c, p in cases if not a.only or a.only in c["id"]]
    # snapshot of the cases this run used
    shutil.copytree(a.cases, os.path.join(run_dir, "cases"), dirs_exist_ok=True)
    cases = [(c, os.path.join(run_dir, "cases", os.path.basename(p))) for c, p in cases]
    # long cases first so the end of a job is not one slow straggler; normal-terrain worlds spread out
    cases.sort(key=lambda cp: -cp[0]["limit_s"])
    ver = waves.mod_version()
    base = json.load(open(os.path.join(REPO, "tests", "deming", "bench-chunk-clear.json"), encoding="utf-8"))["instances"][0]
    cfg = os.path.join(run_dir, "deming.json")
    json.dump({"workspace_dir": os.path.join(os.environ.get("LOCALAPPDATA", ""), "deming"),
               "global_limits": {"max_total_ram_mb": a.j * a.ram_mb, "max_concurrent_instances": a.j,
                                 "default_instance_ram_mb": a.ram_mb, "default_cpu_cores_per_instance": 1,
                                 "process_priority": "below_normal"},
               "retention": {"max_runs": 400, "compress_after_runs": 1000, "auto_prune": True},
               "curseforge_api_key": None}, open(cfg, "w"), indent=2)
    meta = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "cases": len(cases), "j": a.j, "ram_mb": a.ram_mb,
            "mod_version": ver, "git": subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                                                      capture_output=True, text=True).stdout.strip()}
    json.dump(meta, open(os.path.join(run_dir, "meta.json"), "w"), indent=1)
    out = open(os.path.join(run_dir, "results.jsonl"), "a", encoding="utf-8")
    print("%d cases, %d at once -> %s" % (len(cases), a.j, run_dir), flush=True)

    failed = []
    by_id = {}
    t0 = time.time()
    done = 0
    for n in range(0, len(cases), a.chunk):
        chunk = cases[n:n + a.chunk]
        res = run_chunk(chunk, run_dir, 1, a, base, ver, cfg, "c%03d" % (n // a.chunk))
        for r, cp in zip(res, chunk):
            out.write(json.dumps(r) + "\n")
            by_id.setdefault(r["id"], []).append(r)
            if not r["pass"]:
                failed.append(cp)
        out.flush()
        done += len(chunk)
        print("%d/%d done, %d failed so far, %.0f min" % (done, len(cases), len(failed), (time.time() - t0) / 60), flush=True)
        report.write(run_dir)
    if failed and not a.no_retry:
        print("retrying %d failed cases once" % len(failed), flush=True)
        for n in range(0, len(failed), a.chunk):
            res = run_chunk(failed[n:n + a.chunk], run_dir, 2, a, base, ver, cfg, "r%03d" % (n // a.chunk))
            for r in res:
                out.write(json.dumps(r) + "\n")
                by_id.setdefault(r["id"], []).append(r)
            out.flush()
    # games that never started (network, Deming copying mods) have no result yet: launch them up to twice more
    for attempt in (3, 4):
        if a.no_retry:
            break
        todo = [cp for cp in cases if all(report.is_infra(r) for r in by_id.get(cp[0]["id"], []))]
        if not todo:
            break
        print("relaunching %d cases whose game never started" % len(todo), flush=True)
        for n in range(0, len(todo), a.chunk):
            res = run_chunk(todo[n:n + a.chunk], run_dir, attempt, a, base, ver, cfg, "s%d%02d" % (attempt, n // a.chunk))
            for r in res:
                out.write(json.dumps(r) + "\n")
                by_id.setdefault(r["id"], []).append(r)
            out.flush()
    out.close()
    meta["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    meta["wall_min"] = round((time.time() - t0) / 60)
    json.dump(meta, open(os.path.join(run_dir, "meta.json"), "w"), indent=1)
    path = report.write(run_dir)
    print("report: %s" % path, flush=True)


if __name__ == "__main__":
    main()
