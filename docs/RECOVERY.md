# DTX-MULTI 12 — firmware recovery / re-flash (SAFETY NET)

Source: service manual (`docs/sm_text.txt`). The updater + flash-writer live in the resident
**BOOT block**, independent of the **PROG** application image (`code.bin`) we patch — so a bad
PROG patch is **re-flashable**, not a brick (verified: `firmware/boot_recovery.txt`).

## Re-flash a known-good firmware (recover from a bad patch)
1. Copy a pristine **`8H39OS_.PGM`** to the **root of a USB flash drive** (the ONLY file the
   updater reads). Keep an untouched original `8H39OS_.PGM` archived off-machine.
2. Insert the drive in **USB TO DEVICE**. **Power on while holding the ^ (up) button.**
3. Screen: **"Update MULTI 12. Press [ENTER]"** (ENTER LED flashes). Press **[ENTER]**.
4. "Checking File." → "WAVE Erasing." → "WAVE Writing." — all show **"DON'T SHUTDOWN!!"**.
   **Do not cut power during this window** (the only real brick risk).
5. **"Completed. Reboot MULTI 12."** → power-cycle. Recovered.

## Verify firmware versions
- **Power on holding [KIT]+[ENTER]** → version screen: **B**=Boot, **F**=Firm, **W**=Wave.
  (Also enters the service/test program; "Factory Set" is test item A02B00.)
- Over MIDI (no reboot): send `F0 43 10 18 5A 00 F7` → version screen.

## Brick rules
- **Patch PROG only; NEVER modify the BOOT block.** A corrupt BOOT is the only true hard-brick,
  and it is outside the region any PROG patch touches.
- Only genuine risk = power loss during the "DON'T SHUTDOWN!!" erase/write. Use stable power.
