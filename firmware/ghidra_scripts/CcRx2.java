// CcRx2.java — decode the braf jump table at 0x0C03CDC8 (word offsets from table base),
// identify the Control-Change (0xB0) arm, decompile the dispatcher function and the CC arm
// target. Also decompile FUN_0c03d6cc (the common "apply received channel message" routine)
// to determine RX vs TX and whether the path is statically reachable (callers of the dispatcher).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class CcRx2 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; ReferenceManager rm; DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // Decode braf table: base=0x0C03CDC8, entries are signed 16-bit, target = base + entry.
        long base=0x0C03CDC8L;
        sb.append("=== braf table @0x"+Long.toHexString(base)+" (target = base + word) ===\n");
        // The braf is at 0x0C03CDBC; braf adds r0 to PC+4 => PC of braf+4 = 0x0C03CDC0.
        // mova loads r0 = table base 0x0C03CDC8; mov.w @(r0,r2) loads the signed word; braf r0
        // => target = (0x0C03CDBC + 4) + sign-extend(word) = 0x0C03CDC0 + word.
        long brafBase=0x0C03CDC0L;
        String[] names={"0x80 NoteOff","0x90 NoteOn","0xA0 PolyAT","0xB0 CtrlChange",
                        "0xC0 ProgChg","0xD0 ChanAT","0xE0 PitchBend","0xF0 sysex/rt",
                        "idx8","idx9","idx10","idx11","idx12"};
        for (int i=0;i<13;i++){
            long ea=base+i*2;
            byte[] b=new byte[2]; currentProgram.getMemory().getBytes(toAddr(ea),b);
            short w=(short)(((b[0]&0xFF)<<8)|(b[1]&0xFF));
            long tgt=brafBase + w;
            sb.append(String.format("  idx %2d (%-14s) word=0x%04x -> 0x%08x\n",
                i, (i<names.length?names[i]:"?"), w&0xFFFF, tgt));
        }

        // The status->index mapping: r2=status; sub 0xF0?? Re-read: actually index likely (status>>4)-8.
        // Disasm 0x0C03CDA6..0x0C03CDC0 already shown; the CC arm (0xB0) is idx 3.
        // Decompile the function containing the dispatcher (force-create prologue).
        Function disp=bindFunc(0x0C03CDBCL);
        if (disp!=null){
            sb.append("\n########## DISPATCHER FUNC "+disp.getName()+" @ "+disp.getEntryPoint()
               +" size="+disp.getBody().getNumAddresses()+" ##########\n");
            sb.append("STATIC CALLERS:\n");
            int n=0;
            for(Reference r:rm.getReferencesTo(disp.getEntryPoint())){
                Function cf=fm.getFunctionContaining(r.getFromAddress());
                sb.append("  "+r.getFromAddress()+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()):"")+"\n"); n++;
            }
            if(n==0) sb.append("  (none)\n");
        }

        // Decompile FUN_0c03d6cc (the shared apply routine) and FUN_0c03d5c8.
        for(long a: new long[]{0x0C03d6ccL, 0x0C03d5c8L}) charFn(a,"shared");

        // Disasm CC arm region (idx3 target) + a window around it for the cc-number read.
        // We compute it above; also dump 0x0C03CE32 (idx3 target from MidiRx braf list) which is
        // the 4th resolved braf target -> 0x0c03ce32 was idx3 in the prior braf print order.
        sb.append("\n=== disasm CC arm @0x0C03CE32 .. 0x0C03CEA0 ===\n");
        disasmWindow(0x0C03CE32L, 0x0C03CEA0L);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/ccrx2.txt");
        pw.print(sb.toString()); pw.close();
        println("CcRx2 wrote ccrx2.txt ("+sb.length()+" chars)");
    }

    Function bindFunc(long a){
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f!=null) return f;
        for(long s=a; s>a-0x800; s-=2){
            try{ byte[] b=new byte[2]; currentProgram.getMemory().getBytes(toAddr(s),b);
                if((b[0]&0xFF)==0x4F&&(b[1]&0xFF)==0x22){
                    if(getInstructionAt(toAddr(s))==null) disassemble(toAddr(s));
                    try{ f=createFunction(toAddr(s),null);}catch(Exception e){}
                    if(f!=null&&f.getBody().contains(toAddr(a))) return f; else f=null;
                }
            }catch(Exception e){}
        }
        return null;
    }

    void charFn(long a,String tag){
        Function f=fm.getFunctionContaining(toAddr(a));
        sb.append("\n\n########## ["+tag+"] 0x"+Long.toHexString(a)+" ");
        if(f==null){ sb.append("(NO FUNC)\n"); return;}
        sb.append(f.getName()+" @ "+f.getEntryPoint()+" ##########\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }

    void disasmWindow(long lo,long hi){
        Listing lst=currentProgram.getListing(); Address ad=toAddr(lo);
        while(ad.getOffset()<hi){
            Instruction ins=lst.getInstructionAt(ad);
            if(ins==null){ disassemble(ad); ins=lst.getInstructionAt(ad);}
            if(ins==null){ sb.append("  "+ad+"  (data)\n"); ad=ad.add(2); continue;}
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ad+"  "+ins.toString()+rs+"\n");
            ad=ins.getMaxAddress().add(1);
        }
    }
}
