#!/usr/bin/env python3
"""Verify / fix the DTX-MULTI 12 updater (.PGM) checksum.
Checksum = 32-bit sum of bytes [0x80 : EOF], stored big-endian at header 0x64.
File size is stored big-endian at 0x68. Usage:
  pgm_checksum.py verify <file>
  pgm_checksum.py fix    <in> <out>   # recompute 0x64 (+0x68) and write
"""
import sys, struct
def csum(b): return sum(b[0x80:]) & 0xFFFFFFFF
def main():
    if len(sys.argv)<3: sys.exit(__doc__)
    mode=sys.argv[1]; d=bytearray(open(sys.argv[2],"rb").read())
    stored=struct.unpack(">I", d[0x64:0x68])[0]
    calc=csum(d); size=struct.unpack(">I", d[0x68:0x6c])[0]
    print(f"stored=0x{stored:08x} calc=0x{calc:08x} size_field={size} actual={len(d)} "
          f"-> {'OK' if stored==calc and size==len(d) else 'MISMATCH'}")
    if mode=="fix":
        out=sys.argv[3]
        struct.pack_into(">I", d, 0x68, len(d))
        struct.pack_into(">I", d, 0x64, csum(d))
        open(out,"wb").write(d)
        print(f"wrote {out} with checksum 0x{csum(d):08x}")
if __name__=="__main__": main()
