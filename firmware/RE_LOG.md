# DTX-MULTI 12 firmware RE — running log

Goal: recover the **complete editable parameter map** (every SysEx `7F 0F` address →
parameter, with name / min / max / default) so the computer-side "producer" can drive
the full voice + FX + groove surface. This is offline file analysis only — **no writes
to the M12** (the MIDI write tools are hard-denied in settings).

## Image facts (pinned & validated 2026-06-23)
- `8H39OS_.PGM` is an **installer wrapper**: 0x80-byte header (ASCII `InstallerFile DTXM12…`),
  then the de-headered image (== `payload.bin`). Checksum at hdr 0x64, size at 0x68
  (`pgm_checksum.py`).
- **All executable code = first 0x140000 of the payload** (== `code.bin`). The remaining
  ~6.9 MB has essentially no SH-2A function prologues → it is **data** (wave ROM / presets).
- **Load base = `0x0C000000`** (SDRAM / CS3 on the SH7206-class SH-2A), **big-endian**.
  Validated: pointer constants cluster at 0x0C000000–0x0C2FFFFF, and those pointers
  resolve to real strings ("Now working…", "USB device…", Renesas copyright) and to
  `4F22` (`sts.l pr,@-r15`) prologues.
- Address mapping:  `RAM = file_off − 0x80 + 0x0C000000`  ⇔  `file_off = RAM − 0x0C000000 + 0x80`.
- Code stats: ~2565 function prologues (`4F22`), ~11k distinct PC-relative literal pointers.

## Tooling
- `sh2a_xref.py` — pure-Python SH-2A literal-pointer xref engine (REWRITTEN to use base
  0x0C000000). Builds {pointer → loader sites} and {prologue addrs}; lists code-referenced
  strings. Runs with `../.venv/bin/python3 sh2a_xref.py`.
- **Ghidra 12.1.2 + openjdk@21** installed via Homebrew. openjdk@21 is keg-only; the
  Ghidra launcher picks the macOS `/usr/bin/java` stub from PATH first, so headless runs
  are launched with an **inlined PATH** prefix:
  `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" analyzeHeadless …`
  (also set `JAVA_HOME_OVERRIDE` in the install's `support/launch.properties`).
  Import: raw `code.bin`, processor `SuperH:BE:32:SH-2A`, base `0x0C000000`.
  Project: `firmware/ghidra_proj/M12`.
- rizin is installed but its `sh` plugin decoded the SH-2A code as "invalid" in quick
  tests — **using Ghidra for disassembly/decompilation instead.**

## Findings so far
1. **FX/parameter label pool** — RAM `0x0C08DFE8 … 0x0C08FB78`, 176 null-terminated
   `Label=` tokens (RevTime, Cutoff, Resonance, LowGain, ChoPan, PanDirectn, RotorS…).
   Extracted to `firmware/param_labels.txt`. This is the human-readable name set for the
   effect/voice parameter surface.
2. **FX descriptor tables** — RAM ~`0x0C096C00 … 0x0C098000` (data region). Entries pair a
   **handler function pointer** (`0x0C02xxxx`) + **label pointer** + **range bytes**
   (e.g. `FF FF 01 FF FF 01 …` = min/max/default-ish), grouped per effect algorithm
   (Reverb, TempoFlanger, Isolator, sDelay, Delay LR St, 3-band comp…). ~19 effect-type
   headers found. Exact struct stride / field semantics: IN PROGRESS (decompiling the
   handler funcs in Ghidra to read the table-walk).
3. The handler functions that load these labels cluster at RAM `0x0C029000–0x0C02E000`
   (the FX edit/UI code).

## Cross-reference target
`docs/M12_PARAM_MAP.md` already documents category `0x10` (per-pad voice: vol/pan/sends/
tune/cutoff/res/EG) from live capture. Firmware RE should fill its TBDs: kit-common
`0x00`, per-pad `0x02`/`0x21`, the pad→index table, and the full FX param ranges.

## Correction (decompiler results)
- Ghidra analyzed `code.bin` successfully: **2531 functions**.
- The `0x0C096xxx` block is a **menu tree, NOT a clean SysEx parameter table.** The words I
  read as "handler pointers" (0x0C021712, 0x0C02171A …) are **not code** (force-disassemble
  → no instruction). `FUN_0c0217ea` decompiles to ASCII→coordinate parsing with range checks
  [0,6]/[0,0xF] — i.e. **menu page-ID parsing** (cf. the `KIT6-1-` string), not param access.
  ⇒ abandoned the "FX descriptor struct {label,min,max,default,addr}" interpretation.
- The label pool (`param_labels.txt`, 176 `Label=`) is still valid as the **name set**.

## SysEx parser located (in progress)
Functions using the Yamaha SysEx constants (by decoded immediates), strongest first:
- **`FUN_0c023106`** — consts {0x0F,0x43,0x7F,0xF0} (ALL four) → prime `F0 43 ?n 7F 0F …` parser.
- **`FUN_0c109bf0`** — consts {0x0F,0x43,0x7F,0xF0} → second parser/dispatch candidate.
- `FUN_0c103baa`, `FUN_0c042042` — include 0x7E → **universal/identity** SysEx (the `7E 7F 06 02`
  identity reply), not param dispatch.
Decompiling these + their callees to recover the (cat,index,param) → storage+bounds dispatch.

## FINAL STATUS (this session)
**Done & proven:**
- Image layout + load base `0x0C000000` (validated). Ghidra project built (2531 funcs).
- **SysEx dump/transmit protocol CONFIRMED from firmware** (`FUN_0c023106`): exactly
  `F0 43 0n 7F 0F cH cL aH aM aL [data] chk F7`, model 0x7F0F, chk=(−Σ)&0x7F. Independent
  validation of the protocol the project relies on.
- Bulk-dump call chain mapped (Utility menu → `FUN_0c0232dc` → `FUN_0c023106`).
- Label pool (176 names) → `param_labels.txt`.
- **Built + unit-tested `midi-tools/m12_dump_decode.py`** (`--selftest` PASS) — decodes a
  captured dump into the full address-block map with checksum verification.

**Architectural blocker found (why the static per-param map is out of reach):** the MIDI/
parameter dispatch goes through **RAM-resident function-pointer tables** (`jsr @rN`, rN→
0x0C009xxx / 0x0C1BDxxx) populated at boot — the static image lacks the resolved bindings.
The `0x0C096xxx` block is a menu tree, not a flat param table.

**Recommended completion path (needs the hardware, currently denied):** capture one live
dump (`F0 43 20 7F 0F 00 00 00`), run `m12_dump_decode.py`, cross with `param_labels.txt` +
Data List + `docs/M12_PARAM_MAP.md`. See `docs/FIRMWARE_RE.md` for the full write-up.
