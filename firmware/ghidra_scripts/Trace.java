// Trace.java — resolve the exact registers FUN_0c04898a needs, and pin feature-7 toggle.
//  1) Decompile the 4 callers' SHARED structure: they are reached via a RAM dispatch table.
//     Find what sets r10/r11/r12/r13 — search upward: who installs &FUN_0c03d374 into a table,
//     and what common trampoline precedes these handlers. We disasm a wider window BEFORE each
//     handler entry to see if r10..r13 are set by a shared preamble (tail-call / fallthrough).
//  2) Resolve the function pointers actually in r10/r12 by checking the literal pool refs that
//     FEED the dispatch (the senders load r10/r12 nowhere -> they MUST be globals/saved regs).
//     We dump the instructions in each sender that write r10,r11,r12,r13.
//  3) Feature-7: the pad re-strike toggle. The owner's manual behavior is in the PATTERN pad
//     handler. Search for a function that: reads a per-pad "pattern playing" state byte and
//     branches to a stop vs start. We scan funcs that reference DAT in the seq state area and
//     contain both a start primitive (FUN_0c044194/FUN_0c04408a) path and a stop/clear path.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Trace extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) For each sender, list instructions writing r10/r11/r12/r13 (their definitions before
        //    the 0c04898a call), to prove whether they're MIDI data or function pointers.
        long[] senders={0x0c03d374L,0x0c03d428L,0x0c03d500L,0x0c03d92aL};
        sb.append("################ reg writes (r10-r13) in senders before mute call ################\n");
        for (long s: senders) {
            Function f=fm.getFunctionContaining(toAddr(s));
            sb.append("\n-- "+f.getName()+" --\n");
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                Object[] res=ins.getResultObjects();
                boolean w=false;
                for (Object o: res){ String rn=o.toString(); if(rn.equals("r10")||rn.equals("r11")||rn.equals("r12")||rn.equals("r13")) w=true; }
                if (w) sb.append("  "+ins.getAddress()+"  "+ins.toString()+"\n");
            }
        }

        // 2) Who references the 4 senders' addresses as DATA (installs them in a table)?
        sb.append("\n################ DATA refs to senders (dispatch-table install sites) ################\n");
        for (long s: senders) {
            sb.append("  sender 0x"+Long.toHexString(s)+":");
            for (Reference r: rm.getReferencesTo(toAddr(s))) {
                sb.append(" "+r.getReferenceType()+"@"+r.getFromAddress());
            }
            sb.append("\n");
        }

        // 3) Feature-7: scan for the pad-pattern toggle. A pad assigned a Pattern uses message
        //    type that triggers pattern play. The toggle "if playing -> stop" lives where a
        //    pattern-play request checks current state. Search functions that call FUN_0c044194
        //    (seq start, param 1=on) OR FUN_0c043ed6 and ALSO contain a branch testing a state byte
        //    then a "stop" (call with arg 0). We already saw FUN_0c0440de and FUN_0c044022 call
        //    FUN_0c043ed6. Decompile FUN_0c044022 and FUN_0c043ffe (small seq toggles).
        sb.append("\n################ feature-7 toggle candidates (callers of seq stop FUN_0c043ed6) ################\n");
        long[] t7={0x0c044022L,0x0c043ffeL,0x0c0440deL};
        for (long a: t7){ sb.append("\n"); decomp(a); }

        // Also: the pad path that PLAYS a pattern. Find funcs calling FUN_0c044194 or the pattern
        // start with a "toggle" structure. Search all funcs that reference the seq "playing" flag.
        // The realtime handler reads *pcVar8 (DAT_0c044e28) as a transport-state. Find other readers.
        sb.append("\n################ readers of transport-state flags (near FUN_0c044xxx) ################\n");
        long[] flags={0x0c044180L,0x0c044144L,0x0c044148L,0x0c044410L,0x0c044414L};
        for (long fl: flags) {
            sb.append("  flag 0x"+Long.toHexString(fl)+" referenced by:");
            Set<Long> fns=new LinkedHashSet<>();
            for (Reference r: rm.getReferencesTo(toAddr(fl))) {
                Function f=fm.getFunctionContaining(r.getFromAddress());
                if(f!=null) fns.add(f.getEntryPoint().getOffset());
            }
            for (long fn: fns) sb.append(" 0x"+Long.toHexString(fn));
            sb.append("\n");
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/trace.txt");
        pw.print(sb.toString()); pw.close();
        println("Trace wrote trace.txt ("+sb.length()+" chars)");
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
