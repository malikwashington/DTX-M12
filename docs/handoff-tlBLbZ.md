# Handoff — DTX-MULTI 12 SysEx tempo-address hunt

**For:** a fresh Claude Code session running on the user's M1 MacBook.
**One line:** We solved the live-tempo problem by editing the M12's save file; this handoff is the *optional* exploration to find whether the tempo can also be set over MIDI SysEx.

This crosses from a chat sandbox to your machine, so the constants and the script
travel inline rather than as repo paths — there is no shared filesystem to point at.

---

## State

**SOLVED (the thing that mattered):** Per-song tempo on the M12 is set by editing
the kit save file on the laptop. Proven end to end — a hand-edited `.MTK` loaded
and CHURCH came up at the edited tempo. The format has **no checksum** guarding
the kit data (only the 2 tempo bytes changed across edits and the unit accepted
it). This is the show-ready path.

**TEMPO ENCODING (confirmed by 3-file diff @ 100/150/200 bpm):**
- File offset `0x4DD4` = tempo MSB, `0x4DD5` = tempo LSB
- `bpm = MSB*128 + LSB`  (14-bit big-endian)
- This offset is into CHURCH's kit data block (block starts `0x4DC0`, tempo at +0x14)
- The per-kit tempo field on the unit lives on the KIT1 page (kit name/volume/tempo);
  with it set to "hold/off" the kit doesn't override the global tempo, which is why
  earlier diffs were blank. House128bpm carries a numeric tempo, which is how a kit
  forces the tempo on select.

**IN PROGRESS / OPTIONAL:** the MIDI SysEx dig. We have the message grammar but not
the live tempo *address*. The probe script answers it empirically.

**BLOCKED / RULED OUT:**
- M12 transmits nothing when tempo is edited on the panel (echo capture was silent).
- DTXM12 Touch app is proprietary (Yamaha EULA forbids decompiling); its open-source
  *clause* covers bundled libraries only, not the parameter map. Not a source.

---

## Known constants (hard-won — don't re-derive)

- Yamaha ID `0x43`; **M12 model ID `0x18`** (service manual).
- Command nibble after `0x43`: `1n`=Param Change, `0n`=Bulk Dump, `2n`=Dump Request,
  `3n`=Param Request. Reply to `2n` is `0n`; reply to `3n` is `1n`. Device number = "all".
- Identity request `F0 7E 7F 06 01 F7` -> documented reply
  `F0 7E 7F 06 02 43 00 41 3A 06 00 00 00 7F F7`.
- Kit change (already working from forScore): Bank MSB `0x7D`(125), Bank LSB `1`
  (user 1-100) / `2` (user 101-200), then Program Change = kit#-1, on MIDI ch 10.

## Open questions the probe resolves

1. Does the M12 service `3n`/`2n` requests at all? (No front-panel bulk dump exists,
   so silence is plausible and is itself a final answer -> stop, use the file route.)
2. Address width/layout (2- vs 3-byte; the service test cmd `F0 43 10 18 5A 00 F7`
   has only 2 bytes after the model ID, so width is unconfirmed). Script is parameterized.

---

## The artifact

`dtxm12_probe.py` (delivered alongside this doc). Phases:
- `ports` — list/select MIDI ports
- `identity` — known-good round trip; confirms the send->module->receive pipe
- `probe` — small honest battery of `2n`/`3n` tests; learns if requests are serviced
- `scan` — parameter-request address sweep with a tempo-echo detector
  (set CHURCH to `--target` first; it flags any reply whose adjacent byte pair
  decodes to the target bpm = that address is the tempo)

`pip install mido python-rtmidi` to run.

---

## Next steps (in order)

1. **Hardware:** M12 `USB TO HOST` -> Mac directly (not the iPad). On the unit set
   `UTIL6-9 MIDI IN/OUT = USB`, device number `all`, firmware >= 1.11. Power on.
2. `python dtxm12_probe.py ports` -> confirm the M12 in/out names (or `--match`).
3. `python dtxm12_probe.py identity` -> must see the identity reply. If silent, the
   problem is cabling/port/MIDI-mode, not the device — fix before anything else.
4. `python dtxm12_probe.py probe` -> if **both** `2n` and `3n` are silent, the M12
   doesn't service MIDI requests; record that and stop — the file route stands.
   If anything replies, capture the exact bytes.
5. If `3n` replies: set CHURCH to a distinctive tempo (e.g. 175), then
   `python dtxm12_probe.py scan --target 175`, starting with a tight `--low` window
   and widening only around addresses that answer. A match yields the tempo address.
6. With the address, build/test the write `F0 43 10 18 <addr> <MSB> <LSB> F7` and
   verify the tempo changes live on the loaded kit.

## Still-to-build (separate, higher priority for the show)

The **kit-tempo file generator**: input = kit name + list of tempos, output = a
ready-to-load `.MTK` with one CHURCH-clone per tempo, named per song. The hard parts
are mapped already (tempo offset/encoding known; no checksum; 32-byte name-table
records with per-kit pointer and index bytes identified). Cloning needs the kit-block
stride and the name-table/pointer fixups worked out from the existing save file.

## Suggested skills

This is low-level, device-vocabulary work in no formal codebase -> invoke **align-quick**
before writing changes. No `CONTEXT.md` here, so `align-domain` is overkill.
