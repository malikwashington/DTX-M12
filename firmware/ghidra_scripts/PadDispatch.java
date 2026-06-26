// PadDispatch.java — find the pad-strike dispatcher and the FUN_0c04898a flag-arg setup.
//  Part 1: resolve the stack-arg convention of FUN_0c04898a by reading the literal pool
//          entries (PTR_FUN_0c048b6c etc.) and confirming the &4 path target (FUN_0c0440c6).
//  Part 2: find the pad/trigger -> voice-play dispatcher. The pad "Pad Function" enum is a
//          small switch. We look for the function that reads the per-pad assignment table
//          (0x16 block / trigger table) and dispatches on a function code. Heuristic: funcs
//          that call a NoteOn/voice-trigger primitive AND have a jump-table or compare ladder
//          on a small index, reachable from the trigger ISR.
//  Part 3: decompile FUN_0c0440c6 (the &4 target) and the funcs around the trigger input.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class PadDispatch extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // Part 1: dump the literal pool of FUN_0c04898a to resolve the function pointers used by
        // each flag path.
        sb.append("################ FUN_0c04898a literal pool (resolves &4/&40/&80 targets) ################\n");
        long[] pool = {0x0c048b6cL,0x0c048b74L,0x0c048b78L,0x0c048b7cL,0x0c048b80L,0x0c048b84L,
                       0x0c048b88L,0x0c048b8cL,0x0c048b90L,0x0c048b94L,0x0c048b98L,0x0c048b9cL,
                       0x0c048ba0L,0x0c048ba4L,0x0c048ba8L,0x0c048bacL,0x0c048bb0L,0x0c048bb4L,0x0c048bb8L};
        for (long p: pool) {
            long v = mem.getInt(toAddr(p)) & 0xFFFFFFFFL;
            Function tf = fm.getFunctionContaining(toAddr(v));
            sb.append("  @0x"+Long.toHexString(p)+" -> 0x"+Long.toHexString(v)
                      +(tf!=null?("  ("+tf.getName()+")"):"")+"\n");
        }

        // Part 2: the &4 target (FUN_0c0440c6) — the true All-Sound-Off action
        sb.append("\n################ &4 path target FUN_0c0440c6 (true AllSoundOff) ################\n");
        decomp(0x0c0440c6L);

        // Part 3: find pad-strike dispatcher.
        // The trigger->voice path: a function that reads a 22-entry / 0x20-stride trigger table
        // and calls a note-on primitive. We already see 0x20-stride table access in FUN_0c023afc,
        // FUN_0c023c4c (the 0x16 block, 0x20/trigger). Decompile the funcs that the trigger ISR
        // feeds. First: who calls FUN_0c03d374-family (the MIDI msg senders that invoke mute)?
        sb.append("\n################ callers of the MIDI-out msg senders (pad strike feeds these) ################\n");
        long[] senders = {0x0c03d374L,0x0c03d428L,0x0c03d500L,0x0c03d92aL};
        Set<Long> up = new LinkedHashSet<>();
        for (long s: senders) {
            Function f=fm.getFunctionContaining(toAddr(s));
            sb.append("  sender 0x"+Long.toHexString(s)+" callers:");
            for (Function c: f.getCallingFunctions(mon)){ sb.append(" "+c.getEntryPoint()); up.add(c.getEntryPoint().getOffset()); }
            sb.append("\n");
        }

        // Part 4: search for a pad-function switch: functions containing a dense compare-ladder on
        // a value in 0..~12 (pad function enum) that also call into voice/seq. Heuristic: a func
        // with >=4 distinct small immediate compares (cmp/eq #k) for k in 0..15 and >=2 calls.
        sb.append("\n################ pad-function switch candidates (compare-ladder 0..N + calls) ################\n");
        for (Function f : iterable(fm.getFunctions(true))) {
            int small=0; Set<Long> ks=new TreeSet<>();
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                String m=ins.getMnemonicString();
                if (m.startsWith("cmp")) {
                    for (int op=0; op<ins.getNumOperands(); op++)
                        for (Object o: ins.getOpObjects(op))
                            if (o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=12){ks.add(v);} }
                }
            }
            if (ks.size()>=5) {
                int calls=f.getCalledFunctions(mon).size();
                if (calls>=2 && calls<=40 && f.getBody().getNumAddresses()<1200)
                    sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" cmpKeys="+ks+" calls="+calls
                              +" size="+f.getBody().getNumAddresses()+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/paddispatch.txt");
        pw.print(sb.toString()); pw.close();
        println("PadDispatch wrote paddispatch.txt ("+sb.length()+" chars)");
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        sb.append("callers:"); for(Function c:f.getCallingFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
