"""Focused repro for two attempts in one challenge.

Boots one server and one client streaming two_attempts.wav (wrong word, pause,
right word) and reports how each attempt was scored. Roughly three minutes,
against ten for the full suite.

    python scripts/debug_attempts.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.e2e_test import (  # noqa: E402
    CLIENT_LOG, PROJECT, RCON_HOST, RCON_PASSWORD, RCON_PORT, RETRY_GAP_SECONDS,
    SERVER_LOG, TEST_ITEM, Rcon, kill_minecraft_jvms, kill_tree, log_size,
    mod_command, spawn, tail_wait, trigger_pickup,
)


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

        print("starting client with two_attempts.wav...")
        mark = log_size(SERVER_LOG)
        client = spawn("runClientAuto", PROJECT / "run" / "dbg-client.log",
                       f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'two_attempts.wav'}")
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
        mod_command(rcon, player, "set frequency always")
        mod_command(rcon, player, "set timeout 14")
        mod_command(rcon, player, "forget all")
        time.sleep(RETRY_GAP_SECONDS)

        mark_server = log_size(SERVER_LOG)
        mark_client = log_size(CLIENT_LOG)
        print("summon:", trigger_pickup(rcon, player, TEST_ITEM))

        ok, mark_server, line = tail_wait(SERVER_LOG, r"challenge \d+ begun.*", 15, mark_server)
        print(" ", line if ok else "!! no challenge")
        ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ resolved: \w+", 30, mark_server)
        print(" ", line if ok else "!! no resolution")

        print("\nattempts as the client saw them:")
        text = CLIENT_LOG.read_text("utf8", "replace")[mark_client:]
        for row in text.splitlines():
            if "attempt" in row or "Speech verdict" in row:
                print("  ", row.split("(leximine)")[-1].strip())

        held = rcon.command(f"clear {player} {TEST_ITEM} 0")
        print(f"\ninventory: {held.strip()}")
        mod_command(rcon, player, "set timeout 6")
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
