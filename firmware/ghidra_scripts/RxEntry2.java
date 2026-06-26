// RxEntry2.java — confirm the RX dispatcher / realtime path is boot-RAM-gated.
// (1) Disasm around 0x0C05A560..0x0C05A590 to see the call into FUN_0c05ad18 and what
//     decides to call it (is the caller itself reached by jsr @rN through a RAM pointer?).
// (2) Search the whole image for any literal == 0x0C05A578 / dispatcher entry / FUN_0c05ad18
//     (would indicate a static binding); report counts.
// (3) Find the prologue just below the dispatcher region and report whether its in-refs are
//     all DATA (RAM table) vs CALL.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class RxEntry2 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    public void run() throws Exception {
        FunctionManager fm=currentProgram.getFunctionManager();
        ReferenceManager rm=currentProgram.getReferenceManager();
        Listing lst=currentProgram.getListing();

        sb.append("=== disasm 0x0C05A540 .. 0x0C05A5A0 (call into FUN_0c05ad18) ===\n");
        Address ad=toAddr(0x0C05A540L);
        while(ad.getOffset()<0x0C05A5A0L){
            Instruction ins=lst.getInstructionAt(ad);
            if(ins==null){ disassemble(ad); ins=lst.getInstructionAt(ad);}
            if(ins==null){ sb.append("  "+ad+"  (data)\n"); ad=ad.add(2); continue;}
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ad+"  "+ins+rs+"\n"); ad=ins.getMaxAddress().add(1);
        }

        // who contains 0x0C05A578?
        Function f=fm.getFunctionContaining(toAddr(0x0C05A578L));
        sb.append("\nfunc containing 0x0C05A578: "+(f!=null?f.getName()+"@"+f.getEntryPoint():"NONE")+"\n");

        // Scan image for literals equal to candidate entry addresses.
        long[] cands={0x0C05A578L,0x0C05ad18L,0x0C044c08L};
        byte[] img=new byte[0x140000];
        currentProgram.getMemory().getBytes(toAddr(0x0C000000L),img);
        for(long c:cands){
            int cnt=0; StringBuilder where=new StringBuilder();
            for(int i=0;i+4<=img.length;i+=2){
                long v=((img[i]&0xFFL)<<24)|((img[i+1]&0xFFL)<<16)|((img[i+2]&0xFFL)<<8)|(img[i+3]&0xFFL);
                if(v==c){ cnt++; if(cnt<=6) where.append(String.format("0x%08x ",0x0C000000L+i)); }
            }
            sb.append("literal 0x"+Long.toHexString(c)+" occurs "+cnt+" times: "+where+"\n");
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/rxentry2.txt");
        pw.print(sb.toString()); pw.close();
        println("RxEntry2 wrote rxentry2.txt ("+sb.length()+" chars)");
    }
}
