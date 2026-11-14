#!/usr/bin/env python3
"""Leximine release + traffic stats, with the bot-vs-human read built in.

Replaces hand-querying the GitHub API and re-deriving the same conclusions every
time Mason asks "did anyone download it". One call, and it tells you how much of
the number is real.

    python scripts\\release_stats.py
    python scripts\\release_stats.py --csv Z:\\metrics\\leximine_downloads.csv
    python scripts\\release_stats.py --json

Needs `gh` authenticated (it is, as account optumesto). Traffic endpoints require
push access, so they only work via gh -- the anonymous API will not serve them.

WHY THE BOT HEURISTIC IS HERE: on 2026-11-11 the mod showed 153 clones from 72
unique cloners against 29 page views from 6 unique visitors. Nobody clones a repo
72 times without loading the page. Clone counts on a new public repo are dominated
by crawlers watching the public events firehose, and they spike on push days. The
honest signal is unique VISITORS and referrers, not clones and not raw downloads.
"""

import argparse
import datetime as dt
import json
import os
import subprocess
import sys

REPOS = ["optumesto/leximine", "optumesto/leximine-backend"]


def gh(path):
    """Call the GitHub API via gh. Returns parsed JSON, or None if unavailable."""
    try:
        out = subprocess.run(
            ["gh", "api", path],
            capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        print("  ! gh call failed for %s: %s" % (path, e), file=sys.stderr)
        return None
    if out.returncode != 0:
        msg = (out.stderr or "").strip().splitlines()
        print("  ! gh api %s -> exit %d%s" % (
            path, out.returncode, (": " + msg[-1]) if msg else ""), file=sys.stderr)
        return None
    try:
        return json.loads(out.stdout)
    except ValueError:
        print("  ! unparseable JSON from %s" % path, file=sys.stderr)
        return None


def collect(repo):
    """Gather everything for one repo into a flat dict."""
    d = {"repo": repo, "checked": dt.date.today().isoformat()}

    meta = gh("repos/%s" % repo)
    if meta:
        d["stars"] = meta.get("stargazers_count")
        d["forks"] = meta.get("forks_count")
        d["watchers"] = meta.get("subscribers_count")
        d["open_issues"] = meta.get("open_issues_count")
        d["created"] = (meta.get("created_at") or "")[:10]

    rels = gh("repos/%s/releases" % repo) or []
    assets, total = [], 0
    for r in rels:
        for a in r.get("assets", []):
            n = a.get("download_count", 0)
            total += n
            assets.append({
                "tag": r.get("tag_name"),
                "published": (r.get("published_at") or "")[:10],
                "asset": a.get("name"),
                "downloads": n,
                "size": a.get("size"),
            })
    d["releases"] = len(rels)
    d["assets"] = assets
    d["downloads_total"] = total

    v = gh("repos/%s/traffic/views" % repo)
    if v:
        d["views_14d"] = v.get("count")
        d["unique_visitors_14d"] = v.get("uniques")
    c = gh("repos/%s/traffic/clones" % repo)
    if c:
        d["clones_14d"] = c.get("count")
        d["unique_cloners_14d"] = c.get("uniques")

    refs = gh("repos/%s/traffic/popular/referrers" % repo)
    if refs is not None:
        d["referrers"] = [
            {"from": r.get("referrer"), "views": r.get("count"),
             "uniques": r.get("uniques")} for r in refs
        ]
    return d


def verdict(d):
    """Translate the numbers into the honest reading. Returns list of lines."""
    out = []
    dl = d.get("downloads_total", 0)
    uv = d.get("unique_visitors_14d")
    clones = d.get("clones_14d")
    ucl = d.get("unique_cloners_14d")
    views = d.get("views_14d")

    if d.get("releases") == 0:
        out.append("No releases published -- there is nothing here to download.")
        return out

    # Clone-vs-view sanity check: the crawler tell.
    if clones and views is not None and views > 0 and clones > views * 2:
        out.append(
            "CLONES ARE NOISE: %d clones (%s unique) against only %d views (%s unique). "
            "Clones exceeding views by this margin means crawlers, not people. Ignore them."
            % (clones, ucl, views, uv))
    elif clones:
        out.append("Clones: %d (%s unique) -- in line with views, so possibly real."
                   % (clones, ucl))

    # Referrers are the cleanest human signal.
    refs = d.get("referrers") or []
    ref_uniques = sum(r.get("uniques") or 0 for r in refs)
    if refs:
        out.append("Referred visits: %d unique across %d source(s) -- %s"
                   % (ref_uniques, len(refs),
                      ", ".join("%s (%s)" % (r["from"], r["uniques"]) for r in refs)))
    else:
        out.append("No referrers recorded -- nobody arrived from search or a link.")

    # The headline read.
    if uv is not None and dl > 0:
        if uv <= 8:
            out.append(
                "READ: %d all-time downloads against only %d unique visitors ever. "
                "That cannot be %d people. Most of it is automated, and at least one "
                "visitor is Mason (the /upload paths are his browser pushes). "
                "Real adoption is approximately the referred-visit count above."
                % (dl, uv, dl))
        else:
            out.append("READ: %d downloads, %d unique visitors -- plausibly real; "
                       "check referrers above to confirm." % (dl, uv))

    eng = (d.get("stars") or 0) + (d.get("forks") or 0) + (d.get("open_issues") or 0)
    if eng == 0 and dl > 0:
        out.append("Zero stars, forks and issues -- no one has engaged with the repo.")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true", help="emit raw JSON only")
    ap.add_argument("--csv", metavar="PATH",
                    help="append one trend row per repo to this CSV (creates it with a header)")
    ap.add_argument("--repo", action="append",
                    help="override repo (repeatable); default is both Leximine repos")
    args = ap.parse_args()

    repos = args.repo or REPOS
    data = [collect(r) for r in repos]

    if args.json:
        print(json.dumps(data, indent=2))
    else:
        for d in data:
            print()
            print("=" * 68)
            print("%s   (created %s, checked %s)"
                  % (d["repo"], d.get("created", "?"), d["checked"]))
            print("=" * 68)
            if d.get("assets"):
                print("  %-9s %-12s %-26s %s" % ("TAG", "PUBLISHED", "ASSET", "DOWNLOADS"))
                for a in d["assets"]:
                    print("  %-9s %-12s %-26s %d"
                          % (a["tag"], a["published"], a["asset"], a["downloads"]))
                print("  %-49s %d" % ("TOTAL", d["downloads_total"]))
            else:
                print("  no release assets")
            print()
            print("  stars %s | forks %s | watchers %s | open issues %s"
                  % (d.get("stars"), d.get("forks"), d.get("watchers"),
                     d.get("open_issues")))
            print("  views(14d) %s / %s unique   clones(14d) %s / %s unique"
                  % (d.get("views_14d"), d.get("unique_visitors_14d"),
                     d.get("clones_14d"), d.get("unique_cloners_14d")))
            print()
            for line in verdict(d):
                print("  * " + line)
        print()
        print("NOTE: traffic windows are 14 days and cannot be backfilled. Download")
        print("counts are all-time. Run with --csv to build the trend you cannot")
        print("recover later.")

    if args.csv:
        import csv
        cols = ["checked", "repo", "downloads_total", "releases", "stars", "forks",
                "watchers", "open_issues", "views_14d", "unique_visitors_14d",
                "clones_14d", "unique_cloners_14d", "referred_uniques"]
        newfile = not os.path.exists(args.csv)
        parent = os.path.dirname(os.path.abspath(args.csv))
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with open(args.csv, "a", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
            if newfile:
                w.writeheader()
            for d in data:
                row = dict(d)
                row["referred_uniques"] = sum(
                    r.get("uniques") or 0 for r in (d.get("referrers") or []))
                w.writerow(row)
        print("appended %d row(s) to %s" % (len(data), args.csv))
    return 0


if __name__ == "__main__":
    sys.exit(main())
