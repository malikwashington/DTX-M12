// EmulateFn.java — reusable SH-2A p-code emulation harness for code.bin (DTX-MULTI 12).
//
// Executes a chosen function from the firmware image in isolation using Ghidra's
// EmulatorHelper (high-level wrapper over the ghidra.pcode.emulate engine):
//   * sets PC to a target entry, r15 (stack pointer) to a scratch RAM region,
//     and optional argument registers r4..r7
//   * seeds PR (link register) with a RETURN SENTINEL address; a breakpoint there
//     detects "function returned" (RTS pops PR -> PC == sentinel)
//   * installs a LAZY MemoryFaultHandler: any uninitialized/unmapped read is
//     satisfied with zero bytes so emulation continues instead of dying
//   * single-steps under an instruction budget (default 100000) to bound loops
//   * uses EmulatorHelper.enableMemoryWriteTracking + getTrackedMemoryWriteSet
//     to report every byte the function wrote
//
// Args (whitespace-separated, via -postScript EmulateFn.java "0xADDR sp=0x.. r4=0x.. budget=N out=/path"):
//   first bare token  = target entry address (hex, e.g. 0x0c04898a)   [required]
//   sp=0x..           = stack pointer seed (default 0x0C7F0000)
//   r4=.. r5=.. r6=.. r7=..  = argument register seeds (hex)
//   budget=N          = max instructions (default 100000)
//   out=/abs/path     = output report file (default firmware/emu_out.txt)
//   tracestores=1     = also print every store as it happens (verbose)
//
// If no args are given it runs a built-in SELF-TEST on a tiny synthetic routine
// AND on the firmware target FUN_0c04898a, writing firmware/emu_out.txt.

import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.pcode.memstate.MemoryFaultHandler;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.TaskMonitor;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.*;

public class EmulateFn extends GhidraScript {

    // ---- scratch / sentinel layout (chosen in high RAM, away from real code/data) ----
    static final long DEFAULT_SP   = 0x0C7F0000L;   // top of a scratch stack region
    static final long RET_SENTINEL = 0x0C7FFFFEL;   // PR seed: PC==this means "returned"
    static final long DEFAULT_BUDGET = 100000L;

