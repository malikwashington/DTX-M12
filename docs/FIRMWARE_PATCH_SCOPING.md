# Firmware patch scoping — loop-restart & stop-on-pad (2026-06-23)

Target image: `firmware/code.bin` (SH-2A BE, base 0x0C000000) in `firmware/ghidra_proj/M12`.
Goal: assess feasibility/risk of patching in behavior that isn't exposed as a parameter.

## Located
- **All-Sound-Off / mute-all-voices routine: `FUN_0c04898a`** (RAM 0x0C04898A). It runs a
  `while (n < 100)` loop clearing 100 voice slots (stride 0xF1C), with flag bits selecting
  All-Sound-Off (`&4`) / Reset-Controllers / All-Note-Off (`&0x40`/`&0x80`). This is the
  primitive feature #6 (stop-on-pad) must call. ✅ found.
- **Sequencer realtime engine: `FUN_0c044c08`** (Start 0xFA / Stop 0xFC / Cont 0xFB / Clock
  0xF8) plus the seq primitives it calls, in the 0x0C044xxx region; more pattern machinery in
  0x0C12xxxx. The pad-pattern toggle lives in this subsystem. ✅ region found.

## NOT yet pinned (needs deeper RE)
- The exact **"pad re-strike while pattern playing → stop"** branch (#7). It's inside the
  sequencer subsystem but the call graph is broad (dozens of functions across 0x0C044xxx /
  0x0C12xxxx); the single toggle site isn't isolated yet.
- For #6: the **pad-strike dispatcher** hook point + a free code region for a trampoline
  (the patch must ADD a "pad function = stop" path that calls `FUN_0c04898a`).

## Difficulty / risk — honest
- **Neither is a one-byte flag flip.** Both need control-flow changes / added behavior on a
  register-passing SH-2A codebase that decompiles noisily.
- **#7 loop-restart:** if the toggle branch is pinned, redirecting `stop()` → `restart()` is
  plausibly surgical. But "restart" likely = `stop()` + seek-to-0 + `start()`, which needs a
  few extra instructions (trampoline). Difficulty: **medium.**
- **#6 stop-on-pad:** inject a new pad-function behavior + hook the pad dispatch to call the
  mute routine. Difficulty: **medium-hard.**
- **Flashing risk is real.** A bad patch can **brick** the unit (worse than the data wipe you
  already hit). Checksum is fixable (`pgm_checksum.py`); a brick is not.

## Recommended path (de-risk before any flash)
1. **Build an emulation/test harness** — run `code.bin` under an SH-2 emulator (QEMU-SH or
   Ghidra's p-code emulator) so patches are tested in software, turning "brick roulette" into
   iterative debugging. This is the single highest-leverage investment and the responsible
   prerequisite for any firmware patching here.
2. **Validate the exact desired behavior with the laptop-in-loop path first** (USB-B MIDI):
   pad emits a marker → laptop sends All-Sound-Off / pattern Stop→Start (restart). Zero brick
   risk, works today, not standalone — good for confirming "this is the behavior I want"
   before committing to firmware.
3. Only then craft + emulate-test the patch, and flash via thumb drive (or USB-A virtual
   drive) with the pristine `.PGM` kept safe.

## Status
Routines located; exact patch sites for #7 toggle and #6 pad-hook still need RE. This is a
real but serious multi-step firmware project, not a quick flip.
