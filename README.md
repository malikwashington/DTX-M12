# DTX-MULTI 12 — prompt-driven producer & firmware toolkit

A computer-side toolkit for the Yamaha **DTX-MULTI 12** (M12) percussion module, built around
recovering the device's **undocumented SysEx parameter surface** and statically
**reverse-engineering its firmware**. Two threads sit on that foundation:

1. **A generative percussion-loop producer** — role-based gospel/R&B loops the user plays
   standalone on the M12 while drumming kick/snare/hat live.
2. **A control + RE + firmware-patching toolkit** — drives the M12's full parameter surface over
   USB SysEx, and patches the firmware for behavior the parameters can't reach.

The device's parameter protocol is undocumented by the vendor, so it was recovered three
independent ways that cross-check each other.

> **Scope & legal.** This reverse-engineers a DTX-MULTI 12 I own, for interoperability and
> research. **No Yamaha firmware image, sample ROM, or documentation is included in this
> repository** — only my own tools and findings, with the firmware patch described as a diff
> (offset + bytes), never a redistributable binary.

---

## The reverse-engineering chain

### 1. Protocol interception (man-in-the-middle)
`midi-tools/m12_midi_spy.py` is a MITM monitor/bridge placed between the iPad control app and the
M12 over USB MIDI, used to recover the app↔device protocol from live traffic. That capture yielded
the SysEx model `7F 0F` surface:

```
param write    F0 43 1n 7F 0F aH aM aL dd F7          (single parameter, no checksum)
dump request   F0 43 2n 7F 0F aH aM aL F7             (module replies with bulk blocks)
bulk block     F0 43 0n 7F 0F cH cL aH aM aL [data] chk F7
```
Address = `(category, index, offset)`; `chk = (−Σ(cH..last data)) & 0x7F`.

### 2. Firmware reverse-engineering (Ghidra, offline)
Static RE of the updater image `8H39OS_.PGM` — **offline only, no writes to the device** —
reconstructed its structure and confirmed the protocol from the other direction:

| fact | value |
|---|---|
| container | `InstallerFile` wrapper, 0x80-byte header (checksum @`0x64`, size @`0x68`) |
| code image | first `0x140000` of payload (`code.bin`); remainder ≈ 6.9 MB wave ROM / preset data |
| CPU | SH-2A, **big-endian** |
| load base | `0x0C000000` (SDRAM / CS3); `RAM = file_off − 0x80 + 0x0C000000` |
| functions | 2531 (Ghidra auto-analysis) |

The load base was validated two ways: PC-relative literal pointers cluster in
`0x0C000000–0x0C2FFFFF` (~34k of 38k), and at that base they resolve to real strings and to
`sts.l pr,@-r15` function prologues.

The key result: the SysEx **transmit builder `FUN_0c023106`** emits exactly the frame format above
(model group `7F 0F`, `count = (cH<<7)|cL`, `chk = (−Σ)&0x7F`). **This independently confirms, from
the firmware, the protocol that was first recovered from the MITM capture** — the same wire format
arrived at two ways: from traffic, and from the code that produces it. A label pool at
`0x0C08DFE8…0x0C08FB78` yielded **176 parameter labels** (RevTime, Cutoff, Resonance, ChoPan, …).

Static RE has a documented limit: the parameter dispatch runs through **RAM-resident
function-pointer tables populated at boot**, so the complete `(cat,index,param) → storage` map only
exists at runtime — it is captured live and decoded by `m12_dump_decode.py` (checksum-verified;
`--selftest` passes).

### 3. A feature shipped via firmware patch
`firmware/experiment/8H39OS_.PGM` is a patched build adding **Feature 7**: re-striking a playing
pattern restarts it from the top instead of stopping.

```
patch:  code.bin 0x44038   8B 1C  ->  00 09        (NOP the pattern-toggle stop branch)
        installer checksum (@0x64) + size header (@0x68) recomputed so the device accepts the image
        PROG block only; BOOT untouched
```
Because only the PROG block changes and BOOT is untouched, a bad patch re-flashes cleanly from the
archived pristine image — i.e. **recoverable by design**. *Status: built and checksum-validated;
on-hardware flash-test is the open item.* A related feature (stop all patterns) was solved without
firmware — forScore sends raw MIDI `FC` (Stop) with `UTIL6-8 SeqCtrl = in`.

---

## The producer (loop generation)

`loop-pipeline/role_producer.py` is the generator: role-based gospel/R&B percussion, rendering a
stereo preview with the **real M12 voice samples + applied voice parameters**, plus `.mid` and
`.params.json`. Tone design exploits the recovered parameter surface — **voice = sample × cat-`0x10`
params** — so the timbral palette is far larger than the 99 raw samples (a "snap" is a clap with a
tight envelope and a resonant filter). `groove_producer.py` mines style envelopes from reference
loops; the feel rules (steady timekeeper pulse, space carried by the ornament roles, backbeats
carrying a subtle voice) define quality over grid statistics. A `/make-loop` skill and a
`groove-producer` subagent wrap the generator.

```
cd loop-pipeline && ../.venv/bin/python3 role_producer.py compose --bpm 80 --meter 44 --out test
```

---

## Layout
- `loop-pipeline/` — the generator (`role_producer.py`), style mining (`groove_producer.py`), output in `loops/generated/`.
- `samples/` — 99 real M12 voice WAVs (the renderer's voices).
- `midi-tools/` — live control + capture: `m12_control.py` (read/write API), `m12_dump_request.py`, `m12_dump_decode.py`, `m12_midi_spy.py` (the MITM bridge). See `midi-tools/README.md`.
- `firmware/` — RE + patching: `8H39OS_.PGM` (**pristine recovery image — do not modify**), `experiment/` (patched Feature-7 build), `code.bin`, `pgm_checksum.py`, `sh2a_xref.py`, `ghidra_proj/`, `ghidra_scripts/`, `re_evidence/`.
- `docs/` — `FIRMWARE_RE.md`, `M12_PARAM_MAP.md`, `STATUS.md`, `RECOVERY.md`, the Data List + service manual.

## Toolchain (reproducible)
- **Ghidra 12.1.2** project at `firmware/ghidra_proj/M12` — import `code.bin` as `SuperH:BE:32:SH-2A`, base `0x0C000000`. Reusable GhidraScripts in `firmware/ghidra_scripts/` (`DumpRE`, `FindSysex`, `Decomp`, `Callers`, …).
- `firmware/sh2a_xref.py` — pure-Python SH-2A literal xref engine (base `0x0C000000`).
- `firmware/pgm_checksum.py` — installer checksum/size recompute (for repacking a patched image).
- `midi-tools/m12_dump_decode.py` — parses a captured `.syx` dump into the full address-block map, every checksum verified.

## Firmware safety
`firmware/8H39OS_.PGM` is the **pristine recovery image — do not flash it expecting the patch; it is
the revert path.** The patched build is `firmware/experiment/8H39OS_.PGM`. Recovery: copy a pristine
`8H39OS_.PGM` to a USB root, power on holding **^ (up)**, press **[ENTER]**. Patch only the PROG
block, never BOOT; don't lose power mid-write.

## Status
- **Done:** SysEx protocol recovery (MITM + firmware-confirmed), the live control API
  (`m12_control.py`), live param capture/decode, and the loop producer.
- **Built, flash-test pending:** the Feature-7 firmware patch.
- **Active:** iterating the loop generator against player feedback (the player is the scoring function).
