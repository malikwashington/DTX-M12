// PadCtrl.java — trace the pad-CONTROL (pad-function) strike path from the trigger entry.
//  The pad "Pad Function" set {Inc/Dec, Tap, Click, Seq start/stop/top, ...} acts on a HIT. We
//  anchor on the concrete state vars:
//   - tempo variable: who WRITES a tempo cell with +/- (tap/inc/dec)?
//   - click on/off: who toggles the metronome/click enable byte?
//   - current kit index: who changes it (kit inc/dec)?
//  and find their common per-strike caller (a switch on a 0..N pad-function code).
//  Also: walk UP from FUN_0c04dda2 / FUN_0c0656ba to the trigger ISR to classify the layer.
//  And: dump the handler table PTR_DAT_0c04e098 raw (resolve its RAM base from the pool of
//  FUN_0c04dda2 by reading the actual 4-byte pool words near the function end).
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

public class PadCtrl extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) climb from FUN_0c0656ba (above the action dispatcher) to the trigger entry
        sb.append("################ climb from FUN_0c0656ba toward the trigger entry ################\n");
        Set<Long> seen=new HashSet<>();
        chain(fm.getFunctionContaining(toAddr(0x0c0656baL)),5,seen);
        sb.append("\n-- FUN_0c0656ba decompile --\n");
        decomp(0x0c0656baL,40);

        // 2) The pad-function dispatcher should be reached from the trigger/pad-event path with a
        // "function code" argument. Look for a func that takes a pad-function code and switches to
        // SEQ start/stop/top (FUN_0c0440de/FUN_0c044022/FUN_0c0440c6) AND tap/click/kit — i.e.
        // combines transport actions with tempo/click. Search region <0x0C060000 for funcs calling
        // >=2 of {0c0440de,0c044022,0c0440c6,0c043ed6} AND having a small switch.
        sb.append("\n################ pad-function-action switch (transport + small switch) ################\n");
        Set<Long> seqacts=new HashSet<>(Arrays.asList(0x0c0440deL,0x0c044022L,0x0c0440c6L,0x0c043ed6L,0x0c04f306L,0x0c043ffcL));
        for(Function f: iterable(fm.getFunctions(true))){
            long e=f.getEntryPoint().getOffset();
            if(e>=0x0c0c0000L) continue;
            int hit=0; for(Function c: f.getCalledFunctions(mon)) if(seqacts.contains(c.getEntryPoint().getOffset())) hit++;
            if(hit>=1){
                Set<Long> ks=new TreeSet<>();
                for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
                    if(ins.getMnemonicString().startsWith("cmp"))
                        for(int op=0;op<ins.getNumOperands();op++)
                            for(Object o:ins.getOpObjects(op))
                                if(o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=0x2c) ks.add(v); }
                if(ks.size()>=3)
                    sb.append(String.format("  0x%08x seqActs=%d cmpKeys=%s size=%d\n",e,hit,ks,f.getBody().getNumAddresses()));
            }
        }

        // 3) FUN_0c04f306 was the pad-seq action; what's its full caller chain to the trigger?
        sb.append("\n################ FUN_0c04f306 (pad seq action) caller chain ################\n");
        seen.clear(); chain(fm.getFunctionContaining(toAddr(0x0c04f306L)),5,seen);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/padctrl.txt");
        pw.print(sb.toString()); pw.close();
        println("PadCtrl wrote padctrl.txt ("+sb.length()+" chars)");
    }
    void chain(Function f,int depth,Set<Long> seen) throws Exception {
        if(f==null||depth<0) return;
        for(Function c: f.getCallingFunctions(mon)){
            long e=c.getEntryPoint().getOffset();
            sb.append("  ".repeat(5-depth)+"<- "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+"\n");
            if(seen.add(e)) chain(c,depth-1,seen);
        }
    }
    void decomp(long a,int lim) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n"); }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
