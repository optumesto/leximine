"""Focused repro for the 'learned word is left alone' sequence.

The full suite takes ten minutes; this boots one server and one client and
runs just that sequence with diagnostics, so the loop is about three minutes.

    python scripts/debug_learned.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.e2e_test import (  # noqa: E402
    CLIENT_LOG, PROJECT, RCON_HOST, RCON_PASSWORD, RCON_PORT, RETRY_GAP_SECONDS,
    SERVER_LOG, TEST_ITEM, Rcon, kill_client_jvms, kill_minecraft_jvms, kill_tree,
    log_size, mod_command, spawn, tail_wait, trigger_pickup,
)


def ground_items(rcon: Rcon, player: str) -> str:
    reply = rcon.command(
        f"execute at {player} run data get entity "
        f"@e[type=item,limit=1,sort=nearest,distance=..10] Item")
    return reply.strip().replace("\n", " ")[:120]


def inventory(rcon: Rcon, player: str) -> str:
    return rcon.command(f"clear {player} {TEST_ITEM} 0").strip()


def main() -> int:
    kill_minecraft_jvms()
    time.sleep(2)
    for log in (SERVER_LOG, CLIENT_LOG):
        if log.is_file():
            log.unlink()

    server = client = None
    rcon = None
    try:
        print("starting server...")
        server = spawn("runServer", PROJECT / "run" / "dbg-server.log")
        if not tail_wait(SERVER_LOG, r'Done \([\d.]+s\)', 240)[0]:
            print("server never started")
            return 2

        rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)

        print("starting client...")
        mark = log_size(SERVER_LOG)
        client = spawn("runClientAuto", PROJECT / "run" / "dbg-client.log",
                       f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'diamante.wav'}")
        ok, _, joined = tail_wait(SERVER_LOG, r"(\S+) joined the game", 300, mark)
        if not ok:
            print("client never joined")
            return 2
        player = joined.split(" ")[0]
        print(f"joined as {player}\n")

        rcon.command(f"gamemode survival {player}")
        rcon.command(f"clear {player}")
        rcon.command("kill @e[type=item]")
        mod_command(rcon, player, "set language es_es")
        mod_command(rcon, player, "set frequency 10m")
        mod_command(rcon, player, "forget all")
        time.sleep(RETRY_GAP_SECONDS)

        # --- 1. learn it -------------------------------------------------
        print("== 1. learn the word ==")
        mark = log_size(SERVER_LOG)
        print("  summon:", trigger_pickup(rcon, player, TEST_ITEM))
        ok, mark, line = tail_wait(SERVER_LOG, r"challenge \d+ begun.*", 15, mark)
        print("  ", line if ok else "!! no challenge")
        ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ resolved: \w+", 25, mark)
        print("  ", line if ok else "!! no resolution")
        print("  inventory:", inventory(rcon, player))
        print("  ground   :", ground_items(rcon, player))
        print("  words    :", mod_command(rcon, player, "words").strip()[:100])

        # --- 2. should NOT be asked again -------------------------------
        time.sleep(RETRY_GAP_SECONDS)
        print("\n== 2. pick up the same item again (expect: no challenge, +1) ==")
        mark = log_size(SERVER_LOG)
        print("  summon:", trigger_pickup(rcon, player, TEST_ITEM))
        raised, _, line = tail_wait(SERVER_LOG, r"challenge \d+ begun.*", 6, mark)
        print("  ", line if raised else "no challenge raised (correct)")
        for wait in (1, 3, 6):
            time.sleep(wait if wait == 1 else wait - 1)
            print(f"  +{wait}s inventory: {inventory(rcon, player)}")
            print(f"       ground   : {ground_items(rcon, player)}")

        # --- 3. forget, should be asked again ---------------------------
        print("\n== 3. forget, then expect a challenge again ==")
        rcon.command("kill @e[type=item]")
        mod_command(rcon, player, "forget all")
        time.sleep(RETRY_GAP_SECONDS)
        mark = log_size(SERVER_LOG)
        print("  summon:", trigger_pickup(rcon, player, TEST_ITEM))
        ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ begun.*", 12, mark)
        print("  ", line if ok else "!! no challenge after forgetting")
        return 0
    finally:
        if rcon:
            rcon.close()
        for process in (client, server):
            if process:
                kill_tree(process)
        kill_minecraft_jvms()


if __name__ == "__main__":
    sys.exit(main())
