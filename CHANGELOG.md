# Changelog — leximine

The record of what changed and why, in order. The READMEs carry the reasoning
in full; this is the chronology.

---

## 2026-11-10 (later) — 0.1.3, so the download carries the German pack

### Changed

- **Version 0.1.3.** The zip ships `backend/app/phrases.py`, so the German
  coverage work landed in the repository and not in the thing people
  actually download. 0.1.2's asset still had German at 45.8% while the
  README had been updated to say 83.3% — a claim the download did not
  deliver, which is the specific failure this project keeps a rule about.
  Checked by listing the published zip rather than assumed.
- Replacing 0.1.2's bytes in place was rejected for the reason it was
  rejected twice before: that version is public and already downloaded, and
  its tag would stop describing its contents.

## 2026-11-10 — only claims that were measured, and an in-progress notice

### Changed

- **German moved to `full` in the language table.** The backend grew a German
  phrase pack, and the picker tag derives from `supports_sentences()`, so
  this is a documentation change rather than a mod change. Tiers 3 and 4 now
  produce sentences for Spanish, Japanese, Russian, French and German.
- **An In progress block at the top of the README.** The mod is still being
  changed regularly and the README now says so, naming the two things a
  reader should know are unfinished rather than leaving them to be
  discovered: which languages reach tier 3, and that French and German fall
  back silently on items whose gender is not in the table.
- **Coverage claims replaced with numbers a script prints.** French
  **89.7%** and German **83.3%** of 2,116 reachable items, from
  `scripts/coverage_report.py` in the backend repo, which measures through
  the same call the server makes. The figures here before were measured by
  hand against two different denominators that disagreed with each other —
  90.4%/68.7% in the morning and 72.7%/63.6% before that. None of them were
  reproducible, which is the actual defect; being wrong was the symptom.
- **The denominator is described as a floor.** About a hundred of those
  2,116 strings are UI text that shares the `block.` prefix rather than item
  names, so the real coverage is a little better than stated. Said plainly
  rather than tuned away.

### Removed

- **`docs/hud.png` from the README.** It showed an older overlay than the one
  that ships, so it was a picture of something a downloader would not see.
  The paragraph that pointed at "the screenshot above" was rewritten to
  describe `--hud-shot` instead of referring to an image no longer there.

---

## 2026-11-09 (later) — 0.1.2, so the download carries the installer fixes

### Changed

- **Version 0.1.2.** 0.1.1's zip was published at 07:36 and the installer work
  landed after it, so the download contained none of it: no `ensure_env.ps1`,
  no root `check-setup.cmd`, Python still a hard prerequisite, and a setup step
  that still finished in seconds and left 2.3 GB for the first launch. Checked
  against the published asset rather than assumed — zero matches for either
  file. Replacing 0.1.1's bytes in place was the alternative and was rejected
  for the same reason it was rejected yesterday: that version is public and
  already downloaded, and its tag would stop describing its contents.
- The mod jar is unchanged in substance. The 0.1.1 and 0.1.2 jars were compared
  entry by entry: all 60 class files byte-identical, one line different in
  `fabric.mod.json`. This is a backend-and-installer release.

---

## 2026-11-09 — the bare name, and one link that is the download

### Changed

- **`leximine-mod` is now `leximine`.** The suffix existed only to tell the two
  halves apart, and it told players the wrong thing: this repository is the mod,
  the release and the install docs — the one you look for when you want to
  play. The backend keeps `leximine-backend`, which is what it is. GitHub
  redirects the old URL, so the published 0.1.0 download link and every existing
  clone keep working. On the backend side, `setup.ps1` and `make_icon.py` look
  for a sibling folder named `leximine` first and still accept `leximine-mod`,
  so a checkout from before today needs no attention.
- **The README build block starts with `git clone`.** It opened with a `cd` into
  an absolute path on one machine, which is not a step anyone else can follow,
  and the clone step it implied was written down nowhere.
- **Version 0.1.1.** Not cosmetic: the 0.1.0 zip shipped the backend half
  under its old name, with 32 `LINGUA_*` keys in its `.env.example` and a
  README titled `lingua-stt-backend`. Those keys still work, but a download
  whose docs disagree with the repository it came from is its own support
  burden. Same jar logic; the mod code did not change.

### Added

- **`Leximine.zip`, a name that does not move.** GitHub serves the newest
  release's asset from `/releases/latest/download/<name>`, so a fixed filename
  makes one URL the download link forever — no release page to navigate, no
  version to pick. The version still shows: it is on the release, and on the
  folder inside the zip. `make_release.ps1` names the asset, not the version.
- **`Setup.cmd` at the top of the zip.** The first instruction after unzipping
  used to be "go into `backend\`", which is not where anyone looks for an
  installer. It forwards to the same script `backend\setup.cmd` runs, and
  `setup.ps1` resolves both halves from its own location rather than the
  working directory, so the two are interchangeable.
- **`.github/ISSUE_TEMPLATE/config.yml`**, which this repository had none of.
  `bug.yml` still takes speech problems as well as game ones — a player cannot
  tell which half is at fault and should not have to — so it only routes the
  two cases where they can: install questions to INSTALL.md, and the backend
  standalone to the backend's issues.

### Fixed

- **A release could ship a `.cmd` that cmd.exe will not reliably run.**
  `check-setup.cmd` went out in 0.1.0 with LF line endings. `.gitattributes`
  declares `*.cmd eol=crlf`, which fixes a fresh clone and says nothing about a
  working copy that predates the rule — and `git status` stays quiet, because
  it compares normalised content. The release is built from the working copy,
  so it went out wrong. `make_release.ps1` now checks the endings of every
  `.cmd` it is about to ship and refuses the release, alongside the checks for
  a missing jar and for Mojang's dictionaries.

### Verified

- **The published zip was installed and booted by a reviewer who had no
  checkout**, from `Leximine.zip` alone, on a machine with no GPU. The
  production jar loads on a real Fabric 1.20.4 dedicated server:
  `Loading 44 mods: leximine 0.1.1`, the full startup line, then `Done`, with
  no Leximine error anywhere in the log. All three common mixins resolved
  against production mappings — `required: true` with `defaultRequire: 1`
  would have crashed startup otherwise. That capture replaces the one this
  README had been carrying, which no shipped jar could have printed.
- **Not covered by that run, and not by anything else**: the client half — the
  HUD, microphone capture and picking an item up in-game — needs a real
  Minecraft client, and `Setup.cmd` needs Windows. The README now says so
  where the capture is, rather than leaving it to be assumed.
- **INSTALL.md's network table listed two sources while the README promised
  three.** The missing one was the largest: Hugging Face, which the backend
  pulls `Systran/faster-whisper-small` from on first start, 461 MB. Named now,
  with why it is not in the zip.
- **The client half is verified in the dev environment.** `e2e_test.py` ran all
  12 scenarios green on 2026-11-09: a correct `Diamante` through
  `-Dleximine.fakeMic` scored 100 and the diamond landed in the inventory, a
  wrong word scored 40 and it did not, a dead backend granted the item rather
  than eating it, a learned word stopped being asked and `forget all` brought
  the asking back, a bare noun was refused by gemma3 while `"Quiero comprar pan
  por favor."` opened the trade, and an orphaned review timed out server-side
  with the next one still firing. Asserted against game state via
  `clear <player> <item> 0`, not log lines, and the speech scenarios separately
  require a non-empty transcript so that fail-open cannot masquerade as a pass.
  This is the dev build; the **shipped jar's** client path is still unverified,
  since that needs a launcher profile written on a Windows machine.

### Changed — the download asks less of the person downloading it

