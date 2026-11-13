"""Deming as the game launcher and supervisor for the in-game tests (run_tests.py --deming, tests/ingame/deming.bat).

Who does what, per worker k (a worker is one game, as in the old runner: game dir tmp/ingame/wK, bridge port 25700+K):

  * this module prepares the game dir (mods, options.txt, schematics: the same mc.install() / prepare_schem() as before)
    and writes a one-instance Deming job for it (game dir, 3 GB, JVM -Dmanhattan.testbridge.port=<port>);
  * Deming (`deming run submit`) resolves Java, Fabric Loader and the game, starts the JVM inside a Windows Job
    Object (killed with the job, nothing orphaned), streams and archives stdout/latest.log/screenshots under a run id;
  * the Python worker (run_tests.py: enter_world, the test_* functions, the bridge protocol) drives the game over the
    bridge exactly as before, writes `deming-report.json` into the game dir when it is done and sends `quit`;
  * Deming then evaluates the job's rule: exit code 0 AND every entry of deming-report.json passed. So the run's
    status in `deming run status <id>` is the test verdict of that worker as well.

A game that hangs is closed through the bridge; if it does not exit, the `deming run submit` process is ended, which
closes the job object and with it the JVM. Only processes this harness started are ever touched.

Standard library only. Environment: DEMING_EXE (path of deming.exe), DEMING_CONFIG (config file, default: our own
tmp/ingame/deming-config.json in Deming's normal workspace), MANHATTAN_SHOT_ALL=1 (screenshot after every test, not
only after failures).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mc  # noqa: E402
from mc import log  # noqa: E402

MC_VERSION = "26.1.1"
LOADER_VERSION = "0.20.1"
OUT = os.path.join(mc.REPO, "tmp", "ingame", "deming")   # results.json, per-test log slices, screenshots
REPORT = "deming-report.json"                            # in the game dir; matched by Deming's game_test_report rule


def find_deming():
    """deming.exe that knows `run submit --run-id` and InstanceSpec.game_dir (branch manhattan-port or newer)."""
    cands = [os.environ.get("DEMING_EXE")]
    base = os.path.join(os.path.dirname(mc.REPO), "Deming")
    cands += [os.path.join(base, "target-manhattan", "release", "deming.exe"),
              os.path.join(base, "target", "release", "deming.exe"), shutil.which("deming")]
    for c in cands:
        if c and os.path.isfile(c):
            h = subprocess.run([c, "run", "submit", "--help"], capture_output=True, text=True).stdout
            if "--run-id" in h:
                return c
    raise RuntimeError("no deming.exe with `run submit --run-id` found (set DEMING_EXE; build the manhattan-port branch of "
                       "F:\\workflow\\Deming with `cargo build --release`)")


def deming_config():
    """Our own config (same workspace, so the Minecraft/Java caches are shared): high limits, no pruning of runs."""
    path = os.environ.get("DEMING_CONFIG")
    if path:
        return path
    path = os.path.join(mc.REPO, "tmp", "ingame", "deming-config.json")
    ws = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser(r"~\AppData\Local")), "deming")
    cfg = {"workspace_dir": ws,
           "global_limits": {"max_total_ram_mb": 131072, "max_concurrent_instances": 16, "default_instance_ram_mb": 3072,
                             "default_cpu_cores_per_instance": 0, "process_priority": "normal"},
           "retention": {"max_runs": 1000, "compress_after_runs": 1000, "auto_prune": False}}
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    return path


def xmx_mb():
    v = os.environ.get("MANHATTAN_XMX", "3G").upper()
    return int(v[:-1]) * 1024 if v.endswith("G") else int(v[:-1]) if v.endswith("M") else int(v)


class DemingBackend:
    name = "deming"

    def __init__(self, prepare=None):
        self.prepare = prepare   # run_tests.prepare_schem: the schematic and Litematica placement for the game dir
        self.exe = find_deming()
        self.config = deming_config()
        self.port = mc.PORT
        self.gamedir = os.path.abspath(mc.PROFILE)
        self.worker = os.path.basename(self.gamedir)   # w1, w2, ...
        self.runs = []        # one dict per Deming run this worker started
        self.cur = None       # the run in progress
        self.tests = []       # one dict per test this worker ran
        self.pid = None

    # ---------------------------------------------------------------- start / stop

    def _job(self):
        return {
            "name": "manhattan-ingame-" + self.worker,
            "timeout_secs": 6 * 3600,   # a safety net only; the harness ends the game itself
            "instances": [{
                "id": self.worker,
                "mc_version": MC_VERSION,
                "loader": "fabric",
                "loader_version": LOADER_VERSION,
                "mods": [],   # mc.install() has put the test stack into <game dir>/mods
                "account": {"offline": {"username": mc.USERNAME}},
                "ram_mb": xmx_mb(),
                "cpu_cores": 0,   # 0 = no affinity pinning (Deming would pin every game to the same lowest cores)
                "game_dir": self.gamedir,
                "jvm_args": ["--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
                             "-Dminecraft.launcher.brand=manhattan-tests", "-Dminecraft.launcher.version=1",
                             "-Dmanhattan.testbridge.port=%d" % self.port, "-Dmanhattan.tests=true"],
                "game_args": ["--width", "854", "--height", "480"],
                "test_rule": {"composite": ["exit_code_zero", {"game_test_report": {"report_path_glob": REPORT}}]},
            }],
        }

    def start(self, b, attach):
        mc.stop_testing_instance()   # only a game whose --gameDir is exactly this worker's dir
        mc.install()
        if self.prepare:
            self.prepare()
        for stale in (os.path.join(self.gamedir, REPORT), os.path.join(self.gamedir, "screenshots")):
            if os.path.isdir(stale):
                shutil.rmtree(stale, ignore_errors=True)
            elif os.path.isfile(stale):
                os.remove(stale)
        run_id = str(uuid.uuid4())
        job = os.path.join(self.gamedir, "deming-job.json")
        with open(job, "w", encoding="utf-8") as f:
            json.dump(self._job(), f, indent=2)
        out = open(os.path.join(self.gamedir, "deming-submit.json"), "w", encoding="utf-8")
        err = open(os.path.join(self.gamedir, "deming-submit.err"), "w", encoding="utf-8")
        proc = subprocess.Popen([self.exe, "--config", self.config, "--json", "run", "submit", job, "--run-id", run_id],
                                stdout=out, stderr=err, stdin=subprocess.DEVNULL)
        self.cur = {"run_id": run_id, "proc": proc, "job": job, "started": time.time(), "tests": [],
                    "stdout": out.name, "stderr": err.name}
        self.runs.append(self.cur)
        self.pid = None
        log("[%s] Deming run %s (pid %d): game dir %s, port %d" % (self.worker, run_id, proc.pid, self.gamedir, self.port))
        try:
            b.wait_ready(proc=proc)
        except Exception as e:   # noqa: BLE001
            tail = ""
            try:
                tail = open(err.name, encoding="utf-8", errors="replace").read()[-1500:]
            except OSError:
                pass
            raise RuntimeError("%s\n%s" % (e, tail)) from e
        pids = mc.testing_pids()
        self.pid = pids[0] if pids else None
        return proc

    def _write_report(self):
        results = [{"name": t["name"], "passed": t["pass"], "message": t["message"][:300]} for t in self.cur["tests"]]
        if not results:   # this game never got a test (the queue was empty): nothing failed, but the rule wants an entry
            results = [{"name": "(no test claimed)", "passed": True, "message": "the queue was empty when this game was ready"}]
        with open(os.path.join(self.gamedir, REPORT), "w", encoding="utf-8") as f:
            json.dump({"results": results}, f, indent=1)

    def stop(self, b, keep, attach):
        run = self.cur
        if not run:
            return
        proc = run["proc"]
        self._write_report()
        self.cur = None
        if proc.poll() is None:
            try:
                b("quit")
            except Exception as e:   # noqa: BLE001
                log("[%s] bridge quit failed: %s" % (self.worker, e))
        try:
            proc.wait(90)
        except subprocess.TimeoutExpired:
            log("[%s] the game did not exit; ending Deming run %s (closes its job object)" % (self.worker, run["run_id"]))
            proc.terminate()
            try:
                proc.wait(30)
            except subprocess.TimeoutExpired:
                proc.kill()
            mc.stop_testing_instance(None, timeout=5)   # belt and braces, still only this worker's game dir
        self._collect(run)

    def _collect(self, run):
        """The manifest Deming printed: status, instance result, where the archive is."""
        run["seconds"] = time.time() - run["started"]
        try:
            raw = open(run["stdout"], encoding="utf-8", errors="replace").read()
            m = json.loads(raw[raw.index("{"):])
            inst = m["instances"][0]
            run.update(status=m.get("status"), state=inst.get("state"), passed=inst.get("passed"),
                       exit_code=inst.get("exit_code"), reason=inst.get("reason"))
        except Exception as e:   # noqa: BLE001
            run.update(status="unknown", reason="no manifest from deming (%s); see %s" % (e, run["stderr"]))
        base = os.path.join(json.load(open(self.config, encoding="utf-8"))["workspace_dir"], "runs")
        run["run_dir"] = next((os.path.join(base, d) for d in sorted(os.listdir(base)) if d.endswith(run["run_id"])), None) \
            if os.path.isdir(base) else None
        run["proc"] = None

    # ---------------------------------------------------------------- per test

    def _latest_log(self):
        return os.path.join(self.gamedir, "logs", "latest.log")

    def test_begin(self, name):
        try:
            self._mark = os.path.getsize(self._latest_log())
        except OSError:
            self._mark = 0
        self._t0 = time.time()

    def test_done(self, name, ok, info, seconds):
        rec = {"name": name, "pass": bool(ok), "seconds": round(seconds, 1), "message": info or "",
               "worker": self.worker, "run_id": self.cur["run_id"] if self.cur else None, "log_path": None, "screenshots": []}
        os.makedirs(os.path.join(OUT, "logs"), exist_ok=True)
        try:   # the part of the game's own log that this test wrote
            with open(self._latest_log(), "rb") as f:
                f.seek(self._mark)
                data = f.read()
            p = os.path.join(OUT, "logs", name + ".log")
            with open(p, "wb") as f:
                f.write(data)
            rec["log_path"] = p
        except OSError:
            pass
        if (not ok or os.environ.get("MANHATTAN_SHOT_ALL")) and self.pid:
            shot = os.path.join(self.gamedir, "screenshots", name + ".png")   # Deming archives that folder with the run
            r = subprocess.run([self.exe, "--config", self.config, "run", "capture", str(self.pid), shot],
                               capture_output=True, text=True)
            if r.returncode == 0 and os.path.isfile(shot):
                os.makedirs(os.path.join(OUT, "screenshots"), exist_ok=True)
                dst = os.path.join(OUT, "screenshots", name + ".png")
                shutil.copyfile(shot, dst)
                rec["screenshots"].append(dst)
        if self.cur:
            self.cur["tests"].append(rec)
        self.tests.append(rec)

    # ---------------------------------------------------------------- results

    def dump(self, path):
        runs = [{k: v for k, v in r.items() if k not in ("proc", "tests")} for r in self.runs]
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"worker": self.worker, "runs": runs, "tests": self.tests}, f)

    def write_results(self, res, qdir, wall, workers, order):
        """results.json: one entry per test plus the Deming runs. `res` is the runner's own result list."""
        by_test, runs = {}, {}
        for fn in sorted(os.listdir(qdir)):
            if fn.startswith("backend-") and fn.endswith(".json"):
                d = json.load(open(os.path.join(qdir, fn), encoding="utf-8"))
                for r in d["runs"]:
                    runs[r["run_id"]] = dict(r, worker=d["worker"])
                for t in d["tests"]:
                    by_test[t["name"]] = t
        tests = []
        for r in sorted(res, key=lambda r: order.index(r[0]) if r[0] in order else 999):
            name, ok, info, dt = r[0], r[1], r[2], r[3]
            t = by_test.get(name) or {"name": name, "pass": bool(ok), "seconds": round(dt, 1), "message": info,
                                      "worker": "w%s" % r[4] if len(r) > 4 else None, "run_id": None, "log_path": None,
                                      "screenshots": []}
            run = runs.get(t.get("run_id")) or {}
            rd = run.get("run_dir")
            tests.append({"name": name, "pass": bool(ok), "seconds": t["seconds"], "message": info, "worker": t["worker"],
                          "deming_run_id": t["run_id"], "deming_run_dir": rd,
                          "log_path": t["log_path"],
                          "game_log": os.path.join(rd, "instances", t["worker"], "latest.log") if rd and t.get("worker") else None,
                          "screenshots": t["screenshots"]})
        failed = sum(1 for t in tests if not t["pass"])
        out = {"finished": time.strftime("%Y-%m-%dT%H:%M:%S"), "seconds": round(wall, 1), "workers": workers,
               "passed": len(tests) - failed, "failed": failed, "all_passed": failed == 0 and bool(tests),
               "launcher": "deming", "deming_exe": self.exe,
               "runs": [{"run_id": k, "worker": v.get("worker"), "status": v.get("status"), "state": v.get("state"),
                         "reason": v.get("reason"), "exit_code": v.get("exit_code"), "run_dir": v.get("run_dir"),
                         "seconds": round(v.get("seconds", 0), 1)} for k, v in runs.items()],
               "tests": tests}
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, "results.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=2)
        print("results: " + path)
        return path
