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
- `capture_voices.py` — **render preset voices to WAV** off the audio interface (the 1,277
  presets aren't in any file). Selects each voice over SysEx (cat 0x10), triggers a note, records
  the Scarlett (avfoundation `:3`), splits/trims/normalizes. Calibrates each category's voice
  count live (the clamp). Default = the percussion + electric-perc set; pass category bytes for
  others. Output → `samples/presets/<category>/` (**gitignored** — Yamaha audio, never committed).
- `forscore_4sb.py` — **forScore `.4sb` backup** reader/editor (iPad side of the rig).
  `info`/`scores`/`search`/`midi`/`links`/`setlists`; `inject-stop` prepends `FC` (MIDI Stop)
  to a chart's MIDI Send so opening it stops the running pattern then selects the kit — the
  forScore-side automation of the `SeqCtrl=in` stop. See `forscore_4sb_README.md`. **Note:**
  `.4sb` backups can hold copyrighted sheet music → gitignored (`*.4sb`); ship the reader, not data.

Prereq on the unit: **UTIL → MIDI → IN/OUT = USB**, device number all/1.
For the sequencer Stop (forScore `FC`) to work: **UTIL6-8 SeqCtrl = in / in-out**.

## Programming over MIDI vs. editing the save file — what each can reach

Two non-overlapping routes. **Pick by what you're changing**, not preference.

### Live MIDI / SysEx (`m12_control.py`) — real-time, but only the *current-kit edit buffer*
**Verified live (2026-06-26):** you can build a whole kit over SysEx — assign voices,
volume, pan, EG, **and the kit name** (cat 0x14 off 0x00–0x0F is writable) across the pads
— then press **[STORE]** once and it persists (confirmed by reload-from-flash). Kit *select*
is plain MIDI (Bank Select 125 / LSB / Program Change on ch10). Great for tone design and the
sound-matcher. **Limits, and why:**
- **It only writes the edit buffer (the loaded kit), which is volatile.** Nothing persists
  until you **STORE**, and **STORE has no MIDI/SysEx command** — it's a front-panel button.
  So the unit can't be *fully* programmed headless: one panel press per kit you bank.
- **Some fields are read-only over SysEx even though they read back** — notably **stored kit
  TEMPO (cat 0x14 off 0x14/0x15): writes are rejected.** Writability is per-parameter; the
  dump exposes more than is writable. Tempo must go through the save file (or the panel).
- **Patterns, waveforms, songs, and the kit *library* are NOT in the SysEx address space.**
  Only the kit edit buffer (cat 0x00–0x28) answers requests. You can't transfer a pattern, a
  sample, or a whole 200-kit library over MIDI — there is no MIDI path into flash storage.
- *Why:* Yamaha's MIDI spec exposes only live edit + real-time performance control; persistent
  flash (library/patterns/waves) is reachable only through the file format, with STORE as the
  one gated bridge from edit-buffer → flash.

### Save-file editing (`ysfc.py`, thumb drive) — the whole persistent store, but offline
Reaches **everything in flash**: all 200 kit slots, **stored tempo**, patterns (DPTN),
waveforms (DWAV), trigger setups, utility — no checksums, so hand-edits load. **Limit:** it's
**not live** — edit the `.MT*` file on the computer, copy to USB, load on the unit.

**Rule of thumb:** live tone-shaping / kit-select / sound-matching → **MIDI**; stored tempo,
patterns, waves, or bulk kit-library work → **save file**.

## captures/
Archived `.syx` dumps from the RE session (kit-buffer scans, per-category enumerations,
tempo/pad-function diffs). The decoded results are in `docs/M12_PARAM_MAP.md`.