Prompted by asking what would actually make this easier for a first-time
installer, the day before one tests it.

- **`check-setup.cmd` is at the top of the zip**, beside `Setup.cmd`. Both
  issue templates, INSTALL.md and the release notes all say to run it first,
  and it was the only one of those four files that lived a folder down —
  where nobody told to run it in a hurry would look. `make_release.ps1`
  generates it with CRLF endings and asserts it shipped.
- **INSTALL.md no longer lists Python as required.** The backend fetches its
  own when the machine has none, so the "Add python.exe to PATH" checkbox
  that used to be a dead end is now a detail about speed.
- **Step 2 says it is the long step**, names the 2.3 GB and the 461 MB, says
  the progress bars are real, and says what to do if it is interrupted. It
  previously described a step that finished in seconds and left the waiting
  for later.
- **The "what talks to the internet" table was missing its two largest
  entries.** It listed Modrinth, Hugging Face and Microsoft; it omitted
  **PyPI**, which is the 2.3 GB, and python.org, which is new. Five now, each
  with when it happens and what is sent. This is the second correction to
  that table in two days — the first added Hugging Face, and I did not then
  check whether anything else was missing, which is how the bigger omission
  survived.

---

## 2026-11-05 — the backend is renamed, and the build leaves one machine

### Changed

- **`lingua-stt-backend` is now `leximine-backend`**, which reverses the
  decision recorded on 2026-11-02 that it should keep a generic name. The
  reason it lost: the two halves of one product were called different things
  everywhere a player sees them — the repository, the install docs, the
  release zip — and a generic name buys nothing while the only thing it
  serves is this mod. Every link and path here points at the new name;
  `make_release.ps1` and `dry_run_commit.py` still accept a sibling folder
  with the old name, so a clone from before the rename keeps working.
  GitHub redirects the old URL.
- **The JDK is no longer pinned in `gradle.properties`.**
  `org.gradle.java.home` was an absolute path on one machine, and Gradle
  refuses to start when it does not exist — so every other clone and every CI
  runner failed before reading a single task. The build takes its JDK from
  `JAVA_HOME` or PATH now, verified with a clean `gradlew build`, and the
  README says where to pin one privately.
- `LEXIMINE_LLM_MODEL` is the new name of the backend setting mentioned in the
  Ollama section; `LINGUA_LLM_MODEL` still works.

### Added

- **CI** (`.github/workflows/build.yml`): builds the jar on Ubuntu with
  Temurin 21 and uploads it as an artefact. A version bump that only works
  against a warm local Gradle cache now fails here rather than in someone's
  first clone. `gradlew` also gets its executable bit in the index, for Linux
  and macOS clones.
- **CONTRIBUTING.md**: which repository each kind of report belongs in, the
  fail-open rule stated as the one non-negotiable invariant, the
  client/common split that crashes a dedicated server when it is got wrong,
  and `e2e_test.py` as the harness that actually tests a change.

---

## 2026-11-03 (evening, later) — reviewed without a native speaker

Mason has no native speaker available for anything but possibly Spanish, so
"a speaker should read these" stopped being useful advice. Two substitutes.

### A reviewer that was tested before being believed

The 12B was discarded earlier for inventing errors. A 30B reasoning model was
tried instead, and this time with **controls in the batch**: per language,
three sentences known to be fine and three with one planted error each — a
wrong particle for Japanese, a non-nominative noun for Russian, a wrong
gender for French.

It scored **6/6 controls in all three languages**, which is what makes its
verdicts on the real frames worth reading. 96 frames reviewed.

**Three real errors found and fixed:**

- French, twice, and both the same rule: an indefinite article after a
  negation becomes "de". "Presque personne n'a **une** épée" and "On ne
  trouve pas **une** épée tous les jours" should be "d'épée". Both frames
  were rewritten so the item is no longer the direct object of a negated
  verb — the elision ("de" before a consonant, "d'" before a vowel) is a
  rule the renderer cannot apply, and avoiding the construction cannot be
  subtly wrong.
- Russian: «Это большая редкость, алмазная кирка» needs тире, not a comma.

The Japanese flag (後のために as unnatural) **did not reproduce** — the same
model passed the sentence on re-test. Changed anyway to 後で使うために, which
it described as 自然 where the original only got 文法的に正しい, but recorded
here as a preference rather than a confirmed error.

### Two things worth remembering about the method

**The reviewer is not deterministic.** The first pass *passed* "On ne trouve
pas une épée"; the re-test flagged it. It was caught because the first real
finding made the whole construction suspect and every negative frame got
re-examined by hand. A single clean pass is not a clean bill of health.

**An empty answer is not a verdict.** Five of the eight flags in the first
run were blank — the reasoning field had eaten the token budget before any
answer was emitted — and three of those five turned out to be perfectly fine
sentences. This is the third time the harness, rather than the model, nearly
decided something. See [[feedback-validate-the-validator]].

### Added — press N to report a wrong sentence

The better long-term answer: the audience contains the speakers. The people
who asked for Japanese almost certainly read it.

`N` writes the exact prompt — time, language, tier, item, sentence, meaning —
to `config/leximine-reports.txt` and prints it in chat so it can be
screenshotted. No network, no telemetry; a line of text in a file the player
owns and chooses to share. It remembers the *last* prompt rather than only
the current one, because a sentence is judged a second after it has gone.

---

## 2026-11-03 (evening) — French to 90%, and a denominator that was wrong

### The coverage number was understated, because the denominator was wrong

French was reported at 65%. It was really **80%**: 688 of the 2,792 keys being
counted are `block.minecraft.banner.*`, which are heraldic *descriptions* shown
in a banner's tooltip ("Champ de sable plain") and not items at all. The mod
challenges on `stack.getItem().getTranslationKey()`, which for a banner is
`block.minecraft.red_banner` and never one of those. They were also supplying
most of the odd head words — canton, pal, taillé, gorgone, émanchures — so the
list of "things left to label" was largely fictional.

Worth stating because the fix was to measure the right thing, not to write
more code.

### Extended the French table: 80% -> 90%

228 head words now. The additions are the everyday vocabulary — carotte,
morue, saumon, boussole, citrouille, obsidienne, poulet, lance, pépite — each
heading only one or two items, which undersells them badly: a carrot is picked
up rather more often than the fifteenth variant of copper stairs.

**Minecraft's own strings confirmed much of it.** French adjectives agree, and
the game ships the adjective: "Morue cru**e**", "Nylium biscorn**u**",
"Andésite poli**e**", "Houstonie bleu**e**", "Oeilchidée fermé**e**",
"Hyphes biscornu**es**". Where an agreeing adjective exists the gender is in
the data rather than in anyone's memory.

Stopping at 90% is deliberate. The remaining 204 items are spread over 163
head words at one or two items each; labelling 120 more would add 7%. Size was
never the constraint — the table is ~6 KB against a 461 MB model — the
constraint is how many words can be labelled *correctly*.

### Cross-checked, and the table won

The local model was asked for a second opinion on all 228 entries: **17
disagreements**, every one examined by hand, and every one resolved in favour
of the table. Three were settled by Minecraft's own adjective agreement
(diorite, houstonie, hyphes). Two of the model's "corrections" were `pierre`
and `œuf` — precisely the words it got wrong in the measurement that decided
the table should be hand-written in the first place.

That is the right way round to use it: as something that points at places to
look again, never as the author.

### Deliberately still unlabelled

Recorded in the source so nobody "completes" the table with them: the music
disc artists (c418, lena, aaron), format strings and UI messages (s, vous, ce,
ajoutez, en, projectile, pouvoir, durée, amélioration), bare adjectives
(bleu, gris, vert, haut, grande, petite), and two invented compounds whose
gender no string in the game confirms — `planturne` and `torche-fleur`. They
cost two items each, and guessing them is the exact failure the table exists
to prevent.

