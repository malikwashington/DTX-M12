// DumpRE.java — headless post-analysis dump for the DTX-MULTI 12 RE.
// Run: analyzeHeadless <proj> M12 -process code.bin -noanalysis \
//        -scriptPath <dir> -postScript DumpRE.java
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class DumpRE extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        sb.append("function count: " + fm.getFunctionCount() + "\n");

        long[] refTargets = {0x0C096C00L, 0x0C08DFE8L, 0x0C08E404L, 0x0C096C48L};
        for (long a : refTargets) {
            sb.append("\n=== refs to 0x" + Long.toHexString(a) + " ===\n");
            for (Reference r : rm.getReferencesTo(toAddr(a))) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                sb.append("  from " + r.getFromAddress() + " " + r.getReferenceType()
                          + (f != null ? (" in " + f.getName() + "@" + f.getEntryPoint()) : "") + "\n");
            }
        }

        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        // mix of the small "stub" pointers and the larger handlers seen in the FX table
        long[] funcs = {0x0C021712L, 0x0C02171AL, 0x0C0217EAL, 0x0C021832L, 0x0C0218C0L, 0x0C023DB0L};
        for (long a : funcs) {
            Function f = fm.getFunctionContaining(toAddr(a));
            if (f == null) { sb.append("\n--- no function at 0x" + Long.toHexString(a) + " ---\n"); continue; }
            sb.append("\n===== " + f.getName() + " @ " + f.getEntryPoint()
                      + "  (size " + f.getBody().getNumAddresses() + ") =====\n");
            DecompileResults r = dec.decompileFunction(f, 120, mon);
            if (r != null && r.getDecompiledFunction() != null)
                sb.append(r.getDecompiledFunction().getC());
            else
                sb.append("  <decompile failed>\n");
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/ghidra_dump.txt");
        pw.print(sb.toString());
        pw.close();
        println("DumpRE wrote ghidra_dump.txt (" + sb.length() + " chars)");
    }
}
