// Disasm.java — raw disassembly of address windows + force-create the enclosing function
// at a given start, to read the bulk-transmit loop that feeds send_dump_block.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class Disasm extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        // windows around the two computed-call sites
        long[][] windows = {{0x0C02C260L, 0x0C02C340L}, {0x0C02C580L, 0x0C02C620L}};
        for (long[] w : windows) {
            sb.append("\n=== disasm 0x" + Long.toHexString(w[0]) + " .. 0x" + Long.toHexString(w[1]) + " ===\n");
            Address ad = toAddr(w[0]);
            while (ad.getOffset() < w[1]) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins == null) { disassemble(ad); ins = lst.getInstructionAt(ad); }
                if (ins == null) { sb.append("  " + ad + "  (data)\n"); ad = ad.add(2); continue; }
                Reference[] rf = ins.getReferencesFrom();
                StringBuilder rs = new StringBuilder();
                for (Reference r : rf) rs.append(" ->" + r.getToAddress());
                sb.append("  " + ad + "  " + ins.toString() + rs + "\n");
                ad = ins.getMaxAddress().add(1);
            }
        }
        // try to decompile the function that really contains 0x0C02C30E by creating one at a
        // few candidate starts just below it.
        DecompInterface dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        FunctionManager fm = currentProgram.getFunctionManager();
        for (long start : new long[]{0x0C02C24CL, 0x0C02C26CL, 0x0C02C2A0L}) {
            Address ad = toAddr(start);
            Function f = fm.getFunctionContaining(ad);
            if (f == null) { if (getInstructionAt(ad)==null) disassemble(ad); try { f = createFunction(ad, null);} catch(Exception e){} }
            if (f != null && f.getBody().contains(toAddr(0x0C02C30EL))) {
                sb.append("\n##### bulk-xmit fn " + f.getName() + " @ " + f.getEntryPoint() + " #####\n");
                DecompileResults r = dec.decompileFunction(f, 120, mon);
                if (r != null && r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
                break;
            }
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/disasm.txt");
        pw.print(sb.toString()); pw.close();
        println("Disasm wrote disasm.txt (" + sb.length() + " chars)");
    }
}