---

## 2026-11-03 (afternoon, later) — the tier ladder works in four languages

### The problem, measured first

Asked whether it would be worth pre-building a dictionary per featured
language. It would not: dictionaries extract themselves from the player's own
game, and shipping them is Mojang's content, not ours. The real gap was
elsewhere and much bigger.

`phrases.py` only built tier 3–4 sentences for languages with a **phrase
pack**, and there was exactly one. So outside Spanish the entire difficulty
ladder was flat — tier 1 through 4 returned the identical string, and a
netherite ingot asked exactly what a stick does. The picker meanwhile labelled
every language with a dictionary `ready`, which was true and misleading.

    es_es   Espada | Espada de diamante | Quiero una espada... | Por fin tengo...
    ja_jp   ダイヤモンドの剣  (x4, identical)
    ru_ru   Алмазный меч     (x4, identical)

### Added — three phrase packs

- **Japanese.** The most requested, and by luck the cheapest to do correctly:
  no articles, no gender, no number agreement, so the article hook returns
  "". Written in polite form throughout and without spaces, both enforced by
  a check — mixing plain and polite would teach that they are interchangeable.

- **Russian.** Sounds easy (no articles) and is not: Russian declines the noun
  *and* its adjectives, so "I found a diamond pickaxe" needs «алмазную кирку»
  where Minecraft ships the nominative «Алмазная кирка». **Every frame
  therefore keeps the item in the nominative** — «У меня есть X», «Это X»,
  «Вот X» are ordinary high-frequency constructions, not contortions. Narrower
  than Spanish; correct for every item without a word of morphology.

- **French.** The one that needed data: "un épée" is wrong, and Minecraft
  ships no gender. A **hand-written table of head nouns**, keyed on the head
  because the article agrees with that — "Épée en diamant" and "Épée en fer"
  both take "une" — which collapses 2,792 names onto ~400 words.

  **Partial on purpose.** An unlisted head noun produces no sentence and the
  item falls back to tier 2, exactly as an unsupported language does. Covers
  **65%** of items; the remainder is mostly heraldry vocabulary. Adding a word
  turns sentences on for every item built from it.

### Why the table is hand-written

The local model was measured on the job before being trusted with it: **47 of
50** common French head nouns correct. That sounds respectable and is not —
it missed `oeuf`, `pierre` and `or`, between them the head of over a hundred
items. Generating it automatically would have shipped "une œuf" to every
French player.

The same model was tried as a reviewer for the Japanese frames and was worse:
of 21 flags it raised, nearly all were self-contradictory — it flagged
作りました on the grounds that it should be 作りましたです (not Japanese), and
出かけません for being "plain form, should be 出かけません". **None of its
flags were acted on.** A deterministic check replaced it: register, spacing,
placeholder validity and level coverage, which cannot judge naturalness but is
right about what it does assert.

### Fixed — dakuten were being stripped as accents

`strip_accents` ran NFKD and dropped every combining mark, which for Japanese
decomposes ダ into タ plus a mark and then throws the mark away. ダイヤモンド
normalised to タイヤモント, and ハ, バ and パ — three distinct sounds —
collapsed into one. The matcher scored a wrong reading identically to the
right one; it now scores **67 against 100**. Spanish accent-stripping is
unchanged, which the same test asserts.

### Changed — the picker says what you are getting

`/languages` reports `sentences` per language, and the rows are tagged **full**
or **words only** with a legend. Having a dictionary is not the same as being
playable, and the old label said only the first.

### Still flat: German

Genuinely harder, and not worth faking. German compounds, so there is nothing
to cut — all 2,792 names came back uncut, and the "head words" extracted are
mostly colour adjectives (rosa, magenta, polierte). Compound gender follows
the final element, which needs a lexicon to split. It also needs case
(den/dem/des), and the renderer lowercases the item name, which is wrong for
a language that capitalises its nouns. Three separate problems, none of them
a table.

---

## 2026-11-03 (afternoon) — many languages, and knowing what to say

### Added

- **A language picker on the title screen.** `LanguageScreen`, reached from a
  "Learning: <language>" button, with a search box. Driven from the backend's
  `/languages`: the five with dictionaries on disk are listed first with a
  green `ready` tag, then the ~140 more the player's own Minecraft install
  can provide, which the backend extracts on first use. Falls back to a
  built-in list when the backend is closed — which is exactly when someone is
  most likely to be at the title screen.

  It writes the same setting `/lex set language` always wrote. Switching was
  never the hard part; discovering it, and knowing the code, was.

- **A romanized reading on the overlay**, between the word and its meaning:

      ダイヤモンド              Алмаз
      daiyamondo               almaz
      (Diamond)                (Diamond)

  New `app/romanize.py`, no new dependency: Cyrillic, Greek, Hangul
  (decomposed arithmetically) and kana. Reported by the backend on
  `challenge_ready`.

  **It refuses rather than guesses.** Anything containing kanji returns
  nothing, because 石 is *ishi*, *seki* or *shaku* depending on the word —
  a dictionary lookup, not a rule — and a partial reading like
  "stone no tsurugi" reads as a bug rather than a hint.

- **A "Hear the word" key (B).** The spoken example already existed but fired
  only *after* a miss, which quietly assumes you had some way to make a first
  attempt. For Spanish you do: the spelling is the instruction. For Japanese
  you do not.

  Deliberately a key rather than automatic — handing over the answer every
  time turns a recall exercise into repeat-after-me.

### Measured, because it decided the design

Scripts across the shipped dictionaries, per item/block entry:

| | |
|---|---|
| Russian | 99% Cyrillic — rules cover essentially all of it |
| Japanese | **84% contain kanji**; 15% pure kana |

That split is why this is two features and not one. A rule-based romanizer
alone would have covered a seventh of the language that prompted the request.
Audio covers 100% of it, verified: `/pronounce` returns 0.46s for 石, and
five different words across three languages return five genuinely distinct
clips rather than one cached collision.

### Caught in testing, not in review

- The romanizer gave **Spanish** a reading for 24% of words. An ascii check
  let `ñ` and `ó` through, and what came back was the same word with the
  accents filed off — "Pocion" under "Poción", which teaches the wrong
  spelling. Now checks for Latin *script*, and Spanish is correctly 0%.
- Cyrillic `е` was "ye" everywhere, so Камень came out "kamyen". It is only
  *ye* word-initially or after a vowel or a hard/soft sign.

### Notes for whoever is next

- Full romaji needs a kanji dictionary. `pykakasi` is **GPL-3.0**, which is a
  licence decision and not one to make in passing; `cutlet` is MIT but pulls
  a ~250 MB dictionary. Either would slot in as an optional dependency that
  degrades to the present behaviour when absent.
- The e2e harness gained `--lang`, used only for screenshots: the speech
  fixtures are recorded in Spanish, so every scenario that asserts on a
  verdict still needs `es_es` to mean anything.
- `capture_hud.ps1` now falls back to any Minecraft window. It could only
  find the harness's auto-connecting client, which joins a world immediately
  and therefore can never show the title screen — where the picker lives.
- `runClient` is now a declared run config. The vmArg block everyone reaches
  for belongs to `clientAuto`, which hardcodes `--quickPlayMultiplayer`; a
  property added there reaches a client that is never at the title screen,
  and one added to the `server` block (where `lexLanguage` lives) reaches a
  process with no screens at all. Both were tried first.

---

## 2026-11-03 (overnight) — making it installable by someone else

