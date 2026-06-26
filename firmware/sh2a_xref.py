"""SH-2A literal-pointer xref engine for the DTX-MULTI 12 firmware.

Image facts (empirically pinned 2026-06-23):
  - .PGM is an installer wrapper: 0x80-byte header, then the de-headered image.
  - All executable code lives in the first 0x140000 of the payload.
  - Runtime load base = 0x0C000000 (SDRAM / CS3 on the SH7206-class SH-2A).
  - Big-endian. Verified: 0x0C-pointers resolve to real strings + 4F22 prologues.

Address mapping:
  RAM  = file_off - 0x80 + 0x0C000000
  file = RAM - 0x0C000000 + 0x80
"""
import struct, os

_HERE = os.path.dirname(os.path.abspath(__file__))
D = open(os.path.join(_HERE, "8H39OS_.PGM"), "rb").read()

HDR  = 0x80          # installer header size
BASE = 0x0C000000    # SDRAM load base
CODE_LO, CODE_HI = HDR, 0x140080          # file-offset code window
IMG_LO, IMG_HI   = BASE, BASE + 0x7E0000  # valid RAM span

def b16(off): return struct.unpack(">H", D[off:off+2])[0]
def b32(off): return struct.unpack(">I", D[off:off+4])[0]
def ram2off(v): return (v - BASE) + HDR
def off2ram(o): return (o - HDR) + BASE
def valid(v):  return IMG_LO <= v < IMG_HI and 0 <= ram2off(v) < len(D) - 4

def strat(off, n=40):
    s = D[off:off+n].split(b'\x00')[0]
    try:
        t = s.decode('ascii')
        return t if t.isprintable() else None
    except Exception:
        return None

def build():
    """Return (xref, funcs).
    xref: RAM pointer value -> [RAM addrs of the mov.l @(disp,pc) that load it]
    funcs: set of RAM addrs whose word is 4F22 (sts.l pr,@-r15 prologue)."""
    xref, funcs = {}, set()
    for off in range(CODE_LO, CODE_HI, 2):
        ins = b16(off)
        if ins == 0x4F22:
            funcs.add(off2ram(off))
        if (ins >> 12) == 0xD:                       # mov.l @(disp,pc),Rn
            lit = ((off - HDR) & ~3) + 4 + (ins & 0xFF) * 4 + HDR
            if CODE_LO <= lit < len(D) - 4:
                xref.setdefault(b32(lit), []).append(off2ram(off))
    return xref, funcs

if __name__ == "__main__":
    xref, funcs = build()
    print(f"distinct pointer constants: {len(xref)}   prologue funcs (4F22): {len(funcs)}")

    # Real strings pointed at by code, ranked by reference count.
    strs = []
    for v, locs in xref.items():
        if not valid(v):
            continue
        s = strat(ram2off(v))
        if s and len(s) >= 3:
            strs.append((v, len(locs), s))
    strs.sort(key=lambda x: -x[1])
    print(f"\ncode-referenced strings: {len(strs)}; top 30 by ref-count:")
    for v, c, s in strs[:30]:
        print(f"  0x{v:08X} x{c:<3} {s!r}")
