# Installing Leximine

Leximine will not let you keep an item until you say its name out loud, in the
language you are learning. It is free, and the speech recognition runs on your
own machine: **your microphone audio is never uploaded and never written to
disk** — it is decoded in memory and discarded.

That is not the same as "offline". See
[What talks to the internet](#what-talks-to-the-internet) below for the five
things that do.

It comes in two halves and you need both:

| | What it is | Where it runs |
|---|---|---|
| **The mod** | a Fabric mod for Minecraft 1.20.4 | in Minecraft |
| **The speech backend** | a small local server that listens and judges | a window you leave open |

### What you need, and what it costs you

| | |
|---|---|
| **Operating system** | Windows 10 or 11. The only platform this has been played on — see the note below |
| **Game** | Minecraft **Java Edition 1.20.4**, with Fabric Loader. Not Bedrock, not a newer version |
| **Fabric API** | fetched for you on first install (~2 MB from Modrinth) |
| **Java** | whatever the Minecraft launcher already uses. You do not install one |
| **Python** | none needed. If you have no 3.11+, setup fetches the official 11 MB embeddable build |
| **Graphics card** | an NVIDIA GPU is wanted, not required. Without one it drops to the processor by itself |
| **Microphone** | any |
| **Network** | needed for setup, and for speaking a word back to you. Your voice is never uploaded |

| Size | |
|---|---|
| **The download** | `Leximine.zip`, **483 KB** (the mod jar is 310 KB of it) |
| **After first-run setup** | about **2.8 GB** — 2.3 GB of Python libraries and a 461 MB speech model, both fetched once |
| **Free disk space to have** | **5 GB**, which leaves room to spare |
| **VRAM** | the model's weights are 461 MB; any modern card has room |

Setup is one click but it is not quick: it downloads 2.8 GB the first time.
Everything after that is local and instant.

The backend does the hard part — speech recognition on your GPU — which is why
it is a separate program rather than something inside Minecraft.

---

## Before you start

**Required**

- **Minecraft: Java Edition**, version **1.20.4**. Not Bedrock, and not a
  newer version — mods are built against one version at a time.
- **Windows 10 or 11.** Every launcher here is `.cmd` or `.ps1`, so the
  one-click setup is Windows-only, and Windows is the only platform this has
  actually been played on.

  The parts underneath are not Windows-specific: the mod is an ordinary
  Fabric jar, and CI builds the mod and runs the backend's tests on Linux on
  every push. So *Doing it manually* at the bottom should work on Linux with
  the paths changed (`.venv/bin/pip` for `.venv\Scripts\pip`). Two things
  are genuinely missing off Windows, both minor: speaking the word back falls
  to the online voice only, because the offline fallback is Windows SAPI; and
  on macOS there is no CUDA, so recognition runs on the processor — see the
  GPU note below. Nobody has run it end to end on either, so treat it as
  likely rather than supported.
- **About 5 GB of free disk space**: 2.3 GB of Python libraries, 0.5 GB for
  the speech model, and room to spare.
- **A microphone.** Any one. A headset is better than a laptop's built-in mic,
  but only because of background noise.

**You do not need Python.** Setup looks for Python 3.11 or newer and uses it
if you have it; if you do not, it fetches a private copy into the Leximine
folder — 11 MB, the official embeddable build from python.org, checksum
verified. Nothing is installed system-wide, nothing goes on your PATH, and
deleting the Leximine folder removes it completely.

If you would rather install your own (it saves that download and is the normal
thing if you use Python for anything else), get it from
[python.org](https://www.python.org/downloads/) and tick **"Add python.exe to
PATH"** in the installer. Untick that box and Leximine will simply not see it
and fetch its own copy instead, which is the one failure this used to turn
into a dead end.

**Strongly recommended**

- **An NVIDIA GPU.** On one, a pass over what you just said takes about
  60–70 ms (measured on an RTX 5060 Ti with the default `small` model), which
  is what makes the game feel like it is listening rather than thinking. The
  model's weights are 461 MB, so VRAM is not the constraint here — any modern
  card has room. (Windows does not report per-process VRAM, so there is no
  exact figure to quote.)

  Without a GPU it falls back to the processor automatically — the backend
  tries CUDA, and on any failure retries on CPU by itself. That path works but
  has not been benchmarked here, so treat "how much slower" as unknown rather
  than as a number someone measured.

**Optional — only for villagers and the nether portal**

- **[Ollama](https://ollama.com)** with the `gemma3:12b` model (`ollama pull
  gemma3:12b`, about 8 GB and **8 GB of VRAM**).

  These two features ask you to compose a whole sentence, which is judged by a
  language model rather than matched against a word. Without Ollama they
  simply let you through, and everything else works exactly the same. If your
  graphics card has 8 GB or less, you probably want to leave this off.

---

## Install it

### 0. Get it

**[Download `Leximine.zip`](https://github.com/optumesto/leximine/releases/latest/download/Leximine.zip)** — one link, always the
newest release — and unzip it anywhere you like. Inside are the two halves,
already in the layout the setup script expects:

```
Leximine-0.1.2\
  Setup.cmd       double-click this
  INSTALL.md      this file
  LICENSE         the mod half's licence
  backend\        the speech server
  mod\            the built jar
```

Keep `backend\` and `mod\` next to each other. `setup.cmd` finds the mod by
looking beside itself, and installs only half the product if you move one.

### 1. Minecraft and Fabric

1. Run the Minecraft launcher once and play **1.20.4** so the files exist.
2. Install **Fabric Loader** for 1.20.4 from
   [fabricmc.net/use/installer](https://fabricmc.net/use/installer).

That is all you do by hand. **Fabric API** — the library mod everyone forgets,
without which the game starts normally and Leximine simply never loads — is
downloaded for you in the next step and checked against its published
checksum.

### 2. Run setup

Double-click **`Setup.cmd`**, at the top of the folder you just unzipped.
(`backend\setup.cmd` is the same script, if you would rather run it there.)

**This is the long step, and it is meant to be.** It builds the Python
environment (about 2.3 GB of libraries), downloads the speech model (461 MB),
fetches Fabric API, puts a **Leximine Backend** shortcut on your desktop, and
adds a **Leximine** profile to your Minecraft launcher with both mods already
in it. Expect several minutes, and longer on a slow connection; the progress
bars are real, so you can watch it move.

Everything slow happens here on purpose, so that the desktop shortcut starts
in seconds afterwards rather than surprising you with a download the first
time you want to play. **Leave the window alone while it works** — closing it
part way through leaves a half-built environment that has to be deleted by
hand. If it does get interrupted, delete the `backend\.venv` folder and run
`Setup.cmd` again.

The **Leximine** profile keeps its own saves folder, so it cannot disturb
worlds you already have, and your other profiles are left alone.

### 3. Check it

Double-click **`check-setup.cmd`**, also at the top of the folder. It inspects
every piece and tells you which ones are missing and how to fix each one. It changes nothing, so it is
safe to run whenever something seems wrong.

A healthy machine looks like this:

```
  Minecraft
    [ok]   Fabric loader for 1.20.4 (fabric-loader-0.18.6-1.20.4)
    [ok]   'Leximine' launcher profile present
    [ok]   leximine-0.1.2.jar installed
    [ok]   Fabric API present (fabric-api-0.97.3+1.20.4.jar)

  Speech backend (required)
    [ok]   virtualenv built (Python 3.11.0)
    [ok]   Whisper model 'small' downloaded (461 MB)
    [ok]   NVIDIA GPU: NVIDIA GeForce RTX 5060 Ti, 16311 MiB
    [ok]   backend answering on port 8760 (cuda, float16, 5710 words)
```

---

## Playing

Every time:

1. Double-click **Leximine Backend** on your desktop. Wait for **READY**.
   The first start is slower — it downloads the speech model once.
2. Launch Minecraft and pick the **Leximine** profile.
3. Play. Pick something up and it will ask you to say its name.

Leave the backend window open while you play. Closing it does not break
anything: the mod is built to let you through whenever the backend is not
answering, so you get items for free rather than getting stuck.

`/lex` in game shows your settings. `/lex set language fr_fr` switches
language. `/lex words` is your vocabulary list.

---

## When something is wrong

**Run `check-setup.cmd` first.** It is written to answer this exact question.

| What you see | What it is |
|---|---|
| Nothing happens when you pick things up | The backend is not running, or Fabric API is missing — both let you through on purpose |
| "speech backend offline" in chat | The backend window is closed. Start it. |
| Every answer takes a second or two | Running on the processor instead of the GPU. `check-setup.cmd` says which. |
| Villagers trade without asking | Ollama is not running. Optional; everything else is unaffected. |
| It hears the wrong thing constantly | Try `/lex set difficulty lenient`, and check Windows is using the microphone you think it is |

---

## What talks to the internet

Worth being exact about, because "runs on your own machine" is true of the
part that matters and not of everything.

**Your voice never leaves.** Recognition happens locally in the backend, in
memory. Nothing writes your microphone audio to disk and nothing uploads it.

Five things use the network, in the order you meet them. Four of the five are
one-time downloads during setup, and none of them sends anything about you:

| | When | What is sent |
|---|---|---|
| **python.org** | setup, once, and only if you have no Python 3.11+ | nothing about you; it downloads the official 11 MB embeddable build and checks it against a pinned checksum |
| **PyPI** | setup, once | nothing about you; `pip` downloads the Python libraries listed in `requirements.txt` — the 2.3 GB, and the biggest download here by far |
| **Modrinth** | setup, once | nothing about you; it downloads Fabric API and checks it against its published checksum |
| **Hugging Face** | setup, once | nothing about you; it downloads the speech model (`Systran/faster-whisper-small`, 461 MB). It is deliberately not in the zip: the weights are reached through relative symlinks that Windows zip tools flatten to 0-byte files, so a bundled copy would look complete, be empty, and fail on load. |
| **Microsoft (edge-tts)** | you miss a word and it plays you the correct pronunciation | the word being pronounced, as text — e.g. `Diamante`. Never your audio. |

Everything else is local, including two things that sound like they would not
be. The dictionary is built by reading the official translation files out of
**your own Minecraft installation** on disk, not by downloading them. And
Ollama, if you use it, runs on your machine — the sentence you say to a
villager is judged there.

To cut the Microsoft one out: `/lex set pronunciation false`. The client only
requests a spoken example when that setting is on, so with it off nothing is
sent to Microsoft.

---

## Licence

Two halves, two licences, both free and both open source:

- **The mod** — [MIT](LICENSE). Do what you like with it.
- **The speech backend** — [MPL-2.0](https://github.com/optumesto/leximine-backend/blob/main/LICENSE). Also do what you like
  with it, including building something commercial on top; the one condition
  is that if you modify its *own* files and distribute the result, those files
  stay open. New files you write alongside it are yours under any licence you
  choose.

Neither licence covers the name "Leximine" or the icon.

**Selling a Minecraft mod is prohibited by Mojang's EULA**, whatever any
licence here says. That is not a restriction this project invented, and it
applies to forks of this one exactly as it applies to everyone else.

## Doing it manually

The setup scripts are a convenience, not a requirement. By hand:

```powershell
# the backend
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8760
```

```
# the mod
copy leximine-<version>.jar  into  <your instance>\mods\
copy fabric-api-*.jar        into  <your instance>\mods\   # 1.20.4 build
```

The mod looks for the backend at `ws://127.0.0.1:8760/ws`. Point it somewhere
else with `-Dleximine.backend=ws://host:port/ws` as a JVM argument. There is
no in-game command for this — `/lex` shows you the address it is using but
cannot change it, because the client reads it once at startup.

Any Fabric 1.20.4 instance should work — MultiMC, Prism, the vanilla
launcher — though only the vanilla launcher has actually been tested. The
`setup.cmd` profile is just the path of least resistance.
