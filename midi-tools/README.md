# midi-tools/ — live M12 control over USB MIDI

The M12's SysEx protocol (model `7F 0F`): param write `F0 43 1n 7F 0F aH aM aL dd F7`,
dump request `F0 43 2n 7F 0F aH aM aL F7`, bulk block `F0 43 0n 7F 0F cH cL aH aM aL [data] chk F7`.
Address = (category, index, offset). See `docs/M12_PARAM_MAP.md`.

## Tools
- `m12_control.py` — read/write M12 voice params live (the working API). `set_pad`, `param`, `dump`.
- `m12_dump_request.py` — READ-ONLY dump requester: `--ports`, `--scan` (categories),
  `--enum CAT` (indices), `--sweep`, `--addr`. Writes a `.syx`.
- `m12_dump_decode.py` — decode a captured `.syx` dump → address-block map (checksum-verified).
- `syx_view.py` — compact one-line-per-block viewer (ASCII-aware).
- `dtxm12_probe.py` — identity round-trip + probe battery.
- `m12_midi_spy.py` — MITM monitor/bridge/emulate (recover the app↔M12 protocol).
- `ysfc.py` — **offline save-file tool** (the `.MT*` / YSFC family — `.MTA`/`.MTK`/`.MTP`/`.MTW`).
  Read-only `map`/`info`/`kits`/`waves`/`patterns`; `extract-waves FILE --out DIR` (DWAV → WAV);
  `set-tempo FILE --kit NAME --bpm N` (the proven checksum-free per-kit tempo edit; writes a NEW
  file by default). Format notes in the module docstring + `docs/DOSSIER.md` §6. **Note:** extracted
  WAVs are Yamaha factory samples — kept local only (gitignored), never committed.
- `forscore_4sb.py` — **forScore `.4sb` backup** reader/editor (iPad side of the rig).
  `info`/`scores`/`search`/`midi`/`links`/`setlists`; `inject-stop` prepends `FC` (MIDI Stop)
  to a chart's MIDI Send so opening it stops the running pattern then selects the kit — the
  forScore-side automation of the `SeqCtrl=in` stop. See `forscore_4sb_README.md`. **Note:**
  `.4sb` backups can hold copyrighted sheet music → gitignored (`*.4sb`); ship the reader, not data.

Prereq on the unit: **UTIL → MIDI → IN/OUT = USB**, device number all/1.
For the sequencer Stop (forScore `FC`) to work: **UTIL6-8 SeqCtrl = in / in-out**.

## captures/
Archived `.syx` dumps from the RE session (kit-buffer scans, per-category enumerations,
tempo/pad-function diffs). The decoded results are in `docs/M12_PARAM_MAP.md`.
