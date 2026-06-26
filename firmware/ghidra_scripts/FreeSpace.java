// FreeSpace.java — find runs of >=N zero bytes that have no references into them (candidate
// trampoline/patch landing zones). Also reports runs of 0xFF. Prints RAM addr + length.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.io.PrintWriter;

public class FreeSpace extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Memory mem = currentProgram.getMemory();
        ReferenceManager rm = currentProgram.getReferenceManager();
        AddressSetView init = mem.getLoadedAndInitializedAddressSet();
        int MIN = 0x20;
        for (MemoryBlock blk : mem.getBlocks()) {
            if (!blk.isInitialized()) continue;
            long start = blk.getStart().getOffset();
            long end = blk.getEnd().getOffset();
            sb.append("=== block "+blk.getName()+" 0x"+Long.toHexString(start)+"..0x"+Long.toHexString(end)+" ===\n");
            long runStart=-1; int runLen=0; byte runByte=0;
            for (long a=start; a<=end; a++) {
                int b;
                try { b = mem.getByte(toAddr(a)) & 0xFF; } catch(Exception e){ b=-1; }
                boolean filler = (b==0x00 || b==0xFF);
                if (filler && (runLen==0 || (b==(runByte&0xFF)))) {
                    if (runLen==0){ runStart=a; runByte=(byte)b; }
                    runLen++;
                } else {
                    if (runLen>=MIN) emit(sb, rm, runStart, runLen, runByte);
                    if (filler){ runStart=a; runByte=(byte)b; runLen=1; } else runLen=0;
                }
            }
            if (runLen>=MIN) emit(sb, rm, runStart, runLen, runByte);
        }
        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/freespace.txt");
        pw.print(sb.toString()); pw.close();
        println("FreeSpace wrote freespace.txt ("+sb.length()+" chars)");
    }
    void emit(StringBuilder sb, ReferenceManager rm, long start, int len, byte b) {
        // count references that target inside the run
        int refs=0;
        for (long a=start; a<start+len; a+=2) {
            if (rm.getReferenceCountTo(toAddr(a))>0) refs++;
        }
        sb.append("  run 0x"+Long.toHexString(start)+" len="+len+" byte=0x"+Integer.toHexString(b&0xFF)
                  +" refsInto="+refs+(refs==0?"  <<< FREE":"")+"\n");
    }
}
