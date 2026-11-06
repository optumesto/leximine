"""What a first commit would contain, without git being installed.

Applies each repo's own .gitignore (the subset of syntax actually used:
directory names, suffix globs, and one path glob) and reports the file list
and total size. The point is to see the answer BEFORE history exists, because
a 1.9 GB model or Mojang's translation files committed once are committed
forever.
"""
import fnmatch
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

#: This repo, and the backend beside it. Derived rather than written down,
#: so the script works on a machine whose drive letters are not this one's.
_HERE = Path(__file__).resolve().parent.parent
REPOS = [_HERE] + [p for p in (_HERE.parent / "leximine-backend",
                               _HERE.parent / "lingua-stt-backend")
                   if p.is_dir()]

#: Things that must never appear, whatever the ignore file says.
FORBIDDEN = [
    ("lang/*.json", "Mojang's translation files"),
    (".venv/*", "2.3 GB of wheels"),
    ("models/*", "1.9 GB of model weights"),
    (".env", "personal settings"),
    ("data/*", "one player's vocabulary history"),
    ("run/*", "worlds, logs and player data"),
    ("dist/*", "build output"),
]


def load_rules(repo: Path) -> tuple[set[str], list[str]]:
    dirs: set[str] = set()
    globs: list[str] = []
    path = repo / ".gitignore"
    if not path.is_file():
        return dirs, globs
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("/"):
            dirs.add(line.rstrip("/"))
        else:
            globs.append(line)
    return dirs, globs


def ignored(rel: str, dirs: set[str], globs: list[str]) -> bool:
    parts = rel.split("/")
    if any(p in dirs for p in parts):
        return True
    if ".git" in parts:
        return True
    for pattern in globs:
        if fnmatch.fnmatch(rel, pattern) or fnmatch.fnmatch(parts[-1], pattern):
            return True
    return False


problems = 0
for repo in REPOS:
    dirs, globs = load_rules(repo)
    kept: list[tuple[str, int]] = []
    for path in repo.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(repo).as_posix()
        if ignored(rel, dirs, globs):
            continue
        kept.append((rel, path.stat().st_size))

    total = sum(size for _, size in kept)
    print(f"=== {repo.name} ===")
    print(f"  {len(kept)} files, {total / 1024 / 1024:.2f} MB")
    print("  largest:")
    for rel, size in sorted(kept, key=lambda kv: -kv[1])[:6]:
        print(f"    {size / 1024:>9.1f} KB  {rel}")

    for pattern, why in FORBIDDEN:
        hits = [rel for rel, _ in kept if fnmatch.fnmatch(rel, pattern)]
        if hits:
            problems += 1
            print(f"  !! WOULD COMMIT {len(hits)} x {pattern} ({why})")
            for rel in hits[:3]:
                print(f"       {rel}")
    big = [(rel, s) for rel, s in kept if s > 1_000_000]
    if big:
        print(f"  !! {len(big)} file(s) over 1 MB:")
        for rel, s in big:
            print(f"       {s / 1024 / 1024:.1f} MB  {rel}")
            problems += 1
    print()

print("CLEAN — nothing forbidden would be committed." if not problems
      else f"{problems} problem(s) to fix before committing.")
sys.exit(1 if problems else 0)
