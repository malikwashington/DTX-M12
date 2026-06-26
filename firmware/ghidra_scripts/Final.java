// Final.java — verify self-containment of candidate mute/stop primitives for feature 6, and
// confirm the feature-7 pad path. Reports which functions are cleanly callable (no unaff_rXX /
// in_stack params) vs context-dependent.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Final extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // self-containment check: decompile + flag any unaff_/in_stack usage
        long[] cands={0x0c04898aL,0x0c0440c6L,0x0c043ed6L,0x0c043ffcL,0x0c044022L,0x0c0440deL};
        for (long a: cands){
            Function f=fm.getFunctionContaining(toAddr(a));
            DecompileResults r=dec.decompileFunction(f,120,mon);
            String c = (r!=null&&r.getDecompiledFunction()!=null)?r.getDecompiledFunction().getC():"";
            boolean unaff=c.contains("unaff_"), instk=c.contains("in_stack_"), extra=c.contains("extraout");
            sb.append("0x"+Long.toHexString(a)+" "+f.getName()+" size="+f.getBody().getNumAddresses()
                      +"  CLEAN="+(!unaff&&!instk)+(unaff?" [unaff regs]":"")+(instk?" [stack params]":"")
                      +(extra?" [extraout]":"")+"\n");
        }

        // feature-7 pad path: decompile FUN_0c04f306 (caller of toggle FUN_0c044022)
        sb.append("\n################ feature-7 pad path FUN_0c04f306 ################\n");
        decomp(0x0c04f306L);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/final.txt");
        pw.print(sb.toString()); pw.close();
        println("Final wrote final.txt ("+sb.length()+" chars)");
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
