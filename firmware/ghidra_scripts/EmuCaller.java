// EmuCaller.java — find callers of FUN_0c04898a and disassemble the call site context
// to recover how r10/r11/r12/r13 (helper-fn pointers + slot base) are set up before entry.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.Memory;
import java.io.PrintWriter;

public class EmuCaller extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        long TGT = 0x0C04898AL;
        sb.append("=== refs to 0x0c04898a ===\n");
        for (Reference r : rm.getReferencesTo(toAddr(TGT))) {
            Function f = fm.getFunctionContaining(r.getFromAddress());
            sb.append("  from " + r.getFromAddress() + " " + r.getReferenceType()
                + (f!=null?(" in "+f.getName()+"@"+f.getEntryPoint()):"")+"\n");
        }
        // Also: enclosing function of the target itself (the entry 0c04898a may be mid-func).
        Function tf = fm.getFunctionContaining(toAddr(TGT));
        sb.append("\nenclosing func of target entry: " + (tf!=null?(tf.getName()+"@"+tf.getEntryPoint()+" body="+tf.getBody()):"none")+"\n");
        // Dump 0x80 bytes BEFORE the entry to see prologue / pointer setup that precedes it.
        sb.append("\n=== context 0x0c048930 .. 0x0c04898a (what precedes entry) ===\n");
        Address ad = toAddr(0x0C048930L), end = toAddr(0x0C04898AL);
        while (ad!=null && ad.compareTo(end)<0) {
            Instruction ins = lst.getInstructionAt(ad);
            if (ins==null){ disassemble(ad); ins = lst.getInstructionAt(ad); }
            if (ins==null){ sb.append("  "+ad+"  (data)\n"); ad=ad.add(2); continue; }
            Reference[] rf = ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for (Reference r: rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ad+"  "+ins.toString()+rs+"\n");
            ad = ins.getMaxAddress().add(1);
        }
        // Dump the literal pool words the function reads (0xc048b74..0xc048bb8) as raw u32.
        sb.append("\n=== literal pool 0x0c048b6c .. 0x0c048bbc (u32 BE) ===\n");
        Memory mem = currentProgram.getMemory();
        for (long a = 0x0C048B6CL; a <= 0x0C048BB8L; a += 4) {
            int v = mem.getInt(toAddr(a));
            sb.append(String.format("  0x%08x : 0x%08x\n", a, v & 0xFFFFFFFFL));
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/emu_caller.txt");
        pw.print(sb.toString()); pw.close();
        println("EmuCaller wrote emu_caller.txt ("+sb.length()+" chars)");
    }
}
