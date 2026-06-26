// Callers.java — find all callers of a target function and decompile each, to enumerate
// the (addrH,addrM,addrL,count) blocks passed to the SysEx dump transmitter.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Callers extends GhidraScript {
    public void run() throws Exception {
        long TARGET = 0x0C0232dcL;       // send_dump_block wrapper
        StringBuilder sb = new StringBuilder();
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        DecompInterface dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();

        Set<Long> callerEntries = new LinkedHashSet<>();
        sb.append("=== call sites of 0x" + Long.toHexString(TARGET) + " ===\n");
        for (Reference r : rm.getReferencesTo(toAddr(TARGET))) {
            Function f = fm.getFunctionContaining(r.getFromAddress());
            sb.append("  " + r.getFromAddress() + " " + r.getReferenceType()
                      + (f != null ? (" in " + f.getName() + "@" + f.getEntryPoint()) : " (no func)") + "\n");
            if (f != null) callerEntries.add(f.getEntryPoint().getOffset());
        }
        sb.append("(" + callerEntries.size() + " distinct caller functions)\n");

        for (long a : callerEntries) {
            Function f = fm.getFunctionContaining(toAddr(a));
            sb.append("\n\n########## CALLER " + f.getName() + " @ " + f.getEntryPoint()
                      + " size=" + f.getBody().getNumAddresses() + " ##########\n");
            DecompileResults r = dec.decompileFunction(f, 120, mon);
            if (r != null && r.getDecompiledFunction() != null)
                sb.append(r.getDecompiledFunction().getC());
            else sb.append("  <decompile failed>\n");
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/callers.txt");
        pw.print(sb.toString()); pw.close();
        println("Callers wrote callers.txt (" + sb.length() + " chars)");
    }
}
