// ForceDecomp.java — disassemble + createFunction (if needed) + decompile a list of
// addresses Ghidra failed to auto-define, then dump the decompilation.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class ForceDecomp extends GhidraScript {
    public void run() throws Exception {
        long[] TARGETS = {0x0C02C214L, 0x0C0232BEL, 0x0C0232dcL};
        StringBuilder sb = new StringBuilder();
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        for (long a : TARGETS) {
            Address ad = toAddr(a);
            Function f = fm.getFunctionContaining(ad);
            if (f == null) {
                if (getInstructionAt(ad) == null) disassemble(ad);
                try { f = createFunction(ad, null); } catch (Exception e) { sb.append("createFunction failed @"+ad+": "+e+"\n"); }
            }
            if (f == null) { sb.append("\n--- still no func at 0x"+Long.toHexString(a)+" ---\n"); continue; }
            sb.append("\n\n########## " + f.getName() + " @ " + f.getEntryPoint()
                      + " size=" + f.getBody().getNumAddresses() + " ##########\n");
            DecompileResults r = dec.decompileFunction(f, 120, mon);
            if (r != null && r.getDecompiledFunction() != null)
                sb.append(r.getDecompiledFunction().getC());
            else sb.append("  <decompile failed>\n");
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/force.txt");
        pw.print(sb.toString()); pw.close();
        println("ForceDecomp wrote force.txt (" + sb.length() + " chars)");
    }
}
