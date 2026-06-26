// PadFunc.java — find the REAL pad-function dispatcher (UTIL4-1 Pad Function), the path from a
// pad strike to transport/seq/click actions. Strategy:
//  1) callers of FUN_0c0440c6 (transport stop) — the pad "Seq Stop" function calls it.
//  2) callers of the pattern start/stop primitives reachable from a pad (not the MIDI realtime).
//  3) find a switch on a pad-function enum that calls a SET of action funcs incl. transport.
//     The pad-function list (Data List): note/CC handled elsewhere; CONTROL functions include
//     Inc/Dec, Tap, Click, Seq Start/Stop/Top, Bank+/-... -> a dispatcher with ~10-20 arms each
//     calling a distinct small action func.
//  4) Decompile the strongest candidate + its arms, with raw bytes for hook design.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class PadFunc extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) callers of FUN_0c0440c6 (transport stop)
        sb.append("################ callers of FUN_0c0440c6 (transport stop) ################\n");
        callers(0x0c0440c6L);
        // callers of FUN_0c043ed6 stop, FUN_0c043ffc start, FUN_0c044022 toggle, FUN_0c0440de
        long[] seqs={0x0c043ed6L,0x0c043ffcL,0x0c044022L,0x0c0440deL,0x0c044194L,0x0c04408aL};
        for(long s:seqs){ sb.append("\n-- callers of 0x"+Long.toHexString(s)+" --\n"); callers(s); }

        // 2) Heuristic search for the pad-function dispatcher: a function that calls a SET of the
        //    seq/transport funcs above AND has a small-int switch. Score funcs by how many of
        //    {FUN_0c0440c6,FUN_0c044022,FUN_0c0440de,FUN_0c043ed6,FUN_0c043ffc} they call.
        sb.append("\n################ candidate pad-function dispatchers (call >=2 transport funcs) ################\n");
        Set<Long> tset=new HashSet<>(Arrays.asList(0x0c0440c6L,0x0c044022L,0x0c0440deL,0x0c043ed6L,0x0c043ffcL,0x0c044194L));
        for (Function f: iterable(fm.getFunctions(true))) {
            int touch=0; Set<Long> hit=new TreeSet<>();
            for (Function c: f.getCalledFunctions(mon)){
                long e=c.getEntryPoint().getOffset();
                if(tset.contains(e)){ touch++; hit.add(e);}
            }
            if(touch>=2){
                // count small-int cmp keys
                Set<Long> ks=new TreeSet<>();
                for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true)))
                    if(ins.getMnemonicString().startsWith("cmp"))
                        for(int op=0;op<ins.getNumOperands();op++)
                            for(Object o:ins.getOpObjects(op))
                                if(o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=0x40) ks.add(v);}
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" touches="+touch+hit+" cmpKeys="+ks
                          +" size="+f.getBody().getNumAddresses()+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/padfunc.txt");
        pw.print(sb.toString()); pw.close();
        println("PadFunc wrote padfunc.txt ("+sb.length()+" chars)");
    }
    void callers(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func\n"); return; }
        for(Function c: f.getCallingFunctions(mon)) sb.append("  <- "+c.getName()+"@"+c.getEntryPoint()
                  +" size="+c.getBody().getNumAddresses()+"\n");
        for(Reference r: rm.getReferencesTo(toAddr(a)))
            if(r.getReferenceType().isData()) sb.append("  DATA-ref@"+r.getFromAddress()+"\n");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
