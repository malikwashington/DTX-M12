// Hook.java — finalize feature-6 hook + feature-7 toggle.
//  A) Confirm FUN_0c04898a register setup: disasm FULL bodies of its 4 callers' prologues to see
//     how r10/r11/r12/r13 and the stack flag byte (@stack+3) are established before the jsr.
//  B) Decompile the strongest pad-function dispatch candidates (FUN_0c0c9e60 keys1-12,
//     FUN_0c0bb0ac keys0-7) to locate the per-pad function switch (feature-6 hook) and any
//     pattern start/stop (feature-7 toggle).
//  C) Search the sequencer subsystem for the pad-pattern toggle: a func that tests a "playing"
//     flag and calls a stop primitive, reachable from a pad. We check FUN_0c043ed6 (seq stop?)
//     callers and FUN_0c044194 / FUN_0c04408a (start) usage.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Hook extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // A) full disasm of FUN_0c03d374 (representative caller) to see r10-r13 + stack flag setup
        sb.append("################ A) caller FUN_0c03d374 FULL disasm (reg/stack setup for 0c04898a) ################\n");
        fullDisasm(0x0c03d374L);

        // B) pad-function dispatch candidates
        long[] disp = {0x0c0c9e60L, 0x0c0bb0acL, 0x0c12e5c0L};
        for (long a: disp) {
            sb.append("\n################ B) dispatch candidate 0x"+Long.toHexString(a)+" ################\n");
            decomp(a);
        }

        // C) seq stop/start primitives + their callers to find pad-pattern toggle
        sb.append("\n################ C) seq playback primitives + callers ################\n");
        long[] prims = {0x0c043ed6L, 0x0c044194L, 0x0c04408aL, 0x0c043e06L, 0x0c044348L};
        for (long p: prims) {
            Function f=fm.getFunctionContaining(toAddr(p));
            if (f==null) continue;
            sb.append("\n-- prim 0x"+Long.toHexString(p)+" callers:");
            for (Function c: f.getCallingFunctions(mon)) sb.append(" "+c.getEntryPoint());
            sb.append("\n");
        }
        // decompile FUN_0c043ed6 (candidate seq-stop) and FUN_0c044194 (candidate seq-start)
        sb.append("\n-- DECOMP seq prims --\n");
        decomp(0x0c043ed6L);
        decomp(0x0c044194L);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/hook.txt");
        pw.print(sb.toString()); pw.close();
        println("Hook wrote hook.txt ("+sb.length()+" chars)");
    }

    void fullDisasm(long a) {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func\n"); return; }
        for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ins.getAddress()+"  "+ins.toString()+rs+"\n");
        }
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        else sb.append("  <decompile failed>\n");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
