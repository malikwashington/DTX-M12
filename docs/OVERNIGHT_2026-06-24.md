# Overnight session summary — 2026-06-24

Three things landed while you slept: the **stop button is solved (no firmware)**, the
**feature-7 experiment firmware is built and ready to flash**, and the **groove producer is
built end-to-end** with sample loops.

## 1. Stop all patterns — SOLVED, no firmware
forScore can send raw **hex**, and a MIDI **Stop** is the single byte **`FC`**. The M12 halts
all patterns on receiving it. So:
- Configure a forScore control to send hex **`FC`** (it's on your clipboard).
- **Requires UTIL6-8 `SeqCtrl` = `in` or `in/out`** or the M12 ignores it.
- Pad-triggered version (optional): set an M12 pad to transmit a note/CC → forScore "learn"
  binds that incoming message → its action sends `FC` back. Hit pad → all patterns stop.

Two firmware backups were investigated and BOTH confirmed dead ends (the pad-strike dispatch
AND the CC-receive dispatch are both **boot-RAM-gated** — only resolvable with JTAG). So the
forScore `FC` path isn't just easier, it's the only practical no-hardware route. Good that we
checked. Evidence: `firmware/midi_stop_hook.txt`, `firmware/feature6_patch.txt §6`.

## 2. Feature 7 (re-strike restarts the pattern) — experiment PGM ready
`firmware/experiment/8H39OS_.PGM` — patched firmware, checksum recomputed, **only the PROG
block touched** (BOOT untouched → recoverable). Exactly 2 bytes changed from stock plus the
checksum byte. Makes a re-struck playing pattern take the **start-from-top** path instead of
stop. Labeled with `README_EXPERIMENT.txt`.
- **Flash:** copy `8H39OS_.PGM` to a USB root → power on holding **^ (up)** → press **[ENTER]**.
- **Recover:** same combo with a pristine `8H39OS_.PGM` (archived at `firmware/8H39OS_.PGM`).
- **One thing only hardware can confirm:** that start-from-top on an already-playing pattern
  cleanly re-syncs (vs no-ops). If it no-ops, the fallback is a stop+start trampoline (we have
  the free space + emulator ready). Low-risk, recoverable flash.

## 3. Groove producer — built + iterated
`loop-pipeline/groove_producer.py` — the symbolic, style-driven generator we sketched:
- **`build-envelope`**: mines your 5 reference loops (downloads short windows) into groove
  descriptors → `style_envelope.json` (your taste envelope). This is the demoted, good-enough
  role for the transcription work — style mining, not copying.
- **`generate`**: evolutionary search (population → score-vs-envelope → mutate/crossover over
  100+ generations) produces a percussion loop matching your style + a target tempo/meter/
  density, then exports a loadable **SMF** (lanes → loop notes 84/86/88, ch10).
- **Scoring** weights the meaningful descriptors (density, syncopation, rhythmic-shape) and
  enforces constraints: **no kick** (percussion-only) and **leave the backbeat pocket** for
  your live kit.

Sample loops in **`loops/generated/`** (`.mid` + `.json`): 80 BPM 4/4, 87 BPM 6/8, 155 BPM
4/4. They match the envelope's density (~0.29) and syncopation (~0.20) and are bright/shaker-
forward like your references. Load one and have a listen.

### Honest limitations / next steps
- **Lanes are coarse** (low/mid/high by brightness) — your loops are so shaker-dominant that
  almost everything is "high." Fine for groove, but for richer voicing we'd add instrument-
  role lanes (timekeeper/accent/color) and map each to a distinct M12 voice.
- **No hardware audition yet** — the closed loop's "play it on the M12 → capture → score" step
  needs you + the unit (the `stream_match` scaffold does this). Right now it scores in
  symbolic feature-space only.
- **Prompt-driven intent** is currently just tempo/meter/density — next is parsing a real text
  prompt ("sparse 6/8 bell-and-shaker") into targets.
- The generator matches *statistics*, not musical phrasing yet — adding groove-grammar priors
  (clave cells, call-and-response) is the next quality lever.

## Tasks status
- ✅ Stop button (forScore FC). ✅ Feature-7 experiment firmware built. ✅ Groove producer v1.
- Open: flash + on-unit test of feature 7; richer lanes + hardware audition for the producer.
