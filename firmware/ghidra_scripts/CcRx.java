// CcRx.java — find the RECEIVE-side Control-Change dispatcher.
// Strategy: the realtime handler FUN_0c044c08 (Stop/Start/Continue/Clock) is the RX
// realtime path; its sibling channel-voice handlers (Note/CC/PC) live nearby and are
// reached from the same RX status dispatcher. Decompile the function(s) that contain
// the dispatcher 0x0C03CD60, plus characterize the FUN_0c03d374 family (are they RX or TX?),
// and dump the RX status dispatcher region as instructions so we can read the Bn arm.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class CcRx extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm; ReferenceManager rm; DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        rm = currentProgram.getReferenceManager();
        dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon = new ConsoleTaskMonitor();

        // Disasm the RX status dispatcher region 0x0C03CD40..0x0C03CE00 (the switch + table-base load).
        sb.append("=== disasm 0x0C03CD40 .. 0x0C03CE00 (RX status dispatcher) ===\n");
        disasmWindow(0x0C03CD40L, 0x0C03CDC8L);

        // The TX family is FUN_0c03d374/428/500/92a per prior notes. Decompile one to confirm
        // RX vs TX, and find who calls THEM (the per-status dispatcher).
        for (long t : new long[]{0x0C03d374L, 0x0C03d428L, 0x0C03d500L}) {
            charFn(t);
        }

        // Find the dispatcher function containing 0x0C03CD60 by forcing creation.
        Address d = toAddr(0x0C03CD60L);
        Function disp = fm.getFunctionContaining(d);
        if (disp==null) {
            // climb back to a prologue
            for (long s=0x0C03CD60L; s>0x0C03C800L; s-=2) {
                try { byte[] b=new byte[2]; currentProgram.getMemory().getBytes(toAddr(s),b);
                    if ((b[0]&0xFF)==0x4F && (b[1]&0xFF)==0x22) { // sts.l pr,@-r15 prologue
                        sb.append("\n[prologue candidate for dispatcher @0x"+Long.toHexString(s)+"]\n");
                        if (getInstructionAt(toAddr(s))==null) disassemble(toAddr(s));
                        try { disp = createFunction(toAddr(s), null); } catch(Exception e){}
                        if (disp!=null && disp.getBody().contains(d)) break; else disp=null;
                    }
                } catch(Exception e){}
            }
        }
        if (disp!=null) {
            sb.append("\n\n########## DISPATCHER "+disp.getName()+" @ "+disp.getEntryPoint()
                +" size="+disp.getBody().getNumAddresses()+" ##########\n");
            sb.append("STATIC CALLERS:\n");
            for (Reference r : rm.getReferencesTo(disp.getEntryPoint())) {
                Function cf=fm.getFunctionContaining(r.getFromAddress());
                sb.append("  "+r.getFromAddress()+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()):"")+"\n");
            }
            DecompileResults r=dec.decompileFunction(disp,120,mon);
            if (r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        } else sb.append("\n[could not bind dispatcher function for 0x0C03CD60]\n");

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/ccrx.txt");
        pw.print(sb.toString()); pw.close();
        println("CcRx wrote ccrx.txt ("+sb.length()+" chars)");
    }

    void disasmWindow(long lo, long hi){
        Listing lst=currentProgram.getListing();
        Address ad=toAddr(lo);
        while(ad.getOffset()<hi){
            Instruction ins=lst.getInstructionAt(ad);
            if(ins==null){ disassemble(ad); ins=lst.getInstructionAt(ad); }
            if(ins==null){ sb.append("  "+ad+"  (data)\n"); ad=ad.add(2); continue; }
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ad+"  "+ins.toString()+rs+"\n");
            ad=ins.getMaxAddress().add(1);
        }
    }

    void charFn(long a){
        Function f=fm.getFunctionContaining(toAddr(a));
        sb.append("\n\n########## 0x"+Long.toHexString(a)+" ");
        if(f==null){ sb.append("(NO FUNC)\n"); return; }
        sb.append(f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##########\n");
        sb.append("STATIC CALLERS:\n"); int n=0;
        for(Reference r:rm.getReferencesTo(f.getEntryPoint())){
            Function cf=fm.getFunctionContaining(r.getFromAddress());
            sb.append("  "+r.getFromAddress()+" "+r.getReferenceType()+(cf!=null?(" in "+cf.getName()):"")+"\n"); n++;
        }
        if(n==0) sb.append("  (none)\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
}
