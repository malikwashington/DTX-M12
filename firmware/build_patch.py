#!/usr/bin/env python3
"""Build a patched DTX-MULTI 12 firmware (8H39OS_.PGM) for features 6 + 7.

SAFE BY DESIGN:
- Never touches the pristine source; writes a new file.
- Verifies the EXACT current bytes at every site before patching (aborts on mismatch).
- Recomputes the .PGM checksum (sum of bytes[0x80:] @ header 0x64) + size (@0x68).
- Only patches the PROG block; never the BOOT block. Recovery = hold ^ (up) at power-on
  with a pristine 8H39OS_.PGM on USB (see docs/RECOVERY.md).

Address mapping:  PGM_offset = code.bin_offset + 0x80   (verified: code.bin == PGM[0x80:]).

Edits:
  FEAT7 (loop restart): code.bin 0x44038  -> NOP the toggle branch  (8B 1C -> 00 09)
  FEAT6 trampoline:     code.bin 0x13E1C0 -> 16-byte stub (mute r4=6; jmp; lit)
  FEAT6 hook:           code.bin <tempo-inc arm literal> -> trampoline addr 0C 13 E1 C0
                         (filled in from the RE agent's report — see HOOK below)
"""
import sys, struct, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "8H39OS_.PGM")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "experiment")
OUT = os.path.join(OUT_DIR, "8H39OS_.PGM")   # MUST keep this exact name for the M12 updater
HDR = 0x80

def cb(off):  # code.bin offset -> PGM offset
    return off + HDR

# (name, pgm_offset, expected_bytes, new_bytes)
# FEATURE 7 ONLY: re-strike a playing pattern -> take the start-from-top (restart) arm
# instead of the stop arm. 2-byte NOP of the toggle branch at code.bin 0x44038.
# (Feature 6 "stop all patterns" is handled with NO firmware: forScore sends hex FC.)
EDITS = [
    ("FEAT7 toggle NOP (restart on re-strike)", cb(0x44038), bytes.fromhex("8b1c"), bytes.fromhex("0009")),
]

def csum(d):  # 32-bit sum of bytes[0x80:]
    return sum(d[HDR:]) & 0xFFFFFFFF

def main():
    d = bytearray(open(SRC, "rb").read())
    assert struct.unpack(">I", d[0x64:0x68])[0] == csum(d), "source checksum mismatch — not pristine?"
    print(f"source OK: {SRC} ({len(d)} bytes), checksum verifies")
    for name, off, exp, new in EDITS:
        cur = bytes(d[off:off+len(exp)])
        if cur != exp:
            sys.exit(f"ABORT {name}: at 0x{off:X} expected {exp.hex(' ')} but found {cur.hex(' ')}")
        d[off:off+len(new)] = new
        print(f"  patched {name} @0x{off:X}: {exp.hex(' ')} -> {new.hex(' ')}")
    # fix size + checksum
    struct.pack_into(">I", d, 0x68, len(d))
    struct.pack_into(">I", d, 0x64, csum(d))
    os.makedirs(OUT_DIR, exist_ok=True)
    open(OUT, "wb").write(d)
    readme = ("EXPERIMENTAL PATCHED FIRMWARE — DTX-MULTI 12\n"
              "============================================\n"
              "This 8H39OS_.PGM is a MODIFIED firmware (Feature 7: re-strike a playing pattern\n"
              "restarts it from the top instead of stopping). It is NOT stock Yamaha firmware.\n\n"
              "Patch: code.bin 0x44038  8B 1C -> 00 09  (NOP the pattern-toggle stop branch).\n"
              "Checksum + size header recomputed. Only the PROG block is changed; BOOT untouched.\n\n"
              "TO FLASH: copy this 8H39OS_.PGM to the ROOT of a USB drive, insert in USB TO DEVICE,\n"
              "  power on holding ^ (up), press [ENTER]. (See docs/RECOVERY.md.)\n"
              "TO RECOVER (revert): same steps with a PRISTINE 8H39OS_.PGM (archived in firmware/).\n"
              "Risk: low/recoverable (BOOT block re-flashes a bad PROG). Don't lose power mid-write.\n")
    open(os.path.join(OUT_DIR, "README_EXPERIMENT.txt"), "w").write(readme)
    print(f"\nwrote {OUT}\n  new checksum 0x{csum(d):08X}, size {len(d)}")
    print("  Verify, then copy to the ROOT of a USB drive (keep the name 8H39OS_.PGM) and")
    print("  flash: power on holding ^ (up) -> Press [ENTER]. Recovery: same combo w/ pristine PGM.")

if __name__ == "__main__":
    main()
