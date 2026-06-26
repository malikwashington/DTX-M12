// VerifyDispatch.java — confirm FUN_0c0c9e60 is the PAD-STRIKE pad-function dispatcher (not a
// UI param editor). Checks: (1) its callers (should be a pad/trigger event handler), (2) the
// 0x07 (tap) and 0x08 (click) arm handlers FUN_0c0ca010 / FUN_0c0ca026 — confirm they look like
// tap-tempo / click actions (call tempo/metronome funcs), matching the dump-diff. Also dump the
// switch arm targets for codes 0x05,0x06,0x07,0x08 and the default FUN_0c0ca15c.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class VerifyDispatch extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        sb.append("################ FUN_0c0c9e60 callers (is it pad-strike driven?) ################\n");
        Function f=fm.getFunctionContaining(toAddr(0x0c0c9e60L));
        for(Function c: f.getCallingFunctions(mon)) sb.append("  <- "+c.getName()+"@"+c.getEntryPoint()
                  +" size="+c.getBody().getNumAddresses()+"\n");
        for(Reference r: rm.getReferencesTo(toAddr(0x0c0c9e60L)))
            sb.append("  ref "+r.getReferenceType()+"@"+r.getFromAddress()
                      +(fm.getFunctionContaining(r.getFromAddress())!=null?(" in "+fm.getFunctionContaining(r.getFromAddress()).getName()):"")+"\n");
        // decompile the top caller(s)
        sb.append("\n-- caller decompiles --\n");
        for(Function c: f.getCallingFunctions(mon)){
            sb.append("\n#### "+c.getName()+"@"+c.getEntryPoint()+" ####\n");
            DecompileResults r=dec.decompileFunction(c,90,mon);
            if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        }

        // arm handlers for tap(0x07)=FUN_0c0ca010, click(0x08)=FUN_0c0ca026, and a few others
        long[] arms={0x0c0ca010L,0x0c0ca026L,0x0c0c9ff4L,0x0c0c9ffaL,0x0c0ca15cL};
        for(long a: arms){
            sb.append("\n################ arm handler 0x"+Long.toHexString(a)+" ################\n");
            decomp(a);
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/verifydispatch.txt");
        pw.print(sb.toString()); pw.close();
        println("VerifyDispatch wrote verifydispatch.txt ("+sb.length()+" chars)");
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
