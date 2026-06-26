EXPERIMENTAL PATCHED FIRMWARE — DTX-MULTI 12
============================================
This 8H39OS_.PGM is a MODIFIED firmware (Feature 7: re-strike a playing pattern
restarts it from the top instead of stopping). It is NOT stock Yamaha firmware.

Patch: code.bin 0x44038  8B 1C -> 00 09  (NOP the pattern-toggle stop branch).
Checksum + size header recomputed. Only the PROG block is changed; BOOT untouched.

TO FLASH: copy this 8H39OS_.PGM to the ROOT of a USB drive, insert in USB TO DEVICE,
  power on holding ^ (up), press [ENTER]. (See docs/RECOVERY.md.)
TO RECOVER (revert): same steps with a PRISTINE 8H39OS_.PGM (archived in firmware/).
Risk: low/recoverable (BOOT block re-flashes a bad PROG). Don't lose power mid-write.
