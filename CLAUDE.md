# Leximine mod — operating rules

Minecraft Fabric mod: picking up / crafting / trading an item holds it in limbo
until the player pronounces its name in the target language. Pairs with the
`leximine-backend` repo, which must sit beside this one as `..\leximine-backend`.

## Prime directive: fail open

Every failure path **grants the item**. Backend down, mic dead, model loading,
socket timeout — the player gets the item. From CONTRIBUTING.md: "A change that
can leave a player unable to play because a server or a microphone is down is a
bug here, whatever else it fixes." Never gate an action that can strand someone
(this is why the Nether *exit* side is ungated). When a test passes because the
code failed open, that is not a pass — speech scenarios must also assert a
non-empty transcript.

## Build

- `.\gradlew.bat build` — jar into `build/libs/`. **First build ~16 min** (Loom
  decompiles Minecraft). Don't assume it hung.
- `.\gradlew.bat runClient` — ordinary client; the only place the language picker
  appears (title screen).
- `.\gradlew.bat runClientAuto` — hardcodes `--quickPlayMultiplayer 127.0.0.1:25565`,
  so it can **never** show the title screen.
- `.\gradlew.bat runServer` — runs in `run/server`.
- CI: `sh ./gradlew build --no-daemon --stacktrace`, Temurin JDK 21.

**JDK 21 runs Gradle; code compiles to release 17.** Both required.

### Version pins — all in `gradle.properties`, nowhere else

MC `1.20.4` · Fabric loader `0.20.1` · Fabric API `0.97.3+1.20.4` (last for 1.20.4)
· Yarn `1.20.4+build.3` · Loom `1.6-SNAPSHOT` · Gradle `8.6`.

- **Never copy versions off fabricmc.net/develop.** It serves Loom 1.19, which
  needs a Java 25 JVM; MC 1.20.4 predates Java 25 by two years. The fix for a
  Loom/Java mismatch is an *older Loom*, not a newer Java.
- **Never commit `org.gradle.java.home`.** Gradle refuses to start if the path is
  absent, so pinning it broke every other clone and CI before any task ran. Pin
  it privately in `%USERPROFILE%\.gradle\gradle.properties`.
- `org.gradle.configuration-cache=false` is deliberate — Loom 1.6 can't do it.
- Yarn, not Mojang mappings: the central method is `PlayerInventory.insertStack`.

### Run properties

`-Plex*` gradle props map to `-Dleximine.*` system props, **per run config** in
`build.gradle`. Server: `lexTimeout` / `lexFrequency` / `lexLanguage` /
`lexLottoChance` / `lexPenalty`. Client: `lexOpenScreen`. ClientAuto: `lexBackend` /
`lexFakeMic` / `lexPushToTalk` / `lexStream`. A client-only prop is not settable
via the server's `/leximine` command.

## Source-set split — gets you a crash, not a warning

- `src/main/java/dev/mettle/leximine/` — common; loads on client **and dedicated
  server**. Subpackages `mixin/ limbo/ config/ net/ review/ progress/ end/ nether/`.
- `src/client/java/.../client/` — client only: audio, HUD, keybinds, websocket.

Anything touching mic, screen or keybind **must** live in `src/client`, or a
dedicated server dies. `splitEnvironmentSourceSets()` makes most of these a
compile error — but not all, so don't rely on it.

## The crafting hook (hard-won)

One challenge **per craft, not per item**. The two take-routes share almost no
code: plain click goes `tryTakeStackRange` to `takeStack` to `onTakeItem`;
shift-click goes `CraftingScreenHandler.quickMove` to `insertItem` and **never
calls `takeStack`**.

The single correct hook is the slot's `amount` field read **at the head of
`onCrafted`**. The `ItemStack` handed to `onTakeItem` has already been emptied —
its count is a lie. Hooking `takeStack` silently misses all shift-click crafting,
and an earlier unit test that called the confiscation helper directly passed while
shift-click crafting was completely broken. Test through real `onSlotClick` calls.

`/leximine` requires permission level 2 — a passed challenge grants an item, so
without the gate the command is an item duplicator.

## Testing: `python scripts\e2e_test.py`

Boots `runServer` + `runClientAuto` and drives the game over RCON. 12 scenarios,
~10 min full. Flags: `--only-trade` (~2 min), `--lotto --haggle --rig --endgate
--nethergate --streak --hold --smith --hud-shot --trade-shot --ui-tour
--keep-running --lang es_es --shot-item minecraft:diamond_sword`.

- **One run at a time.** OS file lock at `run/.e2e.lock`. A run's first act is
  `kill_minecraft_jvms()`, so a second run kills the first's server and surfaces
  much later as a socket error that reads like a product bug.
- **RCON password is `lex`** (hardcoded at `e2e_test.py:64`, port 25575) and must
  match `run/server/server.properties`. A rename once desynced these and produced
  `rcon auth rejected` with nothing pointing at the cause.
- **`run/options.txt` must exist** with `onboardAccessibility:false`, else the
  accessibility onboarding screen blocks quickPlay forever. Harness exits 2.
- **Kill JVMs by matching `quickPlayMultiplayer`, never `devlaunchinjector`** —
  the latter matches the server too.
- **Write `eula.txt` / `server.properties` without a BOM.** PS 5.1
  `Set-Content -Encoding utf8` adds one; Java's `Properties` then reads the BOM as
  part of the first key and silently ignores `online-mode=false`.