Asked to get this to a state other people can actually use. Most of that
turned out to be an audit problem: the setup path a new user is told to run
had been quietly broken since the rename, and several pieces still wore the
name the rename existed to get away from.

### Fixed — the setup path did not work

- **`setup.ps1` looked for the mod at `lingua-craft-mod`**, the pre-rename
  folder. It has been taking the "mod project not found" branch ever since,
  installing the backend and silently skipping the half that is the product.
- **`make_icon.py` wrote `linguacraft.png` into that same dead folder**, while
  `install_mod.ps1` reads `leximine.png` from the mod repo — so the launcher
  profile has been going in without its icon.
- **`start_backend.ps1` checked for a launcher profile called `linguacraft`**,
  which the rename deleted. Every single start told the user "the Minecraft
  side is not installed yet, run setup.cmd" no matter how correctly it was
  installed.
- `-Dlinguacraft.backend` in the port-clash message: a system property that
  has not existed since the rename.

### Changed — the old name is gone from everything a user sees

Shortcut, window titles, setup output, READMEs, protocol doc. The desktop
shortcut is now **Leximine Backend** and installing removes the old
**LinguaCraft Backend** icon rather than leaving two that do the same thing.

Generic internals of the speech server became plain "Lingua" rather than
"Leximine": it is deliberately a separate, mod-agnostic program, but it must
not keep advertising the name the mod was renamed away from.

### Added

- **`check-setup.cmd` / `scripts/doctor.ps1`** — a read-only preflight that
  checks Minecraft, Fabric loader, the launcher profile, the mod jar, Fabric
  API, Python, the virtualenv, the Whisper model, the GPU, the backend and
  Ollama, and prints the fix for each thing that is missing. Required pieces
  fail (exit 1); the language model only warns, because the mod is built to
  let you through without it.

  It checks the Whisper model by **opening** `model.bin` rather than stat-ing
  it. HuggingFace stores the weights once in a content-addressed blob store
  and links to them, so measuring the folder reports a few MB of tokenizer
  and proves nothing. Getting that wrong first reported a perfectly good
  install as empty — a relative symlink target resolves against the working
  directory, not against the link, and the chain here is two hops.

