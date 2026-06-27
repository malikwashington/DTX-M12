# DTX-MULTI 12 — live-rig automation, reverse-engineered

**Turn the page of your charts and your drum rig reconfigures itself** — the right kit, sounds, and
tempo load for each song, hands-free, across a whole show. This is the computer-side toolkit that
makes that work for a Yamaha **DTX-MULTI 12** (M12) percussion module driven from an iPad.

It sits on **four reverse-engineered surfaces across two vendors** — the M12's SysEx protocol, its
firmware, its `.MT*` save format, and forScore's `.4sb` backup format — plus a generative
percussion-loop producer that programs the kits.

> **Scope & legal.** This reverse-engineers a DTX-MULTI 12 and a forScore library **I own**, for
> interoperability and research. **No Yamaha firmware image, sample ROM, or documentation, and no
> forScore backups or library data, are included in this repository** — only my own tools and
> findings, with the firmware patch described as a diff (offset + bytes), never a redistributable
> binary.

---

## What it does in a show

forScore (the iPad chart reader) **natively** sends a kit-select MIDI message when you open a chart,
and the M12 selects a kit from it. Those are the vendors' own features. The work here is **programming
both sides in bulk** so an entire setlist is wired at once instead of tapped in by hand across 70+
charts on a screen smaller than your pinky:

```
midi-tools/setlist_sync.py  SHOW.4sb  --save SHOW.MTA  --setlist "Sula and the Joyful Noise"
```

For each chart in the setlist, in order, it:
1. finds the M12 kit **named after the song** (or, with `--by-order`, the Nth kit),
2. writes that kit's **Program Change** into the chart's forScore MIDI Send (fired on page-open),
   led by a **Stop** byte so the prior pattern halts first,
3. sets that kit's **tempo** in the M12 save from the chart's stored BPM.

Output is a new `.4sb` and a new save file (the inputs are never touched); `--dry-run` prints the
plan first. Restore the `.4sb` in forScore and load the save on the M12, and the show drives itself
on page-turns.

---

## The reverse-engineering foundation

Four undocumented surfaces, each recovered independently.

### 1. The M12 SysEx protocol — `midi-tools/m12_control.py`, `m12_midi_spy.py`
`m12_midi_spy.py` is a man-in-the-middle bridge placed between the iPad app and the M12 over USB
MIDI; it recovered the app↔device protocol from live traffic. The recovered surface (model `7F 0F`):

```
param write    F0 43 1n 7F 0F aH aM aL dd F7          (single parameter, no checksum)
dump request   F0 43 2n 7F 0F aH aM aL F7             (module replies with bulk blocks)
bulk block     F0 43 0n 7F 0F cH cL aH aM aL [data] chk F7      chk = (−Σ) & 0x7F
```
`m12_control.py` is the live read/write library over that protocol (`read_pad`, `set_pad`,
`set_voice` against the category-`0x10` per-pad voice block).

### 2. The M12 firmware — Ghidra RE + a shipped patch (`firmware/`)
Static RE of the updater image `8H39OS_.PGM` (**offline only, no writes to the device**)
reconstructed its structure and **confirmed the protocol from the other direction**:

| fact | value |
|---|---|
| container | `InstallerFile` wrapper, 0x80-byte header (checksum @`0x64`, size @`0x68`) |
| code image | first `0x140000` of payload (`code.bin`); remainder ≈ 6.9 MB wave ROM / preset data |
| CPU | SH-2A, **big-endian**; load base `0x0C000000` (SDRAM / CS3) |
| functions | 2531 (Ghidra auto-analysis) |

The SysEx **transmit builder `FUN_0c023106`** emits exactly the frame format above — so the wire
format was arrived at **two independent ways**: from intercepted traffic, and from the firmware code
that produces it. A patched build (`firmware/experiment/`) adds **Feature 7** (re-striking a playing
pattern restarts it instead of stopping):

```
patch:  code.bin 0x44038   8B 1C -> 00 09   (NOP the pattern-toggle stop branch)
        installer checksum + size header recomputed; PROG block only, BOOT untouched
```
Recoverable by design (BOOT untouched). *Status: built + checksum-validated; on-hardware flash-test
is the open item.*

### 3. The M12 save format (YSFC) — `midi-tools/ysfc.py`
The M12's USB save files (`.MTA` all-data, `.MTK` kit, `.MTP` pattern, `.MTW` wave) are undocumented
**YSFC** containers. `ysfc.py` reverse-engineers the chunk directory and the kit / waveform / pattern
catalogs (byte-verified against a known save), **extracts waveforms to WAV**, and performs a proven
**checksum-free per-kit tempo edit** (the format carries no save checksum) — the save-side write the
orchestrator uses to set each kit's tempo.

