// FindLeafRts.java — find small leaf functions (no jsr/bsr/jmp) that END in rts/rts/n
// and perform at least one memory store, for use as emulation-validation targets.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import java.io.PrintWriter;

public class FindLeafRts extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        int found = 0;
        for (Function f : fm.getFunctions(true)) {
            long n = f.getBody().getNumAddresses();
            if (n > 60 || n < 6) continue;
            boolean hasCall=false, hasRts=false, hasStore=false, hasBranch=false;
            int insCount=0;
            InstructionIterator it = lst.getInstructions(f.getBody(), true);
            while (it.hasNext()) {
                Instruction ins = it.next(); insCount++;
                String m = ins.getMnemonicString().toLowerCase();
                if (m.startsWith("jsr")||m.startsWith("bsr")||m.equals("jmp")||m.startsWith("trapa")) hasCall=true;
                if (m.startsWith("rts")) hasRts=true;
                // store form: the @ (memory operand) comes AFTER the comma => destination
                String s = ins.toString();
                int at = s.indexOf("@"); int comma = s.indexOf(",");
                if (m.startsWith("mov") && at>=0 && comma>=0 && at>comma) hasStore=true;
            }
            if (hasCall || !hasRts || !hasStore) continue;
            sb.append(f.getName()+" @ "+f.getEntryPoint()+" size="+n+" ins="+insCount+"\n");
            // dump it
            Address ad = f.getEntryPoint(), end = f.getBody().getMaxAddress();
            while (ad!=null && ad.compareTo(end)<=0) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins==null){ sb.append("    "+ad+"  (data)\n"); ad=ad.add(2); continue;}
                sb.append("    "+ad+"  "+ins.toString()+"\n");
                ad = ins.getMaxAddress().add(1);
            }
            sb.append("\n");
            if (++found>=8) break;
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/leaf_rts.txt");
        pw.print(sb.toString()); pw.close();
        println("FindLeafRts wrote leaf_rts.txt ("+found+" funcs)");
    }
}