- **`scripts/make_release.ps1`** — assembles `dist\Leximine-<version>\` with
  both halves in the layout `setup.cmd` expects, and asserts the result is
  complete before claiming success. **4 MB staged, 1.1 MB zipped.** It ships
  neither
  `.venv` (2.3 GB of wheels compiled against one machine's Python) nor
  `models` (1.9 GB, and the weights are reached through relative symlinks
  that Windows zip tools flatten to 0-byte files — a shipped models folder
  would look complete, be empty, and fail at load talking about a corrupt
  model). Both rebuild themselves on first run.

- **`INSTALL.md`** — written for someone who has never seen this before:
  honest requirements, what degrades without a GPU, what is optional, and a
  troubleshooting table. First draft told people to download Fabric API by
  hand; `install_mod.ps1` has been fetching and checksum-verifying it all
  along, so the real instructions are two steps shorter than I wrote.

- **`install_mod.ps1` now finds the jar in three places** — `build\libs`, its
  own folder, or `mod\` — so one script serves both a source checkout and a
  downloaded zip. It also no longer tells someone without a JDK to go and run
  `gradlew`; with no `gradlew.bat` present it says the release is incomplete,
  which is the true answer.

- `gradle.properties` now says, loudly, that `org.gradle.java.home` is one
  machine's path and has to be changed or deleted before anyone else can
  build. Left in place rather than replaced with a toolchain: a missing
  toolchain fails with "no matching toolchains found", which is a worse first
  experience than a comment that tells you exactly what to do.

### Verified

`setup.cmd` end to end; `install_mod.ps1` run **from the staged release** with
no source, Gradle or JDK in the picture; `doctor.ps1` green on a healthy
machine and correctly reporting 2 failures with exit 1 against an empty
`%APPDATA%`; the mod still builds and the full e2e suite still passes.

### Licensing — decided

**MIT for the mod, MPL-2.0 for the backend.** Mason's call, on the reasoning
that the two halves carry different risk:

- Selling a Minecraft mod is barred by **Mojang's EULA** whatever licence is
  on it, so copyleft on the mod would buy almost nothing. MIT, maximum reach.
- The backend is deliberately generic and genuinely reusable outside
  Minecraft, where no EULA applies — that is the half someone could close and
  build a product on. MPL-2.0 is file-level copyleft: combine it with
  anything including proprietary work, but modifications to *its own* files
  stay open.

The canonical MPL text was downloaded from mozilla.org rather than written
from memory, and the BOM that PowerShell 5.1 put on it was stripped —
16,726 bytes, byte-for-byte upstream.

Exhibit A notices added to the backend's own 39 Python and PowerShell files.
Those headers are not decoration under MPL: the licence is *per file*, so
which files carry the notice is precisely what defines its scope.

Dependency licences were read from the installed venv rather than recalled:
everything permissive (MIT/BSD/Apache) except **edge-tts (LGPL-3.0)** and the
**NVIDIA CUDA runtime libraries (proprietary)**. Neither is redistributed,
because the release ships `requirements.txt` and not `.venv` — a packaging
choice originally made for size that turns out to be carrying licensing
weight too. `thefuzz` is MIT, not the GPL-2.0 its `fuzzywuzzy` ancestor was,
so there was no incompatibility to resolve.

### Fixed — the release was redistributing Mojang's content

`lang/*.json` are Minecraft's own translation strings, extracted from the
game's asset cache. They were being copied into the release: not ours to
redistribute, not ours to put a licence on, and a strange thing for this
project in particular to get wrong. Removed, with an assertion in
`make_release.ps1` so they cannot come back, and a `lang/README.txt`
explaining where dictionaries come from.

Nothing is lost — `start_backend.ps1` already extracts the dictionary from
the player's own install on first run. Verified: 8,665 keys for `es_es`,
`item.minecraft.diamond` → `Diamante`. It was also 3.4 of the 4 MB, so the
release went **4 MB → 0.7 MB staged, 1.1 MB → 0.4 MB zipped**.

---

## 2026-11-03 (later) — streaks, and the Nether wants a reason

### Added

- **The nether gate.** `nether/NetherGateManager.java`. Striking obsidian with
  flint and steel asks for **one sentence you compose yourself** — that you
  are going to the Nether, or why — judged by the local language model rather
  than matched against a word.

  The two thresholds are now deliberately different kinds of hard: the End is
  twelve word prompts at the top tier, an endurance test; the Nether is a
  single sentence under pressure with no right answer to learn by rote. A miss
  blocks the strike and rolls **tier 3**, under the End's tier 4, because this
  is the earlier threshold and the ask is shorter. The flint and steel is
  kept.

  **The Nether side is never gated.** Obsidian struck down there is nearly
  always someone rebuilding the portal home, and a speech check that fails
  there is not a punishment, it is being stuck in the Nether until the
  microphone cooperates. Going is a choice; coming back is not. `--nethergate`
  spends a dimension change asserting it.

  Gating the *lighting* rather than the *entry* was the other call worth
  recording: entry means a mixin into the portal tick and a prompt that can
  fire while the player is falling, mid-fight, or being chased into the portal
  they built to escape. Lighting is single, chosen and stationary.

  It gates **every** obsidian strike, not only the ones that would really
  light a portal. Asking vanilla whether a frame is valid means reaching into
  the portal-shape helper, and with `defaultRequire: 1` a target that moves
  between versions fails by crashing at load. The loose check costs a gated
  decorative fire; the tight one costs the mod not loading.

- **A `task` for the trade judge, in the backend.** `TradeJudge.judge` takes an
  optional task string; with one it uses a second prompt that describes the
  job instead of implying a merchant. Kept as a separate prompt rather than
  one template with a hole in it — the trade wording was argued into shape one
  clause at a time (the "comprar is not trading" fix), and a shared template
  would re-litigate that on every future task.

  Verified against the live model, 7/7, including the two trade cases as
  regressions: moving that prompt did not change its answers.

- **A run counter, and a reason to care about easy words.** `progress/Streaks.java`
  counts consecutive correct answers, keeps a persisted personal best, and
  shows both.

  The problem it solves is that most of a session is spent on prompts with
  nothing at stake. "Palo" is one syllable and a miss only reaches the tier-1
  table, so the middle of a session is dead to play and dead to cut. A streak
  gives those prompts something to lose: the fifteenth stick in a row matters
  because fourteen came before it.

  **Breaking a run of 10 or more makes the miss roll one tier higher.** This is
  deliberately the opposite of an insurance mechanic. A streak that protects
  you drains the tension out of exactly the stretch that has the most of it;
  a streak that raises the stakes makes "I'm on fourteen" a sentence with fear
  in it. `/lex set streak off` keeps the counter and the record and drops the
  stake, for anyone who finds that punishing success feels bad — the argument
  is not settled, which is why the setting is a string and not a boolean.

  Counted by: item pickups, crafting, villager negotiations and the ender
  gate. **Not** by reviews, in either direction — nothing is at stake in a
  review, so a correct one was not earned against anything and would make the
  counter farmable by standing at a chest clicking, and a missed one must not
  end a run the player never opted into.

  The HUD counter is server-fed, not client-counted: the server owns both
  numbers and pushes them on every change. A client that counted for itself
  would drift the first time a verdict was dropped and would be trivially
  editable into a record nobody earned.

  Two notes from building it. `Punishments` and `Streaks` pointed at each
  other for one compile — Java allows a cycle and that is exactly how it stops
  being noticed — so the threshold and the on/off switch moved into `Streaks`
  and the dependency now runs one way: the lotto prices a streak, a streak
  knows nothing about the lotto. And the HUD position was wrong twice, both
  times from reasoning instead of looking at a frame: 34 put it inside the
  challenge panel, and the move to clear a wither's boss bar was solving a
  conflict that cannot happen, since a punishment implies a miss, a miss
  zeroes the streak, and the counter does not draw below 3.

  Regression: `e2e_test.py --streak` asserts a broken 11 rolls tier 2 off a
  tier-1 stick, a broken **9** still rolls tier 1 (the boundary is the part
  worth testing), and `off` leaves the tier alone while still counting. It had
  to be added to the harness's `lottoChance=100` list — at 0% there is no roll
  to read a tier off, and a scenario that cannot observe its own subject
  passes for free.

### Fixed — in the harness

- **A scenario was testing against the previous leg's punishment.** `--streak`
  rolled `T2_THE_SWARM` on its first leg and the cave spiders were still
  biting three legs later: the player died, respawned elsewhere, and an item
  summoned at the old position raised no challenge. It read as "the feature
  stopped working". New `calm()` helper — kill every non-player entity, clear
  effects, heal, reset the time and weather — called at the top of each leg.
  Any scenario that fires a real payload and then keeps going needs it.

---

## 2026-11-03 — the End has a toll, and so do the villagers

### Added

- **The ender gate.** Every eye of ender has to be argued into its frame. A
  tier-4 prompt per eye, twelve of them, in a room you cannot leave early.

  Chosen over the two obvious alternatives. Gating the **portal block** is one
  moment — pass it and the feature is over, fail it and you stand on the lip
  repeating yourself. A **guardian entity** beside the frame has to be
  spawned, can be killed, and can be walked around. Gating the **eyes** reuses
  something the player already does twelve times and paces itself.

  A miss **blocks the placement and rolls the tier-4 lottery** — the wither,
  the eviction, the black hole — in a sealed stone room, which is the whole
  point. The eye is **kept**: eyes are finite and shatter on their own often
  enough that burning one for a mispronunciation turns a language game into an
  inventory problem. Being blocked, plus whatever the lottery does about it,
  is the cost.

  A pass does **not** set `EYE=true` directly. It opens a 3-second grace
  window and replays the interaction through
  `interactionManager.interactBlock`, so vanilla's own use path is what
  notices the twelfth eye, checks the frame pattern and lights the portal.
  Reimplementing the completion rule would mean a second copy of it, and the
  copy is the one that goes stale. The grace window does double duty: it lets
  the replay back through the gate and stops the replay being asked again
  forever.

  Ordering matters in two places, both because an eye-on-frame is also an
  ordinary block use and an end-gate challenge rides the ordinary item packet:
  `EndGateManager` runs **before** `ReviewManager` in `UseBlockCallback` (or a
  furnace review asks about a stick while you are standing at the portal), and
  **before** `LimboManager` in the resolve receiver (or the fallback treats it
  as an item in limbo and grants an eye nobody picked up).

  Config `endGate` (default on), `/lex set endgate`, and a `tick()` timeout
  that logs — the pending-map-leak-silently-disables-the-feature failure this
  mod has now shipped twice. A timeout costs nothing: nothing was heard, so
  nothing was got wrong.

  Regression: `e2e_test.py --endgate`, both paths. The miss asserts the frame
  stays empty, the eye count is unchanged, **and** that a tier-4 roll actually
  happened (not which way — it is 50/50 now, so asserting the outcome would
  flake half the time). The pass asserts `EYE=true` and that vanilla spent
  exactly one eye. Driving it needs `/lex testendgate <offer|pass|fail>`,
  because the speech half is covered elsewhere and what is unique here is what
  happens *after* a verdict.

### Fixed

- **Both new gates stood aside for free while another word was open.** Found
  reading back over the gates I had just written, not by a test. Each one ended
  its busy check with `return ActionResult.PASS`, which does not mean "do
  nothing" — it hands the interaction to vanilla. So the way past either was to
  open any challenge at all and never answer it: the end gate then placed eyes
  untested, all twelve of them, and a merchant opened their trade screen
  including the very merchant being negotiated with. Both now return `FAIL`
  with "Finish what you are saying first." and a line in the log.

  This is the **third** appearance of one bug in one week. `LimboManager`
  answered "not due" and "busy" with the same `false` and the pickup mixin
  returned without cancelling for both, so everything was free during a
  challenge; and now twice more, where `PASS` reads as "stand aside" and means
  "go ahead". The lesson is written into `ReviewManager` at the one place
  `PASS` is actually correct — a review has nothing at stake, the door opens
  exactly as it does when the backend is down — with a note not to make it
  match the other two.

  Regressions: `--haggle` opens an item challenge and asserts a merchant
  refuses, and `--endgate` does the same and asserts on **the block state**,
  not the log line — a log line can be right while the frame is wrong.

- **One good sentence opened every villager in range.** `TradeManager` held
  both the post-success grace window and (once it had one) the refusal
  cooldown keyed by **player**. So the 60-second window earned by asking one
  farmer nicely applied to every merchant: walk into a trading hall, negotiate
  once, and buy from thirty people without saying another word. Nothing logged
  it, and from inside the game it looks exactly like the feature working.

  Both are now keyed by `(player, merchant)`, which is also the better fiction
  — you got on with that trader, not with the species. Regression:
  `e2e_test.py --haggle` refuses merchant A and asserts merchant B is **still**
  gated, which is the shape the old code failed.

- **A refused negotiation cost nothing.** The challenge ended, the next click
  opened a new one, and the way through was to keep clicking and talking. The
  judge runs at temperature 0, so the same sentence cannot pass twice, but a
  stream of different guesses eventually will. A refusal now puts that
  merchant on an **8-second cooldown**: no new challenge and no trade screen,
  with angry-villager particles and a line in the log. Logged, unlike the
  grace window, because the two look nothing alike from outside — a click
  inside the grace window opens the trade screen, so the player sees what
  happened, while a click inside the cooldown is a dead click, which is also
  what a broken gate looks like.

- **A refusal was a reroll.** The item demanded was always the merchant's first
  offer, so a multi-offer villager asked for the same thing forever — and once
  the pick became random, missing a hard word and clicking again would hand
  you an easier one. The demand is now chosen once per merchant and **stands
  until it is earned**, dropped on a success and swept after an hour so the map
  cannot grow for the life of the server.

### Fixed — in the harness, not the mod

- **`spawn-npcs=false`, so no villager could exist for more than one tick.**
  `ServerWorld.tick` discards every `Npc` entity — which `VillagerEntity` is —
  on the tick after it appears, when the server says no NPCs. The symptom was
  that `/summon villager` answered "Summoned new Villager", `/lex testtrade`
  reported a merchant with offers and successfully interacted with it, and
  **every** later lookup found nothing there: by RCON selector, by box query,
  by UUID. The dev command only ever worked because it interacts through the
  direct reference it had just made, inside the same tick.

  This was already written down as a known trap in `run_trade_case` —
  "summoning one over RCON reports success and then leaves nothing any
  selector can find" — and the fix at the time was to have the mod spawn the
  merchant instead, which did not fix it at all, only hid it. Six runs of
  `--haggle` went into rediscovering it. `--haggle` now fails loudly and names
  the property.

- Two real but secondary faults found on the way, both of which had never been
  exercised: `getClosestEntity` with a `TargetPredicate` returned null for a
  villager one block away, so the "use the nearest merchant" branch of
  `/lex testtrade` was dead code that had never once returned anything (every
  caller killed the villagers first and so always took the spawn path); and the
  fixture villager had `setAiDisabled` and `setInvulnerable` but not
  `setNoGravity`, which are three different things — disabling AI stops a mob
  steering, not dropping, and invulnerability does not cover the void, which
  bypasses it by design.

---

## 2026-11-02 (later) — renamed to Leximine, and two bugs

### Changed

- **LinguaCraft -> Leximine.** Another published mod already uses the old
  name. Total rename: mod id, `dev.mettle.leximine` package, classes
  (`LinguaCraft`->`Leximine`, `Lingua*`->`Lex*`), lang keys, `/leximine` with
  `/lex` as the short alias, `-Dleximine.*` system properties, `-Plex*` gradle
  properties, `leximine.mixins.json`, `assets/leximine/`, the installer's
  `Leximine` profile, the gameDir, and this repo (`Z:\leximine-mod`).

  **`lingua-stt-backend` deliberately keeps its name** — it is a generic
  speech backend that serves any language and any client, not the mod.

  Migration, since the mod id drives both: `config/linguacraft.json` and
  `linguacraft-progress.json` were copied to `leximine*.json` with the
  originals left in place, so a 6.3 KB vocabulary history survived. The
  gameDir moved with all three worlds intact and the launcher profile was
  repointed; the stale `linguacraft` profile entry was removed because it
  pointed at a directory that no longer exists, and picking it would have
  silently produced an empty world list.

  One trap: a blanket word-boundary `lingua` -> `lex` rewrite also hit the
  harness's **RCON password**, which is not a name, while
  `run/server/server.properties` sat in a skipped directory — so the two
  stopped matching and the suite died on `rcon auth rejected` with nothing
  naming the cause. Auditing the pre-rename backup for the bare term found it
  and confirmed that was the only false hit.

### Fixed

- **Everything was free while a word was pending.** `shouldChallenge` answered
  two opposite questions with one `false` — "not due", meaning vanilla should
  collect the item, and "busy", meaning it must not be collected at all — and
  the pickup mixin returned without cancelling in both cases. So any item
  touched during an open challenge went straight into the inventory untested:
  mine a vein, answer for the first ore, pocket the rest. Split into
  `bypasses()` / `isBusy()` / `shouldChallenge()`, and the mixin now cancels
  the collision while busy, leaving the item on the floor to re-collide.

  Regression: `e2e_test.py --hold`. Verified by negative control — reverting
  the one-line fix makes it fail with "gold_ingot was collected untested" and
  "the held-back item vanished instead of waiting".

- **Review prompts looked like pickups.** A door or chest review arrives with
  nothing in hand and no item icon, and the only thing distinguishing it was
  the dimmest line on the panel. On the first recorded episode a furnace asked
  for "Arena" and it was narrated as "I don't know when I picked up sand".
  The prompt now **names the block** ("Say it to open this Furnace", sent as a
  translation key so it localises) and the panel is **teal rather than grey** —
  nothing is at stake in a review, so it should not look like the panel that
  can eat an item. Colour is seen before text is read.

---

## 2026-11-02 — the lotto is a coin flip now

### Changed

- **`DEFAULT_CHANCE_PERCENT` 20 -> 50.** A miss was 80% safe; it is now 50/50,
  and any single payload goes from a 4% event to a 10% one.

  The old number was argued for on the grounds that rarity makes a punishment a
  threat rather than a tax. That argument is about playing the mod and it loses
  to a different one about filming it. In the first recorded episode the
  punishment system was explained three times, deliberately triggered once to
  demonstrate it, rolled **safe**, and the video ended up explaining to the
  audience why nothing had happened. The single most compelling thing the mod
  does never appeared on camera. Half is still not a tax, and `/lex rig`
  covers the takes where the roll has to land regardless.

  Note the consequence at the top of the ladder: tier 4 misses now fire The
  Boss, The Eviction or The Black Hole half the time, and those damage terrain
  or relocate you 5,000 blocks. Mason asked for this knowingly;
  `/lex set punishment none` and `-Dleximine.lottoChance=N` both still
  override it.

  Followed through to every place that stated the odds: the `Punishments`
  javadoc, `/lex set punishment` feedback ("a coin flip" rather than
  "1-in-5"), the `SAFE` test-seam comments (80% case -> 50% case), the README
  lotto section, and the clip pipeline's detect prompt, which told the model
  the channel's mod had "roughly an 80% chance of nothing".

---

## 2026-11-01 (later) — netherite has to be earned

### Added

- **A netherite upgrade at a smithing table now raises a challenge.** It was
  the one acquisition that slipped through everything: not a pickup, so
  `ItemEntityMixin` never saw it, and not a crafting grid, so
  `CraftingResultSlotMixin` never saw it either — the best gear in the game
  arrived without a word being said.

  **Upgrades only, never trims.** The same table applies armour trims, and a
  trim is not an acquisition: the player already owns the chestplate and is
  decorating it. Challenging that would put their netherite armour in limbo
  over a pattern. The test is that the result is netherite *and* a different
  item from what went in — trimming netherite returns the same item,
  upgrading diamond does not.

  Answers to `challengeCrafting`, rather than inventing a second switch to
  forget about.

- `/leximine testsmith quick|pickup|trim` (dev only) opens a real smithing
  table and takes a real upgrade out of it through `onSlotClick`, and
  `e2e_test.py --smith` asserts all three.

### Notes

- The result is captured in `updateResult`, not from the `onTakeOutput`
  argument. On a shift-click `ForgingScreenHandler.quickMove` empties that
  stack via `insertItem` long before `onTakeItem` runs — the same trap
  documented on `CraftingResultSlotMixin`, confirmed in the 1.20.4 bytecode
  (`insertItem` at offset 71, `onTakeItem` at 302).
- That snapshot is only overwritten when the computed result is non-empty.
  Taking the output decrements the inputs, which fires `onContentChanged` and
  recomputes an empty result *before* `onTakeOutput` runs, so an
  unconditional snapshot would wipe itself a few instructions before use.

---

## 2026-11-01 — rigging the next miss, for the video

### Added

- **`/lex rig <penalty>`** loads one outcome into the next *genuine* miss.
  The existing `force` and `testlotto` fake the miss itself, which is fine for
  B-roll and useless for a take: there is no challenge, no microphone and no
  verdict on screen. A rig fakes nothing — you play the challenge and fumble
  the word for real, and it only decides what the wheel lands on. Spent the
  moment it fires, so the next take rolls honestly.

  `/lex rig` reports what is loaded, `/lex rig off` clears it, and
  `/lex settings` shows it in yellow while armed, because a rig set an hour
  ago and forgotten reads as the lotto being broken when it finally lands. Per
  player, memory only, beats `-DforcePenalty`, and it warns on the spot if
  punishments are switched off and nothing is going to fire.

### Fixed

- **The wheel could lie about what was coming.** It was told the *item's* tier
  while the server fired the rolled penalty, and the client resolves the name
  it locks onto from `(tier, index)` — so any outcome crossing tiers (a rig, or
  `-DforcePenalty`) would spin the wrong five names and lock onto the wrong
  one while the right payload landed two seconds later. The spin now follows
  the penalty's own tier; identical for an honest roll, since that pool is
  built from the item's tier to begin with.
- **`-DforcePenalty` fell through in silence.** It was matched only against the
  missed item's tier pool, so pinning a tier-4 name and then fumbling a stone
  pickaxe went back to an ordinary random roll with nothing logged — a test
  seam whose one failure mode made a rigged run look like a broken one. It now
  searches every tier and warns if the name matches nothing.

### Added (tests)

- `e2e_test.py --rig`: loads a **tier-1** penalty, genuinely fails a **tier-3**
  diamond by letting the clock run out, and asserts the rig was consumed, the
  roll landed on it, the wheel span tier 1 rather than tier 3, it locked onto
  `THE ITCH`, the payload left exactly three silverfish — and that the *next*
  miss comes up SAFE, which is what proves "once" rather than merely implying
  it. Runs at `-PlexLottoChance=0` so an unrigged miss cannot accidentally
  pass the test.

---

## 2026-10-30 (overnight) — overlay polish

### Fixed

- **The level badge collided with the header label.** The label is centred
  and the badge right-aligned at the same height, so on a narrow panel "Ask
  them for this, in a sentence" ran straight under "lvl 2".
- **The panel ignored its own header when sizing itself.** It measured the
  *word*, so the panel for "Pan" came out narrower than its own label. The
  header now counts, and both corners are reserved — item icon left, level
  badge right.
- **The item icon overlapped the English gloss.** Centred vertically it was
  fine until the gloss grew to two lines; it now sits in the header's left
  corner, balancing the badge.
- `RECORD_DOT` is an escape (`●`) rather than a literal bullet, and the
  one em dash in that file is now `--`. Both had been mangled into mojibake
  by a PowerShell `Set-Content -Encoding utf8` round-trip, which compiles
  cleanly and is only visible on screen.

### Added

- `e2e_test.py --ui-tour`, which holds each overlay state open long enough
  for the server's chat warning to fade and captures it: word, sentence,
  legendary, review, trade.

---

## 2026-10-30 — the recording pass

### Added

- **Sensory Deprivation takes the rest of the day**: the clock jumps to
  midnight alongside the darkness, mining fatigue and phantoms. Not only for
  the mood — phantoms burn in daylight, so the three it spawns were a real
  threat only if it already happened to be dark. Forward only, and only from
  daytime: rewinding would un-advance everyone's night, and on a server this
  is felt by every player rather than the one who fumbled the word.

- **The progress rung is on screen.** `lvl 0`–`lvl 3` in the corner of the
  panel. The backend has been scaling prompts by it since the progression
  work; until now that was invisible, which made the whole feature something
  you could only feel.
- `scripts/seed_level.py <0-3>` in the backend, for screenshots. Saying 120
  words correctly to see what a level-3 prompt looks like is not a reasonable
  way to check a layout.
- `e2e_test.py --level-shot [--shot-item <id>]`.

### Fixed

- **The prompt was taking most of the screen.** The panel capped at
  `screenWidth - 40`, which means "do not overflow" rather than "leave room
  for the game" — at a tier-3 sentence it reached nine tenths of the width.
  Now capped at three fifths (four fifths in stream mode), so a tier-4
  sentence sits at about half.
- **Plural items were given singular verbs.** "Esto es unos élitros" was on
  screen. The copulas are substituted by number now (`es`/`son`,
  `Esto`/`Estos`, `encuentra`/`encuentran`), the one frame needing adjective
  agreement is dropped for plurals rather than guessed at, and the English
  "Here is" follows suit. `test_phrases.py` now checks 720 frames per plural
  noun for a singular verb.
- **The English gloss wraps instead of clipping.** It is the only thing
  telling the player what they are about to say, so "I was going to find some
  e..." is the one place a clip costs real meaning.
- The lotto name and the "AI heard" caption are clipped to the window.
  "SENSORY DEPRIVATION" at double size and a whisper sentence are both wider
  than a narrow window, and drawn centred they ran off both sides.
- `GET /translate` still rejected `tier=4`. The websocket path reads the tier
  straight off the message and was never limited, so the game had been
  sending a tier the documented helper refused. It also takes and reports
  `level` now.

### Notes

- Only one harness can run at a time, enforced by an OS file lock — see the
  previous entry's note on why a second run destroys the first.
- The "chat messages can't be verified" banner in the test screenshots is a
  multiplayer artifact and never appears in the installed single-player
  profile. It covers the top-right of the panel, which is where the level
  badge sits, so `--level-shot` holds a challenge open long enough for chat
  to fade.

---

## 2026-10-29 (later still) — tiers 2-4, and the polish pass

### Added

- **The rest of the matrix.** Fifteen more payloads across tiers 2, 3 and 4:
  knockback, disarm, fire, cave spiders; the eleven-strong horde, the panic
  room, the iron jail, a lava source, sensory deprivation; the wither, a
  150-block launch, cursed armour, a 5,000-block eviction, and a sphere of
  world removed. All twenty are asserted in `--lotto` against real game
  state, not log lines.
- **Heartbeat tension.** A pulsing dark red vignette during the spin, with a
  pitched-down warden heartbeat that closes from one beat every 13 ticks to
  one every 4 as the wheel runs down.
- **The "AI heard" caption** under the wheel, in grey italics — the transcript
  that caused all this.
- **`/lex force <tier> <id>`** for B-roll: skips the speech check, takes
  the held item, spins and lands on the penalty you name. Cheats-only rather
  than dev-only, so it exists in an installed profile. `/lex` is now an
  alias for the whole `/leximine` tree.

### Changed

- **The iron jail is a closed ring, and it decays.** All eight neighbours
  including the diagonals, at both heights, plus a ceiling and a floor where
  there was not one — seventeen bars. Cardinal sides only looked like a cage
  and was not one: a 0.6-wide player walks out through an open corner.
  It holds five seconds, then loses a bar a second in a random order. Only
  bars that are still bars are removed, so anything built in their place
  survives, and the decay is not keyed to the player: a cage left standing
  because whoever was inside logged out would be a permanent obstacle.
- **The wheel moved below the crosshair.** It and the verdict flash are
  raised by the same miss and share the screen for about three seconds; at
  `height/2 - 44` they sat a few pixels apart and overlapped outright on a
  short window. Verdict above, lotto below.
- **`heard "X"` left the verdict flash.** It is the "AI heard" caption now —
  one home for the transcript instead of the same string in two places.
- Scenario 9 no longer asserts the flat nausea it used to; it asserts that an
  item's tier selects the right table, which still logs at 0% chance and so
  stays deterministic in the main suite.

### Notes

- **On peaceful, half the matrix silently does nothing.** The game discards
  hostile mobs on their first tick, so silverfish, zombies, creepers, cave
  spiders, phantoms, vexes and the wither all vanish before the player sees
  them — while the iron golem, being neutral, survives. The harness now sets
  `difficulty normal`; a player on peaceful gets free passes on nine of the
  twenty.
- **These payloads destroy the test world**, and the world persists between
  runs. The harness rebuilds a floor from absolute coordinates before each
  case: without it the later ones run inside the crater the earlier ones dug,
  and fire, lava and iron bars all fail to place with nothing to stand on.
- `Punishments.apply` is called *before* `LimboManager` logs "resolved", so a
  test that marks the log after the resolution can never see the roll.
- The vignette must `disableBlend()` when it is done. Leaving blend on leaks
  into everything drawn later in the HUD pass.

---

## 2026-10-29 (later) — the punishment lotto, tier 1

### Added

- **Tier 4** for the things a world yields once — nether star, dragon egg,
  elytra, beacon, totem, conduit, end crystal, echo shard. Longer prompts,
  2.5× the clock.
- **Sentence variety and progression.** Each tier and context now has a pool
  of frames instead of one fixed sentence, and the pool *widens* with how much
  the player has got right (2 → 4 → 6 → 8 at 0 / 15 / 50 / 120 correct
  answers). Stakes follow the item; difficulty follows the player. Every frame
  carries its own English translation, so a sentence prompt is glossed as a
  sentence rather than as one of its words.
- **The lotto.** A miss always costs the item; on top of that the server rolls
  a 20% chance of one of five penalties from that item's tier. Tier 1's five
  are in: The Sabotage, Hunger Pangs, Butterfingers, Fright Night, The Itch.
- **The roulette.** The roll is decided immediately but shown as a slot
  machine: two seconds cycling the five names at 3-tick intervals with a
  note-block tick, then a lock in green (SAFE) or red (the penalty) with the
  payload landing on the same frame. The player is not frozen while it spins.
- `/leximine testlotto <penalty|SAFE>`, `-PlexLottoChance`,
  `-PlexPenalty`, and `e2e_test.py --lotto` / `--lotto-shot`.

### Changed

- `punishment` is on/off now rather than a five-value menu. `none` still means
  none; the old values all read as on, so existing configs keep working.
- The main e2e suite runs with the lotto forced to 0%. Every other scenario
  asserts on exact inventory counts and on what is near the player, and a 20%
  chance of silverfish would make all of them flaky.

### Notes

- **The roll happens on the server, not the client.** The spec called for the
  client to roll when the WebSocket returns a failure, but every payload is
  server-authoritative, and a client that disconnects on hearing "fail" would
  otherwise dodge the lotto entirely. The server rolls, tells the client what
  the wheel will land on, and schedules the payload itself — the animation is
  decoration over an outcome already decided, and it lands on time with nobody
  watching.
- `BLOCK_NOTE_BLOCK_HAT` is a `RegistryEntry.Reference<SoundEvent>` in 1.20.4,
  unlike the other two sounds in the spec; it needs `.value()`.
- `BAD_OMEN` (tier 4, later) does nothing on its own — it only fires on
  entering a village. Good as a lingering curse, invisible as an instant one.
- Mobs are placed by searching a ring for a spot with air to stand in. Spawning
  at a blind offset drops them inside walls, where they suffocate before the
  player sees them — a horde that quietly deletes itself.

---

## 2026-10-29 — modules 1, 3 and 5, then a pass for defects

### Added

- **Module 1 — punishments.** `limbo/Punishments.java`: a miss past tier 1
  costs nausea, and at tier 3 a zombie or primed TNT. Tier 1 is never
  punished whatever the setting. Default `mob`, because TNT damages terrain.
- **Module 3 — spaced repetition.** `review/ReviewManager.java` gates doors
  and containers on a word the *backend* says you keep missing. Cooldown,
  grace window and interaction replay, so a correct answer opens the door
  rather than merely permitting a second click. Nothing is at stake.
- **Module 5 — villager negotiation.** `review/TradeManager.java`: a merchant
  has to be asked for something in a sentence, judged by a local language
  model. Gates *opening* the trade, not completing it — refusing after
  payment would mean destroying emeralds or writing refund logic.
- **The judge's reason on the HUD.** A sentence is judged on whether it was a
  request at all, so a percentage explains nothing. The model's own words now
  appear under the transcript and again on the verdict flash, which is the
  only place they get read — the panel closes when the trade resolves. The
  flash lingers 3.6s when it carries one; 900 ms is not long enough to read a
  sentence.
- `/leximine set review | reviewCooldown | negotiate`, and all three in the
  `/leximine` settings listing.
- `scripts/make_trade_audio.py` — Spanish sentence fixtures built from the
  backend's neural voice. SAPI has no Spanish voice on this machine; single
  words survive an English one, a whole sentence does not.
- `scripts/debug_villager.py`, `--only-trade` (one scenario in ~2 min instead
  of ten) and `--trade-shot` (captures the panel, then the flash).
- e2e scenarios 11 (merchant, both outcomes) and 12 (an orphaned review).

### Fixed

- **A review or trade nobody answered disabled the feature permanently.**
  Neither manager had the server-side deadline `LimboManager` has. An entry
  left in `pending` makes every later challenge stand aside, so doors and
  villagers silently stopped gating anything for the rest of the server's
  uptime — no crash, no log line. Reachable by picking up an item mid-review:
  the client tracks one challenge, switches to the item, and the review is
  orphaned. All three now tick together and share one grace constant; the
  timeout path closes the overlay but sends no message or sound, because the
  player stopped waiting long ago.
- **A refused trade ended the negotiation on the spot.** Word mode already
  allowed a pause and a retry, and a sentence is the harder thing to get out
  in one go. A refusal now comes back as a failed attempt with the clock
  still running; only an accept, the final pass or the deadline is terminal.
- **`onAttemptScored` ignored the transcript lock** that `onPartial`
  respects, so an attempt the backend closed *after* the verdict could
  rewrite the frozen line — the flash said `heard X` above a reason written
  about `Y`.
- **Partials inherited the previous attempt's number**, so try two's words
  were labelled `#1`. Harmless until a judge's reason sat beside them, at
  which point it read as the model rejecting a perfectly good request.
  Numbers now come from the backend and appear only when they distinguish
  something: on the transcript from try two onward, and on the reason only
  when it is about a different try than the line above it.
- `/leximine testtrade` spawns its own merchant via `world.spawnEntity`.
  Summoning one over RCON reports "Summoned new Farmer" and then leaves
  nothing any selector can find — not difficulty, not suffocation, not
  persistence — and the only symptom was the command claiming no merchant was
  nearby. Giving that villager a profession also fills in that profession's
  recipes, and a farmer's first offer sells emeralds, so the offers are
  cleared before the test one is added.

### Verified

Full e2e suite green at every stage — 12 scenarios, driven over RCON against
real game state. Both HUD states captured and inspected rather than assumed;
each screenshot found a real bug. Installed jar byte-identical to the build
that passed.
