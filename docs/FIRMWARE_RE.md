# DTX-MULTI 12 — firmware reverse-engineering findings

Static RE of the updater image `firmware/8H39OS_.PGM`, done to recover the parameter
surface for the computer-side "producer". **Offline only — no writes to the M12.**

## 1. Image structure (proven, validated)
| fact | value |
|------|-------|
| container | `8H39OS_.PGM` = installer wrapper, 0x80-byte header (`InstallerFile DTXM12…`) |
| header fields | checksum @0x64 (Σ bytes[0x80:]), size @0x68 — see `firmware/pgm_checksum.py` |
| code image | first **0x140000** of payload == `firmware/code.bin` |
| data (rest) | ~6.9 MB, no SH-2A prologues → wave ROM / preset data |
| CPU | SH-2A, **big-endian** |
| **load base** | **`0x0C000000`** (SDRAM / CS3) |
| address map | `RAM = file_off − 0x80 + 0x0C000000` |
| function count | 2531 (Ghidra auto-analysis) |

Base validated two ways: PC-relative literal pointers cluster at 0x0C000000–0x0C2FFFFF
(~34k of 38k), and at that base they resolve to real strings ("Now working…", "USB
device…", Renesas copyright) and to `4F22` (`sts.l pr,@-r15`) prologues.

## 2. SysEx protocol — CONFIRMED FROM FIRMWARE ✅
The transmit builder **`FUN_0c023106`** (RAM 0x0C023106) emits, in order:
`F0 · 43 · 0n · 7F · 0F · cH cL · aH aM aL · data×count · chk · F7`
with model group **`7F 0F`**, `count=(cH<<7)|cL`, and `chk = (−Σ(cH..last data)) & 0x7F`
(the code does `(sum*0xFF)&0x7F`, i.e. `(−sum)&0x7F`). This **independently confirms** the
protocol the project already reverse-engineered from the iPad app:
- write : `F0 43 1n 7F 0F aH aM aL dd F7`  (single param, no checksum)
- read  : `F0 43 2n 7F 0F aH aM aL F7`  → module replies with dump blocks
- block : `F0 43 0n 7F 0F cH cL aH aM aL [data] chk F7`

Call chain for the dump: a Utility **"Bulk Dump"** menu action → `FUN_0c0232dc`
(send_dump_block, passes model `0x7F0F`) → `FUN_0c023106` → MIDI-out.

→ Decoder built & unit-tested: **`midi-tools/m12_dump_decode.py`** (`--selftest` passes).
It parses a captured dump into the full `(category,index,param)→count` map and verifies
every checksum. This is the tool that *completes* the parameter map the moment a live
dump is captured.

## 3. Parameter labels (extracted)
A contiguous label pool at RAM `0x0C08DFE8 … 0x0C08FB78` holds **176 `Label=` tokens**
(RevTime, Cutoff, Resonance, LowGain, ChoPan, PanDirectn, RotorS, AmpType, Thresh…) —
the effect/voice parameter name set. Dumped to `firmware/param_labels.txt`.

## 4. Why the full per-parameter map isn't statically extractable
The MIDI/parameter dispatch runs through **RAM-resident function-pointer tables**
(`mov.l @rN,rM ; jsr @rM`, with rN → `0x0C009xxx` / `0x0C1BDxxx`). Those tables are
populated at **boot**, so the static image does not contain the resolved (address→handler)
bindings — every attempt to follow the write path statically ends at a computed call into
RAM. The `0x0C096xxx` region is a **menu tree** (page descriptors like `KIT6-1-`, parsed by
`FUN_0c0217ea`), not a flat parameter table. Net: the authoritative, complete
`(cat,index,param) → storage + min/max/default` table only exists at runtime.

## 5. Recommended path to a COMPLETE map (next session, at the hardware)
1. With the M12 on USB, send one dump request `F0 43 20 7F 0F 00 00 00` (and/or per-category
   requests). Capture the reply to a `.syx`.
2. Run `m12_dump_decode.py capture.syx` → full address-block map (categories, indices,
   sizes), all checksum-verified.
3. Cross the block addresses with `param_labels.txt` + the Data List PDF + the live cat-0x10
   map in `M12_PARAM_MAP.md` to label every field. That yields the complete producer map
   without further firmware RE.

## 6. Toolchain (reproducible)
- `firmware/sh2a_xref.py` — pure-Python SH-2A literal xref engine (base 0x0C000000).
- Ghidra 12.1.2 project at `firmware/ghidra_proj/M12` (import: `code.bin`,
  `SuperH:BE:32:SH-2A`, base 0x0C000000). Headless launch needs the real JDK ahead of the
  macOS stub: `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" analyzeHeadless …`.
- Reusable Java GhidraScripts in `firmware/ghidra_scripts/`: `DumpRE`, `FindSysex`,
  `Decomp`, `Callers`, `ForceDecomp`, `Disasm` (edit target addresses, re-run with
  `-process code.bin -noanalysis -postScript <Name>.java`).
- Raw evidence dumps: `firmware/{decomp,force,callers,sysex_dump,disasm,ghidra_dump}.txt`.
