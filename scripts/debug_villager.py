"""Find out why a summoned villager has no offers.

Boots the dedicated server only -- no client, no speech -- and tries each
summon spelling over RCON, printing what the server says to each one. A full
e2e run takes ten minutes to reach the villager scenario; this takes under
two.

    python scripts/debug_villager.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from e2e_test import (  # noqa: E402
    PROJECT, SERVER_LOG, RCON_HOST, RCON_PASSWORD, RCON_PORT,
    Rcon, kill_minecraft_jvms, kill_tree, spawn, tail_wait,
)

OFFER = ('Offers:{Recipes:[{buy:{id:"minecraft:emerald",Count:1b},'
         'sell:{id:"minecraft:bread",Count:6b},maxUses:99,rewardExp:0b,xp:0}]}')

VARIANTS = [
    ("profession + offers, unqualified ids",
     '{VillagerData:{profession:"farmer",level:2,type:"plains"},' + OFFER + '}'),
    ("profession + offers, namespaced ids",
     '{VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},'
     + OFFER + '}'),
    ("offers only, no villager data", "{" + OFFER + "}"),
    ("profession only, let the game build the offers",
     '{VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"}}'),
    ("bare villager", None),
    # 0 -60 0 is inside the ground in this world, and a living mob inside a
    # block suffocates in well under a second -- which is exactly how the
    # villager scenario failed while reporting only "no merchant within 24
    # blocks". Invulnerable settles it wherever the test happens to put it.
    ("invulnerable, no AI, with offers",
     '{Invulnerable:1b,NoAI:1b,PersistenceRequired:1b,'
     'VillagerData:{profession:"minecraft:farmer",level:2,type:"minecraft:plains"},'
     + OFFER + '}'),
]


def main() -> int:
    kill_minecraft_jvms()
    time.sleep(2)
    if SERVER_LOG.is_file():
        SERVER_LOG.unlink()

    server = spawn("runServer", PROJECT / "run" / "server-stdout.log")
    rcon = None
    try:
        print("starting dedicated server...")
        ok, _, _ = tail_wait(SERVER_LOG, r"Done \([\d.]+s\)", timeout=240)
        if not ok:
            print("!! server never finished starting")
            return 2
        rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)

        # No player is connected, so the chunk has to be held open explicitly
        # -- an entity summoned into an unloaded chunk is not in the live
        # index and no selector will ever see it.
        print(f"  forceload: {rcon.command('forceload add 0 0 0 0').strip()}")
        print(f"  spawn:     {rcon.command('setworldspawn 0 -60 0').strip()}")
        time.sleep(2)

        # Does ANY entity survive a summon here? If a pig vanishes too, the
        # villager NBT is not the problem.
        print("\n--- is it villagers, or is it every entity?")
        for kind in ("pig", "villager", "armor_stand"):
            rcon.command(f"kill @e[type={kind}]")
            print(f"  summon {kind:12} {rcon.command(f'summon minecraft:{kind} 0 -60 0').strip()}")
            print(f"    immediately:  {rcon.command(f'data get entity @e[type={kind},limit=1] UUID').strip()[:90]}")
            time.sleep(1.5)
            print(f"    1.5s later:   {rcon.command(f'data get entity @e[type={kind},limit=1] UUID').strip()[:90]}")
            print(f"    positioned:   {rcon.command(f'execute positioned 0 -60 0 run data get entity @e[type={kind},limit=1,sort=nearest,distance=..20] UUID').strip()[:90]}")
            rcon.command(f"kill @e[type={kind}]")

        print(f"\n  forceload query: {rcon.command('forceload query').strip()}")
        print(f"  block at 0 -60 0: {rcon.command('execute if block 0 -60 0 air run say air').strip() or 'not air'}")
        print(f"  block at 0 -61 0: {rcon.command('execute if block 0 -61 0 air run say air').strip() or 'not air'}")

        for label, nbt in VARIANTS:
            print(f"\n--- {label}")
            rcon.command("kill @e[type=villager]")
            time.sleep(0.5)
            command = "summon minecraft:villager 0 -60 0" + (f" {nbt}" if nbt else "")
            print(f"  > {command}")
            print(f"  < {rcon.command(command).strip() or '(no reply)'}")
            time.sleep(1.0)

            # "Killed N entities" at the end of the loop is the honest count
            # of what actually exists; a selector that matches nothing and an
            # entity with no offers give the same answer to `data get`.
            print(f"  selector:  {rcon.command('data get entity @e[type=villager,limit=1] UUID').strip()[:120] or '(nothing)'}")
            found = rcon.command("data get entity @e[type=villager,limit=1] Offers")
            print(f"  offers:    {found.strip()[:300] or '(nothing)'}")
            print(f"  alive:     {rcon.command('kill @e[type=villager]').strip()}")

        rcon.command("kill @e[type=villager]")
        return 0
    finally:
        if rcon:
            rcon.close()
        kill_tree(server)
        kill_minecraft_jvms()


if __name__ == "__main__":
    sys.exit(main())
