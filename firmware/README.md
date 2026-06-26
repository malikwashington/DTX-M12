# firmware/ — DTX-MULTI 12 firmware RE + patching

## Binaries
- `8H39OS_.PGM` — **pristine** stock firmware updater image. DO NOT MODIFY (recovery source).
- `code.bin` — the PROG application image (first 0x140000 of the payload), SH-2A BE @ 0x0C000000.
- `payload.bin` — the de-headered `.PGM` payload. `milestone0/` — original milestone copy.

## Patching
- `build_patch.py` — builds a patched `.PGM` (verifies bytes, fixes checksum). PROG-only.
- `experiment/` — the experimental patched firmware + `README_EXPERIMENT.txt` (Feature 7).
- `pgm_checksum.py` — verify/fix the `.PGM` checksum.
- **Flash/recover:** USB root → power on holding **^ (up)** → `[ENTER]`. See `docs/RECOVERY.md`.

## Reverse engineering
- `sh2a_xref.py` — pure-Python SH-2A literal-pointer xref engine.
- `ghidra_proj/` — Ghidra project (import `code.bin`, `SuperH:BE:32:SH-2A`, base 0x0C000000).
  Other agent projects were removed (regenerable). Launch headless with the JDK inlined:
  `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" analyzeHeadless …`.
- `ghidra_scripts/` — Java GhidraScripts (Ghidra 12 = no Jython). `EmulateFn.java` =
  p-code emulator harness; `DumpRE/Decomp/FindSysex/…` = analysis passes.

## Findings (keepers)
- `RE_LOG.md`, `FEATURE_6_7_FINDINGS.md` — main writeups.
- `feature6_patch.txt` — feature-6 analysis (strike-hook needs JTAG; stop solved via forScore FC).
- `boot_recovery.txt` — BOOT/PROG split + brick verdict (recoverable).
- `midi_stop_hook.txt` — CC-receive hook feasibility (also boot-RAM-gated).
- `param_labels.txt` — extracted FX/voice parameter labels.
- `re_evidence/` — archived raw analysis dumps from the RE agents (intermediate evidence).
