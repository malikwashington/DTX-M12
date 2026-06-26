# DTX-MULTI 12 — Editability & Limits Investigation

Goal: know **every** memory/storage limit, whether each can be pushed past its intended
value, and the full editable surface — including unintended. You can reflash + restore,
so destructive file/firmware tests are on the table.

Risk: 🟢 no unit risk (pure analysis) · 🟡 reversible unit op · 🔴 destructive (recover by
reflash+restore). 👤 = needs you at the unit.

---

## Known baseline (already confirmed — see DOSSIER §3/§6 + manual)
- User: **200 kits, 500 waves, 50 patterns, 10 trigger setups**; per-sample **≤23 s**,
  **16-bit only**; user memory is **flash ROM** (non-volatile).
- Preset: **50 kits, 1,277 voices, 100 MB** wave ROM.
- `.MTA` = YSFC container, **no checksums on data chunks**, waves = 0x50 header + raw BE
  16-bit mono PCM. We have a working extractor; repacker is buildable.

---

## Q1 — What is the ACTUAL total user-sample memory budget?
*The 500-wave / 23-s caps aren't the real ceiling; a total-MB flash budget is.*
- [ ] 🟢 **Find the total in firmware** — disasm `8H39OS_.PGM`, locate the "Wave Memory
      Status" total constant (the MB number the unit shows as "free/total").
- [ ] 👤🟡 **Read it off the unit** — Wave Memory Status screen shows used/total MB. (1 min.)
- [ ] 🟢 **Cross-check physical flash** — from firmware/teardown, the actual flash chip size
      (the hard wall behind any firmware constant).
→ Answers "what are the real limits" and bounds everything in Q2.

## Q2 — Can I INCREASE the memory I can write to?
*Two separate walls: (a) a firmware budget check, (b) the physical flash chip.*
- [ ] 🟢 **Find the budget check** — the routine comparing used-bytes vs the Q1 constant
      that throws "memory full". Is it a simple constant (patchable) or derived from a
      runtime flash-size probe?
- [ ] 🔴👤 **Patch + reflash the constant** → import past the old cap → does it write &
      play, or corrupt past the physical chip boundary? (Defines the true hardware ceiling.)
- [ ] 🟢/👤 **Hardware expansion feasibility** — teardown: is wave flash a socketed/standard
      part that could be swapped for a larger one, or is size hardcoded in firmware? (the
      SPD-SX-killer question.)

## Q3 — Can I raise the per-sample 23-second cap?
- [ ] 🟢 **Find the 23 s / length constant** in firmware + the import length-check.
- [ ] 🔴👤 **File route first (no flash needed):** craft an `.MTW`/`.MTA` with one wave
      > 23 s, load it → accepted / truncated / "illegal"? Tells us if the cap is import-only
      (file bypass works) or playback-engine-deep.
- [ ] 🔴👤 **Firmware route:** patch the constant if the file route is blocked.

## Q4 — Can I increase the number of USER KITS past 200?
- [ ] 🟢 **Locate the 200 constant** (U001–U200 bound) + the kit-index range checks.
- [ ] 🟢 **Check flash layout headroom** — is there spare flash after the 200-kit region,
      or would kit #201 collide with the next data region? (decides if it's even possible.)
- [ ] 🔴👤 **File probe:** add kit slots >200 in a crafted `.MTA` (format already allows the
      index width) → does the unit show/load them or ignore/reject?
- [ ] 🔴👤 **Firmware patch** to widen the bound if flash headroom exists.
  (Same playbook applies to 500 waves / 50 patterns / 10 triggers — cheap once the
  pattern is known.)

## Q5 — Can I replace the PRESET kits (50)?
**Functionally** (intended-ish) vs **in actuality** (overwrite ROM):
- [ ] 🟢 **Determine where presets live** — are the 50 preset kits + 1,277 preset voices
      *inside* `8H39OS_.PGM`, or in a separate mask ROM not in the update image? This single
      finding decides whether "actual" replacement is even possible.
- [ ] 🟡👤 **Functional replacement** (no flash write): build user kits that shadow presets,
      remap pad→voice references. Always possible; confirm UX (can a user kit fully stand in
      for a preset slot in your workflow?).
- [ ] 🔴👤 **Actual replacement** — if presets are in the `.PGM`: patch preset kit/voice data
      + reflash. If in separate ROM: only via hardware. Report which.