### 4. The forScore `.4sb` backup format — `midi-tools/forscore_4sb.py`
forScore's backup is a flat `<--4SBV02-->` container whose first member is a binary-plist metadata
store (titles, keywords, setlists, per-chart MIDI Sends). `forscore_4sb.py` **round-trips it
byte-identically** (a no-op edit is bit-for-bit; `verify` asserts it), decodes the per-chart
kit-select Send (an `NSKeyedArchiver` array of `{value, kind}` commands), and edits those Sends in
bulk — re-compressing only the one member it changes. That's how a whole setlist's page→kit mappings
get programmed without opening forScore. See `midi-tools/forscore_4sb_README.md`.

---

## Two write paths into the M12 — and why you need both

Programming over **MIDI/SysEx** and editing the **save file** reach *non-overlapping* parts of the
unit. This isn't a preference; it's a hard limit in the M12's MIDI implementation.

**Live MIDI / SysEx** (`m12_control.py`) reaches only the **current-kit edit buffer**. Verified live:
you can build a whole kit over SysEx — voices, volume, pan, EG, **and the kit name** (cat `0x14`
off `0x00–0x0F` is writable) — and select kits over MIDI (Bank Select + Program Change). But:
- the edit buffer is **volatile** — nothing persists until you **STORE**, and **STORE has no
  MIDI/SysEx command**; it's a front-panel button. So the unit can't be fully programmed headless
  (one panel press per kit you bank).
- some fields are **read-only over SysEx** even though they read back — notably **stored kit tempo**
  (writes are rejected); writability is per-parameter.
- **patterns, waveforms, songs, and the kit *library* aren't in the SysEx address space at all** —
  only the kit edit buffer (cat `0x00–0x28`) answers. There is **no MIDI path into flash storage.**

**The save file** (`ysfc.py`, thumb drive) reaches **everything persistent** — all 200 kit slots,
stored tempo, patterns, waveforms, trigger setups — with no checksum to satisfy. But it's **offline**:
edit on the computer, copy to USB, load on the unit.

*Why:* Yamaha's MIDI spec exposes only live edit + real-time performance control; persistent flash
(library / patterns / waves) is reachable only through the file format, with STORE the one gated
bridge from edit-buffer → flash. **So the orchestrator uses both:** MIDI/SysEx for live control and
tone, the save file for tempo and anything stored.

---

## The producer (loop generation) — `loop-pipeline/`
`role_producer.py` generates role-based gospel/R&B percussion loops, rendering a stereo preview with
the **real M12 voice samples + applied voice parameters**, plus `.mid` and `.params.json`. Tone
design exploits the recovered parameter surface — **voice = sample × cat-`0x10` params** — so the
palette is far larger than the raw samples (a "snap" is a clap with a tight envelope + resonant
filter). `groove_producer.py` mines style envelopes from reference loops; the feel rules (steady
timekeeper pulse, space in the ornament roles, backbeats carrying a subtle voice) define quality over
grid statistics. A `/make-loop` skill + `groove-producer` subagent wrap the generator.

```
cd loop-pipeline && ../.venv/bin/python3 role_producer.py compose --bpm 80 --meter 44 --out test
```

---

## Layout
- `midi-tools/` — the integration + control layer: `setlist_sync.py` (the orchestrator),
  `forscore_4sb.py` (.4sb RE + editor), `ysfc.py` (M12 save RE), `m12_control.py` (live SysEx API),
  `m12_midi_spy.py` (the MITM bridge), `m12_dump_decode.py` (checksum-verified dump decoder).
- `loop-pipeline/` — the producer (`role_producer.py`, `groove_producer.py`); output in `loops/`.
- `firmware/` — RE + patching: `pgm_checksum.py`, `sh2a_xref.py`, `ghidra_scripts/`, `experiment/`
  (the Feature-7 build). **Yamaha binaries are git-ignored** (`*.PGM`, `code.bin`, sample ROM).
- `docs/` — `FIRMWARE_RE.md`, `M12_PARAM_MAP.md`, `DOSSIER.md`, `RECOVERY.md`, `STATUS.md`.

## Toolchain (reproducible)
- **Ghidra 12.1.2**: import `code.bin` as `SuperH:BE:32:SH-2A`, base `0x0C000000`; scripts in
  `firmware/ghidra_scripts/`. `firmware/sh2a_xref.py` is a pure-Python SH-2A literal xref engine.
- `firmware/pgm_checksum.py` — installer checksum/size recompute (repacking a patched image).
- `midi-tools/`: `python3` + `mido` (live MIDI only); `forscore_4sb.py` and `ysfc.py` are
  **standard-library only**.

## Firmware safety
The pristine recovery image is the revert path — **do not flash it expecting the patch.** Recovery:
copy a pristine `8H39OS_.PGM` to a USB root, power on holding **^ (up)**, press **[ENTER]**. Patch
the PROG block only, never BOOT; don't lose power mid-write.

## Status
- **Done:** the four RE surfaces (SysEx protocol — MITM + firmware-confirmed; firmware structure; the
  YSFC save format; the forScore `.4sb` format), the live control API, waveform extraction, the
  forScore Send editor, the per-kit tempo edit, the loop producer, and the `setlist_sync` orchestrator.
- **Built, flash-test pending:** the Feature-7 firmware patch.
- **Used in production:** the page-turn show-automation workflow, on real gigs.
