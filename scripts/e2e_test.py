"""End-to-end test of the pickup -> overlay -> verdict loop.

Boots a dedicated server and an auto-connecting client, then drives the game
over RCON. No keyboard, no menus, no human.

Every way a challenge can start, and every way one can end:

  1.  nothing said   -- push-to-talk with nothing pressing the key, so the
                        clock runs out. Item destroyed.
  2.  backend down   -- client fails open rather than eating the item. Granted.
  3.  correct word   -- a wav of "Diamante" streamed through the mod's own
                        audio pipeline. Granted.
  3b. learned words  -- say it right once and stop being asked; forget and the
                        asking comes back.
  4.  wrong word     -- a wav of something else, same path. Destroyed.
  4b. still asked    -- a miss never starts the frequency timer.
  5.  crafting       -- real slot clicks, both the plain and the shift route.
  6.  vocabulary     -- misses are listed as well as successes.
  7.  two attempts   -- wrong, pause, right: the second one wins.
  8.  tiers          -- noun, full name and whole sentence by item value.
  9.  punishment     -- a tier-3 miss costs more than the item; tier 1 never does.
  10. review         -- a container quizzes a word drawn from the backend's
                        own history of misses.
  11. merchant       -- a villager has to be asked, in a sentence, and a local
                        language model decides whether you did.
  12. orphaned review -- one nobody answers times out server-side instead of
                        silently turning the feature off for good.

Scenarios 3 and 4 use `-Dleximine.fakeMic`, which substitutes a wav file
for the microphone *inside* the mod, so the bytes still travel the real route:
AudioCapture -> websocket -> faster-whisper -> fuzzy match -> verdict packet.
They additionally assert that the client logged a speech verdict with a
non-empty transcript, because otherwise a "success" could just be the
fail-open path granting the item without any audio being involved.

Assertions are made against real game state (`clear <player> <item> 0` reports
a count without removing anything), not just log lines.

    python scripts/e2e_test.py
    python scripts/e2e_test.py --keep-running
    python scripts/e2e_test.py --only-trade    # just scenario 11, in ~2 min

Requires the server's run/server/server.properties to have rcon enabled; see
the repo README.
"""

from __future__ import annotations

import argparse
import os
import re
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
GRADLEW = PROJECT / "gradlew.bat"
SERVER_LOG = PROJECT / "run" / "server" / "logs" / "latest.log"
CLIENT_LOG = PROJECT / "run" / "logs" / "latest.log"

RCON_HOST, RCON_PORT, RCON_PASSWORD = "127.0.0.1", 25575, "lex"

#: TradeManager.REFUSAL_COOLDOWN_MILLIS. Hardcoded on purpose: if that
#: constant changes, --haggle should fail and be read, not silently adapt.
TRADE_COOLDOWN_SECONDS = 8
BACKEND_HEALTH = "http://127.0.0.1:8760/health"

TEST_ITEM = "minecraft:diamond"

# Must stay above LimboManager.RETRY_GAP_MILLIS. The mod refuses to raise two
# challenges back to back so that a pile of dropped items cannot machine-gun
# the player; a test that summons sooner than this just sees an uncontested
# pickup and blames the wrong thing.
RETRY_GAP_SECONDS = 4.0


# ----------------------------------------------------------------------
# minimal RCON client (the Source protocol Minecraft implements)
# ----------------------------------------------------------------------
class Rcon:
    LOGIN, COMMAND, RESPONSE = 3, 2, 0

    def __init__(self, host: str, port: int, password: str) -> None:
        self.sock = socket.create_connection((host, port), timeout=10)
        self._request_id = 0
        self._send(self.LOGIN, password)
        packet_id, _ = self._recv()
        if packet_id == -1:
            raise RuntimeError("rcon auth rejected")

    def _send(self, packet_type: int, body: str) -> int:
        self._request_id += 1
        payload = struct.pack("<ii", self._request_id, packet_type) + body.encode("utf8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self._request_id

    def _recv(self) -> tuple[int, str]:
        raw = self._read_exactly(4)
        (length,) = struct.unpack("<i", raw)
        payload = self._read_exactly(length)
        packet_id, _packet_type = struct.unpack("<ii", payload[:8])
        return packet_id, payload[8:-2].decode("utf8", "replace")

    def _read_exactly(self, count: int) -> bytes:
        chunks = b""
        while len(chunks) < count:
            chunk = self.sock.recv(count - len(chunks))
            if not chunk:
                raise ConnectionError("rcon closed")
            chunks += chunk
        return chunks

    def command(self, text: str) -> str:
        try:
            self._send(self.COMMAND, text)
            _, body = self._recv()
        except (ConnectionResetError, ConnectionError, OSError) as e:
            # The server went away mid-run. Almost always because a second
            # harness was started, whose first act is to kill every Minecraft
            # JVM on the machine -- including this run's server. Raised as
            # something readable rather than a socket traceback forty frames
            # from the cause.
            raise ConnectionError(
                f"the server stopped answering while running {text!r}. "
                f"Was another copy of this harness started? Only one can run "
                f"at a time; it kills leftover Minecraft processes on startup."
            ) from e
        return body.strip()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# ----------------------------------------------------------------------
LOCK_FILE = PROJECT / "run" / ".e2e.lock"

#: Which byte the lock is taken on. Past the owner line, which has to stay
#: readable by whoever loses the race.
LOCK_BYTE_OFFSET = 4096


def acquire_run_lock() -> tuple[object | None, str]:
    """Take an exclusive lock, so two harnesses cannot run at once.

    The first thing a run does is kill every Minecraft JVM on the machine, to
    clear orphans left by an interrupted run. That is right when it is the
    only run, and destructive when it is not: a second harness shoots the
    first one's server dead, and the first dies much later with a socket
    error that looks like a product bug. This has already happened once and
    the result was believed for several minutes.

    An OS file lock rather than a pid file, because Windows drops the lock
    when the process ends however it ends -- so a crashed run leaves nothing
    stale to clean up, and there is no "is that pid still alive" guesswork.

    Returns (handle, owner). A handle means the lock is held; otherwise
    `owner` describes who has it.
    """
    import msvcrt

    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    if not LOCK_FILE.exists():
        LOCK_FILE.touch()
    # "r+" rather than "a+": append mode sends every write to the end of the
    # file whatever you seek to, so the owner line could never be rewritten.
    handle = open(LOCK_FILE, "r+", encoding="utf8")

    # The locked byte is far past the text, and deliberately so. Windows
    # makes a locked range unreadable to everyone else, not merely
    # unlockable -- locking byte 0 meant the loser could not read the owner
    # line to report who had it, and its error handler died trying.
    handle.seek(LOCK_BYTE_OFFSET)
    try:
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        handle.close()
        # Read through a *fresh* handle. The one that just lost the lock is
        # refused reads too -- a failed msvcrt.locking poisons that handle,
        # not merely the byte range, so reusing it turns "who has the lock"
        # into a second PermissionError inside the error path.
        try:
            owner = LOCK_FILE.read_text(encoding="utf8").strip()
        except OSError:
            owner = ""
        return None, owner or "an unidentified run"

    handle.seek(0)
    handle.truncate()
    handle.write(f"pid {os.getpid()}, started {time.strftime('%H:%M:%S')}")
    handle.flush()
    return handle, ""


def release_run_lock(handle: object | None) -> None:
    if handle is None:
        return
    import msvcrt
    try:
        handle.seek(LOCK_BYTE_OFFSET)
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    except OSError:
        pass
    try:
        handle.close()
    except OSError:
        pass


def reset_review_history() -> str:
    """Drop the drill history this run just wrote.

    The harness talks to the same backend the player does, and scenario 10
    needs real history to draw a review from -- so a run leaves a dozen words
    in there that "Elefante Sandwich" got wrong. Left behind, the next real
    session opens with doors drilling words a wav fixture fumbled.
    """
    import urllib.error
    import urllib.request
    url = BACKEND_HEALTH.replace("/health", "/review/reset?lang=es_es")
    try:
        request = urllib.request.Request(url, method="POST")
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.read().decode("utf8", "replace")
    except (urllib.error.URLError, OSError) as e:
        return f"could not reset the review history: {e}"


def backend_is_up() -> bool:
    import urllib.error
    import urllib.request
    try:
        with urllib.request.urlopen(BACKEND_HEALTH, timeout=3) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def tail_wait(log: Path, pattern: str, timeout: float, since: int = 0) -> tuple[bool, int, str]:
    """Wait for `pattern` to appear in `log` at or after byte offset `since`."""
    regex = re.compile(pattern)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if log.is_file():
            text = log.read_text("utf8", "replace")
            window = text[since:]
            match = regex.search(window)
            if match:
                return True, since + match.end(), match.group(0)
        time.sleep(0.4)
    return False, since, ""


def log_size(log: Path) -> int:
    return len(log.read_text("utf8", "replace")) if log.is_file() else 0


def spawn(task: str, out_path: Path, *extra: str) -> subprocess.Popen:
    handle = out_path.open("w", encoding="utf8")
    return subprocess.Popen(
        [str(GRADLEW), task, *extra],
        cwd=str(PROJECT),
        stdout=handle,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
    )


def kill_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                   capture_output=True, check=False)


def _kill_jvms_matching(pattern: str) -> None:
    subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
         f"Where-Object {{ $_.CommandLine -match '{pattern}' }} | "
         "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"],
        capture_output=True, check=False)


def kill_client_jvms() -> None:
    """Only the auto-connecting client.

    Matching on 'devlaunchinjector' or 'net.minecraft' would also match the
    dedicated server, which launches exactly the same way -- restarting the
    client for scenario 2 silently killed the server out from under it and the
    rejoin failed with connection-refused. The quickPlay argument is unique to
    this harness's client, so it is the safe discriminator.
    """
    _kill_jvms_matching("quickPlayMultiplayer")


def kill_minecraft_jvms() -> None:
    """Teardown only: both the client and the dedicated server."""
    _kill_jvms_matching("devlaunchinjector|net.minecraft")


# ----------------------------------------------------------------------
def count_in_inventory(rcon: Rcon, player: str, item: str) -> int:
    """`clear <player> <item> 0` counts matching items without removing them."""
    reply = rcon.command(f"clear {player} {item} 0")
    match = re.search(r"(\d+)", reply)
    if "No items were found" in reply or not match:
        return 0
    return int(match.group(1))


def trigger_pickup(rcon: Rcon, player: str, item: str) -> str:
    """Drop an item directly onto the player so vanilla runs onPlayerCollision.

    PickupDelay is forced to 0: the mixin re-checks that guard, so a summoned
    item with the default delay would be ignored for its first half-second.
    """
    return rcon.command(
        f'execute at {player} run summon minecraft:item ~ ~ ~ '
        f'{{Item:{{id:"{item}",Count:1b}},PickupDelay:0s}}'
    )


def scenario(name: str, expectation: str) -> None:
    print(f"\n{'=' * 70}\n{name}\n  expect: {expectation}\n{'=' * 70}")


def mod_command(rcon: Rcon, player: str, command: str) -> str:
    """Run a /leximine subcommand as the player, from the console."""
    return rcon.command(f"execute as {player} run leximine {command}")


def expect_no_challenge(rcon: Rcon, player: str, label: str, *,
                        expect_count: int) -> list[str]:
    """Assert a pickup is NOT challenged, and lands in the inventory normally."""
    failures: list[str] = []
    mark = log_size(SERVER_LOG)
    trigger_pickup(rcon, player, TEST_ITEM)

    raised, _, line = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 6, mark)
    if raised:
        print(f"  !! unexpected challenge: {line}")
        failures.append(f"{label}: word was asked again when it should have been known")
    else:
        print("  no challenge raised (word is known)")

    time.sleep(1.5)
    count = count_in_inventory(rcon, player, TEST_ITEM)
    print(f"  inventory holds {count} x {TEST_ITEM} (want {expect_count})")
    if count != expect_count:
        failures.append(f"{label}: inventory has {count}, wanted {expect_count}")
    return failures


def player_pos(rcon: Rcon, player: str) -> tuple[float, float, float]:
    """Read a player's position. `Pos` comes back as "[1.5d, -60.0d, 2.5d]"."""
    reply = rcon.command(f"data get entity {player} Pos")
    numbers = re.findall(r"(-?\d+\.?\d*)d", reply)
    if len(numbers) != 3:
        return (0.0, 0.0, 0.0)
    return (float(numbers[0]), float(numbers[1]), float(numbers[2]))


def killed_count(reply: str) -> int:
    """`kill` answers "Killed N entities" -- or an error when there were none."""
    match = re.search(r"Killed (\d+)", reply)
    if match:
        return int(match.group(1))
    return 0 if "No entity" in reply or not reply else (1 if "Killed" in reply else 0)


def filled_count(reply: str) -> int:
    """`fill ... replace <block>` answers "Successfully filled N block(s)"."""
    match = re.search(r"filled (\d+)", reply)
    return int(match.group(1)) if match else 0


