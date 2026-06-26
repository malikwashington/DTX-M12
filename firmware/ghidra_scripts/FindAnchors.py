# Ghidra Jython post-analysis script: locate phrase player + sysex anchors.
# Code+rodata loaded at base 0x0 (== ROM addr). Big-endian SH-2A.
from ghidra.program.model.scalar import Scalar
fm = currentProgram.getFunctionManager()
print("=== functions: %d ===" % fm.getFunctionCount())
listing = currentProgram.getListing()
# 1) phrase-event interpreter: real instructions comparing a byte to 0xF9/0xF2/0xE8 (markers)
print("=== funcs with cmp/mov of pattern opcodes 0xF9/0xF2 (real code) ===")
hits = {}
fi = fm.getFunctions(True)
while fi.hasNext():
    f = fi.next()
    body = f.getBody()
    ii = listing.getInstructions(body, True)
    marks = set()
    while ii.hasNext():
        ins = ii.next()
        for k in range(ins.getNumOperands()):
            for o in ins.getOpObjects(k):
                if isinstance(o, Scalar):
                    v = o.getUnsignedValue() & 0xFF
                    if v in (0xF9, 0xF2, 0xE8, 0xF4):
                        marks.add(v)
    if 0xF9 in marks and (0xF2 in marks or 0xF4 in marks):
        print("  %s @ %s  marks=%s" % (f.getName(), f.getEntryPoint(), sorted(marks)))
# 2) list strings mentioning pattern/seq + their xrefs
print("=== pattern/seq strings + xref functions ===")
PY_OK = True
