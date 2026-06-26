// Decomp.java — decompile a fixed list of functions + list their callees, to trace the
// SysEx parser -> parameter dispatch. Edit TARGETS and re-run.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Decomp extends GhidraScript {
    public void run() throws Exception {
        long[] TARGETS = {0x0C103baaL, 0x0C042042L};
        StringBuilder sb = new StringBuilder();
        FunctionManager fm = currentProgram.getFunctionManager();
        DecompInterface dec = new DecompInterface();
        dec.toggleCCode(true);
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        for (long a : TARGETS) {
            Function f = fm.getFunctionContaining(toAddr(a));
            if (f == null) { sb.append("\n--- no func at 0x" + Long.toHexString(a) + " ---\n"); continue; }
            sb.append("\n\n########## " + f.getName() + " @ " + f.getEntryPoint()
                      + "  size=" + f.getBody().getNumAddresses() + " ##########\n");
            // callees
            Set<Function> callees = f.getCalledFunctions(mon);
            sb.append("calls: ");
            for (Function c : callees) sb.append(c.getEntryPoint() + " ");
            sb.append("\n");
            DecompileResults r = dec.decompileFunction(f, 120, mon);
            if (r != null && r.getDecompiledFunction() != null)
                sb.append(r.getDecompiledFunction().getC());
            else
                sb.append("  <decompile failed>\n");
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/decomp.txt");
        pw.print(sb.toString()); pw.close();
        println("Decomp wrote decomp.txt (" + sb.length() + " chars)");
    }
}
