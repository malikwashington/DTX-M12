// EmuRecon.java — recon for the emulation harness:
//  (1) disassemble FUN_0c04898a (the "mute all voices" loop target)
//  (2) find small leaf functions (no calls inside) for validation
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import java.io.PrintWriter;
import java.util.*;

public class EmuRecon extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        // (1) target FUN_0c04898a
        long TGT = 0x0C04898AL;
        Function tf = fm.getFunctionContaining(toAddr(TGT));
        if (tf == null) {
            if (getInstructionAt(toAddr(TGT)) == null) disassemble(toAddr(TGT));
            try { tf = createFunction(toAddr(TGT), null); } catch (Exception e) {}
        }
        sb.append("=== TARGET FUN_0c04898a ===\n");
        if (tf != null) {
            sb.append("entry=" + tf.getEntryPoint() + " body=" + tf.getBody()
                + " numAddr=" + tf.getBody().getNumAddresses() + "\n");
            Address ad = tf.getEntryPoint();
            Address end = tf.getBody().getMaxAddress();
            while (ad != null && ad.compareTo(end) <= 0) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins == null) { sb.append("  " + ad + "  (data)\n"); ad = ad.add(2); continue; }
                Reference[] rf = ins.getReferencesFrom();
                StringBuilder rs = new StringBuilder();
                for (Reference r : rf) rs.append(" ->" + r.getToAddress());
                sb.append("  " + ad + "  " + ins.toString() + rs + "\n");
                ad = ins.getMaxAddress().add(1);
            }
        } else sb.append("  could not create function at target\n");

        // (2) find small leaf functions: no CALL refs out, modest size
        sb.append("\n=== small leaf functions (no calls, size<=40 bytes) ===\n");
        int found = 0;
        for (Function f : fm.getFunctions(true)) {
            long n = f.getBody().getNumAddresses();
            if (n > 40 || n < 4) continue;
            boolean hasCall = false;
            InstructionIterator it = lst.getInstructions(f.getBody(), true);
            int insCount = 0;
            while (it.hasNext()) {
                Instruction ins = it.next();
                insCount++;
                String m = ins.getMnemonicString().toLowerCase();
                if (m.startsWith("jsr") || m.startsWith("bsr") || m.equals("jmp") || m.startsWith("trapa")) { hasCall = true; break; }
            }
            if (hasCall) continue;
            sb.append("  " + f.getName() + " @ " + f.getEntryPoint() + " size=" + n + " ins=" + insCount + "\n");
            if (++found >= 30) break;
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/emu_recon.txt");
        pw.print(sb.toString()); pw.close();
        println("EmuRecon wrote emu_recon.txt (" + sb.length() + " chars)");
    }
}