def calm(rcon: Rcon, player: str) -> None:
    """Clear the wreckage of the previous leg's punishment.

    Any scenario that rolls a real punishment and then runs another leg is
    testing against whatever the payload left behind. `--streak` rolled
    T2_THE_SWARM on its first leg, and the cave spiders were still biting
    three legs later: the player died, respawned somewhere else, and an item
    summoned at the old position raised no challenge at all. The failure read
    as "the feature stopped working".
    """
    rcon.command("kill @e[type=!player]")
    rcon.command(f"effect clear {player}")
    rcon.command(f"effect give {player} minecraft:instant_health 1 10 true")
    rcon.command(f"clear {player}")
    rcon.command("time set day")
    rcon.command("weather clear")


def report(failures: list[str]) -> int:
    """The tail every scenario ends with: what broke, and an exit code."""
    print(f"\n{'=' * 70}")
    if failures:
        print(f"{len(failures)} FAILURE(S):")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("ALL CHECKS PASSED")
    return 0


def run_trade_case(rcon: Rcon, player: str, label: str, *,
                   expect_accepted: bool) -> list[str]:
    """Use a merchant and assert how the negotiation ends.

    `/leximine testtrade` makes its own villager when there is none to
    hand, which is deliberate: summoning one over RCON reports success and
    then leaves nothing any selector can find, and the only symptom is the
    command saying there is no merchant nearby. The mod spawns it through the
    same call the game uses and reports the offer count, so a failure here is
    about negotiation and not about entity plumbing.
    """
    found: list[str] = []
    rcon.command("kill @e[type=villager]")
    time.sleep(0.5)

    mark_s = log_size(SERVER_LOG)
    mark_c = log_size(CLIENT_LOG)
    print("  " + (mod_command(rcon, player, "testtrade") or "(interacted)"))

    ok, mark_s, line = tail_wait(
        SERVER_LOG, r"trade \d+ for .*must ask for .*", 12, mark_s)
    print(f"  server: {line}" if ok else "  !! no trade challenge")
    if not ok:
        found.append(f"{label}: using a merchant raised no challenge")
        return found
    if "bread" not in line:
        found.append(f"{label}: asked for the wrong item ({line})")

    # The model's own verdict, logged by the client. Worth asserting
    # separately from the outcome: an unreachable model deliberately lets the
    # trade through, which would otherwise read as a pass.
    ok, _, verdict = tail_wait(
        CLIENT_LOG, r"Speech verdict for challenge \d+: .*", 45, mark_c)
    print(f"  client: {verdict.strip()}" if ok else "  !! never resolved")
    if not ok:
        found.append(f"{label}: trade challenge never resolved")
        return found
    if "judge_unavailable" in verdict:
        found.append(f"{label}: no language model reachable -- start Ollama")
        return found

    wanted = "accepted" if expect_accepted else "refused"
    ok, _, line = tail_wait(SERVER_LOG, rf"trade \d+ {wanted}: .*", 10, mark_s)
    print(f"  server: {line}" if ok else f"  !! the trade was not {wanted}")
    if not ok:
        found.append(f"{label}: the trade was not {wanted}")
    return found


def run_pickup_case(rcon: Rcon, player: str, label: str, *,
                    expect_success: bool, expect_count: int) -> list[str]:
    """Summon an item onto the player and assert how the challenge ends."""
    failures: list[str] = []
    mark = log_size(SERVER_LOG)
    print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))

    ok, mark, line = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
    print(f"  server: {line}" if ok else "  !! no 'challenge begun' line")
    if not ok:
        failures.append(f"{label}: challenge was never raised")
        return failures

    wanted = "success" if expect_success else "fail"
    ok, _, line = tail_wait(SERVER_LOG, rf"challenge \d+ resolved: {wanted}", 25, mark)
    print(f"  server: {line}" if ok else f"  !! no '{wanted}' resolution")
    if not ok:
        failures.append(f"{label}: challenge never resolved as {wanted}")

    count = count_in_inventory(rcon, player, TEST_ITEM)
    print(f"  inventory holds {count} x {TEST_ITEM} (want {expect_count})")
    if count != expect_count:
        failures.append(f"{label}: inventory has {count}, wanted {expect_count}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep-running", action="store_true",
                        help="leave the server and client up afterwards")
    parser.add_argument("--hud-shot", action="store_true",
                        help="raise one long challenge and screenshot the overlay")
    parser.add_argument("--only-trade", action="store_true",
                        help="just the merchant scenario, for iterating on it")
    parser.add_argument("--trade-shot", action="store_true",
                        help="screenshot a refused trade: the panel, then the flash")
    parser.add_argument("--lotto", action="store_true",
                        help="drive every tier-1 punishment and assert what it did")
    parser.add_argument("--lotto-shot", action="store_true",
                        help="screenshot the wheel: mid-spin, then locked")
    parser.add_argument("--rig", action="store_true",
                        help="load one outcome with /lex rig, miss a word for "
                             "real, and assert it fired exactly once")
    parser.add_argument("--endgate", action="store_true",
                        help="an eye of ender must be argued into its frame: "
                             "blocked on a miss, punished, and the eye is kept")
    parser.add_argument("--nethergate", action="store_true",
                        help="a nether portal needs one composed sentence; and "
                             "the Nether side is never gated")
    parser.add_argument("--streak", action="store_true",
                        help="a run of 10+ makes the next miss roll a tier "
                             "higher, and 'off' takes the stake away again")
    parser.add_argument("--haggle", action="store_true",
                        help="negotiation rules: standing is per merchant, a "
                             "refusal costs a cooldown, and it is not a reroll")
    parser.add_argument("--hold", action="store_true",
                        help="nothing else may be collected while a word is "
                             "pending: the second item must stay on the floor")
    parser.add_argument("--smith", action="store_true",
                        help="netherite upgrades at a smithing table are challenged, "
                             "both click routes; armour trims are not")
    parser.add_argument("--level-shot", action="store_true",
                        help="screenshot the panel showing the progress badge")
    parser.add_argument("--ui-tour", action="store_true",
                        help="capture every overlay state for inspection")
    parser.add_argument("--note-shot", action="store_true",
                        help="screenshot a failure that names the item actually "
                             "said ('that sounded like Hielo') beside the wheel")
    parser.add_argument("--lang", default="es_es",
                        help="language to run in. Worth moving only for "
                             "screenshots: the speech fixtures are recorded "
                             "in Spanish, so every scenario that asserts on a "
                             "verdict needs es_es to mean anything")
    parser.add_argument("--shot-item", default="minecraft:diamond_sword",
                        help="which item --level-shot raises a challenge for")
    args = parser.parse_args()

    # Before anything is killed. Two runs at once cannot work: they share a
    # port, a world, a log file and the backend's review history, and the
    # kill below would take the other one's server with it.
    lock, owner = acquire_run_lock()
    if lock is None:
        print(f"!! another run of this harness is already going ({owner}).")
        print("   They share a server port, a world and the backend, so the")
        print("   second would kill the first's server on the next line.")
        print("   Wait for it, or stop it, then run this again.")
        return 2

    try:
        return run(args)
    finally:
        release_run_lock(lock)


