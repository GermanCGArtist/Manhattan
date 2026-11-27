"""Merges run folders into one: a case's results from a later run replace those from earlier ones.

    python tests/suite/merge.py [--cases tmp/suite/cases] tmp/suite/runs/<out> tmp/suite/runs/<full> tmp/suite/runs/<rerun> ...

With --cases, results of cases the generator no longer makes are left out.

Use it after rerunning part of the suite (failed cases, one family after a fix) to get one report for the whole
suite. Writes results.jsonl, meta.json and the report (report.md, summary.json) in <out>.
"""
import glob, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import report  # noqa: E402


def main():
    args = sys.argv[1:]
    keep = None
    if args[:1] == ["--cases"]:
        keep = {json.load(open(p, encoding="utf-8"))["id"] for p in glob.glob(os.path.join(args[1], "*.json"))}
        args = args[2:]
    if len(args) < 2:
        sys.exit(__doc__)
    out, runs = args[0], args[1:]
    os.makedirs(out, exist_ok=True)
    by_case = {}
    for run in runs:
        recs = {}
        for line in open(os.path.join(run, "results.jsonl"), encoding="utf-8"):
            try:
                r = json.loads(line)
            except ValueError:
                continue
            recs.setdefault(r["id"], []).append(line)
        by_case.update(recs)        # every attempt of a case comes from the latest run that has it
    if keep is not None:
        by_case = {k: v for k, v in by_case.items() if k in keep}
    with open(os.path.join(out, "results.jsonl"), "w", encoding="utf-8") as f:
        for lines in by_case.values():
            f.writelines(lines)
    meta = json.load(open(os.path.join(runs[0], "meta.json")))
    meta["merged_from"] = [os.path.basename(r.rstrip("/\\")) for r in runs]
    json.dump(meta, open(os.path.join(out, "meta.json"), "w"), indent=1)
    print(report.write(out))


if __name__ == "__main__":
    main()
