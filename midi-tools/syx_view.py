#!/usr/bin/env python3
"""Compact viewer for captured M12 SysEx dumps. One line per block:
   cat idx  size  chk  ascii-or-hex-preview
Usage: syx_view.py <capture.syx> [--full]
"""
import sys
import m12_dump_decode as M


def ascii_or_hex(data, width=28):
    printable = sum(32 <= b < 127 for b in data)
    if data and printable / len(data) > 0.7:
        return "'" + "".join(chr(b) if 32 <= b < 127 else "." for b in data[:width]) + "'"
    return " ".join(f"{b:02X}" for b in data[:width]) + (" …" if len(data) > width else "")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    full = "--full" in sys.argv
    path = [a for a in sys.argv[1:] if not a.startswith("--")][0]
    buf = open(path, "rb").read()
    blocks = [M.parse_block(m) for m in M.split_sysex(buf)]
    dumps = [b for b in blocks if b["type"] == "dump"]
    print(f"{len(dumps)} dump blocks  ({sum(not b['chk_ok'] for b in dumps)} bad checksums)\n")
    print(f"{'cat':>3} {'idx':>3} {'off':>3} {'size':>5}  chk  preview")
    for b in dumps:
        aH, aM, aL = b["addr"]
        print(f" {aH:02X}  {aM:02X}  {aL:02X}  {b['count']:5}  {'ok ' if b['chk_ok'] else 'BAD'}  {ascii_or_hex(b['data'])}")
        if full:
            print("      " + " ".join(f"{x:02X}" for x in b["data"]))


if __name__ == "__main__":
    main()
