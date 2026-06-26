# STATUS — resume point (as of 2026-06-24)

Read `CLAUDE.md` first (project + rules), then this. Detailed facts are in auto-memory.

## Where each thread stands

### Loop generator (active focus)
- **`loop-pipeline/role_producer.py`** is the working generator. Gospel/R&B, role-based, renders
  a stereo preview with the real M12 samples + voice params, exports `.mid` + `.params.json`.
- Rebuilt around the player's feel rules (see CLAUDE.md / `groove-producer` memory). Most recent
  correction: **the timekeeper is a STEADY pulse** (don't sparsen it); space lives in the
  conga/ornament layer; **backbeats carry a subtle voice**.
- **Backbeat voice palette** added: `--backbeat clap|snap|snaphi|rim|rimwet` (snap/rim crafted
  from samples + params = tone design).
- **Latest renders to audition** in `loops/generated/`: `role_80_44.wav` (4/4, steady 16th shaker
  + backbeat clap), `role_87_68.wav` (6/8, steady 8th hat + clap), and `bb_clap/snap/snaphi/rim.wav`
  (same 4/4 loop, four backbeat timbres).
- **PENDING — user feedback:** the user was choosing which backbeat sits best, and judging pulse/
  meter/balance on the latest 4/4 & 6/8. Iterate on their notes (it's a tight loop: generate →
  they listen → correct → regenerate). The PLAYER is the scoring function.

### Firmware — feature 7 (re-strike restarts a pattern)
- **`firmware/experiment/8H39OS_.PGM`** built + checksum-fixed + verified (2-byte NOP at code.bin
  0x44038). Labeled `README_EXPERIMENT.txt`. PROG-only → recoverable.
- **PENDING — user was going to FLASH it and TEST.** Flash = hold **^ (up)** at power-on with the
  file on a USB root → `[ENTER]`. **Has not reported the result.** On resume, ASK how it went: did
  a re-strike cleanly RESTART the pattern (vs no-op)? If it no-ops, the fallback is a stop+start
  trampoline (free space + emulator ready — see `firmware/feature6_patch.txt` / FEATURE_6_7_FINDINGS).

### Firmware — feature 6 (stop all patterns) — DONE, no firmware
- Solved: **forScore sends raw hex `FC`** (MIDI Stop) → M12 stops all patterns. Needs
  **UTIL6-8 SeqCtrl = in**. (Both firmware strike-hooks are boot-RAM-gated → JTAG-only; not pursued.)

### Reverse engineering / control — DONE/foundational
- SysEx `7F 0F` read+write proven; full param map captured; `m12_control.py` is the live API.
- Firmware: SH-2A BE @ 0x0C000000; BOOT/PROG split (recovery is independent); see memory + docs.

## Immediate next steps (pick up here)
1. **Ask the user**: (a) how feature-7 flash/test went, (b) which backbeat they liked + any new
   loop feedback.
2. **Iterate the loop generator** on their feedback (swing depth, `--space`, conga-talk density,
   6/8 backbeat stacking, voice/pitch choices).
3. **Extend timbral palettes to other roles** (the user wants this): conga open/mute/slap set,
   hat closed/sizzle, pitched bells — same sample×param approach as the backbeat palette.
4. **Hardware-audition loop** (when desired): stream a generated loop to the M12 + capture, so the
   producer scores against real M12 audio (the `stream_match` scaffold + `m12_control.py` exist).
5. Optional: blend grammar + light evolution; parse a real text prompt into intent; push a loop's
   voice params to the M12 via `m12_control.py` (cat 0x10) and provide the SMF for import.

## Resume checklist
- `cd loop-pipeline && ../.venv/bin/python3 role_producer.py compose --bpm 80 --meter 44 --out test`
  should render a loop (sanity check the toolchain).
- The `/make-loop` skill + `groove-producer` agent live in `.claude/` (restart Claude Code if they
  don't show up in the menus).
- M12 prereqs: UTIL MIDI IN/OUT = USB; SeqCtrl = in (for the forScore stop).
