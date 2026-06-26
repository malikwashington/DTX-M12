// TrigPath.java — find the trigger/pad-hit -> pad-function strike path.
//  The strike engine, on a pad hit, reads the kit's per-pad record (incl. the Pad Function field
//  = cat 0x02 off 0x00). We:
//   1) find the kit-data RAM base (the 0x02/0x21 per-pad records the strike engine consults) by
//      locating functions that read a per-pad record with stride matching the 6-byte cat-0x02 or
//      the trigger table; and the pad-hit entry that dispatches voice vs control.
//   2) anchor on FUN_0c044570 (750B, in the seq engine, calls FUN_0c044938 -> ... -> pad action)
//      and FUN_0c05a10a — decompile to see the pad-event dispatch (note vs control/function).
//   3) search for the function that, given a pad/trigger index, branches on a "this pad is a
//      CONTROL function (not a voice)" flag and dispatches the function code -> the strike
//      pad-function switch. Heuristic: funcs reading a pad record field then a switch calling
//      transport/tempo/click.
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

public class TrigPath extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        long[] targets={0x0c044570L,0x0c044938L,0x0c05a10aL,0x0c050e0eL,0x0c0500d6L};
        for(long t: targets){ sb.append("\n################ "+Long.toHexString(t)+" ################\n"); decomp(t,80); }

        // climb from FUN_0c05a10a (the top of the FUN_0c044938 chain) to the trigger entry
        sb.append("\n################ climb from FUN_0c05a10a (toward trigger ISR) ################\n");
        Set<Long> seen=new HashSet<>();
        chain(fm.getFunctionContaining(toAddr(0x0c05a10aL)),5,seen);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/trigpath.txt");
        pw.print(sb.toString()); pw.close();
        println("TrigPath wrote trigpath.txt ("+sb.length()+" chars)");
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
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n"); }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
