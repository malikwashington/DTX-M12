// TempoVar.java — find the runtime TEMPO variable and the handler that INCREMENTS it (the real
// "Tempo Increment" pad-function ACTION). Then check whether that handler is reached from a
// per-strike pad-function dispatch (our hook site) and whether THAT is statically reachable.
//
// Approach: the realtime sequencer FUN_0c044c08 sets tempo via PTR_FUN_0c0450f8(0xfa0000,..) and
// reads a BPM/tempo cell. Tempo inc/dec must: load tempo cell, +/- step, clamp to [min,max]
// (~30..300 = 0x1e..0x12c), store, and re-arm the clock. We scan for funcs that reference a cell
// AND have add/sub of a small step AND a clamp to ~300, in the engine region. Decompile the best.
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

public class TempoVar extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // funcs with tempo-range clamp constants (300=0x12c AND a low bound) + add/sub small step
        sb.append("################ tempo inc/dec candidates (clamp ~0x12c, +/- step) ################\n");
        List<long[]> hits=new ArrayList<>();
        for(Function f: iterable(fm.getFunctions(true))){
            Set<Long> imms=imms(f);
            boolean hasMax = imms.contains(0x12cL);          // 300 bpm
            boolean hasMin = imms.contains(0x1eL)||imms.contains(0x14L)||imms.contains(0x28L); // ~20-40
            boolean hasStep=false;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.startsWith("add 0x1,")||s.startsWith("add -0x1,")||s.startsWith("add 0x")) hasStep=true;
            }
            if(hasMax && f.getBody().getNumAddresses()<600){
                hits.add(new long[]{f.getEntryPoint().getOffset(), hasMin?1:0, hasStep?1:0, f.getBody().getNumAddresses(), f.getCallingFunctions(mon).size()});
            }
        }
        hits.sort((a,b)->{ if(a[1]!=b[1])return (int)(b[1]-a[1]); return (int)(a[3]-b[3]); });
        for(long[] h: hits)
            sb.append(String.format("  0x%08x hasMinBound=%d hasStep=%d size=%d callers=%d\n",h[0],h[1],h[2],h[3],h[4]));

        // decompile the top few
        sb.append("\n################ top tempo-candidate decompiles ################\n");
        int n=0; for(long[] h: hits){ if(n++>=6) break; sb.append("\n"); decomp(h[0],45); }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/tempovar.txt");
        pw.print(sb.toString()); pw.close();
        println("TempoVar wrote tempovar.txt ("+sb.length()+" chars)");
    }
    void decomp(long a,int lim) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                  +" callers="+f.getCallingFunctions(mon).size()+" ##\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n"); }
    }
    Set<Long> imms(Function f){ Set<Long> s=new HashSet<>();
        for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
            for(int op=0;op<ins.getNumOperands();op++)
                for(Object o:ins.getOpObjects(op))
                    if(o instanceof Scalar) s.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
        return s; }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
