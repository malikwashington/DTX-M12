# DTX-MULTI 12 — Hacking Dossier

Living document. **Goal (revised):** build a **computer-side, prompt-driven producer**
for the M12 — generate percussion grooves to play standalone live, and design kits/voices
across the **full parameter set (documented or not)** to make sounds *without* sampling
(the user's SPD-SX/SPD-SX PRO handle exact samples). No hand-programming the unit.
Focus = the **editable parameter surface** (file format + Data List param map + undocumented
fields). **Storage/sample-size expansion is deprioritized** (§3/§4 kept for reference).

Status legend: ✅ confirmed (by us, on this unit) · 📄 documented (Yamaha/manual) ·
🌐 community report · ❓ open question · 🔬 to test live.

---

## 0. The unit & toolkit
- Unit: Yamaha DTX-MULTI 12, USB MIDI shows **3 ports**; SysEx pipe is **Port 1**.
- Tooling lives in this folder: `dtxm12_probe.py` (run via `.venv/bin/python`).
  Commands: `ports`, `identity`, `probe`, `scan`, `auto`, `monitor`, `write`, `guided`.
- File tools: `extract_waves.py` (DWAV → `waves_out/*.wav`); `ysfc.py` (chunk/kit/wave dump);
  `pgm_checksum.py` (verify/fix firmware checksum). Service manual text → `sm_text.txt`.
- `.venv` holds `mido` + `python-rtmidi` (Homebrew Python blocks global pip).

## 1. SysEx / MIDI — what we've PROVEN on this unit
- ✅ Manufacturer ID `0x43` (Yamaha), model ID `0x18` (M12).
- ✅ Identity Request `F0 7E 7F 06 01 F7` → reply
  `F0 7E 7F 06 02 43 00 41 3E 06 00 00 00 7F F7` (version byte `3E`).
- ✅ **Reads are dead**: silent to every Parameter Request (3n) and Dump
  Request (2n) we sent, across 2- and 3-byte address widths. Verdict: the unit
  does not service MIDI read requests — there is no live address to *discover*
  by reading.
- 🔬 **Writes (1n Parameter Change) are the open frontier.** The service-manual
  test command `F0 43 10 18 5A 00 F7` is a 1n write, so writes are plausibly
  honored even though reads aren't. We have `write --addr .. --data ..` staged
  to test this against the address map (pending research §3). Verification is
  by eye (we can't read back).
- Command grammar (byte after `0x43`): `1n`=Param Change (write),
  `0n`=Bulk Dump, `2n`=Dump Request, `3n`=Param Request. `n`=device number.

## 2. Tempo (fully solved earlier)
- ✅ Save-file offset `0x4DD4`=MSB, `0x4DD5`=LSB; `bpm = MSB*128 + LSB`
  (14-bit big-endian). **No checksum** guards kit data — hand-edits are accepted.
- A kit forces tempo on select only when its KIT1 tempo field is numeric
  (not "hold/off").

---

## 2a. Pad → MIDI output map — ✅ PARTIAL (guided capture)
Captured via `guided` mode → `guided-capture.json`. All pads emit `note_on` on
**channel 10** (`ch9`, 0-indexed). Default kit at capture time.

| Pad | Note | GM name | Pad | Note | GM name |
|----|------|---------|----|------|---------|
| 1  | ❓ — | not captured (gap) | 7  | 36 | Bass Drum 1 |
| 2  | 55 | Splash Cymbal      | 8  | 31 | (below GM range) |
| 3  | 53 | Ride Bell          | 9  | 42 | Closed Hi-Hat |
| 4  | 48 | Hi-Mid Tom         | 10 | 35 | Acoustic Bass Drum |
| 5  | 45 | Low Tom            | 11 | 34 | (below GM range) |
| 6  | 41 | Low Floor Tom      | 12 | 46 | Open Hi-Hat |

Open items on this map:
- ❓ **Pad 1** never registered — re-capture needed.
- ⚠️ **plus / minus buttons**: both recorded as `control_change` CC0=125,
  i.e. indistinguishable. Suspect a duplicate-message capture, not real input.
  Re-capture with a pause between the two.
- `active_sensing_samples` came back empty (harmless).

## 3. Sample / wave memory architecture — ✅ FORMAT CRACKED (from TEST.MTA)
From the `TEST.MTA` ALL backup (a Yamaha **YSFC** container — see §6):
- ✅ Waveforms live in the **DWAV** data chunk. This backup holds **99 waveforms**,
  ids **201–299**, ~20.5 MB total PCM. Catalog is the **EWAV** chunk
  (name / index / byte-size / id per entry, 0x20 stride).
- ✅ **Per-waveform on-disk layout:** `0x50`-byte header then raw PCM.
  - name[16] at +0x00, sample-rate (BE u16) at +0x36 = `AC 44` = **44100 Hz**,
    loop/length fields around +0x40.
  - PCM is **signed 16-bit, BIG-ENDIAN, mono**. Samples stored back-to-back;
    block size = next entry's offset − this one's.
- ✅ Extractor written: `extract_waves.py` → dumps every wave to `waves_out/*.wav`
  (byte-swaps BE→LE). All 99 round-trip to valid audio.
- 🔬 **Core ask — push the sample-size limit past the UI:** the file format imposes
  **no checksum and no obvious size cap**; the limit is UI/firmware-enforced, and the
  firmware is plaintext (§7), so the check is findable/patchable. Splice-in test:
  enlarge a DWAV block + fix its EWAV size/offset fields + reload.
- ✅ **Manual-confirmed limits:** user **200 kits / 500 waves / 50 patterns / 10 trigger
  setups**; per-sample **≤~23 s** @44.1k; **16-bit WAV/AIFF only** (else "Illegal file.").
  User memory = **flash ROM (non-volatile)** — lives in the 64 MB wave flash IC13 (§4),
  so the real cap is a firmware budget over that chip, not RAM.
- 🔬 Open: exact total user-wave MB budget (read unit's Wave Memory Status, or §7 disasm).

## 4. Storage & expansion — ✅ HARDWARE MAPPED (service manual)
Source: `yamaha_dtx-multi_12.pdf` SERVICE MANUAL (93pp) → `sm_text.txt`. Block diagram
(p88), LSI pin table (p14), TEST program (p24).
- **CPU:** Yamaha **SWX02 = R8A02032BG**, an **SH-2A** core (pins literally named
  `SH2A-CPU …`), 16.9344 MHz xtal, 32-bit. Has **JTAG + emulator/break pins** exposed.
- **Memory chips:**
  | role | chip | size | type | notes |
  |---|---|---|---|---|
  | Program ROM | IC10 | **64 Mbit = 8 MB** | **Flash** | firmware; ≈ the 8.26 MB `.PGM` |
  | Wave ROM "P2" | IC11 | **256 Mbit = 32 MB** | **mask ROM** | presets — *permanently fixed silicon* |
  | Wave ROM | IC13 | **512 Mbit = 64 MB** | **Flash** | **flashable; presets + USER samples** |
  | SDRAM | IC9 | 64 Mbit×32 = **32 MB** | SDRAM | work RAM / playback |
- ⇒ **Physical ceiling:** user samples live in the **64 MB wave flash (IC13)**, shared
  with the flashable presets. The 32 MB mask-ROM presets (IC11) can't be changed without
  a chip swap. Spec "100 MB (16-bit linear)" ≈ 32 MB mask + 64 MB flash.
- **Expansion:** memory is fixed BGA/TSOP on-board (not socketed). A larger IC13 is a
  reball-level hardware mod and firmware would need to address it. No Motif-style DIMM slot.
- ❓ USB-TO-DEVICE: manual frames it as save/load + format only (not live playback) — to
  confirm in §8.

## 4a. Service / TEST mode — ✅ documented (p24)
- Test items use codes **`A0 xx 00`** (e.g. `A0 20`=Program ROM, `A0 21`=Wave ROM P2,
  `A0 22`=Wave Flash, `A0 23`=SDRAM, **`A0 2B`=Factory Set**, `A0 2C`=Exit). Entry combo
  TBD; 🔬 worth testing whether these are MIDI/SysEx-reachable (could open a read path
  the normal firmware denies — see §1).

## 5. Parameter / editable-surface map — 🔬 IN PROGRESS (the core of the new goal)
- ✅ **DKIT decoded structurally** (TEST.MTA). DKIT @0x5120, size 0xbea20. Internal
  directory @DKIT+0x20, 0x20 stride: `kit-id(u32) | 0 | reloffset(u32) | FF-pad` ×200.
  **Per-kit body = 0xF20 (3872) bytes**; kit 1 body @file `0x6A40`.
- ✅ Kit body layout (partial): `+0x00` name[16]; `+0x10..` kit-common block (levels/
  tempo/MIDI?); from ~`+0x90` a **repeating ~14-byte per-pad voice record**
  (`level 0x64, 0x40, …, 0x28, … 01 01`) — the per-pad sound settings = tone-design target.
- ✅ **Data List in folder** (`_home_httpd_..._dtxm12_en_dl_a0-.pdf`, 24pp): Voice List
  (1,277), Preset Kit/Pattern lists, Effect Type (p15) + Effect Param (p16–19) lists,
  MIDI Data Format (p20–21).
- ⚠️ **CRITICAL (p21): the M12 has NO SysEx parameter editing.** The entire "Parameter
  Change" section = one msg (XG System On reset). **No native address map, no kit/voice
  param writes, no bulk-dump receive.** ⇒ stored-kit editing is **file-route (thumb drive)
  or front panel only**. Confirms+extends §1 (reads dead AND no param writes).
- Field naming for DKIT now derives from: Data List value ranges + diffing kit bodies that
  differ in one known UI parameter (no MIDI address map exists to borrow).

## 5b. Live MIDI control surface — ✅ what CAN be driven over USB (no thumb drive)
Per Data List p20–21, received in real time on USB-MIDI:
- **Selection:** Bank Select `MSB=125/LSB=0` Preset kits, `/LSB=1` user 1–100, `/LSB=2`
  user 101–200, + Program Change → **instant kit switch 1–200**; `MSB=63` → any of 1,277
  voices on non-drum channels. **Trigger:** note-on (pad map §2a).
- **Real-time shaping CCs:** `CC74`=cutoff(Brightness) `CC71`=resonance `CC73`=attack
  `CC75`=decay `CC72`=release `CC31`=sustain `CC91/93/94`=reverb/chorus/variation send
  `CC7`=vol `CC10`=pan + pitch-bend. (Received, not stored — performance layer.)
- 🔬 Test early: how strongly each CC actually sculpts a given **drum** voice (drums may be
  partly fixed). These CCs + voice-select are the **search space for the sound-matcher**.

## 5c. Automated sound-matcher (the centerpiece) — 🔬 harness being built
Closed loop, all over USB-MIDI + audio-in, no DAW: pick voice (Bank+PC) → note-on → set
shaping CCs → capture M12 audio via **Scarlett 2i2 (ffmpeg avfoundation dev [3])** →
spectral/envelope compare to target → optimize over {voice# × CCs}. Output = a recipe
{voice, CCs} → bake into a kit file (one thumb-drive load) for standalone live use.
Tools: mido/rtmidi (out), ffmpeg (capture), numpy/scipy/soundfile (analysis).
Cabling needed: **M12 OUTPUT L/R → Scarlett inputs 1/2.**

## 6. Save file format — ✅ MAPPED (TEST.MTA, an "ALL" backup)
- ✅ **YSFC container.** Header `" 8H39 ALL … Ver 01.00"`, magic `YSFC` @0x30,
  then a top **chunk directory** @0x80: pairs of `tag`(4) + `abs-offset`(4).
  `E***` = catalog/directory, `D***` = data. No checksums on data chunks.
- ✅ Chunk map (offset / size):
  | chunk | off | size | holds |
  |---|---|---|---|
  | EROT/DROT | 0x200 / 0x29e0 | 10 KB | voice-element root + a waveform name table |
  | EKIT/DKIT | 0x240 / 0x5120 | 780 KB | **200 kit slots**, ~3.9 KB each (0x0f1c) |
  | EWAV/DWAV | 0x1b60 / 0xc3b40 | **21.5 MB** | **99 waveforms** (see §3) |
  | EPTN/DPTN | 0x27e0 / 0x153d270 | <1 KB | patterns/phrases |
  | ETRG/DTRG | 0x2840 / 0x153d620 | 2.9 KB | trigger/pad setup |
  | EUTL/DUTL | 0x29a0 / 0x153e180 | 272 B | utility/system |
- ✅ **EKIT entry** = 0x20 stride: name[16] + per-kit-size(0x0f1c) + catalog-offset
  + kit-index. This backup's 200 slots = **31 named user kits** (CHURCH, SuperHeroes,
  House128bpm, … Your Love), a `Test Kit` at slot 38, rest empty `User Kit`.
- ⚠️ **Tempo offset is format-specific.** The earlier-solved `0x4DD4` (§2) lands inside
  the DROT waveform name-table here (reads `RXRD2`), so that offset was for a *different*
  export (single-kit), **not** this ALL file. Re-locate tempo inside DKIT for ALL files.
- 🔧 Parser: **`midi-tools/ysfc.py`** (committed; byte-verified against TEST.MTA) — `map`/`info`/
  `kits`/`waves`/`patterns`, `extract-waves` (DWAV→WAV), `set-tempo` (checksum-free per-kit edit).
  Record layout confirmed: catalog rec @stride 0x20 = name[16]+order+bytesize+diroff+id; kit body
  @DKIT+reloff, tempo u16BE @body+0x14; wave block @DWAV+cumoff, 0x50 hdr + BE-s16 mono PCM.

## 7. Firmware & flashing — ✅ ROUTE IS OPEN (checksum solved)
- ✅ `8H39OS_.PGM` = updater. Header `"InstallerFile … DTXM12"`; `CM` descriptor @0xa0
  (`+0x06`=0x0100 start, `+0x08`=0x7e0000 len, `+0x0c`=0x20000 = 128 KB erase-sector).
  **NOT encrypted, NOT compressed** (entropy 4.07). SH-2A code (capstone 5.0.7 has SH).
- ✅ **Update mechanism (svc-manual p56–57):** USB stick, file must be `8H39OS_.PGM` in
  root, enter updater by holding `[>]` at power-on (`[SHIFT]`+power = version check).
  Flow: **Check (checksum) → Erase flash sectors → Write.** File contains up to 3 blocks:
  **WAVE / BOOT / PROG** → so **preset wave data + bootloader + program are all flashable**.
- ✅ **CHECKSUM SOLVED — the integrity gate is beatable.**
  `checksum = sum(bytes[0x80:EOF]) & 0xFFFFFFFF`, stored **big-endian at header 0x64**;
  file size at 0x68. Verified exact on stock image (`0x333EA09F`). The updater's
  "Checking File / File Error" is just this sum ⇒ **any patched image is deployable** by
  recomputing it. Tool: **`pgm_checksum.py verify|fix`** (writes 0x64+0x68).
  (Secondary word at 0x60 = `0x019FA09F`, likely a per-block sum — confirm if needed.)
- ✅ **UI string table @ ~0x08b000–0x096000** — anchors for the limit hunt:
  `"Illegal file."` `"No wave data."` **`"too long."`** (sample-length cap) `"Copy
  protected."` `"Completed."` `"Are you sure?"`, plus WAVE/PTN menu labels.
- ⚠️ **Disasm reality check:** messages are a **fixed-width 32-byte indexed table**
  (`msg_id → tbl[id*32]`) — **no direct string pointers exist** (whole-file search = 0),
  so the quick string→check xref is closed. Code starts ~file `0x8101` (pointer tables of
  24-bit `0x0284xx` addrs). Extracting bound constants = real multi-pass SH-2A RE, not a grep.
- 💡 **Smarter path than blind disasm:** (a) get the user-MB budget by reading the unit's
  **Wave Memory Status** screen; (b) prove each cap (length/kits/waves) empirically via
  **file-route probes** (reversible). Reserve targeted RE for *patching* a cap once a
  specific one is confirmed firmware-enforced.
## 8. Community editors / mods / tricks — ❓ PENDING RESEARCH
## 9. Power-user maximization within real limits — ❓ PENDING RESEARCH

---

## Next actions
1. ⏳ Deep-research sweep running (fills §3–§9 with cited facts).
2. ✅ Pad→MIDI map captured (partial — see §2a; Pad 1 + plus/minus pending).
3. ✅ Save file (TEST.MTA) mapped — YSFC format cracked (§6), waves extracted (§3).
4. ✅ Service manual parsed (§4 hardware map, §4a test mode, §7 update/checksum).
5. ✅ Firmware checksum solved → patched images deployable (`pgm_checksum.py`).
6. 🔬 Disasm: xref UI strings (§7) → exact limit constants (200/500/23s/user-MB budget).
7. 🔬 Splice-in test: enlarge a DWAV block + fix EWAV size/offset, reload (§3 core ask).
8. 🔬 Map DKIT/DTRG/DUTL record formats → full editable surface (Q6).
9. 🔬 Once §5 lands, test real 1n writes + whether `A0 xx` test mode is SysEx-reachable.

---
## Session log (autonomous loop work + control)
- ✅ Full M12 SysEx control proven: read/write/param (model `7F 0F`), 13 tone offsets mapped
  (`m12_control.py`, `M12_PARAM_MAP.md`). iPad/DTXM12-Touch no longer needed.
- ✅ Loop transcriber (`loop_to_midi.py` + `refine_loop.py`): YouTube → percussion-only loop.
  Refines by envelope-folding (handles continuous shakers). 5 loops done → `loop1..5.mid`
  (129/103/161/114/156 BPM; mostly shaker grooves w/ velocity accents, kick/snare dropped).
- ✅ **Pattern event format CRACKED** (from file DPTN): per pattern = name + per-track
  vol(0x64)/pan(0x40)/?(0x28) arrays + event stream of 7-byte events
  `E0 [gate] [note] [flag] [vel] [dt_hi dt_lo]`, `F9 F4 09`=bar marker, `F2 F1 FF..`=end.
- ⛔ **BLOCKED — pattern injection:** patterns are NOT in the real-time SysEx address space
  (only the current-kit edit buffer is), so a pattern write can't be read-back-verified and
  could endanger existing patterns (EU001 Worship / EU002 Worship6/8 — DO NOT OVERWRITE).
  Safe injection needs a physical step: SMF/file import (thumb drive) or pattern record-mode.
  SMFs are emitted and ready for either path.
- M12 settings: device#/Rcv10ch/param-receive all confirmed correct. SeqCtrl + MIDI-Sync=auto
  would help the (future) sequencer-sync workflow; global utility category not yet mapped.
- Permissions: project `.claude/settings.local.json` set to acceptEdits + tool allowlist for
  uninterrupted autonomous runs.

---
## Blocker inventory + firmware-fixability (survey before building the matcher)
SOLVED (not blocks): reads (7F0F header), SysEx param edit (full r/w), note→voice→pan
layer assignment (cat 0x10 index = note-13, pan writable).
ACTIVE blocks — all PROG-only fixes (never bootloader):
- Arm record over MIDI: NOT possible now; firmware hook to existing record fn = MEDIUM effort,
  the highest-value patch (enables autonomous final pattern storage, no thumb drive).
- Write patterns over USB: not in SysEx space; firmware "receive pattern->flash" handler = HIGH.
- Mode switch (Kit/Pattern) over MIDI: not possible now; firmware expose = MEDIUM.
- File transfer over USB cable: true USB-MSC = NO (huge); file-over-SysEx = HIGH (overlaps above).
- Sample length / counts / total-MB caps: raise constants = LOW-MED, within 64MB wave-flash chip.
HARDWARE-only (no code fix): stereo-FOH vs loop-isolation (2 outs); 64MB wave-flash ceiling;
USB-stick sample streaming (TG rewrite, unrealistic).
DECISION: streaming matcher needs NO firmware. Record-arm hook is the one worthwhile future patch.
