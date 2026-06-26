// Feat7Emu.java — software validation of the "feature 7" patch (loop restarts on 2nd
// strike instead of stopping) using the same EmulatorHelper harness pattern as
// EmulateFn.java. Runs 4 scenarios and writes a before/after report:
//
//   A) UNPATCHED, is-playing=1  -> expect STOP arm  (bf taken -> 0x0C044074, FUN_0c043ed6 r4=0)
//   B) PATCHED,   is-playing=1  -> expect START arm (bf NOP'd -> fall-through, FUN_0c043ffc -> r4=1)
//   C) UNPATCHED, is-playing=0  -> expect START arm (regression baseline)
//   D) PATCHED,   is-playing=0  -> expect START arm (no regression)
//
// PATCH: at RAM 0x0C044038 (file off 0x44038) replace `bf 0x0C044074` (8b 1c) with NOP (00 09).
// We verify the branch is a PLAIN bf (no delay slot) so a single 2-byte NOP is correct.
//
// Entry FUN_0c044022 expects caller-set r14 (struct ptr) and r0 (a prior result). We map a
// scratch struct at 0x0C7E0000 with halfword [+0xE] = is-playing flag. The lazy fault
// handler zero-fills any other uninitialized read so emulation continues. We detect which
// ARM is reached (0x0C044074 STOP vs the 0x0C04403A.. START region / FUN_0c043ffc) and the
// r4 value at the primitive call site, then stop (derailment at the boot-pointer primitive
// is expected and fine).

import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.pcode.memstate.MemoryFaultHandler;
import ghidra.program.model.address.*;
import ghidra.util.task.TaskMonitor;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.*;

public class Feat7Emu extends GhidraScript {

    static final long ENTRY        = 0x0C044022L;
    static final long BRANCH_ADDR   = 0x0C044038L;   // bf 0x0C044074  (8b1c)
    static final long STOP_ARM      = 0x0C044074L;   // bsr FUN_0c043ed6 ; delay: mov #0,r4
    static final long START_FALLTHRU= 0x0C04403AL;   // fall-through (restart) region
    static final long FUN_043ffc    = 0x0C043FFCL;   // start wrapper -> sets r4=1 -> FUN_0c043ed6
    static final long FUN_043ed6    = 0x0C043ED6L;   // the start/stop primitive
    static final long STRUCT        = 0x0C7E0000L;   // scratch r14 struct base
    static final long SP            = 0x0C7F0000L;
    static final long RET_SENTINEL  = 0x0C7FFFFEL;
    static final long CODE_LO = 0x0C000000L, CODE_HI = 0x0C140000L;
    static final long BUDGET = 4000;

    StringBuilder log = new StringBuilder();
    void P(String s){ log.append(s).append("\n"); println(s); }

    long uninitReads, unknownReads;

    public void run() throws Exception {
        P("######## Feature-7 patch software validation ########");
        P("entry FUN_0c044022; branch bf@0x0C044038 (8b1c); patch -> NOP (0009)");
        P("NOP on SH-2A = 0x0009 (confirmed: 0x0C044072 disassembles to nop = 0009).");
        P("Branch is PLAIN 'bf' (Ghidra does NOT flag it as a delay-slot branch; bf has no");
        P("delay slot, only bf/s does) -> a single 2-byte NOP is safe.\n");

        runScenario("A) UNPATCHED, is-playing=1", false, 1);
        runScenario("B) PATCHED,   is-playing=1", true,  1);
        runScenario("C) UNPATCHED, is-playing=0", false, 0);
        runScenario("D) PATCHED,   is-playing=0", true,  0);

        P("\n######## SUMMARY ########");
        for (Map.Entry<String,String> e : results.entrySet())
            P("  " + e.getKey() + "  ->  " + e.getValue());

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/feat7_emu.txt");
        pw.print(log); pw.close();
        P("\nwrote /Users/velvetmotion/Desktop/DTX-M12/firmware/feat7_emu.txt");
    }

    LinkedHashMap<String,String> results = new LinkedHashMap<>();

