// Feature7.java — pin the pad re-strike -> STOP toggle in the sequencer subsystem.
// Strategy:
//  1) Identify the seq stop/start/seek primitives. The realtime handler FUN_0c044c08 reacts
//     to MIDI Stop 0xFC -> stop primitive; Start 0xFA -> start; seek-to-0 is a candidate
//     primitive too. We enumerate seq primitives and their callers.
//  2) The pad-pattern handler reads a per-pad "pattern assigned?" + "is playing?" state and
//     calls stop. Surface functions that (a) call a stop primitive AND (b) test a playing flag
//     (branch then call), narrow set.
//  3) Decompile + disasm the strongest candidates so the toggle branch + stop call can be
//     pinned to exact addresses, and check for an existing seek-to-start primitive.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Feature7 extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        long HANDLER = 0x0C044c08L;   // realtime seq handler (Start/Stop/Cont/Clock)

        // 1) realtime handler callees = candidate seq primitives (start/stop/cont/clock/seek)
        sb.append("################ seq primitives (callees of FUN_0c044c08) ################\n");
        Function h = fm.getFunctionContaining(toAddr(HANDLER));
        Set<Function> prims = new LinkedHashSet<>();
        if (h!=null) prims.addAll(h.getCalledFunctions(mon));
        for (Function p : prims) {
            Set<Function> callers = p.getCallingFunctions(mon);
            sb.append("  prim "+p.getEntryPoint()+" size="+p.getBody().getNumAddresses()
                      +" callers="+callers.size()+" callees="+p.getCalledFunctions(mon).size()+"\n");
        }

        // 2) functions that look like the pad-pattern toggle: small/medium funcs that call one
        // of the seq primitives AND contain a conditional that gates the call (we approximate by
        // "calls a prim AND has <=2 callees overall AND is called by few sites").
        sb.append("\n################ pad-pattern toggle candidates ################\n");
        // also gather the union of callers of all prims, scored by how many prims they touch
        Map<Long,Integer> score = new HashMap<>();
        Map<Long,Function> byEntry = new HashMap<>();
        for (Function p: prims) {
            for (Function c : p.getCallingFunctions(mon)) {
                long e=c.getEntryPoint().getOffset();
                score.merge(e,1,Integer::sum); byEntry.put(e,c);
            }
        }
        List<Map.Entry<Long,Integer>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((a,b)->b.getValue()-a.getValue());
        for (Map.Entry<Long,Integer> e : ranked) {
            Function c=byEntry.get(e.getKey());
            sb.append("  caller 0x"+Long.toHexString(e.getKey())+" touches "+e.getValue()
                      +" prims  size="+c.getBody().getNumAddresses()+"\n");
        }

        // 3) decompile the realtime handler's stop/start dispatch region + top toggle candidates
        sb.append("\n################ DECOMP: realtime handler FUN_0c044c08 ################\n");
        decompAndImm(HANDLER);

        sb.append("\n################ DECOMP: top toggle candidates ################\n");
        int n=0;
        for (Map.Entry<Long,Integer> e : ranked) {
            if (n++>=8) break;
            long a=e.getKey();
            if (a==HANDLER) continue;
            decompAndImm(a);
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/feature7.txt");
        pw.print(sb.toString()); pw.close();
        println("Feature7 wrote feature7.txt ("+sb.length()+" chars)");
    }

    void decompAndImm(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("\n## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        else sb.append("  <decompile failed>\n");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
