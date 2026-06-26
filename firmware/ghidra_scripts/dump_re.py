# dump_re.py — Ghidra Jython post-analysis dump for the DTX-MULTI 12 RE.
# Run headless via: analyzeHeadless <proj> M12 -process code.bin -postScript dump_re.py
# Writes a text report next to the firmware.
from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor

OUT = "/Users/velvetmotion/Desktop/DTX-M12/firmware/ghidra_dump.txt"
fm  = currentProgram.getFunctionManager()
af  = currentProgram.getAddressFactory()
rm  = currentProgram.getReferenceManager()
def A(x): return af.getAddress(hex(x).rstrip("L"))

lines = []
def out(s): lines.append(s)

out("function count: %d" % fm.getFunctionCount())

# 1) Who references the FX descriptor table region and the label pool?
def refs_to(a):
    res = {}
    for r in rm.getReferencesTo(A(a)):
        f = fm.getFunctionContaining(r.getFromAddress())
        if f: res[f.getEntryPoint().getOffset()] = f.getName()
    return res

for name, a in (("FX_descriptor_table ~0x0C096C00", 0x0C096C00),
                ("label_pool 0x0C08DFE8", 0x0C08DFE8),
                ("label_pool_mid 0x0C08E404", 0x0C08E404)):
    out("\n=== refs to %s ===" % name)
    for off, nm in sorted(refs_to(a).items()):
        out("  %s @ 0x%08X" % (nm, off))

# 2) Decompile target functions (FX handlers + any callers we discover).
dec = DecompInterface(); dec.openProgram(currentProgram)
mon = ConsoleTaskMonitor()
def decompile(a, tag=""):
    f = fm.getFunctionContaining(A(a))
    if not f:
        out("\n--- no function at 0x%08X %s ---" % (a, tag)); return
    out("\n===== %s  %s @ %s =====" % (tag, f.getName(), f.getEntryPoint()))
    r = dec.decompileFunction(f, 90, mon)
    if r and r.getDecompiledFunction():
        out(r.getDecompiledFunction().getC())
    else:
        out("  <decompile failed>")

for a in (0x0C0217EA, 0x0C021832, 0x0C0218C0, 0x0C023DB0):
    decompile(a, "FXhandler")

open(OUT, "w").write("\n".join(lines))
print("dump_re: wrote %s (%d lines)" % (OUT, len(lines)))
