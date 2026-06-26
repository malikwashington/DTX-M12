# DTX-MULTI 12 — "Check First" list

Ordered so each tier de-risks the next. **Do not run a destructive test until Tier 0
gives us a recovery net and Tier 1 tells us whether the unit even respects hand-edits.**
Risk key: 🟢 no unit risk · 🟡 reversible on unit · 🔴 could corrupt data/brick.

---

## Tier 0 — Safety net (do before ANYTHING on the unit) 🟢
- [ ] **Golden backup.** Copy `TEST.MTA` off-machine, read-only. Never edit the original;
      always work on a copy. This file holds your 31 kits — it's irreplaceable.
- [ ] **Know the recovery path.** Document factory-reset combo + firmware re-flash
      procedure (`8H39OS_.PGM` install) *before* risky tests, so a bad load is recoverable.
- [ ] **Confirm the unit re-imports our golden file cleanly** (load ALL from USB, verify
      kits/waves intact). Proves the baseline round-trip works at all.

## Tier 1 — The gating question: does the unit re-serialize on save? 🟡
*This single fact decides whether file-editing an ALL backup is even viable.*
- [ ] **No-op round-trip diff.** Load `TEST.MTA` → save ALL back to USB untouched →
      `cmp`/hex-diff against original.
  - Byte-identical ⇒ unit preserves layout; hand-edits are safe to feed back. ✅
  - Re-serialized/reordered ⇒ the unit rebuilds offsets (and maybe rejects foreign
      files); our edit strategy must match *its* writer, not just be internally valid.
- [ ] **Checksum reality check.** The header/`CM`-style fields may hold a sum even though
      kit/wave *data* doesn't. Flip one harmless byte (e.g. a wave name char in EWAV),
      reload — accepted ⇒ truly no integrity guard.

## Tier 2 — Pin the real limits (read-only / measurement) 🟢🟡
- [ ] **Manual specs** (from `decoded.txt`): wave RAM size, max user samples, max single-
      sample length/seconds, accepted import formats (WAV/AIFF, bit depth, SR, mono/stereo).
- [ ] **Volatile vs flash:** load a user sample, power-cycle. Survives ⇒ flash; gone ⇒ RAM
      (reload-on-boot). Determines whether "expansion" means bigger file or hardware.
- [ ] **UI ceilings by hand:** how many user waveforms / total seconds the front panel
      allows before it refuses. This is the number the splice test tries to beat.

## Tier 3 — Validate our format model SAFELY before exploiting it 🟡
- [ ] **Identity round-trip.** Re-inject an *unchanged-size* wave via crafted file (same
      bytes back into DWAV, fix nothing) → loads & plays ⇒ our read model is correct.
- [ ] **Confirm header fields** by editing one and listening: sample-rate (+0x36),
      loop start/end (~+0x40), and whether a stereo/channel flag exists (all extracts
      assumed mono — verify none are actually stereo playing at half speed).

## Tier 4 — Size-cap probes (escalating, file-based, reversible) 🔴
*Only after Tiers 0–3. One variable at a time; reload golden file between tests.*
- [ ] **+10% length** on one sample (still within plausible bounds) → plays full length?
- [ ] **Past the UI max length** on one sample → firmware rejects / truncates / accepts?
- [ ] **Add a 100th waveform** (new id 300, new EWAV+DWAV entry) → count limit real?
- [ ] **Inflate total DWAV** beyond stated RAM → boundary behavior (graceful vs crash).

## Tier 5 — Firmware recon (parallel, no unit risk) 🟢
- [ ] **CPU/arch + load address** of `8H39OS_.PGM` (confirm SH-2-class; find base) → disasm.
- [ ] **Locate limit constants:** max-length / total-memory / max-count values and the
      import routine that enforces them (the real target for raising the cap).

## Tier 6 — Storage / expansion angle 🟢🟡
- [ ] **USB-TO-DEVICE:** does it allow playback/trigger from a stick, or only save/load?
- [ ] **Internal memory part #s** (teardown photos) → is RAM/flash expansion physically feasible?

---

### Critical-path summary
The one check that most changes our plan is **Tier 1's no-op round-trip diff** — if the
unit rewrites the file on save, the whole "hand-edit the backup" route narrows to
"match the unit's serializer," and the firmware route (Tier 5) becomes primary.