- [ ] 🟢 **Preset-voice repoint:** can a *user* kit point a pad at a *preset* voice id and
      override its params (a soft "edit the preset")? Map this in DKIT.

## Q6 — Full editable surface, incl. UNINTENDED
*Find params editable by crafted file that the UI hides or locks.*
- [ ] 🟢 **Map every chunk's record format** — DKIT (per-pad: voice-id, tuning, level, pan,
      filter, envelope, FX sends, MIDI note/ch, choke groups…), DTRG (trigger/sensitivity),
      DUTL (system/global), DROT (voice-element/name table), DPTN (pattern data).
- [ ] 🟢 **Diff file-editable vs UI-exposed** — anything present in the file but not on the
      panel = candidate "unintended" edit (e.g. out-of-range tuning, hidden FX routings,
      tempo per-kit, MIDI mappings, velocity curves).
- [ ] 🔴👤 **Boundary tests** — write out-of-UI-range values, load, observe (does firmware
      clamp on load, or honor them?).

## Q7 — Firmware flashability gate (enables Q2–Q5 firmware routes)
- [ ] 🟢 **CPU + load address** of the SH-2-class image; set up disassembly.
- [ ] 🟢 **Updater integrity check** — find the CRC/checksum/signature the `.PGM` installer
      verifies. If it's a recomputable checksum → patched firmware is deployable. If a real
      signature → firmware route is blocked, file route only.
- [ ] 🟢 **Update-mode entry + protocol** — the power-on button combo and MIDI-bulk transfer
      the official updater uses (so we can flash a patched image).
- [ ] 🟢 **Recovery image** — does `.PGM` contain a bootloader/2nd-stage (un-brickable path)?

## Q8 — Storage / external playback angle
- [ ] 🟢 **USB-TO-DEVICE scope** (from manual): confirmed mass-storage support; does it allow
      *playback/trigger from the stick*, or only file save/load? (manual says save/load).
- [ ] 🟢 **Streaming feasibility** — any firmware path that reads samples from USB at trigger
      time (vs. only into the 100 MB wave memory)? Long shot, high payoff.

## Q10 — Hot-swap / physical memory expansion (answered: no hot-swap)
*As built: memory is soldered on a parallel bus, no socket — see DOSSIER §4. Deeper:*
- [ ] 🟢 **Bigger-flash feasibility** — does the SWX02 wave-memory bus / firmware support
      a chip larger than the 512 Mbit IC13 (more address lines wired? CS region size)? If
      the firmware caps the addressable wave space, a bigger chip buys nothing without a
      patch. Determine the addressable ceiling from the LSI bus width + firmware.
- [ ] 🔴👤 **Reball mod** (only if above is promising) — desolder IC13, fit larger pin-compatible
      flash, patch firmware to address + partition it. High skill, last resort.

## Q11 — USB sample streaming (answered: no native path)
*TG can't address USB; import-to-flash only — see DOSSIER §4/§8. Long-shot hack check:*
- [ ] 🟢 **Streaming feasibility in firmware** — can the TG be pointed at SDRAM (IC9) and can
      the USB-host DMA into SDRAM? If both, a buffered/streamed-attack hack is *conceivable*
      (still lag-prone). Confirm from disasm whether any TG-from-SDRAM path exists. Verdict
      expected: not feasible without major firmware work.

## Q9 — Live MIDI write surface (parallel, lower priority) 👤🟡
- [ ] **1n Parameter-Change** addresses honored live (tempo, kit select, pad params).
      Verification needs your eyes (no read-back). Mostly orthogonal to the file/firmware work.

---

## Suggested execution order
1. **Q7 + Q1 + Q6 (all 🟢, no unit needed)** — disasm, find limit constants & the updater
   checksum, map the file format fully. This alone answers *most* of "what's the real limit"
   and *whether* firmware patching is viable, with zero risk.
2. **File-route probes (Q3/Q4/Q5 file tests)** 🔴👤 — reversible, no flashing, prove which
   caps are import-side vs engine-deep.
3. **Firmware patch + reflash** 🔴👤 — only if Q7 says the checksum is beatable.
4. **Teardown / hardware** — only if you want the physical-expansion ceiling.

The big decision gate is **Q7's updater-checksum finding**: it determines whether half this
list is a 5-minute hex patch or a dead end.