def run(args: argparse.Namespace) -> int:
    # A leftover client from an interrupted run writes to the same log file and
    # joins the same server, which makes every assertion below meaningless.
    kill_minecraft_jvms()
    time.sleep(2)

    for log in (SERVER_LOG, CLIENT_LOG):
        if log.is_file():
            log.unlink()

    # quickPlay only runs after every "init screen" is dismissed, and the
    # accessibility onboarding screen counts as one whenever options.txt is
    # absent -- so the client would sit at a dialog forever, waiting for a
    # click. Writing the option is what makes auto-connect possible at all.
    options = PROJECT / "run" / "options.txt"
    if not options.is_file():
        print("!! run/options.txt is missing; the client will stop at the")
        print("   accessibility onboarding screen and never auto-connect.")
        return 2

    failures: list[str] = []
    server = None
    client_holder: list[subprocess.Popen | None] = [None]
    launch_count = [0]
    rcon = None

    def stop_client() -> None:
        if client_holder[0] is not None:
            mark = log_size(SERVER_LOG)
            kill_tree(client_holder[0])
            kill_client_jvms()
            tail_wait(SERVER_LOG, r"(\S+) left the game", 60, mark)
            client_holder[0] = None
            time.sleep(2)

    def launch_client(*extra: str) -> str | None:
        """Start the auto-connecting client; returns the joined player name."""
        launch_count[0] += 1
        mark = log_size(SERVER_LOG)
        client_holder[0] = spawn(
            "runClientAuto",
            PROJECT / "run" / f"client{launch_count[0]}-stdout.log",
            *extra)
        ok, _, joined = tail_wait(SERVER_LOG, r"(\S+) joined the game", 300, mark)
        if not ok:
            return None
        name = joined.split(" ")[0]
        rcon_local = rcon
        if rcon_local is not None:
            rcon_local.command(f"gamemode survival {name}")
            rcon_local.command(f"clear {name}")
            # Loose item entities survive a server restart inside a loaded
            # chunk, and two summoned stacks on the same block merge into one.
            # A leftover diamond therefore turns the next "one item" check
            # into a count of two and reads as a product bug.
            rcon_local.command("kill @e[type=item]")
            # Known starting point: every pickup challenged, Spanish, and no
            # vocabulary carried over from a previous run's progress file.
            #
            # --lang overrides it, which is only useful for screenshots: the
            # speech fixtures are recorded Spanish, so every scenario that
            # asserts on a verdict needs es_es to mean anything. Taking a
            # picture of the overlay does not.
            mod_command(rcon_local, name, f"set language {args.lang}")
            mod_command(rcon_local, name, "set frequency always")
            # Tiers off for the speech scenarios: their fixtures say one word,
            # and a diamond is tier 3, so the prompt would be a whole sentence
            # that "Diamante" alone must not satisfy. Scenario 8 turns them
            # back on to test the tier logic itself.
            mod_command(rcon_local, name, "set tiers false")
            mod_command(rcon_local, name, "forget all")
        # Settle: the client needs its world loaded before packets mean much.
        time.sleep(3)
        return name

    try:
        print("starting dedicated server...")
        # No -PlexFrequency here on purpose. A launch override beats the
        # config file, so the scenarios that change frequency with
        # /leximine set would write the value and then be ignored -- which
        # is exactly how an earlier version of this test passed while proving
        # nothing. The harness uses the command instead, like a player would.
        # Every other scenario asserts on exact inventory counts and on what
        # is standing near the player, and a 20% chance of silverfish or of
        # having your held item flung across the room would turn all of them
        # into coin flips. The lotto is driven deliberately in --lotto instead.
        # --streak is deliberately NOT in this list, though it asserts on
        # which tier a miss rolled. It was, on the theory that reading a tier
        # needs a roll to read it off -- but `lotto: tier 3 (sentence) -> SAFE`
        # is logged on a safe roll too, so the tier is observable at 0% and
        # the payloads were pure interference. At 100% the first leg fired a
        # real tier-2 payload and the player was dead or flung across the map
        # by the third, which failed as "raised no challenge" and read like
        # the feature breaking.
        server_extra = ["-PlexLottoChance="
                        + ("100" if (args.lotto or args.lotto_shot
                                     or args.note_shot) else "0")]
        if args.hud_shot or args.level_shot or args.ui_tour:
            server_extra.append("-PlexTimeout=40000")
        server = spawn("runServer", PROJECT / "run" / "server-stdout.log", *server_extra)
        ok, _, _ = tail_wait(SERVER_LOG, r'Done \([\d.]+s\)', timeout=240)
        if not ok:
            print("!! server never finished starting")
            return 2
        print("server up")

        rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)

        # server.properties says peaceful, and on peaceful the game discards
        # every hostile mob on its first tick -- so half the punishment matrix
        # silently does nothing and scenario 9's zombie never arrives. Natural
        # spawning stays off via spawn-monsters=false, so this only affects
        # mobs the mod places deliberately.
        print(f"  {rcon.command('difficulty normal')}")

        # Push-to-talk, with nothing ever pressing the key. That makes
        # scenario 1 deterministic: without it the client opens the real
        # microphone, and whether the challenge fails depends on whether this
        # machine has an input device and how quiet the room is.
        print("starting auto-connecting client...")
        player = launch_client("-PlexPushToTalk=true")
        if player is None:
            print("!! client never joined")
            return 2
        print(f"client joined as {player!r}")

        if not backend_is_up():
            print("!! backend is not running; start lingua-stt-backend first")
            return 2

        # ---------------------------------------------------------------
        if args.hud_shot:
            scenario("HUD SHOT  one long challenge, captured to a PNG",
                     "the overlay, as a player actually sees it")
            # Relaunch with a fake mic so the capture catches the overlay
            # mid-utterance: recording indicator lit, level meter moving and a
            # running transcript, rather than an idle panel.
            stop_client()
            # streamMode is a client rendering option, read from the client's
            # own config. On this rig the client and the dedicated server have
            # separate config dirs, so the server's /leximine command
            # cannot reach it -- hence the launch argument.
            player = launch_client(
                f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'hudshot.wav'}",
                "-PlexStream=true")
            if player is None:
                print("!! client never rejoined")
                return 2
            # A hot streak, so the run counter is in the frame too. It only
            # draws from 3 and only goes gold from 10, so a shot taken at zero
            # proves nothing about the one piece of HUD that is new.
            mod_command(rcon, player, "setstreak 12")
            mark_server = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
            ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark_server)
            print(f"  server: {line}" if ok else "  !! challenge never raised")
            if not ok:
                return 1
            # Early enough that the transcript is still a few words and shows
            # the settled/moving split, rather than a long line that gets
            # trimmed into a single colour. Waiting longer is not an option
            # here: the fake mic's clip is about five seconds, and the client
            # closes the challenge as soon as it runs out.
            time.sleep(1.75)
            shot = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", str(PROJECT / "scripts" / "capture_hud.ps1"),
                 "-Out", "run\\hud.png"],
                cwd=str(PROJECT), capture_output=True, text=True, check=False)
            print("  " + (shot.stdout.strip() or shot.stderr.strip()))
            return 0 if shot.returncode == 0 else 1

        # ---------------------------------------------------------------
        def capture(name: str) -> None:
            shot = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", str(PROJECT / "scripts" / "capture_hud.ps1"),
                 "-Out", f"run\\{name}"],
                cwd=str(PROJECT), capture_output=True, text=True, check=False)
            print("  " + (shot.stdout.strip() or shot.stderr.strip()))

        if args.trade_shot:
            scenario("TRADE SHOT  a refusal, with the judge's reason on screen",
                     "the panel mid-challenge, then the flash after the verdict")

            # The panel. trade_retry.wav is a fragment the model refuses at
            # ~2.5s followed by the real request at ~5s, so a capture in
            # between catches the note while the challenge is still open and
            # the clock still running -- which is the state it exists for.
            stop_client()
            player = launch_client(
                f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'trade_retry.wav'}")
            if player is None:
                print("!! client never joined")
                return 2
            # Trade doubles this, so 30s means the panel is still up.
            mod_command(rcon, player, "set timeout 30")
            rcon.command("kill @e[type=villager]")
            mark_server = log_size(SERVER_LOG)
            mark_client = log_size(CLIENT_LOG)
            mod_command(rcon, player, "testtrade")
            ok, _, line = tail_wait(SERVER_LOG, r"trade \d+ for .*", 12, mark_server)
            print(f"  server: {line}" if ok else "  !! no trade challenge")
            if not ok:
                return 1

            # Wait for the refusal itself rather than guessing at it. The
            # first capture slept a fixed 3.6s and landed a fraction before
            # the model answered, so the shot showed a panel with no note on
            # it -- which looks exactly like the feature not working.
            ok, _, line = tail_wait(
                CLIENT_LOG, r"attempt \d+: heard .* -- .*", 30, mark_client)
            print(f"  client: {line.strip()}" if ok else "  !! no refusal to show")
            if not ok:
                return 1
            capture("hud_trade_note.png")

            # The flash. two_attempts.wav is refused outright, so the verdict
            # is a refusal and the reason has to survive into the flash -- the
            # panel closes the moment the server confirms.
            print("  -- now the flash, after an outright refusal --")
            stop_client()
            player = launch_client(
                f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'two_attempts.wav'}")
            if player is None:
                print("!! client never rejoined")
                return 2
            mod_command(rcon, player, "set timeout 16")
            rcon.command("kill @e[type=villager]")
            mark_client = log_size(CLIENT_LOG)
            mod_command(rcon, player, "testtrade")
            ok, _, verdict = tail_wait(
                CLIENT_LOG, r"Speech verdict for challenge \d+: .*", 45, mark_client)
            print(f"  client: {verdict.strip()}" if ok else "  !! never resolved")
            if not ok:
                return 1
            # The flash starts when the server's confirmation lands, a tick or
            # two after the verdict goes out.
            time.sleep(0.9)
            capture("hud_trade_flash.png")
            mod_command(rcon, player, "set timeout 6")
            return 0

        if args.ui_tour:
            scenario("UI TOUR  every overlay state, captured for inspection",
                     "word, sentence, review and trade panels held open")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            rcon.command("kill @e[type=villager]")

            # Each state is held open by push-to-talk with nothing pressing
            # the key, so nothing resolves and the chat warning has time to
            # fade off the top-right of the panel.
            states = [
                ("ui_word.png", "test minecraft:stone_pickaxe", "tiers true"),
                ("ui_sentence.png", "test minecraft:diamond_sword", "tiers true"),
                ("ui_legendary.png", "test minecraft:elytra", "tiers true"),
                ("ui_review.png", "testreview", None),
                ("ui_trade.png", "testtrade", None),
            ]
            for name, command, setting in states:
                if setting:
                    mod_command(rcon, player, f"set {setting}")
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                rcon.command("kill @e[type=villager]")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)
                mark = log_size(SERVER_LOG)
                print(f"  -- {name}: /{command}")
                mod_command(rcon, player, command)
                ok, _, line = tail_wait(
                    SERVER_LOG, r"(challenge|review|trade) \d+ ", 15, mark)
                if not ok:
                    print(f"     !! nothing started for {command}")
                    continue
                time.sleep(12.0)
                capture(name)

                # Wait for this one to finish before starting the next.
                # LimboManager refuses to raise a challenge while one is
                # open, so without this every state after the first silently
                # failed to start -- which looked like the command being
                # broken rather than the previous capture still holding the
                # floor. Tier 4 scales the clock by 2.5, hence the long wait.
                ok, _, _ = tail_wait(
                    SERVER_LOG,
                    r"(challenge \d+ resolved|review \d+ timed out|trade \d+ (refused|accepted))",
                    140, mark)
                if not ok:
                    print("     (previous challenge still open; next may not start)")
            mod_command(rcon, player, "set tiers false")
            return 0

        if args.level_shot:
            scenario("LEVEL SHOT  the panel with the progress badge",
                     "a challenge held open long enough for the chat to clear")
            # Push-to-talk with nothing pressing the key, so no microphone
            # opens and nothing resolves the challenge: it sits for the full
            # 40s the server was launched with. That is what buys time for
            # the server's "chat can't be verified" warning to fade, which
            # otherwise sits squarely over the top-right of the panel where
            # the badge is. The warning is a multiplayer artifact and never
            # appears in the single-player profile.
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            mod_command(rcon, player, "forget all")
            mod_command(rcon, player, "set tiers true")
            time.sleep(RETRY_GAP_SECONDS)

            mark_server = log_size(SERVER_LOG)
            mod_command(rcon, player, f"test {args.shot_item}")
            ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15,
                                    mark_server)
            print(f"  server: {line}" if ok else "  !! no challenge")
            if not ok:
                return 1
            time.sleep(13.0)
            capture("hud_level.png")
            mod_command(rcon, player, "set tiers false")
            return 0

        if args.lotto_shot:
            scenario("LOTTO SHOT  the wheel mid-spin, then locked",
                     "40 ticks of cycling names, then the result in red")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=silverfish]")
            # A real miss, with a fake mic that says something wrong, so all
            # three things are on screen at once: the verdict flash, the wheel
            # and the "AI heard" caption. The caption needs a transcript, and
            # the default push-to-talk client says nothing at all.
            #
            # hudshot.wav is the long one ("the quick brown fox jumps over the
            # lazy dog again and again"), deliberately: a caption that fits a
            # two-word transcript proves nothing about one that does not.
            # Stream mode on, because that is how this gets recorded.
            stop_client()
            player = launch_client(
                f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'hudshot.wav'}",
                "-PlexStream=true")
            if player is None:
                print("!! client never rejoined")
                return 2

            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)
            mark_server = log_size(SERVER_LOG)
            trigger_pickup(rcon, player, TEST_ITEM)
            ok, _, _ = tail_wait(SERVER_LOG, r"challenge \d+ resolved: fail", 30,
                                 mark_server)
            if ok:
                capture("hud_lotto_with_verdict.png")
            else:
                print("  !! no miss to photograph")

            rcon.command("kill @e[type=silverfish]")
            time.sleep(4)

            mark_client = log_size(CLIENT_LOG)
            # Timed from the command, not from the log. Waiting on a log line
            # costs the poll interval plus the flush, and the first attempt at
            # this captured both frames after the wheel had already stopped --
            # the "mid-spin" shot was the locked one and the "locked" shot was
            # of nothing at all.
            # The longest name in the matrix, at double size, is the one that
            # would overflow if anything did.
            mod_command(rcon, player, "testlotto T3_SENSORY_DEPRIVATION")

            # Fired back to back with no sleeps, which is not an oversight.
            # capture_hud.ps1 raises the window and waits 700ms for it to
            # settle before grabbing, and PowerShell itself costs ~0.6s to
            # start -- so each call lands its frame about 1.3s after it is
            # made. The wheel spins from ~0.1s to ~2.1s and holds the result
            # until ~3.5s, so firing immediately puts the first grab mid-roll
            # and the second, once the first returns, just after the lock.
            # Sleeping first is what put both earlier attempts past the end.
            capture("hud_lotto_rolling.png")
            capture("hud_lotto_locked.png")

            ok, _, locked = tail_wait(CLIENT_LOG, r"lotto wheel: locked on .*", 8,
                                      mark_client)
            print(f"  client: {locked.strip()}" if ok else "  !! never locked")
            return 0

        if args.note_shot:
            scenario("NOTE SHOT  a failure that names the item actually said",
                     "'that sounded like Hielo' on the flash, with the wheel up")
            # String is "Hilo" in Spanish and the clip says "Hielo" -- a real
            # leak from the negative corpus, which used to score 86 and be
            # accepted. The verdict now names what it heard, and that sentence
            # arrives on the same frame as the punishment wheel. Those two have
            # collided before, which is the whole reason for photographing it.
            stop_client()
            player = launch_client(
                f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'rival_hielo.wav'}",
                "-PlexStream=true")
            if player is None:
                print("!! client never rejoined")
                return 2

            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)
            mark_server = log_size(SERVER_LOG)
            mark_client = log_size(CLIENT_LOG)
            trigger_pickup(rcon, player, "minecraft:string")
            ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ resolved: fail", 30,
                                    mark_server)
            if not ok:
                print("  !! the challenge did not fail; nothing to photograph")
                print("     (if it PASSED, the vocabulary check has regressed)")
                return 1
            print(f"  server: {line.strip()}")
            capture("hud_note_with_wheel.png")

            ok, _, verdict = tail_wait(CLIENT_LOG, r"Speech verdict for challenge .*",
                                       8, mark_client)
            print(f"  client: {verdict.strip()}" if ok else "  !! no verdict logged")
            if ok and "sounded like" not in verdict:
                print("  !! the verdict carried no note -- the backend did not "
                      "name the rival")
                return 1
            rcon.command("kill @e[type=silverfish]")
            return 0

        if args.lotto:
            scenario("LOTTO  every punishment in the matrix, driven on purpose",
                     "the wheel spins, locks, and the payload lands two seconds later")

            # Where to put the player back after anything that moves them, and
            # a floor to stand on for the payloads that need solid ground.
            home = player_pos(rcon, player)
            hx, hy, hz = int(home[0] // 1), int(home[1] // 1), int(home[2] // 1)
            print(f"  home: {hx}, {hy}, {hz}")

            def reset_player() -> None:
                """A clean, survivable player standing on solid, empty ground.

                Resistance 255 and fire resistance are not softening the
                payloads -- they are what lets the harness assert on twenty of
                them in one run. A wither, a lava source and a 150-block drop
                each kill an ordinary player outright, and a death screen ends
                the run rather than failing a test.

                The floor is rebuilt from absolute coordinates every time,
                which is not belt-and-braces. These payloads burn, flood and
                delete terrain, and the world persists between runs -- so
                without it the later cases run inside the crater the earlier
                ones dug, and fire, lava and iron bars all quietly fail to
                place because there is no longer any ground to put them on.
                """
                for mob in ("item", "silverfish", "zombie", "skeleton", "creeper",
                            "cave_spider", "phantom", "vex", "wither", "iron_golem",
                            "tnt"):
                    rcon.command(f"kill @e[type={mob}]")
                rcon.command(f"fill {hx - 5} {hy - 1} {hz - 5} "
                             f"{hx + 5} {hy - 1} {hz + 5} minecraft:stone")
                rcon.command(f"fill {hx - 4} {hy} {hz - 4} "
                             f"{hx + 4} {hy + 4} {hz + 4} minecraft:air")
                rcon.command(f"tp {player} {home[0]:.1f} {home[1]:.1f} {home[2]:.1f}")
                rcon.command(f"effect clear {player}")
                rcon.command(f"clear {player}")
                rcon.command(f"effect give {player} resistance 600 255 true")
                rcon.command(f"effect give {player} fire_resistance 600 0 true")
                time.sleep(0.6)

            def spin(penalty: str) -> tuple[bool, str]:
                """Drive one penalty through the real path and wait for it."""
                mark_s = log_size(SERVER_LOG)
                mark_c = log_size(CLIENT_LOG)
                mod_command(rcon, player, f"testlotto {penalty}")

                ok_spin, _, _ = tail_wait(
                    CLIENT_LOG, r"lotto wheel: spinning tier \d+", 8, mark_c)
                ok_lock, _, locked = tail_wait(
                    CLIENT_LOG, r"lotto wheel: locked on .*", 8, mark_c)
                if not ok_spin:
                    return False, "the client never span the wheel"
                if not ok_lock:
                    return False, "the wheel never locked"
                if penalty != "SAFE":
                    ok_run, _, _ = tail_wait(
                        SERVER_LOG, rf"lotto: executing {penalty}", 8, mark_s)
                    if not ok_run:
                        return False, "the payload never fired"
                time.sleep(1.2)
                return True, locked.strip()

            def effects() -> str:
                return rcon.command(f"data get entity {player} active_effects").lower()

            def near(kind: str) -> int:
                """Count and clear: `kill` reports how many it got."""
                return killed_count(rcon.command(f"kill @e[type={kind}]"))

            def blocks(kind: str) -> int:
                """Count and clear a block type around the player."""
                return filled_count(rcon.command(
                    f"execute at {player} run fill ~-4 ~-3 ~-4 ~4 ~4 ~4 air replace {kind}"))

            # --- the 80% case, which is what a player sees most often -------
            print("\n  -- SAFE (the 80% roll) --")
            reset_player()
            ok, detail = spin("SAFE")
            print(f"  client: {detail}" if ok else f"  !! {detail}")
            if not ok:
                failures.append(f"lotto SAFE: {detail}")
            elif "SAFE" not in detail:
                failures.append(f"lotto SAFE: wheel locked on {detail!r}")

            # --- every payload, with what it should leave behind ------------
            def check(penalty: str, expectation: str, verify, setup=None) -> None:
                print(f"\n  -- {penalty}: {expectation} --")
                reset_player()
                if setup is not None:
                    setup()
                ok, detail = spin(penalty)
                if not ok:
                    print(f"  !! {detail}")
                    failures.append(f"lotto {penalty}: {detail}")
                    return
                problem = verify()
                print(f"  {'ok' if problem is None else 'FAIL: ' + problem}")
                if problem is not None:
                    failures.append(f"lotto {penalty}: {problem}")

            # Tier 1
            def junk_given():
                total = sum(count_in_inventory(rcon, player, item) for item in
                            ("minecraft:poisonous_potato", "minecraft:dead_bush",
                             "minecraft:dirt"))
                return None if total >= 1 else "no junk item was given"

            check("T1_SABOTAGE", "junk in the inventory", junk_given)
            check("T1_HUNGER_PANGS", "Hunger II",
                  lambda: None if "hunger" in effects() else "no hunger effect")

            def thrown_away():
                held = rcon.command(f"data get entity {player} SelectedItem").lower()
                floor = near("item")
                if "torch" in held:
                    return "the item is still in their hand"
                return None if floor >= 1 else "nothing was thrown"

            check("T1_BUTTERFINGERS", "held item flung away", thrown_away,
                  setup=lambda: rcon.command(
                      f"item replace entity {player} weapon.mainhand "
                      f"with minecraft:torch 1"))
            check("T1_FRIGHT_NIGHT", "a noise and nothing else", lambda: None)
            check("T1_THE_ITCH", "three silverfish",
                  lambda: None if near("silverfish") >= 1 else "no silverfish")

            # Tier 2
            def classic():
                undead = near("zombie") + near("skeleton")
                if "nausea" not in effects():
                    return "no nausea"
                return None if undead >= 1 else "nothing undead turned up"

            check("T2_THE_CLASSIC", "nausea and something undead", classic)
            check("T2_SEMANTIC_SABOTAGE", "three fires at their feet",
                  lambda: None if blocks("fire") >= 1 else "nothing caught light")

            def knocked():
                moved = abs(player_pos(rcon, player)[0] - home[0]) \
                        + abs(player_pos(rcon, player)[2] - home[2])
                if "slowness" not in effects():
                    return "no slowness"
                return None if moved > 1.5 else f"barely moved ({moved:.1f} blocks)"

            check("T2_THE_KNOCKBACK", "flung backwards and slowed", knocked)

            def disarmed():
                worn = rcon.command(f"data get entity {player} Inventory").lower()
                floor = near("item")
                if "iron_helmet" in worn:
                    return "the helmet is still being worn"
                return None if floor >= 1 else "nothing was dropped"

            check("T2_THE_DISARM", "armour taken off and thrown", disarmed,
                  setup=lambda: rcon.command(
                      f"item replace entity {player} armor.head "
                      f"with minecraft:iron_helmet 1"))
            check("T2_THE_SWARM", "three cave spiders",
                  lambda: None if near("cave_spider") >= 1 else "no cave spiders")

            # Tier 3
            def horde():
                creepers = near("creeper")
                undead = near("zombie") + near("skeleton")
                if "blindness" not in effects():
                    return "no blindness"
                if creepers < 1:
                    return "no creeper"
                return None if undead >= 5 else f"only {undead} undead, wanted 10"

            check("T3_THE_HORDE", "blindness and eleven hostiles", horde)

            def panic():
                primed = near("tnt")
                if primed < 1:
                    return "no TNT was primed"
                if "blindness" not in effects():
                    return "no blindness"
                return None if "slowness" in effects() else "no slowness"

            check("T3_THE_PANIC_ROOM", "primed TNT, blind and slow", panic)

            # Every block theIronJail builds, in absolute coordinates --
            # reset_player has just put the player on this exact spot. The
            # full ring including diagonals, both heights, plus the ceiling:
            # 17 where the player is standing on ground already.
            cage_positions = [
                (hx + dx, hy + dy, hz + dz)
                for dx in (-1, 0, 1) for dz in (-1, 0, 1) for dy in (0, 1)
                if not (dx == 0 and dz == 0)
            ] + [(hx, hy + 2, hz)]

            def jailed():
                # Counted one block at a time, and non-destructively, because
                # the cage has to still be standing for the decay assertions
                # below to mean anything. `fill X replace X` cannot be used to
                # count: Minecraft skips blocks that already match and reports
                # zero changed.
                def bar_count() -> int:
                    found = 0
                    for bx, by, bz in cage_positions:
                        reply = rcon.command(
                            f"execute if block {bx} {by} {bz} minecraft:iron_bars")
                        if "passed" in reply.lower():
                            found += 1
                    return found

                standing = bar_count()
                print(f"  bars placed: {standing} of {len(cage_positions)}")
                # The ring has to be closed. Four cardinal bars would pass a
                # "did it build a cage" check and let the player walk out
                # through a corner, which is what this number is really for.
                if standing < len(cage_positions):
                    return f"the ring is open: {standing} of {len(cage_positions)}"
                if near("iron_golem") < 1:
                    return "no iron golem"

                # Holds five seconds, then loses one a second.
                #
                # Timed from here, not from the payload: spin() has already
                # waited for the execution log and slept 1.2s, so roughly
                # that much of the five-second hold is gone before this line
                # runs. A naive four-second sample lands at ~5.2s and catches
                # the first bar already missing -- which looks like the hold
                # failing when it is the test's clock that is wrong.
                time.sleep(2.0)
                held = bar_count()
                print(f"  bars ~3s in: {held} (should still be {standing})")
                if held < standing:
                    return f"started decaying early ({held} left)"

                # Then watch it come apart rather than sleeping a guessed
                # total: this reports how long it actually took.
                started = time.time()
                left = standing
                # 17 bars at one a second, so this needs room well past 20s.
                while time.time() - started < 45:
                    left = bar_count()
                    if left == 0:
                        break
                    time.sleep(0.5)
                print(f"  cage gone after a further {time.time() - started:.0f}s")
                if left > 0:
                    return f"{left} bars never decayed"
                return None

            check("T3_THE_IRON_JAIL", "a cage that opens after five seconds", jailed)
            check("T3_SABOTAGE_ADVANCED", "a lava source at their feet",
                  lambda: None if blocks("lava") >= 1 else "no lava")

            def daytime() -> int:
                """`time query daytime` answers "The time is N"."""
                match = re.search(r"(\d+)", rcon.command("time query daytime"))
                return int(match.group(1)) if match else -1

            def deprived():
                phantoms = near("phantom")
                clock = daytime()
                print(f"  clock is now {clock} (13000-23000 is night)")
                if "darkness" not in effects():
                    return "no darkness"
                if "mining_fatigue" not in effects():
                    return "no mining fatigue"
                if not 13000 <= clock <= 23000:
                    return f"still daylight at {clock}"
                return None if phantoms >= 1 else "no phantoms"

            # Forced to noon first, so the jump to night is a real change and
            # not the world happening to be dark already.
            check("T3_SENSORY_DEPRIVATION", "darkness, fatigue, phantoms and nightfall",
                  deprived, setup=lambda: rcon.command("time set noon"))

            # Tier 4
            check("T4_THE_BOSS", "a wither",
                  lambda: None if near("wither") >= 1 else "no wither spawned")

            def launched():
                rose = player_pos(rcon, player)[1] - home[1]
                return None if rose > 100 else f"only rose {rose:.0f} blocks"

            check("T4_DIMENSION_BAN", "150 blocks straight up", launched)

            def cursed():
                vexes = near("vex")
                worn = rcon.command(f"data get entity {player} Inventory").lower()
                if "bad_omen" not in effects():
                    return "no bad omen"
                if "binding" not in worn:
                    return "the armour was not cursed"
                return None if vexes >= 1 else "no vexes"

            check("T4_EQUIPMENT_CURSE", "cursed armour, bad omen and vexes", cursed,
                  setup=lambda: rcon.command(
                      f"item replace entity {player} armor.chest "
                      f"with minecraft:iron_chestplate 1"))

            def evicted():
                now = player_pos(rcon, player)
                moved = ((now[0] - home[0]) ** 2 + (now[2] - home[2]) ** 2) ** 0.5
                if moved < 4000:
                    return f"only moved {moved:.0f} blocks"
                # Landed in the open rather than inside a mountain.
                return None if now[1] > -64 else "landed below the world"

            check("T4_THE_EVICTION", "5,000 blocks away, on solid ground", evicted)

            # Checked at a fixed coordinate, not relative to the player. The
            # first version asked whether the block under their feet was air
            # and always found stone -- because removing the ground drops them
            # through it, and by the time the question is asked they are
            # standing on whatever was underneath.
            mark_x, mark_y, mark_z = hx + 2, hy, hz

            def vaporised():
                gone = rcon.command(
                    f"execute if block {mark_x} {mark_y} {mark_z} minecraft:air")
                return None if "passed" in gone.lower() else "the marked block survived"

            check("T4_THE_BLACK_HOLE", "a sphere of world removed", vaporised,
                  setup=lambda: rcon.command(
                      f"setblock {mark_x} {mark_y} {mark_z} minecraft:stone"))

            # --- and finally the real dice --------------------------------
            print("\n  -- a real miss, rolled rather than forced --")
            reset_player()
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)
            mark_server = log_size(SERVER_LOG)
            trigger_pickup(rcon, player, TEST_ITEM)
            ok, mark_server, line = tail_wait(
                SERVER_LOG, r"lotto: tier \d+ \([^)]*\) -> \w+", 20, mark_server)
            print(f"  server: {line}" if ok else "  !! no roll on a real miss")
            if not ok:
                failures.append("lotto: a real miss did not roll")
            elif "SAFE" in line:
                failures.append("lotto: chance was forced to 100 but came up SAFE")

            reset_player()
            print(f"\n{'=' * 70}")
            if failures:
                print(f"{len(failures)} FAILURE(S):")
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        if args.endgate:
            scenario("END GATE  every eye has to be argued into its frame",
                     "the placement is blocked, the miss is punished, the eye is kept")

            mod_command(rcon, player, "forget all")
            mod_command(rcon, player, "set endgate true")
            mod_command(rcon, player, "set punishment on")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=silverfish]")
            time.sleep(RETRY_GAP_SECONDS)

            mark = log_size(SERVER_LOG)
            print("  " + (mod_command(rcon, player, "testendgate") or "(offered)"))

            # The gate must claim the interaction before the review check does.
            ok, mark, line = tail_wait(SERVER_LOG, r"end gate \d+ for .*", 12, mark)
            print(f"  server: {line}" if ok else "  !! the gate never fired")
            if not ok:
                failures.append("endgate: offering an eye raised no challenge")
            elif "tier 4" not in line:
                failures.append(f"endgate: not asked at tier 4 ({line.strip()})")

            # Push-to-talk with nobody pressing V, so it times out as a miss.
            ok, _, line = tail_wait(SERVER_LOG, r"end gate \d+ (refused|timed out)", 40, mark)
            print(f"  server: {line}" if ok else "  !! the gate never resolved")
            if not ok:
                failures.append("endgate: the challenge never resolved")

            # The eye must still be in hand. Burning a finite item for a
            # mispronunciation turns a language game into inventory management.
            eyes = count_in_inventory(rcon, player, "minecraft:ender_eye")
            print(f"  eyes of ender held: {eyes} (want 12 -- a miss must not cost one)")
            if eyes != 12:
                failures.append(f"endgate: holds {eyes} eyes, wanted 12 -- a miss ate one")

            # The hardest prompt answers to the hardest table. Assert that a
            # roll HAPPENED, not which way it fell: the lotto is 50/50 now, so
            # asserting a payload would fail half the time for the right reason.
            ok, _, line = tail_wait(SERVER_LOG, r"lotto: tier 4 \([^)]*\) -> \w+", 10, mark)
            print(f"  server: {line}" if ok else "  !! no tier-4 roll after the miss")
            if not ok:
                failures.append("endgate: a refused gate did not roll the tier-4 lottery")

            # --- and an open word must not leave the frame unguarded --------
            # Standing aside while busy used to be ActionResult.PASS, which
            # hands the eye to vanilla and places it free: open any challenge,
            # never answer it, and fill all twelve frames untested. The eye
            # count is the assertion that matters -- a log line could be right
            # while the block state was wrong.
            print("\n  -- a frame offered while another word is open --")
            # Punishments off from here: the lotto assertion above is done, and
            # a tier-3 payload firing off the decoy item challenge would be
            # rearranging the world under the leg that follows.
            mod_command(rcon, player, "set punishment none")
            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)
            mark = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
            ok, mark, _ = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
            if not ok:
                failures.append("endgate: could not open an item challenge to test against")
            else:
                out = mod_command(rcon, player, "testendgate offer")
                print(f"  {out.strip()}")
                if "eye in frame: true" in out:
                    failures.append("endgate: an eye went in free while another word "
                                    "was open -- open a challenge, never answer it, "
                                    "and fill every frame untested")
                ok, _, line = tail_wait(
                    SERVER_LOG, r"end gate withheld at .*already answering", 6, mark)
                print(f"  server: {line}" if ok else "  !! nothing logged the refusal")
                if not ok:
                    failures.append("endgate: a frame refused while busy said nothing, "
                                    "which is indistinguishable from a broken gate")
            ok, _, _ = tail_wait(SERVER_LOG, r"challenge \d+ resolved: .*", 45, mark)
            if not ok:
                failures.append("endgate: the item challenge never resolved")
                return report(failures)
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)

            # --- and the other half: a PASS must actually place the eye -----
            # Answered through the dev seam rather than a microphone; the
            # speech path is covered elsewhere and what is new here is the
            # replay that lets vanilla place the eye and judge the frame.
            print("\n  -- a passed gate --")
            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            time.sleep(RETRY_GAP_SECONDS)
            mark = log_size(SERVER_LOG)
            out = mod_command(rcon, player, "testendgate pass")
            print(f"  {out.strip()}")
            ok, _, line = tail_wait(SERVER_LOG, r"end gate \d+ passed", 12, mark)
            print(f"  server: {line}" if ok else "  !! the gate never reported a pass")
            if not ok:
                failures.append("endgate: a passed gate did not log a pass")
            if "eye in frame: true" not in out:
                failures.append("endgate: a passed gate did not place the eye")
            # A pass must cost exactly the one eye vanilla consumes.
            eyes = count_in_inventory(rcon, player, "minecraft:ender_eye")
            print(f"  eyes of ender held: {eyes} (want 11 -- a pass spends one)")
            if eyes != 11:
                failures.append(f"endgate: holds {eyes} eyes after a pass, wanted 11")

            print(f"\n{'=' * 70}")
            if failures:
                print(f"{len(failures)} FAILURE(S):")
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        if args.nethergate:
            scenario("NETHER GATE  one sentence before the way opens",
                     "blocked on a miss, lit on a pass, and never gated from the Nether side")

            mod_command(rcon, player, "set nethergate true")
            mod_command(rcon, player, "set punishment on")
            calm(rcon, player)
            time.sleep(RETRY_GAP_SECONDS)

            # --- a strike with nothing said ---------------------------------
            mark = log_size(SERVER_LOG)
            out = mod_command(rcon, player, "testnethergate")
            print(f"  {out.strip()}")
            if "fire placed: true" in out:
                failures.append("nethergate: the portal lit without a word")
            ok, mark, line = tail_wait(SERVER_LOG, r"nether gate \d+ for .*", 12, mark)
            print(f"  server: {line}" if ok else "  !! the gate never fired")
            if not ok:
                failures.append("nethergate: striking obsidian raised no challenge")
            elif "tier 3" not in line:
                failures.append(f"nethergate: not asked at tier 3 ({line.strip()})")

            ok, _, line = tail_wait(
                SERVER_LOG, r"nether gate \d+ (refused|timed out)", 45, mark)
            print(f"  server: {line}" if ok else "  !! the gate never resolved")
            if not ok:
                failures.append("nethergate: the challenge never resolved")

            # The roll is asserted, not the payload: lottoChance is 0 on this
            # run, so the line reads "-> SAFE" and nothing wrecks the world --
            # which is the point. What matters is that it rolled on tier 3.
            ok, _, line = tail_wait(
                SERVER_LOG, r"lotto: tier 3 \([^)]*\) -> \w+", 10, mark)
            print(f"  server: {line}" if ok else "  (no tier-3 roll)")
            if not ok:
                failures.append("nethergate: a refused gate did not roll on tier 3")

            # --- and not for free while another word is open -----------------
            print("\n  -- struck while another word is open --")
            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)
            mark = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
            ok, mark, _ = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
            if not ok:
                failures.append("nethergate: could not open an item challenge to test against")
            else:
                out = mod_command(rcon, player, "testnethergate")
                print(f"  {out.strip()}")
                if "fire placed: true" in out:
                    failures.append("nethergate: the portal lit free while another "
                                    "word was open")
                ok, _, line = tail_wait(
                    SERVER_LOG, r"nether gate withheld at .*already answering", 6, mark)
                if not ok:
                    failures.append("nethergate: a strike refused while busy said nothing")
            ok, _, _ = tail_wait(SERVER_LOG, r"challenge \d+ resolved: .*", 45, mark)
            if not ok:
                failures.append("nethergate: the decoy challenge never resolved")
                return report(failures)
            calm(rcon, player)
            time.sleep(RETRY_GAP_SECONDS)

            # --- a passed gate actually lights it ----------------------------
            print("\n  -- a passed gate --")
            mark = log_size(SERVER_LOG)
            out = mod_command(rcon, player, "testnethergate pass")
            print(f"  {out.strip()}")
            if "fire placed: true" not in out:
                failures.append("nethergate: a passed gate did not light the fire")
            ok, _, line = tail_wait(SERVER_LOG, r"nether gate \d+ passed", 12, mark)
            print(f"  server: {line}" if ok else "  !! no pass logged")
            if not ok:
                failures.append("nethergate: a passed gate did not log a pass")

            # --- the Nether side is never gated ------------------------------
            # The safety rule, and the one worth a dimension change to prove:
            # obsidian struck in the Nether is nearly always someone rebuilding
            # the portal home, and a speech check that fails there is not a
            # punishment, it is being stuck until the microphone cooperates.
            print("\n  -- the same strike, from the Nether --")
            calm(rcon, player)
            rcon.command(f"execute in minecraft:the_nether run tp {player} 0 70 0")
            time.sleep(2.0)
            # A floor and fire resistance, so a death on arrival cannot be
            # mistaken for the gate refusing.
            rcon.command(f"execute at {player} run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone")
            rcon.command(f"effect give {player} minecraft:fire_resistance 120 4 true")
            time.sleep(1.0)
            mark = log_size(SERVER_LOG)
            out = mod_command(rcon, player, "testnethergate")
            print(f"  {out.strip()}")
            if "fire placed: false" in out:
                failures.append("nethergate: the Nether side was gated -- a failed "
                                "sentence there strands the player")
            raised, _, line = tail_wait(SERVER_LOG, r"nether gate \d+ for .*", 5, mark)
            if raised:
                failures.append(f"nethergate: a challenge was raised in the Nether "
                                f"({line.strip()})")
            rcon.command(f"execute in minecraft:overworld run tp {player} 0 -59 0")

            return report(failures)

        if args.streak:
            scenario("STREAK  a long run makes the next slip cost more",
                     "breaking 10+ rolls a tier higher; 'off' leaves the counter alone")

            # A stick, not the usual diamond: tier 1 is the only tier where an
            # escalation is unambiguous. Escalate from tier 3 and the roll
            # lands on 4, which is also what several other things roll, so the
            # log line would not prove the streak caused it.
            CHEAP = "minecraft:stick"
            mod_command(rcon, player, "set punishment on")
            mod_command(rcon, player, "set streak escalate")
            mod_command(rcon, player, "set tiers true")

            for label, streak, want_tier, want_escalation in (
                ("a broken run of 11", 11, 2, True),
                # Nine is the interesting boundary: one short, and one short
                # has to cost exactly what the item was worth.
                ("a broken run of 9", 9, 1, False),
            ):
                print(f"\n  -- {label} --")
                calm(rcon, player)
                mod_command(rcon, player, "forget all")
                mod_command(rcon, player, f"setstreak {streak}")
                time.sleep(RETRY_GAP_SECONDS)

                mark = log_size(SERVER_LOG)
                print("  " + (trigger_pickup(rcon, player, CHEAP) or "(summoned)"))
                ok, mark, line = tail_wait(
                    SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
                if not ok:
                    failures.append(f"streak: {label} raised no challenge")
                    continue
                if "tier 1" not in line:
                    failures.append(f"streak: a stick is not tier 1 ({line.strip()}) -- "
                                    f"the escalation cannot be read off this")
                    continue

                # Nothing said, so it times out as a miss.
                ok, _, line = tail_wait(
                    SERVER_LOG, r"lotto: streak of (\d+) broken, rolling tier (\d+)", 40, mark)
                print(f"  server: {line.strip()}" if ok else "  (no escalation line)")
                if ok != want_escalation:
                    failures.append(
                        f"streak: {label} {'did not escalate' if want_escalation else 'escalated'}"
                        f" -- {'expected' if want_escalation else 'did not expect'} a tier bump")

                ok, _, line = tail_wait(
                    SERVER_LOG, r"lotto: tier (\d+) \([^)]*\) -> \w+", 15, mark)
                print(f"  server: {line.strip()}" if ok else "  !! no roll at all")
                if not ok:
                    failures.append(f"streak: {label} never rolled")
                elif f"tier {want_tier} " not in line:
                    failures.append(f"streak: {label} rolled on the wrong table "
                                    f"(wanted tier {want_tier}): {line.strip()}")

                # And the run is gone either way.
                out = mod_command(rcon, player, "settings")
                if "0 now" not in out:
                    failures.append(f"streak: {label} did not reset the counter "
                                    f"(settings says: "
                                    f"{[l for l in out.splitlines() if 'streak' in l]})")

            # --- and 'off' must take the stake away, not the counter ---------
            print("\n  -- the same run, with the stake switched off --")
            calm(rcon, player)
            mod_command(rcon, player, "set streak off")
            mod_command(rcon, player, "forget all")
            mod_command(rcon, player, "setstreak 11")
            time.sleep(RETRY_GAP_SECONDS)
            mark = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, CHEAP) or "(summoned)"))
            ok, mark, _ = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
            if not ok:
                failures.append("streak: the 'off' leg raised no challenge")
            else:
                escalated, _, line = tail_wait(
                    SERVER_LOG, r"lotto: streak of \d+ broken, rolling tier", 35, mark)
                if escalated:
                    failures.append(f"streak: 'off' still escalated ({line.strip()})")
                ok, _, line = tail_wait(
                    SERVER_LOG, r"lotto: tier (\d+) \([^)]*\) -> \w+", 15, mark)
                print(f"  server: {line.strip()}" if ok else "  !! no roll at all")
                if ok and "tier 1 " not in line:
                    failures.append(f"streak: 'off' rolled off tier 1: {line.strip()}")
                # The counter must still be kept -- "off" is about the stake.
                out = mod_command(rcon, player, "settings")
                if "counted, but costs nothing" not in out:
                    failures.append("streak: 'off' did not describe itself as still counting")

            mod_command(rcon, player, "set streak escalate")
            return report(failures)

        if args.haggle:
            scenario("HAGGLE  standing is with a merchant, not with villagers",
                     "a refusal costs a cooldown, and the cooldown is not a reroll")

            # The bug this exists for: openUntil and the refusal cooldown were
            # keyed by PLAYER. One good sentence to one farmer opened every
            # villager in range for a minute -- walk into a trading hall, ask
            # once, buy from thirty people in silence. Nothing logged it, and
            # from inside the game it looks exactly like the feature working.
            wav_path = PROJECT / "run" / "testaudio" / "wrong.wav"
            if not wav_path.is_file():
                print("!! run scripts/make_test_audio.ps1 first")
                return 2
            stop_client()
            # A single wrong noun, which is the documented FALSE case: "a bare
            # noun is not a request". So every negotiation here is refused,
            # which is what this scenario is about -- the accepted path is
            # --only-trade's job.
            player = launch_client(f"-PlexFakeMic={wav_path}")
            if player is None:
                print("!! client never rejoined")
                return 2
            mod_command(rcon, player, "set negotiate true")
            mod_command(rcon, player, "set timeout 8")
            rcon.command("kill @e[type=villager]")
            time.sleep(RETRY_GAP_SECONDS)

            # --- nothing is free while another word is open ------------------
            # Runs first, on a throwaway merchant, because it has to be the
            # ONLY reason the click is refused: every later leg leaves someone
            # on a cooldown, and the cooldown branch is checked before this
            # one, so a click there would be withheld for the wrong reason and
            # the assertion would pass without testing anything.
            #
            # Standing aside while busy used to mean ActionResult.PASS, which
            # hands the interaction to vanilla and opens the trade screen for
            # nothing: start a negotiation, leave it hanging, then deal with
            # the whole village. One `false` meaning both "not now" and "go
            # ahead" is the bug the pickup mixin shipped with.
            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)
            mark_s = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
            ok, mark_s, _ = tail_wait(SERVER_LOG, r"challenge \d+ begun for .*", 15, mark_s)
            if not ok:
                failures.append("haggle: could not open an item challenge to test against")
            else:
                print("  " + (mod_command(rcon, player, "testtrade new") or "(interacted)"))
                ok, _, line = tail_wait(
                    SERVER_LOG, r"trade for .* withheld: already answering", 6, mark_s)
                print(f"  server: {line}" if ok
                      else "  !! the merchant dealt during an open challenge")
                if not ok:
                    failures.append(
                        "haggle: a merchant was usable while another word was open -- "
                        "start a challenge, never answer it, and trade for free")
            # Let that challenge finish, or every later click stands aside for
            # the reason just tested rather than the one being tested.
            ok, _, _ = tail_wait(SERVER_LOG, r"challenge \d+ resolved: .*", 45, mark_s)
            if not ok:
                failures.append("haggle: the item challenge never resolved")
                return report(failures)
            rcon.command("kill @e[type=villager]")
            rcon.command("kill @e[type=item]")
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)

            # --- merchant A asks for something ------------------------------
            mark_s = log_size(SERVER_LOG)
            mark_c = log_size(CLIENT_LOG)
            print("  " + (mod_command(rcon, player, "testtrade multi")
                          or "(interacted)"))
            ok, mark_s, line = tail_wait(
                SERVER_LOG, r"trade \d+ for .*must ask for .*", 12, mark_s)
            print(f"  server: {line}" if ok else "  !! merchant A raised nothing")
            if not ok:
                failures.append("haggle: the first merchant raised no challenge")
                return report(failures)
            # Asserted out loud, because the merchant silently ceasing to exist
            # is what this scenario spent its first six runs discovering: the
            # test server had `spawn-npcs=false`, and ServerWorld.tick discards
            # every Npc entity -- which a villager is -- on the tick after it
            # appears. /summon answered "Summoned new Villager", the dev
            # command's own interaction worked off its direct reference in that
            # same tick, and by the next RCON call there was nothing there.
            if "No entity" in rcon.command(
                    "data get entity @e[type=villager,limit=1] Pos"):
                failures.append("haggle: the merchant was discarded immediately -- "
                                "check spawn-npcs=true in run/server/server.properties")
                return report(failures)
            # Remembered, because the whole no-reroll rule is about this
            # string coming back unchanged.
            asked_first = line.rsplit("must ask for", 1)[-1].strip()
            print(f"  merchant A wants: {asked_first}")

            ok, _, verdict = tail_wait(
                CLIENT_LOG, r"Speech verdict for challenge \d+: .*", 45, mark_c)
            print(f"  client: {verdict.strip()}" if ok else "  !! never resolved")
            if ok and "judge_unavailable" in verdict:
                failures.append("haggle: no language model reachable -- start Ollama")
                return report(failures)

            ok, mark_s, line = tail_wait(SERVER_LOG, r"trade \d+ refused: .*", 20, mark_s)
            print(f"  server: {line}" if ok else "  !! the bad sentence was not refused")
            if not ok:
                failures.append("haggle: a bare noun was not refused")
                return report(failures)

            # --- that merchant now wants nothing to do with them ------------
            mark_s = log_size(SERVER_LOG)
            print("  " + (mod_command(rcon, player, "testtrade") or "(interacted)"))
            ok, _, line = tail_wait(SERVER_LOG, r"trade for .* on cooldown", 6, mark_s)
            print(f"  server: {line}" if ok else "  !! no cooldown after a refusal")
            if not ok:
                failures.append("haggle: a refused merchant was immediately askable again -- "
                                "a miss costs nothing and clicking is the way through")
            # ...and the click must not have opened a fresh negotiation either.
            spam, _, line = tail_wait(
                SERVER_LOG, r"trade \d+ for .*must ask for .*", 4, mark_s)
            if spam:
                failures.append(f"haggle: re-clicking during the cooldown started "
                                f"another negotiation ({line.strip()})")

            # --- but the merchant NEXT TO HIM is a different conversation ---
            # The regression. Keyed per player, this click is swallowed by A's
            # cooldown and merchant B trades in silence.
            mark_s = log_size(SERVER_LOG)
            print("  " + (mod_command(rcon, player, "testtrade new") or "(interacted)"))
            ok, mark_s, line = tail_wait(
                SERVER_LOG, r"trade \d+ for .*must ask for .*", 12, mark_s)
            print(f"  server: {line}" if ok else "  !! merchant B was not gated")
            if not ok:
                failures.append("haggle: a second merchant raised no challenge -- "
                                "one player's standing is leaking across merchants")
            else:
                # Let B finish, or the open challenge (keyed by player) makes
                # the next click stand aside and the last leg proves nothing.
                ok, mark_s, _ = tail_wait(
                    SERVER_LOG, r"trade \d+ (refused|timed out).*", 45, mark_s)
                if not ok:
                    failures.append("haggle: merchant B's negotiation never ended")
                    return report(failures)

            # --- coming back to A asks for the SAME thing -------------------
            # A refusal must not be a reroll: miss on a hard word, click again,
            # get asked for an easy one. B's negotiation above outlasted A's
            # 8-second cooldown, so A is askable again by now.
            print("\n  -- back to merchant A, after the cooldown --")
            # B goes first, or "the nearest merchant" is ambiguous: B was
            # refused seconds ago and is still cooling down, so a click that
            # landed on B would look exactly like A's cooldown never lifting.
            print("  removing merchant B: " + rcon.command(
                f"execute at {player} run kill @e[type=villager,sort=furthest,limit=1]"
            ).strip())
            # Waited out explicitly rather than assuming merchant B's
            # negotiation took longer than it: when B resolved quickly, A still
            # had 1.6 seconds left and the last leg failed for a reason that
            # had nothing to do with what it tests.
            print(f"  waiting out the {TRADE_COOLDOWN_SECONDS}s cooldown...")
            time.sleep(TRADE_COOLDOWN_SECONDS + 1.5)
            mark_s = log_size(SERVER_LOG)
            print("  " + (mod_command(rcon, player, "testtrade") or "(interacted)"))
            ok, _, line = tail_wait(
                SERVER_LOG, r"trade \d+ for .*must ask for .*", 12, mark_s)
            print(f"  server: {line}" if ok else "  !! A never asked again")
            if not ok:
                stuck, _, cooling = tail_wait(
                    SERVER_LOG, r"trade for .* on cooldown, (\d+)ms left", 3, mark_s)
                failures.append(
                    "haggle: merchant A never asked again -- "
                    + (f"still cooling down ({cooling.strip()})" if stuck
                       else "and not because of the cooldown, which had lifted"))
            else:
                asked_again = line.rsplit("must ask for", 1)[-1].strip()
                print(f"  merchant A wants: {asked_again} (want {asked_first})")
                if asked_again != asked_first:
                    failures.append(
                        f"haggle: the demand was rerolled after a refusal "
                        f"({asked_first} -> {asked_again}) -- missing a hard word "
                        f"is rewarded with an easier one")

            return report(failures)

        if args.hold:
            scenario("HOLD  one word at a time, and nothing slips past it",
                     "a second item that lands mid-challenge stays on the floor")

            # Regression for a bug that was invisible from outside the code.
            # shouldChallenge() answered two different questions with one
            # `false` -- "not due" (vanilla should collect it) and "busy" (it
            # must not be collected at all) -- and the pickup mixin returned
            # without cancelling in both cases. So every item touched during
            # an open challenge was granted untested: mine a vein, answer for
            # the first ore, pocket the rest for free. Nothing logged it and
            # nothing looked wrong, which is how it survived to the first
            # recorded episode.
            OTHER = "minecraft:gold_ingot"
            failures_before = len(failures)

            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)

            mark = log_size(SERVER_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
            ok, mark, line = tail_wait(
                SERVER_LOG, r"challenge \d+ begun for .*", 15, mark)
            print(f"  server: {line}" if ok else "  !! no challenge raised")
            if not ok:
                failures.append("hold: the first pickup raised no challenge")

            # Drop a DIFFERENT item on the player while that word is pending.
            # Push-to-talk is on and nothing presses the key, so the challenge
            # stays open for the whole timeout -- plenty of room to assert in.
            print(f"  dropping {OTHER} while the challenge is open...")
            trigger_pickup(rcon, player, OTHER)
            time.sleep(2.0)

            got = count_in_inventory(rcon, player, OTHER)
            print(f"  inventory holds {got} x {OTHER} (want 0 -- it must NOT be granted)")
            if got != 0:
                failures.append(
                    f"hold: {OTHER} was collected untested during an open challenge")

            # ...and it must still exist as an entity rather than having been
            # eaten. Counting by killing is the harness's idiom.
            on_floor = killed_count(rcon.command(f"kill @e[type=item]"))
            print(f"  item entities still on the floor: {on_floor} (want >= 1)")
            if on_floor < 1:
                failures.append("hold: the held-back item vanished instead of waiting")

            if len(failures) == failures_before:
                print("\n  ok  one word at a time, and the queue is honest")

            rcon.command("kill @e[type=item]")
            print(f"\n{'=' * 70}")
            if failures:
                print(f"{len(failures)} FAILURE(S):")
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        if args.smith:
            scenario("SMITH  a netherite upgrade has to be earned",
                     "both click routes are challenged; an armour trim is not")

            def smith(mode: str, expect_challenge: bool, want_item: str) -> None:
                print(f"\n  -- /leximine testsmith {mode} --")
                # Each case starts clean: the mod skips a word learned a moment
                # ago, and a challenge still open refuses the next one.
                mod_command(rcon, player, "forget all")
                rcon.command(f"clear {player}")
                time.sleep(RETRY_GAP_SECONDS)

                mark = log_size(SERVER_LOG)
                out = mod_command(rcon, player, f"testsmith {mode}")
                print(f"  {out.strip()}")

                ok, _, line = tail_wait(
                    SERVER_LOG, r"challenge \d+ begun for .*", 12, mark)
                if expect_challenge:
                    print(f"  server: {line}" if ok else "  !! no challenge raised")
                    if not ok:
                        failures.append(f"smith {mode}: no challenge for a netherite upgrade")
                    elif want_item not in line:
                        failures.append(
                            f"smith {mode}: challenged the wrong item ({line.strip()})")
                else:
                    # The trim case. A challenge here would put the player's own
                    # netherite armour in limbo for the sake of a pattern.
                    if ok:
                        print(f"  !! challenged a trim: {line.strip()}")
                        failures.append(f"smith {mode}: an armour trim was challenged")
                    else:
                        print("  ok  no challenge, as intended")

                if expect_challenge and ok:
                    # Let it time out so the next case starts with limbo clear.
                    tail_wait(SERVER_LOG, r"challenge \d+ resolved: \w+", 40, mark)

            # Shift-click first: quickMove empties the stack before
            # onTakeOutput sees it, so this is the route that breaks.
            smith("quick", True, "netherite_pickaxe")
            smith("pickup", True, "netherite_pickaxe")
            smith("trim", False, "")

            print(f"\n{'=' * 70}")
            if failures:
                print(f"{len(failures)} FAILURE(S):")
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        if args.rig:
            scenario("RIG  an outcome loaded in advance, fired by a genuine miss",
                     "the rigged penalty lands on the next real failure, once")

            # Deliberately a tier 1 penalty on a tier 3 item (a diamond). It
            # tests two things at once:
            #
            #   - the rig fires at all, and ignores the item's own tier
            #   - the wheel follows the PENALTY's tier, not the item's
            #
            # The second is the one worth a test. The client resolves the name
            # it locks onto from (tier, index), so sending the item's tier here
            # would spin the five tier 3 names and lock onto "The Horde" while
            # the server quietly ran The Itch -- a wheel lying on camera, which
            # is the single worst way for this feature to fail.
            #
            # Tier 1 rather than tier 4 because the payload has to be survivable
            # and countable: three silverfish, no crater.
            rigged = "T1_THE_ITCH"

            rcon.command("kill @e[type=silverfish]")
            rcon.command(f"clear {player}")
            mod_command(rcon, player, "forget all")
            mod_command(rcon, player, "set punishment on")

            # The harness world runs with tiers off, which makes every item a
            # tier 1 noun -- including a diamond. Left that way the cross-tier
            # assertion below passes without ever crossing a tier, which is the
            # one thing it exists to check. Restored at the end of the scenario,
            # because every other scenario in this file assumes nouns.
            print(f"  {mod_command(rcon, player, 'set tiers true')}")
            time.sleep(RETRY_GAP_SECONDS)

            print(f"  {mod_command(rcon, player, f'rig {rigged}')}")
            print(f"  {mod_command(rcon, player, 'rig')}")

            mark_s = log_size(SERVER_LOG)
            mark_c = log_size(CLIENT_LOG)
            print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))

            # A diamond is tier 3 now, so the prompt is a sentence and the
            # clock it gets is doubled -- hence the generous waits here.
            ok, _, line = tail_wait(
                SERVER_LOG, r"challenge \d+ begun .* tier 3", 20, mark_s)
            print(f"  server: {line}" if ok else "  !! the diamond was not tier 3")
            if not ok:
                failures.append("rig: the diamond was not raised as tier 3, so "
                                "nothing below actually crosses a tier")

            # Push-to-talk is on and nothing presses the key, so the clock runs
            # out: a real miss, through the real path, with no audio involved.
            ok, _, line = tail_wait(
                SERVER_LOG, r"challenge \d+ resolved: fail", 45, mark_s)
            print(f"  server: {line}" if ok else "  !! the challenge never failed")
            if not ok:
                failures.append("rig: the challenge never failed, so nothing rolled")

            for label, log, pattern, timeout in (
                    ("the rig was not consumed", SERVER_LOG,
                     rf"lotto: rigged to {rigged} \(one shot, now spent\)", 10),
                    ("the roll did not land on the rigged penalty", SERVER_LOG,
                     rf"lotto: tier 3 \([^)]*\) -> {rigged}", 10),
                    # The fix: tier 1 on the wire, from a tier 3 item.
                    ("the wheel span the item's tier instead of the penalty's",
                     CLIENT_LOG, r"lotto wheel: spinning tier 1 over 5 name\(s\)", 10),
                    ("the wheel locked onto the wrong name", CLIENT_LOG,
                     r"lotto wheel: locked on THE ITCH", 10),
                    ("the payload never fired", SERVER_LOG,
                     rf"lotto: executing {rigged}", 10)):
                found, _, hit = tail_wait(log, pattern, timeout,
                                          mark_s if log is SERVER_LOG else mark_c)
                print(f"  {'ok  ' if found else 'FAIL'} {label}: "
                      + (hit.strip() if found else "no match"))
                if not found:
                    failures.append(f"rig: {label}")

            # At least one, not exactly three. The payload asks for three and
            # `spawnNear` needs somewhere to put them; this scenario does not
            # rebuild a floor and clear the air around the player the way
            # --lotto does, so near bedrock it legitimately places two. The
            # exact count is --lotto's job on prepared ground. What matters
            # here is that the rigged payload reached the world at all.
            seen = killed_count(rcon.command("kill @e[type=silverfish]"))
            print(f"  silverfish spawned: {seen} (want at least 1)")
            if seen < 1:
                failures.append("rig: the payload left no silverfish behind")

            # --- and now the take after it, which must roll honestly --------
            # The server runs at -PlexLottoChance=0 in this mode, so an
            # unrigged miss is guaranteed SAFE. That makes "spent" provable
            # rather than merely likely: without consuming the rig, this second
            # miss would spawn silverfish all over again.
            print("\n  -- the next miss, with nothing loaded --")
            mod_command(rcon, player, "forget all")
            rcon.command(f"clear {player}")
            time.sleep(RETRY_GAP_SECONDS)
            mark_s = log_size(SERVER_LOG)
            trigger_pickup(rcon, player, TEST_ITEM)
            ok, _, line = tail_wait(
                SERVER_LOG, r"lotto: tier \d+ \([^)]*\) -> \w+", 45, mark_s)
            print(f"  server: {line}" if ok else "  !! the second miss never rolled")
            if not ok:
                failures.append("rig: the second miss never rolled")
            elif "SAFE" not in line:
                failures.append(f"rig: the rig outlived its one shot ({line.strip()})")

            # --- off clears without firing ----------------------------------
            print("\n  -- /lex rig off --")
            mod_command(rcon, player, f"rig {rigged}")
            cleared = mod_command(rcon, player, "rig off")
            status = mod_command(rcon, player, "rig")
            print(f"  {cleared}")
            print(f"  {status}")
            if "cleared" not in cleared.lower():
                failures.append(f"rig: 'rig off' did not clear ({cleared.strip()})")
            if "nothing rigged" not in status.lower():
                failures.append(f"rig: still armed after 'off' ({status.strip()})")

            rcon.command("kill @e[type=silverfish]")
            # Back to how the world was found. The config file persists between
            # runs, so leaving tiers on here would quietly change what every
            # other scenario in this file is asking for.
            mod_command(rcon, player, "set tiers false")

            print(f"\n{'=' * 70}")
            if failures:
                print(f"{len(failures)} FAILURE(S):")
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        if args.only_trade:
            scenario("SCENARIO 11 only  a merchant wants to be asked properly",
                     "a sensible Spanish request opens the trade; anything else does not")
            wav_path = PROJECT / "run" / "testaudio" / "trade_retry.wav"
            if not wav_path.is_file():
                print("!! run scripts/make_trade_audio.py first")
                return 2
            stop_client()
            player = launch_client(f"-PlexFakeMic={wav_path}")
            if player is None:
                print("!! client never rejoined")
                return 2
            mod_command(rcon, player, "set timeout 16")
            mod_command(rcon, player, "set negotiate true")

            failures += run_trade_case(rcon, player, "trade (accepted)",
                                       expect_accepted=True)
            print(f"\n{'=' * 70}")
            if failures:
                for failure in failures:
                    print(f"  - {failure}")
                return 1
            print("ALL CHECKS PASSED")
            return 0

        # ---------------------------------------------------------------
        scenario("SCENARIO 1  nothing said, clock runs out",
                 "challenge raised, no speech, item destroyed")
        failures += run_pickup_case(rcon, player, "scenario 1",
                                    expect_success=False, expect_count=0)

        # ---------------------------------------------------------------
        scenario("SCENARIO 2  backend unreachable",
                 "lookup fails, client fails open, item granted")
        # Same client build, pointed at a closed port. Restarting it is the
        # only way to change the backend URL, which is a launch-time property.
        print("  restarting client against a dead backend port...")
        stop_client()
        player = launch_client("-PlexBackend=ws://127.0.0.1:59999/ws")
        if player is None:
            failures.append("scenario 2: client never rejoined")
        else:
            print(f"  rejoined as {player!r}")
            failures += run_pickup_case(rcon, player, "scenario 2",
                                        expect_success=True, expect_count=1)

        # ---------------------------------------------------------------
        # The speech path itself. A wav is streamed through the mod's audio
        # pipeline in place of the microphone, so the bytes travel the real
        # route: AudioCapture -> websocket -> faster-whisper -> fuzzy match.
        for label, wav, expect_success, expect_count in (
            ("SCENARIO 3  correct word spoken", "diamante.wav", True, 1),
            ("SCENARIO 4  wrong word spoken", "wrong.wav", False, 0),
        ):
            wav_path = PROJECT / "run" / "testaudio" / wav
            scenario(label, f"{wav} -> {'granted' if expect_success else 'destroyed'}")
            if not wav_path.is_file():
                failures.append(f"{label}: missing {wav_path}; "
                                f"run scripts/make_test_audio.ps1")
                continue

            print(f"  restarting client with fake mic {wav}...")
            stop_client()
            player = launch_client(f"-PlexFakeMic={wav_path}")
            if player is None:
                failures.append(f"{label}: client never rejoined")
                continue

            mark_client = log_size(CLIENT_LOG)
            failures += run_pickup_case(rcon, player, label,
                                        expect_success=expect_success,
                                        expect_count=expect_count)

            # The decisive assertion. Without it a "success" could just be the
            # fail-open path granting the item because the backend was never
            # reached -- which would make this test prove nothing about speech.
            ok, _, line = tail_wait(
                CLIENT_LOG, r"Speech verdict for challenge \d+: \w+ \([^)]*\), heard .*",
                10, mark_client)
            if ok:
                print(f"  client: {line.strip()}")
                if "heard \"\"" in line:
                    failures.append(f"{label}: backend heard nothing; audio never arrived")
            else:
                failures.append(f"{label}: no speech verdict logged -- "
                                f"the audio path was not exercised")

            # ---------------------------------------------------------
            # Same client, so these cost no extra startup. Both switch the
            # frequency off "always" to exercise the real learning rule.
            if wav == "wrong.wav":
                scenario("SCENARIO 4b  a missed word is asked again",
                         "frequency 10m, but a miss does not start that timer")
                mod_command(rcon, player, "set frequency 10m")
                mod_command(rcon, player, "forget all")
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                # Past the anti-spam retry gap left by the scenario above,
                # or the first pickup here is simply not challenged.
                time.sleep(RETRY_GAP_SECONDS)

                failures += run_pickup_case(rcon, player, "scenario 4b (first miss)",
                                            expect_success=False, expect_count=0)
                time.sleep(RETRY_GAP_SECONDS)
                print("  -- picking up the same item again --")
                failures += run_pickup_case(rcon, player, "scenario 4b (asked again)",
                                            expect_success=False, expect_count=0)
                mod_command(rcon, player, "set frequency always")

            if wav == "diamante.wav":
                scenario("SCENARIO 3b  a learned word is left alone",
                         "frequency 10m, say it right once, then no more asking")
                mod_command(rcon, player, "set frequency 10m")
                mod_command(rcon, player, "forget all")
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                time.sleep(RETRY_GAP_SECONDS)

                failures += run_pickup_case(rcon, player, "scenario 3b (learning it)",
                                            expect_success=True, expect_count=1)

                # Checked here, where a word has genuinely just been learned,
                # rather than at the end of the run where later scenarios have
                # deliberately wiped and missed things.
                listing = mod_command(rcon, player, "words")
                print(f"  words: {listing.strip()}")
                if "Diamante" not in listing:
                    failures.append("scenario 3b: learned word missing from /leximine words")

                time.sleep(RETRY_GAP_SECONDS)
                print("  -- picking up the same item again --")
                failures += expect_no_challenge(rcon, player, "scenario 3b",
                                                expect_count=2)

                print("  -- after /leximine forget all --")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)
                failures += run_pickup_case(rcon, player, "scenario 3b (after forgetting)",
                                            expect_success=True, expect_count=3)
                mod_command(rcon, player, "set frequency always")

        # ---------------------------------------------------------------
        # Real crafting, through onSlotClick, for BOTH take routes. An earlier
        # version of this test called the confiscation helper directly and
        # passed while shift-click crafting was never challenged at all --
        # quickMove does not go anywhere near the code that was hooked.
        scenario("SCENARIO 5  crafting raises a challenge",
                 "shift-click and plain click on a crafting result both ask")
        print("  restarting client with fake mic wrong.wav (so the craft is lost)...")
        stop_client()
        player = launch_client(
            f"-PlexFakeMic={PROJECT / 'run' / 'testaudio' / 'wrong.wav'}")
        if player is None:
            failures.append("scenario 5: client never rejoined")
        else:
            for mode in ("quick", "pickup"):
                label = f"scenario 5 ({mode} click)"
                print(f"  -- {mode} --")
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)

                mark_server = log_size(SERVER_LOG)
                print("  " + (mod_command(rcon, player, f"testcraft {mode}") or "(crafted)"))

                ok, mark_server, line = tail_wait(
                    SERVER_LOG, r"challenge \d+ begun for .*oak_planks.*", 15, mark_server)
                print(f"  server: {line}" if ok else f"  !! no challenge from a {mode} craft")
                if not ok:
                    failures.append(f"{label}: crafting raised no challenge")
                    continue
                if " x4" not in line:
                    failures.append(f"{label}: wrong count challenged ({line})")

                ok, _, line = tail_wait(SERVER_LOG, r"challenge \d+ resolved: fail", 25,
                                        mark_server)
                print(f"  server: {line}" if ok else "  !! not resolved")
                if not ok:
                    failures.append(f"{label}: challenge never resolved")

                planks = count_in_inventory(rcon, player, "minecraft:oak_planks")
                print(f"  inventory holds {planks} x oak_planks (want 0 - lost on a miss)")
                if planks != 0:
                    failures.append(f"{label}: {planks} planks kept after a failed challenge")

                # A miss should be followed by hearing the word said properly.
                # run/options.txt keeps master volume at 0.01 so this whole
                # path runs while staying inaudible on the test machine.
                ok, _, line = tail_wait(
                    CLIENT_LOG, r'Playing pronunciation of "[^"]+" \(\w+\)', 8,
                    log_size(CLIENT_LOG) - 4000)
                print(f"  client: {line.strip()}" if ok
                      else "  !! no spoken example after the miss")
                if not ok:
                    failures.append(f"{label}: no pronunciation played after a miss")

        # ---------------------------------------------------------------
        # The reported bug: two tries in one challenge were transcribed
        # together, so a good second attempt was dragged down by a bad first.
        scenario("SCENARIO 7  two attempts are scored separately",
                 "wrong word, pause, right word -> the second one wins")
        wav_path = PROJECT / "run" / "testaudio" / "two_attempts.wav"
        if not wav_path.is_file():
            failures.append("scenario 7: run scripts/make_two_attempts.py first")
        else:
            print("  restarting client with fake mic two_attempts.wav...")
            stop_client()
            player = launch_client(f"-PlexFakeMic={wav_path}")
            if player is None:
                failures.append("scenario 7: client never rejoined")
            else:
                # The clip is 4.4s; the default 6s leaves no room for the
                # backend's final pass after it finishes playing.
                mod_command(rcon, player, "set timeout 14")
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)

                mark_client = log_size(CLIENT_LOG)
                failures += run_pickup_case(rcon, player, "scenario 7",
                                            expect_success=True, expect_count=1)

                # The first try must appear as its own scored attempt. If the
                # two were merged there would be no attempt line at all.
                ok, _, line = tail_wait(
                    CLIENT_LOG, r'attempt 1: heard "[^"]*" at \d+', 10, mark_client)
                print(f"  client: {line.strip()}" if ok
                      else "  !! first try was never scored on its own")
                if not ok:
                    failures.append("scenario 7: attempts were merged, not separated")
                elif "Diamante" in line:
                    failures.append(
                        "scenario 7: attempt 1 already contains the second try "
                        "- the pause did not split them")

                mod_command(rcon, player, "set timeout 6")

        # ---------------------------------------------------------------
        scenario("SCENARIO 8  how much you say scales with the item",
                 "stone pickaxe -> noun, iron sword -> full name, diamond -> sentence")
        if player is not None:
            mod_command(rcon, player, "set tiers true")
            for item, want_tier, expect in (
                ("minecraft:stone_pickaxe", 1, "Pico"),
                ("minecraft:iron_sword", 2, "Espada de hierro"),
                ("minecraft:diamond_sword", 3, "Estoy fabricando"),
            ):
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)

                mark_server = log_size(SERVER_LOG)
                mark_client = log_size(CLIENT_LOG)
                mod_command(rcon, player, f"test {item}")

                ok, _, line = tail_wait(SERVER_LOG,
                                        r"challenge \d+ begun for .*tier \d.*", 12, mark_server)
                if not ok:
                    failures.append(f"scenario 8: no challenge for {item}")
                    continue
                print(f"  server: {line.split(': ', 1)[-1]}")
                if f"tier {want_tier}" not in line:
                    failures.append(
                        f"scenario 8: {item} classified as the wrong tier ({line})")

                # What the backend actually asked for.
                ok, _, asked = tail_wait(
                    CLIENT_LOG, r"Speech verdict for challenge \d+: .*", 25, mark_client)
                prompt = tail_wait(SERVER_LOG, r"challenge \d+ resolved: \w+", 10,
                                   mark_server)[2]
                print(f"  {prompt}")

            # One long prompt on screen, captured, because a sentence has to
            # fit a panel built for a single word.
            mod_command(rcon, player, "set timeout 30")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)
            mod_command(rcon, player, "test minecraft:diamond_sword")
            time.sleep(2.5)
            shot = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", str(PROJECT / "scripts" / "capture_hud.ps1"),
                 "-Out", "run\\hud_tier3.png"],
                cwd=str(PROJECT), capture_output=True, text=True, check=False)
            print("  " + (shot.stdout.strip() or shot.stderr.strip()))
            mod_command(rcon, player, "set timeout 6")
            mod_command(rcon, player, "set tiers false")

        # ---------------------------------------------------------------
        scenario("SCENARIO 9  a miss rolls on the right table",
                 "the item's tier picks which five penalties are in play")
        # What this used to assert -- nausea and a zombie on every tier-3 miss
        # -- is gone, because the flat penalty is gone. Which payload actually
        # lands is now a 1-in-5 draw and is covered exhaustively by --lotto.
        # The invariant left here is the one that connects the two halves: the
        # tier the item was classified as is the tier the wheel draws from.
        # It holds at 0% chance, because a safe roll is still logged, which is
        # what keeps this scenario deterministic in the main suite.
        if player is not None:
            mod_command(rcon, player, "set tiers true")
            mod_command(rcon, player, "set punishment on")

            for item, want_tier, label in (
                ("minecraft:stone_pickaxe", 1, "noun"),
                ("minecraft:iron_sword", 2, "full name"),
                ("minecraft:diamond_sword", 3, "sentence"),
                ("minecraft:elytra", 4, "legendary"),
            ):
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                mod_command(rcon, player, "forget all")
                time.sleep(RETRY_GAP_SECONDS)

                mark_server = log_size(SERVER_LOG)
                mod_command(rcon, player, f"test {item}")
                # Deliberately not advancing the mark. The roll is logged by
                # Punishments.apply, which LimboManager calls *before* it
                # writes "resolved", so searching from after the resolution
                # looks past the very line this scenario is about.
                ok, _, _ = tail_wait(
                    SERVER_LOG, r"challenge \d+ resolved: fail", 40, mark_server)
                if not ok:
                    failures.append(f"scenario 9: {item} never failed")
                    continue

                ok, _, line = tail_wait(
                    SERVER_LOG, r"lotto: tier \d+ \([^)]*\) -> \w+", 10, mark_server)
                print(f"  {item:28} {line}" if ok else f"  !! {item} did not roll")
                if not ok:
                    failures.append(f"scenario 9: {item} raised no lotto roll")
                elif f"tier {want_tier} ({label})" not in line:
                    failures.append(
                        f"scenario 9: {item} rolled on the wrong table ({line})")

            # And off means off.
            mod_command(rcon, player, "set punishment none")
            rcon.command(f"clear {player}")
            mod_command(rcon, player, "forget all")
            time.sleep(RETRY_GAP_SECONDS)
            mark_server = log_size(SERVER_LOG)
            mod_command(rcon, player, "test minecraft:diamond_sword")
            tail_wait(SERVER_LOG, r"challenge \d+ resolved: fail", 40, mark_server)
            rolled = tail_wait(SERVER_LOG, r"lotto: tier", 5, mark_server)[0]
            print(f"  punishment none -> rolled anyway: {rolled}")
            if rolled:
                failures.append("scenario 9: 'none' still rolled the lotto")

            mod_command(rcon, player, "set punishment on")
            mod_command(rcon, player, "set tiers false")

        # ---------------------------------------------------------------
        scenario("SCENARIO 10  a chest asks about a word you keep missing",
                 "using a container raises a review drawn from the backend's history")
        if player is not None:
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)

            mark_server = log_size(SERVER_LOG)
            mark_client = log_size(CLIENT_LOG)
            print("  " + (mod_command(rcon, player, "testreview") or "(used)"))

            ok, mark_server, line = tail_wait(SERVER_LOG, r"review \d+ for .*", 12,
                                              mark_server)
            print(f"  server: {line}" if ok else "  !! no review raised by the chest")
            if not ok:
                failures.append("scenario 10: using a container raised no review")
            else:
                # The word must come from the backend's own history, not from
                # the server, which has no idea what keeps going wrong.
                ok, _, verdict = tail_wait(
                    CLIENT_LOG, r"Speech verdict for challenge \d+: .*", 30, mark_client)
                print(f"  client: {verdict.strip()}" if ok
                      else "  !! client never answered the review")
                if not ok:
                    failures.append("scenario 10: review never resolved")
                elif 'heard ""' in verdict:
                    failures.append("scenario 10: review ran with no audio")

                # Nothing is at stake in a review: a miss must not cost items.
                held = count_in_inventory(rcon, player, TEST_ITEM)
                if held != 0:
                    failures.append(f"scenario 10: review altered inventory ({held})")

        # ---------------------------------------------------------------
        scenario("SCENARIO 11  a merchant wants to be asked properly",
                 "a sensible Spanish request opens the trade; anything else does not")

        if player is not None:
            # A sentence plus a model call needs room; TradeManager already
            # doubles this, and the fixture itself runs 4.2s.
            mod_command(rcon, player, "set timeout 16")
            mod_command(rcon, player, "set negotiate true")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")

            # The current client's fake mic says "elephant sandwich", then
            # "diamante" -- neither is a way to ask anyone for bread.
            print("  -- nothing like a request --")
            failures += run_trade_case(rcon, player, "scenario 11 (refused)",
                                       expect_accepted=False)

            wav_path = PROJECT / "run" / "testaudio" / "trade_retry.wav"
            if not wav_path.is_file():
                failures.append("scenario 11: run scripts/make_trade_audio.py first")
            else:
                # Deliberately a false start followed by the real request: a
                # refusal partway through must not end the negotiation, the
                # same way a missed word does not end a word challenge.
                print("  -- a false start, then 'Quiero comprar pan, por favor' --")
                stop_client()
                player = launch_client(f"-PlexFakeMic={wav_path}")
                if player is None:
                    failures.append("scenario 11: client never rejoined")
                else:
                    mod_command(rcon, player, "set timeout 16")
                    failures += run_trade_case(rcon, player, "scenario 11 (accepted)",
                                               expect_accepted=True)

            if player is not None:
                rcon.command("kill @e[type=villager]")
                mod_command(rcon, player, "set timeout 6")

        # ---------------------------------------------------------------
        scenario("SCENARIO 12  a review nobody answers does not jam the feature",
                 "an orphaned review times out server-side and the next one still fires")
        if player is not None:
            # Picking something up while a review is on screen is the
            # reachable way to orphan one: the client only tracks a single
            # challenge, so it switches to the item and the review's verdict
            # is never sent. Before the server grew a deadline for these, that
            # entry sat in `pending` for the rest of the server's life -- and
            # because an open review makes every later one stand aside, doors
            # and chests quietly stopped asking that player anything at all.
            mod_command(rcon, player, "set reviewCooldown 0")
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)

            mark_server = log_size(SERVER_LOG)
            mod_command(rcon, player, "testreview")
            ok, mark_server, line = tail_wait(SERVER_LOG, r"review (\d+) for .*", 12,
                                              mark_server)
            print(f"  server: {line}" if ok else "  !! no review raised")
            if not ok:
                failures.append("scenario 12: no review to orphan")
            else:
                first = re.search(r"review (\d+)", line).group(1)

                # Supersede it client-side.
                print("  " + (trigger_pickup(rcon, player, TEST_ITEM) or "(summoned)"))
                ok, mark_server, line = tail_wait(
                    SERVER_LOG, r"challenge \d+ begun for .*", 12, mark_server)
                print(f"  server: {line}" if ok
                      else "  !! the pickup did not supersede it")
                if not ok:
                    failures.append("scenario 12: nothing superseded the review")

                ok, mark_server, line = tail_wait(
                    SERVER_LOG, rf"review {first} timed out with no verdict", 25, mark_server)
                print(f"  server: {line}" if ok
                      else f"  !! review {first} was never given up on")
                if not ok:
                    failures.append(
                        "scenario 12: the orphaned review never timed out, so "
                        "reviews are now off for this player")

                # The assertion that matters: the feature still works.
                rcon.command(f"clear {player}")
                rcon.command("kill @e[type=item]")
                time.sleep(RETRY_GAP_SECONDS)
                mark_server = log_size(SERVER_LOG)
                mod_command(rcon, player, "testreview")
                ok, _, line = tail_wait(SERVER_LOG, r"review (\d+) for .*", 12, mark_server)
                print(f"  server: {line}" if ok else "  !! no second review")
                if not ok:
                    failures.append("scenario 12: reviews stopped firing after an orphan")
                elif re.search(r"review (\d+)", line).group(1) == first:
                    failures.append("scenario 12: the same review id came back")

            mod_command(rcon, player, "set reviewCooldown 300")

        # ---------------------------------------------------------------
        scenario("SCENARIO 6  missed words are listed too",
                 "/leximine words all shows what is still being got wrong")
        if player is not None:
            # Establishes its own state rather than inheriting whatever the
            # scenario above happened to leave behind. Three separate runs
            # have now "failed" here purely because of ordering.
            rcon.command(f"clear {player}")
            rcon.command("kill @e[type=item]")
            time.sleep(RETRY_GAP_SECONDS)
            run_pickup_case(rcon, player, "scenario 6 (seeding a word)",
                            expect_success=False, expect_count=0)

            listing = mod_command(rcon, player, "words all")
            for row in listing.splitlines():
                print(f"  | {row}")
            # Asserts the store round-tripped a real word, rather than that a
            # particular one is still missing: which words are outstanding
            # depends on the order the scenarios above happen to run in.
            if "Words attempted" not in listing or "(0)" in listing:
                failures.append("scenario 6: /leximine words all listed nothing")

            print()
            for row in mod_command(rcon, player, "settings").splitlines():
                print(f"  | {row}")

        # ---------------------------------------------------------------
        print(f"\n{'=' * 70}")
        if failures:
            print(f"{len(failures)} FAILURE(S):")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("ALL CHECKS PASSED")
        return 0

    finally:
        if rcon:
            rcon.close()
        print(f"\nreview history: {reset_review_history()}")
        if not args.keep_running:
            print("\nshutting down...")
            for process in (client_holder[0], server):
                if process:
                    kill_tree(process)
            kill_minecraft_jvms()


if __name__ == "__main__":
    sys.exit(main())


