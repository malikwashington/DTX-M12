// Trace2.java — (a) examine dispatcher 0x0c03cd60 that tail-calls the senders (sets r10-r13?),
// confirming FUN_0c04898a's required context. (b) decompile FUN_0c043ffc (pattern START prim)
// and FUN_0c043ed6 param semantics, and look for a seek-to-0 primitive. (c) find callers of the
// feature-7 toggle FUN_0c044022 (the pad path that reaches it).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Trace2 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // (a) the dispatcher containing 0x0c03cd68 (tail-calls the senders)
        Function disp=fm.getFunctionContaining(toAddr(0x0c03cd68L));
        sb.append("################ dispatcher tail-calling senders (sets r10-r13 ctx for mute) ################\n");
        if (disp!=null){
            sb.append("## "+disp.getName()+" @"+disp.getEntryPoint()+" size="+disp.getBody().getNumAddresses()+"\n");
            for (Instruction ins: iterable(lst.getInstructions(disp.getBody(), true))) {
                Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
                for(Reference r:rf) rs.append(" ->"+r.getToAddress());
                sb.append("  "+ins.getAddress()+"  "+ins.toString()+rs+"\n");
            }
            sb.append("\n-- decompile --\n");
            DecompileResults r=dec.decompileFunction(disp,120,mon);
            if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        } else sb.append("  no func @0x0c03cd68\n");

        // (b) feature-7 primitives
        sb.append("\n################ FUN_0c043ffc (pattern START) ################\n");
        decomp(0x0c043ffcL);
        sb.append("\n################ FUN_0c043ed6 callers (who reaches stop) ################\n");
        // already known; now FUN_0c044022 callers:
        sb.append("\n################ feature-7 toggle FUN_0c044022 callers (pad path) ################\n");
        Function t=fm.getFunctionContaining(toAddr(0x0c044022L));
        for (Function c: t.getCallingFunctions(mon)) sb.append("  <- "+c.getName()+"@"+c.getEntryPoint()+"\n");
        for (Reference r: rm.getReferencesTo(toAddr(0x0c044022L)))
            sb.append("  ref "+r.getReferenceType()+"@"+r.getFromAddress()+"\n");

        // (c) raw disasm of the feature-7 STOP branch in FUN_0c044022 so we get exact bytes
        sb.append("\n################ FUN_0c044022 FULL disasm (exact bytes for the toggle branch) ################\n");
        for (Instruction ins: iterable(lst.getInstructions(t.getBody(), true))) {
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ins.getAddress()+"  "+ins.toString()+rs+"\n");
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/trace2.txt");
        pw.print(sb.toString()); pw.close();
        println("Trace2 wrote trace2.txt ("+sb.length()+" chars)");
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
