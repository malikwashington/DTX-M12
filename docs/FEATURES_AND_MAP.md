# DTX-MULTI 12 — full address map + feature investigation (2026-06-23)

All from live capture (checksum-verified) + the Yamaha Data List MIDI spec +
owner's-manual. Tools: `midi-tools/m12_dump_request.py` (`--scan`/`--enum`/`--sweep`,
read-only), `m12_dump_decode.py`, `syx_view.py`, `m12_control.py` (read+write).

## 1. Index map per category (kit edit buffer) — DONE
Each category's **index** byte enumerates its instances:

| cat | #idx | what | block |
|-----|------|------|-------|
| 0x00 | 1  | kit common | 52 B |
| 0x02 | 22 | per-trigger MIDI/record (uniform defaults) | 6 B |
| 0x10 | 82 | **voice layer 1**, idx = MIDI note−13 (notes 13–94) | 26 B |
| 0x11 | 22 | **voice layer 2** (the 22 trigger inputs) | 26 B |
| 0x14 | 5  | name strings (kit name "PercsMaster" + others) | 26 B |
| 0x15 | 16 | per-pad record (uniform defaults) | 14 B |
| 0x16 | 1  | trigger directory + payload (see §2) | 792 B |
| 0x20 | — | name string ("Stick Normal") | 12 B |
| 0x21 | 17 | per-pad record (first byte varies by pad) | 14 B |
| 0x28 | — | voice name ("Init Voice") | 16 B |

"22" recurs (0x02, 0x11, 0x16 directory) = the **22 trigger inputs** (12 pads + rims +
external + hi-hat/foot). 82 voice slots (0x10) = the full note-addressed voice map.

## 2. The 0x16 block — 792 B
First **88 bytes = 22-entry directory** of `(offset = n×4, length = 4)` pairs (one per
trigger), then ~704 B payload (~32 B/trigger). It's the **per-trigger assignment table**.
Full field decode needs a diff (change one pad's assignment, re-dump).

## 3. Feature findings

### Patterns over USB MIDI (#4) — NO direct write; record path works
The official MIDI spec (Data List §3) exposes **no SysEx bulk dump / parameter change /
file transfer** — only GM-On, Identity, Master Volume, XG-On. Our category scan confirms
only the **kit edit buffer** (0x00–0x28) answers dump requests; pattern/song/wave **storage
is not in the SysEx address space**. So a pattern cannot be *written* as data over MIDI.
**BUT** the internal sequencer *records* incoming MIDI (§2.2). Realistic path: arm the
PATTERN recorder on the unit once (panel), then stream the percussion MIDI from the laptop →
it records into a user pattern (EU0xx). One panel action, then fully computer-driven content.

### File upload without thumb drive (#5) — kit yes (1 panel press); waves/patterns no
- **Kits:** write the whole kit via the SysEx edit buffer (proven read+write), then press
  STORE on the unit once → saved to a user-kit slot. No thumb drive.
- **Waves / patterns / songs:** no MIDI transfer path exists — thumb-drive (SMF/WAV) import
  only, OR the record-arm path above for patterns.

### Stop-all-sounds on a pad (#6) — paths identified, exact param TBD by diff
- MIDI All Sound Off = **CC 120** mutes all voices (also Mono 126 / Poly 127 / AllNoteOff 123).
- A pad can transmit a message on strike (MessageType=CC, Pad Function=CCnn, or stop/start),
  but the manual says these go to **MIDI OUT, not the internal tone generator** — so a pad
  alone won't silence the M12's *own* sound standalone.
- **Standalone solution to verify by diff:** the Pad Function (UTIL4-1) / pad voice-assign
  options — check whether an internal "stop sequencer / mute" assignment exists.
- **Laptop-in-loop solution (works now):** assign a pad a unique CC; laptop catches it and
  sends CC120 back to the M12. Not standalone.

### Loop restart on 2nd strike (#7) — current behavior is STOP; restart needs a workaround
Owner's manual: *"If a pad playing a looped pattern is struck again, the pattern will stop
playing."* That stop-on-restrike is built in. Candidates to flip it to **restart** (test by
diff): pad **Mode** (stack/alternate/hold, MIDI1-1), **Mono/Poly** (VCE5-1; mono cuts+retriggers
a sample), or a pattern loop setting (PTN2). May require a workaround (laptop re-trigger, or
pattern-as-one-shot + retrigger) if no single param gives restart.

## 3b. Kit tempo — located, READ-ONLY over SysEx (verified by diff + write tests)
Diff of tempo 100→200 isolated it: **cat 0x14, idx 0x00, off 0x14 (MSB) + 0x15 (LSB),
14-bit** → `tempo = (data[0x14]<<7) | data[0x15]`  (100=`00 64`, 200=`01 48`).
**Write tests (1n single-param AND 0n bulk-block) were both REJECTED** — display stayed
put (user-confirmed by eye; the dump readback is cached and lags real changes, so trust the
panel, not the readback). ⇒ kit tempo is **read-only over SysEx**.
- Live playback tempo: use **MIDI Clock (F8)** (works when MIDISync=ext/auto).
- Stored kit tempo: panel or file-edit (.MTK generator) only.

**KEY INSIGHT — writability is per-parameter.** cat 0x10 voice params write fine (Volume
round-trip proven); cat 0x14 off 0x10 writes fine; but cat 0x14 tempo is protected. So every
feature parameter must be **write-tested**, not assumed writable. The dump exposes more than
is writable.

## 4. Diff workflow (how we pin the exact bytes)
Baselines already captured: `enum02.syx`, `enum15.syx`, `enum21.syx`, `enum10.syx`,
`scan.syx`. Procedure: change ONE setting on the unit → re-run the matching `--enum`/`--scan`
→ `syx_view --full` diff → the single changed byte = that parameter. Then write it with
`m12_control.py` to apply programmatically.
