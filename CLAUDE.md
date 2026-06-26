# DTX-MULTI 12 — prompt-driven producer

A computer-side "producer" for a Yamaha **DTX-MULTI 12** (M12). The user is a drummer/producer
(gospel/R&B). Two threads:
1. **Loop generation** — generate **percussion-only** loops the user plays standalone on the M12
   while drumming kick/snare/hat **live**.
2. **Control + tone design + firmware** — drive the M12's full parameter surface over USB SysEx;
   patch firmware for behavior the params can't reach.

Rig: M12 ↔ Mac over **USB**; iPad runs **forScore** (sends MIDI incl. raw hex); Scarlett 2i2 for
audio capture. **No laptop in the live signal path** — standalone is iPad + M12 only.

> Detailed facts/decisions live in **auto-memory** (loaded each session): project goal, firmware
> RE, firmware recovery, groove-producer rules, SysEx probe. Current state: **`docs/STATUS.md`**.

## Layout
- **`loop-pipeline/`** — the groove producer.
  - `role_producer.py` — the MAIN generator (role-based, gospel/R&B). Run from inside the dir:
    `../.venv/bin/python3 role_producer.py compose --bpm N --meter 44|68|98 --space 0..1
     --backbeat clap|snap|snaphi|rim|rimwet --seed S --out NAME`. Writes a **stereo preview**
    (`.wav`, real M12 samples + voice params applied), `.mid`, and `.params.json` to
    `loops/generated/`.
  - `groove_producer.py` — style-envelope mining from the reference loops. `nmf_transcribe.py` —
    transcription experiments (style-mining only; demoted).
- **`samples/`** — 99 real M12 voice wavs (the renderer's voices; conga, maraca, clave, cowbell,
  tambourine, shaker `274_HOSHAKER`, hats, claps, rims …).
- **`loops/`** — `loop_list.txt` (reference YouTube loops + note routing), `generated/` (output).
- **`midi-tools/`** — live M12 control (see its `README.md`). `m12_control.py` read/write params;
  `m12_dump_request.py` / `m12_dump_decode.py` read M12 state. Captures in `captures/`.
- **`firmware/`** — RE + patching (see its `README.md`). `8H39OS_.PGM` = **pristine — DO NOT MODIFY**;
  `experiment/` = patched feature-7 firmware; `build_patch.py`; `ghidra_proj/`; `re_evidence/`.
- **`docs/`** — `STATUS.md` (current state), `RECOVERY.md`, `M12_PARAM_MAP.md`, `FIRMWARE_RE.md`,
  `FEATURES_AND_MAP.md`, the Data List + service manual.
- **`.claude/skills/make-loop/`** + **`.claude/agents/groove-producer.md`** — the callable
  `/make-loop` skill + the expert subagent (may need a session restart to register).
- Python: **`.venv/bin/python3`** (Homebrew Python blocks global pip; deps live in `.venv`).

## Hard rules
- **Loops = percussion ONLY** (no kick/snare/hat). Gospel/R&B, almost always 4/4 (also 6/8, 9/8).
- **Loop feel rules** (see `groove-producer` memory — these define "good", not grid stats):
  - The **TIMEKEEPER is the PULSE — keep it STEADY/consistent; negative space does NOT apply to
    it** (a loop must have a findable pulse + clear meter).
  - **SPACE / diversity lives in the OTHER roles** (conga talk, counter, color, accents) over the
    steady pulse — across density, timbre, volume (ghosts↔accents), pitch (conga open/mute/slap),
    swing, accent.
  - DEEP low pitch on **beat 1 of every OTHER bar**; HIGH maraca on **beat 4**; no hammered
    downbeats. **Backbeats carry a subtle voice** (clap/snap/rim — empty backbeats are NOT a goal).
    Keep the **counter** (call-and-response) role.
  - **THE PLAYER IS THE SCORING FUNCTION**: generate → they listen → they correct → regenerate.
  - Tone design: voices = sample **×** full cat-0x10 params, so the timbral palette is far bigger
    than 99 samples (e.g. a "snap" is a clap with a tight envelope + resonant filter).
- **Firmware safety**: patch **PROG only, NEVER BOOT**. Recovery = hold **^ (up)** at power-on with
  a pristine `8H39OS_.PGM` on USB; a bad PROG patch is **recoverable**. Don't lose power mid-write.
- **MIDI write to the M12 is authorized** by the user. Stop-all-patterns = forScore sends hex
  **`FC`** (needs **UTIL6-8 SeqCtrl = in**); no firmware needed.
- End git commit messages (if a repo is ever created) with the standard Claude co-author trailer.
