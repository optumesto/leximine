"""Hold the e2e run lock for a while, so the refusal path can be tested.

Not part of the suite; used by hand to prove that a second harness is turned
away rather than killing the first one's server.

    python scripts/_lock_holder.py 30
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import e2e_test  # noqa: E402

seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 30
handle, owner = e2e_test.acquire_run_lock()
print(f"holder: got the lock = {handle is not None}", flush=True)
if handle is None:
    print(f"holder: somebody else has it ({owner})", flush=True)
    sys.exit(1)
try:
    time.sleep(seconds)
finally:
    e2e_test.release_run_lock(handle)
    print("holder: released", flush=True)
