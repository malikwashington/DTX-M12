// FindFeatures.java — locate code sites for two firmware patches:
//  (1) All Sound Off / channel-mode handler (CC 120 = 0x78, 123 = 0x7B, mono126/poly127)
//  (2) pattern playback toggle (re-strike -> stop) — via pattern/seq strings + xrefs
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindFeatures extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm;
    Listing lst;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        lst = currentProgram.getListing();

        // (1) channel-mode CC handler: functions whose instructions use 0x78 AND 0x7B
        sb.append("=== A) functions with immediates 0x78 (AllSoundOff) and 0x7B (AllNoteOff) ===\n");
        List<Function> cc = new ArrayList<>();
        FunctionIterator it = fm.getFunctions(true);
        while (it.hasNext()) {
            Function f = it.next();
            Set<Long> cs = imms(f);
            if (cs.contains(0x78L) && cs.contains(0x7BL)) {
                cc.add(f);
                sb.append("  " + f.getName() + " @ " + f.getEntryPoint()
                          + "  has{120,123" + (cs.contains(0x7EL)?",126":"") + (cs.contains(0x7FL)?",127":"") + "}\n");
            }
        }
        // also: functions with 0x78 but not necessarily 0x7B (narrower)
        sb.append("\n=== A2) functions with 0x78 present (All Sound Off candidates) ===\n");
        it = fm.getFunctions(true);
        int n=0;
        while (it.hasNext() && n<25) {
            Function f = it.next();
            Set<Long> cs = imms(f);
            if (cs.contains(0x78L) && cs.contains(0x7AL) && cs.contains(0x7CL)) { // 120,122,124 = mode range
                sb.append("  " + f.getName() + " @ " + f.getEntryPoint() + "  (mode-range compares)\n"); n++;
            }
        }

        // (2) pattern / sequencer strings
        sb.append("\n=== B) strings matching pattern/phrase/seq + referencing funcs ===\n");
        ReferenceManager rm = currentProgram.getReferenceManager();
        DataIterator di = lst.getDefinedData(true);
        Memory mem = currentProgram.getMemory();
        String[] keys = {"pattern","Pattern","PATTERN","phrase","Phrase","Seq","seq","Loop","loop","Stop","stop"};
        int shown=0;
        for (Data d : iterable(lst.getDefinedData(true))) {
            if (shown>40) break;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            boolean hit=false; for (String k: keys) if (s.contains(k)) hit=true;
            if (!hit || s.length()<3 || s.length()>24) continue;
            StringBuilder refs = new StringBuilder();
            for (Reference r : rm.getReferencesTo(d.getAddress())) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                if (f!=null) refs.append(" "+f.getName()+"@"+f.getEntryPoint());
            }
            if (refs.length()>0) { sb.append("  \""+s+"\" @"+d.getAddress()+" <-"+refs+"\n"); shown++; }
        }

        // decompile the strongest All-Sound-Off candidates and their callees
        DecompInterface dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        sb.append("\n=== C) decompiled channel-mode handler candidates ===\n");
        for (Function f : cc) {
            sb.append("\n##### " + f.getName() + " @ " + f.getEntryPoint() + " #####\n");
            DecompileResults r = dec.decompileFunction(f, 90, mon);
            if (r!=null && r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/features_scope.txt");
        pw.print(sb.toString()); pw.close();
        println("FindFeatures wrote features_scope.txt (" + sb.length() + " chars)");
    }

    Set<Long> imms(Function f) {
        Set<Long> s = new HashSet<>();
        InstructionIterator ii = lst.getInstructions(f.getBody(), true);
        while (ii.hasNext()) {
            Instruction ins = ii.next();
            for (int op=0; op<ins.getNumOperands(); op++)
                for (Object o : ins.getOpObjects(op))
                    if (o instanceof Scalar) s.add(((Scalar)o).getUnsignedValue() & 0xFF);
        }
        return s;
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return () -> it; }
}
