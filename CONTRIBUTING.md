# Contributing

Two repositories, and most contributions belong in the other one:

| | where |
|---|---|
| A sentence reads oddly in your language | **[lingua-stt-backend](https://github.com/optumesto/lingua-stt-backend/blob/main/CONTRIBUTING.md)** — sentences live in `app/phrases.py` |
| Speech is misheard, the backend will not start, the GPU is unused | **[lingua-stt-backend](https://github.com/optumesto/lingua-stt-backend/issues)** |
| Anything inside Minecraft — gates, the HUD, punishments, installing | here |

To report a sentence you do not need to know what is wrong with it. Press **N**
while the prompt is up; it writes the exact line to
`config/leximine-reports.txt` and prints it in chat. Paste that line.

---

## Building it

```powershell
.\gradlew.bat build        # jar lands in build/libs/
.\gradlew.bat runClient    # launches 1.20.4 with the mod loaded
```

Any **JDK 21** works, taken from `JAVA_HOME` or from PATH. **The first build
takes about 16 minutes** — Loom downloads and decompiles Minecraft — and
seconds after that.

**Do not bump the versions off fabricmc.net/develop.** That page serves the
current line; this mod targets 1.20.4, which needs Loom 1.6 and a Java 21 JVM.
README's *Why these versions* has the full reasoning, including why Yarn
mappings are used instead of Mojang's.

**Do not commit a machine path.** `org.gradle.java.home` was pinned in
`gradle.properties` once and Gradle refuses to start when that path does not
exist, so every other clone failed before reading a task. If you need to pin a
JDK, do it in your own `~/.gradle/gradle.properties`.

## The one rule that is not negotiable: fail open

Every failure path **grants the item**. Backend not running, no translation for
the item, no working microphone, socket dropped mid-challenge — the player gets
the thing and a message, never a confiscation and never a block.

A change that can leave a player unable to play because a server or a
microphone is down is a bug here, whatever else it fixes. Being handed a free
diamond is the better failure.

## Layout

```
src/main/      common    -- loaded on client AND dedicated server
src/client/    client    -- audio, HUD, keybinds, the socket to the backend
src/main/.../mixin/      -- the pickup, crafting and smithing hooks
src/main/.../limbo/      -- what a miss costs: penalties and the lotto
```

Anything that touches the microphone, the screen or a keybind belongs in
`src/client/` — putting it in `src/main/` crashes a dedicated server, which is
the usual first mistake.

## Testing a change

CI builds the jar; it cannot play the game. The real test harness is a
dedicated server plus an auto-connecting client, driven over RCON:

```
python scripts\e2e_test.py                 # every scenario, asserts, exits
python scripts\e2e_test.py --only-trade    # just the merchant, ~2 minutes
python scripts\e2e_test.py --lotto         # all five punishments, in the world
python scripts\e2e_test.py --hud-shot      # one long challenge -> run\hud.png
```

Run the scenario your change touches and say in the pull request what it
printed. A screenshot is better than a description for anything on screen —
several bugs here looked correct in the code and wrong in the frame.

## Pull requests

- One change per pull request, and say what you ran.
- Match the surrounding style rather than reformatting around your change.
- Keep comments to the ones that explain *why*; the repository documents
  reasoning, not syntax.
- **Selling a Minecraft mod is prohibited by Mojang's EULA**, and that applies
  to forks of this one. The MIT licence does not override it.
