// StrikeDisp.java — find the PER-STRIKE pad-function dispatcher.
// Strategy A: anchor on ACTION routines (not config arms).
//   - "click on/off toggle": a function that flips a click/metronome state byte.
//   - "tap tempo" / "kit increment": bump tempo var / change current-kit index.
//   Find a SWITCH over a 0..0x10 function code that calls a SET of such action routines AND is
//   reachable from the trigger/note path (region <0x0C060000), distinct from the 0x0C0Cxxxx UI.
// Strategy B: locate functions in the STRIKE region (<0x0C060000) that contain a dense compare
//   ladder on a small int (0..0x10) and call multiple state-changers (seq/tempo/kit/click), i.e.
//   a per-strike pad-function switch. We score every function by (#small-int cmp keys 0..0x10) +
//   (calls into transport/seq funcs FUN_0c044xxx / FUN_0c0440de / FUN_0c044022).
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

public class StrikeDisp extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // Action anchor set: transport/seq/tempo control functions we already identified as the
        // real state-changers (NOT 0x0C0C74xx display refresh).
        Set<Long> actions = new HashSet<>(Arrays.asList(
            0x0c0440deL, // seq top/reset
            0x0c044022L, // pattern toggle
            0x0c0440c6L, // transport stop
            0x0c043ed6L, // pattern stop/start
            0x0c043ffcL, // pattern start
            0x0c04f306L  // pad seq action
        ));

        // Strategy B: scan STRIKE region funcs (<0x0C0C0000, ideally <0x0C060000) for a 0..0x10
        // compare ladder + calls into action set OR many distinct callees (switch-like).
        sb.append("################ per-strike pad-function switch candidates ################\n");
        sb.append("(region<0x0C0C0000, compare ladder over 0..0x10, calls state-changers, NOT 0xC0C74xx)\n");
        List<long[]> ranked=new ArrayList<>();
        for(Function f: iterable(fm.getFunctions(true))){
            long e=f.getEntryPoint().getOffset();
            if(e>=0x0c0c0000L) continue;        // exclude the UI cluster
            // compare keys 0..0x10
            Set<Long> ks=new TreeSet<>();
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
                if(ins.getMnemonicString().startsWith("cmp"))
                    for(int op=0;op<ins.getNumOperands();op++)
                        for(Object o:ins.getOpObjects(op))
                            if(o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=0x12) ks.add(v); }
            int actHit=0;
            for(Function c: f.getCalledFunctions(mon)) if(actions.contains(c.getEntryPoint().getOffset())) actHit++;
            int callees=f.getCalledFunctions(mon).size();
            // score: needs a real ladder (>=5 keys incl. 0/1) and either action calls or many callees
            if(ks.size()>=5 && (actHit>=1 || callees>=8) && f.getBody().getNumAddresses()<2000){
                ranked.add(new long[]{e, ks.size(), actHit, callees, f.getBody().getNumAddresses()});
            }
        }
        ranked.sort((a,b)->{ if(a[2]!=b[2]) return (int)(b[2]-a[2]); return (int)(b[1]-a[1]); });
        for(long[] r: ranked){
            sb.append(String.format("  0x%08x  cmpKeys=%d actionCalls=%d callees=%d size=%d\n",
                      r[0],r[1],r[2],r[3],r[4]));
        }

        // For the top few, decompile to see if arms call state-changers (strike) vs redraw (config).
        sb.append("\n################ top candidate decompiles ################\n");
        int shown=0;
        for(long[] r: ranked){
            if(shown++>=6) break;
            sb.append("\n#### 0x"+Long.toHexString(r[0])+" ####\n");
            decomp(r[0],70);
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/strikedisp.txt");
        pw.print(sb.toString()); pw.close();
        println("StrikeDisp wrote strikedisp.txt ("+sb.length()+" chars)");
    }
    void decomp(long a,int lim) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##\n");
        sb.append("callers:"); for(Function c:f.getCallingFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n"); }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
