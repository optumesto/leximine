#!/usr/bin/env python3
"""Release preflight: find every version string, PRINT it, change nothing.

    python scripts\\version_preflight.py
    python scripts\\version_preflight.py --expect 0.1.4

WHY THIS EXISTS, AND WHY IT NEVER WRITES
A blanket version replace once rewrote two pasted log captures into a
"verification" that had never happened -- and that shipped publicly. The failure
was not the regex; it was replacing across files without reading the hits. So
this tool deliberately has no --fix. It finds, classifies and prints. You read
the list and edit what should change.

Exit codes: 0 clean, 1 something needs your eyes.
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

VERSION_RE = re.compile(r"\b\d+\.\d+\.\d+\b")

SKIP_DIRS = {".git", ".gradle", "build", ".idea", "run", "node_modules", "__pycache__"}

# Files whose version strings are SUPPOSED to change on a bump.
EXPECTED = (
    "gradle.properties",
    "CHANGELOG.md",
)

# Files that are evidence. Never rewrite these, even when they hold an old version:
# an old version inside a captured log is correct -- that IS what the run said.
EVIDENCE_RE = re.compile(
    r"(^|[\\/])(logs?|captures?|transcripts?|evidence)[\\/]"
    r"|\.log$|\.jsonl$|_output\.(txt|json|log)$|_capture[^\\/]*\.(txt|log|json)$"
    r"|SESSION_NOTES",
    re.IGNORECASE)

TEXT_EXT = {".md", ".txt", ".java", ".json", ".properties", ".gradle", ".ps1",
            ".py", ".cmd", ".yml", ".yaml", ".toml", ".cfg"}

# A captured-output LINE, wherever it lives. Path matching alone is not enough:
# the incident that motivated this tool was pasted log captures inside a .md,
# which no path rule can see. These are the shapes real output takes here.
CAPTURE_LINE_RE = re.compile(r"""
    \[[A-Za-z ]+/(INFO|WARN|ERROR|DEBUG)\]       # [Render thread/INFO], [main/INFO]
  | ^\s*\[(ok|OK|FAIL|PASS|WARN|ERROR|skip)\]    # [ok] / [FAIL] doctor lines
  | \bLoading\s+\d+\s+mods\b
  | ^\s*-\s+[a-z0-9_]+\s+\d+\.\d+\.\d+\s*$       # a mod-list line
  | ^\s*\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}         # leading timestamp
  | ^\s*(PS |\$ |> )                             # a pasted prompt
  | \bBUILD (SUCCESSFUL|FAILED)\b
  | \bTraceback \(most recent call last\)
""", re.VERBOSE)


def released_versions():
    """Versions this project has actually shipped, from git tags.

    Without this the scan reports every dependency version too -- Fabric API
    0.97.3 and loader 0.20.1 are not stale mod versions, and drowning the real
    hits is how a preflight gets ignored.
    """
    import subprocess
    out = set()
    try:
        r = subprocess.run(["git", "tag"], cwd=ROOT, capture_output=True,
                           text=True, timeout=20)
        if r.returncode == 0:
            for t in r.stdout.split():
                m = re.match(r"^v?(\d+\.\d+\.\d+)$", t.strip())
                if m:
                    out.add(m.group(1))
    except (OSError, subprocess.TimeoutExpired):
        pass
    return out


def current_version():
    p = os.path.join(ROOT, "gradle.properties")
    try:
        for line in open(p, encoding="utf-8", errors="replace"):
            m = re.match(r"^version=(.+)$", line.strip())
            if m:
                return m.group(1).strip()
    except OSError:
        pass
    return None


def walk():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            ext = os.path.splitext(fn)[1].lower()
            if ext and ext not in TEXT_EXT:
                continue
            yield os.path.join(dirpath, fn)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--expect", help="the version you intend to ship "
                                     "(default: whatever gradle.properties says)")
    args = ap.parse_args()

    cur = current_version()
    want = args.expect or cur
    if not want:
        print("FAIL: could not read version= from gradle.properties")
        return 1

    print("gradle.properties version : %s" % cur)
    print("checking against          : %s" % want)
    if args.expect and cur != args.expect:
        print("  ** gradle.properties says %s but you expect %s -- bump it first. **"
              % (cur, args.expect))

    known = released_versions() | {want}
    if cur:
        known.add(cur)
    print("known project versions    : %s" % (", ".join(sorted(known)) or "(no git tags)"))
    if len(known) <= 1:
        print("  (no git tags found -- only the current version is recognised, so an")
        print("   older stale version elsewhere will not be reported. Tag releases.)")

    stale, evidence, history, ok_count = [], [], [], 0
    for path in walk():
        rel = os.path.relpath(path, ROOT)
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        for i, line in enumerate(lines, 1):
            for v in set(VERSION_RE.findall(line)):
                # Only this project's own versions -- never dependency versions.
                if v not in known:
                    continue
                if v == want:
                    ok_count += 1
                    continue
                rec = (rel, i, v, line.strip()[:110])
                if EVIDENCE_RE.search(rel) or CAPTURE_LINE_RE.search(line):
                    evidence.append(rec)
                elif os.path.basename(rel) in EXPECTED:
                    history.append(rec)
                else:
                    stale.append(rec)

    print()
    print("matches at the target version : %d" % ok_count)
    if history:
        print("older versions in CHANGELOG/gradle.properties : %d (that is history, "
              "not a defect)" % len(history))

    if evidence:
        print()
        print("=" * 72)
        print("EVIDENCE -- DO NOT REWRITE (%d hits)" % len(evidence))
        print("=" * 72)
        print("An old version inside a captured log or session note is CORRECT.")
        print("That is what the run actually said. Leave every one of these alone.")
        for rel, i, v, text in evidence:
            print("  %-54s:%-5d %s" % (rel, i, v))

    if stale:
        print()
        print("=" * 72)
        print("OTHER VERSIONS FOUND -- READ EACH ONE (%d hits)" % len(stale))
        print("=" * 72)
        print("Some are deliberate history (CHANGELOG entries, 'since 0.1.0' notes).")
        print("Some are stale and should move to %s. Decide per line; do not" % want)
        print("bulk-replace. There is no --fix flag on purpose.")
        print()
        for rel, i, v, text in stale:
            print("  %-46s:%-5d found %s" % (rel, i, v))
            print("      %s" % text)

    # CRLF gate: make_release.ps1 asserts this, but catching it here is cheaper.
    bad_eol = []
    for path in walk():
        if not path.lower().endswith(".cmd"):
            continue
        try:
            raw = open(path, "rb").read()
        except OSError:
            continue
        if b"\n" in raw and b"\r\n" not in raw:
            bad_eol.append(os.path.relpath(path, ROOT))
    if bad_eol:
        print()
        print("FAIL: these .cmd files are LF, must be CRLF (git status stays silent")
        print("about this because it compares normalised content):")
        for b in bad_eol:
            print("  %s" % b)

    problems = bool(bad_eol) or bool(stale) or (args.expect and cur != args.expect)
    print()
    print("RESULT: %s" % ("read the lists above" if problems else "clean"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
