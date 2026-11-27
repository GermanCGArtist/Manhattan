"""Writes report.md (and summary.json) for a test-suite run folder (tests/suite/run.py).

Per family: pass / fail / flaky counts, median elapsed and stuck counts; failures grouped by family and reason
signature, with which parameter values are over-represented among the failures; a repro command per case.
With --baseline <summary.json> it also lists families whose pass rate or median time moved.
"""
import collections, json, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))


def load(run_dir):
    final = {}
    attempts = collections.defaultdict(list)
    p = os.path.join(run_dir, "results.jsonl")
    if not os.path.exists(p):
        return {}, {}
    for line in open(p, encoding="utf-8"):
        try:
            r = json.loads(line)
        except ValueError:
            continue
        attempts[r["id"]].append(r)
    for cid, rs in attempts.items():
        rs.sort(key=lambda r: r["attempt"])
        last = rs[-1]
        if rs[0]["pass"]:
            last["verdict"] = "pass"
        elif last["pass"]:
            last["verdict"] = "flaky"
        elif all("no game log" in r["reason"] or r["reason"].startswith("setup failed") for r in rs):
            last["verdict"] = "infra"     # the game never started (Deming) or the case's setup broke: not a Manhattan result
        else:
            last["verdict"] = "fail"
        last["first_reason"] = rs[0]["reason"]
        final[cid] = last
    return final, attempts


def signature(reason):
    s = re.sub(r"-?\d+(\.\d+)?", "N", reason or "")
    s = re.sub(r"\(first [^)]*\)", "", s)
    s = re.sub(r"\(at [^)]*\)", "", s)
    return s.strip()[:110]


def med(xs):
    xs = [x for x in xs if x is not None and x >= 0]
    return round(statistics.median(xs), 1) if xs else None


def write(run_dir, baseline=None):
    final, attempts = load(run_dir)
    meta = {}
    try:
        meta = json.load(open(os.path.join(run_dir, "meta.json")))
    except (OSError, ValueError):
        pass
    fams = collections.defaultdict(list)
    for r in final.values():
        fams[r["family"]].append(r)
    summary = {"meta": meta, "families": {}, "cases": {}}
    for f, rs in sorted(fams.items()):
        ok = [r for r in rs if r["verdict"] == "pass"]
        summary["families"][f] = {
            "area": rs[0]["area"], "cases": len(rs), "pass": len(ok),
            "flaky": sum(r["verdict"] == "flaky" for r in rs), "fail": sum(r["verdict"] == "fail" for r in rs),
            "median_elapsed_s": med([r["elapsed_ms"] / 1000 for r in ok if r["elapsed_ms"] >= 0]),
            "median_stuck": med([r["stuck_count"] for r in rs]),
            "deaths": sum(max(0, r["deaths"]) for r in rs)}
    for cid, r in final.items():
        summary["cases"][cid] = {"verdict": r["verdict"], "elapsed_ms": r["elapsed_ms"], "stuck": r["stuck_count"],
                                 "deaths": r["deaths"], "reason": r["reason"]}
    json.dump(summary, open(os.path.join(run_dir, "summary.json"), "w"), indent=1)

    n = len(final)
    tot = collections.Counter(r["verdict"] for r in final.values())
    L = ["# Manhattan test suite: run %s" % os.path.basename(run_dir.rstrip("/\\")), ""]
    L.append("Manhattan %s (git %s), %s games at once, started %s, finished %s (%s min)." % (
        meta.get("mod_version"), meta.get("git"), meta.get("j"), meta.get("started"), meta.get("finished", "-"),
        meta.get("wall_min", "-")))
    L.append("")
    L.append("**%d cases: %d pass, %d fail, %d flaky, %d never started or broke in setup** (%.0f%% of the started ones pass)." % (
        n, tot["pass"], tot["fail"], tot["flaky"], tot["infra"], 100.0 * tot["pass"] / max(1, n - tot["infra"])))
    L.append("")
    L.append("| family | area | cases | pass | fail | flaky | pass % | median s (passing) | median stuck | deaths |")
    L.append("|---|---|---|---|---|---|---|---|---|---|")
    for f, s in sorted(summary["families"].items(), key=lambda kv: kv[1]["pass"] / max(1, kv[1]["cases"])):
        L.append("| %s | %s | %d | %d | %d | %d | %.0f | %s | %s | %d |" % (
            f, s["area"], s["cases"], s["pass"], s["fail"], s["flaky"], 100.0 * s["pass"] / max(1, s["cases"]),
            s["median_elapsed_s"], s["median_stuck"], s["deaths"]))
    if baseline and os.path.exists(baseline):
        base = json.load(open(baseline))
        L += ["", "## Compared with the baseline", "", "| family | pass % then | pass % now | median s then | median s now |",
              "|---|---|---|---|---|"]
        for f, s in sorted(summary["families"].items()):
            b = base.get("families", {}).get(f)
            if not b:
                continue
            p0, p1 = 100.0 * b["pass"] / max(1, b["cases"]), 100.0 * s["pass"] / max(1, s["cases"])
            m0, m1 = b.get("median_elapsed_s"), s.get("median_elapsed_s")
            flag = abs(p1 - p0) >= 10 or (m0 and m1 and abs(m1 - m0) / m0 >= 0.15)
            L.append("| %s%s | %.0f | %.0f | %s | %s |" % ("**" + f + "**" if flag else f, "", p0, p1, m0, m1))

    L += ["", "## Failures grouped", ""]
    groups = collections.defaultdict(list)
    for r in final.values():
        if r["verdict"] != "pass":
            groups[(r["family"], signature(r["first_reason"]))].append(r)
    for (f, sig), rs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        L.append("### %s: %s (%d)" % (f, sig or "?", len(rs)))
        L.append("")
        # parameter values over-represented among these failures compared with the family as a whole
        allf = fams[f]
        hints = []
        for key in rs[0]["params"]:
            cf = collections.Counter(str(r["params"][key]) for r in rs)
            ca = collections.Counter(str(r["params"][key]) for r in allf)
            for v, c in cf.items():
                share_fail, share_all = c / len(rs), ca[v] / len(allf)
                if len(rs) >= 3 and share_fail >= 0.6 and share_fail >= share_all + 0.25:
                    hints.append("%s=%s (%d of %d)" % (key, v, c, len(rs)))
        if hints:
            L.append("Mostly with: " + ", ".join(hints))
            L.append("")
        probs = collections.Counter(p for r in rs for p in r.get("problems", []))
        if probs:
            L.append("Manhattan said: " + "; ".join("\"%s\" (%d)" % kv for kv in probs.most_common(3)))
            L.append("")
        for r in rs[:6]:
            L.append("- `%s` %s%s: %s  \n  repro: `python tests/suite/run.py --cases %s --only %s -j 1 --no-retry`" % (
                r["id"], json.dumps(r["params"], separators=(",", ":")), " (flaky)" if r["verdict"] == "flaky" else "",
                r["first_reason"][:220], os.path.relpath(os.path.dirname(r["case_file"]), REPO).replace("\\", "/"),
                r["id"].split("/")[1]))
        if len(rs) > 6:
            L.append("- ... and %d more" % (len(rs) - 6))
        L.append("")
    path = os.path.join(run_dir, "report.md")
    open(path, "w", encoding="utf-8").write("\n".join(L) + "\n")
    return path


if __name__ == "__main__":
    print(write(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
