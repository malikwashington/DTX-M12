// EmuLeaf.java — disassemble candidate leaf functions + the inner store helpers
// (0c069cf8, 0c06a0f8) that FUN_0c04898a calls, so we can choose a validation target
// and understand the actual memory writes.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import java.io.PrintWriter;

public class EmuLeaf extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        long[] addrs = {
            0x0C003c32L, 0x0C003c46L, 0x0C00f476L, 0x0C00f49aL,   // leaf candidates
            0x0C069cf8L, 0x0C06a0f8L, 0x0C040d1aL, 0x0C041078L    // inner helpers called by target
        };
        for (long a : addrs) {
            Function f = fm.getFunctionContaining(toAddr(a));
            if (f == null) { if (getInstructionAt(toAddr(a))==null) disassemble(toAddr(a)); try { f = createFunction(toAddr(a), null);} catch(Exception e){} }
            sb.append("\n=== " + (f!=null?f.getName():("@0x"+Long.toHexString(a))) + " @ 0x" + Long.toHexString(a) + " ===\n");
            if (f == null) { sb.append("  <no function>\n"); continue; }
            sb.append("  body=" + f.getBody() + " size=" + f.getBody().getNumAddresses() + "\n");
            Address ad = f.getEntryPoint();
            Address end = f.getBody().getMaxAddress();
            while (ad != null && ad.compareTo(end) <= 0) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins == null) { sb.append("  " + ad + "  (data)\n"); ad = ad.add(2); continue; }
                Reference[] rf = ins.getReferencesFrom();
                StringBuilder rs = new StringBuilder();
                for (Reference r : rf) rs.append(" ->" + r.getToAddress());
                sb.append("  " + ad + "  " + ins.toString() + rs + "\n");
                ad = ins.getMaxAddress().add(1);
            }
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/emu_leaf.txt");
        pw.print(sb.toString()); pw.close();
        println("EmuLeaf wrote emu_leaf.txt (" + sb.length() + " chars)");
    }
}