    StringBuilder log = new StringBuilder();
    void P(String s){ log.append(s).append("\n"); println(s); }

    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) {
            // No args: self-test + firmware target.
            String out = "/Users/velvetmotion/Desktop/DTX-M12/firmware/emu_out.txt";
            P("### EmulateFn self-test run ###\n");
            // 1) Clean leaf validation: FUN_0c00a7fa with r5/r12 pointed at scratch.
            Map<String,Long> leafSeeds = new LinkedHashMap<>();
            leafSeeds.put("r1", 0x3L);            // small index
            leafSeeds.put("r2", 0x100L);          // base
            leafSeeds.put("r5", 0x0C7E0000L);     // struct ptr (scratch)
            leafSeeds.put("r12", 0x0C7E1000L);    // flags ptr (scratch)
            runOne(0x0C00A7FAL, DEFAULT_SP, leafSeeds, DEFAULT_BUDGET, false,
                   "FUN_0c00a7fa (clean leaf validation)");
            // 2) The firmware target. NOTE: 0x0C04898A is an INTERIOR block label; the
            //    real function prologue (which sets up r10/r11/r12/r13 helper ptrs) is at
            //    0x0C048956. We enter there. r4 is a flags byte; bit1 selects the
            //    100x voice-slot clear path, so seed r4=0x02 to exercise it.
            Map<String,Long> tgtSeeds = new LinkedHashMap<>();
            tgtSeeds.put("r4", 0x02L);
            runOne(0x0C048956L, DEFAULT_SP, tgtSeeds, DEFAULT_BUDGET, false,
                   "FUN_0c048956 (mute-all-voices; entry of the fn containing 0c04898a)");
            PrintWriter pw = new PrintWriter(out); pw.print(log); pw.close();
            P("\nwrote " + out);
            return;
        }
        // Parse args.
        long entry = -1, sp = DEFAULT_SP, budget = DEFAULT_BUDGET;
        boolean traceStores = false;
        // Arbitrary register seeds rN=hex (r0..r14).
        Map<String,Long> regSeeds = new LinkedHashMap<>();
        String out = "/Users/velvetmotion/Desktop/DTX-M12/firmware/emu_out.txt";
        for (String a : args) {
            if (a.startsWith("sp=")) sp = parseHex(a.substring(3));
            else if (a.startsWith("budget=")) budget = Long.parseLong(a.substring(7));
            else if (a.startsWith("out=")) out = a.substring(4);
            else if (a.startsWith("tracestores=")) traceStores = !a.substring(12).equals("0");
            else if (a.matches("r\\d+=.*")) { int eq=a.indexOf('='); regSeeds.put(a.substring(0,eq), parseHex(a.substring(eq+1))); }
            else if (entry < 0) entry = parseHex(a);
        }
        if (entry < 0) { P("ERROR: no target entry address given"); return; }
        runOne(entry, sp, regSeeds, budget, traceStores, "FUN_0x" + Long.toHexString(entry));
        PrintWriter pw = new PrintWriter(out); pw.print(log); pw.close();
        P("\nwrote " + out);
    }

    long parseHex(String s){ s = s.trim(); if (s.startsWith("0x")||s.startsWith("0X")) s = s.substring(2); return Long.parseLong(s, 16); }

    String instrAt(long addr) {
        try {
            ghidra.program.model.listing.Instruction ins = currentProgram.getListing().getInstructionAt(toAddr(addr));
            return ins != null ? ins.toString() : "(no-instr)";
        } catch (Throwable t) { return "(?)"; }
    }

    // Counters for the lazy fault handler.
    long uninitReads = 0, unknownReads = 0;
    Set<Long> faultPages = new TreeSet<>();

    void runOne(long entry, long sp, Map<String,Long> regSeeds, long budget,
                boolean traceStores, String label) throws Exception {

        P("============================================================");
        P("EMULATE " + label + "   entry=0x" + Long.toHexString(entry)
          + " sp=0x" + Long.toHexString(sp) + " budget=" + budget);
        P("============================================================");

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        uninitReads = 0; unknownReads = 0; faultPages.clear();
        try {
            final EmulatorHelper femu = emu;
            // ---- lazy memory fault handler: zero-fill any read of un-imaged memory ----
            emu.setMemoryFaultHandler(new MemoryFaultHandler() {
                public boolean uninitializedRead(Address address, int size, byte[] buf, int bufOffset) {
                    uninitReads++;
                    faultPages.add(address.getOffset() & ~0xFFFL);
                    // Provide zeros for the requested bytes -> read proceeds, value 0.
                    for (int i = 0; i < size; i++) buf[bufOffset + i] = 0;
                    return true; // handled
                }
                public boolean unknownAddress(Address address, boolean write) {
                    unknownReads++;
                    faultPages.add(address.getOffset() & ~0xFFFL);
                    // Treat unknown (un-imaged) addresses as readable zero / writable.
                    return true;
                }
            });

            // ---- seed registers ----
            emu.writeRegister(emu.getStackPointerRegister(), sp);          // r15
            emu.writeRegister("pr", RET_SENTINEL);                          // link reg sentinel
            // Initialize all GP regs to 0 for determinism, then apply explicit seeds.
            for (int i = 0; i <= 14; i++) emu.writeRegister("r" + i, 0L);
            for (Map.Entry<String,Long> e : regSeeds.entrySet()) {
                emu.writeRegister(e.getKey(), e.getValue());
                P("  seed " + e.getKey() + " = 0x" + Long.toHexString(e.getValue()));
            }

            // ---- memory write tracking ----
            emu.enableMemoryWriteTracking(true);

            // ---- breakpoint at the return sentinel ----
            Address sentinel = toAddr(RET_SENTINEL);
            emu.setBreakpoint(sentinel);

            // ---- set PC and step under budget ----
            Address pc = toAddr(entry);
            emu.writeRegister(emu.getPCRegister(), entry);

            long count = 0;
            boolean returned = false;
            String stopReason = "budget exhausted";
            long lastPc = -1;
            TaskMonitor mon = TaskMonitor.DUMMY;

            // Valid executable code window (image load base .. base+0x140000).
            final long CODE_LO = 0x0C000000L, CODE_HI = 0x0C140000L;
            while (count < budget) {
                long cur = emu.getExecutionAddress().getOffset();
                if (cur == RET_SENTINEL) { returned = true; stopReason = "returned (PC==sentinel)"; break; }
                // Derailment guard: PC left the real code region (jumped through an
                // unpopulated global function-pointer -> zero/low address).
                if (cur < CODE_LO || cur >= CODE_HI) {
                    stopReason = "PC left code region at 0x" + Long.toHexString(cur)
                        + " after " + count + " instrs (indirect call through unpopulated pointer)";
                    break;
                }
                if (traceStores && count < 60)
                    P(String.format("  [%4d] pc=0x%08x  %s", count, cur, instrAt(cur)));
                lastPc = cur;
                boolean ok;
                try {
                    ok = emu.step(mon);
                } catch (Throwable t) {
                    stopReason = "step exception @0x" + Long.toHexString(cur) + ": " + t;
                    break;
                }
                count++;
                if (!ok) {
                    long now = emu.getExecutionAddress().getOffset();
                    if (now == RET_SENTINEL) { returned = true; stopReason = "returned (PC==sentinel)"; break; }
                    stopReason = "emulator halted: " + emu.getLastError()
                               + " (last pc 0x" + Long.toHexString(now) + ")";
                    break;
                }
            }

            // ---- dump results ----
            P("\n--- RESULT ---");
            P("instructions executed : " + count);
            P("stop reason           : " + stopReason);
            P("returned cleanly      : " + returned);
            P("last executed pc      : 0x" + Long.toHexString(lastPc));
            P("lazy faults: uninitRead=" + uninitReads + " unknownAddr=" + unknownReads
              + " distinctPages=" + faultPages.size());
            if (!faultPages.isEmpty()) {
                StringBuilder fp = new StringBuilder("fault pages (4K): ");
                int n = 0;
                for (long p : faultPages) { fp.append("0x").append(Long.toHexString(p)).append(" "); if (++n>=24){fp.append("...");break;} }
                P(fp.toString());
            }

            P("\n--- FINAL REGISTERS ---");
            StringBuilder rb = new StringBuilder();
            for (int i = 0; i <= 15; i++) {
                BigInteger v = emu.readRegister("r" + i);
                rb.append(String.format("r%-2d=0x%08x  ", i, v.longValue() & 0xFFFFFFFFL));
                if (i % 4 == 3) { P(rb.toString()); rb.setLength(0); }
            }
            if (rb.length() > 0) P(rb.toString());
            P(String.format("pc =0x%08x  pr =0x%08x  sr =0x%08x",
                emu.readRegister("pc").longValue() & 0xFFFFFFFFL,
                emu.readRegister("pr").longValue() & 0xFFFFFFFFL,
                emu.readRegister("sr").longValue() & 0xFFFFFFFFL));

            // ---- memory writes the function made ----
            P("\n--- MEMORY WRITES ---");
            AddressSetView ws = emu.getTrackedMemoryWriteSet();
            if (ws == null || ws.isEmpty()) {
                P("(none tracked)");
            } else {
                // Exclude the scratch stack region from the "interesting" listing but
                // still report it. We coalesce contiguous bytes and print value bytes.
                long total = ws.getNumAddresses();
                P("total bytes written: " + total + "  (RAM ranges in code/data space below)");
                P("(writes outside the 0x0C000000.. RAM space are emulator-internal register/");
                P(" temporary space and are filtered out)");
                int rngCount = 0;
                for (AddressRange r : ws.getAddressRanges()) {
                    long start = r.getMinAddress().getOffset();
                    long end   = r.getMaxAddress().getOffset();
                    long len   = end - start + 1;
                    // Only report writes into the real RAM space (load base 0x0C000000+).
                    // Anything below is Ghidra's internal register/unique scratch space.
                    if (start < 0x0C000000L) continue;
                    boolean isStack = (start >= (sp - 0x10000)) && (start <= sp + 0x100);
                    // Read back the written bytes from emulator memory (cap dump length).
                    StringBuilder hex = new StringBuilder();
                    long dl = Math.min(len, 32);
                    try {
                        byte[] mb = emu.readMemory(r.getMinAddress(), (int) dl);
                        for (byte b : mb) hex.append(String.format("%02x", b & 0xFF));
                        if (len > dl) hex.append("..");
                    } catch (Throwable t) { hex.append("<unreadable>"); }
                    P(String.format("  0x%08x .. 0x%08x  len=%-4d %s [%s]",
                        start, end, len, (isStack?"(scratch-stack)":""), hex.toString()));
                    if (++rngCount >= 64) { P("  ... (more ranges omitted)"); break; }
                }
            }
            P("");
        } finally {
            emu.dispose();
        }
    }
}
