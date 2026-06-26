// TblOwner.java — for each action-bearing function-pointer table (embedded pool reached PC-rel by
// its owning function via a `bra` that skips it), find the owning function and decompile it to see
// how it indexes the table (the switch/index = pad-function code -> action). These are the
// per-strike dispatch candidates.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class TblOwner extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // action-bearing tables found earlier (bases) — find owning function (the func whose body
        // contains the table address), decompile it.
        long[] tables={0x0c02b498L,0x0c02b8a8L,0x0c02b248L,0x0c02acc0L,0x0c027d88L};
        for(long t: tables){
            sb.append("\n################ table @0x"+Long.toHexString(t)+" owner ################\n");
            Function f=fm.getFunctionContaining(toAddr(t));
            if(f==null){
                // the table is data between functions; find the function whose body END is just
                // before t, or scan back for the nearest function entry.
                Function near=null; long bestStart=0;
                for(Function g: iterable(fm.getFunctions(true))){
                    long s=g.getEntryPoint().getOffset();
                    if(s<t && s>bestStart && t-s<0x600){ bestStart=s; near=g; }
                }
                f=near;
                sb.append("  (table is inter-function data; nearest preceding func: "+(f!=null?f.getName()+"@"+f.getEntryPoint():"none")+")\n");
            }
            if(f!=null){
                sb.append("## owner "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                          +" callers="+f.getCallingFunctions(mon).size()+" ##\n");
                // does the function body's address range include t? (PC-rel pool inside it)
                boolean contains=f.getBody().contains(toAddr(t));
                sb.append("  body contains table: "+contains+"\n");
                DecompileResults r=dec.decompileFunction(f,90,mon);
                if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
                    for(int i=0;i<Math.min(ls.length,55);i++) sb.append(ls[i]).append("\n"); }
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/tblowner.txt");
        pw.print(sb.toString()); pw.close();
        println("TblOwner wrote tblowner.txt ("+sb.length()+" chars)");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
