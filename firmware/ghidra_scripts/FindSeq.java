// FindSeq.java — locate the sequencer realtime handler (Start 0xFA / Stop 0xFC / Cont 0xFB
// / Clock 0xF8) and the pattern re-strike toggle. The seq start/stop funcs it calls are
// also called by the pad-pattern toggle (feature 7's target).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindSeq extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager(); lst = currentProgram.getListing();
        rm = currentProgram.getReferenceManager();
        DecompInterface dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();

        // realtime handler: 0xFA (start) & 0xFC (stop) [& maybe 0xFB cont, 0xF8 clock]
        sb.append("=== realtime msg handler candidates (have 0xFA & 0xFC) ===\n");
        List<Function> rt = new ArrayList<>();
        for (Function f : iterable(fm.getFunctions(true))) {
            Set<Long> cs = imms(f);
            if (cs.contains(0xFAL) && cs.contains(0xFCL)) {
                rt.add(f);
                sb.append("  " + f.getName() + " @ " + f.getEntryPoint()
                          + (cs.contains(0xFBL)?" +cont":"") + (cs.contains(0xF8L)?" +clock":"") + "\n");
            }
        }
        for (Function f : rt) {
            sb.append("\n##### realtime handler " + f.getName() + " @ " + f.getEntryPoint() + " #####\n");
            // its callees are the seq start/stop primitives
            sb.append("callees:"); for (Function c : f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint());
            sb.append("\n");
            DecompileResults r = dec.decompileFunction(f, 90, mon);
            if (r!=null && r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        }

        // The seq-stop primitive is called from many places; the pad-pattern toggle is one of
        // them. After identifying stop() from the handler above, list its callers.
        // We approximate: collect callees of all realtime handlers, then for each, list callers
        // that ALSO look like they branch on a "is playing" flag.
        sb.append("\n=== callers of realtime-handler callees (toggle sites incl. pad re-strike) ===\n");
        Set<Function> prims = new LinkedHashSet<>();
        for (Function f : rt) prims.addAll(f.getCalledFunctions(mon));
        for (Function p : prims) {
            Set<Function> callers = p.getCallingFunctions(mon);
            if (callers.size()==0 || callers.size()>12) continue;  // skip ubiquitous utils
            sb.append("  primitive " + p.getEntryPoint() + " <- ");
            for (Function c : callers) sb.append(c.getEntryPoint()+" ");
            sb.append("\n");
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/seq_scope.txt");
        pw.print(sb.toString()); pw.close();
        println("FindSeq wrote seq_scope.txt (" + sb.length() + " chars)");
    }
    Set<Long> imms(Function f){ Set<Long> s=new HashSet<>();
        for (Instruction ins : iterable(lst.getInstructions(f.getBody(), true)))
            for (int op=0; op<ins.getNumOperands(); op++)
                for (Object o : ins.getOpObjects(op))
                    if (o instanceof Scalar) s.add(((Scalar)o).getUnsignedValue()&0xFF);
        return s; }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return () -> it; }
}
