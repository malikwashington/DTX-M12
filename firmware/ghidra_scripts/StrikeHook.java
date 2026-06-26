// StrikeHook.java — authoritative decomp of the two tempo-cell functions + literal-pool callers.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class StrikeHook extends GhidraScript {
    Listing lst;
    Memory mem;
    public void run() throws Exception {
        lst = currentProgram.getListing();
        mem = currentProgram.getMemory();
        long[] funcs = {0x0C042B6AL, 0x0C0443F6L};
        DecompInterface dec = new DecompInterface();
        dec.toggleCCode(true);
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        FunctionManager fm = currentProgram.getFunctionManager();

        for (long fa : funcs) {
            Address a = toAddr(fa);
            Function f = fm.getFunctionContaining(a);
            if (f == null) { if(getInstructionAt(a)==null) disassemble(a); try{f=createFunction(a,null);}catch(Exception e){} }
            println("\n===================== FUNC 0x"+Long.toHexString(fa)+" =====================");
            if (f != null) {
                println("entry="+f.getEntryPoint()+"  body="+f.getBody().getMinAddress()+".."+f.getBody().getMaxAddress());
                DecompileResults r = dec.decompileFunction(f, 60, mon);
                if (r!=null && r.decompileCompleted()) println(r.getDecompiledFunction().getC());
                else println("(decomp failed: "+(r==null?"null":r.getErrorMessage())+")");
                // raw disasm of body
                println("---- DISASM ----");
                Address ad = f.getBody().getMinAddress();
                Address end = f.getBody().getMaxAddress();
                while (ad.compareTo(end) <= 0) {
                    Instruction ins = lst.getInstructionAt(ad);
                    if (ins==null){ println("  "+ad+"  (data)"); ad=ad.add(2); continue; }
                    StringBuilder rs=new StringBuilder();
                    for (Reference rf: ins.getReferencesFrom()) rs.append(" ->"+rf.getToAddress());
                    println("  "+ad+"  "+ins.toString()+rs);
                    ad = ins.getMaxAddress().add(1);
                }
            } else println("(no function)");
        }

        // ---- literal-pool-aware callers of FUN_0c042b6a and FUN_0c0443f6 ----
        for (long tgt : new long[]{0x0C042B6AL, 0x0C0443F6L, 0x0C045154L}) {
            println("\n===== CALLERS of 0x"+Long.toHexString(tgt)+" =====");
            // 1) Ghidra direct refs
            ReferenceManager rm = currentProgram.getReferenceManager();
            for (Reference rf : rm.getReferencesTo(toAddr(tgt))) {
                println("  ref FROM "+rf.getFromAddress()+" type="+rf.getReferenceType());
            }
            // 2) literal-pool scan: find any 32-bit word in code == tgt, then who loads it
            long base=0x0C000000L, lo=0x0C000000L, hi=0x0C140000L;
            for (long p=lo; p<hi; p+=4) {
                long w = mem.getInt(toAddr(p)) & 0xFFFFFFFFL;
                if (w==tgt) {
                    println("  literal 0x"+Long.toHexString(tgt)+" stored at pool 0x"+Long.toHexString(p));
                    // who loads this pool via mov.l @(disp,pc)? scan backwards ~512 bytes for D-form
                    for (long pc=Math.max(lo,p-0x400); pc<p; pc+=2) {
                        int ins = mem.getShort(toAddr(pc)) & 0xFFFF;
                        if ((ins>>12)==0xD) {
                            long la = (pc & ~3L) + 4 + (ins & 0xFF)*4;
                            if (la==p) println("      loaded by mov.l@(pc) at 0x"+Long.toHexString(pc)+" -> r"+((ins>>8)&0xF));
                        }
                    }
                }
            }
        }
    }
}
