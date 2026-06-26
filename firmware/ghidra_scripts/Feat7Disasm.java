// Feat7Disasm.java — disassemble FUN_0c044022 + neighborhood + raw bytes at the branch,
// and the start/stop arm callees, to verify the patch site before emulation.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.Memory;
import java.io.PrintWriter;

public class Feat7Disasm extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        Memory mem = currentProgram.getMemory();

        long[][] windows = {
            {0x0C044022L, 0x0C0440A0L},   // the toggle function
            {0x0C043ED6L, 0x0C043F10L},   // FUN_0c043ed6 (the start/stop primitive)
            {0x0C043FFCL, 0x0C044022L},   // FUN_0c043ffc (start wrapper)
        };
        for (long[] w : windows) {
            sb.append("\n=== disasm 0x"+Long.toHexString(w[0])+" .. 0x"+Long.toHexString(w[1])+" ===\n");
            Address ad = toAddr(w[0]), end = toAddr(w[1]);
            while (ad!=null && ad.compareTo(end)<0) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins==null){ disassemble(ad); ins = lst.getInstructionAt(ad); }
                if (ins==null){ sb.append("  "+ad+"  (data)\n"); ad=ad.add(2); continue; }
                byte[] b = new byte[ins.getLength()];
                mem.getBytes(ad, b);
                StringBuilder bs = new StringBuilder();
                for (byte x : b) bs.append(String.format("%02x", x & 0xFF));
                Reference[] rf = ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
                for (Reference r: rf) rs.append(" ->"+r.getToAddress());
                String ds = ins.getFlowType()!=null && ins.getDelaySlotDepth()>0 ? "  [DELAY-SLOT BRANCH]" : "";
                sb.append("  "+ad+"  "+bs+"  "+ins.toString()+rs+ds+"\n");
                ad = ins.getMaxAddress().add(1);
            }
        }
        // raw bytes exactly at the branch
        sb.append("\n=== raw bytes 0x0C044034 .. 0x0C04403C ===\n");
        byte[] raw = new byte[10]; mem.getBytes(toAddr(0x0C044034L), raw);
        StringBuilder rs = new StringBuilder();
        for (int i=0;i<raw.length;i++){ rs.append(String.format("%02x ", raw[i]&0xFF)); }
        sb.append("  "+rs+"\n");

        // enclosing func of entry
        Function f = fm.getFunctionContaining(toAddr(0x0C044022L));
        sb.append("\nenclosing func of 0x0c044022: "+(f!=null?(f.getName()+"@"+f.getEntryPoint()+" body="+f.getBody()):"none")+"\n");

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/feat7_disasm.txt");
        pw.print(sb.toString()); pw.close();
        println("Feat7Disasm wrote feat7_disasm.txt ("+sb.length()+" chars)");
    }
}