- **`spawn-npcs=true` required**, or villagers live exactly one tick, unlogged.
  Six `--haggle` runs were lost to a plausible wrong diagnosis here. Villager
  profession must be set but offers cleared (a farmer's first offer sells emeralds).
- Assertions read real game state (`clear <player> <item> 0`), not log lines.
- Timing constants mirror Java constants on purpose: `TRADE_COOLDOWN_SECONDS = 8`
  (= `TradeManager.REFUSAL_COOLDOWN_MILLIS`), `RETRY_GAP_SECONDS = 4.0` (must stay
  above `LimboManager.RETRY_GAP_MILLIS`).
- Wait on a log line, not a sleep. A fixed 3.6 s sleep produced a `--trade-shot`
  screenshot that looked like a broken feature.
- Backend must be running for speech/trade scenarios (`/health` on port 8760).

## Coverage numbers are generated, never written

`coverage_report.py` lives in the **backend** repo
(`Z:\leximine-backend\scripts\coverage_report.py`), not here. It measures through
the same call the server makes. Current: **French 89.7% of 2,116 · German 83.3%**.

Those percentages appear in `README.md` (~line 758) and `CHANGELOG.md:35-44`.
**Never hand-edit or hand-recompute them** — run the script and paste. Earlier
hand-measured figures were non-reproducible, and the CHANGELOG names the
non-reproducibility itself as "the actual defect". The 2,116 denominator is a
documented floor (~100 of those are UI strings).

## Release: `scripts\make_release.ps1 -Zip`

- **Version lives only in `gradle.properties`** (`^version=`). `fabric.mod.json`
  uses `${version}` via `processResources`. CHANGELOG.md is the only other edit.
- Needs the backend sibling (`..\leximine-backend`, falls back to the
  pre-2026-11-05 `..\lingua-stt-backend`) or it throws.
- Must pass `--project-dir $root` to gradlew; cwd is not enough.
- **Asset is `Leximine.zip`, deliberately NOT version-stamped**, so
  `/releases/latest/download/Leximine.zip` is a permanent URL. (v0.1.0 shipped as
  `Leximine-0.1.0.zip` — that was the exception, not the pattern.)
- The verify gate asserts 15 required paths and asserts **absent**: `backend\.venv`
  (2.3 GB), `backend\models` (1.9 GB — relative symlinks that Windows zip tools
  flatten to 0-byte files, giving a release that looks complete and dies on model
  load), `backend\.env`, `backend\data`, any `backend\lang\*.json` (Mojang strings,
  EULA issue) — and asserts **every `.cmd` is CRLF**. `check-setup.cmd` shipped LF
  in 0.1.0; `.gitattributes` only fixes fresh clones, and `git status` stays silent
  because it compares normalised content.
- `.cmd` files are written with `[System.IO.File]::WriteAllText(..., ASCII)` and
  explicit CR LF escapes — not `Set-Content`.
- After releasing: **download the asset from the public URL and verify it.** That
  is the standing procedure, not optional.
- A jar/backend/docs change means a version bump and a new release — ask about
  shipping rather than leaving it built but unreleased.

## Backend coupling

- Default `ws://127.0.0.1:8760/ws`, hardcoded in **three** places:
  `config/LexConfig.java:105` and `:138` (reset default), and
  `client/LexClientConfig.java:28`. Override via `-Dleximine.backend=`.
- HTTP for tooling: `/health`, `/pronounce` (16 kHz mono wav), and
  **`POST`** `/review/reset?lang=` (POST on purpose — a GET would wipe progress
  from a mistyped URL).
- **Target language is per challenge — neither a restart nor a rebuild.**
  `main.py:340` reads `msg["lang"]` off the wire and `lang.py` lazy-extracts it.
  Both this repo's README and the backend's `PROTOCOL.md` (lines ~185-186) claim
  changing language needs a server restart. **They are wrong.** `.env`'s
  `TARGET_LANG` is only the fallback when the mod sends no `lang`.
- Audio is **binary** frames: raw 16 kHz mono s16le, no RIFF header, 20-100 ms
  chunks. Everything else is JSON text. Exactly **one** terminal `result` per
  challenge; only `status` decides the item, `reason` just explains.
- Start the backend first: `cd ..\leximine-backend; .\run.ps1`.
- Env prefix moved `LINGUA_*` to `LEXIMINE_*` on 2026-11-05; old names are still
  honoured, because an ignored key reverts a setting to its default *silently*.
- `BackendProbe` reads the `hello` frame at startup to catch three failures early:
  missing websocket jar on the runtime classpath, backend not running, and backend
  up with the model still loading.

## Known gaps / don'ts

- **Targets MC 1.20.4 while the live game is on 26.x.** This is the single biggest
  limiter on installs. Any "why won't it install" question starts here.
- **Nothing is downloading it** — 33 all-time release downloads, ~2 outside humans
  ever found the repo. The cause is distribution, not the code: GitHub Releases is
  the only channel and it is listed on no mod host. See
  `Z:\TOOLING_UPGRADE_PROGRESS.md` and the open-threads memory.
- FR/DE fall back silently to the bare noun when item gender is unknown. FR
  `Diamant` falls back, `Bloc de diamant` does not. DE `Schild` is refused on
  purpose (*der* shield vs *das* sign).
- On a dedicated server, `stream` / `pushToTalk` / `pronunciation` are client-side.
  On Peaceful, 9 of the 20 penalties do nothing.
- `fabric.mod.json` omits the icon field rather than pointing at a missing file.
- Selling a Minecraft mod or a fork violates Mojang's EULA; MIT does not override it.
- `.gitleaks.toml` allowlists only the `$FABRIC_API_SHA1` assignment (a public
  Modrinth checksum), scoped to the assignment, not the file.