    void runScenario(String name, boolean patched, int isPlaying) throws Exception {
        P("============================================================");
        P("SCENARIO " + name);
        P("============================================================");
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        uninitReads = 0; unknownReads = 0;
        try {
            emu.setMemoryFaultHandler(new MemoryFaultHandler() {
                public boolean uninitializedRead(Address a, int size, byte[] buf, int off) {
                    uninitReads++; for (int i=0;i<size;i++) buf[off+i]=0; return true;
                }
                public boolean unknownAddress(Address a, boolean w){ unknownReads++; return true; }
            });

            // ---- scratch struct: halfword at [r14+0xE] = isPlaying (big-endian) ----
            // zero a page first, then set the field.
            byte[] zero = new byte[0x40];
            emu.writeMemory(toAddr(STRUCT), zero);
            emu.writeMemoryValue(toAddr(STRUCT + 0xE), 2, isPlaying & 0xFFFF);

            // ---- apply patch in emulator memory only (never on disk) ----
            if (patched) {
                byte[] before = emu.readMemory(toAddr(BRANCH_ADDR), 2);
                emu.writeMemory(toAddr(BRANCH_ADDR), new byte[]{0x00, 0x09}); // NOP
                byte[] after = emu.readMemory(toAddr(BRANCH_ADDR), 2);
                P(String.format("  patch @0x%08x: %02x%02x -> %02x%02x",
                    BRANCH_ADDR, before[0]&0xFF, before[1]&0xFF, after[0]&0xFF, after[1]&0xFF));
            }

            // ---- registers ----
            for (int i=0;i<=14;i++) emu.writeRegister("r"+i, 0L);
            emu.writeRegister(emu.getStackPointerRegister(), SP);
            emu.writeRegister("pr", RET_SENTINEL);
            emu.writeRegister("r14", STRUCT);        // struct ptr
            emu.writeRegister("r0", 0L);             // first cmp/eq #-1 check: r0!=0xffff so no early skip
            emu.writeRegister("r13", 0L);
            emu.setBreakpoint(toAddr(RET_SENTINEL));
            emu.writeRegister(emu.getPCRegister(), ENTRY);

            // ---- step + trace + arm detection ----
            // STUBBED primitives: these are called via `jsr`/`bsr` (return addr in PR) BEFORE
            // or instead of the work we care about, and themselves do indirect calls through
            // boot-initialized tables (which are zero here). We stub them = immediate return
            // (PC<-PR, r0<-0) so control-flow reaches/continues past the feature-7 branch.
            // 0x0C03F940 : pre-branch helper (jsr @r2 at 0x0C04402E) — must stub to reach branch.
            // 0x0C043ED6 : the actual start/stop PRIMITIVE — we WANT to detect entry+r4, then
            //              stub it so we don't derail (control-flow-only validation).
            // 0x0C03D0BC : helper used by the start wrapper FUN_0c043ffc.
            Set<Long> stubs = new HashSet<>(Arrays.asList(0x0C03F940L, 0x0C03D0BCL));
            // NOTE: we deliberately do NOT stub 0x0C043ED6 until after recording its r4.

            boolean reachedStop=false, reachedStartFallthru=false, reached043ffc=false, reached043ed6=false;
            long r4AtStop=-1, r4At043ed6=-1;
            long count=0; String stop="budget";
            long branchT=-1;  // T flag value when we execute the branch
            StringBuilder trace = new StringBuilder();
            TaskMonitor mon = TaskMonitor.DUMMY;
            long lastPc=-1;
            while (count < BUDGET) {
                long pc = emu.getExecutionAddress().getOffset();
                if (pc == RET_SENTINEL) { stop="returned"; break; }
                if (pc < CODE_LO || pc >= CODE_HI) {
                    stop = "PC left code @0x"+Long.toHexString(pc)+" (boot-pointer primitive; expected)";
                    break;
                }
                // Stub: pop PR into PC, zero r0, skip body.
                if (stubs.contains(pc)) {
                    long ret = emu.readRegister("pr").longValue() & 0xFFFFFFFFL;
                    if (count < 80) trace.append(String.format("    [%3d] 0x%08x  <STUB %s> -> ret 0x%08x\n",
                        count, pc, "primitive", ret));
                    emu.writeRegister("r0", 0L);
                    emu.writeRegister(emu.getPCRegister(), ret);
                    count++;
                    continue;
                }
                // record arm milestones
                if (pc == BRANCH_ADDR) {
                    branchT = emu.readRegister("r0").longValue(); // r0 just tested by tst; capture r0
                }
                if (pc == STOP_ARM) {
                    reachedStop = true;
                    r4AtStop = emu.readRegister("r4").longValue() & 0xFFFFFFFFL; // r4 set in delay slot already? captured at arm head
                }
                if (pc == START_FALLTHRU) reachedStartFallthru = true;
                if (pc == FUN_043ffc) reached043ffc = true;
                if (pc == FUN_043ed6) {
                    reached043ed6 = true;
                    if (r4At043ed6 < 0) r4At043ed6 = emu.readRegister("r4").longValue() & 0xFFFFFFFFL;
                    // record then stub the primitive so we don't derail; we have our answer.
                    long ret = emu.readRegister("pr").longValue() & 0xFFFFFFFFL;
                    if (count < 80) trace.append(String.format(
                        "    [%3d] 0x%08x  <PRIMITIVE FUN_0c043ed6 ENTRY  r4=0x%x>  (start=1/stop=0) -> stub ret 0x%08x\n",
                        count, pc, r4At043ed6, ret));
                    emu.writeRegister("r0", 0L);
                    emu.writeRegister(emu.getPCRegister(), ret);
                    count++;
                    continue;
                }
                // trace the window around the branch (entry .. just past both arms)
                if (count < 80) {
                    String dis = instrAt(pc);
                    if (patched && pc == BRANCH_ADDR) dis = "nop   [PATCHED: was 'bf 0x0C044074']";
                    trace.append(String.format("    [%3d] 0x%08x  %s\n", count, pc, dis));
                }
                lastPc = pc;
                try { if (!emu.step(mon)) {
                    long now = emu.getExecutionAddress().getOffset();
                    if (now==RET_SENTINEL){ stop="returned"; }
                    else stop = "halt: "+emu.getLastError()+" @0x"+Long.toHexString(now);
                    break;
                }} catch (Throwable t){ stop="exc @0x"+Long.toHexString(pc)+": "+t; break; }
                count++;
            }

            P("  trace (first up to 80 instrs):");
            P(trace.toString().replaceAll("\\n$",""));
            P("  instrs="+count+"  stop="+stop+"  lastPc=0x"+Long.toHexString(lastPc));
            P("  r0 at branch (value tst'd) = 0x"+Long.toHexString(branchT)+"  (nonzero => PLAYING => bf taken)");
            P("  reached STOP arm(0x0C044074)="+reachedStop+(reachedStop?(" r4@arm=0x"+Long.toHexString(r4AtStop)):""));
            P("  reached START fall-through(0x0C04403A)="+reachedStartFallthru);
            P("  reached FUN_0c043ffc(start wrapper)="+reached043ffc);
            P("  reached FUN_0c043ed6(primitive)="+reached043ed6+(reached043ed6?(" r4@entry=0x"+Long.toHexString(r4At043ed6)):""));

            String arm;
            if (reachedStop && !reachedStartFallthru) arm = "STOP arm (FUN_0c043ed6 with r4=0)";
            else if ((reachedStartFallthru||reached043ffc) && !reachedStop) arm = "START/restart arm (FUN_0c043ffc -> FUN_0c043ed6 with r4=1)";
            else if (reached043ed6) arm = (r4At043ed6==1? "START arm (r4=1 at primitive)" : (r4At043ed6==0? "STOP arm (r4=0 at primitive)":"ambiguous r4="+r4At043ed6));
            else arm = "UNDETERMINED (neither arm milestone hit before stop)";
            P("  ==> ARM TAKEN: " + arm);
            results.put(name, arm);
            P("");
        } finally { emu.dispose(); }
    }

    String instrAt(long addr) {
        try { var ins = currentProgram.getListing().getInstructionAt(toAddr(addr));
              return ins!=null?ins.toString():"(no-instr)"; } catch (Throwable t){ return "(?)"; }
    }
}
