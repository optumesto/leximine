# Installing Leximine

Leximine will not let you keep an item until you say its name out loud, in the
language you are learning. It is free, and the speech recognition runs on your
own machine: **your microphone audio is never uploaded and never written to
disk** — it is decoded in memory and discarded.

That is not the same as "offline". See
[What talks to the internet](#what-talks-to-the-internet) below for the three
things that do.

It comes in two halves and you need both:

| | What it is | Where it runs |
|---|---|---|
| **The mod** | a Fabric mod for Minecraft 1.20.4 | in Minecraft |
| **The speech backend** | a small local server that listens and judges | a window you leave open |

The backend does the hard part — speech recognition on your GPU — which is why
it is a separate program rather than something inside Minecraft.

---

## Before you start

**Required**

- **Minecraft: Java Edition**, version **1.20.4**. Not Bedrock, and not a
  newer version — mods are built against one version at a time.
- **Windows 10 or 11.** The mod itself is cross-platform; the one-click setup
  scripts are Windows-only. On Linux or macOS you can still run everything by
  hand (see *Doing it manually* at the bottom).
- **Python 3.11 or newer**, from [python.org](https://www.python.org/downloads/).
  Tick **"Add python.exe to PATH"** in the installer — almost every setup
  problem people hit is this box being unticked.
- **About 5 GB of free disk space**: 2.3 GB of Python libraries, 0.5 GB for
  the speech model, and room to spare.
- **A microphone.** Any one. A headset is better than a laptop's built-in mic,
  but only because of background noise.

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

### 1. Minecraft and Fabric

1. Run the Minecraft launcher once and play **1.20.4** so the files exist.
2. Install **Fabric Loader** for 1.20.4 from
   [fabricmc.net/use/installer](https://fabricmc.net/use/installer).

That is all you do by hand. **Fabric API** — the library mod everyone forgets,
without which the game starts normally and Leximine simply never loads — is
downloaded for you in the next step and checked against its published
checksum.

### 2. Run setup

In the backend folder, double-click **`setup.cmd`**.

It builds the Python environment, fetches Fabric API, puts a **Leximine
Backend** shortcut on your desktop, and adds a **Leximine** profile to your
Minecraft launcher with both mods already in it. The profile keeps its own
saves folder, so it cannot disturb worlds you already have, and your other
profiles are left alone.

### 3. Check it

Double-click **`check-setup.cmd`**. It inspects every piece and tells you
which ones are missing and how to fix each one. It changes nothing, so it is
safe to run whenever something seems wrong.

A healthy machine looks like this:

```
  Minecraft
    [ok]   Fabric loader for 1.20.4 (fabric-loader-0.18.6-1.20.4)
    [ok]   'Leximine' launcher profile present
    [ok]   leximine-0.1.0.jar installed
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

Two things use the network:

| | When | What is sent |
|---|---|---|
| **Microsoft (edge-tts)** | you miss a word and it plays you the correct pronunciation | the word being pronounced, as text — e.g. `Diamante`. Never your audio. |
| **Modrinth** | setup, once | nothing about you; it downloads Fabric API and checks it against its published checksum |

Everything else is local, including two things that sound like they would not
be. The dictionary is built by reading the official translation files out of
**your own Minecraft installation** on disk, not by downloading them. And
Ollama, if you use it, runs on your machine — the sentence you say to a
villager is judged there.

To cut the first one out: `/lex set pronunciation false`. The client only
requests a spoken example when that setting is on, so with it off nothing is
sent to Microsoft.

---

## Licence

Two halves, two licences, both free and both open source:

- **The mod** — [MIT](LICENSE). Do what you like with it.
- **The speech backend** — [MPL-2.0](backend/LICENSE). Also do what you like
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
