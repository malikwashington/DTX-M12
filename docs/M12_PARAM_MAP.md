# DTX-MULTI 12 — SysEx parameter map (built by live capture)

Protocol (verified):
- Param write: `F0 43 1n 7F 0F  aH aM aL  dd  F7`  (n=device#, no checksum)
- Read:        `F0 43 2n 7F 0F  aH aM aL  F7`  → bulk dump
- Bulk block:  `F0 43 0n 7F 0F  cH cL  aH aM aL [data] chk F7`  chk=(-Σ(count+addr+data))&0x7F

Address = `category, index, offset` (3 bytes).

## Category 0x10 — per-pad voice params (live edit buffer)  `10 [padIndex] [offset]`
| offset | parameter | range seen | notes |
|--------|-----------|-----------|-------|
| 0x01   | **Voice Category** | id | 0x04=Hi-Hat, 0x07=Orchestral/Misc (more TBD via Data List order) |
| 0x02   | **Voice Number** | 1–N in category | Hi-Hat 041 = 0x29 |
| 0x03   | **Volume** | 0–127 | Pad 6 (idx 0x08) 100→15 = `10 08 03 0F` |
| 0x04   | **Pan**    | 0–127, 0x40=center | Pad 6 → 0x7F = hard right |
| 0x05   | **Reverb send** | 0–127 | cross-confirmed Pad 1 & Pad 6 |
| 0x06   | **Chorus send** | 0–127 | |
| 0x07   | **Variation send** | 0–127 | |
| 0x08   | **Tune/Pitch (coarse)** | ~, 0x40=center? | cross-confirmed Pad 1 & Pad 6, up=increase |
| 0x0C   | **Filter Cutoff** | 0–127, 0x7F=open | Pad 6 dragged to 0x00 (closed) |
| 0x0D   | **Resonance (Q)** | 0–127 | |
| 0x0E   | **Attack** | 0–127 | |
| 0x0F   | **Decay** | 0–127 | |
| 0x10   | **Release** | 0–127 | |
| 0x00   | ? voice bank/main | 0x7C const | same across pads |
| 0x09,0x0A | ? (fine tune?) | 0x40/0x3C | TBD |

### Pad number → internal index (category 0x10 mid-byte) — NON-LINEAR
| app Pad | index | via |
|---------|-------|-----|
| 1 | 0x0C | pitch capture |
| 6 | 0x08 | volume/pan/pitch/cutoff/decay |
(need the other 10 — plan: nudge each pad's volume once for a full table)

## Category 0x00 — kit common (single block at 00 00 xx)
(captured but not yet labeled — e.g. 00 00 06=0x40, 00 00 0C=0x20, 00 00 0D=0x14 …)

## Category 0x02 — per-pad (xx 01 etc.), 0x21 — per-pad 14-byte records
(seen in capture; meaning TBD)

## Pad voice block (cat 0x10, 26 bytes) — live readback, cross-validated
Pad 6 raw: `7C 04 29 64 7F 28 00 00 25 40 3C 00 00 40 40 7F 40 00 01 00 ...`
```
off  val  meaning
0x00 7C   ? (voice "main"/bank high — same across pads)
0x01 04   Voice Category  (Hi-Hat)            ✓
0x02 29   Voice Number    (041)               ✓
0x03 64   Volume                              ✓
0x04 7F   Pan (0x40=center)                   ✓
0x05 28   ? (fine tune? )
0x08 25   Tune coarse                         ✓
0x0C 00   Filter Cutoff (0x7F=open)           ✓
0x0F 7F   Decay                               ✓
0x09 40 / 0x0A 3C / 0x0D 40 / 0x0E 40 / 0x10 40  ?  (resonance/attack/release/EG/sends — TBD)
```
STATUS: ✅ read + write + per-param all PROVEN. `m12_control.py` is the working API.

---

## Live dump map — captured & checksum-verified from the unit (2026-06-23)

Protocol **confirmed from firmware** (`FUN_0c023106`, see `docs/FIRMWARE_RE.md`) AND from a
live capture. Tools: `midi-tools/m12_dump_request.py` (read-only requester, `--scan`/`--sweep`)
+ `m12_dump_decode.py`. Category scan (`(cat,0,0)` for cat 0x00–0x7F) — **10 categories reply,
all checksums valid**:

| addr (cat idx off) | bytes | content (decoded from live "PercsMaster" kit) |
|--------------------|-------|-----------------------------------------------|
| `00 00 00` | 52  | kit common |
| `02 00 00` | 6   | per-pad small record |
| `10 00 00` | 26  | **pad voice — layer 1** (VoiceCat/Num/Vol/Pan/sends/Tune/Cutoff/Res/EG) |
| `11 00 00` | 26  | **pad voice — layer 2** (same 26-byte layout) |
| `14 00 00` | 26  | **kit NAME string** — read "PercsMaster" (ASCII, not a voice block) |
| `15 00 00` | 14  | per-pad record |
| `16 00 00` | 792 | **large table** — `(index, 0x04)` pairs (pad/note assignment or sequence) |
| `20 00 00` | 12  | NAME string — read "Stick Normal" |
| `21 00 00` | 14  | per-pad record |
| `28 00 00` | 16  | voice NAME string — read "Init Voice" |

(Categories not in the list returned no block at index 0; per-pad/per-layer categories
enumerate further via the **index** byte.)

### Cat 0x10 voice block — live values decoded (idx 0x00)
`7C 07 02 5E 3B 14 00 00 12 60 3C 00 40 40 40 40 40 00 01 03 00 …`
→ VoiceCat=7, VoiceNum=2, Vol=94, Pan=59, RevSend=20, Tune=18, Cutoff=64, Res=64,
Atk/Dec/Rel=64. Matches the offset table above exactly.

### READ + WRITE round-trip PROVEN on hardware ✅
`m12_control.py` round-trip (cat 0x10, idx 0x00, off 0x03 Volume): read 94 → wrote 85 →
read-back 85 → restored 94 → read-back 94. Full live parameter control (read & write),
verified and reversible. **The computer-side producer can now drive the M12's parameter
surface directly over USB — no thumb drive.**

## Pad Function field (located by diff, 2026-06-24)
Per-pad **Pad Function (UTIL4-1)** code = **cat 0x02, idx = pad number, offset 0x00** (first
byte of the 6-byte cat-0x02 per-trigger record). Known values: tap-tempo=0x07, click-On/Off=
0x08 (matches firmware switch FUN_0c0C9E60, codes 0..0x10). Pad 1 = idx 0x00. Used by the
feature-6 stop-on-pad firmware patch (set a pad to the patch's function code here).

## Native menu value lists — DECODED FROM FIRMWARE STRINGS (2026-06-24)
Recovered the fixed-width string tables in `code.bin` (RAM = file off; strides 12 B):

### Pad **Function** (control-on-strike) list — RAM 0x0C093E95, stride 0xC, code = index
| code | string | acts on strike |
|------|--------|----------------|
| 0x00 | `off` | — |
| 0x01 | `inc kitNo` | current-kit++ |
| 0x02 | `dec kitNo` | current-kit-- |
| 0x03 | `inc ptnNo` | pattern# ++ |
| 0x04 | `dec ptnNo` | pattern# -- |
| 0x05 | `inc tempo` | tempo cell 0x0C2BFE94 ++ |
| 0x06 | `dec tempo` | tempo cell 0x0C2BFE94 -- |
| 0x07 | `tap tempo` | tap → tempo |
| 0x08 | `clickOn/Off` | metronome toggle |
| 0x09 | `FX bypass` | effect bypass toggle |

**⇒ There is NO native "stop sequencer / all-sound-off" pad Function.** The list ends at FX bypass.
A native control-function stop therefore REQUIRES a firmware patch (repurpose one of these on-strike
arms — see FEATURE_6_7_FINDINGS). Tempo-cell loaders (on the strike path): 0x0C043792 (FUN_0c042b6a),
0x0C044CB6 (FUN_0c0443f6) — the inc/dec-tempo handler anchor to find the strike dispatcher.

### Pad **MessageType** (what a pad transmits on strike) — RAM 0x0C093923, stride 5
`note`(0) · `CC`(1) · `PC`(2) · `strt`(3 = MIDI Start FA) · `cont`(4 = Continue FB) · `stop`(5 = **MIDI Stop FC**)
**⇒ A pad CAN natively transmit MIDI Stop (FC) on strike** — but it goes to MIDI OUT, not the internal
TG. Combined with a MIDI OUT→IN loopback (the M12 has 5-pin DIN MIDI IN+OUT) + UTIL SeqCtrl=in, this
gives a no-firmware "pad = stop all patterns" (the FC route already proven in feature 6).
Pad mode strings (adjacent, RAM 0x0C093941): `stack` · `alternate` · `hold` · `variable`.
